/* RE:SAFETY vehicle-event intake (hardening of pillars 01 and 02).
   Pure logic: no DOM, no network, no timers. The clock and the storage are injected, so the very same file runs inside the APK
   and in the off-device test page (verification/event_intake_test.html).

   What it decides for every incoming event: is it well formed, from an allowed source, new (not seen before, even across an app
   restart), still valid in time, and does it start a response state or belong to the one that already exists.
   One incident = one response state. A repeated, late or additional event never creates a second one.

   Only the labelled VIRTUAL source is accepted in this build. A real source (OEM crash signal) needs a protected, vendor-provided
   interface; it would be added to `allowedSources` behind that interface and is rejected here. This is not a crash detector.

   Event (JSON):
     id        string 1..64 [A-Za-z0-9._:-]   required, unique per event
     type      'crash' | 'crash_cancel' | 'crash_end'
     source    'virtual' (anything else is rejected)
     level     'DETECTED' | 'SUSPECTED' | 'NONE'      (type crash)
     score     0..1, optional (missing is shown as "-")
     ts        event time, epoch ms, optional (missing: the time of receipt is used and the entry is flagged)
     seq       integer per source, optional (used only to flag late delivery)
     ref       event id this cancel / end refers to (type crash_cancel / crash_end)
     escalate_s  seconds before an unanswered SUSPECTED prompt switches by itself (3..60, default 20)

   Decision: { outcome, action, reason, id, incident, flags, level, score, wait }
     outcome  ACCEPTED | DUPLICATE | STALE | REJECTED_SOURCE | REJECTED_FORMAT
     action   ENTER_ACC | PROMPT | ESCALATE | ABSORBED | CANCEL_PROMPT | END_NOTED | NOTED | NO_TARGET | NONE */
