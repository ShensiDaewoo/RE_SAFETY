"""ADAPTIVE8 step, run AFTER patch_adaptive7_warning.py.
  1  Seat occupancy was shown the wrong way round on the Pleos emulator (found 2026-11-15, evidence P2-08): Vehicle SDK 2.3.2 on this image
     returns true for an EMPTY seat (VHAL raw 1 = VACANT). The R2D build reads it that way (SdkSeatAdapter); this build read true as occupied.
     Now: true -> "n" (reported empty), false -> "y" (possibly occupied), anything else -> "" (unknown). Seat belts were already right.
  2  WARNING screen: on Pleos the accident response opens while the app is still behind the navigation, so the 4 s could run out before
     anyone saw it. The 4 s now start again when the app comes back to the front.
  python patch_adaptive8_seatfix_warn.py <work dir>"""
import sys
from pathlib import Path

W = Path(sys.argv[1]); assert W.name == 'RESAFETY_ADAPTIVE_HMI_WORK'
A = W / 'signal-probe-fullhmi/app'
kt, idx, gr = A / 'src/main/java/com/contest/resafety/fullhmi/VehicleLink.kt', A / 'src/main/assets/index.html', A / 'build.gradle.kts'


def rep(p, a, b):
    t = p.read_text(encoding='utf-8'); assert t.count(a) == 1, (p.name, a[:80], t.count(a)); p.write_text(t.replace(a, b), encoding='utf-8', newline='\n')


rep(kt, """{ b -> note(seatOcc, "SEAT_OCC", key, when (b) { true -> "y"; false -> "n"; else -> "" }) }""",
    """{ b -> note(seatOcc, "SEAT_OCC", key, when (b) { true -> "n"; false -> "y"; else -> "" }) /* adaptive8: SDK true = VACANT on this image, as R2D SdkSeatAdapter */ }""")
rep(idx, """S.warnSrc = ''; S.warnN = 0; let warnTimer = 0;""", """S.warnSrc = ''; S.warnN = 0; let warnTimer = 0;
const armWarn = () => { clearTimeout(warnTimer); warnTimer = setTimeout(hideWarn, 4000); };   // adaptive8: the 4 s start again when the app comes to the front""")
rep(idx, """el.classList.add('on'); S.warnN++; clearTimeout(warnTimer); warnTimer = setTimeout(hideWarn, 4000);""",
    """el.classList.add('on'); S.warnN++; armWarn();""")
rep(idx, """window.onShown = () => { if (sceneSync('app shown again')) render(); };""",
    """window.onShown = () => { if (sceneSync('app shown again')) render(); if ($('accwarn').classList.contains('on')) { armWarn(); console.log('WARN re-armed: app in front'); } };""")
g = gr.read_text(encoding='utf-8'); assert 'versionCode = 56' in g and '0.6.1-adaptive7' in g
gr.write_text(g.replace('versionCode = 56', 'versionCode = 57').replace('0.6.1-adaptive7', '0.6.2-adaptive8'), encoding='utf-8', newline='\n')
print('adaptive8 applied')
