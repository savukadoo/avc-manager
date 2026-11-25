#!/usr/bin/env python3
"""Check product naming in maintained source, documentation and build files."""
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TEXT = {'.java', '.c', '.h', '.kt', '.xml', '.md', '.txt', '.json', '.html',
        '.py', '.sh', '.yml', '.yaml', '.svg'}
OLD_NAME = re.compile(r'\bcc3[ \t-]+manager\b', re.IGNORECASE)

files = [f for f in ROOT.iterdir() if f.is_file()]
for name in ('app', 'modules', 'tools', 'tests', 'docs', 'research', 'mockups', 'design', '.github', '.agents'):
    files.extend((ROOT / name).rglob('*'))

errors = []
for path in sorted(files):
    if path.is_file() and (path.suffix in TEXT or path.name == 'NOTICE'):
        for number, line in enumerate(path.read_text(encoding='utf-8').splitlines(), 1):
            if OLD_NAME.search(line):
                errors.append(f'{path.relative_to(ROOT)}:{number}: use AVC Manager for the app name')
if errors:
    raise SystemExit('\n'.join(errors))
print('AVC Manager branding check passed')
