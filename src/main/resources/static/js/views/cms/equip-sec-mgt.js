/*
 * 설비 보안 대장 (cms-equip-sec) — CMS 1부 C5 (docs/33 4.6).
 * hb.grid(사용 중 설비 전체 + 보안 행, 경고 — docs/40 10.2) → 상세 폼(처음 저장 = 등록) + 엑셀(같은 조건). 경고 판정은 서버(SecWarn).
 */
(function () {
    'use strict';

    var API = '/api/cms/equip-secs';
    var ACTIONS = (document.body.getAttribute('data-actions') || '').split(',');
    var CAN_SAVE = ACTIONS.indexOf('SAVE') >= 0;
    var msg = document.getElementById('msg');
    var form = document.getElementById('secForm');
    var search = document.getElementById('search');
    var current = null, sp, grid, sels = {};
    var names = {warn: {}, level: {}};
    var FIELDS = ['networkYn', 'osNm', 'osVer', 'osEosDate', 'lastScanDate', 'scanTool', 'targetSl', 'currentSl', 'usbCtrlYn',
        'remoteAccess', 'remoteText', 'reviewDate', 'remark'];

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

    function warnText(r) { return (r.warns || []).map(function (w) { return names.warn[w] || w; }).join(', '); }

    function show(r) {
        current = r;
        hb.showFieldErrors(form, []);
        form.hidden = !r;
        document.getElementById('formMode').textContent = r
            ? hb.t(r.secId ? 'js.cms.sec.editing' : 'js.cms.sec.registering', r.equipCd, r.equipNm) : hb.t('js.cms.sec.select');
        document.getElementById('warnLine').textContent = r ? warnText(r) : '';
        if (r) {
            FIELDS.forEach(function (n) {
                var v = r[n];
                if (n === 'targetSl' || n === 'currentSl' || n === 'remoteAccess') {
                    sels[n].setValue(v || (n === 'remoteAccess' ? 'NONE' : ''));
                } else {
                    field(n).value = v === null || v === undefined ? (n === 'networkYn' || n === 'usbCtrlYn' ? 'N' : '') : v;
                }
            });
        }
        hb.dirty.clear();
    }

    function body() {
        var b = {rowVer: current && current.secId ? current.rowVer : null};
        FIELDS.forEach(function (n) { b[n] = field(n).value; });
        return b;
    }

    function cond() {
        var v = sp.values();
        return {keyword: v.keyword || null, plantCd: v.plantCd || null, warn: v.warn || null};
    }

    /** 조회 조건(검색 패널) — grid 가 page·size·sort 를 붙인다 */
    function params() {
        var c = cond();
        var q = {};
        Object.keys(c).forEach(function (k) { if (c[k]) { q[k] = c[k]; } });
        return q;
    }

    /** 행 → 상세(서버에서 다시 읽는다) */
    function openRow(id) {
        return hb.latest('detail', hb.api(API + '/' + id)).then(function (d) {
            show(d.data);
            hb.page.openDetail();
        }, function (err) { notify(err.message, true); });
    }

    /** 목록 다시 읽기(docs/40 10.2 — 표 → hb.grid). selectId 가 이 쪽에 있으면 grid 가 골라 상세를 연다, 다른 쪽이면 상세만 */
    function load(selectId) {
        return grid.reload(true, selectId || undefined).then(function () {
            var sel = grid.selected();
            if (selectId && (!sel || sel.equipId !== selectId)) { return openRow(selectId); }
            if (!selectId) { show(null); }
        });
    }

    var saveBtn = document.getElementById('saveBtn');
    if (saveBtn) {
        saveBtn.addEventListener('click', function () {
            if (!current) { return; }
            var id = current.equipId;
            hb.busy(saveBtn, function () {
                return hb.api(API + '/' + id, {method: 'PUT', body: body()});
            }).then(function (res) {
                if (!res) { return; }
                return load(id).then(function () { notify(hb.t('js.common.saved')); });
            }, function (err) {
                notify(err.message, true);
                hb.showFieldErrors(form, err.errors);
            });
        });
    }

    var excelBtn = document.getElementById('excelBtn');
    if (excelBtn) {
        excelBtn.addEventListener('click', function () {
            hb.busy(excelBtn, function () { return hb.download(API + '/export', cond()); })
                .then(null, function (err) { notify(err.message, true); });
        });
    }

    if (!CAN_SAVE) {
        form.querySelectorAll('input, select').forEach(function (i) { i.disabled = true; });
    }
    hb.dirty.watch(form);
    hb.loadMessages().then(function () {
        hb.page.init();
        // 빈 칸 문구(data-blank)가 문구 로드 뒤에 채워지도록 여기서 만든다 — 앞에서 만들면 코드 목록이 먼저 오면 키가 그대로 보인다
        ['targetSl', 'currentSl', 'remoteAccess'].forEach(function (n) { sels[n] = hb.select(field(n)); });
        return Promise.all([hb.loadSession(), hb.api(API + '/plants'), hb.codeList('CMS_SEC_WARN'),
            sels.targetSl.ready, sels.currentSl.ready, sels.remoteAccess.ready]);
    }).then(function (r) {
        var ps = document.getElementById('plantSearch');
        option(ps, '', hb.t('js.cms.allPlants'));
        r[1].data.forEach(function (p) { option(ps, p.code, p.code + ' · ' + p.name); });
        var ws = document.getElementById('warnSearch');
        option(ws, '', hb.t('js.common.all'));
        option(ws, 'ANY', hb.t('js.cms.sec.anyWarn'));
        r[2].forEach(function (c) {
            names.warn[c.code] = c.name;
            option(ws, c.code, c.name);
        });
        sp = hb.searchPanel(search, {onSearch: function () {
            return cmsUi.leaveOk().then(function (ok) { return ok ? load() : null; });
        }});
        grid = hb.grid('#secGrid', {
            progCd: 'cms-equip-sec', id: 'main', key: 'equipId',
            url: API + '/grid', columnsUrl: API + '/columns', actions: ACTIONS, params: params,
            format: {osNm: function (v, row) { return [row.osNm, row.osVer].filter(Boolean).join(' '); }},
            beforeSelect: function () { return cmsUi.leaveOk(); },
            // 경고가 있으면 ▲ 확인 필요 톤, 없으면 배지 없이 빈 칸(docs/40 부록 A.1)
            status: {warnText: function (v, row) { return row.warns && row.warns.length ? 'warning' : null; }},
            onSelect: function (row) { openRow(row.equipId); },
            notify: notify
        });
        return grid.ready.then(function () { show(null); });   // grid 가 첫 쪽을 이미 읽었다 — 다시 읽지 않는다
    }).then(null, function (err) { notify(err && err.message, true); });
})();
