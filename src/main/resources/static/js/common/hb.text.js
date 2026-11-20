// @ts-check
/*
 * hb.text — 사람이 적는 필수 글 칸의 "보이는 글자가 있는가" (QA R4-a, 서버 io.smarthappy.platform.common.text.VisibleText 와 같은 정의)
 *   hb.text.isBlank(s)        → 보이는 글자가 하나도 없음(null·'' 포함) — 필수 사유 칸 확인에 `!s.trim()` 대신
 *   hb.text.visibleLength(s)  → 보이는 글자 수(코드 포인트 — 한글 한 음절·이모지 하나 = 1). "2자 이상" 은 이것으로
 *   hb.text.strip(s)          → 앞뒤의 보이지 않는 글자를 걷어낸 값(가운데 ZWJ 등은 그대로), 보이는 글자가 없으면 ''
 * 보이지 않는 글자 = 공백·구분자(\p{Z} — NBSP·U+3000)·형식 문자(\p{Cf} — U+200B~200F·U+2060~2064·U+FEFF·U+00AD)·제어(\p{Cc})
 *                   + 한글 채움 문자 U+115F·U+1160·U+3164·U+FFA0, 점자 빈칸 U+2800.
 * 화면 확인은 편의일 뿐 — 서버가 같은 규칙으로 다시 막는다(400 REQUIRED). hb.js 뒤, hb.dialog.js 앞에 넣는다(prompt required 가 이것을 쓴다).
 */
(function (root) {
    'use strict';

    var INVISIBLE = /[\s\p{Z}\p{Cf}\p{Cc}\u115F\u1160\u3164\uFFA0\u2800]/u;

    /** @param {string} ch 코드 포인트 하나 */
    function invisible(ch) { return INVISIBLE.test(ch); }

    /** @param {unknown} s @return {number} */
    function visibleLength(s) {
        if (typeof s !== 'string') { return 0; }
        var n = 0;
        for (var ch of s) {
            if (!invisible(ch)) { n++; }
        }
        return n;
    }

    /** @param {unknown} s @return {boolean} */
    function isBlank(s) { return visibleLength(s) === 0; }

    /** @param {unknown} s @return {string} */
    function strip(s) {
        if (typeof s !== 'string') { return ''; }
        var cps = Array.from(s);
        var from = 0, to = cps.length;
        while (from < to && invisible(cps[from])) { from++; }
        while (to > from && invisible(cps[to - 1])) { to--; }
        return cps.slice(from, to).join('');
    }

    /** @type {HB.Text} */
    var text = {isBlank: isBlank, visibleLength: visibleLength, strip: strip};

    // node 단위 시험(src/test/js/hb.text.test.js) — 브라우저에는 module 이 없다
    if (typeof module === 'object' && module && module.exports) {
        module.exports = text;
    }
    var hb = /** @type {any} */ (root).hb;
    if (hb) { hb.text = text; }
})(typeof window !== 'undefined' ? window : globalThis);
