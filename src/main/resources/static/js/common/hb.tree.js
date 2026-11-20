// @ts-check
/*
 * hb.tree — 들여쓰기 표 (P1 3b, docs/18 6장). BOM 전개·역전개처럼 "정렬된 평면 행 + 레벨" 을 펼침·접기 표로 보여 준다.
 *   var t = hb.tree('#host', {columnsUrl, treeField: 'itemCd', levelField: 'lvl', flagField: 'noConv'});
 *   t.ready.then(...); t.setRows(rows); t.expandAll(); t.collapseTo(1);
 * - 부모 = 바로 앞쪽에서 레벨이 하나 낮은 행(서버가 경로 순서로 보낸다). 들여쓰기는 CSSOM(인라인 style 속성 아님 — CSP)
 * - 키보드(WAI-ARIA treegrid): ↑↓ 행 이동, → 펼치기·첫 자식, ← 접기·부모, Home/End, Space = 펼침 전환
 * - 행은 화면에 보이는 범위(+ 앞뒤 여유)만 DOM 으로 만든다 — 행 높이가 고정(--hbg-row-h)이라 위·아래 빈 행 높이로 스크롤을 맞춘다.
 *   한꺼번에 그리면 16,382행에 13초, 모두 펼치기 15초 동안 멈췄다(QA 3b #7). 행 요소는 처음 보일 때 만들어 재사용한다
 * - beforeSelect(next) → boolean 또는 Promise<boolean>(hb.dialog) — 다른 행으로 옮기기 전 미저장 확인(hb.gate).
 *   답을 기다리는 동안 다른 이동은 무시, 그 사이 setRows 로 행이 바뀌었으면 옮기지 않는다. 화면 코드가 부르는 selectWhere 는 묻지 않는다
 * - 서버 데이터는 textContent 로만. 표 모양은 hb.grid 와 같은 클래스(hbg-*)를 쓴다(편집·정렬·페이지 없음 — 조회 전용)
 * 의존: hb.js(hb.api·hb.el·hb.t·hb.fmt·hb.codes), beforeSelect 를 쓰면 hb.gate.js
 */
