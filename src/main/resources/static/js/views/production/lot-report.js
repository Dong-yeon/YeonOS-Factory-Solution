// @ts-check
/* LOT 역추적 보고서 (prd-lot-report) — MES P2 7b (docs/25 4.10). LOT 번호 → 절 8개(머리·역추적·생산 이력·공정 조건·완료판정·설비 상태·입고·정추적).
   서버 데이터는 textContent 로만, 들여쓰기는 CSSOM(paddingLeft). [인쇄] = window.print() + 인쇄용 CSS(D17 — 서버 PDF 없음). */
/**
 * @typedef {{lvl: number, lotId: number, lotNo: string, itemCd: string, itemNm: string, origin: string, relType: string,
 *   qty?: string | null, unitCd?: string | null, woNo?: string | null, supplierLotNo?: string | null, holdYn: string, cycle: boolean}} RptTrace
 * @typedef {{rows: RptTrace[], truncated: boolean, depthLimit: number}} RptTraceView
 * @typedef {{rows: Record<string, any>[], truncated: boolean}} RptSection
 * @typedef {{lot: Record<string, any>, receivedQty: string, back: RptTraceView, fwd: RptTraceView, production: RptSection,
 *   params: RptSection, closes: RptSection, states: RptSection, receipts: RptSection, generatedAt: string, generatedBy: string,
 *   zone: string}} RptReport
 * @typedef {[string, (r: Record<string, any>) => string, string?]} RptCol
 */
