// @ts-check
/*
 * hb.importDialog — 엑셀 올리기 대화상자 (P1 2단계, docs/17 3b)
 *   1) 템플릿 받기 · 모드(새 것만 / 새 것 + 변경) · 파일 → 올려서 검증(아직 저장하지 않음)
 *   2) 미리보기: 건수(새로·변경·같음·오류), 무시한 열, 행 목록(상태 필터·페이지), 오류 엑셀, 작업 취소, 실행(오류 행 제외 확인)
 *   3) 결과: 넣음·바꿈·같음·제외 + 대사(본 테이블 건수 전후, 일치 여부)
 *   hb.importDialog({templateCd: 'MST_ITEM', onDone: function () { grid.reload(true); }});
 * 의존: hb.js(hb.api·hb.upload·hb.download·hb.busy·hb.el·hb.t). DOM 은 textContent 로만.
 */
(function (global) {
    'use strict';

    var hb = global.hb;
    var BASE = '/api/platform/import';
    var PAGE = 50;
    var EXEC_TIMEOUT_MS = 300000;   // 5만 행 실측 실행 20초 — 여유 있게
    var seq = 0;

    /** @param {string} st */
    function statusText(st) {
        switch (st) {
        case 'NEW': return hb.t('js.imp.st.new');
        case 'CHANGE': return hb.t('js.imp.st.change');
        case 'SAME': return hb.t('js.imp.st.same');
        case 'ERROR': return hb.t('js.imp.st.error');
        default: return hb.t('js.imp.st.all');
        }
    }

    /** @param {string} text @param {string | null} cls @param {(b: HTMLButtonElement) => void} [onClick] */
    function button(text, cls, onClick) {
        var b = hb.el('button', cls || null, text);
        b.type = 'button';
        if (onClick) { b.addEventListener('click', function () { onClick(b); }); }
        return b;
    }

    /**
     * 라디오 세그먼트(fieldset.hb-seg)
     * @param {string} name @param {string} legend @param {[string, string][]} options [값, 글자]
     * @param {string} value @param {(v: string) => void} [onChange]
     */
    function segmented(name, legend, options, value, onChange) {
        var fs = hb.el('fieldset', 'hb-seg');
        fs.appendChild(hb.el('legend', null, legend));
        options.forEach(function (o) {
            var label = hb.el('label');
            var r = hb.el('input');
            r.type = 'radio';
            r.name = name;
            r.value = o[0];
            r.checked = o[0] === value;
            r.addEventListener('change', function () { if (r.checked && onChange) { onChange(o[0]); } });
            label.appendChild(r);
            label.appendChild(hb.el('span', null, o[1]));
            fs.appendChild(label);
        });
        return fs;
    }

    /** @type {HB.Hb['importDialog']} */
    function importDialog(opt) {
        var id = 'hbimp' + (++seq);
        /** 지금 작업(올린 뒤부터) — 미리보기·결과 단계에서는 항상 있다 @type {HB.ImportJob} */
        var job = /** @type {HB.ImportJob} */ (/** @type {unknown} */ (null));
        var rowsSeq = 0;      // 행 목록 요청 순번 — 늦게 온 이전 필터·페이지 응답은 버린다
        var filter = '';
        var page = 1;

        var d = hb.el('dialog', 'hb-dialog hb-import');
        d.setAttribute('aria-labelledby', id + '-t');
        var title = hb.el('h2', null, hb.t('js.imp.title'));
        title.id = id + '-t';
        d.appendChild(title);
        var body = hb.el('div', 'hb-dialog-body');
        d.appendChild(body);
        var msg = hb.el('p', 'hb-msg');
        msg.setAttribute('role', 'status');
        d.appendChild(msg);
        var bar = hb.el('div', 'hb-dialog-bar');
        d.appendChild(bar);
        d.addEventListener('close', function () { d.remove(); });
        document.body.appendChild(d);

        /** @param {string | null | undefined} text @param {boolean} [error] */
        function notify(text, error) {
            msg.textContent = text || '';
            msg.classList.toggle('hb-msg-error', !!error);
        }

        /** @param {HB.ApiError | null | undefined} err */
        function fail(err) {
            notify(err && err.message, true);
        }

        function clear() {
            body.textContent = '';
            bar.textContent = '';
        }

        // ---------- 1. 파일 고르기 ----------
        function stepUpload() {
            clear();
            notify('');
            job = /** @type {HB.ImportJob} */ (/** @type {unknown} */ (null));
            rowsSeq++;
            var mode = 'INSERT_ONLY';
            var steps = hb.el('ol', 'hb-imp-steps');
            steps.appendChild(hb.el('li', null, hb.t('js.imp.step1')));
            steps.appendChild(hb.el('li', null, hb.t('js.imp.step2')));
            steps.appendChild(hb.el('li', null, hb.t('js.imp.step3')));
            body.appendChild(steps);
            var tpl = button(hb.t('js.imp.template'), 'hb-btn-secondary', function (b) {
                hb.busy(b, function () {
                    return hb.download(BASE + '/templates/' + encodeURIComponent(opt.templateCd) + '/file', {});
                }).then(null, fail);
            });
            body.appendChild(hb.el('p', null)).appendChild(tpl);
            body.appendChild(segmented(id + '-mode', hb.t('js.imp.mode'),
                [['INSERT_ONLY', hb.t('js.imp.mode.insert')], ['UPSERT', hb.t('js.imp.mode.upsert')]], mode,
                function (v) { mode = v; }));
            var fileLabel = hb.el('label', 'hb-imp-file');
            fileLabel.appendChild(hb.el('span', null, hb.t('js.imp.file')));
            var file = hb.el('input');
            file.type = 'file';
            file.accept = '.xlsx';
            fileLabel.appendChild(file);
            body.appendChild(fileLabel);
            body.appendChild(hb.el('p', 'hb-muted', hb.t('js.imp.noSaveYet')));

            bar.appendChild(button(hb.t('js.common.close'), 'hb-btn-secondary', function () { d.close(); }));
            bar.appendChild(button(hb.t('js.imp.validate'), null, function (b) {
                if (!file.files || !file.files.length) {
                    notify(hb.t('js.imp.pickFile'), true);
                    file.focus();
                    return;
                }
                var fd = new FormData();
                fd.append('mode', mode);
                fd.append('file', file.files[0]);
                notify(hb.t('js.imp.validating'));
                hb.busy(b, function () {
                    return hb.upload(BASE + '/templates/' + encodeURIComponent(opt.templateCd) + '/jobs', fd);
                }).then(function (res) {
                    if (!res) { return; }
                    job = res.data;
                    filter = job.errorCount ? 'ERROR' : '';
                    page = 1;
                    stepPreview();
                }, fail);
            }));
            file.focus();
        }

        // ---------- 2. 미리보기 ----------
        function counts() {
            var dl = hb.el('dl', 'hb-imp-counts');
            /** @type {[string, number][]} */ ([['', job.total], ['NEW', job.newCount], ['CHANGE', job.changeCount], ['SAME', job.sameCount],
                ['ERROR', job.errorCount]]).forEach(function (c) {
                var item = hb.el('div', 'hb-imp-count' + (c[0] ? ' hb-imp-st-' + c[0] : ''));
                item.appendChild(hb.el('dt', null, statusText(c[0])));
                item.appendChild(hb.el('dd', null, hb.fmt.num(c[1])));
                dl.appendChild(item);
            });
            return dl;
        }

        function stepPreview() {
            clear();
            notify('');
            body.appendChild(hb.el('p', 'hb-muted', hb.t('js.imp.summary', job.fileName,
                job.mode === 'UPSERT' ? hb.t('js.imp.mode.upsert') : hb.t('js.imp.mode.insert'))));
            body.appendChild(counts());
            if (job.ignoredColumns && job.ignoredColumns.length) {
                body.appendChild(hb.el('p', 'hb-notice', hb.t('js.imp.ignored', job.ignoredColumns.join(', '))));
            }
            body.appendChild(segmented(id + '-flt', hb.t('js.imp.filter'),
                [['', statusText('')], ['NEW', statusText('NEW')], ['CHANGE', statusText('CHANGE')],
                    ['SAME', statusText('SAME')], ['ERROR', statusText('ERROR')]], filter,
                function (v) { filter = v; page = 1; loadRows(tableHost, pager); }));
            var tableHost = hb.el('div', 'hb-imp-rows');
            tableHost.id = id + '-rows';
            body.appendChild(tableHost);
            var pager = hb.el('div', 'hb-imp-pager');
            pager.id = id + '-pager';
            body.appendChild(pager);

            /** @type {HTMLInputElement | null} */
            var skip = null;
            if (job.errorCount) {
                var skipLabel = hb.el('label', 'hb-check hb-imp-skip');
                var box = hb.el('input');
                skip = box;
                box.type = 'checkbox';
                skipLabel.appendChild(box);
                skipLabel.appendChild(hb.el('span', null, hb.t('js.imp.skipErrors', job.errorCount)));
                body.appendChild(skipLabel);
            }

            bar.appendChild(button(hb.t('js.imp.cancelJob'), 'hb-btn-ghost', function (b) {
                hb.busy(b, function () {
                    return hb.api(BASE + '/jobs/' + job.jobId + '/cancel', {method: 'POST', body: {rowVer: job.rowVer}});
                }).then(function (res) { if (res) { stepUpload(); } }, fail);
            }));
            if (job.errorCount) {
                bar.appendChild(button(hb.t('js.imp.errorFile'), 'hb-btn-secondary', function (b) {
                    hb.busy(b, function () { return hb.download(BASE + '/jobs/' + job.jobId + '/errors/export', {}); })
                        .then(null, fail);
                }));
            }
            var applyCount = job.newCount + job.changeCount;
            var run = button(hb.t('js.imp.execute', hb.fmt.num(applyCount)), null, function (b) {
                if (job.errorCount && !(skip && skip.checked)) {
                    notify(hb.t('js.imp.needSkip'), true);
                    if (skip) { skip.focus(); }
                    return;
                }
                if (!global.confirm(hb.t('js.imp.confirm', hb.fmt.num(job.newCount), hb.fmt.num(job.changeCount)))) {
                    return;
                }
                notify(hb.t('js.imp.executing'));
                hb.busy(b, function () {
                    return hb.api(BASE + '/jobs/' + job.jobId + '/execute', {method: 'POST',
                        body: {rowVer: job.rowVer, skipErrors: !!(skip && skip.checked)}, timeoutMs: EXEC_TIMEOUT_MS});
                }).then(function (res) {
                    if (!res) { return; }
                    job = res.data;
                    stepResult();
                    if (opt.onDone) { opt.onDone(job); }
                }, fail);
            });
            run.disabled = applyCount === 0;
            bar.appendChild(run);
            loadRows(tableHost, pager);
        }

        /** @param {HTMLElement} host 행 표 자리 @param {HTMLElement} pager 페이지 단추 자리 */
        function loadRows(host, pager) {
            var mySeq = ++rowsSeq;
            var jobId = job.jobId;
            hb.api(BASE + '/jobs/' + jobId + '/rows?page=' + page + '&size=' + PAGE
                    + (filter ? '&status=' + encodeURIComponent(filter) : '')).then(function (res) {
                if (mySeq !== rowsSeq) { return; }     // 그 사이 필터·페이지를 바꿨거나 다른 단계로 넘어감
                host.textContent = '';
                var t = hb.el('table', 'hb-table');
                var head = hb.el('tr');
                [hb.t('js.imp.col.row'), hb.t('js.imp.col.status'), hb.t('js.imp.col.key'), hb.t('js.imp.col.detail')]
                    .forEach(function (h) { head.appendChild(hb.el('th', null, h)); });
                t.appendChild(head);
                (res.list || []).forEach(function (r) {
                    var tr = hb.el('tr');
                    tr.appendChild(hb.el('td', 'hb-num', r.rowNo));
                    tr.appendChild(hb.el('td', 'hb-imp-st-' + r.status, statusText(r.status)));
                    tr.appendChild(hb.el('td', null, r.key || ''));   // 서버가 형식 오류 행은 올린 코드 글자로 채운다
                    var detail = r.errors && r.errors.length
                        ? r.errors.map(function (/** @type {HB.FieldError} */ e) { return (e.title ? e.title + ': ' : '') + e.message; }).join(' / ')
                        : Object.keys(r.raw || {}).slice(1, 4).map(function (k) { return r.raw[k]; })
                            .filter(function (v) { return v; }).join(' · ');
                    tr.appendChild(hb.el('td', r.errors && r.errors.length ? 'hb-field-error' : null, detail));
                    t.appendChild(tr);
                });
                if (!res.list || !res.list.length) {
                    var tr = hb.el('tr');
                    var td = hb.el('td', 'hb-muted', hb.t('js.grid.empty'));
                    td.colSpan = 4;
                    tr.appendChild(td);
                    t.appendChild(tr);
                }
                host.appendChild(t);
                pager.textContent = '';
                var pages = Math.max(1, Math.ceil((res.total || 0) / PAGE));
                var prev = button('‹', 'hb-btn-secondary hb-btn-small', function () { page--; loadRows(host, pager); });
                prev.disabled = page <= 1;
                prev.setAttribute('aria-label', hb.t('js.grid.prev'));
                var next = button('›', 'hb-btn-secondary hb-btn-small', function () { page++; loadRows(host, pager); });
                next.disabled = page >= pages;
                next.setAttribute('aria-label', hb.t('js.grid.next'));
                pager.appendChild(prev);
                pager.appendChild(hb.el('span', null, page + ' / ' + pages));
                pager.appendChild(next);
            }, function (err) { if (mySeq === rowsSeq) { fail(err); } });
        }

        // ---------- 3. 결과 ----------
        function stepResult() {
            clear();
            var r = job.result || {};
            var dl = hb.el('dl', 'hb-imp-counts');
            [['NEW', hb.t('js.imp.res.inserted'), r.inserted], ['CHANGE', hb.t('js.imp.res.updated'), r.updated],
                ['SAME', hb.t('js.imp.res.same'), r.same], ['ERROR', hb.t('js.imp.res.excluded'), r.errorsExcluded]]
                .forEach(function (c) {
                    var item = hb.el('div', 'hb-imp-count hb-imp-st-' + c[0]);
                    item.appendChild(hb.el('dt', null, c[1]));
                    item.appendChild(hb.el('dd', null, hb.fmt.num(c[2] || 0)));
                    dl.appendChild(item);
                });
            body.appendChild(dl);
            body.appendChild(hb.el('p', r.matched ? 'hb-ok' : 'hb-warn', r.matched
                ? hb.t('js.imp.reconOk', hb.fmt.num(job.total), hb.fmt.num(r.before), hb.fmt.num(r.after))
                : hb.t('js.imp.reconNg', hb.fmt.num(job.total), hb.fmt.num(r.before), hb.fmt.num(r.after))));
            notify(hb.t('js.imp.done'));
            var close = button(hb.t('js.common.close'), null, function () { d.close(); });
            bar.appendChild(button(hb.t('js.imp.again'), 'hb-btn-secondary', stepUpload));
            bar.appendChild(close);
            close.focus();
        }

        stepUpload();
        d.showModal();
        return {dialog: d};
    }

    hb.importDialog = importDialog;
})(window);
