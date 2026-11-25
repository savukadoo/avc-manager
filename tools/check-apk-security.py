#!/usr/bin/env python3
from pathlib import Path
import os,subprocess,tempfile,zipfile
root=Path(__file__).resolve().parents[1]
with tempfile.TemporaryDirectory(prefix='cc3-apk-gate-') as work,zipfile.ZipFile(root/'build/AVC-Manager.apk') as apk:
    assert not any(n.lower().endswith(('.keystore','.jks','.pem','.p12','.pfx','.key')) for n in apk.namelist()),'private signing material in APK'
    for source in ('LICENSE', 'NOTICE', 'LICENSES/FYT-ADB-MIT.txt', 'LICENSES/GPL-3.0.txt'):
        name = 'assets/licenses/' + Path(source).name
        assert apk.read(name) == (root / source).read_bytes(), 'Missing or stale notice: ' + name
    vendor = root / 'third_party/zdtun'
    assert apk.read('assets/zdtun-LGPL.txt') == (vendor / 'COPYING').read_bytes()
    header = (vendor / 'third_party/uthash.h').read_bytes().split(b'*/', 1)[0] + b'*/'
    assert apk.read('assets/licenses/uthash-BSD-1-Clause.txt').strip() == header.strip()
    reader=next(Path(os.environ['ANDROID_NDK_HOME']).glob('toolchains/llvm/prebuilt/*/bin/llvm-readelf'))
    libraries=[n for n in apk.namelist() if n.endswith('.so')]
    assert len(libraries)==2
    for name in libraries:
        file=Path(work)/Path(name).name;file.write_bytes(apk.read(name))
        report=subprocess.run([str(reader),'-W','-l','-d','-s',str(file)],capture_output=True,text=True,check=True).stdout
        stack=next(line for line in report.splitlines() if 'GNU_STACK' in line)
        assert 'RWE' not in stack and 'GNU_RELRO' in report and ('BIND_NOW' in report or 'NOW' in report),name
        assert '__stack_chk_fail' in report,'stack protection missing: '+name
    aapt=Path(os.environ['BUILD_TOOLS'])/'aapt2'
    manifest=subprocess.run([str(aapt),'dump','xmltree',str(root/'build/AVC-Manager.apk'),'--file','AndroidManifest.xml'],capture_output=True,text=True,check=True).stdout
    assert 'android:debuggable' not in manifest,'release manifest must not be debuggable'
    assert 'android:allowBackup' in manifest and 'allowBackup(0x01010280)=false' in manifest
print('APK private-material, non-debuggable, backup and native hardening checks passed')
