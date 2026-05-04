v1.8.0 — Stop names list from leaking on silence

- Detects when Whisper returns the vocabulary list as a "transcription" of empty recordings, and drops it
- Catches more silence artifacts: subtitle credits with "Корректор/Редактор", thanks lines in German/French
- Stops eating real one-word dictation: short "Привет" / "Спасибо" / "Хорошо" pass through correctly
- Three-name dictations like "Альфия, Алсу, Рустем" are preserved while seven-name hallucinations are not
