"""Release-candidate checks for aurora17 on the Pleos Connect emulator (nothing is built or changed; the installed APK is only run).
  D  the app runs inside the Pleos application window and does not overlap the Driving View or the system bars (window frames + picture)
  B  what the 119 button does on Pleos: ACTION_DIAL only. The dial button of the phone screen is never tapped; no call is placed
  C  TTS: SDK events AND whether the audio mixer really played a track while the result was spoken (a person listening is a separate matter)
Needs: Pleos_Connect_v2_1 running, adb forward tcp:8088 tcp:8088, the console page open and visible.
  python rc_pleos_checks.py <out dir>"""
import json, re, subprocess, sys, time, urllib.request
from pathlib import Path

ADB = r'C:\Android\Sdk\platform-tools\adb.exe'
PKG = 'com.contest.resafety.fullhmi.aurora'
ACT = PKG + '/com.contest.resafety.fullhmi.MainActivity'
BASE = 'http://127.0.0.1:8088'
OUT = Path(sys.argv[1]); OUT.mkdir(parents=True, exist_ok=True)
notes, res = [], {}


def note(s):
    print(s, flush=True); notes.append(s)


def adb(*a, timeout=90):
    return subprocess.run([ADB, *a], capture_output=True, timeout=timeout).stdout.decode('utf-8', 'replace')


def put(path):
    try: return urllib.request.urlopen(urllib.request.Request(BASE + path, method='PUT'), timeout=8).status
    except Exception as e: return getattr(e, 'code', -1)


def shot(name):
    adb('shell', 'screencap', '-p', '/sdcard/rc.png'); adb('pull', '/sdcard/rc.png', str(OUT / (name + '.png')))


def frames():
    w = adb('shell', 'dumpsys', 'window', 'windows'); out = {}
    for key, pat in (('app', re.escape(PKG) + r'/[^,]*'), ('driving_view', r'ai\.umos\.drivingview/[^,]*'), ('top_bar', 'TopCarSystemBar'), ('bottom_bar', 'BottomCarSystemBar')):
        m = re.search(pat + r', frame=\[(\d+),(\d+),(\d+),(\d+)\]', w)
        out[key] = [int(x) for x in m.groups()] if m else None
    return out


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


def calls():
    return [l for l in adb('shell', 'dumpsys', 'telecom').splitlines() if 'Call TC@' in l]


def focus():
    return ' '.join(l.strip() for l in adb('shell', 'dumpsys', 'window').splitlines() if 'mCurrentFocus' in l)


def logs(*keys):
    return [l for l in adb('logcat', '-d', '-v', 'time', '-s', 'FullHMI:I', 'FullHMIVeh:I').splitlines() if any(k in l for k in keys)]


def overlap(a, b):
    return bool(a and b) and not (a[2] <= b[0] or b[2] <= a[0] or a[3] <= b[1] or b[3] <= a[1])


for p in ('/vehicle/controls?speed=0&accelerator=0&brake=0', '/vehicle/gear?gear=Park', '/vehicle/body?cabin_light=false', '/vehicle/lights?feature=headlamp_mode&value=Off', '/vehicle/body?doors_locked=true'): put(p)
time.sleep(3)
inst = adb('shell', 'pm', 'path', '--user', '10', PKG).replace('package:', '').strip()
res['installed_sha256'] = adb('shell', 'sha256sum', inst).split()[0].upper(); note('installed ' + res['installed_sha256'])
res['version'] = [l.strip() for l in adb('shell', 'dumpsys', 'package', PKG).splitlines() if 'versionName' in l][:1]
adb('shell', 'am', 'force-stop', PKG); adb('logcat', '-G', '16M'); adb('logcat', '-c')
adb('shell', 'am', 'start', '--user', '10', '-n', ACT); time.sleep(9)

# D  window boundaries
f = frames(); FR = f['app'] or [853, 90, 2535, 1300]
res['D_frames'] = f
res['D_app_overlaps'] = {k: overlap(f['app'], f[k]) for k in ('driving_view', 'top_bar', 'bottom_bar')}
res['D_display'] = adb('shell', 'wm', 'size').strip()
note('D frames ' + json.dumps(f) + ' overlaps ' + json.dumps(res['D_app_overlaps']))
shot('D_app_window_on_pleos')
st, rects = page()
if st and st.get('hmi') != 'ACC': tap('accOn', 3); time.sleep(3)   # adaptive7+: the WARNING screen (4 s) takes the first tap; wait until it closes

# B  119 on Pleos
res['B_dial_handler'] = [l.strip() for l in adb('shell', 'cmd', 'package', 'resolve-activity', '--user', '10', '-a', 'android.intent.action.DIAL', '-d', 'tel:119').splitlines() if 'packageName' in l or ' name=' in l][:3]
c0 = calls(); f0 = focus(); tap('b119', 4); shot('B_after_119_tap'); f1 = focus(); c1 = calls()
res['B_log'] = logs('DIAL_'); res['B_focus_before'] = f0; res['B_focus_after'] = f1; res['B_calls_before_after'] = [len(c0), len(c1)]
note('B ' + json.dumps({k: res[k] for k in ('B_dial_handler', 'B_log', 'B_focus_after', 'B_calls_before_after')}, ensure_ascii=False))
adb('shell', 'am', 'start', '--user', '10', '-n', ACT); time.sleep(4); shot('B_back_in_app')
res['B_calls_after_return'] = len(calls())

# C  TTS: events and the audio mixer
st, _ = page()
if st and st.get('screen') != 'home': tap('back', 2)
tap('cprot', 3)
(OUT / 'C_audio_flinger_idle.txt').write_text(adb('shell', 'dumpsys', 'media.audio_flinger'), encoding='utf-8')
tap('proton', 2.2)
(OUT / 'C_audio_flinger_while_speaking_protect.txt').write_text(adb('shell', 'dumpsys', 'media.audio_flinger'), encoding='utf-8')
(OUT / 'C_audio_players_while_speaking.txt').write_text(adb('shell', 'dumpsys', 'audio'), encoding='utf-8')
time.sleep(8); shot('C_after_protect')
tap('protoff', 2.2)
(OUT / 'C_audio_flinger_while_speaking_undo.txt').write_text(adb('shell', 'dumpsys', 'media.audio_flinger'), encoding='utf-8')
time.sleep(8); shot('C_after_undo')
res['C_tts_log'] = logs('TTS_', 'PROTECT_RESULT')
note('C ' + json.dumps(res['C_tts_log'], ensure_ascii=False))
tap('back', 2)
all_logs = adb('logcat', '-d', '-v', 'time')
(OUT / 'C_speech_service_log.txt').write_text('\n'.join(l for l in all_logs.splitlines() if 'GAAS' in l and ('AudioTrack' in l or 'Tts' in l or 'tts' in l)), encoding='utf-8')
(OUT / 'logcat_fullhmi.txt').write_text('\n'.join(logs('')), encoding='utf-8')
res['notes'] = notes
(OUT / 'results.json').write_text(json.dumps(res, ensure_ascii=False, indent=1), encoding='utf-8')
print('done ->', OUT)
