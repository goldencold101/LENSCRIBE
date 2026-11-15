import pathlib, re

root = pathlib.Path('C:/Users/USER/Documents/Qoder/2026-11-10/a8f09c88')
emoji_re = re.compile(r'[\U0001F000-\U0001FAFF\U00002600-\U000027BF]')
for p in sorted(root.rglob('*')):
    s = str(p)
    if any(x in s for x in ['.venv', '.pytest_cache', '.tmp_', '.edgeprofile', '__pycache__', '.git']):
        continue
    if p.is_file() and p.suffix.lower() in ('.py', '.html', '.css', '.js', '.json', '.txt', '.md', '.ps1'):
        try:
            t = p.read_text(encoding='utf-8')
            hits = [(i, c) for i, c in enumerate(t.splitlines(), 1) if emoji_re.search(c)]
            if hits:
                print(f'{p}: {len(hits)}')
                for i, c in hits[:10]:
                    print('   ', i, repr(c))
        except Exception as e:
            pass
