"""ADAPTIVE6 step, run AFTER patch_adaptive_scenes.py. Connects the rest of the vehicle functions to the existing Aurora screens.
No CSS and no layout change: only existing components (panel, kv, btn, sbtn, note, tag) inside screens that already exist.
  1  R2D reads that were missing: VIN (last 6), odometer, remaining range on the pre-drive check ("차량 정보 읽기", on a tap);
     wiper, low beam, battery / fuel live. Kept with the pre-drive record (restored after a restart) and written to the accident record.
     On a device without a vehicle the same panel shows fixed SIMULATION values and says so.
  2  Accident summary step 3 (vehicle state) also shows wiper, low beam, battery / fuel (Pleos only, as before).
  3  Voice input (Pleos SpeechToText) next to the place field and the memo field - only where the Pleos speech service is ready.
  4  Gleo Interface: the three functions of assets/schema.json open screens only (accident response, accident summary, 119 dial screen).
  python patch_adaptive6_more.py <work dir>"""
import sys
from pathlib import Path

W = Path(sys.argv[1]); assert W.name == 'RESAFETY_ADAPTIVE_HMI_WORK'
idx = W / 'signal-probe-fullhmi/app/src/main/assets/index.html'
t = idx.read_text(encoding='utf-8')
assert 'preCarHtml' not in t, 'already applied'


def rep(a, b):
    global t
    assert t.count(a) == 1, (a[:90], t.count(a)); t = t.replace(a, b)


# ---- the pre-drive record keeps the vehicle reading too (restored after a restart through the same storage)
rep("""const newPre = () => ({ chk: [false, false, false], memo: '', savedAt: '' });""",
    """const newPre = () => ({ chk: [false, false, false], memo: '', savedAt: '', car: {} });""")

