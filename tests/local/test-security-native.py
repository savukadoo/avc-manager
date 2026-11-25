#!/usr/bin/env python3
"""Emulator-only native security UI and real HTTPS -> file -> MediaPlayer acceptance."""
from pathlib import Path
import subprocess,json,time,hashlib,importlib.util,socket,wave,math,struct,shlex
ROOT=Path(__file__).resolve().parents[2];OUT=ROOT/'build/qa-0.5.1';ADB='/private/tmp/cc3-build-tools/platform-tools/platform-tools/adb'
def adb(*args,raw=False):
 if args[0] in ('shell','exec-out') and len(args)>2:args=(args[0],shlex.join(args[1:]))
 r=subprocess.run([ADB,'-s','emulator-5558',*args],capture_output=True,timeout=45)
 if r.returncode:raise RuntimeError(r.stdout.decode()+r.stderr.decode())
 return r.stdout if raw else r.stdout.decode().strip()
def state():return json.loads(adb('exec-out','cat','/data/user/0/local.cc3.manager/files/mediaqa-ui.json'))
def wait(condition,seconds=25):
 end=time.monotonic()+seconds
 while time.monotonic()<end:
  s=state()
  if condition(s):return s
  time.sleep(.3)
 raise AssertionError('Native timeout '+str([n['text'] for n in s['nodes'] if n['text']]))
def tap(label,contains=False):
 s=state();matches=[n for n in s['nodes'] if n['enabled'] and ((label in n['text'] or label in n['contentDesc']) if contains else(label==n['text'] or label==n['contentDesc']))]
 if not matches:raise AssertionError('Missing '+label+' '+str([n['text'] for n in s['nodes'] if n['text']]))
 node=next((n for n in matches if n['clickable']),matches[0])
 # A ListView row can dispatch item clicks from a non-clickable TextView; tap its visible text.
 a,b,c,d=node['visibleBounds'];adb('shell','input','tap',str((a+c)//2),str((b+d)//2));time.sleep(.6)
def capture(name):
 s=state();(OUT/(name+'.json')).write_text(json.dumps(s,indent=2));(OUT/(name+'.png')).write_bytes(adb('exec-out','screencap','-p',raw=True));return s
def fits(s):return all(n['bounds']==n['visibleBounds'] and n['bounds'][0]>=0 and n['bounds'][1]>=60 and n['bounds'][2]<=1280 and n['bounds'][3]<=720 for n in s['nodes'] if n['clickable'] and n['enabled'])
checks=[]
def check(ok,label):
 assert ok,label
 checks.append(label);print('PASS',label,flush=True);(OUT/'native-security-checks.json').write_text(json.dumps({'physical_device_contacted':False,'checks':checks,'count':len(checks)},indent=2)+'\n')
if __name__=='__main__':
 assert adb('shell','getprop','ro.kernel.qemu')=='1'
 s=wait(lambda s:any(n['text']=='Enable for 15 minutes' for n in s['nodes']))
 baseline=json.loads((OUT/'baseline.json').read_text());baseline.setdefault('tls_key_existed',s['tls_key_present'])
 baseline.setdefault('activate_vpn_appop',adb('shell','appops','get','local.cc3.manager','ACTIVATE_VPN'))
 (OUT/'baseline.json').write_text(json.dumps(baseline,indent=2)+'\n')
 check(fits(capture('wireless-timed-access')),'Wireless controls fit measured CC3 viewport')
 tap('Enable for 15 minutes');s=capture('wireless-timed-confirmation')
 check(any('not encrypted' in n['text'] for n in s['nodes']) and any('15 minutes' in n['text'] for n in s['nodes']),'Debugging disclosure and expiry visible')
 check(fits(s),'Debugging confirmation controls fit');tap('Cancel')
 tap('Firewall');tap('Protection');s=capture('protection-defaults')
 check(not s['firewall']['firewall_persistent'] and not s['firewall']['firewall_block_unknown'],'New protection policies default off')
 check(fits(s),'Protection controls fit without clipping')
 tap('Close');adb('shell','appops','set','local.cc3.manager','ACTIVATE_VPN','allow')
 tap('Protection');tap('Keep protection running')
 wait(lambda s:s['firewall']['modes']&1 and s['firewall']['firewall_wanted'])
 check(state()['firewall']['firewall_persistent'],'Opt-in starts saved rule enforcement')
 tap('Close');capture('firewall-persistent-running')
 tap('Entertainment');tap('Upload');wait(lambda s:s['activity'].endswith('MediaTransferActivity'))
 tap('Start uploads');s=wait(lambda s:s['transfer']['active'],45);capture('https-uploads-ready')
 check(s['transfer']['url'].startswith('https://'),'Native service exposes HTTPS only')
 fingerprint=s['transfer']['certificate_sha256'];check(len(fingerprint)==95,'Full certificate fingerprint observed from real Android identity')
 tap('Verify secure connection');s=capture('https-certificate-verification');check(fits(s),'Certificate verification dialog fits');tap('Close')
 import runpy
 runpy.run_path(str(Path(__file__).with_name('test-native-https.py')),run_name='__main__')
