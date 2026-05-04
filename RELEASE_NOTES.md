v1.7.8 — Fix vocabulary leak on silence

- Fixed: when nothing was said, post-processing could output a list of vocabulary words
- The vocabulary is now passed as a strict spelling reference, never as content to insert
