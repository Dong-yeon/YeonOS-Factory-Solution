// @ts-check
/* 협력사 반품 출고 (wms-rtn-issue) — SRM 2부 R4-4 (docs/30 12.14). 반품 번호(스캔 RTN:{번호}) → 지시·후보 재고 칸 → 칸별 출고 수량 → 확정 1회.
   요청 id 는 확정을 누른 순간 만들고 성공하면 버린다(응답이 끊겨 다시 눌러도 두 번 안 나감). 합 = 지시 수량·재고·상태 판정은 서버(SRM 게이트). */
/**
 * @typedef {{returnId: number, returnNo: string, status: string, plantCd: string, lotId: number, lotNo: string, itemId: number,
 *   unitCd: string, qty: string | number, baseUnitCd: string, baseQty: string | number | null, replace: boolean, poLineId: number | null}} WmsRtnRef
 * @typedef {{nodeId: number, nodeCd: string, path: string, stockStatus: string, qty: string | number}} WmsRtnStock
 * @typedef {{ret: WmsRtnRef, itemCd: string, itemNm: string, lotCtrl: boolean, stock: WmsRtnStock[]}} WmsRtnView
 * @typedef {{returnNo: string, status: string, poStatus: string | null, openQty: string | number | null}} WmsRtnShipped
 * @typedef {{trxIds: number[], replay: boolean, shipped: WmsRtnShipped | null}} WmsRtnResult
 */
