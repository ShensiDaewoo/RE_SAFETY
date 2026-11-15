"""ADAPTIVE7 step, run AFTER patch_adaptive6_more.py. One new layer: a short WARNING screen right before the accident response screen opens.
  - shown once, when scene ACCIDENT_RESPONSE opens after a fresh accident start (signal, simulation button, driver's choice on the
    suspected-collision prompt, or Gleo voice call). Not shown when the app restarts into a saved accident state.
  - "WARNING!" blinks at 1 Hz (well under the 3 flashes per second limit); no blinking when the system asks for reduced motion.
  - closes by itself after 4 s, or at once on a tap. The accident screen underneath is the unchanged Aurora screen.
  - every accident signal in this build is simulated, so the screen says so. It does not name an accident type (person / vehicle):
    neither the virtual event nor the public SDK reports one.
  python patch_adaptive7_warning.py <work dir>"""
import sys
from pathlib import Path

W = Path(sys.argv[1]); assert W.name == 'RESAFETY_ADAPTIVE_HMI_WORK'
idx = W / 'signal-probe-fullhmi/app/src/main/assets/index.html'
gr = W / 'signal-probe-fullhmi/app/build.gradle.kts'
t = idx.read_text(encoding='utf-8')
assert 'preCarHtml' in t, 'run patch_adaptive6_more.py first'
assert 'accwarn' not in t, 'already applied'


def rep(a, b):
    global t
    assert t.count(a) == 1, (a[:90], t.count(a)); t = t.replace(a, b)


# ---- style (outside #stage: position fixed must not follow the transformed stage of the Pleos layout)
rep("""/* AURORA-THEME-END */""", """/* ===== adaptive7: WARNING screen before the accident response screen ===== */
#accwarn{position:fixed;inset:0;z-index:60;display:none;flex-direction:column;align-items:center;justify-content:center;text-align:center;
  font-family:var(--font-ui);color:#fff;background:radial-gradient(ellipse at 50% 45%,#5e0616 0,#24020a 62%,#0b0105 100%);cursor:pointer}
#accwarn p{margin:0}
#accwarn.on{display:flex}
#accwarn::before{content:"";position:absolute;inset:.18rem;border:.08rem solid #ff2b4a;border-radius:.36rem;box-shadow:0 0 .6rem rgba(255,43,74,.75),inset 0 0 .6rem rgba(255,43,74,.45);animation:wpulse 1s ease-in-out infinite}
#accwarn .wchip{font-size:.34rem;font-weight:800;color:#3a0610;background:#FFD9E2;border-radius:.4rem;padding:.08rem .3rem;margin-bottom:.34rem}
#accwarn .wtitle{display:flex;align-items:center;gap:.36rem;font-size:1.9rem;font-weight:900;letter-spacing:.04rem;line-height:1;color:#ff2b4a;
  text-shadow:0 0 .3rem rgba(255,43,74,.9),0 0 .9rem rgba(255,43,74,.55);animation:wblink 1s steps(1,end) infinite}
#accwarn .wtitle svg{width:1.6rem;height:1.6rem}
#accwarn .wl1{margin-top:.5rem;font-size:.66rem;font-weight:800;line-height:1.3}
#accwarn .wl2{margin-top:.12rem !important;font-size:.66rem;font-weight:800;line-height:1.3;color:#FFD9E2}
#accwarn .wbar{margin-top:.62rem;width:7rem;height:.1rem;border-radius:.1rem;background:rgba(255,255,255,.18);overflow:hidden}
#accwarn .wbar i{display:block;height:100%;width:100%;background:#fff;transform-origin:left;animation:wbar 4s linear forwards}
#accwarn .wtap{margin-top:.2rem;font-size:.3rem;font-weight:700;color:rgba(255,255,255,.72)}
@keyframes wblink{0%{opacity:1}50%{opacity:.15}}
@keyframes wpulse{0%,100%{opacity:.55}50%{opacity:1}}
@keyframes wbar{from{transform:scaleX(1)}to{transform:scaleX(0)}}
@media (prefers-reduced-motion:reduce){#accwarn .wtitle,#accwarn::before{animation:none}}
/* AURORA-THEME-END */""")

