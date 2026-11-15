"""Read the QR code in screen pictures with OpenCV (the picture is read through numpy: cv2.imread cannot open non-ASCII paths).
Tries the full picture, then 0.5x and 2x. Prints one line per picture and writes <picture>.decoded.txt next to it.
  python qrdec.py <png> [<png> ...]"""
import sys
from pathlib import Path
import cv2, numpy as np


def read(p):
    im = cv2.imdecode(np.fromfile(str(p), dtype=np.uint8), cv2.IMREAD_COLOR)
    d = cv2.QRCodeDetector()
    for f in (1.0, 0.5, 2.0):
        x = im if f == 1.0 else cv2.resize(im, None, fx=f, fy=f, interpolation=cv2.INTER_AREA if f < 1 else cv2.INTER_NEAREST)
        t, pts, _ = d.detectAndDecode(x)
        if t:
            return t, f
    return '', None


ok = 0
for a in sys.argv[1:]:
    t, f = read(a)
    Path(a + '.decoded.txt').write_text(t, encoding='utf-8')
    ok += bool(t)
    print(f'{Path(a).name}: {"READ" if t else "NOT READ"} scale={f} chars={len(t)} first="{t.splitlines()[0] if t else ""}"', flush=True)
print(f'read {ok} of {len(sys.argv) - 1}')
