// @ts-check
/*
 * 관리계획서 (apqp-cp) — P6 APQP A2 (docs/41 5장, 결정 C1~C8).
 * 목록(hb.grid, 클릭·Enter = 상세) → 상세: 머리 / 행이 없는 특별특성 / 행 표 / 고른 행 편집.
 * 고칠 때마다 서버에 한 줄씩 보내고 상세를 다시 읽는다. 고칠 수 있는지는 서버가 준 editable,
 * 판정(라우팅 단계·특성 대상·근거 위험행·확정본·회사)은 서버·트리거 — 화면은 단추만 숨긴다.
 */
(function () {
    'use strict';

    var API = '/api/apqp/cps';
    var PHASES = ['PROTO', 'PRE_LAUNCH', 'PRODUCTION'];
    var ACTIONS = (document.body.getAttribute('data-actions') || '').split(',');
    var CAN_SAVE = ACTIONS.indexOf('SAVE') >= 0;
    var CAN_APPROVE = ACTIONS.indexOf('APPROVE') >= 0;
    var CAN_PRINT = ACTIONS.indexOf('PRINT') >= 0;
    var msg = /** @type {HTMLElement} */ (document.getElementById('msg'));
    var listView = /** @type {HTMLElement} */ (document.getElementById('listView'));
    var detailView = /** @type {HTMLElement} */ (document.getElementById('detailView'));
    var headForm = /** @type {HTMLFormElement} */ (document.getElementById('headForm'));
    var lineForm = /** @type {HTMLFormElement} */ (document.getElementById('lineForm'));
    /** 서버 상세(CpDetail) — 신규 작성 중이면 null @type {any} */
    var d = null;
    /** @type {number | null} */
    var selLine = null;
    /** @type {any[]} */
    var items = [];
    /** 근거로 고를 확정 PFMEA(품목마다) @type {any[]} */
    var fmeas = [];
    /** @type {Record<string, string>} */
    var phaseNames = {};
    /** @type {Record<string, string>} */
    var methodNames = {};
    /** @type {Record<string, string>} */
    var gaugeNames = {};
    /** @type {Record<string, string>} */
    var inspTypeNames = {};
    /** @type {Record<string, string>} */
    var freqNames = {};
    /** @type {Record<string, string>} */
    var specialNames = {};
    /** @type {Record<string, string>} */
    var statusNames = {};
    /** @type {HB.SearchPanel} */
    var sp;
    /** @type {HB.Grid} */
    var grid;

    /** @param {string | null | undefined} text @param {boolean} [error] */
    function notify(text, error) {
        msg.textContent = text || '';
        msg.classList.toggle('hb-msg-error', !!error);
    }

    /** @param {HTMLFormElement | null} [f] */
    function fail(f) {
        /** @param {any} err */
        return function (err) {
            if (err && err.stale) { return; }
            var e0 = err && err.errors && err.errors.length ? err.errors[0] : null;
            notify(e0 ? e0.message : err && err.message, true);
            if (f) { hb.showFieldErrors(f, err && err.errors); }
        };
    }

    /** @param {HTMLFormElement} f @param {string} n */
    function fld(f, n) { return /** @type {HTMLInputElement} */ (f.querySelector('[data-field="' + n + '"]')); }

    /** @param {HTMLSelectElement} sel @param {string} value @param {string} text */
    function option(sel, value, text) {
        var o = /** @type {HTMLOptionElement} */ (hb.el('option', null, text));
        o.value = value;
        sel.appendChild(o);
    }

    /** @param {string} id */
    function sel(id) { return /** @type {HTMLSelectElement} */ (document.getElementById(id)); }

    /** "3" → 3, 빈 칸 → null, 그 밖은 글자 그대로(서버가 범위 오류) @param {string} v */
    function num(v) {
        var s = v.trim();
        return s === '' ? null : (/^\d+$/.test(s) ? Number(s) : s);
    }

    /** @param {string} title @param {string} message @param {string} ok @param {boolean} [danger] */
    function ask(title, message, ok, danger) {
        return hb.dialog.confirm({title: title, message: message, ok: ok, danger: !!danger});
    }

    function leaveOk() {
        if (!hb.dirty.isDirty()) { return Promise.resolve(true); }
        return hb.dialog.confirm({title: hb.t('js.common.leaveTitle'), message: hb.t('js.common.confirmLeave'), ok: hb.t('js.common.leaveOk')});
    }

    /** @param {string | null | undefined} ap */
    function apBadge(ap) {
        /** @type {Record<string, HB.StatusTone>} */
        var tone = {H: 'danger', M: 'warning', L: 'neutral'};
        return ap ? hb.status(tone[ap] || 'neutral', ap) : hb.el('span', 'hb-muted', '—');
    }

    /** @param {string | null | undefined} cls */
    function specialBadge(cls) {
        return cls && cls !== 'NONE' ? hb.status('warning', specialNames[cls] || cls) : hb.el('span', 'hb-muted', '—');
    }

    /** @param {string} id @param {boolean} on */
    function show(id, on) {
        var b = document.getElementById(id);
        if (b) { b.hidden = !on; }
    }

    // ------------------------------------------------------------------ 목록

    function params() {
        var v = sp.values();
        /** @type {Record<string, unknown>} */
        var q = {};
        ['keyword', 'status', 'phase'].forEach(function (k) { if (v[k]) { q[k] = v[k]; } });
        return q;
    }

    function openSelected() {
        var row = grid.selected();
        if (row) { openCp(Number(row.cpId)); }
    }

    function showList() {
        leaveOk().then(function (ok) {
            if (!ok) { return; }
            hb.dirty.clear();
            detailView.hidden = true;
            listView.hidden = false;
            d = null;
            grid.reload(true);
        });
    }

    // ------------------------------------------------------------------ 상세 읽기

    /** @param {number} id */
    function openCp(id) {
        selLine = null;
        return load(id).then(function () {
            listView.hidden = true;
            detailView.hidden = false;
        });
    }

    /** @param {number} id */
    function load(id) {
        return hb.latest('cp', hb.api(API + '/' + id)).then(function (res) {
            d = res.data;
            if (selLine !== null && !lineOf(selLine)) { selLine = null; }
            render();
            loadDoc();
        }, fail());
    }

    function reload() {
        return d ? load(d.cp.cpId) : Promise.resolve();
    }

    /** @param {number} id */
    function lineOf(id) { return d ? d.lines.filter(/** @param {any} l */ function (l) { return l.lineId === id; })[0] : null; }

    function render() {
        renderHead();
        renderApproval();
        renderGaps();
        renderCompare();
        renderLines();
        renderLineBox();
    }

    // ------------------------------------------------------------------ 머리

    function renderHead() {
        var c = d ? d.cp : null;
        var edit = !c || d.editable;
        /** @type {HTMLElement} */ (document.getElementById('title')).textContent = c
            ? c.cpNo + ' rev ' + c.rev + ' · ' + c.title : hb.t('js.apqp.cp.creating');
        var st = /** @type {HTMLElement} */ (document.getElementById('statusBox'));
        st.textContent = '';
        if (c) {
            /** @type {Record<string, HB.StatusTone>} */
            var tone = {DRAFT: 'info', IN_APPROVAL: 'warning', RELEASED: 'success', OBSOLETE: 'neutral'};
            st.appendChild(hb.status(tone[c.status] || 'neutral', statusNames[c.status] || c.status));
            st.appendChild(document.createTextNode(' '));
            st.appendChild(hb.status('neutral', phaseNames[c.phase] || c.phase));
        }
        /** @type {HTMLElement} */ (document.getElementById('summary')).textContent = c
            ? hb.t('js.apqp.cp.summary', c.itemCd + ' ' + c.itemNm, c.fmeaNo ? c.fmeaNo + ' rev ' + c.fmeaRev : '-', c.routingText || '-', c.lineCnt)
                + (c.draftExists ? ' · ' + hb.t('js.apqp.draftExists') : '') : '';
        var stale = /** @type {HTMLElement} */ (document.getElementById('staleBox'));
        stale.hidden = !(c && c.fmeaStale);
        stale.textContent = c && c.fmeaStale ? hb.t('js.apqp.cp.stale', c.fmeaNo + ' rev ' + c.fmeaRev) : '';
        ['itemCd', 'title', 'team', 'changeReason'].forEach(function (n) { fld(headForm, n).value = c ? (c[n] || '') : ''; });
        fld(headForm, 'itemCd').readOnly = !!c;
        fld(headForm, 'phase').value = c ? c.phase : '';
        fld(headForm, 'projId').value = c && c.projId ? String(c.projId) : '';
        headForm.querySelectorAll('input, select, textarea').forEach(function (el) {
            /** @type {HTMLInputElement} */ (el).disabled = !CAN_SAVE || !edit;
        });
        fld(headForm, 'phase').disabled = !CAN_SAVE || !!c;
        headForm.querySelectorAll('.apqp-create-only').forEach(function (el) { /** @type {HTMLElement} */ (el).hidden = !!c; });
        /** @type {HTMLDetailsElement} */ (document.getElementById('headDetails')).open = !c;
        if (c) {
            loadFmeas(c.itemCd, c.fmeaId).then(function () { return loadRoutings(c.itemId, c.routingId); });
        }
        show('saveHeadBtn', CAN_SAVE && edit);
        show('pullBtn', CAN_SAVE && !!c && d.editable && !!c.fmeaId);
        show('releaseBtn', CAN_APPROVE && !!c && d.editable && !d.approvalOn);
        show('reviseBtn', CAN_SAVE && !!c && c.status === 'RELEASED' && !c.draftExists);
        show('excelBtn', CAN_PRINT && !!c);
        var next = c && c.status === 'RELEASED' ? PHASES.slice(PHASES.indexOf(c.phase) + 1) : [];
        show('nextBox', CAN_SAVE && next.length > 0);
        var ns = document.getElementById('nextPhaseSel');
        if (ns) {
            var nsel = /** @type {HTMLSelectElement} */ (ns);
            nsel.textContent = '';
            next.forEach(function (p) { option(nsel, p, phaseNames[p] || p); });
        }
        show('deleteBtn', CAN_SAVE && !!c && d.editable);
        show('linesBox', !!c);
        detailView.querySelectorAll('.apqp-edit').forEach(function (el) { /** @type {HTMLElement} */ (el).hidden = !c || !d.editable; });
    }

    /** 품목의 확정 PFMEA → 근거 고르기 @param {string} itemCd @param {number | null} selected */
    function loadFmeas(itemCd, selected) {
        return hb.latest('fmeas', hb.api(API + '/fmeas?itemCd=' + encodeURIComponent(itemCd))).then(function (res) {
            fmeas = res.data;
            var s = sel('fmeaSel');
            s.textContent = '';
            option(s, '', hb.t('js.apqp.cp.noFmea'));
            fmeas.forEach(function (f) { option(s, String(f.fmeaId), f.fmeaNo + ' rev ' + f.rev + ' · ' + f.title); });
            // 지금 근거가 폐기됐으면 목록에 없다 — 표시만 남긴다
            if (selected && !fmeas.some(function (f) { return f.fmeaId === selected; }) && d) {
                option(s, String(selected), d.cp.fmeaNo + ' rev ' + d.cp.fmeaRev + ' (' + (statusNames.OBSOLETE || 'OBSOLETE') + ')');
            }
            s.value = selected ? String(selected) : '';
            syncRoutingLock();
        }, fail());
    }

    /** @param {number} itemId @param {number | null} selected */
    function loadRoutings(itemId, selected) {
        return hb.latest('routings', hb.api(API + '/items/' + itemId + '/routings')).then(function (res) {
            var s = sel('routingSel');
            s.textContent = '';
            option(s, '', hb.t('js.apqp.noRouting'));
            res.data.forEach(/** @param {any} r */ function (r) {
                option(s, String(r.routingId), r.plantCd + ' · ' + r.altNo + ' · ' + r.rev + ' (' + r.status + ', ' + r.validFrom + ')');
            });
            s.value = selected ? String(selected) : '';
            syncRoutingLock();
        }, fail());
    }

    /** 근거 PFMEA 를 고르면 라우팅은 그 FMEA 의 것(서버가 정한다 — C2) */
    function syncRoutingLock() {
        var f = sel('fmeaSel').value;
        var r = sel('routingSel');
        var hit = fmeas.filter(function (x) { return String(x.fmeaId) === f; })[0];
        if (hit) { r.value = hit.routingId ? String(hit.routingId) : ''; }
        r.disabled = !CAN_SAVE || !!f || (!!d && !d.editable);
    }
    sel('fmeaSel').addEventListener('change', syncRoutingLock);

    var itemInput = fld(headForm, 'itemCd');
    var itemTimer = 0;
    itemInput.addEventListener('input', function () {
        window.clearTimeout(itemTimer);
        itemTimer = window.setTimeout(function () {
            hb.latest('items', hb.api(API + '/items?keyword=' + encodeURIComponent(itemInput.value.trim()))).then(function (res) {
                items = res.data;
                var dl = /** @type {HTMLElement} */ (document.getElementById('itemList'));
                dl.textContent = '';
                items.forEach(function (i) {
                    var o = /** @type {HTMLOptionElement} */ (hb.el('option', null, i.itemNm));
                    o.value = i.itemCd;
                    dl.appendChild(o);
                });
                var hit = items.filter(function (i) { return i.itemCd === itemInput.value.trim(); })[0];
                if (hit) { loadFmeas(hit.itemCd, null).then(function () { return loadRoutings(hit.itemId, null); }); }
            }, fail());
        }, 250);
    });

    function headBody() {
        /** @param {string} n */
        function idOf(n) { var v = fld(headForm, n).value; return v ? Number(v) : null; }
        return {itemCd: fld(headForm, 'itemCd').value, fmeaId: idOf('fmeaId'), routingId: idOf('routingId'), phase: fld(headForm, 'phase').value,
            projId: idOf('projId'), title: fld(headForm, 'title').value, team: fld(headForm, 'team').value,
            changeReason: fld(headForm, 'changeReason').value, pullFromFmea: fld(headForm, 'pullFromFmea').checked,
            rowVer: d ? d.cp.rowVer : null};
    }

    // ------------------------------------------------------------------ 행이 없는 특별특성

    function renderGaps() {
        var box = /** @type {HTMLElement} */ (document.getElementById('gapBox'));
        var ul = /** @type {HTMLElement} */ (document.getElementById('gapList'));
        ul.textContent = '';
        var gaps = d ? d.gaps : [];
        box.hidden = !gaps.length;
        gaps.forEach(/** @param {any} g */ function (g) {
            var li = hb.el('li', 'apqp-line');
            li.appendChild(specialBadge(g.specialCls));
            li.appendChild(document.createTextNode(g.kind === 'RISK'
                ? hb.t('js.apqp.cp.gapRisk', g.text) : hb.t('js.apqp.cp.gapChar', (g.charNo || '') + ' ' + (g.charNm || ''))));
            if (d.editable && g.charId) {
                var b = /** @type {HTMLButtonElement} */ (hb.el('button', 'hb-btn-ghost apqp-small', hb.t('js.apqp.cp.addForGap')));
                b.type = 'button';
                b.addEventListener('click', function () {
                    selLine = null;
                    renderLines();
                    fillLine(null);
                    fld(lineForm, 'charId').value = String(g.charId);
                    if (g.kind === 'RISK') { fld(lineForm, 'riskId').value = String(g.id); }
                    fld(lineForm, 'specialCls').value = g.specialCls || '';
                    hb.dirty.mark();
                    fld(lineForm, 'ctrlMethod').focus();
                });
                li.appendChild(b);
            }
            ul.appendChild(li);
        });
    }

    // ------------------------------------------------------------------ 검사계획 대조 (C5)

    /** 문구 — 키는 글자 그대로(I18nKeyGuardTest) @param {string} k */
    function cmpKindName(k) {
        /** @type {Record<string, string>} */
        var m = {MISSING_IN_PLAN: hb.t('js.apqp.cp.cmp.MISSING_IN_PLAN'), DIFF: hb.t('js.apqp.cp.cmp.DIFF'),
            MISSING_IN_CP: hb.t('js.apqp.cp.cmp.MISSING_IN_CP')};
        return m[k] || k;
    }

    /** @param {string | null} type @param {number | null} n */
    function freqText(type, n) {
        if (!type) { return '—'; }
        var name = freqNames[type] || type;
        return n == null ? name : name.replace('N', String(n));
    }

    /** CP 값 / 검사계획 값 — 다르면 강조 @param {string} a @param {string} b */
    function pair(a, b) {
        var td = hb.el('td');
        td.appendChild(hb.el('span', null, a));
        td.appendChild(hb.el('span', 'hb-muted', ' / '));
        td.appendChild(hb.el('span', a !== b && a !== '—' && b !== '—' ? 'hb-warn' : null, b));
        return td;
    }

    function renderCompare() {
        var box = /** @type {HTMLElement} */ (document.getElementById('compareBox'));
        var tbody = /** @type {HTMLElement} */ (document.querySelector('#compareTable tbody'));
        tbody.textContent = '';
        var cmp = d ? d.compare : null;
        box.hidden = !cmp || (!d.lines.length);
        if (!cmp) { return; }
        var summary = /** @type {HTMLElement} */ (document.getElementById('compareSummary'));
        summary.textContent = cmp.ready ? hb.t('js.apqp.cp.cmp.summary', cmp.matched, cmp.rows.length, cmp.skipped) : hb.t('js.apqp.cp.cmp.notReady');
        /** @type {HTMLElement} */ (document.getElementById('compareTable')).hidden = !cmp.rows.length;
        /** @type {Record<string, HB.StatusTone>} */
        var tone = {MISSING_IN_PLAN: d.cp.phase === 'PRODUCTION' ? 'danger' : 'warning', DIFF: 'warning', MISSING_IN_CP: 'info'};
        cmp.rows.forEach(/** @param {any} r */ function (r) {
            var tr = hb.el('tr');
            var k = hb.el('td');
            k.appendChild(hb.status(tone[r.kind] || 'neutral', cmpKindName(r.kind)));
            tr.appendChild(k);
            tr.appendChild(hb.el('td', null, (r.charNo || '') + ' ' + (r.charNm || '')));
            tr.appendChild(hb.el('td', null, inspTypeNames[r.inspType] || r.inspType));
            tr.appendChild(hb.el('td', null, r.procCd || '—'));
            tr.appendChild(hb.el('td', null, r.planRev ? 'rev ' + r.planRev : '—'));
            tr.appendChild(pair(r.cpGauge ? (gaugeNames[r.cpGauge] || r.cpGauge) : '—', r.planGauge ? (gaugeNames[r.planGauge] || r.planGauge) : '—'));
            tr.appendChild(pair(freqText(r.cpFreqType, r.cpFreqN), freqText(r.planFreqType, r.planFreqN)));
            tr.appendChild(hb.el('td', null, (r.cpSample || '—') + ' / ' + (r.planSampling || '—')));
            if (r.lineId) {
                tr.tabIndex = 0;
                tr.addEventListener('click', function () { selLine = r.lineId; renderLines(); renderLineBox(); });
            }
            tbody.appendChild(tr);
        });
    }

    // ------------------------------------------------------------------ 행 표

    /** @param {any} l */
    function stepLabel(l) { return l.stepId ? l.opSeq + ' ' + (l.procNm || '') : (l.stepText || ''); }
    /** @param {any} l */
    function charLabel(l) { return l.charId ? l.charNo + ' ' + l.charNm : (l.charText || ''); }
    /** @param {any} l */
    function specLabel(l) {
        var released = d.cp.status !== 'DRAFT';
        var lsl = released ? l.lsl : l.charLsl;
        var usl = released ? l.usl : l.charUsl;
        var unit = released ? l.unitNm : l.charUnit;
        if (lsl == null && usl == null) { return ''; }
        return (lsl == null ? '' : hb.numbers.format(lsl)) + ' ~ ' + (usl == null ? '' : hb.numbers.format(usl)) + (unit ? ' ' + unit : '');
    }
    /** @param {any} l */
    function freqLabel(l) {
        if (!l.freqType) { return ''; }
        var name = freqNames[l.freqType] || l.freqType;
        // "N 개마다" · "N 시간마다" 의 N 자리에 값
        return l.freqN == null ? name : name.replace('N', String(l.freqN));
    }

    function renderLines() {
        var tbody = /** @type {HTMLElement} */ (document.querySelector('#lineTable tbody'));
        tbody.textContent = '';
        if (!d) { return; }
        var released = d.cp.status !== 'DRAFT';
        d.lines.forEach(/** @param {any} l @param {number} i */ function (l, i) {
            var tr = hb.el('tr');
            tr.setAttribute('aria-selected', l.lineId === selLine ? 'true' : 'false');
            tr.tabIndex = 0;
            tr.appendChild(hb.el('td', 'hb-num', String(i + 1)));
            tr.appendChild(hb.el('td', null, stepLabel(l)));
            tr.appendChild(hb.el('td', null, charLabel(l)));
            var sp0 = hb.el('td');
            sp0.appendChild(specialBadge(released ? l.specialCls : (l.specialCls || l.charSpecial)));
            tr.appendChild(sp0);
            tr.appendChild(hb.el('td', 'hb-num', specLabel(l)));
            tr.appendChild(hb.el('td', null, methodNames[l.ctrlMethod] || l.ctrlMethod));
            tr.appendChild(hb.el('td', null, l.evalMethod || ''));
            tr.appendChild(hb.el('td', null, l.gaugeClass ? (gaugeNames[l.gaugeClass] || l.gaugeClass) : ''));
            tr.appendChild(hb.el('td', null, [l.sampleN == null ? '' : String(l.sampleN), l.sampleText || ''].filter(Boolean).join(' · ')));
            tr.appendChild(hb.el('td', null, freqLabel(l)));
            tr.appendChild(hb.el('td', null, l.reactionPlan || ''));
            var ap = hb.el('td');
            ap.appendChild(apBadge(l.riskAp));
            tr.appendChild(ap);
            /** @param {Event} e */
            function pick(e) {
                e.preventDefault();
                leaveOk().then(function (ok) {
                    if (!ok) { return; }
                    hb.dirty.clear();
                    selLine = l.lineId;
                    renderLines();
                    renderLineBox();
                });
            }
            tr.addEventListener('click', pick);
            tr.addEventListener('keydown', function (e) { if (e.key === 'Enter') { pick(e); } });
            tbody.appendChild(tr);
        });
        if (!d.lines.length) {
            var empty = hb.el('tr');
            var td = hb.el('td', 'hb-muted', hb.t(d.cp.fmeaId ? 'js.apqp.cp.emptyPull' : 'js.apqp.cp.empty'));
            td.setAttribute('colspan', '12');
            empty.appendChild(td);
            tbody.appendChild(empty);
        }
    }

    // ------------------------------------------------------------------ 행 편집

    function renderLineBox() {
        var l = selLine ? lineOf(selLine) : null;
        /** @type {HTMLElement} */ (document.getElementById('lineTitle')).textContent = l
            ? hb.t('js.apqp.cp.lineTitle', stepLabel(l), charLabel(l)) : hb.t('js.apqp.cp.newLineTitle');
        fillLine(l);
        var edit = !!d && d.editable;
        lineForm.querySelectorAll('input, select').forEach(function (el) { /** @type {HTMLInputElement} */ (el).disabled = !CAN_SAVE || !edit; });
        show('deleteLineBtn', CAN_SAVE && edit && !!l);
        // 확정본은 표가 전부 — 편집 칸은 작성 중에만
        /** @type {HTMLElement} */ (document.getElementById('lineBox')).hidden = !edit;
        syncTextBoxes();
    }

    /** 단계 고르기 — 라우팅 단계 + "글자로" */
    function fillSteps() {
        var s = sel('stepSel');
        s.textContent = '';
        option(s, '', hb.t('js.apqp.cp.byText'));
        (d ? d.steps : []).forEach(/** @param {any} x */ function (x) { option(s, String(x.stepId), x.opSeq + ' ' + x.procNm); });
    }

    /** 특성 고르기 — 제품 특성 + 고른 단계 공정의 공정 특성(단계를 안 고르면 공정 특성 전부) */
    function fillChars() {
        var s = sel('charSel');
        var keep = s.value;
        s.textContent = '';
        option(s, '', hb.t('js.apqp.cp.byText'));
        var stepId = fld(lineForm, 'stepId').value;
        var step = d ? d.steps.filter(/** @param {any} x */ function (x) { return String(x.stepId) === stepId; })[0] : null;
        (d ? d.chars : []).forEach(/** @param {any} c */ function (c) {
            if (c.charType === 'PROCESS' && step && c.procCd !== step.procCd) { return; }
            option(s, String(c.charId), c.charNo + ' ' + c.charNm + (c.procCd ? ' [' + c.procCd + ']' : '')
                + (c.specialCls && c.specialCls !== 'NONE' ? ' · ' + (specialNames[c.specialCls] || c.specialCls) : ''));
        });
        s.value = keep;
    }

    /** @param {any} l */
    function fillLine(l) {
        hb.showFieldErrors(lineForm, []);
        fillSteps();
        fld(lineForm, 'stepId').value = l && l.stepId ? String(l.stepId) : '';
        fillChars();
        fld(lineForm, 'charId').value = l && l.charId ? String(l.charId) : '';
        ['stepText', 'charText', 'evalMethod', 'sampleText', 'reactionPlan'].forEach(function (n) { fld(lineForm, n).value = l ? (l[n] || '') : ''; });
        ['sampleN', 'freqN'].forEach(function (n) { fld(lineForm, n).value = l && l[n] != null ? String(l[n]) : ''; });
        fld(lineForm, 'ctrlMethod').value = l ? l.ctrlMethod : 'INSPECT';
        ['gaugeClass', 'inspType', 'freqType', 'specialCls'].forEach(function (n) { fld(lineForm, n).value = l ? (l[n] || '') : ''; });
        var rs = sel('riskSel');
        rs.textContent = '';
        option(rs, '', hb.t('js.apqp.cp.noRisk'));
        (d ? d.risks : []).forEach(/** @param {any} r */ function (r) {
            option(rs, String(r.riskId), (r.ap ? '[' + r.ap + '] ' : '') + (r.stepNm ? r.stepNm + ' · ' : '') + r.fm + ' ← ' + r.fc);
        });
        rs.value = l && l.riskId ? String(l.riskId) : '';
        syncTextBoxes();
    }

    /** 키를 고르면 글자 칸은 숨긴다 */
    function syncTextBoxes() {
        show('stepTextBox', !fld(lineForm, 'stepId').value);
        show('charTextBox', !fld(lineForm, 'charId').value);
        var f = fld(lineForm, 'freqType').value;
        fld(lineForm, 'freqN').disabled = !(f === 'EVERY_N' || f === 'EVERY_HOURS') || !CAN_SAVE || !d || !d.editable;
    }
    fld(lineForm, 'stepId').addEventListener('change', function () { fillChars(); syncTextBoxes(); });
    fld(lineForm, 'charId').addEventListener('change', syncTextBoxes);
    fld(lineForm, 'freqType').addEventListener('change', syncTextBoxes);

    function lineBody() {
        /** @param {string} n */
        function idOf(n) { var v = fld(lineForm, n).value; return v ? Number(v) : null; }
        var l = selLine ? lineOf(selLine) : null;
        var stepId = idOf('stepId');
        var charId = idOf('charId');
        return {stepId: stepId, stepText: stepId ? null : fld(lineForm, 'stepText').value, charId: charId,
            charText: charId ? null : fld(lineForm, 'charText').value, ctrlMethod: fld(lineForm, 'ctrlMethod').value,
            evalMethod: fld(lineForm, 'evalMethod').value, gaugeClass: fld(lineForm, 'gaugeClass').value || null,
            inspType: fld(lineForm, 'inspType').value || null, sampleN: num(fld(lineForm, 'sampleN').value),
            sampleText: fld(lineForm, 'sampleText').value, freqType: fld(lineForm, 'freqType').value || null,
            freqN: num(fld(lineForm, 'freqN').value), reactionPlan: fld(lineForm, 'reactionPlan').value, riskId: idOf('riskId'),
            specialCls: fld(lineForm, 'specialCls').value || null, rowVer: l ? l.rowVer : null};
    }

    // ------------------------------------------------------------------ 보내기

    /** @param {string} url @param {'POST' | 'PUT' | 'DELETE'} method @param {any} body @param {HTMLFormElement | null} form */
    function send(url, method, body, form) {
        if (form) { hb.showFieldErrors(form, []); }
        return hb.api(url, {method: method, body: body}).then(function (res) {
            hb.dirty.clear();
            notify(hb.t('js.common.saved'));
            return reload().then(function () { return res; });
        }, fail(form));
    }

    /** @type {HTMLElement} */ (document.getElementById('backBtn')).addEventListener('click', showList);

    var newBtn = /** @type {HTMLButtonElement | null} */ (document.getElementById('newBtn'));
    if (newBtn) {
        newBtn.addEventListener('click', function () {
            d = null;
            selLine = null;
            fmeas = [];
            render();
            sel('routingSel').textContent = '';
            sel('fmeaSel').textContent = '';
            fld(headForm, 'phase').value = 'PROTO';
            listView.hidden = true;
            detailView.hidden = false;
            fld(headForm, 'itemCd').focus();
        });
    }

    var saveHead = /** @type {HTMLButtonElement | null} */ (document.getElementById('saveHeadBtn'));
    if (saveHead) {
        var shb = saveHead;
        shb.addEventListener('click', function () {
            var editing = !!d;
            hb.showFieldErrors(headForm, []);
            hb.busy(shb, function () {
                return editing ? hb.api(API + '/' + d.cp.cpId, {method: 'PUT', body: headBody()}) : hb.api(API, {method: 'POST', body: headBody()});
            }).then(function (res) {
                if (!res) { return; }
                hb.dirty.clear();
                return (editing ? reload() : openCp(res.data)).then(function () { notify(hb.t('js.common.saved')); });
            }, fail(headForm));
        });
    }

    var pullBtn = /** @type {HTMLButtonElement | null} */ (document.getElementById('pullBtn'));
    if (pullBtn) {
        var pb = pullBtn;
        pb.addEventListener('click', function () {
            hb.busy(pb, function () { return hb.api(API + '/' + d.cp.cpId + '/pull', {method: 'POST', body: {}}); }).then(function (res) {
                if (!res) { return; }
                return reload().then(function () { notify(hb.t('js.apqp.cp.pulled', res.data.added, res.data.skipped)); });
            }, fail());
        });
    }

    var releaseBtn = /** @type {HTMLButtonElement | null} */ (document.getElementById('releaseBtn'));
    if (releaseBtn) {
        var rb = releaseBtn;
        rb.addEventListener('click', function () {
            ask(hb.t('js.apqp.cp.dlg.release'), hb.t('js.apqp.cp.confirmRelease', d.cp.cpNo + ' rev ' + d.cp.rev), hb.t('js.apqp.dlg.releaseOk'))
                .then(function (ok) {
                    if (!ok) { return; }
                    hb.busy(rb, function () { return hb.api(API + '/' + d.cp.cpId + '/release', {method: 'POST', body: {rowVer: d.cp.rowVer}}); })
                        .then(function (res) { if (res) { return reload().then(function () { notify(hb.t('js.apqp.cp.released')); }); } }, function (err) {
                            // 미달 사유: rowKey(LINE:id·CHAR:id·RISK:id) → 행 글자를 붙인다
                            var lines = (err && err.errors || []).slice(0, 5).map(/** @param {any} e */ function (e) {
                                var k = String(e.rowKey || '').split(':');
                                var l = k[0] === 'LINE' ? lineOf(Number(k[1])) : null;
                                var g = k[0] === 'CHAR' || k[0] === 'RISK' ? d.gaps.filter(/** @param {any} x */ function (x) {
                                    return x.kind === k[0] && x.id === Number(k[1]); })[0] : null;
                                var label = l ? stepLabel(l) + ' · ' + charLabel(l) : (g ? (g.charNo ? g.charNo + ' ' + (g.charNm || '') : g.text) : '');
                                return (label ? label + ': ' : '') + e.message;
                            });
                            notify((err && err.message || '') + (lines.length ? ' ' + lines.join(' / ') : ''), true);
                            if (err && err.errors) { hb.showFieldErrors(headForm, err.errors.filter(/** @param {any} e */ function (e) { return !e.rowKey; })); }
                        });
                });
        });
    }

    // QMS 관리문서로 올리기(A3-3) — 서버가 권한·상태를 판정(canPublish), 화면은 단추만
    function loadDoc() {
        var mine = d;
        show('docBtn', false);
        show('docInfo', false);
        if (!d) { return; }
        hb.api(API + '/' + d.cp.cpId + '/qms-doc').then(function (res) {
            if (d !== mine) { return; }
            var st = res.data;
            show('docBtn', st.canPublish);
            var info = /** @type {HTMLElement} */ (document.getElementById('docInfo'));
            info.textContent = st.linked ? hb.t('js.apqp.doc.linked', st.linked.docNo, st.linked.rev || '', docStatusName(st.linked.status)) : '';
            show('docInfo', !!st.linked);
        }, function () { /* 표시만 */ });
    }

    /** 문구 — 키는 글자 그대로(I18nKeyGuardTest) @param {string | null} s */
    function docStatusName(s) {
        /** @type {Record<string, string>} */
        var m = {DRAFT: hb.t('js.apqp.doc.DRAFT'), IN_APPROVAL: hb.t('js.apqp.doc.IN_APPROVAL'), RELEASED: hb.t('js.apqp.doc.RELEASED'),
            OBSOLETE: hb.t('js.apqp.doc.OBSOLETE')};
        return s ? (m[s] || s) : '';
    }

    var docBtn = /** @type {HTMLButtonElement | null} */ (document.getElementById('docBtn'));
    if (docBtn) {
        var dcb = docBtn;
        dcb.addEventListener('click', function () {
            ask(hb.t('js.apqp.doc.dlg'), hb.t('js.apqp.doc.confirm'), hb.t('js.apqp.doc.ok')).then(function (ok) {
                if (!ok) { return; }
                hb.busy(dcb, function () { return hb.api(API + '/' + d.cp.cpId + '/qms-doc', {method: 'POST', body: {}}); })
                    .then(function (res) { if (res) { notify(hb.t('js.apqp.doc.done', res.data.docNo)); loadDoc(); } }, fail());
            });
        });
    }

    // 엑셀 양식(A4) — 이 리비전 그대로, 확정본이 아니면 파일에 표시(서버)
    var excelBtn = /** @type {HTMLButtonElement | null} */ (document.getElementById('excelBtn'));
    if (excelBtn) {
        var xb = excelBtn;
        xb.addEventListener('click', function () {
            hb.busy(xb, function () { return hb.download(API + '/' + d.cp.cpId + '/excel'); }).then(null, fail());
        });
    }

    var reviseBtn = /** @type {HTMLButtonElement | null} */ (document.getElementById('reviseBtn'));
    if (reviseBtn) {
        var vb = reviseBtn;
        vb.addEventListener('click', function () {
            // 근거가 폐기됐으면 새 리비전에서 같은 번호의 확정 FMEA 로 바꾼다(근거 위험행은 비우고 다시 끌어오기)
            var newer = d.cp.fmeaStale ? d.newerFmeaId : null;
            var text = newer ? hb.t('js.apqp.cp.confirmReviseNewer', d.cp.cpNo, d.cp.rev + 1) : hb.t('js.apqp.confirmRevise', d.cp.cpNo, d.cp.rev + 1);
            ask(hb.t('js.apqp.dlg.revise'), text, hb.t('js.apqp.dlg.reviseOk')).then(function (ok) {
                if (!ok) { return; }
                hb.busy(vb, function () { return hb.api(API + '/' + d.cp.cpId + '/revise', {method: 'POST', body: newer ? {fmeaId: newer} : {}}); })
                    .then(function (res) { if (res) { return openCp(res.data).then(function () { notify(hb.t('js.apqp.revised')); }); } }, fail());
            });
        });
    }

    var nextBtn = /** @type {HTMLButtonElement | null} */ (document.getElementById('nextPhaseBtn'));
    if (nextBtn) {
        var nb = nextBtn;
        nb.addEventListener('click', function () {
            var phase = sel('nextPhaseSel').value;
            if (!phase) { return; }
            var newer = d.cp.fmeaStale ? d.newerFmeaId : null;
            ask(hb.t('js.apqp.cp.dlg.nextPhase'), hb.t('js.apqp.cp.confirmNextPhase', d.cp.cpNo, phaseNames[phase] || phase), hb.t('js.apqp.cp.dlg.nextPhaseOk'))
                .then(function (ok) {
                    if (!ok) { return; }
                    hb.busy(nb, function () {
                        return hb.api(API + '/' + d.cp.cpId + '/next-phase', {method: 'POST', body: {phase: phase, fmeaId: newer}});
                    }).then(function (res) { if (res) { return openCp(res.data).then(function () { notify(hb.t('js.apqp.cp.nextDone')); }); } }, fail());
                });
        });
    }

    var deleteBtn = /** @type {HTMLButtonElement | null} */ (document.getElementById('deleteBtn'));
    if (deleteBtn) {
        var db = deleteBtn;
        db.addEventListener('click', function () {
            ask(hb.t('js.apqp.cp.dlg.delete'), hb.t('js.apqp.cp.confirmDelete', d.cp.cpNo + ' rev ' + d.cp.rev), hb.t('js.apqp.dlg.delete'), true)
                .then(function (ok) {
                    if (!ok) { return; }
                    hb.busy(db, function () { return hb.api(API + '/' + d.cp.cpId + '?rowVer=' + d.cp.rowVer, {method: 'DELETE'}); })
                        .then(function (res) {
                            if (!res) { return; }
                            hb.dirty.clear();
                            detailView.hidden = true;
                            listView.hidden = false;
                            d = null;
                            return grid.reload(true).then(function () { notify(hb.t('js.apqp.deleted')); });
                        }, fail());
                });
        });
    }

    /** @type {HTMLElement} */ (document.getElementById('saveLineBtn')).addEventListener('click', function () {
        var l = selLine ? lineOf(selLine) : null;
        send(l ? '/api/apqp/cp-lines/' + l.lineId : API + '/' + d.cp.cpId + '/lines', l ? 'PUT' : 'POST', lineBody(), lineForm)
            .then(function (res) { if (res && !l) { selLine = res.data; renderLines(); renderLineBox(); } });
    });

    /** @type {HTMLElement} */ (document.getElementById('newLineBtn')).addEventListener('click', function () {
        leaveOk().then(function (ok) {
            if (!ok) { return; }
            hb.dirty.clear();
            selLine = null;
            renderLines();
            renderLineBox();
            fld(lineForm, 'stepId').focus();
        });
    });

    detailView.querySelectorAll('[data-move]').forEach(function (b) {
        b.addEventListener('click', function () {
            var l = selLine ? lineOf(selLine) : null;
            if (!l) { return; }
            send('/api/apqp/cp-lines/' + l.lineId + '/move', 'POST', {op: b.getAttribute('data-move'), rowVer: l.rowVer}, null);
        });
    });

    /** @type {HTMLElement} */ (document.getElementById('deleteLineBtn')).addEventListener('click', function () {
        var l = selLine ? lineOf(selLine) : null;
        if (!l) { return; }
        ask(hb.t('js.apqp.cp.dlg.deleteLine'), hb.t('js.apqp.cp.confirmDeleteLine', stepLabel(l) + ' · ' + charLabel(l)), hb.t('js.apqp.dlg.delete'), true)
            .then(function (ok) {
                if (!ok) { return; }
                selLine = null;
                send('/api/apqp/cp-lines/' + l.lineId + '?rowVer=' + l.rowVer, 'DELETE', null, null);
            });
    });

    [headForm, lineForm].forEach(function (f) { f.addEventListener('input', function () { hb.dirty.mark(); }); });


    // ------------------------------------------------------------------ 결재 상신(A3-7) · 결재 중 · 라우팅 재검토 안내

    /** 상신 전 결재선(화면에만) @type {{userId: number, nm: string, lineType: string}[]} */
    var aprLines = [];

    /** 문구 — 키는 글자 그대로(I18nKeyGuardTest) @param {string} t */
    function aprTypeName(t) {
        /** @type {Record<string, string>} */
        var m = {APPROVE: hb.t('js.apqp.apr.APPROVE'), AGREE: hb.t('js.apqp.apr.AGREE'), REF: hb.t('js.apqp.apr.REF')};
        return m[t] || t;
    }

    /** ECO 종류·4M 이름 — 처음 쓸 때 한 번 읽는다 @type {Promise<Record<string, string>[]> | null} */
    var changeNamesP = null;

    /**
     * 재검토 표시 ②③(docs/41 11.6) — 확정 뒤 이 품목에 적용된 ECO·승인된 4M 변경. 표시만, 서버가 고른 목록 그대로.
     * M4 = 4M 영향 특성과 이 문서 특성의 겹침(null = 세부 없음 → 표시 안 함), E-3(11.10) = 이 리비전을 배포한 ECO 는 따로 한 줄
     */
    function renderChanges() {
        var box = /** @type {HTMLElement} */ (document.getElementById('changeNotice'));
        var ul = /** @type {HTMLElement} */ (document.getElementById('changeList'));
        var ownBox = /** @type {HTMLElement} */ (document.getElementById('ownEcoNotice'));
        var c = d && d.changes ? d.changes : null;
        /** @type {any[]} */
        var rows = c ? c.rows : [];
        /** @type {any[]} */
        var own = c && c.own ? c.own : [];
        ownBox.hidden = own.length === 0;
        ownBox.textContent = own.length ? hb.t('js.apqp.ownEco', own.map(function (/** @type {any} */ r) { return r.ecoNo; }).join(', ')) : '';
        box.hidden = rows.length === 0;
        ul.textContent = '';
        if (!rows.length) {
            return;
        }
        /** @type {HTMLElement} */ (document.getElementById('changeHead')).textContent =
            hb.t('js.apqp.changeStale', rows.length) + (c.truncated ? ' ' + hb.t('js.apqp.changeMore') : '');
        if (!changeNamesP) {
            changeNamesP = Promise.all([hb.codes('PLM_CHANGE_KIND'), hb.codes('QMS_4M_TYPE')]);
        }
        changeNamesP.then(function (n) {
            ul.textContent = '';
            rows.forEach(function (r) {
                var parts = [r.ecoNo, r.title, n[0][r.changeKind] || r.changeKind,
                    r.releasedOn ? hb.t('js.apqp.changeReleased', r.releasedOn) : hb.t('js.apqp.changeApproved', r.approvedOn || '')];
                if (r.m4Types && r.m4Types.length) {
                    parts.push(hb.t('js.apqp.change4m', r.m4Types.map(function (/** @type {string} */ m) { return n[1][m] || m; }).join('·')));
                }
                if (r.overlap) {
                    // 겹침 없음이 "검토 안 해도 됨" 으로 읽히지 않게 — 다른 영향(전후 내용 등)은 ECO·4M 상세에서
                    parts.push(r.overlap.length ? hb.t('js.apqp.overlap', r.overlap.map(function (/** @type {any} */ x) {
                        var nm = x.charNm ? x.charNo + ' ' + x.charNm : x.charNo;
                        return x.special ? hb.t('js.apqp.overlapSpecial', nm) : nm;
                    }).join(', ')) : hb.t('js.apqp.overlapNone'));
                }
                var po = r.planOverlap;
                if (po) {
                    // 검사계획 겹침(11.6.2) — 비교 불가는 따로 센다(틀린 "겹침 없음" 을 내지 않음)
                    if (po.hits.length) {
                        parts.push(hb.t('js.apqp.planOverlap', po.hits.map(function (/** @type {any} */ p) {
                            return (p.procCd ? p.procCd + ' ' : '') + (inspTypeNames[p.inspType] || p.inspType) + ' rev ' + p.rev;
                        }).join(', ')));
                    }
                    if (po.unknown) {
                        parts.push(hb.t('js.apqp.planUnknown', po.unknown));
                    }
                    if (!po.hits.length && !po.unknown && po.unrelated) {
                        parts.push(hb.t('js.apqp.planNone'));
                    }
                }
                ul.appendChild(hb.el('li', null, parts.join(' · ')));
            });
        });
    }

    function renderApproval() {
        var h = d ? d.cp : null;
        var on = !!d && d.approvalOn;
        show('aprBox', CAN_SAVE && !!h && d.editable && on);
        var n = /** @type {HTMLElement} */ (document.getElementById('aprNotice'));
        n.hidden = !(h && h.status === 'IN_APPROVAL');
        n.textContent = h && h.status === 'IN_APPROVAL' ? hb.t('js.apqp.apr.inApproval') : '';
        var rn = /** @type {HTMLElement} */ (document.getElementById('routingNotice'));
        rn.hidden = !(h && h.routingStale);
        rn.textContent = h && h.routingStale ? hb.t('js.apqp.routingStale', h.routingText || '') : '';
        renderChanges();
        var ol = /** @type {HTMLElement} */ (document.getElementById('aprLines'));
        ol.textContent = '';
        aprLines.forEach(function (l, i) {
            var li = hb.el('li', 'apqp-line', l.nm + ' · ' + aprTypeName(l.lineType) + ' ');
            var x = /** @type {HTMLButtonElement} */ (hb.el('button', 'hb-btn-ghost apqp-small', hb.t('js.apqp.apr.remove')));
            x.type = 'button';
            x.addEventListener('click', function () { aprLines.splice(i, 1); renderApproval(); });
            li.appendChild(x);
            ol.appendChild(li);
        });
        if (!aprLines.length) { ol.appendChild(hb.el('li', 'hb-muted', hb.t('js.apqp.apr.empty'))); }
        /** @type {HTMLButtonElement} */ (document.getElementById('aprSubmitBtn')).disabled = !aprLines.length;
    }

    /** @type {HTMLElement} */ (document.getElementById('aprAddBtn')).addEventListener('click', function () {
        var u = /** @type {HTMLSelectElement} */ (document.getElementById('aprUser'));
        var t = /** @type {HTMLSelectElement} */ (document.getElementById('aprType'));
        if (!u.value || aprLines.some(function (l) { return String(l.userId) === u.value; })) { return; }
        aprLines.push({userId: Number(u.value), nm: u.options[u.selectedIndex].text, lineType: t.value});
        renderApproval();
    });

    var aprSubmit = /** @type {HTMLButtonElement} */ (document.getElementById('aprSubmitBtn'));
    aprSubmit.addEventListener('click', function () {
        if (!d || !aprLines.length) { return; }
        if (hb.dirty.isDirty()) { notify(hb.t('js.apqp.apr.saveFirst'), true); return; }
        var h = d.cp;
        ask(hb.t('js.apqp.apr.dlg'), hb.t('js.apqp.apr.confirm', h.cpNo + ' rev ' + h.rev), hb.t('js.apqp.apr.dlgOk')).then(function (ok) {
            if (!ok) { return; }
            hb.busy(aprSubmit, function () {
                return hb.api('/api/apqp/cps/' + d.cp.cpId + '/submit', {method: 'POST', body: {rowVer: h.rowVer,
                    lines: aprLines.map(function (l) { return {userId: l.userId, lineType: l.lineType}; })}});
            }).then(function (res) {
                if (!res) { return; }
                aprLines = [];
                return reload().then(function () { notify(hb.t('js.apqp.apr.submitted')); });
            }, function (err) {
                var lines = (err && err.errors || []).slice(0, 5).map(/** @param {any} e */ function (e) { return e.message; });
                notify((err && err.message || '') + (lines.length ? ' ' + lines.join(' / ') : ''), true);
            });
        });
    });

    /** 결재선 고르기 — 사용 중 사용자 @param {any[]} users */
    function fillAprUsers(users) {
        var s = /** @type {HTMLSelectElement} */ (document.getElementById('aprUser'));
        users.forEach(/** @param {any} u */ function (u) {
            var o = /** @type {HTMLOptionElement} */ (hb.el('option', null, u.userNm + ' (' + u.loginId + ')'));
            o.value = String(u.userId);
            s.appendChild(o);
        });
    }

    // ------------------------------------------------------------------ 시작

    hb.loadMessages().then(function () {
        hb.page.init();
        sp = hb.searchPanel(/** @type {HTMLFormElement} */ (document.getElementById('search')), {onSearch: function () { return grid.reload(true); }});
        return Promise.all([hb.loadSession(), hb.codes('APQP_CP_PHASE'), hb.codes('APQP_CTRL_METHOD'), hb.codes('GAUGE_CLASS'),
            hb.codes('INSP_TYPE'), hb.codes('INSP_FREQ'), hb.codes('SPECIAL_CHAR'), hb.codes('APQP_FMEA_STATUS'), hb.api(API + '/projects'), hb.api(API + '/users'), sp.ready]);
    }).then(function (r) {
        phaseNames = r[1];
        methodNames = r[2];
        gaugeNames = r[3];
        inspTypeNames = r[4];
        freqNames = r[5];
        specialNames = r[6];
        statusNames = r[7];
        var pf = sel('phaseFilter');
        option(pf, '', hb.t('js.apqp.cp.allPhases'));
        PHASES.forEach(function (p) { option(pf, p, phaseNames[p] || p); });
        PHASES.forEach(function (p) { option(sel('phaseSel'), p, phaseNames[p] || p); });
        Object.keys(methodNames).forEach(function (k) { option(sel('methodSel'), k, methodNames[k]); });
        option(sel('gaugeSel'), '', hb.t('js.apqp.none'));
        Object.keys(gaugeNames).forEach(function (k) { option(sel('gaugeSel'), k, gaugeNames[k]); });
        option(sel('inspTypeSel'), '', hb.t('js.apqp.none'));
        Object.keys(inspTypeNames).forEach(function (k) { if (k !== 'RECEIVE') { option(sel('inspTypeSel'), k, inspTypeNames[k]); } });
        option(sel('freqSel'), '', hb.t('js.apqp.none'));
        Object.keys(freqNames).forEach(function (k) { option(sel('freqSel'), k, freqNames[k]); });
        option(sel('specialSel'), '', hb.t('js.apqp.none'));
        Object.keys(specialNames).forEach(function (k) { if (k !== 'NONE') { option(sel('specialSel'), k, specialNames[k]); } });
        var projSel = sel('projSel');
        option(projSel, '', hb.t('js.apqp.noProject'));
        r[8].data.forEach(/** @param {any} p */ function (p) { option(projSel, String(p.projId), p.projNo + ' ' + p.projNm); });
        fillAprUsers(r[9].data);
        grid = hb.grid('#cpGrid', {
            progCd: 'apqp-cp', id: 'main', key: 'cpId',
            url: API + '/grid', columnsUrl: API + '/columns', exportUrl: API + '/export', actions: ACTIONS, params: params,
            status: {status: {DRAFT: 'info', IN_APPROVAL: 'warning', RELEASED: 'success', OBSOLETE: 'neutral'},
                // 재검토 — 글자는 LABEL 열(X2 서버 문구), 배지 색만 여기서
                review: function (v) { return v ? 'warning' : null; }},
            rowClass: function (row) { return row.status === 'OBSOLETE' ? 'hb-tree-dim' : null; },
            notify: notify
        });
        // 상세가 목록을 덮는 화면 — 클릭·Enter 만 연다(↑↓ 는 고르기만, docs/32 4.11 G1 과 같은 이유)
        var host = /** @type {HTMLElement} */ (document.getElementById('cpGrid'));
        host.addEventListener('click', function (e) {
            if (e.target instanceof Element && e.target.closest('tbody tr')) { openSelected(); }
        });
        host.addEventListener('keydown', function (e) {
            if (e.key === 'Enter' && e.target instanceof Element && e.target.closest('.hbg-scroll')) { openSelected(); }
        });
        return grid.ready.then(function () { return grid.reload(true); });
    }).then(null, fail());
})();
