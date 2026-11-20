// @ts-check
/*
 * 표준 화면 공통 도구 (docs/11 Top #10): 도움말 패널 + "의견 보내기"
 * - 도움말: [도움말] = 화면 도움말 대화상자(hb.help, docs/46 — 왜·언제·주요 동작·앞뒤 화면). 그 안 [칸 도움말 켜기] 또는
 *   hb.help 가 없으면 예전 패널: 포커스한 입력의 data-help-key → 문구 키 help.{key}. 없으면 화면 도움말 help.{progCd}
 * - 의견: 이 화면에 대한 짧은 글 → /api/platform/feedback (회사 관리자가 본다)
 * - 관련 화면(docs/79 3.0 방식 A): 템플릿의 [data-prog-links="화면,화면"] 칸에 내가 조회 권한을 가진 화면만 단추로 —
 *   메뉴에서 뺀(합친) 화면을 남는 화면에서 연다. 판정은 서버(/api/platform/prog-links), 셸 탭(셸 밖이면 그 화면 주소)
 * 화면 템플릿에 #hbHelpBtn, #hbFeedbackBtn, aside#hbHelp 가 있으면 동작한다. body[data-prog] 필수.
 */
(function (global) {
    'use strict';

    /** 도움말 문구 키 접두어 (help.{progCd}.{field}) — /api/platform/messages 가 js.* 와 함께 내려준다 */
    var HELP_PREFIX = 'help.';

    /** @param {string} key */
    function has(key) {
        return hb.t(key) !== key;
    }

    function init() {
        var prog = document.body.getAttribute('data-prog') || '';
        var panel = document.getElementById('hbHelp');
        var helpBtn = /** @type {HTMLButtonElement | null} */ (document.getElementById('hbHelpBtn'));
        var fbBtn = /** @type {HTMLButtonElement | null} */ (document.getElementById('hbFeedbackBtn'));

        /** @param {string | null} key */
        function showHelp(key) {
            if (!panel) { return; }
            var fieldKey = key ? HELP_PREFIX + key : null;
            var pageKey = HELP_PREFIX + prog;
            var text = fieldKey && has(fieldKey) ? hb.t(fieldKey) : has(pageKey) ? hb.t(pageKey) : hb.t('js.help.none');
            panel.textContent = '';
            panel.appendChild(hb.el('h2', null, hb.t('js.help.title')));
            panel.appendChild(hb.el('p', null, text));
        }

        if (helpBtn && panel) {
            var help = panel;
            var btn = helpBtn;
            /** @param {string | null} key */
            function showPanel(key) {
                help.hidden = false;
                btn.setAttribute('aria-expanded', 'true');
                showHelp(key);
            }
            // 칸 도움말 패널을 쓰는 칸 — 버튼을 누르기 직전 포커스(버튼 클릭은 포커스를 버튼으로 옮긴다)
            /** @type {string | null} */
            var lastKey = null;
            document.addEventListener('focusin', function (e) {
                var target = e.target instanceof Element ? e.target : null;
                if (target && target !== btn) { lastKey = target.getAttribute('data-help-key'); }
            });
            helpBtn.addEventListener('click', function () {
                if (!help.hidden) {                       // 패널이 열려 있으면 닫기만 (예전 동작 그대로)
                    help.hidden = true;
                    btn.setAttribute('aria-expanded', 'false');
                    return;
                }
                var key = lastKey;
                if (!hb.help) { showPanel(key); return; }
                // 화면 도움말 대화상자(docs/46) — 왜·언제·주요 동작·앞뒤 화면 + 칸 목록, [칸 도움말 켜기] = 예전 패널
                var h1 = document.querySelector('h1');
                hb.help.open({
                    prog: prog,
                    title: (h1 && h1.textContent ? h1.textContent.trim() : '') || document.title,
                    focusKey: key,
                    onFieldHelp: function () { showPanel(key); }
                }).catch(function () { showPanel(key); });
            });
            document.addEventListener('focusin', function (e) {
                var target = e.target instanceof Element ? e.target : null;
                if (!help.hidden && target && target.getAttribute('data-help-key')) {
                    showHelp(target.getAttribute('data-help-key'));
                }
            });
        }

        if (fbBtn) {
            fbBtn.addEventListener('click', function () {
                var d = hb.el('dialog', 'hb-dialog');
                d.appendChild(hb.el('h2', null, hb.t('js.feedback.title')));
                d.appendChild(hb.el('p', 'hb-muted', hb.t('js.feedback.guide')));
                var ta = hb.el('textarea', 'hb-feedback-text');
                ta.maxLength = 2000;
                ta.rows = 6;
                ta.setAttribute('aria-label', hb.t('js.feedback.title'));
                d.appendChild(ta);
                var out = hb.el('p', 'hb-msg');
                out.setAttribute('role', 'status');
                d.appendChild(out);
                var bar = hb.el('div', 'hb-dialog-bar');
                var send = hb.el('button', null, hb.t('js.feedback.send'));
                send.type = 'button';
                var cancel = hb.el('button', 'hb-btn-secondary', hb.t('js.common.close'));
                cancel.type = 'button';
                bar.appendChild(send);
                bar.appendChild(cancel);
                d.appendChild(bar);
                cancel.addEventListener('click', function () { d.close(); });
                send.addEventListener('click', function () {
                    if (!ta.value.trim()) { out.textContent = hb.t('js.feedback.empty'); return; }
                    hb.busy(send, function () {
                        return hb.api('/api/platform/feedback', {method: 'POST', body: {progCd: prog, content: ta.value}});
                    }).then(function (res) {
                        if (!res) { return; }
                        out.textContent = hb.t('js.feedback.thanks');
                        setTimeout(function () { d.close(); }, 1200);
                    }, function (err) { out.textContent = err.message; });
                });
                d.addEventListener('close', function () { d.remove(); });
                document.body.appendChild(d);
                d.showModal();
                ta.focus();
            });
        }
    }

    // ---------- 상세 서랍 (docs/15 R3) — 작업 영역 < 900 에서만 CSS 가 서랍으로 보인다. 넓을 땐 속성만 바뀌고 모양은 그대로 ----------
    /** @type {Element | null} */
    var lastOpener = null;

    function splitEl() { return document.querySelector('.hb-split'); }

    function isDrawer() { return window.matchMedia('(max-width: 899px)').matches; }

    /** 행 선택·신규 때 화면 JS 가 부른다 */
    function openDetail() {
        var s = splitEl();
        if (!s) { return; }
        lastOpener = document.activeElement;
        s.setAttribute('data-detail', 'open');
        if (isDrawer()) {
            var side = s.querySelector('.hb-split-side');
            // 상세 폼의 첫 입력칸으로(읽기 전용·비활성 제외), 없으면 닫기 버튼
            var sel = 'input:not([type=hidden]):not([disabled]):not([readonly]), select:not([disabled]), textarea:not([disabled]):not([readonly])';
            var first = /** @type {HTMLElement | null} */ (side && (side.querySelector('.hb-form ' + sel.split(', ').join(', .hb-form ')) || side.querySelector('[data-hb-detail-close]')));
            if (first) { first.focus(); }
        }
    }

    function closeDetail() {
        var s = splitEl();
        if (!s || s.getAttribute('data-detail') !== 'open') { return; }
        s.removeAttribute('data-detail');
        if (lastOpener && document.contains(lastOpener)) { /** @type {HTMLElement} */ (lastOpener).focus(); }
    }

    function initDrawer() {
        document.querySelectorAll('[data-hb-detail-close]').forEach(function (b) {
            b.addEventListener('click', closeDetail);
        });
        document.addEventListener('keydown', function (e) {
            if (e.key === 'Escape' && isDrawer() && !document.querySelector('dialog[open]')) { closeDetail(); }
        });
    }

    // ---------- 목록 | 상세 폭 조절 — 작업 영역 ≥ 900 에서만(서랍일 땐 CSS 가 막대를 숨긴다) ----------
    // 끌기·←/→(Shift 는 크게)·더블클릭/Enter = 기본 폭. 폭은 화면별로 이 기기에 기억(localStorage, 값 검증).
    var SIDE_MIN = 280;     // 상세 폼 1열이 깨지지 않는 폭
    var MAIN_MIN = 360;     // 목록 그리드가 열 2~3개는 보이는 폭

    function initSplitter() {
        var split = splitEl();
        var sideEl = /** @type {HTMLElement | null} */ (split && split.querySelector('.hb-split-side'));
        var main = split && split.querySelector('.hb-split-main');
        if (!split || !sideEl || !main) { return; }
        var s = split;
        var side = sideEl;
        var key = 'hb.split.' + (document.body.getAttribute('data-prog') || '');
        var bar = hb.el('div', 'hb-splitter');
        bar.setAttribute('role', 'separator');
        bar.setAttribute('aria-orientation', 'vertical');
        bar.setAttribute('aria-label', hb.t('js.page.splitter'));
        bar.tabIndex = 0;
        s.insertBefore(bar, side);
        s.classList.add('hb-split-resizable');

        function store() {
            try { return window.localStorage; } catch (e) { return null; }
        }

        function maxSide() { return Math.max(SIDE_MIN, s.clientWidth - bar.offsetWidth - MAIN_MIN); }

        /** @param {number | null} w 상세 폭(px), null = 기본 폭 @param {boolean} save */
        function apply(w, save) {
            if (w === null) {
                side.style.removeProperty('flex-basis');
                bar.removeAttribute('aria-valuenow');
                var st = store();
                if (save && st) { st.removeItem(key); }
                return;
            }
            w = Math.round(Math.min(Math.max(w, SIDE_MIN), maxSide()));
            side.style.flexBasis = w + 'px';
            bar.setAttribute('aria-valuemin', String(SIDE_MIN));
            bar.setAttribute('aria-valuemax', String(maxSide()));
            bar.setAttribute('aria-valuenow', String(w));
            var st2 = store();
            if (save && st2) { st2.setItem(key, String(w)); }
        }

        function saved() {
            var st = store();
            var v = st ? st.getItem(key) : null;
            return v && /^\d{3,4}$/.test(v) ? Number(v) : null;
        }

        function restore() {
            if (isDrawer()) { side.style.removeProperty('flex-basis'); return; }
            var v = saved();
            if (v !== null) { apply(v, false); }
        }

        bar.addEventListener('pointerdown', function (e) {
            if (e.button !== 0 || isDrawer()) { return; }
            e.preventDefault();
            bar.setPointerCapture(e.pointerId);
            s.classList.add('hb-split-dragging');
            var right = s.getBoundingClientRect().right;
            /** @param {PointerEvent} ev */
            function move(ev) { apply(right - ev.clientX - bar.offsetWidth / 2, false); }
            function up() {
                bar.removeEventListener('pointermove', move);
                bar.removeEventListener('pointerup', up);
                bar.removeEventListener('pointercancel', up);
                s.classList.remove('hb-split-dragging');
                apply(side.getBoundingClientRect().width, true);
            }
            bar.addEventListener('pointermove', move);
            bar.addEventListener('pointerup', up);
            bar.addEventListener('pointercancel', up);
        });
        bar.addEventListener('dblclick', function () { apply(null, true); });
        bar.addEventListener('keydown', function (e) {
            var step = e.shiftKey ? 80 : 16;
            var w = side.getBoundingClientRect().width;
            if (e.key === 'ArrowLeft') { apply(w + step, true); }          // 막대를 왼쪽으로 = 상세가 넓어진다
            else if (e.key === 'ArrowRight') { apply(w - step, true); }
            else if (e.key === 'Enter') { apply(null, true); }
            else { return; }
            e.preventDefault();
        });
        window.addEventListener('resize', restore);
        restore();
    }

    /** 셸 탭으로(이미 열려 있으면 그 탭), 셸 밖이면 그 화면 주소로 @param {string} progCd @param {string} title */
    function openProg(progCd, title) {
        var top = /** @type {any} */ (window.top);
        var shell = top && top !== window ? top.hbShell : null;
        if (shell && shell !== true) {
            shell.open(progCd, title);
            return;
        }
        window.location.href = '/page/' + encodeURIComponent(progCd);
    }

    /** [data-prog-links] 칸 — 권한 있는 화면만 단추로, 하나도 없으면 칸을 숨긴 채 둔다 */
    function initProgLinks() {
        var box = /** @type {HTMLElement | null} */ (document.querySelector('[data-prog-links]'));
        if (!box) { return; }
        var host = box;
        var codes = (host.getAttribute('data-prog-links') || '').split(',')
            .map(function (c) { return c.trim(); }).filter(function (c) { return !!c; });
        if (!codes.length) { return; }
        hb.api('/api/platform/prog-links?progs=' + encodeURIComponent(codes.join(','))).then(function (res) {
            /** @type {{progCd: string, name: string}[]} */
            var list = res.data || [];
            list.forEach(function (p) {
                var b = hb.el('button', 'hb-btn-secondary', p.name);
                b.type = 'button';
                b.setAttribute('data-prog-link', p.progCd);
                b.addEventListener('click', function () { openProg(p.progCd, p.name); });
                host.appendChild(b);
            });
            host.hidden = list.length === 0;
        }, function () { host.hidden = true; });
    }

    global.hb.page = {
        init: function () { init(); initDrawer(); initSplitter(); initProgLinks(); },
        openDetail: openDetail,
        closeDetail: closeDetail
    };
})(window);
