// @ts-check
/*
 * 회사 공통코드 (sys-code) — 시스템관리 2단계 (docs/36 9.3). 그룹 선택 → 코드 hb.grid(UI 단계 3 ①, docs/36 17.1) → 오른쪽 회사 코드 폼.
 * 시스템 코드는 이름·순서를 고칠 수 없다. 숨길 수 있는 시스템 코드(hideable)는 사용 여부만 — 이 회사 선택 목록에서
 * 숨기기·되살리기(docs/68 Q4, 이미 저장한 행은 그대로). 분기에 쓰이는 시스템 코드는 읽기 전용(저장 버튼 숨김).
 * UNIT 그룹만 소수 자릿수 칸(늘리기만 — 서버 판정). 시스템 코드와 같은 코드는 서버·DB 트리거가 막는다.
 */
/** @typedef {{code: string, name: string, sortNo: number, useYn: string, system: boolean, scale: number | null, rowVer: number, hideable: boolean}} SysCompCode */
(function () {
    'use strict';

    var API = '/api/admin/codes';
    var ACTIONS = (document.body.getAttribute('data-actions') || '').split(',');
    var CAN_SAVE = ACTIONS.indexOf('SAVE') >= 0;
    var msg = /** @type {HTMLElement} */ (document.getElementById('msg'));
    var form = /** @type {HTMLFormElement} */ (document.getElementById('codeForm'));
    var formMode = /** @type {HTMLElement} */ (document.getElementById('formMode'));
    /** @type {HB.Grid | null} */
    var grid = null;
    var grpSel = /** @type {HTMLSelectElement} */ (document.getElementById('grpSel'));
    var scaleRow = /** @type {HTMLElement} */ (document.getElementById('scaleRow'));
    /** @type {HB.NumberControl} */
    var sortNo;
    /** @type {HB.NumberControl} */
    var scale;
    /** @type {SysCompCode | null} */
    var current = null;
    /** 그룹별 회사 코드 최대 길이(docs/68) @type {Object<string, number>} */
    var maxLens = {};
    var ready = false;
    var saveBtnEl = /** @type {HTMLButtonElement | null} */ (document.getElementById('saveBtn'));

    /** @param {string | null | undefined} text @param {boolean} [error] */
    function notify(text, error) {
        msg.textContent = text || '';
        msg.classList.toggle('hb-msg-error', !!error);
    }

    /** @param {string} n */
    function field(n) {
        return /** @type {HTMLInputElement | HTMLSelectElement} */ (form.querySelector('[data-field="' + n + '"]'));
    }

    /** @param {SysCompCode | null} c */
    function show(c) {
        current = c;
        hb.showFieldErrors(form, []);
        var unit = grpSel.value === 'UNIT';
        var editable = CAN_SAVE && !(c && c.system);   // 시스템 코드는 이름·순서 읽기 전용
        var shownOnly = CAN_SAVE && !!(c && c.system && c.hideable);   // 숨길 수 있는 시스템 코드 — 사용 여부만
        scaleRow.hidden = !unit;
        field('code').value = c ? c.code : '';
        /** @type {HTMLInputElement} */ (field('code')).maxLength = maxLens[grpSel.value] || 30;
        /** @type {HTMLInputElement} */ (field('code')).readOnly = !!c || !CAN_SAVE;
        field('codeNm').value = c ? c.name : '';
        /** @type {HTMLInputElement} */ (field('codeNm')).readOnly = !editable;
        sortNo.setValue(c ? c.sortNo : 0);
        scale.setValue(c && c.scale !== null ? c.scale : (unit ? 0 : ''));
        field('useYn').value = c ? c.useYn : 'Y';
        field('useYn').disabled = !editable && !shownOnly;
        /** @type {HTMLInputElement} */ (field('sortNo')).readOnly = !editable;
        /** @type {HTMLInputElement} */ (field('scale')).readOnly = !editable;
        if (saveBtnEl) { saveBtnEl.hidden = !editable && !shownOnly; }
        formMode.textContent = !c ? hb.t('js.sysCode.creating', grpSel.value)
            : (c.system ? hb.t(shownOnly ? 'js.sysCode.systemHideable' : 'js.sysCode.systemReadOnly', c.code)
                : hb.t('js.sysCode.editing', c.code));
        hb.dirty.clear();
    }

    /** 그룹을 바꾸면 첫 쪽부터, 저장 뒤에는 그 코드를 다시 고른다(그리드가 onSelect 로 폼을 채움) @param {string} [selectCode] */
    function load(selectCode) {
        notify('');
        if (!selectCode) { show(null); }
        return grid ? grid.reload(!selectCode, selectCode) : Promise.resolve();
    }

    var shownGrp = '';   // 지금 표에 보이는 그룹 — 이동을 취소하면 고르기 칸을 되돌린다
    grpSel.addEventListener('change', function () {
        sysUi.leaveOk().then(function (ok) {
            if (!ok) { grpSel.value = shownGrp; return; }
            shownGrp = grpSel.value;
            load();
        });
    });
    /** @type {HTMLFormElement} */ (document.getElementById('search')).addEventListener('submit', function (e) { e.preventDefault(); });

    var newBtn = document.getElementById('newBtn');
    if (newBtn) {
        newBtn.addEventListener('click', function () {
            if (!ready) { return; }
            sysUi.leaveOk().then(function (ok) {
                if (!ok) { return; }
                show(null);
                hb.page.openDetail();
                field('code').focus();
            });
        });
    }
    if (saveBtnEl) {
        var saveBtn = saveBtnEl;
        saveBtn.addEventListener('click', function () {
            if (!ready) { return; }
            var grp = grpSel.value;
            var edit = current;   // 저장 중 다른 코드를 골라도 이 코드로 마무리
            var body = {code: field('code').value, codeNm: field('codeNm').value, sortNo: sortNo.numberValue(),
                useYn: field('useYn').value, scale: grp === 'UNIT' ? scale.numberValue() : null,
                rowVer: edit ? edit.rowVer : null};
            hb.busy(saveBtn, function () {
                if (edit && edit.system) {   // 시스템 코드 = 이 회사 목록에서 숨기기·되살리기만
                    return hb.api(API + '/' + encodeURIComponent(grp) + '/' + encodeURIComponent(edit.code) + '/shown',
                        {method: 'PUT', body: {useYn: field('useYn').value}});
                }
                return edit ? hb.api(API + '/' + encodeURIComponent(grp) + '/' + encodeURIComponent(edit.code), {method: 'PUT', body: body})
                    : hb.api(API + '/' + encodeURIComponent(grp), {method: 'POST', body: body});
            }).then(function (res) {
                if (!res) { return; }
                hb.dirty.clear();
                return load(edit ? edit.code : String(body.code).trim().toUpperCase()).then(function () {
                    if (grid && !grid.selected()) { show(null); }   // 이 쪽 밖으로 간 코드(정렬 순서) — 폼은 비운다
                    notify(hb.t('js.common.saved'));
                });
            }, function (err) {
                notify(err.message, true);
                hb.showFieldErrors(form, err.errors);
            });
        });
    }

    hb.dirty.watch(form);
    hb.loadMessages().then(function () {
        hb.page.init();
        sortNo = hb.number(/** @type {HTMLInputElement} */ (field('sortNo')));
        scale = hb.number(/** @type {HTMLInputElement} */ (field('scale')));
        return Promise.all([hb.loadSession(), hb.api(API)]);
    }).then(function (r) {
        /** @type {{grpCd: string, name: string, maxLen: number}[]} */ (r[1].list || []).forEach(function (g) {
            maxLens[g.grpCd] = g.maxLen;
            var o = hb.el('option', null, g.name + ' (' + g.grpCd + ')');
            o.value = g.grpCd;
            grpSel.appendChild(o);
        });
        shownGrp = grpSel.value;
        if (!grpSel.value) { notify(hb.t('js.sysCode.noGroups'), true); return null; }
        show(null);
        grid = hb.grid('#codeGrid', {
            progCd: 'sys-code', id: 'main', key: 'code',
            url: API + '/grid', columnsUrl: API + '/columns', exportUrl: API + '/export', actions: ACTIONS,
            params: function () { return {grpCd: grpSel.value}; },
            format: {system: function (v) { return v ? hb.t('js.sysCode.system') : hb.t('js.sysCode.company'); }},
            status: {useYn: sysUi.TONES.use},
            rowClass: function (row) { return row.useYn === 'N' ? 'hb-tree-dim' : null; },
            beforeSelect: sysUi.beforeSelect,
            onSelect: function (row) { show(/** @type {SysCompCode} */ (/** @type {unknown} */ (row))); hb.page.openDetail(); },
            notify: notify
        });
        return grid.ready.then(function () { ready = true; });
    }).then(null, function (err) { notify(err && err.message, true); });
})();
