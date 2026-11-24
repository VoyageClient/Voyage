/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.timelineexport

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.airbnb.epoxy.EpoxyModel
import com.airbnb.epoxy.EpoxyViewHolder
import com.airbnb.mvrx.args
import dagger.hilt.android.AndroidEntryPoint
import im.vector.app.core.epoxy.LoadingItem
import im.vector.app.core.epoxy.LoadingItem_
import im.vector.app.core.epoxy.NoResultItem_
import im.vector.app.core.platform.StateView
import im.vector.app.core.platform.VectorBaseFragment
import im.vector.app.databinding.FragmentSearchBinding
import im.vector.app.features.home.room.detail.search.SearchArgs
import im.vector.app.features.home.room.detail.timeline.TimelineEventController
import im.vector.app.features.home.room.detail.timeline.action.MessageActionsBottomSheet
import im.vector.app.features.home.room.detail.timeline.action.MessageSharedActionViewModel
import im.vector.app.features.home.room.detail.timeline.item.AbsMessageItem
import im.vector.app.features.home.room.detail.timeline.item.MessageInformationData
import im.vector.app.features.home.room.detail.timeline.item.MessageTextItem
import im.vector.app.features.roommemberprofile.RoomMemberProfileActivity
import im.vector.app.features.roommemberprofile.RoomMemberProfileArgs
import im.vector.lib.strings.CommonStrings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.matrix.android.sdk.api.session.Session
import timber.log.Timber
import javax.inject.Inject

@AndroidEntryPoint
class TimelineArchiveSearchFragment : VectorBaseFragment<FragmentSearchBinding>() {
    @Inject lateinit var archives: TimelineArchiveStore
    @Inject lateinit var renderer: TimelineExportRenderer
    @Inject lateinit var session: Session
    private val searchArgs: SearchArgs by args()
    private var query = ""
    private var job: Job? = null
    private var media: Job? = null
    private var document: TimelineExportRenderer.Document? = null
    private var limit = PAGE
    private var hasMore = false
    private var shownRows = emptyList<Int>()
    private val edge = EdgeAdapter()

    private val messageActions by lazy {
        TimelineArchiveActions(requireActivity(), childFragmentManager, { document?.snapshot },
                { archives.directory(session.myUserId, requireNotNull(searchArgs.archiveId)) }, ::profile,
                { open(it) }, { open(it.id, "save", it.attachment) })
    }

