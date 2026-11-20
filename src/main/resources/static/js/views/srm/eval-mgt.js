// @ts-check
/*
 * 협력사 평가 (srm-eval) — SRM 1부 S6 (docs/31 4.6, D10·D11).
 * 목록(평가 기간 끝 날) → 신규: 협력사·템플릿·기간 → [평가 만들기] / 상세: 항목 점수(자동 항목은 계산값·근거) + 미리보기(총점·등급·과락)
 * → [점수 저장] [자동 항목 다시 계산] [확정](승인 권한) [평가 취소]. HOLD 제안은 배지만 — 거래 상태는 협력사 정보에서 사람이 바꾼다.
 */
(function () {
    'use strict';

    /**
     * @typedef {{evalId: number, evalNo: string, custCd: string, custNm: string, tradeStatus: string, tmplCd: string, tmplRev: number,
     *   tmplNm: string, periodFrom: string, periodTo: string, status: string, totalScore: string|null, gradeCd: string|null,
     *   failYn: string|null, suggestTradeStatus: string|null, confirmedBy: string|null, confirmedAt: string|null,
     *   cancelReason: string|null, remark: string|null, rowVer: number}} SrmEvalRow
     * @typedef {{itemId: number, itemNo: number, sectionNm: string|null, itemNm: string, maxScore: string, weight: string,
     *   failBelow: string|null, inputType: string, metricCd: string|null, guide: string|null, score: string|null, rawValue: string|null,
     *   autoYn: string, basis: string|null, comment: string|null}} SrmEvalScore
     * @typedef {{gradeCd: string, minScore: string, suggestHoldYn: string}} SrmEvalGrade
     * @typedef {{total: string|null, gradeCd: string|null, fail: boolean, suggestHold: boolean, complete: boolean, failedItems: number[]}} SrmEvalPreview
     * @typedef {{eval: SrmEvalRow, scores: SrmEvalScore[], grades: SrmEvalGrade[], preview: SrmEvalPreview}} SrmEvalDetail
     */

    /** SRM 화면 공용 표시(srm-ui.js) */
    var srmUi = /** @type {any} */ (window).srmUi;
    var API = '/api/srm/evals';
    var ACTIONS = (document.body.getAttribute('data-actions') || '').split(',');
    /** @param {string} a */
    var CAN = function (a) { return ACTIONS.indexOf(a) >= 0; };
    var msg = /** @type {HTMLElement} */ (document.getElementById('msg'));
    var search = /** @type {HTMLFormElement} */ (document.getElementById('search'));
    var createForm = /** @type {HTMLFormElement} */ (document.getElementById('createForm'));
    var evalForm = /** @type {HTMLFormElement} */ (document.getElementById('evalForm'));
    var scoreBox = /** @type {HTMLElement} */ (document.getElementById('scores'));
    /** @type {SrmEvalDetail|null} */
    var current = null;
    /** 화면 입력 itemId → {score, comment} @type {Record<string, {score: string, comment: string}>} */
    var edits = {};
    /** @type {HB.SearchPanel} */
    var sp;
    /** @type {HB.Grid} */
    var grid;
    /** @type {Record<string, string>} */
    var statusNames = {};
    /** @type {Record<string, string>} */
    var metricNames = {};

    /** @param {string|null|undefined} text @param {boolean} [error] */
    function notify(text, error) {
        msg.textContent = text || '';
        msg.classList.toggle('hb-msg-error', !!error);
    }

    /** @param {HTMLFormElement} f @param {string} n */
    function field(f, n) { return /** @type {HTMLInputElement} */ (f.querySelector('[data-field="' + n + '"]')); }

    /** 확정 결과 — 점수·등급 글자 + 과락(danger)·거래 보류 제안(warning) 배지 @param {SrmEvalRow} e */
    function resultCell(e) {
        var f = document.createDocumentFragment();
        if (e.status !== 'CONFIRMED') { return f; }
        f.appendChild(document.createTextNode(hb.t('js.srm.eval.result', e.totalScore, e.gradeCd)));
        flags(f, e.failYn === 'Y', e.suggestTradeStatus === 'HOLD');
        return f;
    }

    /** @param {DocumentFragment|HTMLElement} f @param {boolean} fail @param {boolean} hold */
    function flags(f, fail, hold) {
        if (fail) { f.appendChild(document.createTextNode(' ')); f.appendChild(srmUi.flag('danger', hb.t('js.srm.eval.fail'))); }
        if (hold) { f.appendChild(document.createTextNode(' ')); f.appendChild(srmUi.badge('trade', 'HOLD', hb.t('js.srm.eval.holdBadge'))); }
    }

    /** AUTO 근거(기계 글) → 문구. 키는 글자 그대로(I18nKeyGuardTest) @param {string|null} b */
    function basisText(b) {
        if (!b) { return ''; }
        var p = b.split(':');
        if (p[0] === 'ACK_RATE') { return hb.t('js.srm.eval.basis.ackRate', p[1], p[2]); }
        if (p[0] === 'ACK_NONE') { return hb.t('js.srm.eval.basis.ackNone'); }
        if (p[0] === 'OTD') { return hb.t('js.srm.eval.basis.otd', p[1], p[2]); }
        if (p[0] === 'OTD_NONE') { return hb.t('js.srm.eval.basis.otdNone'); }
        if (p[0] === 'CERT') { return hb.t('js.srm.eval.basis.cert', p[1], p[2] || hb.t('js.srm.eval.basis.noExpiry')); }
        if (p[0] === 'CERT_NONE') { return hb.t('js.srm.eval.basis.certNone'); }
        if (p[0] === 'PPM') { return hb.t('js.srm.eval.basis.ppm', p[1], p[2], p[3], p[4]); }
        if (p[0] === 'PPM_NONE') { return hb.t('js.srm.eval.basis.ppmNone'); }
        if (p[0] === 'PPM_UNITS') { return hb.t('js.srm.eval.basis.ppmUnits', p[1]); }
        if (p[0] === 'PPM_BAND') { return hb.t('js.srm.eval.basis.ppmBand', p[1]); }
        if (p[0] === 'PART2') { return hb.t('js.srm.eval.basis.part2'); }
        return b;
    }

    // ---------------------------------------------------------------- 목록

    /** 조회 조건(검색 패널) — grid 가 page·size·sort 를 붙인다 */
    function params() {
        var v = sp.values();
        /** @type {Record<string, unknown>} */
        var q = {};
        ['from', 'to', 'status', 'keyword'].forEach(function (k) { if (v[k]) { q[k] = v[k]; } });
        return q;
    }

    /** 행 → 상세(서버에서 다시 읽는다) @param {number} id */
    function openRow(id) {
        return hb.latest('eval', hb.api(API + '/' + id)).then(function (r) {
            show(r.data);
            hb.page.openDetail();
        }, function (err) { if (!(err && err.stale)) { notify(err.message, true); } });
    }

    /** 목록 다시 읽기 — selectId 가 이 쪽에 있으면 grid 가 골라 상세를 연다, 없어도(다른 쪽·조건 밖) 방금 만든·고친 것은 상세로 @param {number} [selectId] */
    function load(selectId) {
        return grid.reload(true, selectId || undefined).then(function () {
            var sel = grid.selected();
            if (selectId && (!sel || sel.evalId !== selectId)) { return openRow(selectId); }
            if (!selectId) { show(null); }
        });
    }

    // ---------------------------------------------------------------- 상세

    /** @param {SrmEvalDetail|null} d */
    function show(d) {
        current = d;
        var e = d ? d.eval : null;
        /** @type {HTMLElement} */ (document.getElementById('createBox')).hidden = true;
        /** @type {HTMLElement} */ (document.getElementById('evalBox')).hidden = !d;
        /** @type {HTMLElement} */ (document.getElementById('formMode')).textContent = e
            ? hb.t('js.srm.eval.viewing', e.evalNo, e.custNm, e.tmplNm) : hb.t('js.srm.eval.select');
        edits = {};
        if (d && e) {
            var draft = e.status === 'DRAFT';
            var stateLine = /** @type {HTMLElement} */ (document.getElementById('stateLine'));
            stateLine.textContent = '';
            stateLine.appendChild(srmUi.withBadge(null, srmUi.badge('eval', e.status, statusNames[e.status]), '· ' + e.periodFrom + ' ~ ' + e.periodTo
                + (e.confirmedAt ? ' · ' + hb.t('js.srm.eval.confirmedBy', hb.fmt.datetime(e.confirmedAt), e.confirmedBy) : '')
                + (e.cancelReason ? ' · ' + hb.t('js.srm.eval.cancelled', e.cancelReason) : '')));
            var p = d.preview;
            var resultLine = /** @type {HTMLElement} */ (document.getElementById('resultLine'));
            resultLine.textContent = '';
            if (e.status === 'CONFIRMED') {
                resultLine.appendChild(resultCell(e));
            } else {
                resultLine.textContent = p.complete ? hb.t('js.srm.eval.preview', p.total, p.gradeCd || '-') : hb.t('js.srm.eval.incomplete');
                flags(resultLine, p.fail, false);
            }
            var hold = e.status === 'CONFIRMED' ? e.suggestTradeStatus === 'HOLD' : p.suggestHold;
            var holdLine = /** @type {HTMLElement} */ (document.getElementById('holdLine'));
            holdLine.hidden = !hold;
            holdLine.textContent = hold ? hb.t('js.srm.eval.holdHint', e.custNm) : '';
            ['saveBtn', 'recalcBtn', 'confirmBtn', 'cancelBtn'].forEach(function (id) {
                var b = /** @type {HTMLElement} */ (document.getElementById(id));
                b.hidden = !(draft && CAN(b.getAttribute('data-need') || ''));
            });
            field(evalForm, 'remark').value = e.remark || '';
            field(evalForm, 'remark').disabled = !(draft && CAN('SAVE'));
            d.scores.forEach(function (s) { edits[String(s.itemId)] = {score: s.score || '', comment: s.comment || ''}; });
            var gl = /** @type {HTMLElement} */ (document.getElementById('gradeList'));
            gl.textContent = '';
            d.grades.forEach(function (g) {
                gl.appendChild(hb.el('li', null, hb.t('js.srm.eval.gradeLine', g.gradeCd, g.minScore)
                    + (g.suggestHoldYn === 'Y' ? ' · ' + hb.t('js.srm.eval.holdBadge') : '')));
            });
        }
        renderScores([]);
        hb.dirty.clear();
    }

    /** @param {HB.FieldError[]} errors */
    function renderScores(errors) {
        scoreBox.textContent = '';
        if (!current) { return; }
        var d = current;
        var draft = d.eval.status === 'DRAFT' && CAN('SAVE');
        d.scores.forEach(function (s, i) {
            var block = hb.el('div', 'hb-form');
            var failed = d.preview.failedItems.indexOf(s.itemId) >= 0;
            block.appendChild(hb.el('p', 'hb-wide' + (failed ? ' hb-field-error' : ' hb-muted'), hb.t('js.srm.eval.itemHead', s.itemNo,
                (s.sectionNm ? s.sectionNm + ' · ' : '') + s.itemNm, s.maxScore, s.weight)
                + (s.failBelow ? ' · ' + hb.t('js.srm.eval.failBelow', s.failBelow) : '')));
            if (s.guide) { block.appendChild(hb.el('p', 'hb-wide hb-muted', s.guide)); }
            var errs = (errors || []).filter(function (x) { return x.rowKey === String(i); });
            var ed = edits[String(s.itemId)];
            var auto = s.autoYn === 'Y';
            var lab = hb.el('label');
            lab.appendChild(hb.el('span', null, auto ? hb.t('js.srm.eval.autoScore') : hb.t('js.srm.eval.score', s.maxScore)));
            var inp = hb.el('input');
            inp.inputMode = 'decimal';
            inp.maxLength = 10;
            inp.value = ed.score;
            inp.disabled = !draft || auto;
            inp.addEventListener('input', function () { ed.score = inp.value; hb.dirty.mark(); });
            var se = errs.filter(function (x) { return x.field === 'score'; })[0];
            if (se) { inp.setAttribute('aria-invalid', 'true'); }
            lab.appendChild(inp);
            if (se) { lab.appendChild(hb.el('span', 'hb-field-error', se.message)); }
            block.appendChild(lab);
            var lab2 = hb.el('label');
            lab2.appendChild(hb.el('span', null, hb.t('js.srm.eval.comment')));
            var c = hb.el('input');
            c.maxLength = 500;
            c.value = ed.comment;
            c.disabled = !draft;
            c.addEventListener('input', function () { ed.comment = c.value; hb.dirty.mark(); });
            lab2.appendChild(c);
            block.appendChild(lab2);
            if (s.inputType === 'AUTO') {
                block.appendChild(hb.el('p', 'hb-wide hb-muted', hb.t('js.srm.eval.autoLine', metricNames[s.metricCd || ''] || s.metricCd, basisText(s.basis))));
            }
            scoreBox.appendChild(block);
        });
    }

    // ---------------------------------------------------------------- 만들기

    function newEval() {
        srmUi.leaveOk().then(function (/** @type {boolean} */ ok) { if (ok) { startNew(); } });
    }

    function startNew() {
        show(null);
        /** @type {HTMLElement} */ (document.getElementById('createBox')).hidden = !CAN('SAVE');
        /** @type {HTMLElement} */ (document.getElementById('formMode')).textContent = hb.t('js.srm.eval.creating');
        hb.showFieldErrors(createForm, []);
        hb.page.openDetail();
        field(createForm, 'custCd').focus();
    }
    ['newBtn', 'newBtnList'].forEach(function (id) {
        var b = document.getElementById(id);
        if (b) { b.addEventListener('click', newEval); }
    });

    var createBtn = /** @type {HTMLButtonElement} */ (document.getElementById('createBtn'));
    createBtn.addEventListener('click', function () {
        var body = {custCd: field(createForm, 'custCd').value, tmplCd: field(createForm, 'tmplCd').value,
            periodFrom: field(createForm, 'periodFrom').value || null, periodTo: field(createForm, 'periodTo').value || null,
            remark: field(createForm, 'remark').value};
        hb.busy(createBtn, function () { return hb.api(API, {method: 'POST', body: body}); }).then(function (res) {
            if (!res) { return; }
            hb.dirty.clear();
            return load(res.data).then(function () { notify(hb.t('js.srm.eval.created')); });
        }, function (err) {
            notify(err.errors && err.errors.length ? err.errors[0].message : err.message, true);
            hb.showFieldErrors(createForm, err.errors);
        });
    });

    // ---------------------------------------------------------------- 점수 · 상태

    /** @param {any} err */
    function failed(err) {
        notify(err.errors && err.errors.length ? err.errors[0].message : err.message, true);
        renderScores(err.errors || []);
    }

    var saveBtn = /** @type {HTMLButtonElement} */ (document.getElementById('saveBtn'));
    saveBtn.addEventListener('click', function () {
        if (!current) { return; }
        var d = current;
        var body = {rowVer: d.eval.rowVer, remark: field(evalForm, 'remark').value,
            scores: d.scores.map(function (s) {
                var ed = edits[String(s.itemId)];
                return {itemId: s.itemId, score: s.autoYn === 'Y' ? null : ed.score || null, comment: ed.comment || null};
            })};
        hb.busy(saveBtn, function () { return hb.api(API + '/' + d.eval.evalId + '/scores', {method: 'PUT', body: body}); })
            .then(function (res) {
                if (!res) { return; }
                hb.dirty.clear();
                return load(d.eval.evalId).then(function () { notify(hb.t('js.common.saved')); });
            }, failed);
    });

    /**
     * @param {string} id @param {string} act
     * @param {() => Promise<string|null>} [before] 묻기 — 사유(또는 '') / null = 대화상자 취소(아무 일 없음)
     */
    function action(id, act, before) {
        var b = /** @type {HTMLButtonElement} */ (document.getElementById(id));
        b.addEventListener('click', function () {
            if (!current) { return; }
            if (hb.dirty.isDirty()) { notify(hb.t('js.srm.eval.saveFirst'), true); return; }
            var d = current;
            (before ? before() : Promise.resolve('')).then(function (reason) {
                if (reason === null) { return; }
                hb.busy(b, function () {
                    return hb.api(API + '/' + d.eval.evalId + '/' + act, {method: 'POST', body: {rowVer: d.eval.rowVer, reason: reason || null}});
                }).then(function (res) {
                    if (!res) { return; }
                    return load(d.eval.evalId).then(function () { notify(hb.t('js.common.saved')); });
                }, failed);
            });
        });
    }
    action('recalcBtn', 'recalc');
    action('confirmBtn', 'confirm', function () {
        return srmUi.ask('js.srm.dlg.evalConfirm', hb.t('js.srm.eval.confirmConfirm'), 'js.srm.dlg.evalConfirmOk', false)
            .then(function (/** @type {boolean} */ yes) { return yes ? '' : null; });
    });
    action('cancelBtn', 'cancel', function () {
        return srmUi.reason('js.srm.dlg.evalCancel', 'js.srm.eval.cancelLabel', 'js.srm.dlg.evalCancelOk');
    });

    // ---------------------------------------------------------------- 시작

    hb.dirty.watch(evalForm);
    hb.dirty.watch(createForm);
    search.addEventListener('submit', function (ev) { ev.preventDefault(); });
    hb.loadMessages().then(function () { return hb.loadSession(); }).then(function () {
        hb.page.init();
        sp = hb.searchPanel(search, {onSearch: function () {
            return srmUi.leaveOk().then(function (/** @type {boolean} */ ok) { return ok ? load() : null; });
        }});
        return Promise.all([sp.ready, hb.codes('EVAL_STATUS'), hb.api(API + '/suppliers'), hb.api(API + '/tmpls'), hb.codes('EVAL_METRIC')]);
    }).then(function (r) {
        statusNames = r[1];
        metricNames = r[4];
        var dl = /** @type {HTMLElement} */ (document.getElementById('supList'));
        r[2].data.forEach(function (/** @type {{custCd: string, custNm: string}} */ s) {
            var o = hb.el('option', null, s.custNm);
            o.value = s.custCd;
            dl.appendChild(o);
        });
        var sel = /** @type {HTMLSelectElement} */ (document.getElementById('tmplSel'));
        /** @type {Record<string, boolean>} */
        var seen = {};
        r[3].data.forEach(function (/** @type {{tmplCd: string, tmplNm: string}} */ t) {
            if (seen[t.tmplCd]) { return; }       // 코드마다 하나 — 리비전은 기간 끝 날로 서버가 고른다
            seen[t.tmplCd] = true;
            var o = hb.el('option', null, t.tmplCd + ' · ' + t.tmplNm);
            o.value = t.tmplCd;
            sel.appendChild(o);
        });
        grid = hb.grid('#evalGrid', {
            progCd: 'srm-eval', id: 'main', key: 'evalId',
            url: API + '/grid', columnsUrl: API + '/columns', exportUrl: API + '/export', actions: ACTIONS, params: params,
            filters: {status: 'status'},
            onFilter: function (param, value) { sp.setValue(param, value); },
            beforeSelect: function () { return srmUi.leaveOk(); },
            status: {
                status: srmUi.TONES.eval,
                failYn: function (v) { return v === 'Y' ? 'danger' : null; },              // 과락 = 위험 배지
                suggestTradeStatus: srmUi.TONES.trade                                       // 거래 보류 제안 = 거래 상태 톤
            },
            rowClass: function (row) { return row.status === 'CANCELLED' ? 'hb-tree-dim' : null; },
            onSelect: function (row) { openRow(Number(row.evalId)); },
            notify: notify
        });
        return grid.ready.then(function () { return load(); });
    }).then(null, function (err) { notify(err && err.message, true); });
})();
