// @ts-check
/**
 * 자재소요계획 MRP (aps-mrp) — APS 10단계, docs/38 19장.
 * 실행 목록 → 상세(요약·폐기) → 계획 주문 표(종류 거르기) · 예외 표 · 계획 품목 표 → 재고/소요 목록(품목) → 페깅 사슬(요소).
 * 서버 수량은 글자 그대로 보인다(천 단위 쉼표만). 계산은 지시·구매 요청을 만들지 않는다(AP-4).
 *
 * @typedef {{mrpId: number, mrpNo: string, plantCd: string, mrpNm: string | null, horizonStart: string, horizonDays: number,
 *   mpsId: number | null, mpsNo: string | null, outDays: number, status: string, itemCnt: number, orderCnt: number, excCnt: number,
 *   calculatedAt: string, calculatedBy: string, discardReason: string | null, rowVer: number}} MrpHead
 * @typedef {{itemId: number, itemCd: string, itemNm: string, llc: number, procType: string, unitCd: string, mpsYn: string,
 *   stockQty: string, safetyQty: string, lotRule: string, lotQty: string | null, lotMult: string | null, moq: string | null,
 *   orderMult: string | null, leadDays: number, leadSrc: string, custCd: string | null, orderCnt: number, orderQty: string,
 *   excCnt: number}} MrpItem
 * @typedef {{excId: number, itemId: number, itemCd: string, itemNm: string, excCd: string, elemSeq: number | null,
 *   elemKind: string | null, curDate: string | null, sugDate: string | null, qty: string | null, refNo: string | null}} MrpExc
 * @typedef {{mrp: MrpHead, items: MrpItem[], excs: MrpExc[], excMore: boolean}} MrpDetailView
 * @typedef {{elemSeq: number, itemId: number, itemCd: string, itemNm: string, unitCd: string, dir: string, kind: string,
 *   elemDate: string, qty: string, pastDueYn: string, startDate: string | null, lateYn: string, parentSeq: number | null,
 *   refId: number | null, refNo: string | null, procType: string}} MrpElem
 * @typedef {{seq: number, itemCd: string | null, kind: string | null, elemDate: string | null, refNo: string | null, qty: string}} MrpPegLink
 * @typedef {{elem: MrpElem, pab: string, pegs: MrpPegLink[]}} MrpLine
 * @typedef {{idx: number, parent: number, elem: MrpElem, qty: string | null}} MrpPegNode
 */
