// @ts-check
/*
 * hb.tip — 버튼 툴팁 기본 (docs/36 20장). 화면마다 title 을 달지 않고 이 파일 하나가 모든 버튼에 붙인다.
 * 대상: button·[role=button]·input[type=button|submit|reset]. 문서 전체 위임이라 JS 가 나중에 만든 버튼도 같다.
 * 문구 찾는 순서(먼저 찾은 것):
 *   ① data-tip-key(i18n 키) ② data-tip(이미 번역한 글) ③ js.tip.{progCd}.{id} ④ 기존 title(꺼내 쓰고 지운다)
 *   ⑤ js.tip.common.{id} / 조회 버튼(.hb-btn-search) js.tip.common.search / 상세 닫기 js.tip.common.detailClose
 *   ⑥ 버튼 이름(aria-label → 보이는 글자)
 * 보이는 때: 마우스·펜 0.5초 머묾 / 키보드 포커스(:focus-visible) 바로. 터치는 띄우지 않는다.
 * 숨김: 누름·스크롤·창 크기·Esc(이벤트는 막지 않음 — 대화상자 Esc 닫기 그대로)·포커스 이탈. 끄기: data-tip-off(자신·조상).
 * 아이콘만 있는 버튼(보이는 글자 없음)은 aria-label 이 없으면 ①~⑤ 문구로 붙인다. 인라인 style 속성 없음(CSSOM 좌표만, CSP).
 * hb.js 뒤에 넣는다. 공통 조각 fragments/page·셸 main.html·포털·운영 콘솔이 싣는다.
 */
