#!/usr/bin/env python3
"""Emulator-only Home/player acceptance, using real synthetic audio and native view observation.

Start EntertainmentUiHarness after backing up/setting the isolated CC3 display profile.
It reads its JSON observer output; no fabricated playback state is injected.
"""
import subprocess,json,time,os
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2];OUT=Path(os.environ.get('CC3_QA_OUTPUT',str(ROOT/'build/qa-0.4.3')));ADB='/private/tmp/cc3-build-tools/platform-tools/platform-tools/adb'
def adb(*args,raw=False):
 r=subprocess.run([ADB,'-s','emulator-5558',*args],capture_output=True,timeout=40)
 if r.returncode:raise RuntimeError(r.stderr.decode()+r.stdout.decode())
 return r.stdout if raw else r.stdout.decode().strip()
def state():return json.loads(adb('shell','cat','/data/user/0/local.cc3.manager/files/mediaqa-ui.json'))
def wait(pred,seconds=12):
 end=time.monotonic()+seconds
 while time.monotonic()<end:
  s=state()
  if pred(s):return s
  time.sleep(.3)
 raise AssertionError('State timeout: '+str(s.get('playback'))+' '+str([(n['text'],n['contentDesc']) for n in s['nodes']]))
def tap(label,contains=False):
 s=state();nodes=s['nodes']
 candidates=[n for n in nodes if n['enabled'] and ((label in n['text'] or label in n['contentDesc']) if contains else (label==n['text'] or label==n['contentDesc']))]
 if not candidates:raise AssertionError('Missing '+label)
 n=next((n for n in candidates if n['clickable']),candidates[0])
 while not n['clickable'] and n['parent']>=0:n=nodes[n['parent']]
 assert n['clickable'],label+' has no clickable parent'
 a,b,c,d=n['visibleBounds'];adb('shell','input','tap',str((a+c)//2),str((b+d)//2));time.sleep(.65)
def capture(name):
 s=state();(OUT/(name+'.json')).write_text(json.dumps(s,indent=2));(OUT/(name+'.png')).write_bytes(adb('exec-out','screencap','-p',raw=True));return s
checks=[]
def check(value,name):
 assert value,name
 checks.append(name);print('PASS',name,flush=True)
 (OUT/'home-playback-checks.json').write_text(json.dumps({'real_audio_decode':True,'weather_data':'synthetic emulator cache','checks':checks,'count':len(checks),'complete':False},indent=2)+'\n')
def text(s,value):return any(n['text']==value for n in s['nodes'])
def home(s):return s.get('window_class','').endswith('DashboardActivity')
def chip(s):return next((n for n in s['nodes'] if n['class'].endswith('HomeNowPlayingView')),None)
if __name__=='__main__':
 assert adb('shell','getprop','ro.kernel.qemu')=='1'
 wait(lambda s:text(s,'01 Evening drive'))
 tap('01 Evening drive',True);s=wait(lambda s:s['playback']['playing'] or s['playback']['error'])
 check(s['playback']['playing'] and not s['playback']['error'],'Real WAV playback starts')

 while state()['playback']['repeat']!='One':tap('Repeat: '+state()['playback']['repeat'])
 tap('Video');check(text(state(),'Search videos') or any('Search videos'==n['contentDesc'] for n in state()['nodes']),'Video browser selected while music continues')
 tap('AVC Manager · Open Home');s=wait(lambda s:home(s) and chip(s) and s['playback']['title'] in chip(s)['contentDesc'])
 check(chip(s) is not None and s['playback']['title'] in chip(s)['contentDesc'],'Brand opens Home with real current track')
 check('Now playing' in chip(s)['contentDesc'],'Home shows playing status')
 check(any(n['bounds']==[0,60,1280,720] for n in s['nodes']) and s['density_dpi']==160 and s['font_scale']==1.5,'Measured CC3 screen and large text profile')
 s=capture('clock-weather-now-playing')
 weather=next(n for n in s['nodes'] if n['class'].endswith('WeatherWidget'))
 credits=next(n for n in s['nodes'] if n['contentDesc'].startswith('Weather data by Open-Meteo'))
 check(credits['bounds'][2]==weather['bounds'][2] and credits['bounds'][1]==weather['bounds'][1],'Weather credits are top-right')
 check(credits['bounds'][2]-credits['bounds'][0]==48 and credits['bounds'][3]-credits['bounds'][1]==48,'Weather info has48dp touch target')
 tap('Weather data by Open-Meteo',True);s=wait(lambda s:text(s,'CC BY 4.0 licence'))
 check(text(s,'Open-Meteo') and text(s,'Close'),'Info opens provider/licence links and Close')
 capture('weather-data-info');tap('Close');wait(home)
 tap('Open music player',True);s=wait(lambda s:not home(s) and text(s,'Music'))
 check(any(n['text']=='Music' and n['selected'] for n in s['nodes']),'Now playing opens Music even after browsing Video')
 check(any(n['text']==s['playback']['title'] for n in s['nodes']) and s['playback']['playing'],'Player route preserves active track')
 tap('Pause');tap('AVC Manager · Open Home');s=wait(lambda s:home(s) and chip(s) and 'Paused' in chip(s)['contentDesc'])
 check(not s['playback']['playing'],'Home reflects actual paused state');capture('home-paused')
 tap('Open music player',True);wait(lambda s:not home(s));tap('Stop');tap('AVC Manager · Open Home');s=wait(lambda s:home(s) and not s['playback']['id'])
 check(chip(s) is None,'Stopped player removes Home playback shortcut');capture('home-stopped')
 tap('AVC Manager');wait(lambda s:text(s,'Full-screen preview'))
 # Restart real audio through Entertainment before checking all layout previews.
 tap('Entertainment');wait(lambda s:text(s,'01 Evening drive'));tap('01 Evening drive',True);wait(lambda s:s['playback']['playing'])
 while state()['playback']['repeat']!='One':tap('Repeat: '+state()['playback']['repeat'])
 tap('AVC Manager · Open Home');wait(home);tap('AVC Manager');wait(lambda s:text(s,'Full-screen preview'))
 layouts=[('Radio & Media','radio-media'),('Four-Panel Grid','four-panel'),('Maps & Side Dock','side-dock'),('Maps & Bottom Dock','bottom-dock'),('Large Shortcuts','large-shortcuts'),('Clock & Favourites','clock-favourites')]
 for title,name in layouts:
  tap(title);tap('Full-screen preview');s=wait(lambda s:s.get('window_class','').endswith('DashboardPreviewActivity'))
  check(text(s,title) and chip(s) is not None and any(n['contentDesc']=='All apps' or n['text']=='All apps' for n in s['nodes']),name+' has title, playback and All apps')
  check(not any(n['class'].endswith('ScrollView') and n['scrollable'] for n in s['nodes']),name+' has no scrolling container')
  key=[n for n in s['nodes'] if n['clickable'] and n['enabled']]
  check(all(n['bounds'][1]>=60 and n['bounds'][3]<=720 and n['bounds'][0]>=0 and n['bounds'][2]<=1280 and n['bounds']==n['visibleBounds'] for n in key),name+' controls fit visible screen')
  capture('preview-'+name)
  tap('‹  Back to Appearance');wait(lambda s:text(s,'Full-screen preview'))
 (OUT/'home-playback-checks.json').write_text(json.dumps({'real_audio_decode':True,'weather_data':'synthetic emulator cache','checks':checks,'count':len(checks),'complete':True},indent=2)+'\n')
 print(len(checks),'Home checks passed',flush=True)
