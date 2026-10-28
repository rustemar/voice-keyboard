package com.tyraen.voicekeyboard.feature.ime

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView

/** Keep newly available controls at logical start without undoing the user's manual scroll. */
class LogicalStartHorizontalScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.horizontalScrollViewStyle
) : HorizontalScrollView(context, attrs, defStyleAttr) {
    private data class ContentGeometry(
        val viewportWidth: Int,
        val contentWidth: Int,
        val direction: Int,
        val visibleIds: List<Int>
    )

    private var previousGeometry: ContentGeometry? = null

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        val content = getChildAt(0) ?: return
        val viewportWidth = (width - paddingLeft - paddingRight).coerceAtLeast(0)
        val margins = content.layoutParams as? ViewGroup.MarginLayoutParams
        val contentWidth = content.width + (margins?.leftMargin ?: 0) + (margins?.rightMargin ?: 0)
        val visibleIds = ArrayList<Int>()
        collectVisibleIds(content, visibleIds)
        val geometry = ContentGeometry(viewportWidth, contentWidth, layoutDirection, visibleIds)
        if (geometry == previousGeometry) return
        previousGeometry = geometry
        // Platform RTL initialization can happen while all opt-in controls are still GONE.
        // Reconcile after the real content is laid out, including asynchronous preferences.
        val start = if (layoutDirection == View.LAYOUT_DIRECTION_RTL) {
            (contentWidth - viewportWidth).coerceAtLeast(0)
        } else 0
        scrollTo(start, 0)
    }

    private fun collectVisibleIds(view: View, result: MutableList<Int>) {
        if (view.visibility != View.VISIBLE) return
        result.add(view.id)
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) collectVisibleIds(view.getChildAt(index), result)
        }
    }
}
