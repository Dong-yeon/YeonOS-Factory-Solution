// @ts-check
/*
 * 채번 규칙 (sys-numbering) — 시스템관리 2단계 (docs/36 9.4). 규칙 hb.grid(UI 단계 3 ①, docs/36 17.1) + 오른쪽 폼 + 다음 번호 미리보기(서버 계산, 번호를 쓰지 않음).
 * 패턴·주기를 바꾸면 이미 낸 번호와 겹칠 수 있어 경고한다(저장은 허용, Q8). 패턴 검증은 서버(NumberingPattern).
 */
/**
 * @typedef {{ruleCd: string, ruleNm: string, pattern: string, resetCycle: string, useYn: string, rowVer: number}} SysRule
 * @typedef {{rule: SysRule, lastNo: number | null, nextPreview: string | null, needsPlant: boolean}} SysRuleView
 */
(function () {
    'use strict';

    var API = '/api/admin/numbering';
    var ACTIONS = (document.body.getAttribute('data-actions') || '').split(',');
    var CAN_SAVE = ACTIONS.indexOf('SAVE') >= 0;
    var msg = /** @type {HTMLElement} */ (document.getElementById('msg'));
    var form = /** @type {HTMLFormElement} */ (document.getElementById('ruleForm'));
    var formMode = /** @type {HTMLElement} */ (document.getElementById('formMode'));
    var search = /** @type {HTMLFormElement} */ (document.getElementById('search'));
    /** @type {HB.Grid | null} */
    var grid = null;
    /** @type {HB.SearchPanel} */
    var sp;
    var previewOut = /** @type {HTMLElement} */ (document.getElementById('preview'));
    var warn = /** @type {HTMLElement} */ (document.getElementById('warn'));
    /** @type {SysRuleView | null} */
    var current = null;
    /** @type {Record<string, string>} */
    var cycleNames = {};
    var timer = 0;

    /** @param {string | null | undefined} text @param {boolean} [error] */
    function notify(text, error) {
        msg.textContent = text || '';
        msg.classList.toggle('hb-msg-error', !!error);
    }

    /** @param {string} n */
    function field(n) {
        return /** @type {HTMLInputElement | HTMLSelectElement} */ (form.querySelector('[data-field="' + n + '"]'));
    }

    /** 바뀐 패턴·주기 경고 + 서버 미리보기(입력 후 잠시 뒤, 늦게 온 앞 응답은 버림) */
    function refreshPreview() {
        var r = current ? current.rule : null;
        var changed = !!r && (field('pattern').value.trim() !== r.pattern || field('resetCycle').value !== r.resetCycle);
        warn.hidden = !changed;
        warn.textContent = changed ? hb.t('js.sysNumbering.changeWarn') : '';
        window.clearTimeout(timer);
        timer = window.setTimeout(function () {
            var body = {ruleCd: r ? r.ruleCd : null, pattern: field('pattern').value.trim(), resetCycle: field('resetCycle').value};
            if (!body.pattern) { previewOut.textContent = ''; return; }
            hb.latest('preview', hb.api(API + '/preview', {method: 'POST', body: body})).then(function (res) {
                hb.showFieldErrors(form, []);
                previewOut.textContent = res.data.sample;
            }, function (err) {
                if (err.stale) { return; }
                previewOut.textContent = '';
                hb.showFieldErrors(form, err.errors);
            });
        }, 300);
    }

    /** @param {SysRuleView | null} v */
    function show(v) {
        current = v;
        hb.showFieldErrors(form, []);
        var r = v ? v.rule : null;
        field('ruleCd').value = r ? r.ruleCd : '';
        /** @type {HTMLInputElement} */ (field('ruleCd')).readOnly = !!r || !CAN_SAVE;
        field('ruleNm').value = r ? r.ruleNm : '';
        field('pattern').value = r ? r.pattern : '';
        ['ruleNm', 'pattern'].forEach(function (n) { /** @type {HTMLInputElement} */ (field(n)).readOnly = !CAN_SAVE; });
        field('resetCycle').value = r ? r.resetCycle : 'MONTH';
        field('useYn').value = r ? r.useYn : 'Y';
        field('resetCycle').disabled = !CAN_SAVE;
        field('useYn').disabled = !CAN_SAVE;
        formMode.textContent = r ? hb.t('js.sysNumbering.editing', r.ruleCd) : hb.t('js.sysNumbering.creating');
        previewOut.textContent = v && v.nextPreview ? v.nextPreview : '';
        warn.hidden = true;
        hb.dirty.clear();
    }

    /** 다시 읽고 그 규칙을 고른다(그리드 onSelect 가 폼을 채움). 이 쪽 밖이면 폼은 비운다 @param {string} [selectCd] */
    function load(selectCd) {
        if (!grid) { return Promise.resolve(); }
        var g = grid;
        return g.reload(false, selectCd).then(function () { if (!g.selected()) { show(null); } });
    }

    /** grid 한 줄(펼친 모양) → 폼이 쓰는 모양 @param {HB.Row} row @return {SysRuleView} */
    function viewOf(row) {
        return {rule: {ruleCd: row.ruleCd, ruleNm: row.ruleNm, pattern: row.pattern, resetCycle: row.resetCycle, useYn: row.useYn,
            rowVer: row.rowVer}, lastNo: row.lastNo, nextPreview: row.nextPreview, needsPlant: row.needsPlant};
    }

    field('pattern').addEventListener('input', refreshPreview);
    field('resetCycle').addEventListener('change', refreshPreview);

    var newBtn = document.getElementById('newBtn');
    if (newBtn) {
        newBtn.addEventListener('click', function () {
            sysUi.leaveOk().then(function (ok) {
                if (!ok) { return; }
                show(null);
                hb.page.openDetail();
                field('ruleCd').focus();
            });
        });
    }
    var saveBtnEl = /** @type {HTMLButtonElement | null} */ (document.getElementById('saveBtn'));
    if (saveBtnEl) {
        var saveBtn = saveBtnEl;
        saveBtn.addEventListener('click', function () {
            var edit = current ? current.rule : null;   // 저장 중 다른 규칙을 골라도 이 규칙으로 마무리
            (edit && !warn.hidden ? sysUi.ask('js.sysNumbering.dlg.change', hb.t('js.sysNumbering.confirmChange'), 'js.sysNumbering.dlg.changeOk', true)
                : Promise.resolve(true)).then(function (ok) { if (ok) { save(edit, saveBtn); } });
        });
    }

    /** @param {SysRule | null} edit @param {HTMLButtonElement} saveBtn */
    function save(edit, saveBtn) {
        var body = {ruleCd: field('ruleCd').value, ruleNm: field('ruleNm').value, pattern: field('pattern').value,
            resetCycle: field('resetCycle').value, useYn: field('useYn').value, rowVer: edit ? edit.rowVer : null};
        hb.busy(saveBtn, function () {
            return edit ? hb.api(API + '/' + encodeURIComponent(edit.ruleCd), {method: 'PUT', body: body})
                : hb.api(API, {method: 'POST', body: body});
        }).then(function (res) {
            if (!res) { return; }
            hb.dirty.clear();
            return load(edit ? edit.ruleCd : String(body.ruleCd).trim().toUpperCase()).then(function () { notify(hb.t('js.common.saved')); });
        }, function (err) {
            notify(err.message, true);
            hb.showFieldErrors(form, err.errors);
        });
    }

    hb.dirty.watch(form);
    hb.loadMessages().then(function () {
        hb.page.init();
        /** @type {HTMLSelectElement} */ (field('resetCycle')).querySelectorAll('option').forEach(function (o) {
            cycleNames[o.value] = o.textContent || o.value;
        });
        show(null);
        sp = hb.searchPanel(search, {
            onSearch: function () {
                if (!grid) { return null; }
                var g = grid;
                return sysUi.leaveOk().then(function (ok) { return ok ? g.reload(true) : null; });
            }
        });
        return Promise.all([hb.loadSession(), sp.ready]);
    }).then(function () {
        grid = hb.grid('#ruleGrid', {
            progCd: 'sys-numbering', id: 'main', key: 'ruleCd',
            url: API + '/grid', columnsUrl: API + '/columns', exportUrl: API + '/export', actions: ACTIONS,
            params: function () { return sp.values(); },
            format: {resetCycle: function (v) { return cycleNames[v] || v; }},
            status: {useYn: sysUi.TONES.use},
            rowClass: function (row) { return row.useYn === 'N' ? 'hb-tree-dim' : null; },
            beforeSelect: sysUi.beforeSelect,
            onSelect: function (row) { show(viewOf(row)); hb.page.openDetail(); },
            notify: notify
        });
        return grid.ready;
    }).then(null, function (err) { notify(err && err.message, true); });
})();