(function (root) {
  'use strict';
  var RANK = { NONE: 0, SUSPECTED: 1, DETECTED: 2 };
  var DEFAULTS = { validitySec: 120, futureSkewSec: 5, journalMax: 60, incidentMaxAgeSec: 6 * 3600, allowedSources: ['virtual'],
    escalateMin: 3, escalateMax: 60, escalateDefault: 20 };
  var ID_RE = /^[A-Za-z0-9._:-]{1,64}$/;

  function create(opts) {
    opts = opts || {};
    var cfg = {}; Object.keys(DEFAULTS).forEach(function (k) { cfg[k] = (opts.cfg && opts.cfg[k] !== undefined) ? opts.cfg[k] : DEFAULTS[k]; });
    var now = opts.now || function () { return Date.now(); };
    var load = opts.load || function () { return null; }, save = opts.save || function () {};
    var st = { v: 1, n: 0, journal: [], incident: null, lastEnded: null, prompt: null, sourceSeq: {},
      counters: { accepted: 0, duplicate: 0, stale: 0, rejected_source: 0, rejected_format: 0, absorbed: 0, late: 0 } };
    /* Stored state is checked field by field. Anything that does not have the expected shape is dropped, so a damaged store can never
       open an accident screen or a prompt by itself, and can never make handle() / restore() throw. */
    try {
      var saved = load();
      if (saved && typeof saved === 'object' && saved.v === 1 && Array.isArray(saved.journal)) {
        var num = function (x) { return typeof x === 'number' && isFinite(x); }, own = function (o, k) { return Object.prototype.hasOwnProperty.call(o, k); };
        st.n = num(saved.n) ? saved.n : 0;
        st.journal = saved.journal.filter(function (e) { return e && typeof e === 'object' && !Array.isArray(e) && typeof e.id === 'string'; }).slice(0, cfg.journalMax);
        var i = saved.incident;
        if (i && typeof i === 'object' && typeof i.id === 'string' && (i.state === 'ACTIVE' || i.state === 'PROMPT') && Array.isArray(i.events) && num(i.last) && typeof i.level === 'string' && own(RANK, i.level)) {
          i.events = i.events.filter(function (x) { return typeof x === 'string'; }).slice(0, 200); if (!num(i.started)) i.started = i.last;
          var p = saved.prompt, okPrompt = p && typeof p === 'object' && typeof p.eventId === 'string' && num(p.deadline) && num(p.wait);
          if (i.state === 'ACTIVE') st.incident = i;
          else if (okPrompt) { st.incident = i; st.prompt = { eventId: p.eventId, score: num(p.score) ? p.score : null, wait: p.wait, deadline: p.deadline }; }
        }
        var le = saved.lastEnded;
        if (le && typeof le === 'object' && num(le.endedAt)) st.lastEnded = { id: String(le.id || ''), endedAt: le.endedAt, state: String(le.state || 'ENDED'), reason: String(le.reason || ''), events: Array.isArray(le.events) ? le.events.filter(function (x) { return typeof x === 'string'; }).slice(0, 20) : [] };
        if (saved.sourceSeq && typeof saved.sourceSeq === 'object' && !Array.isArray(saved.sourceSeq)) Object.keys(saved.sourceSeq).slice(0, 20).forEach(function (k) { if (num(saved.sourceSeq[k])) st.sourceSeq[k] = saved.sourceSeq[k]; });
        if (saved.counters && typeof saved.counters === 'object') Object.keys(st.counters).forEach(function (k) { if (num(saved.counters[k])) st.counters[k] = saved.counters[k]; });
      }
    } catch (e) {}
    function persist() { try { save(st); } catch (e) {} }
    function seen(id) { for (var i = 0; i < st.journal.length; i++) if (st.journal[i].id === id) return st.journal[i]; return null; }
    function note(entry) { st.journal.unshift(entry); if (st.journal.length > cfg.journalMax) st.journal.length = cfg.journalMax; }
    function active() { return st.incident && (st.incident.state === 'ACTIVE' || st.incident.state === 'PROMPT') ? st.incident : null; }
    function close(state, reason, t) { var inc = st.incident; if (!inc) return; inc.state = state; inc.endReason = reason; inc.endedAt = t;
      st.lastEnded = { id: inc.id, endedAt: t, state: state, reason: reason, events: inc.events.slice(0, 20) }; st.incident = null; st.prompt = null; }
    function done(d, entry) { if (entry) note(entry); persist(); return d; }

    function handle(raw) {
      var t = now(), e = raw, flags = [];
      if (typeof raw === 'string') { try { e = JSON.parse(raw); } catch (x) { e = null; } }
      function reject(reason, id) { st.counters.rejected_format++; return done({ outcome: 'REJECTED_FORMAT', action: 'NONE', reason: reason, id: id || null, incident: st.incident ? st.incident.id : null, flags: flags },
        { id: id || '(invalid)', outcome: 'REJECTED_FORMAT', action: 'NONE', reason: reason, at: t }); }
      if (!e || typeof e !== 'object' || Array.isArray(e)) return reject('not_an_object');
      if (typeof e.id !== 'string' || !ID_RE.test(e.id)) return reject('bad_id');
      if (e.type !== 'crash' && e.type !== 'crash_cancel' && e.type !== 'crash_end') return reject('bad_type', e.id);
      if (typeof e.source !== 'string' || !e.source) return reject('bad_source', e.id);
      var prev = seen(e.id);
      if (prev) { st.counters.duplicate++; prev.dups = (prev.dups || 0) + 1; return done({ outcome: 'DUPLICATE', action: 'NONE', reason: 'seen_' + prev.outcome, id: e.id, incident: st.incident ? st.incident.id : null, flags: flags }); }
      var base = { id: e.id, type: e.type, source: e.source, at: t };
      if (cfg.allowedSources.indexOf(e.source) < 0) { st.counters.rejected_source++; base.outcome = 'REJECTED_SOURCE'; base.action = 'NONE';
        return done({ outcome: 'REJECTED_SOURCE', action: 'NONE', reason: 'source_not_allowed', id: e.id, incident: st.incident ? st.incident.id : null, flags: flags }, base); }
      /* own keys of RANK only, and a string only: "level in RANK" also accepted inherited names (toString, __proto__) and one-element arrays (['DETECTED']),
         which then opened a driver prompt. Found by verification/event_interface_fuzz.py (162 of 6000 random events). */
      if (e.type === 'crash' && (typeof e.level !== 'string' || !Object.prototype.hasOwnProperty.call(RANK, e.level))) return reject('bad_level', e.id);
      var score = (typeof e.score === 'number' && isFinite(e.score) && e.score >= 0 && e.score <= 1) ? e.score : null;
      if (e.type === 'crash' && score === null) flags.push('score_missing');
      var ts = (typeof e.ts === 'number' && isFinite(e.ts) && e.ts > 0) ? e.ts : null;
      if (ts === null) { flags.push('ts_missing'); ts = t; }
      else if (ts > t + cfg.futureSkewSec * 1000) { flags.push('clock_suspect'); ts = t; }
      if (typeof e.seq === 'number' && isFinite(e.seq)) {
        if (st.sourceSeq[e.source] !== undefined && e.seq <= st.sourceSeq[e.source]) { flags.push('late'); st.counters.late++; }
        else st.sourceSeq[e.source] = e.seq;
      }
      base.ts = ts; base.level = e.level || null; base.flags = flags;
      function out(outcome, action, reason, extra) {
        base.outcome = outcome; base.action = action; if (reason) base.reason = reason;
        if (outcome === 'ACCEPTED') st.counters.accepted++; if (outcome === 'STALE') st.counters.stale++; if (action === 'ABSORBED') st.counters.absorbed++;
        var d = { outcome: outcome, action: action, reason: reason || '', id: e.id, incident: st.incident ? st.incident.id : (st.lastEnded && outcome === 'STALE' ? st.lastEnded.id : null), flags: flags, level: e.level || null, score: score };
        if (extra) Object.keys(extra).forEach(function (k) { d[k] = extra[k]; });
        return done(d, base);
      }
      if (t - ts > cfg.validitySec * 1000) return out('STALE', 'NONE', 'older_than_validity');
      var inc = active();

      if (e.type === 'crash') {
        if (e.level === 'NONE') return out('ACCEPTED', 'NONE', 'below_threshold');
        if (!inc && st.lastEnded && ts <= st.lastEnded.endedAt) return out('STALE', 'NONE', 'before_incident_end');
        if (inc) {
          inc.events.push(e.id); inc.last = Math.max(inc.last, ts); if (RANK[e.level] > RANK[inc.level]) inc.level = e.level;
          if (inc.state === 'PROMPT' && e.level === 'DETECTED') { inc.state = 'ACTIVE'; inc.activatedBy = 'event'; st.prompt = null; return out('ACCEPTED', 'ESCALATE', 'detected_during_prompt'); }
          return out('ACCEPTED', 'ABSORBED', inc.state === 'ACTIVE' ? 'incident_already_active' : 'prompt_already_open');
        }
        st.incident = { id: 'inc-' + e.id, origin: 'event', source: e.source, started: t, last: ts, level: e.level, events: [e.id], state: e.level === 'DETECTED' ? 'ACTIVE' : 'PROMPT' };
        if (e.level === 'DETECTED') { st.incident.activatedBy = 'event'; return out('ACCEPTED', 'ENTER_ACC', ''); }
        var wait = Math.max(cfg.escalateMin, Math.min(cfg.escalateMax, +e.escalate_s || cfg.escalateDefault));
        st.prompt = { eventId: e.id, score: score, wait: wait, deadline: t + wait * 1000 };
        return out('ACCEPTED', 'PROMPT', '', { wait: wait });
      }
      var hit = inc && (e.ref ? inc.events.indexOf(e.ref) >= 0 : true);
      if (!hit) return out('ACCEPTED', 'NO_TARGET', 'nothing_to_apply');
      if (inc.state === 'PROMPT') { close('CANCELLED', e.type === 'crash_cancel' ? 'cancelled_by_source' : 'ended_by_source', t); return out('ACCEPTED', 'CANCEL_PROMPT', ''); }
      /* An accident screen that is already open is never taken away by the source: the person in the car ends it. */
      inc.sourceEnded = e.type; return out('ACCEPTED', e.type === 'crash_end' ? 'END_NOTED' : 'NOTED', 'user_ends_active_incident');
    }

    /* ---- actions of the person in the car ---- */
    function userStart() { var t = now(), inc = active();
      if (inc && inc.state === 'ACTIVE') return { action: 'ALREADY', incident: inc.id };
      if (inc) { inc.state = 'ACTIVE'; inc.activatedBy = 'user'; st.prompt = null; persist(); return { action: 'ENTER_ACC', incident: inc.id }; }
      st.incident = { id: 'inc-user-' + t + '-' + (++st.n), origin: 'user', source: 'user', started: t, last: t, level: 'DETECTED', events: [], state: 'ACTIVE', activatedBy: 'user' };
      persist(); return { action: 'ENTER_ACC', incident: st.incident.id }; }
    function userOk() { var inc = active(); if (!inc || inc.state !== 'PROMPT') return { action: 'NONE' }; close('CANCELLED', 'driver_ok', now()); persist(); return { action: 'CANCEL_PROMPT' }; }
    function userEnd() { var inc = active(); if (!inc) return { action: 'NONE' }; var id = inc.id; close('ENDED', 'user_end', now()); persist(); return { action: 'ENDED', incident: id }; }
    function escalate(eventId) { var inc = active(); if (!inc || inc.state !== 'PROMPT' || !st.prompt || st.prompt.eventId !== eventId) return false;
      inc.state = 'ACTIVE'; inc.activatedBy = 'timeout'; st.prompt = null; persist(); return true; }

    /* ---- after an app (re)start: what the HMI has to show ---- */
    function restore() { var t = now(), inc = active();
      if (!inc) return { hmi: 'NORMAL', incident: null, prompt: null, escalated: false, expired: false };
      if (t - inc.last > cfg.incidentMaxAgeSec * 1000) { var id = inc.id; close('ENDED', 'expired_on_restore', t); persist(); return { hmi: 'NORMAL', incident: null, prompt: null, escalated: false, expired: true, was: id }; }
      if (inc.state === 'ACTIVE') return { hmi: 'ACC', incident: inc.id, prompt: null, escalated: false, expired: false };
      var p = st.prompt, left = p ? p.deadline - t : 0;
      if (!p || left <= 0) { inc.state = 'ACTIVE'; inc.activatedBy = 'timeout_while_closed'; st.prompt = null; persist(); return { hmi: 'ACC', incident: inc.id, prompt: null, escalated: true, expired: false }; }
      return { hmi: 'NORMAL', incident: inc.id, prompt: { id: p.eventId, score: p.score, wait: p.wait, remainingMs: left }, escalated: false, expired: false };
    }
    function snapshot() { var inc = active(), j = st.journal[0] || null;
      return { incident: inc ? inc.id : null, state: inc ? inc.state : (st.lastEnded ? st.lastEnded.state : 'NONE'), level: inc ? inc.level : null, origin: inc ? inc.origin : null,
        events: inc ? inc.events.length : 0, prompt: st.prompt ? st.prompt.eventId : null, journal: st.journal.length, last: j ? { id: j.id, outcome: j.outcome, action: j.action } : null, counters: st.counters }; }
    return { handle: handle, userStart: userStart, userOpen: userStart, userOk: userOk, userEnd: userEnd, escalate: escalate, restore: restore, snapshot: snapshot,
      journal: function () { return st.journal.slice(); }, config: cfg };
  }
  root.EventIntake = { create: create, VERSION: 1 };
})(typeof window !== 'undefined' ? window : globalThis);
