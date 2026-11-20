// @ts-check
/*
 * hb.grid — 자체 제작 조회 그리드 (docs/10 11장 H1, docs/09 4장). 라이브러리 없음.
 *
 *   var g = hb.grid(container, {
 *     progCd: 'mst-cust', id: 'main', key: 'custId',
 *     url: '/api/master/customers', columnsUrl: '/api/master/customers/columns',
 *     params: function () { return {custNm: ...}; },   // 조회 조건
 *     filters: {custType: 'custType'},                  // "이 값으로 필터" 가능한 필드 → 조회 파라미터 이름
 *     exportUrl: '/api/master/customers/export',        // 없으면 엑셀 메뉴 없음
 *     auditTable: 'mst_cust',                           // 없으면 변경 이력 메뉴 없음
 *     actions: ['SEARCH','EXCEL'],                      // 페이지 모델의 권한 액션 (메뉴 노출용, 판정은 서버)
 *     onSelect: function (row) {}, beforeSelect: function (next) { return true; },  // Promise<boolean> 도 됨(hb.dialog)
 *     onFilter: function (param, value) {},             // 화면 조회 조건에 값을 넣는다
 *     status: {woStatus: {PLAN: 'neutral', RUN: 'info', DONE: 'success'}},   // 상태 칸 = hb-status 배지(값 → 톤 표 또는 함수)
 *     rowClass: function (row) { return row.useYn === 'N' ? 'hb-tree-dim' : null; },  // 행 의미 클래스(hb-row-*·hb-tree-* 만)
 *     checkable: true, onCheck: function (rows) {}      // 행 체크박스(여러 행 고르기) — g.getChecked() / g.clearChecked()
 *     checkableRow: function (row) { return row.status === 'OPEN'; },   // 체크할 수 있는 행만(없으면 모든 행)
 *     emptyText: {validMonths: hb.t('js.qual.noExpiry')},               // 빈 칸에 보일 글자(화면 표시만)
 *   });
 *   g.reload(resetPage) / g.selected() / g.selectById(id) / g.destroy() / g.ready (Promise)
 *
 * 규칙 (docs/10 3.1)
 * - DOM 은 createElement + textContent 만. 스타일은 클래스 + CSSOM(style.width·style.left) 만 — CSP style-src 'self'
 * - 서버 페이징·정렬(헤더 클릭, Shift+클릭 다중 정렬 최대 3), 요청 순번으로 늦은 응답 폐기
 * - 헤더 고정·첫 열 고정은 CSS sticky. 열 폭 드래그, 열 숨기기(메뉴). 상태는 "화면 설정 저장" 때만 서버에
 * - 열 폭(docs/15 R6): 넓으면 flex 열만 남는 폭을 나눠 갖는다(없으면 전체 비례). 좁으면 가로 스크롤 +
 *   메뉴 "열 맞춤" = priority 3 → 2 순서로 숨겨 보이는 폭에 맞춤(1 은 숨기지 않음, 저장하지 않음)
 * - 선택은 클릭·↑↓·Home·End (키보드로도 상세가 바뀐다), 마스터 이동 전 beforeSelect 로 미저장 확인.
 *   beforeSelect 가 Promise 를 돌려주면(hb.dialog.confirm) 답을 기다리는 동안 다른 이동·페이지 넘김은 무시하고,
 *   그 사이 다시 조회됐으면 이동하지 않는다 (hb.gate)
 * - 상태 칸(status): 글자는 다른 칸과 같은 cellText(코드 이름 등), 그 글자로 hb-status 배지를 그린다 — textContent 만,
 *   모양 기호는 CSS ::before 라 칸 복사·행 복사·엑셀(서버)은 글자 그대로
 * - 행 체크박스(checkable): 맨 앞 고정 칸. 고르기(선택 행·상세)와 따로 — 체크는 쪽을 넘겨도 key 로 남고, 새 조회(reload(true))면 비운다.
 *   머리 칸 = 이 쪽 모두(일부면 indeterminate), Space = 고른 행 체크. 엑셀·행 복사에는 들어가지 않는다
 *   checkableRow(row) 가 false 인 행은 체크칸이 비활성이고 머리 칸 "모두"·Space 도 건너뛴다. 머리 칸 상태도 체크할 수 있는 행만 센다.
 *   체크해 둔 행이 다시 읽었을 때 못 고르는 행이 되면(저장 뒤 상태 바뀜) 체크에서 빠진다 — 화면 표시일 뿐, 막는 것은 서버
 * - 빈 칸 글자(emptyText): 칸 글자를 먼저 만들고(null·undefined → 빈 글자, INT·DEC·DATE·DATETIME → hb.fmt, TEXT → format(값, 행)
 *   — null 이면 format 은 부르지 않는다, 지금과 같음) 그 결과가 빈 글자일 때만 emptyText[field] 를 보인다. 모든 칸 유형에 같고,
 *   옵션이 없거나 그 필드가 없으면 지금처럼 빈 칸. 화면 표시만 — 칸 복사·행 복사·엑셀(서버)에는 빈 글자 그대로
 * - 행 클래스(rowClass): page.css 의 행 의미 클래스(hb-tree-dim 흐리게·hb-tree-flag 강조·hb-row-warn 주의 …)만 —
 *   'hb-row-'·'hb-tree-' 로 시작하는 이름만 붙이고 그 밖 이름은 버린다(화면이 색을 따로 만들지 않게)
 */
