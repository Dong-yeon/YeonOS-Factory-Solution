// @ts-check
/*
 * 기록 무결성 점검 (sys-integrity) — docs/52 Y1. [점검 실행] → 서버가 다이제스트 체인·원장 보호·전자서명 해시·감사 트리거를
 * 다시 계산해 결과를 남긴다(추가만). 오른쪽 이력에서 지난 결과를 다시 보고, 고른 결과를 엑셀로 낸다.
 * 판정은 모두 서버 — 화면은 코드(상태·결과 코드)를 문구 키로 바꿔 글자로만 그린다.
 */
/**
 * @typedef {'OK' | 'WARN' | 'FAIL'} SysIntegrityStatus
 * @typedef {{subject: string, status: SysIntegrityStatus, code: string, args: string[] | null}} SysIntegrityItem
 * @typedef {{key: string, status: SysIntegrityStatus, checked: number, problems: number, items: SysIntegrityItem[],
 *   more: number}} SysIntegrityCheck
 * @typedef {{runId: number, runAt: string, runBy: string, status: SysIntegrityStatus, checks: SysIntegrityCheck[]}} SysIntegrityRun
 * @typedef {{runId: number, runAt: string, runBy: string, status: SysIntegrityStatus, problems: number}} SysIntegrityRunRow
 */