(function () {
    'use strict';

    var API = '/api/production/lot-report';
    var msg = /** @type {HTMLElement} */ (document.getElementById('msg'));
    var search = /** @type {HTMLFormElement} */ (document.getElementById('search'));
    var printBtn = /** @type {HTMLButtonElement} */ (document.getElementById('printBtn'));
    /** @type {HB.SearchPanel} */
    var sp;
    /** @type {Record<string, string>} */
    var origins = {};
    /** @type {Record<string, string>} */
    var rels = {};

    /** @param {string | null | undefined} text @param {boolean} [error] */
    function notify(text, error) {
        msg.textContent = text || '';
        msg.classList.toggle('hb-msg-error', !!error);
    }

    /** @param {unknown} v */
    function q(v) { return v == null || v === '' ? '' : hb.fmt.num(v, 6, 0); }

    /** @param {unknown} v */
    function dt(v) { return v ? hb.fmt.datetime(v) : ''; }

    /** @param {string} name */
    function sec(name) { return /** @type {HTMLElement} */ (document.querySelector('[data-sec="' + name + '"]')); }

    /**
     * 표 하나 — 열 = [제목, 값 함수, 칸 클래스]. 정정 행·정정된 행은 흐리게
     * @param {RptCol[]} cols @param {Record<string, any>[]} rows @param {boolean} truncated
     * @param {((r: Record<string, any>, tr: HTMLElement) => void)=} decorate
     */
    function table(cols, rows, truncated, decorate) {
        var wrap = hb.el('div', 'hb-replace-host hb-rpt-table');
        if (!rows.length) {
            wrap.appendChild(hb.el('p', 'hb-muted', hb.t('js.prdRpt.none')));
            return wrap;
        }
        var t = hb.el('table', 'hb-cmp-table');
        var head = hb.el('tr');
        cols.forEach(function (c) { head.appendChild(hb.el('th', c[2] || null, c[0])); });
        var thead = hb.el('thead');
        thead.appendChild(head);
        t.appendChild(thead);
        var body = hb.el('tbody');
        rows.forEach(function (r) {
            var tr = hb.el('tr', r.reversalOfId || r.reversed ? 'hb-tree-dim' : null);
            cols.forEach(function (c) { tr.appendChild(hb.el('td', c[2] || null, c[1](r))); });
            if (decorate) { decorate(r, tr); }
            body.appendChild(tr);
        });
        t.appendChild(body);
        wrap.appendChild(t);
        if (truncated) { wrap.appendChild(hb.el('p', 'hb-msg-error', hb.t('js.prdRpt.truncated', rows.length))); }
        return wrap;
    }

    /** @param {string} name @param {HTMLElement} content */
    function fill(name, content) {
        var s = sec(name);
        var body = /** @type {HTMLElement} */ (s.querySelector('.hb-rpt-body'));
        body.textContent = '';
        body.appendChild(content);
        s.hidden = false;
    }

    /** @param {Record<string, any>} r */
    function corr(r) { return r.reversalOfId ? hb.t('js.prdRpt.correction') + (r.reason ? ': ' + r.reason : '') : r.reversed ? hb.t('js.prdRpt.corrected') : ''; }

    /** @param {RptTraceView} v @param {boolean} back */
    function traceTable(v, back) {
        /** @type {RptCol[]} */
        var cols = [
            [hb.t('js.prdRpt.c.lvl'), function (r) { return String(r.lvl); }, 'hb-num'],
            [hb.t('js.prdRpt.c.lotNo'), function (r) { return r.lotNo + (r.cycle ? ' ↺' : ''); }],
            [hb.t('js.prdRpt.c.item'), function (r) { return r.itemCd + ' · ' + r.itemNm; }, 'hb-wrap'],
            [hb.t('js.prdRpt.c.origin'), function (r) { return origins[r.origin] || r.origin; }],
            [hb.t('js.prdRpt.c.rel'), function (r) { return rels[r.relType] || r.relType; }],
            [hb.t('js.prdRpt.c.qty'), function (r) { return q(r.qty) + (r.unitCd ? ' ' + r.unitCd : ''); }, 'hb-num'],
            [back ? hb.t('js.prdRpt.c.sourceBack') : hb.t('js.prdRpt.c.sourceFwd'),
                function (r) { return r.woNo || r.supplierLotNo || ''; }],
            [hb.t('js.prdRpt.c.hold'), function (r) { return r.holdYn === 'Y' ? hb.t('js.prdRpt.hold') : ''; }]
        ];
        return table(cols, v.rows, v.truncated, function (r, tr) {
            var td = /** @type {HTMLElement} */ (tr.children[1]);
            td.style.paddingLeft = ((Number(r.lvl) - 1) * 16 + 8) + 'px';
        });
    }

    /** @param {RptReport} d */
    function render(d) {
        var l = d.lot;
        /** @type {HTMLElement} */ (document.getElementById('empty')).hidden = true;
        /** @type {HTMLElement} */ (document.getElementById('rptTitle')).textContent = hb.t('js.prdRpt.title', l.lotNo);
        var head = /** @type {HTMLElement} */ (document.getElementById('head'));
        head.textContent = '';
        [[hb.t('js.prdRpt.h.item'), l.itemCd + ' · ' + l.itemNm], [hb.t('js.prdRpt.h.origin'), origins[l.origin] || l.origin],
            [hb.t('js.prdRpt.h.made'), q(l.qtyCreated) + ' ' + l.unitCd + ' · ' + dt(l.occurredAt)],
            [hb.t('js.prdRpt.h.balance'), q(l.balance) + ' ' + l.unitCd], [hb.t('js.prdRpt.h.received'), q(d.receivedQty) + ' ' + l.unitCd],
            [hb.t('js.prdRpt.h.wo'), l.woNo || ''], [hb.t('js.prdRpt.h.supplier'), l.supplierCd ? l.supplierCd + ' · ' + (l.supplierLotNo || '') : ''],
            [hb.t('js.prdRpt.h.hold'), l.holdYn === 'Y' ? hb.t('js.prdRpt.hold') + (l.holdReason ? ' — ' + l.holdReason : '') : '']]
            .forEach(function (p) {
                if (!p[1]) { return; }
                head.appendChild(hb.el('dt', null, p[0]));
                head.appendChild(hb.el('dd', null, p[1]));
            });
        /** @type {HTMLElement} */ (document.getElementById('generated')).textContent =
            hb.t('js.prdRpt.generated', hb.fmt.datetime(d.generatedAt), d.generatedBy, d.zone);
        /** @type {HTMLElement} */ (document.getElementById('headSec')).hidden = false;

        fill('back', traceTable(d.back, true));
        fill('fwd', traceTable(d.fwd, false));
        fill('production', table([
            [hb.t('js.prdRpt.c.at'), function (r) { return dt(r.occurredAt); }],
            [hb.t('js.prdRpt.c.lotNo'), function (r) { return r.lotNo || ''; }],
            [hb.t('js.prdRpt.c.wo'), function (r) { return r.woNo + ' · ' + r.opSeq + ' ' + r.procCd; }, 'hb-wrap'],
            [hb.t('js.prdRpt.c.kind'), function (r) { return r.kind === 'RESULT' ? hb.t('js.prdRpt.k.result') : r.kind === 'REWORK'
                ? hb.t('js.prdRpt.k.rework') : hb.t('js.prdRpt.k.repair'); }],
            [hb.t('js.prdRpt.c.qtys'), function (r) { return hb.t('js.prdRpt.qtys', q(r.goodQty), q(r.scrapQty), q(r.reworkQty), q(r.repairQty)); }, 'hb-wrap'],
            [hb.t('js.prdRpt.c.worker'), function (r) { return r.workerNm ? r.workerCd + ' · ' + r.workerNm : ''; }],
            [hb.t('js.prdRpt.c.where'), function (r) { return [r.equipCd, r.terminalCd].filter(Boolean).join(' · '); }],
            [hb.t('js.prdRpt.c.day'), function (r) { return r.workDay ? r.workDay + (r.shiftCd ? ' ' + r.shiftCd : '') : ''; }],
            [hb.t('js.prdRpt.c.entry'), function (r) { return r.entryMethod + ' · ' + r.recordedBy; }],
            [hb.t('js.prdRpt.c.note'), corr, 'hb-wrap']
        ], d.production.rows, d.production.truncated));
        fill('params', table([
            [hb.t('js.prdRpt.c.at'), function (r) { return dt(r.occurredAt); }],
            [hb.t('js.prdRpt.c.wo'), function (r) { return r.woNo + ' · ' + r.opSeq + ' ' + r.procCd; }, 'hb-wrap'],
            [hb.t('js.prdRpt.c.param'), function (r) { return r.paramCd + ' · ' + r.paramNm; }, 'hb-wrap'],
            [hb.t('js.prdRpt.c.value'), function (r) { return q(r.value) + (r.unitNm ? ' ' + r.unitNm : ''); }, 'hb-num'],
            [hb.t('js.prdRpt.c.limits'), function (r) { return r.lsl || r.usl ? q(r.lsl) + ' ~ ' + q(r.usl) : ''; }],
            [hb.t('js.prdRpt.c.judge'), function (r) { return r.judge; }],
            [hb.t('js.prdRpt.c.source'), function (r) { return r.source + (r.equipCd ? ' · ' + r.equipCd : ''); }],
            [hb.t('js.prdRpt.c.note'), corr, 'hb-wrap']
        ], d.params.rows, d.params.truncated, function (r, tr) { if (r.judge === 'NG') { tr.classList.add('hb-rpt-ng'); } }));
        fill('closes', table([
            [hb.t('js.prdRpt.c.wo'), function (r) { return r.woNo + ' · ' + r.opSeq + ' ' + r.procCd; }, 'hb-wrap'],
            [hb.t('js.prdRpt.c.input'), function (r) { return q(r.inputQty) + (r.firstOpYn === 'Y' ? ' *' : ''); }, 'hb-num'],
            [hb.t('js.prdRpt.c.qtys'), function (r) { return hb.t('js.prdRpt.qtys', q(r.goodQty), q(r.scrapQty), q(r.reworkQty), q(r.repairQty)); }, 'hb-wrap'],
            [hb.t('js.prdRpt.c.effGood'), function (r) { return q(r.effGoodQty); }, 'hb-num'],
            [hb.t('js.prdRpt.c.diff'), function (r) { return q(r.diffQty) + ' (±' + r.tolPct + '%)'; }, 'hb-num'],
            [hb.t('js.prdRpt.c.judged'), function (r) { return dt(r.occurredAt) + ' · ' + r.recordedBy; }]
        ], d.closes.rows, d.closes.truncated));
        fill('states', table([
            [hb.t('js.prdRpt.c.wo'), function (r) { return r.woNo; }],
            [hb.t('js.prdRpt.c.equip'), function (r) { return r.equipCd; }],
            [hb.t('js.prdRpt.c.state'), function (r) { return r.stateCat + (r.reasonNm ? ' · ' + r.reasonNm : ''); }],
            [hb.t('js.prdRpt.c.start'), function (r) { return dt(r.startAt); }],
            [hb.t('js.prdRpt.c.end'), function (r) { return r.endAt ? dt(r.endAt) : hb.t('js.prdRpt.open'); }]
        ], d.states.rows, d.states.truncated));
        fill('receipts', table([
            [hb.t('js.prdRpt.c.at'), function (r) { return dt(r.occurredAt); }],
            [hb.t('js.prdRpt.c.loc'), function (r) { return r.nodeCd; }],
            [hb.t('js.prdRpt.c.qty'), function (r) { return q(r.qty) + ' ' + r.unitCd; }, 'hb-num'],
            [hb.t('js.prdRpt.c.entry'), function (r) { return r.entryMethod + ' · ' + r.recordedBy; }],
            [hb.t('js.prdRpt.c.note'), corr, 'hb-wrap']
        ], d.receipts.rows, d.receipts.truncated));
        printBtn.disabled = false;
    }

    function clear() {
        document.querySelectorAll('.hb-rpt-sec').forEach(function (s) { /** @type {HTMLElement} */ (s).hidden = true; });
        /** @type {HTMLElement} */ (document.getElementById('empty')).hidden = false;
        printBtn.disabled = true;
    }

    function load() {
        var v = sp.values();
        if (!v.lotNo) { clear(); return Promise.resolve(); }
        notify('');
        return hb.latest('lotReport', hb.api(API + '?lotNo=' + encodeURIComponent(String(v.lotNo).trim()))).then(function (res) {
            render(/** @type {RptReport} */ (res.data));
        }, function (err) {
            if (err.stale) { return; }
            clear();
            notify(err.status === 404 ? hb.t('js.prdRpt.notFound') : err.message, true);
        });
    }

    printBtn.addEventListener('click', function () { window.print(); });

    hb.loadMessages().then(function () {
        hb.page.init();
        return Promise.all([hb.loadSession(), hb.codeList('LOT_ORIGIN'), hb.codeList('LOT_REL_TYPE')]);
    }).then(function (r) {
        r[1].forEach(function (c) { origins[c.code] = c.name; });
        r[2].forEach(function (c) { rels[c.code] = c.name; });
        sp = hb.searchPanel(search, {onSearch: function () { return load(); }});
        return sp.ready;
    }).then(function () {
        // 다른 화면(LOT 추적)에서 ?lotNo= 로 열면 바로 조회
        var p = new URLSearchParams(window.location.search).get('lotNo');
        if (p) { sp.setValue('lotNo', p); return load(); }
    }).then(null, function (err) { notify(err && err.message, true); });
})();
