/*
 * 점검 실행 (cms-chk-run) — CMS 1부 C2 (docs/33 4.3, D7).
 * 예정일 기간 조회 → 표 → 결과 입력(cmsResults) + 결과 저장 / 완료·건너뛰기(CHECK 방식만) / NG 후속 작업지시.
 */
(function () {
    'use strict';

    var API = '/api/cms/chk-runs';
    var ACTIONS = (document.body.getAttribute('data-actions') || '').split(',');
    var CAN_SAVE = ACTIONS.indexOf('SAVE') >= 0;
    var msg = document.getElementById('msg');
    var search = document.getElementById('search');
    var resBody = document.querySelector('#results tbody');
    var current = null, sp, grid;
    var names = {status: {}, mode: {}, skip: {}};

    function notify(text, error) {
        msg.textContent = text || '';
        msg.classList.toggle('hb-msg-error', !!error);
    }

    function option(sel, value, text) {
        var o = hb.el('option', null, text);
        o.value = value;
        sel.appendChild(o);
    }

    function stateName(r) {
        if (r.status === 'OPEN') {
            return {UPCOMING: hb.t('js.cms.state.UPCOMING'), DUE: hb.t('js.cms.state.DUE'), OVERDUE: hb.t('js.cms.state.OVERDUE')}[r.state] || '';
        }
        if (r.status === 'DONE') { return r.onTime ? hb.t('js.cms.run.onTime') : hb.t('js.cms.run.late'); }
        return names.status[r.status] || r.status;
    }

    /** 사용량 주기 예정: 예정 사용량(·완료 때 누적) — 달력 예정은 빈 글자 */
    function usageText(r) {
        if (!r.dueUsageText) { return ''; }
        var u = r.counterUnit ? ' ' + r.counterUnit : '';
        return r.doneUsageText ? hb.t('js.cms.run.usageDone', r.dueUsageText + u, r.doneUsageText + u)
            : hb.t('js.cms.run.usageDue', r.dueUsageText + u, (r.counterTotalText || '') + u);
    }

    function toggle(id, on) {
        var b = document.getElementById(id);
        if (b) { b.hidden = !on; }
    }

    function show(r) {
        current = r;
        var open = r && r.status === 'OPEN';
        var check = r && r.execMode === 'CHECK';
        toggle('saveBtn', open);
        toggle('completeBtn', open && check);
        toggle('skipBtn', open && check);
        document.getElementById('formMode').textContent = r
            ? hb.t('js.cms.run.editing', r.planCd, r.targetCd + ' · ' + r.targetNm, r.dueDate) : hb.t('js.cms.run.select');
        var parts = [];
        if (r) {
            parts.push(names.mode[r.execMode] || r.execMode, stateName(r));
            if (r.dueUsageText) { parts.push(usageText(r)); }
            if (r.mwoNo) { parts.push(hb.t('js.cms.run.viaMwo', r.mwoNo)); }
            if (r.performedAt) { parts.push(hb.t('js.cms.run.performed', r.performedNm || '', String(r.performedAt).substring(0, 16).replace('T', ' '))); }
            if (r.skipReason) { parts.push(hb.t('js.cms.mwo.cancelled', skipText(r))); }
        }
        document.getElementById('stateLine').textContent = parts.join(' · ');
        cmsResults.render(resBody, r, {editable: CAN_SAVE && open, canFollowUp: CAN_SAVE, onFollowUp: followUp});
    }

    /** 건너뛰기 사유 — 코드가 있으면 "코드 이름: 메모"(메모가 이름 그대로면 이름만), 없으면 글자(미분류·작업지시 취소) */
    function skipText(r) {
        if (!r.skipReasonCd) { return r.skipReason; }
        var name = names.skip[r.skipReasonCd] || r.skipReasonCd;
        return r.skipReason && r.skipReason !== name ? name + ': ' + r.skipReason : name;
    }

    function followUp(seq, btn) {
        hb.busy(btn, function () {
            return hb.api(API + '/' + current.runId + '/follow-up', {method: 'POST', body: {seq: seq}});
        }).then(function (res) {
            if (res) { notify(hb.t('js.cms.mwo.created', res.data.mwoNo)); }
        }, function (err) { notify(err.message, true); });
    }

    /** 조회 조건(검색 패널) — grid 가 page·size·sort 를 붙인다 */
    function params() {
        var v = sp.values();
        var q = {};
        ['from', 'to', 'status', 'execMode', 'plantCd', 'keyword'].forEach(function (k) { if (v[k]) { q[k] = v[k]; } });
        return q;
    }

    function open(id) {
        return hb.latest('detail', hb.api(API + '/' + id)).then(function (r) {
            show(r.data);
            hb.page.openDetail();
        }, function (err) { notify(err.message, true); });
    }

    /** 목록 다시 읽기 — 고른 실행(selectId)은 이 쪽에 없어도 상세로 */
    function load(selectId) {
        return grid.reload(true, selectId || undefined).then(function () {
            var sel = grid.selected();
            if (selectId && (!sel || sel.runId !== selectId)) { return open(selectId); }
            if (!selectId) { show(null); }
        });
    }

    function act(btn, url, body) {
        hb.busy(btn, function () {
            return hb.api(url, {method: 'POST', body: body});
        }).then(function (res) {
            if (!res) { return; }
            return load(current.runId).then(function () { notify(hb.t('js.common.saved')); });
        }, function (err) {
            notify(err.message, true);
            cmsResults.showErrors(resBody, err.errors);
        });
    }

    var saveBtn = document.getElementById('saveBtn');
    if (saveBtn) {
        saveBtn.addEventListener('click', function () {
            var rows = cmsResults.collect(resBody);
            if (!rows.length) { notify(hb.t('js.cms.run.nothing')); return; }
            hb.busy(saveBtn, function () {
                return hb.api(API + '/' + current.runId + '/results', {method: 'PUT', body: {rowVer: current.rowVer, results: rows}});
            }).then(function (res) {
                if (!res) { return; }
                return load(current.runId).then(function () { notify(hb.t('js.common.saved')); });
            }, function (err) {
                notify(err.message, true);
                cmsResults.showErrors(resBody, err.errors);
            });
        });
    }
    var completeBtn = document.getElementById('completeBtn');
    if (completeBtn) {
        completeBtn.addEventListener('click', function () {
            if (cmsResults.collect(resBody).length) { notify(hb.t('js.cms.run.saveFirst'), true); return; }
            act(completeBtn, API + '/' + current.runId + '/complete', {rowVer: current.rowVer});
        });
    }
    var skipBtn = document.getElementById('skipBtn');
    if (skipBtn) {
        skipBtn.addEventListener('click', function () {
            // 사유 코드(필수, 집계용) + 메모(선택) — docs/68 Q1
            hb.dialog.reasonCode({title: hb.t('js.cms.dlg.skip'), code: 'CMS_SKIP_REASON', codeLabel: hb.t('js.cms.run.skipReasonCd'),
                label: hb.t('js.common.memo'), ok: hb.t('js.cms.dlg.skipOk'), danger: true, maxLength: 500}).then(function (r) {
                if (r === null) { return; }
                act(skipBtn, API + '/' + current.runId + '/skip', {rowVer: current.rowVer, reasonCd: r.code, reason: r.memo || null});
            });
        });
    }

    search.addEventListener('submit', function (e) { e.preventDefault(); });
    hb.loadMessages().then(function () {
        hb.page.init();
        return Promise.all([hb.loadSession(), hb.codeNames('CMS_SKIP_REASON'), hb.codeList('CMS_RUN_STATUS'), hb.codeList('CMS_EXEC_MODE')]);
    }).then(function (r) {
        r[2].forEach(function (c) { names.status[c.code] = c.name; });
        r[3].forEach(function (c) { names.mode[c.code] = c.name; });
        names.skip = r[1] || {};   // 건너뛰기 사유 코드 이름(사용 중지 포함, docs/68 Q1)
        var ps = document.getElementById('plantSearch');
        option(ps, '', hb.t('js.cms.allPlants'));
        return hb.api('/api/cms/chk-runs/plants').then(function (p) {
            p.data.forEach(function (x) { option(ps, x.code, x.code + ' · ' + x.name); });
        }, function () { /* 공장 선택지는 보조 */ });
    }).then(function () {
        sp = hb.searchPanel(search, {onSearch: function () { return load(); }});
        grid = hb.grid('#runGrid', {
            progCd: 'cms-chk-run', id: 'main', key: 'runId',
            url: API + '/grid', columnsUrl: API + '/columns', exportUrl: API + '/export', actions: ACTIONS, params: params,
            filters: {status: 'status', execMode: 'execMode'},
            format: {state: function (v) {   // 열림 = 예정 상태, 완료 = 기한 안·늦음(서버 grid 가 ON_TIME·LATE 로)
                return {UPCOMING: hb.t('js.cms.state.UPCOMING'), DUE: hb.t('js.cms.state.DUE'), OVERDUE: hb.t('js.cms.state.OVERDUE'),
                    ON_TIME: hb.t('js.cms.run.onTime'), LATE: hb.t('js.cms.run.late')}[v] || '';
            }},
            onFilter: function (param, value) { sp.setValue(param, value); },
            status: {
                status: cmsUi.TONES.run,
                state: {UPCOMING: 'neutral', DUE: 'warning', OVERDUE: 'danger', ON_TIME: 'success', LATE: 'warning'},
                result: {OK: 'success', NG: 'danger'}
            },
            rowClass: function (row) { return row.status === 'OPEN' ? null : 'hb-tree-dim'; },
            onSelect: function (row) { open(row.runId); },
            notify: notify
        });
        return grid.ready.then(function () { show(null); });   // grid 가 첫 쪽을 이미 읽었다 — 다시 읽지 않는다
    }).then(null, function (err) { notify(err && err.message, true); });
})();