(function () {
    'use strict';

    var API = '/api/wms/rtn-issue';
    var msg = /** @type {HTMLElement} */ (document.getElementById('msg'));
    var findForm = /** @type {HTMLFormElement} */ (document.getElementById('findForm'));
    var returnNo = /** @type {HTMLInputElement} */ (document.getElementById('returnNo'));
    var offMsg = /** @type {HTMLElement} */ (document.getElementById('offMsg'));
    var entry = /** @type {HTMLElement} */ (document.getElementById('entry'));
    var summary = /** @type {HTMLElement} */ (document.getElementById('summary'));
    var body = /** @type {HTMLTableSectionElement} */ (document.querySelector('#stockTbl tbody'));
    var totalCell = /** @type {HTMLElement} */ (document.getElementById('totalCell'));
    var emptyMsg = /** @type {HTMLElement} */ (document.getElementById('emptyMsg'));
    var extraForm = /** @type {HTMLFormElement} */ (document.getElementById('extraForm'));
    var fillBtn = /** @type {HTMLButtonElement | null} */ (document.getElementById('fillBtn'));
    var saveBtn = /** @type {HTMLButtonElement | null} */ (document.getElementById('saveBtn'));
    /** @type {WmsRtnView | null} */
    var view = null;
    /** @type {{row: WmsRtnStock, num: HB.NumberControl}[]} */
    var cells = [];
    var scanned = false;
    /** @type {string | null} */
    var pendingReq = null;
    /** @type {Record<string, string>} */
    var statusNames = {};
    /** @type {Record<string, string>} */
    var rtnStatusNames = {};

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

    function reqId() {
        if (window.crypto && typeof window.crypto.randomUUID === 'function') { return 'rtn-' + window.crypto.randomUUID(); }
        var a = new Uint32Array(4);
        window.crypto.getRandomValues(a);
        return 'rtn-' + Array.prototype.map.call(a, function (x) { return x.toString(16); }).join('');
    }

    /** @param {string} name @param {string} text */
    function setV(name, text) {
        var dd = summary.querySelector('[data-v="' + name + '"]');
        if (dd) { dd.textContent = text; }
    }

    function total() {
        var t = 0;
        cells.forEach(function (c) { t += c.num.numberValue() || 0; });
        return t;
    }

    function showTotal() {
        if (!view) { return; }
        var want = Number(view.ret.baseQty);
        var t = total();
        totalCell.textContent = q(t) + ' / ' + q(view.ret.baseQty) + ' ' + view.ret.baseUnitCd;
        totalCell.classList.toggle('hb-msg-error', Math.abs(t - want) > 1e-9);
    }

    function changed() { pendingReq = null; showTotal(); }

    /** @param {WmsRtnView} v */
    function render(v) {
        view = v;
        var r = v.ret;
        entry.hidden = false;
        setV('returnNo', r.returnNo);
        setV('status', rtnStatusNames[r.status] || r.status);
        setV('item', v.itemCd + ' · ' + v.itemNm);
        setV('lotNo', r.lotNo + (v.lotCtrl ? '' : ' ' + hb.t('js.wmsRtn.lotNotControlled')));
        setV('qty', q(r.qty) + ' ' + r.unitCd + (r.unitCd !== r.baseUnitCd ? ' = ' + q(r.baseQty) + ' ' + r.baseUnitCd : ''));
        setV('replace', hb.t(r.replace ? 'js.wmsRtn.replaceY' : 'js.wmsRtn.replaceN'));
        setV('plantCd', r.plantCd);
        body.textContent = '';
        cells = [];
        v.stock.forEach(function (s) {
            var inp = /** @type {HTMLInputElement} */ (document.createElement('input'));
            inp.setAttribute('inputmode', 'decimal');
            inp.dataset.hb = 'number';
            inp.dataset.scale = '6';
            inp.dataset.pad = 'false';
            inp.dataset.min = '0';
            inp.dataset.max = String(s.qty);
            inp.setAttribute('aria-label', hb.t('js.wmsRtn.issueQtyOf', s.path));
            var tr = document.createElement('tr');
            tr.appendChild(hb.el('td', null, s.path));
            tr.appendChild(hb.el('td', null, statusNames[s.stockStatus] || s.stockStatus));
            tr.appendChild(hb.el('td', 'hb-num', q(s.qty)));
            var td = hb.el('td', 'hb-num');
            td.appendChild(inp);
            tr.appendChild(td);
            body.appendChild(tr);
            var num = hb.number(inp);
            inp.addEventListener('input', changed);
            inp.addEventListener('change', changed);
            cells.push({row: s, num: num});
        });
        var shippable = r.status === 'CONFIRMED';
        emptyMsg.hidden = !shippable || v.stock.length > 0;
        if (saveBtn) { saveBtn.disabled = !shippable || v.stock.length === 0; }
        if (fillBtn) { fillBtn.disabled = !shippable || v.stock.length === 0; }
        pendingReq = null;
        showTotal();
        if (!shippable) { notify(hb.t('js.wmsRtn.alreadyShipped', r.returnNo)); }
    }

    function find() {
        var no = returnNo.value.trim();
        if (!no) { return Promise.resolve(); }
        notify('');
        return hb.latest('wmsRtnView', hb.api(API + '?returnNo=' + encodeURIComponent(no))).then(function (res) {
            render(/** @type {WmsRtnView} */ (res.data));
        }, function (err) {
            if (err.stale) { return; }
            view = null;
            entry.hidden = true;
            notify(errText(err), true);
        });
    }

    /** 위 칸부터 지시 수량만큼 채움(칸 재고까지) */
    function fill() {
        if (!view) { return; }
        var left = Number(view.ret.baseQty);
        cells.forEach(function (c) {
            var take = Math.min(left, Number(c.row.qty));
            c.num.setValue(take > 0 ? hb.fmt.num(take, 6, 0).replace(/,/g, '') : '');
            left -= Math.max(take, 0);
        });
        changed();
    }

    function preview() {
        if (!view) { return ''; }
        var lines = cells.filter(function (c) { return (c.num.numberValue() || 0) > 0; })
            .map(function (c) { return c.row.path + ' · ' + (statusNames[c.row.stockStatus] || c.row.stockStatus) + ' − ' + c.num.value(); });
        return hb.t('js.wmsRtn.dlg.body', view.ret.returnNo, lines.join(', '), q(total()), view.ret.baseUnitCd);
    }

    if (saveBtn) {
        var btn = saveBtn;
        btn.addEventListener('click', function () {
            if (!view) { return; }
            var bad = cells.some(function (c) { var k = c.num.validate(); return !k.ok && k.code !== 'REQUIRED'; });
            if (bad) { return; }
            var v = view;
            hb.dialog.confirm({title: hb.t('js.wmsRtn.dlg.title'), message: preview(), ok: hb.t('js.wmsRtn.dlg.ok')}).then(function (yes) {
                if (!yes) { return; }
                if (!pendingReq) { pendingReq = reqId(); }
                var extra = /** @type {Record<string, string>} */ ({});
                extraForm.querySelectorAll('[data-field]').forEach(function (el) {
                    var i = /** @type {HTMLInputElement} */ (el);
                    extra[/** @type {string} */ (i.dataset.field)] = i.value;
                });
                var b = {
                    returnNo: v.ret.returnNo,
                    rows: cells.filter(function (c) { return (c.num.numberValue() || 0) > 0; })
                        .map(function (c) { return {nodeId: c.row.nodeId, stockStatus: c.row.stockStatus, qty: c.num.value()}; }),
                    occurredAt: extra.occurredAt || null,
                    remark: extra.remark || null,
                    entryMethod: scanned ? 'SCAN' : 'KEY',
                    clientReqId: pendingReq
                };
                return hb.busy(btn, function () { return hb.api(API, {method: 'POST', body: b}); }).then(function (res) {
                    if (!res) { return; }
                    var r = /** @type {WmsRtnResult} */ (res.data);
                    pendingReq = null;
                    var text = hb.t(r.replay ? 'js.wmsRtn.replayed' : 'js.wmsRtn.posted', v.ret.returnNo, r.trxIds.join(', '));
                    if (r.shipped && r.shipped.openQty != null) { text += ' ' + hb.t('js.wmsRtn.waitReplace', q(r.shipped.openQty)); }
                    return find().then(function () { notify(text); });
                }, function (err) {
                    notify(errText(err), true);
                    if (err.status && err.status !== 409 && err.status < 500) { pendingReq = null; }   // 고쳐서 다시 — 새 요청
                });
            });
        });
    }
    if (fillBtn) { fillBtn.addEventListener('click', fill); }
    findForm.addEventListener('submit', function (e) {
        e.preventDefault();
        scanned = /^RTN:/i.test(returnNo.value.trim());
        find();
    });
    extraForm.addEventListener('input', function () { pendingReq = null; });
    extraForm.addEventListener('submit', function (e) { e.preventDefault(); });

    hb.loadMessages().then(function () {
        hb.page.init();
        return Promise.all([hb.loadSession(), hb.api(API + '/available'), hb.codes('STOCK_STATUS'), hb.codes('RETURN_STATUS')]);
    }).then(function (r) {
        statusNames = r[2];
        rtnStatusNames = r[3];
        if (!r[1].data) {
            offMsg.hidden = false;
            findForm.hidden = true;
            return;
        }
        returnNo.focus();
    }, function (err) { notify(err && err.message, true); });
})();
