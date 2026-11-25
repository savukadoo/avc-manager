#!/usr/bin/env python3
from pathlib import Path
import subprocess
import tempfile
import os
ROOT=Path(__file__).resolve().parents[2]
checks=0
with tempfile.TemporaryDirectory(prefix='cc3-signing-tests-') as directory:
    directory=Path(directory);key=directory/'identity';password=directory/'password'
    key.write_bytes(b'fixture-only');password.write_text('not-a-release-secret\n')
    for file in [key,password]:os.chmod(file,0o600)
    def accepted(first=key,second=password):
        return subprocess.run(['python3',str(ROOT/'tools/check-signing-inputs.py'),str(first),str(second)],capture_output=True).returncode==0
    assert accepted();checks+=1
    os.chmod(password,0o644);assert not accepted();checks+=1
    os.chmod(password,0o600);assert not accepted(directory/'missing');checks+=1
    assert not accepted(ROOT/'build/AVC-Manager.apk');checks+=1
    assert not (directory/'missing').exists();checks+=1
print(f'{checks} release signing boundary checks passed')
