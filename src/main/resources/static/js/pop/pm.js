// @ts-check
/* POP 설비 점검 (/pop 의 패널, docs/25 4.58) — 예방점검(CHECK 방식) 실행: 열린 점검 목록 → 항목 입력 → [점검 완료] 또는 [건너뛰기](사유 코드 필수).
   홈(pop/home.js)이 내 준 문 window.popHome 으로 패널 전환·피드백·스캔·키패드·고장 신고를 쓴다. 온라인만(오프라인 대기열 없음).
   판정은 서버(CMS) — 화면의 규격 밖 표시는 미리 보기일 뿐이다. 응답을 못 받았을 수 있으니 같은 점검의 완료·건너뛰기는 같은 요청 id 로
   다시 보낸다(서버가 처음 결과를 돌려준다). 값·사유를 바꾸면 새 id. */
(function () {
    'use strict';

    /**
     * @typedef {{seq: number, itemNm: string, methodText: string | null, judgeType: 'OKNG' | 'NUM' | 'TEXT', lsl: number | null,
     *   usl: number | null, unitNm: string | null, scale: number, required: boolean, valueNum: number | null, valueText: string | null,
     *   judge: string | null, remark: string | null}} PopPmItem
     * @typedef {{runId: number, planCd: string, planNm: string, tmplNm: string, equipId: number, equipCd: string, equipNm: string,
     *   dueDate: string, state: string, itemCount: number, rowVer: number}} PopPmRun
     * @typedef {{status: string, result: string | null, ngItems: string[], duplicate: boolean}} PopPmDone
     * @typedef {{get: () => string, set: (v: string) => void, el: HTMLElement}} PopPmTarget
     * @typedef {{register: (mod: any) => void, show: (name: string) => void, tasks: () => Promise<any>,
     *   api: (url: string, opts?: any) => Promise<any>, reqId: () => string, keypad: (host: HTMLElement, opts: any) => any,
     *   say: (kind: string, text: string) => void, fail: (err: any) => void,
     *   breakdown: (equipId: number, text: string) => Promise<any>}} PopHomeDoor
     */

    var API = '/api/pop/pm';
    /** @type {PopHomeDoor} */
    var home = /** @type {any} */ (window).popHome;
    if (!home) { return; }

    /** @param {string} id */
    function $(id) { return /** @type {HTMLElement} */ (document.getElementById(id)); }

    var panel = $('pmPanel');
    var btn = $('pmBtn');
    /** @type {PopPmRun[]} */
    var runs = [];
    /** 열린 점검 — {run, items, values(순번 → 입력), reqId(보낸 적 있는 같은 내용의 요청 id)} */
    /** @type {{run: PopPmRun, items: PopPmItem[], values: Record<number, {value: string, judge: string, valueText: string}>, reqId: string | null, skipCd: string | null} | null} */
    var open = null;
    /** @type {PopPmTarget | null} */
    var target = null;
    /** @type {any} */
    var pad = null;
    /** 끝난 점검의 설비·불량 항목 — 고장 신고로 이어 갈 때 */
    /** @type {{equipId: number, text: string} | null} */
    var after = null;

    /** @type {Record<string, string>} */
    var STATE_KEY = {UPCOMING: 'js.pop.pm.st.UPCOMING', DUE: 'js.pop.pm.st.DUE', OVERDUE: 'js.pop.pm.st.OVERDUE'};
    /** @type {Record<string, 'neutral' | 'warning' | 'danger'>} */
    var STATE_TONE = {UPCOMING: 'neutral', DUE: 'warning', OVERDUE: 'danger'};

    /** @param {string} which list | run | result */
    function part(which) {
        $('pmRuns').hidden = which !== 'list';
        $('pmInfo').hidden = which !== 'list';
        $('pmRun').hidden = which !== 'run';
        $('pmResult').hidden = which !== 'result';
        if (which !== 'run') { open = null; target = null; }
    }

    function load() {
        return hb.latest('pmRuns', home.api(API + '/runs')).then(function (res) {
            runs = res.data;
            renderRuns();
            part('list');
        }, home.fail);
    }

    function renderRuns() {
        var host = $('pmRuns');
        host.textContent = '';
        $('pmInfo').textContent = runs.length ? hb.t('js.pop.pm.count', runs.length) : hb.t('js.pop.pm.none');
        runs.forEach(function (r) {
            var li = hb.el('li', 'hb-pop-task');
            li.tabIndex = 0;
            li.setAttribute('role', 'button');
            li.addEventListener('click', function () { openRun(r.runId); });
            li.addEventListener('keydown', function (e) { if (e.key === 'Enter') { openRun(r.runId); } });
            li.appendChild(hb.el('h3', null, r.equipCd + ' · ' + r.equipNm));
            li.appendChild(hb.status(STATE_TONE[r.state] || 'neutral', STATE_KEY[r.state] ? hb.t(STATE_KEY[r.state]) : r.state));
            li.appendChild(hb.el('p', null, r.planNm));
            li.appendChild(hb.el('p', 'hb-pop-sub', hb.t('js.pop.pm.dueLine', r.dueDate, r.itemCount)));
            host.appendChild(li);
        });
    }

    /** @param {number} runId */
    function openRun(runId) {
        return hb.latest('pmRun', home.api(API + '/runs/' + runId)).then(function (res) {
            var v = res.data.view;
            /** @type {Record<number, {value: string, judge: string, valueText: string}>} */
            var values = {};
            v.items.forEach(function (/** @type {PopPmItem} */ it) {
                // 사무 화면에서 미리 넣은 값이 있으면 이어서 쓴다
                values[it.seq] = {value: it.valueNum === null || it.valueNum === undefined ? '' : String(it.valueNum),
                    judge: it.judgeType === 'OKNG' ? (it.judge || '') : '', valueText: it.valueText || ''};
            });
            open = {run: v.run, items: v.items, values: values, reqId: null, skipCd: null};
            $('pmRunTitle').textContent = v.run.equipCd + ' · ' + v.run.equipNm;
            $('pmRunInfo').textContent = [v.run.planNm, v.run.tmplNm, hb.t('js.pop.pm.due', v.run.dueDate)].join(' · ');
            renderSkip(res.data.skipReasons);
            renderItems();
            part('run');
        }, home.fail);
    }

    /** 값을 바꾸면 같은 내용이 아니다 — 새 요청 id */
    function touched() { if (open) { open.reqId = null; } }

    /** @param {PopPmTarget | null} t */
    function setTarget(t) {
        target = t;
        document.querySelectorAll('#pmItems .hb-pop-field').forEach(function (b) { b.setAttribute('aria-pressed', String(!!t && b === t.el)); });
        $('pmPad').hidden = !t;
        if (t && pad) { pad.set(t.get()); }
    }

    /** 규격 밖 미리 보기(판정은 서버) @param {PopPmItem} it @param {string} raw */
    function outOfRange(it, raw) {
        if (raw === '' || isNaN(Number(raw))) { return false; }
        var n = Number(raw);
        return (it.lsl !== null && n < it.lsl) || (it.usl !== null && n > it.usl);
    }

    function renderItems() {
        var host = $('pmItems');
        host.textContent = '';
        if (!open) { return; }
        var cur = open;
        if (!pad) {
            pad = home.keypad($('pmPad'), {max: 14, decimal: true, onChange: function (/** @type {string} */ v) { if (target) { target.set(v); } }});
        }
        cur.items.forEach(function (it) {
            var val = cur.values[it.seq];
            var li = hb.el('li');
            li.setAttribute('data-seq', String(it.seq));
            var label = hb.el('span', null, it.seq + '. ' + it.itemNm + (it.required ? ' *' : ''));
            if (it.methodText) { label.appendChild(hb.el('small', 'hb-pop-sub', ' ' + it.methodText)); }
            li.appendChild(label);
            if (it.judgeType === 'OKNG') {
                var box = hb.el('div', 'hb-pop-okng');
                [['OK', 'js.pop.pm.ok'], ['NG', 'js.pop.pm.ng']].forEach(function (pair) {
                    var b = hb.el('button', null, hb.t(pair[0] === 'OK' ? 'js.pop.pm.ok' : 'js.pop.pm.ng'));
                    b.type = 'button';
                    b.setAttribute('data-judge', pair[0]);
                    b.setAttribute('aria-pressed', String(val.judge === pair[0]));
                    b.addEventListener('click', function () {
                        val.judge = val.judge === pair[0] ? '' : pair[0];   // 다시 누르면 고르기 취소
                        touched();
                        box.querySelectorAll('button').forEach(function (x) {
                            x.setAttribute('aria-pressed', String(x.getAttribute('data-judge') === val.judge));
                        });
                    });
                    box.appendChild(b);
                });
                li.appendChild(box);
            } else if (it.judgeType === 'NUM') {
                var range = hb.t('js.pop.pm.range', it.lsl === null ? '' : it.lsl, it.usl === null ? '' : it.usl, it.unitNm || '');
                li.appendChild(hb.el('span', 'hb-pop-sub', range));
                var f = hb.el('button', 'hb-pop-field');
                f.type = 'button';
                f.appendChild(hb.el('span', null, hb.t('js.pop.value')));
                var shown = hb.el('b', null, val.value);
                f.appendChild(shown);
                var warn = hb.el('span', 'hb-pop-judge', '');
                var paint = function () {
                    var out = outOfRange(it, val.value);
                    warn.textContent = out ? hb.t('js.pop.pm.out') : '';
                    warn.setAttribute('data-judge', out ? 'NG' : 'OK');
                };
                /** @type {PopPmTarget} */
                var t = {el: f, get: function () { return val.value; }, set: function (v) { val.value = v; shown.textContent = v; touched(); paint(); }};
                f.addEventListener('click', function () { setTarget(t); });
                li.appendChild(f);
                li.appendChild(warn);
                paint();
            } else {
                var ta = /** @type {HTMLTextAreaElement} */ (hb.el('textarea'));
                ta.rows = 2;
                ta.maxLength = 500;
                ta.value = val.valueText;
                ta.setAttribute('aria-label', it.itemNm);
                ta.addEventListener('input', function () { val.valueText = ta.value; touched(); });
                var wrap = hb.el('label', 'hb-pop-text');
                wrap.appendChild(ta);
                li.appendChild(wrap);
            }
            li.appendChild(hb.el('p', 'hb-pop-reason', ''));
            host.appendChild(li);
        });
        setTarget(null);
    }

    /** 서버 칸 오류(행 키 = 항목 순번)를 그 항목 아래에 @param {any} err */
    function showErrors(err) {
        document.querySelectorAll('#pmItems .hb-pop-reason').forEach(function (p) { p.textContent = ''; });
        /** @type {Element[]} */
        var hit = [];
        (err && err.errors || []).forEach(function (/** @type {any} */ x) {
            var li = document.querySelector('#pmItems li[data-seq="' + x.rowKey + '"]');
            if (!li) { return; }
            /** @type {HTMLElement} */ (li.querySelector('.hb-pop-reason')).textContent = x.message;
            hit.push(li);
        });
        if (hit.length) { hit[0].scrollIntoView({block: 'center'}); }
    }

    /** @param {PopPmDone} d @param {PopPmRun} run */
    function finished(d, run) {
        var ng = d.status === 'DONE' && d.result === 'NG';
        $('pmResultTitle').textContent = run.equipCd + ' · ' + run.equipNm;
        $('pmResultInfo').textContent = d.status === 'SKIPPED' ? hb.t('js.pop.pm.skipped')
            : ng ? hb.t('js.pop.pm.doneNg', d.ngItems.join(', ')) : hb.t('js.pop.pm.doneOk');
        after = ng ? {equipId: run.equipId, text: hb.t('js.pop.pm.bdText', d.ngItems.join(', ')).substring(0, 200)} : null;
        $('pmToBreakdown').hidden = !ng;
        part('result');
        home.say(ng ? 'error' : 'ok', $('pmResultInfo').textContent || '');
    }

    /** @param {string} path @param {any} body @param {HTMLElement} button */
    function send(path, body, button) {
        if (!open) { return; }
        var cur = open;
        if (!navigator.onLine) { home.say('error', hb.t('js.pop.pm.offline')); return; }
        cur.reqId = cur.reqId || home.reqId();
        body.rowVer = cur.run.rowVer;
        body.clientReqId = cur.reqId;
        hb.busy(/** @type {HTMLButtonElement} */ (button), function () {
            return home.api(API + '/runs/' + cur.run.runId + path, {method: 'POST', body: body});
        }).then(function (res) {
            if (!res) { return; }
            finished(res.data, cur.run);
        }, function (err) {
            if (err && (err.code === 'NETWORK' || err.code === 'TIMEOUT')) { home.say('error', hb.t('js.pop.pm.offline')); return; }
            if (err && err.status === 409) {
                // 그새 사무 화면에서 값이 바뀌었거나 이미 끝난 점검 — 목록부터 다시
                home.say('error', err.message);
                load();
                return;
            }
            showErrors(err);
            home.fail(err);
        });
    }

    function complete(/** @type {HTMLElement} */ button) {
        if (!open) { return; }
        var cur = open;
        var results = cur.items.map(function (it) {
            var v = cur.values[it.seq];
            return {seq: it.seq, value: it.judgeType === 'NUM' ? v.value : null, judge: it.judgeType === 'OKNG' ? (v.judge || null) : null,
                valueText: it.judgeType === 'TEXT' ? v.valueText : null, remark: it.remark};
        });
        send('/complete', {results: results}, button);
    }

    /** @param {{code: string, name: string}[]} reasons */
    function renderSkip(reasons) {
        var host = $('pmSkipReasons');
        host.textContent = '';
        $('pmSkipBox').hidden = true;
        /** @type {HTMLTextAreaElement} */ ($('pmSkipMemo')).value = '';
        reasons.forEach(function (r) {
            var b = hb.el('button', null, r.name);
            b.type = 'button';
            b.setAttribute('aria-pressed', 'false');
            b.addEventListener('click', function () {
                if (!open) { return; }
                open.skipCd = r.code;
                touched();
                host.querySelectorAll('button').forEach(function (x) { x.setAttribute('aria-pressed', String(x === b)); });
            });
            host.appendChild(b);
        });
    }

    function skip(/** @type {HTMLElement} */ button) {
        if (!open) { return; }
        if (!open.skipCd) { home.say('error', hb.t('js.pop.pm.needReason')); return; }
        var memo = /** @type {HTMLTextAreaElement} */ ($('pmSkipMemo')).value.trim();
        send('/skip', {reasonCd: open.skipCd, memo: memo || null}, button);
    }

    home.register({
        name: 'pm',
        el: panel,
        /** 세션 pm — CMS 구현 없음·PDA 면 거짓 @param {{pm?: boolean}} s */
        onSession: function (s) { btn.hidden = !s.pm; },
        /** 설비 바코드(설비 코드)를 읽으면 그 설비의 첫 점검을 연다 @param {string} v */
        onScan: function (v) {
            var code = v.replace(/^EQUIP:/i, '').split(':').pop() || '';
            var hit = runs.filter(function (r) { return r.equipCd === code; })[0];
            if (!hit) { home.say('info', hb.t('js.pop.pm.scanNone', code)); return; }
            openRun(hit.runId);
        }
    });

    btn.addEventListener('click', function () { home.show('pm'); load(); });
    $('pmClose').addEventListener('click', function () { part('list'); home.tasks(); });
    $('pmRefresh').addEventListener('click', load);
    $('pmBack').addEventListener('click', load);
    $('pmDone').addEventListener('click', function (e) { complete(/** @type {HTMLElement} */ (e.currentTarget)); });
    $('pmSkip').addEventListener('click', function () { $('pmSkipBox').hidden = !$('pmSkipBox').hidden; });
    $('pmSkipMemo').addEventListener('input', touched);
    $('pmSkipSend').addEventListener('click', function (e) { skip(/** @type {HTMLElement} */ (e.currentTarget)); });
    $('pmResultClose').addEventListener('click', load);
    $('pmToBreakdown').addEventListener('click', function () {
        if (!after) { return; }
        var a = after;
        after = null;
        part('list');
        home.breakdown(a.equipId, a.text);
    });
})();