(function (global) {
    'use strict';

    var PAGE_SIZES = [20, 50, 100, 200, 500];
    var MAX_SORTS = 3;
    var MIN_WIDTH = 40;
    var CHECK_W = 36;   // 행 체크박스 칸 폭(px)

    /** @param {{field: string}[] | null} cols */
    function hashFields(cols) {
        // FNV-1a 32bit — 열 정의(필드 순서)가 바뀌었는지 판단용
        var s = (cols || []).map(function (c) { return c.field; }).join('|');
        var h = 0x811c9dc5;
        for (var i = 0; i < s.length; i++) {
            h ^= s.charCodeAt(i);
            h = Math.imul(h, 0x01000193) >>> 0;
        }
        return ('0000000' + h.toString(16)).slice(-8);
    }

    /**
     * 상태 칸 톤 — 표(값 → 톤) 또는 함수(값, 행). 빈 값·표에 없는 값은 null(배지 없이 글자만)
     * @param {HB.GridStatusSpec | undefined} spec @param {unknown} v @param {HB.Row} row
     * @return {HB.StatusTone | null}
     */
    function toneOf(spec, v, row) {
        if (!spec || v === null || v === undefined || v === '') { return null; }
        var t = typeof spec === 'function' ? spec(v, row) : spec[String(v)];
        return t || null;
    }

    var ROW_CLASS = /^hb-(row|tree)-[a-z0-9-]+$/;
    /**
     * 행 클래스 — 'hb-row-*'·'hb-tree-*' 만(공백으로 여러 개·배열 가능), 그 밖은 버린다
     * @param {HB.GridOptions['rowClass']} fn @param {HB.Row} row @return {string[]}
     */
    function rowClasses(fn, row) {
        if (!fn) { return []; }
        var out = fn(row);
        var list = Array.isArray(out) ? out : (out ? String(out).split(/\s+/) : []);
        return list.filter(function (c) { return ROW_CLASS.test(c); });
    }

    /**
     * TEXT 칸 글자 — 화면 format(값, 행)이 있으면 그 결과, 없으면 값 그대로. 빈 값은 format 없이 빈 글자.
     * 두 번째 인자(행 전체)로 여러 필드를 합친 글자를 만든다("1개월 · 달력 고정") — 한 인자 함수는 그대로 동작
     * @param {((v: any, row: HB.Row) => unknown) | undefined} fn @param {unknown} v @param {HB.Row} row @return {string}
     */
    function textOf(fn, v, row) {
        if (v === null || v === undefined) { return ''; }
        if (!fn) { return String(v); }
        var out = fn(v, row);
        return out === null || out === undefined ? '' : String(out);
    }

    /** 체크할 수 있는 행인가 — checkableRow 가 없으면 모든 행 @param {((row: HB.Row) => boolean) | undefined} fn @param {HB.Row} row */
    function canCheck(fn, row) { return !fn || !!fn(row); }

    /**
     * 머리 체크박스 상태 — 이 쪽의 체크할 수 있는 행이 모두 체크면 all, 하나도 없으면 none(빈 쪽·고를 수 있는 행 없음 포함), 그 사이 some
     * @param {HB.Row[]} rows @param {string} key @param {(k: string) => boolean} has
     * @param {(row: HB.Row) => boolean} [can] 체크할 수 있는 행(없으면 모두) @return {'none' | 'some' | 'all'}
     */
    function pageCheckState(rows, key, has, can) {
        var open = can ? rows.filter(can) : rows;
        var n = open.filter(function (r) { return has(String(r[key])); }).length;
        return n === 0 ? 'none' : n === open.length ? 'all' : 'some';
    }

    /**
     * 빈 칸에 보일 글자 — 칸 글자(text)가 빈 글자이고 emptyText 에 그 필드가 있을 때만 그 글자, 아니면 null(빈 칸 그대로)
     * @param {Record<string, string> | undefined} map @param {string} field @param {string} text @return {string | null}
     */
    function emptyOf(map, field, text) {
        if (text !== '' || !map || !Object.prototype.hasOwnProperty.call(map, field)) { return null; }
        var e = map[field];
        return e === null || e === undefined || e === '' ? null : String(e);
    }

    if (typeof module === 'object' && module.exports) {
        module.exports = {hashFields: hashFields, toneOf: toneOf, rowClasses: rowClasses, textOf: textOf, pageCheckState: pageCheckState,
            canCheck: canCheck, emptyOf: emptyOf};
        return;
    }

    /** @param {string} text @return {Promise<void>} */
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

    /**
     * 정렬 하나 @typedef {{field: string, desc: boolean}} Sort
     * 저장된 화면 설정(서버 sys_grid_state) @typedef {{cols?: {f: string, w?: number, h?: boolean}[], sort?: {f: string, d?: boolean}[], pageSize?: number}} SavedState
     */

    /**
     * @param {string | HTMLElement} container
     * @param {HB.GridOptions} opt
     * @return {HB.Grid}
     */
    function grid(container, opt) {
        var found = typeof container === 'string' ? document.querySelector(container) : container;
        if (!(found instanceof HTMLElement)) {
            throw new Error('hb.grid: 그릴 곳이 없음 ' + container);
        }
        var root = found;
        var actions = opt.actions || [];
        /**
         * @type {{columns: HB.GridColumn[], rows: HB.Row[], total: number | null, page: number, pageSize: number,
         *   sorts: Sort[], selected: number, seq: number, codeMaps: Record<string, Record<string, string>>, fit: boolean}}
         */
        var state = {
            columns: [],        // [{field,title,type,scale,codeGrp,width,sortable,exportable,priority,minWidth,flex,hidden,fitHidden,noFill}]
            rows: [], total: null, page: 1, pageSize: 50, sorts: [], selected: -1, seq: 0, codeMaps: {},
            fit: false          // "열 맞춤" 켜짐
        };
        /** @type {HB.GridColumn[]} */
        var defaultColumns = [];
        var checkable = !!opt.checkable;
        var checkW = checkable ? CHECK_W : 0;
        /** @param {HB.Row} row */
        function checkOk(row) { return canCheck(opt.checkableRow, row); }
        /** 체크한 행 key → 행(넣은 순서) @type {Map<string, HB.Row>} */
        var checked = new Map();
        /** @type {HTMLInputElement | null} */
        var checkAllBox = null;

        // ---------- 뼈대 ----------
        root.textContent = '';
        root.classList.add('hbg');
        var scroll = hb.el('div', 'hbg-scroll');
        scroll.tabIndex = 0;
        scroll.setAttribute('role', 'grid');
        scroll.setAttribute('aria-readonly', 'true');
        var table = hb.el('table', 'hbg-table');
        var colgroup = hb.el('colgroup');
        var thead = hb.el('thead');
        var tbody = hb.el('tbody');
        table.appendChild(colgroup);
        table.appendChild(thead);
        table.appendChild(tbody);
        scroll.appendChild(table);
        var overlay = hb.el('div', 'hbg-overlay');
        overlay.setAttribute('role', 'status');
        scroll.appendChild(overlay);
        var foot = hb.el('div', 'hbg-foot');
        root.appendChild(scroll);
        root.appendChild(foot);

        // ---------- 열 ----------
        function visibleColumns() {
            return state.columns.filter(function (c) { return !c.hidden && !c.fitHidden; });
        }

        /** 보이는 열의 실제 폭: 남는 폭은 flex 열(사용자가 폭을 바꾸지 않은 것)에만 폭 비율로 */
        /** @param {HB.GridColumn[]} vis */
        function effectiveWidths(vis) {
            var widths = vis.map(function (c) { return c.width; });
            var sum = widths.reduce(function (a, b) { return a + b; }, 0);
            var extra = scroll.clientWidth - checkW - sum;
            /** @type {number[]} */
            var flexIdx = [];
            vis.forEach(function (c, i) { if (c.flex && !c.noFill) { flexIdx.push(i); } });
            if (extra > 0 && flexIdx.length) {
                var base = flexIdx.reduce(function (a, i) { return a + widths[i]; }, 0);
                var given = 0;
                flexIdx.forEach(function (i, k) {
                    var add = k === flexIdx.length - 1 ? extra - given : Math.floor(extra * widths[i] / base);
                    widths[i] += add;
                    given += add;
                });
            }
            return widths;
        }

        function applyWidths() {
            var left = 0;
            var vis = visibleColumns();
            var widths = effectiveWidths(vis);
            var cols = colgroup.querySelectorAll('col');
            cols.forEach(function (col, i) {
                col.style.width = (checkable ? (i === 0 ? checkW : widths[i - 1]) : widths[i]) + 'px';
            });
            left = checkW;   // 체크박스 칸이 있으면 첫 데이터 열은 그 뒤에 고정
            // 첫 열 고정: sticky left — 헤더·본문 모두
            var firstWidth = vis.length ? vis[0].width : 0;
            /** @type {NodeListOf<HTMLElement>} */ (root.querySelectorAll('.hbg-locked')).forEach(function (cell) {
                cell.style.left = left + 'px';
            });
            // 열 합은 최소 폭 — flex 열이 없으면 CSS width:100% 로 남는 폭을 열 비율대로 나눈다(docs/15 4.6)
            table.style.minWidth = checkW + widths.reduce(function (a, b) { return a + b; }, 0) + 'px';
            return firstWidth;
        }

        /** 열 맞춤: 보이는 폭에 맞을 때까지 priority 3 → 2 를 오른쪽부터 숨긴다. @return 바뀌었는지 */
        function refit() {
            var before = state.columns.map(function (c) { return !!c.fitHidden; }).join();
            state.columns.forEach(function (c) { c.fitHidden = false; });
            if (state.fit) {
                var avail = scroll.clientWidth;
                var total = function () { return visibleColumns().reduce(function (a, c) { return a + c.width; }, 0); };
                [3, 2].forEach(function (p) {
                    for (var i = state.columns.length - 1; i >= 0 && total() > avail; i--) {
                        var c = state.columns[i];
                        if (!c.hidden && c.priority === p && visibleColumns().length > 1) { c.fitHidden = true; }
                    }
                });
            }
            return before !== state.columns.map(function (c) { return !!c.fitHidden; }).join();
        }

        function fitHiddenCount() {
            return state.columns.filter(function (c) { return c.fitHidden; }).length;
        }

        function toggleFit() {
            state.fit = !state.fit;
            refit();
            renderHeader();
            renderBody();
        }

        function renderHeader() {
            colgroup.textContent = '';
            thead.textContent = '';
            var tr = hb.el('tr');
            if (checkable) {
                colgroup.appendChild(hb.el('col'));
                var cth = hb.el('th', 'hbg-check');
                cth.setAttribute('role', 'columnheader');
                var all = /** @type {HTMLInputElement} */ (hb.el('input'));
                all.type = 'checkbox';
                all.setAttribute('aria-label', hb.t('js.grid.checkAll'));
                all.addEventListener('change', function () {
                    state.rows.filter(checkOk).forEach(function (r) {
                        if (all.checked) { checked.set(String(r[opt.key]), r); } else { checked.delete(String(r[opt.key])); }
                    });
                    syncChecks();
                });
                cth.appendChild(all);
                checkAllBox = all;
                tr.appendChild(cth);
            }
            visibleColumns().forEach(function (c, i) {
                colgroup.appendChild(hb.el('col'));
                var th = hb.el('th', 'hbg-th hbg-' + c.type.toLowerCase() + (i === 0 ? ' hbg-locked' : ''));
                th.setAttribute('role', 'columnheader');
                th.setAttribute('data-field', c.field);
                var label = hb.el('span', 'hbg-th-label', c.title);
                th.appendChild(label);
                var si = indexOfSort(c.field);
                if (c.sortable) {
                    th.classList.add('hbg-sortable');
                    th.setAttribute('aria-sort', si < 0 ? 'none' : (state.sorts[si].desc ? 'descending' : 'ascending'));
                    if (si >= 0) {
                        th.appendChild(hb.el('span', 'hbg-sort-mark',
                                (state.sorts[si].desc ? '▼' : '▲') + (state.sorts.length > 1 ? (si + 1) : '')));
                    }
                    th.addEventListener('click', function (e) {
                        if (e.target instanceof Element && e.target.classList.contains('hbg-resize')) { return; }
                        toggleSort(c.field, e.shiftKey);
                    });
                }
                var handle = hb.el('span', 'hbg-resize');
                handle.setAttribute('aria-hidden', 'true');
                handle.addEventListener('mousedown', function (e) { startResize(e, c); });
                th.appendChild(handle);
                tr.appendChild(th);
            });
            thead.appendChild(tr);
            fitTitles(tr);
            applyWidths();
        }

        /**
         * 열 제목이 "비밀번호 변경…" 처럼 잘리지 않게 — 제목 글자 폭 + 칸 여백(+ 정렬 표시 자리)을 열 폭·조절 최소 폭의 하한으로.
         * 정렬 표시는 아직 없어도 자리를 잡아 둔다(누를 때마다 폭이 바뀌지 않게). 표가 숨겨져 있으면(폭 0) 건너뛴다.
         * @param {HTMLElement} tr
         */
        function fitTitles(tr) {
            var ths = tr.querySelectorAll('th.hbg-th');
            if (!ths.length || !(/** @type {HTMLElement} */ (ths[0])).offsetWidth) { return; }
            var markW = 0;
            visibleColumns().forEach(function (c, i) {
                var th = /** @type {HTMLElement} */ (ths[i]);
                var label = /** @type {HTMLElement} */ (th.querySelector('.hbg-th-label'));
                var cs = getComputedStyle(th);
                var need = label.getBoundingClientRect().width + parseFloat(cs.paddingLeft) + parseFloat(cs.paddingRight)
                    + parseFloat(cs.borderRightWidth);
                if (c.sortable) {
                    if (!markW) {
                        var probe = hb.el('span', 'hbg-sort-mark', '▼9');
                        th.appendChild(probe);
                        markW = probe.getBoundingClientRect().width + parseFloat(getComputedStyle(probe).marginLeft);
                        th.removeChild(probe);
                    }
                    need += markW;
                }
                need = Math.ceil(need);
                if (c.width < need) { c.width = need; }
                if ((c.minWidth || MIN_WIDTH) < need) { c.minWidth = need; }
            });
        }

        /** @param {MouseEvent} e @param {HB.GridColumn} c */
        function startResize(e, c) {
            e.preventDefault();
            e.stopPropagation();
            var startX = e.clientX;
            var th = e.target instanceof Element ? e.target.closest('th') : null;
            var startW = th ? th.getBoundingClientRect().width : c.width;   // 보이는 폭에서 시작(flex 로 늘어난 폭 포함)
            c.noFill = true;                                                   // 사용자가 정한 폭은 채우기로 다시 늘리지 않는다
            /** @param {MouseEvent} ev */
            function move(ev) {
                c.width = Math.round(Math.max(c.minWidth || MIN_WIDTH, Math.min(1000, startW + ev.clientX - startX)));
                applyWidths();
            }
            function up() {
                document.removeEventListener('mousemove', move);
                document.removeEventListener('mouseup', up);
            }
            document.addEventListener('mousemove', move);
            document.addEventListener('mouseup', up);
        }

        // ---------- 정렬 ----------
        /** @param {string} field */
        function indexOfSort(field) {
            for (var i = 0; i < state.sorts.length; i++) {
                if (state.sorts[i].field === field) { return i; }
            }
            return -1;
        }

        /** @param {string} field @param {boolean} multi */
        function toggleSort(field, multi) {
            var i = indexOfSort(field);
            var cur = i >= 0 ? state.sorts[i] : null;
            var next = !cur ? {field: field, desc: false} : (!cur.desc ? {field: field, desc: true} : null);
            if (!multi) {
                state.sorts = next ? [next] : [];
            } else if (cur) {
                if (next) { state.sorts[i] = next; } else { state.sorts.splice(i, 1); }
            } else if (next && state.sorts.length < MAX_SORTS) {
                state.sorts.push(next);
            }
            state.page = 1;
            renderHeader();
            load();
        }

        // ---------- 셀 ----------
        /** @param {HB.GridColumn} c @param {unknown} v @param {HB.Row} row @return {string} */
        function cellText(c, v, row) {
            if (v === null || v === undefined) { return ''; }
            switch (c.type) {
                case 'INT': return hb.fmt.num(v, 0);
                case 'DEC': return hb.fmt.num(v, c.scale || 0, c.minScale);
                case 'DATE': return hb.fmt.date(v);
                case 'DATETIME': return hb.fmt.datetime(v);
                case 'CODE': return (state.codeMaps[c.field] || {})[String(v)] || String(v);
                case 'LABEL': return hb.fmt.label(c.labels, v);
                case 'YN': return v === 'Y' ? hb.t('js.yn.Y') : v === 'N' ? hb.t('js.yn.N') : String(v);
                default: return textOf(opt.format ? opt.format[c.field] : undefined, v, row);
            }
        }

        function renderBody() {
            tbody.textContent = '';
            var vis = visibleColumns();
            state.rows.forEach(function (row, r) {
                var tr = hb.el('tr', 'hbg-row');
                rowClasses(opt.rowClass, row).forEach(function (k) { tr.classList.add(k); });
                tr.setAttribute('aria-rowindex', String((state.page - 1) * state.pageSize + r + 2));
                tr.setAttribute('aria-selected', r === state.selected ? 'true' : 'false');
                tr.setAttribute('data-index', String(r));
                if (checkable) {
                    var ctd = hb.el('td', 'hbg-check');
                    var box = /** @type {HTMLInputElement} */ (hb.el('input'));
                    box.type = 'checkbox';
                    box.tabIndex = -1;   // 키보드는 Space(고른 행) — 칸마다 탭 멈춤을 만들지 않는다
                    box.setAttribute('aria-label', hb.t('js.grid.checkRow'));
                    box.checked = checked.has(String(row[opt.key]));
                    box.disabled = !checkOk(row);
                    box.addEventListener('change', function () { toggleCheck(row, box.checked); });
                    ctd.appendChild(box);
                    tr.appendChild(ctd);
                }
                vis.forEach(function (c, i) {
                    var text = cellText(c, row[c.field], row);
                    var empty = emptyOf(opt.emptyText, c.field, text);
                    if (empty !== null) { text = empty; }
                    var tone = opt.status ? toneOf(opt.status[c.field], row[c.field], row) : null;
                    var td = hb.el('td', 'hbg-' + c.type.toLowerCase() + (i === 0 ? ' hbg-locked' : '') + (tone ? ' hbg-status' : ''),
                            tone ? null : text);
                    if (tone) { td.appendChild(hb.status(tone, text)); }
                    td.setAttribute('data-field', c.field);
                    if (empty !== null) { td.setAttribute('data-empty-text', ''); }   // 칸 복사는 빈 글자로
                    tr.appendChild(td);
                });
                tbody.appendChild(tr);
            });
            overlay.textContent = state.rows.length ? '' : hb.t('js.grid.empty');
            overlay.classList.toggle('hbg-show', !state.rows.length);
            applyWidths();
            renderFoot();
            syncCheckAll();
        }

        // ---------- 행 체크박스 ----------
        /** @param {HB.Row} row @param {boolean} on */
        function toggleCheck(row, on) {
            if (on && !checkOk(row)) { return; }
            if (on) { checked.set(String(row[opt.key]), row); } else { checked.delete(String(row[opt.key])); }
            syncChecks();
        }

        /** 머리 칸 상태(이 쪽) */
        function syncCheckAll() {
            if (!checkAllBox) { return; }
            var st = pageCheckState(state.rows, opt.key, function (k) { return checked.has(k); }, checkOk);
            checkAllBox.checked = st === 'all';
            checkAllBox.indeterminate = st === 'some';
            checkAllBox.disabled = !state.rows.some(checkOk);
        }

        /** 칸·머리·건수를 체크 상태에 맞추고 화면에 알린다 */
        function syncChecks() {
            tbody.querySelectorAll('tr.hbg-row').forEach(function (tr) {
                var row = state.rows[Number(tr.getAttribute('data-index'))];
                var box = /** @type {HTMLInputElement | null} */ (tr.querySelector('td.hbg-check input'));
                if (box && row) { box.checked = checked.has(String(row[opt.key])); }
            });
            syncCheckAll();
            renderFoot();
            if (opt.onCheck) { opt.onCheck(Array.from(checked.values())); }
        }

        // ---------- 페이지 ----------
        function renderFoot() {
            foot.textContent = '';
            var pages = state.total === null ? null : Math.max(1, Math.ceil(state.total / state.pageSize));
            /** @param {string} label @param {string} title @param {number} page @param {boolean} disabled */
            function btn(label, title, page, disabled) {
                var b = hb.el('button', 'hbg-pg', label);
                b.type = 'button';
                b.title = title;
                b.setAttribute('aria-label', title);
                b.disabled = disabled;
                b.addEventListener('click', function () { goPage(page); });
                return b;
            }
            foot.appendChild(btn('«', hb.t('js.grid.first'), 1, state.page <= 1));
            foot.appendChild(btn('‹', hb.t('js.grid.prev'), state.page - 1, state.page <= 1));
            foot.appendChild(hb.el('span', 'hbg-pageinfo', pages ? state.page + ' / ' + pages : String(state.page)));
            foot.appendChild(btn('›', hb.t('js.grid.next'), state.page + 1, pages !== null && state.page >= pages));
            foot.appendChild(btn('»', hb.t('js.grid.last'), pages || state.page, pages === null || state.page >= pages));
            var size = hb.el('select', 'hbg-size');
            size.setAttribute('aria-label', hb.t('js.grid.pageSize'));
            PAGE_SIZES.forEach(function (n) {
                var o = hb.el('option', null, n);
                o.value = String(n);
                o.selected = n === state.pageSize;
                size.appendChild(o);
            });
            size.addEventListener('change', function () {
                state.pageSize = Number(size.value);
                state.page = 1;
                load();
            });
            foot.appendChild(size);
            if (state.fit && fitHiddenCount()) {
                foot.appendChild(hb.el('span', 'hbg-fit-info', hb.t('js.grid.fitHidden', fitHiddenCount())));
            }
            if (checkable && checked.size) {
                foot.appendChild(hb.el('span', 'hbg-checked-info', hb.t('js.grid.checked', hb.fmt.num(checked.size))));
            }
            foot.appendChild(hb.el('span', 'hbg-total', state.total === null ? '' : hb.t('js.grid.total', hb.fmt.num(state.total))));
        }

        /** @param {number} p */
        function goPage(p) {
            if (p < 1 || p === state.page) { return; }
            whenLeave(null, function () {
                state.page = p;
                load();
            });
        }

        // ---------- 조회 ----------
        /** @param {Record<string, unknown>} [extra] @return {string[]} */
        function query(extra) {
            /** @type {Record<string, unknown>} */
            var q = Object.assign({}, opt.params ? opt.params() : {}, extra || {});
            /** @type {string[]} */
            var parts = [];
            Object.keys(q).forEach(function (k) {
                if (q[k] !== null && q[k] !== undefined && q[k] !== '') {
                    parts.push(encodeURIComponent(k) + '=' + encodeURIComponent(String(q[k])));
                }
            });
            state.sorts.forEach(function (s) {
                parts.push('sort=' + encodeURIComponent(s.field + ',' + (s.desc ? 'desc' : 'asc')));
            });
            return parts;
        }

        /** @param {unknown} [keepId] 다시 읽은 뒤 고를 행 id @return {Promise<void>} */
        function load(keepId) {
            var mySeq = ++state.seq;
            var parts = query({page: state.page, size: state.pageSize});
            overlay.textContent = hb.t('js.grid.loading');
            overlay.classList.add('hbg-show');
            root.setAttribute('aria-busy', 'true');
            return hb.api(opt.url + '?' + parts.join('&')).then(function (res) {
                if (mySeq !== state.seq) { return; }   // 늦게 온 이전 응답은 버린다
                state.rows = res.list || [];
                // 체크해 둔 행이 이 쪽에 다시 오면 새 값으로(저장 뒤 다시 읽기), 이제 못 고르는 행이면 체크에서 뺀다
                var dropped = false;
                state.rows.forEach(function (r) {
                    var k = String(r[opt.key]);
                    if (!checked.has(k)) { return; }
                    if (checkOk(r)) { checked.set(k, r); } else { checked.delete(k); dropped = true; }
                });
                state.total = res.total === undefined ? null : res.total;
                state.selected = -1;
                if (keepId !== undefined && keepId !== null) {
                    state.rows.forEach(function (r, i) { if (r[opt.key] === keepId) { state.selected = i; } });
                }
                root.removeAttribute('aria-busy');
                scroll.setAttribute('aria-rowcount', String((state.total || state.rows.length) + 1));
                renderBody();
                if (dropped && opt.onCheck) { opt.onCheck(Array.from(checked.values())); }
                if (state.selected >= 0 && opt.onSelect) { opt.onSelect(state.rows[state.selected]); }
            }, function (err) {
                if (mySeq !== state.seq) { return; }
                root.removeAttribute('aria-busy');
                state.rows = [];
                renderBody();
                overlay.textContent = err.message;
                overlay.classList.add('hbg-show', 'hbg-error');
            });
        }

        // ---------- 선택 ----------
        var asking = false;   // beforeSelect 가 Promise 를 돌려줘 답을 기다리는 중

        /** 이동 전 확인 — 동기 답은 바로, 비동기 답은 기다리되 그 사이 다시 조회됐으면 버린다 @param {HB.Row | null} next @param {() => void} proceed */
        function whenLeave(next, proceed) {
            if (asking) { return; }
            if (!opt.beforeSelect) { proceed(); return; }
            var seq = state.seq;
            var r = hb.gate(opt.beforeSelect(next), function () { if (seq === state.seq) { proceed(); } });
            if (typeof r !== 'boolean') {
                asking = true;
                r.then(function () { asking = false; });
            }
        }

        /** @param {number} i @param {boolean} focusRow */
        function select(i, focusRow) {
            if (i < 0 || i >= state.rows.length || i === state.selected) { return; }
            whenLeave(state.rows[i], function () { move(i, focusRow); });
        }

        /** @param {number} i @param {boolean} focusRow */
        function move(i, focusRow) {
            var prev = tbody.querySelector('tr[aria-selected="true"]');
            if (prev) { prev.setAttribute('aria-selected', 'false'); }
            state.selected = i;
            var tr = tbody.querySelector('tr[data-index="' + i + '"]');
            if (tr) {
                tr.setAttribute('aria-selected', 'true');
                if (focusRow) { tr.scrollIntoView({block: 'nearest'}); }
            }
            if (opt.onSelect) { opt.onSelect(state.rows[i]); }
        }

        tbody.addEventListener('click', function (e) {
            if (e.target instanceof Element && e.target.closest('td.hbg-check')) { return; }   // 체크박스 칸은 고르기와 따로
            var tr = e.target instanceof Element ? e.target.closest('tr') : null;
            if (tr) { select(Number(tr.getAttribute('data-index')), false); }
        });
        scroll.addEventListener('keydown', function (e) {
            /** @type {Record<string, number>} */
            var keys = {ArrowDown: 1, ArrowUp: -1};
            if (keys[e.key]) {
                e.preventDefault();
                select(Math.max(0, Math.min(state.rows.length - 1, state.selected + keys[e.key])), true);
            } else if (e.key === 'Home') {
                e.preventDefault();
                select(0, true);
            } else if (e.key === 'End') {
                e.preventDefault();
                select(state.rows.length - 1, true);
            } else if (e.key === ' ' && checkable && state.selected >= 0) {
                e.preventDefault();
                var row = state.rows[state.selected];
                toggleCheck(row, !checked.has(String(row[opt.key])));
            }
        });

        // ---------- 컨텍스트 메뉴 (docs/09 4.4) ----------
        /** @type {HTMLDivElement | null} */
        var menu = null;
        function closeMenu() {
            if (menu) { menu.remove(); menu = null; }
        }
        /** @param {string} label @param {() => void} fn @param {boolean} [disabled] */
        function addItem(label, fn, disabled) {
            var b = hb.el('button', 'hbg-menu-item', label);
            b.type = 'button';
            b.setAttribute('role', 'menuitem');
            b.disabled = !!disabled;
            b.addEventListener('click', function () { closeMenu(); fn(); });
            if (menu) { menu.appendChild(b); }
        }
        function sep() { if (menu) { menu.appendChild(hb.el('hr', 'hbg-menu-sep')); } }

        // 칸 위만이 아니라 빈 아래 영역·0건 안내·페이지 줄에서도 연다(행 항목 없이) — 거기서 브라우저 기본 메뉴가 떠
        // "이 그리드는 메뉴가 없다"로 보였다(2026-10-26 사용자). 페이지 줄의 입력칸(쪽 크기 select)은 기본 동작 그대로
        root.addEventListener('contextmenu', function (e) {
            var target = e.target instanceof Element ? e.target : null;
            if (!target || target.closest('input, select, textarea')) { return; }
            var td = target.closest('td, th');
            if (!td && !target.closest('.hbg-scroll, .hbg-foot')) { return; }
            e.preventDefault();
            closeMenu();
            var tr = td ? td.closest('tr') : null;
            var rowIdx = tr && tr.hasAttribute('data-index') ? Number(tr.getAttribute('data-index')) : -1;
            if (rowIdx >= 0) { select(rowIdx, false); }
            var field = td ? td.getAttribute('data-field') || '' : '';
            var col = state.columns.filter(function (c) { return c.field === field; })[0];
            var row = rowIdx >= 0 ? state.rows[rowIdx] : null;
            menu = hb.el('div', 'hbg-menu');
            menu.setAttribute('role', 'menu');
            var hasFilters = !!(opt.filters && opt.onFilter);
            var filters = opt.filters || {};
            var onFilter = opt.onFilter || function () { /* 필터 없음 */ };
            var cellValue = !td || td.hasAttribute('data-empty-text') ? '' : td.textContent || '';   // emptyText 는 화면 표시만
            if (row) {
                var r = row;
                addItem(hb.t('js.grid.copyCell'), function () { copyText(cellValue); });
                addItem(hb.t('js.grid.copyRow'), function () {
                    copyText(visibleColumns().map(function (c) { return cellText(c, r[c.field], r); }).join('\t'));
                });
                if (hasFilters && filters[field]) {
                    addItem(hb.t('js.grid.filterByValue'), function () {
                        onFilter(filters[field], r[field]);
                        state.page = 1;
                        load();
                    });
                }
                if (opt.auditTable) {
                    addItem(hb.t('js.grid.history'), function () { showHistory(r); });
                }
                sep();
            }
            if (hasFilters) {
                addItem(hb.t('js.grid.clearFilter'), function () {
                    Object.keys(filters).forEach(function (f) { onFilter(filters[f], ''); });
                    state.page = 1;
                    load();
                });
            }
            if (col) {
                addItem(hb.t('js.grid.hideColumn'), function () {
                    if (visibleColumns().length > 1) { col.hidden = true; renderHeader(); renderBody(); }
                });
            }
            addItem(hb.t('js.grid.chooseColumns'), chooseColumns);
            addItem(state.fit ? hb.t('js.grid.unfitColumns') : hb.t('js.grid.fitColumns'), toggleFit);
            if (opt.exportUrl && actions.indexOf('EXCEL') >= 0) {
                sep();
                addItem(hb.t('js.grid.excel'), exportExcel);
            }
            sep();
            addItem(hb.t('js.grid.saveLayout'), saveLayout);
            addItem(hb.t('js.grid.resetLayout'), resetLayout);
            document.body.appendChild(menu);
            var x = Math.min(e.clientX, window.innerWidth - menu.offsetWidth - 4);
            var y = Math.min(e.clientY, window.innerHeight - menu.offsetHeight - 4);
            menu.style.left = Math.max(0, x) + 'px';
            menu.style.top = Math.max(0, y) + 'px';
            var first = /** @type {HTMLButtonElement | null} */ (menu.querySelector('button:not([disabled])'));
            if (first) { first.focus(); }
        });
        document.addEventListener('click', function (e) { if (menu && !menu.contains(/** @type {Node | null} */ (e.target))) { closeMenu(); } });
        document.addEventListener('keydown', function (e) { if (e.key === 'Escape') { closeMenu(); } });

        /** @param {string} title */
        function dialog(title) {
            var d = hb.el('dialog', 'hb-dialog');
            d.appendChild(hb.el('h2', null, title));
            var body = hb.el('div', 'hb-dialog-body');
            d.appendChild(body);
            var bar = hb.el('div', 'hb-dialog-bar');
            var close = hb.el('button', 'hb-btn-secondary', hb.t('js.common.close'));
            close.type = 'button';
            close.addEventListener('click', function () { d.close(); });
            bar.appendChild(close);
            d.appendChild(bar);
            d.addEventListener('close', function () { d.remove(); });
            document.body.appendChild(d);
            return {dialog: d, body: body, bar: bar};
        }

        function chooseColumns() {
            var dl = dialog(hb.t('js.grid.chooseColumns'));
            state.columns.forEach(function (c) {
                var label = hb.el('label', 'hb-check');
                var box = hb.el('input');
                box.type = 'checkbox';
                box.checked = !c.hidden;
                box.addEventListener('change', function () {
                    if (!box.checked && visibleColumns().length <= 1) { box.checked = true; return; }
                    c.hidden = !box.checked;
                    renderHeader();
                    renderBody();
                });
                label.appendChild(box);
                label.appendChild(hb.el('span', null, c.title));
                dl.body.appendChild(label);
            });
            dl.dialog.showModal();
        }

        /** @param {HB.Row} row */
        function showHistory(row) {
            var dl = dialog(hb.t('js.grid.history'));
            dl.body.appendChild(hb.el('p', 'hb-muted', hb.t('js.grid.loading')));
            dl.dialog.showModal();
            hb.api('/api/platform/audit?table=' + encodeURIComponent(opt.auditTable || '') + '&key='
                    + encodeURIComponent(row[opt.key])).then(function (res) {
                dl.body.textContent = '';
                var list = res.list || [];
                if (!list.length) {
                    dl.body.appendChild(hb.el('p', 'hb-muted', hb.t('js.grid.noHistory')));
                }
                list.forEach(function (h) {
                    var item = hb.el('div', 'hb-audit');
                    item.appendChild(hb.el('div', 'hb-audit-head', hb.fmt.datetime(h.occurredAt) + '  ' + h.action + '  ' + h.actor));
                    var before = h.before ? JSON.parse(h.before) : {};
                    var after = h.after ? JSON.parse(h.after) : {};
                    var keys = Object.keys(Object.assign({}, before, after)).filter(function (k) {
                        return JSON.stringify(before[k]) !== JSON.stringify(after[k]) && k !== 'comp_cd';
                    });
                    var ul = hb.el('ul', 'hb-audit-list');
                    keys.forEach(function (k) {
                        ul.appendChild(hb.el('li', null, k + ': ' + (before[k] === undefined ? '' : JSON.stringify(before[k]))
                                + ' → ' + (after[k] === undefined ? '' : JSON.stringify(after[k]))));
                    });
                    item.appendChild(ul);
                    dl.body.appendChild(item);
                });
            }, function (err) {
                dl.body.textContent = err.message;
            });
        }

        function exportExcel() {
            /** @type {Record<string, unknown>} */
            var q = Object.assign({}, opt.params ? opt.params() : {});
            q.sort = state.sorts.map(function (s) { return s.field + ',' + (s.desc ? 'desc' : 'asc'); });
            q.columns = visibleColumns().filter(function (c) { return c.exportable; }).map(function (c) { return c.field; });
            notify(hb.t('js.grid.exporting'));
            return hb.download(opt.exportUrl || '', q).then(function () {
                notify(hb.t('js.grid.exported'));
            }, function (err) {
                notify(err.message, true);
            });
        }

        /** @param {string} text @param {boolean} [error] */
        function notify(text, error) {
            if (opt.notify) { opt.notify(text, error); }
        }

        // ---------- 화면 설정 (docs/09 4.6) ----------
        function stateUrl() {
            return '/api/platform/grid-state/' + encodeURIComponent(opt.progCd) + '/' + encodeURIComponent(opt.id || 'main');
        }

        function saveLayout() {
            var body = {
                defHash: hashFields(defaultColumns),
                state: {
                    cols: state.columns.map(function (c) { return {f: c.field, w: c.width, h: !!c.hidden}; }),   // 열 맞춤 숨김은 저장 안 함
                    sort: state.sorts.map(function (s) { return {f: s.field, d: s.desc}; }),
                    pageSize: state.pageSize
                }
            };
            hb.api(stateUrl(), {method: 'PUT', body: body}).then(function () {
                notify(hb.t('js.grid.layoutSaved'));
            }, function (err) { notify(err.message, true); });
        }

        function resetLayout() {
            hb.api(stateUrl(), {method: 'DELETE'}).then(function () {
                state.columns = defaultColumns.map(function (c) { return Object.assign({}, c); });
                state.fit = false;
                state.sorts = [];
                state.pageSize = opt.pageSize || 50;
                renderHeader();
                state.page = 1;
                load();
                notify(hb.t('js.grid.layoutReset'));
            }, function (err) { notify(err.message, true); });
        }

        /** 저장된 설정을 코드 정의에 병합 — 사라진 열은 버리고 새 열은 기본 위치(끝) @param {{state?: SavedState} | null | undefined} saved */
        function merge(saved) {
            if (!saved || !saved.state) { return; }
            /** @type {Record<string, HB.GridColumn>} */
            var byField = {};
            state.columns.forEach(function (c) { byField[c.field] = c; });
            /** @type {HB.GridColumn[]} */
            var ordered = [];
            (saved.state.cols || []).forEach(function (s) {
                var c = byField[s.f];
                if (c) {
                    if (s.w) { c.width = s.w; }
                    c.hidden = !!s.h;
                    ordered.push(c);
                    delete byField[s.f];
                }
            });
            state.columns.forEach(function (c) { if (byField[c.field]) { ordered.push(c); } });
            state.columns = ordered;
            if (!visibleColumns().length && state.columns.length) { state.columns[0].hidden = false; }
            state.sorts = (saved.state.sort || []).filter(function (s) {
                return state.columns.some(function (c) { return c.field === s.f && c.sortable; });
            }).map(function (s) { return {field: s.f, desc: !!s.d}; });
            if (saved.state.pageSize) { state.pageSize = saved.state.pageSize; }
        }

        // ---------- 시작 ----------
        state.pageSize = opt.pageSize || 50;
        var ready = Promise.all([hb.loadSession(), hb.api(opt.columnsUrl)]).then(function (r) {
            /** @type {HB.GridColumn[]} */
            var cols = (r[1].list || []).map(function (/** @type {HB.Column} */ c) {
                /** @type {HB.Column & {hidden?: boolean}} */
                var o = Object.assign({}, c, (opt.columnOverrides || {})[c.field] || {});
                return Object.assign(o, {
                    hidden: !!o.hidden,
                    priority: o.priority || 2,
                    minWidth: o.minWidth || Math.min(o.width, 60),
                    flex: !!o.flex
                });
            });
            defaultColumns = cols.map(function (c) { return Object.assign({}, c); });
            state.columns = cols;
            var codeLoads = cols.filter(function (c) { return c.type === 'CODE' && c.codeGrp; }).map(function (c) {
                return hb.codeNames(c.codeGrp || '').then(function (m) { state.codeMaps[c.field] = m; });   // 사용 중지 코드도(X4)
            });
            return Promise.all(codeLoads.concat([hb.api(stateUrl()).then(function (res) { merge(res.data); },
                    function () { /* 설정 조회 실패는 기본값으로 */ })]));
        }).then(function () {
            renderHeader();
            return load();
        });

        // 작업 영역 폭이 바뀌면(창·메뉴 접기·서랍) 채우기·열 맞춤을 다시 — 한 프레임에 한 번
        var resizeQueued = false;
        var observer = typeof ResizeObserver !== 'undefined' ? new ResizeObserver(function () {
            if (resizeQueued || !state.columns.length) { return; }
            resizeQueued = true;
            global.requestAnimationFrame(function () {
                resizeQueued = false;
                if (refit()) {
                    renderHeader();
                    renderBody();
                } else {
                    var headRow = /** @type {HTMLElement | null} */ (thead.querySelector('tr'));
                    if (headRow) { fitTitles(headRow); }   // 숨긴 탭에서 처음 보일 때 제목 폭을 그때 잰다
                    applyWidths();
                }
            });
        }) : null;
        if (observer) { observer.observe(scroll); }

        return {
            ready: ready,
            reload: function (/** @type {boolean | undefined} */ resetPage, /** @type {unknown} */ keepId) {
                if (resetPage) {
                    state.page = 1;
                    if (checked.size) { checked.clear(); syncChecks(); }   // 새 조회 = 새 고르기
                }
                return load(keepId);
            },
            getChecked: function () { return Array.from(checked.values()); },
            clearChecked: function () { if (checked.size) { checked.clear(); syncChecks(); } },
            selected: function () { return state.selected >= 0 ? state.rows[state.selected] : null; },
            selectById: function (/** @type {unknown} */ id) {
                state.rows.forEach(function (r, i) { if (r[opt.key] === id) { select(i, true); } });
            },
            exportExcel: exportExcel,
            destroy: function () {
                closeMenu();
                if (observer) { observer.disconnect(); }
                root.textContent = '';
            }
        };
    }

    global.hb.grid = Object.assign(grid, {hashFields: hashFields});
})(typeof window !== 'undefined' ? window : globalThis);
