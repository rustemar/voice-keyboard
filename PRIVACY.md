# Privacy Policy

**Voice Keyboard** is an Android keyboard (input method) that converts speech to text using third‑party transcription APIs. This document describes what data the app handles, where it goes, and what stays on your device.

_Last updated: 2026‑09‑22_

## TL;DR

- The app does **not** collect, sell, or transmit any analytics, telemetry, advertising IDs, or device identifiers.
- Audio you record is sent **only** to the speech‑to‑text provider you configure (Groq, OpenAI, Mistral or any OpenAI‑compatible endpoint), using **your own API key**.
- Optional post‑processing sends the transcribed text to the language‑model provider you configure (OpenAI, Anthropic, or an OpenAI‑compatible one such as OpenRouter, Groq, Mistral or DeepSeek), again using **your own API key**.
- All settings, API keys, diagnostic logs, and crash reports stay on your device. They are explicitly excluded from Google Drive backup and device‑transfer.
- The app is open source: <https://github.com/rustemar/voice-keyboard>.

## Data the app handles

### Audio

When you press the microphone button, the app records audio from the device microphone and sends it to the transcription endpoint you configured in settings (by default, Groq Whisper). In password fields the microphone is off and auto-record does not start. The audio is sent over HTTPS together with your API key. The app does **not** keep audio after a successful transcription: recordings live briefly in the app's private cache directory and are deleted as soon as the text comes back. A recording that could **not** be transcribed is kept in the app's private storage. If the cause was no internet, it is resent automatically once the connection returns and deleted after a successful send; if the provider rejected it (wrong key, provider error), it waits until you tap the resend key. Either way you can delete every unsent recording from the keyboard by holding the resend key twice. Nothing leaves the device meanwhile.

The app does **not** receive a copy of the audio after sending — handling and retention of submitted audio is governed by the policy of the provider you choose:

- Groq: <https://groq.com/privacy-policy/>
- OpenAI: <https://openai.com/policies/privacy-policy>
- Mistral: <https://mistral.ai/terms/#privacy-policy>

### Transcribed text

The text returned by the transcription provider is inserted into the app you are typing in (as if you typed it yourself). If you enable a post‑processing mode (Fix, Shorten, Emoji, Rhyme, Translate, Terminal), the transcribed text is additionally sent to the post‑processing provider you selected using your own API key, and the cleaned‑up text replaces the original.

- Anthropic: <https://www.anthropic.com/legal/privacy>
- OpenRouter: <https://openrouter.ai/privacy>
- DeepSeek: <https://platform.deepseek.com/downloads/DeepSeek%20Privacy%20Policy.html>

If post‑processing is disabled (the default for most modes), no third‑party request is made for text — the Whisper response is inserted directly.

### API keys

Your API keys are stored on your device using Android's `DataStore`, in the app's private storage. They are:

- never logged,
- never sent to any server other than the corresponding provider's own API,
- explicitly excluded from Android cloud backup and device‑transfer (see `AndroidManifest.xml` and `res/xml/data_extraction_rules.xml`).

If you uninstall the app, the keys are deleted with it.

### Settings

Your preferences (selected language, theme, post‑processing mode, prompt text, endpoint URLs, etc.) are stored locally in the same `DataStore` and are likewise excluded from cloud backup.

### Diagnostic log

The app keeps a small ring‑buffered log file (`app_log.txt`, capped at 500 lines) in its private storage to help you debug issues. The log records technical events such as "recording started", "transcription succeeded", HTTP error codes, and similar. It does **not** record API keys, and only the first 50 characters of any transcribed text are written, solely to verify that the right text came back during local debugging.

The log can be saved to a file, shared through any app you choose, or cleared from the **Logs** section of the settings screen. It is never uploaded on its own.

### Crash reports

If the app crashes, an uncaught‑exception handler writes a single crash report (timestamp, thread, device model, Android version, full stack trace) to the app's private storage. On next launch you are shown a dialog offering to **share** or **save** that report so you can pass it to the developer for debugging. There is no automatic upload — the report only leaves your device if you explicitly share it.

The pending crash file is deleted whether you choose to save it or not.

## Permissions and why we need them

| Permission | Purpose |
|---|---|
| `RECORD_AUDIO` | To capture your voice when you press the microphone button. Used only while the keyboard is visible and you have started a recording. |
| `INTERNET` | To send the recorded audio to the transcription provider you configured, and to send transcribed text to the post‑processing provider when you use that feature. |
| `REQUEST_INSTALL_PACKAGES` | To let you install the latest version directly from inside the app, **if** you switch the GitHub update check on — it is off by default. When enabled, the app checks GitHub Releases and offers to download and install a newer APK signed with the same key. You can also obtain updates entirely outside the app (e.g. via Obtainium, F‑Droid, or by downloading the APK from GitHub manually). |

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
3. To the GitHub Releases API to check whether a newer version of the app exists — **only if you turned the update check on**, which is off by default — and, if you accept the update, to GitHub's download URL for the APK.

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
