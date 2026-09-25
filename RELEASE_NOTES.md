v1.9.2 — Voice input for other keyboards

- Keyboards such as HeliBoard now show a mic key that opens Voice Keyboard; on Android 10 and newer they could not see it at all
- New setting "Return to previous keyboard": when another keyboard's mic key opens Voice Keyboard, that keyboard comes back as soon as the text is typed, and the key in the top corner takes you back to it too. Off by default. Contributed by @freeman-jus
- Switching keyboards in the middle of a recording no longer throws the recording away; its text goes to the clipboard
- The navigation bar's hide button now closes the keyboard (Android 13 and newer, gesture navigation)
- A transcription that hits the provider's rate limit now waits and resends by itself instead of asking you to resend it
- Post-processing works with Claude models that do not accept a temperature setting
- Text an app refuses to accept is copied to the clipboard instead of being lost
- The update window shows each release's notes once, without repeating older ones
- If HeliBoard shows no mic key right after updating, restart it (or the phone)
