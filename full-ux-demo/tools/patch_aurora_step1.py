"""AURORA step 1: structure for the two states (READY: four list cards + quick row + seat control; ACCIDENT: the existing console) and theme injection.
Run on the merged base (tools/index_step0_merged_hardening6_plus_master9.html). The CSS lives in tools/aurora_theme.css and is injected between markers,
so it can be edited and injected again with inject_css.py.
  python patch_aurora_step1.py <work dir>"""
import sys
from pathlib import Path

W = Path(sys.argv[1]); assert W.name in ('FULL_HMI_AURORA_SUBMISSION_WORK', 'RESAFETY_ADAPTIVE_HMI_WORK')
idx = W / 'signal-probe-fullhmi/app/src/main/assets/index.html'
t = (W / 'tools/index_step0_merged_hardening6_plus_master9.html').read_text(encoding='utf-8')


def rep(a, b):
    global t
    assert t.count(a) == 1, (a[:90], t.count(a)); t = t.replace(a, b)


# ---- icons
rep("  check:'<path d=\"M9 25l10 10 20-22\"/>'\n};", """  check:'<path d="M9 25l10 10 20-22"/>',
  chk2:'<circle cx="24" cy="24" r="18"/><path d="M15 25l6 6 12-14"/>',
  beltman:'<circle cx="24" cy="9" r="5"/><path d="M12 43V31c0-7.500 5-12 12-12s12 4.500 12 12v12"/><path d="M15.500 22.500 34 41"/><path d="M12 37h17"/><rect x="29" y="33.500" width="8" height="7" rx="1.800"/>',   /* seated person with a fastened three-point belt */
  carshield:'<path d="M4 31l3-8c1-2 3-3 5-3h14c2 0 4 1 5 3"/><path d="M4 31v5h4m8 0h9"/><circle cx="12" cy="36" r="3.5"/><path d="M36 22l8 3v6c0 6-4 9-8 11-4-2-8-5-8-11v-6Z"/><path d="M32 31l3 3 5-6"/>',
  precheck:'<rect x="8" y="8" width="26" height="33" rx="4"/><path d="M15 8V5h12v3M14 19h14M14 26h8"/><circle cx="34" cy="35" r="8" fill="none"/><path d="M30 35l3 3 5-6"/>',
  seatheat:'<path d="M24 8c3 0 5 2 4.5 5l-2 14h10c2.500 0 4 1.500 3.500 4l-1 6"/><path d="M20 13l-1 15c-.3 3 1.500 5 4.500 5h14"/><path d="M6 12c2 2-2 4 0 6s-2 4 0 6M12 12c2 2-2 4 0 6s-2 4 0 6"/>',
  user:'<circle cx="24" cy="16" r="8"/><path d="M8 42c2-9 8-13 16-13s14 4 16 13"/>',
  leaf:'<path d="M8 40c0-18 10-30 32-32 0 20-10 30-28 30"/><path d="M8 40c6-10 14-18 24-24"/>',
  reset:'<path d="M40 24a16 16 0 1 1-5-11.600"/><path d="M36 5v9h-9"/>',
  heat:'<path d="M14 9c4 5-4 10 0 15s-4 10 0 15M24 9c4 5-4 10 0 15s-4 10 0 15M34 9c4 5-4 10 0 15s-4 10 0 15"/>',
  fan:'<circle cx="24" cy="24" r="4"/><path d="M24 20c-2-8 2-14 8-13 2 6-2 11-8 13ZM28 24c8-2 14 2 13 8-6 2-11-2-13-8ZM24 28c2 8-2 14-8 13-2-6 2-11 8-13ZM20 24c-8 2-14-2-13-8 6-2 11 2 13 8Z"/>',
  aup:'<path d="M24 40V10M12 22l12-12 12 12"/>', adown:'<path d="M24 8v30M12 26l12 12 12-12"/>', aleft:'<path d="M40 24H10M22 12 10 24l12 12"/>', aright:'<path d="M8 24h30M26 12l12 12-12 12"/>',
  fore:'<path d="M6 24h36M14 16l-8 8 8 8M34 16l8 8-8 8"/>', updown:'<path d="M24 6v36M16 14l8-8 8 8M16 34l8 8 8-8"/>', angle:'<path d="M10 40V20a12 12 0 0 1 12-12"/><path d="M10 40h28"/>'
};""")

