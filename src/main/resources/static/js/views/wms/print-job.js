/*
 * 인쇄 이력 (wms-print-job) — P4 1부 W2 (docs/30). 기간 필수 조회(hb.dateRange, 서버도 92일 상한) + hb.grid + 오른쪽 상세·재발행.
 * 상태는 아웃박스에서 파생(대기 NEW → 보내는 중 → 완료 SENT / 실패 FAIL). 공통 hb.* 는 고치지 않는다.
 */
(function () {
    'use strict';

    var API = '/api/wms/print-jobs';
    var PROG = document.body.getAttribute('data-prog');
    var ACTIONS = (document.body.getAttribute('data-actions') || '').split(',');
    var msg = document.getElementById('msg');
    var search = document.getElementById('search');
    var info = document.getElementById('jobInfo');
    var form = document.getElementById('reprintForm');
    var sp, grid, current = null, statusNames = {}, typeNames = {}, stateNames = {}, eventNames = {};

    function notify(text, error) {
        msg.textContent = text || '';
        msg.classList.toggle('hb-msg-error', !!error);
    }

    function f(n) { return form ? form.querySelector('[data-field="' + n + '"]') : null; }

    function line(label, value) {
        info.appendChild(hb.el('dt', null, label));
        info.appendChild(hb.el('dd', null, value === null || value === undefined || value === '' ? '-' : String(value)));
    }

    function show(j) {
        current = j;
        info.textContent = '';
        document.getElementById('zpl').textContent = j ? j.zpl : '';
        if (!j) {
            document.getElementById('jobMode').textContent = hb.t('js.wmsJob.selectFirst');
            if (form) { form.style.display = 'none'; }   // .hb-form 의 display 가 hidden 속성을 덮는다
            showTrack(null);
            return;
        }
        document.getElementById('jobMode').textContent = hb.t('js.wmsJob.title', j.jobId, typeNames[j.labelType] || j.labelType);
        line(hb.t('js.wmsJob.printState'), stateNames[j.printState] || j.printState);
        line(hb.t('js.wmsJob.status'), (statusNames[j.status] || j.status || '-') + (j.attempts ? ' (' + hb.t('js.wmsJob.attempts', j.attempts) + ')' : ''));
        line(hb.t('js.wmsJob.bizKey'), j.bizKey);
        line(hb.t('js.wmsJob.printer'), j.printerCd + ' · ' + j.printerNm);
        line(hb.t('js.wmsJob.template'), j.tplCd);
        line(hb.t('js.wmsJob.copies'), j.copies);
        line(hb.t('js.wmsJob.requested'), hb.fmt.datetime(j.requestedAt));
        line(hb.t('js.wmsJob.requestedBy'), j.requestedBy);
        if (j.sentAt) { line(hb.t('js.wmsJob.sentAt'), hb.fmt.datetime(j.sentAt)); }
        if (j.lastError) { line(hb.t('js.wmsJob.lastError'), j.lastError); }
        if (j.reprintOfId) { line(hb.t('js.wmsJob.reprintOf'), '#' + j.reprintOfId + ' — ' + (j.reprintReason || '')); }
        if (j.testYn === 'Y') { line(hb.t('js.wmsJob.test'), hb.t('js.wmsJob.testYes')); }
        if (form) {
            form.style.display = '';
            f('reason').value = '';
            f('printerId').value = String(j.printerId);
            hb.showFieldErrors(form, []);
        }
        showTrack(j);
    }

    // ---------------------------------------------------------------- 인쇄 상태 3단 (docs/65 18.1)

    var verifyForm = document.getElementById('verifyForm');
    var resolveForm = document.getElementById('resolveForm');

    function showTrack(j) {
        var open = j && (j.printState === 'UNKNOWN' || j.printState === 'PRINTER_ERROR'
            || (j.connMode === 'AGENT' && j.printState === 'QUEUED'));
        var movable = j && (['UNKNOWN', 'FAILED', 'PRINTER_ERROR'].indexOf(j.printState) >= 0 || (j.connMode === 'AGENT' && j.printState === 'QUEUED'));
        var mb = document.getElementById('moveBtn');
        if (mb) { mb.style.display = movable ? '' : 'none'; }
        var rp = resolveForm ? document.getElementById('resolvePrintedBtn') : null;
        if (rp) { rp.style.display = j && j.printState === 'QUEUED' ? 'none' : ''; }
        var verifiable = j && ['SENT', 'PRINTED', 'PRINTER_ERROR', 'UNKNOWN'].indexOf(j.printState) >= 0;
        if (verifyForm) {
            verifyForm.style.display = verifiable ? '' : 'none';
            verifyForm.querySelector('[data-field="scanned"]').value = '';
            hb.showFieldErrors(verifyForm, []);
        }
        if (resolveForm) {
            resolveForm.style.display = open ? '' : 'none';
            resolveForm.querySelector('[data-field="reason"]').value = '';
            hb.showFieldErrors(resolveForm, []);
        }
        var rows = document.getElementById('eventRows');
        rows.textContent = '';
        document.getElementById('eventBox').hidden = !j;
        if (!j) { return; }
        hb.latest('events', hb.api(API + '/' + j.jobId + '/events')).then(function (res) {
            res.data.forEach(function (e) {
                var tr = hb.el('tr');
                tr.appendChild(hb.el('td', null, hb.fmt.datetime(e.occurredAt)));
                tr.appendChild(hb.el('td', null, eventNames[e.eventCd] || e.eventCd));
                tr.appendChild(hb.el('td', null, [e.reasonCd, e.detail].filter(Boolean).join(' · ')));
                tr.appendChild(hb.el('td', null, e.recordedBy));
                rows.appendChild(tr);
            });
        }, function (err) { notify(err.message, true); });
    }

    /** 상세 다시 읽기 + 목록 새로 */
    function refresh(jobId) {
        return hb.api(API + '/' + jobId).then(function (x) { show(x.data); return grid.reload(false, jobId); });
    }

    bind('verifyBtn', function (b) {
        if (!current) { return; }
        var id = current.jobId;
        var scanned = verifyForm.querySelector('[data-field="scanned"]').value;
        hb.busy(b, function () { return hb.api(API + '/' + id + '/verify', {method: 'POST', body: {scanned: scanned}}); }).then(function (res) {
            if (!res) { return; }
            notify(res.data.ok ? hb.t('js.wmsJob.verified') : hb.t('js.wmsJob.verifyNg'), !res.data.ok);
            return refresh(id);
        }, function (err) {
            notify(err.message, true);
            hb.showFieldErrors(verifyForm, err.errors);
        });
    });

    function resolve(b, stateCd) {
        if (!current) { return; }
        var id = current.jobId;
        var reason = resolveForm.querySelector('[data-field="reason"]').value;
        var cancel = stateCd === 'CANCELLED';
        hb.dialog.confirm({title: hb.t('js.wmsJob.dlg.resolve', current.bizKey),
            message: cancel ? hb.t('js.wmsJob.confirmCancel') : hb.t('js.wmsJob.confirmPrinted'),
            ok: cancel ? hb.t('js.wmsJob.resolveCancel') : hb.t('js.wmsJob.resolvePrinted')}).then(function (yes) {
            if (!yes) { return; }
            hb.busy(b, function () { return hb.api(API + '/' + id + '/resolve', {method: 'POST', body: {stateCd: stateCd, reason: reason}}); })
                .then(function (res) {
                    if (!res) { return; }
                    notify(hb.t('js.common.saved'));
                    return refresh(id);
                }, function (err) {
                    notify(err.message, true);
                    hb.showFieldErrors(resolveForm, err.errors);
                });
        });
    }
    bind('resolvePrintedBtn', function (b) { resolve(b, 'PRINTED'); });
    bind('moveBtn', function (b) {
        if (!current) { return; }
        var id = current.jobId;
        var printerId = f('printerId').value ? Number(f('printerId').value) : null;
        hb.dialog.confirm({title: hb.t('js.wmsJob.dlg.move', current.bizKey), message: hb.t('js.wmsJob.confirmMove'),
            ok: hb.t('js.wmsJob.move')}).then(function (yes) {
            if (!yes) { return; }
            hb.busy(b, function () { return hb.api(API + '/' + id + '/move', {method: 'POST', body: {printerId: printerId, reason: f('reason').value}}); })
                .then(function (res) {
                    if (!res) { return; }
                    notify(hb.t('js.wmsJob.moved', res.data));
                    return grid.reload(true);
                }, function (err) {
                    notify(err.message, true);
                    hb.showFieldErrors(form, err.errors);
                });
        });
    });
    bind('resolveCancelBtn', function (b) { resolve(b, 'CANCELLED'); });

    function bind(id, fn) {
        var b = document.getElementById(id);
        if (b) { b.addEventListener('click', function () { fn(b); }); }
    }

    bind('reprintBtn', function (b) {
        if (!current) { return; }
        var reason = f('reason').value;
        hb.dialog.confirm({title: hb.t('js.wmsJob.dlg.reprint', current.bizKey), message: hb.t('js.wmsJob.confirmReprint', current.bizKey),
            ok: hb.t('js.wmsJob.dlg.reprintOk')}).then(function (yes) {
            if (!yes) { return; }
            hb.busy(b, function () {
                return hb.api(API + '/' + current.jobId + '/reprint', {method: 'POST',
                    body: {reason: reason, printerId: f('printerId').value ? Number(f('printerId').value) : null}});
            }).then(function (res) {
                if (!res) { return; }
                notify(hb.t('js.wmsJob.reprinted', res.data));
                return grid.reload(true);
            }, function (err) {
                notify(err.message, true);
                hb.showFieldErrors(form, err.errors);
            });
        });
    });

    search.addEventListener('submit', function (e) { e.preventDefault(); });
    hb.loadMessages().then(function () {
        hb.page.init();
        return Promise.all([hb.loadSession(), hb.codes('WMS_PRINT_STATUS'), hb.codes('WMS_LABEL_TYPE'),
            form ? hb.api(API + '/printers') : Promise.resolve({data: []}), hb.codes('WMS_PRINT_STATE'), hb.codes('WMS_PRINT_EVENT')]);
    }).then(function (r) {
        statusNames = r[1];
        typeNames = r[2];
        stateNames = r[4];
        eventNames = r[5];
        if (form) {
            var sel = f('printerId');
            r[3].data.forEach(function (p) {
                var o = hb.el('option', null, p.plantCd + ' · ' + p.printerCd + ' · ' + p.printerNm);
                o.value = String(p.printerId);
                sel.appendChild(o);
            });
        }
        sp = hb.searchPanel(search, {onSearch: function () { return grid ? grid.reload(true) : null; }});
        grid = hb.grid('#jobGrid', {
            progCd: PROG, id: 'main', key: 'jobId',
            url: API, columnsUrl: API + '/columns', exportUrl: API + '/export',
            actions: ACTIONS, params: function () { return sp.values(); },
            onSelect: function (row) {
                hb.latest('detail', hb.api(API + '/' + row.jobId)).then(function (x) { show(x.data); }, function (e) { notify(e.message, true); });
                hb.page.openDetail();
            },
            notify: notify
        });
        show(null);
        return grid.ready.then(function () { return grid.reload(true); });
    }).then(null, function (err) { notify(err && err.message, true); });
})();
