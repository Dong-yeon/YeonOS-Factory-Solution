// @ts-check
/*
 * BOM 역전개 조회 (mst-bom-where) — P1 3b. 이 품목을 쓰는 상위 BOM 을 들여쓰기 표로(최상위만이면 평면 목록).
 */
/**
 * @typedef {{lvl?: number, [field: string]: any}} BomWhereRow
 * @typedef {{itemCd: string, itemNm: string, date: string, allRevs: boolean, topOnly: boolean, llc?: number | null,
 *   rows: BomWhereRow[], [field: string]: any}} BomWhereResult
 */
(function () {
    'use strict';

    var API = '/api/master/bom-where';
    var msg = /** @type {HTMLElement} */ (document.getElementById('msg'));
    var search = /** @type {HTMLFormElement} */ (document.getElementById('search'));
    var summary = /** @type {HTMLElement} */ (document.getElementById('summary'));
    /** 조회 조건 패널 @type {HB.SearchPanel} */
    var sp;
    /** @type {HB.Tree | undefined} */
    var tree;
    /** 마지막으로 성공한 조회 결과(엑셀용) @type {BomWhereResult | null} */
    var last = null;

    /** @param {string | null | undefined} text @param {boolean} [error] */
    function notify(text, error) {
        msg.textContent = text || '';
        msg.classList.toggle('hb-msg-error', !!error);
    }

    function load() {
        var v = sp.values();
        notify('');
        var q = 'itemCd=' + encodeURIComponent(v.itemCd || '') + (v.date ? '&date=' + encodeURIComponent(v.date) : '')
            + (v.allRevs && v.allRevs.length ? '&allRevs=true' : '') + (v.topOnly && v.topOnly.length ? '&topOnly=true' : '');
        return hb.latest('where', hb.api(API + '?' + q)).then(function (res) {
            /** @type {BomWhereResult} */
            var r = res.data;
            last = r;
            if (tree) { tree.setRows(r.rows.map(function (x) { return Object.assign({}, x, {lvl: r.topOnly ? 1 : x.lvl}); })); }
            summary.textContent = hb.t('js.bomWhere.summary', r.itemCd, r.itemNm, r.allRevs ? hb.t('js.bomWhere.allRevs') : r.date,
                hb.fmt.num(r.rows.length, 0)) + (r.llc === null || r.llc === undefined ? '' : ' · ' + hb.t('js.bomWhere.llc', r.llc));
            if (!r.rows.length) { notify(hb.t('js.bomWhere.none', r.itemCd)); }   // 조회 전 빈 표 문구와 구분(docs/60 X5)
        }, function (err) {
            last = null;
            if (tree) { tree.setRows([]); }
            summary.textContent = '';
            notify(bomUi.errorText(err, 'itemCd', v.itemCd), true);
        });
    }

    /** 고른 행의 사용 품목(모품목), 없으면 null @return {string | null} */
    function pickedParent() {
        var r = tree ? tree.selected() : null;
        return r && r.parentCd ? String(r.parentCd) : null;
    }
    /** 바로가기(docs/60 X2·W1): 전개·BOM 관리 = 고른 행의 사용 품목(없으면 조회한 품목), 일괄 대체 = 조회한 품목을 바꿀 자품목으로 */
    /** @type {HTMLButtonElement} */ (document.getElementById('toTreeBtn')).addEventListener('click', function () {
        var cd = pickedParent() || (last && last.itemCd);
        if (cd) { bomUi.open('mst-bom-tree', {itemCd: cd}); } else { notify(hb.t('js.bomTree.searchFirst'), true); }
    });
    /** @type {HTMLButtonElement} */ (document.getElementById('toMgtBtn')).addEventListener('click', function () {
        var cd = pickedParent() || (last && last.itemCd);
        if (cd) { bomUi.open('mst-bom', {itemCd: cd}); } else { notify(hb.t('js.bomTree.searchFirst'), true); }
    });
    /** @type {HTMLButtonElement} */ (document.getElementById('toReplaceBtn')).addEventListener('click', function () {
        if (last && last.itemCd) { bomUi.open('mst-bom-replace', {fromItemCd: last.itemCd}); } else { notify(hb.t('js.bomTree.searchFirst'), true); }
    });

    // 표가 준비되기 전에 누르면 아무것도 하지 않는다
    /** @type {HTMLButtonElement} */ (document.getElementById('expandBtn')).addEventListener('click', function () { if (tree) { tree.expandAll(); } });
    /** @type {HTMLButtonElement} */ (document.getElementById('collapseBtn')).addEventListener('click', function () { if (tree) { tree.collapseTo(1); } });
    var excelBtn = /** @type {HTMLButtonElement | null} */ (document.getElementById('excelBtn'));   // EXCEL 권한 없으면 없음
    if (excelBtn) {
        var exBtn = excelBtn;
        exBtn.addEventListener('click', function () {
            var r = last;
            if (!r) { notify(hb.t('js.bomTree.searchFirst'), true); return; }
            var body = {itemCd: r.itemCd, date: r.allRevs ? null : r.date, allRevs: r.allRevs, topOnly: r.topOnly};
            hb.busy(exBtn, function () {
                return hb.download(API + '/export', body);
            }).then(function () { notify(hb.t('js.grid.exported')); }, function (err) { notify(err.message, true); });
        });
    }

    search.addEventListener('submit', function (e) { e.preventDefault(); });
    hb.loadMessages().then(function () {
        hb.page.init();
        var t = hb.tree('#tree', {onExcel: function () { var b = document.getElementById('excelBtn'); if (b) { b.click(); } }, columnsUrl: API + '/columns', treeField: 'parentCd', flagField: 'cycle',
            emptyText: hb.t('js.bomWhere.empty')});
        tree = t;
        sp = hb.searchPanel(search, {onSearch: function () { return load(); }});
        return Promise.all([hb.loadSession(), sp.ready, t.ready]);
    }).then(function () { return bomUi.linked(sp, 'itemCd'); }, function (err) { notify(err && err.message, true); });
})();
