// @ts-check
/*
 * 연계 현황·실패 재전송 (sys-outbox) — docs/36 19장. 조회 패널(기간·주제·상태·업무 키) + hb.grid + 오른쪽 상세.
 * 재시도(SAVE)·수동 종결(FN1)은 실패(FAIL)이고 종결 결재 대기가 아닐 때만 단추를 보인다 — 서버가 다시 본다(409).
 * 종결은 사유 필수, 회사 정책이 결재면 결재선을 붙여 올린다. 서버 값(오류·요청 내용)은 textContent 로만.
 */
/**
 * @typedef {{outboxId: number, messageId: string, createdAt: string, createdBy: string, topic: string, bizKey: string | null,
 *   status: string, attempts: number, maxAttempts: number, nextRetryAt: string | null, sentAt: string | null,
 *   lastError: string | null, payload: unknown, retryCnt: number, retriedAt: string | null, retriedBy: string | null,
 *   closedAt: string | null, closedBy: string | null, closeReason: string | null, closeAprId: number | null,
 *   closeAprStatus: string | null, closeNeedsApproval: boolean, linkProg: string | null}} OutboxDetailView
 * @typedef {{userId: number, loginId: string, userNm: string}} ApproverOption
 */
(function () {
    'use strict';

    var PROG = document.body.getAttribute('data-prog') || '';
    var ACTIONS = (document.body.getAttribute('data-actions') || '').split(',');
    var CAN_RETRY = ACTIONS.indexOf('SAVE') >= 0;
    var CAN_CLOSE = ACTIONS.indexOf('FN1') >= 0;
    var API = '/api/admin/outbox';
    var msg = /** @type {HTMLElement} */ (document.getElementById('msg'));
    var search = /** @type {HTMLFormElement} */ (document.getElementById('search'));
    var head = /** @type {HTMLElement} */ (document.getElementById('detailHead'));
    var body = /** @type {HTMLElement} */ (document.getElementById('detailBody'));
    var retryBtn = /** @type {HTMLButtonElement | null} */ (document.getElementById('retryBtn'));
    var printBtn = /** @type {HTMLButtonElement} */ (document.getElementById('printJobBtn'));
    var closeBox = /** @type {HTMLElement | null} */ (document.getElementById('closeBox'));
    var reason = /** @type {HTMLTextAreaElement | null} */ (document.getElementById('closeReason'));
    var lineTools = /** @type {HTMLElement | null} */ (document.getElementById('lineTools'));
    /** 상태 → 문구 키(키를 이어 붙이지 않는다 — I18nKeyGuardTest) @type {Record<string, string>} */
    var STATUS_KEYS = {NEW: 'js.sysOutbox.st.NEW', SENDING: 'js.sysOutbox.st.SENDING', SENT: 'js.sysOutbox.st.SENT',
        FAIL: 'js.sysOutbox.st.FAIL', CANCELED: 'js.sysOutbox.st.CANCELED', CLOSED_MANUAL: 'js.sysOutbox.st.CLOSED_MANUAL'};
    /** 종결 결재 상태 @type {Record<string, string>} */
    var APR_KEYS = {PENDING: 'js.sysOutbox.apr.PENDING', APPROVED: 'js.sysOutbox.apr.APPROVED',
        REJECTED: 'js.sysOutbox.apr.REJECTED', WITHDRAWN: 'js.sysOutbox.apr.WITHDRAWN'};
    /** 상태 톤 — 실패만 위험, 수동 종결·취소는 끝남(보낸 것과 구분), 대기·보내는 중은 볼 것 있음 @type {Record<string, HB.StatusTone>} */
    var STATUS_TONES = {FAIL: 'danger', CLOSED_MANUAL: 'neutral', CANCELED: 'neutral', SENT: 'normal', NEW: 'info', SENDING: 'info'};
    /** @type {Record<string, string>} */
    var LINE_KEYS = {APPROVE: 'js.sysOutbox.line.APPROVE', AGREE: 'js.sysOutbox.line.AGREE', REF: 'js.sysOutbox.line.REF'};
    /** @type {HB.Grid | undefined} */
    var grid;
    /** @type {HB.SearchPanel} */
    var sp;
    /** 주제 코드 → 이름 @type {Record<string, string>} */
    var topicNames = {};
    /** @type {OutboxDetailView | null} */
    var cur = null;
    /** 올리기 전 결재선 @type {{userId: number, name: string, lineType: string}[]} */
    var lines = [];
    var approversLoaded = false;

    /** @param {string | null | undefined} text @param {boolean} [error] */
    function notify(text, error) {
        msg.textContent = text || '';
        msg.classList.toggle('hb-msg-error', !!error);
    }

    /** @param {string} id @param {string | null | undefined} text */
    function put(id, text) {
        /** @type {HTMLElement} */ (document.getElementById(id)).textContent = text || '-';
    }

    /** @param {string | null | undefined} v */
    function dt(v) {
        return v ? hb.fmt.datetime(v) : null;
    }

    /** @param {string} s */
    function statusName(s) {
        return STATUS_KEYS[s] ? hb.t(STATUS_KEYS[s]) : s;
    }

    function shell() {
        var s = window.top && window.top !== window ? window.top.hbShell : null;
        return s && s !== true ? s : null;
    }

    function renderLines() {
        var table = /** @type {HTMLTableElement | null} */ (document.getElementById('lineTable'));
        if (!table) { return; }
        var tb = /** @type {HTMLElement} */ (table.tBodies[0]);
        tb.textContent = '';
        lines.forEach(function (l, i) {
            var tr = hb.el('tr');
            tr.appendChild(hb.el('td', null, String(i + 1)));
            tr.appendChild(hb.el('td', null, l.name));
            tr.appendChild(hb.el('td', null, hb.t(LINE_KEYS[l.lineType] || 'js.sysOutbox.line.APPROVE')));
            var td = hb.el('td');
            var del = /** @type {HTMLButtonElement} */ (hb.el('button', 'hb-btn-ghost', hb.t('js.sysOutbox.removeLine')));
            del.type = 'button';
            del.addEventListener('click', function () { lines.splice(i, 1); renderLines(); });
            td.appendChild(del);
            tr.appendChild(td);
            tb.appendChild(tr);
        });
    }

    function loadApprovers() {
        if (approversLoaded) { return; }
        approversLoaded = true;
        hb.api(API + '/approvers').then(function (res) {
            var sel = /** @type {HTMLSelectElement} */ (document.getElementById('lineUser'));
            /** @type {ApproverOption[]} */ (res.list || []).forEach(function (u) {
                var o = /** @type {HTMLOptionElement} */ (hb.el('option', null, u.userNm + ' (' + u.loginId + ')'));
                o.value = String(u.userId);
                sel.appendChild(o);
            });
        }, function (err) { approversLoaded = false; notify(err.message, true); });
    }

    /** @param {OutboxDetailView | null} d */
    function show(d) {
        cur = d;
        body.hidden = !d;
        if (!d) {
            head.textContent = hb.t('js.sysOutbox.pick');
            if (retryBtn) { retryBtn.hidden = true; }
            printBtn.hidden = true;
            return;
        }
        head.textContent = '#' + d.outboxId + ' · ' + (topicNames[d.topic] || d.topic) + (d.bizKey ? ' · ' + d.bizKey : '');
        var pending = d.closeAprId !== null && d.closeAprStatus === 'PENDING';
        put('dStatus', statusName(d.status) + (pending ? ' — ' + hb.t('js.sysOutbox.closePending') : ''));
        put('dTopic', (topicNames[d.topic] || d.topic) + ' (' + d.topic + ')');
        put('dBizKey', d.bizKey);
        put('dMessageId', d.messageId);
        put('dCreated', [dt(d.createdAt), d.createdBy].join(' · '));
        put('dTries', d.attempts + ' / ' + d.maxAttempts);
        put('dNext', d.status === 'NEW' || d.status === 'SENDING' ? dt(d.nextRetryAt) : null);
        put('dSent', dt(d.sentAt));
        put('dRetried', d.retryCnt > 0 ? hb.t('js.sysOutbox.retriedText', d.retryCnt, dt(d.retriedAt) || '-', d.retriedBy || '-') : null);
        var closed = d.status === 'CLOSED_MANUAL'
            ? hb.t('js.sysOutbox.closedText', dt(d.closedAt) || '-', d.closedBy || '-', d.closeReason || '-')
            : pending ? hb.t('js.sysOutbox.pendingText', d.closedBy || '-', d.closeReason || '-') : null;
        if (d.closeAprStatus && APR_KEYS[d.closeAprStatus]) {
            closed = (closed ? closed + ' · ' : '') + hb.t('js.sysOutbox.aprText', d.closeAprId, hb.t(APR_KEYS[d.closeAprStatus]));
        }
        put('dClosed', closed);
        put('dError', d.lastError);
        put('dPayload', d.payload === null || d.payload === undefined ? null : JSON.stringify(d.payload, null, 2));

        var actionable = d.status === 'FAIL' && !pending;
        if (retryBtn) { retryBtn.hidden = !(CAN_RETRY && actionable); }
        printBtn.hidden = !d.linkProg;
        if (closeBox && reason && lineTools) {
            closeBox.hidden = !(CAN_CLOSE && actionable);
            reason.value = '';
            lines = [];
            renderLines();
            lineTools.hidden = !d.closeNeedsApproval;
            put('closeNote', hb.t(d.closeNeedsApproval ? 'js.sysOutbox.closeNoteApproval' : 'js.sysOutbox.closeNoteDirect'));
            if (d.closeNeedsApproval && !closeBox.hidden) { loadApprovers(); }
        }
    }

    /** @param {number} id */
    function loadDetail(id) {
        notify('');
        return hb.latest('detail', hb.api(API + '/' + id)).then(function (res) { show(res.data); },
            function (err) { if (!err.stale) { notify(err.message, true); } });
    }

    function afterChange(/** @type {string} */ done) {
        var id = cur ? cur.outboxId : null;
        return Promise.all([grid ? grid.reload() : null, id ? loadDetail(id) : null]).then(function () { notify(done); });
    }

    if (retryBtn) {
        var rb = retryBtn;
        rb.addEventListener('click', function () {
            if (!cur) { return; }
            var d = cur;
            hb.dialog.confirm({title: hb.t('js.sysOutbox.confirmRetry'), message: hb.t('js.sysOutbox.confirmRetryMsg'),
                ok: hb.t('js.sysOutbox.retry')}).then(function (yes) {
                if (!yes) { return; }
                hb.busy(rb, function () { return hb.api(API + '/' + d.outboxId + '/retry', {method: 'POST'}); })
                    .then(function (res) { if (res) { return afterChange(hb.t('js.sysOutbox.retried')); } },
                        function (err) { notify(err.message, true); });
            });
        });
    }

    var addLineBtn = document.getElementById('addLineBtn');
    if (addLineBtn) {
        addLineBtn.addEventListener('click', function () {
            var u = /** @type {HTMLSelectElement} */ (document.getElementById('lineUser'));
            if (!u.value) { return; }
            lines.push({userId: Number(u.value), name: u.options[u.selectedIndex].text,
                lineType: /** @type {HTMLSelectElement} */ (document.getElementById('lineType')).value});
            renderLines();
        });
    }

    var closeBtn = /** @type {HTMLButtonElement | null} */ (document.getElementById('closeBtn'));
    if (closeBtn && reason) {
        var cb = closeBtn;
        var rs = reason;
        cb.addEventListener('click', function () {
            if (!cur) { return; }
            var d = cur;
            var why = rs.value.trim();
            if (!why) { notify(hb.t('js.sysOutbox.reasonRequired'), true); rs.focus(); return; }
            if (d.closeNeedsApproval && !lines.length) { notify(hb.t('js.sysOutbox.linesRequired'), true); return; }
            var approval = d.closeNeedsApproval;
            hb.dialog.confirm({title: hb.t(approval ? 'js.sysOutbox.confirmSubmit' : 'js.sysOutbox.confirmClose'),
                message: hb.t('js.sysOutbox.confirmCloseMsg'), ok: hb.t('js.sysOutbox.closeOk'), danger: true}).then(function (yes) {
                if (!yes) { return; }
                hb.busy(cb, function () {
                    return hb.api(API + '/' + d.outboxId + '/close', {method: 'POST', body: {reason: why,
                        lines: lines.map(function (l) { return {userId: l.userId, lineType: l.lineType}; })}});
                }).then(function (res) {
                    if (res) { return afterChange(hb.t(res.data && res.data.closed ? 'js.sysOutbox.closed' : 'js.sysOutbox.submitted')); }
                }, function (err) { notify(err.message, true); });
            });
        });
    }

    printBtn.addEventListener('click', function () {
        var prog = cur ? cur.linkProg : null;
        if (!prog) { return; }
        var s = shell();
        if (s) {
            s.open(prog, hb.t('js.sysOutbox.printJobsTitle'));
        } else {
            window.location.href = '/page/' + encodeURIComponent(prog);
        }
    });

    search.addEventListener('submit', function (e) { e.preventDefault(); });
    hb.loadMessages().then(function () {
        hb.page.init();
        return hb.api(API + '/topics');
    }).then(function (res) {
        var sel = /** @type {HTMLSelectElement} */ (document.getElementById('topicSel'));
        (res.list || []).forEach(function (/** @type {{code: string, name: string}} */ t) {
            topicNames[t.code] = t.name;
            var o = /** @type {HTMLOptionElement} */ (hb.el('option', null, t.name));
            o.value = t.code;
            sel.appendChild(o);
        });
        sp = hb.searchPanel(search, {
            onSearch: function () { return grid ? grid.reload(true) : null; }
        });
        show(null);
        return sp.ready;
    }).then(function () {
        grid = hb.grid('#outboxGrid', {
            progCd: PROG, id: 'main', key: 'outboxId',
            url: API, columnsUrl: API + '/columns', exportUrl: API + '/export',
            actions: ACTIONS, params: function () { return sp.values(); },
            filters: {topic: 'topic', status: 'status'},
            status: {status: STATUS_TONES},
            onFilter: function (param, value) { sp.setValue(param, value); },
            onSelect: function (row) { loadDetail(row.outboxId); hb.page.openDetail(); },
            notify: notify
        });
        return grid.ready;
    }).then(null, function (err) { notify(err && err.message, true); });
})();
