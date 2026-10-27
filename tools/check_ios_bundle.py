#!/usr/bin/env python3
"""Verify runtime resources which a successful compile alone does not prove."""
import argparse
import plistlib
import re
import subprocess
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('app', type=Path, help='Built LumaRemote.app directory')
args = parser.parse_args()
app = args.app
with (app / 'Info.plist').open('rb') as stream:
    info = plistlib.load(stream)
required = {'_androidtvremote2._tcp'}
assert required.issubset(info.get('NSBonjourServices', [])), 'Missing remote discovery declarations'
assert info.get('NSLocalNetworkUsageDescription'), 'Missing local network permission description'
assert '_lumaremote._tcp' not in info.get('NSBonjourServices', []), 'Retired TV receiver discovery remains'
assert not any('googlecast' in service.lower() for service in info.get('NSBonjourServices', [])), 'Obsolete Cast discovery remains'
for language in ['en', 'ru']:
    assert (app / (language + '.lproj') / 'Localizable.strings').is_file(), f'Missing {language} localization'
assert not any('GoogleCast' in str(path) or 'GTMSessionFetcher' in str(path) for path in app.rglob('*')), 'Obsolete Cast runtime resources remain'
# XCTest/debugger framework search paths can hide a broken standalone installation.
images = [app / info['CFBundleExecutable']]
debug_image = app / (info['CFBundleExecutable'] + '.debug.dylib')
if debug_image.is_file(): images.append(debug_image)
paths = set()
for image in images:
    dependencies = subprocess.check_output(['otool', '-L', str(image)], text=True)
    assert 'GoogleCast' not in dependencies and 'GTMSessionFetcher' not in dependencies, 'Obsolete Cast linkage remains'
    commands = subprocess.check_output(['otool', '-l', str(image)], text=True)
    paths.update(re.findall(r'cmd LC_RPATH\s+cmdsize \d+\s+path (.+?) \(offset', commands))
assert '@executable_path/Frameworks' in paths, 'Missing installed-app framework runpath; XCTest may conceal launch failure'
print('PASS remote discovery, EN/RU resources, runtime paths and absence of Cast')
