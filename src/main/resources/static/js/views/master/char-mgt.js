// @ts-check
/*
 * 특성 마스터 (mst-char) — P1 6단계 (docs/21). 목록 = hb.grid, 상세 = 폼.
 * 도면을 보며 풍선 번호 순서로 넣는 흐름: 저장 → "같은 품목 다음 번호"(품목·공정·단위·자릿수·도면을 이어받고 번호 +1).
 * 계수(합부)면 하한·공칭·상한 칸을 숨긴다. 서버 데이터는 textContent·value 로만 넣는다.
 */
/**
 * 폼에 넣는 값 — 서버 상세 행 또는 "다음 번호" 로 이어받은 값(charId 없음)
 * @typedef {{charId?: number, charType: string, itemCd?: string | null, itemNm?: string | null, procCd?: string | null,
 *   procNm?: string | null, charNo?: string | null, dataType: string, specialCls: string, source: string, useYn: string,
 *   rowVer?: number, [field: string]: any}} CharForm
 * @typedef {CharForm & {charId: number, rowVer: number}} CharRow
 * @typedef {{procCd: string, procNm: string, [field: string]: any}} CharProc
 */
(function () {
    'use strict';

    var PROG = document.body.getAttribute('data-prog') || '';
    var ACTIONS = (document.body.getAttribute('data-actions') || '').split(',');
    var CAN_SAVE = ACTIONS.indexOf('SAVE') >= 0;
    var API = '/api/master/chars';
    var msg = /** @type {HTMLElement} */ (document.getElementById('msg'));
    var search = /** @type {HTMLFormElement} */ (document.getElementById('search'));
    var form = /** @type {HTMLFormElement} */ (document.getElementById('charForm'));
    var formMode = /** @type {HTMLElement} */ (document.getElementById('formMode'));
    var itemNm = /** @type {HTMLElement} */ (document.getElementById('itemNm'));
    /** 고른 특성 @type {CharRow | null} */
    var current = null;
    /** @type {HB.Grid | undefined} */
    var grid;
    /** 조회 조건 패널 @type {HB.SearchPanel} */
    var sp;
    /** 숫자 칸(준비 뒤에 만든다) @type {Record<string, HB.NumberControl>} */
    var num = {};
    /** 코드 select(준비 뒤에 만든다) @type {Record<string, HB.SelectControl>} */
    var sel = {};
    /** 폼 준비(숫자·select·공정 목록) 끝 — 그 전 버튼은 무시 */
    var formReady = false;
    var TEXTS = ['itemCd', 'charNo', 'charNm', 'drawingNo', 'drawingRev', 'remark'];
    var NUMS = ['scale', 'lsl', 'nominal', 'usl', 'sortNo'];
    var SELS = ['charType', 'dataType', 'specialCls', 'source', 'unitCd'];
    /** 옛 행 단위 글자(코드 없이 글자만, docs/68 Q2) — 빈 선택지로 보이고 그대로 저장 @type {string | null} */
    var legacyUnit = null;

    /** @param {string | null | undefined} text @param {boolean} [error] */
    function notify(text, error) {
        msg.textContent = text || '';
        msg.classList.toggle('hb-msg-error', !!error);
    }

    /** 상세 폼 칸 @param {string} n */
    function field(n) {
        return /** @type {HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement} */ (form.querySelector('[data-field="' + n + '"]'));
    }

    /** select 칸 @param {string} n */
    function selField(n) { return /** @type {HTMLSelectElement} */ (field(n)); }

    /** @param {HTMLSelectElement} s @param {string} value @param {string} text */
    function option(s, value, text) {
        var o = hb.el('option', null, text);
        o.value = value;
        s.appendChild(o);
        return o;
    }

    /** 계수면 규격 칸 숨김, 공정 특성이면 공정 칸이 필수라는 표시 */
    function layout() {
        var attr = sel.dataType.value() === 'ATTRIBUTE';
        form.querySelectorAll('.hb-spec').forEach(function (l) { /** @type {HTMLElement} */ (l).hidden = attr; });
        field('procCd').required = sel.charType.value() === 'PROCESS';
        field('itemCd').required = sel.charType.value() === 'PRODUCT';
    }

    /** @param {CharForm | null} c @param {boolean} [keepMode] */
    function setForm(c, keepMode) {
        current = c && c.charId ? /** @type {CharRow} */ (c) : null;
        hb.showFieldErrors(form, []);
        TEXTS.forEach(function (n) { field(n).value = c && c[n] !== null && c[n] !== undefined ? c[n] : ''; });
        NUMS.forEach(function (n) { num[n].setValue(c ? c[n] : (n === 'scale' ? 2 : n === 'sortNo' ? 0 : null)); });
        sel.charType.setValue(c ? c.charType : 'PRODUCT');
        sel.dataType.setValue(c ? c.dataType : 'VARIABLE');
        sel.specialCls.setValue(c ? c.specialCls : 'NONE');
        var ss = selField('specialCls');
        ss.querySelectorAll('option[data-stale]').forEach(function (o) { o.remove(); });
        if (c && c.specialCls && ss.value !== c.specialCls) {
            // 사용 중지된 회사 특별특성 코드 — 선택지에 없으면 select 가 NONE 이 되어 저장 때 조용히 바뀐다(QA 6 결함 #2)
            option(ss, c.specialCls, c.specialCls + ' — ' + hb.t('js.mstChar.inactive')).setAttribute('data-stale', '');
            ss.value = c.specialCls;
        }
        sel.source.setValue(c ? c.source : 'DRAWING');
        setUnit(c);
        var ps = selField('procCd');
        ps.value = c && c.procCd ? c.procCd : '';
        if (c && c.procCd && ps.value !== c.procCd) {
            // 사용 중지된 공정 — 선택지에 없으니 그대로 보여 준다
            option(ps, c.procCd, c.procCd + ' · ' + (c.procNm || '') + ' — ' + hb.t('js.mstChar.inactive'));
            ps.value = c.procCd;
        }
        field('useYn').value = c ? c.useYn : 'Y';
        itemNm.textContent = c && c.itemNm ? c.itemNm : '';
        var edit = CAN_SAVE;
        form.querySelectorAll('input, textarea').forEach(function (i) {
            /** @type {HTMLInputElement | HTMLTextAreaElement} */ (i).readOnly = !edit;
        });
        form.querySelectorAll('select').forEach(function (s) { s.disabled = !edit; });
        // 유형·품목은 만든 뒤 고정, 공정 특성의 공정도 고정
        field('charType').disabled = !!current || !edit;
        /** @type {HTMLInputElement} */ (field('itemCd')).readOnly = !!current || !edit;
        field('procCd').disabled = !edit || (!!current && current.charType === 'PROCESS');
        if (!keepMode) {
            formMode.textContent = current ? hb.t('js.mstChar.editing', current.itemCd || current.procCd, current.charNo)
                : hb.t('js.mstChar.creating');
        }
        layout();
        hb.dirty.clear();
    }

    /**
     * 측정 단위(MEAS_UNIT, docs/68 Q2) — 코드가 있으면 그 코드(목록에 없으면 hb.select 가 "(목록에 없음)"으로 남긴다).
     * 옛 행(글자만): 같은 이름 코드가 하나면 그 코드로(서버도 같은 규칙), 없으면 빈 선택지에 "글자 (목록에 없음)" — 저장해도 지워지지 않게
     * @param {CharForm | null} c
     */
    function setUnit(c) {
        var us = selField('unitCd');
        legacyUnit = null;
        var code = c && c.unitCd ? String(c.unitCd) : '';
        if (!code && c && c.unitNm) {
            var name = String(c.unitNm);
            var hit = Array.prototype.filter.call(us.options, function (/** @type {HTMLOptionElement} */ o) {
                return o.value && !o.hasAttribute('data-hb-extra') && o.textContent === name;
            });
            if (hit.length === 1) { code = hit[0].value; } else { legacyUnit = name; }
        }
        sel.unitCd.setValue(code);
        if (us.options.length) {
            us.options[0].textContent = legacyUnit ? hb.t('js.common.codeNotListed', legacyUnit) : hb.t('js.common.choose');
        }
    }

    /** 폼 값 묶음(서버 행 모양) */
    function body() {
        /** @type {Record<string, any>} */
        var b = {};
        TEXTS.forEach(function (n) { b[n] = field(n).value; });
        NUMS.forEach(function (n) { b[n] = num[n].value(); });
        SELS.forEach(function (n) { b[n] = sel[n].value(); });
        b.unitNm = b.unitCd ? null : legacyUnit;   // 코드를 고르면 이름은 서버가 채운다
        b.scale = b.scale === null || b.scale === '' ? null : Number(b.scale);
        b.sortNo = b.sortNo === null || b.sortNo === '' ? null : Number(b.sortNo);
        b.procCd = field('procCd').value;
        b.useYn = field('useYn').value;
        if (b.dataType === 'ATTRIBUTE') { b.lsl = b.nominal = b.usl = null; }
        if (current) { b.rowVer = current.rowVer; }
        return b;
    }

    /** @param {number} id @param {boolean} [keepMsg] */
    function load(id, keepMsg) {
        if (!keepMsg) { notify(''); }
        return hb.latest('detail', hb.api(API + '/' + id)).then(function (res) { setForm(res.data); }, function (err) { notify(err.message, true); });
    }

    /** 미저장 입력이 있으면 화면 안 대화상자로 묻는다(docs/40 V-7) — 없으면 바로 true @returns {Promise<boolean>} */
    function confirmLeave() {
        if (!hb.dirty.isDirty()) { return Promise.resolve(true); }
        return hb.dialog.confirm({title: hb.t('js.common.leaveTitle'), message: hb.t('js.common.confirmLeave'), ok: hb.t('js.common.leaveOk')});
    }

    /** 목록 다시 읽기 — 그리드가 아직 준비 전이면 건너뛴다 @param {unknown} [keepId] */
    function reloadGrid(keepId) {
        return grid ? grid.reload(false, keepId) : Promise.resolve();
    }

    /** 번호 끝 숫자 +1 (12 → 13, 12A → 12A 그대로, A-09 → A-10) @param {string | null | undefined} no */
    function nextNo(no) {
        var m = /^(.*?)(\d+)$/.exec(no || '');
        if (!m) { return ''; }
        var n = String(Number(m[2]) + 1);
        while (n.length < m[2].length) { n = '0' + n; }
        return m[1] + n;
    }

    /** 버튼(권한에 따라 없을 수 있음)에 클릭 연결 — 폼 준비 전 클릭은 무시 @param {string} id @param {(b: HTMLButtonElement) => void} fn */
    function bind(id, fn) {
        var b = /** @type {HTMLButtonElement | null} */ (document.getElementById(id));
        if (b) {
            var btn = b;
            btn.addEventListener('click', function () { if (formReady) { fn(btn); } });
        }
    }

    bind('newBtn', function () {
        confirmLeave().then(function (ok) {
            if (!ok) { return; }
            setForm(null);
            hb.page.openDetail();
            field('itemCd').focus();
        });
    });
    bind('newBtnList', function () {
        confirmLeave().then(function (ok) {
            if (!ok) { return; }
            setForm(null);
            hb.page.openDetail();
            field('itemCd').focus();
        });
    });
    bind('nextBtn', function () {
        confirmLeave().then(function (ok) {
            if (!ok) { return; }
            var base = body();
            var keep = {charType: base.charType, itemCd: base.itemCd, itemNm: itemNm.textContent, procCd: base.procCd,
                dataType: base.dataType, unitCd: base.unitCd, unitNm: base.unitNm, scale: base.scale, specialCls: 'NONE', source: base.source,
                drawingNo: base.drawingNo, drawingRev: base.drawingRev, sortNo: base.sortNo === null ? null : base.sortNo + 10,
                charNo: nextNo(base.charNo), useYn: 'Y'};
            setForm(keep);
            hb.page.openDetail();
            field('charNm').focus();
        });
    });

    field('itemCd').addEventListener('change', function () {
        var cd = field('itemCd').value.trim();
        if (!cd || current) { itemNm.textContent = ''; return; }
        // 늦게 온 응답: 그 사이 다른 특성을 골랐거나 품목 코드를 바꿨으면 품목명을 덮지 않는다(앞 요청은 STALE 로 거부됨)
        function stillThis() { return !current && field('itemCd').value.trim() === cd; }
        hb.latest('itemLookup', hb.api(API + '/item?itemCd=' + encodeURIComponent(cd))).then(function (res) {
            if (!stillThis()) { return; }
            itemNm.textContent = res.data.itemNm + (res.data.useYn === 'Y' ? '' : ' — ' + hb.t('js.mstChar.inactive'));
        }, function (err) {
            if (err.stale || !stillThis()) { return; }
            itemNm.textContent = hb.t('js.mstChar.itemNotFound');
        });
    });
    field('dataType').addEventListener('change', layout);
    field('charType').addEventListener('change', layout);

    bind('saveBtn', function (btn) {
        var bad = NUMS.some(function (n) { return num[n].validate && !num[n].validate().ok; });
        if (bad) { return; }
        var b = body();
        var editId = current ? current.charId : null;   // 저장 중에 다른 행·신규를 골라도 이 특성으로 마무리
        hb.busy(btn, function () {
            return editId !== null ? hb.api(API + '/' + editId, {method: 'PUT', body: b}) : hb.api(API, {method: 'POST', body: b});
        }).then(function (res) {
            if (!res) { return; }
            var id = editId !== null ? editId : res.data;
            hb.dirty.clear();
            return reloadGrid(id).then(function () {
                if (!grid || !grid.selected()) { return load(id, true); }
            }).then(function () { notify(hb.t('js.common.saved')); });
        }, function (err) {
            notify(err.message, true);
            hb.showFieldErrors(form, err.errors);
        });
    });

    // ---------- 시작 ----------
    hb.dirty.watch(form);
    search.addEventListener('submit', function (e) { e.preventDefault(); });
    hb.loadMessages().then(function () {
        hb.page.init();
        NUMS.forEach(function (n) { num[n] = hb.number(/** @type {HTMLInputElement} */ (field(n))); });
        SELS.forEach(function (n) { sel[n] = hb.select(selField(n)); });
        return Promise.all([hb.api(API + '/procs'), Promise.all(SELS.map(function (n) { return sel[n].ready; }))]);
    }).then(function (r) {
        var sp1 = /** @type {HTMLSelectElement} */ (document.getElementById('searchProc'));
        option(sp1, '', hb.t('js.common.all'));
        option(selField('procCd'), '', hb.t('js.mstChar.noProc'));
        /** @type {CharProc[]} */ (r[0].data).forEach(function (p) {
            option(sp1, p.procCd, p.procCd + ' · ' + p.procNm);
            option(selField('procCd'), p.procCd, p.procCd + ' · ' + p.procNm);
        });
        sp = hb.searchPanel(search, {
            onSearch: function () {
                if (!grid) { return null; }
                var g = grid;
                return confirmLeave().then(function (ok) { return ok ? g.reload(true) : null; });
            }
        });
        return Promise.all([hb.loadSession(), sp.ready]);
    }).then(function () {
        setForm(null);
        formReady = true;
        grid = hb.grid('#charGrid', {
            progCd: PROG, id: 'main', key: 'charId',
            status: {useYn: {Y: 'normal', N: 'neutral'}},                          // 사용 여부 = 상태 배지(docs/40 부록 A)
            rowClass: function (row) { return row.useYn === 'N' ? 'hb-tree-dim' : null; },   // 미사용 행 흐리게
            url: API, columnsUrl: API + '/columns', exportUrl: API + '/export', auditTable: 'qms_char',
            actions: ACTIONS, params: function () { return sp.values(); },
            filters: {specialCls: 'specialCls', charType: 'charType'},
            onFilter: function (param, value) { sp.setValue(param, value); },
            beforeSelect: confirmLeave,
            onSelect: function (row) { load(row.charId); hb.page.openDetail(); },
            notify: notify
        });
        return grid.ready;
    }).then(null, function (err) { notify(err && err.message, true); });
})();