# ---- left view and top chips
rep("""    : `<h1>주행 준비 상태</h1><p>사고 대응은 오른쪽 RE:SAFETY에서 언제든 열 수 있습니다.<br>왼쪽 화면은 차량과 연결되지 않은 콘셉트 표시입니다.</p><span class="simchip">콘셉트 화면<i> · </i>Pleos 시스템 화면 아님</span>`);""",
    """    : `<h1>주행 준비 상태</h1><p>사고 대응은 오른쪽 RE:SAFETY에서 언제든 열 수 있습니다.<br>왼쪽 화면은 차량과 연결되지 않은 콘셉트 표시입니다.</p><span class="cpill">콘셉트 화면 · Pleos 시스템 화면 아님</span><span class="simchip">주행 준비 모드<i> · </i>시스템 정상 · 즉시 주행 가능</span>`);""")
rep("""    : `<button class="cbtn go" data-act="accOn" data-k="accOn">${svg('play', 2.6)}<span class="tx"><b>사고 상황 시뮬레이션 시작</b><span>가상 이벤트 · 실제 사고 감지가 아닙니다</span></span><span class="cv">${svg('chev', 4)}</span></button>`;""",
    """    : `<button class="cbtn sys" data-act="about" data-k="lsys">${svg('chk2', 2.6)}<span class="tx"><b>시스템 정상</b><span>차량 상태가 안전하게 준비되었습니다</span></span><span class="cv">${svg('chev', 4)}</span></button>
       <button class="cbtn drive" data-act="pre" data-k="lgo">${svg('carshield', 2.6)}<span class="tx"><b>주행 시작</b><span>안전한 운전을 시작하세요</span></span><span class="cv">${svg('chev', 4)}</span></button>`;   // both are part of the concept display of the vehicle area; they open "about" and "출발 전 확인\"""")
rep("""    + (acc ? `<span class="chip sim">사고 상황 시뮬레이션</span><button class="chip ctl" data-act="accOff" data-k="accOff">시뮬레이션 종료 ✕</button>` : '');""",
    """    + (acc ? `<span class="chip sim">사고 상황 시뮬레이션</span><button class="chip ctl" data-act="accOff" data-k="accOff">시뮬레이션 종료 ✕</button>`
           : `<span class="chip mode">주행 준비 모드</span><button class="chip ctl" data-act="accOn" data-k="accOn" aria-label="사고 상황 시뮬레이션 시작 (가상 이벤트)">시뮬레이션</button>`);""")

# ---- home: READY = four list cards (R1) + quick row; ACCIDENT = the console as before
rep("""function homeHtml() {
  const g = S.g;
  return mast('사고 대응') +""", """const rcard = (cls, icon, title, sub, attr, k) => `<button class="rcard ${cls}" ${attr} data-k="${k}"><span class="ricon">${svg(icon, 2.4)}</span><span class="rdiv"></span><span class="rtx"><b>${title}</b><span>${sub}</span></span><span class="chev">${svg('chev', 3.4)}</span></button>`;
function readyHomeHtml() {                               // reference R1, without rail and without a quick row (owner's decision): 119, SOS, 보호 모드, 사고 상황 정리 are reached in the ACCIDENT state
  return mast('주행 준비') + `<div class="rlist">
    ${rcard('hot', 'precheck', '출발 전 확인', '주행 전 안전 항목과 준비 상태를 점검합니다.', 'data-act="pre"', 'cpre')}
    ${rcard('', 'beltman', '좌석 · 안전벨트 확인', '모든 좌석의 안전벨트 착용 상태를 확인합니다.', 'data-act="seat"', 'cseat')}
    ${rcard('', 'seatheat', '좌석 제어', '좌석 위치 조절, 열선, 통풍, 릴렉스 등 좌석 기능을 제어합니다.', 'data-act="seatctl"', 'cseatctl')}
    ${rcard('', 'doc', '사고 기록', '과거 사고 이력과 관련 정보를 확인합니다.', 'data-act="record"', 'crec')}</div>`;
}
function homeHtml() {
  const g = S.g;
  if (S.hmi !== 'ACC') return readyHomeHtml();
  return mast('사고 대응') +""")

