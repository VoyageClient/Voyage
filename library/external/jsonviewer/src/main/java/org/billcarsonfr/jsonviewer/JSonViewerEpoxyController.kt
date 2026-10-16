/*
 * Copyright 2022-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package org.billcarsonfr.jsonviewer

import android.content.Context
import android.view.View
import com.airbnb.epoxy.EpoxyAsyncUtil
import com.airbnb.epoxy.TypedEpoxyController
import com.airbnb.mvrx.Fail
import im.vector.lib.core.utils.epoxy.charsequence.EpoxyCharSequence
import im.vector.lib.core.utils.epoxy.charsequence.toEpoxyCharSequence
import im.vector.lib.core.utils.text.neutralizeDirectionOverrides
import me.gujun.android.span.Span
import me.gujun.android.span.span
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date
import java.util.IdentityHashMap

// Set at app start so keys/values render emoji like the app's other text surfaces; this vendored
// module can't depend on the app's EmojiSpanify.
@Volatile
var jsonViewerEmojiSpanify: ((CharSequence) -> CharSequence)? = null

private fun CharSequence.toDisplayEpoxyCharSequence() =
        (jsonViewerEmojiSpanify?.invoke(this) ?: this).toEpoxyCharSequence()

internal class JSonViewerEpoxyController(private val context: Context) :
        TypedEpoxyController<JSonViewerState>(
                EpoxyAsyncUtil.getAsyncBackgroundHandler(),
                EpoxyAsyncUtil.getAsyncBackgroundHandler()
        ) {

    @Volatile
    private var styleProvider: JSonViewerStyleProvider = JSonViewerStyleProvider.default(context)

    // So a toggle doesn't re-span and re-emojify every row. Model-building thread only.
    private val openTexts = IdentityHashMap<JSonViewerModel, EpoxyCharSequence>()
    private val rowTexts = IdentityHashMap<JSonViewerModel, EpoxyCharSequence>()
    private var cachedRoot: JSonViewerObject? = null
    private var cachedStyle: JSonViewerStyleProvider? = null

    @Volatile
    var valueListener: JSonViewerValueListener? = null

    fun setStyle(styleProvider: JSonViewerStyleProvider?) {
        this.styleProvider = styleProvider ?: JSonViewerStyleProvider.default(context)
    }

    override fun buildModels(data: JSonViewerState?) {
        val host = this
        val async = data?.root ?: return

        when (async) {
            is Fail -> {
                valueItem {
                    id("fail")
                    text(async.error.localizedMessage?.toEpoxyCharSequence())
                }
            }
            else -> {
                async.invoke()?.let { root ->
                    if (root !== cachedRoot || styleProvider !== cachedStyle) {
                        openTexts.clear()
                        rowTexts.clear()
                        cachedRoot = root
                        cachedStyle = styleProvider
                    }
                    val query = data.searchQuery.trim()
                    if (query.isEmpty()) {
                        buildRec(root, 0, "")
                    } else if (!buildFiltered(root, 0, "", query)) {
                        valueItem {
                            id("no_search_result")
                            text(host.context.getString(R.string.jv_no_search_result).toEpoxyCharSequence())
                        }
                    }
                }
            }
        }
    }

    /**
     * Renders only the nodes matching [query] and the containers leading to them, which are opened
     * whatever their expanded state. A matching node is rendered as usual, so a matched object can
     * still be folded away by hand. Returns false when nothing under [model] matches.
     */
    private fun buildFiltered(model: JSonViewerModel, depth: Int, idBase: String, query: String): Boolean {
        if (model.matches(query)) {
            buildRec(model, depth, idBase)
            return true
        }
        val id = "$idBase/${model.key ?: model.index}"
        val children = when (model) {
            is JSonViewerObject -> model.keys.values.toList()
            is JSonViewerArray -> model.items.toList()
            else -> return false
        }
        if (children.none { it.subtreeMatches(query) }) return false
        val isObject = model is JSonViewerObject
        open(id, depth, isObject, model)
        children.forEach { buildFiltered(it, depth + 1, id, query) }
        close(id, depth, isObject)
        return true
    }

    private fun JSonViewerModel.matches(query: String): Boolean {
        return key?.contains(query, ignoreCase = true) == true ||
                (this is JSonViewerLeaf && stringRes.contains(query, ignoreCase = true))
    }

    private fun JSonViewerModel.subtreeMatches(query: String): Boolean {
        if (matches(query)) return true
        return when (this) {
            is JSonViewerObject -> keys.values.any { it.subtreeMatches(query) }
            is JSonViewerArray -> items.any { it.subtreeMatches(query) }
            else -> false
        }
    }

    // Keys and string values are neutralized in the rendered spans only; copyValue always carries
    // the raw data, so copying from the viewer round-trips the exact source.
    private fun buildRec(
            model: JSonViewerModel,
            depth: Int,
            idBase: String
    ) {
        val host = this
        val id = "$idBase/${model.key ?: model.index}"
        when (model) {
            is JSonViewerObject, is JSonViewerArray -> {
                val isObject = model is JSonViewerObject
                if (model.isExpanded) {
                    open(id, depth, isObject, model)
                    if (model is JSonViewerObject) {
                        model.keys.values.forEach { buildRec(it, depth + 1, id) }
                    } else if (model is JSonViewerArray) {
                        model.items.forEach { buildRec(it, depth + 1, id) }
                    }
                    close(id, depth, isObject)
                } else {
                    valueItem {
                        id(id + "_sum")
                        depth(depth)
                        text(host.rowTexts.getOrPut(model) { host.summaryText(model) })
                        copyValue { host.serializedValue(model) }
                        itemClickListener(View.OnClickListener { host.itemClicked(model) })
                    }
                }
            }
            is JSonViewerLeaf -> {
                val kind = model.valueKind
                val listener = valueListener
                valueItem {
                    id(id)
                    depth(depth)
                    text(
                            if (model.showHumanTime) {
                                host.humanTimeText(model)
                            } else {
                                host.rowTexts.getOrPut(model) { host.leafText(model) }
                            }
                    )
                    copyValue { model.stringRes }
                    when {
                        kind == JSonViewerValueKind.TIMESTAMP -> {
                            onDoubleTap { host.setHumanTime(model, true) }
                            if (model.showHumanTime) onSingleTap { host.setHumanTime(model, false) }
                        }
                        kind != null && listener != null -> onDoubleTap { listener.onJsonValueDoubleTapped(kind, model.stringRes) }
                    }
                }
            }
        }
    }

    private fun Span.keyPrefix(model: JSonViewerModel, neutralizeKey: Boolean) {
        val key = model.key
        if (key != null) {
            span("\"${if (neutralizeKey) key.neutralizeDirectionOverrides() else key}\"") {
                textColor = styleProvider.keyColor
            }
            span(" : ") {
                textColor = styleProvider.baseColor
            }
        }
        if (model.index != null) {
            span("${model.index}") {
                textColor = styleProvider.secondaryColor
            }
            span(" : ") {
                textColor = styleProvider.baseColor
            }
        }
    }

    private fun summaryText(model: JSonViewerModel): EpoxyCharSequence {
        val host = this
        val summary = when (model) {
            is JSonViewerObject -> "{+${model.keys.size}}"
            is JSonViewerArray -> "[+${model.items.size}]"
            else -> ""
        }
        return span {
            keyPrefix(model, neutralizeKey = true)
            span {
                +summary
                textColor = host.styleProvider.baseColor
            }
        }.toDisplayEpoxyCharSequence()
    }

    private fun leafText(model: JSonViewerLeaf): EpoxyCharSequence {
        val host = this
        return span {
            keyPrefix(model, neutralizeKey = false)
            append(host.valueToSpan(model))
        }.toDisplayEpoxyCharSequence()
    }

    private fun humanTimeText(model: JSonViewerLeaf): EpoxyCharSequence {
        val host = this
        val ms = JSonViewerValueDetector.timestampMs(model.stringRes) ?: return leafText(model)
        val formatted = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM).format(Date(ms))
        return span {
            keyPrefix(model, neutralizeKey = false)
            span(formatted) {
                textColor = host.styleProvider.numberColor
            }
        }.toDisplayEpoxyCharSequence()
    }

    private fun setHumanTime(model: JSonViewerLeaf, show: Boolean) {
        if (model.showHumanTime == show) return
        model.showHumanTime = show
        setData(currentData)
    }

    private fun openText(model: JSonViewerModel, isObject: Boolean): EpoxyCharSequence {
        val host = this
        return span {
            keyPrefix(model, neutralizeKey = true)
            span("- ") {
                textColor = host.styleProvider.secondaryColor
            }
            span("{".takeIf { isObject } ?: "[") {
                textColor = host.styleProvider.baseColor
            }
        }.toDisplayEpoxyCharSequence()
    }

    private fun valueToSpan(leaf: JSonViewerLeaf): Span {
        val host = this
        return when (leaf.type) {
            JSONType.STRING -> {
                span("\"${leaf.stringRes.neutralizeDirectionOverrides()}\"") {
                    textColor = host.styleProvider.stringColor
                }
            }
            JSONType.NUMBER -> {
                span(leaf.stringRes) {
                    textColor = host.styleProvider.numberColor
                }
            }
            JSONType.BOOLEAN -> {
                span(leaf.stringRes) {
                    textColor = host.styleProvider.booleanColor
                }
            }
            JSONType.NULL -> {
                span("null") {
                    textColor = host.styleProvider.booleanColor
                }
            }
        }
    }

    private fun open(
            id: String,
            depth: Int,
            isObject: Boolean,
            composed: JSonViewerModel
    ) {
        val host = this
        valueItem {
            id("${id}_Open")
            depth(depth)
            text(host.openTexts.getOrPut(composed) { host.openText(composed, isObject) })
            copyValue { host.serializedValue(composed) }
            itemClickListener(View.OnClickListener { host.itemClicked(composed) })
        }
    }

    // The node's value serialized as pretty-printed JSON, excluding its key — so long-pressing
    // "content" copies `{ ... }`, and the root copies the whole event. Lazy: eagerly it serializes each
    // subtree once per ancestor on every rebuild, which hangs and OOMs on large documents.
    private fun serializedValue(model: JSonViewerModel): String? = try {
        // org.json escapes every '/' as '\/'; undo it so the copied JSON matches the source exactly.
        when (val o = model.jObject) {
            is JSONObject -> o.toString(4).replace("\\/", "/")
            is JSONArray -> o.toString(4).replace("\\/", "/")
            else -> null
        }
    } catch (failure: JSONException) {
        null
    }

    private fun itemClicked(model: JSonViewerModel) {
        model.isExpanded = !model.isExpanded
        setData(currentData)
    }

    private fun close(id: String, depth: Int, isObject: Boolean = true) {
        val host = this
        valueItem {
            id("${id}_Close")
            depth(depth)
            text(
                    span {
                        text = "}".takeIf { isObject } ?: "]"
                        textColor = host.styleProvider.baseColor
                    }.toEpoxyCharSequence()
            )
        }
    }
}
