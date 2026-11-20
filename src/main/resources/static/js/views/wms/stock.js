// @ts-check
/* 현재고 (wms-stock) — P4 1부 W3 (docs/30 4.3). 공장 → 위치(하위 포함) 조건 + hb.grid(서버 페이징·정렬·엑셀) + 오른쪽 최근 수불 20건.
   현재고는 원장의 투영이라 이 화면에서 고치지 않는다. 대사(FN1)는 결과만 보여 준다(불일치 = 알림, 자동 보정 없음).
   공통 hb.* 는 고치지 않는다(docs/29 2.4). */
/**
 * @typedef {{stockId: number, plantCd: string, nodeId: number, nodeCd: string, path: string, itemId: number, itemCd: string,
 *   itemNm: string, spec?: string | null, unitCd: string, stockStatus: string, qty: string | number, updatedAt: string,
 *   lotNo?: string | null}} WmsStockRow
 * @typedef {{trxId: number, trxType: string, qty: string | number, unitCd: string, occurredAt: string, recordedBy: string,
 *   reversalOfId?: number | null, reversedById?: number | null}} WmsStockHist
 * @typedef {{nodeId: number, nodeType: string, nodeCd: string, nodeNm: string, path: string}} WmsStockNode
 * @typedef {{runId: number, runAt: string, keyCount: number, mismatchCount: number, missingReceipts?: number | null,
 *   rcvMatOver?: number | null, rcvPoMismatch?: number | null, qcJudged?: number | null, qmsVoidLive?: number | null}} WmsStockRecon
 * @typedef {{path: string, stockStatus: string, fifoDate?: string | null, expiryDate?: string | null, availQty: string,
 *   takeQty: string, lotNo?: string | null}} WmsAllocPick
 * @typedef {{path: string, stockStatus: string, qty: string, reason: string, lotNo?: string | null}} WmsAllocSkip
 * @typedef {{itemCd: string, itemNm: string, unitCd: string, strategy: string, strategySource: string, qty: string,
 *   allocated: string, shortage: string, shelfLifeDays?: number | null, picks: WmsAllocPick[], skipped: WmsAllocSkip[]}} WmsAllocView
 */
