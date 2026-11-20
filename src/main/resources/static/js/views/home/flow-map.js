// @ts-check
/*
 * 업무 흐름 지도 (home-flow-map) — docs/46 12장. 흐름(F0~F8)을 고르면 화면 상자(버튼)를 단계 순서로 놓고 상자 뒤 SVG 에 화살표.
 * - 원본: 흐름 단계 /js/common/flow-map.json, 화살표 /js/common/help-flow.json(도움말 대화상자와 같은 원본),
 *   화면 이름·열 수 있는지 GET /api/home/flow-map/screens(판정은 서버). 계산은 window.hbFlowMap(flow-map.layout.js, 순수 함수)
 * - 선은 기본 "주 흐름"(다음 단계·같은 단계 아래)만, 건너뜀·되돌아감은 [보일 선]에서 켜거나 상자를 골랐을 때 그 상자 것만 강조
 * - 클릭(Enter·Space) = 고르기 → 그림판 아래 요약 띠(가로 전체, 높이 고정 — 상자를 가리지 않고 그림판 크기도 안 바뀜, [접기] 가능).
 *   탭 열기는 요약의 [화면 열기] 버튼으로만(셸 hbShell.open, 셸 밖이면 /page/{progCd}) — 더블클릭·Enter 열기는 실수로 여는 일이 많아 뺐다
 *   (2026-10-26 사용자). 권한 없는 화면은 버튼 비활성
 * - 끌기(포인터)·방향키 = 상자 옮기기, 자리는 이 기기 localStorage(흐름마다, 값 검증), [자리 초기화]. 900 미만 = 세로 목록(자리·선 없음)
 * - 위치는 CSSOM 사용자 속성(--fm-x·--fm-y)만 — 인라인 style 속성·HTML 문자열 없음(CSP). 서버 글자는 textContent 로만(hb.el)
 */
