// @ts-check
/* 생산 입고 (prd-receipt) — MES P2 7a (docs/25 4.9). 지시 표 → 상세: 마지막 공정 양품 LOT(만든 양·입고·남음) + 입고 폼 + 이력·정정.
   서버가 LOT 별 남은 수량·창고 위치·지시 상태를 다시 확인한다(동시 입고는 트리거가 최종). 요청 id 는 누른 순간 만들어 재전송에도 한 번만. */
/**
 * @typedef {{woId: number, woNo: string, plantCd: string, itemCd: string, itemNm: string, planQty: string, unitCd: string,
 *   status: string, lastGoodQty?: string | null, receivedQty?: string | null}} RcvWo
 * @typedef {{lotId: number, lotNo: string, qtyCreated: string, receivedQty: string, remainQty: string, holdYn: string,
 *   occurredAt: string}} RcvLot
 * @typedef {{receiptId: number, lotId: number, lotNo: string, nodeId: number, nodeCd: string, qty: string, unitCd: string,
 *   entryMethod: string, reversalOfId?: number | null, reversed: boolean, reason?: string | null, occurredAt: string,
 *   recordedBy: string}} RcvRow
 * @typedef {{nodeId: number, nodeCd: string, nodeNm: string, nodeType: string, parentCd?: string | null, status?: string | null,
 *   reasonCd?: string | null}} RcvLoc
 * @typedef {{wo: RcvWo, lots: RcvLot[], history: RcvRow[]}} RcvView
 */
