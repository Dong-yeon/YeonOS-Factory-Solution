// @ts-check
/*
 * hb.numbers — 숫자 입력 해석·표시 (docs/16 6.5·7.6). DOM 을 만지지 않는 순수 함수.
 * 브라우저(window.hb.numbers)와 node 단위 테스트(module.exports) 양쪽에서 쓴다.
 * 편집 그리드 붙여넣기 파서(dev/grid-poc/paste-parser.js)도 이 해석을 쓴다 — 숫자 규칙은 이 파일 하나.
 *
 * - 값은 **10진 문자열**로 다룬다('1234.5'). 부동소수로 바꾸지 않아 자릿수가 조용히 바뀌지 않는다(서버는 BigDecimal).
 * - 받는 형식: 천 단위 쉼표(3자리 묶음만), 소수, 음수(-1, 회계 괄호 (1,234)), +1, 전각 숫자·기호(NFKC), 앞뒤 공백.
 *   거부: 지수(1e5), 통화 기호, 쉼표 위치 틀림(1,23), 소수점 둘, 앞뒤 점(.5 / 5.)
 * - 자릿수(scale) 초과는 반올림하지 않고 SCALE 오류. 끝의 0 은 자릿수로 세지 않는다(1.230 은 scale 2 에서 1.23).
 * - min·max 비교도 문자열 10진 비교(15자리 넘는 값도 정확).
 */
/**
 * @param {{hb?: Partial<HB.Hb>}} root
 * @param {() => HB.Numbers} factory
 */
