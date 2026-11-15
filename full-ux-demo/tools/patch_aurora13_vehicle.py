"""AURORA13 step: protect mode on the real Pleos Vehicle SDK. Run AFTER patch_aurora_step1.py (which rebuilds assets/index.html from the base).
The design of aurora12 is frozen: no CSS, no layout. On a device without a vehicle (window.Native.prot() returns "") every screen stays exactly
as in aurora12. With a vehicle the protect screen keeps the same panels and classes; only its texts and values come from the vehicle,
and the simulation-settings panel (rear fog assumption, failure rehearsal) is not shown because it does not apply to a real vehicle.
  python patch_aurora13_vehicle.py <work dir>"""
import sys
from pathlib import Path

W = Path(sys.argv[1]); assert W.name in ('FULL_HMI_AURORA_SUBMISSION_WORK', 'RESAFETY_ADAPTIVE_HMI_WORK')
idx = W / 'signal-probe-fullhmi/app/src/main/assets/index.html'
t = idx.read_text(encoding='utf-8')
assert 'VEHMODE' not in t, 'already applied: run patch_aurora_step1.py first'


def rep(a, b):
    global t
    assert t.count(a) == 1, (a[:90], t.count(a)); t = t.replace(a, b)


# ---- decided once, next to NATIVE, so every later line (render included) can use it
rep("""const SIM = !NATIVE;                                   // plain browser: labelled simulation""", """const SIM = !NATIVE;                                   // plain browser: labelled simulation
const VEHMODE = NATIVE && typeof window.Native.prot === 'function' && window.Native.prot() !== '';   // aurora13: a Pleos vehicle is connected""")

# ---- remembered across restarts: which finished vehicle run has already been written to the record
rep("""opt: { fogSupported: false, failNext: false } });""", """opt: { fogSupported: false, failNext: false }, vlast: '' });""")

# ---- the tap goes to the vehicle when there is one
rep("""function protRun(mode) {                                 // mode: 'on' = request, 'off' = put the values back
  const p = S.p;""", """function protRun(mode) {                                 // mode: 'on' = request, 'off' = put the values back
  if (VEHMODE) return vehRun(mode);
  const p = S.p;""")

