# Audio comparison test

Voice Keyboard Audio Test is a separate experimental app for comparing
audio encoding and speech recognition. It installs beside Voice Keyboard
as `com.tyraen.voicekeyboard.audiotest`. It has no keyboard service or
automatic updater and cannot replace the regular app or its settings.

Download `VoiceKeyboardAudioTest.apk` from an `audio-test-*` prerelease
on the [releases page](https://github.com/rustemar/voice-keyboard/releases).
These tags do not match F-Droid's current `^v[0-9.]+$` update pattern.
The regular keyboard updater skips prereleases. A checksum is attached
to each test download.

## Run a comparison

1. Open the test app and turn on **Enable audio comparison**. It starts
   disabled each time the app opens.
2. Tap **Record one sample**, grant microphone permission, and read the
   sample or dictate your own text. Tap **Stop and encode** when done.
   Recording stops automatically after 45 seconds and is cancelled if
   you leave the app during capture.
3. Inspect the available WAV, Opus 64/128/200 kbps and AAC 128 kbps
   variants. Recording and **Save audio and report ZIP** need no API key.
4. To compare recognition, enter your own key and check the endpoint,
   model and language. The default is Groq `whisper-large-v3-turbo`.
   The key stays in memory and must be entered again after closing.
5. Optionally add the text you actually read as the reference. The
   **Use reading text as reference** button fills the displayed sample.
   The reference is never sent as a recognition prompt.
6. Tap **Recognize variants**. The app sends one request per available
   variant with identical settings, in shuffled order. Post-processing,
   context, filtering and automatic request retries are disabled.
7. Save the ZIP before closing. It contains the audio, recording and
   encoder details, test settings, raw transcript text, status codes,
   timings and optional word-error scores. It does not contain the API
   key. **Cancel** stops the current request and the remaining batch.

The default endpoint accepts WAV/OGG/M4A. A custom provider must support
the chosen formats and the multipart `/audio/transcriptions` API;
Chat Completions is outside this test. Unsupported encoders are reported
explicitly, without replacing a requested profile. Opus encoding and
OGG muxing require Android 10 or later.

## Interpret the results

Every variant comes from the same 48 kHz mono PCM16 recording. AAC also
uses 48 kHz to avoid changing sample rate and codec at the same time.
The original is not created by decoding a lower-quality recording.

Requested encoder bitrate, declared bitrate and file-average bitrate
are different measurements. The report preserves those distinctions.
Upload timing measures writes to the network stack, not delivery
acknowledgements; response wait includes both networking and provider
processing. The utility does not estimate internet speed or change
compression automatically.

Word-error scoring ignores case and punctuation but does not normalize
digits versus written numbers. Review names and numbers yourself.
One recording or a successful HTTP response cannot establish general
recognition quality. Repeat with natural speech and varied conditions;
the provider can return different text even with temperature zero.
AudioRecord capture may also differ from the regular keyboard's
MediaRecorder processing on a particular device.

Temporary recordings are private to the test app and replaced by a new
sample. Closing the activity clears them; a later capture removes any
leftovers after a crash. Exported ZIP files remain where you save them
and contain your voice and transcripts.

## Build locally

Run `./gradlew testAudioTestUnitTest assembleAudioTest`. The APK is
`app/build/outputs/apk/audioTest/VoiceKeyboard.apk`. Without release
signing credentials, local builds use the local debug certificate and
cannot update a test APK downloaded from GitHub; use a separate device
or uninstall the previous test app first. The normal `assembleRelease`
task and stable version remain unchanged.
