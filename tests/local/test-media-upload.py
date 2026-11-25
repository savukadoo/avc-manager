#!/usr/bin/env python3
"""Real HTTP uploads; auth/origin boundaries, interrupted writes, duplicates and expiry."""
import hashlib, http.client, json, os, pathlib, shutil, socket, subprocess, tempfile, time
ROOT=pathlib.Path(__file__).resolve().parents[2]
JAVA=pathlib.Path(os.environ.get('JAVA_HOME','/private/tmp/cc3-build-tools/jdk/jdk-21.0.13.1+2/Contents/Home'))/'bin'
checks=0
def check(ok, label):
    global checks
    assert ok,label
    checks+=1
class Server:
    def __init__(self, classes, folder, reserve=0, port=0):
        self.folder=folder
        self.proc=subprocess.Popen([str(JAVA/'java'),'-Xmx48m','-cp',str(classes),'local.cc3.manager.MediaUploadHost',str(folder),str(ROOT/'app/src/main/assets/media-upload.html'),str(reserve),str(port)],stdin=subprocess.PIPE,stdout=subprocess.PIPE,text=True)
        self.url,self.pin=self.proc.stdout.readline().strip().split()
        self.host=self.url.removeprefix('http://');self.port=int(self.host.split(':')[1]);self.cookie='';self.csrf=''
    def request(self, method, path, body=b'', headers=None, auth=True):
        base={'Host':self.host}
        if method=='POST':base.update({'Origin':self.url,'X-CC3-Upload':'1'})
        if auth:base.update({'Cookie':self.cookie,'X-CC3-CSRF':self.csrf})
        base.update(headers or {})
        connection=http.client.HTTPConnection('127.0.0.1',self.port,timeout=5)
        connection.request(method,path,body if method=='POST' else None,base)
        response=connection.getresponse();result=(response.status,dict(response.getheaders()),response.read());connection.close();return result
    def pair(self):
        status,headers,body=self.request('POST','/api/pair',self.pin.encode(),auth=False)
        check(status==200,'pair succeeds');self.cookie=headers['Set-Cookie'].split(';')[0];self.csrf=json.loads(body)['csrf'];return headers
    def raw(self,request):
        with socket.create_connection(('127.0.0.1',self.port),timeout=5) as connection:
            connection.sendall(request);connection.shutdown(socket.SHUT_WR);data=b''
            while True:
                part=connection.recv(65536)
                if not part:break
                data+=part
        return int(data.split(b' ')[1])
    def close(self):
        if self.proc.poll() is None:self.proc.communicate('stop\n',timeout=5)