(function (root) {
    'use strict';

    var SHOW_DELAY_MS = 500;
    /** 툴팁 하나가 막 사라진 뒤 이 시간 안에 다른 버튼으로 옮기면 바로 띄운다(도구줄을 훑을 때) */
    var WARM_MS = 300;
    var GAP = 6;
    var EDGE = 4;
    var TIP_ID = 'hb-tip';
    var SELECTOR = 'button, [role="button"], input[type="button"], input[type="submit"], input[type="reset"]';
    /** ⑤ 관례 id — 여러 화면에서 뜻이 같은 버튼만 */
    var COMMON_IDS = ['hbHelpBtn', 'hbFeedbackBtn', 'saveBtn', 'newBtn', 'newBtnList', 'excelBtn'];

    /** @param {unknown} s */
    function clean(s) {
        return typeof s === 'string' ? s.replace(/\s+/g, ' ').trim() : '';
    }

    /**
     * 툴팁 문구 고르기(DOM 없음 — node 시험). t 는 hb.t, 키가 없으면 키를 그대로 돌려준다.
     * @param {HB.TipInfo} info
     * @param {(key: string) => string} t
     * @return {HB.TipText}
     */
    function resolve(info, t) {
        var name = clean(info.ariaLabel) || clean(info.text) || clean(info.value);
        /** @param {string | null | undefined} key */
        function msg(key) {
            if (!key) { return ''; }
            var v = t(key);
            return v && v !== key ? clean(v) : '';
        }
        /** @param {string} text @param {HB.TipText['source']} source @return {HB.TipText} */
        function out(text, source) {
            return {text: text, name: name, source: source, extra: text !== name};
        }
        var s = msg(info.tipKey);
        if (s) { return out(s, 'key'); }
        s = clean(info.tip);
        if (s) { return out(s, 'tip'); }
        if (info.prog && info.id) {
            s = msg('js.tip.' + info.prog + '.' + info.id);
            if (s) { return out(s, 'prog'); }
        }
        s = clean(info.title);
        if (s) { return out(s, 'title'); }
        if (info.id && COMMON_IDS.indexOf(info.id) >= 0) {
            s = msg('js.tip.common.' + info.id);
            if (s) { return out(s, 'common'); }
        }
        if (info.role) {
            s = msg('js.tip.common.' + info.role);
            if (s) { return out(s, 'common'); }
        }
        return out(name, 'name');
    }

    /**
     * 툴팁 자리(뷰포트 좌표) — 버튼 아래 가운데, 아래가 모자라면 위, 좌우는 화면 안.
     * @param {{left: number, top: number, width: number, height: number}} r 버튼
     * @param {{width: number, height: number}} tip
     * @param {{width: number, height: number}} view
     * @return {{left: number, top: number, place: 'below' | 'above'}}
     */
    function place(r, tip, view) {
        var left = r.left + r.width / 2 - tip.width / 2;
        left = Math.max(EDGE, Math.min(left, view.width - tip.width - EDGE));
        if (left < EDGE) { left = EDGE; }
        var below = r.top + r.height + GAP;
        if (below + tip.height + EDGE <= view.height || r.top - GAP - tip.height < EDGE) {
            return {left: Math.round(left), top: Math.round(below), place: 'below'};
        }
        return {left: Math.round(left), top: Math.round(r.top - GAP - tip.height), place: 'above'};
    }

    /** @type {HB.Tip} */
    var api = {resolve: resolve, place: place, init: function () { /* 브라우저에서 아래가 바꾼다 */ }};

    if (typeof module === 'object' && module && module.exports) {
        module.exports = api;
    }
    if (typeof document === 'undefined') { return; }

    var hb = /** @type {HB.Hb | undefined} */ (/** @type {any} */ (root).hb);

    /** @param {string} key */
    function tr(key) { return hb ? hb.t(key) : key; }

    /** @type {HTMLElement | null} */
    var tipEl = null;
    /** @type {HTMLElement | null} */
    var current = null;
    /** @type {number | null} */
    var timer = null;
    var hiddenAt = 0;
    var started = false;

    /** @param {Element} el */
    function info(el) {
        var title = el.getAttribute('title');
        if (title !== null) {                         // ④ 기존 title — 꺼내 두고 지운다(브라우저 기본 툴팁과 겹치지 않게)
            el.setAttribute('data-tip-title', title);
            el.removeAttribute('title');
        }
        var input = el instanceof HTMLInputElement ? el : null;
        var role = el.classList.contains('hb-btn-search') ? 'search'
            : el.hasAttribute('data-hb-detail-close') ? 'detailClose' : null;
        return {
            tipKey: el.getAttribute('data-tip-key'),
            tip: el.getAttribute('data-tip'),
            prog: document.body ? document.body.getAttribute('data-prog') : null,
            id: el.id || null,
            title: el.getAttribute('data-tip-title'),
            role: role,
            ariaLabel: el.getAttribute('aria-label') || labelledBy(el),
            text: input ? '' : el.textContent,
            value: input ? input.value : ''
        };
    }

    /** @param {Element} el */
    function labelledBy(el) {
        var ids = (el.getAttribute('aria-labelledby') || '').split(/\s+/).filter(Boolean);
        return ids.map(function (id) {
            var x = document.getElementById(id);
            return x ? x.textContent : '';
        }).join(' ');
    }

    /** @param {EventTarget | null} t @return {HTMLElement | null} */
    function target(t) {
        var el = t instanceof Element ? t.closest(SELECTOR) : null;
        if (!(el instanceof HTMLElement) || el.closest('[data-tip-off]') || el === tipEl) { return null; }
        return el;
    }

    function ensureTip() {
        if (!tipEl) {
            tipEl = document.createElement('div');
            tipEl.id = TIP_ID;
            tipEl.className = 'hb-tip';
            tipEl.setAttribute('role', 'tooltip');
            tipEl.hidden = true;
            tipEl.addEventListener('pointerleave', function (e) {
                if (!(current && e.relatedTarget instanceof Node && current.contains(e.relatedTarget))) { hide(); }
            });
        }
        return tipEl;
    }

    /** @param {HTMLElement} el @param {boolean} on */
    function describe(el, on) {
        var list = (el.getAttribute('aria-describedby') || '').split(/\s+/).filter(function (x) { return x && x !== TIP_ID; });
        if (on) { list.push(TIP_ID); }
        if (list.length) { el.setAttribute('aria-describedby', list.join(' ')); } else { el.removeAttribute('aria-describedby'); }
    }

    /** @param {HTMLElement} el */
    function show(el) {
        clearTimer();
        if (!el.isConnected) { return; }
        var r = resolve(info(el), tr);
        if (!r.text) { hide(); return; }
        if (current && current !== el) { describe(current, false); }
        var tip = ensureTip();
        var host = el.closest('dialog[open]') || document.body;   // 모달 dialog(top layer) 안이면 그 안에
        if (tip.parentNode !== host) { host.appendChild(tip); }
        tip.textContent = r.text;
        tip.hidden = false;
        tip.style.left = '0px';
        tip.style.top = '0px';
        var b = el.getBoundingClientRect();
        var p = place(b, {width: tip.offsetWidth, height: tip.offsetHeight},
            {width: document.documentElement.clientWidth, height: document.documentElement.clientHeight});
        tip.style.left = p.left + 'px';
        tip.style.top = p.top + 'px';
        tip.setAttribute('data-place', p.place);
        current = el;
        describe(el, r.extra);
    }

    function clearTimer() {
        if (timer !== null) { clearTimeout(timer); timer = null; }
    }

    function hide() {
        clearTimer();
        if (current) { describe(current, false); }
        if (tipEl && !tipEl.hidden) { tipEl.hidden = true; hiddenAt = Date.now(); }
        current = null;
    }

    /** @param {HTMLElement} el */
    function later(el) {
        clearTimer();
        if (Date.now() - hiddenAt < WARM_MS || (tipEl && !tipEl.hidden)) { show(el); return; }
        timer = setTimeout(function () { show(el); }, SHOW_DELAY_MS);
    }

    // ---------- 아이콘만 있는 버튼의 aria-label ----------
    /** @param {Element} el */
    function iconOnly(el) {
        if (el instanceof HTMLInputElement) { return false; }
        if (!el.firstElementChild || clean(el.textContent)) { return false; }   // 속이 빈 버튼 = 화면 JS 가 글자를 채운다
        return !el.querySelector('img[alt]:not([alt=""])');
    }

    /** @param {Element} el */
    function label(el) {
        if (el.hasAttribute('aria-label') || el.hasAttribute('aria-labelledby') || !iconOnly(el)) { return; }
        var r = resolve(info(el), tr);
        if (r.source !== 'name' && r.text) {
            el.setAttribute('aria-label', r.text);
        } else if (root.console && el.id !== TIP_ID) {
            root.console.warn('[hb.tip] 아이콘만 있는 버튼에 이름이 없습니다 — aria-label 또는 data-tip-key 를 다세요', el);
        }
    }

    /** @param {ParentNode} scope */
    function labelAll(scope) {
        if (scope instanceof Element && scope.matches(SELECTOR)) { label(scope); }
        scope.querySelectorAll(SELECTOR).forEach(label);
    }

    function init() {
        if (started) { return; }
        started = true;
        document.addEventListener('pointerover', function (e) {
            if (e.pointerType === 'touch') { return; }
            var el = target(e.target);
            if (!el) { return; }
            if (el !== current) { later(el); }
        });
        document.addEventListener('pointerout', function (e) {
            var el = target(e.target);
            if (!el) { return; }
            var to = e.relatedTarget instanceof Node ? e.relatedTarget : null;
            if (to && (el.contains(to) || (tipEl && tipEl.contains(to)))) { return; }
            if (el === current || timer !== null) { hide(); }
        });
        document.addEventListener('pointerdown', function () { hide(); }, true);
        document.addEventListener('focusin', function (e) {
            var el = target(e.target);
            if (!el) { return; }
            var keyboard = false;
            try { keyboard = el.matches(':focus-visible'); } catch (x) { keyboard = false; }
            if (keyboard) { show(el); }
        });
        document.addEventListener('focusout', function (e) {
            if (current && e.target === current) { hide(); }
        });
        document.addEventListener('keydown', function (e) {
            if (e.key === 'Escape' && current) { hide(); }     // 막지 않는다 — 대화상자·서랍 Esc 닫기 그대로
        }, true);
        document.addEventListener('click', function (e) {
            if (current && e.target instanceof Node && current.contains(e.target)) { hide(); }
        }, true);
        root.addEventListener('scroll', function () { if (current) { hide(); } }, true);
        root.addEventListener('resize', function () { if (current) { hide(); } });
        root.addEventListener('blur', function () { hide(); });

        labelAll(document);
        if (typeof MutationObserver === 'function') {
            new MutationObserver(function (records) {
                records.forEach(function (rec) {
                    rec.addedNodes.forEach(function (n) {
                        if (n instanceof Element && n !== tipEl) { labelAll(n); }
                    });
                    if (current && !current.isConnected) { hide(); }
                });
            }).observe(document.documentElement, {childList: true, subtree: true});
        }
    }

    api.init = init;
    if (hb) {
        hb.tip = api;
        // 키 문구(①·③·⑤)는 문구를 받은 뒤에야 이름이 된다. 화면이 부르는 hb.loadMessages 뒤에 한 번 더 붙인다 —
        // 여기서 따로 부르지 않는다(문구 API 를 쓰지 않는 페이지에서 요청·401 이동이 생기지 않게).
        var h = hb;
        var load = h.loadMessages;
        h.loadMessages = function () {
            var p = load.call(h);
            p.then(function () { if (started) { labelAll(document); } }, function () { /* 문구 없음 — 경고만 남는다 */ });
            return p;
        };
    }
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }
})(typeof window !== 'undefined' ? window : globalThis);
