// @ts-check
/*
 * 화면 공통 스캔 입력 hb.scan (docs/50 7장 C1~C4) — hb.js 위에 얹는다. 환경을 보고 구현을 고른다.
 *
 * hb.scan.listen(handler, opts?) → 해제 함수
 *   handler({value, source})   source = 'wedge'(키보드 웨지) | 'native'(APK 스캐너 SDK — window 이벤트 hb:scan)
 *   opts.input   이 칸에서 Enter 로 끝난 값(포커스 칸 + Enter — 숨은 스캔 칸·바코드 입력 칸)
 *   opts 없음    문서 전체: 입력 칸 밖(버튼·빈 곳)에 포커스가 있을 때 빠른 키 입력(글자 간격 ≤ gapMs) + Enter, 길이 ≥ minLength
 *                — 입력 칸에 포커스가 있으면 건드리지 않는다(사람이 치는 글자를 가로채지 않게)
 *   한 화면에 듣는 곳은 하나 — 두 번째 listen 은 앞 것을 해제한다(값이 두 곳에 들어가지 않게, C1)
 * hb.scan.clean(v) → 같은 값 정리(쓸 수 없으면 '') — showModal 대화상자처럼 화면 스캔 칸이 막힌 곳이 직접 받은 값에(docs/50 KS10)
 *
 * 값 정리(C3): 제어 문자 제거(GS1 구분자 \u001d 는 남김), 앞뒤 공백 제거, 200자 상한, 빈 값은 버린다.
 * 서버가 다시 검증한다 — 스캔이라고 믿지 않는다. 카메라(C5)는 아직 없다.
 * hb.pop.scanner 는 이 위의 감싸기로 바뀐다(C6, MES 몫).
 */
(function (global) {
    'use strict';

    var MAX = 200;
    var GAP_MS = 50;
    var MIN_LENGTH = 3;
    var EVENT = 'hb:scan';
    // 제어 문자(GS \u001d 제외) + 보이지 않는 방향 문자
    var CONTROL = /[\u0000-\u001c\u001e\u001f\u007f-\u009f​-‏‪-‮⁦-⁩]/g;

    /** @param {unknown} v @return {string} 정리한 값(쓸 수 없으면 '') */
    function clean(v) {
        if (typeof v !== 'string') { return ''; }
        var s = v.replace(CONTROL, '').trim();
        return s.length > MAX ? s.slice(0, MAX) : s;
    }

    /** @param {Element | null | undefined} el */
    function editable(el) {
        if (!el) { return false; }
        var tag = el.tagName;
        return tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT' || /** @type {HTMLElement} */ (el).isContentEditable === true;
    }

    /**
     * @param {{doc: Document, win: Window, now: () => number}} env 브라우저면 document·window·Date.now (시험은 가짜)
     */
    function makeScan(env) {
        /** @type {(() => void) | null} */
        var current = null;

        /**
         * @param {(r: {value: string, source: 'wedge' | 'native'}) => void} handler
         * @param {{input?: HTMLInputElement, gapMs?: number, minLength?: number}} [opts]
         * @return {() => void}
         */
        function listen(handler, opts) {
            if (current) { current(); }
            opts = opts || {};
            var gap = opts.gapMs || GAP_MS;
            var minLength = opts.minLength || MIN_LENGTH;
            var done = false;
            /** @type {Array<() => void>} */
            var offs = [];

            /** @param {string} raw @param {'wedge' | 'native'} source */
            function emit(raw, source) {
                var value = clean(raw);
                if (!done && value) { handler({value: value, source: source}); }
            }

            if (opts.input) {
                var input = opts.input;
                /** @param {KeyboardEvent} e */
                var onInputKey = function (e) {
                    if (e.key !== 'Enter' || e.isComposing) { return; }
                    e.preventDefault();
                    var v = input.value;
                    input.value = '';
                    emit(v, 'wedge');
                };
                input.addEventListener('keydown', onInputKey);
                offs.push(function () { input.removeEventListener('keydown', onInputKey); });
            } else {
                var buf = '';
                var last = 0;
                /** @param {KeyboardEvent} e */
                var onDocKey = function (e) {
                    if (editable(env.doc.activeElement) || e.ctrlKey || e.altKey || e.metaKey || e.isComposing) {
                        buf = '';
                        return;
                    }
                    var t = env.now();
                    if (buf && t - last > gap) { buf = ''; }   // 느린 입력 = 사람
                    last = t;
                    if (e.key === 'Enter') {
                        if (buf.length >= minLength) {
                            e.preventDefault();   // 스캔으로 본 Enter 는 눌린 버튼을 누르지 않는다
                            emit(buf, 'wedge');
                        }
                        buf = '';
                    } else if (e.key && e.key.length === 1) {
                        buf += e.key;
                    }
                };
                env.doc.addEventListener('keydown', onDocKey, true);
                offs.push(function () { env.doc.removeEventListener('keydown', onDocKey, true); });
            }

            // APK 스캐너 SDK(C4) — 플러그인이 없으면(브라우저) 이 이벤트가 오지 않는다
            /** @param {Event} e */
            var onNative = function (e) {
                var detail = /** @type {CustomEvent} */ (e).detail;
                emit(detail && detail.value, 'native');
            };
            env.win.addEventListener(EVENT, onNative);
            offs.push(function () { env.win.removeEventListener(EVENT, onNative); });

            function off() {
                if (done) { return; }
                done = true;
                offs.forEach(function (f) { f(); });
                if (current === off) { current = null; }
            }
            current = off;
            return off;
        }

        return {listen: listen};
    }

    if (global.hb && typeof document !== 'undefined') {
        var made = makeScan({doc: document, win: window, now: Date.now});
        // clean = 같은 값 정리(C3)를 스캔을 직접 받는 곳(키오스크 대화상자 안 숨은 칸 — docs/50 KS10)도 쓰게
        global.hb.scan = {listen: made.listen, clean: clean};
    }
    // node 단위 시험(src/test/js/hb.scan.test.js) — 브라우저에는 module 이 없다
    if (typeof module === 'object' && module && module.exports) {
        module.exports = {makeScan: makeScan, clean: clean};
    }
})(typeof window !== 'undefined' ? window : globalThis);
