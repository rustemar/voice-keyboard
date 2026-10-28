v1.11.0 — Optional correction tools and safer dictation

- Keep the main setup focused on provider, API key and dictation languages. Connection details, keyboard preferences, diagnostics and updates expand when needed. Changing the interface language preserves dictation languages and custom prompts.
- Add an optional beta keyboard for quick corrections, with 14 basic layouts, including French AZERTY, German QWERTZ, Russian, Hindi and Arabic. Includes accents, Shift and numbers/symbols. The chosen layout is independent of interface and dictation languages.
- Add optional beta actions on selected text: proofread, translate or supply a one-time instruction using your existing post-processing provider. Review the result before applying it, and undo while the replaced passage remains selected and unchanged. Editor, selection and text checks prevent a delayed response from replacing another passage.
- Keep dictations that finish after leaving their original editor in private Saved texts. Insert, explicitly copy or delete individual results; background dictations no longer replace the system clipboard. Unapplied text-action previews can be kept there too.
- Preserve recordings when saving a completed dictation fails. Offer the keyboard picker when manual return has no usable history, restore accessible Backspace clicks, stop held-key repeats when input closes, and preserve auto-recording after theme changes.
- Localize the new interfaces and accessibility labels in all 17 interface languages. Use icons for mode switching and keep typed layout selection separate from interface language. Diagnostic success logs record text lengths rather than text snippets.

Both beta tools are off by default under Advanced settings → Experimental features. The correction keyboard provides basic direct typing, without predictions, swipe typing or Chinese, Japanese and Korean input conversion. Selected-text actions require a configured post-processing provider and network access; text is sent only after choosing an action, and copied to the clipboard only on explicit Copy.

Thanks to [@flyingpafnouti](https://github.com/rustemar/voice-keyboard/issues/9) for the prototype screenshots and ideas behind quick corrections and selected-text actions.
