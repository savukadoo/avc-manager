#!/usr/bin/env python3
"""Emulator-only acceptance driver. Start EntertainmentUiHarness and generate synthetic fixtures first."""
import subprocess,json,time
from pathlib import Path
ADB='/private/tmp/cc3-build-tools/platform-tools/platform-tools/adb';ROOT=Path(__file__).resolve().parents[2];OUT=ROOT/'build/qa-0.4.0'
def adb(*args,raw=False):
 r=subprocess.run([ADB,'-s','emulator-5558',*args],capture_output=True,timeout=40)
 if r.returncode:raise RuntimeError(r.stderr.decode()+r.stdout.decode())
 return r.stdout if raw else r.stdout.decode().strip()
def state():return json.loads(adb('shell','cat','/data/user/0/local.cc3.manager/files/mediaqa-ui.json'))
def nodes():return state()['nodes']
def tap(label,contains=False):
 n=next((n for n in nodes() if n['enabled'] and (label in n['text'] or label in n['contentDesc'] if contains else label==n['text'] or label==n['contentDesc'])),None)
 if n is None:raise AssertionError('Missing '+label+' '+str([(n['text'],n['contentDesc']) for n in nodes()]))
 a,b,c,d=n['visibleBounds'];adb('shell','input','tap',str((a+c)//2),str((b+d)//2));time.sleep(.8)
def capture(name):
 s=state();(OUT/(name+'.json')).write_text(json.dumps(s,indent=2));(OUT/(name+'.png')).write_bytes(adb('exec-out','screencap','-p',raw=True));return s
def wait(predicate,seconds=12):
 deadline=time.monotonic()+seconds
 while time.monotonic()<deadline:
  s=state()
  if predicate(s):return s
  time.sleep(.5)
 raise AssertionError('State timeout: '+str(s['playback'])+' '+str(s['transfer']))
if __name__=='__main__':
 checks=[]
 def check(value,label):
  assert value,label
  checks.append(label)
  print('PASS',label,flush=True)
 s=wait(lambda s:any(n['text']=='Refresh' for n in s['nodes']))
 check(s['width_dp']==1280 and any(n['bounds']==[0,60,1280,720] for n in s['nodes']) and s['density_dpi']==160 and s['font_scale']==1.5,'CC3 1280×720,60px system area,160dpi,font1.5')
 capture('entertainment-music-ready')
 for name in ['01 Evening drive','02 Coastal route','03 Night skyline','05 Morning light']:
  if not any(name in n['text'] for n in nodes()):adb('shell','input','swipe','900','600','900','380','350');time.sleep(.7)
  tap(name,True);s=wait(lambda s:s['playback']['playing'] or s['playback']['error'])
  check(s['playback']['playing'] and not s['playback']['error'],'Decode '+name)
  start=s['playback']['position'];time.sleep(1.5);check(state()['playback']['position']>start,'Position advances '+name)
  capture('play-'+name.split(' ')[0]);tap('Pause');check(not state()['playback']['playing'],'Pause '+name)
 # Queue modes, seeking, background audio and explicit Stop.
 tap('Shuffle');check(state()['playback']['shuffle'],'Shuffle selected')
 tap('Repeat: Off');check(state()['playback']['repeat']=='All','Repeat all selected')
 tap('Repeat: All');check(state()['playback']['repeat']=='One','Repeat one selected')
 tap('Repeat: One');check(state()['playback']['repeat']=='Off','Repeat off')
 tap('Play');n=next(n for n in nodes() if n['contentDesc']=='Playback position');x1,y1,x2,y2=n['visibleBounds'];adb('shell','input','tap',str(x1+(x2-x1)*2//3),str((y1+y2)//2));time.sleep(1)
 check(state()['playback']['position']>15000,'Seek changes real playback position')
 tap('Controls');check(state()['playback']['playing'],'Music continues in Controls');capture('background-audio-controls')
 tap('Entertainment');tap('Stop');check(not state()['playback']['playing'] and not state()['playback']['preparing'],'Stop ends playback')
 # An invalid extension-qualified file must expose an honest decoder error.
 adb('shell','input','swipe','900','610','900','360','350');time.sleep(.7);tap('99 Unsupported demo',True)
 s=wait(lambda s:s['playback']['error']);check('Cannot play' in s['playback']['message'] or 'could not' in s['playback']['message'],'Damaged media gives clear playback error');capture('unsupported-media-error')
 tap('Video');tap('06 Coastal motion',True);s=wait(lambda s:s['playback']['playing'] or s['playback']['error'])
 check(s['playback']['playing'] and s['playback']['videoWidth']==640 and s['playback']['videoHeight']==360,'H264 MP4 real decode and surface');capture('entertainment-video-playing')
 tap('Controls');check(not state()['playback']['playing'],'Video pauses on page exit')
 tap('Entertainment');tap('Upload');capture('laptop-uploads-off');tap('Start uploads');s=wait(lambda s:s['transfer']['active'] or s['transfer']['message'].startswith('Cannot'))
 check(s['transfer']['active'] and s['transfer']['url'].startswith('http://'),'Wi-Fi upload listener starts with pairing code');capture('laptop-uploads-on')
 tap('Stop uploads');s=wait(lambda s:not s['transfer']['active']);check(not s['transfer']['busy'],'Stop uploads shuts listener');capture('laptop-uploads-stopped')
 (OUT/'native-checks.json').write_text(json.dumps(checks,indent=2));print(len(checks),'native checks passed')
