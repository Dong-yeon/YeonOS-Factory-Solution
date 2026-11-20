/*
 * 편집 그리드 PoC — Tabulator 6.7.1 (docs/10 8장 1·3·5일차).
 * 화면 코드가 Tabulator API 를 직접 부르는 것은 PoC 라서다. 채택하면 hb.grid mode:'edit' 내부로 감싼다(docs/09 4.1).
 *
 * - 열: 텍스트·숫자·날짜·코드, 고정 열(상태·거래처코드·거래처명), 편집 불가 열(rowVer)
 * - 범위 선택(selectableRange) + 복사는 Tabulator, **붙여넣기는 직접**(paste-parser): 타입 파서, 오류 셀은 원문 유지 + 빨간 표시
 * - 일괄 저장 docs/09 5.1: created/updated/deleted + rowVer + X-Idempotency-Key, 400 details → 셀 오류, 409 → 행 충돌 표시
 * - 셀 표시는 DOM 노드(textContent)만 반환 — 문자열 HTML formatter 금지(Tabulator #4168)
 */
(function (global) {
    'use strict';

    var Tabulator = global.Tabulator;
    var P = global.hbPaste;
    var log = global.hbPoc.log;
    var perf = global.hbPoc.perf;
    var API = '/api/dev/poc/grid';
    var NF = new Intl.NumberFormat('ko-KR', {maximumFractionDigits: 2});

    var TRADE = [{code: 'SALE', name: '매출'}, {code: 'PURC', name: '매입'}, {code: 'BOTH', name: '매출입'}];
    var YN = [{code: 'Y', name: '사용'}, {code: 'N', name: '미사용'}];
    var ERR_TEXT = {
        REQUIRED: '필수 입력', TOO_LONG: '너무 김', NUMBER: '숫자가 아님', MIN: '최솟값 미만', MAX: '최댓값 초과',
        SCALE: '소수 자릿수 초과', DATE: '날짜 형식 아님(yyyy-MM-dd)', CODE: '없는 코드', CODE_AMBIGUOUS: '같은 이름의 코드가 여럿'
    };

    /** 열 메타 (hb.grid 컬럼 메타 흉내) — 파서·검증·editor 를 여기서 만든다 */
    var META = [
        {field: 'custCd', title: '거래처코드', type: 'text', required: true, maxLength: 20, frozen: true, width: 110},
        {field: 'custNm', title: '거래처명', type: 'text', required: true, maxLength: 100, frozen: true, width: 150},
        {field: 'personNm', title: '담당자', type: 'text', maxLength: 50, width: 100},
        {field: 'qty', title: '수량', type: 'number', min: 0, scale: 0, width: 90},
        {field: 'unitPrice', title: '단가', type: 'number', scale: 2, width: 110},
        {field: 'tradeDt', title: '거래일', type: 'date', width: 110},
        {field: 'tradeType', title: '거래구분(코드)', type: 'code', options: TRADE, required: true, width: 120},
        {field: 'useYn', title: '사용', type: 'code', options: YN, required: true, width: 80},
        {field: 'remark', title: '비고 (Tabulator 기본 editor — 비교용)', type: 'text', maxLength: 200, width: 220, baseline: true},
        {field: 'rowVer', title: 'rowVer', type: 'number', editable: false, width: 70}
    ];
    var BY_FIELD = {};
    META.forEach(function (m) {
        m.editable = m.editable !== false;
        BY_FIELD[m.field] = m;
    });

    var table;
    var deleted = [];
    var keySeq = 0;

    function $(id) {
        return document.getElementById(id);
    }

    function msg(text, bad) {
        var el = $('gridMsg');
        el.textContent = text;
        el.classList.toggle('poc-bad', !!bad);
        log('grid', 'msg', text);
    }

    function nextKey() {
        keySeq += 1;
        return 'k-' + keySeq;
    }

    function toRow(r) {
        var d = {};
        Object.keys(r).forEach(function (k) { d[k] = r[k]; });
        d._rowKey = nextKey();
        d._state = '';
        d._err = {};
        d._conflict = false;
        return d;
    }

    function blankRow() {
        return {_rowKey: nextKey(), _state: 'C', _err: {}, _conflict: false, id: null, rowVer: null, custCd: '', custNm: '',
            personNm: '', qty: null, unitPrice: null, tradeDt: null, tradeType: 'SALE', useYn: 'Y', remark: ''};
    }

    // ---- 표시 (DOM 노드만) ----

    function cellFormatter(cell) {
        var d = cell.getData();
        var f = cell.getField();
        var m = BY_FIELD[f];
        var v = cell.getValue();
        var err = d._err && d._err[f];
        var text;
        if (v === null || v === undefined || v === '') {
            text = '';
        } else if (err) {
            text = String(v); // 원문 그대로
        } else if (m.type === 'number' && typeof v === 'number') {
            text = NF.format(v);
        } else if (m.type === 'code') {
            var o = m.options.filter(function (x) { return x.code === v; })[0];
            text = o ? o.name : String(v);
        } else {
            text = String(v);
        }
        var el = cell.getElement();
        el.classList.toggle('poc-cell-error', !!err);
        if (err) {
            el.title = ERR_TEXT[err] || err;
        } else {
            el.removeAttribute('title');
        }
        var span = document.createElement('span');
        span.textContent = text;
        return span;
    }

    function statusFormatter(cell) {
        var d = cell.getData();
        var span = document.createElement('span');
        span.textContent = d._conflict ? '충돌' : d._state === 'C' ? '신규' : d._state === 'U' ? '수정' : '';
        return span;
    }

    function rowFormatter(row) {
        var d = row.getData();
        var el = row.getElement();
        el.classList.toggle('poc-row-new', d._state === 'C');
        el.classList.toggle('poc-row-dirty', d._state === 'U');
        el.classList.toggle('poc-row-conflict', !!d._conflict);
    }

    /**
     * 행·셀의 상태 표시만 다시 칠한다. row.reformat() 을 쓰지 않는 이유: reformat 은 행의 셀을 새로 만든다 →
     * Tab 이동 중(cellEdited 안)에 부르면 Tabulator 가 이미 고른 "다음 셀"이 파괴돼 edit() 가 TypeError (PoC 에서 발견).
     * 화면 밖 행은 그려질 때 rowFormatter·cellFormatter 가 같은 일을 한다.
     */
    function paint(row) {
        var el = row.getElement();
        if (!el) {
            return;
        }
        rowFormatter(row);
        var d = row.getData();
        row.getCells().forEach(function (cell) {
            var f = cell.getField();
            var cellEl = cell.getElement();
            if (!BY_FIELD[f] || !cellEl) {
                return;
            }
            var err = d._err && d._err[f];
            cellEl.classList.toggle('poc-cell-error', !!err);
            if (err) {
                cellEl.title = ERR_TEXT[err] || err;
            } else {
                cellEl.removeAttribute('title');
            }
        });
    }

    function columnDef(m) {
        var def = {title: m.title, field: m.field, width: m.width, frozen: !!m.frozen, formatter: cellFormatter,
            hozAlign: m.type === 'number' ? 'right' : 'left', headerSort: false};
        if (!m.editable) {
            return def;
        }
        if (m.baseline) {
            def.editor = 'input'; // 비교용: Tabulator 기본 editor (229 처리 없음)
        } else if (m.type === 'code') {
            def.editor = 'list'; // 비교 대상: Tabulator list editor 의 자동완성(한글 검색 후 Enter)
            def.editorParams = {
                values: m.options.map(function (o) { return {label: o.code + ' ' + o.name, value: o.code}; }),
                autocomplete: true, listOnEmpty: true, allowEmpty: false, freetext: false
            };
        } else {
            def.editor = global.hbImeEditor.create({inputMode: m.type === 'number' ? 'decimal' : m.type === 'date' ? 'numeric' : ''});
        }
        return def;
    }

    // ---- 편집 → 파서 → 오류 표시 (입력·붙여넣기 같은 경로) ----

    function applyParsed(row, field, res) {
        var d = row.getData();
        var err = {};
        Object.keys(d._err || {}).forEach(function (k) { err[k] = d._err[k]; });
        if (res.error) {
            err[field] = res.error;
        } else {
            delete err[field];
        }
        var upd = {_err: err};
        upd[field] = res.value;
        if (d._state !== 'C') {
            upd._state = 'U';
        }
        return upd;
    }

    function onCellEdited(cell) {
        var t0 = performance.now();
        var f = cell.getField();
        var m = BY_FIELD[f];
        var raw = cell.getValue();
        var res = P.parseCell(m.type, raw === null || raw === undefined ? '' : String(raw), m);
        var row = cell.getRow();
        row.update(applyParsed(row, f, res));
        paint(row);
        global.hb.dirty.mark();
        log('grid', 'cellEdited', f, JSON.stringify(raw), '→', JSON.stringify(res.value), res.error || 'OK',
            (performance.now() - t0).toFixed(1) + 'ms');
    }

    // ---- 붙여넣기 ----

    function visibleMeta() {
        return table.getColumns().filter(function (c) { return c.isVisible(); }).map(function (c) {
            return BY_FIELD[c.getField()] || {field: c.getField(), editable: false};
        });
    }

    function onPaste(e) {
        var target = e.target;
        if (target && (target.tagName === 'INPUT' || target.tagName === 'TEXTAREA')) {
            return; // 편집 중인 셀 안에서는 브라우저 기본 붙여넣기
        }
        var text = e.clipboardData ? e.clipboardData.getData('text/plain') : '';
        if (!text) {
            return;
        }
        e.preventDefault();
        var t0 = performance.now();
        var ranges = table.getRanges();
        var startRow = ranges.length ? ranges[0].getTopEdge() : 0;
        var startCol = ranges.length ? ranges[0].getLeftEdge() : 0;
        var matrix = P.parseTsv(text);
        var plan = P.plan(matrix, startRow, startCol, visibleMeta(), table.getRows('display').length, {maxRows: 500});
        if (plan.truncated) {
            msg('붙여넣기는 500행까지입니다(' + matrix.length + '행). 대량은 엑셀 업로드를 쓰세요.', true);
            return;
        }
        var adds = [];
        for (var i = 0; i < plan.newRows; i++) {
            adds.push(blankRow());
        }
        (adds.length ? table.addData(adds, false) : Promise.resolve()).then(function () {
            var rows = table.getRows('display');
            var byRow = {};
            plan.cells.forEach(function (c) {
                (byRow[c.row] = byRow[c.row] || []).push(c);
            });
            table.blockRedraw();
            try {
                Object.keys(byRow).forEach(function (r) {
                    var row = rows[Number(r)];
                    var d = row.getData();
                    var err = {};
                    Object.keys(d._err || {}).forEach(function (k) { err[k] = d._err[k]; });
                    var upd = {};
                    byRow[r].forEach(function (c) {
                        upd[c.field] = c.value;
                        if (c.error) {
                            err[c.field] = c.error;
                        } else {
                            delete err[c.field];
                        }
                    });
                    upd._err = err;
                    if (d._state !== 'C') {
                        upd._state = 'U';
                    }
                    row.update(upd);
                });
            } finally {
                table.restoreRedraw();
            }
            Object.keys(byRow).forEach(function (r) { paint(rows[Number(r)]); });
            global.hb.dirty.mark();
            var ms = performance.now() - t0;
            perf('붙여넣기 ' + matrix.length + '행×' + (matrix[0] ? matrix[0].length : 0) + '열', ms,
                '셀 ' + plan.cells.length + ', 오류 ' + plan.errors);
            msg('붙여넣기: ' + plan.cells.length + '셀, 오류 ' + plan.errors + '셀(빨간 칸, 원문 유지), 새 행 ' + plan.newRows
                + (plan.skippedReadonly ? ', 편집 불가 열이라 버림 ' + plan.skippedReadonly + '셀' : '')
                + (plan.skippedOverflow ? ', 열 범위를 넘어 버림 ' + plan.skippedOverflow + '셀' : ''), plan.errors > 0);
        });
    }

    // ---- 조회·저장 ----

    function load() {
        // 편집 중 재조회 → 먼저 편집을 끝낸다(09 5.2). 열린 editor 를 둔 채 setData 하면 Tabulator Validate.editorClear 가 TypeError
        var active = document.activeElement;
        if (active && active.classList && active.classList.contains('poc-cell-editor')) {
            active.blur();
        }
        var t0 = performance.now();
        return global.hb.api(API + '/rows').then(function (res) {
            var t1 = performance.now();
            var rows = (res.list || []).map(toRow);
            deleted = [];
            return table.setData(rows).then(function () {
                global.hb.dirty.clear();
                return new Promise(function (resolve) {
                    requestAnimationFrame(function () {
                        perf('조회 ' + rows.length + '행 (응답 ' + (t1 - t0).toFixed(0) + 'ms 포함)', performance.now() - t0,
                            'setData+그리기 ' + (performance.now() - t1).toFixed(1) + 'ms');
                        resolve(rows.length);
                    });
                });
            });
        }, function (err) {
            msg(err.message, true);
        });
    }

    function pick(d, withVersion) {
        var o = {rowKey: d._rowKey};
        if (withVersion) {
            o.id = d.id;
            o.rowVer = d.rowVer;
        }
        META.forEach(function (m) {
            if (m.editable) {
                o[m.field] = d[m.field];
            }
        });
        return o;
    }

    function uuid() {
        if (global.crypto && global.crypto.randomUUID) {
            return global.crypto.randomUUID();
        }
        return 'k' + Date.now() + Math.random().toString(16).slice(2);
    }

    function save(button) {
        var created = [];
        var updated = [];
        var errorCells = 0;
        table.blockRedraw();
        try {
            table.getRows().forEach(function (row) {
                var d = row.getData();
                if (d._state !== 'C' && d._state !== 'U') {
                    return;
                }
                // 1차 검사: 손대지 않은 새 행의 필수 칸 (서버가 최종)
                var err = {};
                Object.keys(d._err || {}).forEach(function (k) { err[k] = d._err[k]; });
                META.forEach(function (m) {
                    if (m.required && (d[m.field] === null || d[m.field] === undefined || d[m.field] === '') && !err[m.field]) {
                        err[m.field] = 'REQUIRED';
                    }
                });
                if (Object.keys(err).length) {
                    errorCells += Object.keys(err).length;
                    row.update({_err: err});
                    paint(row);
                }
                (d._state === 'C' ? created : updated).push(pick(d, d._state === 'U'));
            });
        } finally {
            table.restoreRedraw();
        }
        if (errorCells) {
            msg('오류 칸 ' + errorCells + '개를 고친 뒤 저장하세요 (빨간 칸에 마우스를 올리면 사유).', true);
            return Promise.resolve();
        }
        if (!created.length && !updated.length && !deleted.length) {
            msg('변경한 내용이 없습니다.');
            return Promise.resolve();
        }
        var body = {created: created, updated: updated, deleted: deleted};
        return global.hb.busy(button, function () {
            var t0 = performance.now();
            return global.hb.api(API + '/batch', {method: 'POST', body: body, headers: {'X-Idempotency-Key': uuid()}})
                .then(function (res) {
                    var c = res.data.count;
                    perf('일괄 저장 c' + c.c + '/u' + c.u + '/d' + c.d, performance.now() - t0);
                    msg('저장했습니다: 신규 ' + c.c + ', 수정 ' + c.u + ', 삭제 ' + c.d + '. 재조회합니다.');
                    return load();
                }, showSaveError);
        });
    }

    function showSaveError(err) {
        var details = err.details || [];
        if (err.code === 'CONFLICT') {
            var missing = 0;
            details.forEach(function (x) {
                var row = table.getRow(x.rowKey);
                if (row) {
                    row.update({_conflict: true});
                    paint(row);
                } else {
                    missing++; // 삭제 대기 행
                }
            });
            msg('[409] ' + err.message + ' 충돌 ' + details.length + '행(주황, 삭제 대기 ' + missing
                + '행 포함). 자동 병합하지 않습니다 — 재조회 후 다시 입력하세요.', true);
            return;
        }
        if (err.status === 400 && details.length && details[0].field) {
            details.forEach(function (x) {
                var row = table.getRow(x.rowKey);
                if (row) {
                    var err2 = {};
                    var d = row.getData();
                    Object.keys(d._err || {}).forEach(function (k) { err2[k] = d._err[k]; });
                    err2[x.field] = x.code;
                    row.update({_err: err2});
                    paint(row);
                }
            });
            msg('[400] ' + err.message + ' 서버 검증 오류 ' + details.length + '칸 (빨간 칸).', true);
            return;
        }
        msg('[' + err.status + ' ' + err.code + '] ' + err.message, true);
    }

    function selectedRows() {
        var seen = {};
        var out = [];
        table.getRanges().forEach(function (range) {
            range.getRows().forEach(function (row) {
                var k = row.getData()._rowKey;
                if (!seen[k]) {
                    seen[k] = true;
                    out.push(row);
                }
            });
        });
        return out;
    }

    function deleteSelected() {
        var rows = selectedRows();
        rows.forEach(function (row) {
            var d = row.getData();
            if (d._state !== 'C') {
                deleted.push({rowKey: d._rowKey, id: d.id, rowVer: d.rowVer});
            }
            row.delete();
        });
        if (rows.length) {
            global.hb.dirty.mark();
        }
        msg('삭제 표시 ' + rows.length + '행 (저장해야 반영). 삭제 대기 ' + deleted.length + '행.');
    }

    function bumpSelected() {
        var rows = selectedRows().filter(function (r) { return r.getData().id; });
        if (!rows.length) {
            msg('저장된 행을 먼저 선택하세요.', true);
            return;
        }
        Promise.all(rows.map(function (r) {
            return global.hb.api(API + '/' + r.getData().id + '/bump', {method: 'POST'});
        })).then(function () {
            msg('다른 사용자가 ' + rows.length + '행을 먼저 수정한 상태로 만들었습니다. 이 행을 고쳐 저장하면 409 가 나야 합니다.');
        }, function (err) { msg(err.message, true); });
    }

    // ---- 성능 ----

    function resetRows(count, button) {
        return global.hb.busy(button, function () {
            return global.hb.api(API + '/reset?count=' + count, {method: 'POST'}).then(load, function (err) {
                msg(err.message, true);
            });
        });
    }

    function bulkEdit() {
        var t0 = performance.now();
        var rows = table.getRows();
        table.blockRedraw();
        try {
            rows.forEach(function (row) {
                var d = row.getData();
                row.update({qty: (typeof d.qty === 'number' ? d.qty : 0) + 1, _state: d._state === 'C' ? 'C' : 'U'});
            });
        } finally {
            table.restoreRedraw();
        }
        requestAnimationFrame(function () {
            perf('전 행 수량 +1 (' + rows.length + '행 update)', performance.now() - t0);
        });
        global.hb.dirty.mark();
    }

    function scrollEnd() {
        var rows = table.getRows();
        if (!rows.length) {
            return;
        }
        var t0 = performance.now();
        var last = rows[rows.length - 1];
        table.scrollToRow(last, 'bottom', false).then(function () {
            requestAnimationFrame(function () {
                perf('끝 행으로 스크롤 (' + rows.length + '행)', performance.now() - t0);
                return table.scrollToRow(rows[0], 'top', false);
            });
        });
    }

    /** 엑셀 대신 쓸 수 있는 50행×8열 TSV (오류 일부 포함) → 클립보드 */
    function sampleTsv() {
        var lines = [];
        for (var i = 1; i <= 50; i++) {
            var bad = i % 10 === 0;
            lines.push([
                'P' + String(1000 + i), (bad ? '' : '붙여넣기 거래처 ' + i), '담당 ' + i,
                bad ? '12a' : (i * 1234).toLocaleString('en-US'), (i * 10.5).toFixed(2),
                bad ? '2026-02-30' : '2026/10/' + ((i % 28) + 1), i % 3 === 0 ? '매입' : i % 3 === 1 ? 'SALE' : (bad ? '없는코드' : '매출입'),
                i % 7 === 0 ? 'N' : '사용'
            ].join('\t'));
        }
        return lines.join('\r\n') + '\r\n';
    }

    function init() {
        var columns = [{title: '상태', field: '_state', frozen: true, width: 56, headerSort: false, formatter: statusFormatter}]
            .concat(META.map(columnDef));
        table = new Tabulator('#grid', {
            height: '440px',
            index: '_rowKey',
            data: [],
            layout: 'fitData',
            columns: columns,
            rowFormatter: rowFormatter,
            selectableRange: 1,
            selectableRangeColumns: true,
            selectableRangeRows: false,
            editTriggerEvent: 'dblclick',
            clipboard: 'copy',
            clipboardCopyRowRange: 'range',
            clipboardCopyConfig: {columnHeaders: false, columnGroups: false, rowHeaders: false, columnCalcs: false},
            clipboardCopyStyled: false
        });
        table.on('cellEdited', onCellEdited);
        table.on('tableBuilt', function () {
            load();
        });
        $('grid').addEventListener('paste', onPaste);
        // 셀 editor 밖에서 오는 키·조합 이벤트도 기록 (list editor·기본 editor 비교용)
        ['keydown', 'compositionstart', 'compositionend', 'input'].forEach(function (type) {
            $('grid').addEventListener(type, function (e) {
                if (e.target && e.target.classList && e.target.classList.contains('poc-cell-editor')) {
                    return; // hb editor 는 스스로 기록
                }
                var cellEl = e.target && e.target.closest ? e.target.closest('.tabulator-cell') : null;
                var field = cellEl ? cellEl.getAttribute('tabulator-field') : '-';
                log('grid:' + field, global.hbPoc.describeKey(e));
            }, true);
        });

        $('reloadBtn').addEventListener('click', load);
        $('addRowBtn').addEventListener('click', function () {
            table.addRow(blankRow(), false).then(function (row) {
                paint(row);
                table.scrollToRow(row, 'bottom', false);
            });
            global.hb.dirty.mark();
        });
        $('deleteRowBtn').addEventListener('click', deleteSelected);
        $('saveBtn').addEventListener('click', function () { save($('saveBtn')); });
        $('bumpBtn').addEventListener('click', bumpSelected);
        $('reset30Btn').addEventListener('click', function () { resetRows(30, $('reset30Btn')); });
        $('reset1000Btn').addEventListener('click', function () { resetRows(1000, $('reset1000Btn')); });
        $('bulkEditBtn').addEventListener('click', bulkEdit);
        $('scrollEndBtn').addEventListener('click', scrollEnd);
        $('sampleTsvBtn').addEventListener('click', function () {
            var tsv = sampleTsv();
            navigator.clipboard.writeText(tsv).then(function () {
                msg('50행×8열 예시를 클립보드에 넣었습니다(10행마다 오류 포함). 그리드의 거래처코드 칸을 클릭하고 Ctrl+V.');
            }, function () {
                msg('클립보드 쓰기가 거부되었습니다. 엑셀에서 직접 복사하세요.', true);
            });
        });
        global.hb.dirty.watch(document);
        global.hbGridPoc = {table: function () { return table; }, sampleTsv: sampleTsv};
    }

    document.addEventListener('DOMContentLoaded', function () {
        global.hb.loadMessages().then(init);
    });
})(window);
