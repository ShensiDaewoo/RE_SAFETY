"""Nearby SOS, RECEIVING side, on the installed ② APK (0.6.4-adaptive10), native screen pictures (2560 x 1440 PNG).
A loopback test relay runs on this PC (127.0.0.1; the emulator reaches it as 10.0.2.2). Two test identities with fresh tokens:
  APP = the RE:SAFETY app on the emulator, SOS 알림 받기 = on
  E   = a test sender (Python client) 100 m away (virtual position) that sends one SOS
Pictures: the alert on the app, then the state after the app pressed 확인, and the sender's status (ACKED count).
Nothing leaves this PC; positions are virtual; no call is placed.
  python capture_sos_receive.py <hardening dir> <out dir>"""
import json, re, secrets, subprocess, sys, time, uuid
from pathlib import Path

H, OUT = Path(sys.argv[1]), Path(sys.argv[2]); OUT.mkdir(parents=True, exist_ok=True)
sys.path.insert(0, str(H / 'sos' / 'relay')); sys.path.insert(0, str(H / 'sos' / 'clients'))
from relay_core import token_hash  # noqa: E402
from relay_client import RelayClient  # noqa: E402

ADB = r'C:\Android\Sdk\platform-tools\adb.exe'
PKG = 'com.contest.resafety.fullhmi.aurora'
ACT = PKG + '/com.contest.resafety.fullhmi.MainActivity'
PORT = 8774
HOST_BASE, APP_BASE = f'http://127.0.0.1:{PORT}', f'http://10.0.2.2:{PORT}'
log = []


def say(s): print(time.strftime('%H:%M:%S'), s, flush=True); log.append(time.strftime('%H:%M:%S ') + s)
def adb(*a, timeout=60): return subprocess.run([ADB, *a], capture_output=True, timeout=timeout).stdout.decode('utf-8', 'replace')


USER = adb('shell', 'am', 'get-current-user').strip() or '0'
CACHE = {}


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
    CACHE.update(rects); return st or {}, dict(CACHE, **rects)


def tap(key, wait=1.5):
    fx, fy, _, _ = frame()
    for _ in range(8):
        st, rects = page()
        if key in rects:
            x, y, w, h = rects[key]; adb('shell', 'input', 'tap', str(fx + x + w // 2), str(fy + y + h // 2)); say('tap ' + key); time.sleep(wait); return True
        time.sleep(0.5)
    say('NOT FOUND ' + key); return False


def wait_state(cond, sec):
    end = time.time() + sec
    while time.time() < end:
        st, _ = page()
        if cond(st): return st
        time.sleep(0.4)
    return page()[0]


def shot(name):
    adb('shell', 'screencap', '-p', '/sdcard/fig.png'); adb('pull', '/sdcard/fig.png', str(OUT / (name + '.png'))); say('saved ' + name + '.png')


ids = {l: str(uuid.uuid4()) for l in ('APP', 'E')}; tokens = {l: secrets.token_urlsafe(24) for l in ids}
(OUT / 'registry_public.json').write_text(json.dumps({ids[l]: {'token_hash': token_hash(tokens[l]), 'label': l} for l in ids}), encoding='utf-8')
server = subprocess.Popen([sys.executable, str(H / 'sos' / 'relay' / 'relay_server.py'), '--registry', str(OUT / 'registry_public.json'), '--port', str(PORT),
                           '--log', str(OUT / 'server.jsonl'), '--cooldown', '20', '--request-ttl', '90', '--presence-ttl', '10', '--max-per-window', '30',
                           '--window', '600', '--max-req-per-min', '600'], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
res = {}
try:
    up = False
    for _ in range(30):
        try: up = RelayClient(HOST_BASE, ids['E'], tokens['E']).health()[0] == 200
        except Exception: up = False
        if up: break
        time.sleep(0.3)
    say(f'relay up={up}')
    adb('shell', 'am', 'force-stop', PKG); adb('logcat', '-c')
    adb('shell', 'am', 'start', '--user', USER, '-n', ACT, '--es', 'relay_base', APP_BASE, '--es', 'relay_id', ids['APP'], '--es', 'relay_token', tokens['APP'],
        '--ef', 'relay_east', '0', '--ef', 'relay_north', '0'); time.sleep(9)
    st, _ = page()
    if st.get('hmi') != 'ACC': tap('accOn', 1.0); time.sleep(5.0)          # the SOS screen is reached from the accident home; WARNING takes 4 s
    tap('recvset', 2.0)
    st = wait_state(lambda s: s.get('screen') == 'sos', 6)
    if not st.get('recv'): tap('recv', 2.0)
    st = wait_state(lambda s: s.get('recv') is True and s.get('conn') == 'ok', 10); say(f"receive on={st.get('recv')} conn={st.get('conn')}")
    time.sleep(3)
    e = RelayClient(HOST_BASE, ids['E'], tokens['E'], 'E'); e.position = (100, 0); e.presence(receive=False)
    kind, _, resp = e.send_sos(); say(f'sender E: {kind}')
    st = wait_state(lambda s: s.get('alerts', 0) > 0, 15); res['alert_shown'] = st.get('alerts', 0) > 0
    time.sleep(1.0); shot('S2_sos_received_on_pleos')
    res['ack_tapped'] = tap('ack', 3.0)
    counts = (e.status(resp['request_id'])[1].get('counts') if resp else {}) or {}
    res.update({'sender_result': kind, 'sender_counts_after_ack': counts}); say('sender status counts: ' + json.dumps(counts))
    shot('S3_after_ack_on_pleos')
    tap('recv', 1.5); tap('back', 1.5); tap('accOff', 2.0)
finally:
    server.terminate()
srv = (OUT / 'server.jsonl').read_text(encoding='utf-8') if (OUT / 'server.jsonl').exists() else ''
res.update({'relay': 'loopback test relay on this PC (127.0.0.1:%d), virtual positions' % PORT, 'server_events': {k: srv.count('"' + k + '"') for k in ('sos_created', 'delivered', 'acked')}})
(OUT / 'sos_receive_result.json').write_text(json.dumps(res, ensure_ascii=False, indent=1), encoding='utf-8')
(OUT / 'capture_sos_receive.log.txt').write_text('\n'.join(log), encoding='utf-8')
print(json.dumps(res, ensure_ascii=False))
