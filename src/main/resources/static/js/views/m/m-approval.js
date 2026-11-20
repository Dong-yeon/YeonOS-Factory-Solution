// @ts-check
/*
 * 모바일 결재 승인 (/m/approval, docs/74 화면 2·M7) — 기존 결재 API 그대로.
 * - 목록: 내 차례 / 내가 올린 것 / 결재선에 있던 것 (카드). 카드를 누르면 문서 요약 + 결재선
 * - 승인·반려는 내 차례(myTurn)일 때만 보인다. 판정은 서버(결재선) — 단추 숨김은 편의
 * - 반려는 사유 필수(서버 apr.reject.commentRequired, 화면도 먼저 확인)
 * - 처리 요청은 누른 순간 만든 X-Idempotency-Key 와 함께(연타·재전송 → 409 DUPLICATE_REQUEST). 이미 처리된 결재는 서버가 거부
 * - 문서 유형이 서명을 요구하면(상세 esignApprove·esignReject) hb.esign 으로 토큰을 받아 싣는다
 * - 오프라인이면 단추를 끄고 알린다. 대기열에 쌓지 않는다(끊긴 사이 다른 사람이 처리했을 수 있다, docs/45 A-3)
 */
/**
 * @typedef {{aprId: number, docType: string, bizKey: string, title: string, status: string, requesterNm: string,
 *            requestedAt: string, finishedAt: string | null, myLineType: string | null, myLineStatus: string | null,
 *            myTurn: boolean, mine: boolean, delegated: boolean}} MAprRow
 * @typedef {{seq: number, approverNm: string, lineType: string, status: string, actedAt: string | null, comment: string | null,
 *            actedByNm: string | null}} MAprLine
 * @typedef {{meaningCd: string, targetType: string, targetId: number, title: string | null}} MAprSign
 * @typedef {{request: {aprId: number, docType: string, bizKey: string, title: string, status: string, requestedAt: string},
 *            lines: MAprLine[], esignApprove: MAprSign | null, esignReject: MAprSign | null}} MAprDetail
 */
