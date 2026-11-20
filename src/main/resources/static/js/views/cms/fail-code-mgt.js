/*
 * 고장 코드 (cms-fail-code) — CMS 1부 C1 (docs/33 D1).
 * 조회 패널 → 코드 표 → 상세 폼. 유형·코드는 만든 뒤 고정, 원인은 상위 분류 필수, 설비 클래스는 선택(그 클래스 작업지시에서만).
 */
(function () {
    'use strict';

    var API = '/api/cms/fail-codes';
    var ACTIONS = (document.body.getAttribute('data-actions') || '').split(',');
    var CAN_SAVE = ACTIONS.indexOf('SAVE') >= 0;
    var msg = document.getElementById('msg');
    var form = document.getElementById('codeForm');
    var search = document.getElementById('search');
    var formMode = document.getElementById('formMode');
    var current = null, sp, grid, typeSel, catSel, sortNum;
    var names = {type: {}, cat: {}};

    function notify(text, error) {
        msg.textContent = text || '';
        msg.classList.toggle('hb-msg-error', !!error);
    }

    function field(n) { return form.querySelector('[data-field="' + n + '"]'); }

    function option(sel, value, text) {
        var o = hb.el('option', null, text);
        o.value = value;
        sel.appendChild(o);
    }

    function show(c) {
        current = c;
        hb.showFieldErrors(form, []);
        typeSel.setValue(c ? c.codeType : '');
        field('failCd').value = c ? c.failCd : '';
        field('failNm').value = c ? c.failNm : '';
        catSel.setValue(c ? c.failCat : '');
        var cs = field('equipClassCd');
        cs.querySelectorAll('option[data-stale]').forEach(function (o) { o.remove(); });
        cs.value = c && c.equipClassCd ? c.equipClassCd : '';
        if (c && c.equipClassCd && cs.value !== c.equipClassCd) {
            option(cs, c.equipClassCd, c.equipClassCd + ' — ' + hb.t('js.cms.inactive'));   // 사용 중지된 클래스 — 지우지 않고 보여 준다
            cs.lastChild.setAttribute('data-stale', '');
            cs.value = c.equipClassCd;
        }
        sortNum.setValue(c ? String(c.sortNo) : '0');
        field('useYn').value = c ? c.useYn : 'Y';
        field('remark').value = c && c.remark ? c.remark : '';
        field('codeType').disabled = !!c || !CAN_SAVE;
        field('failCd').readOnly = !!c || !CAN_SAVE;
        formMode.textContent = c ? hb.t('js.cms.fail.editing', c.failCd, c.failNm) : hb.t('js.cms.fail.creating');
        hb.dirty.clear();
    }

    /** 조회 조건(검색 패널) — grid 가 page·size·sort 를 붙인다 */
    function params() {
        var v = sp.values();
        var q = {};
        ['codeType', 'keyword', 'useYn'].forEach(function (k) { if (v[k]) { q[k] = v[k]; } });
        return q;
    }

    /** 목록 다시 읽기 — selectId 가 이 쪽에 있으면 grid 가 골라 상세를 연다 (grid 행 = 목록 행이라 그대로 보인다) */
    function load(selectId) {
        return grid.reload(true, selectId || undefined).then(function () {
            var sel = grid.selected();
            if (selectId && (!sel || sel.failId !== selectId)) { show(null); return; }
            if (!selectId) { show(null); }
        });
    }

    function newCode() {
        cmsUi.leaveOk().then(function (ok) {
            if (!ok) { return; }
            show(null);
            hb.page.openDetail();
            field('codeType').focus();
        });
    }
    ['newBtn', 'newBtnList'].forEach(function (id) {
        var b = document.getElementById(id);
        if (b) { b.addEventListener('click', newCode); }
    });

    var saveBtn = document.getElementById('saveBtn');
    if (saveBtn) {
        saveBtn.addEventListener('click', function () {
            var body = {codeType: field('codeType').value, failCd: field('failCd').value, failNm: field('failNm').value,
                failCat: field('failCat').value, equipClassCd: field('equipClassCd').value, sortNo: sortNum.value(),
                useYn: field('useYn').value, remark: field('remark').value, rowVer: current ? current.rowVer : null};
            var editing = !!current;
            hb.busy(saveBtn, function () {
                return editing ? hb.api(API + '/' + current.failId, {method: 'PUT', body: body})
                    : hb.api(API, {method: 'POST', body: body});
            }).then(function (res) {
                if (!res) { return; }
                return load(editing ? current.failId : res.data).then(function () { notify(hb.t('js.common.saved')); });
            }, function (err) {
                notify(err.message, true);
                hb.showFieldErrors(form, err.errors);
            });
        });
    }

    sortNum = hb.number(field('sortNo'));
    if (!CAN_SAVE) {
        form.querySelectorAll('input, select').forEach(function (i) { i.disabled = true; });
    }

    hb.dirty.watch(form);
    hb.loadMessages().then(function () {
        hb.page.init();
        // 빈 칸 문구(data-blank)가 문구 로드 뒤에 채워지도록 여기서 만든다 — 앞에서 만들면 코드 목록이 먼저 오면 키가 그대로 보인다
        typeSel = hb.select(field('codeType'));
        catSel = hb.select(field('failCat'));
        sp = hb.searchPanel(search, {onSearch: function () {
            return cmsUi.leaveOk().then(function (ok) { return ok ? load() : null; });
        }});
        return Promise.all([hb.loadSession(), hb.api(API + '/classes'), hb.codeList('CMS_FAIL_TYPE'), hb.codeList('CMS_FAIL_CAT'),
            typeSel.ready, catSel.ready]);
    }).then(function (r) {
        var cs = field('equipClassCd');
        option(cs, '', hb.t('js.cms.fail.allClasses'));
        r[1].data.forEach(function (c) { option(cs, c.code, c.code + ' · ' + c.name); });
        r[2].forEach(function (c) { names.type[c.code] = c.name; });
        r[3].forEach(function (c) { names.cat[c.code] = c.name; });
        grid = hb.grid('#failGrid', {
            progCd: 'cms-fail-code', id: 'main', key: 'failId',
            url: API + '/grid', columnsUrl: API + '/columns', exportUrl: API + '/export', actions: ACTIONS, params: params,
            filters: {codeType: 'codeType', useYn: 'useYn'},
            onFilter: function (param, value) { sp.setValue(param, value); },
            beforeSelect: function () { return cmsUi.leaveOk(); },
            status: {useYn: {Y: 'normal', N: 'neutral'}},
            rowClass: function (row) { return row.useYn === 'N' ? 'hb-tree-dim' : null; },
            onSelect: function (row) { show(row); hb.page.openDetail(); },
            notify: notify
        });
        return grid.ready.then(function () { show(null); });   // grid 가 첫 쪽을 이미 읽었다 — 다시 읽지 않는다
    }).then(null, function (err) { notify(err && err.message, true); });
})();
