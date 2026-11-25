# Historical pre-0.5 helper; current acceptance uses test-obd-modules-ui.py.
from pathlib import Path
exec(Path(__file__).with_name('test-media-native.py').read_text().split("if __name__")[0])
OUT=ROOT/'build/qa-0.4.1'
assert adb('shell','getprop','ro.kernel.qemu')=='1', 'DPF UI tests require the isolated emulator'
def state():return json.loads(adb('shell','cat','/data/user/0/local.cc3.manager/files/obd-qa-ui.json'))
def nodes():return state()['nodes']
def scroll_to(label,limit=12):
 for n in range(limit):
  matches=[x for x in nodes() if label in (x.get('text','')+' '+x.get('contentDesc','')) and x.get('enabled')]
  if matches:return
  scrolls=[x for x in nodes() if x['class'].endswith('ScrollView')];r=scrolls[-1]['visibleBounds'];x=(r[0]+r[2])//2;adb('shell','input','swipe',str(x),str(r[3]-30),str(x),str(r[1]+30),'250');time.sleep(.5)
 raise AssertionError('Cannot scroll to '+label)