# Voice Keyboard

Android keyboard (IME) for speech-to-text transcription using Whisper API via GroQ with AI-powered post-processing.

## Features

### Voice Input
- Real-time voice recording with amplitude visualization
- **Processing queue** — start a new recording immediately, previous ones transcribe in the background
- GroQ Whisper API integration (whisper-large-v3-turbo)
- Configurable API endpoint, model, and language
- Auto-start recording when keyboard opens

### AI Post-Processing
- **Fix errors** — corrects punctuation, spelling, removes filler words (um, uh)
- **Shorten** — makes text concise while keeping key points
- **Emoji** — adds relevant emoji to your messages
- **Rhyme** — rewrites dictated text as poetry
- **Translate** — translates to any of the supported languages
- Supports OpenAI and Claude as processing providers
- Customizable prompts and temperature for each mode

### Keyboard
- **Send button** (paper plane) — sends Ctrl+Enter for quick message sending in messengers
- **Accelerating backspace** — hold to delete slowly at first, then faster
- **Clipboard bar** stays visible after paste for repeated pasting
- **Graceful shutdown** — if keyboard hides during recording, audio is finalized and transcribed to clipboard

### General
- 17 interface and transcription languages
- Light, Dark, and Auto themes
- Long-press spacebar to switch keyboard
- Built-in test recording in settings
- App logs and crash reports
- Auto-update from GitHub Releases

## Setup

1. Install the APK from [Releases](https://github.com/rustemar/voice-keyboard/releases)
2. Go to Settings → System → Languages & input → On-screen keyboard
3. Enable "Voice Keyboard"
4. Open the app and enter your [GroQ API key](https://console.groq.com/keys)
5. (Optional) Configure AI post-processing with your OpenAI or Claude API key

## Building from source

```bash
git clone https://github.com/rustemar/voice-keyboard.git
cd voice-keyboard
./gradlew assembleDebug
```

## License

[MIT](LICENSE)
