v1.14.0 — Better auto-stop in background noise

- Auto-stop now detects speech on the device to recognize pauses more reliably around background noise. It remains off by default, with the same timer button and 0.3–3 second pause setting. Other voices, singing or difficult noise can still affect stopping.
- Auto-start recording, trailing-space and single-word punctuation switches now save immediately. Auto-start recording also has an explanation in settings.
- Settings more clearly separate voice recognition from optional text post-processing. Provider key links, default presets and the save button are clearer; changing the interface language preserves unfinished form edits without applying them.

Pause detection uses [Silero VAD](https://github.com/snakers4/silero-vad) and [ONNX Runtime](https://github.com/microsoft/onnxruntime), both MIT-licensed. Thanks to their contributors. The detector runs locally; recordings still go only to the speech provider configured in the app. The universal APK is about 46 MB because the local speech detector and its runtime are bundled.
