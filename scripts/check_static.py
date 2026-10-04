#!/usr/bin/env python3
"""Dependency-free lexical/resource checks. These do not replace the Kotlin compiler or Android lint."""
import re
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'app/src/main/java/com/radiosport/ninegradio'

class KotlinLexicalCheck:
    def __init__(self, path):
        self.path, self.text, self.i = path, path.read_text(), 0
    def error(self, message):
        line = self.text.count('\n', 0, self.i) + 1
        raise AssertionError(f'{self.path.relative_to(ROOT)}:{line}: {message}')
    def string(self, marker):
        raw = marker == '"""'
        self.i += len(marker)
        while self.i < len(self.text):
            if self.text.startswith(marker, self.i):
                self.i += len(marker); return
            if not raw and self.text[self.i] == '\\':
                self.i += 1
                if self.i >= len(self.text) or self.text[self.i] not in "tbnr'\"\\$u":
                    self.error('Invalid Kotlin string escape')
                if self.text[self.i] == 'u':
                    if not re.fullmatch(r'[0-9a-fA-F]{4}', self.text[self.i + 1:self.i + 5]):
                        self.error('Invalid Unicode escape')
                    self.i += 4
                self.i += 1; continue
            if marker != "'" and self.text.startswith('${', self.i):
                self.i += 2; self.code('}'); continue
            self.i += 1
        self.error('Unclosed literal')
    def code(self, stop=None):
        pairs = {'(': ')', '[': ']', '{': '}'}
        while self.i < len(self.text):
            if self.text.startswith('//', self.i):
                end = self.text.find('\n', self.i)
                self.i = len(self.text) if end < 0 else end + 1; continue
            if self.text.startswith('/*', self.i):
                self.i += 2; depth = 1
                while self.i < len(self.text) and depth:
                    if self.text.startswith('/*', self.i): depth += 1; self.i += 2
                    elif self.text.startswith('*/', self.i): depth -= 1; self.i += 2
                    else: self.i += 1
                if depth: self.error('Unclosed comment')
                continue
            if self.text.startswith('"""', self.i): self.string('"""'); continue
            token = self.text[self.i]
            if token in ['"', "'"]: self.string(token); continue
            if token == '`':
                end = self.text.find('`', self.i + 1)
                if end < 0: self.error('Unclosed identifier')
                self.i = end + 1; continue
            if token in pairs:
                self.i += 1; self.code(pairs[token]); continue
            if token in ')]}':
                if token != stop: self.error(f'Unexpected {token}, expected {stop}')
                self.i += 1; return
            self.i += 1
        if stop: self.error(f'Unclosed delimiter; expected {stop}')

files = sorted((SRC / 'skylog').glob('*.kt')) + sorted((SRC / 'adsblog').glob('*.kt')) + [SRC / 'ui' / name for name in
    ['AdsbActivity.kt', 'AdsbLogActivity.kt', 'AdsbDetailActivity.kt', 'MainActivity.kt', 'ControlsTabManager.kt']]
files += [SRC / 'data/AppDatabase.kt', SRC / 'RtlSdrApplication.kt']
files += list((ROOT / 'app/src/test').rglob('*.kt')) + list((ROOT / 'app/src/androidTest').rglob('*.kt'))
for file in files:
    KotlinLexicalCheck(file).code()
    assert not re.search(r'\bfun\s+import\s*\(', file.read_text()), f'Reserved function name: {file}'
resources = list((ROOT / 'app/src/main/res').rglob('*.xml')) + [ROOT / 'app/src/main/AndroidManifest.xml']
for file in resources: ET.parse(file)
resource_text = '\n'.join(p.read_text() for p in resources)
ids = set(re.findall(r'@\+id/([A-Za-z0-9_]+)', resource_text))
ids |= set(re.findall(r'<item[^>]*type="id"[^>]*name="([A-Za-z0-9_]+)"', resource_text))
for file in files:
    refs = set(re.findall(r'(?<!android\.)\bR\.id\.([A-Za-z0-9_]+)', file.read_text()))
    missing = refs - ids
    assert not missing, f'{file}: missing resource IDs {missing}'
manifest = ET.parse(ROOT / 'app/src/main/AndroidManifest.xml')
android = '{http://schemas.android.com/apk/res/android}'
for activity in manifest.findall('.//activity'):
    name = activity.attrib[android + 'name']
    if name.startswith('.'):
        package, class_name = name[1:].rsplit('.', 1)
        candidates = (SRC / package.replace('.', '/')).glob('*.kt')
        assert any(re.search(r'\bclass\s+' + re.escape(class_name) + r'\b', p.read_text()) for p in candidates), f'Missing Activity {name}'
xlsx = (SRC / 'adsblog/ReceptionXlsx.kt').read_text()
styles = re.search(r'private fun styles\(\) = """(.*?)"""', xlsx, re.S).group(1)
styles = ET.fromstring(styles.replace('$MAIN', 'http://schemas.openxmlformats.org/spreadsheetml/2006/main'))
for child in styles:
    if 'count' in child.attrib: assert len(child) == int(child.attrib['count']), f'Invalid XLSX style count {child.tag}'
assert 'ndkVersion "27.0.12077973"' in (ROOT / 'app/build.gradle').read_text()
assert "NDK_VERSION: '27.0.12077973'" in (ROOT / '.github/workflows/adsb-logger.yml').read_text()
print(f'PASS: lexical checks for {len(files)} Kotlin files; {len(resources)} XML files; resource IDs; manifest; XLSX styles; CI NDK consistency.')
print('Kotlin type checking, Room validation and export runtime tests run in GitHub Actions.')
