// @ts-check
/* 출하 검수 양식 (qms-preship-tpl) — docs/78. hb.grid 목록 + 상세 폼(머리) + 항목 한 벌(행마다 작은 폼). 지우지 않고 사용 중지 */
/**
 * @typedef {{tplId: number, tplCd: string, tplNm: string, targetType: string, targetCd: string, targetNm: string, lineCnt: number,
 *   remark: string | null, useYn: string, rowVer: number, [field: string]: any}} PreshipTplRow
 * @typedef {{tplLineId: number | null, seq: number, grpNm: string | null, checkText: string, naAllowedYn: string, photoReqYn: string,
 *   useYn: string}} PreshipTplLine
 */
(function () {
    'use strict';

    var API = '/api/qms/preship/tpls';
    var ACTIONS = (document.body.getAttribute('data-actions') || '').split(',');
    var CAN_SAVE = ACTIONS.indexOf('SAVE') >= 0;
    var PROG = document.body.getAttribute('data-prog') || '';
    var msg = /** @type {HTMLElement} */ (document.getElementById('msg'));
    var form = /** @type {HTMLFormElement} */ (document.getElementById('tplForm'));
    var formMode = /** @type {HTMLElement} */ (document.getElementById('formMode'));
    var linesBox = /** @type {HTMLElement} */ (document.getElementById('lines'));
    var search = /** @type {HTMLFormElement} */ (document.getElementById('search'));
    /** @type {PreshipTplRow | null} */
    var current = null;
    /** @type {HB.SearchPanel} */
    var sp;
    /** @type {HB.Grid | undefined} */
    var grid;

    /** @param {string | null | undefined} text @param {boolean} [error] */
    function notify(text, error) {
        msg.textContent = text || '';
        msg.classList.toggle('hb-msg-error', !!error);
    }

    function confirmLeave() {
        if (!hb.dirty.isDirty()) { return Promise.resolve(true); }
        return hb.dialog.confirm({title: hb.t('js.common.leaveTitle'), message: hb.t('js.common.confirmLeave'), ok: hb.t('js.common.leaveOk')});
    }

    /** @param {string} n */
    function field(n) {
        return /** @type {HTMLInputElement | HTMLSelectElement} */ (form.querySelector('[data-field="' + n + '"]'));
    }

    function targetType() {
        var r = /** @type {HTMLInputElement | null} */ (form.querySelector('input[name="targetType"]:checked'));
        return r ? r.value : 'ITEM';
    }

    /** @param {string} v */
    function setTargetType(v) {
        form.querySelectorAll('input[name="targetType"]').forEach(function (i) {
            var r = /** @type {HTMLInputElement} */ (i);
            r.checked = r.value === v;
        });
    }

    /** 항목 한 줄 — 순번·분류·확인 내용·해당 없음 허용·사진 필수, [빼기] @param {PreshipTplLine | null} l */
    function lineRow(l) {
        var box = hb.el('fieldset', 'hb-form hb-qms-preship-line');
        box.setAttribute('data-line-id', l && l.tplLineId ? String(l.tplLineId) : '');
        /** @param {string} name @param {string} label @param {string} value @param {number} max */
        function text(name, label, value, max) {
            var lab = hb.el('label', name === 'checkText' ? 'hb-wide' : null);
            lab.appendChild(hb.el('span', null, label));
            var i = hb.el('input');
            i.setAttribute('data-line', name);
            i.maxLength = max;
            i.value = value;
            i.disabled = !CAN_SAVE;
            lab.appendChild(i);
            box.appendChild(lab);
        }
        /** @param {string} name @param {string} label @param {boolean} on */
        function check(name, label, on) {
            var lab = hb.el('label', 'hb-check');
            var i = hb.el('input');
            i.type = 'checkbox';
            i.setAttribute('data-line', name);
            i.checked = on;
            i.disabled = !CAN_SAVE;
            lab.appendChild(i);
            lab.appendChild(hb.el('span', null, label));
            box.appendChild(lab);
        }
        text('seq', hb.t('js.qms.preship.seq'), l ? String(l.seq) : '', 4);
        text('grpNm', hb.t('js.qms.preship.grpNm'), l && l.grpNm ? l.grpNm : '', 60);
        text('checkText', hb.t('js.qms.preship.checkText'), l ? l.checkText : '', 300);
        check('naAllowedYn', hb.t('js.qms.preship.naAllowed'), !!l && l.naAllowedYn === 'Y');
        check('photoReqYn', hb.t('js.qms.preship.photoReq'), !!l && l.photoReqYn === 'Y');
        if (CAN_SAVE) {
            var rm = hb.el('button', 'hb-btn-ghost', hb.t('js.qms.preship.removeLine'));
            rm.type = 'button';
            rm.addEventListener('click', function () { box.remove(); hb.dirty.mark(form); });
            box.appendChild(rm);
        }
        box.addEventListener('input', function () { hb.dirty.mark(form); });
        return box;
    }

    /** @param {PreshipTplLine[]} lines */
    function showLines(lines) {
        linesBox.textContent = '';
        lines.filter(function (l) { return l.useYn === 'Y'; }).forEach(function (l) { linesBox.appendChild(lineRow(l)); });
    }

    function collectLines() {
        /** @type {Record<string, unknown>[]} */
        var out = [];
        linesBox.querySelectorAll('fieldset.hb-qms-preship-line').forEach(function (b) {
            /** @param {string} n */
            function v(n) { return /** @type {HTMLInputElement} */ (b.querySelector('[data-line="' + n + '"]')); }
            var id = b.getAttribute('data-line-id');
            var seq = v('seq').value.trim();
            out.push({tplLineId: id ? Number(id) : null, seq: seq ? Number(seq) : null, grpNm: v('grpNm').value, checkText: v('checkText').value,
                naAllowedYn: v('naAllowedYn').checked ? 'Y' : 'N', photoReqYn: v('photoReqYn').checked ? 'Y' : 'N', useYn: 'Y'});
        });
        return out;
    }

    /** @param {PreshipTplRow | null} d @param {PreshipTplLine[]} lines */
    function show(d, lines) {
        current = d;
        hb.showFieldErrors(form, []);
        field('tplCd').value = d ? d.tplCd : '';
        /** @type {HTMLInputElement} */ (field('tplCd')).readOnly = !!d;
        field('tplNm').value = d ? d.tplNm : '';
        setTargetType(d ? d.targetType : 'ITEM');
        field('itemCd').value = d ? d.targetCd : '';
        field('remark').value = d && d.remark ? d.remark : '';
        field('useYn').value = d ? d.useYn : 'Y';
        formMode.textContent = d ? hb.t('js.qms.preship.tplEditing', d.tplCd) : hb.t('js.qms.preship.tplCreating');
        showLines(lines);
        if (!d && CAN_SAVE) { linesBox.appendChild(lineRow(null)); }
        hb.dirty.clear(form);
    }

    /** @param {number} id */
    function load(id) {
        return hb.latest('preship-tpl', hb.api(API + '/' + id)).then(function (res) {
            show(/** @type {PreshipTplRow} */ (res.data.head), /** @type {PreshipTplLine[]} */ (res.data.lines));
        }, function (err) {
            if (!err.stale) { notify(err.message, true); }
        });
    }

    function params() {
        var v = sp.values();
        return {keyword: v.keyword, useYn: v.withUnused && v.withUnused.length ? '' : 'Y'};
    }

    function newTpl() {
        confirmLeave().then(function (ok) {
            if (!ok) { return; }
            show(null, []);
            hb.page.openDetail();
            field('tplCd').focus();
        });
    }
    ['newBtn', 'newBtnList'].forEach(function (id) {
        var b = document.getElementById(id);
        if (b) { b.addEventListener('click', newTpl); }
    });

    var addBtn = document.getElementById('addLineBtn');
    if (addBtn) {
        addBtn.addEventListener('click', function () {
            var row = lineRow(null);
            linesBox.appendChild(row);
            hb.dirty.mark(form);
            var t = /** @type {HTMLInputElement} */ (row.querySelector('[data-line="checkText"]'));
            t.focus();
        });
    }

    var saveBtn = /** @type {HTMLButtonElement | null} */ (document.getElementById('saveBtn'));
    if (saveBtn) {
        var btn = saveBtn;
        btn.addEventListener('click', function () {
            var tt = targetType();
            var cd = field('itemCd').value;
            var body = {tplCd: field('tplCd').value, tplNm: field('tplNm').value, itemCd: tt === 'ITEM' ? cd : null, classCd: tt === 'CLASS' ? cd : null,
                remark: field('remark').value, useYn: field('useYn').value, rowVer: current ? current.rowVer : null, lines: collectLines()};
            var editId = current ? current.tplId : null;
            hb.busy(btn, function () {
                return editId !== null ? hb.api(API + '/' + editId, {method: 'PUT', body: body}) : hb.api(API, {method: 'POST', body: body});
            }).then(function (res) {
                if (!res) { return; }
                var id = editId !== null ? editId : /** @type {number} */ (res.data);
                hb.dirty.clear(form);
                return (grid ? grid.reload(false, id) : Promise.resolve()).then(function () { return load(id); })
                    .then(function () { notify(hb.t('js.common.saved')); });
            }, function (err) {
                // 대상 칸은 하나 — 품목군 코드 오류도 같은 칸에, 항목 행 오류는 첫 문구를 알림 줄에
                var errs = (err.errors || []).map(function (/** @type {HB.FieldError} */ x) {
                    return x.field === 'classCd' ? Object.assign({}, x, {field: 'itemCd'}) : x;
                });
                var rowErr = errs.filter(function (/** @type {HB.FieldError} */ x) { return x.rowKey !== null && x.rowKey !== undefined; })[0];
                notify(rowErr ? hb.t('js.qms.preship.lineError', Number(rowErr.rowKey) + 1, rowErr.message) : err.message, true);
                hb.showFieldErrors(form, errs);
                hb.showFieldErrors(/** @type {HTMLElement} */ (linesBox.parentElement), errs.filter(function (/** @type {HB.FieldError} */ x) {
                    return x.field === 'lines';
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
    search.addEventListener('submit', function (e) { e.preventDefault(); });
    hb.loadMessages().then(function () {
        hb.page.init();
        sp = hb.searchPanel(search, {
            onSearch: function () {
                if (!grid) { return null; }
                var g = grid;
                return confirmLeave().then(function (ok) { return ok ? g.reload(true) : null; });
            }
        });
        return Promise.all([hb.loadSession(), sp.ready]);
    }).then(function () {
        show(null, []);
        grid = hb.grid('#tplGrid', {
            progCd: PROG, id: 'main', key: 'tplId',
            url: API + '/grid', columnsUrl: API + '/columns', exportUrl: API + '/export',
            actions: ACTIONS, params: params,
            beforeSelect: function () { return confirmLeave(); },
            status: {useYn: {Y: 'normal', N: 'neutral'}},
            rowClass: function (row) { return row.useYn === 'N' ? 'hb-tree-dim' : null; },
            onSelect: function (row) { load(/** @type {PreshipTplRow} */ (row).tplId); hb.page.openDetail(); },
            notify: notify
        });
        return grid.ready;
    }).then(null, function (err) { notify(err && err.message, true); });
})();
