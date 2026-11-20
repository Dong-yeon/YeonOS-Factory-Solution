// @ts-check
/* OCAP 표준 조치 목록 (qms-ocap) — docs/29_P3 26장 1단계. hb.grid 목록 + 상세 폼(불량 유형과 같은 구조). 지우지 않고 사용 중지 */
/**
 * @typedef {{ocapId: number, ruleCd: string | null, ruleKey: string, seq: number, actionText: string, useYn: string,
 *   rowVer: number, [field: string]: any}} QmsOcapRow
 */
(function () {
    'use strict';

    var API = '/api/qms/ocaps';
    var ACTIONS = (document.body.getAttribute('data-actions') || '').split(',');
    var msg = /** @type {HTMLElement} */ (document.getElementById('msg'));
    var form = /** @type {HTMLFormElement} */ (document.getElementById('ocapForm'));
    var formMode = /** @type {HTMLElement} */ (document.getElementById('formMode'));
    var PROG = document.body.getAttribute('data-prog') || '';
    var search = /** @type {HTMLFormElement} */ (document.getElementById('search'));
    /** @type {QmsOcapRow | null} */
    var current = null;
    /** @type {HB.NumberControl} */
    var seq;
    /** @type {HB.SelectControl} */
    var rule;
    /** @type {HB.SearchPanel} */
    var sp;
    /** @type {HB.Grid | undefined} */
    var grid;
    /** 규칙 표시 — 공통(*)·코드 이름(문구 키는 글자 그대로) @type {Record<string, string>} */
    var ruleNames = {};

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

    /** @param {QmsOcapRow | null} d */
    function show(d) {
        current = d;
        hb.showFieldErrors(form, []);
        field('actionText').value = d ? d.actionText : '';
        rule.setValue(d && d.ruleCd ? d.ruleCd : '');
        seq.setValue(d ? d.seq : 0);
        field('useYn').value = d ? d.useYn : 'Y';
        formMode.textContent = d ? hb.t('js.qms.ocap.editing', ruleNames[d.ruleKey] || d.ruleKey) : hb.t('js.qms.ocap.creating');
        hb.dirty.clear();
    }

    function params() {
        var v = sp.values();
        return {keyword: v.keyword, ruleCd: v.ruleCd, useYn: v.withUnused && v.withUnused.length ? '' : 'Y'};
    }

    function newItem() {
        if (!rule) { return; }
        confirmLeave().then(function (ok) {
            if (!ok) { return; }
            var keep = current ? (current.ruleCd || '') : rule.value();
            show(null);
            rule.setValue(keep);      // 같은 규칙 항목을 연달아 넣는 경우가 많다
            hb.page.openDetail();
            field('actionText').focus();
        });
    }
    ['newBtn', 'newBtnList'].forEach(function (id) {
        var b = document.getElementById(id);
        if (b) { b.addEventListener('click', newItem); }
    });

    var saveBtn = /** @type {HTMLButtonElement | null} */ (document.getElementById('saveBtn'));
    if (saveBtn) {
        var btn = saveBtn;
        btn.addEventListener('click', function () {
            if (!rule) { return; }
            var body = {ruleCd: rule.value() || null, seq: seq.numberValue(), actionText: field('actionText').value, useYn: field('useYn').value,
                rowVer: current ? current.rowVer : null};
            var editId = current ? current.ocapId : null;
            hb.busy(btn, function () {
                return editId !== null ? hb.api(API + '/' + editId, {method: 'PUT', body: body}) : hb.api(API, {method: 'POST', body: body});
            }).then(function (res) {
                if (!res) { return; }
                return (grid ? grid.reload(false, editId !== null ? editId : res.data) : Promise.resolve())
                    .then(function () { notify(hb.t('js.common.saved')); });
            }, function (err) {
                notify(err.message, true);
                hb.showFieldErrors(form, err.errors);
            });
        });
    }
    if (ACTIONS.indexOf('SAVE') < 0) {
        form.querySelectorAll('input, select').forEach(function (i) {
            /** @type {HTMLInputElement | HTMLSelectElement} */ (i).disabled = true;
        });
    }

    hb.dirty.watch(form);
    search.addEventListener('submit', function (e) { e.preventDefault(); });
    hb.loadMessages().then(function () {
        hb.page.init();
        ruleNames = {'*': hb.t('js.qms.ocap.common'), WE1: hb.t('js.qms.spc.rule.WE1'), WE2: hb.t('js.qms.spc.rule.WE2'),
            WE3: hb.t('js.qms.spc.rule.WE3'), WE4: hb.t('js.qms.spc.rule.WE4'), R1: hb.t('js.qms.spc.rule.R1')};
        seq = hb.number(/** @type {HTMLInputElement} */ (field('seq')));
        rule = hb.select(/** @type {HTMLSelectElement} */ (field('ruleCd')));
        var filter = hb.select(/** @type {HTMLSelectElement} */ (document.getElementById('ruleFilter')));
        sp = hb.searchPanel(search, {
            onSearch: function () {
                if (!grid) { return null; }
                var g = grid;
                return confirmLeave().then(function (ok) { return ok ? g.reload(true) : null; });
            }
        });
        return Promise.all([hb.loadSession(), rule.ready, filter.ready, sp.ready]);
    }).then(function () {
        // 규칙 칸의 빈 값 = 모든 규칙 공통, 조회 칸에는 "공통만"(*) 을 전체 다음에
        var blank = /** @type {HTMLSelectElement} */ (field('ruleCd')).options[0];
        if (blank) { blank.textContent = hb.t('js.qms.ocap.common'); }
        var f = /** @type {HTMLSelectElement} */ (document.getElementById('ruleFilter'));
        var only = hb.el('option', null, hb.t('js.qms.ocap.commonOnly'));
        /** @type {HTMLOptionElement} */ (only).value = '*';
        f.insertBefore(only, f.options[1] || null);
        show(null);
        grid = hb.grid('#ocapGrid', {
            progCd: PROG, id: 'main', key: 'ocapId',
            url: API + '/grid', columnsUrl: API + '/columns', exportUrl: API + '/export',
            actions: ACTIONS, params: params,
            format: {ruleKey: function (v) { return ruleNames[v] || v; }},
            beforeSelect: function () { return confirmLeave(); },
            status: {useYn: {Y: 'normal', N: 'neutral'}},
            rowClass: function (row) { return row.useYn === 'N' ? 'hb-tree-dim' : null; },
            onSelect: function (row) { show(/** @type {QmsOcapRow} */ (row)); hb.page.openDetail(); },
            notify: notify
        });
        return grid.ready;
    }).then(null, function (err) { notify(err && err.message, true); });
})();
