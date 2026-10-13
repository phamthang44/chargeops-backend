"""Apply public URL overlays with byte-for-byte env backups; never print secrets."""
import argparse
from datetime import datetime
import json
from pathlib import Path
import re
import shutil
import subprocess

parser = argparse.ArgumentParser()
parser.add_argument('--frontend', type=Path, required=True)
parser.add_argument('--restore', type=Path, help='Restore a prior manifest, keeping current files too')
args = parser.parse_args()
backend = Path(__file__).resolve().parents[1]
frontend = args.frontend.resolve()
if not (frontend / 'chargeops-web').is_dir():
    raise SystemExit('Expected the chargeops-frontend repository root.')
stamp = datetime.now().strftime('%Y%m%d-%H%M%S')

if args.restore:
    manifest = json.loads(args.restore.read_text(encoding='utf-8'))
    for item in manifest['files']:
        path = Path(item['path']).resolve()
        if not (path.is_relative_to(backend) or path.is_relative_to(frontend)):
            raise SystemExit('Restore path is outside the two project roots.')
        if not path.name.startswith('.env'):
            raise SystemExit('Restore only supports environment files.')
        if path.exists():
            shutil.copy2(path, path.with_name(path.name + '.backup-domain-' + stamp))
        if item['backup']:
            backup = Path(item['backup']).resolve()
            if backup.parent != path.parent or not backup.name.startswith(path.name + '.backup-'):
                raise SystemExit('Unexpected backup path.')
            shutil.copy2(backup, path)
        else:
            # Keep the newly created file in place as a backup; no file is deleted.
            path.rename(path.with_name(path.name + '.disabled-domain-' + stamp))
    print('Restored environment files; current domain configs were retained as backups.')
    raise SystemExit(0)

groups = [
    (backend, backend/'config/domain/backend.env.example', ['.env', '.env.demo']),
    (frontend/'chargeops-web/apps/web', frontend/'config/domain/web.env.example', ['.env']),
    (frontend/'chargeops-web', frontend/'config/domain/web.env.example', []),
    (frontend/'chargeops-driver-mobile', frontend/'config/domain/driver.env.example', ['.env']),
    (frontend/'chargeops-marketing', frontend/'config/domain/marketing.env.example', ['.env.local']),
]
valid_names = {'.env', '.env.local', '.env.development', '.env.development.local',
               '.env.production', '.env.production.local', '.env.demo'}
changes = []
for directory, template, required in groups:
    values = {}
    for line in template.read_text(encoding='utf-8').splitlines():
        if line and not line.startswith('#') and '=' in line:
            key, value = line.split('=', 1)
            values[key] = value
    paths = {directory/name for name in required}
    paths.update(path for path in directory.glob('.env*') if path.name in valid_names)
    for path in sorted(paths):
        original = path.read_text(encoding='utf-8-sig') if path.exists() else ''
        lines = original.splitlines()
        seen = set()
        updated = []
        for line in lines:
            match = re.match(r'^(\s*(?:export\s+)?)([A-Za-z_][A-Za-z0-9_]*)(\s*=)(.*)$', line)
            if match:
                prefix, key, separator, value = match.groups()
                if key in values:
                    line = prefix + key + separator + values[key]
                    seen.add(key)
                else:
                    for old, new in (
                        ('https://thang.tail704409.ts.net:8443', 'https://driver.chargeops.online'),
                        ('https://thang.tail704409.ts.net:10000', 'https://driver.chargeops.online'),
                        ('https://thang.tail704409.ts.net', 'https://chargeops.online'),
                        ('wss://thang.tail704409.ts.net', 'wss://chargeops.online'),
                    ):
                        value = value.replace(old, new)
                    line = prefix + key + separator + value
            updated.append(line)
        updated += ['', '# Public domain migration: ' + stamp]
        updated += [key + '=' + value for key, value in values.items() if key not in seen]
        backup = path.with_name(path.name + '.backup-tailscale-' + stamp)
        repo = backend if path.is_relative_to(backend) else frontend
        for private_path in (path, backup):
            result = subprocess.run(['git', '-C', str(repo), 'check-ignore', '-q', str(private_path)])
            if result.returncode:
                raise SystemExit('Environment/backup path must be ignored by Git: ' + str(private_path))
        changes.append((path, backup if path.exists() else None, '\n'.join(updated) + '\n'))

manifest = backend / '.codex-tmp' / ('domain-migration-' + stamp) / 'manifest.json'
manifest.parent.mkdir(parents=True, exist_ok=False)
items = []
for path, backup, content in changes:
    if backup:
        shutil.copy2(path, backup)
    path.write_text(content, encoding='utf-8')
    items.append({'path': str(path), 'backup': str(backup) if backup else None})
    print('Configured:', path, '(original retained)' if backup else '(new file)')
manifest.write_text(json.dumps({'files': items}, indent=2), encoding='utf-8')
print('Rollback manifest:', manifest)
