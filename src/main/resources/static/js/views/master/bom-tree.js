// @ts-check
/*
 * BOM 전개 조회 (mst-bom-tree) — P1 3b. 조회 패널 → /api/master/bom-tree → hb.tree(들여쓰기 표).
 * 합산 보기는 레벨이 없는 평면 목록(같은 표, 들여쓰기 없음). 엑셀은 같은 조건으로 서버가 만든다(화면과 같은 결과).
 */
/**
 * @typedef {{noConv?: boolean, [field: string]: any}} BomTreeRow
 * @typedef {{itemCd: string, itemNm: string, rev: string, date: string, qty: number | string, itemUnit: string,
 *   rows: BomTreeRow[], [field: string]: any}} BomTreeResult
 */
(function () {
    'use strict';

    var API = '/api/master/bom-tree';
    var msg = /** @type {HTMLElement} */ (document.getElementById('msg'));
    var search = /** @type {HTMLFormElement} */ (document.getElementById('search'));
    var summary = /** @type {HTMLElement} */ (document.getElementById('summary'));
    /** 조회 조건 패널 @type {HB.SearchPanel} */
    var sp;
    /** @type {HB.Tree | undefined} */
    var tree;
    /** 마지막으로 성공한 조회 조건(엑셀용) @type {HB.SearchValues | null} */
    var last = null;

    /** @param {string | null | undefined} text @param {boolean} [error] */
    function notify(text, error) {
        msg.textContent = text || '';
        msg.classList.toggle('hb-msg-error', !!error);
    }

    /** @param {HB.SearchValues} v */
    function query(v) {
        /** @type {string[]} */
        var q = [];
        ['itemCd', 'date', 'qty', 'view'].forEach(function (k) {
            if (v[k] !== null && v[k] !== undefined && v[k] !== '') { q.push(k + '=' + encodeURIComponent(v[k])); }
        });
        return q.join('&');
    }

    function load() {
        var v = sp.values();
        notify('');
        return hb.latest('tree', hb.api(API + '?' + query(v))).then(function (res) {
            /** @type {BomTreeResult} */
            var r = res.data;
            last = v;
            if (tree) { tree.setRows(r.rows); }
            var flagged = r.rows.filter(function (x) { return x.noConv; }).length;
            summary.textContent = hb.t('js.bomTree.summary', r.itemCd, r.itemNm, r.rev, r.date, String(r.qty), r.itemUnit,
                hb.fmt.num(r.rows.length, 0));
            if (flagged) { notify(hb.t('js.bomTree.noConv', flagged), true); }
        }, function (err) {
            last = null;
            if (tree) { tree.setRows([]); }
            summary.textContent = '';
            notify(bomUi.errorText(err, 'itemCd', v.itemCd), true);
        });
    }

    /** 바로가기 대상 품목 — 고른 행의 자품목, 없으면 조회한(또는 입력한) 모품목 (docs/60 X2) */
    function targetItem() {
        var r = tree ? tree.selected() : null;
        if (r && r.itemCd) { return String(r.itemCd); }
        var v = sp ? sp.values() : null;
        return (last && last.itemCd) || (v && v.itemCd) || '';
    }
    /** @type {HTMLButtonElement} */ (document.getElementById('toWhereBtn')).addEventListener('click', function () {
        var cd = targetItem();
        if (cd) { bomUi.open('mst-bom-where', {itemCd: cd}); } else { notify(hb.t('js.bomTree.searchFirst'), true); }
    });
    /** @type {HTMLButtonElement} */ (document.getElementById('toMgtBtn')).addEventListener('click', function () {
        var cd = targetItem();
        if (cd) { bomUi.open('mst-bom', {itemCd: cd}); } else { notify(hb.t('js.bomTree.searchFirst'), true); }
    });

    // 표가 준비되기 전에 누르면 아무것도 하지 않는다
    /** @type {HTMLButtonElement} */ (document.getElementById('expandBtn')).addEventListener('click', function () { if (tree) { tree.expandAll(); } });
    /** @type {HTMLButtonElement} */ (document.getElementById('collapseBtn')).addEventListener('click', function () { if (tree) { tree.collapseTo(1); } });
    var excelBtn = /** @type {HTMLButtonElement | null} */ (document.getElementById('excelBtn'));   // EXCEL 권한 없으면 없음
    if (excelBtn) {
        var exBtn = excelBtn;
        exBtn.addEventListener('click', function () {
            var cond = last;
            if (!cond) { notify(hb.t('js.bomTree.searchFirst'), true); return; }
            var body = {itemCd: cond.itemCd, date: cond.date || null, qty: cond.qty || null, view: cond.view};
            hb.busy(exBtn, function () {
                return hb.download(API + '/export', body);
            }).then(function () { notify(hb.t('js.grid.exported')); }, function (err) { notify(err.message, true); });
        });
    }

    search.addEventListener('submit', function (e) { e.preventDefault(); });
    hb.loadMessages().then(function () {
        hb.page.init();
        var t = hb.tree('#tree', {onExcel: function () { var b = document.getElementById('excelBtn'); if (b) { b.click(); } }, columnsUrl: API + '/columns', treeField: 'itemCd', flagField: 'noConv',
            flagText: hb.t('js.bomTree.noConvBadge'), emptyText: hb.t('js.bomTree.empty')});
        tree = t;
        sp = hb.searchPanel(search, {onSearch: function () { return load(); }});
        return Promise.all([hb.loadSession(), sp.ready, t.ready]);
    }).then(function () { return bomUi.linked(sp, 'itemCd'); }, function (err) { notify(err && err.message, true); });
})();
