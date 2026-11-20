// @ts-check
/*
 * 업무 흐름 지도(home-flow-map) 계산 — DOM 없는 순수 함수 (docs/46 12장). node 시험: src/test/js/flow-map.test.js
 * - model: 흐름 단계(flow-map.json) + 앞·뒤 화면(help-flow.json, 도움말 대화상자와 같은 원본) → 단계 열·화살표 종류
 *   화살표 A → B = A.next 에 B 가 있거나 B.prev 에 A 가 있을 때. 흐름 밖 화면을 가리키는 화살표는 뺀다
 * - layout: 기본 자리(뱀·ㄹ자 배치 — 화면에 가장 크게 들어가는 한 줄 단계 수) / fitScale: 화면에 맞는 배율
 * - restore: 저장한 자리(끌어 옮긴 것)를 검증해 덮기
 * - shown: 보일 화살표 = 켠 종류 + 고른 상자의 들어오고 나가는 화살표(강조)
 * - route: 두 상자 사이 짧은 직각 연결(SVG path d) — 상자 위치에 따라 좌우 또는 위아래 면을 잇는다
 * 브라우저에서는 window.hbFlowMap (계약 HB.FlowMapLib, types/hb.d.ts)
 */
(function (root) {
    'use strict';

    var PROG_CD = /^[a-z0-9][a-z0-9-]{1,49}$/;
    /** 저장 자리 상한(px) — 잘못 저장된 값이 그림판을 끝없이 넓히지 않게 */
    var MAX_POS = 20000;

    /** @param {unknown} v @returns {string[]} */
    function codeList(v) {
        return Array.isArray(v) ? v.filter(function (c) { return typeof c === 'string' && PROG_CD.test(c); }) : [];
    }

    /** @param {object} o @param {string} k */
    function own(o, k) { return Object.prototype.hasOwnProperty.call(o, k); }

    /**
     * @param {unknown} flow flow-map.json 의 흐름 하나 {steps: string[][]}
     * @param {unknown} links help-flow.json 전체 {progCd: {prev, next}}
     * @returns {HB.FlowMapModel}
     */
    function model(flow, links) {
        var steps = flow && typeof flow === 'object' ? /** @type {{steps?: unknown}} */ (flow).steps : null;
        var all = links && typeof links === 'object' ? /** @type {Record<string, {prev?: unknown, next?: unknown}>} */ (links) : {};
        /** @type {string[][]} */
        var cols = [];
        /** @type {Record<string, {col: number, row: number}>} */
        var pos = {};
        (Array.isArray(steps) ? steps : []).forEach(function (s) {
            var col = codeList(s).filter(function (c) { return !own(pos, c); });
            col = col.filter(function (c, i) { return col.indexOf(c) === i; });
            if (!col.length) { return; }
            col.forEach(function (c, row) { pos[c] = {col: cols.length, row: row}; });
            cols.push(col);
        });
        /** @param {string} c @param {'prev' | 'next'} dir */
        function side(c, dir) {
            var e = own(all, c) ? all[c] : null;
            return e && typeof e === 'object' ? codeList(e[dir]) : [];
        }
        /** @type {HB.FlowMapEdge[]} */
        var edges = [];
        /** @type {Record<string, boolean>} */
        var seen = {};
        /** @param {string} a @param {string} b */
        function add(a, b) {
            if (a === b || !own(pos, a) || !own(pos, b) || seen[a + '>' + b]) { return; }
            seen[a + '>' + b] = true;
            edges.push({from: a, to: b, kind: kind(pos[a], pos[b])});
        }
        cols.forEach(function (col) {
            col.forEach(function (a) {
                side(a, 'next').forEach(function (b) { add(a, b); });
                side(a, 'prev').forEach(function (p) { add(p, a); });
            });
        });
        edges.sort(function (x, y) { return order(pos, x.from, y.from) || order(pos, x.to, y.to); });
        /** @type {Record<string, string[]>} */
        var next = {};
        /** @type {Record<string, string[]>} */
        var prev = {};
        Object.keys(pos).forEach(function (c) { next[c] = []; prev[c] = []; });
        edges.forEach(function (e) { next[e.from].push(e.to); prev[e.to].push(e.from); });
        return {cols: cols, pos: pos, edges: edges, next: next, prev: prev};
    }

    /** @param {Record<string, {col: number, row: number}>} pos @param {string} a @param {string} b */
    function order(pos, a, b) {
        return pos[a].col - pos[b].col || pos[a].row - pos[b].row;
    }

    /**
     * @param {{col: number, row: number}} a @param {{col: number, row: number}} b
     * @returns {HB.FlowMapEdgeKind}
     */
    function kind(a, b) {
        var d = b.col - a.col;
        if (d === 1) { return 'next'; }
        if (d > 1) { return 'skip'; }
        if (d === 0 && b.row > a.row) { return 'down'; }
        return 'back';
    }

    /**
     * 기본 자리 — 뱀(ㄹ자) 배치: 단계를 한 줄에 perRow 개씩 놓고, 홀수 줄은 오른쪽 → 왼쪽으로 되돌아온다(줄 끝 → 다음 줄 첫 단계가
     * 바로 아래라 선이 짧다). 같은 단계는 아래로 쌓는다. perRow 는 1 ~ 단계 수 중 화면(avail)에 가장 크게 들어가는 값
     * (같으면 줄이 적은 쪽) — "한눈에"(2026-10-26 사용자 피드백)
     * @param {HB.FlowMapModel} m
     * @param {HB.FlowMapGrid} g 상자 폭·높이, 열·쌓기 사이, 줄 사이, 가장자리
     * @param {{w: number, h: number}} avail 보이는 그림판 크기(px)
     * @param {number} [maxScale] 맞춤 배율 상한(기본 1) — 그림판이 넉넉하면 상자를 키워 채운다
     * @returns {HB.FlowMapLayout}
     */
    function layout(m, g, avail, maxScale) {
        var cap = maxScale == null ? 1 : maxScale;
        var n = m.cols.length;
        /** @type {HB.FlowMapLayout | null} */
        var best = null;
        for (var k = Math.max(1, n); k >= 1; k--) {
            var cand = snake(m, g, k);
            cand.scale = fitScale(cand, avail, 0, cap);
            if (!best || cand.scale > best.scale + 1e-9) { best = cand; }
        }
        return best || {points: {}, w: 2 * g.pad, h: 2 * g.pad, perRow: 1, scale: 1};
    }

    /**
     * 한 줄에 k 단계씩 뱀 배치
     * @param {HB.FlowMapModel} m @param {HB.FlowMapGrid} g @param {number} k
     * @returns {HB.FlowMapLayout}
     */
    function snake(m, g, k) {
        /** @type {Record<string, HB.FlowMapPoint>} */
        var points = {};
        var y = g.pad;
        var maxX = 0;
        for (var start = 0, row = 0; start < m.cols.length; start += k, row++) {
            var steps = m.cols.slice(start, start + k);
            var tall = 0;
            steps.forEach(function (col, i) {
                var slot = row % 2 === 0 ? i : k - 1 - i;
                var x = g.pad + slot * (g.w + g.gx);
                maxX = Math.max(maxX, x + g.w);
                col.forEach(function (c, j) { points[c] = {x: x, y: y + j * (g.h + g.gy)}; });
                tall = Math.max(tall, col.length * (g.h + g.gy) - g.gy);
            });
            y += tall + g.gr;
        }
        return {points: points, w: maxX + g.pad, h: y - g.gr + g.pad, perRow: k, scale: 1};
    }

    /**
     * 화면에 맞는 배율 — 내용이 avail 안에 들어가는 가장 큰 값, [min, max] 로 자름
     * @param {{w: number, h: number}} size @param {{w: number, h: number}} avail @param {number} min @param {number} max
     */
    function fitScale(size, avail, min, max) {
        if (!(size.w > 0) || !(size.h > 0) || !(avail.w > 0) || !(avail.h > 0)) { return max; }
        var s = Math.min(avail.w / size.w, avail.h / size.h);
        return Math.max(min, Math.min(max, Math.floor(s * 100) / 100));
    }

    /**
     * 저장한 자리로 덮기 — 이 흐름의 화면이고 0 ~ 상한 안의 숫자일 때만(나머지는 기본 자리)
     * @param {Record<string, HB.FlowMapPoint>} base
     * @param {unknown} saved
     * @returns {Record<string, HB.FlowMapPoint>}
     */
    function restore(base, saved) {
        /** @type {Record<string, HB.FlowMapPoint>} */
        var out = {};
        var s = saved && typeof saved === 'object' ? /** @type {Record<string, unknown>} */ (saved) : {};
        Object.keys(base).forEach(function (c) {
            var v = own(s, c) ? s[c] : null;
            var p = v && typeof v === 'object' ? /** @type {{x?: unknown, y?: unknown}} */ (v) : null;
            out[c] = p && ok(p.x) && ok(p.y) ? {x: Math.round(/** @type {number} */ (p.x)), y: Math.round(/** @type {number} */ (p.y))}
                : {x: base[c].x, y: base[c].y};
        });
        return out;
    }

    /** @param {unknown} n */
    function ok(n) { return typeof n === 'number' && isFinite(n) && n >= 0 && n <= MAX_POS; }

    /**
     * 옮기기 — 0 아래·상한 위로는 못 간다
     * @param {HB.FlowMapPoint} p @param {number} dx @param {number} dy
     * @returns {HB.FlowMapPoint}
     */
    function move(p, dx, dy) {
        return {x: Math.round(Math.min(MAX_POS, Math.max(0, p.x + dx))), y: Math.round(Math.min(MAX_POS, Math.max(0, p.y + dy)))};
    }

    /**
     * 보일 화살표 — 켠 종류(kinds) + 고른 상자(selected)의 들어오고 나가는 화살표(hot)
     * @param {HB.FlowMapModel} m
     * @param {Partial<Record<HB.FlowMapEdgeKind, boolean>>} kinds
     * @param {string | null} selected
     * @returns {(HB.FlowMapEdge & {hot: boolean})[]}
     */
    function shown(m, kinds, selected) {
        /** @type {(HB.FlowMapEdge & {hot: boolean})[]} */
        var out = [];
        m.edges.forEach(function (e) {
            var hot = !!selected && (e.from === selected || e.to === selected);
            if (hot || kinds[e.kind]) { out.push({from: e.from, to: e.to, kind: e.kind, hot: hot}); }
        });
        return out;
    }

    /** @param {number} n */
    function r(n) { return Math.round(n * 10) / 10; }

    /**
     * 두 상자 사이 직각 연결. 겹치지 않고 옆에 있으면 좌우 면을, 위아래로 겹치면 위·아래 면을 잇는다
     * @param {HB.FlowMapRect} a 출발 @param {HB.FlowMapRect} b 도착
     */
    function route(a, b) {
        var gap = 8;
        var ay = a.y + a.h / 2;
        var by = b.y + b.h / 2;
        var x1;
        var x2;
        if (b.x >= a.x + a.w + gap || b.x + b.w + gap <= a.x) {
            var right = b.x >= a.x + a.w + gap;
            x1 = right ? a.x + a.w : a.x;
            x2 = right ? b.x : b.x + b.w;
            var mx = (x1 + x2) / 2;
            return 'M' + r(x1) + ' ' + r(ay) + ' H' + r(mx) + ' V' + r(by) + ' H' + r(x2);
        }
        var down = b.y >= a.y + a.h;
        x1 = a.x + a.w / 2;
        x2 = b.x + b.w / 2;
        var y1 = down ? a.y + a.h : a.y;
        var y2 = down ? b.y : b.y + b.h;
        var my = (y1 + y2) / 2;
        return 'M' + r(x1) + ' ' + r(y1) + ' V' + r(my) + ' H' + r(x2) + ' V' + r(y2);
    }

    /**
     * 상자 한 줄 설명 = 도움말 문구 첫 문장(괄호 안 마침표는 끝이 아님)
     * @param {string | null | undefined} s
     */
    function firstSentence(s) {
        if (!s) { return ''; }
        var t = String(s).split('\n')[0];
        var depth = 0;
        for (var i = 0; i < t.length; i++) {
            var c = t.charAt(i);
            if (c === '(' || c === '（') { depth++; } else if ((c === ')' || c === '）') && depth > 0) { depth--; }
            else if (depth === 0 && (c === '.' || c === '!' || c === '?') && (i + 1 === t.length || /\s/.test(t.charAt(i + 1)))) {
                return t.slice(0, i + 1).trim();
            }
        }
        return t.trim();
    }

    /** @type {HB.FlowMapLib} */
    var lib = {model: model, layout: layout, fitScale: fitScale, restore: restore, move: move, shown: shown, route: route, firstSentence: firstSentence};
    if (typeof module === 'object' && module.exports) {
        module.exports = lib;
        return;
    }
    /** @type {any} */ (root).hbFlowMap = lib;
})(typeof window !== 'undefined' ? window : globalThis);
