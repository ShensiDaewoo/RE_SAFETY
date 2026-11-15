"""Takes the red warning triangle (and its mirror image on the wet road) out of the accident picture handed over by the project owner.
No image generation: the place is covered with REAL wet-road texture taken from another part of the SAME picture (Poisson blending,
cv2.seamlessClone), the small tip in front of the traffic cone is filled from its surroundings, and the lane marking that ran behind the
triangle is drawn through again in the colour measured on both sides. First attempt (plain cv2.inpaint over the whole area) smeared: not used.
  python remove_triangle.py <source webp> <out png> <preview png>"""
import sys
from pathlib import Path
import cv2, numpy as np

src, out, prev = Path(sys.argv[1]), Path(sys.argv[2]), Path(sys.argv[3])
im = cv2.imdecode(np.fromfile(str(src), np.uint8), cv2.IMREAD_COLOR)
if im is None:
    from PIL import Image
    im = cv2.cvtColor(np.array(Image.open(src).convert('RGB')), cv2.COLOR_RGB2BGR)
h, w = im.shape[:2]
res = im.copy()


def poly(points, shape=None, off=(0, 0)):
    m = np.zeros(shape or (h, w), np.uint8)
    cv2.fillPoly(m, [np.array([(x - off[0], y - off[1]) for x, y in points], np.int32)], 255)
    return m


def cover(dst, zone_pts, src_xy):
    """Blend road texture from src_xy (top-left of a patch as large as the zone's bounding box) over the polygon zone_pts."""
    xs, ys = [p[0] for p in zone_pts], [p[1] for p in zone_pts]
    x0, y0, x1, y1 = min(xs) - 3, min(ys) - 3, max(xs) + 3, max(ys) + 3
    pw, ph = x1 - x0, y1 - y0
    patch = im[src_xy[1]:src_xy[1] + ph, src_xy[0]:src_xy[0] + pw].copy()
    mask = poly(zone_pts, (ph, pw), (x0, y0))
    o = cv2.seamlessClone(patch, dst, mask, (x0 + pw // 2, y0 + ph // 2), cv2.NORMAL_CLONE)
    # the blending pulls a grey veil in from bright reflections at the edge of the zone: the part of the low-frequency
    # difference to the pasted texture that is common to all three colours is taken out again (a red glow stays, a grey veil goes)
    reg = o[y0:y1, x0:x1].astype(np.float32)
    veil = np.clip(cv2.GaussianBlur(reg - patch.astype(np.float32), (0, 0), 12).min(axis=2), 0, None)
    wgt = cv2.GaussianBlur(cv2.erode(mask, np.ones((9, 9), np.uint8)).astype(np.float32) / 255, (0, 0), 5)
    o[y0:y1, x0:x1] = np.clip(reg - (veil * wgt * 0.9)[..., None], 0, 255).astype(np.uint8)
    return o


# lane marking: where it enters and leaves the area (brightest row in a column just outside the area), measured BEFORE anything is changed
def line_at(x, ya, yb):
    col = im[ya:yb, x - 2:x + 3].astype(np.float32).min(axis=2).mean(axis=1)
    col = cv2.GaussianBlur(col.reshape(-1, 1), (1, 9), 0).ravel()
    yc = int(col.argmax()); thr = (col.max() + np.median(col)) / 2
    on = np.flatnonzero(col >= thr)
    return ya + yc, max(3, int(on.max() - on.min() + 1)), im[ya + yc - 1:ya + yc + 2, x - 2:x + 3].reshape(-1, 3).mean(axis=0)


yl, wl, cl = line_at(8, 975, 1040)
yr, wr, cr = line_at(266, 825, 885)
wr = min(wr, 10); wl = min(wl, 14)                              # the marking gets narrower with distance; the right-hand measurement also caught a light reflection
# 1 the tip of the triangle in front of the traffic cone: every row is filled from the cone pixels left and right of the tip
#   (second try; the first, cv2.inpaint over the red pixels only, left the dark rim of the triangle and smeared the foot of the cone)
apex = poly([(125, 818), (98, 868), (154, 868)]) > 0
for y in np.flatnonzero(apex.any(axis=1)):
    xs = np.flatnonzero(apex[y]); xa, xb = xs.min() - 1, xs.max() + 1
    ca = res[y, xa - 2:xa + 1].astype(np.float32).mean(axis=0); cb = res[y, xb:xb + 3].astype(np.float32).mean(axis=0)
    k = ((np.arange(xa + 1, xb) - xa) / (xb - xa))[:, None]
    res[y, xa + 1:xb] = (ca * (1 - k) + cb * k).astype(np.uint8)
res[apex] = cv2.GaussianBlur(res, (3, 5), 0)[apex]
# 2 the triangle with its stand, below the cone: real road texture from the dark road at the lower right of the same picture
res = cover(res, [(98, 866), (154, 866), (254, 1010), (260, 1046), (8, 1046), (10, 1010)], (650, 1196))     # starts under the base of the cone
# 3 its mirror image on the road
res = cover(res, [(10, 1040), (258, 1040), (236, 1150), (170, 1300), (84, 1300), (26, 1150)], (640, 1236))
# 4 the lane marking that ran behind the triangle
n = 260
layer = np.zeros((h, w, 3), np.float32); alpha = np.zeros((h, w), np.float32)
for i in range(n + 1):
    t = i / n
    x = 8 + (266 - 8) * t; y = yl + (yr - yl) * t; half = (wl + (wr - wl) * t) / 2
    col = cl + (cr - cl) * t
    cv2.circle(layer, (int(round(x)), int(round(y))), int(round(half)), [float(c) for c in col], -1)
    cv2.circle(alpha, (int(round(x)), int(round(y))), int(round(half)), 1.0, -1)
zone = (poly([(98, 868), (154, 868), (256, 1008), (262, 1050), (6, 1050), (8, 1008)]) > 0).astype(np.float32)
alpha = cv2.GaussianBlur(alpha * zone, (0, 0), 1.6)[..., None] * 0.6
res = np.clip(res.astype(np.float32) * (1 - alpha) + cv2.GaussianBlur(layer, (0, 0), 1.2) * alpha, 0, 255).astype(np.uint8)
cv2.imencode('.png', res, [cv2.IMWRITE_PNG_COMPRESSION, 9])[1].tofile(str(out))
pv = np.hstack([im[760:1320, 0:440], np.full((560, 8, 3), 255, np.uint8), res[760:1320, 0:440]])
cv2.imencode('.png', pv)[1].tofile(str(prev))
changed = np.abs(res.astype(np.int16) - im.astype(np.int16)).max(axis=2) > 6
print('picture', (w, h), ' lane marking: left y', yl, 'width', wl, ' right y', yr, 'width', wr, ' changed pixels', f'{100 * changed.mean():.2f} % of the picture')