# ---- pre-drive check: the vehicle panel, after the existing panel
rep("""    <p class="note">운행 전에 직접 확인한 내용만 남깁니다. 차량 점검 결과가 아니며, 차량 값은 읽지 않습니다.</p></div></div>`;
}""", """    <p class="note">위 항목은 운행 전에 직접 확인한 내용만 남깁니다. 차량 정보는 아래에서 따로 읽습니다.</p></div>${preCarHtml()}</div>`;
}
/* adaptive6: vehicle information on the pre-drive check (R2D "handover" record + live wiper / low beam / battery / fuel) */
const SIMCAR = { vin6: 'SIM000', odo: 12345, range: 410, EV_SOC: '78', FUEL_PCT: null, WIPER: 'OFF', LOWBEAM: 'OFF' };
function preCarHtml() {
  const c = S.pre.car || {}, sim = !VEHMODE, has = !!c.at, x = VEHMODE ? (vehSnap().extra || {}) : SIMCAR;
  const km = n => (typeof n === 'number' && isFinite(n)) ? n.toLocaleString('ko-KR') + ' km' : '확인 안 됨';
  const pct = s => (s == null || s === '') ? '값 없음' : s + '%';
  const sw = (s, on) => s == null ? '값 없음' : String(s).toUpperCase() === 'ON' ? on : '꺼짐';
  return `<div class="panel"><h3>차량 정보 ${sim ? '<span class="tag sim">SIMULATION · 가상 차량</span>' : '<span class="tag ok">Pleos 차량</span>'}</h3>
    <div class="kv"><span>차대번호</span><b data-k="precar_vin">${has ? (c.vin6 ? '…' + esc(c.vin6) : '확인 안 됨') : '읽기 전'}</b></div>
    <div class="kv"><span>누적 주행거리</span><b data-k="precar_odo">${has ? km(c.odo) : '읽기 전'}</b></div>
    <div class="kv"><span>주행 가능 거리</span><b data-k="precar_range">${has ? km(c.range) : '읽기 전'}</b></div>
    <div class="kv"><span>배터리 · 연료</span><b data-k="precar_energy">배터리 ${pct(x.EV_SOC)} · 연료 ${pct(x.FUEL_PCT)}</b></div>
    <div class="kv"><span>와이퍼 · 하향등</span><b data-k="precar_lights">와이퍼 ${sw(x.WIPER, '작동 중')} · 하향등 ${sw(x.LOWBEAM, '켜짐')}</b></div>
    <div class="row" style="margin-top:.16rem"><button class="btn dark" style="flex:1" data-f="pre:car" data-k="precar">차량 정보 읽기</button></div>
    <div class="kv" style="margin-top:.1rem"><span>마지막으로 읽은 시각</span><b data-k="precar_at">${has ? esc(c.at) : '없음'}</b></div>
    <p class="note">${sim ? '이 시연본에는 차량이 연결되어 있지 않아 고정된 가상 값(SIMULATION)을 보여 줍니다.' : 'Pleos 차량이 보고한 값입니다 (Vehicle SDK). 누를 때만 읽고, 차대번호는 끝 6자리만 보여 줍니다.'} 읽은 값은 사고 기록에 남습니다.</p></div>`;
}
function preCar() {
  if (VEHMODE) { if (!window.Native.handoverQuery()) return toast('차량 연결을 확인하고 있어요.'); S.preAsk = Date.now(); return toast('차량 정보를 읽고 있어요.'); }
  S.pre.car = { vin6: SIMCAR.vin6, odo: SIMCAR.odo, range: SIMCAR.range, at: nowText() }; savePre();
  log(`출발 전 차량 정보 (SIMULATION · 가상 차량 값) · 차대 …${SIMCAR.vin6} · 누적 ${SIMCAR.odo.toLocaleString('ko-KR')} km · 주행 가능 ${SIMCAR.range} km`, 'demo'); render();
}
let lastHo = '', lastExtra = '';
function vehCarSync(v) {                                    // called from onVeh: the reading that was asked for arrived, and the live values for the pre screen
  const ho = v.handover;
  if (ho && S.preAsk && ho.at >= S.preAsk - 1000 && JSON.stringify(ho) !== lastHo) {
    lastHo = JSON.stringify(ho); S.preAsk = 0;
    S.pre.car = { vin6: ho.vin6 || '', at: nowText() }; if (typeof ho.odoKm === 'number') S.pre.car.odo = ho.odoKm; if (typeof ho.rangeKm === 'number') S.pre.car.range = ho.rangeKm; savePre();
    log(`출발 전 차량 정보 (Pleos 차량) · 차대 ${ho.vin6 ? '…' + ho.vin6 : '확인 안 됨'} · 누적 ${typeof ho.odoKm === 'number' ? ho.odoKm.toLocaleString('ko-KR') + ' km' : '확인 안 됨'} · 주행 가능 ${typeof ho.rangeKm === 'number' ? ho.rangeKm.toLocaleString('ko-KR') + ' km' : '확인 안 됨'}`, 'app');
    return true;
  }
  const ex = JSON.stringify(v.extra || {}); if (ex !== lastExtra) { lastExtra = ex; return S.screen === 'pre'; }
  return false;
}""")
rep("""  pre: (a, k) => { const p = S.pre; if (a === 'chk')""", """  pre: (a, k) => { const p = S.pre; if (a === 'car') return preCar(); if (a === 'chk')""")
rep("""vehSync(v); vehChrome(v);""", """vehSync(v); vehChrome(v);
  if (vehCarSync(v) && S.screen === 'pre' && !(document.activeElement && document.activeElement.tagName === 'INPUT')) return render();""")

# ---- accident summary step 3 on a vehicle: the rest of the vehicle state
rep("""<div class="kv"><span>차량 위치</span>""", """<div class="kv"><span>와이퍼 · 하향등</span><b>와이퍼 ${(v.extra || {}).WIPER == null ? '값 없음' : String(v.extra.WIPER).toUpperCase() === 'ON' ? '작동 중' : '꺼짐'} · 하향등 ${(v.extra || {}).LOWBEAM == null ? '값 없음' : String(v.extra.LOWBEAM).toUpperCase() === 'ON' ? '켜짐' : '꺼짐'}</b></div><div class="kv"><span>배터리 · 연료</span><b>배터리 ${(v.extra || {}).EV_SOC == null ? '값 없음' : v.extra.EV_SOC + '%'} · 연료 ${(v.extra || {}).FUEL_PCT == null ? '값 없음' : v.extra.FUEL_PCT + '%'}</b></div><div class="kv"><span>차량 위치</span>""")

