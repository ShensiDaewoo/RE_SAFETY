"""① Pleos APK (R2D) on the Pleos Connect emulator: reach the QR screen by real taps, take the screen picture, read the QR with OpenCV.
Two paths: 'guide' (홈 -> 사고 상황 정리 -> ... -> QR 만들기) and 'tow' (홈 -> 보험·견인 -> 다음 -> 다음 -> QR로 공유).
Run in the foreground in chunks of at most 10 (a background loop once produced a false result, see the project notes).
  python r2d_qr_loop.py <out dir> guide|tow <first index> <count>"""
import json, re, subprocess, sys, time
from pathlib import Path
import cv2, numpy as np

ADB = r'C:\Android\Sdk\platform-tools\adb.exe'
PKG = 'com.contest.signalprobe.r2d'
OUT, PATH, FIRST, N = Path(sys.argv[1]), sys.argv[2], int(sys.argv[3]), int(sys.argv[4])
assert PATH in ('guide', 'tow') and N <= 10
OUT.mkdir(parents=True, exist_ok=True)
LOG = OUT / f'qr_{PATH}.log'


def adb(*a, binary=False):
    r = subprocess.run([ADB, *a], capture_output=True, timeout=90)
    return r.stdout if binary else r.stdout.decode('utf-8', 'replace')


def say(s):
    line = time.strftime('%H:%M:%S') + '  ' + s; print(line, flush=True)
    with open(LOG, 'a', encoding='utf-8') as f: f.write(line + '\n')


def dump():
    adb('shell', 'uiautomator', 'dump', '/sdcard/u.xml'); return adb('shell', 'cat', '/sdcard/u.xml')


def tap(text, exact=True, x=None):
    x = x or dump()
    m = re.search(r'(?:text|content-desc)="' + re.escape(text) + (r'"' if exact else r'[^"]*"') + r'[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', x)
    if not m: return False
    adb('shell', 'input', 'tap', str((int(m[1]) + int(m[3])) // 2), str((int(m[2]) + int(m[4])) // 2)); return True


def decode(png):
    im = cv2.imdecode(np.fromfile(str(png), dtype=np.uint8), cv2.IMREAD_COLOR); d = cv2.QRCodeDetector()
    for f in (1.0, 0.5, 2.0):
        t = d.detectAndDecode(im if f == 1.0 else cv2.resize(im, None, fx=f, fy=f))[0]
        if t: return t
    return ''


def to_qr():
    if PATH == 'tow':
        if not tap('보험·견인', exact=False): return 'no tow card'
        time.sleep(2.5)
        for _ in range(2):
            if not tap('다음'): return 'no 다음'
            time.sleep(2.5)
        if not tap('QR로 공유'): return 'no QR로 공유'
        time.sleep(3); return ''
    if not tap('사고 상황 정리', exact=False): return 'no guide card'
    time.sleep(2.5)
    for _ in range(9):
        x = dump()
        if '휴대폰 카메라로 스캔' in x: return ''
        if tap('QR 만들기', x=x): time.sleep(3); continue
        if '홈으로' in x and '단계 6' in x: tap('이전', x=x); time.sleep(2.5); continue
        if not tap('다음', x=x): return 'no way forward'
        time.sleep(2.5)
    return 'QR screen not reached'


ok = 0
for i in range(FIRST, FIRST + N):
    if adb('shell', 'pidof', PKG).strip() == '':
        say(f'{PATH} {i:02d} app process not running before the run (counted as crash)')
    tap('홈'); time.sleep(2)
    err = to_qr()
    png = OUT / f'{PATH}_{i:02d}.png'
    adb('shell', 'screencap', '-p', '/sdcard/c.png'); adb('pull', '/sdcard/c.png', str(png))
    t = decode(png) if not err else ''
    (OUT / f'{PATH}_{i:02d}_decoded.txt').write_text(t, encoding='utf-8')
    good = t.startswith('[RE:SAFETY]')
    ok += good
    say(f'{PATH} {i:02d} {"READ" if good else "FAIL"} {err} chars={len(t)} first="{t.splitlines()[0] if t else ""}" pid={adb("shell", "pidof", PKG).strip()}')
    if PATH == 'tow': tap('이전 화면'); time.sleep(1.5)
say(f'{PATH} chunk {FIRST}-{FIRST + N - 1}: {ok}/{N} read')
