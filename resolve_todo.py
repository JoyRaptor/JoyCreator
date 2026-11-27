from pathlib import Path
p=Path('tasks/todo.md'); lines=p.read_text(encoding='utf-8').splitlines(); lines=[line for line in lines if not line.startswith(('<<<<<<<','=======','>>>>>>>'))]; p.write_text('\n'.join(lines)+'\n', encoding='utf-8')
