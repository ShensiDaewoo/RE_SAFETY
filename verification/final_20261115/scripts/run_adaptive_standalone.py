"""adaptive build as the stand-alone prototype (generic Android, no vehicle): the three scenes and every transition of the scene controller,
driven by the page's own simulation switches. Scene B here is a labelled place-holder (no Pleos system screen exists on this device).
  A READY -> (주행 시작) -> B DRIVING -> (주차 완료) -> A
  A -> B -> (사고 이벤트) -> ACCIDENT_PENDING -> (정차 확인) -> C ACCIDENT_RESPONSE -> (시뮬레이션 종료) -> A
  A -> (시뮬레이션) -> C directly while parked
  python run_adaptive_standalone.py <apk>"""
import hashlib, json, re, subprocess, sys, time
from pathlib import Path

ADB = r'C:\Android\Sdk\platform-tools\adb.exe'
PKG = 'com.contest.resafety.fullhmi.aurora'
ACT = PKG + '/com.contest.resafety.fullhmi.MainActivity'
APK = Path(sys.argv[1])
RUN = Path(__file__).resolve().parent / ('adaptive_standalone_' + time.strftime('%Y%m%d_%H%M%S'))
RUN.mkdir(parents=True, exist_ok=True)
notes, res, steps = [], {}, []


def note(s):
    print(s, flush=True); notes.append(s)


def adb(*a, timeout=120):
    return subprocess.run([ADB, *a], capture_output=True, timeout=timeout).stdout.decode('utf-8', 'replace')


def page():
    st, rects = None, {}
    for l in adb('logcat', '-d', '-v', 'raw', '-s', 'FullHMI:I').splitlines():
        if l.startswith('STATE '):
            try: st = json.loads(l[6:]); rects = {}
            except Exception: pass
        elif l.startswith('RECT '):
            p = l.split()
            if len(p) == 6: rects[p[1]] = tuple(int(x) for x in p[2:])
    return st, rects


def tap(key, wait=2.2):
    for _ in range(4):
        st, rects = page()
        if key in rects:
            x, y, w, h = rects[key]; adb('shell', 'input', 'tap', str(x + w // 2), str(y + h // 2)); time.sleep(wait); return True
        time.sleep(1)
    note(f'NOT FOUND {key}'); return False


def step(name, expect):
    adb('shell', 'screencap', '-p', '/sdcard/as.png'); adb('pull', '/sdcard/as.png', str(RUN / (name + '.png')))
    st, rects = page(); got = (st or {}).get('scene')
    steps.append({'step': name, 'expected_scene': expect, 'scene': got, 'ok': got == expect, 'hmi': (st or {}).get('hmi'), 'layout': (st or {}).get('layout'),
                  'placeholder_shown': 'dnavi' in rects, 'aurora_home_keys': sorted(k for k in rects if k in ('cpre', 'cseat', 'cseatctl', 'crec', 'b119', 'bsos', 'cprot', 'cguide'))})
    note(f"{name}: scene={got} (expected {expect}) placeholder={'dnavi' in rects}")


sha = hashlib.sha256(APK.read_bytes()).hexdigest().upper(); note(f'apk {APK.name} {sha}')
adb('shell', 'am', 'force-stop', PKG); adb('uninstall', PKG)
note('install: ' + (adb('install', str(APK), timeout=300).strip().splitlines() or ['?'])[-1])
inst = adb('shell', 'pm', 'path', PKG).replace('package:', '').strip()
if adb('shell', 'sha256sum', inst).split()[0].upper() != sha: note('STOP: installed APK differs'); sys.exit(2)
res['automotive_feature'] = 'automotive' in adb('shell', 'pm', 'list', 'features')
adb('logcat', '-G', '16M'); adb('logcat', '-c'); adb('shell', 'am', 'start', '-n', ACT); time.sleep(9)

step('s01_A_ready', 'READY')
tap('lgo'); step('s02_B_driving_placeholder', 'DRIVING')
tap('dpark'); step('s03_A_ready_after_parking', 'READY')
tap('lgo'); tap('dacc'); step('s04_accident_pending_while_driving', 'ACCIDENT_PENDING')
tap('dstop'); step('s05_C_accident_response_after_stop', 'ACCIDENT_RESPONSE'); time.sleep(4.6)   # adaptive7+: WARNING (4 s) takes the first tap
tap('accOff', 3); step('s06_A_ready_after_response_end', 'READY')
tap('accOn', 3); step('s07_C_accident_response_when_parked', 'ACCIDENT_RESPONSE'); time.sleep(4.6)
tap('accOff', 3); step('s08_A_ready_end', 'READY')
L = [l for l in adb('logcat', '-d', '-v', 'time', '-s', 'FullHMI:I').splitlines() if 'SCENE ' in l and '->' in l or 'PAGE_ERROR' in l]
(RUN / 'logcat_scene.txt').write_text('\n'.join(L), encoding='utf-8')
res.update({'apk': APK.name, 'sha256': sha, 'steps': steps, 'all_ok': all(s['ok'] for s in steps), 'transitions': [re.sub(r'.*?SCENE (\S+ -> \S+).*', r'\1', l) for l in L if 'SCENE ' in l],
            'page_errors': sum('PAGE_ERROR' in l for l in L), 'notes': notes})
(RUN / 'results.json').write_text(json.dumps(res, ensure_ascii=False, indent=1), encoding='utf-8')
print('\nall_ok', res['all_ok'], '| transitions', res['transitions'], '| page errors', res['page_errors'], '|', RUN)
