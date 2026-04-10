# Voice Keyboard

Android keyboard (IME) for speech-to-text transcription using Whisper API via GroQ.

## Features

- Voice input with real-time amplitude visualization
- GroQ Whisper API integration (whisper-large-v3-turbo)
- Configurable API endpoint, model, and language
- 17 UI languages and transcription languages
- Auto-start recording when keyboard opens
- Built-in test recording in settings
- App logs and crash reports
- Auto-update from GitHub Releases

## Setup

1. Install the APK from [Releases](https://github.com/rustemar/voice-keyboard/releases)
2. Go to Settings → System → Languages & input → On-screen keyboard
3. Enable "Voice Keyboard"
4. Open the app and enter your [GroQ API key](https://console.groq.com/keys)

## Building from source

```bash
git clone https://github.com/rustemar/voice-keyboard.git
cd voice-keyboard
./gradlew assembleDebug
```

## License

[MIT](LICENSE)