# ---- seat control (R5), SIMULATION only: values live in memory, nothing is sent anywhere and nothing is stored
rep("/* ---- insurance / tow: contacts typed by the user, dialer on request, summary + QR. No automatic claim or dispatch exists. ---- */",
    """/* ---- seat control (reference R5). SIMULATION: this build has no vehicle control interface. The values exist on this screen only; they are
   not stored, not sent and move nothing. A real seat control needs the manufacturer's vehicle-control permission. ---- */
const SC_SEATS = [['D', '운전석', 'Driver Seat'], ['P', '조수석', 'Passenger Seat'], ['RL', '뒷좌석 좌', 'Rear Left'], ['RR', '뒷좌석 우', 'Rear Right']];
const SC_LIM = { fore: [-10, 10, 0.5], height: [-5, 5, 0.5], rec: [90, 130, 1] };
const SC_PRESET = { p1: { fore: 3, height: 1.5, rec: 110 }, p2: { fore: 1, height: 0.5, rec: 105 }, relax: { fore: -4, height: -1, rec: 125 } };
const newSeatCtl = () => ({ seat: 'D', tab: 'ctl', mode: 'pos', preset: 'p1', v: Object.fromEntries(SC_SEATS.map(([k]) => [k, Object.assign({ heat: 0, vent: 0 }, k === 'D' ? SC_PRESET.p1 : { fore: 0, height: 0, rec: 100 })])) });
S.sc = newSeatCtl();
const scFmt = (k, v) => k === 'rec' ? v + '°' : (v > 0 ? '+' : '') + v.toFixed(1) + ' cm';
const scPct = (k, v) => Math.round((v - SC_LIM[k][0]) / (SC_LIM[k][1] - SC_LIM[k][0]) * 100) + '%';
const scClamp = (k, v) => Math.max(SC_LIM[k][0], Math.min(SC_LIM[k][1], Math.round(v / SC_LIM[k][2]) * SC_LIM[k][2]));
function seatCtlHtml() {
  const c = S.sc, v = c.v[c.seat], cur = SC_SEATS.find(s => s[0] === c.seat);
  const pos = { D: [18.7, 28.7], P: [53.2, 28.7], RL: [18.7, 54.2], RR: [53.2, 54.2] };   // per cent of the car picture (hmi-assets/installed/SEATCTL_PICTURES_NOTE.json)
  const seats = SC_SEATS.map(([k, n]) => `<button class="scseat${k[0] === 'R' ? ' rear' : ''}${c.seat === k ? ' on' : ''}" style="left:${pos[k][0]}%;top:${pos[k][1]}%" data-f="sc:seat:${k}" data-k="scs_${k}"><span>${n}</span></button>`).join('');
  const car = `<img class="sccarimg" src="assets/hmi/SEATCTL_CAR_TOP.png" alt="">`;   // picture cut from the owner's concept picture; the seats on it are buttons
  const mode = (k, icon, n) => `<button class="scmode${c.mode === k ? ' on' : ''}" data-f="sc:mode:${k}" data-k="scm_${k}">${svg(icon, 3.4)}${n}</button>`;
  const mid = c.mode === 'pos'
    ? `<div class="scmid pos"><img class="scside" src="assets/hmi/SEATCTL_SEAT_SIDE.png" alt=""></div><div class="scpad"><button class="u" data-f="sc:nudge:height:1" data-k="scu" aria-label="높이 올리기">${svg('aup', 4)}</button><button class="l" data-f="sc:nudge:fore:-1" data-k="scl" aria-label="뒤로">${svg('aleft', 4)}</button><button class="r" data-f="sc:nudge:fore:1" data-k="scr" aria-label="앞으로">${svg('aright', 4)}</button><button class="d" data-f="sc:nudge:height:-1" data-k="scd" aria-label="높이 내리기">${svg('adown', 4)}</button></div>`
    : `<div class="scmid">${svg(c.mode === 'heat' ? 'heat' : 'fan', 2.4)}<div><div style="margin-bottom:.1rem">${c.mode === 'heat' ? '열선' : '통풍'} 단계</div><div class="sclv">${[0, 1, 2, 3].map(l => `<button class="${v[c.mode] === l ? 'on' : ''}" data-f="sc:lv:${c.mode}:${l}" data-k="sclv_${l}">${l === 0 ? '끔' : l}</button>`).join('')}</div></div></div>`;
  const slider = (k, icon, n) => `<div class="scrow">${svg(icon, 3.4)}<span class="lb">${n}</span><input type="range" min="${SC_LIM[k][0]}" max="${SC_LIM[k][1]}" step="${SC_LIM[k][2]}" value="${v[k]}" style="--p:${scPct(k, v[k])}" data-sc="${k}" data-k="scr_${k}" aria-label="${n}"><span class="vl" data-scv="${k}">${scFmt(k, v[k])}</span></div>`;
  const env = (k, n, icon) => `<div class="scrow">${svg(icon, 3.4)}<span class="lb">${n}</span><div class="sclv" style="flex:1">${[0, 1, 2, 3].map(l => `<button class="${v[k] === l ? 'on' : ''}" data-f="sc:lv:${k}:${l}" data-k="sce_${k}_${l}">${l === 0 ? '끔' : l}</button>`).join('')}</div><span class="vl">${v[k] ? v[k] + '단계' : '꺼짐'}</span></div>`;
  const q = (k, icon, n) => `<button class="scq${c.preset === k ? ' on' : ''}" data-f="sc:preset:${k}" data-k="scq_${k}">${svg(icon, 3.2)}${n}</button>`;
  return `<div class="mast"><span class="t">RE:SAFETY</span><span class="d"></span><span class="s">좌석 제어</span><span class="sctabs">
      <button class="sctab${c.tab === 'ctl' ? ' on' : ''}" data-f="sc:tab:ctl" data-k="sct_ctl">${svg('seat', 3.2)}좌석 제어</button><button class="sctab${c.tab === 'env' ? ' on' : ''}" data-f="sc:tab:env" data-k="sct_env">${svg('heat', 3.2)}좌석 환경</button></span></div>
  <div class="scwrap"><div class="sccar" data-k="sccar">${car}${seats}</div>
    <div class="scright">
      <div class="schead"><div><b data-k="scname">${cur[1]}</b><span class="en">${cur[2]}</span><span class="scbadges"><span class="scsel">${svg('chk2', 4)}선택됨</span><span class="tag sim">SIMULATION</span></span></div><img class="scill" src="assets/hmi/SEATCTL_SEAT_3D.png" alt=""></div>
      ${c.tab === 'ctl' ? `<div class="scgroup ctl"><div class="scmodes">${mode('pos', 'seat', '좌석 위치')}${mode('heat', 'heat', '열선')}${mode('vent', 'fan', '통풍')}</div>${mid}</div>
      <div class="scgroup sl">${slider('fore', 'fore', '앞뒤 위치')}${slider('height', 'updown', '높이')}${slider('rec', 'angle', '등받이 각도')}</div>`
      : `<div class="scgroup sl" style="height:auto;gap:.2rem;padding:.3rem .2rem">${env('heat', '열선', 'heat')}${env('vent', '통풍', 'fan')}</div>`}
    </div></div>
  <div class="scquick"><span>빠른 설정</span>${q('p1', 'user', '운전 자세 1')}${q('p2', 'user', '운전 자세 2')}${q('relax', 'leaf', '릴렉스')}${q('reset', 'reset', '초기화')}</div>
  <p class="scnote" data-k="scnote">SIMULATION · 이 시연본은 차량과 연결되어 있지 않습니다. 실제 좌석은 움직이지 않고 값은 이 화면에만 표시됩니다. 실제 좌석 제어에는 제조사의 차량 제어 권한이 필요합니다.</p>`;
}

/* ---- insurance / tow: contacts typed by the user, dialer on request, summary + QR. No automatic claim or dispatch exists. ---- */""")
rep("""  lab: (a) => { if (a === 'check') return checkConn();""",
    """  sc: (a, k, x) => { const c = S.sc, v = c.v[c.seat];
    if (a === 'seat' && c.v[k]) c.seat = k;
    else if (a === 'tab') c.tab = k === 'env' ? 'env' : 'ctl';
    else if (a === 'mode') c.mode = ['pos', 'heat', 'vent'].includes(k) ? k : 'pos';
    else if (a === 'nudge' && SC_LIM[k]) { v[k] = scClamp(k, v[k] + (+x) * SC_LIM[k][2]); c.preset = ''; }
    else if (a === 'lv' && (k === 'heat' || k === 'vent')) v[k] = Math.max(0, Math.min(3, +x | 0));
    else if (a === 'preset') { if (k === 'reset') { Object.assign(v, { fore: 0, height: 0, rec: 100, heat: 0, vent: 0 }); c.preset = ''; } else if (SC_PRESET[k]) { Object.assign(v, SC_PRESET[k]); c.preset = k; } }
    console.log('SEATCTL_SIM ' + a + ' ' + c.seat); render(); },
  lab: (a) => { if (a === 'check') return checkConn();""")
