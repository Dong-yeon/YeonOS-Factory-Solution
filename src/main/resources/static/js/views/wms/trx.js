// @ts-check
/* 수불 원장 (wms-trx) — P4 1부 W3 (docs/30 4.3). 기간 필수 조회(hb.dateRange + 서버 92일 상한) + hb.grid + 오른쪽 상세·역분개.
   원장은 고치지 않는다 — 되돌리기 = 반대 수량의 새 행(이동은 짝 두 행 함께). 판정(이미 되돌림·역분개의 역분개·마이너스)은 서버.
   공통 hb.* 는 고치지 않는다(docs/29 2.4). */
/**
 * @typedef {{trxId: number, plantCd: string, trxType: string, itemCd: string, itemNm: string, unitCd: string, path: string,
 *   stockStatus: string, qty: string | number, moveGrpId?: string | null, srcType: string, srcId?: number | null,
 *   reasonCd?: string | null, remark?: string | null, occurredAt: string, recordedAt: string, entryMethod: string,
 *   recordedBy: string, performedByNm?: string | null, reversalOfId?: number | null, reversedById?: number | null,
 *   lotNo?: string | null}} WmsTrxRow
 */
(function () {
    'use strict';

    var API = '/api/wms/trx';
    var PROG = document.body.getAttribute('data-prog') || 'wms-trx';
    var ACTIONS = (document.body.getAttribute('data-actions') || '').split(',');
    var msg = /** @type {HTMLElement} */ (document.getElementById('msg'));
    var search = /** @type {HTMLFormElement} */ (document.getElementById('search'));
    var plantSel = /** @type {HTMLSelectElement} */ (document.getElementById('plantSel'));
    var mode = /** @type {HTMLElement} */ (document.getElementById('trxMode'));
    var info = /** @type {HTMLElement} */ (document.getElementById('trxInfo'));
    var box = /** @type {HTMLElement | null} */ (document.getElementById('reverseBox'));
    var form = /** @type {HTMLFormElement | null} */ (document.getElementById('reverseForm'));
    /** @type {WmsTrxRow | null} */
    var current = null;
    /** @type {HB.SearchPanel} */
    var sp;
    /** @type {HB.Grid | undefined} */
    var grid;
    /** @type {Record<string, string>} */
    var typeNames = {};
    /** @type {Record<string, string>} */
    var statusNames = {};
    /** @type {Record<string, string>} */
    var reasonNames = {};
    /** @type {Record<string, string>} */
    var methodNames = {};

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

    /** @param {string} label @param {unknown} value */
    function line(label, value) {
        info.appendChild(hb.el('dt', null, label));
        info.appendChild(hb.el('dd', null, value === null || value === undefined || value === '' ? '-' : String(value)));
    }

    /** @param {WmsTrxRow | null} t */
    function show(t) {
        current = t;
        info.textContent = '';
        if (box) { box.hidden = true; }
        if (!t) {
            mode.textContent = hb.t('js.wmsTrx.selectFirst');
            return;
        }
        mode.textContent = hb.t('js.wmsTrx.title', t.trxId, typeNames[t.trxType] || t.trxType);
        line(hb.t('js.wmsTrx.item'), t.itemCd + ' · ' + t.itemNm);
        line(hb.t('js.wmsTrx.place'), t.path + ' · ' + (statusNames[t.stockStatus] || t.stockStatus));
        if (t.lotNo) { line(hb.t('js.wmsTrx.lot'), t.lotNo); }
        line(hb.t('js.wmsTrx.qty'), q(t.qty) + ' ' + t.unitCd);
        line(hb.t('js.wmsTrx.occurredAt'), hb.fmt.datetime(t.occurredAt));
        line(hb.t('js.wmsTrx.recordedAt'), hb.fmt.datetime(t.recordedAt) + ' · ' + t.recordedBy);
        line(hb.t('js.wmsTrx.method'), methodNames[t.entryMethod] || t.entryMethod);
        if (t.performedByNm) { line(hb.t('js.wmsTrx.performedBy'), t.performedByNm); }
        line(hb.t('js.wmsTrx.source'), t.srcType + (t.srcId ? ' #' + t.srcId : ''));
        if (t.reasonCd) { line(hb.t('js.wmsTrx.reasonCd'), reasonNames[t.reasonCd] || t.reasonCd); }
        if (t.remark) { line(hb.t('js.wmsTrx.remark'), t.remark); }
        if (t.moveGrpId) { line(hb.t('js.wmsTrx.moveGrp'), hb.t('js.wmsTrx.movePair')); }
        if (t.reversalOfId) {
            line(hb.t('js.wmsTrx.state'), hb.t('js.wmsTrx.isReversal', t.reversalOfId));
        } else if (t.reversedById) {
            line(hb.t('js.wmsTrx.state'), hb.t('js.wmsTrx.alreadyReversed', t.reversedById));
        } else if (t.srcType === 'QMS_INSP' || t.srcType === 'QMS_NCR') {
            line(hb.t('js.wmsTrx.state'), hb.t('js.wmsTrx.qmsSource'));   // 서버도 400 QMS_SOURCE 로 막는다(docs/30 12.15.7)
        } else if (t.srcType === 'PRD_RECEIPT' || t.srcType === 'LOT_GEN') {
            line(hb.t('js.wmsTrx.state'), hb.t('js.wmsTrx.mesSource'));   // 서버도 400 MES_SOURCE
        } else if (t.srcType === 'CMS_PART' && t.trxType === 'ISSUE_MRO') {
            line(hb.t('js.wmsTrx.state'), hb.t('js.wmsTrx.cmsIssueSource'));   // 서버도 400 CMS_ISSUE_SOURCE(W-10)
        } else if (t.srcType === 'SHIP_PICK') {
            line(hb.t('js.wmsTrx.state'), hb.t('js.wmsTrx.shipSource'));  // 서버도 400 SHIP_SOURCE(QA CR-e)
        } else if (t.srcType === 'CUST_RTN') {
            line(hb.t('js.wmsTrx.state'), hb.t('js.wmsTrx.custRtnSource'));   // 서버도 400 CUST_RTN_SOURCE(docs/59 R-6)
        } else if (box && form) {
            box.hidden = false;
            /** @type {HTMLInputElement} */ (form.querySelector('[data-field="reason"]')).value = '';
            hb.showFieldErrors(form, []);
            hb.dirty.clear(form);
        }
    }

    /** @param {number} id */
    function open(id) {
        return hb.latest('wmsTrxDetail', hb.api(API + '/' + id)).then(function (x) {
            show(/** @type {WmsTrxRow} */ (x.data));
        }, function (err) { if (!err.stale) { notify(errText(err), true); } });
    }

    if (form) {
        var f = form;
        var btn = /** @type {HTMLButtonElement} */ (document.getElementById('reverseBtn'));
        btn.addEventListener('click', function () {
            if (!current) { return; }
            var id = current.trxId;
            var reason = /** @type {HTMLInputElement} */ (f.querySelector('[data-field="reason"]')).value;
            hb.dialog.confirm({title: hb.t('js.wmsTrx.dlg.reverse', id), message: hb.t('js.wmsTrx.confirmReverse', id),
                ok: hb.t('js.wmsTrx.dlg.reverseOk'), danger: true}).then(function (yes) {
                if (!yes) { return; }
                hb.showFieldErrors(f, []);
                hb.busy(btn, function () {
                    return hb.api(API + '/' + id + '/reverse', {method: 'POST', body: {reason: reason}});
                }).then(function (res) {
                    if (!res) { return; }
                    hb.dirty.clear(f);
                    var ids = /** @type {number[]} */ (res.data);
                    var g = grid;
                    return (g ? g.reload(false, id) : Promise.resolve()).then(function () { return open(id); }).then(function () {
                        notify(hb.t('js.wmsTrx.reversed', ids.join(', ')));
                    });
                }, function (err) {
                    notify(errText(err), true);
                    hb.showFieldErrors(f, err.errors);
                });
            });
        });
        hb.dirty.watch(f);
    }

    hb.loadMessages().then(function () {
        hb.page.init();
        return Promise.all([hb.loadSession(), hb.api(API + '/plants'), hb.codes('INV_TRX_TYPE'), hb.codes('STOCK_STATUS'),
            hb.codes('WMS_ADJ_REASON'), hb.codes('ENTRY_METHOD')]);
    }).then(function (r) {
        typeNames = r[2];
        statusNames = r[3];
        reasonNames = r[4];
        methodNames = r[5];
        /** @type {{plantCd: string, plantNm: string}[]} */ (r[1].data).forEach(function (p) {
            var o = /** @type {HTMLOptionElement} */ (hb.el('option', null, p.plantCd + ' · ' + p.plantNm));
            o.value = p.plantCd;
            plantSel.appendChild(o);
        });
        sp = hb.searchPanel(search, {onSearch: function () { return grid ? grid.reload(true) : null; }});
        var g = hb.grid('#trxGrid', {
            progCd: PROG, id: 'main', key: 'trxId',
            url: API, columnsUrl: API + '/columns', exportUrl: API + '/export',
            actions: ACTIONS, params: function () { return sp.values(); },
            filters: {trxType: 'trxType'},
            onFilter: function (param, value) { sp.setValue(param, value); },
            onSelect: function (row) { open(Number(row.trxId)); hb.page.openDetail(); },
            notify: notify
        });
        grid = g;
        show(null);
        return sp.ready.then(function () { return g.ready; }).then(function () { return g.reload(true); });
    }).then(null, function (err) { notify(err && err.message, true); });
})();