# ---- vehicle mode: state from the app's native side (R2D code), drawn with the same panels and classes as the simulation
rep("""function protHtml() {
  const p = S.p, busy""", """/* ---- aurora13: protect mode on the real vehicle (Pleos Vehicle SDK through window.Native). The native side is the R2D submission app's
   verified code: it runs only after a tap, only while gear P and 0 km/h are freshly reported, re-checks before every step, reads each value
   before and after the request, and the undo button writes back only what this app changed. */
let vehLast = '';
const vehSnap = () => { try { return JSON.parse(window.Native.prot() || '{}'); } catch (e) { return {}; } };
const VOK = { VERIFIED: 1, ALREADY: 1 }, VBAD = { MISMATCH: 1, DENIED: 1, NO_VALUE: 1, ERROR: 1, TIMEOUT: 1, STOPPED: 1, UNCERTAIN: 1 };
function vehCount(v) { const o = Object.values(v.items || {}).map(x => x.o).filter(Boolean);
  return { ok: o.filter(x => VOK[x]).length, bad: o.filter(x => VBAD[x]).length, un: o.filter(x => x === 'UNSUPPORTED').length }; }
function vehRun(mode) {
  const why = window.Native.protStart(mode);
  if (why) { toast(why); return; }
  log(`보호 모드 ${mode === 'on' ? '켜기' : '되돌리기'} 요청 (Pleos 차량)`, 'user');
}
function vehSync(v) {                                       // once per finished run: history and record, like the simulation's protDone
  const p = S.p; if (!v.id || v.running || p.vlast === v.id) return;
  const c = vehCount(v); if (!c.ok && !c.bad && !c.un) return;
  p.vlast = v.id;
  p.state = v.mode === 'off' ? (c.bad ? 'partial' : 'off') : (c.bad === 0 ? 'on' : c.ok > 0 ? 'partial' : 'failed');
  p.hist.unshift({ t: nowText(), mode: v.mode === 'off' ? 'off' : 'on', ok: c.ok, bad: c.bad, un: c.un }); if (p.hist.length > 20) p.hist.pop();
  log(`보호 모드 ${v.mode === 'off' ? '되돌리기' : '켜기'} 결과 (Pleos 차량이 다시 알려 준 값) · 확인 ${c.ok} · 실패 ${c.bad} · 미지원 ${c.un}` + (v.stopped ? ` · 중단 : ${v.stopped}` : ''), 'app');
  saveProt();
}
/* aurora14: what else the vehicle reports. Reads only: gear and speed, doors, seat occupancy, belt buckles (asked on a tap), vehicle position
   (Pleos FusedLocation) and the navigation address (Pleos NaviHelper). The screens keep their layout; only the values and their source labels change. */
function vehChrome(v) {                                     // top strip speed and the gear letters
  const sp = $('tspd'), gr = $('tgear');
  if (sp && v.kmh != null) sp.innerHTML = `${v.kmh}<small>km/h</small>`;
  if (gr && ['P', 'R', 'N', 'D'].indexOf(v.gear) >= 0) gr.innerHTML = ['P', 'R', 'N', 'D'].map(g => g === v.gear ? `<b>${g}</b>` : `<span>${g}</span>`).join('');
}
function vehPosLine() { const l = vehSnap().loc || {};
  return l.lat == null ? `미확인 (${l.status || '차량 위치 없음'})` : `${l.addr ? l.addr + ' · ' : ''}${l.lat.toFixed(5)}, ${l.lon.toFixed(5)} (Pleos 차량 위치)`; }
function vehStateLine() { const v = vehSnap();
  return v.kmh == null || !v.gear ? '미확인 (차량 신호 없음)' : `${v.kmh === 0 ? '정차' : v.kmh + ' km/h'} · 기어 ${v.gear} (Pleos 차량 신호)`; }
function vehDoorText(v) { const d = v.doors || {}, ks = Object.keys(d).filter(k => d[k]), open = ks.filter(k => d[k] === 'open'), nm = { FL: '운전석', FR: '조수석', RL: '뒤 왼쪽', RR: '뒤 오른쪽', TG: '트렁크' };
  return !ks.length ? '값 없음' : open.length ? '열림 : ' + open.map(k => nm[k]).join(' · ') : '모두 닫힘'; }
function vehGuideBody() { const v = vehSnap(), l = v.loc || {};
  return `<h3>차량 상태 <span class="tag ok">Pleos 차량 신호</span></h3>
      <div class="kv" style="margin-top:.14rem"><span>이동 상태</span><b>${v.kmh == null || !v.gear ? '확인 중' : (v.kmh === 0 ? '정차' : v.kmh + ' km/h') + ' · 기어 ' + v.gear}</b></div><div class="kv"><span>문·트렁크</span><b>${vehDoorText(v)}</b></div><div class="kv"><span>차량 위치</span><b>${esc(l.lat == null ? (l.status || '확인 전') : (l.addr || `${l.lat.toFixed(5)}, ${l.lon.toFixed(5)}`))}</b></div>
      <p class="note">Pleos 차량이 지금 보고한 값입니다 (Vehicle SDK · FusedLocation · NaviHelper). 비상등은 차량 신호를 읽지 않으니 직접 확인하세요.</p>`; }
const vehSeat = k => { const s = (vehSnap().seats || {})[k] || {}; return [s.occ || '', s.belt == null ? '-' : s.belt]; };
const seatRep = k => VEHMODE ? vehSeat(k) : SIMSEAT[k];
const vehBeltText = sv => sv[1] === '-' ? '미조회' : sv[1] === 'y' ? '체결 보고 (차량)' : sv[1] === 'n' ? (sv[0] === 'y' ? '미체결 보고 (차량)' : '빈 좌석 · 미체결 (차량)') : sv[1] === 'x' ? '미지원 (차량)' : '값 없음 (차량)';
window.onVeh = () => { if (!VEHMODE) return; const s = window.Native.prot(); if (s === vehLast) return; vehLast = s; const v = vehSnap(); vehSync(v); vehChrome(v);
  if (S.screen === 'prot' || S.screen === 'seat' || (S.screen === 'guide' && S.g.step === 2)) render(); };
function protVehHtml() {
  const v = vehSnap(), p = S.p, items = v.items || {}, off = v.mode === 'off', c = vehCount(v), ran = !!v.id;
  const st = v.running ? [off ? '되돌리는 중…' : '요청 중…', 'warn']
    : v.stopped ? ['중단됨 · 요청을 멈췄어요', 'bad']
    : !ran ? ['꺼짐', 'idle']
    : off ? (c.bad ? ['일부만 되돌림', 'warn'] : ['꺼짐 · 처음 값으로 되돌림', 'idle'])
    : c.bad === 0 ? [`켜짐 · 확인 ${c.ok}${c.un ? ` · 미지원 ${c.un}` : ''}`, 'ok'] : c.ok > 0 ? ['일부만 적용됨', 'warn'] : ['실패 · 적용된 항목 없음', 'bad'];
  const TGT = { tail: '켜짐', fog: '켜짐', cabin: '켜짐', unlock: '풀림' };
  const badge = (k, o) => ({ VERIFIED: [off ? '원래대로' : '확인됨', 'ok'], ALREADY: [off ? '이미 원래대로' : '이미 ' + TGT[k], 'ok'], UNSUPPORTED: ['미지원', 'un'], IN_PROGRESS: ['요청 중', 'run'],
    WAITING: ['대기', ''], MISMATCH: ['반영 대기', 'bad'], DENIED: ['권한 없음', 'bad'], NO_VALUE: ['값 없음', 'bad'], ERROR: ['오류', 'bad'], TIMEOUT: ['응답 없음', 'bad'],
    STOPPED: ['중단', 'bad'], UNCERTAIN: ['확인 필요', 'bad'] }[o] || ['요청 전', '']);
  const src = v.sdk === 'ready' ? 'Pleos Vehicle SDK · 실제 차량 값 (요청 뒤 다시 읽어 확인)' : v.sdk === 'error' ? 'Pleos Vehicle SDK 연결 실패 · 요청할 수 없음' : 'Pleos 차량 연결 확인 중';
  const park = v.gate ? v.gate + (v.perm === false ? ' (속도 권한 없음)' : '') : `기어 ${v.gear || 'P'} · ${v.kmh == null ? 0 : v.kmh} km/h 확인됨 (차량 신호)`;
  const canOn = !v.running && v.sdk === 'ready' && !v.gate, canOff = !v.running && !!v.canUndo && !v.gate;
  return mast('보호 모드') + `<button class="back" data-act="home" data-k="back">←  홈</button>
  <div class="scroll stack"><div class="panel"><h3>보호 모드 <span class="tag ok">Pleos 차량 연결</span></h3>
    <div class="bigstate ${st[1]}" data-k="protstate">${st[0]}</div>
    <div class="kv"><span>데이터 출처</span><b>${src}</b></div>
    <div class="kv"><span>정차 조건</span><b data-k="protgate">${esc(park)}</b></div>
    ${v.stopped ? `<div class="kv"><span>중단 이유</span><b>${esc(v.stopped)}</b></div>` : ''}
    <div style="margin-top:.1rem">${PITEMS.map(([k, n]) => { const it = items[k] || {}, r = badge(k, it.o); return `<div class="irow"><span class="nm">${n}</span><span class="val">${esc(it.now || '확인 전')}</span><span class="badge ${r[1]}" data-k="pr_${k}">${r[0]}</span></div>`; }).join('')}</div>
    <div class="row" style="margin-top:.18rem"><button class="btn dark" style="flex:1" data-f="prot:on" data-k="proton" ${canOn ? '' : 'disabled'}>보호 모드 켜기</button><button class="btn" style="flex:1" data-f="prot:off" data-k="protoff" ${canOff ? '' : 'disabled'}>되돌리기</button></div>
    <p class="note">정차(기어 P · 0 km/h)가 확인될 때, 누를 때만 요청합니다. 항목마다 차량 값을 읽고, 요청하고, 다시 읽어 확인합니다. 되돌리기는 이 앱이 바꾼 항목만 처음 값으로 돌려놓습니다. 창문·트렁크·후드는 다루지 않습니다.</p></div>
  <div class="panel"><h3>요청 이력 <span class="tag">이 기기 안에만 보관</span></h3>
    ${p.hist.length ? `<ul class="hist" data-k="prothist">${p.hist.slice(0, 6).map(h => `<li>${h.t} · ${h.mode === 'on' ? '요청' : '되돌리기'} · 확인 ${h.ok} · 실패 ${h.bad} · 미지원 ${h.un}</li>`).join('')}</ul>` : '<p class="note">아직 요청한 적이 없습니다.</p>'}</div></div>`;
}
function protHtml() {
  if (VEHMODE) return protVehHtml();
  const p = S.p, busy""")