(function () {
    'use strict';

    var API = '/api/platform/approvals';
    var msg = document.getElementById('msg');
    var listView = /** @type {HTMLElement} */ (document.getElementById('listView'));
    var docView = /** @type {HTMLElement} */ (document.getElementById('docView'));
    var listEl = /** @type {HTMLElement} */ (document.getElementById('aprList'));
    var linesEl = /** @type {HTMLElement} */ (document.getElementById('aprLines'));
    var titleEl = /** @type {HTMLElement} */ (document.getElementById('aprTitle'));
    var infoEl = /** @type {HTMLElement} */ (document.getElementById('aprInfo'));
    var actBox = /** @type {HTMLElement} */ (document.getElementById('actBox'));
    var offlineNote = /** @type {HTMLElement} */ (document.getElementById('offlineNote'));
    var comment = /** @type {HTMLTextAreaElement} */ (document.getElementById('aprComment'));
    var approveBtn = /** @type {HTMLButtonElement} */ (document.getElementById('approveBtn'));
    var rejectBtn = /** @type {HTMLButtonElement} */ (document.getElementById('rejectBtn'));
    var backBtn = /** @type {HTMLButtonElement} */ (document.getElementById('backBtn'));
    /** @type {MAprRow | null} */
    var current = null;
    /** @type {MAprDetail | null} */
    var detail = null;

    function box() {
        var r = /** @type {HTMLInputElement | null} */ (document.querySelector('input[name="box"]:checked'));
        return r ? r.value : 'TODO';
    }

    /** 문구 키는 리터럴로만(I18nKeyGuardTest) @return {Record<string, string>} */
    function statusNames() {
        return {
            PENDING: hb.t('js.sysApproval.st.PENDING'), APPROVED: hb.t('js.sysApproval.st.APPROVED'),
            REJECTED: hb.t('js.sysApproval.st.REJECTED'), WITHDRAWN: hb.t('js.sysApproval.st.WITHDRAWN'),
            WAIT: hb.t('js.sysApproval.st.WAIT'), CURRENT: hb.t('js.sysApproval.st.CURRENT'),
            SKIPPED: hb.t('js.sysApproval.st.SKIPPED'), REF: hb.t('js.sysApproval.st.REF')
        };
    }

    /** @return {Record<string, string>} */
    function lineTypeNames() {
        return {APPROVE: hb.t('js.sysApproval.lt.APPROVE'), AGREE: hb.t('js.sysApproval.lt.AGREE'), REF: hb.t('js.sysApproval.lt.REF')};
    }

    /** @param {string} status @return {HB.StatusTone} */
    function statusTone(status) {
        return status === 'REJECTED' ? 'danger' : status === 'APPROVED' ? 'success' : status === 'PENDING' || status === 'CURRENT' ? 'info' : 'neutral';
    }

    /** @param {string} status */
    function badge(status) {
        return hb.status(statusTone(status), hb.fmt.label(statusNames(), status));
    }

    function loadList() {
        var b = box();
        return hb.latest('m-apr-list', hb.api(API + '/box?box=' + encodeURIComponent(b))).then(function (res) {
            var list = /** @type {MAprRow[]} */ (res.list || []);
            hbM.clear(listEl);
            if (!list.length) { listEl.appendChild(hbM.empty(hb.t('js.sysApproval.empty'))); return; }
            list.forEach(function (r) {
                listEl.appendChild(hbM.card({title: r.title, right: badge(r.myTurn ? 'CURRENT' : r.status),
                    meta: [r.requesterNm + ' · ' + hb.fmt.datetime(r.requestedAt), r.docType + ' ' + r.bizKey],
                    tone: r.myTurn ? 'warn' : null, onClick: function () { open(r); }}));
            });
        }, function (err) {
            if (!err.stale) { hbM.say(msg, hbM.online() ? err.message : hb.t('js.m.offlineRead'), true); }
        });
    }

    /** @param {MAprRow} r */
    function open(r) {
        hbM.say(msg, '');
        hb.latest('m-apr-detail', hb.api(API + '/' + r.aprId)).then(function (res) {
            var d = /** @type {MAprDetail} */ (res.data);
            current = r;
            detail = d;
            titleEl.textContent = d.request.title;
            hbM.clear(infoEl);
            infoEl.appendChild(hbM.kv([
                [hb.t('js.m.apr.status'), badge(d.request.status)],
                [hb.t('js.m.apr.doc'), d.request.docType + ' ' + d.request.bizKey],
                [hb.t('js.m.apr.requester'), r.requesterNm],
                [hb.t('js.m.apr.requestedAt'), hb.fmt.datetime(d.request.requestedAt)]
            ]));
            hbM.clear(linesEl);
            var types = lineTypeNames();
            d.lines.forEach(function (l) {
                var who = l.actedByNm ? hb.t('js.sysApproval.deleg.onBehalf', l.approverNm, l.actedByNm) : l.approverNm;
                linesEl.appendChild(hbM.card({title: l.seq + '. ' + who, right: badge(l.status),
                    meta: [hb.fmt.label(types, l.lineType) + (l.actedAt ? ' · ' + hb.fmt.datetime(l.actedAt) : ''), l.comment]}));
            });
            comment.value = '';
            actBox.hidden = !(d.request.status === 'PENDING' && r.myTurn);
            listView.hidden = true;
            docView.hidden = false;
            syncOnline(hbM.online());
            window.scrollTo(0, 0);
        }, function (err) {
            if (!err.stale) { hbM.say(msg, err.message, true); }
        });
    }

    function back() {
        current = null;
        detail = null;
        docView.hidden = true;
        listView.hidden = false;
        return loadList();
    }

    /** @param {boolean} on */
    function syncOnline(on) {
        approveBtn.disabled = !on;
        rejectBtn.disabled = !on;
        offlineNote.hidden = on;
    }

    /** @param {'approve' | 'reject'} action @return {Promise<{token: string | null} | null>} */
    function sign(action) {
        var spec = !detail ? null : action === 'approve' ? detail.esignApprove : detail.esignReject;
        if (!spec || !current) { return Promise.resolve({token: null}); }
        return hb.esign({meaning: spec.meaningCd, targetType: spec.targetType, targetId: spec.targetId, title: spec.title || current.title})
            .then(function (token) { return token ? {token: token} : null; });
    }

    /** @param {HTMLButtonElement} btn @param {'approve' | 'reject'} action */
    function act(btn, action) {
        var r = current;
        if (!r) { return; }
        var row = r;
        var text = comment.value;
        if (action === 'reject' && (hb.text ? hb.text.isBlank(text) : !text.trim())) {
            hbM.say(msg, hb.t('js.m.apr.rejectReason'), true);
            comment.focus();
            return;
        }
        var ask = action === 'approve'
            ? {title: hb.t('js.sysApproval.dlg.approve'), message: hb.t('js.sysApproval.confirmApprove', row.title), ok: hb.t('js.sysApproval.dlg.approveOk')}
            : {title: hb.t('js.sysApproval.dlg.reject'), message: hb.t('js.sysApproval.confirmReject', row.title), ok: hb.t('js.sysApproval.dlg.rejectOk'), danger: true};
        hb.dialog.confirm(ask).then(function (ok) {
            if (!ok) { return null; }
            return sign(action);
        }).then(function (signed) {
            if (!signed) { return; }
            if (!hbM.online()) { syncOnline(false); hbM.say(msg, hb.t('js.sysApproval.offline'), true); return; }
            var key = hbM.key('m-apr-' + row.aprId + '-' + action);   // 확인한 순간 만든다 — 같은 누름의 재전송은 같은 키
            /** @type {{comment: string, esignToken?: string}} */
            var body = {comment: text};
            if (signed.token) { body.esignToken = signed.token; }
            return hb.busy(btn, function () {
                return hb.api(API + '/' + row.aprId + '/' + action, {method: 'POST', body: body, headers: {'X-Idempotency-Key': key}});
            }).then(function (res) {
                syncOnline(hbM.online());   // hb.busy 가 단추를 다시 켰다 — 그사이 끊겼으면 다시 끈다
                if (!res) { return; }
                return back().then(function () { hbM.say(msg, hb.t('js.sysApproval.done')); });
            });
        }).then(null, function (err) { hbM.say(msg, err.message, true); });
    }

    approveBtn.addEventListener('click', function () { act(approveBtn, 'approve'); });
    rejectBtn.addEventListener('click', function () { act(rejectBtn, 'reject'); });
    backBtn.addEventListener('click', function () { hbM.say(msg, ''); back(); });
    /** @type {HTMLElement} */ (document.getElementById('boxSeg')).addEventListener('change', function () { hbM.say(msg, ''); loadList(); });

    hbM.ready().then(function () {
        var first = true;
        hbM.onNet(function (on) {
            syncOnline(on);
            if (first) { first = false; loadList(); return; }
            if (on && docView.hidden) { loadList(); }
        });
    });
})();
