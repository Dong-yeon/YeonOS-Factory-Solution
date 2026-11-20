/*
 * 엑셀 붙여넣기 파서 (docs/09 5.3, docs/10 8장 3일차) — DOM·Tabulator 에 의존하지 않는 순수 함수.
 * 브라우저: window.hbPaste / node 단위 테스트: require() (src/test/js/paste-parser.test.js)
 *
 * - parseTsv(text) → 2차원 문자열 배열. 엑셀 클립보드 형식(탭·줄바꿈, 따옴표 셀 안의 탭·줄바꿈·"" 이스케이프, 끝 줄바꿈)
 * - parseCell(type, raw, opts) → {value, raw, error}. 실패하면 value 는 **원문 그대로**(비우지 않는다) + error 코드
 *   text   : 앞뒤 공백·전각 공백·NBSP 제거. '=' 로 시작해도 수식이 아니라 텍스트(07 4.2). maxLength → TOO_LONG
 *   number : 천 단위 쉼표(3자리 묶음만), 소수, 음수(-1, (1,234) 회계 표기), 전각 숫자. min·scale → MIN·SCALE (해석은 hb.numbers)
 *   date   : yyyy-MM-dd, yyyy/MM/dd, yyyy.MM.dd(한 자리 월·일 허용), yyyyMMdd. 실제 없는 날짜·엑셀 일련번호는 DATE
 *   code   : 코드(대소문자 무시) 또는 코드명 → 코드. 같은 이름이 둘 이상이면 CODE_AMBIGUOUS
 * - plan(matrix, startRow, columns, rowCount, opts) → 붙여넣기 계획 (어느 행·열에 무엇을, 편집 불가 열에 버린 수, 새 행 수)
 */
