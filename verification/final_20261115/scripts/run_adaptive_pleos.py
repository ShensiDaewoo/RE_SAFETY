"""adaptive1 on the Pleos Connect emulator: the three scenes with REAL vehicle signals (gear and speed set on the Pleos Virtual Console).
  A  READY             parked            -> RE:SAFETY panel in the application window (no own vehicle scene, no own status strip)
  B  DRIVING           gear D, 30 km/h   -> Pleos native Driving View + Pleos navigation (handed over through NaviHelper)
     back to parked                      -> does the app come back to the front by itself? (recorded as it is)
  P  ACCIDENT_PENDING  a VIRTUAL accident event while driving -> no accident screen; spoken notice only
  C  ACCIDENT_RESPONSE parked again      -> RE:SAFETY accident panel; protect mode against the console once
The accident event is a simulation (intent extra). Nothing is called, nothing is sent.
  python run_adaptive_pleos.py <apk>"""
import base64, hashlib, json, re, subprocess, sys, time, urllib.request, uuid
from pathlib import Path

ADB = r'C:\Android\Sdk\platform-tools\adb.exe'
PKG = 'com.contest.resafety.fullhmi.aurora'
ACT = PKG + '/com.contest.resafety.fullhmi.MainActivity'
BASE = 'http://127.0.0.1:8088'
APK = Path(sys.argv[1])
RUN = Path(__file__).resolve().parent / ('adaptive_pleos_' + time.strftime('%Y%m%d_%H%M%S'))
RUN.mkdir(parents=True, exist_ok=True)
notes, res = [], {}


def note(s):
    print(s, flush=True); notes.append(s)


def adb(*a, timeout=120):
    return subprocess.run([ADB, *a], capture_output=True, timeout=timeout).stdout.decode('utf-8', 'replace')


def put(path):
    try: return urllib.request.urlopen(urllib.request.Request(BASE + path, method='PUT'), timeout=8).status
    except Exception as e: return getattr(e, 'code', -1)


def car():
    j = json.loads(urllib.request.urlopen(BASE + '/vehicle/state', timeout=8).read().decode('utf-8'))
    b, l = j['body'], j.get('lights', {})
    hm = l.get('headlamp_mode')
    if isinstance(hm, dict): hm = hm.get('value', hm.get('mode'))
    return {'gear': j['gear']['actual']['value'], 'speed': j['controls']['speed_kph'], 'locks': [b['door_locks'][k] for k in ('front_left', 'front_right', 'rear_left', 'rear_right')],
            'cabin': b['cabin_light']['on'], 'headlamp_mode': hm}


def park():
    """Brake to 0 km/h first, THEN shift to P. (Run 12:15: P without braking left the virtual car at 'P, 44 km/h', which is not a parked car.)"""
    put('/vehicle/controls?speed=0&accelerator=0&brake=100')
    for _ in range(20):
        if car()['speed'] == 0: break
        time.sleep(0.5)
    put('/vehicle/gear?gear=Park'); put('/vehicle/controls?speed=0&accelerator=0&brake=0')


def drive():
    for p in ('/vehicle/gear?gear=Drive', '/vehicle/controls?speed=30&accelerator=10&brake=0'): put(p)


def shot(name):
    adb('shell', 'screencap', '-p', '/sdcard/ad.png'); adb('pull', '/sdcard/ad.png', str(RUN / (name + '.png')))


def focus():
    m = re.search(r'mCurrentFocus=Window\{\S+ \S+ ([^}]+)\}', adb('shell', 'dumpsys', 'window'))
    return m.group(1) if m else '?'


def frame():
    m = re.search(re.escape(PKG) + r'/[^,]*, frame=\[(\d+),(\d+),(\d+),(\d+)\]', adb('shell', 'dumpsys', 'window', 'windows'))
    return tuple(int(x) for x in m.groups()) if m else (853, 90, 2535, 1300)


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


