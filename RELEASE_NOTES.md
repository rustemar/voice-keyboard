v1.8.3 — Don't lose recordings when the network drops

- Failed transcriptions are now retried automatically (3 attempts with a short backoff), so a brief Wi-Fi↔mobile hand-off no longer eats your dictation
- If a recording still can't be sent, it's kept instead of discarded: a red counter and "resend" button appear under the backspace key — tap it once the connection is back to transcribe everything that was waiting
- Permanent errors (wrong API key) stop retrying immediately but the recording is still kept, so you can fix the key and resend
