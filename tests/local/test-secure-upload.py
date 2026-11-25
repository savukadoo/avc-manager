#!/usr/bin/env python3
"""Real TLS, certificate pinning and pre-authentication resource-boundary checks; loopback only."""
from pathlib import Path
import http.client
import importlib.util
import json
import os
import socket
import ssl
import subprocess
import tempfile
import time

ROOT=Path(__file__).resolve().parents[2]
JAVA=Path(os.environ['JAVA_HOME'])/'bin'
spec=importlib.util.spec_from_file_location('cc3_upload',ROOT/'tools/cc3-upload.py')
module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
checks=0
def check(condition,name):
    global checks
    assert condition,name
    checks+=1

with tempfile.TemporaryDirectory(prefix='cc3-tls-tests-') as directory:
    work=Path(directory);classes=work/'classes';classes.mkdir()
    sources=ROOT/'app/src/main/java/local/cc3/manager'
    subprocess.run([str(JAVA/'javac'),'--release','8','-Xlint:-options','-d',str(classes),
                    *[str(sources/(name+'.java')) for name in ['MediaUploadServer','MediaFileStore','TlsCertificate']],
                    str(ROOT/'tests/java/local/cc3/manager/MediaUploadHost.java')],check=True,capture_output=True)
    for secure in [False,True,"ec"]:
        library=work/('tls-ec' if secure=='ec' else 'tls-ec' if secure=='ec' else 'tls' if secure else 'http')
        process=subprocess.Popen([str(JAVA/'java'),'-cp',str(classes),'local.cc3.manager.MediaUploadHost',str(library),str(ROOT/'app/src/main/assets/media-upload.html'),'0','0','tls-ec' if secure=='ec' else 'tls' if secure else 'http'],stdin=subprocess.PIPE,stdout=subprocess.PIPE,text=True)
        held=[]
        try:
            parts=process.stdout.readline().split();url,pin=parts[:2];port=int(url.rsplit(':',1)[1]);authority=f'127.0.0.1:{port}'
            if secure:
                fingerprint=parts[2]
                bad=module.Uploader(url,'00'*32)
                try:bad.pair(pin);raise AssertionError('bad pin accepted')
                except ssl.SSLError:checks+=1
                check(not list(library.glob('*.mp3')),'mismatch sends no upload')
                client=module.Uploader(url,fingerprint);check(client.pair(pin)['paired'],'pinned pairing succeeds')
                track=work/'pinned track.mp3';track.write_bytes(bytes(range(256))*256)
                saved=client.upload(track);check(saved['bytes']==track.stat().st_size,'pinned streaming upload complete')
                check((library/track.name).read_bytes()==track.read_bytes(),'TLS upload bytes preserved')
                check(client.cookie.startswith('cc3media='),'authenticated session established')
                connection=module.PinnedConnection('127.0.0.1',port,fingerprint);connection.connect()
                check(connection.sock.version() in ('TLSv1.2','TLSv1.3'),'modern TLS negotiated');connection.close()
                # Raw HTTP must not produce the upload page from a TLS listener.
                plain=http.client.HTTPConnection('127.0.0.1',port,timeout=4)
                try:
                    plain.request('GET','/',headers={'Host':authority});reply=plain.getresponse();body=reply.read()
                    check(b'Good music.' not in body,'no plaintext upload page')
                except (OSError,http.client.HTTPException):checks+=1
                finally:plain.close()
            else:
                # Saturate this peer's pre-authentication budget and drip before each read timeout.
                for _ in range(2):
                    connection=socket.create_connection(('127.0.0.1',port),timeout=4);connection.sendall(b'G');held.append(connection)
                start=time.monotonic();served=False
                while time.monotonic()-start<5:
                    for connection in held:
                        try:connection.sendall(b'E')
                        except OSError:pass
                    legitimate=http.client.HTTPConnection('127.0.0.1',port,timeout=.5)
                    try:
                        legitimate.request('GET','/',headers={'Host':authority});reply=legitimate.getresponse();reply.read();served=reply.status==200
                    except (OSError,http.client.HTTPException):pass
                    finally:legitimate.close()
                    if served:break
                    time.sleep(.1)
                check(served and time.monotonic()-start<5,'absolute deadline restores service despite drip requests')
                for connection in held:
                    try:closed=connection.recv(1)==b''
                    except OSError:closed=True
                    check(closed,'drip request closed at absolute deadline')
                for connection in held:connection.close()
                held=[]
        finally:
            for connection in held:connection.close()
            process.communicate('stop\n',timeout=5)
    for bad_url in ['http://127.0.0.1:8765','https://example.com:8765','https://user@127.0.0.1:8765','https://127.0.0.1:8765/path']:
        try:module.Uploader(bad_url,'00'*32);raise AssertionError('unsafe endpoint accepted')
        except ValueError:checks+=1
print(f'{checks} encrypted upload and connection-budget checks passed')