# ---- the native side must know whether the protect screen is shown (leaving it stops a running request)
rep("""  main.innerHTML = (SCREENS[sc] || homeHtml)(); main.dataset.screen = sc;""",
    """  main.innerHTML = (SCREENS[sc] || homeHtml)(); main.dataset.screen = sc;
  if (VEHMODE) window.Native.protScreen(sc === 'prot');""")

# ---- test hook: where the protect values come from
rep("""    prot: { state: S.p.state, on:""", """    prot: { src: VEHMODE ? 'vehicle' : 'sim', state: S.p.state, on:""")

# ---- aurora14: ids for the two values in the fixed top strip (no style change)
rep("""<span style="left:8.74rem">0<small>km/h</small></span>""", """<span style="left:8.74rem" id="tspd">0<small>km/h</small></span>""")
rep("""<div class="gear"><b>P</b>""", """<div class="gear" id="tgear"><b>P</b>""")
rep("""detectBg();""", """detectBg();
if (VEHMODE) window.onVeh();""")

# ---- aurora14: hand-over summary and its QR take the vehicle state and the vehicle position from Pleos
rep("""['veh', '차량 상태 (시뮬레이션 값)'], ['loc', '위치 (시험용 가상 위치)']];""",
    """['veh', VEHMODE ? '차량 상태 (Pleos 차량 신호)' : '차량 상태 (시뮬레이션 값)'], ['loc', VEHMODE ? '위치 (Pleos 차량 위치)' : '위치 (시험용 가상 위치)']];""")
