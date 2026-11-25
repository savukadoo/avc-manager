#!/usr/bin/env python3
"""Continue native HTTPS acceptance through the guest Wi-Fi address (QEMU's WLAN is .16)."""
from pathlib import Path
import importlib.util,json,hashlib,wave,math,struct,os,subprocess,time
ROOT=Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location('native_security',Path(__file__).with_name('test-security-native.py'));qa=importlib.util.module_from_spec(spec);spec.loader.exec_module(qa)
qa.checks.extend(json.loads((qa.OUT/'native-security-checks.json').read_text())['checks'])
assert qa.adb('shell','getprop','ro.kernel.qemu')=='1'
transfer=qa.wait(lambda s:s['transfer']['active'])['transfer'];fixture=qa.OUT/'SecurityQA local tone.wav'
with wave.open(str(fixture),'wb') as audio:
 audio.setnchannels(1);audio.setsampwidth(2);audio.setframerate(16000);audio.writeframes(b''.join(struct.pack('<h',int(600*math.sin(2*math.pi*440*i/16000))) for i in range(16000*15)))
remote_fixture='/data/local/tmp/SecurityQA-native-tone.wav';remote_config='/data/local/tmp/cc3-051-tls-probe.json';remote_probe='/data/local/tmp/cc3-051-tls-probe.jar'
config=qa.OUT/'tls-probe-private.json'
fd=os.open(config,os.O_WRONLY|os.O_CREAT|os.O_TRUNC,0o600)
with os.fdopen(fd,'w') as out:json.dump({'url':transfer['url'],'fingerprint':transfer['certificate_sha256'],'code':transfer['code'],'fixture':remote_fixture},out)
try:
 qa.adb('push',str(fixture),remote_fixture);qa.adb('push',str(config),remote_config);qa.adb('push',str(qa.OUT/'native-upload-probe.jar'),remote_probe)
 result=qa.adb('shell','CLASSPATH='+remote_probe+' app_process /system/bin cc3.qa.NativeUploadProbe '+remote_config)
 result=json.loads(next(line for line in reversed(result.splitlines()) if line.startswith('{')))
 qa.check(result['wrong_pin_rejected'],'Wrong Android certificate pin rejected')
 qa.check(result['paired'] and result['secure_cookie'],'Actual Android-keystore TLS pairing and Secure cookie work')
 qa.check(result['saved'] and result['bytes']==fixture.stat().st_size,'Pinned HTTPS upload through native Wi-Fi listener completes')
 remote='/sdcard/Android/data/local.cc3.manager/files/entertainment/'+result['name'];actual=qa.adb('exec-out','cat',remote,raw=True)
 qa.check(hashlib.sha256(actual).digest()==hashlib.sha256(fixture.read_bytes()).digest(),'Committed Android file matches upload SHA256')
 (qa.OUT/'uploaded-fixture.json').write_text(json.dumps({'path':remote,'sha256':hashlib.sha256(actual).hexdigest(),'name':result['name']},indent=2)+'\n')
 (qa.OUT/'native-tls-proof.json').write_text(json.dumps({**result,'method':'Native pin-verifying client to guest WLAN; laptop Python client separately tested against real Java TLS server','physical_laptop_wifi_tested':False},indent=2)+'\n')
finally:
 config.unlink(missing_ok=True)
 for path in [remote_config,remote_probe,remote_fixture]:qa.adb('shell','rm','-f',path)
qa.wait(lambda s:s['transfer']['completed']>=1);qa.capture('https-upload-saved');qa.tap('Stop uploads');qa.wait(lambda s:not s['transfer']['active']);qa.check(True,'Explicit Stop closes HTTPS listener')
qa.tap('‹ Entertainment');qa.wait(lambda s:s['activity'].endswith('MainActivity'))
search=next(n for n in qa.state()['nodes'] if n['contentDesc']=='Search music');a,b,c,d=search['visibleBounds'];qa.adb('shell','input','tap',str((a+c)//2),str((b+d)//2));qa.adb('shell','input','text','SecurityQA');qa.adb('shell','input','keyevent','4');time.sleep(.8)
qa.tap(Path(result['name']).stem,True);state=qa.wait(lambda s:s['playback']['playing'] or s['playback']['error'],30)
qa.check(state['playback']['playing'] and state['playback']['duration']>0,'Uploaded WAV decodes through real Android MediaPlayer');qa.capture('https-upload-playing');qa.tap('Pause');qa.wait(lambda s:not s['playback']['playing']);qa.tap('Firewall');qa.capture('persistent-firewall-after-upload')
print(len(qa.checks),'native security checks passed',flush=True)
