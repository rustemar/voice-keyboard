package com.tyraen.voicekeyboard.feature.setup

import android.os.Bundle
import android.view.View
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.tyraen.voicekeyboard.R

/** Keeps uncommon options discoverable without making first-time setup a long form. */
class SettingsSections(private val activity: AppCompatActivity, savedState: Bundle?) {
    private data class Section(val button: Button, val content: View)
    private val sections = mutableMapOf<Int, Section>()

    init {
        bind(R.id.btnRecognitionDetails, R.id.recognitionDetails, savedState)
        bind(R.id.btnKeyboardOptions, R.id.keyboardOptions, savedState)
        bind(R.id.btnDiagnostics, R.id.diagnosticsOptions, savedState)
        bind(R.id.btnAboutOptions, R.id.aboutOptions, savedState)
    }

    private fun bind(buttonId: Int, contentId: Int, savedState: Bundle?) {
        val section = Section(activity.findViewById(buttonId), activity.findViewById(contentId))
        sections[buttonId] = section
        setExpanded(buttonId, savedState?.getBoolean("settings_section_$buttonId", false) == true)
        section.button.setOnClickListener { setExpanded(buttonId, !section.content.isVisible) }
    }

    fun setExpanded(buttonId: Int, expanded: Boolean) {
        val section = sections[buttonId] ?: return
        section.content.isVisible = expanded
        section.button.setCompoundDrawablesRelativeWithIntrinsicBounds(
            0, 0, if (expanded) R.drawable.ic_section_collapse else R.drawable.ic_section_expand, 0
        )
        section.button.contentDescription = activity.getString(
            if (expanded) R.string.settings_section_collapse else R.string.settings_section_expand,
            section.button.text
        )
    }

    fun saveState(outState: Bundle) {
        sections.forEach { (id, section) -> outState.putBoolean("settings_section_$id", section.content.isVisible) }
    }
}
