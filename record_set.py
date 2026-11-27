from pathlib import Path
p=Path('tasks/todo.md'); s=p.read_text(encoding='utf-8'); tail=s.index('## JB-9.10 owner test surfaces'); s=s[:tail]+s[tail:].replace('- [ ]','- [x]')+'\nReview: targeted core XML9/0 at2026-10-02 00:30:38 EDT; flat-linen mutation1/9 red, restored. Actual renderer contact sheet inspected. See reviews/JB-9.10__test_set.md.\n'; p.write_text(s, encoding='utf-8')
p=Path('tasks/joybrush/PAPER_DISPATCH.md'); s=p.read_text(encoding='utf-8').replace('27 looks + 8 surfaces handed off; image-free AMOLED black added; generation paused by the owner','Three canvases + factory/handmade/artisan pulp ready for testing; image-free AMOLED black; generation paused by the owner'); p.write_text(s, encoding='utf-8')

