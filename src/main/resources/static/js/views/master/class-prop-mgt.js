// @ts-check
/*
 * 클래스 속성 (mst-class-prop) — P1 7b (docs/22 5b). 왼쪽 클래스 hb.grid(UI 단계 3 ①, 유형 세그먼트: 품목·설비) + 품목 클래스 폼,
 * 오른쪽 고른 클래스의 속성 정의 표(고른 클래스의 상세라 표 유지) + 폼. 형식에 따라 자릿수(NUM)·선택지(LIST) 칸만 보인다. 코드·형식은 만든 뒤 고정.
 */
/**
 * @typedef {{classId: number, classCd: string, classNm: string, propCnt: number, targetCnt: number, useYn: string,
 *   sortNo: number, rowVer: number, [field: string]: any}} ClassPropClass
 * @typedef {{propId: number, propCd: string, propNm: string, dataType: string, scale: number | null, unitNm?: string | null,
 *   options?: string[] | null, requiredYn: string, semanticId?: string | null, sortNo: number, useYn: string, rowVer: number,
 *   [field: string]: any}} ClassPropDef
 */
(function () {
    'use strict';

    var API = '/api/master/class-props';
    var ACTIONS = (document.body.getAttribute('data-actions') || '').split(',');
    var CAN_SAVE = ACTIONS.indexOf('SAVE') >= 0;
    var msg = /** @type {HTMLElement} */ (document.getElementById('msg'));
    var PROG = document.body.getAttribute('data-prog') || '';
    var pbody = /** @type {HTMLElement} */ (document.querySelector('#props tbody'));
    var cform = /** @type {HTMLFormElement | null} */ (document.getElementById('classForm'));   // SAVE 권한 없으면 없음
    var pform = /** @type {HTMLFormElement} */ (document.getElementById('propForm'));
    var formMode = /** @type {HTMLElement} */ (document.getElementById('formMode'));
    var classType = 'ITEM';
    /** 고른 클래스 @type {ClassPropClass | null} */
    var curClass = null;
    /** 고른 속성 @type {ClassPropDef | null} */
    var curProp = null;
    /** @type {Record<string, string>} */
    var typeNames = {};
    /** 준비(문구 로드) 뒤에 만든다 — 클래스를 고르기 전에는 쓰지 않는다 @type {HB.SelectControl} */
    var dataType;
    /** @type {HB.NumberControl} */
    var scale;
    /** @type {HB.NumberControl} */
    var sortNo;
    var nextSort = 10;
    /** @type {HB.Grid | undefined} */
    var grid;

    /** @param {string | null | undefined} text @param {boolean} [error] */
    function notify(text, error) {
        msg.textContent = text || '';
        msg.classList.toggle('hb-msg-error', !!error);
    }

    /** 속성 폼 칸 @param {string} n */
    function pf(n) {
        return /** @type {HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement} */ (pform.querySelector('[data-field="' + n + '"]'));
    }
    /** 클래스 폼 칸 — classForm 이 있을 때(SAVE 권한)만 부른다 @param {string} n */
    function cf(n) {
        return /** @type {HTMLInputElement | HTMLSelectElement} */ (/** @type {HTMLFormElement} */ (cform).querySelector('[data-field="' + n + '"]'));
    }

    /** 사용 여부 배지 hb-status(docs/40 ⑤) — 글자는 그대로 @param {string} v */
    function yn(v) { return hb.status(v === 'Y' ? 'normal' : 'neutral', v === 'Y' ? hb.t('js.yn.Y') : hb.t('js.yn.N')); }
    /** @param {string} v */
    function yesNo(v) { return v === 'Y' ? hb.t('js.attrs.yes') : hb.t('js.attrs.no'); }

    /** @param {HTMLElement} tbody @param {(string | HTMLElement)[]} cells @param {boolean} dim @param {boolean} selected @param {() => void} pick */
    function row(tbody, cells, dim, selected, pick) {
        var tr = hb.el('tr', dim ? 'hb-row-pick hb-tree-dim' : 'hb-row-pick');
        tr.tabIndex = 0;
        tr.setAttribute('aria-selected', selected ? 'true' : 'false');
        cells.forEach(function (v) {
            var td = hb.el('td', null, typeof v === 'string' ? v : null);
            if (typeof v !== 'string') { td.appendChild(v); }
            tr.appendChild(td);
        });
        tr.addEventListener('click', pick);
        tr.addEventListener('keydown', function (e) { if (e.key === 'Enter') { pick(); } });
        tbody.appendChild(tr);
    }

    /** @param {HTMLElement} tbody @param {string} text @param {number} cols */
    function empty(tbody, text, cols) {
        var tr = hb.el('tr');
        var td = hb.el('td', 'hb-muted', text);
        td.colSpan = cols;
        tr.appendChild(td);
        tbody.appendChild(tr);
    }

    // ---------- 클래스 ----------
    /** 미저장 입력(속성 폼)이 있으면 화면 안 대화상자로 묻는다(docs/40 V-7) — 없으면 바로 true @returns {Promise<boolean>} */
    function confirmLeave() {
        if (!hb.dirty.isDirty()) { return Promise.resolve(true); }
        return hb.dialog.confirm({title: hb.t('js.common.leaveTitle'), message: hb.t('js.common.confirmLeave'), ok: hb.t('js.common.leaveOk')});
    }

    /** 클래스 목록 다시 읽기(속성·대상 수가 바뀐다) — keepId 가 이 페이지에 있으면 다시 골라 onSelect 가 속성을 채운다.
     * 없으면(유형을 바꿈) 첫 쪽부터, 고른 클래스 없음 @param {number | null} keepId */
    function loadClasses(keepId) {
        if (!grid) { return Promise.resolve(); }
        if (!keepId) {
            pickClass(null);
            return grid.reload(true);
        }
        return grid.reload(false, keepId);
    }

    /** @param {ClassPropClass | null} c */
    function setClassForm(c) {
        if (!cform) { return; }
        hb.showFieldErrors(cform, []);
        cf('classCd').value = c ? c.classCd : '';
        cf('classNm').value = c ? c.classNm : '';
        cf('useYn').value = c ? c.useYn : 'Y';
        /** @type {HTMLInputElement} */ (cf('classCd')).readOnly = !!c;
    }

    /** @param {ClassPropClass | null} c */
    function pickClass(c) {
        curClass = c;
        if (classType === 'ITEM') { setClassForm(c); }
        /** @type {HTMLElement} */ (document.getElementById('propBox')).hidden = !c;
        formMode.textContent = c ? hb.t('js.classProp.classTitle', typeNames[classType] || classType, c.classCd, c.classNm)
            : hb.t('js.classProp.selectClass');
        if (c) { loadProps(c.classId, null); }
    }

    // ---------- 속성 ----------
    /** @param {number} classId @param {number | null} keepId */
    function loadProps(classId, keepId) {
        return hb.latest('props', hb.api(API + '?classType=' + classType + '&classId=' + classId)).then(function (res) {
            /** @type {ClassPropDef[]} */
            var list = res.data;
            pbody.textContent = '';
            if (!list.length) { empty(pbody, hb.t('js.classProp.noProp'), 5); }
            /** @type {ClassPropDef | null} */
            var found = null;
            nextSort = 10;
            list.forEach(function (p) {
                nextSort = Math.max(nextSort, (p.sortNo || 0) + 10);
                if (keepId && p.propId === keepId) { found = p; }
                var type = p.dataType + (p.dataType === 'NUM' ? '(' + p.scale + ')' : '') + (p.unitNm ? ' · ' + p.unitNm : '');
                row(pbody, [p.propCd, p.propNm, type, yesNo(p.requiredYn), yn(p.useYn)], p.useYn !== 'Y', keepId === p.propId,
                    function () { showProp(p); });
            });
            showProp(found);
        }, function (err) { notify(err.message, true); });
    }

    function layout() {
        var t = dataType.value();
        pform.querySelectorAll('.hb-prop-num').forEach(function (l) { /** @type {HTMLElement} */ (l).hidden = t !== 'NUM'; });
        pform.querySelectorAll('.hb-prop-list').forEach(function (l) { /** @type {HTMLElement} */ (l).hidden = t !== 'LIST'; });
    }

    /** @param {ClassPropDef | null} p */
    function showProp(p) {
        curProp = p;
        hb.showFieldErrors(pform, []);
        ['propCd', 'propNm', 'unitNm', 'semanticId'].forEach(function (n) { pf(n).value = p && p[n] ? p[n] : ''; });
        dataType.setValue(p ? p.dataType : 'TEXT');
        scale.setValue(p && p.scale !== null ? p.scale : 0);
        sortNo.setValue(p ? p.sortNo : nextSort);      // 새 속성은 맨 뒤
        pf('options').value = p && p.options ? p.options.join('\n') : '';
        pf('requiredYn').value = p ? p.requiredYn : 'N';
        pf('useYn').value = p ? p.useYn : 'Y';
        /** @type {HTMLInputElement} */ (pf('propCd')).readOnly = !!p || !CAN_SAVE;
        pf('dataType').disabled = !!p || !CAN_SAVE;     // 형식은 만든 뒤 고정
        pbody.querySelectorAll('tr').forEach(function (tr) {
            tr.setAttribute('aria-selected', p && tr.firstChild && tr.firstChild.textContent === p.propCd ? 'true' : 'false');
        });
        layout();
        hb.dirty.clear();
    }

    pf('dataType').addEventListener('change', layout);
    document.querySelectorAll('input[name="classType"]').forEach(function (el) {
        var r = /** @type {HTMLInputElement} */ (el);
        r.addEventListener('change', function () {
            if (!r.checked) { return; }
            classType = r.value;
            if (cform) { cform.hidden = classType !== 'ITEM'; }
            /** @type {HTMLElement} */ (document.getElementById('equipHint')).hidden = classType !== 'EQUIP';
            loadClasses(null);
        });
    });

    /** 버튼(권한에 따라 없을 수 있음)에 클릭 연결 @param {string} id @param {(b: HTMLButtonElement) => void} fn */
    function bind(id, fn) {
        var b = /** @type {HTMLButtonElement | null} */ (document.getElementById(id));
        if (b) {
            var btn = b;
            btn.addEventListener('click', function () { fn(btn); });
        }
    }

    bind('newClassBtn', function () { pickClass(null); setClassForm(null); cf('classCd').focus(); });
    bind('saveClassBtn', function (btn) {
        var body = {classCd: cf('classCd').value, classNm: cf('classNm').value, useYn: cf('useYn').value,
            sortNo: curClass ? curClass.sortNo : 0, rowVer: curClass ? curClass.rowVer : null};
        // 저장 중에 다른 클래스·신규·유형을 골라도 이 클래스로 마무리
        var editId = curClass ? curClass.classId : null;
        var ct = classType;
        hb.busy(btn, function () {
            return editId !== null ? hb.api(API + '/item-classes/' + editId, {method: 'PUT', body: body})
                : hb.api(API + '/item-classes', {method: 'POST', body: body});
        }).then(function (res) {
            if (!res) { return; }
            if (classType !== ct) { notify(hb.t('js.common.saved')); return; }   // 유형을 바꿨으면 그 목록을 덮지 않는다
            return loadClasses(editId !== null ? editId : res.data).then(function () { notify(hb.t('js.common.saved')); });
        }, function (err) {
            notify(err.message, true);
            if (cform) { hb.showFieldErrors(cform, err.errors); }
        });
    });

    bind('newBtn', function () {
        if (!curClass) { notify(hb.t('js.classProp.selectClass'), true); return; }
        showProp(null);
        pf('propCd').focus();
    });
    bind('saveBtn', function (btn) {
        if (!curClass) { notify(hb.t('js.classProp.selectClass'), true); return; }
        var t = dataType.value();
        // 저장 중에 다른 클래스·속성·유형을 골라도 이 속성으로 마무리
        var classId = curClass.classId;
        var editId = curProp ? curProp.propId : null;
        var ct = classType;
        var body = {classType: classType, classId: classId, propCd: pf('propCd').value, propNm: pf('propNm').value,
            dataType: t, unitNm: pf('unitNm').value, scale: t === 'NUM' ? scale.numberValue() : null,
            options: t === 'LIST' ? pf('options').value.split('\n') : null, requiredYn: pf('requiredYn').value,
            semanticId: pf('semanticId').value, sortNo: sortNo.numberValue(), useYn: pf('useYn').value,
            rowVer: curProp ? curProp.rowVer : null};
        hb.busy(btn, function () {
            return editId !== null ? hb.api(API + '/' + editId, {method: 'PUT', body: body}) : hb.api(API, {method: 'POST', body: body});
        }).then(function (res) {
            if (!res) { return; }
            var id = editId !== null ? editId : res.data;
            if (classType !== ct) { notify(hb.t('js.common.saved')); return; }   // 유형을 바꿨으면 그 목록을 덮지 않는다
            return loadClasses(classId).then(function () { return loadProps(classId, id); })
                .then(function () { notify(hb.t('js.common.saved')); });
        }, function (err) {
            notify(err.message, true);
            hb.showFieldErrors(pform, err.errors);
        });
    });

    if (!CAN_SAVE) {
        pform.querySelectorAll('input, select, textarea').forEach(function (i) {
            /** @type {HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement} */ (i).disabled = true;
        });
    }

    hb.dirty.watch(pform);
    hb.loadMessages().then(function () {
        hb.page.init();
        dataType = hb.select(/** @type {HTMLSelectElement} */ (pf('dataType')));
        scale = hb.number(/** @type {HTMLInputElement} */ (pf('scale')));
        sortNo = hb.number(/** @type {HTMLInputElement} */ (pf('sortNo')));
        return Promise.all([hb.loadSession(), dataType.ready, hb.codes('CLASS_TYPE')]);
    }).then(function (r) {
        typeNames = r[2];
        pickClass(null);
        grid = hb.grid('#classGrid', {
            progCd: PROG, id: 'classes', key: 'classId',
            url: API + '/classes/grid', columnsUrl: API + '/classes/columns', exportUrl: API + '/classes/export',
            actions: ACTIONS, params: function () { return {classType: classType}; },
            // 속성 폼을 고친 채 다른 클래스를 고르면 묻는다(예전엔 말없이 버려졌다)
            beforeSelect: function () { return confirmLeave(); },
            status: {useYn: {Y: 'normal', N: 'neutral'}},                                   // 사용 여부 = 상태 배지
            rowClass: function (row) { return row.useYn === 'N' ? 'hb-tree-dim' : null; },   // 미사용 행 흐리게
            onSelect: function (row) { pickClass(/** @type {ClassPropClass} */ (row)); hb.page.openDetail(); },
            notify: notify
        });
        return grid.ready;
    }).then(null, function (err) { notify(err && err.message, true); });
    // 조회 패널은 즉시 반영(체크·선택 변경) — Enter 로 폼이 제출돼 화면이 새로 고쳐지지 않게
    /** @type {HTMLFormElement} */ (document.getElementById('search')).addEventListener('submit', function (e) { e.preventDefault(); });
})();
