"""ADAPTIVE step: the scene controller (READY / DRIVING / ACCIDENT_PENDING / ACCIDENT_RESPONSE). Run AFTER patch_aurora_step1.py and
patch_aurora13_vehicle.py.  READY = our Aurora, DRIVING = native Pleos, ACCIDENT = our Aurora.
The Aurora scenes (READY, ACCIDENT_RESPONSE) are not redrawn: same markup, same CSS as aurora17.
  python patch_adaptive_scenes.py <work dir>"""
import sys
from pathlib import Path

W = Path(sys.argv[1]); assert W.name == 'RESAFETY_ADAPTIVE_HMI_WORK'
idx = W / 'signal-probe-fullhmi/app/src/main/assets/index.html'
t = idx.read_text(encoding='utf-8')
assert 'sceneSync' not in t, 'already applied'


def rep(a, b):
    global t
    assert t.count(a) == 1, (a[:90], t.count(a)); t = t.replace(a, b)


# ---- markup: one layer for scene B in the stand-alone prototype
rep("""  <div id="evprompt" role="alertdialog\"""", """  <div id="drive" aria-label="주행 중 : Pleos 기본 화면 자리 (독립 시연본)"></div>
  <div id="evprompt" role="alertdialog\"""")

# ---- Pleos runtime: panel-only layout from the first frame
rep("""const VEHMODE = NATIVE && typeof window.Native.prot === 'function' && window.Native.prot() !== '';   // aurora13: a Pleos vehicle is connected""",
    """const VEHMODE = NATIVE && typeof window.Native.prot === 'function' && window.Native.prot() !== '';   // aurora13: a Pleos vehicle is connected
if (VEHMODE) document.documentElement.classList.add('pleos');   // adaptive: inside the Pleos application window only the RE:SAFETY panel is drawn""")

# ---- the controller
rep("""function renderInner() {
  renderLeft(); renderChrome();""", """/* ===== RE:SAFETY Context-Aware HMI Orchestrator (scene controller) =====
   ONE place decides which scene owns the screen.
     inputs : accident state (S.hmi) and motion (gear and speed from the Pleos vehicle; a simulation switch in the stand-alone prototype)
     scenes : READY and ACCIDENT_RESPONSE  -> RE:SAFETY Aurora
              DRIVING and ACCIDENT_PENDING -> Pleos native Driving View and navigation
   On Pleos the app hands the screen over through window.Native.scene (NaviHelper.requestNavigationScreen) and the system itself keeps the
   app covered while the car is not parked. The stand-alone prototype cannot call a Pleos system screen: it shows a labelled place-holder.
   An accident signal while moving never replaces the driving information: the full accident screen opens after the car is parked. */
S.scene = 'READY'; S.simMove = false; S.sceneLog = [];
let mvRaw = 'parked', mvSince = 0, mvHeld = 'parked', mvTimer = 0;
function motion() {
  if (!VEHMODE) return S.simMove ? 'moving' : 'parked';
  const v = vehSnap(), raw = (v.kmh == null || !v.gear) ? 'unknown' : (v.gear === 'P' && v.kmh === 0) ? 'parked' : 'moving', now = Date.now();
  if (raw !== mvRaw) { mvRaw = raw; mvSince = now; }
  if (raw === 'moving') mvHeld = 'moving';                              // moving is believed at once
  else if (raw === 'parked' && mvHeld === 'moving') {                   // parked must hold for 2 s (seen on the emulator: one "parked" sample while the car was still slowing down)
    if (now - mvSince >= 2000) mvHeld = 'parked';
    else { clearTimeout(mvTimer); mvTimer = setTimeout(() => { if (sceneSync('parked for 2 s')) render(); }, 2050 - (now - mvSince)); }
  }
  return mvHeld;                                                        // a lost signal keeps the last known state; it never turns "moving" into "parked"
}
const sceneFor = (accident, m) => accident ? (m === 'moving' ? 'ACCIDENT_PENDING' : 'ACCIDENT_RESPONSE') : (m === 'moving' ? 'DRIVING' : 'READY');
function sceneSync(why) {                                   // true when the scene changed
  const m = motion(), next = sceneFor(S.hmi === 'ACC', m), prev = S.scene;
  if (next === prev) return false;
  S.scene = next; S.qrBig = false; S.sceneLog.push(prev + '>' + next); if (S.sceneLog.length > 30) S.sceneLog.shift();
  console.log(`SCENE ${prev} -> ${next} motion=${m} src=${VEHMODE ? 'vehicle' : 'sim'} why=${why || 'render'}`);
  if (next === 'ACCIDENT_PENDING') log('사고 신호 (시뮬레이션) : 주행 중이라 사고 대응 화면은 정차 뒤에 엽니다', 'app');
  if (next === 'ACCIDENT_RESPONSE' && prev === 'ACCIDENT_PENDING') log('정차 확인 : 사고 대응 화면을 엽니다', 'app');
  if (NATIVE && typeof window.Native.scene === 'function') window.Native.scene(next, prev);
  return true;
}
window.onShown = () => { if (sceneSync('app shown again')) render(); };      // the page sleeps while another app is in front
function renderDrive() {                                    // stand-alone prototype only; on Pleos the system shows scene B itself
  const on = S.scene === 'DRIVING' || S.scene === 'ACCIDENT_PENDING', pend = S.scene === 'ACCIDENT_PENDING', el = $('drive');
  document.body.classList.toggle('drv', on);
  if (!VEHMODE) { const sp = $('tspd'), gr = $('tgear'); if (sp) sp.innerHTML = `${on ? 30 : 0}<small>km/h</small>`;
    if (gr) gr.innerHTML = ['P', 'R', 'N', 'D'].map(g => g === (on ? 'D' : 'P') ? `<b>${g}</b>` : `<span>${g}</span>`).join(''); }
  if (!on || VEHMODE) { if (el.innerHTML) el.innerHTML = ''; return; }
  el.className = pend ? 'pend' : '';
  el.innerHTML = `${pend ? `<div class="dalert" data-k="dalert"><b>사고 신호 감지 (시뮬레이션)</b><span>주행 정보는 그대로 둡니다. 안전한 곳에 정차하면 사고 대응 화면이 열립니다.</span></div>` : ''}
    <div class="dv" data-k="dview"><span class="dtag">자리 표시 · Pleos 시스템 화면 아님</span><h2>Pleos 기본 Driving View</h2><p>주행 중 차량 정보와 주행 보조 표시는<br>Pleos 시스템 화면이 맡습니다.</p>
      <div class="dspd"><b>30</b><small>km/h</small><i>D</i></div><em>시뮬레이션 값</em></div>
    <div class="nv" data-k="dnavi"><span class="dtag">자리 표시 · 실제 지도 아님</span>${svg('lane', 2.6)}<h2>Pleos 내비게이션</h2><p>지도와 경로 안내는 Pleos 내비게이션이 맡습니다.<br>RE:SAFETY는 주행 중 Aurora 화면과 사고 대응 화면을 띄우지 않습니다.</p>
      <div class="drow">${pend ? `<button class="dbtn go" data-act="drvPark" data-k="dstop">정차 확인 (시뮬레이션)</button>`
        : `<button class="dbtn" data-act="drvPark" data-k="dpark">주차 완료 (시뮬레이션)</button><button class="dbtn warn" data-act="drvAcc" data-k="dacc">사고 이벤트 (시뮬레이션)</button>`}</div></div>`;
}
function renderInner() {
  sceneSync(); renderDrive();
  renderLeft(); renderChrome();""")