/** @typedef {{progCd: string, name: string, canOpen: boolean}} FlowMapScreen */
(function () {
    'use strict';

    var SVG = 'http://www.w3.org/2000/svg';
    /** 기본 자리 격자 — flow-map.css 의 상자 폭·높이(.hb-fm-node)와 같게. gr = 뱀 배치 줄 사이 */
    var GRID = {w: 148, h: 48, gx: 40, gy: 12, gr: 40, pad: 16};
    /** 확대·축소 범위·단계 — 기본은 "화면에 맞춤"(최대 100%) */
    var ZOOM_MIN = 0.35;
    var ZOOM_MAX = 1.6;
    /** 맞춤 상한 — 그림판이 넉넉하면 상자를 이만큼까지 키워 채운다("왼쪽 영역을 키우자", 2026-10-26) */
    var FIT_MAX = 1.4;
    var ZOOM_STEP = 0.1;
    var STEP = 16;
    var DRAG_START = 4;
    var FLOWS = /^F[0-8]$/;
    var STORE = 'hb.flowMap.v2.';   // v2 = 작은 상자·뱀 배치(2026-10-26) — v1 자리는 쓰지 않는다
    var narrow = window.matchMedia('(max-width: 899px)');

    var lib = /** @type {HB.FlowMapLib} */ (window.hbFlowMap);
    var sel = /** @type {HTMLSelectElement} */ (document.getElementById('flowSel'));
    var descEl = /** @type {HTMLElement} */ (document.getElementById('flowDesc'));
    var msg = /** @type {HTMLElement} */ (document.getElementById('msg'));
    var canvas = /** @type {HTMLElement} */ (document.getElementById('canvas'));
    var scroller = /** @type {HTMLElement} */ (document.getElementById('scroller'));
    var zoomOut = /** @type {HTMLButtonElement} */ (document.getElementById('zoomOut'));
    var zoomIn = /** @type {HTMLButtonElement} */ (document.getElementById('zoomIn'));
    var zoomFit = /** @type {HTMLButtonElement} */ (document.getElementById('zoomFit'));
    /** 상자·선을 담아 배율(--fm-scale)로 줄이는 판 @type {HTMLElement} */
    var stage = hb.el('div', 'hb-fm-stage');
    var mapBody = /** @type {HTMLElement} */ (document.querySelector('.hb-fm-body'));
    /** 요약 띠 — 펼침 높이 = 창 아래까지 남는 높이의 비율(최소·최대), 접힘 = 머리 줄만 */
    var BAND_RATIO = 0.34;
    var BAND_MIN = 170;
    var BAND_MAX = 280;
    var BAND_HEAD = 44;
    var detail = /** @type {HTMLElement} */ (document.getElementById('detail'));
    var resetBtn = /** @type {HTMLButtonElement} */ (document.getElementById('resetBtn'));
    var kindEls = {
        main: /** @type {HTMLInputElement} */ (document.getElementById('kindMain')),
        skip: /** @type {HTMLInputElement} */ (document.getElementById('kindSkip')),
        back: /** @type {HTMLInputElement} */ (document.getElementById('kindBack'))
    };

    /** @type {Record<string, unknown>} */
    var flows = {};
    /** @type {Record<string, unknown>} */
    var links = {};
    /** @type {Record<string, FlowMapScreen>} */
    var screens = {};
    var flowId = '';
    /** @type {HB.FlowMapModel | null} */
    var current = null;
    /** @type {Record<string, HB.FlowMapPoint>} */
    var points = {};
    /** @type {Record<string, HTMLButtonElement>} */
    var boxes = {};
    /** @type {Record<string, HTMLElement>} */
    var nodes = {};
    /** @type {SVGSVGElement | null} */
    var svg = null;
    /** @type {string | null} */
    var selected = null;
    var seq = 0;
    /** 사용자가 고른 배율(null = 화면에 맞춤) @type {number | null} */
    var zoom = null;
    var scale = 1;
    /** 저장한 자리가 있으면 창 폭이 바뀌어도 다시 배치하지 않는다 */
    var hasSaved = false;
    var bandOpen = true;

    /** @param {string | null | undefined} text @param {boolean} [error] */
    function notify(text, error) {
        msg.textContent = text || '';
        msg.classList.toggle('hb-msg-error', !!error);
    }

    /** @param {string} url */
    function json(url) {
        return fetch(url, {credentials: 'same-origin'}).then(function (r) {
            if (!r.ok) { throw new Error(String(r.status)); }
            return r.json();
        }).then(function (j) { return /** @type {Record<string, unknown>} */ (j && typeof j === 'object' ? j : {}); });
    }

    /** @param {string} key */
    function load(key) {
        try {
            var s = window.localStorage.getItem(STORE + key);
            return s ? JSON.parse(s) : null;
        } catch (e) { return null; }
    }

    /** @param {string} key @param {unknown} value */
    function keep(key, value) {
        try {
            if (value == null) { window.localStorage.removeItem(STORE + key); } else { window.localStorage.setItem(STORE + key, JSON.stringify(value)); }
        } catch (e) { /* 저장 못 해도 지도는 쓴다 */ }
    }

    /** @param {string} code */
    function nameOf(code) {
        var s = screens[code];
        return s && s.name ? s.name : code;
    }

    /** @param {string} code */
    function canOpen(code) {
        var s = screens[code];
        return !!s && s.canOpen;
    }

    /** @param {string} key */
    function text(key) {
        var s = hb.t(key);
        return s && s !== key ? s : '';
    }

    /** 셸 안이면 탭, 밖이면 이 창에서 이동. 권한 없음은 안내만 @param {string} code */
    function openScreen(code) {
        if (!canOpen(code)) {
            notify(hb.t('js.flowMap.noAuthMsg', nameOf(code)), true);
            return;
        }
        notify('');
        var top = window.top && window.top !== window ? window.top.hbShell : null;
        if (top && top !== true) {
            top.open(code, nameOf(code), null);
        } else {
            window.location.href = '/page/' + encodeURIComponent(code);
        }
    }

    /** 셸에 열린 탭의 화면 코드 */
    function openTabs() {
        try {
            var top = window.top && window.top !== window ? window.top.hbShell : null;
            if (top && top !== true) { return top.tabs().map(function (t) { return t.progCd; }); }
        } catch (e) { /* 다른 출처 */ }
        return [];
    }

    function markOpenTabs() {
        var open = openTabs();
        Object.keys(boxes).forEach(function (c) {
            var b = boxes[c];
            var on = open.indexOf(c) >= 0 && canOpen(c);
            if (b.classList.contains('is-open') === on) { return; }
            b.classList.toggle('is-open', on);
            var tags = /** @type {HTMLElement} */ (b.querySelector('.hb-fm-tags'));
            var tag = tags.querySelector('[data-tag="open"]');
            if (on && !tag) {
                var s = hb.status('success', hb.t('js.flowMap.openTab'));
                s.setAttribute('data-tag', 'open');
                tags.appendChild(s);
            } else if (!on && tag) {
                tag.remove();
            }
        });
    }

    // ---------- 고르기 · 요약 ----------

    /**
     * @param {string | null} code
     * @param {boolean} [focusPanel] 키보드로 골랐으면 요약 제목으로 포커스(다음 Tab = 요약 안 버튼)
     */
    function select(code, focusPanel) {
        selected = code;
        Object.keys(boxes).forEach(function (c) { boxes[c].setAttribute('aria-pressed', String(c === code)); });
        stage.classList.toggle('has-sel', !!code);
        drawLines();
        drawDetail();
        if (code && narrow.matches && !focusPanel) { detail.scrollIntoView({block: 'nearest'}); }
        if (code && focusPanel) {
            var h = /** @type {HTMLElement | null} */ (detail.querySelector('h2'));
            if (h) { h.focus(); }
        }
    }

    /** 고르기 해제 — 고른 상자로 포커스를 돌려준다 */
    function unselect() {
        var was = selected;
        select(null);
        if (was && boxes[was]) { boxes[was].focus(); }
    }

    /** 요약 띠 접기·펼치기 — 그림판 높이가 바뀌므로 다시 맞춘다(고르기와 달리 사용자가 직접 누를 때만) */
    function toggleBand() {
        bandOpen = !bandOpen;
        keep('band', bandOpen);
        drawDetail();
        relayout();
        redraw();
    }

    /** @param {string} title @param {string[]} codes */
    function linkList(title, codes) {
        var sec = hb.el('section', 'hb-fm-d-sec');
        sec.appendChild(hb.el('h3', null, title));
        var ul = hb.el('ul', 'hb-fm-d-links');
        codes.forEach(function (c) {
            var li = hb.el('li');
            var b = /** @type {HTMLButtonElement} */ (hb.el('button', 'hb-btn-secondary hb-btn-small', nameOf(c)));
            b.type = 'button';
            b.addEventListener('click', function () { select(c, true); });
            li.appendChild(b);
            ul.appendChild(li);
        });
        sec.appendChild(ul);
        return sec;
    }

    /** @param {string} title @param {string} text */
    function para(title, text) {
        var sec = hb.el('section', 'hb-fm-d-sec');
        sec.appendChild(hb.el('h3', null, title));
        sec.appendChild(hb.el('p', null, text));
        return sec;
    }

    /**
     * 요약 띠(그림판 아래, 가로 전체) — 머리: 이름·코드·[화면 열기]·[고르기 해제]·[접기]. 몸: 무엇을·왜 | 언제 | 주요 동작 | 앞·다음 화면.
     * 접으면 머리만(고른 화면 이름과 [화면 열기]는 그대로)
     */
    function drawDetail() {
        detail.textContent = '';
        detail.classList.toggle('is-collapsed', !bandOpen);
        var code = selected;
        var head = hb.el('div', 'hb-fm-d-head');
        var title = hb.el('h2', null, code ? nameOf(code) : hb.t('js.flowMap.summary'));
        title.tabIndex = -1;
        head.appendChild(title);
        if (code) {
            var c = code;
            head.appendChild(hb.el('span', 'hb-fm-code', c));
            var open = /** @type {HTMLButtonElement} */ (hb.el('button', 'hb-btn-small', hb.t('js.flowMap.open')));
            open.type = 'button';
            if (canOpen(c)) {
                open.addEventListener('click', function () { openScreen(c); });
            } else {
                open.disabled = true;
                open.classList.add('is-off');
            }
            head.appendChild(open);
            if (!canOpen(c)) { head.appendChild(hb.status('neutral', hb.t('js.flowMap.noAuth'))); }
        }
        var tools = hb.el('span', 'hb-fm-d-tools');
        if (code) {
            var x = /** @type {HTMLButtonElement} */ (hb.el('button', 'hb-btn-secondary hb-btn-small', hb.t('js.flowMap.unselect')));
            x.type = 'button';
            x.addEventListener('click', unselect);
            tools.appendChild(x);
        }
        var fold = /** @type {HTMLButtonElement} */ (hb.el('button', 'hb-btn-secondary hb-btn-small',
            bandOpen ? hb.t('js.flowMap.bandClose') : hb.t('js.flowMap.bandOpen')));
        fold.type = 'button';
        fold.setAttribute('aria-expanded', String(bandOpen));
        fold.setAttribute('aria-controls', 'detailBody');
        fold.addEventListener('click', toggleBand);
        tools.appendChild(fold);
        head.appendChild(tools);
        detail.appendChild(head);
        var body = hb.el('div', 'hb-fm-d-body');
        body.id = 'detailBody';
        body.hidden = !bandOpen;
        detail.appendChild(body);
        if (!code || !current) {
            body.appendChild(hb.el('p', 'hb-muted', hb.t('js.flowMap.pick')));
            return;
        }
        var m = current;
        var col1 = hb.el('div', 'hb-fm-d-col');
        var what = text('help.' + code);
        if (what) { col1.appendChild(para(hb.t('js.flowMap.what'), lib.firstSentence(what))); }
        var why = text('help.' + code + '.helpWhy');
        if (why) { col1.appendChild(para(hb.t('js.flowMap.why'), why)); }
        body.appendChild(col1);
        var when = text('help.' + code + '.helpWhen');
        if (when) {
            var col2 = hb.el('div', 'hb-fm-d-col');
            col2.appendChild(para(hb.t('js.flowMap.when'), when));
            body.appendChild(col2);
        }
        var does = text('help.' + code + '.helpDo').split('\n').map(function (t) { return t.trim(); }).filter(Boolean);
        if (does.length) {
            var col3 = hb.el('div', 'hb-fm-d-col');
            var ds = hb.el('section', 'hb-fm-d-sec');
            ds.appendChild(hb.el('h3', null, hb.t('js.flowMap.does')));
            var ul = hb.el('ul', 'hb-fm-d-does');
            does.forEach(function (d) { ul.appendChild(hb.el('li', null, d)); });
            ds.appendChild(ul);
            col3.appendChild(ds);
            body.appendChild(col3);
        }
        if (m.prev[code].length || m.next[code].length) {
            var col4 = hb.el('div', 'hb-fm-d-col');
            if (m.prev[code].length) { col4.appendChild(linkList(hb.t('js.flowMap.prevList'), m.prev[code])); }
            if (m.next[code].length) { col4.appendChild(linkList(hb.t('js.flowMap.nextList'), m.next[code])); }
            body.appendChild(col4);
        }
    }

    // ---------- 자리 · 선 ----------

    /** @param {string} code */
    function apply(code) {
        var p = points[code];
        nodes[code].style.setProperty('--fm-x', p.x + 'px');
        nodes[code].style.setProperty('--fm-y', p.y + 'px');
    }

    /** 보이는 그림판 크기 — 높이는 창 아래까지(위치는 그때그때 읽는다) */
    /**
     * 보이는 그림판 크기 — 창 아래까지에서 요약 띠(펼침 = 남는 높이의 BAND_RATIO, 접힘 = 머리 줄만)를 뺀 높이.
     * 요약 띠 높이는 고르기와 상관없이 같아서 상자를 골라도 그림판 크기·배율이 바뀌지 않는다
     */
    function avail() {
        var top = scroller.getBoundingClientRect().top;
        var total = Math.max(320, Math.floor(window.innerHeight - top - 16));
        var band = bandOpen ? Math.max(BAND_MIN, Math.min(BAND_MAX, Math.round(total * BAND_RATIO))) : BAND_HEAD;
        var h = total - band - 8;
        mapBody.style.setProperty('--fm-map-h', h + 'px');
        mapBody.style.setProperty('--fm-band-h', band + 'px');
        return {w: Math.max(200, scroller.clientWidth - 4), h: h - 4};
    }

    /** 내용 크기 → 배율(맞춤 또는 고른 값) → 판·그림판 크기 */
    function fit() {
        var w = 0;
        var h = 0;
        Object.keys(points).forEach(function (c) {
            w = Math.max(w, points[c].x + (boxes[c].offsetWidth || GRID.w));
            h = Math.max(h, points[c].y + (boxes[c].offsetHeight || GRID.h));
        });
        w += GRID.pad;
        h += GRID.pad;
        var a = avail();
        scale = narrow.matches ? 1 : zoom != null ? zoom : lib.fitScale({w: w, h: h}, a, ZOOM_MIN, FIT_MAX);
        stage.style.setProperty('--fm-w', w + 'px');
        stage.style.setProperty('--fm-h', h + 'px');
        stage.style.setProperty('--fm-scale', String(scale));
        canvas.style.setProperty('--fm-cw', Math.ceil(w * scale) + 'px');
        canvas.style.setProperty('--fm-ch', Math.ceil(h * scale) + 'px');
        if (svg) {
            svg.setAttribute('width', String(w));
            svg.setAttribute('height', String(h));
        }
        zoomFit.textContent = hb.t('js.flowMap.zoomFit', Math.round(scale * 100));
        zoomFit.setAttribute('aria-pressed', String(zoom == null));
        zoomOut.disabled = scale <= ZOOM_MIN + 1e-9;
        zoomIn.disabled = scale >= ZOOM_MAX - 1e-9;
    }

    /** @param {number | null} z null = 화면에 맞춤 */
    function setZoom(z) {
        zoom = z == null ? null : Math.round(Math.min(ZOOM_MAX, Math.max(ZOOM_MIN, z)) * 100) / 100;
        fit();
    }

    /** 저장 자리가 없으면 지금 폭에 맞춰 다시 뱀 배치 */
    function relayout() {
        if (!current || hasSaved || narrow.matches) { return; }
        points = lib.layout(current, GRID, avail(), FIT_MAX).points;
        Object.keys(points).forEach(apply);
    }

    function savePoints() {
        keep('pos.' + flowId, points);
        hasSaved = true;
        resetBtn.disabled = false;
    }

    /** @param {string} code */
    function rectOf(code) {
        var b = boxes[code];
        return {x: points[code].x, y: points[code].y, w: b.offsetWidth || GRID.w, h: b.offsetHeight || GRID.h};
    }

    function kinds() {
        return {next: kindEls.main.checked, down: kindEls.main.checked, skip: kindEls.skip.checked, back: kindEls.back.checked};
    }

    function drawLines() {
        if (!current || !svg) { return; }
        var s = svg;
        s.querySelectorAll('.hb-fm-line').forEach(function (p) { p.remove(); });
        if (narrow.matches) { return; }
        lib.shown(current, kinds(), selected).forEach(function (e) {
            var p = document.createElementNS(SVG, 'path');
            p.setAttribute('d', lib.route(rectOf(e.from), rectOf(e.to)));
            p.setAttribute('class', 'hb-fm-line k-' + e.kind + (e.hot ? ' is-hot' : ''));
            p.setAttribute('marker-end', 'url(#' + (e.hot ? 'hbFmHeadHot' : 'hbFmHead') + ')');
            s.appendChild(p);
        });
    }

    /** @param {string} id @param {string} cls */
    function marker(id, cls) {
        var mk = document.createElementNS(SVG, 'marker');
        mk.setAttribute('id', id);
        mk.setAttribute('viewBox', '0 0 10 10');
        mk.setAttribute('refX', '9');
        mk.setAttribute('refY', '5');
        mk.setAttribute('markerWidth', '7');
        mk.setAttribute('markerHeight', '7');
        mk.setAttribute('orient', 'auto');
        var p = document.createElementNS(SVG, 'path');
        p.setAttribute('d', 'M0 0 L10 5 L0 10 z');
        p.setAttribute('class', cls);
        mk.appendChild(p);
        return mk;
    }

    var raf = 0;
    function redraw() {
        if (raf) { return; }
        raf = window.requestAnimationFrame(function () { raf = 0; fit(); drawLines(); });
    }

    // ---------- 상자: 클릭(고르기)·키·끌기 ----------

    /** @param {string} code @param {HTMLButtonElement} b */
    function wire(code, b) {
        /** @type {{id: number, sx: number, sy: number, p: HB.FlowMapPoint, moved: boolean} | null} */
        var drag = null;
        var swallow = false;
        b.addEventListener('click', function (e) {
            if (swallow) { swallow = false; return; }
            select(code, e.detail === 0);   // detail 0 = 키보드(Enter·Space)
        });
        b.addEventListener('keydown', function (e) {
            var d = {ArrowLeft: [-1, 0], ArrowRight: [1, 0], ArrowUp: [0, -1], ArrowDown: [0, 1]}[e.key];
            if (!d || narrow.matches) { return; }
            e.preventDefault();
            var k = e.shiftKey ? STEP * 4 : STEP;
            points[code] = lib.move(points[code], d[0] * k, d[1] * k);
            apply(code);
            redraw();
            savePoints();
        });
        b.addEventListener('pointerdown', function (e) {
            if (e.button !== 0 || narrow.matches) { return; }
            drag = {id: e.pointerId, sx: e.clientX, sy: e.clientY, p: points[code], moved: false};
        });
        b.addEventListener('pointermove', function (e) {
            if (!drag || e.pointerId !== drag.id) { return; }
            var dx = (e.clientX - drag.sx) / scale;   // 화면 px → 배율 1 의 px
            var dy = (e.clientY - drag.sy) / scale;
            if (!drag.moved) {
                if (Math.abs(dx) < DRAG_START && Math.abs(dy) < DRAG_START) { return; }
                drag.moved = true;
                try { b.setPointerCapture(e.pointerId); } catch (err) { /* 이미 끝난 포인터 — 끌기는 계속 */ }
                nodes[code].classList.add('is-drag');
            }
            points[code] = lib.move(drag.p, dx, dy);
            apply(code);
            redraw();
        });
        /** @param {PointerEvent} e */
        function end(e) {
            if (!drag || e.pointerId !== drag.id) { return; }
            if (drag.moved) {
                // 끌기 끝의 click 은 고르기가 아님 — click 이 안 오는 경우(다른 곳에서 놓음)를 위해 다음 턴에 푼다
                swallow = true;
                window.setTimeout(function () { swallow = false; }, 0);
                nodes[code].classList.remove('is-drag');
                savePoints();
            }
            drag = null;
        }
        b.addEventListener('pointerup', end);
        b.addEventListener('pointercancel', end);
    }

    /** @param {string} code @param {HB.FlowMapModel} m @param {string} nextId */
    function box(code, m, nextId) {
        var off = !canOpen(code);
        var b = /** @type {HTMLButtonElement} */ (hb.el('button', 'hb-fm-box' + (off ? ' is-off' : '')));
        b.type = 'button';
        b.setAttribute('aria-pressed', 'false');
        b.title = nameOf(code);
        b.appendChild(hb.el('span', 'hb-fm-name', nameOf(code)));
        b.appendChild(hb.el('span', 'hb-fm-code', code));
        var tags = hb.el('span', 'hb-fm-tags');
        if (off) { tags.appendChild(hb.status('neutral', hb.t('js.flowMap.noAuth'))); }
        b.appendChild(tags);
        if (m.next[code].length) { b.setAttribute('aria-describedby', nextId); }
        wire(code, b);
        return b;
    }

    function draw() {
        var m = lib.model(flows[flowId], links);
        current = m;
        boxes = {};
        nodes = {};
        selected = null;
        seq++;
        canvas.textContent = '';
        stage.textContent = '';
        var opt = sel.selectedOptions[0];
        descEl.textContent = opt ? opt.getAttribute('data-desc') || '' : '';
        var saved = load('pos.' + flowId);
        hasSaved = !!saved;
        points = lib.restore(lib.layout(m, GRID, avail(), FIT_MAX).points, saved);
        resetBtn.disabled = !saved;
        drawDetail();
        if (!m.cols.length) {
            canvas.appendChild(hb.el('p', 'hb-fm-empty', hb.t('js.flowMap.empty')));
            svg = null;
            return;
        }
        svg = /** @type {SVGSVGElement} */ (document.createElementNS(SVG, 'svg'));
        svg.setAttribute('class', 'hb-fm-lines');
        svg.setAttribute('aria-hidden', 'true');
        svg.setAttribute('focusable', 'false');
        var defs = document.createElementNS(SVG, 'defs');
        defs.appendChild(marker('hbFmHead', 'hb-fm-head'));
        defs.appendChild(marker('hbFmHeadHot', 'hb-fm-head is-hot'));
        svg.appendChild(defs);
        stage.appendChild(svg);
        canvas.appendChild(stage);
        m.cols.forEach(function (col, i) {
            var sec = hb.el('section', 'hb-fm-col');
            var h = hb.el('h3', 'hb-fm-sr', hb.t('js.flowMap.step', i + 1));
            h.id = 'hbFmStep' + seq + '-' + i;
            sec.setAttribute('aria-labelledby', h.id);
            sec.appendChild(h);
            var ul = hb.el('ul');
            col.forEach(function (code, j) {
                var li = hb.el('li', 'hb-fm-node');
                var nextId = 'hbFmNext' + seq + '-' + i + '-' + j;
                var b = box(code, m, nextId);
                boxes[code] = b;
                nodes[code] = li;
                li.appendChild(b);
                if (m.next[code].length) {
                    var n = hb.el('p', 'hb-fm-next hb-fm-sr', hb.t('js.flowMap.next', m.next[code].map(nameOf).join(', ')));
                    n.id = nextId;
                    li.appendChild(n);
                }
                ul.appendChild(li);
                apply(code);
            });
            sec.appendChild(ul);
            stage.appendChild(sec);
        });
        markOpenTabs();
        fit();
        drawLines();
    }

    function pick() {
        if (!FLOWS.test(sel.value)) { return; }
        flowId = sel.value;
        notify('');
        draw();
    }

    function start() {
        var q = new URLSearchParams(window.location.search).get('q.flow');
        sel.value = q && FLOWS.test(q) ? q : 'F1';
        sel.addEventListener('change', pick);
        bandOpen = load('band') !== false;
        var k = load('kinds');
        if (k && typeof k === 'object') {
            kindEls.main.checked = k.main !== false;
            kindEls.skip.checked = k.skip === true;
            kindEls.back.checked = k.back === true;
        }
        Object.keys(kindEls).forEach(function (n) {
            /** @type {HTMLInputElement} */ (kindEls[/** @type {'main' | 'skip' | 'back'} */ (n)]).addEventListener('change', function () {
                keep('kinds', {main: kindEls.main.checked, skip: kindEls.skip.checked, back: kindEls.back.checked});
                drawLines();
            });
        });
        detail.addEventListener('keydown', function (e) {
            if (e.key === 'Escape' && selected) { e.preventDefault(); unselect(); }
        });
        resetBtn.addEventListener('click', function () {
            keep('pos.' + flowId, null);
            zoom = null;
            var was = selected;
            draw();
            if (was && boxes[was]) { select(was); }
            notify(hb.t('js.flowMap.resetDone'));
        });
        Promise.all([json('/js/common/flow-map.json'), json('/js/common/help-flow.json'), hb.api('/api/home/flow-map/screens')])
            .then(function (r) {
                flows = r[0];
                links = r[1];
                /** @type {FlowMapScreen[]} */
                var list = Array.isArray(r[2] && r[2].data) ? r[2].data : [];
                list.forEach(function (s) { screens[s.progCd] = s; });
                canvas.removeAttribute('aria-busy');
                pick();
            }, function (err) {
                canvas.removeAttribute('aria-busy');
                notify(err && err.message ? err.message : hb.t('js.flowMap.loadFail'), true);
            });
        // 글꼴·폭이 바뀌면 상자 크기가 바뀐다 → 선 다시
        // 그림판 폭이 바뀌면(창·메뉴 접기) 다시 배치·맞춤, 글꼴이 늦게 와 상자 크기가 바뀌면 선 다시
        if (typeof ResizeObserver === 'function') {
            new ResizeObserver(function () { relayout(); redraw(); }).observe(scroller);
        }
        window.addEventListener('resize', redraw);
        narrow.addEventListener('change', function () { relayout(); redraw(); });
        zoomOut.addEventListener('click', function () { setZoom(scale - ZOOM_STEP); drawLines(); });
        zoomIn.addEventListener('click', function () { setZoom(scale + ZOOM_STEP); drawLines(); });
        zoomFit.addEventListener('click', function () { setZoom(null); drawLines(); });
        // 다른 탭에서 돌아오면 "열린 탭" 표시를 다시
        window.addEventListener('focus', markOpenTabs);
        canvas.addEventListener('pointerenter', markOpenTabs);
    }

    hb.loadMessages().then(function () {
        hb.page.init();
        start();
    });
})();
