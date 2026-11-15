"""Pictures for the seat-control screen, cut out of the concept picture the project owner handed over (827 x 745; the owner said it was made
with ChatGPT). No image generation here: crop, clean, enlarge 2x (Lanczos) and a transparent edge.
  car   : the cabin seen from above. The names printed on the seats are filled in (the screen draws its own), and the driver's seat, which
          carried a printed "selected" glow, is replaced by the mirror image of the passenger's seat so that the screen can mark ANY seat.
  seat  : the leather seat with the rings behind it; soft transparent edge left, right and top.
  side  : the blue side view with the arrows; the dark card behind it is made transparent.
  python make_seatctl_assets.py <reference png> <work folder> <preview png>"""
import sys, json, hashlib
from pathlib import Path
import cv2, numpy as np

ref, W, prev = Path(sys.argv[1]), Path(sys.argv[2]), Path(sys.argv[3])
P = W / 'signal-probe-fullhmi'; hmi = P / 'app/src/main/assets/assets/hmi'
im = cv2.imdecode(np.fromfile(str(ref), np.uint8), cv2.IMREAD_COLOR)
assert im.shape[:2] == (745, 827), im.shape


def up(a, f=2):
    b = cv2.resize(a, None, fx=f, fy=f, interpolation=cv2.INTER_LANCZOS4).astype(np.float32)
    if a.shape[2] == 4:
        c, al = b[..., :3], b[..., 3:]
    else:
        c, al = b, None
    c = np.clip(c + 0.45 * (c - cv2.GaussianBlur(c, (0, 0), 1.6)), 0, 255)
    return (np.dstack([c, np.clip(al, 0, 255)]) if al is not None else c).astype(np.uint8)


def save(name, a):
    cv2.imencode('.png', a, [cv2.IMWRITE_PNG_COMPRESSION, 9])[1].tofile(str(hmi / name))
    return {'file': 'assets/hmi/' + name, 'pixels': [a.shape[1], a.shape[0]], 'sha256': hashlib.sha256((hmi / name).read_bytes()).hexdigest().upper()}


# ---------------- car ----------------
X0, Y0, X1, Y1 = 22, 86, 288, 620
car = im.copy()
labels = [(165, 313, 237, 340), (71, 456, 145, 486), (163, 456, 238, 486)]          # 조수석, 뒷좌석 좌, 뒷좌석 우 (printed)
m = np.zeros(car.shape[:2], np.uint8)
for a, b, c, d in labels:
    cv2.rectangle(m, (a, b), (c, d), 255, -1)
car = cv2.inpaint(car, m, 4, cv2.INPAINT_TELEA)
AX = 154                                                                            # mirror axis: middle between the two front seats
sx0, sx1, sy0, sy1 = 160, 243, 233, 356                                             # passenger's seat with a margin
patch = car[sy0:sy1, sx0:sx1][:, ::-1]
tx0, tx1 = 2 * AX - sx1, 2 * AX - sx0
al = np.zeros((sy1 - sy0, sx1 - sx0), np.float32); al[3:-3, 3:-3] = 1; al = cv2.GaussianBlur(al, (0, 0), 1.5)[..., None]
car[sy0:sy1, tx0:tx1] = (car[sy0:sy1, tx0:tx1] * (1 - al) + patch * al).astype(np.uint8)
crop = car[Y0:Y1, X0:X1]
# background = dark pixels connected to the border of the crop
dark = (crop.max(axis=2) < 46).astype(np.uint8)
ff = dark.copy(); h, w = ff.shape; mask = np.zeros((h + 2, w + 2), np.uint8)
for x in range(w):
    for y in (0, h - 1):
        if ff[y, x] == 1: cv2.floodFill(ff, mask, (x, y), 2)
for y in range(h):
    for x in (0, w - 1):
        if ff[y, x] == 1: cv2.floodFill(ff, mask, (x, y), 2)
