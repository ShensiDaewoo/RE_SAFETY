"""SHORT check, the same 16 checks as run_aurora_smoke.py, for the build 0.5.0-adaptive4 on a generic Android emulator (stand-alone prototype).
Only the build name in S0 differs. Original text of that script follows.


Why short (measured before writing this, see the report): against hardening6, which passed the full suites, the aurora build has
  - byte-identical MainActivity.kt, AndroidManifest.xml and event_intake.js,
  - identical page functions for storage (store, shape), relay calls (call), QR planning (qrPlan, qrMatrix, qrChunks),
  - changes only in screens, in the QR / tow summary TEXT, and in log texts (a colon got blanks: "가 : 나").
So this run checks once each what the design work could have broken: getting to every screen and back without the rail,
119 opens the dialer only, a virtual event still changes the screen, one SOS round with the test relay, a guide that survives a restart,
and the new QR texts (single, large, split, tow) with three independent readers.

Safety: virtual coordinates, loopback test relay (127.0.0.1 / 10.0.2.2), per-run tokens as intent extras, ACTION_DIAL only (no call is placed).
  python run_aurora_smoke.py"""
import base64, json, os, secrets, subprocess, sys, time, uuid, tempfile
from pathlib import Path
import cv2, numpy as np

H = Path(r'<PROJECT>\SEVEN_PILLARS_HARDENING_WORK')
sys.path.insert(0, str(H / 'sos' / 'relay')); sys.path.insert(0, str(H / 'sos' / 'clients'))
from relay_core import token_hash  # noqa: E402
from relay_client import RelayClient  # noqa: E402

ADB = r'C:\Android\Sdk\platform-tools\adb.exe'
PY = sys.executable
JAVA = r'C:\Android\jdk\jdk-21.0.13+9\bin\java.exe'
ZDIR = str(H / 'fullhmi' / 'verification' / 'zxing_reader')
ZJAR = r'C:\Android\gradle\caches\modules-2\files-2.1\com.google.zxing\core\3.5.3\ca1349214a356cd7958651b2d5a0e1f3811a9c4b\core-3.5.3.jar'
PKG = 'com.contest.resafety.fullhmi.aurora'
ACT = PKG + '/com.contest.resafety.fullhmi.MainActivity'
PORT = 8773
HOST_BASE, APP_BASE = f'http://127.0.0.1:{PORT}', f'http://10.0.2.2:{PORT}'
RUN = Path(__file__).resolve().parent / 'evidence' / ('smoke_' + time.strftime('%Y%m%d_%H%M%S'))
RUN.mkdir(parents=True, exist_ok=True)
results = []


def rec(name, ok, detail=''):
    detail = detail if isinstance(detail, str) else json.dumps(detail, ensure_ascii=False)
    results.append({'check': name, 'pass': bool(ok), 'detail': detail})
    print(('PASS ' if ok else 'FAIL ') + name + ' :: ' + detail, flush=True)


def adb(*a, binary=False, timeout=60):
    r = subprocess.run([ADB, '-s', 'emulator-5554', *a], capture_output=True, timeout=timeout)
    return r.stdout if binary else r.stdout.decode('utf-8', 'replace')


def lines():
    return adb('logcat', '-d', '-v', 'raw', '-s', 'FullHMI:I').splitlines()


def page():
    ls = lines()
    i = max((k for k, l in enumerate(ls) if l.startswith('STATE ')), default=-1)
    if i < 0:
        return {}, {}
    try:
        st = json.loads(ls[i][6:])
    except ValueError:
        return {}, {}
    rects = {}
    for l in ls[i + 1:]:
        if l.startswith('RECT '):
            p = l.split()
            if len(p) >= 6:
                rects[p[1]] = tuple(int(v) for v in p[2:6])
    return st, rects


def settled(n=20):
    prev = None
    for _ in range(n):
        cur = page()
        if cur[0] and cur == prev:
            return cur
        prev = cur; time.sleep(0.4)
    return prev or ({}, {})


def logs(sub):
    return [l for l in lines() if sub in l]


