/*
 * pmsGantt — WBS 표 + 간트 막대 (PMS 1단계, docs/32 D1). 조회 전용: 편집은 화면의 폼이 한다.
 *   var g = pmsGantt('#host', {columns: [...], onSelect: fn(row), onActivate: fn(row)});
 *   g.setRows(rows, {today: 'yyyy-MM-dd'}); g.setScale('day'|'week'|'month'); g.select(id); g.scrollToToday();
 *   행: {id, lvl, summary, type: TASK/MILESTONE/GATE, start, end ('yyyy-MM-dd'), progress, late, dim, title, cells: {key: 글자},
 *        baseStart, baseEnd(기준선 막대, PM2), depBad(선행 의존 위반 — 이름 뒤 경고 표시)}
 *   opts.links = [{from: 선행 id, to: 후행 id, bad: 위반, type: FS|SS|FF(없으면 FS)}] — 의존 선(SVG). 보이는 행 범위에 걸친 것만 그린다
 * - 모듈 안 파일(공통 hb.* 를 고치지 않는다 — docs/29 6장). 병합 뒤 쓸 곳이 생기면 hb.gantt 로 공통화
 * - 서버 글자는 textContent 로만, 위치·폭은 CSSOM(el.style.x) — 인라인 style 속성·innerHTML 없음(CSP)
 * - 행 높이 고정(--pmsg-row-h) + 보이는 범위만 DOM(hb.tree 와 같은 창 렌더 — 1만 행에서 전부 그리면 멈춘다, SKILL §5)
 * - 키보드(WAI-ARIA treegrid 흉내): ↑↓ 이동, → 펼치기·첫 자식, ← 접기·부모, Home/End, Enter = onActivate, Space = 펼침 전환.
 *   포커스는 표(스크롤 영역) 하나에 두고 선택 행은 aria-activedescendant 로 알린다(다시 그릴 때 포커스가 떨어지지 않게)
 * 날짜는 UTC 일 번호로만 계산한다(브라우저 시간대·서머타임과 무관)
 */