alpha = cv2.GaussianBlur(((ff != 2) * 255).astype(np.float32), (0, 0), 1.0)
car_rgba = up(np.dstack([crop, alpha.astype(np.uint8)]))
# ---------------- leather seat ----------------
sx0, sy0, sx1, sy1 = 498, 72, 772, 252
seat = im[sy0:sy1, sx0:sx1]
hh, ww = seat.shape[:2]
xs = np.minimum(np.arange(ww), ww - 1 - np.arange(ww)) / 34.0; ys = np.arange(hh) / 26.0
a = np.clip(xs, 0, 1)[None, :] * np.clip(ys, 0, 1)[:, None]
seat_rgba = up(np.dstack([seat, (a * a * (3 - 2 * a) * 255).astype(np.uint8)]))
# ---------------- side view ----------------
dx0, dy0, dx1, dy1 = 474, 264, 640, 408
side = im[dy0:dy1, dx0:dx1].astype(np.float32)
bg = np.median(np.vstack([side[0], side[-1], side[:, 0], side[:, -1]]), axis=0)
d = np.clip((side - bg).max(axis=2) / 38.0, 0, 1)
d = cv2.GaussianBlur(d, (0, 0), 0.7)
hh, ww = d.shape
ex = np.clip(np.minimum(np.arange(ww), ww - 1 - np.arange(ww)) / 8.0, 0, 1)[None, :] * np.clip(np.minimum(np.arange(hh), hh - 1 - np.arange(hh)) / 8.0, 0, 1)[:, None]
side_rgba = up(np.dstack([side.astype(np.uint8), (d * ex * 255).astype(np.uint8)]))

note = {'source': {'what': 'Concept picture of the seat-control screen handed over by the project owner in the work session of 2026-11-14; the owner said it was made with ChatGPT.',
                   'pixels': [827, 745], 'sha256': hashlib.sha256(ref.read_bytes()).hexdigest().upper(),
                   'provenance': 'Under which terms the picture may be published was NOT stated; to be confirmed by the project owner before submission.'},
        'car': dict(save('SEATCTL_CAR_TOP.png', car_rgba), edit='crop; printed seat names filled in; driver seat replaced by the mirror image of the passenger seat (the printed "selected" glow is gone); background made transparent; enlarged 2x'),
        'seat': dict(save('SEATCTL_SEAT_3D.png', seat_rgba), edit='crop; transparent soft edge left, right and top; enlarged 2x'),
        'side': dict(save('SEATCTL_SEAT_SIDE.png', side_rgba), edit='crop; the card colour behind the drawing made transparent; enlarged 2x'),
        'seat_boxes_per_cent_of_car_picture': {'D': [18.7, 28.7], 'P': [53.2, 28.7], 'RL': [18.7, 54.2], 'RR': [53.2, 54.2], 'size': [27.6, 20.6]}}
(P / 'hmi-assets/installed/SEATCTL_PICTURES_NOTE.json').write_text(json.dumps(note, ensure_ascii=False, indent=1), encoding='utf-8')
(P / 'hmi-assets/previous').mkdir(parents=True, exist_ok=True)
(P / 'hmi-assets/previous/SEATCTL_concept_picture_as_handed_over.png').write_bytes(ref.read_bytes())


def on_bg(rgba, col=(38, 25, 5)):
    a = rgba[..., 3:].astype(np.float32) / 255
    return (rgba[..., :3] * a + np.array(col, np.float32) * (1 - a)).astype(np.uint8)


c, s, d2 = on_bg(car_rgba), on_bg(seat_rgba), on_bg(side_rgba)
H = c.shape[0]; canvas = np.full((H, c.shape[1] + s.shape[1] + 30, 3), (38, 25, 5), np.uint8)
canvas[:, :c.shape[1]] = c; canvas[20:20 + s.shape[0], c.shape[1] + 20:c.shape[1] + 20 + s.shape[1]] = s
canvas[440:440 + d2.shape[0], c.shape[1] + 20:c.shape[1] + 20 + d2.shape[1]] = d2
cv2.imencode('.png', canvas)[1].tofile(str(prev))
print('car', car_rgba.shape, 'seat', seat_rgba.shape, 'side', side_rgba.shape)
