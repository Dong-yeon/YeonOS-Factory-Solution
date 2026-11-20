// @ts-check
/* 계획 기준정보 (aps-item-param) — APS 8단계 (docs/38 17장). hb.grid 목록 + 상세 폼. 불량 유형 화면과 같은 구조 */
/**
 * @typedef {{paramId: number, plantCd: string, itemId: number, itemCd: string, itemNm: string, procType: string, unitCd: string,
 *   safetyQty: string, lotRule: string, lotQty: string | null, lotMult: string | null, makeLeadDays: number | null,
 *   fenceDays: number | null, remark: string | null, useYn: string, rowVer: number, [field: string]: any}} ApsParamRow
 */
(function () {
    'use strict';

    var API = '/api/aps/item-params';
    var ACTIONS = (document.body.getAttribute('data-actions') || '').split(',');
    var CAN_SAVE = ACTIONS.indexOf('SAVE') >= 0;
    var msg = /** @type {HTMLElement} */ (document.getElementById('msg'));
    var form = /** @type {HTMLFormElement} */ (document.getElementById('paramForm'));
    var formMode = /** @type {HTMLElement} */ (document.getElementById('formMode'));
    var itemInfo = /** @type {HTMLElement} */ (document.getElementById('itemInfo'));
    var PROG = document.body.getAttribute('data-prog') || '';
    var search = /** @type {HTMLFormElement} */ (document.getElementById('search'));
    /** @type {ApsParamRow | null} */
    var current = null;
    /** @type {HB.NumberControl} */
    var leadCtl;
    /** @type {HB.NumberControl} */
    var fenceCtl;
    /** @type {HB.SearchPanel} */
    var sp;
    /** @type {HB.Grid | undefined} */
    var grid;

    function confirmLeave() {
        if (!hb.dirty.isDirty()) { return Promise.resolve(true); }
        return hb.dialog.confirm({title: hb.t('js.common.leaveTitle'), message: hb.t('js.common.confirmLeave'), ok: hb.t('js.common.leaveOk')});
    }

    /** @param {string | null | undefined} text @param {boolean} [error] */
    function notify(text, error) {
        msg.textContent = text || '';
        msg.classList.toggle('hb-msg-error', !!error);
    }

    /** @param {string} n */
    function field(n) {
        return /** @type {HTMLInputElement | HTMLSelectElement} */ (form.querySelector('[data-field="' + n + '"]'));
    }

    /** @param {HTMLSelectElement} sel @param {string} v @param {string} text */
    function option(sel, v, text) {
        var o = document.createElement('option');
        o.value = v;
        o.textContent = text;
        sel.appendChild(o);
    }

    /** 로트 규칙에 맞춰 칸 잠금 — EX 는 로트 수량 없음, FX 는 배수 없음(로트 수량이 배수) */
    function syncLot() {
        var rule = field('lotRule').value;
        var lot = /** @type {HTMLInputElement} */ (field('lotQty'));
        var mult = /** @type {HTMLInputElement} */ (field('lotMult'));
        lot.disabled = !CAN_SAVE || rule === 'EX';
        mult.disabled = !CAN_SAVE || rule === 'FX';
        if (rule === 'EX') { lot.value = ''; }
        if (rule === 'FX') { mult.value = ''; }
    }

    /** @param {ApsParamRow | null} d */
    function show(d) {
        current = d;
        hb.showFieldErrors(form, []);
        field('plantCd').value = d ? d.plantCd : (sp && sp.values().plantCd) || field('plantCd').value;
        field('itemCd').value = d ? d.itemCd : '';
        field('safetyQty').value = d ? d.safetyQty : '0';
        field('lotRule').value = d ? d.lotRule : 'EX';
        field('lotQty').value = d && d.lotQty ? d.lotQty : '';
        field('lotMult').value = d && d.lotMult ? d.lotMult : '';
        leadCtl.setValue(d ? d.makeLeadDays : null);
        fenceCtl.setValue(d ? d.fenceDays : null);
        field('useYn').value = d ? d.useYn : 'Y';
        field('remark').value = d && d.remark ? d.remark : '';
        field('plantCd').disabled = !CAN_SAVE || !!d;
        /** @type {HTMLInputElement} */ (field('itemCd')).readOnly = !!d;
        itemInfo.textContent = d ? hb.t('js.apsParam.itemInfo', d.itemNm, d.unitCd) : '';
        formMode.textContent = d ? hb.t('js.apsParam.editing', d.plantCd, d.itemCd) : hb.t('js.apsParam.creating');
        syncLot();
        hb.dirty.clear();
    }

    function params() {
        var v = sp.values();
        return {plantCd: v.plantCd || '', keyword: v.keyword || '', useYn: v.withUnused && v.withUnused.length ? '' : 'Y'};
    }

    /** @param {number} [keepId] */
    function reloadGrid(keepId) {
        return grid ? grid.reload(false, keepId) : Promise.resolve();
    }

    function newParam() {
        if (!leadCtl) { return; }
        confirmLeave().then(function (ok) {
            if (!ok) { return; }
            show(null);
            hb.page.openDetail();
            field('itemCd').focus();
        });
    }
    ['newBtn', 'newBtnList'].forEach(function (id) {
        var b = document.getElementById(id);
        if (b) { b.addEventListener('click', newParam); }
    });
    field('lotRule').addEventListener('change', syncLot);

    var saveBtn = /** @type {HTMLButtonElement | null} */ (document.getElementById('saveBtn'));
    if (saveBtn) {
        var btn = saveBtn;
        btn.addEventListener('click', function () {
            if (!leadCtl) { return; }
            var lead = leadCtl.validate();
            var fence = fenceCtl.validate();
            if (!lead.ok || !fence.ok) { return; }
            var body = {plantCd: field('plantCd').value, itemCd: field('itemCd').value.trim(), safetyQty: field('safetyQty').value.trim(),
                lotRule: field('lotRule').value, lotQty: field('lotQty').value.trim() || null, lotMult: field('lotMult').value.trim() || null,
                makeLeadDays: leadCtl.numberValue(), fenceDays: fenceCtl.numberValue(), useYn: field('useYn').value,
                remark: field('remark').value.trim(), rowVer: current ? current.rowVer : null};
            var editId = current ? current.paramId : null;
            hb.busy(btn, function () {
                return editId !== null ? hb.api(API + '/' + editId, {method: 'PUT', body: body}) : hb.api(API, {method: 'POST', body: body});
            }).then(function (res) {
                if (!res) { return; }
                var id = editId !== null ? editId : /** @type {ApsParamRow} */ (res.data).paramId;
                return reloadGrid(id).then(function () { notify(hb.t('js.common.saved')); });
            }, function (err) {
                notify(err.message, true);
                hb.showFieldErrors(form, err.errors);
            });
        });
    }
    if (!CAN_SAVE) {
        form.querySelectorAll('input, select').forEach(function (i) {
            /** @type {HTMLInputElement | HTMLSelectElement} */ (i).disabled = true;
        });
    }

    hb.dirty.watch(form);
    search.addEventListener('submit', function (e) { e.preventDefault(); });
    hb.loadMessages().then(function () {
        hb.page.init();
        leadCtl = hb.number(/** @type {HTMLInputElement} */ (field('makeLeadDays')));
        fenceCtl = hb.number(/** @type {HTMLInputElement} */ (field('fenceDays')));
        return Promise.all([hb.loadSession(), hb.api(API + '/plants')]);
    }).then(function (r) {
        var plantSel = /** @type {HTMLSelectElement} */ (document.getElementById('plantSel'));
        var formPlant = /** @type {HTMLSelectElement} */ (field('plantCd'));
        option(plantSel, '', hb.t('js.apsParam.allPlant'));
        /** @type {{plantCd: string, plantNm: string}[]} */ (r[1].data).forEach(function (p) {
            option(plantSel, p.plantCd, p.plantCd + ' · ' + p.plantNm);
            option(formPlant, p.plantCd, p.plantCd + ' · ' + p.plantNm);
        });
        sp = hb.searchPanel(search, {
            onSearch: function () {
                if (!grid) { return null; }
                var g = grid;
                return confirmLeave().then(function (ok) { return ok ? g.reload(true) : null; });
            }
        });
        return sp.ready;
    }).then(function () {
        show(null);
        grid = hb.grid('#paramGrid', {
            progCd: PROG, id: 'main', key: 'paramId',
            url: API + '/grid', columnsUrl: API + '/columns', exportUrl: API + '/export',
            actions: ACTIONS, params: params,
            beforeSelect: function () { return confirmLeave(); },
            status: {useYn: {Y: 'normal', N: 'neutral'}},
            rowClass: function (row) { return row.useYn === 'N' ? 'hb-tree-dim' : null; },
            onSelect: function (row) { show(/** @type {ApsParamRow} */ (row)); hb.page.openDetail(); },
            notify: notify
        });
        return grid.ready;
    }).then(null, function (err) { notify(err && err.message, true); });
})();
