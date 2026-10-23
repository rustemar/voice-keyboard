v1.10.0 — Voice input for apps and a compact terminal

- Voice input for other apps: an optional Android recognition service and dictation window use your saved speech provider and API key. Enable this in Advanced settings; no keyboard switch is needed. Requests use the calling app’s language, without text post-processing or recent context.
- Optional compact terminal layout: a smaller panel with microphone, cancel, backspace, Enter and keyboard switching. It appears only in Terminal mode, keeps buttons at least 48 dp, and can expand during recording without interrupting it.
- Cancel the latest unfinished dictation during recording, recognition, post-processing or while waiting to be resent. Earlier recordings keep their order; cancelled results are not inserted or added to context.

Both new options are off by default. Speech recognition needs a network connection to your provider. Apps with their own speech engine may not use the system service; on-device recognition is not provided.
