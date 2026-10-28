# Privacy Policy

**Voice Keyboard** is an Android keyboard (input method) that converts speech to text using third‑party transcription APIs. This document describes what data the app handles, where it goes, and what stays on your device.

_Last updated: 2026‑10‑28_

## TL;DR

- The app does **not** collect, sell, or transmit any analytics, telemetry, advertising IDs, or device identifiers.
- Audio you record is sent **only** to the speech‑to‑text provider you configure (Groq, OpenAI, Mistral or any OpenAI‑compatible endpoint, including a server of your own), using **your own API key**.
- Optional post‑processing sends the transcribed text to the language‑model provider you configure (OpenAI, Anthropic, or an OpenAI‑compatible one such as OpenRouter, Groq, Mistral or DeepSeek), again using **your own API key**. Recent dictations are included as context only if you enable that separate option, which is off by default.
- Optional **selected-text actions** send the passage you explicitly select, together with the chosen editing instruction, to your configured post-processing provider. This beta is off by default; requests start only after you choose an action, and results require review before replacement.
- All settings, API keys, saved dictations, diagnostic logs, and crash reports stay on your device. They are explicitly excluded from Google Drive backup and device‑transfer.
- The app is open source: <https://github.com/rustemar/voice-keyboard>.

## Data the app handles

### Audio

For keyboard dictation, when you press the microphone button, the app records audio from the device microphone and sends it to the transcription endpoint you configured in settings (by default, Groq Whisper). In the keyboard, password fields disable the microphone and auto-record. The audio is sent together with your API key, over HTTPS unless you type a plain `http://` address yourself (see below). Recordings live briefly in the app's private cache directory and are deleted after the text is inserted or safely saved for later insertion. A recording that could **not** be transcribed or whose result could not be saved is kept in the app's private storage. If the cause was no internet, it is resent automatically once the connection returns and deleted after successful delivery; if the provider rejected it (wrong key, provider error), it waits until you tap the resend key. Either way you can delete every unsent recording from the keyboard by holding the resend key twice. Nothing leaves the device meanwhile.

The app does **not** receive a copy of the audio after sending — handling and retention of submitted audio is governed by the policy of the provider you choose:

- Groq: <https://groq.com/privacy-policy/>
- OpenAI: <https://openai.com/policies/privacy-policy>
- Mistral: <https://mistral.ai/terms/#privacy-policy>

### Transcribed text

For keyboard dictation, the text returned by the transcription provider is inserted into the app you are typing in (as if you typed it yourself). If you enable a post‑processing mode (Fix, Shorten, Emoji, Rhyme, Translate, Terminal), the transcribed text is additionally sent to the post‑processing provider you selected using your own API key, and the cleaned‑up text replaces the original.

- Anthropic: <https://www.anthropic.com/legal/privacy>
- OpenRouter: <https://openrouter.ai/privacy>
- DeepSeek: <https://platform.deepseek.com/downloads/DeepSeek%20Privacy%20Policy.html>

If post‑processing is disabled (the default for most modes), no third‑party request is made for text — the Whisper response is inserted directly.

### Saved dictations

If the original editor is no longer available when a keyboard dictation finishes, the completed text is saved in the app's private storage. Each result remains available through **Saved texts** on the keyboard until you insert it or explicitly delete it. There is no automatic expiry. These saved results survive app restarts, are excluded from cloud backup and device transfer, and are deleted if you uninstall the app. They are not automatically copied to the system clipboard; **Copy** does that only when you press it and leaves the saved result available. Other apps may read text you explicitly copy according to Android's clipboard rules.

### Optional correction tools

The basic letter layout types directly into the focused editor. It does not send typed keys to a server or collect a typing history. Its selected layout is stored privately on the device and is independent of interface and dictation languages.

Selected-text actions read the selected passage and a small amount of adjacent text to verify the destination before replacement. Only the selected passage and your editing instruction are sent to the post-processing provider; neighbouring text stays in memory on the device. These actions do not add the passage to recent-dictation context. Password fields are excluded. A preview is shown before applying a replacement; the app checks the editor session, selection and text again before Apply or Undo. An unapplied completed preview is kept in Saved texts when closed, using the same private storage and deletion controls described above. Explicit Copy writes to the system clipboard; explicit Paste reads it for a custom instruction. Neither happens automatically.

### Recent dictation context

**Use recent dictations as context** is optional and **off by default**. When enabled, the app keeps up to 5 of your own completed dictations, excluding Terminal mode, in RAM. Each is limited to 1,000 UTF-16 code units. This context buffer is never written to disk or restored after the app process restarts.

The app sends this additional text to your chosen post-processing provider with subsequent post-processing requests. It does not read conversations or other text from the receiving app to build this history. Context is scoped to the app, so switching between chats in the same app can reuse earlier dictations.

The buffer is cleared when you switch apps, enter a password field or a field that requests no personalized learning, turn off context or post-processing, save a different post-processing provider or endpoint, or restart the app process. It also expires after 30 minutes of inactivity. The provider's own policy governs text already sent to it.

### Voice input for other apps

This separate integration is **off by default**. If you enable it in Advanced settings, compatible apps can request speech recognition using Android’s speech-recognition service or a visible dictation window. You choose the default recognition service in Android settings; some apps select a service themselves. The app sends each recording to your saved speech provider using your API key and returns the transcript to the requesting app. The device needs network access to your configured speech provider, including a local server if you use one.

These requests do not use keyboard post-processing, custom vocabulary, recent dictations, clipboard delivery, or the keyboard’s saved-recording queue. Temporary audio is deleted on success, failure, cancellation or session teardown; it is not retained for later retry. A pause ends recording automatically, and capture and network operations have time limits. Cancelling stops local processing; audio already received by your provider remains subject to its policy.

