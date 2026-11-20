/*
 * 샘플링 계획 · AQL 표 (qms-sampling) — P3 QMS Q1 (docs/29).
 *   보기 1 = hb.grid(샘플링 계획) + 상세 폼 + 판정 미리보기 / 보기 2 = AQL 표 두 개(엑셀 올리기 + 조회)
 * 방식에 따라 칸을 보이고 숨긴다(FIXED = n·Ac, ALL = Ac, PERCENT = 비율·Ac, AQL = 표준·수준·엄격도·AQL).
 * 확정 검사계획이 쓰는 계획은 방식·수치 칸을 잠근다(서버·트리거가 최종). 서버 데이터는 textContent·value 로만 넣는다.
 */
(function () {
    'use strict';

    /** QMS 화면 공용 — 상태 배지·대화상자(qms-ui.js) */
    var qmsUi = /** @type {QmsUi} */ (/** @type {any} */ (window).qmsUi);

    var PROG = document.body.getAttribute('data-prog');
    var ACTIONS = (document.body.getAttribute('data-actions') || '').split(',');
    var CAN_SAVE = ACTIONS.indexOf('SAVE') >= 0;
    var API = '/api/qms/sampling-plans';
    var AQL = '/api/qms/aql';
    var msg = document.getElementById('msg');
    var search = document.getElementById('search');
    var form = document.getElementById('samplingForm');
    var formMode = document.getElementById('formMode');
    var lockNote = document.getElementById('lockNote');
    var previewForm = document.getElementById('previewForm');
    var previewOut = document.getElementById('previewOut');
    var current = null;
    var grid, sp, num = {}, sel = {}, pnum = {};
    var stds = [], methodNames = {}, severityNames = {}, levelNames = {};
    var NUMS = ['fixedN', 'pct', 'aql', 'ac', 'sortNo'];
    var SELS = ['method', 'inspLevel', 'severity'];
    var RULES = ['method', 'fixedN', 'pct', 'stdCd', 'inspLevel', 'severity', 'aql', 'ac'];

    function notify(text, error) {
        msg.textContent = text || '';
        msg.classList.toggle('hb-msg-error', !!error);
    }

    function field(n) { return form.querySelector('[data-field="' + n + '"]'); }

    function option(s, value, text) {
        var o = hb.el('option', null, text);
        o.value = value;
        s.appendChild(o);
    }

    function fillStds(s, blankText, keep) {
        s.textContent = '';
        if (blankText !== null) { option(s, '', blankText); }
        stds.forEach(function (c) { option(s, c, c); });
        if (keep && stds.indexOf(keep) < 0) {
            // 표가 지금 없는 표준(사용 중지·삭제) — 그대로 보여 준다
            option(s, keep, keep + ' — ' + hb.t('js.qms.noTable'));
        }
        if (keep) { s.value = keep; }
    }

    /** 방식에 맞는 칸만 보인다 */
    function layout() {
        var m = sel.method.value();
        form.querySelectorAll('.hb-qms-m').forEach(function (l) {
            l.hidden = !m || l.getAttribute('data-methods').split(' ').indexOf(m) < 0;
        });
    }

    function setForm(r) {
        current = r && r.samplingPlanId ? r : null;
        hb.showFieldErrors(form, []);
        ['planCd', 'planNm', 'remark'].forEach(function (n) { field(n).value = r && r[n] ? r[n] : ''; });
        num.fixedN.setValue(r ? r.fixedN : null);
        num.pct.setValue(r ? r.pct : null);
        num.aql.setValue(r ? r.aql : null);
        num.ac.setValue(r && r.ac !== null && r.ac !== undefined ? r.ac : 0);
        num.sortNo.setValue(r ? r.sortNo : 0);
        sel.method.setValue(r ? r.method : '');
        sel.inspLevel.setValue(r && r.inspLevel ? r.inspLevel : 'II');
        sel.severity.setValue(r && r.severity ? r.severity : 'NORMAL');
        fillStds(field('stdCd'), hb.t('js.common.choose'), r ? r.stdCd : null);
        if (!r && stds.length === 1) { field('stdCd').value = stds[0]; }
        field('useYn').value = r ? r.useYn : 'Y';
        var locked = !!(current && current.lockedCnt > 0);
        lockNote.hidden = !locked;
        lockNote.textContent = locked ? hb.t('js.qms.sampling.locked', current.lockedCnt) : '';
        form.querySelectorAll('input').forEach(function (i) { i.readOnly = !CAN_SAVE; });
        form.querySelectorAll('select').forEach(function (s) { s.disabled = !CAN_SAVE; });
        field('planCd').readOnly = !!current || !CAN_SAVE;
        RULES.forEach(function (n) {
            var f = field(n);
            if (f.tagName === 'SELECT') { f.disabled = f.disabled || locked; } else { f.readOnly = f.readOnly || locked; }
        });
        formMode.textContent = current ? hb.t('js.qms.sampling.editing', current.planCd) : hb.t('js.qms.sampling.creating');
        previewOut.textContent = '';
        layout();
        hb.dirty.clear();
    }

    function body() {
        var b = {planCd: field('planCd').value, planNm: field('planNm').value, method: sel.method.value(),
            fixedN: num.fixedN.numberValue(), pct: num.pct.value(), stdCd: field('stdCd').value || null,
            inspLevel: sel.inspLevel.value(), severity: sel.severity.value(), aql: num.aql.value(), ac: num.ac.numberValue(),
            sortNo: num.sortNo.numberValue(), useYn: field('useYn').value, remark: field('remark').value};
        if (current) { b.rowVer = current.rowVer; }
        return b;
    }

    function load(id, keepMsg) {
        if (!keepMsg) { notify(''); }
        return hb.latest('detail', hb.api(API + '/' + id)).then(function (res) { setForm(res.data); },
            function (err) { notify(err.message, true); });
    }

    function confirmLeave() {
        return qmsUi.leaveOk(hb.dirty.isDirty());
    }

    function bind(id, fn) {
        var b = document.getElementById(id);
        if (b) { b.addEventListener('click', function () { fn(b); }); }
    }

    function newPlan() {
        confirmLeave().then(function (ok) {
            if (!ok) { return; }
            setForm(null);
            hb.page.openDetail();
            field('planCd').focus();
        });
    }
    bind('newBtn', newPlan);
    bind('newBtnList', newPlan);
    field('method').addEventListener('change', layout);

    bind('saveBtn', function (btn) {
        var bad = NUMS.some(function (n) { return !num[n].validate().ok; });
        if (bad) { return; }
        var b = body();
        var editing = !!current;
        hb.busy(btn, function () {
            return editing ? hb.api(API + '/' + current.samplingPlanId, {method: 'PUT', body: b}) : hb.api(API, {method: 'POST', body: b});
        }).then(function (res) {
            if (!res) { return; }
            var id = editing ? current.samplingPlanId : res.data;
            hb.dirty.clear();
            return grid.reload(false, id).then(function () {
                if (!grid.selected()) { return load(id, true); }
            }).then(function () { notify(hb.t('js.common.saved')); });
        }, function (err) {
            notify(err.message, true);
            hb.showFieldErrors(form, err.errors);
        });
    });

    // ---------- 판정 미리보기 (지금 폼 값으로 — 저장 전에도) ----------
    function verdictText(v) {
        if (v === 'ACCEPT') { return hb.t('js.qms.verdict.ACCEPT'); }
        if (v === 'REJECT') { return hb.t('js.qms.verdict.REJECT'); }
        if (v === 'ACCEPT_TO_NORMAL') { return hb.t('js.qms.verdict.ACCEPT_TO_NORMAL'); }
        return '';
    }

    previewForm.addEventListener('submit', function (e) {
        e.preventDefault();
        if (!pnum.lotQty.validate().ok || !pnum.defects.validate().ok) { return; }
        var b = body();
        var req = {form: {method: b.method, fixedN: b.fixedN, pct: b.pct, stdCd: b.stdCd, inspLevel: b.inspLevel,
            severity: b.severity, aql: b.aql, ac: b.ac}, lotQty: pnum.lotQty.numberValue(), defects: pnum.defects.numberValue()};
        hb.showFieldErrors(previewForm, []);
        hb.showFieldErrors(form, []);
        previewOut.textContent = '';
        hb.busy(document.getElementById('previewBtn'), function () {
            return hb.api(API + '/preview', {method: 'POST', body: req});
        }).then(function (res) {
            if (!res) { return; }
            var r = res.data;
            var text = hb.t('js.qms.sampling.result', r.lotQty, r.sampleSize, r.ac, r.re)
                + (r.codeLetter ? ' · ' + hb.t('js.qms.sampling.letter', r.codeLetter) : '')
                + (r.all ? ' · ' + hb.t('js.qms.sampling.all') : '');
            if (r.verdict) { text += ' → ' + hb.t('js.qms.sampling.defectsOf', r.defects) + ' ' + verdictText(r.verdict); }
            previewOut.textContent = text;
            previewOut.classList.toggle('hb-msg-error', r.verdict === 'REJECT');
        }, function (err) {
            var errs = err.errors || [];
            hb.showFieldErrors(previewForm, errs.filter(function (x) { return x.field === 'lotQty' || x.field === 'defects'; }));
            hb.showFieldErrors(form, errs.filter(function (x) { return x.field !== 'lotQty' && x.field !== 'defects'; }));
            previewOut.textContent = err.message;
            previewOut.classList.add('hb-msg-error');
        });
    });

    // ---------- AQL 표 보기 ----------
    var aqlStd = document.getElementById('aqlStd');

    function cells(tr, values) {
        values.forEach(function (v, i) {
            var td = hb.el('td', i > 0 && typeof v === 'number' ? 'hb-num' : null, v === null || v === undefined ? '' : String(v));
            tr.appendChild(td);
        });
    }

    function renderAql(view) {
        var lt = document.querySelector('#letterTable tbody');
        var pt = document.querySelector('#planTable tbody');
        lt.textContent = '';
        pt.textContent = '';
        document.getElementById('aqlEmpty').hidden = !!(view && (view.letters.length || view.rows.length));
        if (!view) { return; }
        view.letters.forEach(function (l) {
            var tr = hb.el('tr', l.useYn === 'Y' ? null : 'hb-tree-dim');
            cells(tr, [levelNames[l.inspLevel] || l.inspLevel, l.lotFrom, l.lotTo === null ? hb.t('js.qms.aql.open') : l.lotTo,
                l.codeLetter, l.useYn === 'Y' ? hb.t('js.yn.Y') : hb.t('js.yn.N')]);
            lt.appendChild(tr);
        });
        view.rows.forEach(function (r) {
            var tr = hb.el('tr', r.useYn === 'Y' ? null : 'hb-tree-dim');
            cells(tr, [severityNames[r.severity] || r.severity, r.codeLetter, String(r.aql), r.sampleSize, r.ac, r.re,
                r.useYn === 'Y' ? hb.t('js.yn.Y') : hb.t('js.yn.N')]);
            pt.appendChild(tr);
        });
    }

    function loadAql() {
        var std = aqlStd.value;
        if (!std) { renderAql(null); return Promise.resolve(); }
        return hb.latest('aql', hb.api(AQL + '?stdCd=' + encodeURIComponent(std))).then(function (res) { renderAql(res.data); },
            function (err) { notify(err.message, true); });
    }

    function loadStds(keep) {
        return hb.api(AQL + '/stds').then(function (res) {
            stds = res.data;
            fillStds(aqlStd, null, null);
            if (keep && stds.indexOf(keep) >= 0) { aqlStd.value = keep; }
            if (!current) { fillStds(field('stdCd'), hb.t('js.common.choose'), null); }
            return loadAql();
        });
    }
    aqlStd.addEventListener('change', loadAql);

    function importDone() { loadStds(aqlStd.value); }
    bind('importLetterBtn', function () { hb.importDialog({templateCd: 'QMS_AQL_TABLE', onDone: importDone}); });
    bind('importPlanBtn', function () { hb.importDialog({templateCd: 'QMS_AQL_PLAN', onDone: importDone}); });

    document.getElementById('views').addEventListener('change', function (e) {
        var aql = e.target.value === 'aql';
        var target = e.target;
        (aql ? confirmLeave() : Promise.resolve(true)).then(function (ok) {
            if (!ok) {
                target.checked = false;
                document.querySelector('#views input[value="plans"]').checked = true;
                return;
            }
            document.getElementById('aqlView').hidden = !aql;
            document.getElementById('planView').hidden = aql;
            search.hidden = aql;
            if (aql) { loadStds(aqlStd.value); }
        });
    });

    // ---------- 시작 ----------
    hb.dirty.watch(form);
    search.addEventListener('submit', function (e) { e.preventDefault(); });
    hb.loadMessages().then(function () {
        hb.page.init();
        NUMS.forEach(function (n) { num[n] = hb.number(field(n)); });
        SELS.forEach(function (n) { sel[n] = hb.select(field(n)); });
        ['lotQty', 'defects'].forEach(function (n) { pnum[n] = hb.number(previewForm.querySelector('[data-field="' + n + '"]')); });
        return Promise.all([hb.api(AQL + '/stds'), hb.codes('SAMPLING_METHOD'), hb.codes('AQL_SEVERITY'), hb.codes('INSP_LEVEL')]
            .concat(SELS.map(function (n) { return sel[n].ready; })));
    }).then(function (r) {
        stds = r[0].data;
        methodNames = r[1];
        severityNames = r[2];
        levelNames = r[3];
        fillStds(aqlStd, null, null);
        sp = hb.searchPanel(search, {
            onSearch: function () {
                var g = grid;
                return g ? confirmLeave().then(function (ok) { return ok && g ? g.reload(true) : null; }) : null;
            }
        });
        return Promise.all([hb.loadSession(), sp.ready]);
    }).then(function () {
        setForm(null);
        grid = hb.grid('#samplingGrid', {
            progCd: PROG, id: 'main', key: 'samplingPlanId',
            url: API, columnsUrl: API + '/columns', exportUrl: API + '/export', auditTable: 'qms_sampling_plan',
            actions: ACTIONS, params: function () { return sp.values(); },
            filters: {method: 'method'},
            onFilter: function (param, value) { sp.setValue(param, value); },
            beforeSelect: confirmLeave,
            // 상태 칸 = 배지(톤 표 qms-ui.js), 끝난·쓰지 않는 행은 흐리게(UI 단계 3, docs/40 부록 A.6)
            status: {useYn: qmsUi.TONES.useYn},
            rowClass: function (row) { return row.useYn === 'N' ? 'hb-tree-dim' : null; },
            onSelect: function (row) { load(row.samplingPlanId); hb.page.openDetail(); },
            notify: notify
        });
        return grid.ready;
    }).then(null, function (err) { notify(err && err.message, true); });
})();
