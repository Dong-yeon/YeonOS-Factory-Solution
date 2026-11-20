// @ts-check
/*
 * 변경 이력 (sys-audit-log) — 시스템관리 3단계 (docs/36 12장). 조회 패널 + hb.grid + 오른쪽 바뀐 칸 표.
 * 표 선택지는 서버가 준 "볼 수 있는 표"만(화면 권한 — Q17). 값은 textContent 로만.
 */
/**
 * @typedef {{column: string, before: string | null, after: string | null}} AuditChange
 * @typedef {{auditId: number, occurredAt: string, tableNm: string, tableProgNm: string, rowKey: string, action: string,
 *   actor: string, changes: AuditChange[]}} AuditDetailView
 * @typedef {{tableNm: string, progCd: string | null, progNm: string | null}} AuditTableChoice
 */
(function () {
    'use strict';

    var PROG = document.body.getAttribute('data-prog') || '';
    var ACTIONS = (document.body.getAttribute('data-actions') || '').split(',');
    var API = '/api/admin/audit-logs';
    var msg = /** @type {HTMLElement} */ (document.getElementById('msg'));
    var search = /** @type {HTMLFormElement} */ (document.getElementById('search'));
    var head = /** @type {HTMLElement} */ (document.getElementById('detailHead'));
    var diff = /** @type {HTMLTableElement} */ (document.getElementById('diff'));
    /** @type {HB.Grid | undefined} */
    var grid;
    /** @type {HB.SearchPanel} */
    var sp;
    /** @type {Record<string, string>} */
    var actionNames = {};

    /** @param {string | null | undefined} text @param {boolean} [error] */
    function notify(text, error) {
        msg.textContent = text || '';
        msg.classList.toggle('hb-msg-error', !!error);
    }

    /** @param {AuditDetailView | null} d */
    function show(d) {
        var body = /** @type {HTMLElement} */ (diff.tBodies[0]);
        body.textContent = '';
        diff.hidden = !d;
        if (!d) {
            head.textContent = hb.t('js.sysAudit.pick');
            return;
        }
        head.textContent = [hb.fmt.datetime(d.occurredAt), d.tableProgNm + ' (' + d.tableNm + ' #' + d.rowKey + ')',
            actionNames[d.action] || d.action, d.actor].join(' · ');
        if (!d.changes.length) {
            var tr0 = hb.el('tr');
            var td0 = hb.el('td', 'hb-audit-null', hb.t('js.sysAudit.noChange'));
            td0.colSpan = 3;
            tr0.appendChild(td0);
            body.appendChild(tr0);
        }
        d.changes.forEach(function (c) {
            var tr = hb.el('tr');
            tr.appendChild(hb.el('td', 'hb-audit-col', c.column));
            tr.appendChild(hb.el('td', c.before === null ? 'hb-audit-null' : null, c.before === null ? '—' : c.before));
            tr.appendChild(hb.el('td', c.after === null ? 'hb-audit-null' : null, c.after === null ? '—' : c.after));
            body.appendChild(tr);
        });
    }

    /** @param {number} id */
    function loadDetail(id) {
        notify('');
        hb.latest('detail', hb.api(API + '/' + id)).then(function (res) { show(res.data); },
            function (err) { if (!err.stale) { notify(err.message, true); } });
    }

    search.addEventListener('submit', function (e) { e.preventDefault(); });
    hb.loadMessages().then(function () {
        hb.page.init();
        return Promise.all([hb.api(API + '/tables'), hb.codes('AUDIT_ACTION')]);
    }).then(function (r) {
        actionNames = r[1];
        var sel = /** @type {HTMLSelectElement} */ (document.getElementById('tableSel'));
        (r[0].list || []).forEach(function (/** @type {AuditTableChoice} */ t) {
            var o = hb.el('option', null, (t.progNm || t.tableNm) + ' · ' + t.tableNm);
            o.value = t.tableNm;
            sel.appendChild(o);
        });
        sp = hb.searchPanel(search, {
            onSearch: function () { return grid ? grid.reload(true) : null; }
        });
        show(null);
        return sp.ready;
    }).then(function () {
        grid = hb.grid('#auditGrid', {
            progCd: PROG, id: 'main', key: 'auditId',
            url: API, columnsUrl: API + '/columns', exportUrl: API + '/export',
            actions: ACTIONS, params: function () { return sp.values(); },
            filters: {tableNm: 'tableNm', action: 'action'},
            onFilter: function (param, value) { sp.setValue(param, value); },
            onSelect: function (row) { loadDetail(row.auditId); hb.page.openDetail(); },
            notify: notify
        });
        return grid.ready;
    }).then(null, function (err) { notify(err && err.message, true); });
})();
