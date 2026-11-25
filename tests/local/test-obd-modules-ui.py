#!/usr/bin/env python3
"""Emulator-only native profile/category acceptance; real engine decodes local synthetic replies."""
from pathlib import Path
import subprocess,json,time,csv,io,os,xml.etree.ElementTree as E
ROOT=Path(__file__).resolve().parents[2];OUT=Path(os.environ.get('CC3_QA_OUTPUT',str(ROOT/'build/qa-0.5.0')))
ADB=os.environ.get('CC3_QA_ADB','/private/tmp/cc3-build-tools/platform-tools/platform-tools/adb')
SERIAL=os.environ.get('CC3_QA_SERIAL','emulator-5558')
def adb(*args,raw=False):
 r=subprocess.run([ADB,'-s',SERIAL,*args],capture_output=True,timeout=40)
 if r.returncode:raise RuntimeError(r.stdout.decode()+r.stderr.decode())
 return r.stdout if raw else r.stdout.decode().strip()
def state():return json.loads(adb('shell','cat','/data/user/0/local.cc3.manager/files/obd-qa-ui.json'))
def nodes():return state()['nodes']
def has(s,label):return any(n['text']==label or n['contentDesc']==label for n in s['nodes'])
def wait(pred,seconds=25):
 end=time.monotonic()+seconds
 while time.monotonic()<end:
  s=state()
  if pred(s):return s
  time.sleep(.3)
 raise AssertionError('Native state timeout: '+str([(n['text'],n['contentDesc']) for n in s['nodes']]))
