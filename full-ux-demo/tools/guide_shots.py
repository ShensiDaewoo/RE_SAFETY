"""Screenshots of the six steps of "사고 상황 정리" (with everything ticked, so that the texts are at their longest), the large QR, the record
and the "about" screen on the emulator. Real taps. A look at the type sizes, not a regression run.
  python guide_shots.py <out dir>"""
import json, subprocess, sys, time
from pathlib import Path

ADB = r'C:\Android\Sdk\platform-tools\adb.exe'
PKG = 'com.contest.resafety.fullhmi.aurora'
ACT = PKG + '/com.contest.resafety.fullhmi.MainActivity'
out = Path(sys.argv[1]); out.mkdir(parents=True, exist_ok=True)


def adb(*a, binary=False):
    r = subprocess.run([ADB, '-s', 'emulator-5554', *a], capture_output=True, timeout=60)
    return r.stdout if binary else r.stdout.decode('utf-8', 'replace')


def page():
    ls = adb('logcat', '-d', '-v', 'raw', '-s', 'FullHMI:I').splitlines()
    i = max((k for k, l in enumerate(ls) if l.startswith('STATE ')), default=-1)
    if i < 0:
        return {}, {}
    rects = {}
    for l in ls[i + 1:]:
        if l.startswith('RECT '):
            p = l.split()
            if len(p) >= 6:
                rects[p[1]] = tuple(int(v) for v in p[2:6])
    try:
        return json.loads(ls[i][6:]), rects
    except ValueError:
        return {}, rects


def settled():
    prev = None
    for _ in range(25):
        cur = page()
        if cur[0] and cur == prev:
            return cur
        prev = cur; time.sleep(0.4)
    return prev or ({}, {})


def tap(key):
    st, r = settled()
    if key not in r:
        print('  no button', key); return False
    x, y, w, h = r[key]
    adb('shell', 'input', 'tap', str(x + w // 2), str(y + h // 2)); time.sleep(1.0)
    return True


def shot(name):
    st, r = settled()
    (out / (name + '.png')).write_bytes(adb('exec-out', 'screencap', '-p', binary=True))
    print(name, {k: st.get(k) for k in ('hmi', 'screen', 'pageErrors')}, 'keys', sorted(r)[:40], flush=True)
    return st, r


adb('shell', 'am', 'force-stop', PKG); adb('shell', 'pm', 'clear', PKG); time.sleep(1.5)
adb('logcat', '-c')
for _ in range(3):
    adb('shell', 'am', 'start', '-n', ACT); time.sleep(5)
    if page()[0]:
        break
if 'ImmersiveModeConfirmation' in adb('shell', 'dumpsys', 'window', 'windows'):
    adb('shell', 'input', 'tap', '1492', '402'); time.sleep(1)
time.sleep(3)
tap('lsys'); shot('G0_about')
tap('back'); tap('accOn'); tap('cguide')
for k in ('chk0', 'chk1', 'chk2', 'chk3'):
    tap(k)
shot('G1_checks_all_ticked')
tap('gnext'); tap('minorp'); tap('majorp'); shot('G2_injuries')
tap('gnext'); shot('G3_vehicle')
tap('gnext'); st, r = settled()
tap('shloc')                                                 # the other three are on from the start (first run: tapping all four switched them off)
st, r = shot('G4_share'); print('  share keys:', sorted(k for k in r if k.startswith('sh')))
tap('gnext'); st, r = shot('G5_qr')
if tap('qr'):
    shot('G5b_qr_large'); tap('qr')
tap('gnext'); shot('G6_done')
tap('gnext'); shot('G7_after_done')
st, r = settled()
if 'crec' in r:
    tap('crec'); shot('G8_record')
