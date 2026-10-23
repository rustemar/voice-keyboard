package com.tyraen.voicekeyboard.feature.ime

/** Compact controls must always leave room for six 48 dp touch targets and their spacing. */
internal object CompactTerminalLayoutPolicy {
    const val MIN_WIDTH_DP = 320

    fun eligible(
        enabled: Boolean,
        postProcessingEnabled: Boolean,
        terminalVisible: Boolean,
        terminalActive: Boolean
    ): Boolean = enabled && postProcessingEnabled && terminalVisible && terminalActive

    fun compact(eligible: Boolean, manuallyExpanded: Boolean, widthDp: Int): Boolean =
        eligible && !manuallyExpanded && widthDp >= MIN_WIDTH_DP
}
