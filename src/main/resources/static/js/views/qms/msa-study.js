// @ts-check
/*
 * GRR 연구 (qms-msa) — P3 QMS Q2b (docs/29 D9).
 *   hb.grid(연구) + 상세: 머리 폼 / 원자료 표(행 = 측정자 × 반복, 열 = 부품, 계수형은 맨 위 기준값 행) / 계산 결과.
 * 저장 = 머리 + 원자료 한 벌. 계산은 서버(MsaCalc) — 작성 중이면 저장된 원자료로 미리 계산, 확정본은 스냅샷.
 * 모양(측정자·부품·반복·방법)을 바꾸면 표를 다시 그리되 같은 칸(측정자-부품-반복) 값은 남긴다. 서버 데이터는 textContent·value 로만.
 */
/**
 * @typedef {{a: number, p: number, t: number, v: string}} MsaCell
 * @typedef {{studyId: number, studyNo: string, assetCd: string, charId?: number | null, method: string, appraiserCnt: number,
 *   partCnt: number, trialCnt: number, basis?: string | null, processSigma?: number | null, appraisers: string[], studyDate: string,
 *   status: string, remark?: string | null, rowVer: number, tolerance?: number | null}} MsaStudy
 * @typedef {{study: MsaStudy, cells: MsaCell[], result?: Record<string, any> | null, preview?: Record<string, any> | null,
 *   previewError?: string | null, okPct: number, limitPct: number}} MsaDetail
 * @typedef {{id: number, cd: string, nm: string, extra?: string | null}} MsaChoice
 */