(function () {
    'use strict';

    var API = '/api/wms/stock';
    var PROG = document.body.getAttribute('data-prog') || 'wms-stock';
    var ACTIONS = (document.body.getAttribute('data-actions') || '').split(',');
    var msg = /** @type {HTMLElement} */ (document.getElementById('msg'));
    var search = /** @type {HTMLFormElement} */ (document.getElementById('search'));
    var plantSel = /** @type {HTMLSelectElement} */ (document.getElementById('plantSel'));
    var nodeSel = /** @type {HTMLSelectElement} */ (document.getElementById('nodeSel'));
    var info = /** @type {HTMLElement} */ (document.getElementById('stockInfo'));
    var histSection = /** @type {HTMLElement} */ (document.getElementById('histSection'));
    var histBody = /** @type {HTMLTableSectionElement} */ (document.querySelector('#history tbody'));
    var reconInfo = /** @type {HTMLElement} */ (document.getElementById('reconcileInfo'));
    var reconBtn = /** @type {HTMLButtonElement | null} */ (document.getElementById('reconcileBtn'));
    /** @type {HB.SearchPanel} */
    var sp;
    /** @type {HB.Grid | undefined} */
    var grid;
    /** @type {Record<string, string>} */
    var typeNames = {};
    /** @type {Record<string, string>} */
    var statusNames = {};
    var allocSection = /** @type {HTMLElement} */ (document.getElementById('allocSection'));
    var allocForm = /** @type {HTMLFormElement} */ (document.getElementById('allocForm'));
    var allocInfo = /** @type {HTMLElement} */ (document.getElementById('allocInfo'));
    var allocBody = /** @type {HTMLTableSectionElement} */ (document.querySelector('#allocTable tbody'));
    /** @type {HB.NumberControl | undefined} */
    var allocQty;
    /** 고른 현재고 행 — 미리보기 품번 기본값 @type {WmsStockRow | null} */
    var picked = null;

    /** @param {string | null | undefined} text @param {boolean} [error] */
    function notify(text, error) {
        msg.textContent = text || '';
        msg.classList.toggle('hb-msg-error', !!error);
    }

    /** @param {HB.ApiError} err */
    function errText(err) {
        return err.errors && err.errors.length ? err.errors.map(function (x) { return x.message; }).join(' / ') : err.message;
    }

    /** @param {string | number | null | undefined} v */
    function q(v) { return v == null ? '' : hb.fmt.num(v, 6, 0); }

    /** @param {HTMLSelectElement} sel @param {string} value @param {string} text */
    function option(sel, value, text) {
        var o = /** @type {HTMLOptionElement} */ (hb.el('option', null, text));
        o.value = value;
        sel.appendChild(o);
    }

    /** 공장이 바뀌면 그 공장의 창고·로케이션 @param {string} plantCd */
    function loadNodes(plantCd) {
        nodeSel.textContent = '';
        option(nodeSel, '', hb.t('js.wmsStock.allNodes'));
        if (!plantCd) { return Promise.resolve(); }
        return hb.latest('wmsStockNodes', hb.api(API + '/nodes?plantCd=' + encodeURIComponent(plantCd))).then(function (res) {
            /** @type {WmsStockNode[]} */ (res.data).forEach(function (n) { option(nodeSel, String(n.nodeId), n.path + ' · ' + n.nodeNm); });
        }, function (err) { if (!err.stale) { notify(errText(err), true); } });
    }

    /** @param {WmsStockRecon | null} r */
    function showRecon(r) {
        if (!r) {
            reconInfo.textContent = hb.t('js.wmsStock.neverReconciled');
            reconInfo.classList.remove('hb-msg-error');
            return;
        }
        // 입고·발주(생산 입고 빠짐·자재 입고 초과·발주 불일치)·품질 연동(판정 빠짐·무효 남음) — 0 이 아니면 덧붙임(docs/44 S3)
        var receipt = (r.missingReceipts || 0) + (r.rcvMatOver || 0) + (r.rcvPoMismatch || 0);
        var quality = (r.qcJudged || 0) + (r.qmsVoidLive || 0);
        var text = hb.t('js.wmsStock.lastReconcile', hb.fmt.datetime(r.runAt), r.mismatchCount);
        if (receipt > 0 || quality > 0) { text += ' · ' + hb.t('js.wmsStock.reconExtra', receipt, quality); }
        reconInfo.textContent = text;
        reconInfo.classList.toggle('hb-msg-error', r.mismatchCount > 0 || receipt > 0 || quality > 0);
    }

    /** @param {WmsStockRow | null} row */
    function show(row) {
        picked = row;
        allocSection.hidden = true;
        histBody.textContent = '';
        if (!row) {
            info.textContent = hb.t('js.wmsStock.selectFirst');
            histSection.hidden = true;
            return;
        }
        var stockId = row.stockId;
        info.textContent = hb.t('js.wmsStock.title', row.itemCd, row.itemNm, row.path, statusNames[row.stockStatus] || row.stockStatus,
            q(row.qty), row.unitCd) + (row.lotNo ? ' · LOT ' + row.lotNo : '');
        histSection.hidden = false;
        hb.latest('wmsStockHist', hb.api(API + '/' + stockId + '/history')).then(function (res) {
            var list = /** @type {WmsStockHist[]} */ (res.data);
            histBody.textContent = '';
            if (!list.length) {
                var tr = hb.el('tr');
                var td = /** @type {HTMLTableCellElement} */ (hb.el('td', 'hb-muted', hb.t('js.wmsStock.noHistory')));
                td.colSpan = 4;
                tr.appendChild(td);
                histBody.appendChild(tr);
            }
            list.forEach(function (t) {
                var tr = hb.el('tr', t.reversalOfId || t.reversedById ? 'hb-tree-dim' : null);
                tr.appendChild(hb.el('td', null, hb.fmt.datetime(t.occurredAt)));
                tr.appendChild(hb.el('td', null, (typeNames[t.trxType] || t.trxType) + (t.reversalOfId ? ' ' + hb.t('js.wmsStock.reversalMark') : '')));
                tr.appendChild(hb.el('td', 'hb-num', q(t.qty)));
                tr.appendChild(hb.el('td', null, t.recordedBy));
                histBody.appendChild(tr);
            });
        }, function (err) { if (!err.stale) { notify(errText(err), true); } });
    }

    if (reconBtn) {
        var btn = reconBtn;
        btn.addEventListener('click', function () {
            hb.busy(btn, function () { return hb.api(API + '/reconcile', {method: 'POST', body: {}}); }).then(function (res) {
                if (!res) { return; }
                var r = /** @type {WmsStockRecon} */ (res.data);
                showRecon(r);
                notify(hb.t('js.wmsStock.reconciled', r.keyCount, r.mismatchCount), r.mismatchCount > 0 || reconInfo.classList.contains('hb-msg-error'));
            }, function (err) { notify(errText(err), true); });
        });
    }

    // ------------------------------------------------------------------ 출고 후보 미리보기 (W4) — 저장·예약하지 않는다

    /** @param {string} name */
    function af(name) { return /** @type {HTMLInputElement | HTMLSelectElement} */ (allocForm.querySelector('[data-field="' + name + '"]')); }

    /** 문구 키는 리터럴로(I18nKeyGuardTest) @param {string} k */
    function stName(k) {
        return k === 'FEFO' ? hb.t('js.wmsAlloc.st.FEFO') : k === 'LOC_SEQ' ? hb.t('js.wmsAlloc.st.LOC_SEQ') : hb.t('js.wmsAlloc.st.FIFO');
    }

    /** @param {string} k */
    function srcName(k) {
        return k === 'REQUEST' ? hb.t('js.wmsAlloc.src.REQUEST') : k === 'ITEM' ? hb.t('js.wmsAlloc.src.ITEM') : hb.t('js.wmsAlloc.src.POLICY');
    }

    /** @param {string} k */
    function skipName(k) {
        return k === 'EXPIRED' ? hb.t('js.wmsAlloc.skip.EXPIRED') : k === 'WHOLE_LOT' ? hb.t('js.wmsAlloc.skip.WHOLE_LOT')
            : k === 'EMPTY' ? hb.t('js.wmsAlloc.skip.EMPTY') : k === 'LOCATION' ? hb.t('js.wmsAlloc.skip.LOCATION')
            : k === 'HOLD' ? hb.t('js.wmsAlloc.skip.HOLD')
            : hb.t('js.wmsAlloc.skip.STATUS');
    }

    /** @param {WmsAllocView} v */
    function showAlloc(v) {
        allocInfo.textContent = hb.t('js.wmsAlloc.summary', v.itemCd, v.itemNm, stName(v.strategy),
            srcName(v.strategySource), q(v.allocated), q(v.qty), v.unitCd)
            + (v.shortage !== '0' ? ' ' + hb.t('js.wmsAlloc.shortage', q(v.shortage), v.unitCd) : '')
            + (v.skipped.length ? ' ' + hb.t('js.wmsAlloc.skipped', v.skipped.length) : '');
        allocInfo.classList.toggle('hb-msg-error', v.shortage !== '0');
        allocBody.textContent = '';
        v.picks.forEach(function (p) {
            var tr = hb.el('tr');
            tr.appendChild(hb.el('td', 'hb-wrap', p.path + (p.lotNo ? ' · LOT ' + p.lotNo : '')
                + (p.stockStatus === 'AVAILABLE' ? '' : ' · ' + (statusNames[p.stockStatus] || p.stockStatus))));
            tr.appendChild(hb.el('td', null, p.fifoDate || '-'));
            tr.appendChild(hb.el('td', null, p.expiryDate || '-'));
            tr.appendChild(hb.el('td', 'hb-num', q(p.availQty)));
            tr.appendChild(hb.el('td', 'hb-num', q(p.takeQty)));
            allocBody.appendChild(tr);
        });
        v.skipped.forEach(function (s) {
            var tr = hb.el('tr', 'hb-tree-dim');
            tr.appendChild(hb.el('td', 'hb-wrap', s.path + (s.lotNo ? ' · LOT ' + s.lotNo : '') + ' · '
                + (statusNames[s.stockStatus] || s.stockStatus)));
            var td = /** @type {HTMLTableCellElement} */ (hb.el('td', null, skipName(s.reason)));
            td.colSpan = 2;
            tr.appendChild(td);
            tr.appendChild(hb.el('td', 'hb-num', q(s.qty)));
            tr.appendChild(hb.el('td', 'hb-num', '0'));
            allocBody.appendChild(tr);
        });
    }

    var allocBtn = /** @type {HTMLButtonElement} */ (document.getElementById('allocBtn'));
    allocBtn.addEventListener('click', function () {
        allocSection.hidden = false;
        histSection.hidden = true;
        if (picked) {
            af('itemCd').value = picked.itemCd;
            info.textContent = hb.t('js.wmsAlloc.from', picked.itemCd, picked.itemNm);
        } else {
            info.textContent = '';
        }
        allocBody.textContent = '';
        allocInfo.textContent = '';
        hb.page.openDetail();
        af(picked ? 'qty' : 'itemCd').focus();
    });

    var allocRun = /** @type {HTMLButtonElement} */ (document.getElementById('allocRunBtn'));
    allocRun.addEventListener('click', function () {
        hb.showFieldErrors(allocForm, []);
        var qc = allocQty;
        if (!qc || !qc.validate().ok) { return; }
        var v = sp.values();
        var body = {plantCd: plantSel.value, nodeId: v.nodeId ? Number(v.nodeId) : null, itemCd: af('itemCd').value.trim(),
            qty: qc.value(), strategy: af('strategy').value || null, availableOnly: af('availableOnly').value === 'true'};
        hb.busy(allocRun, function () {
            return hb.latest('wmsAlloc', hb.api(API + '/alloc-preview', {method: 'POST', body: body}));
        }).then(function (res) {
            if (res) {
                notify('');                                // 앞 계산의 거부 문구가 남지 않게
                showAlloc(/** @type {WmsAllocView} */ (res.data));
            }
        }, function (err) {
            if (err.stale) { return; }
            notify(errText(err), true);
            hb.showFieldErrors(allocForm, err.errors);
        });
    });
    allocForm.addEventListener('submit', function (e) { e.preventDefault(); allocRun.click(); });

    // ------------------------------------------------------------------ 생산 입고 이관 (2부 B1, FN2) — 미리보기 확인 뒤 실행
    var backfillBtn = /** @type {HTMLButtonElement | null} */ (document.getElementById('backfillBtn'));
    if (backfillBtn) {
        var bb = backfillBtn;
        bb.addEventListener('click', function () {
            hb.busy(bb, function () {
                return hb.api(API + '/backfill').then(function (res) {
                    var p = /** @type {{total: number, posted: number, pending: number}} */ (res.data);
                    if (!p.pending) {
                        notify(hb.t('js.wmsStock.backfillNothing', p.total));
                        return null;
                    }
                    return hb.dialog.confirm({title: hb.t('js.wmsStock.dlg.backfill'), message: hb.t('js.wmsStock.backfillConfirm', p.total, p.posted, p.pending),
                        ok: hb.t('js.wmsStock.dlg.backfillOk')}).then(function (yes) {
                        return yes ? hb.api(API + '/backfill', {method: 'POST', body: {}, timeoutMs: 600000}) : null;
                    });
                });
            }).then(function (res) {
                if (!res) { return; }
                var r = /** @type {{posted: number, existed: number, failed: number}} */ (res.data);
                notify(hb.t('js.wmsStock.backfillDone', r.posted, r.existed, r.failed), r.failed > 0);
                return grid ? grid.reload(true) : null;
            }, function (err) { notify(errText(err), true); });
        });
    }

    plantSel.addEventListener('change', function () { loadNodes(plantSel.value); });

    hb.loadMessages().then(function () {
        hb.page.init();
        return Promise.all([hb.loadSession(), hb.api(API + '/plants'), hb.codes('INV_TRX_TYPE'), hb.codes('STOCK_STATUS'),
            hb.api(API + '/reconcile')]);
    }).then(function (r) {
        typeNames = r[2];
        statusNames = r[3];
        /** @type {{plantCd: string, plantNm: string}[]} */ (r[1].data).forEach(function (p) {
            option(plantSel, p.plantCd, p.plantCd + ' · ' + p.plantNm);
        });
        showRecon(/** @type {WmsStockRecon | null} */ (r[4].data));
        allocQty = hb.number(/** @type {HTMLInputElement} */ (af('qty')));
        return loadNodes(plantSel.value);
    }).then(function () {
        sp = hb.searchPanel(search, {onSearch: function () { return grid ? grid.reload(true) : null; }});
        var g = hb.grid('#stockGrid', {
            progCd: PROG, id: 'main', key: 'stockId',
            url: API, columnsUrl: API + '/columns', exportUrl: API + '/export',
            actions: ACTIONS, params: function () { return sp.values(); },
            filters: {stockStatus: 'stockStatus'},
            onFilter: function (param, value) { sp.setValue(param, value); },
            onSelect: function (row) { show(/** @type {WmsStockRow} */ (/** @type {unknown} */ (row))); hb.page.openDetail(); },
            notify: notify
        });
        grid = g;
        show(null);
        return sp.ready.then(function () { return g.ready; }).then(function () { return g.reload(true); });
    }).then(null, function (err) { notify(err && err.message, true); });
})();