(function (root, factory) {
    'use strict';
    // 숫자 규칙은 hb.numbers 하나(docs/16 6.5) — 브라우저는 hb.js·hb.numbers.js 를 먼저 읽는다
    if (typeof module === 'object' && module.exports) {
        module.exports = factory(require('../../common/hb.numbers.js'));
    } else {
        root.hbPaste = factory(root.hb.numbers);
    }
})(typeof self !== 'undefined' ? self : this, function (numbers) {
    'use strict';

    var TRIM = /^[\s\u3000\u00a0\ufeff]+|[\s\u3000\u00a0\ufeff]+$/g; // JS \s 에 전각 공백·NBSP 도 들지만 명시

    function clean(s) {
        return s === null || s === undefined ? '' : String(s).replace(TRIM, '');
    }

    function parseTsv(text) {
        var src = String(text === null || text === undefined ? '' : text);
        var rows = [];
        var row = [];
        var cell = '';
        var i = 0;
        var quoted = false;
        var atCellStart = true;
        while (i < src.length) {
            var ch = src.charAt(i);
            if (quoted) {
                if (ch === '"') {
                    if (src.charAt(i + 1) === '"') {
                        cell += '"';
                        i += 2;
                        continue;
                    }
                    quoted = false;
                    i++;
                    continue;
                }
                cell += ch;
                i++;
                continue;
            }
            if (ch === '"' && atCellStart) {
                quoted = true;
                atCellStart = false;
                i++;
                continue;
            }
            if (ch === '\t') {
                row.push(cell);
                cell = '';
                atCellStart = true;
                i++;
                continue;
            }
            if (ch === '\r' || ch === '\n') {
                row.push(cell);
                rows.push(row);
                row = [];
                cell = '';
                atCellStart = true;
                i += (ch === '\r' && src.charAt(i + 1) === '\n') ? 2 : 1;
                continue;
            }
            cell += ch;
            atCellStart = false;
            i++;
        }
        if (cell !== '' || row.length > 0) {
            row.push(cell);
            rows.push(row);
        }
        return rows;
    }

    function ok(value, raw) {
        return {value: value, raw: raw, error: null};
    }

    function fail(code, raw) {
        return {value: raw, raw: raw, error: code};
    }

    /** 해석은 hb.numbers.parse — 그리드 값은 숫자(Number), 실패하면 원문 유지 */
    function parseNumber(raw, opts) {
        var r = numbers.parse(raw, {scale: opts.scale, min: opts.min, max: opts.max});
        if (!r.ok) {
            return fail(r.code, raw);
        }
        return ok(r.value === null ? null : Number(r.value), raw);
    }

    var DATE_SEP = /^(\d{4})[-/.](\d{1,2})[-/.](\d{1,2})\.?$/;
    var DATE_COMPACT = /^(\d{4})(\d{2})(\d{2})$/;

    function pad(n) {
        return n < 10 ? '0' + n : String(n);
    }

    function parseDate(raw) {
        var s = clean(raw);
        if (s === '') {
            return ok(null, raw);
        }
        s = s.normalize ? s.normalize('NFKC') : s;
        var m = DATE_SEP.exec(s) || DATE_COMPACT.exec(s);
        if (!m) {
            return fail('DATE', raw); // 엑셀 일련번호(46000 등)도 여기서 거부
        }
        var y = Number(m[1]);
        var mo = Number(m[2]);
        var d = Number(m[3]);
        var dt = new Date(Date.UTC(y, mo - 1, d));
        if (y < 1900 || y > 2999 || dt.getUTCFullYear() !== y || dt.getUTCMonth() !== mo - 1 || dt.getUTCDate() !== d) {
            return fail('DATE', raw);
        }
        return ok(y + '-' + pad(mo) + '-' + pad(d), raw);
    }

    function parseCode(raw, opts) {
        var s = clean(raw);
        if (s === '') {
            return ok(null, raw);
        }
        var list = opts.options || [];
        var upper = s.toUpperCase();
        for (var i = 0; i < list.length; i++) {
            if (String(list[i].code).toUpperCase() === upper) {
                return ok(list[i].code, raw);
            }
        }
        var found = list.filter(function (o) { return clean(o.name) === s; });
        if (found.length === 1) {
            return ok(found[0].code, raw);
        }
        return fail(found.length > 1 ? 'CODE_AMBIGUOUS' : 'CODE', raw);
    }

    function parseText(raw, opts) {
        var s = clean(raw);
        if (opts.maxLength !== undefined && s.length > opts.maxLength) {
            return fail('TOO_LONG', raw);
        }
        return ok(s, raw);
    }

    function parseCell(type, raw, opts) {
        opts = opts || {};
        var r = raw === null || raw === undefined ? '' : String(raw);
        var res;
        switch (type) {
            case 'number': res = parseNumber(r, opts); break;
            case 'date': res = parseDate(r); break;
            case 'code': res = parseCode(r, opts); break;
            default: res = parseText(r, opts);
        }
        if (!res.error && opts.required && (res.value === null || res.value === '')) {
            return fail('REQUIRED', r);
        }
        return res;
    }

    /**
     * 붙여넣기 계획.
     * columns: 화면에 보이는 순서의 열 [{field, type, editable, options, min, scale, maxLength, required}]
     * startCol: columns 안의 시작 위치, startRow: 행 위치, rowCount: 지금 행 수
     * opts.maxRows: 상한(넘으면 truncated=true, 아무것도 적용하지 않음 — 엑셀 업로드 안내)
     */
    function plan(matrix, startRow, startCol, columns, rowCount, opts) {
        opts = opts || {};
        var maxRows = opts.maxRows || 500;
        var result = {cells: [], skippedReadonly: 0, skippedOverflow: 0, newRows: 0, errors: 0, truncated: false};
        if (matrix.length > maxRows) {
            result.truncated = true;
            return result;
        }
        for (var r = 0; r < matrix.length; r++) {
            for (var c = 0; c < matrix[r].length; c++) {
                var col = columns[startCol + c];
                if (!col) {
                    result.skippedOverflow++;
                    continue;
                }
                if (!col.editable) {
                    result.skippedReadonly++;
                    continue;
                }
                var parsed = parseCell(col.type, matrix[r][c], col);
                if (parsed.error) {
                    result.errors++;
                }
                result.cells.push({row: startRow + r, field: col.field, value: parsed.value, raw: parsed.raw,
                    error: parsed.error});
            }
        }
        result.newRows = Math.max(0, startRow + matrix.length - rowCount);
        return result;
    }

    return {parseTsv: parseTsv, parseCell: parseCell, plan: plan, clean: clean};
});