def tap(label,contains=False):
 s=state();matches=[n for n in s['nodes'] if n['enabled'] and ((label in n['text'] or label in n['contentDesc']) if contains else(label==n['text'] or label==n['contentDesc']))]
 if not matches:raise AssertionError('Missing '+label)
 n=next((n for n in matches if n['clickable']),matches[0])
 while not n['clickable'] and n['parent']>=0:n=s['nodes'][n['parent']]
 assert n['clickable'],label
 a,b,c,d=n['visibleBounds'];adb('shell','input','tap',str((a+c)//2),str((b+d)//2));time.sleep(.65)
def capture(name):
 s=state();(OUT/(name+'.json')).write_text(json.dumps(s,indent=2));(OUT/(name+'.png')).write_bytes(adb('exec-out','screencap','-p',raw=True));return s
checks=[]
def check(ok,label):
 assert ok,label
 checks.append(label);print('PASS',label,flush=True)
 (OUT/'native-profile-checks.json').write_text(json.dumps({'simulated':True,'checks':checks,'count':len(checks),'complete':False},indent=2)+'\n')
def related(label):tap(label+',',True)
def monitoring():
 candidates=[n for n in nodes() if n['text']=='Vehicle' and n['clickable']]
 n=max(candidates,key=lambda item:item['bounds'][0]);a,b,c,d=n['visibleBounds']
 adb('shell','input','tap',str((a+c)//2),str((b+d)//2));time.sleep(.65)
def settings():
 s=state();n=next(n for n in s['nodes'] if n['clickable'] and n['text'].endswith('  ›'))
 a,b,c,d=n['visibleBounds'];adb('shell','input','tap',str((a+c)//2),str((b+d)//2));time.sleep(.65)
def fits(s):
 return all(n['bounds'][0]>=0 and n['bounds'][1]>=60 and n['bounds'][2]<=1280 and n['bounds'][3]<=720 and n['bounds']==n['visibleBounds'] for n in s['nodes'] if n['clickable'] and n['enabled'])
if __name__=='__main__':
 assert adb('shell','getprop','ro.kernel.qemu')=='1'
 OUT.mkdir(parents=True,exist_ok=True)
 s=wait(lambda s:any(n['class'].endswith('ObdGaugeView') for n in s['nodes']))
 check(len([n for n in s['nodes'] if n['class'].endswith('ObdGaugeView')])==9,'Nine migrated dashboard cards retained')
 check(any(n['bounds']==[0,60,1280,720] for n in s['nodes']),'Correct CC3 usable screen bounds')
 capture('migrated-nine-card-dashboard')
 tap('Sensors');wait(lambda s:any('34 available channels' in n['text'] for n in s['nodes']),45)
 check(any('Toyota 1GD' in n['text'] for n in nodes()),'Selected vehicle profile shown')
 capture('toyota-sensors')
 tap('DPF checks');wait(lambda s:any('12 responding' in n['text'] for n in s['nodes']))
 check(not any(n['text'].lower().startswith(('oil temperature','regen','service regen','observed starts')) for n in nodes()),'Removed oil and regeneration rows absent')
 capture('dpf-without-regen');tap('Save report')
 folder='/sdcard/Android/data/local.cc3.manager/files/vehicle-reports'
 filename=sorted(n for n in adb('shell','ls',folder).splitlines() if n.startswith('cc3-vehicle-') and n.endswith('.csv'))[-1]
 report=adb('exec-out','cat',folder+'/'+filename,raw=True);(OUT/filename).write_bytes(report);rows=list(csv.DictReader(io.StringIO(report.decode())))
 check(any('toyota-1gd-130kw' in r['quality'] for r in rows if r['record_type']=='session_summary'),'Report identifies selected profile')
 check(not any(r['sensor'] in ['oil_temp','toyota_oil_temp','dpf_regen','dpf_regen_type','dpf_service_regen','dpf_trigger','dpf_avg_time','dpf_avg_distance'] for r in rows),'Removed oil and regen readings absent from export')
 check(all(any(r['sensor']==id and r['record_type']=='sensor' for r in rows) for id in ['fuel_rail_actual','fuel_rail_target','injector_1','injector_2','injector_3','injector_4']),'Six enhanced fuel/injector readings exported')
 check(any('U16(D,E)' in r['quality'] for r in rows if r['sensor']=='fuel_rail_actual' and r['record_type']=='sensor_check'),'New field definition and raw evidence exported')
 tap('‹ Sensors');tap('Dashboard');tap('Configure');tap('1 ·',True)
 check(any('Fuel rail' in n['text'] for n in nodes()) and any('Injectors' in n['text'] for n in nodes()),'Fuel rail and Injectors offered as single categories')
 capture('category-picker');tap('Fuel rail',True);tap('2 ·',True);tap('Injectors',True);tap('Done')
 s=capture('fuel-injector-dashboard');check(fits(s),'Nine-card dashboard controls fit without clipping')
 check(any('Fuel rail · Actual' in n['contentDesc'] for n in s['nodes']),'Rail card defaults to actual pressure')
 check(any('Injectors · Cylinder 1' in n['contentDesc'] for n in s['nodes']),'Injector card defaults to first cylinder')
 tap('Fuel rail · Actual',True);s=capture('fuel-rail-graph');check(has(s,'33.0') and has(s,'MPa'),'Actual fuel-rail value and unit shown')
 related('Fuel rail target');s=capture('fuel-rail-target');check(has(s,'35.0'),'Related target pressure selects its graph')
 check(any(n['text']=='Use on card' and n['enabled'] for n in s['nodes']),'Explicit main-stat action available')
 tap('Use on card');check(any(n['text']=='On card ✓' and n['selected'] for n in nodes()),'Chosen rail main stat visibly selected');tap('Close')
 check(any('Fuel rail · Target' in n['contentDesc'] for n in nodes()),'Chosen target retained as dashboard primary')
 tap('Injectors · Cylinder 1',True);s=capture('injector-related-readings')
 check(sum(1 for n in s['nodes'] if n['clickable'] and n['class'].endswith('LinearLayout') and n['contentDesc'].startswith('Injector correction'))==4,'All four related injector corrections selectable')
 related('Injector correction 3');s=capture('negative-injector-correction');check(has(s,'-1.25') and has(s,'mm³/stroke'),'Negative injector feedback shown with correct unit')
 check(fits(s),'Injector graph, related list and footer fit target screen')
 tap('Use on card');tap('Close');check(any('Injectors · Cylinder 3' in n['contentDesc'] for n in nodes()),'Selected injector persists as single card primary')
 settings();check(has(state(),'Vehicle settings') and has(state(),'Pause'),'Status badge opens adapter settings under Controls')
 check(state()['same_engine'] and state()['phase']=='MONITORING','Settings transition preserves the live engine and connection')
 tap('Choose profile');s=capture('vehicle-manufacturer-picker')
 check(has(s,'Choose manufacturer') and has(s,'Standard OBD-II')
       and any(n['text'].startswith('Toyota') and n['selected'] for n in s['nodes']),
       'Existing Toyota selection is marked alongside the standard profile')
 check(fits(s),'Manufacturer choices fit the CC3 viewport')
 tap('Toyota',True);s=capture('vehicle-model-picker')
 check(has(s,'Choose model') and all(any(n['text'].startswith(model) and n['clickable'] for n in s['nodes'])
       for model in ('Fortuner','Hilux','Prado')),'Toyota lists all three supported model paths')
 check(fits(s),'Model choices fit the CC3 viewport')
 tap('Fortuner',True);s=capture('vehicle-engine-picker')
 check(has(s,'Choose engine') and any(n['text'].startswith('2.8 L diesel') for n in s['nodes']),
       'Fortuner exposes its early 1GD engine')
 check(fits(s),'Engine details and navigation fit the CC3 viewport')
 adb('shell','input','keyevent','4');wait(lambda s:has(s,'Choose model'))
 tap('Back');wait(lambda s:has(s,'Choose manufacturer'))
 tap('Close');check(state()['phase']=='MONITORING','Browsing and going Back preserves the connection')
 tap('Choose profile');tap('Toyota',True);tap('Fortuner',True);tap('2.8 L diesel',True)
 s=wait(lambda s:any(n['text'].startswith('Toyota Fortuner\n2.8 L diesel') for n in s['nodes']))
 check(s['phase']=='DISCONNECTED','Confirming a model clears the previous session')
 capture('vehicle-fortuner-selected')
 tap('Choose profile');tap('Toyota',True)
 check(any(n['text'].startswith('Fortuner') and n['selected'] for n in nodes()),'The chosen model is selected')
 tap('Fortuner',True)
 check(any(n['text'].startswith('2.8 L diesel') and n['selected'] for n in nodes()),'The chosen engine is selected')
 tap('Back');tap('Back')
 tap('Standard OBD-II');s=wait(lambda s:any('Standard OBD-II' in n['text'] for n in s['nodes']))
 monitoring();s=capture('generic-profile-clears-toyota-values')
 check(not any('Toyota test' in n['contentDesc'] for n in s['nodes']),'Profile switch clears old Toyota values')
 tap('Configure');tap('1 ·',True);s=capture('generic-profile-sensor-picker')
 check(not any('Fuel rail' in n['text'] or 'Injectors' in n['text'] or 'Transmission pan' in n['text'] for n in s['nodes']),'Generic picker excludes manufacturer-specific categories and sensors')
 tap('Back');tap('Done');settings();tap('Choose profile');s=capture('generic-profile-selected')
 check(any(n['text'].startswith('Standard OBD-II') and n['selected'] for n in s['nodes']),'Generic profile selected state retained')
 tap('Close')
 (OUT/'native-profile-checks.json').write_text(json.dumps({'simulated':True,'checks':checks,'count':len(checks),'complete':True},indent=2)+'\n')
 print(len(checks),'native modular-profile checks passed',flush=True)
