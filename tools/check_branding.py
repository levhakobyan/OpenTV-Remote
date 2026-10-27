#!/usr/bin/env python3
"""Reject obsolete product branding while preserving explicit upgrade/cleanup metadata."""
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]
TEXT_EXTENSIONS = {'.md', '.swift', '.kt', '.kts', '.xml', '.strings', '.plist', '.pbxproj', '.xcscheme', '.py', '.html', '.sh', '.json', '.yml', '.yaml', '.properties', '.svg', '.entitlements'}


def obsolete_text(path, line):
    # Installed-app identities and package imports are not display branding.
    line = re.sub(r'com\.luma\.remote(?:\.[A-Za-z0-9_.]+)?', '', line)
    line = line.replace('com/luma/remote', '')
    if path == 'android/app/src/main/java/com/luma/remote/data/Vault.kt':
        for value in ('"luma-private"', '"luma-vault"', '"luma"'):
            line = line.replace(value, '')
    if path == 'android/app/src/main/java/com/luma/remote/protocol/AndroidTvProvider.kt':
        line = line.replace('"luma"', '')
    if path == 'tools/check_ios_bundle.py':
        line = line.replace('_lumaremote._tcp', '')
    if path in ('tools/prepare_downloads.py', 'tools/test_downloads.py', 'docs/TESTING.md'):
        line = line.replace('luma-tv-companion-debug.apk', '').replace('luma-remote-debug.apk', '')
    if path == 'docs/TESTING.md':
        # Preserve original, dated evidence filenames rather than fabricate renamed logs.
        line = re.sub(r'/private/tmp/luma[^`\s,)]+', '', line)
        line = re.sub(r'Test-LumaRemote-[^`]+\.xcresult', '', line)
        line = re.sub(r'`luma-[^`]+\.log`', '', line)
    return re.search('luma', line, re.IGNORECASE) is not None


def main():
    result = subprocess.check_output(['git', 'ls-files', '--cached', '--others', '--exclude-standard', '-z'], cwd=ROOT)
    files = sorted(set(result.decode().split('\0')) - {''})
    findings = []
    checked = 0
    for name in files:
        path = ROOT / name
        if not path.is_file() or name in ('tools/check_branding.py', 'tools/test_branding.py'):
            continue
        for part in Path(name).parts:
            if 'luma' in part.lower() and not (part == 'luma' and '/com/luma/remote/' in name):
                findings.append(f'{name}: obsolete filename/directory')
        if path.suffix not in TEXT_EXTENSIONS:
            continue
        checked += 1
        for number, line in enumerate(path.read_text(encoding='utf-8').splitlines(), 1):
            if obsolete_text(name, line):
                findings.append(f'{name}:{number}: obsolete product name')
    if findings:
        print('\n'.join(findings))
        raise SystemExit(1)
    print(f'PASS: {checked} source/document files; old literals limited to explicit compatibility, cleanup and historical evidence metadata')


if __name__ == '__main__':
    main()