(function () {
    'use strict';

    var API = '/api/production/receipts';
    var PROG = document.body.getAttribute('data-prog') || '';
    var ACTIONS = (document.body.getAttribute('data-actions') || '').split(',');
    var CAN_SAVE = ACTIONS.indexOf('SAVE') >= 0;
    var msg = /** @type {HTMLElement} */ (document.getElementById('msg'));
    var search = /** @type {HTMLFormElement} */ (document.getElementById('search'));
    var plantSel = /** @type {HTMLSelectElement} */ (document.getElementById('plantSel'));
    var lotBody = /** @type {HTMLTableSectionElement} */ (document.querySelector('#lots tbody'));
    var histBody = /** @type {HTMLTableSectionElement} */ (document.querySelector('#history tbody'));
    var woInfo = /** @type {HTMLElement} */ (document.getElementById('woInfo'));
    var lotSection = /** @type {HTMLElement} */ (document.getElementById('lotSection'));
    var histSection = /** @type {HTMLElement} */ (document.getElementById('histSection'));
    var rcvForm = /** @type {HTMLFormElement | null} */ (document.getElementById('rcvForm'));
    var lotSel = /** @type {HTMLSelectElement | null} */ (document.getElementById('lotSel'));
    var locSel = /** @type {HTMLSelectElement | null} */ (document.getElementById('locSel'));
    /** @type {RcvView | null} */
    var current = null;
    /** @type {HB.SearchPanel} */
    var sp;
    /** 주 목록(입고 대상 지시) @type {HB.Grid | null} */
    var grid = null;
    /** 지시·LOT 별 입고 위치(WMS 판정 — 받을 수 없는 칸·받으면 들어갈 상태, docs/25 4.45) @type {Record<string, RcvLoc[]>} */
    var locs = {};
    /** 받을 수 없는 사유(WMS ReceiptLocQuery) @type {Record<string, string>} */
    var LOC_REASON = {STOCK_NOT_ALLOWED: 'js.prdRcv.locReason.STOCK_NOT_ALLOWED', NODE_INACTIVE: 'js.prdRcv.locReason.NODE_INACTIVE',
        STAGING_NOT_ALLOWED: 'js.prdRcv.locReason.STAGING_NOT_ALLOWED', NOT_WH: 'js.prdRcv.locReason.NOT_WH'};
    /** 재고 상태 코드명(STOCK_STATUS) @type {Record<string, string>} */
    var stockSt = {};
    /** @type {Record<string, string>} */
    var ST = {IN_PROGRESS: 'js.prdWo.st.IN_PROGRESS', DONE: 'js.prdWo.st.DONE', CLOSED: 'js.prdWo.st.CLOSED'};

    /** @param {string | null | undefined} text @param {boolean} [error] */
    function notify(text, error) {
        msg.textContent = text || '';
        msg.classList.toggle('hb-msg-error', !!error);
    }

    /** @param {HB.ApiError} err */
    function errText(err) {
        return err.errors && err.errors.length ? err.errors.map(function (x) { return x.message; }).join(' / ') : err.message;
    }

    /** @param {string | null | undefined} v */
    function q(v) { return v == null ? '' : hb.fmt.num(v, 6, 0); }

    /** @param {string} s */
    function stText(s) { return hb.t(ST[s] || 'js.prdWo.st.IN_PROGRESS'); }

    /** @param {HTMLSelectElement} sel @param {string} value @param {string} text */
    function option(sel, value, text) {
        var o = /** @type {HTMLOptionElement} */ (hb.el('option', null, text));
        o.value = value;
        sel.appendChild(o);
    }

    /** @param {HTMLElement} body @param {number} cols @param {string} text */
    function emptyRow(body, cols, text) {
        var tr = hb.el('tr');
        var td = /** @type {HTMLTableCellElement} */ (hb.el('td', 'hb-muted', text));
        td.colSpan = cols;
        tr.appendChild(td);
        body.appendChild(tr);
    }

    function reqId() {
        if (window.crypto && typeof window.crypto.randomUUID === 'function') { return window.crypto.randomUUID(); }
        var a = new Uint8Array(16);
        window.crypto.getRandomValues(a);
        return Array.prototype.map.call(a, function (/** @type {number} */ x) { return ('0' + x.toString(16)).slice(-2); }).join('');
    }

    // ------------------------------------------------------------------ 목록

    /** 조회 조건(검색 패널) — 빈 값은 보내지 않는다 */
    function params() {
        var v = sp.values();
        /** @type {Record<string, unknown>} */
        var q = {};
        ['plantCd', 'status', 'keyword'].forEach(function (k) { if (v[k]) { q[k] = v[k]; } });
        return q;
    }

    /** 목록 다시 읽기(페이지 그대로) + 그 지시 상세 — 지금 페이지에 없으면 상세만 다시 연다 @param {number} [selectId] */
    function load(selectId) {
        if (!grid) { return Promise.resolve(); }
        var g = grid;
        return g.reload(false, selectId).then(function () {
            var sel = g.selected();
            if (selectId && (!sel || sel.woId !== selectId)) { return open(selectId); }
        });
    }

    /** @param {number} woId */
    function open(woId) {
        return hb.latest('rcvView', hb.api(API + '/wos/' + woId)).then(function (res) {
            return show(/** @type {RcvView} */ (res.data));
        }, function (err) { if (!err.stale) { notify(errText(err), true); } });
    }

    // ------------------------------------------------------------------ 상세

    /** @param {RcvView} d */
    function show(d) {
        current = d;
        var w = d.wo;
        woInfo.textContent = hb.t('js.prdRcv.woInfo', w.woNo, w.itemCd, w.itemNm, q(w.lastGoodQty), q(w.receivedQty), w.unitCd, stText(w.status));
        lotSection.hidden = false;
        histSection.hidden = false;
        var open = w.status === 'IN_PROGRESS' || w.status === 'DONE';
        lotBody.textContent = '';
        if (!d.lots.length) { emptyRow(lotBody, 5, hb.t('js.prdRcv.noLots')); }
        d.lots.forEach(function (l) {
            var tr = hb.el('tr', l.holdYn === 'Y' ? 'hb-tree-dim' : null);
            [l.lotNo, q(l.qtyCreated), q(l.receivedQty), q(l.remainQty),
                l.holdYn === 'Y' ? hb.t('js.prdRcv.hold') : l.remainQty === '0' ? hb.t('js.prdRcv.full') : ''].forEach(function (x, i) {
                tr.appendChild(hb.el('td', i >= 1 && i <= 3 ? 'hb-num' : null, x));
            });
            lotBody.appendChild(tr);
        });
        if (rcvForm && lotSel && locSel) {
            hb.showFieldErrors(rcvForm, []);
            rcvForm.hidden = !CAN_SAVE || !open;
            var ls = lotSel;
            var keep = ls.value;
            ls.textContent = '';
            d.lots.filter(function (l) { return l.holdYn !== 'Y' && l.remainQty !== '0'; }).forEach(function (l) {
                option(ls, String(l.lotId), l.lotNo + ' · ' + hb.t('js.prdRcv.remainOf', q(l.remainQty), w.unitCd));
            });
            ls.value = keep;
            if (ls.selectedIndex < 0 && ls.options.length) { ls.selectedIndex = 0; }
            fillQty();
            fillLocs();
        }
        histBody.textContent = '';
        if (!d.history.length) { emptyRow(histBody, 6, hb.t('js.prdRcv.noHistory')); }
        d.history.forEach(function (r) {
            var tr = hb.el('tr', r.reversed || r.reversalOfId ? 'hb-tree-dim' : null);
            tr.appendChild(hb.el('td', null, hb.fmt.datetime(r.occurredAt)));
            tr.appendChild(hb.el('td', null, r.lotNo));
            tr.appendChild(hb.el('td', null, r.nodeCd));
            tr.appendChild(hb.el('td', 'hb-num', q(r.qty) + ' ' + r.unitCd));
            tr.appendChild(hb.el('td', 'hb-wrap', r.recordedBy + (r.reversalOfId ? ' · ' + hb.t('js.prdRcv.correction', r.reason || '') : '')));
            var act = hb.el('td');
            if (CAN_SAVE && open && !r.reversed && !r.reversalOfId) {
                // 정정 = 역분개(되돌릴 수 없음) — 외곽선 위험 톤, 행 끝 칸(주동작 입고와 떨어진 자리)
                var b = /** @type {HTMLButtonElement} */ (hb.el('button', 'hb-btn-danger-outline hb-btn-small', hb.t('js.prdRcv.correct')));
                b.type = 'button';
                b.addEventListener('click', function () { correct(r, b); });
                act.appendChild(b);
            } else if (r.reversed) {
                act.textContent = hb.t('js.prdRcv.reversed');
            }
            tr.appendChild(act);
            histBody.appendChild(tr);
        });
        hb.dirty.clear();
    }

    /** 고른 LOT 의 남은 수량을 기본값으로 */
    function fillQty() {
        if (!rcvForm || !lotSel || !current) { return; }
        var id = Number(lotSel.value);
        var lot = current.lots.find(function (l) { return l.lotId === id; });
        /** @type {HTMLInputElement} */ (rcvForm.querySelector('[data-field="qty"]')).value = lot ? q(lot.remainQty) : '';
    }

    /**
     * 입고 위치 — 지시·고른 LOT 기준 WMS 판정(받으면 들어갈 상태가 LOT 의 판정을 이어받을 수 있어 LOT 마다 다시 묻는다).
     * 받을 수 없는 칸은 고를 수 없게(사유 표시), 가용이 아닌 상태로 들어갈 칸은 상태를 붙인다. 서버가 저장 때 같은 판정으로 다시 막는다
     */
    function fillLocs() {
        if (!locSel || !current) { return; }
        var sel = locSel;
        var keep = sel.value;
        var woId = current.wo.woId;
        var lotId = lotSel && lotSel.value ? lotSel.value : '';
        var key = woId + ':' + lotId;
        /** @param {RcvLoc[]} list */
        var draw = function (list) {
            sel.textContent = '';
            if (!list.some(function (n) { return !n.reasonCd; })) { option(sel, '', hb.t('js.prdRcv.noLoc')); }
            list.forEach(function (n) {
                var text = (n.nodeType === 'LOC' && n.parentCd ? n.parentCd + ' › ' : '') + n.nodeCd + ' · ' + n.nodeNm;
                if (n.reasonCd) {
                    text += ' — ' + hb.t(LOC_REASON[n.reasonCd] || 'js.prdRcv.locReason.NOT_WH');
                } else if (n.status && n.status !== 'AVAILABLE') {
                    text += ' — ' + hb.t('js.prdRcv.locStatus', stockSt[n.status] || n.status);
                }
                option(sel, String(n.nodeId), text);
                /** @type {HTMLOptionElement} */ (sel.lastElementChild).disabled = !!n.reasonCd;
            });
            sel.value = keep;
            if (sel.selectedIndex < 0 || sel.options[sel.selectedIndex].disabled) {
                var first = Array.prototype.findIndex.call(sel.options, function (/** @type {HTMLOptionElement} */ o) { return !o.disabled; });
                sel.selectedIndex = first;
            }
        };
        if (locs[key]) { draw(locs[key]); return; }
        hb.latest('rcvLocs', hb.api(API + '/locations?woId=' + woId + (lotId ? '&lotId=' + lotId : ''))).then(function (res) {
            locs[key] = /** @type {RcvLoc[]} */ (res.data);
            draw(locs[key]);
        }, function (err) { if (!err.stale) { notify(errText(err), true); } });
    }

    /** @param {RcvRow} r @param {HTMLButtonElement} btn */
    function correct(r, btn) {
        var woId = current ? current.wo.woId : 0;
        hb.dialog.prompt({title: hb.t('js.mes.dlg.rcvCorrect'), message: hb.t('js.prdRcv.correctPrompt', r.lotNo, q(r.qty), r.unitCd),
            label: hb.t('js.mes.dlg.reason'), ok: hb.t('js.mes.dlg.rcvCorrectOk'), required: true, maxLength: 500, danger: true})
            .then(function (reason) {
                if (reason === null) { return; }
                hb.busy(btn, function () {
                    return hb.api(API + '/' + r.receiptId + '/correct', {method: 'POST', body: {reason: reason}});
                }).then(function (res) {
                    if (!res) { return; }
                    return load(woId).then(function () { notify(hb.t('js.prdRcv.corrected')); });
                }, function (err) { notify(errText(err), true); });
            });
    }

    if (rcvForm && lotSel && locSel) {
        var form = rcvForm;
        var lots = lotSel;
        var locsSel = locSel;
        var rcvBtn = /** @type {HTMLButtonElement} */ (document.getElementById('rcvBtn'));
        lots.addEventListener('change', function () { fillQty(); fillLocs(); });
        rcvBtn.addEventListener('click', function () {
            if (!current) { return; }
            var woId = current.wo.woId;
            hb.showFieldErrors(form, []);
            var body = {clientReqId: reqId(), woId: woId, lotId: lots.value ? Number(lots.value) : null,
                nodeId: locsSel.value ? Number(locsSel.value) : null,
                qty: /** @type {HTMLInputElement} */ (form.querySelector('[data-field="qty"]')).value.trim()};
            hb.busy(rcvBtn, function () {
                return hb.api(API, {method: 'POST', body: body});
            }).then(function (res) {
                if (!res) { return; }
                return load(woId).then(function () { notify(hb.t('js.prdRcv.received')); });
            }, function (err) {
                notify(errText(err), true);
                hb.showFieldErrors(form, err.errors);
            });
        });
        hb.dirty.watch(form);
    }

    hb.loadMessages().then(function () {
        hb.page.init();
        return Promise.all([hb.loadSession(), hb.api(API + '/plants'), hb.codes('STOCK_STATUS')]);
    }).then(function (r) {
        stockSt = r[2];
        /** @type {{plantCd: string, plantNm: string}[]} */ (r[1].data).forEach(function (p) {
            option(plantSel, p.plantCd, p.plantCd + ' · ' + p.plantNm);
        });
        sp = hb.searchPanel(search, {onSearch: function () {
            if (!grid) { return null; }
            var g = grid;
            return mesUi.leaveOk().then(function (ok) { return ok ? g.reload(true) : null; });
        }});
        return sp.ready;
    }).then(function () {
        // 주 목록 = hb.grid(UI 단계 3 ①) — 서버 페이징·정렬, 상태 칸 배지, 입고 입력 중이면 옮기기 전에 묻는다
        grid = hb.grid('#woGrid', {
            progCd: PROG, id: 'main', key: 'woId',
            url: API + '/wos/grid', columnsUrl: API + '/wos/columns', exportUrl: API + '/wos/export',
            actions: ACTIONS, params: params,
            beforeSelect: function () { return mesUi.leaveOk(); },
            status: {status: mesUi.TONES.wo},
            rowClass: function (row) { return row.status === 'CLOSED' ? 'hb-tree-dim' : null; },
            onSelect: function (row) { open(row.woId); hb.page.openDetail(); },
            notify: notify
        });
        return grid.ready;
    }).then(null, function (err) { notify(err && err.message, true); });
})();