(function (root, factory) {
    'use strict';
    var api = factory();
    if (typeof module === 'object' && module.exports) {
        module.exports = api;
    } else {
        root.hb = root.hb || {};
        root.hb.numbers = api;
    }
})(typeof window !== 'undefined' ? window : globalThis, function () {
    'use strict';

    var TRIM = /^[\s　 ﻿]+|[\s　 ﻿]+$/g;
    var PLAIN = /^\d+(\.\d+)?$/;
    // 첫 묶음은 1~999(0 으로 시작하지 않음) — 0,123 은 소수점 쉼표 표기(0.123)일 수 있어 천 단위로 읽지 않고 형식 오류(QA N-a)
    var GROUPED = /^[1-9]\d{0,2}(,\d{3})+(\.\d+)?$/;

    /** @typedef {{neg: boolean, int: string, frac: string}} Parts */

    /**
     * '007.50' → {neg:false, int:'7', frac:'5'} (정규화: 앞 0·끝 0 제거, -0 → 0)
     * @param {string} digits
     * @param {boolean} negative
     * @return {Parts}
     */
    function split(digits, negative) {
        var dot = digits.indexOf('.');
        var i = (dot < 0 ? digits : digits.substring(0, dot)).replace(/^0+(?=\d)/, '');
        var f = dot < 0 ? '' : digits.substring(dot + 1).replace(/0+$/, '');
        var zero = /^0*$/.test(i) && f === '';
        return {neg: negative && !zero, int: i || '0', frac: f};
    }

    /** @param {Parts} p */
    function join(p) {
        return (p.neg ? '-' : '') + p.int + (p.frac ? '.' + p.frac : '');
    }

    /** 정규화된 10진 문자열 → 부분. 형식이 틀리면 null @param {string | number} value @return {Parts | null} */
    function parts(value) {
        var s = String(value);
        var neg = s.charAt(0) === '-';
        var body = neg ? s.substring(1) : s;
        if (!PLAIN.test(body)) {
            return null;
        }
        return split(body, neg);
    }

    /**
     * a < b → -1, 같으면 0, a > b → 1 (정규화된 10진 문자열 또는 숫자). 형식이 틀리면 TypeError(조용히 틀린 답을 내지 않는다)
     * @param {string | number} a
     * @param {string | number} b
     */
    function compare(a, b) {
        var x = parts(a);
        var y = parts(b);
        if (!x || !y) {
            throw new TypeError('hb.numbers.compare: 숫자 형식이 아님 ' + a + ' / ' + b);
        }
        if (x.neg !== y.neg) {
            return x.neg ? -1 : 1;
        }
        var sign = x.neg ? -1 : 1;
        if (x.int.length !== y.int.length) {
            return (x.int.length < y.int.length ? -1 : 1) * sign;
        }
        if (x.int !== y.int) {
            return (x.int < y.int ? -1 : 1) * sign;
        }
        var len = Math.max(x.frac.length, y.frac.length);
        var fx = pad(x.frac, len);
        var fy = pad(y.frac, len);
        if (fx === fy) {
            return 0;
        }
        return (fx < fy ? -1 : 1) * sign;
    }

    /** @param {string} frac @param {number} len */
    function pad(frac, len) {
        while (frac.length < len) {
            frac += '0';
        }
        return frac;
    }

    /** 상한·하한 — 숫자 형식이 아니면 쓰지 않는다(화면 선언 오타로 비교가 깨지지 않게) @param {string | number | null | undefined} v @return {string | null} */
    function bound(v) {
        var p = v === undefined || v === null || v === '' ? null : parts(v);
        return p ? join(p) : null;
    }

    /**
     * 입력 글자 해석.
     * opts: scale(소수 자릿수 상한, 0 = 정수만), min, max, allowNegative(기본 true)
     * @param {unknown} raw
     * @param {HB.NumberParseOptions} [options]
     * @return {HB.NumberParse}  value 예 '1234.5', 빈 칸은 null
     */
    function parse(raw, options) {
        var opts = options || {};
        var s = raw === null || raw === undefined ? '' : String(raw).replace(TRIM, '');
        if (s === '') {
            return {ok: true, value: null};
        }
        s = (s.normalize ? s.normalize('NFKC') : s).replace(TRIM, '');
        var negative = false;
        if (/^\(.*\)$/.test(s)) {
            negative = true;
            s = s.substring(1, s.length - 1);
        } else if (s.charAt(0) === '-') {
            negative = true;
            s = s.substring(1);
        } else if (s.charAt(0) === '+') {
            s = s.substring(1);
        }
        if (!PLAIN.test(s) && !GROUPED.test(s)) {
            return {ok: false, code: 'NUMBER'};
        }
        var p = split(s.replace(/,/g, ''), negative);
        if (p.neg && opts.allowNegative === false) {
            return {ok: false, code: 'NEGATIVE'};
        }
        if (opts.scale !== undefined && opts.scale !== null && p.frac.length > opts.scale) {
            return {ok: false, code: 'SCALE'};
        }
        var value = join(p);
        var min = bound(opts.min);
        var max = bound(opts.max);
        if (min !== null && compare(value, min) < 0) {
            return {ok: false, code: 'MIN'};
        }
        if (max !== null && compare(value, max) > 0) {
            return {ok: false, code: 'MAX'};
        }
        return {ok: true, value: value};
    }

    /**
     * 정규화된 값 → 소수 자릿수 고정 문자열('1234.5', 3 → '1234.500'). scale 이 없으면 그대로.
     * 숫자 형식이 아니면(예: 1e-7) 글자 그대로 — 화면 표시가 예외로 멈추지 않게
     * @param {string | number | null | undefined} value
     * @param {number | null} [scale]
     * @return {string | null}
     */
    function fixed(value, scale) {
        if (value === null || value === undefined || value === '') {
            return null;
        }
        var p = parts(value);
        if (!p) {
            return String(value);
        }
        if (scale === undefined || scale === null) {
            return join(p);
        }
        return (p.neg ? '-' : '') + p.int + (scale > 0 ? '.' + pad(p.frac, scale) : '');
    }

    /** 표시용: 천 단위 쉼표 + 자릿수 고정('12345.5', 2 → '12,345.50') @param {string | number | null | undefined} value @param {number | null} [scale] */
    function format(value, scale) {
        var f = fixed(value, scale);
        if (f === null) {
            return '';
        }
        var neg = f.charAt(0) === '-';
        var body = neg ? f.substring(1) : f;
        var dot = body.indexOf('.');
        var i = dot < 0 ? body : body.substring(0, dot);
        var grouped = i.replace(/\B(?=(\d{3})+(?!\d))/g, ',');
        return (neg ? '-' : '') + grouped + (dot < 0 ? '' : body.substring(dot));
    }

    return {parse: parse, fixed: fixed, format: format, compare: compare};
});