def tap(key, wait=1.1):
    _, r = settled()
    if key not in r:
        return False
    x, y, w, h = r[key]
    if w <= 0 or h <= 0:
        return False
    adb('shell', 'input', 'tap', str(x + w // 2), str(y + h // 2)); time.sleep(wait)
    return True


def tapv(key, wait=1.1):
    """Scroll the control into the visible part of the panel first (the page reports new rectangles after a scroll)."""
    for _ in range(8):
        _, r = settled()
        if key not in r:
            return False
        x, y, w, h = r[key]
        if 300 <= y and y + h <= 1352:
            return tap(key, wait)
        if y + h > 1352:
            adb('shell', 'input', 'swipe', '1900', '1150', '1900', str(1150 - min(780, y + h - 1352 + 120)), '500')
        else:
            adb('shell', 'input', 'swipe', '1900', '500', '1900', str(500 + min(780, 300 - y + 120)), '500')
        time.sleep(1.2)
    return False


def wait_state(pred, timeout=12):
    end = time.time() + timeout; st = {}
    while time.time() < end:
        st, _ = page()
        if st and pred(st):
            return st
        time.sleep(0.5)
    return st


def shot(name):
    p = RUN / (name + '.png'); p.write_bytes(adb('exec-out', 'screencap', '-p', binary=True)); return p


def launch(extras=(), clear=False):
    adb('shell', 'am', 'force-stop', PKG)
    if clear:
        adb('shell', 'pm', 'clear', PKG)
    time.sleep(1.5); adb('logcat', '-c')
    for _ in range(3):
        adb('shell', 'am', 'start', '-n', ACT, *extras); t1 = time.time(); time.sleep(3)
        while not page()[0] and time.time() - t1 < 20:
            time.sleep(1)
        if page()[0]:
            break
        adb('shell', 'am', 'force-stop', PKG); time.sleep(2)
    time.sleep(2)
    if 'ImmersiveModeConfirmation' in adb('shell', 'dumpsys', 'window', 'windows'):
        adb('shell', 'input', 'tap', '1492', '402'); time.sleep(1)
    return settled()


def calls():
    return [l for l in adb('shell', 'dumpsys', 'telecom').splitlines() if 'Call TC@' in l]


def device_ms():
    return int(adb('shell', 'date', '+%s').strip()) * 1000


def inject(e):
    adb('shell', 'am', 'start', '-n', ACT, '--es', 'vevent_b64', base64.b64encode(json.dumps(e).encode()).decode('ascii')); time.sleep(2.5)
    return settled()


# three independent QR readers on the app pane (x >= 1030). ZxDecode looks only at the right half of a picture: the pane is put on a white sheet.
std, aru = cv2.QRCodeDetector(), cv2.QRCodeDetectorAruco()


def read_qr(paths):
    tmp = Path(tempfile.mkdtemp(prefix='qrsmoke_')); sheets = []
    panes = {}
    for p in paths:
        im = cv2.imdecode(np.fromfile(str(p), np.uint8), cv2.IMREAD_COLOR)[:, 1030:]; panes[p.name] = im
        s = tmp / p.name; cv2.imencode('.png', np.hstack([np.full_like(im, 255), im]))[1].tofile(str(s)); sheets.append(str(s))
    z = {}
    for l in subprocess.run([JAVA, '-cp', ZDIR + ';' + ZJAR, 'ZxDecode', *sheets], capture_output=True, timeout=300).stdout.decode('utf-8', 'replace').splitlines():
        n, b = l.split('\t'); z[n] = '' if b == '-' else base64.b64decode(b).decode('utf-8')
    out = {}
    for p in paths:
        im = panes[p.name]
        r = [std.detectAndDecode(im)[0], aru.detectAndDecode(im)[0], z.get(p.name, '')]
        best = max(set(x for x in r if x), key=r.count, default='')
        out[p.name] = (best if r.count(best) >= 2 else '', r)          # accepted only when 2 of the 3 readers read the same text
    return out


labels = ['A', 'B']
ids = {l: str(uuid.uuid4()) for l in labels}; tokens = {l: secrets.token_urlsafe(24) for l in labels}
(RUN / 'registry_public.json').write_text(json.dumps({ids[l]: {'token_hash': token_hash(tokens[l]), 'label': l} for l in labels}), encoding='utf-8')
(RUN / 'ids_private.json').write_text(json.dumps({ids[l]: {'label': l} for l in labels}), encoding='utf-8')
server, procs = None, []
t_start = time.time()
try:
    ver = [l.strip() for l in adb('shell', 'dumpsys', 'package', PKG).splitlines() if 'versionName' in l][:1]
    apk = adb('shell', 'pm', 'path', PKG).strip().replace('package:', '')
    rec('S0 build under test', 'adaptive4' in ''.join(ver), f"{ver} apk sha256={adb('shell', 'sha256sum', apk).split()[0].upper() if apk else '-'}")

    # ---- S1 READY: every card opens its screen and the back button returns home ----
    st, r = launch(clear=True)
    rec('S1a ready home: four cards and the two left buttons, no page error', all(k in r for k in ('cpre', 'cseat', 'cseatctl', 'crec', 'accOn', 'lsys', 'lgo')) and st.get('pageErrors') == 0,
        {'hmi': st.get('hmi'), 'keys': sorted(k for k in r if not k.startswith('sys_'))})
    nav = {}
    for k, want in (('cpre', 'pre'), ('cseat', 'seat'), ('cseatctl', 'seatctl'), ('crec', 'record'), ('lsys', 'info')):
        tap(k); a = settled()[0].get('screen'); tap('back'); b = settled()[0].get('screen'); nav[k] = f'{a}->{b}'
    shot('s1_ready_after_nav')
    rec('S1b ready: each card opens its screen and "← 홈" returns home', all(v == f'{w}->home' for (k, w), v in zip((('cpre', 'pre'), ('cseat', 'seat'), ('cseatctl', 'seatctl'), ('crec', 'record'), ('lsys', 'info')), nav.values())), nav)

    # ---- S2 ACCIDENT: every card and the emergency buttons ----
    tap('accOn'); st, r = settled(); shot('s2_accident_home')
    need = ('b119', 'bsos', 'cprot', 'cguide', 'cseat', 'ctow', 'cpre', 'crec', 'recvset', 'l119', 'belt', 'accOff')
    rec('S2a accident home: 119, SOS, six cards, SOS settings and the two left buttons', st.get('hmi') == 'ACC' and all(k in r for k in need), {k: k in r for k in need})
    nav = {}
    for k, want in (('cprot', 'prot'), ('cseat', 'seat'), ('ctow', 'tow'), ('cpre', 'pre'), ('crec', 'record'), ('recvset', 'sos')):
        tap(k); s = settled()[0]; a = s.get('screen'); tap('back'); b = settled()[0].get('screen'); nav[k] = f'{a}->{b}'
    tap('cguide'); a = settled()[0].get('screen'); tap('back'); b = settled()[0].get('screen'); nav['cguide'] = f'{a}->{b}'
    ok = all(nav[k] == f'{w}->home' for k, w in (('cprot', 'prot'), ('cseat', 'seat'), ('ctow', 'tow'), ('cpre', 'pre'), ('crec', 'record'), ('recvset', 'sos'), ('cguide', 'guide')))
    rec('S2b accident: each card opens its screen and "← 홈" returns home (SOS settings opens the SOS screen)', ok and settled()[0].get('pageErrors') == 0, nav)

    # ---- S3 119 opens the dialer only ----
    c0 = len(calls()); tap('b119', 3); d = logs('DIAL_SCREEN_OPENED'); shot('s3_dialer')
    rec('S3 the 119 banner opens the dialer with 119 and places no call', any('number=119' in l for l in d) and len(calls()) == c0, (d or ['no log'])[-1] + f' | calls {c0}->{len(calls())}')
    adb('shell', 'input', 'keyevent', 'KEYCODE_BACK'); time.sleep(1)

    # ---- S4 virtual vehicle events change the screen (logic unchanged; the screens around it changed) ----
    launch(clear=True)
    e1 = {'type': 'crash', 'id': 'smoke-' + uuid.uuid4().hex[:6], 'source': 'virtual', 'level': 'SUSPECTED', 'score': 0.6, 'scenario': 'smoke', 'ts': device_ms()}
    st, r = inject(e1); shot('s4_prompt')
    prompt_ok = st.get('ev', {}).get('prompt') is True and 'evopen' in r and st.get('hmi') == 'NORMAL'
    tap('evopen'); st2 = wait_state(lambda s: s.get('hmi') == 'ACC', 6); shot('s4_after_open')
    rec('S4a a SUSPECTED event shows the question first; "사고 대응 화면 열기" switches to the accident screen', prompt_ok and st2.get('hmi') == 'ACC' and st2.get('screen') == 'home', f"prompt={prompt_ok} -> hmi={st2.get('hmi')}")
    n0 = settled()[0].get('ev', {}).get('n'); st3, _ = inject(e1)
    rec('S4b the same event again is recognised as a duplicate and changes nothing', st3.get('ev', {}).get('dup', 0) >= 1 and st3.get('hmi') == 'ACC' and st3.get('ev', {}).get('n') == n0, st3.get('ev', {}))
    launch(clear=True)
    e2 = {'type': 'crash', 'id': 'smoke-' + uuid.uuid4().hex[:6], 'source': 'virtual', 'level': 'DETECTED', 'score': 0.9, 'scenario': 'smoke', 'ts': device_ms()}
    st, _ = inject(e2); shot('s4_detected')
    rec('S4c a DETECTED event switches straight to the accident screen', st.get('hmi') == 'ACC' and st.get('ev', {}).get('prompt') is False, {'hmi': st.get('hmi'), 'ev': st.get('ev')})

    # ---- S5 one SOS round with the loopback test relay ----
    server = subprocess.Popen([PY, str(H / 'sos' / 'relay' / 'relay_server.py'), '--registry', str(RUN / 'registry_public.json'), '--port', str(PORT), '--log', str(RUN / 'server.jsonl'),
                               '--cooldown', '20', '--request-ttl', '90', '--presence-ttl', '10', '--max-per-window', '30', '--window', '600', '--max-req-per-min', '600'],
                              stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    up = False
    for _ in range(30):
        try:
            up = RelayClient(HOST_BASE, ids['A'], tokens['A']).health()[0] == 200
        except Exception:
            up = False
        if up:
            break
        time.sleep(0.3)
    env = dict(os.environ, RELAY_TOKEN_B=tokens['B'], PYTHONIOENCODING='utf-8')
    procs.append(subprocess.Popen([PY, str(H / 'sos' / 'clients' / 'client_agent.py'), '--mode', 'listen', '--label', 'B', '--registry', str(RUN / 'ids_private.json'), '--base', HOST_BASE,
                                   '--east', '100', '--north', '0', '--action', 'ack', '--out', str(RUN / 'B_alerts.jsonl'), '--seconds', '120', '--receive'], env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL))
    time.sleep(4)
    ex = ['--es', 'relay_base', APP_BASE, '--es', 'relay_id', ids['A'], '--es', 'relay_token', tokens['A'], '--ef', 'relay_east', '0', '--ef', 'relay_north', '0']
    launch(ex, clear=True); tap('accOn'); tap('bsos')
    st = wait_state(lambda s: s.get('screen') == 'sos', 5); consent = st.get('sos') == 'consent'; shot('s5_consent')
    tap('agree', 0.3); seen = []
    end = time.time() + 30
    while time.time() < end:
        st, _ = page()
        if st and (not seen or seen[-1] != st.get('sos')):
            seen.append(st.get('sos'))
        if st.get('sos') == 'acked':
            break
        time.sleep(0.3)
    shot('s5_sos_acked')
    srv = (RUN / 'server.jsonl').read_text(encoding='utf-8') if (RUN / 'server.jsonl').exists() else ''
    got = [json.loads(l) for l in (RUN / 'B_alerts.jsonl').read_text(encoding='utf-8').splitlines() if '"event": "alert"' in l] if (RUN / 'B_alerts.jsonl').exists() else []
    rec('S5 SOS from the accident home: consent, one request, delivered to the test receiver, acknowledged', up and consent and st.get('sos') == 'acked' and srv.count('"sos_created"') == 1 and len(got) == 1,
        f"relay={up} consent={consent} phases={seen} created={srv.count('sos_created')} receiver alerts={len(got)}")
    import zipfile, io
    raw = adb('exec-out', 'cat', apk, binary=True, timeout=120)
    page_txt = zipfile.ZipFile(io.BytesIO(raw)).read('assets/index.html').decode('utf-8')
    rec('S5b (first run checked the log, but record lines are not logged) the page inside the installed APK: SOS record lines use "가 : 나"',
        page_txt.count('근처 SOS : ') >= 6 and page_txt.count('근처 SOS: ') == 0, f"'근처 SOS : ' x{page_txt.count('근처 SOS : ')}, '근처 SOS: ' x{page_txt.count('근처 SOS: ')}")

    # ---- S6 guide, restart in the middle, QR texts ----
    tap('back'); tap('cguide')
    for k in ('chk0', 'chk1', 'chk2', 'chk3'):
        tap(k, 0.6)
    tap('gnext', 0.8); tap('minorp', 0.5); tap('majorp', 0.5); tap('gnext', 0.8); tap('gnext', 0.8); tap('shloc', 0.6); tap('gnext', 1.5)
    st = wait_state(lambda s: s.get('guide') == 4, 6)
    launch(ex); st, r = settled()
    rec('S6a a guide in progress survives an app restart (step 5 of 6, the same inputs)', st.get('gactive') is True and st.get('guide') == 4 and st.get('checks') == 4 and st.get('minor') == 1 and st.get('major') == 1,
        {k: st.get(k) for k in ('gactive', 'guide', 'checks', 'minor', 'major', 'screen', 'hmi')})
    if st.get('screen') != 'guide':
        tap('cguide')
    st = wait_state(lambda s: s.get('screen') == 'guide' and s.get('guide') == 4, 6); time.sleep(1)
    pics = [shot('s6_qr_small')]
    tap('qr', 1.5); pics.append(shot('s6_qr_large'))
    st, r = settled(); parts, mode = [], 'single'
    if 'qrauto' in r or 'qrsplit' in r:
        mode = 'auto' if 'qrauto' in r else 'offered'
        if 'qrsplit' in r:
            tap('qrsplit', 1.5)
        k = settled()[0].get('qrParts') or 2
        for i in range(k):
            parts.append(shot(f's6_qr_part{i + 1}')); tap('qrnext', 1.5)
    dec = read_qr(pics + parts)
    small, large = dec[pics[0].name][0], dec[pics[1].name][0]
    if parts:
        txt = [dec[p.name][0] for p in parts]
        whole = '\n'.join(x.split('\n', 1)[1] if x.startswith('(') else x for x in txt)
        rec('S6c the long summary is shown as several QRs; every part is read (2 of 3 readers) and the parts together give one text',
            all(txt) and all(x.startswith(f'({i + 1}/{len(txt)})') for i, x in enumerate(txt)) and small == txt[0], {'mode': mode, 'parts': len(parts), 'read': [bool(x) for x in txt], 'small view = part 1': small == txt[0]})
    else:
        whole = large
        rec('S6c single QR: small and large view read the same text', bool(large) and small == large, {'mode': mode})
    (RUN / 's6_qr_text.txt').write_text(whole, encoding='utf-8')
    L = whole.split('\n')
    form = (L[0] == '[RE:SAFETY] 사고 요약 (Full HMI 시연)' and any(l.startswith('시각 : ') for l in L) and '부상 : 경미 1명 · 중상 1명 (탑승자 입력)' in L
            and '현장 조치 : 4개 중 4개 확인 (탑승자 입력)' in L and '차량 : 정차 · 기어 P (시뮬레이션 값)' in L and any(l.startswith('위치 : ') and '시험용 가상 위치' in l for l in L) and L[-1] == '※ 시연 데이터 · 신고 아님')
    rec('S6b the guide QR text, put together, is the new briefing form with "가 : 나" (when, where, injuries, actions, vehicle)', form, {'chars': len(whole), 'lines': L})
    tap('qr', 1.0)

    # ---- S7 tow / insurance QR (its text changed too) ----
    tap('back'); tap('ctow'); tapv('townow', 0.6)
    st, r = settled()
    if 'towpii' in r:
        tapv('towpii', 0.6)
    tapv('towqr', 1.5); st, r = settled()
    tapv('towqrbox', 1.5); st, r = settled(); p = shot('s7_tow_qr_large'); tparts = []
    if 'qrauto' in r:
        for i in range(st.get('qrParts') or 2):
            tparts.append(shot(f's7_tow_qr_part{i + 1}')); tap('qrnext', 1.5)
    dt = read_qr([p] + tparts)
    t = '\n'.join(dt[x.name][0].split('\n', 1)[1] if dt[x.name][0].startswith('(') else dt[x.name][0] for x in tparts) if tparts else dt[p.name][0]
    (RUN / 's7_tow_qr_text.txt').write_text(t, encoding='utf-8')
    T = t.split('\n')
    rec('S7 the insurance/tow QR is read (2 of 3 readers) and has the new form', bool(t) and T[0].startswith('[RE:SAFETY] ') and '요약 (Full HMI 시연)' in T[0] and any(l.startswith('사고 시각 : ') for l in T)
        and T[-1] == '※ 자동 접수·자동 출동 아님 · 신고 아님' and not any(':' in l and ' : ' not in l and not l.startswith('사고 시각') for l in T[1:-1]), T)
    st = settled()[0]
    rec('S8 no page error during the whole run', st.get('pageErrors') == 0, f"pageErrors={st.get('pageErrors')}")
finally:
    for p in procs:
        p.terminate()
    if server:
        server.terminate()
    passed = sum(r['pass'] for r in results)
    out = {'build': PKG, 'passed': passed, 'total': len(results), 'minutes': round((time.time() - t_start) / 60, 1), 'results': results}
    (RUN / 'results.json').write_text(json.dumps(out, ensure_ascii=False, indent=1), encoding='utf-8')
    print(f'\naurora smoke: {passed}/{len(results)} in {out["minutes"]} min  ->  {RUN}')
