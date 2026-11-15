"""A few screenshots of the installed aurora build on the emulator (real taps through the rectangles the page reports). Not a regression run.
  python dev_shots.py <out dir>"""
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
    adb('shell', 'input', 'tap', str(x + w // 2), str(y + h // 2)); time.sleep(1.2)
    return True


def home():
    """READY state has no rail: the screens carry a back button instead."""
    return tap('rhome') if 'rhome' in settled()[1] else tap('back')


def shot(name):
    st, r = settled()
    (out / (name + '.png')).write_bytes(adb('exec-out', 'screencap', '-p', binary=True))
    print(name, {k: st.get(k) for k in ('hmi', 'screen', 'pageErrors', 'bg')}, 'rects', len(r), flush=True)
    return st, r


adb('shell', 'am', 'force-stop', PKG); adb('shell', 'pm', 'clear', PKG); time.sleep(1.5)
adb('logcat', '-c')
for _ in range(3):
    adb('shell', 'am', 'start', '-n', ACT); time.sleep(5)
    if page()[0]:
        break
if 'ImmersiveModeConfirmation' in adb('shell', 'dumpsys', 'window', 'windows'):
    adb('shell', 'input', 'tap', '1492', '402'); time.sleep(1)
time.sleep(3)                                                # the first frame of the WebView needs a moment (aurora1: first shot was black)
st, r = shot('D1_ready_home')
print('  ready home keys present:', {k: k in r for k in ('cpre', 'cseat', 'cseatctl', 'crec', 'b119', 'bsos', 'cprot', 'cguide', 'ctow', 'recvset', 'accOn', 'q112', 'rhome', 'lsys', 'lgo')})
tap('cseat'); shot('D4_ready_seat')
home(); tap('cseatctl'); shot('D5_ready_seatcontrol')
tap('scq_relax'); tap('scm_heat'); tap('sclv_2'); st, r = shot('D5b_seatcontrol_after_taps'); print('  seat control state:', st.get('seatctl'))
tap('scs_RR'); tap('scm_pos'); st, r = shot('D5c_seatcontrol_rear_right'); print('  seat control state:', st.get('seatctl'))
home(); tap('accOn'); st, r = shot('D2_accident_home')
print('  accident home keys present:', {k: k in r for k in ('b119', 'bsos', 'cprot', 'cguide', 'cseat', 'ctow', 'cpre', 'crec', 'accOff', 'q112')})
tap('cseat'); shot('D3_accident_seat')
home(); tap('cguide'); shot('D7_accident_guide')
home(); tap('cprot'); shot('D8_accident_protect')
home(); tap('accOff'); st, r = shot('D6_back_to_ready'); print('  back in ready state:', st.get('hmi'))