def tap(key, wait=2.5):
    fx, fy = FR[0], FR[1]
    for _ in range(4):
        st, rects = page()
        if key in rects:
            x, y, w, h = rects[key]; adb('shell', 'input', 'tap', str(fx + x + w // 2), str(fy + y + h // 2)); note(f'tapped {key}'); time.sleep(wait); return True
        time.sleep(1)
    note(f'NOT FOUND {key}'); return False


def logs(*keys):
    return [l for l in adb('logcat', '-d', '-v', 'time', '-s', 'FullHMI:I', 'FullHMIVeh:I').splitlines() if any(k in l for k in keys)]


def state(label):
    st, _ = page(); s = {k: (st or {}).get(k) for k in ('scene', 'motion', 'layout', 'hmi', 'screen')}; s['focus'] = focus(); s['car'] = {k: car()[k] for k in ('gear', 'speed')}
    res[label] = s; note(f'{label}: ' + json.dumps(s, ensure_ascii=False)); return st


res.update({'apk': APK.name, 'apk_sha256': hashlib.sha256(APK.read_bytes()).hexdigest().upper()}); note('apk ' + res['apk_sha256'])
park(); [put(p) for p in ('/vehicle/body?cabin_light=false', '/vehicle/lights?feature=headlamp_mode&value=Off', '/vehicle/body?doors_locked=true')]; time.sleep(4)
note('install: ' + (adb('install', '--user', '10', '-r', str(APK), timeout=300).strip().splitlines() or ['?'])[-1])
inst = adb('shell', 'pm', 'path', '--user', '10', PKG).replace('package:', '').strip()
res['installed_sha256'] = adb('shell', 'sha256sum', inst).split()[0].upper()
if res['installed_sha256'] != res['apk_sha256']:
    note('STOP: installed APK differs'); (RUN / 'results.json').write_text(json.dumps(res | {'notes': notes}, ensure_ascii=False, indent=1), encoding='utf-8'); sys.exit(2)
adb('shell', 'am', 'force-stop', PKG); adb('logcat', '-G', '16M'); adb('logcat', '-c')
adb('shell', 'am', 'start', '--user', '10', '-n', ACT); time.sleep(12)
FR = frame()
st, rects = page()
if st and st.get('hmi') == 'ACC': tap('accOff', 3)                     # start from READY
st, rects = page()
if st and st.get('screen') != 'home': tap('back', 2)

# A  READY
state('A_READY'); shot('A1_ready_parked')
res['A_frames'] = {'app': FR}

# B  DRIVING with real signals
drive(); time.sleep(7); state('B_DRIVING'); shot('B1_driving_30kmh')
# back to parked without an accident
park(); time.sleep(13); state('B_then_parked'); shot('B2_parked_again')      # 13 s: the car slows down, then parked must hold for 2 s
if focus().split('/')[0] != PKG:                                        # the driver opens the app from the system (what a person would do)
    adb('shell', 'am', 'start', '--user', '10', '-n', ACT); time.sleep(4); note('app reopened by hand after parking')
state('A_READY_again'); shot('A2_ready_again')

# P  a virtual accident event while driving
drive(); time.sleep(7); state('B_DRIVING_2')
ev = {'type': 'crash', 'id': 'adaptive-' + uuid.uuid4().hex[:6], 'source': 'virtual', 'level': 'DETECTED', 'score': 0.9, 'scenario': 'adaptive',
      'ts': int(adb('shell', 'date', '+%s%3N').strip() or 0)}
adb('shell', 'am', 'start', '--user', '10', '-n', ACT, '--es', 'vevent_b64', base64.b64encode(json.dumps(ev).encode()).decode('ascii')); time.sleep(6)
state('P_ACCIDENT_PENDING'); shot('P1_accident_event_while_driving')
STAY = len(sys.argv) > 2 and sys.argv[2] == 'stay'                      # 'stay': RE:SAFETY stays in front after the accident signal (covered by the system while moving)
if not STAY:                                                           # default: the driver puts the navigation in front again
    adb('shell', 'am', 'start', '--user', '10', '-n', 'ai.umos.maps.android.navigation.app/.MainActivity'); time.sleep(4); state('P_pending_navigation_in_front'); shot('P2_pending_navigation_in_front')

# C  parked again -> accident response
park(); time.sleep(13); state('C_after_parking'); shot('C1_parked_after_accident')
if focus().split('/')[0] != PKG:
    adb('shell', 'am', 'start', '--user', '10', '-n', ACT); time.sleep(4); note('app reopened by hand after parking')
state('C_ACCIDENT_RESPONSE'); shot('C2_accident_response')
# protect mode once, against the console (the SDK path in the new layout)
tap('cprot', 3); before = car(); tap('proton', 9); shot('C3_protect_on'); after = car(); tap('protoff', 9); undo = car(); tap('back', 2)
res['protect'] = {'before': before, 'after': after, 'undo': undo,
                  'changed': after['locks'] == [False] * 4 and after['cabin'] is True and str(after['headlamp_mode']) == 'Position',
                  'restored': all(undo[k] == before[k] for k in ('locks', 'cabin', 'headlamp_mode'))}
note('protect ' + json.dumps({k: res['protect'][k] for k in ('changed', 'restored')}))
tap('accOff', 3); state('A_READY_end'); shot('A3_ready_end')

L = logs('SCENE', 'NAVI', 'TTS_', 'PAGE_ERROR', 'PROTECT_RESULT')
(RUN / 'logcat_scene.txt').write_text('\n'.join(L), encoding='utf-8')
for l in L: note('app: ' + l[:200])
res['scene_transitions'] = [re.sub(r'.*?(SCENE \S+ -> \S+).*', r'\1', l) for l in L if re.search(r'FullHMI\s*\(\s*\d+\): SCENE \S+ -> ', l)]
res['page_errors'] = sum('PAGE_ERROR' in l for l in L); res['notes'] = notes
(RUN / 'results.json').write_text(json.dumps(res, ensure_ascii=False, indent=1), encoding='utf-8')
print('\ntransitions:', res['scene_transitions'], '| page errors', res['page_errors'], '|', RUN)