rep("""document.addEventListener('input', e => { const el = e.target; if (el.dataset && el.dataset.in) setPath(el.dataset.in, el.value.slice(0, 60)); });""",
    """document.addEventListener('input', e => { const el = e.target; if (el.dataset && el.dataset.in) setPath(el.dataset.in, el.value.slice(0, 60));
  if (el.dataset && el.dataset.sc && SC_LIM[el.dataset.sc]) { const k = el.dataset.sc, v = S.sc.v[S.sc.seat]; v[k] = scClamp(k, +el.value); S.sc.preset = '';      // slider: no redraw while it is dragged
    el.style.setProperty('--p', scPct(k, v[k])); const o = document.querySelector('[data-scv="' + k + '"]'); if (o) o.textContent = scFmt(k, v[k]); } });""")
rep("""document.addEventListener('change', e => { if (e.target.dataset && e.target.dataset.in) render(); });""",
    """document.addEventListener('change', e => { if (e.target.dataset && (e.target.dataset.in || e.target.dataset.sc)) render(); });""")
rep("  prot: () => go('prot'), seat: () => go('seat'), tow: () => go('tow'), pre: () => go('pre'), lab: () => { go('lab'); checkConn(); },",
    "  prot: () => go('prot'), seat: () => go('seat'), seatctl: () => go('seatctl'), tow: () => go('tow'), pre: () => go('pre'), lab: () => { go('lab'); checkConn(); },")

