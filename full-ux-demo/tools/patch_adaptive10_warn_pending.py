"""ADAPTIVE10 step, run AFTER patch_adaptive9_warn_visible.py.
Seen in the demo recording on the Pleos emulator (2026-11-15 17:45, part1 frames 33-41 s): after an accident signal while driving, the app
sits behind the Pleos "not while driving" cover. When the car is put in P the cover lifts at once and the accident HOME is visible, but the
scene becomes ACCIDENT_RESPONSE only after the 2 s parked check, so the WARNING screen came AFTER the accident home. Wrong order.
Now, on Pleos only (VEHMODE): the WARNING layer goes up already at ACCIDENT_PENDING (the system keeps the app covered while moving, so
nothing new is shown on the driving screen) and is held without a countdown; its 4 s start when ACCIDENT_RESPONSE opens.
The stand-alone prototype is unchanged (its scene B is a visible place-holder; the WARNING still comes after "정차 확인").
  python patch_adaptive10_warn_pending.py <work dir>"""
import sys
from pathlib import Path

W = Path(sys.argv[1]); assert W.name == 'RESAFETY_ADAPTIVE_HMI_WORK'
A = W / 'signal-probe-fullhmi/app'
idx, gr = A / 'src/main/assets/index.html', A / 'build.gradle.kts'


def rep(p, a, b):
    t = p.read_text(encoding='utf-8'); assert t.count(a) == 1, (p.name, a[:80], t.count(a)); p.write_text(t.replace(a, b), encoding='utf-8', newline='\n')


rep(idx, """el.classList.add('on'); S.warnN++; armWarn();""",
    """el.classList.add('on'); S.warnN++; if (S.warnHold) { clearTimeout(warnTimer); warnArm++; console.log('WARN held until parked'); } else armWarn();""")
rep(idx, """S.warnSrc = ''; S.warnN = 0; let warnTimer = 0;""", """S.warnSrc = ''; S.warnN = 0; S.warnHold = false; let warnTimer = 0;""")
rep(idx, """function hideWarn() { clearTimeout(warnTimer); warnArm++;""", """function hideWarn() { clearTimeout(warnTimer); warnArm++; S.warnHold = false;""")
rep(idx, """  if (next === 'ACCIDENT_RESPONSE' && S.warnSrc) { const s = S.warnSrc; S.warnSrc = ''; setTimeout(() => showWarn(s), 0); }""",
    """  if (next === 'ACCIDENT_PENDING' && VEHMODE && S.warnSrc) { const s = S.warnSrc; S.warnSrc = ''; S.warnHold = true; setTimeout(() => showWarn(s), 0); }
  if (next === 'ACCIDENT_RESPONSE' && S.warnHold && $('accwarn').classList.contains('on')) { S.warnHold = false; armWarn(); console.log('WARN countdown: accident response open'); }
  if (next === 'ACCIDENT_RESPONSE' && S.warnSrc) { const s = S.warnSrc; S.warnSrc = ''; setTimeout(() => showWarn(s), 0); }""")
rep(idx, """  if (next === 'READY' || next === 'DRIVING') { S.warnSrc = ''; hideWarn(); }""",
    """  if (next === 'READY' || next === 'DRIVING') { S.warnSrc = ''; S.warnHold = false; hideWarn(); }""")
g = gr.read_text(encoding='utf-8'); assert 'versionCode = 58' in g and '0.6.3-adaptive9' in g
gr.write_text(g.replace('versionCode = 58', 'versionCode = 59').replace('0.6.3-adaptive9', '0.6.4-adaptive10'), encoding='utf-8', newline='\n')
print('adaptive10 applied')
