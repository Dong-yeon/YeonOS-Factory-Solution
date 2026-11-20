/* POP 작업장 홈 (/pop) — 배지(+PIN) → 내 할 일(자격 게이트 미리보기) → 공정: 착수·멈춤·재개·종료, 실적 확정(수량·불량·투입),
   파라미터, 오늘 실적·정정 (docs/25 4.6·4.7). 배지 값은 PIN 을 누르는 동안만 메모리에 두고 끝나면 지운다. 수량은 글자 그대로.
   실적 확정은 오프라인 대기열(hb.pop.queue) — 요청 id·발생 시각을 누른 순간에 정해 재전송해도 같다.
   고장 신고(docs/25 4.35)는 온라인만 — 같은 내용 재전송은 같은 요청 id(처음 번호), 내용을 바꾸면 새 id.
   감독자 승인(4.37): 정정이 403 POP_APPROVAL_REQUIRED 면 감독자 배지 → PIN → 승인 토큰을 실어 다시 보낸다(배지·PIN 은 그동안만 메모리). */
(function () {
    'use strict';

    var API = '/api/pop';
    var REFRESH_MS = 60000;
    var feedback = document.getElementById('feedback');
    var badgePanel = document.getElementById('badgePanel');
    var pinPanel = document.getElementById('pinPanel');
    var taskPanel = document.getElementById('taskPanel');
    var tasksEl = document.getElementById('tasks');
    var switchBtn = document.getElementById('switchBtn');
    /** 배지(+PIN) 로그인(hb.pop 공용) — 키패드가 준비된 뒤 만든다 */
    var login = null;
    var scan = null, pad = null;
    var procPanel = document.getElementById('procPanel');
    var rejectedPanel = document.getElementById('rejectedPanel');
    var breakdownPanel = document.getElementById('breakdownPanel');
    var approvalPanel = document.getElementById('approvalPanel');
    var current = null;         // 열린 공정 보기(ProcView)
    var kiosk = null;           // 키오스크 고정(hb.kiosk, docs/25 4.52) — 다리 없는 브라우저에서는 아무 일도 하지 않는다

    function fail(err) {
        if (err && err.stale) { return; }
        // 서버 안내(details — 예: 출하 대기 칸 재고는 먼저 옮기세요, WMS F1)도 같이 보인다
        var text = err && err.message ? err.message : hb.t('js.error.server');
        if (err && err.details && err.details.length) { text += ' — ' + err.details.join(' / '); }
        hb.pop.feedback(feedback, 'error', text);
    }

    function qtyText(s) {
        if (s === null || s === undefined || s === '') { return ''; }
        var p = String(s).split('.');
        return p[0].replace(/\B(?=(\d{3})+(?!\d))/g, ',') + (p.length > 1 ? '.' + p[1] : '');
    }

    /** 붙는 화면(설비 점검·검사 입력 — pop/pm.js·pop/insp.js)이 등록한 패널 [{name, el, onScan, onSession}] — 아래 popHome */
    var mods = [];
    function openMod() {
        return mods.filter(function (m) { return !m.el.hidden; })[0] || null;
    }

    function showPanel(which) {
        mods.forEach(function (m) { m.el.hidden = which !== m.name; });
        badgePanel.hidden = which !== 'badge';
        pinPanel.hidden = which !== 'pin';
        taskPanel.hidden = which !== 'tasks';
        procPanel.hidden = which !== 'proc';
        rejectedPanel.hidden = which !== 'rejected';
        breakdownPanel.hidden = which !== 'breakdown';
        approvalPanel.hidden = which !== 'approval';
        if (which !== 'proc') { current = null; }
    }

    function worstMode(a, b) {
        if (a === 'BLOCK' || b === 'BLOCK') { return 'BLOCK'; }
        return a === 'WARN' || b === 'WARN' ? 'WARN' : 'OK';
    }

    function gateText(g) {
        if (!g) { return ''; }
        var names = (g.missing || []).map(function (m) { return m.qualNm; }).join(', ');
        if (g.mode === 'BLOCK') { return hb.t('js.pop.gateBlock', names); }
        if (g.mode === 'WARN') { return hb.t('js.pop.gateWarn', names); }
        return hb.t('js.pop.gateOk');
    }

    var PROC_STATUS = {WAIT: 'js.pop.st.WAIT', READY: 'js.pop.st.READY', RUN: 'js.pop.st.RUN', PAUSE: 'js.pop.st.PAUSE'};
    /** 공정 상태 → hb-status 톤(docs/40 부록 A.3): 대기·준비 neutral, 작업 중 normal(색 없음), 일시 정지 warning */
    var PROC_TONE = {WAIT: 'neutral', READY: 'neutral', RUN: 'normal', PAUSE: 'warning'};

    /** @param {string} s */
    function procBadge(s) {
        return hb.status(/** @type {any} */ (PROC_TONE[s] || 'neutral'), PROC_STATUS[s] ? hb.t(PROC_STATUS[s]) : s);
    }

    /** 계획 시각 — 오늘(작업일)이면 시:분, 다른 날이면 월-일 시:분(공장 시간대) */
    function planTime(iso) {
        var dt = hb.fmt.datetime(iso, zone);
        return dt.substring(0, 10) === workDay ? dt.substring(11) : dt.substring(5);
    }

    /** 일일 배정 자리(docs/25 4.56) — 순번·계획 시각·자원. 순서 어김·앞선 미착수는 글자로 알리기만(막지 않는다, AP-10) */
    function planLine(plan) {
        var el = hb.el('p', 'hb-pop-plan');
        if (!plan) { el.textContent = hb.t('js.pop.planNone'); el.setAttribute('data-plan', 'none'); return el; }
        var text = hb.t('js.pop.plan', plan.seq, planTime(plan.startAt), planTime(plan.endAt), plan.resCd);
        if (plan.early) {
            text += ' · ' + hb.t('js.pop.planEarly', plan.ahead);
        } else if (plan.ahead > 0) {
            text += ' · ' + hb.t('js.pop.planAhead', plan.ahead);
        }
        el.textContent = text;
        el.setAttribute('data-plan', plan.early ? 'early' : plan.ahead > 0 ? 'ahead' : 'ok');
        return el;
    }

    function renderTasks(tasks) {
        tasksEl.textContent = '';
        // 현행 계획이 하나라도 걸려 있으면 "계획 순서" 임을 알리고, 계획 밖 공정은 그렇다고 적는다. 없으면 예전 그대로
        var planned = tasks.filter(function (t) { return t.plan; });
        document.getElementById('taskInfo').textContent = !tasks.length ? hb.t('js.pop.noTasks')
            : planned.length ? hb.t('js.pop.taskCountPlan', tasks.length, planned[0].plan.planNo) : hb.t('js.pop.taskCount', tasks.length);
        tasks.forEach(function (t) {
            var li = hb.el('li', 'hb-pop-task');
            li.tabIndex = 0;
            li.setAttribute('role', 'button');
            li.addEventListener('click', function () { openProc(t.woProcId); });
            li.addEventListener('keydown', function (e) { if (e.key === 'Enter') { openProc(t.woProcId); } });
            // 제목 = 지시 번호 · 수량, 상태 = 배지(docs/40 P2)
            li.appendChild(hb.el('h3', null, t.woNo + ' · ' + qtyText(t.planQty) + ' ' + t.unitCd));
            li.appendChild(procBadge(t.procStatus));
            li.appendChild(hb.el('p', null, t.opSeq + '. ' + t.procCd + ' · ' + t.procNm));
            li.appendChild(hb.el('p', null, t.itemCd + ' ' + t.itemNm));
            li.appendChild(hb.el('p', 'hb-pop-sub', [t.equipCd ? t.equipCd + ' · ' + t.equipNm : hb.t('js.pop.noEquip'), t.planStart].join(' · ')));
            if (planned.length) { li.appendChild(planLine(t.plan)); }
            var g = hb.el('span', 'hb-pop-gate', gateText(t.gate));
            g.setAttribute('data-mode', t.gate ? t.gate.mode : 'OK');
            li.appendChild(g);
            tasksEl.appendChild(li);
        });
    }

    function render(view) {
        var s = view.session;
        zone = s.zone;
        workDay = s.workDay;
        document.getElementById('where').textContent = s.terminalNm + ' · ' + (s.nodeNm || s.plantCd);
        document.getElementById('shift').textContent = s.shiftCd ? hb.t('js.pop.shift', s.workDay, s.shiftNm || s.shiftCd)
            : hb.t('js.pop.noShift', s.workDay);
        document.getElementById('worker').textContent = s.workerCd ? hb.t('js.pop.workerIs', s.workerNm, s.workerCd) : '';
        switchBtn.hidden = !s.workerCd;
        if (kiosk) { kiosk.worker(s.workerCd); }   // 작업자가 바뀌면(내려감 포함) 다시 잠근다
        breakdownOn = !!s.breakdown;
        mods.forEach(function (m) { if (m.onSession) { m.onSession(s); } });
        document.getElementById('breakdownBtn').hidden = !breakdownOn;
        if (s.workerCd) {
            if (login) { login.clear(); }
            renderTasks(view.tasks);
            if (!current && breakdownPanel.hidden && approvalPanel.hidden && !openMod()) { showPanel('tasks'); }   // 신고 쓰는 중에는 자동 새로 보기가 화면을 바꾸지 않는다
        } else if (!login || !login.pending()) {
            showPanel('badge');
        }
    }

    function refresh() {
        return hb.latest('home', hb.pop.api(API + '/home')).then(function (res) { render(res.data); }, fail);
    }

    /** 작업자가 내려갔으면(428) 배지 화면으로 */
    function workFail(err) {
        if (login) { login.gone(err); }
        fail(err && err.errors && err.errors.length ? {message: err.errors.map(function (x) { return x.message; }).join(' / ')} : err);
    }

    // ------------------------------------------------------------------ 공정

    var QTY = ['goodQty', 'scrapQty', 'reworkQty', 'repairQty'];
    var KIND = {scrapQty: 'SCRAP', reworkQty: 'REWORK', repairQty: 'REPAIR'};
    var KIND_KEY = {SCRAP: 'js.pop.kind.SCRAP', REWORK: 'js.pop.kind.REWORK', REPAIR: 'js.pop.kind.REPAIR'};
    var EVENT_KEY = {start: 'js.pop.ev.start', pause: 'js.pop.ev.pause', resume: 'js.pop.ev.resume', end: 'js.pop.ev.end'};
    var zone = null;            // 공장 시간대(세션)
    var workDay = null;         // 작업일(세션) — 계획 시각을 오늘이면 시:분만 보이려고
    var entry = {};             // 수량 칸 값(글자)
    var inputs = [];            // 투입 [{lotNo, itemCd, itemNm, unitCd, balance, qty}]
    var target = null;          // 키패드가 쓰는 칸 {get, set, el}
    var suggested = null;       // 수집 COUNT 제안을 칸에 넣었으면 {goodQty, scrapQty} — 그대로 확정하면 fromCount
    var qtyPad = null;

    function openProc(id) {
        return hb.latest('proc', hb.pop.api(API + '/procs/' + id)).then(function (res) {
            entry = {};
            inputs = [];
            suggested = null;
            renderProc(res.data);
            showPanel('proc');
            current = res.data;
        }, workFail);
    }

    function reloadProc() { return current ? openProc(current.proc.woProcId) : Promise.resolve(); }

    function setTarget(t) {
        target = t;
        document.querySelectorAll('.hb-pop-field').forEach(function (b) { b.setAttribute('aria-pressed', String(b === t.el)); });
        qtyPad.set(t.get());
    }

    function qtyField(name) {
        var el = document.querySelector('[data-qty="' + name + '"]');
        return {el: el, get: function () { return entry[name] || ''; }, set: function (v) {
            entry[name] = v;
            el.querySelector('b').textContent = qtyText(v);
            syncDefects();
        }};
    }

    function renderProc(v) {
        var p = v.proc;
        current = v;
        document.getElementById('procTitle').textContent = p.opSeq + '. ' + p.procCd + ' · ' + p.procNm;
        var st = document.getElementById('procStatus');
        st.textContent = '';
        st.appendChild(procBadge(p.status));
        document.getElementById('procInfo').textContent = [p.itemCd + ' ' + p.itemNm, qtyText(p.planQty) + ' ' + p.unitCd, p.woNo,
            p.equipCd ? p.equipCd + ' · ' + p.equipNm : hb.t('js.pop.noEquip')].join(' · ');
        var g = document.getElementById('procGate');
        // 자격 게이트 + 착수 게이트(QMS 등) 미리보기 — 더 나쁜 쪽 색, 이유는 이어서(docs/25 4.21)
        var sg = v.startGate;
        var mode = worstMode(v.gate ? v.gate.mode : 'OK', sg ? sg.mode : 'OK');
        g.textContent = [gateText(v.gate)].concat(sg ? sg.reasons.map(function (r) { return r.message; }) : []).join(' · ');
        g.setAttribute('data-mode', mode);
        // 일일 배정 자리 — 계획에 있는 공정만(없으면 줄을 비운다)
        var planHost = document.getElementById('procPlan');
        planHost.textContent = '';
        if (v.plan) { planHost.appendChild(planLine(v.plan)); }
        var running = p.status === 'RUN' || p.status === 'PAUSE';
        document.getElementById('startBtn').hidden = !(p.status === 'WAIT' || p.status === 'READY');
        document.getElementById('pauseBtn').hidden = p.status !== 'RUN';
        document.getElementById('resumeBtn').hidden = p.status !== 'PAUSE';
        document.getElementById('endBtn').hidden = !running;
        document.getElementById('procBreakdownBtn').hidden = !breakdownOn;
        document.getElementById('reasonBox').hidden = true;
        document.getElementById('resultBox').hidden = !running;
        document.getElementById('paramBox').hidden = !running || !v.params.length;
        // 수집 COUNT 제안(docs/25 4.27) — 진행 중 공정·설비 하나일 때만 서버가 준다
        var cs = v.countSuggest;
        document.getElementById('countBox').hidden = !running || !cs;
        if (cs) {
            document.getElementById('countInfo').textContent = hb.t('js.pop.countInfo', hb.fmt.datetime(cs.since, zone).substring(11),
                qtyText(cs.goodQty), qtyText(cs.scrapQty));
        }
        // 수량 칸
        QTY.forEach(function (n) { qtyField(n).set(entry[n] || ''); });
        setTarget(qtyField('goodQty'));
        renderInputs();
        document.getElementById('bomInfo').textContent = v.bom.length ? hb.t('js.pop.bom', v.bom.map(function (b) {
            return b.itemCd + ' ' + qtyText(b.reqQty) + ' ' + b.unitCd; }).join(', ')) : '';
        // 멈춤 사유
        var reasons = document.getElementById('reasons');
        reasons.textContent = '';
        v.reasons.forEach(function (r) {
            var b = hb.el('button', null, r.name);
            b.type = 'button';
            b.addEventListener('click', function () { event('pause', {reasonCd: r.code}, b); });
            reasons.appendChild(b);
        });
        renderParams(v.params);
        renderToday(v);
        mods.forEach(function (m) { if (m.onProc) { m.onProc(v); } });   // 붙는 화면(검사 입력)이 공정 화면의 자기 버튼을 켠다
    }

    function syncDefects() {
        var box = document.getElementById('defectBox');
        var rows = document.getElementById('defectRows');
        var keep = {};
        rows.querySelectorAll('select').forEach(function (s) { keep[s.getAttribute('data-kind')] = s.value; });
        rows.textContent = '';
        var any = false;
        Object.keys(KIND).forEach(function (n) {
            if (!entry[n] || Number(entry[n]) === 0) { return; }
            any = true;
            var line = hb.el('label', 'hb-pop-defect');
            line.appendChild(hb.el('span', null, hb.t(KIND_KEY[KIND[n]]) + ' ' + qtyText(entry[n])));
            var sel = hb.el('select');
            sel.setAttribute('data-kind', KIND[n]);
            var o0 = hb.el('option', null, hb.t('js.pop.pickDefect'));
            o0.value = '';
            sel.appendChild(o0);
            (current ? current.defects : []).forEach(function (d) {
                var o = hb.el('option', null, d.defectCd + ' · ' + d.defectNm);
                o.value = d.defectCd;
                sel.appendChild(o);
            });
            sel.value = keep[KIND[n]] || '';
            line.appendChild(sel);
            rows.appendChild(line);
        });
        box.hidden = !any;
    }

    function renderInputs() {
        var ul = document.getElementById('inputs');
        ul.textContent = '';
        inputs.forEach(function (x, i) {
            var li = hb.el('li');
            li.appendChild(hb.el('span', null, x.lotNo + ' · ' + x.itemCd + ' ' + x.itemNm + ' · ' + hb.t('js.pop.balance', qtyText(x.balance), x.unitCd)));
            var q = hb.el('button', 'hb-pop-field');
            q.type = 'button';
            q.appendChild(hb.el('span', null, hb.t('js.pop.inputQty', x.unitCd)));
            q.appendChild(hb.el('b', null, qtyText(x.qty)));
            var t = {el: q, get: function () { return x.qty || ''; }, set: function (v) { x.qty = v; q.querySelector('b').textContent = qtyText(v); }};
            q.addEventListener('click', function () { setTarget(t); });
            li.appendChild(q);
            var rm = hb.el('button', null, hb.t('js.pop.remove'));
            rm.type = 'button';
            rm.addEventListener('click', function () { inputs.splice(i, 1); renderInputs(); setTarget(qtyField('goodQty')); });
            li.appendChild(rm);
            ul.appendChild(li);
            if (i === inputs.length - 1 && !x.qty) { setTarget(t); }
        });
    }

    function scanInput(lotNo) {
        if (inputs.some(function (x) { return x.lotNo === lotNo; })) {
            hb.pop.feedback(feedback, 'info', hb.t('js.pop.already', lotNo));
            return;
        }
        hb.pop.api(API + '/lots?lotNo=' + encodeURIComponent(lotNo)).then(function (res) {
            var l = res.data;
            if (l.holdYn === 'Y') { hb.pop.feedback(feedback, 'error', hb.t('js.pop.lotHold', l.lotNo)); return; }
            inputs.push({lotNo: l.lotNo, itemCd: l.itemCd, itemNm: l.itemNm, unitCd: l.unitCd, balance: l.balance, qty: ''});
            renderInputs();
            hb.pop.feedback(feedback, 'ok', hb.t('js.pop.scanned', l.lotNo));
        }, function (err) {
            hb.pop.feedback(feedback, 'error', err.status === 404 ? hb.t('js.pop.noLot', lotNo) : err.message);
        });
    }

    function renderParams(params) {
        var ul = document.getElementById('params');
        ul.textContent = '';
        params.forEach(function (pr) {
            var li = hb.el('li');
            var range = [pr.lsl, pr.usl].some(function (x) { return x !== null && x !== undefined; })
                ? hb.t('js.pop.range', pr.lsl == null ? '' : pr.lsl, pr.usl == null ? '' : pr.usl) : '';
            li.appendChild(hb.el('span', null, pr.paramCd + ' · ' + pr.paramNm + (pr.unitNm ? ' (' + pr.unitNm + ')' : '') + ' ' + range));
            if (pr.lastValue != null) {
                var last = hb.el('span', 'hb-pop-judge', hb.t('js.pop.last', pr.lastValue, pr.lastJudge));
                last.setAttribute('data-judge', pr.lastJudge);
                li.appendChild(last);
            }
            var val = '';
            var f = hb.el('button', 'hb-pop-field');
            f.type = 'button';
            f.appendChild(hb.el('span', null, hb.t('js.pop.value')));
            f.appendChild(hb.el('b'));
            var t = {el: f, get: function () { return val; }, set: function (v) { val = v; f.querySelector('b').textContent = v; }};
            f.addEventListener('click', function () { setTarget(t); });
            li.appendChild(f);
            var save = hb.el('button', 'hb-pop-primary', hb.t('js.pop.save'));
            save.type = 'button';
            save.addEventListener('click', function () {
                if (!val) { setTarget(t); return; }
                hb.busy(save, function () {
                    return hb.pop.api(API + '/procs/' + current.proc.woProcId + '/params', {method: 'POST', body: {
                        clientReqId: hb.pop.reqId(), occurredAt: new Date().toISOString(), paramCd: pr.paramCd, value: val}});
                }).then(function (res) {
                    if (!res) { return; }
                    hb.pop.feedback(feedback, res.data.judge === 'OK' ? 'ok' : 'error', hb.t('js.pop.judged', pr.paramCd, val, res.data.judge));
                    return reloadProc();
                }, workFail);
            });
            li.appendChild(save);
            // 되돌리는 동작은 저장(주동작)과 떨어진 줄 끝(docs/25 4.28)
            if (pr.lastActualId != null) { li.appendChild(paramCorrectButton(pr)); }
            ul.appendChild(li);
        });
    }

    /** 마지막 실측 정정(역분개, docs/25 4.38) — 사유 필수, 공장 정책이면 감독자 승인(4.37). 정정하면 그 앞 값이 "최근" 이 된다 */
    function paramCorrectButton(pr) {
        var b = hb.el('button', 'hb-pop-danger', hb.t('js.pop.correct'));
        b.type = 'button';
        b.addEventListener('click', function () {
            var procId = current.proc.woProcId;
            var url = API + '/params/' + pr.lastActualId + '/correct';
            hb.dialog.prompt({title: hb.t('js.pop.dlg.paramCorrect'), message: hb.t('js.pop.paramCorrectReason', pr.paramCd, pr.lastValue),
                label: hb.t('js.mes.dlg.reason'), ok: hb.t('js.pop.correct'), required: true, maxLength: 500, danger: true}).then(function (reason) {
                if (reason === null) { return; }
                hb.busy(b, function () {
                    return hb.pop.api(url, {method: 'POST', body: {reason: reason}});
                }).then(function (res) {
                    if (!res) { return; }
                    hb.pop.feedback(feedback, 'ok', hb.t('js.pop.paramCorrected', pr.paramCd));
                    return reloadProc();
                }, function (err) {
                    if (err && err.code === 'POP_APPROVAL_REQUIRED') {
                        startApproval({action: 'PARAM_CORRECT', targetId: pr.lastActualId, reason: reason, procId: procId, url: url,
                            what: hb.t('js.pop.ap.whatParam', pr.paramCd, pr.lastValue)});
                        return;
                    }
                    workFail(err);
                });
            });
        });
        return b;
    }

    function renderToday(v) {
        document.getElementById('todayInfo').textContent = hb.t('js.pop.todayGood', qtyText(v.todayGood), qtyText(v.proc.planQty), v.proc.unitCd);
        var ul = document.getElementById('today');
        ul.textContent = '';
        v.today.forEach(function (r) {
            var li = hb.el('li', r.reversed ? 'hb-pop-void' : null);
            li.appendChild(hb.el('span', null, [hb.fmt.datetime(r.occurredAt, zone).substring(11), hb.t('js.pop.qtys', qtyText(r.goodQty),
                qtyText(r.scrapQty), qtyText(r.reworkQty), qtyText(r.repairQty)), r.lotNo || '', r.workerNm || ''].join(' · ')));
            if (!r.reversed) {
                var b = hb.el('button', 'hb-pop-danger', hb.t('js.pop.correct'));
                b.type = 'button';
                b.addEventListener('click', function () {
                    // 화면 안 대화상자(docs/40 V-7) — 사유 필수(서버도 REQUIRED), 위험 톤
                    hb.dialog.prompt({title: hb.t('js.pop.dlg.correct'), message: hb.t('js.pop.correctReason'), label: hb.t('js.mes.dlg.reason'),
                        ok: hb.t('js.pop.correct'), required: true, maxLength: 500, danger: true}).then(function (reason) {
                        if (reason === null) { return; }
                        hb.busy(b, function () {
                            return hb.pop.api(API + '/results/' + r.resultId + '/correct', {method: 'POST', body: {reason: reason}});
                        }).then(function (res) {
                            if (!res) { return; }
                            hb.pop.feedback(feedback, 'ok', hb.t('js.pop.corrected'));
                            return reloadProc();
                        }, function (err) {
                            if (err && err.code === 'POP_APPROVAL_REQUIRED') {
                                startApproval({action: 'RESULT_CORRECT', targetId: r.resultId, reason: reason, procId: v.proc.woProcId,
                                    url: API + '/results/' + r.resultId + '/correct',
                                    what: hb.t('js.pop.ap.what', hb.fmt.datetime(r.occurredAt, zone).substring(11), hb.t('js.pop.qtys',
                                        qtyText(r.goodQty), qtyText(r.scrapQty), qtyText(r.reworkQty), qtyText(r.repairQty)))});
                                return;
                            }
                            workFail(err);
                        });
                    });
                });
                li.appendChild(b);
            }
            ul.appendChild(li);
        });
    }

    function event(name, extra, button) {
        var body = {clientReqId: hb.pop.reqId()};
        Object.keys(extra || {}).forEach(function (k) { body[k] = extra[k]; });
        return hb.busy(button, function () {
            return hb.pop.api(API + '/procs/' + current.proc.woProcId + '/events/' + name, {method: 'POST', body: body});
        }).then(function (res) {
            if (!res) { return; }
            hb.pop.feedback(feedback, 'ok', hb.t(EVENT_KEY[name]));
            return reloadProc();
        }, function (err) {
            // 자격 경고·착수 게이트 경고는 같은 한 번의 확인(docs/29_P3 12.9 G-1)
            if (err && (err.code === 'QUAL_WARN_ACK' || err.code === 'START_GATE_WARN_ACK')) {
                return hb.dialog.confirm({title: hb.t('js.pop.dlg.ack'), message: err.message, ok: hb.t('js.pop.dlg.ackOk')}).then(function (ok) {
                    if (!ok) { return; }
                    extra = extra || {};
                    extra.ack = true;
                    return event(name, extra, button);
                });
            }
            workFail(err);
        });
    }

    function confirmResult(button) {
        var defects = [];
        var missing = false;
        document.querySelectorAll('#defectRows select').forEach(function (sel) {
            var kind = sel.getAttribute('data-kind');
            var n = Object.keys(KIND).filter(function (k) { return KIND[k] === kind; })[0];
            if (!sel.value) { missing = true; return; }
            defects.push({kind: kind, defectCd: sel.value, qty: entry[n]});
        });
        if (missing) {
            hb.pop.feedback(feedback, 'error', hb.t('js.pop.needDefect'));
            return;
        }
        var body = {clientReqId: hb.pop.reqId(), occurredAt: new Date().toISOString(), goodQty: entry.goodQty || '0',
            scrapQty: entry.scrapQty || '0', reworkQty: entry.reworkQty || '0', repairQty: entry.repairQty || '0', defects: defects,
            inputs: inputs.map(function (x) { return {lotNo: x.lotNo, qty: x.qty}; }),
            fromCount: !!suggested && (entry.goodQty || '0') === suggested.goodQty && (entry.scrapQty || '0') === suggested.scrapQty};
        hb.busy(button, function () {
            return hb.pop.queue.send(API + '/procs/' + current.proc.woProcId + '/results', body);
        }).then(function (res) {
            if (!res) { return; }
            entry = {};
            inputs = [];
            suggested = null;
            if (res.queued) {
                hb.pop.feedback(feedback, 'info', hb.t('js.pop.queued'));
                return renderProc(current);
            }
            var r = res.data;
            hb.pop.feedback(feedback, 'ok', hb.t('js.pop.done', qtyText(r.todayGood), qtyText(r.planQty), r.unitCd)
                + (r.lotNo ? ' · ' + r.lotNo : '') + (r.lastDefect ? ' · ' + hb.t('js.pop.lastDefect', r.lastDefect) : ''));
            return reloadProc();
        }, workFail);
    }

    // ------------------------------------------------------------------ 고장 신고(docs/25 4.35)

    var breakdownOn = false;    // 세션 breakdown — CMS 구현 없음(NONE)·PDA 면 거짓
    /** 신고 초안 — from = 돌아갈 화면, reqId = 보낸 적 있는 같은 내용의 요청 id(내용을 바꾸면 버린다) */
    var bd = {from: 'tasks', procId: null, equipId: null, symptomCd: null, reqId: null};
    var bdText = document.getElementById('bdText');

    function pressed(host, el) {
        host.querySelectorAll('button').forEach(function (b) { b.setAttribute('aria-pressed', String(b === el)); });
    }

    function openBreakdown(equipId) {
        var procId = current && !procPanel.hidden ? current.proc.woProcId : null;
        return hb.latest('bdEquips', hb.pop.api(API + '/breakdown/equips')).then(function (res) {
            bd = {from: procId ? 'proc' : 'tasks', procId: procId, equipId: null, symptomCd: null, reqId: null};
            bdText.value = '';
            bdPhoto = null;
            document.getElementById('bdForm').hidden = false;
            document.getElementById('bdPhotoCard').hidden = true;
            // 돌아갈 곳 — 공정 화면에서 열었으면 그 공정으로
            document.getElementById('breakdownClose').textContent = hb.t(procId ? 'js.pop.bd.backProc' : 'js.pop.bd.backTasks');
            renderBdEquips(res.data, equipId);
            showPanel('breakdown');
        }, workFail);
    }

    function closeBreakdown() {
        var back = bd.from === 'proc' && bd.procId ? openProc(bd.procId) : Promise.resolve();
        return back.then(function () {
            if (procPanel.hidden) { showPanel('tasks'); return refresh(); }   // 공정이 그새 끝났으면 내 할 일로
        });
    }

    function renderBdEquips(list, pre) {
        var host = document.getElementById('bdEquips');
        host.textContent = '';
        renderBdSymptoms(null);
        if (!list.length) { hb.pop.feedback(feedback, 'info', hb.t('js.pop.bd.noEquip')); }
        list.forEach(function (e) {
            var b = hb.el('button', null, e.equipCd + ' · ' + e.equipNm);
            b.type = 'button';
            b.setAttribute('aria-pressed', 'false');
            b.addEventListener('click', function () { pickEquip(e.equipId, b); });
            host.appendChild(b);
            if (e.equipId === pre) { pickEquip(e.equipId, b); }
        });
    }

    function pickEquip(id, button) {
        pressed(document.getElementById('bdEquips'), button);
        bd.equipId = id;
        bd.symptomCd = null;
        bd.reqId = null;
        renderBdSymptoms(null);
        hb.latest('bdSymptoms', hb.pop.api(API + '/breakdown/symptoms?equipId=' + id)).then(function (res) {
            renderBdSymptoms(res.data);
        }, workFail);
    }

    /** @param {{symptomCd: string, symptomNm: string}[] | null} list null = 설비를 아직 안 골랐음 */
    function renderBdSymptoms(list) {
        var host = document.getElementById('bdSymptoms');
        host.textContent = '';
        var info = document.getElementById('bdSymptomInfo');
        info.textContent = list === null ? hb.t('js.pop.bd.pickEquip') : list.length ? '' : hb.t('js.pop.bd.noSymptom');
        info.hidden = !info.textContent;
        (list || []).forEach(function (x) {
            var b = hb.el('button', null, x.symptomNm);
            b.type = 'button';
            b.setAttribute('aria-pressed', 'false');
            // 다시 누르면 고르기 취소(글만으로도 신고할 수 있다)
            b.addEventListener('click', function () {
                bd.symptomCd = bd.symptomCd === x.symptomCd ? null : x.symptomCd;
                bd.reqId = null;
                pressed(host, bd.symptomCd ? b : null);
            });
            host.appendChild(b);
        });
    }

    function sendBreakdown(button) {
        var text = bdText.value.trim();
        if (!bd.equipId) { hb.pop.feedback(feedback, 'error', hb.t('js.pop.bd.pickEquip')); return; }
        if (!bd.symptomCd && !text) { hb.pop.feedback(feedback, 'error', hb.t('js.pop.bd.need')); bdText.focus(); return; }
        if (!navigator.onLine) { hb.pop.feedback(feedback, 'error', hb.t('js.pop.bd.offline')); return; }
        // 응답을 못 받았을 수 있으니 같은 내용이면 같은 id — 서버가 처음 번호를 돌려준다(duplicate)
        bd.reqId = bd.reqId || hb.pop.reqId();
        var body = {equipId: bd.equipId, symptomCd: bd.symptomCd, symptomText: text || null, clientReqId: bd.reqId};
        hb.busy(button, function () {
            return hb.pop.api(API + '/breakdown', {method: 'POST', body: body});
        }).then(function (res) {
            if (!res) { return; }
            var r = res.data;
            var said = hb.t(r.duplicate ? 'js.pop.bd.dup' : 'js.pop.bd.done', r.reqNo);
            if (r.photoMax > 0) { openPhotos(r); hb.pop.feedback(feedback, 'ok', said); return; }   // 사진 단계(docs/25 4.44)
            return closeBreakdown().then(function () { hb.pop.feedback(feedback, 'ok', said); });
        }, function (err) {
            if (err && (err.code === 'NETWORK' || err.code === 'TIMEOUT')) {
                hb.pop.feedback(feedback, 'error', hb.t('js.pop.bd.offline'));
                return;
            }
            workFail(err);
        });
    }

    // ------------------------------------------------------------------ 신고 사진(docs/25 4.44, CMS C25)

    var PHOTO_EDGE = 1600;      // 긴 변(px) — 늘 다시 인코딩(D74): 크기를 줄이고 EXIF(촬영 위치 등)를 지운다
    var PHOTO_QUALITY = 0.85;
    var THUMB_EDGE = 96;
    /** 사진 단계 — {reqNo, reqId, max, bytes, type, count(null = 아직 모름), items:[{blob, name, thumb, state}]} */
    var bdPhoto = null;
    var PHOTO_STATE = {sending: 'js.pop.bd.photoSending', done: 'js.pop.bd.photoOk', dup: 'js.pop.bd.photoDup',
        failed: 'js.pop.bd.photoFailed'};

    function openPhotos(r) {
        var exts = r.photoExts || [];
        var jpeg = exts.indexOf('jpg') >= 0 || exts.indexOf('jpeg') >= 0;
        bdPhoto = {reqNo: r.reqNo, reqId: bd.reqId, max: r.photoMax, bytes: r.photoBytes, type: jpeg ? 'image/jpeg' : 'image/png',
            count: null, items: []};
        document.getElementById('bdPhotoInput').accept = exts.map(function (x) { return x === 'png' ? 'image/png' : 'image/jpeg'; })
            .filter(function (x, i, a) { return a.indexOf(x) === i; }).join(',');
        document.getElementById('bdForm').hidden = true;
        document.getElementById('bdPhotoCard').hidden = false;
        renderPhotos();
    }

    function renderPhotos() {
        var p = bdPhoto;
        document.getElementById('bdPhotoInfo').textContent = p.count === null ? hb.t('js.pop.bd.photoInfoMax', p.reqNo, p.max)
            : hb.t('js.pop.bd.photoInfo', p.reqNo, p.count, p.max);
        document.getElementById('bdPhotoAdd').hidden = p.full === true || (p.count !== null && p.count >= p.max);
        var host = document.getElementById('bdPhotos');
        host.textContent = '';
        p.items.forEach(function (it) {
            var li = hb.el('li');
            li.setAttribute('data-state', it.state);
            var img = hb.el('img');
            img.src = it.thumb;      // data: URL(CSP img-src 'self' data:) — blob: URL 은 쓰지 않는다
            img.alt = '';
            li.appendChild(img);
            li.appendChild(hb.el('span', null, hb.t(PHOTO_STATE[it.state])));
            if (it.state === 'failed') {
                var again = hb.el('button', null, hb.t('js.pop.bd.photoRetry'));
                again.type = 'button';
                again.addEventListener('click', function () { sendPhoto(it); });
                li.appendChild(again);
            }
            host.appendChild(li);
        });
    }

    /** 사진 줄이기 — createImageBitmap(URL 없이 디코드) → canvas 긴 변 PHOTO_EDGE → JPEG(또는 PNG). 썸네일은 작은 canvas 의 data: URL */
    function shrink(file, type) {
        return createImageBitmap(file).then(function (bmp) {
            var scale = Math.min(1, PHOTO_EDGE / Math.max(bmp.width, bmp.height));
            var c = document.createElement('canvas');
            c.width = Math.round(bmp.width * scale);
            c.height = Math.round(bmp.height * scale);
            c.getContext('2d').drawImage(bmp, 0, 0, c.width, c.height);
            var ts = THUMB_EDGE / Math.max(bmp.width, bmp.height);
            var t = document.createElement('canvas');
            t.width = Math.max(1, Math.round(bmp.width * ts));
            t.height = Math.max(1, Math.round(bmp.height * ts));
            t.getContext('2d').drawImage(bmp, 0, 0, t.width, t.height);
            if (bmp.close) { bmp.close(); }
            return new Promise(function (resolve, reject) {
                c.toBlob(function (blob) {
                    if (blob) { resolve({blob: blob, thumb: t.toDataURL('image/jpeg', 0.7)}); } else { reject(new Error('encode')); }
                }, type, PHOTO_QUALITY);
            });
        });
    }

    function pickPhoto(file) {
        var p = bdPhoto;
        if (!p || !file) { return; }
        if (!navigator.onLine) { hb.pop.feedback(feedback, 'error', hb.t('js.pop.bd.photoOffline')); return; }
        shrink(file, p.type).then(function (x) {
            if (x.blob.size > p.bytes) {
                hb.pop.feedback(feedback, 'error', hb.t('js.pop.bd.photoTooBig', Math.floor(p.bytes / 1048576)));
                return;
            }
            var it = {blob: x.blob, name: 'photo-' + (p.items.length + 1) + (p.type === 'image/png' ? '.png' : '.jpg'), thumb: x.thumb,
                state: 'sending'};
            p.items.push(it);
            sendPhoto(it);
        }, function () {
            hb.pop.feedback(feedback, 'error', hb.t('js.pop.bd.photoBad'));
        });
    }

    /** 보내기·다시 보내기 — 같은 바이트(it.blob)를 다시 보내므로 서버가 처음 파일을 돌려준다(duplicate) */
    function sendPhoto(it) {
        var p = bdPhoto;
        if (!navigator.onLine) { hb.pop.feedback(feedback, 'error', hb.t('js.pop.bd.photoOffline')); return; }
        it.state = 'sending';
        renderPhotos();
        var fd = new FormData();
        fd.append('reqNo', p.reqNo);
        fd.append('clientReqId', p.reqId);
        fd.append('file', it.blob, it.name);
        hb.pop.upload(API + '/breakdown/photos', fd).then(function (res) {
            it.state = res.data.duplicate ? 'dup' : 'done';
            p.count = res.data.count;
            renderPhotos();
            hb.pop.feedback(feedback, 'ok', hb.t('js.pop.bd.photoSent', p.count, p.max));   // 앞선 오류 안내를 덮는다
        }, function (err) {
            var code = err && err.errors && err.errors.length ? err.errors[0].code : err && err.code;
            if (err && (err.code === 'NETWORK' || err.code === 'TIMEOUT')) {
                it.state = 'failed';
                renderPhotos();
                hb.pop.feedback(feedback, 'error', hb.t('js.pop.bd.photoOffline'));
                return;
            }
            p.items.splice(p.items.indexOf(it), 1);
            if (code === 'PHOTO_LIMIT') {
                p.full = true;
                renderPhotos();
                hb.pop.feedback(feedback, 'error', hb.t('js.pop.bd.photoFull', p.max));
                return;
            }
            if (code === 'CMS_MWO_STATE' || code === 'FILE_LOCKED') {
                p.full = true;      // 보전이 이미 착수·취소 — 더 붙일 수 없다
                renderPhotos();
                hb.pop.feedback(feedback, 'error', hb.t('js.pop.bd.photoClosed'));
                return;
            }
            renderPhotos();
            workFail(err);
        });
    }

    document.getElementById('bdPhotoAdd').addEventListener('click', function () {
        var input = /** @type {HTMLInputElement} */ (document.getElementById('bdPhotoInput'));
        input.value = '';
        input.click();
    });
    document.getElementById('bdPhotoInput').addEventListener('change', function (e) {
        var files = /** @type {HTMLInputElement} */ (e.currentTarget).files;
        pickPhoto(files && files[0]);
    });
    document.getElementById('bdPhotoDone').addEventListener('click', function () { bdPhoto = null; closeBreakdown(); });

    bdText.addEventListener('input', function () { bd.reqId = null; });
    document.getElementById('breakdownBtn').addEventListener('click', function () { openBreakdown(null); });
    document.getElementById('procBreakdownBtn').addEventListener('click', function () {
        openBreakdown(current ? current.proc.equipId : null);
    });
    document.getElementById('breakdownClose').addEventListener('click', closeBreakdown);
    document.getElementById('bdSend').addEventListener('click', function (e) { sendBreakdown(e.currentTarget); });

    // ------------------------------------------------------------------ 감독자 승인(docs/25 4.37)

    /** 진행 중 승인 — {action, targetId, reason, procId, url, what, badge} (badge = 스캔한 감독자 배지, PIN 을 누르는 동안만) */
    var approving = null;
    var approvalPad = null;

    function approvalStep(badgeRead) {
        document.getElementById('approvalStep').hidden = badgeRead;
        document.getElementById('approvalPinBox').hidden = !badgeRead;
        if (approvalPad) { approvalPad.clear(); }
    }

    function startApproval(job) {
        approving = job;
        document.getElementById('approvalWhat').textContent = job.what || '';
        approvalStep(false);
        showPanel('approval');
        hb.pop.feedback(feedback, 'info', hb.t('js.pop.ap.need'));
    }

    function endApproval() {
        var procId = approving ? approving.procId : null;
        approving = null;
        approvalStep(false);
        return procId ? openProc(procId) : (showPanel('tasks'), refresh());
    }

    function approvalBadge(badge) {
        approving.badge = badge;
        document.getElementById('approverWho').textContent = hb.t('js.pop.ap.pin');
        approvalStep(true);
    }

    /** 배지·PIN → 승인 토큰 → 정정 다시. 틀린 PIN 은 PIN 화면에 머물고, 배지 쪽 오류(감독자 아님·본인·잠김)는 배지부터 다시 */
    function approvalPin(pin, button) {
        var job = approving;
        if (!job || !job.badge) { return; }
        hb.busy(button, function () {
            return hb.pop.api(API + '/approvals', {method: 'POST', body: {badge: job.badge, pin: pin, action: job.action, targetId: job.targetId}})
                .then(function (ap) {
                    return hb.pop.api(job.url, {method: 'POST', body: {reason: job.reason, approvalToken: ap.data.approvalToken}})
                        .then(function () { return ap.data; });
                });
        }).then(function (who) {
            if (!who) { return; }
            job.badge = null;
            return endApproval().then(function () {
                hb.pop.feedback(feedback, 'ok', hb.t('js.pop.ap.done', who.approverNm));
            });
        }, function (err) {
            if (approvalPad) { approvalPad.clear(); }
            if (login && login.gone(err)) { approving = null; return; }
            if (err && err.code === 'PIN_WRONG') { fail(err); return; }
            if (err && err.code === 'POP_APPROVAL_REQUIRED') {
                // 승인 뒤 정정이 다시 막힘(2분 넘김 등) — 처음부터
                job.badge = null;
                approvalStep(false);
                fail(err);
                return;
            }
            job.badge = null;
            approvalStep(false);
            workFail(err);
        });
    }

    document.getElementById('approvalCancel').addEventListener('click', function () { endApproval(); });

    // ------------------------------------------------------------------ 보내지 못한 실적(대기열 거부)

    /** 거부된 실적 — 확정한 시각·수량·투입·사유 + [다시 보내기]·[버리기](확인). 목록 그리기는 hb.pop 공용(docs/25 4.34) */
    function rejectedText(it) {
        // 보낸 화면이 요약 글자(label)를 남겼으면 그것(PDA 창고 등 본문 모양이 다른 건), 없으면 POP 실적 본문으로
        if (it.label) { return it.label; }
        var b = it.body || {};
        return [hb.fmt.datetime(b.occurredAt, zone), hb.t('js.pop.qtys', qtyText(b.goodQty), qtyText(b.scrapQty), qtyText(b.reworkQty),
            qtyText(b.repairQty)), (b.inputs || []).map(function (x) { return x.lotNo + ' ' + qtyText(x.qty); }).join(', ')]
            .filter(function (x) { return x; }).join(' · ');
    }
    var renderRejected = hb.pop.rejectedList(document.getElementById('rejectedList'), {
        describe: rejectedText,
        keys: {none: 'js.pop.rejectedNone', resend: 'js.pop.resend', discard: 'js.pop.discard', dlgDiscard: 'js.pop.dlg.discard',
            confirmDiscard: 'js.pop.confirmDiscard'},
        onRetried: function (res) {
            if (res && res.sent) {
                hb.pop.feedback(feedback, 'ok', hb.t('js.pop.flushed', res.sent));
            } else if (res && res.rejected) {
                hb.pop.feedback(feedback, 'error', hb.t('js.pop.flushRejected', res.rejected));
            } else {
                hb.pop.feedback(feedback, 'info', hb.t('js.pop.queued'));
            }
        }
    });

    document.getElementById('backBtn').addEventListener('click', function () { showPanel('tasks'); refresh(); });
    document.getElementById('startBtn').addEventListener('click', function (e) { event('start', {}, e.currentTarget); });
    document.getElementById('resumeBtn').addEventListener('click', function (e) { event('resume', {}, e.currentTarget); });
    document.getElementById('endBtn').addEventListener('click', function (e) {
        var btn = e.currentTarget;
        hb.dialog.confirm({title: hb.t('js.pop.dlg.end'), message: hb.t('js.pop.confirmEnd'), ok: hb.t('js.pop.dlg.endOk'), danger: true})
            .then(function (ok) { if (ok) { event('end', {}, btn); } });
    });
    document.getElementById('pauseBtn').addEventListener('click', function () {
        var box = document.getElementById('reasonBox');
        box.hidden = !box.hidden;
    });
    document.getElementById('confirmBtn').addEventListener('click', function (e) { confirmResult(e.currentTarget); });
    // 수집 값 쓰기 — 양품·폐기 칸에 넣기만(폐기는 불량 유형을 골라야 확정된다). 고치면 그냥 사람 입력(KEY)
    document.getElementById('countUseBtn').addEventListener('click', function () {
        var cs = current && current.countSuggest;
        if (!cs) { return; }
        qtyField('scrapQty').set(cs.scrapQty === '0' ? '' : cs.scrapQty);
        qtyField('goodQty').set(cs.goodQty);
        suggested = {goodQty: cs.goodQty, scrapQty: cs.scrapQty};
        setTarget(qtyField('goodQty'));
    });
    QTY.forEach(function (n) {
        document.querySelector('[data-qty="' + n + '"]').addEventListener('click', function () { setTarget(qtyField(n)); });
    });

    switchBtn.addEventListener('click', function () {
        hb.busy(switchBtn, function () { return hb.pop.api(API + '/worker/logout', {method: 'POST', body: {}}); })
            .then(function (res) { if (res) { return refresh(); } }, fail);
    });

    document.getElementById('pinCancel').addEventListener('click', function () { if (login) { login.cancel(); } });

    document.getElementById('refreshBtn').addEventListener('click', refresh);

    var langBtn = document.getElementById('langBtn');
    langBtn.addEventListener('click', function () {
        var next = document.documentElement.lang === 'en' ? 'ko' : 'en';
        hb.busy(langBtn, function () { return hb.pop.api(API + '/lang', {method: 'POST', body: {lang: next}}); })
            .then(function (res) { if (res) { window.location.reload(); } }, fail);
    });

    /**
     * 붙는 화면용 문(docs/25 4.58) — 설비 점검·검사 입력은 따로 둔 파일이 이 객체로 홈의 패널 전환·피드백·스캔·고장 신고를 쓴다.
     * 공용 부품(hb.pop)도 여기로 넘긴다 — 붙는 화면 파일은 타입 검사(@ts-check) 대상이고 hb.pop 은 계약(types/hb.d.ts) 밖이라서
     */
    /** @type {any} */ (window).popHome = {
        register: function (mod) { mods.push(mod); },
        show: function (name) { showPanel(name); },
        tasks: function () { showPanel('tasks'); return refresh(); },
        openProc: function (id) { return openProc(id); },
        proc: function () { return current && !procPanel.hidden ? current.proc : null; },
        api: function (url, opts) { return hb.pop.api(url, opts); },
        reqId: function () { return hb.pop.reqId(); },
        keypad: function (host, opts) { return hb.pop.keypad(host, opts); },
        say: function (kind, text) { hb.pop.feedback(feedback, kind, text); },
        fail: function (err) { workFail(err); },
        zone: function () { return zone; },
        /** 고장 신고로 잇기 — 설비를 미리 고르고 내용 글을 채운다(보낼지는 작업자가 정한다) */
        breakdown: function (equipId, text) {
            return openBreakdown(equipId).then(function () { bdText.value = text || ''; });
        }
    };

    hb.loadMessages().then(function () {
        hb.pop.network(document.getElementById('net'));
        hb.pop.keepAwake();   // 화면 꺼짐 방지(docs/50 K7) — APK 는 껍데기, 브라우저는 Wake Lock(지원 안 하면 아무 일 없음)
        pad = hb.pop.keypad(document.getElementById('keypad'), {max: 8, onChange: hb.pop.pinDots(document.getElementById('pinDots')),
            onConfirm: function (v, b) { login.onPin(v, b); }});
        login = hb.pop.badgeLogin({url: API + '/badge', pad: pad, pinWho: document.getElementById('pinWho'), feedback: feedback,
            showPanel: showPanel, onSignedIn: refresh, onGone: refresh, fail: fail});
        approvalPad = hb.pop.keypad(document.getElementById('approvalKeypad'), {max: 8,
            onChange: hb.pop.pinDots(document.getElementById('approvalDots')), onConfirm: approvalPin});
        qtyPad = hb.pop.keypad(document.getElementById('qtyPad'), {max: 18, decimal: true, onChange: function (v) {
            if (target) { target.set(v); }
        }});
        // 공정 화면에서 진행 중이면 스캔 = 투입 자재 LOT, 그 밖에는 배지
        kiosk = hb.kiosk ? hb.kiosk.attach() : null;
        scan = hb.pop.scanner(document.getElementById('scan'), function (v) {
            if (kiosk && kiosk.isOpen()) { kiosk.badge(v); return; }   // 키오스크 해제 대화상자: 스캔 = 감독자 배지
            if (!breakdownPanel.hidden) { return; }   // 신고 쓰는 중 배지가 읽혀 작업자가 바뀌지 않게
            var mod = openMod();
            if (mod) { if (mod.onScan) { mod.onScan(v); } return; }   // 붙는 화면이 열려 있으면 스캔은 그 화면 것(배지로 읽지 않는다)
            if (!approvalPanel.hidden) { if (approving) { approvalBadge(v); } return; }   // 승인 화면: 스캔 = 감독자 배지
            if (current && !procPanel.hidden) {
                if (current.proc.status === 'RUN' || current.proc.status === 'PAUSE') { scanInput(v); }
                return;
            }
            login.submit(v, null, null);
        });
        var pendingEl = document.getElementById('pending');
        var rejectedBtn = document.getElementById('rejectedBtn');
        hb.pop.queue.onChange(function (n, r) {
            pendingEl.hidden = n === 0;
            pendingEl.textContent = hb.t('js.pop.pending', n);
            rejectedBtn.hidden = !r;
            rejectedBtn.textContent = hb.t('js.pop.rejectedCount', r || 0);
            if (!rejectedPanel.hidden) { renderRejected(); }
        });
        var flush = function () {
            hb.pop.queue.flush().then(function (res) {
                if (res.sent > 0) {
                    hb.pop.feedback(feedback, 'ok', hb.t('js.pop.flushed', res.sent));
                    reloadProc();
                }
                if (res.rejected > 0) {
                    hb.pop.feedback(feedback, 'error', hb.t('js.pop.flushRejected', res.rejected));
                }
            });
        };
        rejectedBtn.addEventListener('click', function () { showPanel('rejected'); renderRejected(); });
        document.getElementById('rejectedClose').addEventListener('click', function () { showPanel('tasks'); refresh(); });
        window.addEventListener('online', flush);
        setInterval(flush, 30000);
        flush();
        setInterval(function () { if (!current) { refresh(); } }, REFRESH_MS);
        return refresh();
    });
})();