(function (global) {
    'use strict';

    var DAY = 86400000;
    var OVERSCAN = 20;
    var SCALES = {day: 24, week: 8, month: 3};       // 하루 폭(px)
    var INDENT = 14;
    var SVG = 'http://www.w3.org/2000/svg';

    function dayNo(s) {
        if (!s) { return null; }
        var p = String(s).split('-');
        return Math.floor(Date.UTC(+p[0], +p[1] - 1, +p[2]) / DAY);
    }

    function ymd(n) {
        var d = new Date(n * DAY);
        return d.getUTCFullYear() + '-' + pad(d.getUTCMonth() + 1) + '-' + pad(d.getUTCDate());
    }

    function pad(n) { return n < 10 ? '0' + n : String(n); }

    /** 월요일 = 0 (1970-01-01 은 목요일) */
    function weekday(n) { return (n + 3) % 7; }

    function pmsGantt(target, options) {
        var host = typeof target === 'string' ? document.querySelector(target) : target;
        var o = Object.assign({columns: [], onSelect: null, onActivate: null, emptyText: ''}, options || {});
        var all = [];             // 받은 행(순서 = WBS 전위)
        var vis = [];             // 보이는 행 번호
        var closed = {};          // 접은 요약 id
        var selId = null;
        var scale = 'day';
        var dayW = SCALES.day;
        var range = {from: 0, to: 0};
        var today = null;
        var rowH = 0;
        var drawn = {start: -1, end: -1, key: ''};
        var frame = 0;
        var uid = 'pmsg' + Math.random().toString(36).slice(2, 8);
        var leftW = o.columns.reduce(function (s, c) { return s + c.width; }, 0);

        var wrap = hb.el('div', 'pmsg');
        var scroll = hb.el('div', 'pmsg-scroll');
        scroll.tabIndex = 0;
        scroll.setAttribute('role', 'treegrid');
        var content = hb.el('div', 'pmsg-content');
        var head = hb.el('div', 'pmsg-head');
        var headLeft = hb.el('div', 'pmsg-left pmsg-head-left');
        var headTrack = hb.el('div', 'pmsg-track pmsg-head-track');
        var body = hb.el('div', 'pmsg-body');
        var topPad = hb.el('div', 'pmsg-pad');
        var rowsBox = hb.el('div', 'pmsg-rows');
        var bottomPad = hb.el('div', 'pmsg-pad');
        var todayLine = hb.el('div', 'pmsg-today');
        var empty = hb.el('div', 'pmsg-empty');
        var links = [];
        var svg = document.createElementNS(SVG, 'svg');
        svg.setAttribute('class', 'pmsg-links');
        svg.setAttribute('aria-hidden', 'true');
        todayLine.hidden = true;
        todayLine.setAttribute('aria-hidden', 'true');

        o.columns.forEach(function (c) {
            var h = hb.el('div', 'pmsg-cell pmsg-hcell' + (c.align === 'right' ? ' pmsg-num' : ''), c.label);
            h.style.width = c.width + 'px';
            h.setAttribute('role', 'columnheader');
            headLeft.appendChild(h);
        });
        headLeft.style.width = leftW + 'px';
        head.appendChild(headLeft);
        head.appendChild(headTrack);
        body.appendChild(topPad);
        body.appendChild(rowsBox);
        body.appendChild(bottomPad);
        body.appendChild(todayLine);
        body.appendChild(svg);
        content.appendChild(head);
        content.appendChild(body);
        scroll.appendChild(content);
        wrap.appendChild(scroll);
        wrap.appendChild(empty);
        host.textContent = '';
        host.appendChild(wrap);

        function measure() {
            if (!rowH) {
                rowH = parseFloat(getComputedStyle(wrap).getPropertyValue('--pmsg-row-h')) || 32;
            }
        }

        function computeRange() {
            var lo = null, hi = null;
            all.forEach(function (r) {
                [dayNo(r.start), dayNo(r.baseStart)].forEach(function (s) { if (s !== null) { lo = lo === null ? s : Math.min(lo, s); } });
                [dayNo(r.end), dayNo(r.baseEnd)].forEach(function (e) { if (e !== null) { hi = hi === null ? e : Math.max(hi, e); } });
            });
            var t = dayNo(today);
            if (lo === null) { lo = t !== null ? t : dayNo('2026-01-01'); hi = lo + 30; }
            // 오늘이 1년 안쪽이면 범위에 넣는다(먼 과거 프로젝트에서 빈 칸이 끝없이 늘지 않게)
            if (t !== null && t >= lo - 365 && t <= hi + 365) { lo = Math.min(lo, t); hi = Math.max(hi, t); }
            lo -= 7;
            lo -= weekday(lo);           // 월요일부터 — 주말 음영이 배경 반복과 맞는다
            hi += 14;
            range = {from: lo, to: hi};
        }

        function trackW() { return (range.to - range.from + 1) * dayW; }

        function buildHeader() {
            headTrack.textContent = '';
            headTrack.style.width = trackW() + 'px';
            var top = hb.el('div', 'pmsg-scale-top');
            var bottom = hb.el('div', 'pmsg-scale-bottom');
            var n = range.from;
            while (n <= range.to) {
                var d = new Date(n * DAY);
                var next = Math.floor(Date.UTC(d.getUTCFullYear(), d.getUTCMonth() + 1, 1) / DAY);
                var end = Math.min(next, range.to + 1);
                var label = scale === 'month' ? String(d.getUTCFullYear()).slice(2) + '.' + pad(d.getUTCMonth() + 1)
                    : d.getUTCFullYear() + '-' + pad(d.getUTCMonth() + 1);
                var w = (end - n) * dayW;
                // 범위 앞뒤의 며칠짜리 달은 칸이 좁아 "202" 처럼 잘린다 → 글자는 비우고 마우스를 올리면 보이게
                var m = hb.el('div', 'pmsg-tick', w < label.length * 7 + 8 ? '' : label);
                m.title = label;
                m.style.left = ((n - range.from) * dayW) + 'px';
                m.style.width = w + 'px';
                top.appendChild(m);
                n = end;
            }
            if (scale !== 'month') {
                var step = scale === 'day' ? 1 : 7;
                for (var k = range.from; k <= range.to; k += step) {
                    var dd = new Date(k * DAY);
                    var t = hb.el('div', 'pmsg-tick' + (scale === 'day' && weekday(k) >= 5 ? ' pmsg-weekend' : ''),
                        scale === 'day' ? String(dd.getUTCDate()) : pad(dd.getUTCMonth() + 1) + '/' + pad(dd.getUTCDate()));
                    t.style.left = ((k - range.from) * dayW) + 'px';
                    t.style.width = (step * dayW) + 'px';
                    bottom.appendChild(t);
                }
            }
            headTrack.appendChild(top);
            headTrack.appendChild(bottom);
            var tn = dayNo(today);
            todayLine.hidden = tn === null || tn < range.from || tn > range.to;
            if (!todayLine.hidden) {
                todayLine.style.left = (leftW + (tn - range.from) * dayW + Math.floor(dayW / 2)) + 'px';
            }
            wrap.classList.toggle('pmsg-scale-day', scale === 'day');
            wrap.style.setProperty('--pmsg-day', dayW + 'px');
            content.style.width = (leftW + trackW()) + 'px';
        }

        function recomputeVisible() {
            vis = [];
            var hideBelow = null;     // 접힌 요약의 레벨 — 그보다 깊은 행은 숨김
            for (var i = 0; i < all.length; i++) {
                var r = all[i];
                if (hideBelow !== null) {
                    if (r.lvl > hideBelow) { continue; }
                    hideBelow = null;
                }
                vis.push(i);
                if (r.summary && closed[r.id]) { hideBelow = r.lvl; }
            }
        }

        function draw() {
            frame = 0;
            measure();
            var total = vis.length;
            var top = scroll.scrollTop;
            var viewH = scroll.clientHeight || 400;
            var start = Math.max(0, Math.floor(top / rowH) - OVERSCAN);
            var end = Math.min(total, Math.ceil((top + viewH) / rowH) + OVERSCAN);
            var key = selId + '|' + scale + '|' + total;
            if (start === drawn.start && end === drawn.end && key === drawn.key) { return; }
            drawn = {start: start, end: end, key: key};
            topPad.style.height = (start * rowH) + 'px';
            bottomPad.style.height = ((total - end) * rowH) + 'px';
            todayLine.style.height = (total * rowH) + 'px';
            rowsBox.textContent = '';
            for (var v = start; v < end; v++) {
                rowsBox.appendChild(rowEl(all[vis[v]], v));
            }
            empty.textContent = total ? '' : o.emptyText;
            empty.hidden = !!total;
            drawLinks(start, end, total);
        }

        /** 의존 선: 선행 막대 끝 → 후행 막대 시작(꺾은선 + 화살촉). 두 끝 중 하나라도 그린 범위에 있고 둘 다 보이는(접히지 않은) 것만 */
        function drawLinks(start, end, total) {
            while (svg.firstChild) { svg.removeChild(svg.firstChild); }
            svg.style.left = leftW + 'px';
            svg.style.width = trackW() + 'px';
            svg.style.height = (total * rowH) + 'px';
            if (!links.length) { return; }
            var pos = {};
            for (var v = 0; v < vis.length; v++) { pos[all[vis[v]].id] = v; }
            links.forEach(function (l) {
                var a = pos[l.from], b = pos[l.to];
                if (a === undefined || b === undefined) { return; }
                if ((a < start || a >= end) && (b < start || b >= end)) { return; }
                var ra = all[vis[a]], rb = all[vis[b]];
                // 종류(docs/32 4.27 S5): FS 선행 끝 → 후행 시작, SS 시작 → 시작(왼쪽으로 돌아 들어감), FF 끝 → 끝(오른쪽에서 들어감)
                var type = l.type || 'FS';
                var x1 = type === 'SS' ? xStart(ra) : xEnd(ra);
                var x2 = type === 'FF' ? xEnd(rb) : xStart(rb);
                if (x1 === null || x2 === null) { return; }
                var y1 = a * rowH + rowH / 2, y2 = b * rowH + rowH / 2;
                var d, head;
                if (type === 'SS') {
                    var left = Math.min(x1, x2) - 8;
                    d = 'M' + x1 + ' ' + y1 + ' H' + left + ' V' + y2 + ' H' + (x2 - 1);
                    head = 'M' + (x2 - 1) + ' ' + y2 + ' l-5 -4 v8 z';
                } else if (type === 'FF') {
                    var right = Math.max(x1, x2) + 8;
                    d = 'M' + x1 + ' ' + y1 + ' H' + right + ' V' + y2 + ' H' + (x2 + 1);
                    head = 'M' + (x2 + 1) + ' ' + y2 + ' l5 -4 v8 z';
                } else {
                    d = x2 - 6 >= x1 + 6
                        ? 'M' + x1 + ' ' + y1 + ' H' + (x1 + 6) + ' V' + y2 + ' H' + (x2 - 1)
                        : 'M' + x1 + ' ' + y1 + ' H' + (x1 + 6) + ' V' + (y2 - rowH / 2 + 2) + ' H' + (x2 - 8) + ' V' + y2 + ' H' + (x2 - 1);
                    head = 'M' + (x2 - 1) + ' ' + y2 + ' l-5 -4 v8 z';
                }
                var p = document.createElementNS(SVG, 'path');
                p.setAttribute('d', d);
                p.setAttribute('class', 'pmsg-link' + (l.bad ? ' pmsg-link-bad' : ''));
                svg.appendChild(p);
                var h = document.createElementNS(SVG, 'path');
                h.setAttribute('d', head);
                h.setAttribute('class', 'pmsg-arrow' + (l.bad ? ' pmsg-link-bad' : ''));
                svg.appendChild(h);
            });
        }

        function point(r) { return r.type === 'MILESTONE' || r.type === 'GATE'; }
        function xStart(r) {
            var s = dayNo(r.start);
            return s === null ? null : (s - range.from) * dayW + (point(r) ? Math.floor(dayW / 2) - 6 : 0);
        }
        function xEnd(r) {
            var e = dayNo(r.end);
            return e === null ? null : (point(r) ? (e - range.from) * dayW + Math.floor(dayW / 2) + 6 : (e - range.from + 1) * dayW - 2);
        }

        function schedule() {
            if (!frame) { frame = requestAnimationFrame(draw); }
        }

        function rowEl(r, v) {
            var row = hb.el('div', 'pmsg-row' + (r.id === selId ? ' pmsg-selected' : '') + (r.dim ? ' pmsg-dim' : '')
                + (r.summary ? ' pmsg-summary' : ''));
            row.id = uid + '-' + r.id;
            row.setAttribute('role', 'row');
            row.setAttribute('aria-level', String(r.lvl));
            row.setAttribute('aria-selected', r.id === selId ? 'true' : 'false');
            if (r.summary) { row.setAttribute('aria-expanded', closed[r.id] ? 'false' : 'true'); }
            row.style.width = (leftW + trackW()) + 'px';
            var left = hb.el('div', 'pmsg-left');
            left.style.width = leftW + 'px';
            o.columns.forEach(function (c) {
                var cell = hb.el('div', 'pmsg-cell' + (c.align === 'right' ? ' pmsg-num' : ''));
                cell.style.width = c.width + 'px';
                cell.setAttribute('role', 'gridcell');
                if (c.tree) {
                    cell.classList.add('pmsg-tree');
                    cell.style.paddingLeft = (4 + (r.lvl - 1) * INDENT) + 'px';
                    var tog = hb.el('span', 'pmsg-toggle', r.summary ? (closed[r.id] ? '▸' : '▾') : '');
                    tog.setAttribute('aria-hidden', 'true');
                    if (r.summary) {
                        tog.addEventListener('click', function (e) { e.stopPropagation(); toggle(r.id); });
                    }
                    cell.appendChild(tog);
                    cell.appendChild(hb.el('span', 'pmsg-name', (r.cells && r.cells[c.key]) || ''));
                } else {
                    cell.textContent = (r.cells && r.cells[c.key]) || '';
                }
                left.appendChild(cell);
            });
            if (r.late) { left.classList.add('pmsg-late'); }
            if (r.depBad) { row.classList.add('pmsg-dep-bad'); }
            row.appendChild(left);
            var track = hb.el('div', 'pmsg-track');
            track.style.width = trackW() + 'px';
            var s = dayNo(r.start), e = dayNo(r.end);
            if (s !== null && e !== null) {
                var bar;
                if (r.type === 'MILESTONE' || r.type === 'GATE') {
                    bar = hb.el('div', 'pmsg-point pmsg-' + r.type.toLowerCase());
                    bar.style.left = ((s - range.from) * dayW + Math.floor(dayW / 2)) + 'px';
                } else {
                    bar = hb.el('div', 'pmsg-bar' + (r.summary ? ' pmsg-bar-summary' : '') + (r.late ? ' pmsg-bar-late' : ''));
                    bar.style.left = ((s - range.from) * dayW) + 'px';
                    bar.style.width = Math.max(2, (e - s + 1) * dayW - 2) + 'px';
                    if (!r.summary && r.progress > 0) {
                        var fill = hb.el('div', 'pmsg-fill');
                        fill.style.width = Math.min(100, r.progress) + '%';
                        bar.appendChild(fill);
                    }
                }
                bar.title = r.title || '';
                var bs = dayNo(r.baseStart), be = dayNo(r.baseEnd);
                if (bs !== null && be !== null && !r.summary) {
                    // 기준선 막대 — 지금 막대 아래 얇게(마일스톤·게이트도 그 날 하루 폭)
                    var base = hb.el('div', 'pmsg-base');
                    base.style.left = ((bs - range.from) * dayW) + 'px';
                    base.style.width = Math.max(2, (be - bs + 1) * dayW - 2) + 'px';
                    track.appendChild(base);
                }
                track.appendChild(bar);
            }
            row.appendChild(track);
            row.addEventListener('click', function () { select(r.id, true); });
            row.addEventListener('dblclick', function () { if (o.onActivate) { o.onActivate(r); } });
            return row;
        }

        function indexOfId(id) {
            for (var i = 0; i < all.length; i++) { if (all[i].id === id) { return i; } }
            return -1;
        }

        function select(id, fire) {
            selId = id;
            var i = indexOfId(id);
            // 접힌 조상 아래에 있으면 펼친다
            if (i >= 0) {
                var lvl = all[i].lvl;
                for (var k = i - 1; k >= 0 && lvl > 1; k--) {
                    if (all[k].lvl < lvl) { delete closed[all[k].id]; lvl = all[k].lvl; }
                }
            }
            recomputeVisible();
            drawn.key = '';
            ensureVisible(id);
            draw();
            scroll.setAttribute('aria-activedescendant', id === null ? '' : uid + '-' + id);
            if (fire && o.onSelect) { o.onSelect(i >= 0 ? all[i] : null); }
        }

        function ensureVisible(id) {
            measure();
            var v = vis.indexOf(indexOfId(id));
            if (v < 0) { return; }
            var y = v * rowH;
            var headH = head.offsetHeight;
            if (y < scroll.scrollTop) { scroll.scrollTop = y; }
            else if (y + rowH > scroll.scrollTop + scroll.clientHeight - headH) { scroll.scrollTop = y + rowH - scroll.clientHeight + headH; }
        }

        function toggle(id) {
            if (closed[id]) { delete closed[id]; } else { closed[id] = true; }
            recomputeVisible();
            drawn.key = '';
            draw();
        }

        scroll.addEventListener('scroll', schedule);
        // 좁은 화면(폰 390 등)에서 왼쪽 칸을 붙여 두면 막대가 영영 안 보인다 → 막대 자리가 200px 안 되면 왼쪽도 함께 스크롤
        function fitLeft() {
            wrap.classList.toggle('pmsg-unstick', scroll.clientWidth > 0 && scroll.clientWidth < leftW + 200);
            schedule();
        }
        if (global.ResizeObserver) { new ResizeObserver(fitLeft).observe(scroll); }
        scroll.addEventListener('keydown', function (e) {
            if (!vis.length || e.isComposing) { return; }
            var cur = vis.indexOf(indexOfId(selId));
            var r = cur >= 0 ? all[vis[cur]] : null;
            var next = null;
            switch (e.key) {
                case 'ArrowDown': next = cur < 0 ? 0 : Math.min(vis.length - 1, cur + 1); break;
                case 'ArrowUp': next = cur < 0 ? 0 : Math.max(0, cur - 1); break;
                case 'Home': next = 0; break;
                case 'End': next = vis.length - 1; break;
                case 'ArrowRight':
                    if (r && r.summary && closed[r.id]) { toggle(r.id); e.preventDefault(); return; }
                    if (r && r.summary) { next = Math.min(vis.length - 1, cur + 1); }
                    break;
                case 'ArrowLeft':
                    if (r && r.summary && !closed[r.id]) { toggle(r.id); e.preventDefault(); return; }
                    if (r && r.lvl > 1) {
                        for (var k = cur - 1; k >= 0; k--) { if (all[vis[k]].lvl < r.lvl) { next = k; break; } }
                    }
                    break;
                case ' ':
                    if (r && r.summary) { toggle(r.id); }
                    e.preventDefault();
                    return;
                case 'Enter':
                    if (r && o.onActivate) { o.onActivate(r); }
                    e.preventDefault();
                    return;
                default: return;
            }
            e.preventDefault();
            if (next !== null && next >= 0) { select(all[vis[next]].id, true); }
        });

        return {
            setRows: function (rows, opts) {
                all = rows || [];
                today = (opts && opts.today) || null;
                links = (opts && opts.links) || [];
                if (selId !== null && indexOfId(selId) < 0) { selId = null; }
                computeRange();
                buildHeader();
                recomputeVisible();
                drawn.key = '';
                draw();
            },
            setScale: function (s) {
                if (!SCALES[s]) { return; }
                // 보이던 가운데 날짜를 유지한다
                var mid = range.from + (scroll.scrollLeft + Math.max(0, scroll.clientWidth - leftW) / 2) / dayW;
                scale = s;
                dayW = SCALES[s];
                buildHeader();
                drawn.key = '';
                draw();
                scroll.scrollLeft = Math.max(0, (mid - range.from) * dayW - Math.max(0, scroll.clientWidth - leftW) / 2);
            },
            scrollToToday: function () {
                var tn = dayNo(today);
                if (tn === null) { return; }
                scroll.scrollLeft = Math.max(0, (tn - range.from) * dayW - Math.max(0, scroll.clientWidth - leftW) / 3);
            },
            select: function (id) { select(id, false); },
            selected: function () { var i = indexOfId(selId); return i >= 0 ? all[i] : null; },
            expandAll: function () { closed = {}; recomputeVisible(); drawn.key = ''; draw(); },
            collapseTo: function (lvl) {
                closed = {};
                all.forEach(function (r) { if (r.summary && r.lvl >= lvl) { closed[r.id] = true; } });
                recomputeVisible();
                drawn.key = '';
                draw();
            },
            focus: function () { scroll.focus(); },
            /** 시험용: 지금 DOM 에 그린 행 수 */
            drawnCount: function () { return rowsBox.childElementCount; },
            ymd: ymd
        };
    }

    global.pmsGantt = pmsGantt;
})(window);