def staged(folder):return list((folder/'.incoming').glob('*'))
with tempfile.TemporaryDirectory(prefix='cc3-media-http-') as temporary:
    temporary=pathlib.Path(temporary);classes=temporary/'classes';classes.mkdir()
    sources=ROOT/'app/src/main/java/local/cc3/manager'
    subprocess.run([str(JAVA/'javac'),'--release','8','-d',str(classes),str(sources/'MediaFileStore.java'),str(sources/'MediaUploadServer.java'),str(sources/'TlsCertificate.java'),str(ROOT/'tests/java/local/cc3/manager/MediaUploadHost.java')],check=True,capture_output=True)
    folder=temporary/'library';server=Server(classes,folder)
    try:
        code,headers,page=server.request('GET','/',auth=False)
        check(code==200 and b'Good music.' in page,'standalone upload page served')
        check('frame-ancestors' in headers['Content-Security-Policy'] and headers['Cache-Control']=='no-store','page boundary headers')
        check(server.request('GET','/api/status',auth=False)[0]==401,'no anonymous status')
        check(server.request('POST','/api/upload?name=a.mp3',b'abc',auth=False)[0]==401,'no anonymous write')
        check(server.request('POST','/api/pair',b'123',{'Origin':'http://evil.test'},auth=False)[0]==403,'cross-origin pair rejected')
        check(server.request('POST','/api/pair',b'xxxxxxxx',auth=False)[0]==401,'wrong pairing code rejected')
        pair_headers=server.pair()
        check('HttpOnly' in pair_headers['Set-Cookie'] and 'SameSite=Strict' in pair_headers['Set-Cookie'],'session cookie restricted')
        check(server.request('GET','/api/status')[0]==200,'paired status')
        check(server.request('GET','/api/status',headers={'Host':'evil.test'})[0]==403,'host rebinding rejected')
        check(server.request('POST','/api/upload?name=a.mp3',b'abc',{'X-CC3-CSRF':'bad'})[0]==403,'CSRF token required')
        check(server.request('POST','/api/upload?name=a.mp3',b'abc',{'Origin':'http://evil.test'})[0]==403,'cross-origin upload rejected')
        for name in ['..%2Fescape.mp3','%2Ftmp%2Fescape.mp3','..%5Cescape.mp3','.hidden.mp3','bad%00.mp3','bad%0D%0A.mp3','%C0%AF.mp3','bad%.mp3']:
            check(server.request('POST','/api/upload?name='+name,b'abc')[0]==400,'reject unsafe file name '+name)
        check(server.request('POST','/api/upload?name=bad.apk',b'abc')[0]==415,'non-media extension rejected')
        check(server.request('POST','/api/upload?name=empty.mp3',b'')[0]==400,'empty file rejected')
        data=bytes(range(256))*32768 # 8 MiB; process has 48 MiB heap.
        code,_,body=server.request('POST','/api/upload?name=Music%20%C3%A9.mp3',data)
        check(code==201 and json.loads(body)['bytes']==len(data),'large streaming transfer completes')
        saved=folder/'Music é.mp3';check(hashlib.sha256(saved.read_bytes()).digest()==hashlib.sha256(data).digest(),'saved bytes match input')
        check(server.request('POST','/api/upload?name=Music%20%C3%A9.mp3',b'second')[0]==201,'duplicate saved separately')
        check(saved.stat().st_size==len(data) and (folder/'Music é (2).mp3').read_bytes()==b'second','existing file preserved')
        prefix=f'POST /api/upload?name=cut.mp3 HTTP/1.1\r\nHost: {server.host}\r\nOrigin: {server.url}\r\nX-CC3-Upload: 1\r\nCookie: {server.cookie}\r\nX-CC3-CSRF: {server.csrf}\r\nContent-Length: 100000\r\n\r\n'
        connection=socket.create_connection(('127.0.0.1',server.port),timeout=5);connection.sendall(prefix.encode()+b'partial');connection.close()
        time.sleep(.25);check(not (folder/'cut.mp3').exists() and not staged(folder),'disconnect removes partial file')
        base=f'POST /api/pair HTTP/1.1\r\nHost: {server.host}\r\nOrigin: {server.url}\r\nX-CC3-Upload: 1\r\n'
        check(server.raw((base+'Content-Length: 8\r\nContent-Length: 8\r\n\r\n'+server.pin).encode())==400,'duplicate content length rejected')
        check(server.raw((base+'Transfer-Encoding: chunked\r\nContent-Length: 8\r\n\r\n'+server.pin).encode())==400,'TE/CL ambiguity rejected')
        check(server.raw((base+'\r\n').encode())==411,'known file length required')
        check(server.raw((base+'Content-Length: 17179869185\r\n\r\n').replace('/api/pair','/api/upload?name=huge.mp4').replace('X-CC3-Upload: 1\r\n',f'X-CC3-Upload: 1\r\nCookie: {server.cookie}\r\nX-CC3-CSRF: {server.csrf}\r\n').encode())==413,'16 GiB cap checked before reading body')
        # Stalled transfer cannot admit a second file; Stop cancels both socket and staging.
        held=socket.create_connection(('127.0.0.1',server.port),timeout=5);held.sendall(prefix.encode()+b'partial');time.sleep(.15)
        check(server.request('POST','/api/upload?name=other.mp3',b'other')[0]==409,'single active file enforced')
        old_port=server.port;server.close();held.close();time.sleep(.1);check(not staged(folder),'stop cancels and removes staging')
        restarted=Server(classes,folder,port=old_port)
        try:
            check(restarted.request('GET','/')[0]==200,'immediate restart reuses the same port')
            check(restarted.request('GET','/api/status',headers={'Cookie':server.cookie})[0]==401,'restart invalidates prior session')
        finally:restarted.close()
    finally:server.close()
    low=Server(classes,temporary/'low-space',9223372036854775807)
    try:
        low.pair();check(low.request('POST','/api/upload?name=a.mp3',b'abc')[0]==507,'storage reserve enforced before write')
    finally:low.close()
    expired=Server(classes,temporary/'expired')
    try:
        expired.pair();expired.proc.stdin.write('advance 600001\n');expired.proc.stdin.flush();time.sleep(.1)
        try:status=expired.request('GET','/api/status')[0]
        except (OSError,http.client.HTTPException):status=410
        check(status==410,'idle session expires even when browser polls status')
    finally:expired.close()
    limited=Server(classes,temporary/'limited')
    try:
        for _ in range(10):check(limited.request('POST','/api/pair',b'xxxxxxxx',auth=False)[0]==401,'incorrect PIN bounded')
        check(limited.request('POST','/api/pair',limited.pin.encode(),auth=False)[0]==429,'pair brute-force rate limited')
    finally:limited.close()
print(f'{checks} media transfer checks passed')
