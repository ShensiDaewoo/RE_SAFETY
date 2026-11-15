"""Still pictures for the proposal, taken at the emulator's native size (2560 x 1440, lossless PNG) from the installed ② APK.
Not frames from a video and not resized. Each scene is set up first and the picture is taken when the screen has settled.
  pleos    READY, seats (console: driver + rear-left occupied), driving = Pleos navigation, WARNING after parking, accident home,
           protect mode ON (console values change; undone afterwards)
  generic  READY, WARNING, accident home (Aurora full screen; vehicle values and accident signal are simulated)
The WARNING title blinks once a second: several pictures are taken and the one with the most bright red in the title is kept.
  python capture_figures.py pleos|generic <out dir>"""
import base64, json, re, subprocess, sys, time, urllib.request, uuid
from pathlib import Path
import numpy as np
from PIL import Image

ADB = r'C:\Android\Sdk\platform-tools\adb.exe'
PKG = 'com.contest.resafety.fullhmi.aurora'
ACT = PKG + '/com.contest.resafety.fullhmi.MainActivity'
BASE = 'http://127.0.0.1:8088'
MODE, OUT = sys.argv[1], Path(sys.argv[2]); OUT.mkdir(parents=True, exist_ok=True)
log = []


def say(s): print(time.strftime('%H:%M:%S'), s, flush=True); log.append(time.strftime('%H:%M:%S ') + s)
def adb(*a, binary=False, timeout=90):
    r = subprocess.run([ADB, *a], capture_output=True, timeout=timeout); return r.stdout if binary else r.stdout.decode('utf-8', 'replace')


USER = adb('shell', 'am', 'get-current-user').strip() or '0'


def put(p):
    try: return urllib.request.urlopen(urllib.request.Request(BASE + p, method='PUT'), timeout=8).status
    except Exception as e: return getattr(e, 'code', -1)


def speed():
    try: return json.loads(urllib.request.urlopen(BASE + '/vehicle/state', timeout=8).read().decode())['controls']['speed_kph']
    except Exception: return -1


def frame():
    m = re.search(re.escape(PKG) + r'/[^,]*, frame=\[(\d+),(\d+),(\d+),(\d+)\]', adb('shell', 'dumpsys', 'window', 'windows'))
    return tuple(int(x) for x in m.groups()) if m else (0, 0, 2560, 1440)


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
    fx, fy, fr, fb = frame()
    for _ in range(6):
        st, rects = page()
        if key in rects:
            x, y, w, h = rects[key]; adb('shell', 'input', 'tap', str(fx + x + w // 2), str(fy + y + h // 2)); say('tap ' + key); time.sleep(wait); return True
        time.sleep(0.8)
    say('NOT FOUND ' + key); return False


def grab():
    adb('shell', 'screencap', '-p', '/sdcard/fig.png'); p = OUT / '_tmp.png'; adb('pull', '/sdcard/fig.png', str(p)); return Image.open(p).convert('RGB')


def shot(name, settle=1.5):
    time.sleep(settle); im = grab(); im.save(OUT / (name + '.png')); say(f'saved {name}.png {im.size[0]}x{im.size[1]}')


def warn_closed_count(): return adb('logcat', '-d', '-v', 'raw', '-s', 'FullHMI:I').count('WARN closed')


def best_warning(name, box, n=8):
    """Several pictures while WARNING is up; keep the one with the most bright red inside box (the title)."""
    best = None
    for i in range(n):
        im = grab(); a = np.asarray(im.crop(box)).astype(int)
        red = float(((a[..., 0] > 200) & (a[..., 1] < 90) & (a[..., 2] < 110)).mean())
        if best is None or red > best[1]: best = (im, red)
        time.sleep(0.15)
    best[0].save(OUT / (name + '.png')); say(f'saved {name}.png (red share {best[1]:.3f} of the best of {n})')


adb('shell', 'am', 'force-stop', PKG); adb('logcat', '-G', '16M'); adb('logcat', '-c')
if MODE == 'pleos':
    for p in ('/vehicle/controls?speed=0&accelerator=0&brake=0', '/vehicle/gear?gear=Park', '/vehicle/body?cabin_light=false',
              '/vehicle/lights?feature=headlamp_mode&value=Off', '/vehicle/body?doors_locked=true'): put(p)
    time.sleep(3)
adb('shell', 'am', 'start', '--user', USER, '-n', ACT); time.sleep(9)
st, _ = page()
if (st or {}).get('hmi') == 'ACC': tap('accOff', 3)
if (page()[0] or {}).get('screen') != 'home': tap('back', 2)
say('installed sha256: ' + adb('shell', 'sha256sum', adb('shell', 'pm', 'path', '--user', USER, PKG).replace('package:', '').strip()).split()[0])

if MODE == 'pleos':
    shot('P1_ready_pleos', 2)
    tap('cseat', 3); tap('seatsim', 5); shot('P2_seats_vs_console', 1); tap('back', 2.5)
    say('drive: gear D, 30 km/h'); put('/vehicle/gear?gear=Drive'); put('/vehicle/controls?speed=30&accelerator=10&brake=0'); time.sleep(10)
    shot('P3_driving_pleos_navigation', 1)
    ev = {'type': 'crash', 'id': 'fig-' + uuid.uuid4().hex[:6], 'source': 'virtual', 'level': 'DETECTED', 'score': 0.9, 'scenario': 'figure',
          'ts': int(adb('shell', 'date', '+%s%3N').strip() or 0)}
    adb('shell', 'am', 'start', '--user', USER, '-n', ACT, '--es', 'vevent_b64', base64.b64encode(json.dumps(ev).encode()).decode()); time.sleep(6)
    c0 = warn_closed_count()
    put('/vehicle/controls?speed=0&accelerator=0&brake=100')
    for _ in range(20):
        if speed() == 0: break
        time.sleep(0.5)
    put('/vehicle/gear?gear=Park'); put('/vehicle/controls?speed=0&accelerator=0&brake=0'); time.sleep(1.0)
    best_warning('P4_warning_after_parking', (1400, 480, 2200, 680), 10)
    for _ in range(20):
        if warn_closed_count() > c0: break
        time.sleep(0.5)
    shot('P5_accident_home_pleos', 2)
    tap('cprot', 3); tap('proton', 9); shot('P6_protect_on_pleos', 0.5); tap('protoff', 8); tap('back', 2.5)
else:
    shot('G1_ready_fullhmi', 2)
    c0 = warn_closed_count()
    tap('accOn', 0.6); best_warning('G2_warning_fullhmi', (900, 500, 2000, 700), 10)
    for _ in range(20):
        if warn_closed_count() > c0: break
        time.sleep(0.5)
    shot('G3_accident_fullhmi', 2.5)
    tap('accOff', 3)
(OUT / '_tmp.png').unlink(missing_ok=True)
(OUT / f'capture_{MODE}.log.txt').write_text('\n'.join(log), encoding='utf-8')
print('done', OUT)
