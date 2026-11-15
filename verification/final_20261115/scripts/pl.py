"""Small step tool for the final evidence runs on the Pleos Connect emulator (user 10). Every step writes its own file into <out dir>
and appends one line to <out dir>/steps.log (time, command, result). Nothing here places a call or sends anything.
  python pl.py <out dir> shot NAME            screen picture
  python pl.py <out dir> ui NAME              accessibility dump (texts on the screen) -> NAME.xml and NAME.txt
  python pl.py <out dir> tap "TEXT" [exact]   tap the element whose text (or content-desc) starts with TEXT
  python pl.py <out dir> xy X Y               tap a point
  python pl.py <out dir> car NAME             Pleos Virtual Console /vehicle/state -> NAME.json (full) + summary line
  python pl.py <out dir> put PATH             Virtual Console PUT
  python pl.py <out dir> log NAME [TAG ...]   logcat -d (all, or only these tags) -> NAME.txt
  python pl.py <out dir> sh NAME CMD...       adb shell CMD -> NAME.txt"""
import json, re, subprocess, sys, time, urllib.request
from pathlib import Path

ADB = r'C:\Android\Sdk\platform-tools\adb.exe'
BASE = 'http://127.0.0.1:8088'
OUT = Path(sys.argv[1]); OUT.mkdir(parents=True, exist_ok=True)
cmd, args = sys.argv[2], sys.argv[3:]


def adb(*a, binary=False, timeout=90):
    r = subprocess.run([ADB, *a], capture_output=True, timeout=timeout)
    return r.stdout if binary else r.stdout.decode('utf-8', 'replace')


def step(s):
    line = time.strftime('%H:%M:%S') + '  ' + s
    print(line, flush=True)
    with open(OUT / 'steps.log', 'a', encoding='utf-8') as f: f.write(line + '\n')


def dump():
    adb('shell', 'uiautomator', 'dump', '/sdcard/u.xml')
    return adb('shell', 'cat', '/sdcard/u.xml')


def texts(x):
    return [t for t in re.findall(r'(?:text|content-desc)="([^"]+)"', x) if t.strip()]


def car():
    return json.loads(urllib.request.urlopen(BASE + '/vehicle/state', timeout=8).read().decode('utf-8'))


def summary(j):
    b, l = j.get('body', {}), j.get('lights', {})
    hm = l.get('headlamp_mode'); hm = hm.get('value', hm) if isinstance(hm, dict) else hm
    try:
        locks = [b['door_locks'][k] for k in ('front_left', 'front_right', 'rear_left', 'rear_right')]
    except Exception:
        locks = '?'
    return {'gear': j.get('gear', {}).get('actual', {}).get('value'), 'speed_kph': j.get('controls', {}).get('speed_kph'),
            'locks': locks, 'cabin': b.get('cabin_light', {}).get('on'), 'headlamp_mode': hm, 'web_active': j.get('web_active')}


if cmd == 'shot':
    adb('shell', 'screencap', '-p', '/sdcard/c.png'); adb('pull', '/sdcard/c.png', str(OUT / (args[0] + '.png'))); step('shot ' + args[0])
elif cmd == 'ui':
    x = dump(); (OUT / (args[0] + '.xml')).write_text(x, encoding='utf-8'); t = texts(x)
    (OUT / (args[0] + '.txt')).write_text('\n'.join(t), encoding='utf-8'); step(f'ui {args[0]}: ' + ' / '.join(t)[:900])
elif cmd == 'tap':
    exact = len(args) > 1 and args[1] == 'exact'
    for _ in range(5):
        x = dump()
        pat = r'(?:text|content-desc)="' + re.escape(args[0]) + (r'"' if exact else r'[^"]*"') + r'[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'
        m = re.search(pat, x)
        if m:
            px, py = (int(m.group(1)) + int(m.group(3))) // 2, (int(m.group(2)) + int(m.group(4))) // 2
            adb('shell', 'input', 'tap', str(px), str(py)); step(f'tap "{args[0]}" at ({px},{py})'); break
        time.sleep(1.2)
    else:
        step(f'NOT FOUND "{args[0]}"'); sys.exit(3)
elif cmd == 'xy':
    adb('shell', 'input', 'tap', args[0], args[1]); step(f'tap point ({args[0]},{args[1]})')
elif cmd == 'car':
    j = car(); (OUT / (args[0] + '.json')).write_text(json.dumps(j, ensure_ascii=False, indent=1), encoding='utf-8')
    step(f'car {args[0]}: ' + json.dumps(summary(j), ensure_ascii=False))
elif cmd == 'put':
    try:
        st = urllib.request.urlopen(urllib.request.Request(BASE + args[0], method='PUT'), timeout=8).status
    except Exception as e:
        st = getattr(e, 'code', -1)
    step(f'put {args[0]} -> {st}')
elif cmd == 'log':
    a = ['logcat', '-d', '-v', 'time'] + (['-s'] + list(args[1:]) if len(args) > 1 else [])
    t = adb(*a); (OUT / (args[0] + '.txt')).write_text(t, encoding='utf-8'); step(f'log {args[0]}: {len(t.splitlines())} lines')
elif cmd == 'sh':
    t = adb('shell', *args[1:]); (OUT / (args[0] + '.txt')).write_text(t, encoding='utf-8'); step(f'sh {args[0]}: ' + t.strip()[:400].replace('\n', ' | '))
else:
    sys.exit('unknown command ' + cmd)
