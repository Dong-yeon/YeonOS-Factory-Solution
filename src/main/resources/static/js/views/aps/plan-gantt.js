// @ts-check
/* 생산 계획(간트) (aps-plan) — APS 1·2단계 (docs/38). 계획 목록 → 상세(요약·폐기 / 새 계획 계산 폼) → 간트·계획 못 한 공정·지시별 결과.
   2단계(11장): 반영 미리보기 → [반영](서버가 잠근 뒤 다시 계산, 낡으면 409) · 같은 공장 계획 비교.
   3단계(12장): 막대 끌어 놓기 또는 키보드(M → ←/→ ↑/↓ → Enter)로 수동 고정 — 다음 계산이 먼저 자리 잡는다. 지금 고정은 점선 막대·목록.
   간트 막대 위치·폭은 서버 시각 → (t − 축 시작) ÷ 축 길이 × 100% 를 CSSOM(style.left/width)으로 — 인라인 style 속성은 쓰지 않는다(CSP). */
/**
 * @typedef {{planId: number, planNo: string, plantCd: string, plantNm: string, planNm: string | null, ruleCd: string,
 *   horizonStart: string, horizonDays: number, outsrcLeadHr: number, timeZone: string, schedFrom: string, schedTo: string,
 *   status: string, calculatedAt: string, calculatedBy: string, woCnt: number, opCnt: number, lateWoCnt: number,
 *   unschedOpCnt: number, makespanEnd: string | null, calcMs: number, offDayCnt: number, matMode: string, matPo: string,
 *   matShortWoCnt: number, discardReason: string | null,
 *   purgedAt: string | null, appliedAt: string | null,
 *   appliedBy: string | null, appliedWoCnt: number | null, appliedOpCnt: number | null, rowVer: number, currentYn?: string}} ApsPlan
 * @typedef {{planWoId: number, seq: number, woId: number, woNo: string, woStatus: string, itemCd: string, itemNm: string,
 *   planQty: string, unitCd: string, priority: number, planStart: string, planEnd: string, dueDate: string | null,
 *   dueSrc: string | null, startAt: string | null, endAt: string | null, lateYn: string, excCd: string | null,
 *   matCd: string | null}} ApsPlanWo
 * @typedef {{woId: number, woNo: string, opSeq: number, srcOpSeq: number | null, itemCd: string, itemNm: string, needQty: string,
 *   allocQty: string, shortQty: string, unitCd: string, needAt: string | null, coveredAt: string | null, supplyQty: string,
 *   planMatId: number, procType: string | null, prNo: string | null}} ApsPlanMat
 * @typedef {{itemId: number, itemCd: string, itemNm: string, unitCd: string, shortQty: string, moq: string | null, orderMult: string | null,
 *   reqQty: string, needDate: string, leadDays: number, orderBy: string, late: boolean, supplierCd: string | null, supplierNm: string | null,
 *   noSource: boolean, matIds: number[], woNos: string[]}} ApsPrLine
 * @typedef {{planId: number, planNo: string, plantCd: string, needDate: string | null, lines: ApsPrLine[],
 *   errors: {matId: number, code: string}[]}} ApsPrPreview
 * @typedef {{planOpId: number, woId: number, woNo: string, seq: number, lateYn: string, opSeq: number, procCd: string,
 *   procNm: string, nodeId: number | null, nodeCd: string | null, nodeNm: string | null, equipId: number | null,
 *   equipCd: string | null, equipNm: string | null, resKind: string | null, qty: string, unitCd: string, setupSec: number,
 *   runSec: number, startAt: string | null, endAt: string | null, excCd: string | null, pinId: number | null,
 *   warnCd: string | null, matShortYn: string, cands: string[] | null}} ApsPlanOp
 * @typedef {{pinId: number, plantCd: string, woId: number, woNo: string, woStatus: string, opSeq: number, procCd: string | null,
 *   procNm: string | null, resKind: string, equipId: number | null, nodeId: number | null, resCd: string | null, startAt: string,
 *   srcPlanId: number, srcPlanNo: string, updatedBy: string, updatedAt: string, rowVer: number}} ApsPin
 * @typedef {{key: string, kind: string, equipId: number | null, nodeId: number | null, code: string | null, name: string | null}} ApsRes
 * @typedef {{plan: ApsPlan, wos: ApsPlanWo[], unscheduled: ApsPlanOp[], mats: ApsPlanMat[]}} ApsDetail
 * @typedef {{plan: ApsPlan, days: {date: string, start: number}[], resources: ApsRes[], ops: ApsPlanOp[],
 *   windows: {start: number, end: number}[], pins: ApsPin[], offDays: {day: string, source: string, reason: string | null}[]}} ApsGantt
 * @typedef {{woProcId: number, opSeq: number, procCd: string, fromEquipCd: string | null, toEquipCd: string | null}} ApsApplyEquip
 * @typedef {{woId: number, woNo: string, woStatus: string, fromStart: string, fromEnd: string, toStart: string, toEnd: string,
 *   pushed: boolean, equips: ApsApplyEquip[]}} ApsApplyWo
 * @typedef {{plan: ApsPlan, wos: ApsApplyWo[], stale: {woNo: string | null, code: string}[], expired: boolean, woCnt: number,
 *   opCnt: number, pushedCnt: number, fenced: ApsFencedWo[], fenceDays: number}} ApsApplyPreview
 * @typedef {{woId: number, woNo: string, woStatus: string, fromStart: string, fromEnd: string, toStart: string, toEnd: string,
 *   fenceDays: number, equipCnt: number}} ApsFencedWo
 * @typedef {{woId: number, woNo: string, opSeq: number, procCd: string, procNm: string, kind: string, aStart: string | null,
 *   aEnd: string | null, aRes: string | null, aExc: string | null, bStart: string | null, bEnd: string | null, bRes: string | null,
 *   bExc: string | null, shiftSec: number | null}} ApsCmpRow
 * @typedef {{a: ApsPlan, b: ApsPlan, rows: ApsCmpRow[], kinds: Record<string, number>}} ApsCompare
 */
/**
 * @typedef {{opSeq: number, procCd: string, procNm: string, resKind: string | null, resCd: string | null, startAt: string | null,
 *   endAt: string | null, excCd: string | null}} ApsCtpOp
 * @typedef {{itemId: number, itemCd: string | null, itemNm: string | null, opSeq: number, needQty: string, allocQty: string,
 *   shortQty: string, unitCd: string, needAt: string | null, coveredAt: string | null}} ApsCtpMat
 * @typedef {{plantCd: string, itemCd: string, itemNm: string, qty: string, unitCd: string, wantDate: string | null, priority: number,
 *   timeZone: string, startAt: string | null, endAt: string | null, lateYn: string, excCd: string | null, woCnt: number,
 *   ops: ApsCtpOp[], mats: ApsCtpMat[]}} ApsCtpResult
 */
