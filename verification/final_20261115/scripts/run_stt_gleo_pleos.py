"""adaptive6 on the Pleos Connect emulator: SpeechToText and the Gleo Interface, app side.
  STT   a Korean test sentence (made on this PC with the Windows voice "Heami", 16 kHz 16-bit mono) is handed to the app through a lab
        intent extra and fed to the Pleos SpeechToText SDK with sendAudio() (the SDK's documented "send audio" path, ON_DEVICE mode),
        after a tap on "음성 입력" next to the memo field. Checked: the recognised text lands in the field. The microphone path is not tested.
  GLEO  1. after (re)installing, does the Gleo app on the emulator read assets/schema.json? (log search only)
        2. the three functions are started exactly as the Pleos guide says Gleo starts them (intent action = function name, package set,
           extra ACTION_PARAM). This imitates Gleo; it is NOT a call by Gleo itself (that needs Pleos's review and approval).
  python run_stt_gleo_pleos.py <apk> <wav> <out dir>"""
import base64, json, re, subprocess, sys, time, wave
from pathlib import Path

ADB = r'C:\Android\Sdk\platform-tools\adb.exe'
PKG = 'com.contest.resafety.fullhmi.aurora'
ACT = PKG + '/com.contest.resafety.fullhmi.MainActivity'
APK, WAV, OUT = Path(sys.argv[1]), Path(sys.argv[2]), Path(sys.argv[3])
OUT.mkdir(parents=True, exist_ok=True)
res, notes = {}, []


def note(s): print(s, flush=True); notes.append(s)


def adb(*a, timeout=120):
    return subprocess.run([ADB, *a], capture_output=True, timeout=timeout).stdout.decode('utf-8', 'replace')


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
    fx, fy, fr, fb = frame()
    for _ in range(6):
        st, rects = page()
        if key in rects:
            x, y, w, h = rects[key]; cx, cy = fx + x + w // 2, fy + y + h // 2
            if cy > fb - 30 or cy < fy + 200:
                d = 1 if cy > fb - 30 else -1
                adb('shell', 'input', 'swipe', str(fx + 900), str(fy + 700 + 250 * d), str(fx + 900), str(fy + 700 - 250 * d), '300'); time.sleep(1.3); continue
            adb('shell', 'input', 'tap', str(cx), str(cy)); note(f'tapped {key}'); time.sleep(wait); return True
        time.sleep(1)
    note(f'NOT FOUND {key}'); return False


def shot(n):
    adb('shell', 'screencap', '-p', '/sdcard/sg.png'); adb('pull', '/sdcard/sg.png', str(OUT / (n + '.png')))


def focus():
    m = re.search(r'mCurrentFocus=Window\{\S+ \S+ ([^}]+)\}', adb('shell', 'dumpsys', 'window')); return m.group(1) if m else '?'


def logs(*keys):
    return [l for l in adb('logcat', '-d', '-v', 'time').splitlines() if any(k in l for k in keys)]


# ---- GLEO 1: reinstall and look for the schema scan
adb('logcat', '-G', '16M'); adb('logcat', '-c')
note('install: ' + (adb('install', '--user', '10', '-r', str(APK), timeout=300).strip().splitlines() or ['?'])[-1])
time.sleep(12)
scan = [l for l in logs('schema', 'Schema', 'SCHEMA') if 'resafety' in l or 'schema.json' in l][:12]
res['gleo_schema_scan_lines'] = scan; note(f'schema scan lines mentioning this app: {len(scan)}')
(OUT / 'gleo_schema_scan_log.txt').write_text('\n'.join(logs('schema', 'Schema', 'SCHEMA', 'gleo', 'Gleo')), encoding='utf-8')

# ---- STT: test audio through the lab extra, then the "음성 입력" button next to the memo
with wave.open(str(WAV)) as w:
    assert (w.getframerate(), w.getsampwidth(), w.getnchannels()) == (16000, 2, 1), (w.getframerate(), w.getsampwidth(), w.getnchannels())
    pcm = w.readframes(w.getnframes())
b64 = base64.b64encode(pcm).decode('ascii'); note(f'test audio {len(pcm)} bytes, {len(pcm) / 32000:.1f} s')
script = OUT / 'start_with_audio.sh'
script.write_text(f"am start --user 10 -n {ACT} --es stt_test_pcm_b64 {b64}\n", encoding='ascii', newline='\n')
adb('push', str(script), '/data/local/tmp/stt_start.sh')
adb('shell', 'am', 'force-stop', PKG); adb('logcat', '-c')
adb('shell', 'am', 'start', '--user', '10', '-n', ACT); time.sleep(9)
st, _ = page()
if st and st.get('hmi') == 'ACC': tap('accOff', 3)
st, _ = page()
if st and st.get('screen') != 'home': tap('back', 2)
note('stt state before: ' + str((st or {}).get('veh', {}).get('stt')))
adb('shell', 'sh', '/data/local/tmp/stt_start.sh'); time.sleep(3)
tap('cpre', 3)
memo0 = (page()[0] or {}).get('pre', {}).get('memo')
tap('stt_pre_memo', 1.0); time.sleep(10)
st, _ = page(); res['stt_memo_chars_before_after'] = [memo0, (st or {}).get('pre', {}).get('memo')]
shot('stt_1_pre_after_voice')
res['stt_lines'] = [l[19:200] for l in logs('STT_')]
for l in res['stt_lines']: note('app: ' + l)
adb('shell', 'rm', '/data/local/tmp/stt_start.sh')

# ---- GLEO 2: the three functions, started the way the guide says Gleo starts them
tap('back', 2)
st, _ = page()
if st and st.get('hmi') == 'ACC': tap('accOff', 3)
calls = lambda: len([l for l in adb('shell', 'dumpsys', 'telecom').splitlines() if 'Call TC@' in l])
g = {}
for fn, wait in (('OPEN_ACCIDENT_RESPONSE', 4), ('OPEN_ACCIDENT_GUIDE', 4), ('OPEN_EMERGENCY_CALL_SCREEN', 5)):
    c0 = calls(); adb('logcat', '-c')
    adb('shell', 'am', 'start', '--user', '10', '-a', fn, '-p', PKG, '--es', 'ACTION_PARAM', '{}'); time.sleep(wait)
    st, _ = page(); f = focus(); shot('gleo_' + fn)
    g[fn] = {'screen': (st or {}).get('screen'), 'hmi': (st or {}).get('hmi'), 'focus': f, 'calls_before_after': [c0, calls()],
             'app_lines': [l[19:220] for l in logs('GLEO_', 'DIAL_')]}
    note(f'{fn}: ' + json.dumps(g[fn], ensure_ascii=False))
    if fn == 'OPEN_EMERGENCY_CALL_SCREEN': adb('shell', 'am', 'start', '--user', '10', '-n', ACT); time.sleep(3)
res['gleo_functions'] = g
st, _ = page()
if st and st.get('hmi') == 'ACC': tap('accOff', 3)
res['notes'] = notes
(OUT / 'results.json').write_text(json.dumps(res, ensure_ascii=False, indent=1), encoding='utf-8')
print('done ->', OUT)
