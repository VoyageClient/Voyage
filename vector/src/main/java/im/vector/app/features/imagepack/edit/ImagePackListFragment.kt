/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.imagepack.edit

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.airbnb.mvrx.args
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import im.vector.app.core.extensions.cleanup
import im.vector.app.core.extensions.configureWith
import im.vector.app.core.platform.VectorBaseFragment
import im.vector.app.core.utils.toast
import im.vector.app.databinding.FragmentGenericRecyclerBinding
import im.vector.lib.strings.CommonStrings
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@AndroidEntryPoint
class ImagePackListFragment :
        VectorBaseFragment<FragmentGenericRecyclerBinding>(),
        ImagePackListController.Listener {

    @Inject lateinit var repository: ImagePackRepository
    @Inject lateinit var controller: ImagePackListController
    @Inject lateinit var archiver: ImagePackArchiver

    private val listArgs: ImagePackListArgs by args()

    override fun getBinding(inflater: LayoutInflater, container: ViewGroup?) =
            FragmentGenericRecyclerBinding.inflate(inflater, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        controller.listener = this
        views.genericRecyclerView.configureWith(controller)
        // Live + off-main: reflects saves/toggles without re-entering, and won't ANR on large scans.
        repository.listDataLive(listArgs.roomId)
                .onEach { controller.setData(it) }
                .launchIn(viewLifecycleOwner.lifecycleScope)
    }

    override fun onDestroyView() {
        views.genericRecyclerView.cleanup()
        controller.listener = null
        super.onDestroyView()
    }

    override fun onPackClicked(pack: ManagedPack) {
        startActivity(
                ImagePackEditActivity.newIntent(
                        requireContext(),
                        roomId = pack.roomId,
                        stateKey = pack.stateKey ?: "",
                        canEdit = pack.canEdit,
                        displayName = pack.displayName,
                )
        )
    }

    override fun onGlobalToggled(pack: ManagedPack, enabled: Boolean) {
        val roomId = pack.roomId ?: return
        val stateKey = pack.stateKey ?: return
        lifecycleScope.launch {
            // The live flow refreshes the list once the account-data write lands; no manual refresh needed.
            try {
                repository.setPackEnabledGlobally(roomId, stateKey, enabled)
            } catch (cancellation: kotlinx.coroutines.CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                if (isAdded) showFailure(failure)
            }
        }
    }

    override fun onCreateAccountPack() {
        startActivity(ImagePackEditActivity.newIntent(requireContext(), roomId = null, stateKey = "", canEdit = true))
    }

    override fun onCreateRoomPack() {
        // A room can hold several packs, each under a distinct state_key.
        startActivity(ImagePackEditActivity.newIntent(requireContext(), roomId = listArgs.roomId, stateKey = UUID.randomUUID().toString(), canEdit = true))
    }

    private val importZipLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uris = extractPickedUris(result.data)
            if (uris.isNotEmpty()) importZips(uris)
        }
    }

    override fun onImportPack() {
        val intent = Intent(Intent.ACTION_GET_CONTENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/zip")
                .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/zip", "application/x-zip-compressed"))
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        importZipLauncher.launch(intent)
    }

    private var importJob: Job? = null

    // Zips are processed one at a time, in selection order, so the packs land in that order too.
    private fun importZips(uris: List<Uri>) {
        val roomId = listArgs.roomId ?: return
        if (importJob?.isActive == true) return
        val dialog = MaterialAlertDialogBuilder(requireContext())
                .setTitle(CommonStrings.image_pack_import)
                .setMessage("…")
                .setNegativeButton(CommonStrings.action_cancel) { _, _ -> importJob?.cancel() }
                .setCancelable(false)
                .show()
        importJob = lifecycleScope.launch {
            val imported = mutableListOf<String>()
            var firstFailure: Throwable? = null
            try {
                uris.forEach { uri ->
                    try {
                        archiver.importPack(uri, roomId) { name, done, total ->
                            dialog.setMessage(getString(CommonStrings.image_pack_importing, name, done, total))
                        }?.let { imported += it }
                    } catch (cancellation: kotlinx.coroutines.CancellationException) {
                        throw cancellation
                    } catch (failure: Throwable) {
                        if (firstFailure == null) firstFailure = failure
                    }
                }
            } finally {
                runCatching { dialog.dismiss() }
            }
            if (!isAdded) return@launch
            firstFailure?.let { showFailure(it) }
            if (imported.isNotEmpty()) {
                requireContext().toast(getString(CommonStrings.image_pack_import_done, imported.joinToString(", ")))
            }
        }
    }
}
