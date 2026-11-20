// @ts-check
/*
 * 발주 진행 현황 (srm-po-status) — SRM 1부 S5 (docs/31 4.5). 조회 전용: 열린 발주 행의 회신·약속·지연.
 * 2부 R1(4.9): 입고 중 발주 포함, 열린 수량 = 수량 − 취소 − 입고, 받은 게 있으면 "입고 n" 을 붙인다.
 */
(function () {
    'use strict';

    /**
     * @typedef {{poId: number, poNo: string, rev: number, custNm: string, lineNo: number, itemCd: string, itemNm: string,
     *   unitCd: string, openQty: string, recvQty: string, dueDate: string, ackStatus: string|null, ackDueDate: string|null, promisedQty: string|null,
     *   promisedFirst: string|null, promisedLast: string|null, overdue: boolean, lateDays: number|null, dueInDays: number}} SrmStatusRow
     */

    var API = '/api/srm/po-status';
    var msg = /** @type {HTMLElement} */ (document.getElementById('msg'));
    var search = /** @type {HTMLFormElement} */ (document.getElementById('search'));
    var ACTIONS = (document.body.getAttribute('data-actions') || '').split(',');
    /** @type {HB.SearchPanel} */
    var sp;
    /** @type {HB.Grid} */
    var grid;
    /** SRM 화면 공용 표시(srm-ui.js) */
    var srmUi = /** @type {any} */ (window).srmUi;

    /** @param {string|null|undefined} text @param {boolean} [error] */
    function notify(text, error) {
        msg.textContent = text || '';
        msg.classList.toggle('hb-msg-error', !!error);
    }

    /** 임박 일수(검색 패널, 비면 7 — 서버 기본과 같다) */
    function soonDays() {
        var v = sp.values().soonDays;
        return v === '' || v === null || v === undefined ? 7 : Number(v);
    }

    /** 조회 조건(검색 패널) — grid 가 page·size·sort 를 붙인다 */
    function params() {
        var v = sp.values();
        /** @type {Record<string, unknown>} */
        var q = {};
        ['from', 'to', 'view', 'soonDays', 'keyword'].forEach(function (k) { if (v[k]) { q[k] = v[k]; } });
        return q;
    }

    function load() {
        notify('');
        return grid.reload(true);
    }

    search.addEventListener('submit', function (e) { e.preventDefault(); });
    hb.loadMessages().then(function () { return hb.loadSession(); }).then(function () {
        hb.page.init();
        sp = hb.searchPanel(search, {onSearch: load});
        // 주의 칸(전에는 "상황" 한 칸 배지)을 각 칸의 배지로: 납기 지남 = danger·임박 = warning(요구 납기), 늦어짐 = danger(약속 끝 날),
        // 회신 기한 지남(회신 없음·거절) = danger(회신 기한)
        grid = hb.grid('#statusGrid', {
            progCd: 'srm-po-status', id: 'main', key: 'poLineId',
            url: API + '/grid', columnsUrl: API + '/columns', exportUrl: API + '/export', actions: ACTIONS, params: params,
            status: {
                dueDate: function (v, row) { return row.dueInDays < 0 ? 'danger' : row.dueInDays <= soonDays() ? 'warning' : null; },
                promisedLast: function (v, row) { return row.lateDays !== null && row.lateDays > 0 ? 'danger' : null; },
                ackStatus: srmUi.TONES.ack,
                ackDueDate: function (v, row) { return row.overdue ? 'danger' : null; }
            },
            notify: notify
        });
        return Promise.all([sp.ready, grid.ready]);
    }).then(function () {
        return load();
    }).then(null, function (err) { notify(err && err.message, true); });
})();