rep("""L.push('위치 : ' + (posText() ? posText() + ' (시험용 가상 위치)' : '미확인'));""",
    """L.push('위치 : ' + (VEHMODE ? vehPosLine() : (posText() ? posText() + ' (시험용 가상 위치)' : '미확인')));""")
rep("""L.push('차량 : 정차 · 기어 P (시뮬레이션 값)');""", """L.push('차량 : ' + (VEHMODE ? vehStateLine() : '정차 · 기어 P (시뮬레이션 값)'));""")
rep("""  else if (st === 2) body = `<h3>차량 상태 <span class="tag sim">시뮬레이션 값</span></h3>""",
    """  else if (st === 2) body = VEHMODE ? vehGuideBody() : `<h3>차량 상태 <span class="tag sim">시뮬레이션 값</span></h3>""")

# ---- aurora14: seats and belts. "차량 보고" is what the Pleos vehicle reports; the direct confirmation by a person stays as it is
rep("""s = t.s[k], rep = SIMSEAT[k][0] === 'y', conf""", """s = t.s[k], rep = seatRep(k)[0] === 'y', conf""")
rep("""rep ? '착석 가능성' : '빈 좌석';""", """rep ? '착석 가능성' : (VEHMODE && seatRep(k)[0] === '') ? '값 없음' : '빈 좌석';""")
rep("""on = s.occ === 'y', sv = SIMSEAT[k];
    const belt = !t.sim""", """on = s.occ === 'y', sv = seatRep(k);
    const belt = VEHMODE ? vehBeltText(sv) : !t.sim""")
rep("""차량 보고 <span class="tag sim">SIMULATION</span></div>""", """차량 보고 ${VEHMODE ? '<span class="tag ok">Pleos 차량</span>' : '<span class="tag sim">SIMULATION</span>'}</div>""")
rep("""사람이 확인한 내용이 아니에요. 이 시연본에서는 가상 차량 값이에요.</p>""", """사람이 확인한 내용이 아니에요. ${VEHMODE ? 'Pleos 차량이 지금 보고한 값이에요.' : '이 시연본에서는 가상 차량 값이에요.'}</p>""")
rep("""<small>SIMULATION · ${t.beltAt} 조회</small>""", """<small>${VEHMODE ? 'Pleos 차량' : 'SIMULATION'} · ${t.beltAt} 조회</small>""")
rep("""else if (a === 'sim') { t.sim = true;""", """else if (a === 'sim' && VEHMODE) { if (!window.Native.beltQuery()) { toast('차량 연결을 확인하고 있어요.'); return; } t.sim = true; t.beltAt = nowText(); log('안전벨트 조회 (Pleos 차량이 보고한 값 · 사람이 확인한 내용 아님)', 'user'); }
    else if (a === 'sim') { t.sim = true;""")
