"""Target main screen (scene A, READY) as ONE picture, made from the real parts:
  - the middle is the real aurora17 page (same HTML/CSS/pictures), rendered by a headless browser in the target arrangement:
    no own top strip, no letterbox, the Aurora car scene takes the whole left side, the RE:SAFETY panel keeps its exact proportions;
  - the top status bar and the bottom system bar are pixels of a real Pleos Connect emulator screenshot.
It is a composed target picture, not a screenshot of a running build.
  python make_mock.py <assets dir> <pleos screenshot> <work dir> <out png> [accident]"""
import shutil, subprocess, sys
from pathlib import Path
import cv2, numpy as np

ASSETS, PLEOS, WORK, OUT = Path(sys.argv[1]), Path(sys.argv[2]), Path(sys.argv[3]), Path(sys.argv[4])
ACC = len(sys.argv) > 5 and sys.argv[5] == 'accident'
EDGE = r'C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe'
W, H, TOP, BOTTOM = 2560, 1440, 90, 140                       # Pleos display: top bar 90 px, bottom bar 140 px -> 1210 px for the scene
MID = H - TOP - BOTTOM
REM = MID / 13.58                                             # the page's own height under its top strip is 13.58rem

site = WORK / 'site'
if site.exists(): shutil.rmtree(site)
shutil.copytree(ASSETS, site)
html = (site / 'index.html').read_text(encoding='utf-8')
override = f"""
<style>
html{{font-size:{REM:.4f}px !important}}
body{{background:#000a14}}
#stage{{left:0 !important;top:-.82rem !important;transform:none !important;width:100vw !important;height:14.4rem !important}}
#top{{display:none !important}}                                  /* the status bar is the Pleos system's */
#left{{width:calc(100vw - 15.3rem) !important}}                  /* the car scene takes all the width the panel does not need */
#app{{left:auto !important;width:14.84rem !important}}           /* the panel keeps its proportions */
.gear,.adas{{display:none !important}}                           /* gear and driver-assist signs are shown by the Pleos bar */
#left[data-bg="photo"][data-driving-state="normal"]{{--bg-size:104% !important;--bg-shift:-1.5rem !important}}
#left[data-bg="photo"][data-driving-state="accident"]{{--bg-size:104% !important;--bg-shift:-1.7rem !important}}
</style>
<script>
window.addEventListener('load', () => setTimeout(() => {{
  const p = document.querySelector('#headline p'), pill = document.querySelector('#headline .cpill');
  if (!document.body.classList.contains('acc')) {{
    if (p) p.innerHTML = '사고 대응은 오른쪽 RE:SAFETY에서 언제든 열 수 있습니다.<br>기어 P · 0 km/h · 문 모두 닫힘';
    if (pill) pill.textContent = 'Pleos 차량 신호';
  }}
}}, 600));
</script>
"""
assert html.count('</body>') == 1
(site / 'mock.html').write_text(html.replace('</body>', override + '</body>'), encoding='utf-8')
shot = WORK / 'middle.png'
url = (site / 'mock.html').resolve().as_uri() + ('?state=accident' if ACC else '')
subprocess.run([EDGE, '--headless=new', '--disable-gpu', '--hide-scrollbars', '--force-device-scale-factor=1', f'--window-size={W},{MID}',
                '--virtual-time-budget=6000', '--allow-file-access-from-files', f'--screenshot={shot}', url], check=True, timeout=120,
               stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
mid = cv2.imdecode(np.fromfile(str(shot), dtype=np.uint8), cv2.IMREAD_COLOR)
pleos = cv2.imdecode(np.fromfile(str(PLEOS), dtype=np.uint8), cv2.IMREAD_COLOR)
print('middle', mid.shape, 'pleos', pleos.shape)
assert pleos.shape[:2] == (H, W)
if mid.shape[:2] != (MID, W): mid = cv2.resize(mid, (W, MID), interpolation=cv2.INTER_AREA)
canvas = pleos.copy()
canvas[TOP:H - BOTTOM] = mid
OUT.parent.mkdir(parents=True, exist_ok=True)
cv2.imencode('.png', canvas)[1].tofile(str(OUT))
print('written', OUT)
