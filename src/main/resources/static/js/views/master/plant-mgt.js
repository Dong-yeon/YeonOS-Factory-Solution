// @ts-check
/* 공장 관리 (mst-plant) — P1 4a. hb.grid 목록(UI 단계 3 ①) + 상세 폼, 저장 = 신규·수정(사용 중지 포함) */
/**
 * @typedef {{plantCd: string, plantNm: string, addr?: string | null, timeZone?: string | null, sortNo: number,
 *   useYn: string, nodeCnt: number, rowVer: number, [field: string]: any}} MstPlant
 */
(function () {
    'use strict';

    var API = '/api/master/plants';
    var ACTIONS = (document.body.getAttribute('data-actions') || '').split(',');
    var msg = /** @type {HTMLElement} */ (document.getElementById('msg'));
    var form = /** @type {HTMLFormElement} */ (document.getElementById('plantForm'));
    var PROG = document.body.getAttribute('data-prog') || '';
    var formMode = /** @type {HTMLElement} */ (document.getElementById('formMode'));
    /** 선택된 공장 @type {MstPlant | null} */
    var current = null;
    /** 순서 칸(메시지를 읽은 뒤 만든다) @type {HB.NumberControl} */
    var sortNo;
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

    /** @param {MstPlant | null} p */
    function show(p) {
        current = p;
        hb.showFieldErrors(form, []);
        ['plantCd', 'plantNm', 'addr', 'timeZone'].forEach(function (n) { field(n).value = p ? (p[n] || '') : ''; });
        sortNo.setValue(p ? p.sortNo : 0);
        field('useYn').value = p ? p.useYn : 'Y';
        /** @type {HTMLInputElement} */ (field('plantCd')).readOnly = !!p;
        formMode.textContent = p ? hb.t('js.plant.editing', p.plantCd) : hb.t('js.plant.creating');
        hb.dirty.clear();
    }

    /** 목록 다시 읽기 — selectCd 가 이 페이지에 있으면 다시 골라 onSelect 가 폼을 채운다 @param {string} [selectCd] */
    function load(selectCd) {
        return grid ? grid.reload(false, selectCd) : Promise.resolve();
    }

    /** 신규 — 미저장 입력이 있으면 먼저 묻는다(예전엔 고친 내용이 말없이 지워졌다) */
    function newPlant() {
        confirmLeave().then(function (ok) {
            if (!ok) { return; }
            show(null);
            hb.page.openDetail();
            field('plantCd').focus();
        });
    }
    ['newBtn', 'newBtnList'].forEach(function (id) {
        var b = document.getElementById(id);
        if (b) { b.addEventListener('click', newPlant); }
    });
    var saveBtn = /** @type {HTMLButtonElement | null} */ (document.getElementById('saveBtn'));   // SAVE 권한 없으면 없음
    if (saveBtn) {
        var btn = saveBtn;
        btn.addEventListener('click', function () {
            var body = {plantCd: field('plantCd').value, plantNm: field('plantNm').value, addr: field('addr').value,
                timeZone: field('timeZone').value, sortNo: sortNo.numberValue(), useYn: field('useYn').value,
                rowVer: current ? current.rowVer : null};
            var editCd = current ? current.plantCd : null;   // 저장 중에 다른 공장을 골라도 이 공장으로 마무리
            hb.busy(btn, function () {
                return editCd !== null ? hb.api(API + '/' + encodeURIComponent(editCd), {method: 'PUT', body: body})
                    : hb.api(API, {method: 'POST', body: body});
            }).then(function (res) {
                if (!res) { return; }
                return load(editCd !== null ? editCd : res.data).then(function () { notify(hb.t('js.common.saved')); });
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
    hb.loadMessages().then(function () {
        hb.page.init();
        sortNo = hb.number(/** @type {HTMLInputElement} */ (field('sortNo')));
        return hb.loadSession();
    }).then(function () {
        show(null);
        grid = hb.grid('#plantGrid', {
            progCd: PROG, id: 'main', key: 'plantCd',
            url: API + '/grid', columnsUrl: API + '/columns', exportUrl: API + '/export',
            actions: ACTIONS,
            beforeSelect: function () { return confirmLeave(); },
            status: {useYn: {Y: 'normal', N: 'neutral'}},                                   // 사용 여부 = 상태 배지
            rowClass: function (row) { return row.useYn === 'N' ? 'hb-tree-dim' : null; },   // 미사용 행 흐리게
            onSelect: function (row) { show(/** @type {MstPlant} */ (row)); hb.page.openDetail(); },
            notify: notify
        });
        return grid.ready;
    }).then(null, function (err) { notify(err && err.message, true); });
})();
