/*
 * 설비 알람 정의 (cms-alarm-def) — CMS 2부 C8 (docs/33 11.6, D22).
 * 표(코드·클래스·이름·심각도·자동 신고·사용) → 상세 폼(코드·클래스는 만든 뒤 고정). 자동 고장 신고는 중대(CRITICAL)만 — 최종 판정은 서버.
 */
(function () {
    'use strict';

    var API = '/api/cms/alarm-defs';
    var ACTIONS = (document.body.getAttribute('data-actions') || '').split(',');
    var CAN_SAVE = ACTIONS.indexOf('SAVE') >= 0;
    var msg = document.getElementById('msg');
    var form = document.getElementById('defForm');
    var search = document.getElementById('search');
    var current = null, sp, grid;

    function notify(text, error) {
        msg.textContent = text || '';
        msg.classList.toggle('hb-msg-error', !!error);
    }

    function field(n) { return form.querySelector('[data-field="' + n + '"]'); }

    function syncAuto() {
        var critical = field('severity').value === 'CRITICAL';
        if (!critical) { field('autoBmYn').value = 'N'; }
        field('autoBmYn').disabled = !critical || !CAN_SAVE;
    }

    function show(d) {
        current = d;
        hb.showFieldErrors(form, []);
        field('alarmCd').value = d ? d.alarmCd : '';
        field('classCd').value = d && d.classCd ? d.classCd : '';
        field('alarmNm').value = d ? d.alarmNm : '';
        field('severity').value = d ? d.severity : 'WARN';
        field('symptomCd').value = d && d.symptomCd ? d.symptomCd : '';
        field('autoBmYn').value = d ? d.autoBmYn : 'N';
        field('useYn').value = d ? d.useYn : 'Y';
        field('remark').value = d && d.remark ? d.remark : '';
        ['alarmCd', 'classCd'].forEach(function (n) { field(n).disabled = !!d || !CAN_SAVE; });
        syncAuto();
        document.getElementById('formMode').textContent = d
            ? hb.t('js.cms.alarmDef.editing', d.alarmCd, d.classCd ? d.classCd + ' · ' + d.classNm : hb.t('js.cms.alarmDef.allClasses'))
            : hb.t('js.cms.alarmDef.creating');
        hb.dirty.clear();
    }

    function body() {
        return {alarmCd: field('alarmCd').value, classCd: field('classCd').value, alarmNm: field('alarmNm').value,
            severity: field('severity').value, symptomCd: field('symptomCd').value, autoBmYn: field('autoBmYn').value,
            useYn: field('useYn').value, remark: field('remark').value, rowVer: current ? current.rowVer : null};
    }

    /** 조회 조건(검색 패널) — grid 가 page·size·sort 를 붙인다 */
    function params() {
        var v = sp.values();
        var q = {};
        ['keyword', 'classCd', 'useYn'].forEach(function (k) { if (v[k]) { q[k] = v[k]; } });
        return q;
    }

    /** 목록 다시 읽기 — selectId 가 이 쪽에 있으면 grid 가 골라 상세를 연다(grid 행 = 목록 행이라 그대로 보인다) */
    function load(selectId) {
        return grid.reload(true, selectId || undefined).then(function () {
            var sel = grid.selected();
            if (!selectId || !sel || sel.defId !== selectId) { show(null); }
        });
    }

    ['newBtn', 'newBtnList'].forEach(function (id) {
        var b = document.getElementById(id);
        if (b) {
            b.addEventListener('click', function () {
                cmsUi.leaveOk().then(function (ok) {
                    if (!ok) { return; }
                    show(null);
                    hb.page.openDetail();
                    field('alarmCd').focus();
                });
            });
        }
    });

    var saveBtn = document.getElementById('saveBtn');
    if (saveBtn) {
        saveBtn.addEventListener('click', function () {
            var editing = !!current;
            hb.busy(saveBtn, function () {
                return editing ? hb.api(API + '/' + current.defId, {method: 'PUT', body: body()}) : hb.api(API, {method: 'POST', body: body()});
            }).then(function (res) {
                if (!res) { return; }
                return load(editing ? current.defId : res.data).then(function () { notify(hb.t('js.common.saved')); });
            }, function (err) {
                notify(err.message, true);
                hb.showFieldErrors(form, err.errors);
            });
        });
    }

    field('severity').addEventListener('change', syncAuto);
    if (!CAN_SAVE) {
        form.querySelectorAll('input, select').forEach(function (i) { i.disabled = true; });
    }
    hb.dirty.watch(form);
    hb.loadMessages().then(function () {
        hb.page.init();
        return Promise.all([hb.codeList('CMS_ALARM_SEV'), hb.api(API + '/classes'), hb.api(API + '/symptoms')]);
    }).then(function (r) {
        r[0].forEach(function (c) {
            var o = hb.el('option', null, c.name);
            o.value = c.code;
            document.getElementById('sevForm').appendChild(o);
        });
        r[1].data.forEach(function (c) {
            ['classSearch', 'classForm'].forEach(function (id) {
                var o = hb.el('option', null, c.code + ' · ' + c.name);
                o.value = c.code;
                document.getElementById(id).appendChild(o);
            });
        });
        var dl = document.getElementById('symptomList');
        r[2].data.forEach(function (s) {
            var o = hb.el('option', null, s.name);
            o.value = s.code;
            dl.appendChild(o);
        });
        sp = hb.searchPanel(search, {onSearch: function () {
            return cmsUi.leaveOk().then(function (ok) { return ok ? load() : null; });
        }});
        grid = hb.grid('#defGrid', {
            progCd: 'cms-alarm-def', id: 'main', key: 'defId',
            url: API + '/grid', columnsUrl: API + '/columns', exportUrl: API + '/export', actions: ACTIONS, params: params,
            filters: {severity: 'severity', useYn: 'useYn'},
            onFilter: function (param, value) { sp.setValue(param, value); },
            format: {autoBmYn: function (v) { return v === 'Y' ? hb.t('js.cms.alarmDef.autoMark') : ''; }},   // 클래스 없음 = 모든 클래스(빈 칸)
            beforeSelect: function () { return cmsUi.leaveOk(); },
            status: {severity: cmsUi.TONES.severity, useYn: {Y: 'normal', N: 'neutral'}},
            rowClass: function (row) { return row.useYn === 'N' ? 'hb-tree-dim' : null; },
            onSelect: function (row) { show(row); hb.page.openDetail(); },
            notify: notify
        });
        return grid.ready.then(function () { show(null); });   // grid 가 첫 쪽을 이미 읽었다 — 다시 읽지 않는다
    }).then(null, function (err) { notify(err && err.message, true); });
})();