# ---- vehicle signals drive the scene on Pleos
rep("""vehSync(v); vehChrome(v);
  if (S.screen === 'prot'""", """vehSync(v); vehChrome(v);
  if (sceneSync('vehicle signal')) return render();
  if (S.screen === 'prot'""")

# ---- simulation switches of the stand-alone prototype (the same transitions as on a vehicle, driven by hand)
rep("""  accOn: () => { intake.userStart();""", """  drvStart: () => { if (VEHMODE) return toast('주행 상태는 차량 신호로 판단합니다.'); S.simMove = true; log('주행 시작 (시뮬레이션)', 'demo'); render(); },
  drvPark: () => { S.simMove = false; log(S.hmi === 'ACC' ? '정차 (시뮬레이션)' : '주차 완료 (시뮬레이션)', 'demo'); render(); },
  drvAcc: () => { intake.userStart(); S.hmi = 'ACC'; S.screen = 'home'; log('사고 상황 시뮬레이션 시작 (가상 이벤트 · 주행 중)', 'demo'); render(); },
  accOn: () => { intake.userStart();""")
rep("""<button class="cbtn drive" data-act="pre" data-k="lgo">""", """<button class="cbtn drive" data-act="drvStart" data-k="lgo">""")

# ---- top chips say which scene is on
rep("""  const acc = S.hmi === 'ACC';
  $('chips').innerHTML = `<button class="chip concept" data-act="about" data-k="about">${VEHMODE ? 'FULL HMI · Pleos 차량 연결' : 'FULL HMI CONCEPT · 독립 시연본'}</button>`
    + (acc ?""", """  const acc = S.hmi === 'ACC', drv = S.scene === 'DRIVING' || S.scene === 'ACCIDENT_PENDING';
  $('chips').innerHTML = `<button class="chip concept" data-act="about" data-k="about">${VEHMODE ? 'RE:SAFETY · Pleos 차량 연결' : 'FULL HMI CONCEPT · 독립 시연본'}</button>`
    + (drv ? `<span class="chip ${acc ? 'sim' : 'mode'}" data-k="scenechip">${acc ? '사고 신호 · 정차 대기 (시뮬레이션)' : '주행 중 · Pleos 기본 화면 (시뮬레이션)'}</span>` : acc ?""")

# ---- test hook
rep("""console.log('STATE ' + JSON.stringify({ hmi: S.hmi, screen: S.screen,""", """console.log('STATE ' + JSON.stringify({ scene: S.scene, motion: motion(), sceneLog: S.sceneLog.slice(-8), layout: VEHMODE ? 'pleos-panel' : 'full', hmi: S.hmi, screen: S.screen,""")

# ---- styles
css = (W / 'tools/adaptive_scenes.css').read_text(encoding='utf-8')
rep("""/* AURORA-THEME-END */""", css + """\n/* AURORA-THEME-END */""")

idx.write_text(t, encoding='utf-8', newline='\n')
print('adaptive scenes applied; lines', t.count('\n') + 1)
