// @ts-check
/* 고객 반품 입고 (wms-cust-rtn) — docs/59. 근거(출하 행 × LOT) 찾기 → [받기] → 수량·사유·받는 칸 → 반품 입고(BLOCKED, LOT 관리 품목은 QMS NCR 함께).
   요청 id 는 누른 순간 만들고 입력이 바뀌면 버린다(응답이 끊겨 다시 눌러도 두 번 안 받음). 받을 수 있는 양·칸·상태 판정은 서버.
   취소는 승인 권한 + NCR 이 열림(OPEN)이거나 NCR 없음일 때만 단추(서버도 같은 규칙 — R-6). */
/**
 * @typedef {{shipId: number, shipNo: string, shippedAt: string, shipLineId: number, custCd: string, custNm: string, itemId: number,
 *   itemCd: string, itemNm: string, unitCd: string, lotId: number | null, lotNo: string | null, shippedQty: string | number,
 *   returnedQty: string | number, openQty: string | number}} WmsCrtSource
 * @typedef {{rtnId: number, rtnNo: string, status: string, shipNo: string, custCd: string, custNm: string, itemCd: string, itemNm: string,
 *   unitCd: string, lotNo: string | null, qty: string | number, reasonCd: string, nodePath: string, ncrId: number | null,
 *   ncrNo: string | null, ncrStatus: string | null, receivedAt: string, receivedBy: string, cancelReason: string | null,
 *   rowVer: number, warnings?: string[]}} WmsCrtRtn
 * @typedef {{nodeId: number, path: string, locUsage: string | null}} WmsCrtNode
 */
