// @ts-check
/*
 * hb.dates — 날짜 문자열('YYYY-MM-DD') 유틸 (docs/16 7.1). Date 객체는 UTC 정수 계산에만 쓴다.
 * - "오늘"은 회사 시간대(hb.session().timeZone) — toISOString() 의 UTC 날짜를 쓰지 않는다(한국 00~09시에 하루 어긋남).
 * - 직접 입력·붙여넣기 해석, 프리셋, 일수(종료일 포함).
 * 브라우저(window.hb.dates)와 node 단위 테스트(module.exports) 양쪽에서 쓴다 — DOM 을 만지지 않는다.
 */
/**
 * @param {{hb?: Partial<HB.Hb>}} root
 * @param {() => HB.Dates} factory
 */
(function (root, factory) {
    'use strict';
    var api = factory();
    if (typeof module === 'object' && module.exports) {
        module.exports = api;
    } else {
        root.hb = root.hb || {};
        root.hb.dates = api;
    }
})(typeof window !== 'undefined' ? window : globalThis, function () {
    'use strict';

    var ISO = /^(\d{4})-(\d{2})-(\d{2})$/;
    var PRESETS = ['today', 'yesterday', 'thisWeek', 'last7', 'last30', 'thisMonth', 'lastMonth'];

    /** @param {number} n */
    function pad(n) {
        return (n < 10 ? '0' : '') + n;
    }

    /** @param {number} y @param {number} m @param {number} d */
    function fromParts(y, m, d) {
        return y + '-' + pad(m) + '-' + pad(d);
    }

    /** 실제로 있는 날짜인지 (2월 30일·13월 X) @param {number} y @param {number} m @param {number} d */
    function valid(y, m, d) {
        if (y < 1900 || y > 2999 || m < 1 || m > 12 || d < 1) {
            return false;
        }
        return d <= daysInMonth(y, m);
    }

    /** @param {number} y @param {number} m */
    function daysInMonth(y, m) {
        return new Date(Date.UTC(y, m, 0)).getUTCDate();
    }

    /** 'YYYY-MM-DD' → UTC 자정 ms. 형식이 틀리면 NaN @param {string} iso */
    function toUtc(iso) {
        var p = ISO.exec(iso);
        return p ? Date.UTC(+p[1], +p[2] - 1, +p[3]) : NaN;
    }

    /** @param {number} ms */
    function fromUtc(ms) {
        var d = new Date(ms);
        return fromParts(d.getUTCFullYear(), d.getUTCMonth() + 1, d.getUTCDate());
    }

    /** @param {string} iso @param {number} days */
    function add(iso, days) {
        return fromUtc(toUtc(iso) + days * 86400000);
    }

    /** 종료일 포함 일수 — 같은 날 1, 순서가 바뀌면 0 이하 @param {string} from @param {string} to */
    function diffDays(from, to) {
        return Math.round((toUtc(to) - toUtc(from)) / 86400000) + 1;
    }

    /** 0=일 … 6=토 @param {string} iso */
    function weekday(iso) {
        return new Date(toUtc(iso)).getUTCDay();
    }

    /** @param {string} a @param {string} b */
    function compare(a, b) {
        return a < b ? -1 : a > b ? 1 : 0;   // ISO 문자열은 사전순 = 날짜순
    }

    /** 시간대의 오늘 — Intl 로 그 지역 달력 날짜를 얻는다 @param {string} timeZone @param {Date} [now] */
    function todayIn(timeZone, now) {
        var parts = new Intl.DateTimeFormat('en-CA', {timeZone: timeZone, year: 'numeric', month: '2-digit', day: '2-digit'})
            .formatToParts(now || new Date());
        /** @param {Intl.DateTimeFormatPartTypes} t */
        var get = function (t) { return parts.filter(function (p) { return p.type === t; })[0].value; };
        return get('year') + '-' + get('month') + '-' + get('day');
    }

    /** 회사 시간대 오늘 (브라우저: hb.session(), 없으면 Asia/Seoul) */
    function today() {
        var s = typeof window !== 'undefined' && window.hb && window.hb.session ? window.hb.session() : null;
        return todayIn((s && s.timeZone) || 'Asia/Seoul');
    }

    /**
     * 한 날짜 해석. 받는 모양: 2026-10-01 · 2026.10.01 · 2026/10/01 · 20261001 · 26-10-01(2000년대) · 1001·10-01(연도는 opts.year)
     * @param {unknown} text
     * @param {{year?: number}} [opts]
     * @return {HB.DateParse}
     */
    function parse(text, opts) {
        var s = String(text == null ? '' : text).trim().replace(/\s+/g, '');
        if (!s) {
            return {ok: false, code: 'EMPTY'};
        }
        var y;
        var m;
        var d;
        /** @type {RegExpExecArray | null} */
        var r;
        if ((r = /^(\d{4})[-./](\d{1,2})[-./](\d{1,2})\.?$/.exec(s))) {
            y = +r[1]; m = +r[2]; d = +r[3];
        } else if ((r = /^(\d{4})(\d{2})(\d{2})$/.exec(s))) {
            y = +r[1]; m = +r[2]; d = +r[3];
        } else if ((r = /^(\d{2})[-./](\d{1,2})[-./](\d{1,2})$/.exec(s)) || (r = /^(\d{2})(\d{2})(\d{2})$/.exec(s))) {
            y = 2000 + (+r[1]); m = +r[2]; d = +r[3];
        } else if ((r = /^(\d{1,2})[-./](\d{1,2})$/.exec(s)) || (r = /^(\d{2})(\d{2})$/.exec(s))) {
            y = (opts && opts.year) || +today().substring(0, 4); m = +r[1]; d = +r[2];
        } else {
            return {ok: false, code: 'INVALID_DATE'};
        }
        return valid(y, m, d) ? {ok: true, value: fromParts(y, m, d)} : {ok: false, code: 'INVALID_DATE'};
    }

    /**
     * 붙여넣기용 범위 해석: "2026-10-01 ~ 2026-10-15", 엑셀 두 칸(탭), "2026.10.01-2026.10.15", 줄바꿈 구분
     * 순서가 거꾸로면 정렬하고 swapped=true (안내용). 날짜가 하나면 null(한 칸 붙여넣기로 처리)
     * @param {unknown} text
     * @return {HB.DateRangeParse | null}
     */
    function parseRange(text) {
        var s = String(text == null ? '' : text).trim();
        var pieces = s.split(/\s*(?:~|\t|\r?\n|\s-\s|–|—)\s*/).filter(function (x) { return x !== ''; });
        if (pieces.length === 1) {
            // 구분자 없는 "2026.10.01-2026.10.15" / "20261001-20261015"
            var r = /^(\d{4}[./]?\d{1,2}[./]?\d{1,2})-(\d{4}[./]?\d{1,2}[./]?\d{1,2})$/.exec(pieces[0]);
            if (r) {
                pieces = [r[1], r[2]];
            }
        }
        if (pieces.length !== 2) {
            return null;
        }
        var a = parse(pieces[0]);
        var b = parse(pieces[1], {year: a.ok ? +a.value.substring(0, 4) : undefined});
        if (!a.ok || !b.ok) {
            return {ok: false, code: 'INVALID_DATE'};
        }
        var swapped = compare(a.value, b.value) > 0;
        return {ok: true, from: swapped ? b.value : a.value, to: swapped ? a.value : b.value, swapped: swapped};
    }

    /**
     * 프리셋 범위. 기준일 anchor = min(오늘, max). 범위가 max 를 넘거나 비면 null(목록에서 빼거나 비활성).
     * 주는 월요일 시작(opts.weekStart: 1=월, 0=일). "지난 7일"은 기준일 포함 7일(docs/16 I8).
     * @param {string} key
     * @param {HB.PresetOptions} [options]
     */
    function preset(key, options) {
        var opts = options || {};
        var t = opts.today || today();
        var anchor = opts.max && compare(opts.max, t) < 0 ? opts.max : t;
        var ws = opts.weekStart === 0 ? 0 : 1;
        var y = +anchor.substring(0, 4);
        var m = +anchor.substring(5, 7);
        /** @type {{from: string, to: string}} */
        var r;
        switch (key) {
        case 'today': r = {from: t, to: t}; break;
        case 'yesterday': r = {from: add(t, -1), to: add(t, -1)}; break;
        case 'thisWeek': r = {from: add(anchor, -((weekday(anchor) - ws + 7) % 7)), to: anchor}; break;
        case 'last7': r = {from: add(anchor, -6), to: anchor}; break;
        case 'last30': r = {from: add(anchor, -29), to: anchor}; break;
        case 'thisMonth': r = {from: fromParts(y, m, 1), to: anchor}; break;
        case 'lastMonth': {
            var py = m === 1 ? y - 1 : y;
            var pm = m === 1 ? 12 : m - 1;
            r = {from: fromParts(py, pm, 1), to: fromParts(py, pm, daysInMonth(py, pm))};
            break;
        }
        default: return null;
        }
        if (opts.max && compare(r.to, opts.max) > 0) {
            return null;
        }
        if (opts.min && compare(r.from, opts.min) < 0) {
            return null;
        }
        if (opts.maxDays && diffDays(r.from, r.to) > opts.maxDays) {
            return null;
        }
        return {from: r.from, to: r.to, days: diffDays(r.from, r.to)};
    }

    /** 'today' | 'yesterday' | 'YYYY-MM-DD' | null → 날짜 @param {string | null | undefined} spec @param {string} [t] */
    function resolve(spec, t) {
        if (!spec) {
            return null;
        }
        t = t || today();
        if (spec === 'today') {
            return t;
        }
        if (spec === 'yesterday') {
            return add(t, -1);
        }
        return ISO.test(spec) && parse(spec).ok ? spec : null;
    }

    return {
        PRESETS: PRESETS, today: today, todayIn: todayIn, add: add, diffDays: diffDays, weekday: weekday,
        daysInMonth: daysInMonth, compare: compare, parse: parse, parseRange: parseRange, preset: preset, resolve: resolve,
        isIso: function (/** @type {unknown} */ s) { return ISO.test(String(s)) && parse(s).ok; }
    };
});