# ---- markup: after #stage
rep("""  <div id="toast" role="status"></div>
</div>""", """  <div id="toast" role="status"></div>
</div>
<div id="accwarn" role="alertdialog" aria-live="assertive" aria-label="경고 : 사고 대응 모드 작동" data-k="accwarn"></div>""")

# ---- the layer and when it shows
rep("""function enterAcc(text, src) { const was = S.hmi;""", """/* adaptive7: WARNING screen. S.warnSrc is set only by a fresh accident start; sceneSync shows it when ACCIDENT_RESPONSE opens. */
S.warnSrc = ''; S.warnN = 0; let warnTimer = 0;
function showWarn(src) {
  const el = $('accwarn'), voice = src === 'voice';
  el.innerHTML = `<span class="wchip">${voice ? '음성 호출 · Gleo' : '가상 신호 · 시뮬레이션'}</span>
    <div class="wtitle"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 2.5 22.5 21h-21Z" fill="#ff2b4a"/><path d="M12 9v5.6" stroke="#fff" stroke-width="2.4" stroke-linecap="round"/><circle cx="12" cy="17.6" r="1.4" fill="#fff"/></svg>WARNING!</div>
    <p class="wl1" data-k="warnl1">${voice ? '음성으로 사고 대응을 요청하셨습니다.' : '충돌 신호가 감지되었습니다.'}</p>
    <p class="wl2" data-k="warnl2">사고 대응 모드를 작동합니다.</p>
    <div class="wbar"><i></i></div><p class="wtap">화면을 누르면 바로 넘어갑니다</p>`;
  el.classList.add('on'); S.warnN++; clearTimeout(warnTimer); warnTimer = setTimeout(hideWarn, 4000);
  console.log('WARN shown src=' + src + ' n=' + S.warnN);
}
function hideWarn() { clearTimeout(warnTimer); const el = $('accwarn'); if (el.classList.contains('on')) { el.classList.remove('on'); console.log('WARN closed'); } }
$('accwarn').addEventListener('click', hideWarn);
function enterAcc(text, src) { if (S.hmi !== 'ACC') S.warnSrc = 'sig'; const was = S.hmi;""")
rep("""  evOpen: () => { intake.userOpen();""", """  evOpen: () => { S.warnSrc = 'sig'; intake.userOpen();""")
rep("""  drvAcc: () => { intake.userStart();""", """  drvAcc: () => { S.warnSrc = 'sig'; intake.userStart();""")
rep("""  accOn: () => { intake.userStart();""", """  accOn: () => { S.warnSrc = 'sig'; intake.userStart();""")
rep("""  if (S.hmi !== 'ACC') { intake.userStart(); S.hmi = 'ACC'; }
  if (name === 'OPEN_ACCIDENT_GUIDE')""", """  if (S.hmi !== 'ACC') { S.warnSrc = 'voice'; intake.userStart(); S.hmi = 'ACC'; }
  if (name === 'OPEN_ACCIDENT_GUIDE')""")
rep("""  if (next === 'ACCIDENT_RESPONSE' && prev === 'ACCIDENT_PENDING') log('정차 확인 : 사고 대응 화면을 엽니다', 'app');""",
    """  if (next === 'ACCIDENT_RESPONSE' && prev === 'ACCIDENT_PENDING') log('정차 확인 : 사고 대응 화면을 엽니다', 'app');
  if (next === 'ACCIDENT_RESPONSE' && S.warnSrc) { const s = S.warnSrc; S.warnSrc = ''; setTimeout(() => showWarn(s), 0); }
  if (next === 'READY' || next === 'DRIVING') { S.warnSrc = ''; hideWarn(); }""")

# ---- test hook
rep("""  console.log('STATE ' + JSON.stringify({ scene: S.scene,""", """  console.log('STATE ' + JSON.stringify({ warn: S.warnN, warnOn: $('accwarn').classList.contains('on'), scene: S.scene,""")

idx.write_text(t, encoding='utf-8', newline='\n')
g = gr.read_text(encoding='utf-8')
assert 'versionCode = 55' in g and '0.6.0-adaptive6' in g
gr.write_text(g.replace('versionCode = 55', 'versionCode = 56').replace('0.6.0-adaptive6', '0.6.1-adaptive7'), encoding='utf-8', newline='\n')
print('adaptive7 step applied; lines', t.count('\n') + 1)
