from pathlib import Path
p=Path('tasks/todo.md'); s=p.read_text(encoding='utf-8'); tail=s.index('## JB-9.07 custom colour backend'); s=s[:tail]+s[tail:].replace('- [ ]','- [x]')+'\nReview: targeted core XML21/0 at2026-10-02 00:34:06 EDT; stale-tint mutation1/21 red, restored. Lead retains swatch/selector UI. See reviews/JB-9.07__custom_colour.md.\n'; p.write_text(s, encoding='utf-8')
