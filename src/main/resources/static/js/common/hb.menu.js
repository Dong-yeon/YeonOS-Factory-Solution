// @ts-check
/*
 * hb.menu — 우클릭(컨텍스트) 메뉴 공통 부품 + 일반 표용 hb.tableMenu (2026-10-26 사용자: "모든 그리드는 컨텍스트 메뉴가 있어야").
 *   hb.menu.open([{label, run, disabled}, '-', …], x, y);   // '-' = 구분선
 *   hb.tableMenu(hostOrTable, {exportUrl?, params?, actions?, onExcel?});
 * - 모양은 hb.grid 메뉴와 같은 클래스(.hbg-menu·.hbg-menu-item·.hbg-menu-sep) — 한 화면 안 메뉴가 같아 보이게
 * - 한 번에 하나만 열린다. 바깥 클릭·Esc·창 크기 변경·스크롤에서 닫고, ↑↓ 로 항목 이동
 * - hb.tableMenu 는 위임(호스트에 리스너 하나)이라 표를 다시 그려도 그대로 동작한다. 입력칸·버튼·링크 위에서는 브라우저 기본 메뉴
 * - 글자는 화면에 보이는 textContent 그대로(서버 데이터를 HTML 로 해석하지 않음)
 * 의존: hb.js(hb.el·hb.t·hb.download)
 */
