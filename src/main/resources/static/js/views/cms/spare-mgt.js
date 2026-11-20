/*
 * 설비 예비품 (cms-spare) — CMS 1부 C4 (docs/33 4.5).
 * 표(설비·클래스 × 품목) → 상세 폼(대상·품목은 만든 뒤 고정) + 사용 이력(기록일 기간 — 설비 연결은 그 설비, 클래스 연결은 그 클래스 설비들).
 */
(function () {
    'use strict';

    var API = '/api/cms/spares';
    var ACTIONS = (document.body.getAttribute('data-actions') || '').split(',');
    var CAN_SAVE = ACTIONS.indexOf('SAVE') >= 0;
    var msg = document.getElementById('msg');
    var form = document.getElementById('spareForm');
    var usageForm = document.getElementById('usageForm');
    var search = document.getElementById('search');
    var usageBody = document.querySelector('#usage tbody');
    var current = null, sp, grid, classes = [];

    function notify(text, error) {
        msg.textContent = text || '';
        msg.classList.toggle('hb-msg-error', !!error);
    }

    function field(n) { return form.querySelector('[data-field="' + n + '"]'); }

    function ufield(n) { return usageForm.querySelector('[data-field="' + n + '"]'); }

    function kindName(k) { return k === 'CLASS' ? hb.t('js.cms.spare.kind.CLASS') : hb.t('js.cms.spare.kind.EQUIP'); }

    function show(s) {
        current = s;
        hb.showFieldErrors(form, []);
        field('targetKind').value = s ? s.targetKind : 'EQUIP';
        field('targetCd').value = s ? s.targetCd : '';
        field('itemCd').value = s ? s.itemCd : '';
        field('installQty').value = s ? s.installQtyText : '1';
        field('keySpareYn').value = s ? s.keySpareYn : 'N';
        field('useYn').value = s ? s.useYn : 'Y';
        field('remark').value = s && s.remark ? s.remark : '';
        ['targetKind', 'targetCd', 'itemCd'].forEach(function (n) { field(n).disabled = !!s || !CAN_SAVE; });
        document.getElementById('qtyLabel').textContent = s ? hb.t('js.cms.spare.qtyIn', s.unitCd) : hb.t('js.cms.spare.qty');
        document.getElementById('formMode').textContent = s
            ? hb.t('js.cms.spare.editing', kindName(s.targetKind), s.targetCd + ' · ' + s.targetNm, s.itemCd + ' · ' + s.itemNm)
                + (s.itemUseYn === 'Y' ? '' : ' — ' + hb.t('js.cms.inactive'))
            : hb.t('js.cms.spare.creating');
        document.getElementById('usageSection').hidden = !s;
        usageBody.textContent = '';
        document.getElementById('usageSum').textContent = '';
        hb.dirty.clear();
        if (s) { loadUsage(); }
    }

    /** 사용 이력 조건(선택한 연결 + 기간) — 조회와 엑셀이 같은 조건을 쓴다 */
    function usageQuery() {
        var s = current;
        var q = {from: ufield('from').value, to: ufield('to').value, itemCd: s.itemCd};
        q[s.targetKind === 'CLASS' ? 'classCd' : 'equipCd'] = s.targetCd;
        return q;
    }

    function loadUsage() {
        var u = usageQuery();
        var q = Object.keys(u).map(function (k) { return k + '=' + encodeURIComponent(u[k]); });
        hb.showFieldErrors(usageForm, []);
        return hb.latest('usage', hb.api(API + '/usage?' + q.join('&'))).then(function (res) {
            usageBody.textContent = '';
            if (!res.data.length) {
                var tr0 = hb.el('tr');
                var td0 = hb.el('td', 'hb-muted', hb.t('js.cms.spare.noUsage'));
                td0.colSpan = 4;
                tr0.appendChild(td0);
                usageBody.appendChild(tr0);
            }
            res.data.forEach(function (u) {
                var tr = hb.el('tr');
                [String(u.usedAt).substring(0, 16).replace('T', ' '), u.mwoNo, u.targetCd + ' · ' + u.targetNm, u.qtyText + ' ' + u.unitCd]
                    .forEach(function (v) { tr.appendChild(hb.el('td', null, v)); });
                usageBody.appendChild(tr);
            });
            document.getElementById('usageSum').textContent = hb.t('js.cms.spare.usageCount', res.data.length);
        }, function (err) {
            notify(err.message, true);
            hb.showFieldErrors(usageForm, err.errors);
        });
    }

    function body() {
        return {targetKind: field('targetKind').value, targetCd: field('targetCd').value, itemCd: field('itemCd').value,
            installQty: field('installQty').value, keySpareYn: field('keySpareYn').value, useYn: field('useYn').value,
            remark: field('remark').value, rowVer: current ? current.rowVer : null};
    }

    /** 조회 조건(검색 패널) — grid 가 page·size·sort 를 붙인다 */
    function params() {
        var v = sp.values();
        var q = {};
        ['keyword', 'useYn', 'targetKind'].forEach(function (k) { if (v[k]) { q[k] = v[k]; } });
        return q;
    }

    /** 행 → 상세(서버에서 다시 읽는다) */
    function openRow(id) {
        return hb.latest('detail', hb.api(API + '/' + id)).then(function (r) {
            show(r.data);
            hb.page.openDetail();
        }, function (err) { notify(err.message, true); });
    }

    /** 목록 다시 읽기 — selectId 가 이 쪽에 있으면 grid 가 골라 상세를 연다 */
    function load(selectId) {
        return grid.reload(true, selectId || undefined).then(function () {
            var sel = grid.selected();
            if (selectId && (!sel || sel.spareId !== selectId)) { return openRow(selectId); }
            if (!selectId) { show(null); }
        });
    }

    ['newBtn', 'newBtnList'].forEach(function (id) {
        var b = document.getElementById(id);
        if (b) {
            b.addEventListener('click', function () {
                cmsUi.leaveOk().then(function (ok) {
                    if (!ok) { return; }
                    show(null);
                    fillTargets();
                    hb.page.openDetail();
                    field('targetCd').focus();
                });
            });
        }
    });

    var saveBtn = document.getElementById('saveBtn');
    if (saveBtn) {
        saveBtn.addEventListener('click', function () {
            var editing = !!current;
            hb.busy(saveBtn, function () {
                return editing ? hb.api(API + '/' + current.spareId, {method: 'PUT', body: body()}) : hb.api(API, {method: 'POST', body: body()});
            }).then(function (res) {
                if (!res) { return; }
                return load(editing ? current.spareId : res.data).then(function () { notify(hb.t('js.common.saved')); });
            }, function (err) {
                notify(err.message, true);
                hb.showFieldErrors(form, err.errors);
            });
        });
    }

    document.getElementById('usageBtn').addEventListener('click', function () { if (current) { loadUsage(); } });

    // 엑셀(C15) — 연결 표는 지금 조회 조건, 사용 이력은 고른 연결 + 기간
    var excelBtn = document.getElementById('excelBtn');
    if (excelBtn) {
        excelBtn.addEventListener('click', function () {
            hb.busy(/** @type {HTMLButtonElement} */ (excelBtn), function () { return hb.download(API + '/export', sp.values()); })
                .then(null, function (err) { notify(err.message, true); });
        });
    }
    var usageExcelBtn = document.getElementById('usageExcelBtn');
    if (usageExcelBtn) {
        usageExcelBtn.addEventListener('click', function () {
            if (!current) { return; }
            hb.showFieldErrors(usageForm, []);
            hb.busy(/** @type {HTMLButtonElement} */ (usageExcelBtn), function () { return hb.download(API + '/usage/export', usageQuery()); })
                .then(null, function (err) { notify(err.message, true); hb.showFieldErrors(usageForm, err.errors); });
        });
    }

    /** 대상 후보: 설비 = 찾기 API, 클래스 = 처음 받은 목록 */
    var targetTimer = null;
    function fillTargets() {
        var dl = document.getElementById('targetList');
        if (field('targetKind').value === 'CLASS') {
            dl.textContent = '';
            classes.forEach(function (c) {
                var o = hb.el('option', null, c.name);
                o.value = c.code;
                dl.appendChild(o);
            });
            return;
        }
        clearTimeout(targetTimer);
        targetTimer = setTimeout(function () {
            hb.latest('targets', hb.api(API + '/targets?keyword=' + encodeURIComponent(field('targetCd').value))).then(function (res) {
                dl.textContent = '';
                res.data.forEach(function (t) {
                    var o = hb.el('option', null, t.name + (t.classNm ? ' (' + t.classNm + ')' : ''));
                    o.value = t.code;
                    dl.appendChild(o);
                });
            }, function () { /* 후보는 보조 */ });
        }, 250);
    }
    field('targetCd').addEventListener('input', fillTargets);
    field('targetKind').addEventListener('change', function () {
        field('targetCd').value = '';
        fillTargets();
    });

    var itemTimer = null;
    field('itemCd').addEventListener('input', function () {
        clearTimeout(itemTimer);
        itemTimer = setTimeout(function () {
            hb.latest('items', hb.api(API + '/items?keyword=' + encodeURIComponent(field('itemCd').value))).then(function (res) {
                var dl = document.getElementById('itemList');
                dl.textContent = '';
                res.data.forEach(function (i) {
                    var o = hb.el('option', null, i.itemNm + (i.spec ? ' ' + i.spec : '') + ' (' + i.unitCd + ')');
                    o.value = i.itemCd;
                    dl.appendChild(o);
                });
            }, function () { /* 후보는 보조 */ });
        }, 250);
    });

    if (!CAN_SAVE) {
        form.querySelectorAll('input, select').forEach(function (i) { i.disabled = true; });
    }
    hb.dirty.watch(form);
    hb.loadMessages().then(function () {
        hb.page.init();
        sp = hb.searchPanel(search, {onSearch: function () {
            return cmsUi.leaveOk().then(function (ok) { return ok ? load() : null; });
        }});
        return Promise.all([hb.loadSession(), hb.api(API + '/classes')]);
    }).then(function (r) {
        classes = r[1].data;
        var today = hb.dates.todayIn(hb.session().timeZone);
        ufield('to').value = today;
        ufield('from').value = hb.dates.add(today, -365);
        grid = hb.grid('#spareGrid', {
            progCd: 'cms-spare', id: 'main', key: 'spareId',
            url: API + '/grid', columnsUrl: API + '/columns', exportUrl: API + '/export', actions: ACTIONS, params: params,
            filters: {targetKind: 'targetKind', useYn: 'useYn'},
            onFilter: function (param, value) { sp.setValue(param, value); },
            beforeSelect: function () { return cmsUi.leaveOk(); },
            status: {useYn: {Y: 'normal', N: 'neutral'}, keySpareYn: {Y: 'info'}},   // 사용 여부·핵심 예비품 = 배지
            rowClass: function (row) { return row.useYn === 'N' ? 'hb-tree-dim' : null; },
            onSelect: function (row) { openRow(row.spareId); },
            notify: notify
        });
        return grid.ready.then(function () { show(null); });   // grid 가 첫 쪽을 이미 읽었다 — 다시 읽지 않는다
    }).then(null, function (err) { notify(err && err.message, true); });
})();
