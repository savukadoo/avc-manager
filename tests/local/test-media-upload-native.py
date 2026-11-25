#!/usr/bin/env python3
"""Emulator-only HTTP -> managed library -> MediaPlayer integration. Requires the UI observer."""
from pathlib import Path
exec((Path(__file__).with_name('test-media-native.py')).read_text().split("if __name__")[0])
import hashlib,urllib.parse
if state()['activity'].endswith('MainActivity'):tap('Upload')

if not state()['transfer']['active']:tap('Start uploads')
s=wait(lambda s:s['transfer']['active']);transfer=s['transfer'];url=transfer['url'];host=url.removeprefix('http://');address,port=host.split(':');cookie='';csrf=''
def request(path,body=None):
 method='GET' if body is None else 'POST'
 headers=f'{method} {path} HTTP/1.1\r\nHost: {host}\r\n'
 if body is not None:headers+=f'Origin: {url}\r\nX-CC3-Upload: 1\r\nContent-Length: {len(body)}\r\n'
 if cookie:headers+=f'Cookie: {cookie}\r\nX-CC3-CSRF: {csrf}\r\n'
 payload=(headers+'Connection: close\r\n\r\n').encode()+(body or b'')
 local=OUT/'upload-request.bin';local.write_bytes(payload);adb('push',str(local),'/data/local/tmp/cc3-media-request.bin')
 result=subprocess.run([ADB,'-s','emulator-5558','exec-out',f'toybox nc -w 5 -q 1 {address} {port} < /data/local/tmp/cc3-media-request.bin'],capture_output=True,timeout=25)
 raw=result.stdout
 if not raw.startswith(b'HTTP/1.1 '):raise AssertionError('No response '+repr(raw[:100])+repr(result.stderr))
 head,data=raw.split(b'\r\n\r\n',1);status=int(head.split(b' ')[1]);return status,head.decode(),data
code,head,data=request('/api/pair',transfer['code'].encode());assert code==200,(code,data)
cookie=next(x.split(': ',1)[1].split(';')[0] for x in head.split('\r\n') if x.startswith('Set-Cookie:'));csrf=json.loads(data)['csrf']
fixture=OUT/'media-fixtures/02 Coastal route.m4a';payload=fixture.read_bytes();name='07 Uploaded native fixture.m4a'
code,head,data=request('/api/upload?name='+urllib.parse.quote(name),payload);assert code==201,(code,data)
actual_name=json.loads(data)['name']
saved=adb('exec-out','cat','/sdcard/Android/data/local.cc3.manager/files/entertainment/'+actual_name,raw=True)
assert hashlib.sha256(saved).digest()==hashlib.sha256(payload).digest()
s=wait(lambda s:s['transfer']['completed']==1);capture('native-upload-saved')
tap('Stop uploads');tap('‹ Entertainment');wait(lambda s:s['activity'].endswith('MainActivity'))
search=next(n for n in nodes() if n['contentDesc']=='Search music');x1,y1,x2,y2=search['visibleBounds'];adb('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2));adb('shell','input','keyevent','123');adb('shell','input','keyevent',*(['67']*50));adb('shell','input','text','Uploaded');adb('shell','input','keyevent','4');time.sleep(1)
tap(Path(actual_name).stem,True);s=wait(lambda s:s['playback']['playing'] or s['playback']['error']);assert s['playback']['playing'],s['playback']
capture('native-upload-playing');tap('Pause')
(OUT/'native-upload-checks.json').write_text(json.dumps({'paired':True,'streamed_and_sha256_verified':True,'completed_count':1,'automatic_library_refresh':True,'uploaded_m4a_real_decode':True,'transport':'ADB pipes a real HTTP request to emulator Wi-Fi interface; physical laptop LAN pending'},indent=2))
print('Native upload → atomic file → automatic library refresh → real M4A playback passed')
