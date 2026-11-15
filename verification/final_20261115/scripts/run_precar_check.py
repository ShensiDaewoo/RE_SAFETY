"""adaptive6: "차량 정보 읽기" on the pre-drive check. Tap -> values shown -> written to the record -> kept after the app is stopped.
Runs on either emulator (generic Android = SIMULATION values; Pleos = Vehicle SDK). Does not reinstall: the installed build is tested.
  python run_precar_check.py <out dir>"""
import json, re, subprocess, sys, time
from pathlib import Path

ADB = r'C:\Android\Sdk\platform-tools\adb.exe'
PKG = 'com.contest.resafety.fullhmi.aurora'
ACT = PKG + '/com.contest.resafety.fullhmi.MainActivity'
OUT = Path(sys.argv[1]); OUT.mkdir(parents=True, exist_ok=True)
res, notes = {}, []


def adb(*a, timeout=90):
    return subprocess.run([ADB, *a], capture_output=True, timeout=timeout).stdout.decode('utf-8', 'replace')


USER = adb('shell', 'am', 'get-current-user').strip() or '0'


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
            x, y, w, h = rects[key]; cx, cy = fx + x + w // 2, fy + y + h // 2
            if cy > fb - 30:
                adb('shell', 'input', 'swipe', str(fx + (fr - fx) * 3 // 4), str(fb - 300), str(fx + (fr - fx) * 3 // 4), str(fb - 800), '300'); time.sleep(1.3); continue
            adb('shell', 'input', 'tap', str(cx), str(cy)); notes.append(f'tapped {key}'); time.sleep(wait); return True
        time.sleep(1)
    notes.append(f'NOT FOUND {key}'); return False


def shot(n):
    adb('shell', 'screencap', '-p', '/sdcard/pc.png'); adb('pull', '/sdcard/pc.png', str(OUT / (n + '.png')))


def start():
    adb('shell', 'am', 'force-stop', PKG); adb('logcat', '-c'); adb('shell', 'am', 'start', '--user', USER, '-n', ACT); time.sleep(9)
    st, _ = page()
    if st and st.get('screen') != 'home': tap('back', 2)


start()
st, _ = page(); res['vehicle'] = bool(st and st.get('veh')); rec0 = (st or {}).get('rec')
tap('cpre', 2.5); st, _ = page(); res['before'] = (st or {}).get('pre')
tap('precar', 6); st, rects = page(); res['after_tap'] = (st or {}).get('pre'); res['rec_after'] = (st or {}).get('rec'); res['rec_before'] = rec0
res['veh_after'] = (st or {}).get('veh')
shot('1_pre_after_read')
for k in ('precar_vin', 'precar_odo', 'precar_range', 'precar_energy', 'precar_lights', 'precar_at'):
    pass
start()                                                                              # stop and start again: the reading must still be there
tap('cpre', 2.5); st, _ = page(); res['after_restart'] = (st or {}).get('pre'); shot('2_pre_after_restart')
tap('back', 2); tap('crec', 2.5); shot('3_record_after_read')
L = [l for l in adb('logcat', '-d', '-v', 'time', '-s', 'FullHMI:I', 'FullHMIVeh:I').splitlines() if 'HANDOVER' in l or 'PAGE_ERROR' in l]
res['native_lines'] = L[-6:]; res['notes'] = notes
res['ok'] = bool(res['after_tap'] and res['after_tap'].get('car') and res['after_restart'] and res['after_restart'].get('car') and (res['rec_after'] or 0) > (res['rec_before'] or 0))
(OUT / 'results.json').write_text(json.dumps(res, ensure_ascii=False, indent=1), encoding='utf-8')
print(json.dumps({k: res[k] for k in ('vehicle', 'before', 'after_tap', 'rec_before', 'rec_after', 'after_restart', 'ok')}, ensure_ascii=False)); print('\n'.join(res['native_lines'])); print(notes)