# ---- rail and screen table
rep("  const railOn = sc === 'home' ? 'home' : sc === 'record' ? 'rec' : sc === 'seat' ? 'seat' : sc === 'prot' ? 'prot' : '';",
    "  const railOn = sc === 'home' ? 'home' : sc === 'record' ? 'rec' : (sc === 'seat' || sc === 'seatctl') ? 'seat' : sc === 'prot' ? 'prot' : '';")
rep("""    + (sc === 'home' ? `<button id="q112" data-act="d112" data-k="q112" aria-label="112 경찰 전화 화면 열기">112</button>`
      : ['prot', 'seat', 'tow', 'pre', 'guide', 'record'].includes(sc) ? `<button id="q119" data-act="d119" data-k="q119" aria-label="119 전화 화면 열기">119</button>` : '');""",
    """    + (sc === 'home' ? `<button id="q112" data-act="d112" data-k="q112" aria-label="112 경찰 전화 화면 열기">112</button>`
      : ['prot', 'seat', 'seatctl', 'tow', 'pre', 'guide', 'record'].includes(sc) ? `<button id="q119" data-act="d119" data-k="q119" aria-label="119 전화 화면 열기">119</button>` : '');""")
rep("prot: protHtml, seat: seatHtml, tow: towHtml, pre: preHtml, lab: labHtml, labqr: labQrHtml };", "prot: protHtml, seat: seatHtml, seatctl: seatCtlHtml, tow: towHtml, pre: preHtml, lab: labHtml, labqr: labQrHtml };")
rep("    sosRid: (S.sos.rid || '').slice(0, 8),", "    seatctl: { seat: S.sc.seat, tab: S.sc.tab, mode: S.sc.mode, preset: S.sc.preset, v: S.sc.v[S.sc.seat] },\n    sosRid: (S.sos.rid || '').slice(0, 8),")