(function () {
    'use strict';

    /** QMS 화면 공용 — 상태 배지·대화상자(qms-ui.js) */
    var qmsUi = /** @type {QmsUi} */ (/** @type {any} */ (window).qmsUi);

    var PROG = document.body.getAttribute('data-prog') || '';
    var ACTIONS = (document.body.getAttribute('data-actions') || '').split(',');
    var CAN_SAVE = ACTIONS.indexOf('SAVE') >= 0;
    var API = '/api/qms/msa';
    var msg = /** @type {HTMLElement} */ (document.getElementById('msg'));
    var search = /** @type {HTMLFormElement} */ (document.getElementById('search'));
    var form = /** @type {HTMLFormElement} */ (document.getElementById('studyForm'));
    var formMode = /** @type {HTMLElement} */ (document.getElementById('formMode'));
    var table = /** @type {HTMLTableElement} */ (document.getElementById('dataTable'));
    /** 선택된 연구(신규면 null) @type {MsaStudy | null} */
    var current = null;
    /** 표 칸 값 "a-p-t" → 글자 — 모양을 바꿔도 같은 칸은 남긴다 @type {Record<string, string>} */
    var values = {};
    var editable = true;
    /** @type {HB.Grid | undefined} */
    var grid;
    /** @type {HB.SearchPanel} */
    var sp;
    /** @type {HB.SelectControl} */
    var methodSel;
    /** @type {HB.SelectControl} */
    var basisSel;
    /** @type {Record<string, HB.NumberControl>} */
    var num = {};
    /** @type {Record<string, string>} */
    var statusNames = {};
    /** @type {Record<string, string>} */
    var verdictNames = {};

    /** @param {string | null | undefined} text @param {boolean} [error] */
    function notify(text, error) {
        msg.textContent = text || '';
        msg.classList.toggle('hb-msg-error', !!error);
    }

    /** @param {string} n */
    function field(n) {
        return /** @type {HTMLInputElement | HTMLSelectElement} */ (form.querySelector('[data-field="' + n + '"]'));
    }

    /** @param {HTMLSelectElement} s @param {string} value @param {string} text */
    function option(s, value, text) {
        var o = /** @type {HTMLOptionElement} */ (hb.el('option', null, text));
        o.value = value;
        s.appendChild(o);
    }

    /** 지금 값이 선택지에 없으면(사용 중지) 그대로 보이게 덧붙인다 @param {HTMLSelectElement} s @param {string} v @param {string} text */
    function keep(s, v, text) {
        if (!v) { s.value = ''; return; }
        s.value = v;
        if (s.value !== v) {
            option(s, v, text + ' (' + hb.t('js.qms.inactive') + ')');
            s.value = v;
        }
    }

    function shape() {
        return {k: num.appraiserCnt.numberValue() || 0, n: num.partCnt.numberValue() || 0, r: num.trialCnt.numberValue() || 0,
            attr: methodSel.value() === 'ATTRIBUTE'};
    }

    /** 방법에 따라 기준·σ 칸 */
    function layout() {
        var attr = methodSel.value() === 'ATTRIBUTE';
        /** @type {HTMLElement} */ (document.getElementById('basisBox')).hidden = attr;
        /** @type {HTMLElement} */ (document.getElementById('sigmaBox')).hidden = attr || basisSel.value() === 'TOLERANCE';
    }

    /** 칸 입력 하나 @param {string} key */
    function cellInput(key) {
        var i = /** @type {HTMLInputElement} */ (hb.el('input'));
        i.setAttribute('data-cell', key);
        i.value = values[key] || '';
        i.readOnly = !editable;
        i.addEventListener('input', function () {
            values[key] = i.value;
            countFilled();
        });
        return i;
    }

    /** 원자료 표 다시 그리기 — 모양이 범위 밖이면 비운다 */
    function renderGrid() {
        var s = shape();
        var head = /** @type {HTMLElement} */ (table.tHead);
        var body = /** @type {HTMLElement} */ (table.tBodies[0]);
        head.textContent = '';
        body.textContent = '';
        if (s.k < 2 || s.k > 3 || s.n < 2 || s.n > (s.attr ? 50 : 10) || s.r < 2 || s.r > 3) { countFilled(); return; }
        var names = parseNames();
        var hr = hb.el('tr');
        hr.appendChild(hb.el('th'));
        for (var p = 1; p <= s.n; p++) { hr.appendChild(hb.el('th', null, hb.t('js.qms.msa.part', p))); }
        head.appendChild(hr);
        if (s.attr) {
            var rr = hb.el('tr');
            rr.appendChild(hb.el('th', null, hb.t('js.qms.msa.ref')));
            for (var rp = 1; rp <= s.n; rp++) {
                var td0 = hb.el('td');
                td0.appendChild(cellInput('0-' + rp + '-1'));
                rr.appendChild(td0);
            }
            body.appendChild(rr);
        }
        for (var a = 1; a <= s.k; a++) {
            for (var t = 1; t <= s.r; t++) {
                var tr = hb.el('tr');
                tr.appendChild(hb.el('th', null, hb.t('js.qms.msa.row', names[a - 1] || hb.t('js.qms.msa.appraiser', a), t)));
                for (var q = 1; q <= s.n; q++) {
                    var td = hb.el('td');
                    td.appendChild(cellInput(a + '-' + q + '-' + t));
                    tr.appendChild(td);
                }
                body.appendChild(tr);
            }
        }
        countFilled();
    }

    function countFilled() {
        var s = shape();
        var need = s.k * s.n * s.r;
        var n = 0;
        table.querySelectorAll('input[data-cell]').forEach(function (i) {
            var key = i.getAttribute('data-cell') || '';
            if (key.indexOf('0-') !== 0 && /** @type {HTMLInputElement} */ (i).value.trim()) { n++; }
        });
        /** @type {HTMLElement} */ (document.getElementById('filled')).textContent = need > 0 ? hb.t('js.qms.msa.filled', n, need) : '';
    }

    function parseNames() {
        var raw = field('appraisers').value.trim();
        return raw ? raw.split(',').map(function (x) { return x.trim(); }) : [];
    }

    /** 표에 보이는 칸만 보낸다(모양 밖 값은 버림) */
    function cellsBody() {
        /** @type {{a: number, p: number, t: number, v: string}[]} */
        var out = [];
        table.querySelectorAll('input[data-cell]').forEach(function (i) {
            var v = /** @type {HTMLInputElement} */ (i).value.trim();
            if (!v) { return; }
            var k = (i.getAttribute('data-cell') || '').split('-').map(Number);
            out.push({a: k[0], p: k[1], t: k[2], v: v});
        });
        return out;
    }

    function body() {
        var names = parseNames();
        return {assetCd: field('assetCd').value, charId: field('charId').value ? Number(field('charId').value) : null,
            method: methodSel.value(), appraiserCnt: num.appraiserCnt.numberValue(), partCnt: num.partCnt.numberValue(),
            trialCnt: num.trialCnt.numberValue(), basis: basisSel.value() || null, processSigma: num.processSigma.value(),
            appraisers: names, studyDate: field('studyDate').value || null, remark: field('remark').value,
            rowVer: current ? current.rowVer : null, cells: cellsBody()};
    }

    /** @param {MsaDetail | null} d */
    function show(d) {
        var s = d ? d.study : null;
        current = s;
        editable = CAN_SAVE && (!s || s.status === 'DRAFT');
        hb.showFieldErrors(form, []);
        keep(/** @type {HTMLSelectElement} */ (field('assetCd')), s ? s.assetCd : '', s ? s.assetCd : '');
        keep(/** @type {HTMLSelectElement} */ (field('charId')), s && s.charId ? String(s.charId) : '', s && s.charId ? String(s.charId) : '');
        methodSel.setValue(s ? s.method : 'AVG_RANGE');
        basisSel.setValue(s && s.basis ? s.basis : 'PROCESS');
        num.appraiserCnt.setValue(s ? s.appraiserCnt : 3);
        num.partCnt.setValue(s ? s.partCnt : 10);
        num.trialCnt.setValue(s ? s.trialCnt : 3);
        num.processSigma.setValue(s ? s.processSigma : null);
        field('appraisers').value = s ? s.appraisers.join(', ') : '';
        field('studyDate').value = s ? s.studyDate : hb.dates.today();
        field('remark').value = s && s.remark ? s.remark : '';
        /** @type {NodeListOf<HTMLInputElement | HTMLSelectElement>} */ (form.querySelectorAll('input, select'))
            .forEach(function (i) { i.disabled = !editable; });
        values = {};
        (d ? d.cells : []).forEach(function (c) { values[c.a + '-' + c.p + '-' + c.t] = c.v; });
        formMode.textContent = !s ? hb.t('js.qms.msa.creating')
            : s.status === 'DRAFT' ? hb.t('js.qms.msa.editing', s.studyNo)
            : hb.t('js.qms.msa.readonly', s.studyNo, statusNames[s.status] || s.status);
        ['completeBtn', 'cancelStudyBtn'].forEach(function (id) {
            var b = document.getElementById(id);
            if (b) { b.hidden = !s || s.status !== 'DRAFT'; }
        });
        var save = document.getElementById('saveBtn');
        if (save) { save.hidden = !editable; }
        var paste = /** @type {HTMLElement | null} */ (document.querySelector('.hb-qms-paste'));
        if (paste) { paste.hidden = !editable; }
        layout();
        renderGrid();
        renderResult(d);
        hb.dirty.clear(form);
    }

    /** @param {MsaDetail | null} d */
    function renderResult(d) {
        var section = /** @type {HTMLElement} */ (document.getElementById('resultSection'));
        var note = /** @type {HTMLElement} */ (document.getElementById('resultNote'));
        var line = /** @type {HTMLElement} */ (document.getElementById('verdictLine'));
        var tbody = /** @type {HTMLElement} */ (document.querySelector('#resultTable tbody'));
        tbody.textContent = '';
        line.hidden = true;
        var anovaBox = /** @type {HTMLElement} */ (document.getElementById('anovaBox'));
        anovaBox.hidden = true;
        if (!d) { section.hidden = true; return; }
        section.hidden = false;
        var r = d.result || d.preview;
        if (!r) {
            note.textContent = d.previewError === 'MSA_NO_TOLERANCE' ? hb.t('js.qms.msa.noTolerance')
                : d.previewError === 'MSA_NO_VARIATION' ? hb.t('js.qms.msa.noVariation') : hb.t('js.qms.msa.incomplete');
            return;
        }
        note.textContent = d.result ? hb.t('js.qms.msa.snapshot', d.study.studyNo, statusNames[d.study.status] || '') : hb.t('js.qms.msa.preview');
        /** @param {string} label @param {unknown} a @param {unknown} [b] */
        function row(label, a, b) {
            var tr = hb.el('tr');
            tr.appendChild(hb.el('th', null, label));
            tr.appendChild(hb.el('td', 'hb-num', a === null || a === undefined ? '' : String(a)));
            tr.appendChild(hb.el('td', 'hb-num', b === null || b === undefined ? '' : String(b) + ' %'));
            tbody.appendChild(tr);
        }
        if (d.study.method === 'ATTRIBUTE') {
            /** @type {number[]} */ (r.withinPct || []).forEach(function (v, i) { row(hb.t('js.qms.msa.within', i + 1), null, v); });
            if (r.vsRefPct) { /** @type {number[]} */ (r.vsRefPct).forEach(function (v, i) { row(hb.t('js.qms.msa.vsRef', i + 1), null, v); }); }
            row(hb.t('js.qms.msa.between'), null, r.betweenPct);
            if (r.allVsRefPct !== null && r.allVsRefPct !== undefined) { row(hb.t('js.qms.msa.allVsRef'), null, r.allVsRefPct); }
            return;
        }
        row('EV', r.ev, r.pctEv);
        row('AV', r.av, r.pctAv);
        if (d.study.method === 'ANOVA') { row(hb.t('js.qms.msa.int'), r.iv, r.pctIv); }
        row('GRR', r.grr, r.pctGrr);
        row('PV', r.pv, r.pctPv);
        row('TV', r.tv);
        row('ndc', r.ndc);
        row('R̿', r.rbar);
        row('X̄diff', r.xdiff);
        row('Rp', r.rp);
        if (r.tolerance !== null && r.tolerance !== undefined) { row('Tol', r.tolerance); }
        if (r.processSigma !== null && r.processSigma !== undefined) { row('σ', r.processSigma); }
        if (d.study.method === 'ANOVA' && r.anova) { renderAnova(r); }
        line.hidden = false;
        line.setAttribute('data-status', r.verdict);
        line.textContent = (verdictNames[r.verdict] || r.verdict) + ' · %GRR ' + r.pctGrr + ' % (≤ ' + d.okPct + ' / ≤ ' + d.limitPct + ')'
            + (r.ndc < 5 ? ' · ' + hb.t('js.qms.msa.ndcLow') : '');
    }

    /** ANOVA 표(docs/29_P3 45장) — 상호작용을 오차에 합쳤으면 그렇게 알린다 @param {Record<string, any>} r */
    function renderAnova(r) {
        var box = /** @type {HTMLElement} */ (document.getElementById('anovaBox'));
        var tbody = /** @type {HTMLElement} */ (document.querySelector('#anovaTable tbody'));
        tbody.textContent = '';
        /** @type {Record<string, string>} */
        var names = {PART: hb.t('js.qms.msa.anova.PART'), APPRAISER: hb.t('js.qms.msa.anova.APPRAISER'),
            INTERACTION: hb.t('js.qms.msa.anova.INTERACTION'), REPEATABILITY: hb.t('js.qms.msa.anova.REPEATABILITY'), TOTAL: hb.t('js.qms.msa.anova.TOTAL')};
        /** @type {{source: string, df: number, ss?: number | null, ms?: number | null, f?: number | null, p?: number | null}[]} */ (r.anova)
            .forEach(function (x) {
                var tr = hb.el('tr');
                tr.appendChild(hb.el('th', null, names[x.source] || x.source));
                [x.df, x.ss, x.ms, x.f].forEach(function (v) { tr.appendChild(hb.el('td', 'hb-num', v === null || v === undefined ? '' : String(v))); });
                tr.appendChild(hb.el('td', 'hb-num', x.p === null || x.p === undefined ? '' : Number(x.p) < 0.0001 ? '< 0.0001' : Number(x.p).toFixed(4)));
                tbody.appendChild(tr);
            });
        /** @type {HTMLElement} */ (document.getElementById('anovaNote')).textContent = r.pooled ? hb.t('js.qms.msa.anova.pooled') : hb.t('js.qms.msa.anova.kept');
        box.hidden = false;
    }

    /** 엑셀 붙여넣기 — 행 = 측정자 × 반복(계수형은 첫 행 기준값 선택), 열 = 부품 */
    function paste() {
        var s = shape();
        var box = /** @type {HTMLTextAreaElement} */ (document.getElementById('pasteBox'));
        var rows = box.value.replace(/\r/g, '').split('\n').filter(function (x) { return x.trim() !== ''; })
            .map(function (x) { return x.split('\t'); });
        var dataRows = s.k * s.r;
        var withRef = s.attr && rows.length === dataRows + 1;
        if ((rows.length !== dataRows && !withRef) || rows.some(function (x) { return x.length !== s.n; })) {
            notify(hb.t('js.qms.msa.pasteBad', dataRows, s.n), true);
            return;
        }
        var i = 0;
        if (withRef) {
            rows[0].forEach(function (v, p) { values['0-' + (p + 1) + '-1'] = v.trim(); });
            i = 1;
        }
        for (var a = 1; a <= s.k; a++) {
            for (var t = 1; t <= s.r; t++, i++) {
                var cols = rows[i];
                for (var p = 1; p <= s.n; p++) { values[a + '-' + p + '-' + t] = cols[p - 1].trim(); }
            }
        }
        box.value = '';
        renderGrid();
        notify('');
    }

    /** @param {number} id */
    function load(id) {
        return hb.latest('detail', hb.api(API + '/' + id)).then(function (r) { show(r.data); },
            function (err) { if (err.code !== 'STALE') { notify(err.message, true); } });
    }

    /** @param {number} id */
    function after(id) {
        var g = grid;
        return (g ? g.reload(false, id) : Promise.resolve()).then(function () {
            if (!g || !g.selected()) { return load(id); }
        });
    }

    /** @param {string} id @param {(b: HTMLButtonElement) => void} fn */
    function bind(id, fn) {
        var b = /** @type {HTMLButtonElement | null} */ (document.getElementById(id));
        if (b) {
            var btn = b;
            btn.addEventListener('click', function () { fn(btn); });
        }
    }

    var dirtyCells = false;
    function confirmLeave() {
        return qmsUi.leaveOk(hb.dirty.isDirty() || dirtyCells);
    }
    table.addEventListener('input', function () { dirtyCells = true; });

    function newStudy() {
        confirmLeave().then(function (ok) {
            if (!ok) { return; }
            dirtyCells = false;
            show(null);
            hb.page.openDetail();
        });
    }
    bind('newBtn', newStudy);
    bind('newBtnList', newStudy);
    bind('pasteBtn', function () { paste(); dirtyCells = true; });
    ['appraiserCnt', 'partCnt', 'trialCnt', 'method', 'appraisers'].forEach(function (n) { field(n).addEventListener('change', renderGrid); });
    field('method').addEventListener('change', layout);
    field('basis').addEventListener('change', layout);

    /** 표 칸 오류(행 키 a-p-t)를 그 칸에 표시 @param {HB.FieldError[] | undefined} errs */
    function markCells(errs) {
        table.querySelectorAll('input[data-cell]').forEach(function (i) { i.removeAttribute('aria-invalid'); i.removeAttribute('title'); });
        (errs || []).forEach(function (e) {
            if (!e.rowKey) { return; }
            var i = table.querySelector('input[data-cell="' + e.rowKey + '"]');
            if (i) {
                i.setAttribute('aria-invalid', 'true');
                i.setAttribute('title', e.message);
            }
        });
    }

    bind('saveBtn', function (btn) {
        var bad = ['appraiserCnt', 'partCnt', 'trialCnt', 'processSigma'].some(function (n) { return !num[n].validate().ok; });
        if (bad) { return; }
        var editId = current ? current.studyId : null;
        var b = body();
        hb.busy(btn, function () {
            return editId !== null ? hb.api(API + '/' + editId, {method: 'PUT', body: b}) : hb.api(API, {method: 'POST', body: b});
        }).then(function (res) {
            if (!res) { return; }
            hb.dirty.clear(form);
            dirtyCells = false;
            markCells([]);
            return after(editId !== null ? editId : res.data).then(function () { notify(hb.t('js.common.saved')); });
        }, function (err) {
            notify(err.message, true);
            hb.showFieldErrors(form, (err.errors || []).filter(/** @param {HB.FieldError} e */ function (e) { return !e.rowKey; }));
            markCells(err.errors);
        });
    });

    /** @param {HTMLButtonElement} btn @param {string} act @param {string} confirmText @param {string} done */
    function action(btn, act, confirmText, done) {
        if (!current) { return; }
        if (hb.dirty.isDirty() || dirtyCells) { notify(hb.t('js.qms.plan.saveFirst'), true); return; }
        var s = current;
        qmsUi.ask(act, confirmText).then(function (yes) {
            if (!yes) { return; }
            hb.busy(btn, function () { return hb.api(API + '/' + s.studyId + '/' + act, {method: 'POST', body: {rowVer: s.rowVer}}); })
                .then(function (res) {
                    if (!res) { return; }
                    return after(s.studyId).then(function () { notify(done); });
                }, function (err) {
                    notify(err.message + (err.errors && err.errors.length
                        ? ' ' + err.errors.map(/** @param {HB.FieldError} e */ function (e) { return e.message; }).join(' ') : ''), true);
                });
        });
    }
    bind('completeBtn', function (btn) {
        if (current) { action(btn, 'complete', hb.t('js.qms.msa.confirmComplete', current.studyNo), hb.t('js.qms.msa.completed')); }
    });
    bind('cancelStudyBtn', function (btn) {
        if (current) { action(btn, 'cancel', hb.t('js.qms.msa.confirmCancel', current.studyNo), hb.t('js.qms.msa.cancelled')); }
    });

    // ---------- 시작 ----------
    hb.dirty.watch(form);
    search.addEventListener('submit', function (e) { e.preventDefault(); });
    hb.loadMessages().then(function () {
        hb.page.init();
        methodSel = hb.select(/** @type {HTMLSelectElement} */ (field('method')));
        basisSel = hb.select(/** @type {HTMLSelectElement} */ (field('basis')));
        ['appraiserCnt', 'partCnt', 'trialCnt', 'processSigma'].forEach(function (n) {
            num[n] = hb.number(/** @type {HTMLInputElement} */ (field(n)));
        });
        sp = hb.searchPanel(search, {onSearch: function () { var g = grid;
            return g ? confirmLeave().then(function (ok) { return ok && g ? g.reload(true) : null; }) : null; }});
        return Promise.all([hb.api(API + '/gauges'), hb.api(API + '/chars'), hb.codes('MSA_STATUS'), hb.codes('MSA_VERDICT'),
            Promise.all([hb.loadSession(), sp.ready, methodSel.ready, basisSel.ready])]);
    }).then(function (r) {
        var gs = /** @type {HTMLSelectElement} */ (field('assetCd'));
        option(gs, '', hb.t('js.common.choose'));
        /** @type {MsaChoice[]} */ (r[0].data).forEach(function (g) { option(gs, g.cd, g.cd + ' · ' + g.nm); });
        var cs = /** @type {HTMLSelectElement} */ (field('charId'));
        option(cs, '', hb.t('js.qms.msa.noChar'));
        /** @type {MsaChoice[]} */ (r[1].data).forEach(function (c) { option(cs, String(c.id), c.cd + ' ' + c.nm); });
        statusNames = r[2];
        verdictNames = r[3];
        grid = hb.grid('#msaGrid', {
            progCd: PROG, id: 'main', key: 'studyId',
            url: API, columnsUrl: API + '/columns', exportUrl: API + '/export', auditTable: 'qms_msa_study',
            actions: ACTIONS, params: function () { return sp.values(); },
            filters: {method: 'method', status: 'status'},
            onFilter: function (param, value) { sp.setValue(param, value); },
            beforeSelect: confirmLeave,
            // 상태 칸 = 배지(톤 표 qms-ui.js), 끝난·쓰지 않는 행은 흐리게(UI 단계 3, docs/40 부록 A.6)
            status: {status: qmsUi.TONES.msa, verdict: qmsUi.TONES.msaVerdict},
            rowClass: function (row) { return row.status === 'CANCELLED' ? 'hb-tree-dim' : null; },
            onSelect: function (row) { dirtyCells = false; load(row.studyId); hb.page.openDetail(); },
            notify: notify
        });
        return grid.ready.then(function () { show(null); });
    }).then(null, function (err) { notify(err && err.message, true); });
})();
