// @ts-check
/* 자격 관리 (mst-qual) — P2 1단계 (docs/25 4.2). 자격 표 + 상세 폼 + 이 자격이 필요한 공정(경고/차단·사용) */
/**
 * @typedef {{qualId: number, qualCd: string, qualNm: string, validMonths: number | null, holderCnt: number, procCnt: number,
 *   sortNo: number, useYn: string, remark?: string, rowVer: number, [field: string]: any}} QualRow
 * @typedef {{reqId: number, procCd: string, procNm: string, procUseYn: string, gateMode: string, useYn: string,
 *   rowVer: number, [field: string]: any}} QualReq
 * @typedef {{procCd: string, procNm: string, [field: string]: any}} QualProc
 */
(function () {
    'use strict';

    var API = '/api/master/quals';
    var ACTIONS = (document.body.getAttribute('data-actions') || '').split(',');
    var CAN_SAVE = ACTIONS.indexOf('SAVE') >= 0;
    var msg = /** @type {HTMLElement} */ (document.getElementById('msg'));
    var form = /** @type {HTMLFormElement} */ (document.getElementById('qualForm'));
    var PROG = document.body.getAttribute('data-prog') || '';
    var reqBody = /** @type {HTMLElement} */ (document.querySelector('#reqs tbody'));
    var reqSection = /** @type {HTMLElement} */ (document.getElementById('reqSection'));
    var reqForm = /** @type {HTMLFormElement | null} */ (document.getElementById('reqForm'));   // SAVE 권한 없으면 없음
    var formMode = /** @type {HTMLElement} */ (document.getElementById('formMode'));
    var withUnused = /** @type {HTMLInputElement} */ (document.getElementById('withUnused'));
    /** 고른 자격 @type {QualRow | null} */
    var current = null;
    /** 준비(문구 로드) 뒤에 만든다 @type {HB.NumberControl} */
    var sortNo;
    /** @type {HB.NumberControl} */
    var months;
    /** @type {HB.Grid | undefined} */
    var grid;

    /** @param {string | null | undefined} text @param {boolean} [error] */
    function notify(text, error) {
        msg.textContent = text || '';
        msg.classList.toggle('hb-msg-error', !!error);
    }

    /** 상세 폼 칸 @param {string} n */
    function field(n) {
        return /** @type {HTMLInputElement | HTMLSelectElement} */ (form.querySelector('[data-field="' + n + '"]'));
    }

    /** @param {string} m */
    function modeName(m) { return m === 'BLOCK' ? hb.t('js.qual.block') : hb.t('js.qual.warn'); }

    /** @param {QualRow | null} q */
    function show(q) {
        current = q;
        hb.showFieldErrors(form, []);
        ['qualCd', 'qualNm', 'remark'].forEach(function (n) { field(n).value = q ? (q[n] || '') : ''; });
        months.setValue(q && q.validMonths !== null ? q.validMonths : null);
        sortNo.setValue(q ? q.sortNo : 0);
        field('useYn').value = q ? q.useYn : 'Y';
        /** @type {HTMLInputElement} */ (field('qualCd')).readOnly = !!q || !CAN_SAVE;
        formMode.textContent = q ? hb.t('js.qual.editing', q.qualCd) : hb.t('js.qual.creating');
        reqSection.hidden = !q;
        if (q) { loadReqs(q.qualId); }
        hb.dirty.clear();
    }

    /**
     * 자격 목록 다시 읽기(보유자·공정 수가 바뀐다) — selectId 가 이 페이지에 있으면 다시 골라 onSelect 가 상세·요구를 채운다.
     * resetPage = 조건(사용 중지 포함)을 바꿨을 때 첫 쪽부터 @param {number | null} [selectId] @param {boolean} [resetPage]
     */
    function load(selectId, resetPage) {
        return grid ? grid.reload(!!resetPage, selectId || undefined) : Promise.resolve();
    }

    /** 이 자격이 필요한 공정 @param {number} qualId */
    function loadReqs(qualId) {
        return hb.latest('reqs', hb.api(API + '/reqs?qualId=' + qualId)).then(function (res) {
            /** @type {QualReq[]} */
            var list = res.data;
            reqBody.textContent = '';
            if (!list.length) {
                var tr = hb.el('tr');
                var td = hb.el('td', 'hb-muted', hb.t('js.qual.noReq'));
                td.colSpan = 4;
                tr.appendChild(td);
                reqBody.appendChild(tr);
            }
            list.forEach(function (r) {
                var tr = hb.el('tr', r.useYn === 'Y' ? null : 'hb-tree-dim');
                tr.appendChild(hb.el('td', null, r.procCd + ' · ' + r.procNm
                    + (r.procUseYn === 'Y' ? '' : ' — ' + hb.t('js.qual.inactive'))));
                var modeTd = hb.el('td');
                var useTd = hb.el('td', null, r.useYn === 'Y' ? hb.t('js.yn.Y') : hb.t('js.yn.N'));
                var actTd = hb.el('td');
                if (CAN_SAVE) {
                    var sel = hb.el('select');
                    ['WARN', 'BLOCK'].forEach(function (m) {
                        var o = hb.el('option', null, modeName(m));
                        o.value = m;
                        sel.appendChild(o);
                    });
                    sel.value = r.gateMode;
                    sel.setAttribute('aria-label', hb.t('js.qual.gateMode', r.procCd));
                    sel.addEventListener('change', function () { saveReq(qualId, r, {gateMode: sel.value}); });
                    modeTd.appendChild(sel);
                    var btn = hb.el('button', 'hb-btn-secondary', r.useYn === 'Y' ? hb.t('js.qual.off') : hb.t('js.qual.on'));
                    btn.type = 'button';
                    btn.addEventListener('click', function () { saveReq(qualId, r, {useYn: r.useYn === 'Y' ? 'N' : 'Y'}, btn); });
                    actTd.appendChild(btn);
                } else {
                    modeTd.textContent = modeName(r.gateMode);
                }
                [modeTd, useTd, actTd].forEach(function (td) { tr.appendChild(td); });
                reqBody.appendChild(tr);
            });
        }, function (err) { notify(err.message, true); });
    }

    /**
     * 필요 공정 한 줄 저장 — 저장 중에 다른 자격을 골라도 이 자격(qualId)으로 마무리
     * @param {number} qualId @param {QualReq} r @param {{gateMode?: string, useYn?: string}} change @param {HTMLButtonElement} [btn]
     */
    function saveReq(qualId, r, change, btn) {
        var body = {gateMode: change.gateMode || r.gateMode, useYn: change.useYn || r.useYn, rowVer: r.rowVer};
        var run = function () { return hb.api(API + '/reqs/' + r.reqId, {method: 'PUT', body: body}); };
        /** @type {Promise<HB.ApiResponse | null>} */
        var p = btn ? hb.busy(btn, run) : run();
        p.then(function (res) {
            if (res === null) { return; }
            notify(hb.t('js.common.saved'));
            return load(qualId);
        }, function (err) {
            notify(err.errors && err.errors.length ? err.errors[0].message : err.message, true);
            if (current && current.qualId === qualId) { loadReqs(qualId); }   // 다른 자격으로 옮겼으면 그 표를 덮지 않는다
        });
    }

    function newQual() {
        if (!months) { return; }   // 준비 전
        mesUi.leaveOk().then(function (ok) {
            if (!ok) { return; }
            show(null);
            hb.page.openDetail();
            field('qualCd').focus();
        });
    }
    ['newBtn', 'newBtnList'].forEach(function (id) {
        var b = document.getElementById(id);
        if (b) { b.addEventListener('click', newQual); }
    });
    withUnused.addEventListener('change', function () {
        if (!months) { return; }   // 준비 전 — 첫 조회가 체크 상태를 읽는다
        load(current ? current.qualId : null, true);
    });

    var saveBtn = /** @type {HTMLButtonElement | null} */ (document.getElementById('saveBtn'));   // SAVE 권한 없으면 없음
    if (saveBtn) {
        var btn = saveBtn;
        btn.addEventListener('click', function () {
            if (!months) { return; }   // 준비 전
            var m = months.value();
            var body = {qualCd: field('qualCd').value, qualNm: field('qualNm').value,
                validMonths: m === null || m === '' ? null : Number(m), sortNo: sortNo.numberValue(),
                useYn: field('useYn').value, remark: field('remark').value, rowVer: current ? current.rowVer : null};
            var editId = current ? current.qualId : null;   // 저장 중에 다른 행·신규를 골라도 이 자격으로 마무리
            hb.busy(btn, function () {
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
    }
    var addReqBtn = /** @type {HTMLButtonElement | null} */ (document.getElementById('addReqBtn'));   // reqForm 안(SAVE 권한)
    if (addReqBtn && reqForm) {
        var addBtn = addReqBtn;
        var rForm = reqForm;
        addBtn.addEventListener('click', function () {
            if (!current) { return; }
            var qualId = current.qualId;   // 저장 중에 다른 자격을 골라도 이 자격으로 마무리
            hb.showFieldErrors(rForm, []);
            var body = {procCd: /** @type {HTMLSelectElement} */ (rForm.querySelector('[data-field="procCd"]')).value,
                qualCd: current.qualCd,
                gateMode: /** @type {HTMLSelectElement} */ (rForm.querySelector('[data-field="gateMode"]')).value};
            hb.busy(addBtn, function () { return hb.api(API + '/reqs', {method: 'POST', body: body}); }).then(function (res) {
                if (!res) { return; }
                notify(hb.t('js.common.saved'));
                return load(qualId);
            }, function (err) {
                notify(err.message, true);
                hb.showFieldErrors(rForm, (err.errors || []).map(function (/** @type {HB.FieldError} */ x) {
                    return x.field === 'qualCd' ? Object.assign({}, x, {field: 'procCd'}) : x;
                }));
            });
        });
    }
    if (!CAN_SAVE) {
        form.querySelectorAll('input, select').forEach(function (i) {
            /** @type {HTMLInputElement | HTMLSelectElement} */ (i).disabled = true;
        });
    }

    hb.dirty.watch(form);
    hb.loadMessages().then(function () {
        hb.page.init();
        sortNo = hb.number(/** @type {HTMLInputElement} */ (field('sortNo')));
        months = hb.number(/** @type {HTMLInputElement} */ (field('validMonths')));
        return Promise.all([hb.loadSession(), hb.api(API + '/procs')]);
    }).then(function (r) {
        var procSel = document.getElementById('procSel');
        if (procSel) {
            var sel = procSel;
            /** @type {QualProc[]} */ (r[1].data).forEach(function (p) {
                var o = hb.el('option', null, p.procCd + ' · ' + p.procNm);
                o.value = p.procCd;
                sel.appendChild(o);
            });
        }
        show(null);
        grid = hb.grid('#qualGrid', {
            progCd: PROG, id: 'main', key: 'qualId',
            url: API + '/grid', columnsUrl: API + '/columns', exportUrl: API + '/export',
            actions: ACTIONS, params: function () { return {useYn: withUnused.checked ? '' : 'Y'}; },
            format: {validText: function (v) { return v === '' ? hb.t('js.qual.noExpiry') : String(v); }},   // 만료 없음 = 빈 글자(서버)
            beforeSelect: function () { return mesUi.leaveOk(); },
            status: {useYn: mesUi.TONES.use},                                              // 사용 여부 = 상태 배지
            rowClass: function (row) { return row.useYn === 'N' ? 'hb-tree-dim' : null; },   // 미사용 행 흐리게
            onSelect: function (row) { show(/** @type {QualRow} */ (row)); hb.page.openDetail(); },
            notify: notify
        });
        return grid.ready;
    }).then(null, function (err) { notify(err && err.message, true); });
})();
