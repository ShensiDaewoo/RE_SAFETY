"""R2D SUBMISSION APK (unchanged, sha256 DCBAF29B...) on the Pleos Connect emulator: press 보호 모드 and 되돌리기, and compare what the app reports
with what the Pleos Virtual Console says about the car at the same moments. Same method as the D build check of 2026-11-12
(signal-probe/evidence/d/console-crosscheck.txt). Reads only; the APK and the submission ZIP are not touched.
Needs: Pleos_Connect_v2_1 running, adb forward tcp:8088 tcp:8088, the console page open and VISIBLE in a browser.
  python run_protect_crosscheck.py"""
import json, re, subprocess, time, urllib.request
from pathlib import Path

ADB = r'C:\Android\Sdk\platform-tools\adb.exe'
PKG = 'com.contest.signalprobe.r2d'
ACT = PKG + '/com.contest.signalprobe.ProductActivity'
BASE = 'http://127.0.0.1:8088'
RUN = Path(__file__).resolve().parent / ('run_' + time.strftime('%Y%m%d_%H%M%S'))
RUN.mkdir(parents=True, exist_ok=True)
notes = []


def note(s):
    print(s, flush=True); notes.append(s)


def adb(*a, binary=False, timeout=60):
    r = subprocess.run([ADB, *a], capture_output=True, timeout=timeout)
    return r.stdout if binary else r.stdout.decode('utf-8', 'replace')


def put(path):
    try:
        req = urllib.request.Request(BASE + path, method='PUT')
        return urllib.request.urlopen(req, timeout=8).status
    except Exception as e:
        return getattr(e, 'code', -1)


def car():
    j = json.loads(urllib.request.urlopen(BASE + '/vehicle/state', timeout=8).read().decode('utf-8'))
    b, l = j['body'], j.get('lights', {})
    hm = l.get('headlamp_mode')
    if isinstance(hm, dict):
        hm = hm.get('value', hm.get('mode', json.dumps(hm, ensure_ascii=False)))
    return {'gear': j['gear']['actual']['value'], 'speed': j['controls']['speed_kph'],
            'locks': [b['door_locks'][k] for k in ('front_left', 'front_right', 'rear_left', 'rear_right')],
            'cabin': b['cabin_light']['on'], 'headlamp_mode': hm, 'web_active': j.get('web_active')}


def shot(name):
    (RUN / (name + '.png')).write_bytes(adb('exec-out', 'screencap', '-p', binary=True))


def ui():
    adb('shell', 'uiautomator', 'dump', '/sdcard/u.xml')
    return adb('shell', 'cat', '/sdcard/u.xml')


def find(text, exact=False):
    x = ui()
    pat = r'text="' + re.escape(text) + (r'"' if exact else r'[^"]*"') + r'[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'
    m = re.search(pat, x)
    return ((int(m.group(1)) + int(m.group(3))) // 2, (int(m.group(2)) + int(m.group(4))) // 2) if m else None


def tap_text(text, exact=False, tries=4):
    for _ in range(tries):
        p = find(text, exact)
        if p:
            adb('shell', 'input', 'tap', str(p[0]), str(p[1])); note(f'tapped "{text}" at {p}'); return True
        time.sleep(1)
    note(f'NOT FOUND "{text}"'); return False


def app_log():
    return [l for l in adb('logcat', '-d', '-v', 'time', '-s', 'Accident10:I', 'Accident10Port:I', 'Accident10Hub:I').splitlines() if l.strip()]


# 1 the car at rest: P, 0 km/h, doors locked, cabin light off, exterior lights off
st = {p: put(p) for p in ('/vehicle/controls?speed=0&accelerator=0&brake=0', '/vehicle/gear?gear=Park', '/vehicle/body?cabin_light=false',
                          '/vehicle/lights?feature=headlamp_mode&value=Off', '/vehicle/body?doors_locked=true')}
note('console PUT status: ' + json.dumps(st)); time.sleep(4)
# 2 only the app under test runs (memory: a second test app behind it steals focus)
for other in ('com.contest.signalprobe.r2d.labhard', 'com.contest.signalprobe.r2d.lab', 'com.contest.signalprobe.fig', 'com.contest.signalprobe.sosdemo'):
    adb('shell', 'am', 'force-stop', other)
adb('shell', 'am', 'force-stop', PKG); time.sleep(1.5); adb('logcat', '-c')
apk = adb('shell', 'pm', 'path', '--user', '10', PKG).replace('package:', '').strip()
note('installed apk sha256: ' + adb('shell', 'sha256sum', apk).split()[0].upper())
adb('shell', 'am', 'start', '--user', '10', '-n', ACT); time.sleep(7)
for allow in ('앱 사용 중에만 허용', '허용'):
    if find(allow, exact=True):
        tap_text(allow, exact=True); time.sleep(2)
shot('c00_home')
# 3 protect mode screen, then ON, then UNDO, reading the console each time
tap_text('보호 모드'); time.sleep(3); shot('c01_protect_screen')
before = car(); note('before        ' + json.dumps(before, ensure_ascii=False))
tap_text('보호 모드 켜기', exact=True); time.sleep(8); shot('c02_after_protect')
after = car(); note('after-protect ' + json.dumps(after, ensure_ascii=False))
tap_text('되돌리기'); time.sleep(8); shot('c03_after_undo')
undo = car(); note('after-undo    ' + json.dumps(undo, ensure_ascii=False))
logs = app_log(); (RUN / 'app_logcat.txt').write_text('\n'.join(logs), encoding='utf-8')
pr = [l for l in logs if 'PROTECT_RESULT' in l or 'PROTECT ' in l or 'RESTORE ' in l]
for l in pr[-12:]:
    note('app: ' + l[:240])
ok_on = after['locks'] == [False] * 4 and after['cabin'] is True and str(after['headlamp_mode']) == 'Position'
ok_undo = undo['locks'] == before['locks'] and undo['cabin'] == before['cabin'] and undo['headlamp_mode'] == before['headlamp_mode']
res = {'apk_package': PKG, 'before': before, 'after_protect': after, 'after_undo': undo,
       'protect_changed_the_car_as_requested': ok_on, 'undo_restored_the_car': ok_undo, 'app_result_lines': pr[-12:], 'notes': notes}
(RUN / 'results.json').write_text(json.dumps(res, ensure_ascii=False, indent=1), encoding='utf-8')
print('\nPROTECT ->', 'PASS' if ok_on else 'FAIL', '| UNDO ->', 'PASS' if ok_undo else 'FAIL', '|', RUN)