# ---- the click handler passes a third argument (seat control uses "sc:nudge:height:1")
rep("  if (ds.f) { const [m, a, k] = ds.f.split(':'); F[m](a, k); }", "  if (ds.f) { const [m, a, k, x] = ds.f.split(':'); F[m](a, k, x); }")
# ---- quick call button on the rail: READY shows 112 on every screen (R4, R5); ACCIDENT keeps 112 on Home and 119 on the other screens (R2, R3)
rep("""    + (sc === 'home' ? `<button id="q112" data-act="d112" data-k="q112" aria-label="112 경찰 전화 화면 열기">112</button>`
      : ['prot', 'seat', 'seatctl', 'tow', 'pre', 'guide', 'record'].includes(sc) ?""",
    """    + ((sc === 'home' || (S.hmi !== 'ACC' && ['prot', 'seat', 'seatctl', 'tow', 'pre', 'guide', 'record'].includes(sc))) ? `<button id="q112" data-act="d112" data-k="q112" aria-label="112 경찰 전화 화면 열기">112</button>`
      : ['prot', 'seat', 'seatctl', 'tow', 'pre', 'guide', 'record'].includes(sc) ?""")

# ---- no rail in either state (owner's decision): a screen without its own back button gets one under the title
rep("  main.innerHTML = (SCREENS[sc] || homeHtml)(); main.dataset.screen = sc;",
    """  main.innerHTML = (SCREENS[sc] || homeHtml)(); main.dataset.screen = sc;
  if (sc !== 'home' && !main.querySelector('.back')) { const m = main.querySelector('.mast'); if (m) m.insertAdjacentHTML('afterend', '<button class="back" data-act="home" data-k="back">←  홈</button>'); }""")

# ---- ACCIDENT left view (reference R6): the simulation notice is a pill under the text, as in the READY state
rep("""<span class="simchip">사고 상황 시뮬레이션<i> · </i>실제 차량 사고 감지 아님</span>`""", """<span class="cpill warn">사고 상황 시뮬레이션 · 실제 차량 사고 감지 아님</span>`""")

