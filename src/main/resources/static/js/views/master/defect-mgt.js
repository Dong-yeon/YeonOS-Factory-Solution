// @ts-check
/* 불량 유형 (mst-defect) — P1 6단계 (docs/21). hb.grid 목록(UI 단계 3 ①, 빠른 찾기·페이지) + 상세 폼. 공정 관리와 같은 구조 */
/**
 * @typedef {{defectId: number, defectCd: string, defectNm: string, catCd: string, sortNo: number, useYn: string,
 *   remark?: string, rowVer: number, [field: string]: any}} DefectRow
 */
(function () {
    'use strict';

    var API = '/api/master/defects';
    var ACTIONS = (document.body.getAttribute('data-actions') || '').split(',');
    var msg = /** @type {HTMLElement} */ (document.getElementById('msg'));
    var form = /** @type {HTMLFormElement} */ (document.getElementById('defectForm'));
    var formMode = /** @type {HTMLElement} */ (document.getElementById('formMode'));
    var PROG = document.body.getAttribute('data-prog') || '';
    var search = /** @type {HTMLFormElement} */ (document.getElementById('search'));
    /** 고른 불량 유형 @type {DefectRow | null} */
    var current = null;
    /** 준비(문구 로드) 뒤에 만든다 @type {HB.NumberControl} */
    var sortNo;
    /** @type {HB.SelectControl} */
    var cat;
    /** 조회 조건 패널 @type {HB.SearchPanel} */
    var sp;
    /** @type {HB.Grid | undefined} */
    var grid;

    /** 미저장 입력이 있으면 화면 안 대화상자로 묻는다(docs/40 V-7) — 없으면 바로 true @returns {Promise<boolean>} */
    function confirmLeave() {
        if (!hb.dirty.isDirty()) { return Promise.resolve(true); }
        return hb.dialog.confirm({title: hb.t('js.common.leaveTitle'), message: hb.t('js.common.confirmLeave'), ok: hb.t('js.common.leaveOk')});
    }

    /** @param {string | null | undefined} text @param {boolean} [error] */
    function notify(text, error) {
        msg.textContent = text || '';
        msg.classList.toggle('hb-msg-error', !!error);
    }

    /** 상세 폼 칸 @param {string} n */
    function field(n) {
        return /** @type {HTMLInputElement | HTMLSelectElement} */ (form.querySelector('[data-field="' + n + '"]'));
    }

    /** @param {DefectRow | null} d */
    function show(d) {
        current = d;
        hb.showFieldErrors(form, []);
        ['defectCd', 'defectNm', 'remark'].forEach(function (n) { field(n).value = d ? (d[n] || '') : ''; });
        cat.setValue(d ? d.catCd : '');
        sortNo.setValue(d ? d.sortNo : 0);
        field('useYn').value = d ? d.useYn : 'Y';
        /** @type {HTMLInputElement} */ (field('defectCd')).readOnly = !!d;
        formMode.textContent = d ? hb.t('js.defect.editing', d.defectCd) : hb.t('js.defect.creating');
        hb.dirty.clear();
    }

    /** 조회 조건 — 사용 중지 포함을 안 고르면 사용 중인 것만(기존 목록 API 와 같은 조건) */
    function params() {
        var v = sp.values();
        return {keyword: v.keyword, useYn: v.withUnused && v.withUnused.length ? '' : 'Y'};
    }

    /** 목록 다시 읽기 — keepId 가 이 페이지에 있으면 다시 골라 onSelect 가 상세를 채운다 @param {number} [keepId] */
    function reloadGrid(keepId) {
        return grid ? grid.reload(false, keepId) : Promise.resolve();
    }

    function newDefect() {
        if (!cat) { return; }   // 준비 전
        confirmLeave().then(function (ok) {
            if (!ok) { return; }
            var keepCat = current ? current.catCd : cat.value();
            show(null);
            cat.setValue(keepCat);       // 같은 분류를 연달아 넣는 경우가 많다
            hb.page.openDetail();
            field(keepCat ? 'defectCd' : 'catCd').focus();
        });
    }
    ['newBtn', 'newBtnList'].forEach(function (id) {
        var b = document.getElementById(id);
        if (b) { b.addEventListener('click', newDefect); }
    });

    var saveBtn = /** @type {HTMLButtonElement | null} */ (document.getElementById('saveBtn'));   // SAVE 권한 없으면 없음
    if (saveBtn) {
        var btn = saveBtn;
        btn.addEventListener('click', function () {
            if (!cat) { return; }   // 준비 전
            var body = {defectCd: field('defectCd').value, defectNm: field('defectNm').value, catCd: cat.value(),
                sortNo: sortNo.numberValue(), useYn: field('useYn').value, remark: field('remark').value,
                rowVer: current ? current.rowVer : null};
            var editId = current ? current.defectId : null;   // 저장 중에 다른 행·신규를 골라도 이 불량 유형으로 마무리
            hb.busy(btn, function () {
                return editId !== null ? hb.api(API + '/' + editId, {method: 'PUT', body: body})
                    : hb.api(API, {method: 'POST', body: body});
            }).then(function (res) {
                if (!res) { return; }
                // 그리드가 같은 행을 다시 고르면 onSelect 가 상세를 다시 채운다(그때 미저장 표시도 지움) → 다 읽은 뒤 알린다
                return reloadGrid(editId !== null ? editId : res.data).then(function () { notify(hb.t('js.common.saved')); });
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
    search.addEventListener('submit', function (e) { e.preventDefault(); });   // 패널이 준비되기 전 Enter 로 화면이 다시 읽히지 않게
    hb.loadMessages().then(function () {
        hb.page.init();
        sortNo = hb.number(/** @type {HTMLInputElement} */ (field('sortNo')));
        cat = hb.select(/** @type {HTMLSelectElement} */ (field('catCd')));
        sp = hb.searchPanel(search, {
            onSearch: function () {
                if (!grid) { return null; }
                var g = grid;
                return confirmLeave().then(function (ok) { return ok ? g.reload(true) : null; });
            }
        });
        return Promise.all([hb.loadSession(), cat.ready, sp.ready]);
    }).then(function () {
        show(null);
        grid = hb.grid('#defectGrid', {
            progCd: PROG, id: 'main', key: 'defectId',
            url: API + '/grid', columnsUrl: API + '/columns', exportUrl: API + '/export',
            actions: ACTIONS, params: params,
            beforeSelect: function () { return confirmLeave(); },
            status: {useYn: {Y: 'normal', N: 'neutral'}},                                   // 사용 여부 = 상태 배지
            rowClass: function (row) { return row.useYn === 'N' ? 'hb-tree-dim' : null; },   // 미사용 행 흐리게
            onSelect: function (row) { show(/** @type {DefectRow} */ (row)); hb.page.openDetail(); },
            notify: notify
        });
        return grid.ready;
    }).then(null, function (err) { notify(err && err.message, true); });
})();