(function () {
    'use strict';

    var API = '/api/wms/cust-rtns';
    var ACTIONS = (document.body.getAttribute('data-actions') || '').split(',');
    var CAN_APPROVE = ACTIONS.indexOf('APPROVE') >= 0;
    var msg = /** @type {HTMLElement} */ (document.getElementById('msg'));
    var findForm = /** @type {HTMLFormElement} */ (document.getElementById('findForm'));
    var plantSel = /** @type {HTMLSelectElement} */ (document.getElementById('plantSel'));
    var srcBox = /** @type {HTMLElement} */ (document.getElementById('srcBox'));
    var srcBody = /** @type {HTMLTableSectionElement} */ (document.querySelector('#srcTbl tbody'));
    var entryBox = /** @type {HTMLElement} */ (document.getElementById('entryBox'));
    var entryInfo = /** @type {HTMLElement} */ (document.getElementById('entryInfo'));
    var entryForm = /** @type {HTMLFormElement} */ (document.getElementById('entryForm'));
    var listForm = /** @type {HTMLFormElement} */ (document.getElementById('listForm'));
    var statusSel = /** @type {HTMLSelectElement} */ (document.getElementById('statusSel'));
    var rtnBody = /** @type {HTMLTableSectionElement} */ (document.querySelector('#rtnTbl tbody'));
    /** @type {WmsCrtSource | null} */
    var picked = null;
    /** @type {HB.NumberControl | null} */
    var qtyNum = null;
    /** @type {string | null} */
    var pendingReq = null;
    /** @type {Record<string, string>} */
    var statusNames = {};
    /** @type {Record<string, string>} */
    var reasonNames = {};

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

    /** @param {string} id */
    function val(id) { return (/** @type {HTMLInputElement} */ (document.getElementById(id))).value.trim(); }

    /** @param {string} name */
    function field(name) { return /** @type {HTMLInputElement | HTMLSelectElement} */ (entryForm.querySelector('[data-field="' + name + '"]')); }

    function reqId() {
        if (window.crypto && typeof window.crypto.randomUUID === 'function') { return 'crt-' + window.crypto.randomUUID(); }
        var a = new Uint32Array(4);
        window.crypto.getRandomValues(a);
        return 'crt-' + Array.prototype.map.call(a, function (/** @type {number} */ x) { return x.toString(16); }).join('');
    }

    /** @param {HTMLSelectElement} sel @param {string} value @param {string} text */
    function option(sel, value, text) {
        var o = /** @type {HTMLOptionElement} */ (hb.el('option', null, text));
        o.value = value;
        sel.appendChild(o);
    }

    // ---------------------------------------------------------------- 근거 찾기

    function find() {
        var p = new URLSearchParams({plantCd: plantSel.value});
        [['shipNo', 'shipNo'], ['lotNo', 'lotNo'], ['custCd', 'custCd'], ['from', 'fromDate'], ['to', 'toDate']].forEach(function (x) {
            var v = val(x[1]);
            if (v) { p.set(x[0], v); }
        });
        hb.latest('wmsCrtSrc', hb.api(API + '/sources?' + p.toString())).then(function (res) {
            drawSources(/** @type {WmsCrtSource[]} */ (res.data));
        }, function (err) { if (!err.stale) { notify(errText(err), true); } });
    }

    /** @param {WmsCrtSource[]} list */
    function drawSources(list) {
        srcBox.hidden = false;
        srcBody.textContent = '';
        if (!list.length) {
            var tr = hb.el('tr');
            var td = hb.el('td', 'hb-muted', hb.t('js.wmsCustRtn.noSource'));
            td.setAttribute('colspan', '8');
            tr.appendChild(td);
            srcBody.appendChild(tr);
            return;
        }
        list.forEach(function (s) {
            var tr = hb.el('tr');
            tr.appendChild(hb.el('td', null, s.shipNo + ' · ' + hb.fmt.date(s.shippedAt)));
            tr.appendChild(hb.el('td', 'hb-wrap', s.custCd + ' ' + s.custNm));
            tr.appendChild(hb.el('td', 'hb-wrap', s.itemCd + ' ' + s.itemNm));
            tr.appendChild(hb.el('td', null, s.lotNo || '—'));
            tr.appendChild(hb.el('td', 'hb-num', q(s.shippedQty) + ' ' + s.unitCd));
            tr.appendChild(hb.el('td', 'hb-num', q(s.returnedQty)));
            tr.appendChild(hb.el('td', 'hb-num', q(s.openQty)));
            var cell = hb.el('td');
            if (ACTIONS.indexOf('SAVE') >= 0) {
                var b = /** @type {HTMLButtonElement} */ (hb.el('button', 'hb-btn-secondary', hb.t('js.wmsCustRtn.pick')));
                b.type = 'button';
                b.setAttribute('aria-label', hb.t('js.wmsCustRtn.pickFor', s.shipNo, s.lotNo || s.itemCd));
                b.addEventListener('click', function () { pick(s); });
                cell.appendChild(b);
            }
            tr.appendChild(cell);
            srcBody.appendChild(tr);
        });
    }

    /** @param {WmsCrtSource} s */
    function pick(s) {
        picked = s;
        pendingReq = null;
        entryBox.hidden = false;
        entryInfo.textContent = hb.t('js.wmsCustRtn.entryInfo', s.shipNo, s.custCd + ' ' + s.custNm, s.itemCd + ' ' + s.itemNm,
            s.lotNo || '—', q(s.openQty), s.unitCd);
        if (qtyNum) { qtyNum.setValue(''); }
        field('qty').focus();
    }

    function loadNodes() {
        var sel = /** @type {HTMLSelectElement} */ (field('nodeId'));
        sel.textContent = '';
        option(sel, '', hb.t('js.wmsCustRtn.chooseNode'));
        if (!plantSel.value) { return Promise.resolve(); }
        return hb.api(API + '/nodes?plantCd=' + encodeURIComponent(plantSel.value)).then(function (res) {
            /** @type {WmsCrtNode[]} */ (res.data).forEach(function (n) {
                option(sel, String(n.nodeId), n.path + (n.locUsage === 'QC' ? ' · QC' : ''));
            });
        }, function (err) { notify(errText(err), true); });
    }

    function save() {
        var btn = /** @type {HTMLButtonElement | null} */ (document.getElementById('saveBtn'));
        if (!picked || !btn || !qtyNum) { return; }
        if (!pendingReq) { pendingReq = reqId(); }
        var body = {plantCd: plantSel.value, shipLineId: picked.shipLineId, lotId: picked.lotId, qty: qtyNum.value(),
            reasonCd: field('reasonCd').value, nodeId: field('nodeId').value ? Number(field('nodeId').value) : null,
            capaNo: field('capaNo').value, remark: field('remark').value, entryMethod: 'KEY', clientReqId: pendingReq};
        var src = picked;
        hb.busy(btn, function () { return hb.api(API, {method: 'POST', body: body}); }).then(function (res) {
            if (!res) { return; }
            var r = /** @type {WmsCrtRtn} */ (res.data);
            pendingReq = null;
            var warn = r.warnings && r.warnings.length ? r.warnings.join(' ') : '';   // 적치 검사 경고(W-12, 저장은 됨)
            notify(hb.t('js.wmsCustRtn.saved', r.rtnNo, q(r.qty), r.unitCd) + (r.ncrNo ? ' · ' + hb.t('js.wmsCustRtn.withNcr', r.ncrNo) : '')
                + (warn ? ' ' + warn : ''), !!warn);
            entryBox.hidden = true;
            picked = null;
            entryForm.reset();
            loadList();
            if (src) { find(); }
        }, function (err) {
            notify(errText(err), true);
            if (err && err.status && err.status !== 409 && err.status < 500) { pendingReq = null; }   // 고쳐서 다시 — 새 요청
        });
    }

    // ---------------------------------------------------------------- 받은 반품

    function loadList() {
        if (!plantSel.value) { return; }
        var p = new URLSearchParams({plantCd: plantSel.value});
        if (statusSel.value) { p.set('status', statusSel.value); }
        hb.latest('wmsCrtList', hb.api(API + '?' + p.toString())).then(function (res) {
            drawList(/** @type {WmsCrtRtn[]} */ (res.list || []));
        }, function (err) { if (!err.stale) { notify(errText(err), true); } });
    }

    /** @param {WmsCrtRtn[]} list */
    function drawList(list) {
        rtnBody.textContent = '';
        if (!list.length) {
            var tr0 = hb.el('tr');
            var td0 = hb.el('td', 'hb-muted', hb.t('js.wmsCustRtn.noRtn'));
            td0.setAttribute('colspan', '9');
            tr0.appendChild(td0);
            rtnBody.appendChild(tr0);
            return;
        }
        list.forEach(function (r) {
            var tr = hb.el('tr', r.status === 'CANCELLED' ? 'hb-tree-dim' : null);
            tr.appendChild(hb.el('td', null, r.rtnNo));
            tr.appendChild(hb.el('td', null, (statusNames[r.status] || r.status) + (r.cancelReason ? ' — ' + r.cancelReason : '')));
            tr.appendChild(hb.el('td', 'hb-wrap', r.custCd + ' ' + r.custNm));
            tr.appendChild(hb.el('td', null, r.shipNo));
            tr.appendChild(hb.el('td', 'hb-wrap', r.itemCd + ' ' + r.itemNm + (r.lotNo ? ' · LOT ' + r.lotNo : '') + ' · '
                + (reasonNames[r.reasonCd] || r.reasonCd) + ' · ' + r.nodePath));
            tr.appendChild(hb.el('td', 'hb-num', q(r.qty) + ' ' + r.unitCd));
            tr.appendChild(hb.el('td', null, r.ncrNo ? r.ncrNo + (r.ncrStatus ? ' · ' + r.ncrStatus : '') : hb.t('js.wmsCustRtn.noNcr')));
            tr.appendChild(hb.el('td', null, hb.fmt.datetime(r.receivedAt) + ' · ' + r.receivedBy));
            var cell = hb.el('td');
            if (CAN_APPROVE && r.status === 'RECEIVED' && (!r.ncrId || r.ncrStatus === 'OPEN')) {
                var b = /** @type {HTMLButtonElement} */ (hb.el('button', 'hb-btn-danger-outline', hb.t('js.wmsCustRtn.cancel')));
                b.type = 'button';
                b.setAttribute('aria-label', hb.t('js.wmsCustRtn.cancelFor', r.rtnNo));
                b.addEventListener('click', function () { cancel(r, b); });
                cell.appendChild(b);
            }
            tr.appendChild(cell);
            rtnBody.appendChild(tr);
        });
    }

    /** @param {WmsCrtRtn} r @param {HTMLButtonElement} b */
    function cancel(r, b) {
        hb.dialog.prompt({title: hb.t('js.wmsCustRtn.dlg.cancel', r.rtnNo), message: hb.t('js.wmsCustRtn.cancelPrompt'),
            label: hb.t('js.wmsCustRtn.reason'), required: true, maxLength: 500, ok: hb.t('js.wmsCustRtn.cancel'), danger: true})
            .then(function (reason) {
                if (!reason) { return; }
                hb.busy(b, function () { return hb.api(API + '/' + r.rtnId + '/cancel', {method: 'POST', body: {rowVer: r.rowVer, reason: reason}}); })
                    .then(function (res) {
                        if (!res) { return; }
                        notify(hb.t('js.wmsCustRtn.cancelled', r.rtnNo));
                        loadList();
                    }, function (err) { notify(errText(err), true); loadList(); });
            });
    }

    // ---------------------------------------------------------------- 연결

    findForm.addEventListener('submit', function (e) { e.preventDefault(); find(); });
    listForm.addEventListener('submit', function (e) { e.preventDefault(); loadList(); });
    entryForm.addEventListener('submit', function (e) { e.preventDefault(); save(); });
    entryForm.addEventListener('input', function () { pendingReq = null; });
    entryForm.addEventListener('change', function () { pendingReq = null; });
    var closeBtn = document.getElementById('entryClose');
    if (closeBtn) { closeBtn.addEventListener('click', function () { entryBox.hidden = true; picked = null; }); }
    plantSel.addEventListener('change', function () { entryBox.hidden = true; picked = null; srcBox.hidden = true; loadNodes(); loadList(); });

    hb.loadMessages().then(function () {
        hb.page.init();
        return Promise.all([hb.loadSession(), hb.api(API + '/plants'), hb.codes('WMS_CUST_RTN_STATUS'), hb.codes('WMS_CUST_RTN_REASON')]);
    }).then(function (r) {
        statusNames = r[2];
        reasonNames = r[3];
        /** @type {{plantCd: string, plantNm: string}[]} */ (r[1].data).forEach(function (p) {
            option(plantSel, p.plantCd, p.plantCd + ' · ' + p.plantNm);
        });
        qtyNum = hb.number(/** @type {HTMLInputElement} */ (field('qty')));
        hb.select(/** @type {HTMLSelectElement} */ (field('reasonCd')));      // data-hb="select" 는 부르는 화면이 채운다(count.js 와 같음)
        hb.select(statusSel);
        loadNodes();
        loadList();
    }, function (err) { notify(err && err.message, true); });
})();