# ---- hand-over summary (owner 2026-11-14: "as clear as a doctor or a paramedic briefing"): the text that goes into the QR is ordered
#      when - where - people - what was done - vehicle, every line says in brackets where its value comes from, and the screen draws the same
#      text as rows (label, value, source in small type) instead of a block of small text
a = t.index("function summaryText() {"); b = t.index("/* HARDENING round 6 (pillar 07): no QR symbol larger")
t = t[:a] + """function summaryText() {                                 // hand-over order: when, where, people, what was done, vehicle
  const g = S.g, d = new Date(), L = ['[RE:SAFETY] 사고 요약 (Full HMI 시연)'];
  L.push(`시각 : ${d.getFullYear()}-${p2(d.getMonth() + 1)}-${p2(d.getDate())} ${p2(d.getHours())}:${p2(d.getMinutes())} (기기 시계)`);
  if (g.share.loc) L.push('위치 : ' + (posText() ? posText() + ' (시험용 가상 위치)' : '미확인'));
  if (g.share.inj) L.push(`부상 : 경미 ${g.minor}명 · 중상 ${g.major}명 (탑승자 입력)`);
  if (g.share.act) L.push(`현장 조치 : 4개 중 ${g.checks.filter(Boolean).length}개 확인 (탑승자 입력)`);
  if (g.share.veh) L.push('차량 : 정차 · 기어 P (시뮬레이션 값)');
  L.push('※ 시연 데이터 · 신고 아님');
  return L.join('\\n');
}
function briefHtml(text) {                                // the SAME text as in the QR, drawn as rows; nothing is added and nothing is left out
  const lines = text.split('\\n'), head = lines[0].replace(/^\\[RE:SAFETY\\]\\s*/, ''), foot = lines.filter(l => l.charAt(0) === '※').map(l => l.replace(/^※\\s*/, '')).join(' · ');
  const tail = s => { const m = s.match(/^(.*\\S)\\s*\\(([^()]*)\\)$/); return m ? [m[1], m[2]] : [s, '']; };
  const rows = lines.slice(1).filter(l => l.charAt(0) !== '※').map(l => { const i = l.indexOf(' : '); const [v, q] = tail(i < 0 ? l : l.slice(i + 3));
    return `<div class="br"><span class="bk">${esc(i < 0 ? '' : l.slice(0, i))}</span><span class="bv"><b>${esc(v)}</b>${q ? `<small>${esc(q)}</small>` : ''}</span></div>`; }).join('');
  return `<div class="brief" data-k="brief"><div class="bh"><b>${esc(head)}</b></div>${rows}${foot ? `<p class="bf">${esc(foot)}</p>` : ''}</div>`;
}
""" + t[b:]
rep("""  if (t.items.time) L.push('사고 시각(사용자 입력): ' + (t.when || '미입력'));""", """  if (t.items.time) L.push('사고 시각 : ' + (t.when || '미입력') + ' (사용자 입력)');""")
rep("""  if (t.items.where) L.push('위치(사용자 입력): ' + (t.where || '미입력'));""", """  if (t.items.where) L.push('위치 : ' + (t.where || '미입력') + ' (사용자 입력)');""")
rep("""  if (t.items.inj) L.push('부상 입력(사용자): ' + (g ? `경미 ${g.minor}명 · 중상 ${g.major}명` : '정리 기록 없음'));""", """  if (t.items.inj) L.push('부상 : ' + (g ? `경미 ${g.minor}명 · 중상 ${g.major}명 (탑승자 입력)` : '정리 기록 없음'));""")
rep("""  if (t.items.act) L.push('사용자 확인: ' + (g ? `행동요령 ${g.checks}/4` : '정리 기록 없음'));""", """  if (t.items.act) L.push('현장 조치 : ' + (g ? `4개 중 ${g.checks}개 확인 (탑승자 입력)` : '정리 기록 없음'));""")
rep("""  L.push('※ 자동 접수·자동 출동 아님. 신고가 아님.');""", """  L.push('※ 자동 접수·자동 출동 아님 · 신고 아님');""")
rep("""<pre>${preHtml}</pre>${hint}${ctl}</div>`;""", """<div class="qrside">${preHtml.charAt(0) === '<' ? preHtml : `<pre>${preHtml}</pre>`}${hint}</div>${ctl}</div>`;""")
rep("""${qrBox(txt, 'qr', txt, S.qrErr === 'too_long'""", """${qrBox(txt, 'qr', briefHtml(txt), S.qrErr === 'too_long'""")
rep("""qrBox(text, 'towqrbox', esc(text), qrFailText())""", """qrBox(text, 'towqrbox', briefHtml(text), qrFailText())""")
# ---- the step line of "사고 상황 정리": upright, large, without the slash
rep("""<div class="lab"><span>${st + 1} / ${GUIDE.length} 단계 · ${GUIDE[st]}</span>""", """<div class="lab glab"><span data-k="gstep">${GUIDE.length}단계 중 ${st + 1}단계 · ${GUIDE[st]}</span>""")
# ---- owner's rule for the whole APK (2026-11-14): a colon between a label and its value has one blank before and after it ("가 : 나").
#      Only a colon that follows a Korean syllable (or a bracket closed after one) is touched: times (13:12), RE:SAFETY, code and CSS stay as they are.
import re
_hits = []
def _colon(m):
    _hits.append(t[max(0, m.start() - 14):m.end() + 10].replace('\\n', ' ')); return m.group(1) + ' : '
t = re.sub(r'([가-힣]\)?):[ ]?(?=[^\s:/])', _colon, t)
print('colons given blanks:', len(_hits)); [print('   ', h) for h in _hits]
_n = t.count("근처 SOS: "); assert _n >= 4, _n; t = t.replace("근처 SOS: ", "근처 SOS : "); print('colons after "근처 SOS":', _n)      # record lines whose label ends in Latin letters (found by a scan of the page)

# ---- logo mark in the top strip: pink and white, the colours of the first design (owner 2026-11-14)
rep("""<path d="M10 2h8L10 28H2Z" fill="#E0465A"/><path d="M24 2h8l-8 26h-8Z" fill="#FF8499"/>""", """<path d="M10 2h8L10 28H2Z" fill="#FF8FAB"/><path d="M24 2h8l-8 26h-8Z" fill="#FFFFFF"/>""")

# ---- theme
css =(W / 'tools/aurora_theme.css').read_text(encoding='utf-8') + '\n' + (W / 'tools/legibility.css').read_text(encoding='utf-8')   # legibility.css: larger type (owner's order), sizes and weights only
assert t.count('</style>') == 1
t = t.replace('</style>', '/* AURORA-THEME-BEGIN */\n' + css + '\n/* AURORA-THEME-END */\n</style>')
idx.write_text(t, encoding='utf-8', newline='\n')
print('aurora step 1 applied; lines', t.count('\n') + 1)
