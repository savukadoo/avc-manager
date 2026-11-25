#!/usr/bin/env python3
"""Compile production Java with the Android resource interface and a strict warning gate."""
from pathlib import Path
import os,subprocess,tempfile
root=Path(__file__).resolve().parents[1]
java=Path(os.environ['JAVA_HOME'])/'bin/javac';sdk=os.environ['ANDROID_JAR'];aapt=Path(os.environ['BUILD_TOOLS'])/'aapt2'
with tempfile.TemporaryDirectory(prefix='cc3-java-gate-') as work:
 work=Path(work);(work/'gen').mkdir();(work/'classes').mkdir()
 for args in [[str(aapt),'compile','--dir',str(root/'app/src/main/res'),'-o',str(work/'res.zip')],[str(aapt),'link','-I',sdk,'--manifest',str(root/'app/src/main/AndroidManifest.xml'),'--java',str(work/'gen'),'-o',str(work/'res.apk'),str(work/'res.zip')]]:
  subprocess.run(args,check=True,capture_output=True)
 sources=[str(p) for base in [root/'app/src/main/java',root/'modules/obd-core/src/main/java',root/'modules/obd-android/src/main/java',work/'gen'] for p in base.rglob('*.java')]
 result=subprocess.run([str(java),'--release','8','-Xlint:all,-options','-Werror','-encoding','UTF-8','-classpath',sdk,'-d',str(work/'classes'),*sources])
 if result.returncode:raise SystemExit(result.returncode)
 print(f'{len(sources)} production Java sources compiled; no untriaged warnings')