    override fun getBinding(inflater: LayoutInflater, container: ViewGroup?) = FragmentSearchBinding.inflate(inflater, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        query = savedInstanceState?.getString("query").orEmpty()
        views.stateView.contentView = views.searchResultRecycler
        views.stateView.state = StateView.State.Empty()
        // Newest at the bottom, paging older results in at the top, as the room search does.
        views.searchResultRecycler.layoutManager = LinearLayoutManager(requireContext()).apply { stackFromEnd = true }
        views.searchResultRecycler.itemAnimator = null
        views.searchResultRecycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) { loadMedia() }
        })
        activityViewModelProvider.get(MessageSharedActionViewModel::class.java).stream().onEach(messageActions::handle).launchIn(viewLifecycleOwner.lifecycleScope)
        if (query.isNotBlank()) renderResults()
    }

    fun search(value: String) { query = value; limit = PAGE; renderResults() }

    private fun loadMore() {
        if (!hasMore || job?.isActive == true) return
        limit += PAGE
        renderResults(paging = true)
    }

    private fun renderResults(paging: Boolean = false) {
        job?.cancel()
        val manager = views.searchResultRecycler.layoutManager as LinearLayoutManager
        // Paging adds rows above, so keep the first visible result where it is.
        val anchor = if (!paging) null else document?.let { doc ->
            val position = manager.findFirstVisibleItemPosition()
            shownRows.getOrNull(position - edge.itemCount)?.let { index -> doc.rows[index].events.firstOrNull()?.id }
                    ?.let { it to (manager.findViewByPosition(position)?.top ?: 0) }
        }
        job = viewLifecycleOwner.lifecycleScope.launch {
            var next: TimelineExportRenderer.Document? = null
            try {
                if (!paging) views.stateView.state = StateView.State.Loading
                val id = requireNotNull(searchArgs.archiveId)
                val source = withContext(Dispatchers.IO) { archives.read(session.myUserId, id) }
                val hits = withContext(Dispatchers.Default) {
                    val filter = session.searchService().parseQuery(query)
                    source.events.asSequence().filter { filter.matches(it.timelineEvent().root) }.sortedByDescending { it.timestamp }.toList()
                }
                hasMore = hits.size > limit
                val shown = hits.take(limit).asReversed()
                val ids = shown.map { it.id }.toSet()
                val replies = shown.mapNotNull { it.replyTo }.toSet()
                next = renderer.layout(requireContext(), source.copy(events = shown + source.events.filter { it.id in replies && it.id !in ids },
                        options = source.options.copy(roomHeader = false, dates = false, receipts = false, expandAll = true,
                                layoutWidth = (resources.displayMetrics.widthPixels / resources.displayMetrics.density).toInt())), archives.directory(session.myUserId, id))
                val rendered = next
                rendered.clearBoundRows()
                rendered.models.filterIsInstance<AbsMessageItem<*>>().forEach { model ->
                    val event = rendered.eventsById[model.attributes.informationData.eventId] ?: return@forEach
                    if (model is MessageTextItem) {
                        model.urlClickCallback = messageActions
                        model.movementMethod = im.vector.app.features.home.room.detail.timeline.tools.createLinkMovementMethod(messageActions)
                    }
                    model.attributes = model.attributes.copy(avatarCallback = object : TimelineEventController.AvatarCallback {
                        override fun onAvatarClicked(informationData: MessageInformationData) { profile(event.person.id) }
                        override fun onMemberNameClicked(informationData: MessageInformationData) { profile(event.person.id) }
                    }, itemClickListener = { open(event.id) }, memberClickListener = { profile(event.person.id) },
                            itemLongClickListener = View.OnLongClickListener {
                                MessageActionsBottomSheet.newInstance(searchArgs.roomId, model.attributes.informationData, false, archiveId = id)
                                        .show(parentFragmentManager, "MESSAGE_CONTEXTUAL_ACTIONS")
                                true
                            })
                    model.inReplyToClickCallback = object : TimelineEventController.InReplyToClickCallback {
                        override fun onRepliedToEventClicked(sourceEventId: String?, targetEventId: String) { open(targetEventId) }
                    }
                }
                media?.cancel()
                views.searchResultRecycler.adapter = null
                document?.release()
                document = rendered
                shownRows = rendered.rows.indices.filter { index -> rendered.rows[index].events.any { it.id in ids } }
                edge.model = when {
                    hasMore -> LoadingItem_().id("loadMore")
                    shown.isNotEmpty() -> NoResultItem_().id("noMoreResult").text(getString(CommonStrings.no_more_results))
                    else -> null
                }
                views.searchResultRecycler.adapter = ConcatAdapter(edge, TimelineArchiveAdapter(rendered, shownRows))
                anchor?.let { (eventId, top) ->
                    val row = shownRows.indexOfFirst { index -> rendered.rows[index].events.any { it.id == eventId } }
                    if (row >= 0) manager.scrollToPositionWithOffset(row + edge.itemCount, top)
                }
                next = null
                views.stateView.state = if (shown.isEmpty()) StateView.State.Empty(message = getString(CommonStrings.no_result_placeholder)) else StateView.State.Content
                views.searchResultRecycler.post { loadMedia() }
                Timber.d("ExportTrace archive native search hits=%d displayed=%d", hits.size, shown.size)
            } catch (cancelled: CancellationException) { throw cancelled } catch (failure: Exception) {
                Timber.e(failure, "ExportTrace archive native search failed")
                views.stateView.state = StateView.State.Error(getString(CommonStrings.archive_open_failed))
            } finally { next?.release() }
        }
    }

    private fun loadMedia() {
        val doc = document ?: return
        media?.cancel()
        media = viewLifecycleOwner.lifecycleScope.launch { renderer.prepareAssets(doc, waitForMedia = false, bindRows = false) }
    }

    // The archive is open beneath the search, so this returns to it instead of opening it again.
    private fun open(event: String, action: String? = null, attachment: String? = null) {
        startActivity(Intent(requireContext(), TimelineArchiveActivity::class.java).putExtra("archive", searchArgs.archiveId).putExtra("event", event)
                .putExtra("action", action).putExtra("attachment", attachment).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }

    /** The room search's own loading and end-of-results rows, above the results. */
    private inner class EdgeAdapter : RecyclerView.Adapter<EdgeAdapter.Holder>() {
        inner class Holder(val epoxy: EpoxyViewHolder) : RecyclerView.ViewHolder(epoxy.itemView)
        var model: EpoxyModel<*>? = null
            @SuppressLint("NotifyDataSetChanged") set(value) { field = value; notifyDataSetChanged() }
        override fun getItemCount() = if (model == null) 0 else 1
        override fun getItemViewType(position: Int) = model!!.layout
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(EpoxyViewHolder(parent, model!!.buildView(parent), false))
        override fun onBindViewHolder(holder: Holder, position: Int) {
            val bound = model!!
            holder.epoxy.bind(bound, null, emptyList(), position)
            // Bound when it scrolls into view, which is when the room search asks for the next page.
            if (bound is LoadingItem) views.searchResultRecycler.post { loadMore() }
        }
        override fun onViewRecycled(holder: Holder) = holder.epoxy.unbind()
    }

    private fun profile(user: String) {
        startActivity(RoomMemberProfileActivity.newIntent(requireContext(),
                RoomMemberProfileArgs(user, searchArgs.roomId, searchArgs.archiveId)))
    }

    private companion object {
        const val PAGE = 30
    }

    override fun onSaveInstanceState(outState: Bundle) { outState.putString("query", query); super.onSaveInstanceState(outState) }
    override fun onDestroyView() { job?.cancel(); media?.cancel(); views.searchResultRecycler.adapter = null; document?.release(); document = null; super.onDestroyView() }
}