(function (global) {
    'use strict';

    /** @type {HTMLDivElement | null} */
    var current = null;

    function close() {
        if (current) { current.remove(); current = null; }
    }

    /** @param {string} text */
    function copyText(text) {
        if (navigator.clipboard && navigator.clipboard.writeText) {
            return navigator.clipboard.writeText(text);
        }
        var ta = document.createElement('textarea');
        ta.value = text;
        document.body.appendChild(ta);
        ta.select();
        try { document.execCommand('copy'); } finally { document.body.removeChild(ta); }
        return Promise.resolve();
    }

    /** @type {HB.Hb['menu']['open']} */
    function open(items, x, y) {
        close();
        var menu = /** @type {HTMLDivElement} */ (global.hb.el('div', 'hbg-menu'));
        menu.setAttribute('role', 'menu');
        var lastSep = true;   // 맨 앞·연속 구분선은 넣지 않는다
        items.forEach(function (it) {
            if (it === '-') {
                if (!lastSep) { menu.appendChild(global.hb.el('hr', 'hbg-menu-sep')); lastSep = true; }
                return;
            }
            var b = /** @type {HTMLButtonElement} */ (global.hb.el('button', 'hbg-menu-item', it.label));
            b.type = 'button';
            b.setAttribute('role', 'menuitem');
            b.disabled = !!it.disabled;
            b.addEventListener('click', function () { close(); it.run(); });
            menu.appendChild(b);
            lastSep = false;
        });
        if (menu.lastElementChild && menu.lastElementChild.tagName === 'HR') { menu.lastElementChild.remove(); }
        menu.addEventListener('keydown', function (e) {
            var list = Array.prototype.filter.call(menu.querySelectorAll('button'),
                function (/** @type {HTMLButtonElement} */ b) { return !b.disabled; });
            var i = list.indexOf(document.activeElement);
            if (e.key === 'ArrowDown') { list[(i + 1) % list.length].focus(); }
            else if (e.key === 'ArrowUp') { list[(i - 1 + list.length) % list.length].focus(); }
            else { return; }
            e.preventDefault();
        });
        document.body.appendChild(menu);
        // 화면 밖으로 나가지 않게(위치는 CSSOM — 인라인 style 속성이 아니라 CSP 가 막지 않는다)
        menu.style.left = Math.max(0, Math.min(x, global.innerWidth - menu.offsetWidth - 4)) + 'px';
        menu.style.top = Math.max(0, Math.min(y, global.innerHeight - menu.offsetHeight - 4)) + 'px';
        current = menu;
        var first = /** @type {HTMLButtonElement | null} */ (menu.querySelector('button:not([disabled])'));
        if (first) { first.focus(); }
    }

    document.addEventListener('click', function (e) {
        if (current && !current.contains(/** @type {Node | null} */ (e.target))) { close(); }
    });
    document.addEventListener('keydown', function (e) { if (e.key === 'Escape') { close(); } });
    global.addEventListener('resize', close);
    global.addEventListener('blur', close);
    document.addEventListener('scroll', close, true);

    /** 칸 글자 — 줄바꿈·탭은 공백 하나로(붙여 넣을 때 칸이 밀리지 않게) @param {Element} cell */
    function cellOf(cell) {
        return (cell.textContent || '').replace(/[\t\r\n]+/g, ' ').trim();
    }

    /** @param {HTMLTableRowElement} tr */
    function rowOf(tr) {
        return Array.prototype.map.call(tr.cells, cellOf).join('\t');
    }

    /** 머리글 + 본문 전체(TSV) — 빈 안내 행(칸 하나에 colspan)은 뺀다 @param {HTMLTableElement} table */
    function tableOf(table) {
        /** @type {string[]} */
        var lines = [];
        Array.prototype.forEach.call(table.rows, function (/** @type {HTMLTableRowElement} */ tr) {
            if (tr.cells.length === 1 && tr.cells[0].colSpan > 1) { return; }
            if (tr.hidden || tr.closest('[hidden]')) { return; }
            lines.push(rowOf(tr));
        });
        return lines.join('\n');
    }

    /** @type {HB.Hb['tableMenu']} */
    function tableMenu(target, options) {
        var found = typeof target === 'string' ? document.querySelector(target) : target;
        if (!(found instanceof HTMLElement)) {
            throw new Error('hb.tableMenu: 붙일 곳이 없음 ' + target);
        }
        var host = found;
        var o = options || {};
        var actions = o.actions || (document.body.getAttribute('data-actions') || '').split(',');
        host.addEventListener('contextmenu', function (e) {
            var t = e.target instanceof Element ? e.target : null;
            if (!t || t.closest('input, select, textarea, button, a[href]')) { return; }
            var table = /** @type {HTMLTableElement | null} */ (t.closest('table'));
            if (!table || !host.contains(table) || table.closest('.hbg')) { return; }   // hb.grid·hb.tree 는 자기 메뉴
            e.preventDefault();
            var td = t.closest('td, th');
            var tr = /** @type {HTMLTableRowElement | null} */ (td ? td.closest('tr') : null);
            var inBody = !!(tr && tr.parentElement && tr.parentElement.tagName !== 'THEAD' && !(tr.cells.length === 1 && tr.cells[0].colSpan > 1));
            /** @type {(HB.MenuItem | '-')[]} */
            var items = [];
            if (td && inBody && tr) {
                var cell = td;
                var row = tr;
                items.push({label: global.hb.t('js.grid.copyCell'), run: function () { copyText(cellOf(cell)); }});
                items.push({label: global.hb.t('js.grid.copyRow'), run: function () { copyText(rowOf(row)); }});
            }
            var tbl = table;
            items.push({label: global.hb.t('js.grid.copyTable'), run: function () { copyText(tableOf(tbl)); }});
            var excel = o.onExcel || (o.exportUrl ? function () {
                return global.hb.download(o.exportUrl || '', o.params ? o.params() : {});
            } : null);
            if (excel && actions.indexOf('EXCEL') >= 0) {
                var run = excel;
                items.push('-');
                items.push({label: global.hb.t('js.grid.excel'), run: function () { run(); }});
            }
            open(items, e.clientX, e.clientY);
        });
    }

    global.hb = global.hb || {};
    global.hb.menu = {open: open, close: close, copyText: copyText};
    global.hb.tableMenu = tableMenu;

    // 템플릿 <body data-table-menu="엑셀 버튼 id(없으면 빈 값)"> 이면 화면 JS 없이 붙인다 — hb.grid 없이 일반 표로 목록을 그리는 화면.
    // 메뉴의 "엑셀"은 그 화면 엑셀 버튼을 대신 누른다(조회 조건 검증·연타 잠금을 버튼 쪽 그대로)
    var body = document.body;
    if (body && body.hasAttribute('data-table-menu')) {
        var btnId = body.getAttribute('data-table-menu') || '';
        tableMenu(body, btnId ? {onExcel: function () {
            var b = /** @type {HTMLButtonElement | null} */ (document.getElementById(btnId));
            if (b && !b.disabled) { b.click(); }
        }} : {});
    }
})(window);
