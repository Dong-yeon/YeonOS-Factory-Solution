// @ts-check
/*
 * 받은 의견함 (sys-feedback) — 시스템관리 3단계 (docs/36 12장). 조회 패널 + hb.grid + 오른쪽 의견.
 * 상태 버튼은 지금 상태에서 갈 수 있는 것만(NEW → 확인함·처리 완료, SEEN → 처리 완료·새 의견으로, DONE 은 끝). 판정은 서버·트리거.
 */
/**
 * @typedef {{feedbackId: number, createdAt: string, progCd: string, progNm: string, content: string, status: string,
 *   clientType: string, userId: number, loginId: string, userNm: string, handledBy: string | null,
 *   handledAt: string | null, rowVer: number}} SysFeedbackRow
 */
(function () {
    'use strict';

    var PROG = document.body.getAttribute('data-prog') || '';
    var ACTIONS = (document.body.getAttribute('data-actions') || '').split(',');
    var API = '/api/admin/feedback';
    var msg = /** @type {HTMLElement} */ (document.getElementById('msg'));
    var search = /** @type {HTMLFormElement} */ (document.getElementById('search'));
    /** @type {HB.Grid | undefined} */
    var grid;
    /** @type {HB.SearchPanel} */
    var sp;
    /** @type {SysFeedbackRow | null} */
    var current = null;
    /** @type {Record<string, string>} */
    var statusNames = {};

    /** @param {string | null | undefined} text @param {boolean} [error] */
    function notify(text, error) {
        msg.textContent = text || '';
        msg.classList.toggle('hb-msg-error', !!error);
    }

    /** @param {string} id @param {boolean} visible */
    function toggle(id, visible) {
        var b = document.getElementById(id);
        if (b) { b.hidden = !visible; }
    }

    /** @param {SysFeedbackRow | null} f */
    function show(f) {
        current = f;
        /** @type {HTMLElement} */ (document.getElementById('fbEmpty')).hidden = !!f;
        /** @type {HTMLElement} */ (document.getElementById('fbDoc')).hidden = !f;
        toggle('seenBtn', !!f && f.status === 'NEW');
        toggle('doneBtn', !!f && f.status !== 'DONE');
        toggle('newBtn', !!f && f.status === 'SEEN');
        if (!f) { return; }
        var meta = /** @type {HTMLElement} */ (document.getElementById('fbMeta'));
        meta.textContent = [hb.fmt.datetime(f.createdAt), f.progNm, f.userNm + ' (' + f.loginId + ')', f.clientType].join(' · ') + ' ';
        meta.appendChild(sysUi.badge('feedback', f.status, statusNames[f.status] || f.status));
        /** @type {HTMLElement} */ (document.getElementById('fbContent')).textContent = f.content;
        /** @type {HTMLElement} */ (document.getElementById('fbHandled')).textContent = f.handledAt
            ? hb.t('js.sysFeedback.handled', f.handledBy || '', hb.fmt.datetime(f.handledAt)) : '';
    }

    /** @param {number} id @param {boolean} [keepMsg] */
    function loadDetail(id, keepMsg) {
        if (!keepMsg) { notify(''); }
        return hb.latest('detail', hb.api(API + '/' + id)).then(function (res) { show(res.data); },
            function (err) { if (!err.stale) { notify(err.message, true); } });
    }

    /** @param {string} id @param {string} status @param {string} doneKey */
    function bindStatus(id, status, doneKey) {
        var b = /** @type {HTMLButtonElement | null} */ (document.getElementById(id));
        if (!b) { return; }
        var btn = b;
        btn.addEventListener('click', function () {
            var cur = current;   // 처리 중 다른 행을 골라도 이 의견으로 마무리
            if (!cur) { return; }
            var f = cur;
            hb.busy(btn, function () {
                return hb.api(API + '/' + f.feedbackId + '/status', {method: 'PUT', body: {status: status, rowVer: f.rowVer}});
            }).then(function (res) {
                if (!res) { return; }
                return (grid ? grid.reload(false, f.feedbackId) : Promise.resolve())
                    .then(function () { return loadDetail(f.feedbackId, true); })
                    .then(function () { notify(hb.t(doneKey)); });
            }, function (err) { notify(err.message, true); });
        });
    }
    bindStatus('seenBtn', 'SEEN', 'js.sysFeedback.seen');
    bindStatus('doneBtn', 'DONE', 'js.sysFeedback.done');
    bindStatus('newBtn', 'NEW', 'js.sysFeedback.reopened');

    search.addEventListener('submit', function (e) { e.preventDefault(); });
    hb.loadMessages().then(function () {
        hb.page.init();
        sp = hb.searchPanel(search, {
            onSearch: function () { return grid ? grid.reload(true) : null; }
        });
        return Promise.all([sp.ready, hb.codes('FEEDBACK_STATUS')]);
    }).then(function (r) {
        statusNames = r[1];
        show(null);
        grid = hb.grid('#fbGrid', {
            progCd: PROG, id: 'main', key: 'feedbackId',
            url: API, columnsUrl: API + '/columns', exportUrl: API + '/export', auditTable: 'sys_feedback',
            actions: ACTIONS, params: function () { return sp.values(); },
            filters: {status: 'status'},
            status: {status: sysUi.TONES.feedback},
            onFilter: function (param, value) { sp.setValue(param, value); },
            onSelect: function (row) { loadDetail(row.feedbackId); hb.page.openDetail(); },
            notify: notify
        });
        return grid.ready;
    }).then(null, function (err) { notify(err && err.message, true); });
})();
