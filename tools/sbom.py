#!/usr/bin/env python3
"""Verify vendored source provenance and emit a small CycloneDX 1.6 software inventory."""
from pathlib import Path
import argparse,hashlib,json,re
ROOT=Path(__file__).resolve().parents[1]
COMMIT='4a53fbbbfc16ed012af676a871dd989f3583a5e6'
parser=argparse.ArgumentParser();parser.add_argument('--update-lock',action='store_true');parser.add_argument('--output',type=Path,default=ROOT/'build/cc3-manager-sbom.cdx.json');args=parser.parse_args()
files={str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted((ROOT/'third_party/zdtun').rglob('*')) if p.is_file()}
lock={'zdtun_base_commit':COMMIT,'patch_register':'third_party/zdtun/CC3_PATCHES.md','files_sha256':files}
lockfile=ROOT/'third-party-lock.json'
if args.update_lock:lockfile.write_text(json.dumps(lock,indent=2)+'\n')
elif not lockfile.exists() or json.loads(lockfile.read_text())!=lock:raise SystemExit('Vendored source changed: review patches before tools/sbom.py --update-lock')
components=[{'type':'library','bom-ref':'zdtun','name':'zdtun','version':COMMIT+'+cc3-patches','licenses':[{'license':{'id':'LGPL-3.0-or-later'}}],'externalReferences':[{'type':'vcs','url':'https://github.com/emanuele-f/zdtun/tree/'+COMMIT}],'properties':[{'name':'cc3:patch-register','value':lock['patch_register']}]},{'type':'library','bom-ref':'uthash','name':'uthash','version':'2.2.0','licenses':[{'license':{'id':'BSD-1-Clause'}}]}]
components.append({'type':'library','bom-ref':'fyt-adb','name':'Inherited FYT wireless ADB code','licenses':[{'license':{'id':'MIT'}}]})
for name,digest in files.items():components.append({'type':'file','bom-ref':name,'name':name,'hashes':[{'alg':'SHA-256','content':digest}]})
version_match = re.search(r'--version-name\s+([0-9]+(?:\.[0-9]+)+)\b', (ROOT / 'build.sh').read_text())
if not version_match:
    raise SystemExit('Cannot identify the APK version from build.sh')
sbom={'bomFormat':'CycloneDX','specVersion':'1.6','version':1,'metadata':{'component':{'type':'application','bom-ref':'cc3-manager','name':'AVC Manager','version':version_match.group(1),'licenses':[{'license':{'id':'AGPL-3.0-only'}}]},'properties':[{'name':'cc3:android-min-sdk','value':'29'},{'name':'cc3:scope','value':'Bundled software sources; platform firmware and image/data licences are documented separately in README.md'}]},'components':components,'dependencies':[{'ref':'cc3-manager','dependsOn':['zdtun','fyt-adb']},{'ref':'zdtun','dependsOn':['uthash',*files.keys()]}]}
args.output.parent.mkdir(parents=True,exist_ok=True);args.output.write_text(json.dumps(sbom,indent=2)+'\n');print('Vendored source lock verified; software inventory:',args.output)
