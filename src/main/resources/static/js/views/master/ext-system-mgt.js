// @ts-check
/* 외부 시스템·코드 매핑 (mst-ext-system) — P1 7a (docs/22). 시스템 hb.grid(UI 단계 3 ①) + 상세 폼 + 코드 매핑 표(추가·사용 중지 — 고른 시스템의 상세라 표 유지) */
/**
 * @typedef {{systemId: number, systemCd: string, systemNm: string, systemType: string, custCd?: string | null,
 *   custNm?: string | null, mapCnt?: number, codeMapCnt?: number, useYn: string, remark?: string | null, rowVer: number,
 *   [field: string]: any}} ExtSystemRow
 * @typedef {{codeMapId: number, codeType: string, extCode: string, intCode: string, useYn: string, rowVer: number}} ExtCodeMap
 * @typedef {HTMLInputElement | HTMLSelectElement} ExtSystemField
 */
(function () {
    'use strict';

    var API = '/api/master/ext-systems';
    var ACTIONS = (document.body.getAttribute('data-actions') || '').split(',');
    var CAN_SAVE = ACTIONS.indexOf('SAVE') >= 0;
    var msg = /** @type {HTMLElement} */ (document.getElementById('msg'));
    var form = /** @type {HTMLFormElement} */ (document.getElementById('systemForm'));
    var PROG = document.body.getAttribute('data-prog') || '';
    var cbody = /** @type {HTMLTableSectionElement} */ (document.querySelector('#codeMaps tbody'));
    var cform = /** @type {HTMLFormElement | null} */ (document.getElementById('codeForm'));   // SAVE 권한 없으면 없음
    var formMode = /** @type {HTMLElement} */ (document.getElementById('formMode'));
    /** 선택된 시스템 @type {ExtSystemRow | null} */
    var current = null;
    /** @type {HB.SelectControl} */
    var type;
    /** 코드 매핑 유형(cform 이 있을 때만) @type {HB.SelectControl | undefined} */
    var codeType;
    /** @type {Record<string, string>} */
    var codeTypeNames = {};
    /** @type {HB.Grid | undefined} */
    var grid;

    /** 사용 여부 칸을 hb-status 배지로(docs/40 ⑤) — 방금 붙인 칸 @param {HTMLElement} tr @param {string} useYn */
    function ynCell(tr, useYn) {
        var td = /** @type {HTMLElement} */ (tr.lastElementChild);
        td.textContent = '';
        td.appendChild(hb.status(useYn === 'Y' ? 'normal' : 'neutral', useYn === 'Y' ? hb.t('js.yn.Y') : hb.t('js.yn.N')));
    }

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
    function field(n) { return /** @type {ExtSystemField} */ (form.querySelector('[data-field="' + n + '"]')); }

    /** 코드 매핑 폼 칸 @param {HTMLFormElement} f @param {string} n */
    function cfield(f, n) { return /** @type {HTMLInputElement} */ (f.querySelector('[data-field="' + n + '"]')); }

    /** @param {ExtSystemRow | null} s */
    function show(s) {
        current = s;
        hb.showFieldErrors(form, []);
        ['systemCd', 'systemNm', 'custCd', 'remark'].forEach(function (n) { field(n).value = s ? (s[n] || '') : ''; });
        type.setValue(s ? s.systemType : '');
        field('useYn').value = s ? s.useYn : 'Y';
        field('connMode').value = s && s.connMode ? s.connMode : 'DIRECT';
        /** @type {HTMLInputElement} */ (field('systemCd')).readOnly = !!s;
        formMode.textContent = s ? hb.t('js.extSys.editing', s.systemCd) : hb.t('js.extSys.creating');
        /** @type {HTMLElement} */ (document.getElementById('codeSection')).hidden = !s;
        if (cform) { hb.showFieldErrors(cform, []); }
        hb.dirty.clear();
        if (s) { loadCodes(); }
    }

    /** 시스템 목록 다시 읽기(매핑 수가 바뀐다) — selectId 가 이 페이지에 있으면 다시 골라 onSelect 가 상세·매핑을 채운다
     * @param {number} [selectId] */
    function load(selectId) {
        return grid ? grid.reload(false, selectId) : Promise.resolve();
    }

    function loadCodes() {
        if (!current) { return; }
        var systemId = current.systemId;   // 사용 중지 처리 중에 다른 시스템을 골라도 이 시스템으로 마무리
        return hb.latest('codes', hb.api(API + '/' + systemId + '/code-maps')).then(function (res) {
            /** @type {ExtCodeMap[]} */
            var list = res.data;
            cbody.textContent = '';
            if (!list.length) {
                var tr = hb.el('tr');
                var td = hb.el('td', 'hb-muted', hb.t('js.extSys.noCode'));
                td.colSpan = 5;
                tr.appendChild(td);
                cbody.appendChild(tr);
            }
            list.forEach(function (c) {
                var tr = hb.el('tr', c.useYn === 'Y' ? null : 'hb-tree-dim');
                [codeTypeNames[c.codeType] || c.codeType, c.extCode, c.intCode, c.useYn === 'Y' ? hb.t('js.yn.Y') : hb.t('js.yn.N')]
                    .forEach(function (v, i) { tr.appendChild(hb.el('td', i === 1 || i === 2 ? 'hb-wrap' : null, v)); });
                ynCell(tr, c.useYn);
                var td = hb.el('td');
                if (CAN_SAVE) {
                    var b = hb.el('button', 'hb-btn-secondary hb-btn-small', c.useYn === 'Y' ? hb.t('js.extSys.disable') : hb.t('js.extSys.enable'));
                    b.type = 'button';
                    b.addEventListener('click', function () {
                        hb.busy(b, function () {
                            return hb.api(API + '/code-maps/' + c.codeMapId, {method: 'PUT',
                                body: {extCode: c.extCode, intCode: c.intCode, useYn: c.useYn === 'Y' ? 'N' : 'Y', rowVer: c.rowVer}});
                        }).then(function (res) {
                            if (!res) { return; }
                            return load(systemId).then(function () { notify(hb.t('js.common.saved')); });
                        }, function (err) { notify(err.message, true); });
                    });
                    td.appendChild(b);
                }
                tr.appendChild(td);
                cbody.appendChild(tr);
            });
        }, function (err) { notify(err.message, true); });
    }

    function newSystem() {
        confirmLeave().then(function (ok) {
            if (!ok) { return; }
            show(null);
            hb.page.openDetail();
            field('systemCd').focus();
        });
    }

    /** 버튼(권한에 따라 없을 수 있음)에 클릭 연결 @param {string} id @param {(b: HTMLButtonElement) => void} fn */
    function bind(id, fn) {
        var b = /** @type {HTMLButtonElement | null} */ (document.getElementById(id));
        if (b) {
            var btn = b;
            btn.addEventListener('click', function () { fn(btn); });
        }
    }
    bind('newBtn', newSystem);
    bind('newBtnList', newSystem);

    bind('saveBtn', function (saveBtn) {
        var body = {systemCd: field('systemCd').value, systemNm: field('systemNm').value, systemType: type.value(),
            custCd: field('custCd').value, useYn: field('useYn').value, connMode: field('connMode').value, remark: field('remark').value,
            rowVer: current ? current.rowVer : null};
        var editId = current ? current.systemId : null;   // 저장 중에 다른 행·신규를 골라도 이 시스템으로 마무리
        hb.busy(saveBtn, function () {
            return editId !== null ? hb.api(API + '/' + editId, {method: 'PUT', body: body})
                : hb.api(API, {method: 'POST', body: body});
        }).then(function (res) {
            if (!res) { return; }
            return load(editId !== null ? editId : res.data).then(function () { notify(hb.t('js.common.saved')); });
        }, function (err) {
            notify(err.message, true);
            hb.showFieldErrors(form, err.errors);
        });
    });
    if (cform) {
        var cf = cform;
        var addCodeBtn = /** @type {HTMLButtonElement} */ (document.getElementById('addCodeBtn'));
        cf.addEventListener('submit', function (e) {
            e.preventDefault();
            if (!current || !codeType) { return; }
            var systemId = current.systemId;   // 추가 중에 다른 시스템을 골라도 이 시스템으로 마무리
            var body = {codeType: codeType.value(), extCode: cfield(cf, 'extCode').value, intCode: cfield(cf, 'intCode').value};
            hb.busy(addCodeBtn, function () {
                return hb.api(API + '/' + systemId + '/code-maps', {method: 'POST', body: body});
            }).then(function (res) {
                if (!res) { return; }
                hb.dirty.clear(cf);
                cfield(cf, 'extCode').value = '';
                cfield(cf, 'intCode').value = '';
                hb.showFieldErrors(cf, []);
                return load(systemId).then(function () {
                    notify(hb.t('js.common.saved'));
                    cfield(cf, 'extCode').focus();
                });
            }, function (err) {
                notify(err.message, true);
                hb.showFieldErrors(cf, err.errors);
            });
        });
    }
    if (!CAN_SAVE) {
        form.querySelectorAll('input, select').forEach(function (i) { /** @type {ExtSystemField} */ (i).disabled = true; });
    }

    hb.dirty.watch(form);
    if (cform) { hb.dirty.watch(cform); }      // 코드 매핑 입력도 이탈 확인 대상(7단계 QA 결함 #11)
    hb.loadMessages().then(function () {
        hb.page.init();
        type = hb.select(/** @type {HTMLSelectElement} */ (field('systemType')));
        if (cform) {
            codeType = hb.select(/** @type {HTMLSelectElement} */ (cform.querySelector('[data-field="codeType"]')));
        }
        return Promise.all([hb.loadSession(), type.ready, hb.codes('IF_CODE_TYPE'), codeType ? codeType.ready : null]);
    }).then(function (r) {
        codeTypeNames = r[2];
        show(null);
        grid = hb.grid('#systemGrid', {
            progCd: PROG, id: 'main', key: 'systemId',
            url: API + '/grid', columnsUrl: API + '/columns', exportUrl: API + '/export',
            actions: ACTIONS,
            beforeSelect: function () { return confirmLeave(); },
            status: {useYn: {Y: 'normal', N: 'neutral'}},                                   // 사용 여부 = 상태 배지
            rowClass: function (row) { return row.useYn === 'N' ? 'hb-tree-dim' : null; },   // 미사용 행 흐리게
            onSelect: function (row) { show(/** @type {ExtSystemRow} */ (row)); hb.page.openDetail(); },
            notify: notify
        });
        return grid.ready;
    }).then(null, function (err) { notify(err && err.message, true); });
})();
