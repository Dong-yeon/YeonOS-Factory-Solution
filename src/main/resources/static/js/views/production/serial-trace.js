// @ts-check
/* 부품 역추적 (prd-serial-trace) — docs/77 5.4. 부품 LOT 또는 품목 × 부품 시리얼 → 장비 시리얼 hb.grid(+ 엑셀).
   조건 없이 읽으면 서버가 400 이라 그리드는 첫 조회 때 만든다. 행을 고르면 [시리얼 구성 열기](prd-serial, 링크 조건 q.keyword). */
(function () {
    'use strict';

    var API = '/api/production/serial-trace';
    var PROG = document.body.getAttribute('data-prog') || '';
    var ACTIONS = (document.body.getAttribute('data-actions') || '').split(',');
    var msg = /** @type {HTMLElement} */ (document.getElementById('msg'));
    var form = /** @type {HTMLFormElement} */ (document.getElementById('search'));
    var traceInfo = /** @type {HTMLElement} */ (document.getElementById('traceInfo'));
    var openLink = /** @type {HTMLAnchorElement} */ (document.getElementById('openLink'));
    /** 그리드가 쪽·정렬을 넘길 때 쓰는 조건 — 조회 때만 바뀐다 @type {Record<string, string>} */
    var cond = {};
    /** @type {HB.Grid | null} */
    var grid = null;

    /** @param {string | null | undefined} text @param {boolean} [error] */
    function notify(text, error) {
        msg.textContent = text || '';
        msg.classList.toggle('hb-msg-error', !!error);
    }

    /** @param {HB.SearchValues} v */
    function search(v) {
        /** @type {Record<string, string>} */
        var c = {};
        ['lotNo', 'itemCd', 'partSerialNo'].forEach(function (k) { if (v[k]) { c[k] = String(v[k]); } });
        if (!c.lotNo && !(c.itemCd && c.partSerialNo)) {
            notify(hb.t('js.prdSerial.traceNeed'), true);
            return null;
        }
        cond = c;
        notify('');
        openLink.hidden = true;
        traceInfo.textContent = c.lotNo ? hb.t('js.prdSerial.traceLot', c.lotNo) : hb.t('js.prdSerial.traceSn', c.itemCd, c.partSerialNo);
        if (grid) { return grid.reload(true); }
        grid = hb.grid('#traceGrid', {
            progCd: PROG, id: 'main', key: 'partId',
            url: API + '/grid', columnsUrl: API + '/columns', exportUrl: API + '/export',
            actions: ACTIONS, params: function () { return cond; },
            status: {state: {ACTIVE: 'success', REMOVED: 'neutral'}},
            rowClass: function (row) { return row.removed ? 'hb-tree-dim' : null; },
            emptyText: {via: hb.t('js.prdSerial.direct')},
            onSelect: function (row) {
                openLink.hidden = false;
                openLink.textContent = hb.t('js.prdSerial.openSerialNo', row.serialNo);
                openLink.href = '/page/prd-serial?q.keyword=' + encodeURIComponent(String(row.serialNo)) + '&open=' + encodeURIComponent(String(row.serialId));
            },
            notify: notify
        });
        return grid.ready;
    }

    form.addEventListener('submit', function (e) { e.preventDefault(); });
    hb.loadMessages().then(function () {
        hb.page.init();
        return hb.loadSession();
    }).then(function () {
        var sp = hb.searchPanel(form, {onSearch: search});
        return sp.ready.then(function () {
            // 주소 링크(?q.lotNo=… 등)로 조건이 채워져 열렸으면 바로 조회
            var v = sp.values();
            return v.lotNo || (v.itemCd && v.partSerialNo) ? sp.search() : null;
        });
    }).then(null, function (err) { notify(err && err.message, true); });
})();