(function (global) {
    'use strict';

    var INDENT = 16;     // 레벨당 들여쓰기 px
    var OVERSCAN = 30;   // 보이는 범위 앞뒤로 더 그리는 행 수

    /** 표 안 행 상태 @typedef {{data: HB.Row, lvl: number, hasKids: boolean, open: boolean, shown: boolean, el: HTMLTableRowElement | null}} TreeRow */

    /** @type {HB.Hb['tree']} */
    function tree(target, options) {
        var found = typeof target === 'string' ? document.querySelector(target) : target;
        if (!(found instanceof HTMLElement)) {
            throw new Error('hb.tree: 그릴 곳이 없음 ' + target);
        }
        var host = found;
        /** @type {HB.TreeOptions & {levelField: string}} */
        var o = Object.assign({columns: null, columnsUrl: null, treeField: null, levelField: 'lvl', flagField: null,
            flagText: null, emptyText: null, onSelect: null, dimField: null, beforeSelect: null, onExcel: null}, options || {});
        /** @type {HB.Column[]} */
        var columns = [];
        /** 코드 그룹 → {코드: 이름} @type {Record<string, Record<string, string>>} */
        var codeNames = {};
        /** @type {TreeRow[]} */
        var rows = [];
        /** 보이는(접힌 조상 없는) 행 번호, 순서대로 @type {number[]} */
        var vis = [];
        var focusIdx = -1;
        var rowH = 0;            // 측정한 행 높이(px)
        var drawn = {start: -1, end: -1};
        var frame = 0;
        var restoring = false;   // 다시 그리며 포커스를 되돌리는 중(스크롤 영역 focus 처리 건너뜀)

        var wrap = hb.el('div', 'hbg hb-tree');
        var scroll = hb.el('div', 'hbg-scroll');
        var table = hb.el('table', 'hbg-table');
        table.setAttribute('role', 'treegrid');
        var colgroup = hb.el('colgroup');
        var thead = hb.el('thead');
        var tbody = hb.el('tbody');
        var empty = hb.el('div', 'hbg-overlay');
        table.appendChild(colgroup);
        table.appendChild(thead);
        table.appendChild(tbody);
        scroll.appendChild(table);
        scroll.appendChild(empty);
        wrap.appendChild(scroll);
        host.textContent = '';
        host.appendChild(wrap);

        // ---------- 우클릭 메뉴(hb.menu.js — 2026-10-26 사용자: 모든 그리드에 메뉴) ----------
        /** 한 행의 글자(TSV) — 트리 칸 앞에는 레벨 @param {TreeRow} r */
        function rowTsv(r) {
            return columns.map(function (c) {
                var t = cellText(c, r.data[c.field]).replace(/[\t\r\n]+/g, ' ');
                return c.field === o.treeField ? r.lvl + ' ' + t : t;
            }).join('\t');
        }
        wrap.addEventListener('contextmenu', function (e) {
            if (!hb.menu) { return; }   // hb.menu.js 를 싣지 않은 화면은 브라우저 기본 메뉴
            e.preventDefault();
            var t = e.target instanceof Element ? e.target : null;
            var td = t ? t.closest('td') : null;
            var tr = td ? td.closest('tr[data-index]') : null;
            var r = tr ? rows[Number(tr.getAttribute('data-index'))] : null;
            /** @type {(HB.MenuItem | '-')[]} */
            var items = [];
            if (td && r) {
                var cellTxt = (td.lastElementChild && td.classList.contains('hb-tree-cell') ? td.lastElementChild.textContent : td.textContent) || '';
                var row = r;
                items.push({label: hb.t('js.grid.copyCell'), run: function () { hb.menu.copyText(cellTxt.trim()); }});
                items.push({label: hb.t('js.grid.copyRow'), run: function () { hb.menu.copyText(rowTsv(row)); }});
            }
            items.push({label: hb.t('js.grid.copyTable'), disabled: !rows.length, run: function () {
                hb.menu.copyText([columns.map(function (c) { return c.title; }).join('\t')].concat(rows.map(rowTsv)).join('\n'));
            }});
            items.push('-');
            items.push({label: hb.t('js.grid.expandAll'), disabled: !rows.length, run: function () { api.expandAll(); }});
            items.push({label: hb.t('js.grid.collapseAll'), disabled: !rows.length, run: function () { api.collapseTo(1); }});
            var actions = (document.body.getAttribute('data-actions') || '').split(',');
            if (o.onExcel && actions.indexOf('EXCEL') >= 0) {
                var excel = o.onExcel;
                items.push('-');
                items.push({label: hb.t('js.grid.excel'), run: function () { excel(); }});
            }
            hb.menu.open(items, e.clientX, e.clientY);
        });

        /** @return {Promise<HB.Column[]>} */
        function loadColumns() {
            if (o.columns) { return Promise.resolve(o.columns); }
            return hb.api(o.columnsUrl || '').then(function (res) { return res.list || []; });
        }

        /** 트리 칸에 더하는 폭 — 가장 깊은 레벨의 들여쓰기만큼(깊은 행의 코드가 잘리지 않게, docs/60 D2) */
        var treeExtra = 0;

        /** @param {HB.Column} c */
        function colWidth(c) { return c.width + (c.field === o.treeField ? treeExtra : 0); }

        function header() {
            colgroup.textContent = '';
            thead.textContent = '';
            var tr = hb.el('tr');
            columns.forEach(function (c) {
                var col = hb.el('col');
                col.style.width = colWidth(c) + 'px';
                colgroup.appendChild(col);
                var th = hb.el('th', 'hbg-th hbg-' + c.type.toLowerCase(), c.title);
                th.setAttribute('role', 'columnheader');
                tr.appendChild(th);
            });
            thead.appendChild(tr);
            var total = columns.reduce(function (s, c) { return s + colWidth(c); }, 0);
            table.style.minWidth = total + 'px';
        }

        /** @param {HB.Column} c @param {unknown} v @return {string} */
        function cellText(c, v) {
            if (v === null || v === undefined || v === '') { return ''; }
            switch (c.type) {
            case 'DEC': return hb.fmt.num(v, c.scale, c.minScale);
            case 'INT': return hb.fmt.num(v, 0);
            case 'DATE': return hb.fmt.date(v);
            case 'DATETIME': return hb.fmt.datetime(v);
            case 'YN': return v === true || v === 'Y' ? hb.t('js.common.yes') : v === false || v === 'N' ? hb.t('js.common.no') : String(v);
            case 'CODE': return (codeNames[c.codeGrp || ''] || {})[String(v)] || String(v);
            case 'LABEL': return hb.fmt.label(c.labels, v);
            default: return String(v);
            }
        }

        /** @param {number} i */
        function makeRow(i) {
            var r = rows[i];
            var tr = hb.el('tr');
            tr.setAttribute('role', 'row');
            tr.setAttribute('data-index', String(i));   // 우클릭 메뉴가 행을 찾는다
            tr.setAttribute('aria-level', String(r.lvl));
            tr.tabIndex = -1;
            if (o.flagField && r.data[o.flagField]) { tr.classList.add('hb-tree-flag'); }
            if (o.dimField && r.data[o.dimField]) { tr.classList.add('hb-tree-dim'); }
            columns.forEach(function (c) {
                var td = hb.el('td', 'hbg-' + c.type.toLowerCase());
                td.setAttribute('role', 'gridcell');
                if (c.field === o.treeField) {
                    td.classList.add('hb-tree-cell');
                    td.style.paddingLeft = (6 + (r.lvl - 1) * INDENT) + 'px';
                    var tog = hb.el('span', 'hb-tree-tog', '');
                    tog.setAttribute('aria-hidden', 'true');
                    tog.addEventListener('click', function (e) { e.stopPropagation(); toggle(i); });
                    td.appendChild(tog);
                    td.appendChild(hb.el('span', null, cellText(c, r.data[c.field])));
                    if (o.flagField && r.data[o.flagField] && o.flagText) {
                        td.appendChild(hb.el('span', 'hb-tree-badge', o.flagText));
                    }
                } else {
                    td.textContent = cellText(c, r.data[c.field]);
                }
                tr.appendChild(td);
            });
            tr.addEventListener('click', function () { focus(i); });
            tr.addEventListener('dblclick', function () { toggle(i); });
            r.el = tr;
            return tr;
        }

        /** 행 요소의 펼침 표시·선택 표시를 지금 상태로 */
        /** @param {number} i */
        function syncRow(i) {
            var r = rows[i];
            var el = r.el || makeRow(i);
            if (r.hasKids) {
                el.setAttribute('aria-expanded', r.open ? 'true' : 'false');
                var tog = el.querySelector('.hb-tree-tog');
                if (tog) { tog.textContent = r.open ? '▾' : '▸'; }
            }
            if (i === focusIdx) { el.setAttribute('aria-selected', 'true'); } else { el.removeAttribute('aria-selected'); }
            return el;
        }

        /** @param {number} h */
        function spacer(h) {
            var tr = hb.el('tr', 'hb-tree-spacer');
            tr.setAttribute('aria-hidden', 'true');
            var td = hb.el('td');
            td.colSpan = Math.max(1, columns.length);
            td.style.height = h + 'px';
            td.style.padding = '0';
            td.style.border = '0';
            tr.appendChild(td);
            return tr;
        }

        function measuredRowH() {
            if (!rowH) {
                var v = parseFloat(getComputedStyle(scroll).getPropertyValue('--hbg-row-h'));
                rowH = v > 0 ? v : 32;
            }
            return rowH;
        }

        /** 보이는 범위만 그린다. force 면 범위가 같아도 다시 */
        /** @param {boolean} force */
        function draw(force) {
            var h = measuredRowH();
            var viewRows = Math.ceil((scroll.clientHeight || 600) / h);
            var first = Math.floor(scroll.scrollTop / h);
            var start = Math.max(0, first - OVERSCAN);
            var end = Math.min(vis.length, first + viewRows + OVERSCAN);
            if (!force && start === drawn.start && end === drawn.end) { return; }
            drawn = {start: start, end: end};
            // 포커스가 표 안에 있었으면 다시 그린 뒤 되돌린다 — 행을 떼었다 붙이면 포커스가 body 로 빠져 키보드가 끊긴다
            var hadFocus = scroll.contains(document.activeElement);
            var frag = document.createDocumentFragment();
            if (start > 0) { frag.appendChild(spacer(start * h)); }
            for (var k = start; k < end; k++) {
                var el = syncRow(vis[k]);
                el.setAttribute('aria-rowindex', String(k + 2));     // 머리글 = 1
                frag.appendChild(el);
            }
            if (end < vis.length) { frag.appendChild(spacer((vis.length - end) * h)); }
            tbody.textContent = '';
            tbody.appendChild(frag);
            table.setAttribute('aria-rowcount', String(vis.length + 1));
            if (hadFocus) {
                var cur = focusIdx >= 0 && rows[focusIdx] ? rows[focusIdx].el : null;
                restoring = true;
                (cur && cur.parentNode === tbody ? cur : scroll).focus({preventScroll: true});
                restoring = false;
            }
            // 실제 행 높이가 변수와 다르면(글꼴·확대) 한 번 다시 맞춘다
            var probe = /** @type {HTMLElement | null} */ (tbody.querySelector('tr[role="row"]'));
            if (probe && probe.offsetHeight > 0 && Math.abs(probe.offsetHeight - h) > 0.5) {
                rowH = probe.offsetHeight;
                draw(true);
            }
        }

        scroll.addEventListener('scroll', function () {
            if (frame) { return; }
            frame = requestAnimationFrame(function () { frame = 0; draw(false); });
        });

        function render() {
            tbody.textContent = '';
            drawn = {start: -1, end: -1};
            empty.textContent = o.emptyText || hb.t('js.grid.empty');
            empty.classList.toggle('hbg-show', rows.length === 0);
            focusIdx = -1;
        }

        /** 펼침 상태로 보이기 다시 계산 — 접힌 조상이 있으면 숨김. DOM 은 보이는 범위만 다시 그린다 */
        function applyVisibility() {
            var hiddenBelow = Infinity;    // 이 레벨보다 깊은 행은 숨김
            vis = [];
            rows.forEach(function (r, i) {
                if (r.lvl <= hiddenBelow) { hiddenBelow = Infinity; }
                r.shown = r.lvl <= hiddenBelow;
                if (r.shown) {
                    vis.push(i);
                    if (r.hasKids && !r.open) { hiddenBelow = r.lvl; }
                }
            });
            draw(true);
        }

        /** @param {number} i @param {boolean} [open] */
        function toggle(i, open) {
            var r = rows[i];
            if (!r || !r.hasKids) { return; }
            r.open = open === undefined ? !r.open : open;
            applyVisibility();
        }

        /** i 행이 그려진 범위에 들도록 스크롤 */
        /** @param {number} i */
        function reveal(i) {
            var k = vis.indexOf(i);
            if (k < 0) { return; }
            var h = measuredRowH();
            var head = thead.offsetHeight || h;
            var top = k * h;
            if (top < scroll.scrollTop) {
                scroll.scrollTop = top;
            } else if (top + h + head > scroll.scrollTop + scroll.clientHeight) {
                scroll.scrollTop = top + h + head - scroll.clientHeight;
            }
            draw(false);
        }

        var asking = false;   // beforeSelect 가 Promise 를 돌려줘 답을 기다리는 중

        /** 다른 행으로 옮길 때만 beforeSelect 로 묻는다 @param {number} i */
        function focus(i) {
            if (i < 0 || i >= rows.length || !rows[i].shown) { return; }
            if (asking) { return; }
            if (i === focusIdx || !o.beforeSelect) { moveTo(i); return; }
            var token = rows;
            var r = hb.gate(o.beforeSelect(rows[i].data), function () { if (token === rows) { moveTo(i); } });
            if (typeof r !== 'boolean') {
                asking = true;
                r.then(function () { asking = false; });
            }
        }

        /** @param {number} i */
        function moveTo(i) {
            var prev = focusIdx;
            focusIdx = i;
            var prevEl = prev >= 0 && rows[prev] ? rows[prev].el : null;
            if (prevEl) { prevEl.removeAttribute('aria-selected'); }
            reveal(i);
            var el = syncRow(i);
            el.focus({preventScroll: true});
            if (prev !== i && o.onSelect) { o.onSelect(rows[i].data); }
        }

        /** @param {number} i @param {number} dir */
        function visibleStep(i, dir) {
            for (var j = i + dir; j >= 0 && j < rows.length; j += dir) {
                if (rows[j].shown) { return j; }
            }
            return i;
        }

        /** @param {number} i */
        function parentOf(i) {
            for (var j = i - 1; j >= 0; j--) {
                if (rows[j].lvl < rows[i].lvl) { return j; }
            }
            return -1;
        }

        // 키는 스크롤 영역에서 받는다(포커스가 표 영역 자체에 있든 행에 있든)
        scroll.addEventListener('keydown', function (e) {
            if (!rows.length) { return; }
            if (focusIdx < 0) {
                if (['ArrowDown', 'ArrowUp', 'Home', 'End'].indexOf(e.key) >= 0) { focus(0); e.preventDefault(); }
                return;
            }
            var r = rows[focusIdx];
            switch (e.key) {
            case 'ArrowDown': focus(visibleStep(focusIdx, 1)); break;
            case 'ArrowUp': focus(visibleStep(focusIdx, -1)); break;
            case 'ArrowRight':
                if (r.hasKids && !r.open) { toggle(focusIdx, true); } else if (r.hasKids) { focus(focusIdx + 1); }
                break;
            case 'ArrowLeft':
                if (r.hasKids && r.open) { toggle(focusIdx, false); } else { focus(parentOf(focusIdx)); }
                break;
            case 'Home': focus(0); break;
            case 'End': focus(visibleStep(rows.length, -1)); break;
            case ' ': toggle(focusIdx); break;
            default: return;
            }
            e.preventDefault();
        });
        // 표에 키보드 포커스가 들어오면 첫 행부터
        scroll.tabIndex = 0;
        scroll.addEventListener('focus', function () {
            if (restoring) { return; }
            if (rows.length) { setTimeout(function () { focus(focusIdx < 0 || !rows[focusIdx].shown ? 0 : focusIdx); }, 0); }
        });

        var ready = loadColumns().then(function (cols) {
            columns = cols;
            var grps = cols.map(function (c) { return c.codeGrp || ''; }).filter(function (g) { return g; });
            return Promise.all(grps.map(function (g) {
                return hb.codeNames(g).then(function (m) { codeNames[g] = m; });   // 사용 중지 코드도(X4)
            }));
        }).then(function () {
            header();
            // 열을 받기 전에 setRows 가 불렸으면(조회 패널이 먼저 준비돼 Enter) 칸 없이 만든 행 요소를 버리고 다시 그린다
            var pending = rows.length > 0;
            rows.forEach(function (r) { r.el = null; });
            render();
            if (pending) { applyVisibility(); }
            return api;
        });

        /** @type {HB.Tree} */
        var api = {
            ready: ready,
            setRows: function (/** @type {HB.Row[] | null | undefined} */ data) {
                var lv = o.levelField;
                rows = (data || []).map(function (d, i, all) {
                    var next = all[i + 1];
                    var lvl = Number(d[lv]) || 1;
                    return {data: d, lvl: lvl, hasKids: !!next && Number(next[lv]) > lvl, open: true, shown: true, el: null};
                });
                var deepest = rows.reduce(function (m, r) { return Math.max(m, r.lvl); }, 1);
                var extra = o.treeField ? (deepest - 1) * INDENT : 0;
                if (extra !== treeExtra) {
                    treeExtra = extra;
                    if (columns.length) { header(); }   // 칸을 아직 못 받았으면 받을 때 header() 가 이 폭으로 그린다
                }
                render();
                applyVisibility();
            },
            expandAll: function () { rows.forEach(function (r) { r.open = true; }); applyVisibility(); },
            /** 이 레벨까지만 보이게(그 레벨의 행은 접힘) */
            collapseTo: function (/** @type {number} */ level) { rows.forEach(function (r) { r.open = r.lvl < level; }); applyVisibility(); },
            visibleCount: function () { return vis.length; },
            /** 조건에 맞는 첫 행을 고른다(접힌 조상은 펼친다). 없으면 false */
            selectWhere: function (/** @type {(row: HB.Row) => boolean} */ pred) {
                for (var i = 0; i < rows.length; i++) {
                    if (pred(rows[i].data)) {
                        for (var j = i - 1, lv = rows[i].lvl; j >= 0 && lv > 1; j--) {
                            if (rows[j].lvl < lv) { rows[j].open = true; lv = rows[j].lvl; }
                        }
                        applyVisibility();
                        focusIdx = -1;
                        moveTo(i);
                        return true;
                    }
                }
                return false;
            },
            selected: function () { return focusIdx >= 0 && rows[focusIdx] ? rows[focusIdx].data : null; },
            columns: function () { return columns; }
        };
        return api;
    }

    global.hb = global.hb || {};
    global.hb.tree = tree;
})(window);
