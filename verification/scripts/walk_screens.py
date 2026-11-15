"""Visual regression, part 1: install one APK with fresh storage, walk the same screens in the same order, save one picture per screen.
Works on the Pleos Connect emulator (current user 10, app window inside the Pleos frame) and on a generic Android emulator (user 0, full screen).
The SOS screen is not opened (its one tap starts a request) and the 119 buttons are not tapped (they leave the app for the dialer).
  python walk_screens.py <apk> <out dir>"""
import hashlib, json, re, subprocess, sys, time
from pathlib import Path

ADB = r'C:\Android\Sdk\platform-tools\adb.exe'
PKG = 'com.contest.resafety.fullhmi.aurora'
ACT = PKG + '/com.contest.resafety.fullhmi.MainActivity'
APK, OUT = Path(sys.argv[1]), Path(sys.argv[2])
OUT.mkdir(parents=True, exist_ok=True)
notes = []


def note(s):
    print(s, flush=True); notes.append(s)


def adb(*a, timeout=120):
    return subprocess.run([ADB, *a], capture_output=True, timeout=timeout).stdout.decode('utf-8', 'replace')


USER = (adb('shell', 'am', 'get-current-user').strip() or '0')


def frame():
    m = re.search(re.escape(PKG) + r'/[^,]*, frame=\[(\d+),(\d+),(\d+),(\d+)\]', adb('shell', 'dumpsys', 'window', 'windows'))
    if m: return tuple(int(x) for x in m.groups())
    m = re.search(r'Physical size: (\d+)x(\d+)', adb('shell', 'wm', 'size'))
    return (0, 0, int(m.group(1)), int(m.group(2)))


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


def tap(key, wait=2.0):
    fx, fy, fr, fb = FR
    for _ in range(5):
        st, rects = page()
        if key not in rects:
            time.sleep(1); continue
        x, y, w, h = rects[key]; cx, cy = fx + x + w // 2, fy + y + h // 2
        if cy > fb - 30:
            adb('shell', 'input', 'swipe', str(fx + (fr - fx) * 3 // 4), str(fb - 400), str(fx + (fr - fx) * 3 // 4), str(fb - 780), '300'); time.sleep(1.2); continue
        adb('shell', 'input', 'tap', str(cx), str(cy)); time.sleep(wait); return True
    note(f'NOT FOUND {key}'); return False


def shot(name):
    adb('shell', 'screencap', '-p', '/sdcard/w.png'); adb('pull', '/sdcard/w.png', str(OUT / (name + '.png')))
    st, _ = page(); shots.append({'name': name, 'screen': st and st.get('screen'), 'hmi': st and st.get('hmi'), 'guide': st and st.get('guide')})


def find_text(text):
    adb('shell', 'uiautomator', 'dump', '/sdcard/u.xml')
    m = re.search(r'text="' + re.escape(text) + r'"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', adb('shell', 'cat', '/sdcard/u.xml'))
    return ((int(m.group(1)) + int(m.group(3))) // 2, (int(m.group(2)) + int(m.group(4))) // 2) if m else None


def home():
    st, _ = page()
    if st and st.get('screen') != 'home': tap('back', 1.8)


shots = []
sha = hashlib.sha256(APK.read_bytes()).hexdigest().upper(); note(f'apk {APK.name} {sha} user {USER}')
adb('shell', 'am', 'force-stop', PKG); adb('uninstall', PKG)                  # a clean install: no storage, no granted permission, older or newer build
note('install: ' + (adb('install', '--user', USER, str(APK), timeout=300).strip().splitlines() or ['?'])[-1])
inst = adb('shell', 'pm', 'path', '--user', USER, PKG).replace('package:', '').strip()
got = adb('shell', 'sha256sum', inst).split()[0].upper()
if got != sha:
    note('STOP: installed APK differs'); (OUT / 'walk.json').write_text(json.dumps({'notes': notes}, ensure_ascii=False, indent=1), encoding='utf-8'); sys.exit(2)
adb('logcat', '-G', '16M'); adb('logcat', '-c')
adb('shell', 'am', 'start', '--user', USER, '-n', ACT); time.sleep(6)
for _ in range(3):                                                              # runtime permission dialogs (only builds that ask; a person taps "allow")
    p = find_text('앱 사용 중에만 허용') or find_text('허용')
    if not p: break
    adb('shell', 'input', 'tap', str(p[0]), str(p[1])); note('permission dialog: allowed'); time.sleep(2.5)
time.sleep(10)                                                                  # vehicle services, when there are any, report within a few seconds
adb('shell', 'am', 'start', '--user', USER, '-n', ACT, '--ez', 'reload', 'true'); time.sleep(5)
FR = frame(); note(f'app window {FR}')
st, rects = page(); note('start state hmi=%s screen=%s vehicle=%s' % (st and st.get('hmi'), st and st.get('screen'), bool(st and st.get('veh'))))

# READY state
shot('r01_home')
for key, name in (('cpre', 'r02_pre'), ('cseat', 'r03_seat'), ('cseatctl', 'r04_seatctl'), ('crec', 'r05_record'), ('about', 'r06_about')):
    tap(key); shot(name); home()
# ACCIDENT state (the page's own simulation switch)
tap('accOn', 3); shot('a01_home')
tap('cprot'); shot('a02_protect'); home()
tap('cguide'); shot('a03_guide1_actions')
for name in ('a04_guide2_injury', 'a05_guide3_vehicle', 'a06_guide4_share', 'a07_guide5_qr', 'a08_guide6_done'):
    tap('gnext'); shot(name)
home()
tap('cseat'); shot('a09_seat'); tap('seatsim', 3); adb('shell', 'input', 'swipe', str(FR[0] + 1250), '500', str(FR[0] + 1250), '1100', '300'); time.sleep(1.5); shot('a10_seat_belt_query'); home()
tap('ctow'); shot('a11_tow'); home()
tap('belt'); shot('a12_belt_info'); home()
tap('crec'); shot('a13_record'); home()
tap('cpre'); shot('a14_pre'); home()
errs = sum('PAGE_ERROR' in l for l in adb('logcat', '-d', '-v', 'raw', '-s', 'FullHMI:I').splitlines())
(OUT / 'walk.json').write_text(json.dumps({'apk': APK.name, 'sha256': sha, 'user': USER, 'frame': FR, 'page_errors': errs, 'shots': shots, 'notes': notes}, ensure_ascii=False, indent=1), encoding='utf-8')
print(f'{len(shots)} pictures, page errors {errs}, not found {sum(n.startswith("NOT FOUND") for n in notes)} ->', OUT)
