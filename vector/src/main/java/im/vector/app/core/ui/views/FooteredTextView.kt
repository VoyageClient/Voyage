/*
 * Copyright 2021-2024 SchildiChat and New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.core.ui.views

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView
import im.vector.app.features.home.room.detail.timeline.tools.applySpoilerRenderLayer

class FooteredTextView @JvmOverloads constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0,
) : AppCompatTextView(context, attrs, defStyleAttr), AbstractFooteredTextView {

    override val footerState: AbstractFooteredTextView.FooterState = AbstractFooteredTextView.FooterState()
    override fun getAppCompatTextView(): AppCompatTextView = this
    override fun setMeasuredDimensionExposed(measuredWidth: Int, measuredHeight: Int) = setMeasuredDimension(measuredWidth, measuredHeight)

    override fun onTextChanged(text: CharSequence?, start: Int, lengthBefore: Int, lengthAfter: Int) {
        super.onTextChanged(text, start, lengthBefore, lengthAfter)
        applySpoilerRenderLayer()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val updatedMeasures = updateDimensionsWithFooter(widthMeasureSpec, heightMeasureSpec)
        setMeasuredDimension(updatedMeasures.first, updatedMeasures.second)
    }

    override fun onDraw(canvas: Canvas) {
        updateFooterOnPreDraw(canvas)
        super.onDraw(canvas)
    }
}