(function () {
    'use strict';

    var API = '/api/aps/mrp';
    var ACTIONS = (document.body.getAttribute('data-actions') || '').split(',');
    var CAN_SAVE = ACTIONS.indexOf('SAVE') >= 0;
    var msg = /** @type {HTMLElement} */ (document.getElementById('msg'));
    var search = /** @type {HTMLFormElement} */ (document.getElementById('search'));
    var form = /** @type {HTMLFormElement} */ (document.getElementById('mrpForm'));
    var formMode = /** @type {HTMLElement} */ (document.getElementById('formMode'));
    var summary = /** @type {HTMLElement} */ (document.getElementById('summary'));
    var noMps = /** @type {HTMLElement} */ (document.getElementById('noMps'));
    var listBody = /** @type {HTMLElement} */ (document.querySelector('#mrpList tbody'));
    var listCount = /** @type {HTMLElement} */ (document.getElementById('listCount'));
    var calcBtn = /** @type {HTMLButtonElement | null} */ (document.getElementById('calcBtn'));
    var discardBtn = /** @type {HTMLButtonElement | null} */ (document.getElementById('discardBtn'));
    var kindSel = /** @type {HTMLSelectElement} */ (document.getElementById('kindSel'));
    var sections = ['orderSection', 'excSection', 'itemSection'].map(function (id) {
        return /** @type {HTMLElement} */ (document.getElementById(id));
    });
    var listSection = /** @type {HTMLElement} */ (document.getElementById('listSection'));
    var pegSection = /** @type {HTMLElement} */ (document.getElementById('pegSection'));
    var ST = {CALC: 'js.apsMrp.st.CALC', DISCARDED: 'js.apsMrp.st.DISCARDED'};
    /** @type {Record<string, HB.StatusTone>} */
    var TONE = {CALC: 'success', DISCARDED: 'neutral'};
    /** 요소 종류 — 시스템 고정(엔진이 값마다 분기) */
    var KINDS = ['STOCK', 'WO', 'PO', 'MPS', 'PLAN', 'SO', 'FC', 'DEP', 'WODEP', 'SAFE'];
    var EXCS = ['RESCHED_IN', 'RESCHED_OUT', 'CANCEL', 'REDUCE', 'START_PAST', 'NO_LEAD', 'NO_SOURCE', 'NO_BOM', 'UNIT', 'MPS_PAST',
        'MPS_DEP', 'MPS_SHORT'];
    var PROCS = ['MAKE', 'BUY', 'OUTSRC'];
    var LOTS = ['EX', 'FX', 'MIN'];
    /** @type {HB.SearchPanel} */
    var sp;
    /** @type {HB.NumberControl} */
    var daysCtl;
    /** @type {MrpDetailView | null} */
    var current = null;

    /** @param {string | null | undefined} text @param {boolean} [error] */
    function notify(text, error) {
        msg.textContent = text || '';
        msg.classList.toggle('hb-msg-error', !!error);
    }

    /** @param {HB.ApiError} err */
    function fail(err) {
        if (err && err.stale) { return; }
        notify(err && err.errors && err.errors.length ? err.errors.map(function (x) { return x.message; }).join(' / ')
            : err && err.message, true);
    }

    /** @param {HTMLFormElement} f @param {string} n */
    function field(f, n) {
        return /** @type {HTMLInputElement | HTMLSelectElement} */ (f.querySelector('[data-field="' + n + '"]'));
    }

    /** 시스템 고정 값 이름 @param {string} prefix @param {string[]} known @param {string | null} v */
    function named(prefix, known, v) {
        return v && known.indexOf(v) >= 0 ? hb.t(prefix + v) : (v || '');
    }

    /** @param {string | null} k */
    function kindText(k) { return named('js.apsMrp.kind.', KINDS, k); }

    /** 서버 글자 수량 → 천 단위 쉼표 @param {string | number | null | undefined} s */
    function qtyText(s) {
        if (s === null || s === undefined || s === '') { return ''; }
        var t = String(s);
        var neg = t.charAt(0) === '-';
        var p = (neg ? t.slice(1) : t).split('.');
        return (neg ? '-' : '') + p[0].replace(/\B(?=(\d{3})+(?!\d))/g, ',') + (p.length > 1 ? '.' + p[1] : '');
    }

    /** @param {string | null} iso */
    function dt(iso) { return iso ? hb.fmt.datetime(iso) : ''; }

    /** @param {HTMLSelectElement} sel @param {string} value @param {string} text */
    function option(sel, value, text) {
        var o = /** @type {HTMLOptionElement} */ (hb.el('option', null, text));
        o.value = value;
        sel.appendChild(o);
    }

    function requestKey() {
        return typeof crypto !== 'undefined' && crypto.randomUUID ? crypto.randomUUID()
            : String(Date.now()) + '-' + Math.random().toString(36).slice(2);
    }

    /** 빈 표 한 줄 @param {HTMLElement} body @param {number} cols @param {string} key */
    function empty(body, cols, key) {
        var tr = hb.el('tr');
        var td = /** @type {HTMLTableCellElement} */ (hb.el('td', 'hb-muted', hb.t(key)));
        td.colSpan = cols;
        tr.appendChild(td);
        body.appendChild(tr);
    }

    /** 고를 수 있는 줄 @param {HTMLElement} tr @param {() => void} pick */
    function pickable(tr, pick) {
        tr.classList.add('aps-mps-pick');
        tr.tabIndex = 0;
        tr.addEventListener('click', pick);
        tr.addEventListener('keydown', function (ev) { if (ev.key === 'Enter') { pick(); } });
    }

    /** @param {string} sel */
    function tbody(sel) { return /** @type {HTMLElement} */ (document.querySelector(sel + ' tbody')); }

    // ------------------------------------------------------------------ 목록

    /** @param {number} [selectId] */
    function load(selectId) {
        var v = sp.values();
        /** @type {string[]} */
        var q = [];
        ['from', 'to', 'plantCd'].forEach(function (k) { if (v[k]) { q.push(k + '=' + encodeURIComponent(v[k])); } });
        return hb.latest('list', hb.api(API + '?' + q.join('&'))).then(function (res) {
            var data = /** @type {{list: MrpHead[], more: boolean}} */ (res.data);
            renderList(data.list);
            listCount.textContent = data.more ? hb.t('js.apsMrp.more', data.list.length) : hb.t('js.apsMrp.count', data.list.length);
            if (selectId) { return open(selectId); }
            show(null);
            return undefined;
        }, fail);
    }

    /** @param {MrpHead[]} rows */
    function renderList(rows) {
        listBody.textContent = '';
        if (!rows.length) { empty(listBody, 8, 'js.apsMrp.none'); return; }
        rows.forEach(function (m) {
            var tr = hb.el('tr', current && current.mrp.mrpId === m.mrpId ? 'aps-mps-picked' : null);
            tr.setAttribute('data-id', String(m.mrpId));
            tr.appendChild(hb.el('td', null, m.mrpNo + (m.mrpNm ? ' · ' + m.mrpNm : '')));
            tr.appendChild(hb.el('td', null, m.plantCd));
            tr.appendChild(hb.el('td', null, hb.t('js.apsMrp.horizon', m.horizonStart, m.horizonDays)));
            tr.appendChild(hb.el('td', m.mpsNo ? null : 'aps-warn', m.mpsNo || hb.t('js.apsMrp.noMpsShort')));
            tr.appendChild(hb.el('td', 'hb-num', m.orderCnt));
            tr.appendChild(hb.el('td', 'hb-num', m.excCnt));
            var st = hb.el('td');
            st.appendChild(hb.status(TONE[m.status] || 'neutral', hb.t(/** @type {Record<string, string>} */ (ST)[m.status] || ST.CALC)));
            tr.appendChild(st);
            tr.appendChild(hb.el('td', null, dt(m.calculatedAt) + ' · ' + m.calculatedBy));
            var id = m.mrpId;
            pickable(tr, function () { open(id).then(function () { hb.page.openDetail(); }); });
            listBody.appendChild(tr);
        });
    }

    /** @param {number} id */
    function open(id) {
        return hb.latest('mrp', hb.api(API + '/' + id)).then(function (res) {
            show(/** @type {MrpDetailView} */ (res.data));
            return loadOrders();
        }, fail);
    }

    // ------------------------------------------------------------------ 상세

    /** @param {MrpDetailView | null} d */
    function show(d) {
        current = d;
        hb.showFieldErrors(form, []);
        var m = d ? d.mrp : null;
        Array.prototype.forEach.call(listBody.querySelectorAll('tr[data-id]'), function (/** @type {HTMLElement} */ tr) {
            tr.classList.toggle('aps-mps-picked', !!m && tr.getAttribute('data-id') === String(m.mrpId));
        });
        if (m) {
            field(form, 'plantCd').value = m.plantCd;
            daysCtl.setValue(m.horizonDays);
            field(form, 'mrpNm').value = m.mrpNm || '';
        } else {
            daysCtl.setValue(28);
            field(form, 'mrpNm').value = '';
        }
        ['plantCd', 'horizonDays', 'mrpNm'].forEach(function (n) { field(form, n).disabled = !CAN_SAVE || !!m; });
        formMode.textContent = m ? hb.t('js.apsMrp.viewing', m.mrpNo) : hb.t('js.apsMrp.creating');
        if (calcBtn) { calcBtn.hidden = !!m; }
        if (discardBtn) { discardBtn.hidden = !m || m.status !== 'CALC'; }
        noMps.hidden = !m || !!m.mpsId;
        renderSummary(d);
        sections.forEach(function (s) { s.hidden = !d; });
        listSection.hidden = true;
        pegSection.hidden = true;
        if (d) {
            renderExcs(d);
            renderItems(d);
        }
    }

    /** @param {MrpDetailView | null} d */
    function renderSummary(d) {
        summary.textContent = '';
        summary.hidden = !d;
        if (!d) { return; }
        var m = d.mrp;
        /** @type {[string, string][]} */
        var items = [
            [hb.t('js.apsMrp.sum.status'), hb.t(/** @type {Record<string, string>} */ (ST)[m.status] || ST.CALC)
                + (m.discardReason ? ' — ' + m.discardReason : '')],
            [hb.t('js.apsMrp.sum.calculated'), dt(m.calculatedAt) + ' · ' + m.calculatedBy],
            [hb.t('js.apsMrp.sum.horizon'), hb.t('js.apsMrp.horizon', m.horizonStart, m.horizonDays)],
            [hb.t('js.apsMrp.sum.mps'), m.mpsNo || hb.t('js.apsMrp.noMpsShort')],
            [hb.t('js.apsMrp.sum.counts'), hb.t('js.apsMrp.counts', m.itemCnt, m.orderCnt, m.excCnt)],
            [hb.t('js.apsMrp.sum.outDays'), hb.t('js.apsMrp.outDays', m.outDays)]];
        items.forEach(function (it) {
            summary.appendChild(hb.el('dt', null, it[0]));
            summary.appendChild(hb.el('dd', null, it[1]));
        });
    }

    // ------------------------------------------------------------------ 계획 주문

    function loadOrders() {
        if (!current) { return Promise.resolve(); }
        var id = current.mrp.mrpId;
        var k = kindSel.value;
        return hb.latest('orders', hb.api(API + '/' + id + '/orders' + (k ? '?kind=' + encodeURIComponent(k) : ''))).then(function (res) {
            var data = /** @type {{list: MrpElem[], more: boolean}} */ (res.data);
            renderOrders(data.list, data.more);
        }, fail);
    }

    /** @param {MrpElem[]} rows @param {boolean} more */
    function renderOrders(rows, more) {
        var body = tbody('#orderTable');
        body.textContent = '';
        /** @type {HTMLElement} */ (document.getElementById('orderCount')).textContent =
            more ? hb.t('js.apsMrp.more', rows.length) : hb.t('js.apsMrp.count', rows.length);
        if (!rows.length) { empty(body, 6, 'js.apsMrp.noOrders'); return; }
        rows.forEach(function (e) {
            var tr = hb.el('tr');
            tr.appendChild(hb.el('td', null, e.itemCd + ' · ' + e.itemNm));
            tr.appendChild(hb.el('td', null, kindText(e.kind)));
            tr.appendChild(hb.el('td', 'hb-num', qtyText(e.qty) + ' ' + e.unitCd));
            tr.appendChild(hb.el('td', e.lateYn === 'Y' ? 'aps-warn' : null, e.startDate || ''));
            tr.appendChild(hb.el('td', null, e.elemDate));
            tr.appendChild(hb.el('td', e.lateYn === 'Y' ? 'aps-warn' : null, e.lateYn === 'Y' ? hb.t('js.apsMrp.late') : ''));
            pickable(tr, function () { openItem(e.itemId).then(function () { return peg(e); }); });
            body.appendChild(tr);
        });
    }

    // ------------------------------------------------------------------ 예외·품목

    /** @param {MrpDetailView} d */
    function renderExcs(d) {
        var body = tbody('#excTable');
        body.textContent = '';
        /** @type {HTMLElement} */ (document.getElementById('excCount')).textContent =
            d.excMore ? hb.t('js.apsMrp.more', d.excs.length) : hb.t('js.apsMrp.count', d.excs.length);
        if (!d.excs.length) { empty(body, 6, 'js.apsMrp.noExcs'); return; }
        d.excs.forEach(function (x) {
            var tr = hb.el('tr');
            tr.appendChild(hb.el('td', null, x.itemCd + ' · ' + x.itemNm));
            tr.appendChild(hb.el('td', 'aps-warn', named('js.apsMrp.exc.', EXCS, x.excCd)));
            tr.appendChild(hb.el('td', null, (x.elemKind ? kindText(x.elemKind) + ' ' : '') + (x.refNo || '')));
            tr.appendChild(hb.el('td', null, x.curDate || ''));
            tr.appendChild(hb.el('td', null, x.sugDate || ''));
            tr.appendChild(hb.el('td', 'hb-num', qtyText(x.qty)));
            var itemId = x.itemId;
            pickable(tr, function () { openItem(itemId); });
            body.appendChild(tr);
        });
    }

    /** @param {MrpItem} it */
    function lotText(it) {
        var s = named('js.apsMrp.lot.', LOTS, it.lotRule) + (it.lotQty ? ' ' + qtyText(it.lotQty) : '');
        if (it.lotMult) { s += ' · ' + hb.t('js.apsMrp.mult', qtyText(it.lotMult)); }
        if (it.moq) { s += ' · ' + hb.t('js.apsMrp.moq', qtyText(it.moq)); }
        if (it.orderMult) { s += ' · ' + hb.t('js.apsMrp.mult', qtyText(it.orderMult)); }
        return s;
    }

    /** @param {MrpDetailView} d */
    function renderItems(d) {
        var body = tbody('#itemTable');
        body.textContent = '';
        if (!d.items.length) { empty(body, 9, 'js.apsMrp.noItems'); return; }
        d.items.forEach(function (it) {
            var tr = hb.el('tr');
            tr.appendChild(hb.el('td', 'hb-num', it.llc));
            tr.appendChild(hb.el('td', null, it.itemCd + ' · ' + it.itemNm + (it.mpsYn === 'Y' ? ' · ' + hb.t('js.apsMrp.mpsItem') : '')));
            tr.appendChild(hb.el('td', null, named('js.apsMrp.proc.', PROCS, it.procType)));
            tr.appendChild(hb.el('td', 'hb-num', qtyText(it.stockQty) + ' ' + it.unitCd));
            tr.appendChild(hb.el('td', 'hb-num', qtyText(it.safetyQty)));
            tr.appendChild(hb.el('td', null, lotText(it)));
            tr.appendChild(hb.el('td', it.leadSrc === 'NONE' ? 'aps-warn' : null,
                it.leadSrc === 'NONE' ? hb.t('js.apsMrp.leadNone') : hb.t('js.apsMrp.leadDays', it.leadDays) + (it.custCd ? ' · ' + it.custCd : '')));
            tr.appendChild(hb.el('td', 'hb-num', it.orderCnt ? hb.t('js.apsMrp.orderSum', it.orderCnt, qtyText(it.orderQty)) : ''));
            tr.appendChild(hb.el('td', it.excCnt ? 'hb-num aps-warn' : 'hb-num', it.excCnt || ''));
            var id = it.itemId;
            pickable(tr, function () { openItem(id); });
            body.appendChild(tr);
        });
    }

    // ------------------------------------------------------------------ 재고/소요 목록 · 페깅

    /** @param {number} itemId */
    function openItem(itemId) {
        if (!current) { return Promise.resolve(); }
        return hb.latest('item', hb.api(API + '/' + current.mrp.mrpId + '/items/' + itemId)).then(function (res) {
            var data = /** @type {{item: MrpItem, lines: MrpLine[]}} */ (res.data);
            renderLines(data.item, data.lines);
            listSection.hidden = false;
            pegSection.hidden = true;
            listSection.scrollIntoView({block: 'start'});
        }, fail);
    }

    /** @param {MrpItem} it @param {MrpLine[]} lines */
    function renderLines(it, lines) {
        /** @type {HTMLElement} */ (document.getElementById('listTitle')).textContent = hb.t('js.apsMrp.listOf', it.itemCd + ' · ' + it.itemNm);
        var body = tbody('#elemTable');
        body.textContent = '';
        if (!lines.length) { empty(body, 7, 'js.apsMrp.noLines'); return; }
        lines.forEach(function (l) {
            var e = l.elem;
            var tr = hb.el('tr', e.kind === 'PLAN' || e.kind === 'MPS' ? 'aps-mrp-order' : null);
            tr.appendChild(hb.el('td', e.pastDueYn === 'Y' ? 'aps-warn' : null, e.elemDate + (e.pastDueYn === 'Y' ? ' · ' + hb.t('js.apsMrp.pastDue') : '')));
            tr.appendChild(hb.el('td', null, kindText(e.kind) + (e.startDate ? ' · ' + hb.t('js.apsMrp.startOn', e.startDate) : '')));
            tr.appendChild(hb.el('td', null, e.refNo || ''));
            tr.appendChild(hb.el('td', 'hb-num', e.dir === 'S' ? qtyText(e.qty) : ''));
            tr.appendChild(hb.el('td', 'hb-num', e.dir === 'D' ? qtyText(e.qty) : ''));
            tr.appendChild(hb.el('td', l.pab.charAt(0) === '-' ? 'hb-num aps-warn' : 'hb-num', qtyText(l.pab)));
            tr.appendChild(hb.el('td', null, l.pegs.map(function (p) {
                return hb.t('js.apsMrp.pegLink', kindText(p.kind) + (p.refNo ? ' ' + p.refNo : '') + (p.itemCd && p.itemCd !== it.itemCd ? ' ' + p.itemCd : ''),
                    qtyText(p.qty));
            }).join(' / ')));
            pickable(tr, function () { peg(e); });
            body.appendChild(tr);
        });
    }

    /** 위로 사슬 @param {MrpElem} e */
    function peg(e) {
        if (!current) { return Promise.resolve(); }
        return hb.latest('peg', hb.api(API + '/' + current.mrp.mrpId + '/elems/' + e.elemSeq + '/peg')).then(function (res) {
            var data = /** @type {{nodes: MrpPegNode[], more: boolean}} */ (res.data);
            /** @type {HTMLElement} */ (document.getElementById('pegTitle')).textContent =
                hb.t('js.apsMrp.pegOf', e.itemCd + ' ' + kindText(e.kind) + ' ' + e.elemDate) + (data.more ? ' · ' + hb.t('js.apsMrp.pegMore') : '');
            var body = tbody('#pegTable');
            body.textContent = '';
            /** @type {number[]} */
            var depth = [];
            data.nodes.forEach(function (n) {
                depth[n.idx] = n.parent < 0 ? 0 : depth[n.parent] + 1;
                var el = n.elem;
                var tr = hb.el('tr', n.parent < 0 ? 'aps-mps-picked' : null);
                tr.appendChild(hb.el('td', 'hb-num', depth[n.idx]));
                tr.appendChild(hb.el('td', null, el.itemCd + ' · ' + el.itemNm));
                tr.appendChild(hb.el('td', null, kindText(el.kind)));
                tr.appendChild(hb.el('td', null, el.elemDate));
                tr.appendChild(hb.el('td', 'hb-num', qtyText(el.qty)));
                tr.appendChild(hb.el('td', 'hb-num', qtyText(n.qty)));
                tr.appendChild(hb.el('td', null, el.refNo || ''));
                body.appendChild(tr);
            });
            pegSection.hidden = false;
            pegSection.scrollIntoView({block: 'start'});
        }, fail);
    }

    // ------------------------------------------------------------------ 계산·폐기

    function startNew() {
        show(null);
        hb.page.openDetail();
        field(form, 'plantCd').focus();
    }

    ['newBtn', 'newBtnList'].forEach(function (id) {
        var b = document.getElementById(id);
        if (b) { b.addEventListener('click', startNew); }
    });

    if (calcBtn) {
        var calc = calcBtn;
        calc.addEventListener('click', function () {
            hb.showFieldErrors(form, []);
            if (!daysCtl.validate().ok) { return; }
            var days = daysCtl.numberValue();
            var body = {plantCd: field(form, 'plantCd').value, horizonDays: days === null ? null : days, mrpNm: field(form, 'mrpNm').value.trim()};
            var key = requestKey();
            notify(hb.t('js.apsMrp.calculating'));
            hb.busy(calc, function () {
                return hb.api(API, {method: 'POST', body: body, headers: {'X-Idempotency-Key': key}, timeoutMs: 120000});
            }).then(function (res) {
                if (!res) { return; }
                return load(/** @type {number} */ (res.data)).then(function () { notify(hb.t('js.apsMrp.calculated')); });
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
            var m = current.mrp;
            hb.dialog.prompt({title: hb.t('js.apsMrp.discardTitle'), message: hb.t('js.apsMrp.discardPrompt', m.mrpNo),
                label: hb.t('js.apsMrp.discardReason'), ok: hb.t('js.apsMrp.discardOk'), danger: true, required: true, maxLength: 500})
                .then(function (reason) {
                    if (reason === null) { return null; }
                    return hb.busy(discard, function () {
                        return hb.api(API + '/' + m.mrpId + '/discard', {method: 'POST', body: {reason: reason, rowVer: m.rowVer}});
                    });
                }).then(function (res) {
                if (!res) { return; }
                return load(m.mrpId).then(function () { notify(hb.t('js.apsMrp.discarded')); });
            }, fail);
        });
    }

    kindSel.addEventListener('change', function () { loadOrders(); });

    // ------------------------------------------------------------------ 시작

    hb.loadMessages().then(function () {
        hb.page.init();
        daysCtl = hb.number(/** @type {HTMLInputElement} */ (field(form, 'horizonDays')));
        return Promise.all([hb.loadSession(), hb.api(API + '/plants')]);
    }).then(function (r) {
        var plantSel = /** @type {HTMLSelectElement} */ (document.getElementById('plantSel'));
        option(plantSel, '', hb.t('js.apsMrp.allPlant'));
        /** @type {{plantCd: string, plantNm: string}[]} */ (r[1].data).forEach(function (p) {
            option(plantSel, p.plantCd, p.plantCd + ' · ' + p.plantNm);
            option(/** @type {HTMLSelectElement} */ (field(form, 'plantCd')), p.plantCd, p.plantCd + ' · ' + p.plantNm);
        });
        sp = hb.searchPanel(search, {onSearch: function () { return load(); }});
        return sp.ready;
    }).then(function () {
        show(null);
        return load();
    }).then(null, function (err) { fail(err); });
})();