# ---- voice input next to the place field and the memo field (Pleos speech service only; nothing is shown elsewhere)
rep("""placeholder="도로 이름·방향을 직접 입력" autocomplete="off"></div>""", """placeholder="도로 이름·방향을 직접 입력" autocomplete="off">${sttBtn('tow.where')}</div>""")
rep("""placeholder="예 : 뒷좌석 카시트 1" autocomplete="off"></div>""", """placeholder="예 : 뒷좌석 카시트 1" autocomplete="off">${sttBtn('pre.memo')}</div>""")
rep("""/* ---- pre-drive check ---- */""", """/* adaptive6: voice input (Pleos SpeechToText, on a tap only) */
const sttBtn = f => (VEHMODE && vehSnap().stt === 'ready') ? `<button class="sbtn" data-f="stt:${f}" data-k="stt_${f.replace('.', '_')}">음성 입력</button>` : '';
window.onStt = (f, text, fin, st) => {
  if (st === 'listening') toast('듣고 있어요. 말씀해 주세요.');
  else if (st === 'final' && text) { setPath(f, String(text).slice(0, 60)); render(); toast('음성 입력 : ' + String(text).slice(0, 40)); }
  else if (String(st).indexOf('error') === 0) toast('음성 인식이 되지 않았어요. 다시 눌러 주세요.');
};
/* ---- pre-drive check ---- */""")
rep("""  pre: (a, k) => { const p = S.pre; if (a === 'car')""", """  stt: (f) => { if (!VEHMODE || !window.Native.sttStart(f)) toast('음성 인식을 쓸 수 없어요.'); },
  pre: (a, k) => { const p = S.pre; if (a === 'car')""")

# ---- Gleo Interface: open screens only
rep("""if (VEHMODE) window.onVeh();""", """if (VEHMODE) window.onVeh();
/* adaptive6: Gleo Interface (assets/schema.json). Each function only opens a screen; no call is placed and nothing in the vehicle is changed by voice. */
window.onGleo = name => {
  const done = (ok, m) => { try { if (NATIVE && window.Native.gleoDone) window.Native.gleoDone(name, ok, m); } catch (e) {} };
  if (name === 'OPEN_EMERGENCY_CALL_SCREEN') { log('음성 호출 (Gleo) : 119 전화 화면 열기', 'user'); ACT.d119(); return done(true, 'The dial screen was opened with 119. The call is not placed by the app.'); }
  if (name !== 'OPEN_ACCIDENT_RESPONSE' && name !== 'OPEN_ACCIDENT_GUIDE') return done(false, 'Unknown function.');
  if (S.hmi !== 'ACC') { intake.userStart(); S.hmi = 'ACC'; }
  if (name === 'OPEN_ACCIDENT_GUIDE') { log('음성 호출 (Gleo) : 사고 상황 정리 열기', 'user'); ACT.guide(); return done(true, 'The accident summary was opened.'); }
  S.screen = 'home'; log('음성 호출 (Gleo) : 사고 대응 화면 열기', 'user'); render(); done(true, 'The accident response screen was opened.');
};
{ let g = ''; try { g = NATIVE && window.Native.takeGleo ? window.Native.takeGleo() : ''; } catch (e) {} if (g) setTimeout(() => window.onGleo(g), 400); }""")

# ---- test hook and the "what really works" page
rep("""seats: v.seats, doors: v.doors, tts: v.tts }; })() : null,""", """seats: v.seats, doors: v.doors, tts: v.tts, stt: v.stt, extra: v.extra, ho: v.handover ? 1 : 0 }; })() : null,""")
rep("""    pre: { chk: S.pre.chk.filter(Boolean).length, memo: S.pre.memo.length, saved: !!S.pre.savedAt },""",
    """    pre: { chk: S.pre.chk.filter(Boolean).length, memo: S.pre.memo.length, saved: !!S.pre.savedAt, car: !!(S.pre.car && S.pre.car.at) },""")
rep("""/ 보호 모드 결과 음성 안내 : TextToSpeech</td></tr>
    <tr><td><span class="tag nat">R2D 네이티브 전용</span></td><td>출발 전 차량 정보 기록(차대번호 끝자리·주행거리·주행 가능 거리)</td></tr>`""",
    """/ 보호 모드 결과 음성 안내 : TextToSpeech / 출발 전 차량 정보(차대번호 끝 6자리·주행거리·주행 가능 거리)·와이퍼·하향등·배터리·연료 : Vehicle SDK / 음성 입력 : SpeechToText / 음성 호출 3개 : Gleo Interface (Pleos 검토·승인 전)</td></tr>`""")

idx.write_text(t, encoding='utf-8', newline='\n')
print('adaptive6 step applied; lines', t.count('\n') + 1)
