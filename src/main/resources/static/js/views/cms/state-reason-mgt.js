/*
 * 설비 상태 사유 (cms-state-reason) — CMS 2부 C7 (docs/33 11.2, Q1). 표는 MES 것, 화면·API 만 CMS.
 * 표(분류·코드·이름·계획·순서·사용) → 상세 폼(코드·분류는 만든 뒤 고정).
 */
(function () {
    'use strict';

    var API = '/api/cms/state-reasons';
    var ACTIONS = (document.body.getAttribute('data-actions') || '').split(',');
    var CAN_SAVE = ACTIONS.indexOf('SAVE') >= 0;
    var msg = document.getElementById('msg');
    var form = document.getElementById('reasonForm');
    var search = document.getElementById('search');
    var current = null, sp, grid;
    var catNames = {};

    function notify(text, error) {
        msg.textContent = text || '';
        msg.classList.toggle('hb-msg-error', !!error);
    }

    function field(n) { return form.querySelector('[data-field="' + n + '"]'); }

    function catName(c) { return catNames[c] || c; }

    function show(r) {
        current = r;
        hb.showFieldErrors(form, []);
        field('reasonCd').value = r ? r.reasonCd : '';
        field('stateCat').value = r ? r.stateCat : 'UDT';
        field('reasonNm').value = r ? r.reasonNm : '';
        field('plannedYn').value = r ? r.plannedYn : 'N';
        field('sortNo').value = r ? String(r.sortNo) : '0';
        field('useYn').value = r ? r.useYn : 'Y';
        ['reasonCd', 'stateCat'].forEach(function (n) { field(n).disabled = !!r || !CAN_SAVE; });
        document.getElementById('formMode').textContent = r
            ? hb.t('js.cms.stateReason.editing', r.reasonCd, catName(r.stateCat))
            : hb.t('js.cms.stateReason.creating');
        hb.dirty.clear();
    }

    function body() {
        var sort = field('sortNo').value.trim();
        return {reasonCd: field('reasonCd').value, stateCat: field('stateCat').value, reasonNm: field('reasonNm').value,
            plannedYn: field('plannedYn').value, sortNo: sort === '' ? 0 : (/^\d+$/.test(sort) ? Number(sort) : -1),
            useYn: field('useYn').value, rowVer: current ? current.rowVer : null};
    }

    /** 조회 조건(검색 패널) — grid 가 page·size·sort 를 붙인다 */
    function params() {
        var v = sp.values();
        var q = {};
        ['keyword', 'stateCat', 'useYn'].forEach(function (k) { if (v[k]) { q[k] = v[k]; } });
        return q;
    }

    /** 목록 다시 읽기 — selectId 가 이 쪽에 있으면 grid 가 골라 상세를 연다(grid 행 = 목록 행이라 그대로 보인다) */
    function load(selectId) {
        return grid.reload(true, selectId || undefined).then(function () {
            var sel = grid.selected();
            if (!selectId || !sel || sel.reasonId !== selectId) { show(null); }
        });
    }

    ['newBtn', 'newBtnList'].forEach(function (id) {
        var b = document.getElementById(id);
        if (b) {
            b.addEventListener('click', function () {
                cmsUi.leaveOk().then(function (ok) {
                    if (!ok) { return; }
                    show(null);
                    hb.page.openDetail();
                    field('reasonCd').focus();
                });
            });
        }
    });

    var saveBtn = document.getElementById('saveBtn');
    if (saveBtn) {
        saveBtn.addEventListener('click', function () {
            var editing = !!current;
            hb.busy(saveBtn, function () {
                return editing ? hb.api(API + '/' + current.reasonId, {method: 'PUT', body: body()}) : hb.api(API, {method: 'POST', body: body()});
            }).then(function (res) {
                if (!res) { return; }
                return load(editing ? current.reasonId : res.data).then(function () { notify(hb.t('js.common.saved')); });
            }, function (err) {
                notify(err.message, true);
                hb.showFieldErrors(form, err.errors);
            });
        });
    }

    if (!CAN_SAVE) {
        form.querySelectorAll('input, select').forEach(function (i) { i.disabled = true; });
    }
    hb.dirty.watch(form);
    hb.loadMessages().then(function () {
        hb.page.init();
        return hb.codeList('STATE_CAT');
    }).then(function (cats) {
        cats.forEach(function (c) {
            catNames[c.code] = c.name;
            if (c.code === 'UNKNOWN') { return; }   // 통신 끊김은 사유가 없다
            ['catSearch', 'catForm'].forEach(function (id) {
                var o = hb.el('option', null, c.name);
                o.value = c.code;
                document.getElementById(id).appendChild(o);
            });
        });
        sp = hb.searchPanel(search, {onSearch: function () {
            return cmsUi.leaveOk().then(function (ok) { return ok ? load() : null; });
        }});
        grid = hb.grid('#reasonGrid', {
            progCd: 'cms-state-reason', id: 'main', key: 'reasonId',
            url: API + '/grid', columnsUrl: API + '/columns', exportUrl: API + '/export', actions: ACTIONS, params: params,
            filters: {stateCat: 'stateCat', useYn: 'useYn'},
            onFilter: function (param, value) { sp.setValue(param, value); },
            format: {plannedYn: function (v) { return v === 'Y' ? hb.t('js.cms.stateReason.plannedMark') : ''; }},
            beforeSelect: function () { return cmsUi.leaveOk(); },
            status: {useYn: {Y: 'normal', N: 'neutral'}},
            rowClass: function (row) { return row.useYn === 'N' ? 'hb-tree-dim' : null; },
            onSelect: function (row) { show(row); hb.page.openDetail(); },
            notify: notify
        });
        return grid.ready.then(function () { show(null); });   // grid 가 첫 쪽을 이미 읽었다 — 다시 읽지 않는다
    }).then(null, function (err) { notify(err && err.message, true); });
})();
