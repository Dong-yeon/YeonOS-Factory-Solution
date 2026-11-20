// @ts-check
/* 정위치 점검 (wms-loc-check) — docs/55. 목록(hb.grid) → 오른쪽 상세 줄(판정·판정 때 장부 칸) → 다른 위치 줄 [보정 이동](장부 칸마다, 수량 확인)
   · 정상 아닌 줄 [확인만](사유). 보정·확인은 한 번만(서버 409), 끝나면 상세와 목록을 다시 읽는다. 공통 hb.* 는 고치지 않는다. */
/**
 * @typedef {{checkId: number, plantCd: string, nodeId: number, path: string, checkDate: string, status: string, okCnt?: number | null,
 *   misplacedCnt?: number | null, missingCnt?: number | null, unknownCnt?: number | null, accurateYn?: string | null,
 *   doneAt?: string | null, doneBy?: string | null, createdBy: string, lineCnt: number, openCnt: number}} WmsLcHead
 * @typedef {{nodeId: number, path?: string | null, status: string, qty: string, usage?: string | null}} WmsLcBook
 * @typedef {{lineId: number, scanCode?: string | null, itemCd?: string | null, itemNm?: string | null, unitCd?: string | null,
 *   lotNo?: string | null, result: string, reasonCd?: string | null, bookJson: WmsLcBook[], reviewAction?: string | null,
 *   reviewReason?: string | null, reviewedBy?: string | null, reviewedAt?: string | null}} WmsLcLine
 * @typedef {{head: WmsLcHead, lines: WmsLcLine[]}} WmsLcView
 */