The service uses Android’s caller permissions and attribution checks. It receives a recognition request, not the requesting app’s focused field or chat history, so the keyboard’s password-field detection does not apply to this separate interface. On modern Android, microphone access is attributed to the requesting app through the platform API. Apps that use their own fixed recognition engine are outside this integration.

The app declares `QUERY_ALL_PACKAGES`, which allows package visibility on Android 11 and newer. Android’s speech-service permission checks need this visibility to verify microphone grants for arbitrary requesting apps; otherwise, a hidden caller can be rejected despite having microphone permission. Voice Keyboard does not enumerate, store or transmit a list of installed apps. This permission does not enable the integration or grant microphone access: the integration remains off by default, and Android’s microphone permission and attribution checks still apply.

### API keys

Your API keys are stored on your device using Android's `DataStore`, in the app's private storage. They are:

- never logged,
- never sent to any server other than the corresponding provider's own API,
- explicitly excluded from Android cloud backup and device‑transfer (see `AndroidManifest.xml` and `res/xml/data_extraction_rules.xml`).

If you uninstall the app, the keys are deleted with it.

### Settings

Your preferences (selected language, theme, post‑processing mode, prompt text, endpoint URLs, etc.) are stored locally in `DataStore`; advanced opt-ins use private `SharedPreferences`. Both are excluded from cloud backup and device transfer.

### Diagnostic log

The app keeps a small ring‑buffered log file (`app_log.txt`, capped at 500 lines) in its private storage to help you debug issues. The log records technical events such as "recording started", "transcription succeeded", HTTP error codes, and similar. It does **not** record API keys. Post-processing logs record result length or error type, without selected passages, editing results or provider error bodies that could echo the text.

The log can be saved to a file, shared through any app you choose, or cleared from the **Logs** section of the settings screen. It is never uploaded on its own.

### Crash reports

If the app crashes, an uncaught‑exception handler writes a single crash report (timestamp, thread, device model, Android version, full stack trace) to the app's private storage. On next launch you are shown a dialog offering to **share** or **save** that report so you can pass it to the developer for debugging. There is no automatic upload — the report only leaves your device if you explicitly share it.

The pending crash file is deleted whether you choose to save it or not.

## Permissions and why we need them

| Permission | Purpose |
|---|---|
| `RECORD_AUDIO` | To capture speech for keyboard dictation, a settings test, or an explicitly enabled Android speech-recognition request. Recording ends when you finish or cancel; recognition sessions also stop after a pause or a bounded timeout. |
| `QUERY_ALL_PACKAGES` | To let Android’s speech-service permission checks verify microphone grants for any requesting app. The app does not enumerate, store or transmit your installed-app list. |
| `INTERNET` | To send recorded audio to your transcription provider, and transcribed or explicitly selected text to your post-processing provider when you choose those features. |
| `REQUEST_INSTALL_PACKAGES` | To let you install a newer version directly from inside the app. The app looks for one only if you switch the GitHub update check on (it is off by default) or press “Check for updates” yourself; it then offers to download and install a newer APK signed with the same key. You can also obtain updates entirely outside the app (e.g. via Obtainium, F‑Droid, or by downloading the APK from GitHub manually). |

The app does **not** request access to contacts, SMS, location, photos, files outside its own sandbox, or any system identifier.

## What we do **not** do

- No analytics SDK (no Crashlytics, Firebase Analytics, Sentry, Amplitude, Mixpanel, etc.).
- No advertising. The app shows no ads and contains no ad SDK.
- No tracking. We have no servers and no way to identify you.
- No account. The app has no sign‑up, no login, no user account.
- No telemetry. The app does not "phone home" on launch or in the background.

The only outbound network requests the app ever makes are:

1. To the transcription endpoint you configured (default: `api.groq.com`).
2. To the post‑processing endpoint you configured, if you use post‑processing (default: `api.anthropic.com`).
   Provider presets in the settings only fill in an address; no request is made until you record or press Apply.
3. To the GitHub Releases API to check whether a newer version of the app exists — on its own **only if you turned the update check on**, which is off by default, or when you press “Check for updates” — and, if you accept the update, to GitHub's download URL for the APK.

Every built‑in address uses HTTPS. If you type a plain `http://` address for either endpoint, for example a server on your home network, everything sent to it (audio, text and your API key) travels unencrypted; the settings screens show a warning under the address when that is the case.

You can verify all of the above by reading the source.

## Updates

The update check is **off by default**. The app asks once — on a launch after you have acknowledged the microphone notice — whether it may check GitHub for new versions, explaining that updates obtained this way come straight from the developer and are not reviewed by the app store you installed from; declining is remembered and the switch stays in settings under "Check GitHub for updates".

While it is off, the app never contacts GitHub on its own. While it is on, it polls `https://api.github.com/repos/rustemar/voice-keyboard/releases` when you open the setup screen. The "Check for updates" button works either way, because pressing it is itself a deliberate request. If a newer release exists you are shown the changelog, told where the file comes from, and offered the choice to download the APK.

GitHub's privacy policy applies to those requests: <https://docs.github.com/en/site-policy/privacy-policies/github-general-privacy-statement>.

## Children

The app is not directed at children under 13 and does not knowingly handle any data about them. As above, the app does not collect personal data from anyone.

## Changes to this policy

If this policy changes in a way that affects what data leaves your device, the change will be reflected in this file (`PRIVACY.md`) in the repository, and noted in the release that introduces the change.

## Contact

Questions, concerns, or false‑positive reports: open an issue at <https://github.com/rustemar/voice-keyboard/issues> or email <hukutu4.eth@gmail.com>.
