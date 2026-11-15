"""Visual regression, part 2: compare the pictures of two walks (walk_screens.py) screen by screen.
For each screen: share of pixels that differ inside the app window, the rectangles where they differ, and a side-by-side picture with those
rectangles drawn. A pixel differs when any colour channel is more than 24 apart (below that is picture-compression noise).
  python compare_shots.py <dir before> <dir after> <out dir>"""
import json, sys
from pathlib import Path
import cv2, numpy as np

A, B, OUT = Path(sys.argv[1]), Path(sys.argv[2]), Path(sys.argv[3])
OUT.mkdir(parents=True, exist_ok=True)
wa, wb = (json.loads((d / 'walk.json').read_text(encoding='utf-8')) for d in (A, B))
fx, fy, fr, fb = wa['frame']; assert wa['frame'] == wb['frame'], 'the two walks used different app windows'


def read(p):
    return cv2.imdecode(np.fromfile(str(p), dtype=np.uint8), cv2.IMREAD_COLOR)


rows = []
for sa in wa['shots']:
    n = sa['name']; pa, pb = A / (n + '.png'), B / (n + '.png')
    if not pb.exists(): rows.append({'screen': n, 'missing_after': True}); continue
    ia, ib = read(pa)[fy:fb, fx:fr], read(pb)[fy:fb, fx:fr]
    d = (np.abs(ia.astype(np.int16) - ib.astype(np.int16)).max(axis=2) > 24).astype(np.uint8)
    pct = 100.0 * d.mean()
    boxes = []
    if d.any():
        m = cv2.dilate(d, np.ones((25, 25), np.uint8))                      # join the letters of one changed text into one rectangle
        cnt, _, stats, _ = cv2.connectedComponentsWithStats(m, 8)
        boxes = sorted(([int(s[0]), int(s[1]), int(s[2]), int(s[3])] for s in stats[1:] if s[4] > 150), key=lambda b: (b[1], b[0]))
    side = np.hstack([ia, np.full((ia.shape[0], 12, 3), 255, np.uint8), ib.copy()])
    for x, y, w, h in boxes:
        for off in (0, ia.shape[1] + 12): cv2.rectangle(side, (off + x, y), (off + x + w, y + h), (0, 255, 255), 3)
    side = cv2.resize(side, (side.shape[1] // 2, side.shape[0] // 2), interpolation=cv2.INTER_AREA)
    cv2.imencode('.png', side)[1].tofile(str(OUT / (n + '_before_after.png')))
    sb = next((s for s in wb['shots'] if s['name'] == n), {})
    rows.append({'screen': n, 'same_screen_reached': sa.get('screen') == sb.get('screen') and sa.get('hmi') == sb.get('hmi'), 'changed_pct': round(pct, 3), 'changed_rects_xywh': boxes})
res = {'before': {'apk': wa['apk'], 'sha256': wa['sha256']}, 'after': {'apk': wb['apk'], 'sha256': wb['sha256']}, 'app_window': wa['frame'],
       'pixel_threshold': 24, 'screens': rows}
(OUT / 'compare.json').write_text(json.dumps(res, ensure_ascii=False, indent=1), encoding='utf-8')
for r in rows:
    print(f"{r['screen']:<24} same_screen={r.get('same_screen_reached')} changed={r.get('changed_pct')}% rects={len(r.get('changed_rects_xywh', []))} {r.get('changed_rects_xywh', [])[:6]}")
