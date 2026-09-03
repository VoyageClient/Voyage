/*
 * Copyright 2022-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package org.billcarsonfr.jsonviewer

import android.content.Context
import android.view.View
import com.airbnb.epoxy.TypedEpoxyController
import com.airbnb.mvrx.Fail
import im.vector.lib.core.utils.epoxy.charsequence.toEpoxyCharSequence
import im.vector.lib.core.utils.text.neutralizeDirectionOverrides
import me.gujun.android.span.Span
import me.gujun.android.span.span
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

// Set at app start so keys/values render emoji like the app's other text surfaces; this vendored
// module can't depend on the app's EmojiSpanify.
@Volatile
var jsonViewerEmojiSpanify: ((CharSequence) -> CharSequence)? = null

private fun CharSequence.toDisplayEpoxyCharSequence() =
        (jsonViewerEmojiSpanify?.invoke(this) ?: this).toEpoxyCharSequence()

internal class JSonViewerEpoxyController(private val context: Context) :
        TypedEpoxyController<JSonViewerState>() {

    private var styleProvider: JSonViewerStyleProvider = JSonViewerStyleProvider.default(context)

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
        val id = "$idBase/${model.key ?: model.index}_${model.isExpanded}}"
        val children = when (model) {
            is JSonViewerObject -> model.keys.values.toList()
            is JSonViewerArray -> model.items.toList()
            else -> return false
        }
        if (children.none { it.subtreeMatches(query) }) return false
        val isObject = model is JSonViewerObject
        open(id, model.key, model.index, depth, isObject, model)
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
        val id = "$idBase/${model.key ?: model.index}_${model.isExpanded}}"
        when (model) {
            is JSonViewerObject -> {
                if (model.isExpanded) {
                    open(id, model.key, model.index, depth, true, model)
                    model.keys.forEach {
                        buildRec(it.value, depth + 1, id)
                    }
                    close(id, depth, true)
                } else {
                    valueItem {
                        id(id + "_sum")
                        depth(depth)
                        text(
                                span {
                                    if (model.key != null) {
                                        span("\"${model.key?.neutralizeDirectionOverrides()}\"") {
                                            textColor = host.styleProvider.keyColor
                                        }
                                        span(" : ") {
                                            textColor = host.styleProvider.baseColor
                                        }
                                    }
                                    if (model.index != null) {
                                        span("${model.index}") {
                                            textColor = host.styleProvider.secondaryColor
                                        }
                                        span(" : ") {
                                            textColor = host.styleProvider.baseColor
                                        }
                                    }
                                    span {
                                        +"{+${model.keys.size}}"
                                        textColor = host.styleProvider.baseColor
                                    }
                                }.toDisplayEpoxyCharSequence()
                        )
                        copyValue(host.serializedValue(model))
                        itemClickListener(View.OnClickListener { host.itemClicked(model) })
                    }
                }
            }
            is JSonViewerArray -> {
                if (model.isExpanded) {
                    open(id, model.key, model.index, depth, false, model)
                    model.items.forEach {
                        buildRec(it, depth + 1, id)
                    }
                    close(id, depth, false)
                } else {
                    valueItem {
                        id(id + "_sum")
                        depth(depth)
                        text(
                                span {
                                    if (model.key != null) {
                                        span("\"${model.key?.neutralizeDirectionOverrides()}\"") {
                                            textColor = host.styleProvider.keyColor
                                        }
                                        span(" : ") {
                                            textColor = host.styleProvider.baseColor
                                        }
                                    }
                                    if (model.index != null) {
                                        span("${model.index}") {
                                            textColor = host.styleProvider.secondaryColor
                                        }
                                        span(" : ") {
                                            textColor = host.styleProvider.baseColor
                                        }
                                    }
                                    span {
                                        +"[+${model.items.size}]"
                                        textColor = host.styleProvider.baseColor
                                    }
                                }.toDisplayEpoxyCharSequence()
                        )
                        copyValue(host.serializedValue(model))
                        itemClickListener(View.OnClickListener { host.itemClicked(model) })
                    }
                }
            }
            is JSonViewerLeaf -> {
                valueItem {
                    id(id)
                    depth(depth)
                    text(
                            span {
                                if (model.key != null) {
                                    span("\"${model.key}\"") {
                                        textColor = host.styleProvider.keyColor
                                    }
                                    span(" : ") {
                                        textColor = host.styleProvider.baseColor
                                    }
                                }

                                if (model.index != null) {
                                    span("${model.index}") {
                                        textColor = host.styleProvider.secondaryColor
                                    }
                                    span(" : ") {
                                        textColor = host.styleProvider.baseColor
                                    }
                                }
                                append(host.valueToSpan(model))
                            }.toDisplayEpoxyCharSequence()
                    )
                    copyValue(model.stringRes)
                }
            }
        }
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
            key: String?,
            index: Int?,
            depth: Int,
            isObject: Boolean = true,
            composed: JSonViewerModel
    ) {
        val host = this
        valueItem {
            id("${id}_Open")
            depth(depth)
            text(
                    span {
                        if (key != null) {
                            span("\"${key.neutralizeDirectionOverrides()}\"") {
                                textColor = host.styleProvider.keyColor
                            }
                            span(" : ") {
                                textColor = host.styleProvider.baseColor
                            }
                        }
                        if (index != null) {
                            span("$index") {
                                textColor = host.styleProvider.secondaryColor
                            }
                            span(" : ") {
                                textColor = host.styleProvider.baseColor
                            }
                        }
                        span("- ") {
                            textColor = host.styleProvider.secondaryColor
                        }
                        span("{".takeIf { isObject } ?: "[") {
                            textColor = host.styleProvider.baseColor
                        }
                    }.toDisplayEpoxyCharSequence()
            )
            copyValue(host.serializedValue(composed))
            itemClickListener(View.OnClickListener { host.itemClicked(composed) })
        }
    }

    // The node's value serialized as pretty-printed JSON, excluding its key — so long-pressing
    // "content" copies `{ ... }`, and the root copies the whole event.
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