(function () {
    'use strict';

    var API = '/api/aps/plans';
    var PROG = document.body.getAttribute('data-prog') || 'aps-plan';
    var ACTIONS = (document.body.getAttribute('data-actions') || '').split(',');
    var CAN_SAVE = ACTIONS.indexOf('SAVE') >= 0;
    var msg = /** @type {HTMLElement} */ (document.getElementById('msg'));
    var search = /** @type {HTMLFormElement} */ (document.getElementById('search'));
    var form = /** @type {HTMLFormElement} */ (document.getElementById('planForm'));
    var formMode = /** @type {HTMLElement} */ (document.getElementById('formMode'));
    var summary = /** @type {HTMLElement} */ (document.getElementById('summary'));
    var ganttSection = /** @type {HTMLElement} */ (document.getElementById('ganttSection'));
    var ganttTitle = /** @type {HTMLElement} */ (document.getElementById('ganttTitle'));
    var ganttBox = /** @type {HTMLElement} */ (document.getElementById('gantt'));
    var opInfo = /** @type {HTMLElement} */ (document.getElementById('opInfo'));
    var unschedBody = /** @type {HTMLElement} */ (document.querySelector('#unsched tbody'));
    var wosBody = /** @type {HTMLElement} */ (document.querySelector('#planWos tbody'));
    var matsBody = /** @type {HTMLElement} */ (document.querySelector('#mats tbody'));
    var calcBtn = /** @type {HTMLButtonElement | null} */ (document.getElementById('calcBtn'));   // SAVE 권한 없으면 없음
    var discardBtn = /** @type {HTMLButtonElement | null} */ (document.getElementById('discardBtn'));
    var previewBtn = /** @type {HTMLButtonElement | null} */ (document.getElementById('previewBtn'));   // APPROVE 권한 없으면 없음
    var applySection = /** @type {HTMLElement | null} */ (document.getElementById('applySection'));
    var applyBtn = /** @type {HTMLButtonElement | null} */ (document.getElementById('applyBtn'));
    var applyInfo = /** @type {HTMLElement | null} */ (document.getElementById('applyInfo'));
    var staleBox = /** @type {HTMLElement | null} */ (document.getElementById('staleBox'));
    var staleList = /** @type {HTMLElement | null} */ (document.getElementById('staleList'));
    var applyBody = /** @type {HTMLElement | null} */ (document.querySelector('#applyWos tbody'));
    var cmpSel = /** @type {HTMLSelectElement} */ (document.getElementById('cmpSel'));
    var cmpBtn = /** @type {HTMLButtonElement} */ (document.getElementById('cmpBtn'));
    var cmpChanged = /** @type {HTMLInputElement} */ (document.getElementById('cmpChanged'));
    var cmpBox = /** @type {HTMLElement} */ (document.getElementById('cmpBox'));
    var cmpSummary = /** @type {HTMLElement} */ (document.getElementById('cmpSummary'));
    var cmpBody = /** @type {HTMLElement} */ (document.querySelector('#cmp tbody'));
    var pinsBody = /** @type {HTMLElement} */ (document.querySelector('#pins tbody'));
    /** 15분 — 끌어 놓기·키보드 이동 단위(초) */
    var SNAP = 900;
    /** 키보드 이동 중인 막대 @type {{o: ApsPlanOp, bar: HTMLElement, a: number, key: string} | null} */
    var kbMove = null;
    /** 주 목록(UI 단계 3 — 서버 페이징·정렬) @type {HB.Grid} */
    var grid;
    /** 마지막 반영 미리보기(지금 계획의 것만) @type {ApsApplyPreview | null} */
    var preview = null;
    /** 마지막 비교(바뀐 것만 토글 때 다시 그림) @type {ApsCompare | null} */
    var compared = null;
    /** 고른 계획 @type {ApsDetail | null} */
    var current = null;
    /** 그린 간트(확대 바꿀 때 다시 그림) @type {ApsGantt | null} */
    var gantt = null;
    /** 강조할 지시 @type {number | null} */
    var pickedWo = null;
    /** @type {HB.SearchPanel} */
    var sp;
    /** @type {HB.NumberControl} */
    var daysCtl;
    /** @type {HB.NumberControl} */
    var leadCtl;
    var ST = {CALCULATED: 'js.apsPlan.st.CALCULATED', DISCARDED: 'js.apsPlan.st.DISCARDED', APPLIED: 'js.apsPlan.st.APPLIED'};
    var STALE = {APS_WO_CHANGED: 'js.apsPlan.stale.APS_WO_CHANGED', APS_PROC_CHANGED: 'js.apsPlan.stale.APS_PROC_CHANGED',
        APS_NEW_WO: 'js.apsPlan.stale.APS_NEW_WO', APS_OLD_PLAN: 'js.apsPlan.stale.APS_OLD_PLAN'};
    var WOST = {PLANNED: 'js.apsPlan.wost.PLANNED', RELEASED: 'js.apsPlan.wost.RELEASED', IN_PROGRESS: 'js.apsPlan.wost.IN_PROGRESS',
        DONE: 'js.apsPlan.wost.DONE', CLOSED: 'js.apsPlan.wost.CLOSED', CANCELLED: 'js.apsPlan.wost.CANCELLED'};
    var OFFSRC = {WORK_CAL: 'js.apsPlan.offSrc.WORK_CAL', COMP_CAL: 'js.apsPlan.offSrc.COMP_CAL', WEEKLY: 'js.apsPlan.offSrc.WEEKLY'};
    var WARN = {PIN_MOVED: 'js.apsPlan.warn.PIN_MOVED', PIN_IGNORED: 'js.apsPlan.warn.PIN_IGNORED', PIN_PREV: 'js.apsPlan.warn.PIN_PREV'};
    var KIND = {SAME: 'js.apsPlan.kind.SAME', MOVED: 'js.apsPlan.kind.MOVED', RES: 'js.apsPlan.kind.RES',
        SCHEDULED: 'js.apsPlan.kind.SCHEDULED', UNSCHEDULED: 'js.apsPlan.kind.UNSCHEDULED', ONLY_A: 'js.apsPlan.kind.ONLY_A',
        ONLY_B: 'js.apsPlan.kind.ONLY_B'};
    var EXC = {NO_CT: 'js.apsPlan.exc.NO_CT', NO_NODE: 'js.apsPlan.exc.NO_NODE', HORIZON: 'js.apsPlan.exc.HORIZON',
        PREV: 'js.apsPlan.exc.PREV', MAT: 'js.apsPlan.exc.MAT'};
    var MATCD = {SHORT: 'js.apsPlan.mat.SHORT', NO_BOM: 'js.apsPlan.mat.NO_BOM'};
    var MATMODE = {WARN: 'js.apsPlan.matMode.WARN', DELAY: 'js.apsPlan.matMode.DELAY'};
    /** 상태 배지 톤 — APS 화면 하나의 표(docs/40 부록 A.1). 계획: 계산됨 = 볼 것 있음, 반영 = 완료, 폐기 = 해당 없음 */
    /** @type {{plan: Record<string, HB.StatusTone>, wo: Record<string, HB.StatusTone>}} */
    var TONES = {
        plan: {CALCULATED: 'info', APPLIED: 'success', DISCARDED: 'neutral'},
        wo: {PLANNED: 'neutral', RELEASED: 'info', IN_PROGRESS: 'normal', DONE: 'success', CLOSED: 'success', CANCELLED: 'neutral'}
    };

    /**
     * @param {'plan' | 'wo'} kind @param {string} code @param {string} text
     * @returns {HTMLSpanElement}
     */
    function badge(kind, code, text) {
        var tone = /** @type {Record<string, string>} */ (TONES[kind])[code] || 'neutral';
        return hb.status(/** @type {HB.StatusTone} */ (tone), text);
    }

    /** @param {string | null | undefined} text @param {boolean} [error] */
    function notify(text, error) {
        msg.textContent = text || '';
        msg.classList.toggle('hb-msg-error', !!error);
    }

    /** @param {string} n */
    function field(n) {
        return /** @type {HTMLInputElement | HTMLSelectElement} */ (form.querySelector('[data-field="' + n + '"]'));
    }

    /** @param {string} s */
    function stText(s) { return hb.t(/** @type {Record<string, string>} */ (ST)[s] || 'js.apsPlan.st.CALCULATED'); }

    /** @param {string} s */
    function woStText(s) { var k = /** @type {Record<string, string>} */ (WOST)[s]; return k ? hb.t(k) : s; }

    /** @param {string | null} c */
    function warnText(c) { return c ? hb.t(/** @type {Record<string, string>} */ (WARN)[c] || 'js.apsPlan.warn.PIN_MOVED') : ''; }

    /** @param {string | null} c */
    function excText(c) { return c ? hb.t(/** @type {Record<string, string>} */ (EXC)[c] || 'js.apsPlan.exc.PREV') : ''; }

    /** 서버가 글자로 보낸 수량에 천 단위 쉼표만 @param {string | null | undefined} s */
    function qtyText(s) {
        if (s === null || s === undefined || s === '') { return ''; }
        var p = String(s).split('.');
        return p[0].replace(/\B(?=(\d{3})+(?!\d))/g, ',') + (p.length > 1 ? '.' + p[1] : '');
    }

    /** @param {HTMLSelectElement} sel @param {unknown} value @param {string} text */
    function option(sel, value, text) {
        var o = hb.el('option', null, text);
        o.value = value === null || value === undefined ? '' : String(value);
        sel.appendChild(o);
    }

    /** @param {string | null} iso */
    function dt(iso) { return iso && current ? hb.fmt.datetime(iso, current.plan.timeZone) : ''; }

    /** @param {HB.ApiError} err */
    function fail(err) {
        if (err && err.stale) { return; }
        notify(err && err.errors && err.errors.length ? err.errors.map(function (x) { return x.message; }).join(' / ')
            : err && err.message, true);
    }

    // ------------------------------------------------------------------ 목록

    /** 조회 조건(검색 패널) — grid 가 page·size·sort 를 붙인다 */
    function params() {
        var v = sp.values();
        /** @type {Record<string, unknown>} */
        var q = {};
        ['from', 'to', 'plantCd'].forEach(function (k) { if (v[k]) { q[k] = v[k]; } });
        return q;
    }

    /** 새 계획 폼에 넣다 만 값이 있으면 화면 안 대화상자로 묻는다(고른 계획은 읽기 전용) @returns {Promise<boolean>} */
    function leaveOk() {
        if (current || !hb.dirty.isDirty()) { return Promise.resolve(true); }
        return hb.dialog.confirm({title: hb.t('js.common.leaveTitle'), message: hb.t('js.common.confirmLeave'), ok: hb.t('js.common.leaveOk')});
    }

    /** 목록 다시 읽기 — selectId 가 이 쪽에 있으면 grid 가 골라 상세를 연다, 없어도(기간 밖·다른 쪽) 방금 만든·고친 것은 상세로 @param {number} [selectId] */
    function load(selectId) {
        return grid.reload(true, selectId || undefined).then(function () {
            var sel = grid.selected();
            if (selectId && (!sel || sel.planId !== selectId)) { return open(selectId); }
            if (!selectId) { show(null, null); }
        });
    }

    /** @param {number} id */
    function open(id) {
        return hb.latest('plan', Promise.all([hb.api(API + '/' + id), hb.api(API + '/' + id + '/gantt')])).then(function (r) {
            show(r[0].data, r[1].data);
        }, fail);
    }

    // ------------------------------------------------------------------ 상세

    /** @param {ApsDetail | null} d @param {ApsGantt | null} g */
    function show(d, g) {
        current = d;
        gantt = g;
        pickedWo = null;
        hb.dirty.clear();
        hb.showFieldErrors(form, []);
        var p = d ? d.plan : null;
        if (p) {
            field('plantCd').value = p.plantCd;
            field('horizonStart').value = p.horizonStart;
            daysCtl.setValue(p.horizonDays);
            leadCtl.setValue(p.outsrcLeadHr);
            field('planNm').value = p.planNm || '';
            field('matMode').value = p.matMode || 'WARN';
            /** @type {HTMLInputElement} */ (field('matPo')).checked = p.matPo === 'Y';
        } else {
            field('horizonStart').value = hb.dates.add(hb.dates.today(), 1);
            daysCtl.setValue(7);
            leadCtl.setValue(24);
            field('planNm').value = '';
            field('matMode').value = 'WARN';
            /** @type {HTMLInputElement} */ (field('matPo')).checked = true;
        }
        ['plantCd', 'horizonStart', 'horizonDays', 'outsrcLeadHr', 'planNm', 'matMode', 'matPo'].forEach(function (n) { field(n).disabled = !CAN_SAVE || !!p; });
        formMode.textContent = p ? hb.t('js.apsPlan.viewing', p.planNo) : hb.t('js.apsPlan.creating');
        if (calcBtn) { calcBtn.hidden = !!p; }
        if (discardBtn) { discardBtn.hidden = !p || p.status !== 'CALCULATED'; }
        if (previewBtn) { previewBtn.hidden = !p || p.status !== 'CALCULATED' || !!p.purgedAt; }
        preview = null;
        if (applySection) { applySection.hidden = true; }
        fillCompare(p);
        renderSummary(p);
        ganttSection.hidden = !d;
        if (d && g) {
            ganttTitle.textContent = hb.t('js.apsPlan.ganttOf', p ? p.planNo : '');
            renderGantt();
            renderPins();
            renderMats(d.mats || []);
            renderUnsched(d.unscheduled);
            renderWos(d.wos);
        }
    }

    /** @param {ApsPlan | null} p */
    function renderSummary(p) {
        summary.textContent = '';
        summary.hidden = !p;
        if (!p) { return; }
        /** @type {[string, string][]} */
        var items = [
            [hb.t('js.apsPlan.sum.status'), stText(p.status) + (p.currentYn === 'Y' ? ' · ' + hb.t('js.apsPlan.current') : '') + (p.discardReason ? ' — ' + p.discardReason : '')],
            [hb.t('js.apsPlan.sum.calculated'), dt(p.calculatedAt) + ' · ' + p.calculatedBy + ' · ' + hb.t('js.apsPlan.ms', p.calcMs)],
            [hb.t('js.apsPlan.sum.window'), dt(p.schedFrom) + ' ~ ' + dt(p.schedTo) + ' (' + p.timeZone + ')'],
            [hb.t('js.apsPlan.sum.counts'), hb.t('js.apsPlan.counts', p.woCnt, p.opCnt, p.lateWoCnt, p.unschedOpCnt)],
            [hb.t('js.apsPlan.sum.makespan'), p.makespanEnd ? dt(p.makespanEnd) : '-'],
            [hb.t('js.apsPlan.sum.lead'), hb.t('js.apsPlan.hours', p.outsrcLeadHr)],
            [hb.t('js.apsPlan.sum.offDays'), hb.t('js.apsPlan.offDays', p.offDayCnt || 0)],
            [hb.t('js.apsPlan.sum.mat'), hb.t('js.apsPlan.matSum',
                hb.t(/** @type {Record<string, string>} */ (MATMODE)[p.matMode] || 'js.apsPlan.matMode.WARN'),
                p.matPo === 'Y' ? hb.t('js.apsPlan.matPoOn') : hb.t('js.apsPlan.matPoOff'), p.matShortWoCnt || 0)]];
        if (p.purgedAt) {
            items.push([hb.t('js.apsPlan.sum.purged'), hb.t('js.apsPlan.purgedInfo', dt(p.purgedAt))]);
        }
        if (p.appliedAt) {
            items.push([hb.t('js.apsPlan.sum.applied'), hb.t('js.apsPlan.appliedInfo', dt(p.appliedAt), p.appliedBy || '',
                p.appliedWoCnt || 0, p.appliedOpCnt || 0)]);
        }
        items.forEach(function (it) {
            summary.appendChild(hb.el('dt', null, it[0]));
            summary.appendChild(hb.el('dd', null, it[1]));
        });
    }

    // ------------------------------------------------------------------ 간트

    function zoom() {
        var r = /** @type {HTMLInputElement | null} */ (document.querySelector('#zoom input:checked'));
        return r ? Number(r.value) : 12;
    }

    /** @param {HTMLElement} el @param {number} a @param {number} b @param {number} from @param {number} span */
    function place(el, a, b, from, span) {
        el.style.left = ((a - from) / span * 100) + '%';
        el.style.width = (Math.max(0, b - a) / span * 100) + '%';
    }

    /** @param {ApsRes} r */
    function resLabel(r) {
        if (r.kind === 'OUTSRC') { return hb.t('js.apsPlan.outsrc'); }
        return (r.code || '') + ' · ' + (r.name || '') + (r.kind === 'NODE' ? ' ' + hb.t('js.apsPlan.nodeRes') : '');
    }

    /** @param {ApsPlanOp} o */
    function resKey(o) {
        return o.resKind === 'EQUIP' ? 'EQUIP:' + o.equipId : o.resKind === 'NODE' ? 'NODE:' + o.nodeId : 'OUTSRC';
    }

    /** @param {string} iso */
    function sec(iso) { return Math.floor(Date.parse(iso) / 1000); }

    function renderGantt() {
        var g = gantt;
        ganttBox.textContent = '';
        opInfo.textContent = '';
        if (!g) { return; }
        if (!g.ops.length) {
            ganttBox.appendChild(hb.el('p', 'hb-muted', hb.t('js.apsPlan.noOps')));
            return;
        }
        var from = g.days[0].start;
        var to = g.days[g.days.length - 1].start;
        var span = Math.max(1, to - from);
        var inner = hb.el('div', 'aps-g-inner');
        inner.style.width = 'calc(var(--aps-label-w) + ' + Math.ceil(span / 3600 * zoom()) + 'px)';

        // 머리: 날 눈금
        var head = hb.el('div', 'aps-g-head');
        head.appendChild(hb.el('div', 'aps-g-corner', hb.t('js.apsPlan.resource')));
        var axis = hb.el('div', 'aps-g-axis');
        /** 계산 때 쉬는 날(4단계) @type {Record<string, {source: string, reason: string | null}>} */
        var off = {};
        (g.offDays || []).forEach(function (x) { off[x.day] = x; });
        for (var i = 0; i + 1 < g.days.length; i++) {
            var o0 = off[g.days[i].date];
            var tick = hb.el('div', 'aps-g-day' + (o0 ? ' aps-g-day-off' : ''),
                g.days[i].date.substring(5) + (o0 ? ' ' + hb.t('js.apsPlan.offDay') : ''));
            if (o0) {
                tick.title = hb.t(/** @type {Record<string, string>} */ (OFFSRC)[o0.source] || 'js.apsPlan.offSrc.WEEKLY')
                    + (o0.reason ? ' · ' + o0.reason : '');
            }
            place(tick, g.days[i].start, g.days[i + 1].start, from, span);
            axis.appendChild(tick);
        }
        head.appendChild(axis);
        inner.appendChild(head);

        // 몸통: 근무 외 음영(한 층) + 자원 행
        var body = hb.el('div', 'aps-g-body');
        var shade = hb.el('div', 'aps-g-shade');
        var cursor = from;
        g.windows.concat([{start: to, end: to}]).forEach(function (w) {
            if (w.start > cursor) {
                var off = hb.el('div', 'aps-g-off');
                place(off, cursor, Math.min(w.start, to), from, span);
                shade.appendChild(off);
            }
            cursor = Math.max(cursor, w.end);
        });
        body.appendChild(shade);
        /** @type {Record<string, HTMLElement>} */
        var tracks = {};
        /** 겹치는 막대(외주 = 무한 용량)는 줄을 나눈다 — 자원별 줄마다 마지막 끝 @type {Record<string, number[]>} */
        var lanes = {};
        g.resources.forEach(function (r) {
            var row = hb.el('div', 'aps-g-row');
            row.setAttribute('data-key', r.key);
            row.appendChild(hb.el('div', 'aps-g-label', resLabel(r)));
            var track = hb.el('div', 'aps-g-track');
            row.appendChild(track);
            tracks[r.key] = track;
            lanes[r.key] = [];
            body.appendChild(row);
        });
        g.ops.forEach(function (o) {   // 서버가 시작 순으로 보낸다
            var key = resKey(o);
            var track = tracks[key];
            if (!track || !o.startAt || !o.endAt) { return; }
            var ends = lanes[key];
            var a = sec(o.startAt);
            var lane = 0;
            while (lane < ends.length && ends[lane] > a) { lane++; }
            ends[lane] = sec(o.endAt);
            var cls = 'aps-g-bar' + (o.lateYn === 'Y' ? ' aps-g-late' : '') + (o.matShortYn === 'Y' ? ' aps-g-matshort' : '') + (o.pinId && o.warnCd !== 'PIN_IGNORED' ? ' aps-g-pinned' : '')
                + (o.warnCd ? ' aps-g-warn' : '') + (CAN_SAVE ? ' aps-g-draggable' : '');
            var bar = hb.el('button', cls, (o.pinId && o.warnCd !== 'PIN_IGNORED' ? '◆ ' : '') + o.woNo + '·' + o.opSeq);
            bar.type = 'button';
            bar.setAttribute('data-wo', String(o.woId));
            var tip = o.woNo + ' ' + o.opSeq + ' ' + o.procCd + ' · ' + o.procNm + ' · ' + qtyText(o.qty) + ' ' + o.unitCd
                + ' · ' + dt(o.startAt) + ' ~ ' + dt(o.endAt) + (o.warnCd ? ' · ' + warnText(o.warnCd) : '')
                + (o.matShortYn === 'Y' ? ' · ' + hb.t('js.apsPlan.mat.SHORT') : '')
                + (CAN_SAVE ? ' · ' + hb.t('js.apsPlan.moveHint') : '');
            bar.title = tip;
            bar.setAttribute('aria-label', tip);
            place(bar, a, sec(o.endAt), from, span);
            bar.style.top = 'calc(' + lane + ' * var(--aps-row-h) + 4px)';
            bar.addEventListener('click', function () {
                if (bar.getAttribute('data-dragged') === 'Y') { bar.removeAttribute('data-dragged'); return; }
                pick(o);
            });
            if (CAN_SAVE) { draggable(bar, o, from, span); }
            track.appendChild(bar);
        });
        // 지금 고정(다음 계산 입력) — 그 자원 행이 있으면 점선 막대로. 길이는 이 계획의 같은 공정 막대와 같게
        var allOps = g.ops;
        (g.pins || []).forEach(function (pn) {
            var key = pn.resKind === 'EQUIP' ? 'EQUIP:' + pn.equipId : pn.resKind === 'NODE' ? 'NODE:' + pn.nodeId : 'OUTSRC';
            var track = tracks[key];
            if (!track) { return; }
            var same = allOps.filter(function (x) { return x.woId === pn.woId && x.opSeq === pn.opSeq && x.startAt && x.endAt; })[0];
            var a = sec(pn.startAt);
            var len = same && same.startAt && same.endAt ? sec(same.endAt) - sec(same.startAt) : 3600;
            if (a >= to || a + len <= from) { return; }
            var ghost = hb.el('div', 'aps-g-ghost', pn.woNo + '·' + pn.opSeq);
            ghost.setAttribute('aria-hidden', 'true');
            place(ghost, Math.max(a, from), Math.min(a + Math.max(len, 60), to), from, span);
            track.appendChild(ghost);
        });
        Object.keys(lanes).forEach(function (k) {
            var row = /** @type {HTMLElement} */ (tracks[k].parentElement);
            if (lanes[k].length > 1) { row.style.height = 'calc(' + lanes[k].length + ' * var(--aps-row-h))'; }
        });
        inner.appendChild(body);
        ganttBox.appendChild(inner);
        highlight();
    }

    // ------------------------------------------------------------------ 수동 고정 (3단계)

    /** @param {number} a */
    function snap(a) { return Math.round(a / SNAP) * SNAP; }

    /** 공정의 후보 자원 중 간트에 행이 있는 것 @param {ApsPlanOp} o */
    function candRows(o) {
        return (o.cands || []).filter(function (k) { return !!ganttBox.querySelector('.aps-g-row[data-key="' + k + '"]'); });
    }

    /** 막대를 자원 행 key 의 시각 a(초)에 보이게 옮긴다(저장 전 미리 보기) @param {HTMLElement} bar @param {ApsPlanOp} o
        @param {string} key @param {number} a @param {number} from @param {number} span */
    function showMove(bar, o, key, a, from, span) {
        var row = ganttBox.querySelector('.aps-g-row[data-key="' + key + '"]');
        var track = row ? row.querySelector('.aps-g-track') : null;
        if (!track || !o.startAt || !o.endAt) { return; }
        if (bar.parentElement !== track) { track.appendChild(bar); }
        place(bar, a, a + (sec(o.endAt) - sec(o.startAt)), from, span);
        bar.style.top = '4px';
        opInfo.textContent = hb.t('js.apsPlan.moving', o.woNo, o.opSeq, (row && row.querySelector('.aps-g-label') || {textContent: ''}).textContent,
            dt(new Date(a * 1000).toISOString()));
    }

    /** 고정 저장 — 지금 고정이 있으면 옮기기(rowVer), 없으면 새로 @param {ApsPlanOp} o @param {string} key @param {number} a */
    function savePin(o, key, a) {
        if (!current || !gantt) { return Promise.resolve(); }
        var planId = current.plan.planId;
        var existing = gantt.pins.filter(function (p) { return p.woId === o.woId && p.opSeq === o.opSeq; })[0];
        var startAt = new Date(a * 1000).toISOString();
        var req = existing
            ? hb.api('/api/aps/pins/' + existing.pinId, {method: 'PUT', body: {resKey: key, startAt: startAt, rowVer: existing.rowVer}})
            : hb.api('/api/aps/pins', {method: 'POST', body: {planOpId: o.planOpId, resKey: key, startAt: startAt}});
        return req.then(function () {
            return open(planId).then(function () { notify(hb.t('js.apsPlan.pinned', o.woNo, o.opSeq)); });
        }, function (err) {
            fail(err);
            renderGantt();
        });
    }

    /** 포인터 끌어 놓기 — 15분 단위, 후보 자원 행만 받는다 @param {HTMLElement} bar @param {ApsPlanOp} o
        @param {number} from @param {number} span */
    function draggable(bar, o, from, span) {
        /** @type {{x: number, y: number, a: number, key: string, moved: boolean, w: number} | null} */
        var d = null;
        var a0 = o.startAt ? sec(o.startAt) : from;
        var key0 = resKey(o);
        bar.addEventListener('pointerdown', function (e) {
            if (e.button !== 0 || kbMove) { return; }
            var track = /** @type {HTMLElement} */ (bar.parentElement);
            d = {x: e.clientX, y: e.clientY, a: a0, key: key0, moved: false, w: track.getBoundingClientRect().width};
            bar.setPointerCapture(e.pointerId);
        });
        bar.addEventListener('pointermove', function (e) {
            if (!d) { return; }
            if (!d.moved && Math.abs(e.clientX - d.x) + Math.abs(e.clientY - d.y) < 6) { return; }
            d.moved = true;
            bar.classList.add('aps-g-dragging');
            d.a = Math.min(Math.max(snap(a0 + (e.clientX - d.x) / Math.max(1, d.w) * span), from), from + span - 60);
            var under = document.elementsFromPoint(e.clientX, e.clientY)
                .map(function (el) { return el.closest('.aps-g-row'); }).filter(function (el) { return !!el; })[0];
            var k = under ? under.getAttribute('data-key') : null;
            if (k && (o.cands || []).indexOf(k) >= 0) { d.key = k; }
            showMove(bar, o, d.key, d.a, from, span);
        });
        function end(/** @type {boolean} */ commit) {
            if (!d) { return; }
            var x = d;
            d = null;
            bar.classList.remove('aps-g-dragging');
            if (!x.moved) { return; }
            bar.setAttribute('data-dragged', 'Y');   // 뒤따르는 click 은 고르기가 아니다
            if (!commit || (x.a === a0 && x.key === key0)) { renderGantt(); return; }
            savePin(o, x.key, x.a);
        }
        bar.addEventListener('pointerup', function () { end(true); });
        bar.addEventListener('pointercancel', function () { end(false); });
        bar.addEventListener('keydown', function (e) { keyMove(e, bar, o, from, span); });
    }

    /** 키보드 이동: M 시작 → ←/→ 15분(Shift 1시간) · ↑/↓ 후보 자원 → Enter 고정 / Esc 취소
        @param {KeyboardEvent} e @param {HTMLElement} bar @param {ApsPlanOp} o @param {number} from @param {number} span */
    function keyMove(e, bar, o, from, span) {
        if (!kbMove) {
            if (e.key !== 'm' && e.key !== 'M') { return; }
            e.preventDefault();
            kbMove = {o: o, bar: bar, a: o.startAt ? sec(o.startAt) : from, key: resKey(o)};
            bar.classList.add('aps-g-moving');
            opInfo.textContent = hb.t('js.apsPlan.kbHelp');
            return;
        }
        if (kbMove.bar !== bar) { return; }
        var m = kbMove;
        var rows = candRows(o);
        if (e.key === 'ArrowLeft' || e.key === 'ArrowRight') {
            var step = (e.shiftKey ? 4 : 1) * SNAP * (e.key === 'ArrowLeft' ? -1 : 1);
            m.a = Math.min(Math.max(snap(m.a) + step, from), from + span - 60);
        } else if ((e.key === 'ArrowUp' || e.key === 'ArrowDown') && rows.length) {
            var i = rows.indexOf(m.key);
            m.key = rows[(i + (e.key === 'ArrowUp' ? rows.length - 1 : 1)) % rows.length];
        } else if (e.key === 'Enter') {
            e.preventDefault();
            kbMove = null;
            bar.classList.remove('aps-g-moving');
            savePin(o, m.key, m.a);
            return;
        } else if (e.key === 'Escape') {
            e.preventDefault();
            kbMove = null;
            renderGantt();
            opInfo.textContent = hb.t('js.apsPlan.moveCancelled');
            return;
        } else {
            return;
        }
        e.preventDefault();
        showMove(bar, o, m.key, m.a, from, span);
        bar.focus();
    }

    /** 지금 고정 목록 + [해제] */
    function renderPins() {
        pinsBody.textContent = '';
        var list = gantt ? gantt.pins : [];
        if (!list.length) {
            var tr0 = hb.el('tr');
            var td0 = hb.el('td', 'hb-muted', hb.t('js.apsPlan.noPins'));
            td0.colSpan = 6;
            tr0.appendChild(td0);
            pinsBody.appendChild(tr0);
        }
        list.forEach(function (pn) {
            var tr = hb.el('tr');
            [pn.woNo + ' · ' + woStText(pn.woStatus), pn.opSeq + ' · ' + (pn.procCd || '') + ' · ' + (pn.procNm || ''),
                pn.resKind === 'OUTSRC' ? hb.t('js.apsPlan.outsrc') : pn.resCd || '', dt(pn.startAt),
                pn.srcPlanNo + ' · ' + pn.updatedBy].forEach(function (x, i) { tr.appendChild(hb.el('td', i === 1 ? 'hb-wrap' : null, x)); });
            var td = hb.el('td');
            if (CAN_SAVE) {
                var b = hb.el('button', 'hb-btn-secondary', hb.t('js.apsPlan.unpin'));
                b.type = 'button';
                b.addEventListener('click', function () {
                    if (!current) { return; }
                    var planId = current.plan.planId;
                    hb.busy(b, function () {
                        return hb.api('/api/aps/pins/' + pn.pinId + '/release', {method: 'POST', body: {rowVer: pn.rowVer}});
                    }).then(function (res) {
                        if (!res) { return; }
                        return open(planId).then(function () { notify(hb.t('js.apsPlan.unpinned', pn.woNo, pn.opSeq)); });
                    }, fail);
                });
                td.appendChild(b);
            }
            tr.appendChild(td);
            pinsBody.appendChild(tr);
        });
    }

    /** 막대를 고르면 같은 지시 막대를 강조하고 아래에 공정 상세 @param {ApsPlanOp} o */
    function pick(o) {
        pickedWo = pickedWo === o.woId ? null : o.woId;
        highlight();
        opInfo.textContent = pickedWo === null ? '' : hb.t('js.apsPlan.opInfo', o.woNo, o.opSeq, o.procCd + ' · ' + o.procNm,
            dt(o.startAt), dt(o.endAt), Math.round(o.setupSec / 60), Math.round(o.runSec / 60))
            + (o.warnCd ? ' · ' + warnText(o.warnCd) : '');
    }

    function highlight() {
        ganttBox.querySelectorAll('.aps-g-bar').forEach(function (b) {
            var mine = pickedWo !== null && b.getAttribute('data-wo') === String(pickedWo);
            b.classList.toggle('aps-g-hl', mine);
            b.classList.toggle('aps-g-dim', pickedWo !== null && !mine);
            b.setAttribute('aria-pressed', mine ? 'true' : 'false');
        });
    }

    /** @param {ApsPlanOp[]} rows */
    function renderUnsched(rows) {
        unschedBody.textContent = '';
        if (!rows.length) {
            var tr0 = hb.el('tr');
            var td0 = hb.el('td', 'hb-muted', hb.t('js.apsPlan.allScheduled'));
            td0.colSpan = 5;
            tr0.appendChild(td0);
            unschedBody.appendChild(tr0);
        }
        rows.forEach(function (o) {
            var tr = hb.el('tr');
            [o.woNo, o.opSeq + ' · ' + o.procCd + ' · ' + o.procNm, o.nodeCd || '', qtyText(o.qty) + ' ' + o.unitCd, excText(o.excCd)]
                .forEach(function (x, i) {
                    var td = hb.el('td', i === 3 ? 'hb-num' : i === 1 ? 'hb-wrap' : null, i === 4 ? null : x);
                    if (i === 4) { td.appendChild(hb.status('danger', x)); }
                    tr.appendChild(td);
                });
            unschedBody.appendChild(tr);
        });
    }

    /** 자재 할당(부족 먼저) @param {ApsPlanMat[]} rows */
    function renderMats(rows) {
        matsBody.textContent = '';
        picked = {};
        syncPrBtn();
        var p = current ? current.plan : null;
        var canPick = !!prBtn && !!p && !p.purgedAt && p.status !== 'DISCARDED';
        if (!rows.length) {
            var tr0 = hb.el('tr');
            var td0 = hb.el('td', 'hb-muted', hb.t('js.apsPlan.noMats'));
            td0.colSpan = 10;
            tr0.appendChild(td0);
            matsBody.appendChild(tr0);
        }
        rows.forEach(function (m) {
            var tr = hb.el('tr');
            var short = Number(m.shortQty) > 0;
            var pick = hb.el('td', 'aps-pick-col');
            if (canPick && short && m.procType === 'BUY' && !m.prNo) {
                var cb = /** @type {HTMLInputElement} */ (hb.el('input'));
                cb.type = 'checkbox';
                cb.setAttribute('aria-label', hb.t('js.apsPlan.pr.pickRow', m.woNo, m.itemCd));
                cb.addEventListener('change', function () {
                    if (cb.checked) { picked[m.planMatId] = true; } else { delete picked[m.planMatId]; }
                    syncPrBtn();
                });
                pick.appendChild(cb);
            }
            tr.appendChild(pick);
            [m.woNo, String(m.opSeq) + (m.srcOpSeq !== null && m.srcOpSeq !== m.opSeq ? ' (' + m.srcOpSeq + ')' : ''),
                m.itemCd + ' · ' + m.itemNm, qtyText(m.needQty) + ' ' + m.unitCd, qtyText(m.allocQty), qtyText(m.shortQty),
                m.needAt ? dt(m.needAt) : hb.t('js.apsPlan.matNoJudge'), m.coveredAt ? dt(m.coveredAt) : (short ? hb.t('js.apsPlan.matNever') : '')]
                .forEach(function (x, i) {
                    var cls = i >= 3 && i <= 5 ? 'hb-num' : i === 2 ? 'hb-wrap' : null;
                    if (i === 5 && short) { cls = 'hb-num aps-warn'; }
                    tr.appendChild(hb.el('td', cls, x));
                });
            tr.appendChild(hb.el('td', m.prNo ? null : 'hb-muted', m.prNo ? m.prNo
                : short && m.procType && m.procType !== 'BUY' ? hb.t('js.apsPlan.pr.needWo') : ''));
            matsBody.appendChild(tr);
        });
    }

    /** @param {ApsPlanWo[]} rows */
    function renderWos(rows) {
        wosBody.textContent = '';
        rows.forEach(function (w) {
            var tr = hb.el('tr');
            var result = (w.excCd ? excText(w.excCd) : w.lateYn === 'Y' ? hb.t('js.apsPlan.late') : hb.t('js.apsPlan.onTime'))
                + (w.matCd ? ' · ' + hb.t(/** @type {Record<string, string>} */ (MATCD)[w.matCd] || 'js.apsPlan.mat.SHORT') : '');
            var due = hb.fmt.date(w.dueDate || w.planEnd) + (w.dueSrc === 'ORDER' ? ' ' + hb.t('js.apsPlan.dueOrder') : '');
            [String(w.seq), w.woNo, w.itemCd + ' · ' + w.itemNm, qtyText(w.planQty) + ' ' + w.unitCd, String(w.priority),
                due, dt(w.startAt), dt(w.endAt), result]
                .forEach(function (x, i) {
                    var cls = i === 0 || i === 3 || i === 4 ? 'hb-num' : i === 2 ? 'hb-wrap' : null;
                    var td = hb.el('td', cls, i === 8 ? null : x);
                    // 결과: 계획 못 함 = 위험, 납기 늦음·자재 부족 = 주의, 그 밖 = 정상(색 없음)
                    if (i === 8) { td.appendChild(hb.status(w.excCd ? 'danger' : w.lateYn === 'Y' || w.matCd ? 'warning' : 'normal', x)); }
                    tr.appendChild(td);
                });
            wosBody.appendChild(tr);
        });
    }

    // ------------------------------------------------------------------ 계산·폐기

    function newPlan() {
        show(null, null);
        hb.page.openDetail();
        field('horizonStart').focus();
    }
    ['newBtn', 'newBtnList'].forEach(function (id) {
        var b = document.getElementById(id);
        if (b) { b.addEventListener('click', newPlan); }
    });

    /** 연타·재전송이 계획 두 개를 만들지 않게 요청마다 키 하나 */
    function requestKey() {
        return typeof crypto !== 'undefined' && crypto.randomUUID ? crypto.randomUUID()
            : String(Date.now()) + '-' + Math.random().toString(36).slice(2);
    }

    if (calcBtn) {
        var calc = calcBtn;
        calc.addEventListener('click', function () {
            hb.showFieldErrors(form, []);
            if (!daysCtl.validate().ok || !leadCtl.validate().ok) { return; }
            var days = daysCtl.numberValue();
            var lead = leadCtl.numberValue();
            var body = {plantCd: field('plantCd').value, horizonStart: field('horizonStart').value,
                horizonDays: days === null ? null : days, outsrcLeadHr: lead === null ? null : lead,
                planNm: field('planNm').value.trim(), matMode: field('matMode').value,
                matPo: /** @type {HTMLInputElement} */ (field('matPo')).checked};
            var key = requestKey();
            notify(hb.t('js.apsPlan.calculating'));
            hb.busy(calc, function () {
                return hb.api(API, {method: 'POST', body: body, headers: {'X-Idempotency-Key': key}, timeoutMs: 120000});
            }).then(function (res) {
                if (!res) { return; }
                return load(res.data).then(function () { notify(hb.t('js.apsPlan.calculated')); });
            }, function (err) {
                fail(err);
                hb.showFieldErrors(form, err.errors);
            });
        });
    }

    if (discardBtn) {
        var discard = discardBtn;
        discard.addEventListener('click', function () {
            if (!current) { return; }
            var plan = current.plan;   // 클릭 시점 값 — 응답 뒤 current 가 바뀌어도 이 계획
            hb.dialog.prompt({title: hb.t('js.apsPlan.discardTitle'), message: hb.t('js.apsPlan.discardPrompt', plan.planNo),
                label: hb.t('js.apsPlan.discardReason'), ok: hb.t('js.apsPlan.discardOk'), danger: true, required: true, maxLength: 500})
                .then(function (reason) {
                    if (reason === null) { return null; }   // 취소 = 아무 일 없음
                    return hb.busy(discard, function () {
                        return hb.api(API + '/' + plan.planId + '/discard', {method: 'POST', body: {reason: reason, rowVer: plan.rowVer}});
                    });
                }).then(function (res) {
                if (!res) { return; }
                return load(plan.planId).then(function () { notify(hb.t('js.apsPlan.discarded')); });
            }, fail);
        });
    }

    // ------------------------------------------------------------------ 반영 (2단계)

    /** @param {ApsApplyPreview} pv */
    function renderPreview(pv) {
        if (!applySection || !applyInfo || !staleBox || !staleList || !applyBody || !applyBtn) { return; }
        var list = staleList;
        var body = applyBody;
        preview = pv;
        applySection.hidden = false;
        list.textContent = '';
        pv.stale.forEach(function (x) {
            list.appendChild(hb.el('li', null, (x.woNo || hb.t('js.apsPlan.wholePlan')) + ' — '
                + hb.t(/** @type {Record<string, string>} */ (STALE)[x.code] || 'js.apsPlan.stale.APS_WO_CHANGED')));
        });
        staleBox.hidden = !pv.stale.length;
        var blocked = pv.expired || pv.stale.length > 0;
        applyInfo.textContent = pv.expired ? hb.t('js.apsPlan.applyExpired') : pv.stale.length ? hb.t('js.apsPlan.applyStale')
            : pv.wos.length ? hb.t('js.apsPlan.applyCounts', pv.woCnt, pv.opCnt, pv.pushedCnt) : hb.t('js.apsPlan.applyNone');
        applyInfo.classList.toggle('aps-warn', blocked);
        applyBtn.hidden = blocked;
        body.textContent = '';
        pv.wos.forEach(function (w) {
            var tr = hb.el('tr');
            var start = w.fromStart === w.toStart ? hb.fmt.date(w.toStart) : hb.fmt.date(w.fromStart) + ' → ' + hb.fmt.date(w.toStart);
            var end = w.fromEnd === w.toEnd ? hb.fmt.date(w.toEnd) : hb.fmt.date(w.fromEnd) + ' → ' + hb.fmt.date(w.toEnd)
                + (w.pushed ? ' (' + hb.t('js.apsPlan.pushed') + ')' : '');
            var eq = w.equips.map(function (q) {
                return q.opSeq + ' ' + q.procCd + ': ' + (q.fromEquipCd || '-') + ' → ' + (q.toEquipCd || '-');
            }).join(', ');
            [w.woNo, '', start, end, eq].forEach(function (x, i) {
                var cls = i === 4 ? 'hb-wrap' : null;
                if ((i === 2 && w.fromStart !== w.toStart) || (i === 3 && w.fromEnd !== w.toEnd) || (i === 4 && eq)) { cls = 'aps-changed' + (i === 4 ? ' hb-wrap' : ''); }
                if (i === 3 && w.pushed) { cls = 'aps-warn'; }
                var td = hb.el('td', cls, i === 1 ? null : x);
                if (i === 1) { td.appendChild(badge('wo', w.woStatus, woStText(w.woStatus))); }
                tr.appendChild(td);
            });
            body.appendChild(tr);
        });
        renderFenced(pv);
    }

    /** 확정 구간 안이라 그대로 두는 지시(9단계, docs/38 18.3) @param {ApsApplyPreview} pv */
    function renderFenced(pv) {
        var box = /** @type {HTMLElement | null} */ (document.getElementById('fencedBox'));
        var fb = /** @type {HTMLElement | null} */ (document.querySelector('#fencedWos tbody'));
        if (!box || !fb) { return; }
        var fbody = fb;
        var list = pv.fenced || [];
        box.hidden = !list.length;
        /** @type {HTMLElement} */ (document.getElementById('fencedInfo')).textContent = hb.t('js.apsPlan.fencedInfo', list.length, pv.fenceDays);
        fb.textContent = '';
        list.forEach(function (w) {
            var tr = hb.el('tr');
            tr.appendChild(hb.el('td', null, w.woNo));
            var st = hb.el('td');
            st.appendChild(badge('wo', w.woStatus, woStText(w.woStatus)));
            tr.appendChild(st);
            tr.appendChild(hb.el('td', null, hb.fmt.date(w.fromStart) + ' ~ ' + hb.fmt.date(w.fromEnd)));
            tr.appendChild(hb.el('td', 'hb-muted', hb.fmt.date(w.toStart) + ' ~ ' + hb.fmt.date(w.toEnd)
                + (w.equipCnt ? ' · ' + hb.t('js.apsPlan.fencedEquip', w.equipCnt) : '')));
            tr.appendChild(hb.el('td', 'hb-num', hb.t('js.apsPlan.fenceDays', w.fenceDays)));
            fbody.appendChild(tr);
        });
    }

    if (previewBtn) {
        var pvBtn = previewBtn;
        pvBtn.addEventListener('click', function () {
            if (!current) { return; }
            var id = current.plan.planId;   // 클릭 시점 값
            hb.busy(pvBtn, function () { return hb.latest('apply', hb.api(API + '/' + id + '/apply')); }).then(function (res) {
                if (!res || !current || current.plan.planId !== id) { return; }
                notify('');
                renderPreview(res.data);
            }, fail);
        });
    }

    if (applyBtn) {
        var apBtn = applyBtn;
        apBtn.addEventListener('click', function () {
            var pv = preview;
            if (!pv || !current || current.plan.planId !== pv.plan.planId) { return; }
            var plan = current.plan;
            hb.dialog.confirm({title: hb.t('js.apsPlan.applyTitle'),
                message: hb.t('js.apsPlan.applyConfirm', plan.planNo, hb.t('js.apsPlan.applyCounts', pv.woCnt, pv.opCnt, pv.pushedCnt)),
                ok: hb.t('js.apsPlan.applyOk')}).then(function (yes) {
                if (!yes) { return null; }   // 취소 = 아무 일 없음
                var key = requestKey();
                return hb.busy(apBtn, function () {
                    return hb.api(API + '/' + plan.planId + '/apply', {method: 'POST', body: {rowVer: plan.rowVer},
                        headers: {'X-Idempotency-Key': key}, timeoutMs: 120000});
                });
            }).then(function (res) {
                if (!res) { return; }
                var r = res.data;
                return load(plan.planId).then(function () { notify(hb.t('js.apsPlan.applied', r.woCnt, r.opCnt)); });
            }, function (err) {
                fail(err);
                // 낡음 409 면 미리보기를 다시 받아 사유를 보인다
                if (err && err.code === 'APS_PLAN_STALE' && previewBtn) { previewBtn.click(); }
            });
        });
    }

    // ------------------------------------------------------------------ 비교 (2단계)

    /** 같은 공장의 다른 계획을 선택지로 @param {ApsPlan | null} p */
    function fillCompare(p) {
        compared = null;
        cmpBox.hidden = true;
        cmpSel.textContent = '';
        cmpBtn.disabled = true;
        if (!p) { return; }
        // 선택지 = 조회 기간 안 같은 공장의 다른 계획(기존 목록 API — grid 는 한 쪽만 가진다)
        var v = sp.values();
        var q = ['from', 'to'].filter(function (k) { return v[k]; }).map(function (k) { return k + '=' + encodeURIComponent(v[k]); })
            .concat('plantCd=' + encodeURIComponent(p.plantCd)).join('&');
        var id = p.planId;
        hb.latest('cmpList', hb.api(API + '?' + q)).then(function (res) {
            if (!current || current.plan.planId !== id) { return; }
            /** @type {ApsPlan[]} */
            var others = res.data.rows.filter(/** @param {ApsPlan} x */ function (x) { return x.planId !== id; });
            cmpSel.textContent = '';
            if (!others.length) { option(cmpSel, '', hb.t('js.apsPlan.cmpNone')); }
            others.forEach(function (x) { option(cmpSel, x.planId, x.planNo + (x.planNm ? ' · ' + x.planNm : '') + ' · ' + stText(x.status)); });
            cmpBtn.disabled = !others.length;
        }, fail);
    }

    /** @param {number | null} s */
    function shiftText(s) {
        if (s === null || s === undefined) { return ''; }
        return (s > 0 ? '+' : '') + hb.t('js.apsPlan.shiftMin', Math.round(s / 60));
    }

    /** @param {string | null} start @param {string | null} exc */
    function cmpTime(start, exc) { return start ? dt(start) : exc ? excText(exc) : '-'; }

    function renderCompare() {
        var c = compared;
        cmpBody.textContent = '';
        if (!c) { cmpBox.hidden = true; return; }
        cmpBox.hidden = false;
        var k = c.kinds;
        cmpSummary.textContent = hb.t('js.apsPlan.cmpSummary', k.SAME || 0, k.MOVED || 0, k.RES || 0, k.SCHEDULED || 0, k.UNSCHEDULED || 0,
            (k.ONLY_A || 0) + (k.ONLY_B || 0));
        var rows = cmpChanged.checked ? c.rows.filter(function (r) { return r.kind !== 'SAME'; }) : c.rows;
        if (!rows.length) {
            var tr0 = hb.el('tr');
            var td0 = hb.el('td', 'hb-muted', hb.t('js.apsPlan.cmpSame'));
            td0.colSpan = 7;
            tr0.appendChild(td0);
            cmpBody.appendChild(tr0);
        }
        rows.forEach(function (r) {
            var tr = hb.el('tr');
            var res = r.aRes === r.bRes ? (r.aRes || '') : (r.aRes || '-') + ' → ' + (r.bRes || '-');
            [hb.t(/** @type {Record<string, string>} */ (KIND)[r.kind] || 'js.apsPlan.kind.SAME'), r.woNo,
                r.opSeq + ' · ' + r.procCd + ' · ' + r.procNm, cmpTime(r.aStart, r.aExc), cmpTime(r.bStart, r.bExc),
                shiftText(r.shiftSec), res].forEach(function (x, i) {
                var cls = i === 5 ? 'hb-num' : i === 2 ? 'hb-wrap' : null;
                if (i === 0 && r.kind !== 'SAME') { cls = 'aps-changed'; }
                if (i === 0 && r.kind === 'UNSCHEDULED') { cls = 'aps-warn'; }
                tr.appendChild(hb.el('td', cls, x));
            });
            cmpBody.appendChild(tr);
        });
    }

    cmpBtn.addEventListener('click', function () {
        if (!current) { return; }
        var id = current.plan.planId;
        var other = cmpSel.value;
        if (!other) { notify(hb.t('js.apsPlan.cmpPick'), true); return; }
        hb.busy(cmpBtn, function () {
            return hb.latest('compare', hb.api(API + '/' + id + '/compare?other=' + encodeURIComponent(other)));
        }).then(function (res) {
            if (!res || !current || current.plan.planId !== id) { return; }
            notify('');
            compared = res.data;
            renderCompare();
        }, fail);
    });
    cmpChanged.addEventListener('change', renderCompare);

    document.querySelectorAll('#zoom input').forEach(function (r) { r.addEventListener('change', renderGantt); });

    // ------------------------------------------------------------------ 부족 → 구매 요청 초안 (8단계, docs/38 17.4)

    var prBtn = /** @type {HTMLButtonElement | null} */ (document.getElementById('prBtn'));   // SAVE 권한 없으면 없음
    var prDialog = /** @type {HTMLDialogElement} */ (document.getElementById('prDialog'));
    var prMake = /** @type {HTMLButtonElement} */ (document.getElementById('prMake'));
    var prErrors = /** @type {HTMLElement} */ (document.getElementById('prErrors'));
    /** 고른 자재 줄 id @type {Record<number, boolean>} */
    var picked = {};
    /** 미리보기한 계획·줄(만들기는 이것으로) @type {{planId: number, matIds: number[]} | null} */
    var prPick = null;

    function pickedIds() {
        return Object.keys(picked).map(Number);
    }

    function syncPrBtn() {
        if (prBtn) { prBtn.disabled = !pickedIds().length; }
    }

    var PR_ERR = {APS_PR_NOT_SHORT: 'js.apsPlan.pr.err.NOT_SHORT', APS_PR_MAKE: 'js.apsPlan.pr.err.MAKE',
        APS_PR_LINKED: 'js.apsPlan.pr.err.LINKED', INACTIVE: 'js.apsPlan.pr.err.INACTIVE'};

    /** @param {ApsPrPreview} r */
    function renderPr(r) {
        /** @type {HTMLElement} */ (document.getElementById('prHead')).textContent =
            hb.t('js.apsPlan.pr.head', r.planNo, r.plantCd, r.needDate || '-');
        var body = /** @type {HTMLElement} */ (document.querySelector('#prLines tbody'));
        body.textContent = '';
        r.lines.forEach(function (l) {
            var tr = hb.el('tr');
            tr.appendChild(hb.el('td', 'hb-wrap', l.itemCd + ' · ' + l.itemNm));
            tr.appendChild(hb.el('td', 'hb-num', qtyText(l.shortQty) + ' ' + l.unitCd));
            tr.appendChild(hb.el('td', 'hb-num', (l.moq ? qtyText(l.moq) : '-') + ' / ' + (l.orderMult ? qtyText(l.orderMult) : '-')));
            tr.appendChild(hb.el('td', 'hb-num', qtyText(l.reqQty) + ' ' + l.unitCd));
            tr.appendChild(hb.el('td', null, l.needDate));
            tr.appendChild(hb.el('td', l.late ? 'aps-warn' : null,
                l.orderBy + ' (' + hb.t('js.apsPlan.pr.leadDays', l.leadDays) + ')' + (l.late ? ' · ' + hb.t('js.apsPlan.pr.late') : '')));
            tr.appendChild(hb.el('td', l.noSource ? 'aps-warn' : null,
                l.noSource ? hb.t('js.apsPlan.pr.noSource') : (l.supplierCd || '') + ' · ' + (l.supplierNm || '')));
            body.appendChild(tr);
        });
        var errs = r.errors.map(function (x) {
            return x.matId + ': ' + hb.t(/** @type {Record<string, string>} */ (PR_ERR)[x.code] || 'js.apsPlan.pr.err.NOT_SHORT');
        });
        prErrors.hidden = !errs.length;
        prErrors.textContent = errs.length ? hb.t('js.apsPlan.pr.errors', errs.join(' / ')) : '';
        prMake.disabled = !r.lines.length || !!errs.length;
    }

    if (prBtn) {
        var prOpen = prBtn;
        prOpen.addEventListener('click', function () {
            if (!current) { return; }
            var planId = current.plan.planId;   // 클릭 시점 값
            var ids = pickedIds();
            if (!ids.length) { return; }
            hb.busy(prOpen, function () {
                return hb.api(API + '/' + planId + '/pr/preview', {method: 'POST', body: {matIds: ids}});
            }).then(function (res) {
                if (!res) { return; }
                prPick = {planId: planId, matIds: ids};
                renderPr(/** @type {ApsPrPreview} */ (res.data));
                prDialog.showModal();
            }, fail);
        });
        prMake.addEventListener('click', function () {
            if (!prPick) { return; }
            var pk = prPick;   // 누른 시점 값(널 아님)
            var key = requestKey();
            hb.busy(prMake, function () {
                return hb.api(API + '/' + pk.planId + '/pr', {method: 'POST', body: {matIds: pk.matIds}, headers: {'X-Idempotency-Key': key}});
            }).then(function (res) {
                if (!res) { return; }
                prDialog.close();
                prPick = null;
                var made = /** @type {{prId: number, prNo: string, lineCnt: number}} */ (res.data);
                return open(pk.planId).then(function () { notify(hb.t('js.apsPlan.pr.made', made.prNo, made.lineCnt)); });
            }, function (err) {
                prDialog.close();
                fail(err);
                return open(pk.planId);
            });
        });
        /** @type {HTMLElement} */ (document.getElementById('prClose')).addEventListener('click', function () { prDialog.close(); });
    }

    // ------------------------------------------------------------------ 일일 작업 배정표 (9단계, docs/38 18.5 — 읽기만)

    var dispatchForm = /** @type {HTMLFormElement} */ (document.getElementById('dispatchForm'));
    var dispatchPlant = /** @type {HTMLSelectElement} */ (document.getElementById('dispatchPlant'));
    var dispatchDay = /** @type {HTMLInputElement} */ (dispatchForm.querySelector('[data-field="day"]'));
    var dispatchBtn = /** @type {HTMLButtonElement} */ (document.getElementById('dispatchBtn'));
    var PROC = {WAIT: 'js.apsPlan.proc.WAIT', READY: 'js.apsPlan.proc.READY', RUN: 'js.apsPlan.proc.RUN',
        PAUSE: 'js.apsPlan.proc.PAUSE', DONE: 'js.apsPlan.proc.DONE'};

    /**
     * @typedef {{seq: number, resKind: string, resCd: string | null, woId: number, woNo: string, woProcId: number | null, opSeq: number,
     *   procCd: string, procNm: string, startAt: string, endAt: string, itemCd: string, itemNm: string, qty: string, unitCd: string,
     *   woStatus: string, procStatus: string | null, curEquipCd: string | null, outOfOrder: boolean}} ApsBoardRow
     * @typedef {{plantCd: string, day: string, planId: number | null, planNo: string | null, appliedAt: string | null,
     *   timeZone: string | null, rows: ApsBoardRow[]}} ApsBoard
     */

    /** @param {ApsBoard} b */
    function renderBoard(b) {
        var info = /** @type {HTMLElement} */ (document.getElementById('dispatchInfo'));
        var body = /** @type {HTMLElement} */ (document.querySelector('#dispatchRows tbody'));
        body.textContent = '';
        var zone = b.timeZone || undefined;
        info.textContent = b.planId === null ? hb.t('js.apsPlan.dispatch.noPlan', b.plantCd)
            : hb.t('js.apsPlan.dispatch.info', b.planNo, b.appliedAt ? hb.fmt.datetime(b.appliedAt, zone) : '-', b.day, b.rows.length);
        if (b.planId !== null && !b.rows.length) {
            var tr0 = hb.el('tr');
            var td0 = /** @type {HTMLTableCellElement} */ (hb.el('td', 'hb-muted', hb.t('js.apsPlan.dispatch.empty')));
            td0.colSpan = 10;
            tr0.appendChild(td0);
            body.appendChild(tr0);
        }
        b.rows.forEach(function (r) {
            var tr = hb.el('tr', r.seq === 1 ? 'aps-dispatch-first' : null);
            var res = r.resKind === 'OUTSRC' ? hb.t('js.apsPlan.outsrc') : (r.resCd || '-');
            if (r.resKind === 'EQUIP' && r.curEquipCd && r.curEquipCd !== r.resCd) { res += ' (' + hb.t('js.apsPlan.dispatch.nowEquip', r.curEquipCd) + ')'; }
            [res, String(r.seq), hb.fmt.datetime(r.startAt, zone), hb.fmt.datetime(r.endAt, zone), r.woNo, r.opSeq + ' ' + r.procNm,
                r.itemCd + ' · ' + r.itemNm, qtyText(r.qty) + ' ' + r.unitCd,
                r.procStatus ? hb.t(/** @type {Record<string, string>} */ (PROC)[r.procStatus] || 'js.apsPlan.proc.WAIT') : woStText(r.woStatus),
                r.outOfOrder ? hb.t('js.apsPlan.dispatch.outOfOrder') : ''].forEach(function (x, i) {
                var cls = i === 1 || i === 7 ? 'hb-num' : i === 6 ? 'hb-wrap' : null;
                if (i === 9 && r.outOfOrder) { cls = 'aps-warn'; }
                tr.appendChild(hb.el('td', cls, x));
            });
            body.appendChild(tr);
        });
    }

    function loadBoard() {
        var q = '?plantCd=' + encodeURIComponent(dispatchPlant.value) + (dispatchDay.value ? '&day=' + encodeURIComponent(dispatchDay.value) : '');
        return hb.busy(dispatchBtn, function () { return hb.latest('dispatch', hb.api('/api/aps/dispatch' + q)); }).then(function (res) {
            if (!res) { return; }
            var b = /** @type {ApsBoard} */ (res.data);
            dispatchDay.value = b.day;
            renderBoard(b);
        }, fail);
    }
    dispatchBtn.addEventListener('click', function () { loadBoard(); });
    /** @type {HTMLElement} */ (document.getElementById('dispatchPrint')).addEventListener('click', function () {
        document.body.classList.add('aps-print-dispatch');   // 배정표만 인쇄(css @media print)
        window.addEventListener('afterprint', function () { document.body.classList.remove('aps-print-dispatch'); }, {once: true});
        window.print();
    });
    dispatchForm.addEventListener('submit', function (e) { e.preventDefault(); loadBoard(); });

    // ------------------------------------------------------------------ 납기 답변 (6단계 CTP — 읽기 전용)

    var ctpDialog = /** @type {HTMLDialogElement} */ (document.getElementById('ctpDialog'));
    var ctpForm = /** @type {HTMLFormElement} */ (document.getElementById('ctpForm'));
    var ctpPlant = /** @type {HTMLSelectElement} */ (document.getElementById('ctpPlant'));
    var ctpRun = /** @type {HTMLButtonElement} */ (document.getElementById('ctpRun'));
    var ctpResult = /** @type {HTMLElement} */ (document.getElementById('ctpResult'));
    var ctpAnswer = /** @type {HTMLElement} */ (document.getElementById('ctpAnswer'));

    /** @param {string} n */
    function ctpField(n) {
        return /** @type {HTMLInputElement | HTMLSelectElement} */ (ctpForm.querySelector('[data-field="' + n + '"]'));
    }

    /** @param {string | null} iso @param {string} zone */
    function ctpDt(iso, zone) { return iso ? hb.fmt.datetime(iso, zone) : '-'; }

    /** @param {ApsCtpResult} r */
    function renderCtp(r) {
        ctpResult.hidden = false;
        ctpAnswer.textContent = '';
        var head = r.itemCd + ' · ' + r.itemNm;
        if (r.endAt) {
            ctpAnswer.appendChild(document.createTextNode(hb.t('js.apsPlan.ctp.answer', head, qtyText(r.qty), r.unitCd,
                ctpDt(r.endAt, r.timeZone), r.woCnt) + ' '));
            ctpAnswer.appendChild(hb.status(r.lateYn === 'Y' ? 'warning' : 'success',
                hb.t(r.lateYn === 'Y' ? 'js.apsPlan.ctp.late' : 'js.apsPlan.ctp.onTime')));
        } else {
            ctpAnswer.appendChild(hb.status('danger', hb.t('js.apsPlan.ctp.cannot', head, qtyText(r.qty), r.unitCd,
                r.excCd === 'NO_BOM' ? hb.t('js.apsPlan.ctp.noBom') : excText(r.excCd))));
        }
        if (r.excCd === 'NO_BOM' && r.endAt) {
            ctpAnswer.appendChild(hb.el('span', 'hb-muted', ' ' + hb.t('js.apsPlan.ctp.noBom')));
        }
        var ob = /** @type {HTMLElement} */ (document.querySelector('#ctpOps tbody'));
        ob.textContent = '';
        r.ops.forEach(function (o) {
            var tr = hb.el('tr');
            [o.opSeq + ' · ' + o.procCd + ' · ' + o.procNm, o.resCd || (o.resKind === 'OUTSRC' ? hb.t('js.apsPlan.outsrc') : '-'),
                ctpDt(o.startAt, r.timeZone), ctpDt(o.endAt, r.timeZone)].forEach(function (x, i) {
                tr.appendChild(hb.el('td', i === 0 ? 'hb-wrap' : null, x));
            });
            var td = hb.el('td');
            td.appendChild(o.excCd ? hb.status('danger', excText(o.excCd)) : hb.status('normal', hb.t('js.apsPlan.onTime')));
            tr.appendChild(td);
            ob.appendChild(tr);
        });
        var mb = /** @type {HTMLElement} */ (document.querySelector('#ctpMats tbody'));
        mb.textContent = '';
        r.mats.forEach(function (m) {
            var tr = hb.el('tr');
            var short = Number(m.shortQty) > 0;
            // 계획 못 한 공정의 줄은 판정 없음(필요 시각 없음) — 부족 0 이 아니라 '-'
            [(m.itemCd || m.itemId) + (m.itemNm ? ' · ' + m.itemNm : ''), qtyText(m.needQty) + ' ' + m.unitCd, m.needAt ? qtyText(m.shortQty) : '-',
                ctpDt(m.coveredAt, r.timeZone)].forEach(function (x, i) {
                tr.appendChild(hb.el('td', i === 1 || i === 2 ? 'hb-num' + (i === 2 && short ? ' aps-warn' : '') : null, x));
            });
            mb.appendChild(tr);
        });
        /** @type {HTMLElement} */ (document.getElementById('ctpMats')).hidden = !r.mats.length;
    }

    /** @type {HTMLElement} */ (document.getElementById('ctpBtn')).addEventListener('click', function () {
        hb.showFieldErrors(ctpForm, []);
        var v = sp.values();
        if (v.plantCd) { ctpPlant.value = String(v.plantCd); }
        ctpResult.hidden = true;
        ctpDialog.showModal();
        ctpField('itemCd').focus();
    });
    /** @type {HTMLElement} */ (document.getElementById('ctpClose')).addEventListener('click', function () { ctpDialog.close(); });
    ctpRun.addEventListener('click', function () {
        hb.showFieldErrors(ctpForm, []);
        var prio = ctpField('priority').value.trim();
        var body = {plantCd: ctpPlant.value, itemCd: ctpField('itemCd').value.trim(), qty: ctpField('qty').value.trim(),
            wantDate: ctpField('wantDate').value || null, priority: prio ? Number(prio) : null};
        hb.busy(ctpRun, function () { return hb.latest('ctp', hb.api('/api/aps/ctp', {method: 'POST', body: body})); }).then(function (res) {
            if (res) { renderCtp(res.data); }
        }, function (err) {
            if (err && err.stale) { return; }
            ctpResult.hidden = true;
            hb.showFieldErrors(ctpForm, err.errors);
            if (!err.errors || !err.errors.length) { ctpAnswer.textContent = err.message || ''; ctpResult.hidden = false; }
        });
    });

    hb.loadMessages().then(function () {
        hb.page.init();
        daysCtl = hb.number(/** @type {HTMLInputElement} */ (field('horizonDays')));
        leadCtl = hb.number(/** @type {HTMLInputElement} */ (field('outsrcLeadHr')));
        return Promise.all([hb.loadSession(), hb.api(API + '/plants')]);
    }).then(function (r) {
        var plantSel = /** @type {HTMLSelectElement} */ (document.getElementById('plantSel'));
        option(plantSel, '', hb.t('js.apsPlan.allPlant'));
        /** @type {{plantCd: string, plantNm: string}[]} */ (r[1].data).forEach(function (p) {
            option(plantSel, p.plantCd, p.plantCd + ' · ' + p.plantNm);
            option(/** @type {HTMLSelectElement} */ (field('plantCd')), p.plantCd, p.plantCd + ' · ' + p.plantNm);
            option(ctpPlant, p.plantCd, p.plantCd + ' · ' + p.plantNm);
            option(dispatchPlant, p.plantCd, p.plantCd + ' · ' + p.plantNm);
        });
        sp = hb.searchPanel(search, {onSearch: function () { return leaveOk().then(function (ok) { return ok ? load() : null; }); }});
        hb.dirty.watch(form);
        grid = hb.grid('#planGrid', {
            progCd: PROG, id: 'main', key: 'planId',
            url: API + '/grid', columnsUrl: API + '/columns', exportUrl: API + '/export', actions: ACTIONS, params: params,
            format: {status: function (v, row) { return stText(v) + (row.currentYn === 'Y' ? ' · ' + hb.t('js.apsPlan.current') : ''); }},
            status: {status: TONES.plan},
            rowClass: function (row) { return row.status === 'DISCARDED' ? 'hb-tree-dim' : null; },
            beforeSelect: function () { return leaveOk(); },
            onSelect: function (row) { open(/** @type {number} */ (row.planId)).then(function () { hb.page.openDetail(); }); },
            notify: notify
        });
        return Promise.all([sp.ready, grid.ready]);
    }).then(function () {
        show(null, null);
        if (dispatchPlant.value) { loadBoard(); }
        return load();
    }).then(null, function (err) { fail(err); });
})();
