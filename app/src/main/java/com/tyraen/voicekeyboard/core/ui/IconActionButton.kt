package com.tyraen.voicekeyboard.core.ui

import android.content.Context
import android.util.AttributeSet
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import com.google.android.material.button.MaterialButton
import com.tyraen.voicekeyboard.R
import kotlin.math.roundToInt

/** Compact action with a complete spoken label and a discoverable long-press tooltip. */
class IconActionButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : MaterialButton(context, attrs) {
    init {
        text = ""
        iconGravity = ICON_GRAVITY_TEXT_START
        iconPadding = 0
        iconSize = (24 * resources.displayMetrics.density).roundToInt()
        minWidth = 0
        minimumWidth = 0
        minHeight = (48 * resources.displayMetrics.density).roundToInt()
        minimumHeight = minHeight
        val padding = (12 * resources.displayMetrics.density).roundToInt()
        setPadding(padding, padding, padding, padding)
        backgroundTintList = ContextCompat.getColorStateList(context, R.color.key_bg)
        iconTint = ContextCompat.getColorStateList(context, R.color.control_key_text)
        contentDescription?.let(::setActionLabel)
    }

    fun setActionLabel(label: CharSequence) {
        contentDescription = label
        ViewCompat.setTooltipText(this, label)
    }
}
