"""ADAPTIVE9 step, run AFTER patch_adaptive8_seatfix_warn.py.
On the Pleos emulator (adaptive8 run, 2026-11-15 17:35) the WARNING screen was shown at 53.4 s, the "app shown" hook re-armed it at 53.5 s,
but the app was really in front only at 55.8 s, so it was visible for about 1.7 s. Timers run while the app is behind the navigation;
animation frames do not. The 4 s now start inside the second animation frame after the screen is shown or re-shown, so they count only
while the page is actually drawn. The progress bar restarts at the same moment.
  python patch_adaptive9_warn_visible.py <work dir>"""
import sys
from pathlib import Path

W = Path(sys.argv[1]); assert W.name == 'RESAFETY_ADAPTIVE_HMI_WORK'
A = W / 'signal-probe-fullhmi/app'
idx, gr = A / 'src/main/assets/index.html', A / 'build.gradle.kts'


def rep(p, a, b):
    t = p.read_text(encoding='utf-8'); assert t.count(a) == 1, (p.name, a[:80], t.count(a)); p.write_text(t.replace(a, b), encoding='utf-8', newline='\n')


rep(idx, """const armWarn = () => { clearTimeout(warnTimer); warnTimer = setTimeout(hideWarn, 4000); };""",
    """let warnArm = 0;
const armWarn = () => {                      // adaptive9: count the 4 s only from a drawn frame (frames stop while the app is behind another app)
  clearTimeout(warnTimer); const my = ++warnArm;
  requestAnimationFrame(() => requestAnimationFrame(() => {
    if (my !== warnArm || !$('accwarn').classList.contains('on')) return;
    const bar = $('accwarn').querySelector('.wbar i'); if (bar) { bar.style.animation = 'none'; void bar.offsetWidth; bar.style.animation = ''; }
    warnTimer = setTimeout(hideWarn, 4000); console.log('WARN countdown started (frame drawn)');
  }));
};""")
rep(idx, """function hideWarn() { clearTimeout(warnTimer);""", """function hideWarn() { clearTimeout(warnTimer); warnArm++;""")
g = gr.read_text(encoding='utf-8'); assert 'versionCode = 57' in g and '0.6.2-adaptive8' in g
gr.write_text(g.replace('versionCode = 57', 'versionCode = 58').replace('0.6.2-adaptive8', '0.6.3-adaptive9'), encoding='utf-8', newline='\n')
print('adaptive9 applied')
