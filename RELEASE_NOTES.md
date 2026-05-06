v1.8.1 — Strip trailing notes from post-processing output

- Removes trailing "Note:" / "Footnote:" / "Disclaimer:" blocks (in any supported language) that post-processing sometimes appended after the actual text
- Removes stray separator lines (---, ***, ___) at the end of the output
- Strengthened the post-processing prompt to forbid these in the first place; the strip step is a safety net