(function () {
    'use strict';

    var API = '/api/admin/integrity/runs';
    var state = /** @type {HTMLElement} */ (document.getElementById('state'));
    var empty = /** @type {HTMLElement} */ (document.getElementById('empty'));
    var checksHost = /** @type {HTMLElement} */ (document.getElementById('checks'));
    var title = /** @type {HTMLElement} */ (document.getElementById('resultTitle'));
    var historyBody = /** @type {HTMLTableSectionElement} */ (
        /** @type {HTMLTableElement} */ (document.getElementById('history')).tBodies[0]);
    var runBtn = /** @type {HTMLButtonElement | null} */ (document.getElementById('runBtn'));
    var excelBtn = /** @type {HTMLButtonElement | null} */ (document.getElementById('excelBtn'));
    /** @type {number | null} */
    var shownRunId = null;

    /** 상태 → 톤·문구 키(키를 이어 붙이지 않는다 — I18nKeyGuardTest) */
    /** @type {Record<string, HB.StatusTone>} */
    var TONES = {OK: 'success', WARN: 'warning', FAIL: 'danger'};
    /** @type {Record<string, string>} */
    var STATUS_KEYS = {OK: 'js.sysIntegrity.status.OK', WARN: 'js.sysIntegrity.status.WARN', FAIL: 'js.sysIntegrity.status.FAIL'};
    /** @type {Record<string, string>} */
    var CHECK_KEYS = {DIGEST: 'js.sysIntegrity.check.DIGEST', LEDGER: 'js.sysIntegrity.check.LEDGER',
        ESIGN: 'js.sysIntegrity.check.ESIGN', AUDIT: 'js.sysIntegrity.check.AUDIT'};
    /** @type {Record<string, string>} */
    var CHECK_WHY = {DIGEST: 'js.sysIntegrity.why.DIGEST', LEDGER: 'js.sysIntegrity.why.LEDGER',
        ESIGN: 'js.sysIntegrity.why.ESIGN', AUDIT: 'js.sysIntegrity.why.AUDIT'};
    /** @type {Record<string, string>} */
    var ITEM_KEYS = {
        DIGEST_BROKEN: 'js.sysIntegrity.item.DIGEST_BROKEN',
        LEDGER_OK: 'js.sysIntegrity.item.LEDGER_OK',
        LEDGER_NO_TRIGGER: 'js.sysIntegrity.item.LEDGER_NO_TRIGGER',
        LEDGER_APP_CAN_MODIFY: 'js.sysIntegrity.item.LEDGER_APP_CAN_MODIFY',
        LEDGER_STD_COLS: 'js.sysIntegrity.item.LEDGER_STD_COLS',
        ESIGN_TYPE: 'js.sysIntegrity.item.ESIGN_TYPE',
        ESIGN_NO_SOURCE: 'js.sysIntegrity.item.ESIGN_NO_SOURCE',
        ESIGN_LIMIT: 'js.sysIntegrity.item.ESIGN_LIMIT',
        ESIGN_CHANGED: 'js.sysIntegrity.item.ESIGN_CHANGED',
        ESIGN_TARGET_MISSING: 'js.sysIntegrity.item.ESIGN_TARGET_MISSING',
        AUDIT_MISSING: 'js.sysIntegrity.item.AUDIT_MISSING',
        AUDIT_EXEMPT: 'js.sysIntegrity.item.AUDIT_EXEMPT'
    };

    /** @param {string} text @param {boolean} [error] */
    function notify(text, error) {
        state.textContent = text || '';
        state.classList.toggle('hb-msg-error', !!error);
    }

    /** @param {string} s */
    function badge(s) {
        return hb.status(TONES[s] || 'neutral', hb.t(STATUS_KEYS[s] || s));
    }

    /** @param {SysIntegrityItem} it */
    function itemText(it) {
        var key = ITEM_KEYS[it.code];
        if (!key) { return it.code; }
        return hb.t.apply(hb, /** @type {[string, ...unknown[]]} */ ([key].concat(it.args || [])));
    }

    /** @param {SysIntegrityCheck} c */
    function renderCheck(c) {
        var box = hb.el('section', 'hb-section');
        var head = hb.el('h3');
        head.appendChild(badge(c.status));
        head.appendChild(document.createTextNode(' ' + hb.t(CHECK_KEYS[c.key] || c.key)));
        box.appendChild(head);
        box.appendChild(hb.el('p', 'hb-msg', hb.t(CHECK_WHY[c.key] || c.key)));
        box.appendChild(hb.el('p', null, hb.t('js.sysIntegrity.summary', c.checked, c.problems)));
        var table = hb.el('table', 'hb-cmp-table');
        var thead = table.createTHead().insertRow();
        ['js.sysIntegrity.col.subject', 'js.sysIntegrity.col.status', 'js.sysIntegrity.col.detail'].forEach(function (k) {
            thead.appendChild(hb.el('th', null, hb.t(k)));
        });
        var body = table.createTBody();
        c.items.forEach(function (it) {
            var tr = body.insertRow();
            tr.appendChild(hb.el('td', null, it.subject));
            var st = hb.el('td');
            st.appendChild(badge(it.status));
            tr.appendChild(st);
            tr.appendChild(hb.el('td', null, itemText(it)));
        });
        if (!c.items.length) { hb.emptyRow(body, hb.t('js.sysIntegrity.noProblem')); }
        box.appendChild(table);
        if (c.more > 0) { box.appendChild(hb.el('p', 'hb-msg', hb.t('js.sysIntegrity.more', c.more))); }
        return box;
    }

    /** @param {SysIntegrityRun} run */
    function show(run) {
        shownRunId = run.runId;
        empty.hidden = true;
        title.textContent = hb.t('js.sysIntegrity.resultOf', hb.fmt.datetime(run.runAt), run.runBy);
        title.appendChild(document.createTextNode(' '));
        title.appendChild(badge(run.status));
        checksHost.textContent = '';
        run.checks.forEach(function (c) { checksHost.appendChild(renderCheck(c)); });
        if (excelBtn) { excelBtn.hidden = false; }
    }

    function loadHistory() {
        return hb.api(API).then(function (r) {
            /** @type {SysIntegrityRunRow[]} */
            var rows = r.data || [];
            historyBody.textContent = '';
            rows.forEach(function (row) {
                var tr = historyBody.insertRow();
                tr.tabIndex = 0;
                tr.appendChild(hb.el('td', null, hb.fmt.datetime(row.runAt)));
                var st = hb.el('td');
                st.appendChild(badge(row.status));
                tr.appendChild(st);
                tr.appendChild(hb.el('td', null, hb.fmt.num(row.problems)));
                tr.appendChild(hb.el('td', null, row.runBy));
                var open = function () { openRun(row.runId); };
                tr.addEventListener('click', open);
                tr.addEventListener('keydown', function (e) { if (e.key === 'Enter') { open(); } });
            });
            if (!rows.length) { hb.emptyRow(historyBody); }
            return rows;
        });
    }

    /** @param {number} id */
    function openRun(id) {
        hb.latest('integrity-run', hb.api(API + '/' + id)).then(function (r) { show(r.data); }, function (err) {
            if (!err.stale) { notify(err.message, true); }
        });
    }

    if (runBtn) {
        var rb = runBtn;
        rb.addEventListener('click', function () {
            notify(hb.t('js.sysIntegrity.running'));
            hb.busy(rb, function () { return hb.api(API, {method: 'POST'}); }).then(function (r) {
                if (!r) { return; }
                show(r.data);
                notify(hb.t('js.sysIntegrity.done'));
                loadHistory();
            }, function (err) { notify(err.message, true); });
        });
    }

    if (excelBtn) {
        var xb = excelBtn;
        xb.addEventListener('click', function () {
            if (shownRunId === null) { return; }
            var id = shownRunId;
            hb.busy(xb, function () { return hb.download(API + '/' + id + '/excel'); }).then(null, function (err) {
                notify(err.message, true);
            });
        });
    }

    // 문구를 먼저 받고(상태 문구가 키로 보이지 않게) 이력 → 가장 최근 결과
    Promise.all([hb.loadMessages(), loadHistory()]).then(function (r) {
        var rows = r[1];
        if (rows.length) { openRun(rows[0].runId); }
    }, function (err) { notify(err.message, true); });
})();
