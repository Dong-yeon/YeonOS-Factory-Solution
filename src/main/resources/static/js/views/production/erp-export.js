// @ts-check
/* ERP 업로드용 엑셀 (prd-erp-export) — MES P2 7c (docs/25 4.11). 종류 × 기간 → 미리보기 + 매핑 없는 행 + 이전 내보내기(D20) → [엑셀].
   엑셀은 마지막으로 조회한 조건 그대로(조회 전·조건을 바꾼 뒤에는 막는다 — 미리보기와 다른 파일이 나가지 않게). */
/**
 * @typedef {{bizKey: string, day: string, woNo: string, itemCd: string, itemNm: string, erpItemCd?: string | null,
 *   qtyText?: string | null, scrapQtyText?: string | null, unitCd: string, whCd?: string | null, lotNo?: string | null,
 *   entryMethod: string}} ErpRow
 * @typedef {{exportId: number, kind: string, fromDay: string, toDay: string, rowCnt: number, exportedAt: string, exportedBy: string}} ErpExportLog
 * @typedef {{rows: ErpRow[], total: number, truncated: boolean, unmapped: number, exports: ErpExportLog[], erpSystem: string}} ErpPreview
 * @typedef {[string, (r: ErpRow) => string, string?]} ErpCol
 */
(function () {
    'use strict';

    var API = '/api/production/erp-export';
    var msg = /** @type {HTMLElement} */ (document.getElementById('msg'));
    var form = /** @type {HTMLFormElement} */ (document.getElementById('search'));
    var periodHost = /** @type {HTMLElement} */ (document.getElementById('period'));
    var info = /** @type {HTMLElement} */ (document.getElementById('info'));
    var warn = /** @type {HTMLElement} */ (document.getElementById('warn'));
    var exportsSec = /** @type {HTMLElement} */ (document.getElementById('exportsSec'));
    var exportsList = /** @type {HTMLElement} */ (document.getElementById('exports'));
    var preview = /** @type {HTMLElement} */ (document.getElementById('preview'));
    var excelBtn = /** @type {HTMLButtonElement | null} */ (document.getElementById('excelBtn'));   // EXCEL 권한 없으면 없음
    /** 마지막으로 조회한 조건 @type {{kind: string, from: string, to: string} | null} */
    var last = null;
    /** @type {Record<string, string>} */
    var methods = {};

    /** @param {string | null | undefined} text @param {boolean} [error] */
    function notify(text, error) {
        msg.textContent = text || '';
        msg.classList.toggle('hb-msg-error', !!error);
    }

    /** @param {unknown} v */
    function q(v) { return v == null || v === '' ? '' : hb.fmt.num(v, 6, 0); }

    /** @param {string} kind @returns {ErpCol[]} */
    function columns(kind) {
        /** @type {ErpCol[]} */
        var head = [
            [hb.t('js.prdErp.col.day'), function (r) { return r.day; }],
            [hb.t('js.prdErp.col.erpItemCd'), function (r) { return r.erpItemCd || hb.t('js.prdErp.unmapped'); }],
            [hb.t('js.prdErp.col.item'), function (r) { return r.itemCd + ' ' + r.itemNm; }]
        ];
        if (kind === 'RESULT') {
            return head.concat(/** @type {ErpCol[]} */ ([
                [hb.t('js.prdErp.col.goodQty'), function (r) { return q(r.qtyText); }, 'hb-num'],
                [hb.t('js.prdErp.col.scrapQty'), function (r) { return q(r.scrapQtyText); }, 'hb-num'],
                [hb.t('js.prdErp.col.unitCd'), function (r) { return r.unitCd; }],
                [hb.t('js.prdErp.col.woNo'), function (r) { return r.woNo; }]
            ]));
        }
        return head.concat(/** @type {ErpCol[]} */ ([
            [hb.t('js.prdErp.col.qty'), function (r) { return q(r.qtyText); }, 'hb-num'],
            [hb.t('js.prdErp.col.unitCd'), function (r) { return r.unitCd; }],
            [hb.t('js.prdErp.col.whCd'), function (r) { return r.whCd || ''; }],
            [hb.t('js.prdErp.col.lotNo'), function (r) { return r.lotNo || ''; }],
            [hb.t('js.prdErp.col.woNo'), function (r) { return r.woNo; }],
            [hb.t('js.prdErp.col.entryMethod'), function (r) { return methods[r.entryMethod] || r.entryMethod; }]
        ]));
    }

    /** @param {string} kind @param {ErpRow[]} rows */
    function drawTable(kind, rows) {
        preview.textContent = '';
        if (!rows.length) {
            preview.appendChild(hb.el('p', 'hb-muted', hb.t('js.prdErp.none')));
            return;
        }
        var cols = columns(kind);
        var t = hb.el('table', 'hb-cmp-table');
        var thead = hb.el('thead');
        var head = hb.el('tr');
        cols.forEach(function (c) { head.appendChild(hb.el('th', c[2] || null, c[0])); });
        thead.appendChild(head);
        t.appendChild(thead);
        var body = hb.el('tbody');
        rows.forEach(function (r) {
            var tr = hb.el('tr', r.erpItemCd ? null : 'hb-row-warn');
            cols.forEach(function (c) { tr.appendChild(hb.el('td', c[2] || null, c[1](r))); });
            body.appendChild(tr);
        });
        t.appendChild(body);
        preview.appendChild(t);
    }

    /** @param {ErpPreview} d */
    function drawExports(d) {
        exportsList.textContent = '';
        d.exports.forEach(function (x) {
            exportsList.appendChild(hb.el('li', null, hb.t('js.prdErp.exportLine', x.fromDay, x.toDay, hb.fmt.num(x.rowCnt),
                hb.fmt.datetime(x.exportedAt), x.exportedBy)));
        });
        exportsSec.hidden = !d.exports.length;
    }

    /** 조회 패널이 검사를 마친 값으로 부른다 @param {HB.SearchValues} v */
    function search(v) {
        var cond = {kind: String(v.kind || ''), from: String(v.from || ''), to: String(v.to || '')};
        last = null;
        if (excelBtn) { excelBtn.disabled = true; }
        return hb.latest('erp', hb.api(API + '?kind=' + encodeURIComponent(cond.kind) + '&from=' + encodeURIComponent(cond.from)
                + '&to=' + encodeURIComponent(cond.to))).then(function (res) {
            /** @type {ErpPreview} */
            var d = res.data;
            last = cond;
            info.textContent = hb.t(d.truncated ? 'js.prdErp.infoTrunc' : 'js.prdErp.info', hb.fmt.num(d.total), hb.fmt.num(d.rows.length))
                + ' · ' + hb.t('js.prdErp.system', d.erpSystem);
            warn.textContent = d.unmapped ? hb.t('js.prdErp.unmappedWarn', hb.fmt.num(d.unmapped)) : '';
            warn.hidden = !d.unmapped;
            drawExports(d);
            drawTable(cond.kind, d.rows);
            if (excelBtn) { excelBtn.disabled = !d.total; }
            notify('');
        }, /** @param {HB.ApiError} err */ function (err) {
            if (err.code === 'STALE') { return; }
            /** @type {HB.FieldError | undefined} */
            var fe = (err.errors || []).filter(/** @param {HB.FieldError} e */ function (e) { return e.field === 'from' || e.field === 'to'; })[0];
            var period = periodHost.hbDateRange;
            if (fe && fe.field && period) {
                period.showServerError(fe.field, fe.message);
            } else {
                notify(err.message, true);
            }
        });
    }

    if (excelBtn) {
        var exBtn = excelBtn;
        exBtn.addEventListener('click', function () {
            var c = last;
            if (!c) { notify(hb.t('js.prdErp.searchFirst'), true); return; }
            hb.busy(exBtn, function () {
                return hb.download(API + '/export', c);
            }).then(function (r) {
                if (r === null) { return; }
                // 이전 내보내기 목록에 방금 것이 보이게 다시 조회 — 조회가 문구를 비우므로 그 뒤에 알린다
                return sp.search().then(function () { notify(hb.t('js.grid.exported')); });
            }, /** @param {HB.ApiError} err */ function (err) { notify(err.message, true); });
        });
    }
    // 조건을 바꾸면 엑셀은 다시 조회한 뒤에 (미리보기와 다른 파일 방지)
    form.addEventListener('input', function () {
        last = null;
        if (excelBtn) { excelBtn.disabled = true; }
    });

    /** @type {HB.SearchPanel} */
    var sp;
    form.addEventListener('submit', function (e) { e.preventDefault(); });
    hb.loadMessages().then(function () { return hb.loadSession(); }).then(function () {
        hb.page.init();
        sp = hb.searchPanel(form, {onSearch: search});
        hb.codeList('ENTRY_METHOD').then(function (list) {
            list.forEach(function (c) { methods[c.code] = c.name; });
        }, function () { /* 코드명 없으면 코드 그대로 */ });
        sp.search();
    });
})();