(function () {
    'use strict';

    var API = '/api/wms/loc-checks';
    var PROG = document.body.getAttribute('data-prog') || '';
    var ACTIONS = (document.body.getAttribute('data-actions') || '').split(',');
    var CAN_SAVE = ACTIONS.indexOf('SAVE') >= 0;
    var msg = /** @type {HTMLElement} */ (document.getElementById('msg'));
    var search = /** @type {HTMLFormElement} */ (document.getElementById('search'));
    var plantSel = /** @type {HTMLSelectElement} */ (document.getElementById('plantSel'));
    var info = /** @type {HTMLElement} */ (document.getElementById('checkInfo'));
    var lineHost = /** @type {HTMLElement} */ (document.getElementById('lineHost'));
    var lineBody = /** @type {HTMLTableSectionElement} */ (document.querySelector('#lines tbody'));
    /** @type {any} */
    var sp = null;
    /** @type {any} */
    var grid = null;
    /** @type {WmsLcView | null} */
    var current = null;
    /** @type {Record<string, string>} */
    var resultNames = {};
    /** @type {Record<string, string>} */
    var statusNames = {};
    /** @type {Record<string, string>} */
    var reviewNames = {};
    /** @type {Record<string, HB.StatusTone>} */
    var TONE = {OK: 'success', MISPLACED: 'warning', MISSING: 'danger', UNKNOWN: 'danger'};
    /** @type {Record<string, string>} */
    var REASON = {NO_STOCK: 'js.wmsLocCheck.reason.NO_STOCK', LOT_REQUIRED: 'js.wmsLocCheck.reason.LOT_REQUIRED',
        NOT_FOUND: 'js.wmsLocCheck.reason.NOT_FOUND', NOT_ITEM: 'js.wmsLocCheck.reason.NOT_ITEM'};

    /** @param {string} text @param {boolean} [error] */
    function notify(text, error) {
        msg.textContent = text || '';
        msg.classList.toggle('hb-msg-error', !!error);
    }

    /** @param {any} err */
    function errText(err) {
        var detail = err && err.errors && err.errors.length ? err.errors.map(function (/** @type {any} */ x) { return x.message; }).join(' / ') : '';
        var more = err && err.details && err.details.length ? err.details.join(' ') : '';
        return ((err && err.message) || hb.t('js.error.server')) + (detail ? ' — ' + detail : '') + (more ? ' ' + more : '');
    }

    /** @param {string | number | null | undefined} v */
    function q(v) { return v == null ? '' : hb.fmt.num(v, 6, 0); }

    /** @param {WmsLcLine} l */
    function what(l) {
        if (!l.itemCd) { return l.scanCode || '-'; }
        return l.itemCd + ' ' + (l.itemNm || '') + (l.lotNo ? ' · ' + l.lotNo : '');
    }

    /** @param {WmsLcView | null} v */
    function show(v) {
        current = v;
        lineBody.textContent = '';
        if (!v) {
            info.textContent = hb.t('js.wmsLocCheck.selectFirst');
            lineHost.hidden = true;
            return;
        }
        var h = v.head;
        info.textContent = hb.t('js.wmsLocCheck.title', h.path, h.checkDate, statusNames[h.status] || h.status)
            + (h.status === 'DONE' ? ' — ' + hb.t('js.wmsLocCheck.summary', h.okCnt || 0, h.misplacedCnt || 0, h.missingCnt || 0, h.unknownCnt || 0)
                + (h.accurateYn === 'Y' ? ' · ' + hb.t('js.wmsLocCheck.accurate') : '') : ' — ' + hb.t('js.wmsLocCheck.notDone'));
        lineHost.hidden = false;
        if (!v.lines.length) { hb.emptyRow(lineBody, hb.t('js.wmsLocCheck.noLines')); }
        v.lines.forEach(function (l) {
            var tr = hb.el('tr', l.reviewAction ? 'hb-tree-dim' : null);
            var r = hb.el('td');
            r.appendChild(hb.status(TONE[l.result] || 'neutral', resultNames[l.result] || l.result));
            if (l.reasonCd) { r.appendChild(hb.el('div', 'hb-muted', REASON[l.reasonCd] ? hb.t(REASON[l.reasonCd]) : l.reasonCd)); }
            tr.appendChild(r);
            tr.appendChild(hb.el('td', 'hb-wrap', what(l)));
            var b = hb.el('td', 'hb-wrap');
            if (!l.bookJson.length) { b.textContent = '-'; }
            l.bookJson.forEach(function (x) {
                b.appendChild(hb.el('div', null, (x.path || String(x.nodeId)) + ' · ' + q(x.qty) + ' ' + (l.unitCd || '') + (x.status !== 'AVAILABLE' ? ' · ' + x.status : '')));
            });
            tr.appendChild(b);
            tr.appendChild(actionCell(l));
            lineBody.appendChild(tr);
        });
    }

    /** @param {WmsLcLine} l */
    function actionCell(l) {
        var td = hb.el('td');
        if (l.reviewAction) {
            td.appendChild(hb.el('div', null, (reviewNames[l.reviewAction] || l.reviewAction) + ' · ' + (l.reviewedBy || '')));
            if (l.reviewReason) { td.appendChild(hb.el('div', 'hb-muted', l.reviewReason)); }
            return td;
        }
        if (!CAN_SAVE || l.result === 'OK') { return td; }
        if (l.result === 'MISPLACED') {
            // 출하 대기 칸 장부는 보정하지 않는다(서버 400 STAGING_LOCKED — 피킹 취소는 출하 화면) — 단추를 보이지 않는다
            l.bookJson.forEach(function (x) {
                if (x.usage === 'STAGING') {
                    td.appendChild(hb.el('div', 'hb-muted', hb.t('js.wmsLocCheck.stagingNote', x.path || String(x.nodeId))));
                    return;
                }
                var btn = /** @type {HTMLButtonElement} */ (hb.el('button', 'hb-btn-secondary', hb.t('js.wmsLocCheck.correctFrom', x.path || String(x.nodeId))));
                btn.type = 'button';
                btn.addEventListener('click', function () { correct(l, x, btn); });
                td.appendChild(btn);
            });
        }
        var c = /** @type {HTMLButtonElement} */ (hb.el('button', 'hb-btn-ghost', hb.t('js.wmsLocCheck.confirm')));
        c.type = 'button';
        c.addEventListener('click', function () { confirmOnly(l, c); });
        td.appendChild(c);
        return td;
    }

    /** 보정 이동 — 장부 칸 → 점검한 칸, 수량 확인(기본 = 판정 때 장부 수량) @param {WmsLcLine} l @param {WmsLcBook} x @param {HTMLButtonElement} b */
    function correct(l, x, b) {
        if (!current) { return; }
        var to = current.head.path;
        hb.dialog.prompt({title: hb.t('js.wmsLocCheck.dlg.correct', what(l)), message: hb.t('js.wmsLocCheck.dlg.correctMsg', x.path || '', to),
            label: hb.t('js.wmsLocCheck.qty'), value: x.qty, required: true, number: {scale: 6}, ok: hb.t('js.wmsLocCheck.dlg.correctOk')})
            .then(function (qty) {
                if (qty == null) { return; }
                hb.busy(b, function () {
                    return hb.api(API + '/lines/' + l.lineId + '/correct', {method: 'POST',
                        body: {fromNodeId: x.nodeId, fromStatus: x.status, qty: qty}});
                }).then(function (res) {
                    if (!res) { return; }
                    notify(hb.t('js.wmsLocCheck.corrected', q(qty), x.path || '', to));
                    after(/** @type {WmsLcView} */ (res.data));
                }, function (err) { notify(errText(err), true); });
            });
    }

    /** 확인만 — 사유 필수, 원장 없음 @param {WmsLcLine} l @param {HTMLButtonElement} b */
    function confirmOnly(l, b) {
        hb.dialog.prompt({title: hb.t('js.wmsLocCheck.dlg.confirm', what(l)), message: hb.t('js.wmsLocCheck.dlg.confirmMsg'),
            label: hb.t('js.wmsLocCheck.reasonLabel'), required: true, maxLength: 500, ok: hb.t('js.wmsLocCheck.dlg.confirmOk')})
            .then(function (reason) {
                if (reason == null) { return; }
                hb.busy(b, function () {
                    return hb.api(API + '/lines/' + l.lineId + '/confirm', {method: 'POST', body: {reason: reason}});
                }).then(function (res) {
                    if (!res) { return; }
                    notify(hb.t('js.wmsLocCheck.confirmed'));
                    after(/** @type {WmsLcView} */ (res.data));
                }, function (err) { notify(errText(err), true); });
            });
    }

    /** 처리 뒤 — 상세는 응답으로, 목록(미처리 수)은 다시 @param {WmsLcView} v */
    function after(v) {
        show(v);
        if (grid) { grid.reload(false); }
    }

    /** @param {number} id */
    function open(id) {
        hb.latest('detail', hb.api(API + '/' + id)).then(function (/** @type {any} */ res) {
            show(/** @type {WmsLcView} */ (res.data));
        }, function (/** @type {any} */ err) {
            if (err && err.stale) { return; }
            notify(errText(err), true);
        });
    }

    search.addEventListener('submit', function (e) { e.preventDefault(); });
    hb.loadMessages().then(function () {
        hb.page.init();
        return Promise.all([hb.loadSession(), hb.api(API + '/plants'), hb.codes('WMS_LOC_CHECK_RESULT'), hb.codes('WMS_LOC_CHECK_STATUS'),
            hb.codes('WMS_LOC_CHECK_REVIEW')]);
    }).then(function (r) {
        /** @type {{plantCd: string, plantNm: string}[]} */ (r[1].data).forEach(function (p) {
            var o = hb.el('option', null, p.plantCd + ' · ' + p.plantNm);
            o.setAttribute('value', p.plantCd);
            plantSel.appendChild(o);
        });
        resultNames = r[2];
        statusNames = r[3];
        reviewNames = r[4];
        sp = hb.searchPanel(search, {onSearch: function () { return grid ? grid.reload(true) : null; }});
        grid = hb.grid('#checkGrid', {
            progCd: PROG, id: 'main', key: 'checkId',
            url: API + '/grid', columnsUrl: API + '/columns', exportUrl: API + '/export',
            actions: ACTIONS, params: function () { return sp.values(); },
            onSelect: function (row) {
                open(Number(row.checkId));
                hb.page.openDetail();
            },
            notify: notify
        });
        show(null);
        return sp.ready;
    }).then(function () {
        return grid ? grid.ready.then(function () { return grid.reload(true); }) : null;
    }).then(null, function (err) { notify(errText(err), true); });
})();