rep("""<p class="note">이 시연본에는 좌석·벨트 센서가 연결되어 있지 않습니다. "차량 보고"와 "안전벨트 조회" 결과는 고정된 가상 값(SIMULATION)이며, 실제 차량 값은 Pleos R2D 네이티브 앱에서만 읽습니다.""",
    """<p class="note">${VEHMODE ? '"차량 보고"와 "안전벨트 조회"는 Pleos 차량이 보고한 값입니다 (Vehicle SDK). 사람이 확인한 내용이 아닙니다.' : '이 시연본에는 좌석·벨트 센서가 연결되어 있지 않습니다. "차량 보고"와 "안전벨트 조회" 결과는 고정된 가상 값(SIMULATION)이며, 실제 차량 값은 Pleos R2D 네이티브 앱에서만 읽습니다.'}""")

# ---- the chip in the top strip must not say "stand-alone" when a vehicle is connected
rep("""data-k="about">FULL HMI CONCEPT · 독립 시연본</button>""", """data-k="about">${VEHMODE ? 'FULL HMI · Pleos 차량 연결' : 'FULL HMI CONCEPT · 독립 시연본'}</button>""")

# ---- test hook: what the vehicle reports (no coordinates and no address text in the log)
rep("""    prot: { src: VEHMODE ? 'vehicle' : 'sim', state: S.p.state, on:""", """    veh: VEHMODE ? (() => { const v = vehSnap(), l = v.loc || {}; return { sdk: v.sdk, gear: v.gear, kmh: v.kmh, loc: l.lat != null, addr: !!l.addr, locStatus: l.status, seats: v.seats, doors: v.doors, tts: v.tts }; })() : null,
    prot: { src: VEHMODE ? 'vehicle' : 'sim', state: S.p.state, on:""")

# ---- "what really works" page: with a vehicle, the rows say what now comes from Pleos
rep("""<td>보호 모드(가상 차량), 좌석의 "차량 보고" 값, 가상 충돌 이벤트에 따른 화면 자동 전환, 왼쪽 사고 장면, 속도·기어 표시</td>""",
    """<td>${VEHMODE ? '가상 충돌 이벤트에 따른 화면 자동 전환, 왼쪽 사고 장면, 좌석 제어' : '보호 모드(가상 차량), 좌석의 "차량 보고" 값, 가상 충돌 이벤트에 따른 화면 자동 전환, 왼쪽 사고 장면, 속도·기어 표시'}</td>""")
rep("""    <tr><td><span class="tag nat">R2D 네이티브 전용</span></td><td>실제 차량 조명·문 잠금 요청과 재확인, 실제 좌석·벨트 센서 값, 차량 좌표·내비 주소</td></tr>""",
    """    ${VEHMODE ? `<tr><td><span class="tag ok">Pleos 차량 연결</span></td><td>보호 모드(정차 확인 뒤 미등·실내등·문 잠금 해제 요청, 다시 읽어 확인, 되돌리기) · 속도·기어·문 상태 · 좌석 착석·벨트 보고 : Vehicle SDK / 차량 위치 : FusedLocation / 주소 : NaviHelper / 보호 모드 결과 음성 안내 : TextToSpeech</td></tr>
    <tr><td><span class="tag nat">R2D 네이티브 전용</span></td><td>출발 전 차량 정보 기록(차대번호 끝자리·주행거리·주행 가능 거리)</td></tr>`
    : `<tr><td><span class="tag nat">R2D 네이티브 전용</span></td><td>실제 차량 조명·문 잠금 요청과 재확인, 실제 좌석·벨트 센서 값, 차량 좌표·내비 주소</td></tr>`}""")
rep("""  <p class="note">이 앱은 일반 Android에서 전체 화면으로 실행되는 독립 시연본입니다. Pleos 시스템 화면을 바꾸거나 덮지 않으며, 실제 차량·실제 위치·실제 신고와 연결되어 있지 않습니다.</p></div>`;""",
    """  <p class="note">${VEHMODE ? '이 앱은 Pleos 시스템 화면을 바꾸거나 덮지 않습니다. 차량 값은 Pleos SDK로 읽고, 차량에 요청하는 것은 보호 모드뿐입니다. SOS는 시험 서버로만 동작하고, 실제 신고와는 연결되어 있지 않습니다.' : '이 앱은 일반 Android에서 전체 화면으로 실행되는 독립 시연본입니다. Pleos 시스템 화면을 바꾸거나 덮지 않으며, 실제 차량·실제 위치·실제 신고와 연결되어 있지 않습니다.'}</p></div>`;""")

idx.write_text(t, encoding='utf-8', newline='\n')
print('aurora13 vehicle step applied; lines', t.count('\n') + 1)
