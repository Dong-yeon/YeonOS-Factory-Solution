// @ts-check
/*
 * 평가 템플릿 (srm-eval-tmpl) — SRM 1부 S6 (docs/31 4.6, D10).
 * 목록 → 상세: 머리 폼 + 항목(행마다 작은 폼, 항목 번호 = 순서) + 등급 구간 + 가중치 합 표시. 작성 중만 편집,
 * [확정](APPROVE: 가중치 100·0점 등급 필수) / [새 리비전](마지막 리비전 복사) / [폐기].
 */
(function () {
    'use strict';

    /**
     * @typedef {{tmplId: number, tmplCd: string, rev: number, tmplNm: string, status: string, validFrom: string, validTo: string|null,
     *   remark: string|null, itemCnt: number, weightSum: string, releasedBy: string|null, releasedAt: string|null, rowVer: number}} SrmEvalTmplRow
     * @typedef {{sectionNm: string, itemNm: string, maxScore: string, weight: string, failBelow: string, inputType: string,
     *   metricCd: string, guide: string, bands: SrmEvalBandEdit[]}} SrmEvalItemEdit
     * @typedef {{upperPpm: string, scorePct: string}} SrmEvalBandEdit
     * @typedef {{gradeCd: string, minScore: string, suggestHoldYn: string}} SrmEvalGradeEdit
     * @typedef {{tmpl: SrmEvalTmplRow, items: SrmEvalItemEdit[], grades: SrmEvalGradeEdit[]}} SrmEvalTmplDetail
     */

    var API = '/api/srm/eval-tmpls';
    /** SRM 화면 공용 표시(srm-ui.js) */
    var srmUi = /** @type {any} */ (window).srmUi;
    var ACT_SEL = '#actionBar [data-act], #dangerBar [data-act]';
    var ACTIONS = (document.body.getAttribute('data-actions') || '').split(',');
    /** @param {string} a */
    var CAN = function (a) { return ACTIONS.indexOf(a) >= 0; };
    var msg = /** @type {HTMLElement} */ (document.getElementById('msg'));
    var form = /** @type {HTMLFormElement} */ (document.getElementById('tmplForm'));
    var search = /** @type {HTMLFormElement} */ (document.getElementById('search'));
    var itemBox = /** @type {HTMLElement} */ (document.getElementById('items'));
    var gradeBox = /** @type {HTMLElement} */ (document.getElementById('grades'));
    /** @type {SrmEvalTmplDetail|null} */
    var current = null;
    /** @type {SrmEvalItemEdit[]} */
    var items = [];
    /** @type {SrmEvalGradeEdit[]} */
    var grades = [];
    /** @type {HB.SearchPanel} */
    var sp;
    /** @type {HB.Grid} */
    var grid;
    /** @type {Record<string, string>} */
    var statusNames = {};
    /** @type {HB.Code[]} */
    var metrics = [];

    /** @param {string|null|undefined} text @param {boolean} [error] */
    function notify(text, error) {
        msg.textContent = text || '';
        msg.classList.toggle('hb-msg-error', !!error);
    }

    /** @param {string} n */
    function field(n) { return /** @type {HTMLInputElement} */ (form.querySelector('[data-field="' + n + '"]')); }

    function editable() { return CAN('SAVE') && (!current || current.tmpl.status === 'DRAFT'); }

    // ---------------------------------------------------------------- 목록

    /** 조회 조건(검색 패널) — grid 가 page·size·sort 를 붙인다 */
    function params() {
        var v = sp.values();
        /** @type {Record<string, unknown>} */
        var q = {};
        ['status', 'keyword'].forEach(function (k) { if (v[k]) { q[k] = v[k]; } });
        return q;
    }

    /** 행 → 상세(서버에서 다시 읽는다) @param {number} id */
    function openRow(id) {
        return hb.latest('tmpl', hb.api(API + '/' + id)).then(function (r) {
            show(r.data);
            hb.page.openDetail();
        }, function (err) { if (!(err && err.stale)) { notify(err.message, true); } });
    }

    /** 목록 다시 읽기 — selectId 가 이 쪽에 있으면 grid 가 골라 상세를 연다, 없어도(다른 쪽·조건 밖) 방금 만든·고친 것은 상세로 @param {number} [selectId] */
    function load(selectId) {
        return grid.reload(true, selectId || undefined).then(function () {
            var sel = grid.selected();
            if (selectId && (!sel || sel.tmplId !== selectId)) { return openRow(selectId); }
            if (!selectId) { show(null); }
        });
    }

    // ---------------------------------------------------------------- 상세

    /** @param {SrmEvalTmplDetail|null} d */
    function show(d) {
        current = d;
        hb.showFieldErrors(form, []);
        var t = d ? d.tmpl : null;
        field('tmplCd').value = t ? t.tmplCd : '';
        field('tmplNm').value = t ? t.tmplNm : '';
        field('validFrom').value = t ? t.validFrom : '';
        field('validTo').value = t && t.validTo ? t.validTo : '';
        field('remark').value = t && t.remark ? t.remark : '';
        items = d ? d.items.map(function (i) {
            return {sectionNm: i.sectionNm || '', itemNm: i.itemNm, maxScore: i.maxScore, weight: i.weight, failBelow: i.failBelow || '',
                inputType: i.inputType, metricCd: i.metricCd || '', guide: i.guide || '',
                bands: (i.bands || []).map(function (/** @type {{upperPpm: string|null, scorePct: string}} */ b) {
                    return {upperPpm: b.upperPpm || '', scorePct: b.scorePct};
                })};
        }) : [];
        grades = d ? d.grades.map(function (g) { return {gradeCd: g.gradeCd, minScore: g.minScore, suggestHoldYn: g.suggestHoldYn}; }) : [];
        var edit = editable();
        form.querySelectorAll('input').forEach(function (i) { i.disabled = !edit; });
        field('tmplCd').disabled = !!t || !CAN('SAVE');
        ['saveBtn', 'addItemBtn', 'addGradeBtn'].forEach(function (id) {
            var b = document.getElementById(id);
            if (b) { b.hidden = !edit; }
        });
        /** @type {HTMLElement} */ (document.getElementById('formMode')).textContent = t
            ? hb.t('js.srm.evalTmpl.editing', t.tmplCd, t.rev) : hb.t('js.srm.evalTmpl.creating');
        var stateLine = /** @type {HTMLElement} */ (document.getElementById('stateLine'));
        stateLine.textContent = '';
        if (t) {
            stateLine.appendChild(srmUi.withBadge(null, srmUi.badge('tmpl', t.status, statusNames[t.status]),
                t.releasedAt ? '· ' + hb.t('js.srm.evalTmpl.released', hb.fmt.datetime(t.releasedAt), t.releasedBy) : null));
        }
        var allowed = !t ? [] : t.status === 'DRAFT' ? ['release', 'obsolete'] : t.status === 'RELEASED' ? ['new-rev', 'obsolete'] : ['new-rev'];
        document.querySelectorAll(ACT_SEL).forEach(function (b) {
            /** @type {HTMLElement} */ (b).hidden = !(allowed.indexOf(b.getAttribute('data-act') || '') >= 0 && CAN(b.getAttribute('data-need') || ''));
        });
        /** @type {HTMLElement} */ (document.getElementById('dangerBar')).hidden = !document.querySelector('#dangerBar [data-act]:not([hidden])');
        render([]);
        hb.dirty.clear();
    }

    function weightSum() {
        var cents = items.reduce(function (s, i) {
            var n = Number(i.weight);
            return s + (isFinite(n) ? Math.round(n * 100) : 0);
        }, 0);
        return cents / 100;
    }

    function showWeight() {
        var w = weightSum();
        var line = /** @type {HTMLElement} */ (document.getElementById('weightLine'));
        line.textContent = hb.t('js.srm.evalTmpl.weightSum', String(w));
        line.classList.toggle('hb-field-error', w !== 100);
    }

    /** @param {HB.FieldError[]} errors */
    function render(errors) {
        var edit = editable();
        /** @type {Record<string, HB.FieldError[]>} */
        var byRow = {};
        (errors || []).forEach(function (e) {
            if (e.rowKey !== null && e.rowKey !== undefined) { (byRow[e.rowKey] = byRow[e.rowKey] || []).push(e); }
        });
        [['items', 'itemsErrorBox', 'itemsError'], ['grades', 'gradesErrorBox', 'gradesError']].forEach(function (x) {
            var top = (errors || []).filter(function (e) { return e.field === x[0] && (e.rowKey === null || e.rowKey === undefined); });
            /** @type {HTMLElement} */ (document.getElementById(x[1])).hidden = !top.length;
            /** @type {HTMLElement} */ (document.getElementById(x[2])).textContent = top.length ? top[0].message : '';
        });
        itemBox.textContent = '';
        if (!items.length) { itemBox.appendChild(hb.el('p', 'hb-muted', hb.t('js.srm.evalTmpl.noItems'))); }
        items.forEach(function (it, i) {
            var block = hb.el('div', 'hb-form');
            block.appendChild(hb.el('p', 'hb-muted hb-wide', hb.t('js.srm.evalTmpl.itemHead', i + 1)));
            var errs = byRow[String(i)] || [];
            cell(block, it, 'sectionNm', hb.t('js.srm.evalTmpl.col.section'), errs, edit, {maxlength: '100'});
            cell(block, it, 'itemNm', hb.t('js.srm.evalTmpl.col.item'), errs, edit, {maxlength: '200'});
            cell(block, it, 'maxScore', hb.t('js.srm.evalTmpl.col.max'), errs, edit, {inputmode: 'decimal', maxlength: '10'});
            cell(block, it, 'weight', hb.t('js.srm.evalTmpl.col.weight'), errs, edit, {inputmode: 'decimal', maxlength: '6'}, showWeight);
            cell(block, it, 'failBelow', hb.t('js.srm.evalTmpl.col.fail'), errs, edit, {inputmode: 'decimal', maxlength: '10'});
            var typeLab = hb.el('label');
            typeLab.appendChild(hb.el('span', null, hb.t('js.srm.evalTmpl.col.input')));
            var sel = hb.el('select');
            sel.disabled = !edit;
            [['MANUAL', hb.t('js.srm.evalTmpl.manual')]].concat(metrics.map(function (m) { return ['AUTO:' + m.code, hb.t('js.srm.evalTmpl.auto', m.name)]; }))
                .forEach(function (o) {
                    var op = hb.el('option', null, o[1]);
                    op.value = o[0];
                    sel.appendChild(op);
                });
            sel.value = it.inputType === 'AUTO' ? 'AUTO:' + it.metricCd : 'MANUAL';
            sel.addEventListener('change', function () {
                it.inputType = sel.value === 'MANUAL' ? 'MANUAL' : 'AUTO';
                it.metricCd = sel.value === 'MANUAL' ? '' : sel.value.slice(5);
                hb.dirty.mark();
                render([]);           // 입고 PPM 이면 점수 구간 칸이 생긴다
            });
            typeLab.appendChild(sel);
            var typeErr = errs.filter(function (e) { return e.field === 'inputType' || e.field === 'metricCd'; })[0];
            if (typeErr) { typeLab.appendChild(hb.el('span', 'hb-field-error', typeErr.message)); }
            block.appendChild(typeLab);
            cell(block, it, 'guide', hb.t('js.srm.evalTmpl.col.guide'), errs, edit, {maxlength: '1000'}, null, true);
            if (isPpm(it)) { block.appendChild(bandBlock(it, errs, edit)); }
            if (edit) { block.appendChild(removeBar(items, i)); }
            itemBox.appendChild(block);
        });
        gradeBox.textContent = '';
        grades.forEach(function (g, i) {
            var block = hb.el('div', 'hb-form');
            var errs = byRow['g' + i] || [];
            cell(block, g, 'gradeCd', hb.t('js.srm.evalTmpl.col.grade'), errs, edit, {maxlength: '10'});
            cell(block, g, 'minScore', hb.t('js.srm.evalTmpl.col.minScore'), errs, edit, {inputmode: 'decimal', maxlength: '6'});
            var lab = hb.el('label', 'hb-wide');
            var cb = hb.el('input');
            cb.type = 'checkbox';
            cb.checked = g.suggestHoldYn === 'Y';
            cb.disabled = !edit;
            cb.addEventListener('change', function () { g.suggestHoldYn = cb.checked ? 'Y' : 'N'; hb.dirty.mark(); });
            lab.appendChild(cb);
            lab.appendChild(hb.el('span', null, hb.t('js.srm.evalTmpl.suggestHold')));
            block.appendChild(lab);
            if (edit) { block.appendChild(removeBar(grades, i)); }
            gradeBox.appendChild(block);
        });
        showWeight();
    }

    /**
     * @param {HTMLElement} block @param {Record<string, any>} row @param {string} name @param {string} label
     * @param {HB.FieldError[]} errs @param {boolean} edit @param {Record<string, string>} attrs
     * @param {(() => void)|null} [after] @param {boolean} [wide]
     */
    function cell(block, row, name, label, errs, edit, attrs, after, wide) {
        var lab = hb.el('label', wide ? 'hb-wide' : null);
        lab.appendChild(hb.el('span', null, label));
        var inp = hb.el('input');
        inp.value = row[name] || '';
        inp.disabled = !edit;
        Object.keys(attrs).forEach(function (k) { inp.setAttribute(k, attrs[k]); });
        var err = errs.filter(function (e) { return e.field === name; })[0];
        if (err) { inp.setAttribute('aria-invalid', 'true'); }
        inp.addEventListener('input', function () {
            row[name] = inp.value;
            hb.dirty.mark();
            if (after) { after(); }
        });
        lab.appendChild(inp);
        if (err) { lab.appendChild(hb.el('span', 'hb-field-error', err.message)); }
        block.appendChild(lab);
    }

    /** 입고 PPM 자동 항목 — 점수 구간을 쓴다(docs/31 4.12.3) @param {SrmEvalItemEdit} it */
    function isPpm(it) { return it.inputType === 'AUTO' && it.metricCd === 'RECV_PPM'; }

    /**
     * 점수 구간: PPM ≤ 상한인 가장 낮은 구간의 점수 %. 상한을 비운 줄 = 그 위 전부(하나, 확정 때 필수)
     * @param {SrmEvalItemEdit} it @param {HB.FieldError[]} errs @param {boolean} edit
     */
    function bandBlock(it, errs, edit) {
        var box = hb.el('fieldset', 'hb-wide hb-form');
        box.appendChild(hb.el('legend', null, hb.t('js.srm.evalTmpl.bands')));
        box.appendChild(hb.el('p', 'hb-muted hb-wide', hb.t('js.srm.evalTmpl.bandHint')));
        var err = errs.filter(function (e) { return e.field === 'bands'; })[0];
        if (err) { box.appendChild(hb.el('p', 'hb-field-error hb-wide', err.message)); }
        it.bands.forEach(function (b, j) {
            cell(box, b, 'upperPpm', hb.t('js.srm.evalTmpl.col.upperPpm'), [], edit, {inputmode: 'decimal', maxlength: '14',
                placeholder: hb.t('js.srm.evalTmpl.bandOpen')});
            cell(box, b, 'scorePct', hb.t('js.srm.evalTmpl.col.scorePct'), [], edit, {inputmode: 'decimal', maxlength: '6'});
            if (edit) { box.appendChild(removeBar(it.bands, j)); }
        });
        if (edit) {
            var bar = hb.el('div', 'hb-toolbar hb-wide');
            var add = hb.el('button', 'hb-btn-secondary', hb.t('js.srm.evalTmpl.addBand'));
            add.type = 'button';
            add.addEventListener('click', function () {
                it.bands.push({upperPpm: '', scorePct: ''});
                hb.dirty.mark();
                render([]);
            });
            bar.appendChild(add);
            box.appendChild(bar);
        }
        return box;
    }

    /** @param {any[]} list @param {number} i */
    function removeBar(list, i) {
        var bar = hb.el('div', 'hb-toolbar hb-wide');
        var del = hb.el('button', 'hb-btn-ghost', hb.t('js.srm.evalTmpl.remove'));
        del.type = 'button';
        del.addEventListener('click', function () {
            list.splice(i, 1);
            hb.dirty.mark();
            render([]);
        });
        bar.appendChild(del);
        return bar;
    }

    var addItemBtn = document.getElementById('addItemBtn');
    if (addItemBtn) {
        addItemBtn.addEventListener('click', function () {
            items.push({sectionNm: '', itemNm: '', maxScore: '10', weight: '', failBelow: '', inputType: 'MANUAL', metricCd: '', guide: '',
                bands: []});
            hb.dirty.mark();
            render([]);
        });
    }
    var addGradeBtn = document.getElementById('addGradeBtn');
    if (addGradeBtn) {
        addGradeBtn.addEventListener('click', function () {
            grades.push({gradeCd: '', minScore: '', suggestHoldYn: 'N'});
            hb.dirty.mark();
            render([]);
        });
    }

    // ---------------------------------------------------------------- 저장 · 상태

    function newTmpl() {
        srmUi.leaveOk().then(function (/** @type {boolean} */ ok) {
            if (!ok) { return; }
            show(null);
            hb.page.openDetail();
            field('tmplCd').focus();
        });
    }
    ['newBtn', 'newBtnList'].forEach(function (id) {
        var b = document.getElementById(id);
        if (b) { b.addEventListener('click', newTmpl); }
    });

    var saveBtn = /** @type {HTMLButtonElement|null} */ (document.getElementById('saveBtn'));
    if (saveBtn) {
        var sb = saveBtn;
        sb.addEventListener('click', function () {
            var editing = current;
            var body = {tmplCd: field('tmplCd').value, tmplNm: field('tmplNm').value, validFrom: field('validFrom').value || null,
                validTo: field('validTo').value || null, remark: field('remark').value, rowVer: editing ? editing.tmpl.rowVer : null,
                items: items.map(function (i) {
                    return {sectionNm: i.sectionNm, itemNm: i.itemNm, maxScore: i.maxScore, weight: i.weight, failBelow: i.failBelow || null,
                        inputType: i.inputType, metricCd: i.metricCd || null, guide: i.guide,
                        bands: isPpm(i) ? i.bands.map(function (b) { return {upperPpm: b.upperPpm || null, scorePct: b.scorePct}; }) : []};
                }), grades: grades};
            hb.busy(sb, function () {
                return editing ? hb.api(API + '/' + editing.tmpl.tmplId, {method: 'PUT', body: body}) : hb.api(API, {method: 'POST', body: body});
            }).then(function (res) {
                if (!res) { return; }
                hb.dirty.clear();
                return load(editing ? editing.tmpl.tmplId : res.data).then(function () { notify(hb.t('js.common.saved')); });
            }, failed);
        });
    }

    /** @param {any} err */
    function failed(err) {
        notify(err.errors && err.errors.length ? err.errors[0].message : err.message, true);
        hb.showFieldErrors(form, (err.errors || []).filter(function (/** @type {HB.FieldError} */ e) {
            return e.rowKey === null || e.rowKey === undefined;
        }));
        render(err.errors || []);
    }

    document.querySelectorAll(ACT_SEL).forEach(function (el) {
        var b = /** @type {HTMLButtonElement} */ (el);
        b.addEventListener('click', function () {
            if (!current) { return; }
            if (hb.dirty.isDirty()) { notify(hb.t('js.srm.evalTmpl.saveFirst'), true); return; }
            var act = b.getAttribute('data-act');
            var t = current.tmpl;
            // 폐기 = 되돌릴 수 없음 → 위험 확인(기본 포커스 취소). 대화상자 취소는 아무 일 없음
            (act === 'obsolete' ? srmUi.ask('js.srm.dlg.tmplObsolete', hb.t('js.srm.evalTmpl.confirmObsolete'), 'js.srm.dlg.tmplObsoleteOk', true)
                : Promise.resolve(true)).then(function (/** @type {boolean} */ yes) {
                if (!yes) { return; }
                hb.busy(b, function () {
                    return hb.api(API + '/' + t.tmplId + '/' + act, {method: 'POST', body: act === 'new-rev' ? {} : {rowVer: t.rowVer}});
                }).then(function (res) {
                    if (!res) { return; }
                    return load(act === 'new-rev' ? res.data : t.tmplId).then(function () { notify(hb.t('js.common.saved')); });
                }, failed);
            });
        });
    });

    // ---------------------------------------------------------------- 시작

    hb.dirty.watch(form);
    search.addEventListener('submit', function (e) { e.preventDefault(); });
    hb.loadMessages().then(function () { return hb.loadSession(); }).then(function () {
        hb.page.init();
        sp = hb.searchPanel(search, {onSearch: function () {
            return srmUi.leaveOk().then(function (/** @type {boolean} */ ok) { return ok ? load() : null; });
        }});
        return Promise.all([sp.ready, hb.codes('BOM_STATUS'), hb.codeList('EVAL_METRIC')]);
    }).then(function (r) {
        statusNames = r[1];
        metrics = r[2];
        grid = hb.grid('#tmplGrid', {
            progCd: 'srm-eval-tmpl', id: 'main', key: 'tmplId',
            url: API + '/grid', columnsUrl: API + '/columns', exportUrl: API + '/export', actions: ACTIONS, params: params,
            filters: {status: 'status'},
            onFilter: function (param, value) { sp.setValue(param, value); },
            beforeSelect: function () { return srmUi.leaveOk(); },
            status: {status: srmUi.TONES.tmpl},
            rowClass: function (row) { return row.status === 'OBSOLETE' ? 'hb-tree-dim' : null; },
            onSelect: function (row) { openRow(Number(row.tmplId)); },
            notify: notify
        });
        return grid.ready.then(function () { return load(); });
    }).then(null, function (err) { notify(err && err.message, true); });
})();
