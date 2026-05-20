v1.8.2 — Strip trailing "Субтитры создавал X" hallucinations

- Removes Whisper end-card credits appended after legitimate speech (the classic "Субтитры создавал DimaTorzok" tail, plus "Subtitles by …", "Sous-titres réalisés par …", "Untertitelung im Auftrag des …", "Спасибо за просмотр", etc.)
- Real sentences that just happen to mention subtitles ("Я смотрел фильм. Субтитры были на английском.", "Включи субтитры пожалуйста.") pass through unchanged
- Initials in credit lines ("И. Иванов", "А. Сёмкин") are recognized as part of the credit, not a sentence boundary
