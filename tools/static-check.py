#!/usr/bin/env python3
"""Supplemental repository checks. This is not an Android/Kotlin compiler or Android lint replacement."""
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET
import zipfile

root = Path(__file__).resolve().parent.parent
failures = []
for xml in root.glob('app/src/main/**/*.xml'):
    try:
        ET.parse(xml)
    except ET.ParseError as exc:
        failures.append(f'{xml}: {exc}')
for source in root.rglob('*.kt'):
    text = source.read_text()
    if re.search(r'\b(?:val|var)\s+(?:private|public|class|object|fun)\s*[:=]', text):
        failures.append(f'{source}: reserved identifier')
    if ('TODO(' in text or 'NotImplementedError' in text):
        failures.append(f'{source}: unimplemented path')
for path in root.rglob('*'):
    if path.is_file() and path.suffix in {'.jks', '.keystore', '.p12', '.pem'}:
        failures.append(f'Unexpected secret/key file: {path}')
jar = root/'gradle/wrapper/dgchat-bootstrap.jar'
with zipfile.ZipFile(jar) as z:
    assert 'io/github/goraidebjyoti/dgchat/build/GradleBootstrap.class' in z.namelist()
assert (root/'.github/workflows/build-apk.yml').exists()
assert 'dgchat-bootstrap.jar' in (root/'gradlew').read_text()
if failures:
    print('\n'.join(failures))
    sys.exit(1)
print('PASS: XML, reserved identifiers, missing implementation sentinels, key-file exclusion, bootstrap archive, workflow presence.')
