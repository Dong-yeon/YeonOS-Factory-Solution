// @ts-check
/*
 * 내 결재함 (sys-approval) — 시스템관리 3단계 (docs/36 12장). 개인 화면: 서버가 나와 관계있는 결재만 준다.
 * 승인·반려는 내 차례일 때만, 회수는 내가 올린 진행 중 결재만(버튼은 편의, 판정은 ApprovalService — 결재선).
 * 처리 요청은 누른 순간 만든 X-Idempotency-Key 와 함께(연타·재전송 → 409 DUPLICATE_REQUEST).
 */
/**
 * @typedef {{aprId: number, docType: string, bizKey: string, title: string, status: string, requesterId: number,
 *   requesterNm: string, requestedAt: string, finishedAt: string | null, myLineType: string | null,
 *   myLineStatus: string | null, docProg: string | null, docParams: Record<string, string>, myTurn: boolean,
 *   mine: boolean, delegated: boolean}} AprBoxRow
 * @typedef {{seq: number, approverId: number, approverNm: string, lineType: string, status: string,
 *   actedAt: string | null, comment: string | null, actedByNm: string | null}} AprLine
 * @typedef {{action: string, seq: number | null, actorId: number, comment: string | null, occurredAt: string,
 *   onBehalfOf: number | null}} AprHist
 * @typedef {{delegId: number, delegatorId: number, delegatorNm: string, delegateId: number, delegateNm: string,
 *   docType: string | null, fromDate: string, toDate: string, reason: string, revokedAt: string | null,
 *   state: string, rowVer: number, mine: boolean, revokeCd: string | null}} AprDeleg
 * @typedef {{meaningCd: string, targetType: string, targetId: number, title: string | null}} AprSign
 * @typedef {{request: {aprId: number, docType: string, title: string, status: string, requesterId: number,
 *   requestedAt: string, finishedAt: string | null}, lines: AprLine[], history: AprHist[],
 *   esignApprove: AprSign | null, esignReject: AprSign | null}} AprDetail
 */
(function () {
    'use strict';

    var API = '/api/platform/approvals';
    var msg = /** @type {HTMLElement} */ (document.getElementById('msg'));
    var tabs = /** @type {HTMLElement} */ (document.getElementById('tabs'));
    var list = /** @type {HTMLElement} */ (document.getElementById('aprList'));
    var boxView = /** @type {HTMLElement} */ (document.getElementById('boxView'));
    var delegView = /** @type {HTMLElement} */ (document.getElementById('delegView'));
    /** 고른 결재 @type {AprBoxRow | null} */
    var current = null;
    /** 지금 연 결재의 상세(서명 안내) @type {AprDetail | null} */
    var detail = null;

    /** @param {string | null | undefined} text @param {boolean} [error] */
    function notify(text, error) {
        msg.textContent = text || '';
        msg.classList.toggle('hb-msg-error', !!error);
    }

    function shell() {
        var s = window.top && window.top !== window ? window.top.hbShell : null;
        return s && s !== true ? s : null;
    }

    function box() {
        var on = /** @type {HTMLInputElement | null} */ (tabs.querySelector('input[name="box"]:checked'));
        return on ? on.value : 'TODO';
    }

    /** @param {string} key 상태·구분 문구 — 서버 코드 그대로 쓰지 않게 고정 목록 @param {string} code */
    function label(key, code) {
        /** @type {Record<string, string>} */
        var m = key === 'status' ? {
            PENDING: hb.t('js.sysApproval.st.PENDING'), APPROVED: hb.t('js.sysApproval.st.APPROVED'),
            REJECTED: hb.t('js.sysApproval.st.REJECTED'), WITHDRAWN: hb.t('js.sysApproval.st.WITHDRAWN'),
            WAIT: hb.t('js.sysApproval.st.WAIT'), CURRENT: hb.t('js.sysApproval.st.CURRENT'),
            SKIPPED: hb.t('js.sysApproval.st.SKIPPED'), REF: hb.t('js.sysApproval.st.REF')
        } : {
            APPROVE: hb.t('js.sysApproval.lt.APPROVE'), AGREE: hb.t('js.sysApproval.lt.AGREE'), REF: hb.t('js.sysApproval.lt.REF'),
            SUBMIT: hb.t('js.sysApproval.act.SUBMIT'), REJECT: hb.t('js.sysApproval.act.REJECT'),
            WITHDRAW: hb.t('js.sysApproval.act.WITHDRAW')
        };
        return m[code] || code;
    }

    function loadList() {
        var b = box();
        boxView.hidden = b === 'DELEG';
        delegView.hidden = b !== 'DELEG';
        if (b === 'DELEG') { return loadDeleg(); }
        return hb.latest('list', hb.api(API + '/box?box=' + b)).then(function (res) {
            /** @type {AprBoxRow[]} */
            var rows = res.list || [];
            list.textContent = '';
            if (b === 'TODO') {
                var cnt = /** @type {HTMLElement} */ (document.getElementById('todoCnt'));
                cnt.textContent = String(rows.length);
                cnt.hidden = !rows.length;
            }
            if (!rows.length) { list.appendChild(hb.el('li', 'hb-inbox-empty', hb.t('js.sysApproval.empty'))); }
            rows.forEach(function (r) {
                var li = hb.el('li');
                li.setAttribute('data-unread', r.myLineStatus === 'CURRENT' ? 'true' : 'false');
                var btn = hb.el('button', 'hb-inbox-item');
                btn.type = 'button';
                btn.appendChild(hb.el('span', 'hb-inbox-title', r.title));
                var meta = hb.el('span', 'hb-inbox-meta', [r.docType, r.requesterNm, hb.fmt.datetime(r.requestedAt)].join(' · ')
                    + (r.delegated ? ' · ' + hb.t('js.sysApproval.deleg.asDelegate') : '') + ' ');
                meta.appendChild(sysUi.badge('apr', r.status, label('status', r.status)));
                btn.appendChild(meta);
                btn.addEventListener('click', function () {
                    list.querySelectorAll('[aria-current]').forEach(function (e) { e.removeAttribute('aria-current'); });
                    btn.setAttribute('aria-current', 'true');
                    open(r);
                });
                li.appendChild(btn);
                list.appendChild(li);
            });
        }, function (err) { if (!err.stale) { notify(err.message, true); } });
    }

    /** @param {AprBoxRow} r */
    function open(r) {
        current = r;
        notify('');
        hb.latest('detail', hb.api(API + '/' + r.aprId)).then(function (res) {
            /** @type {AprDetail} */
            var d = res.data;
            detail = d;
            /** @type {HTMLElement} */ (document.getElementById('aprEmpty')).hidden = true;
            /** @type {HTMLElement} */ (document.getElementById('aprDoc')).hidden = false;
            /** @type {HTMLElement} */ (document.getElementById('aprTitle')).textContent = d.request.title;
            var meta = /** @type {HTMLElement} */ (document.getElementById('aprMeta'));
            meta.textContent = [r.docType + ' ' + r.bizKey, r.requesterNm, hb.fmt.datetime(d.request.requestedAt)].join(' · ') + ' ';
            meta.appendChild(sysUi.badge('apr', d.request.status, label('status', d.request.status)));
            /** @type {Record<number, string>} */
            var names = {};
            var tbody = /** @type {HTMLElement} */ (/** @type {HTMLTableElement} */ (document.getElementById('aprLines')).tBodies[0]);
            tbody.textContent = '';
            d.lines.forEach(function (l) {
                names[l.approverId] = l.approverNm;
                var tr = hb.el('tr');
                [String(l.seq), l.approverNm + (l.actedByNm ? ' (' + hb.t('js.sysApproval.deleg.actedBy', l.actedByNm) + ')' : ''),
                    label('lineType', l.lineType), null, l.comment || '']
                    .forEach(function (v, i) {
                        var td = hb.el('td', null, v);
                        if (i === 3) { td.appendChild(sysUi.badge('aprLine', l.status, label('status', l.status))); }
                        tr.appendChild(td);
                    });
                tbody.appendChild(tr);
            });
            names[r.requesterId] = r.requesterNm;
            var hist = /** @type {HTMLElement} */ (document.getElementById('aprHistory'));
            hist.textContent = '';
            d.history.forEach(function (h) {
                var who = names[h.actorId] || String(h.actorId);
                if (h.onBehalfOf != null) {
                    who = hb.t('js.sysApproval.deleg.onBehalf', who, names[h.onBehalfOf] || String(h.onBehalfOf));
                }
                hist.appendChild(hb.el('li', null, [hb.fmt.datetime(h.occurredAt), who,
                    label('lineType', h.action)].join(' · ') + (h.comment ? ' — ' + h.comment : '')));
            });
            // 내 차례·내 결재는 목록 행(서버 판정) 기준 — 상세가 더 최신이면 상태로 한 번 더 거른다
            var pending = d.request.status === 'PENDING';
            var myTurn = pending && r.myTurn;
            var mine = pending && r.mine;
            toggle('approveBtn', myTurn);
            toggle('rejectBtn', myTurn);
            toggle('withdrawBtn', mine);
            /** @type {HTMLElement} */ (document.getElementById('actBox')).hidden = !myTurn && !mine;
            toggle('openDocBtn', !!r.docProg);
            hb.page.openDetail();
        }, function (err) { if (!err.stale) { notify(err.message, true); } });
    }

    /** @param {string} id @param {boolean} visible */
    function toggle(id, visible) {
        var b = document.getElementById(id);
        if (b) { b.hidden = !visible; }
    }

    /**
     * 처리 — 키는 확인한 순간 만든다(같은 누름의 재전송은 같은 키)
     * @param {string} id @param {string} action @param {string} confirmKey @param {string} titleKey @param {string} okKey @param {boolean} danger
     */
    function bindAct(id, action, confirmKey, titleKey, okKey, danger) {
        var b = /** @type {HTMLButtonElement | null} */ (document.getElementById(id));
        if (!b) { return; }
        var btn = b;
        btn.addEventListener('click', function () {
            var cur = current;
            if (!cur) { return; }
            var r = cur;
            sysUi.ask(titleKey, hb.t(confirmKey, r.title), okKey, danger).then(function (ok) { if (ok) { act(btn, r, action); } });
        });
    }

    /**
     * 문서 유형이 이 처리에 서명을 요구하면(상세의 esignApprove·esignReject) 먼저 서명 대화상자 — 취소하면 처리하지 않는다.
     * 판정(서명 필요·토큰 검증)은 서버 beforeAct, 여기는 안내대로 토큰을 받아 싣기만
     * @param {AprBoxRow} r @param {string} action @return {Promise<{token: string | null} | null>}
     */
    function signFor(r, action) {
        var d = detail && detail.request.aprId === r.aprId ? detail : null;
        var spec = !d ? null : action === 'approve' ? d.esignApprove : action === 'reject' ? d.esignReject : null;
        if (!spec) { return Promise.resolve({token: null}); }
        return hb.esign({meaning: spec.meaningCd, targetType: spec.targetType, targetId: spec.targetId, title: spec.title || r.title})
            .then(function (token) { return token ? {token: token} : null; });
    }

    /** @param {HTMLButtonElement} btn @param {AprBoxRow} r @param {string} action */
    function act(btn, r, action) {
        signFor(r, action).then(function (signed) {
            if (signed) { send(btn, r, action, signed.token); }
        });
    }

    /** @param {HTMLButtonElement} btn @param {AprBoxRow} r @param {string} action @param {string | null} token */
    function send(btn, r, action, token) {
        if (navigator.onLine === false) { syncOnline(); notify(hb.t('js.sysApproval.offline'), true); return; }
        var key = 'apr-' + r.aprId + '-' + action + '-' + Date.now() + '-' + Math.random().toString(36).slice(2, 10);
        var comment = /** @type {HTMLTextAreaElement} */ (document.getElementById('aprComment')).value;
        /** @type {{comment: string, esignToken?: string}} */
        var body = {comment: comment};
        if (token) { body.esignToken = token; }
        hb.busy(btn, function () {
            return hb.api(API + '/' + r.aprId + '/' + action, {method: 'POST', body: body,
                headers: {'X-Idempotency-Key': key}});
        }).then(function (res) {
            syncOnline();   // hb.busy 가 단추를 다시 켰다 — 그사이 끊겼으면 다시 끈다
            if (!res) { return; }
            /** @type {HTMLTextAreaElement} */ (document.getElementById('aprComment')).value = '';
            notify(hb.t('js.sysApproval.done'));
            var s = shell();
            if (s) { s.refreshBell(); }
            return loadList().then(function () { open(r); });
        }, function (err) { notify(err.message, true); });
    }
    bindAct('approveBtn', 'approve', 'js.sysApproval.confirmApprove', 'js.sysApproval.dlg.approve', 'js.sysApproval.dlg.approveOk', false);
    bindAct('rejectBtn', 'reject', 'js.sysApproval.confirmReject', 'js.sysApproval.dlg.reject', 'js.sysApproval.dlg.rejectOk', true);
    bindAct('withdrawBtn', 'withdraw', 'js.sysApproval.confirmWithdraw', 'js.sysApproval.dlg.withdraw', 'js.sysApproval.dlg.withdrawOk', false);

    /**
     * 결재는 연결됐을 때만(docs/45 A-3) — 오프라인이면 승인·반려·회수 단추를 끄고 알린다. 대기열에 쌓지 않는다
     * (끊긴 사이 다른 사람이 처리했을 수 있어, 다시 연결된 뒤 지금 상태를 보고 사람이 다시 누른다)
     */
    function syncOnline() {
        var off = navigator.onLine === false;
        ['approveBtn', 'rejectBtn', 'withdrawBtn'].forEach(function (id) {
            var b = /** @type {HTMLButtonElement | null} */ (document.getElementById(id));
            if (b) { b.disabled = off; }
        });
        /** @type {HTMLElement} */ (document.getElementById('offlineNote')).hidden = !off;
    }
    window.addEventListener('online', function () { syncOnline(); loadList(); });
    window.addEventListener('offline', syncOnline);
    syncOnline();

    var openDoc = /** @type {HTMLButtonElement} */ (document.getElementById('openDocBtn'));
    openDoc.addEventListener('click', function () {
        var r = current;
        if (!r || !r.docProg) { return; }
        var s = shell();
        if (s) {
            s.open(r.docProg, r.docType, r.docParams || null);
        } else {
            window.location.href = '/page/' + encodeURIComponent(r.docProg);
        }
    });

    // ── 결재 위임 (docs/36 14.6) — 판정(기간·겹침·자기·다른 회사)은 서버, 화면은 고르기·보여 주기만
    var delegForm = /** @type {HTMLFormElement | null} */ (document.getElementById('delegForm'));
    var delegTable = /** @type {HTMLTableElement} */ (document.getElementById('delegTable'));

    /** @param {string | null} docType */
    function docTypeLabel(docType) {
        return docType || hb.t('js.sysApproval.deleg.allTypes');
    }

    /** 상태 문구 — 키를 이어 붙이지 않게 고정 목록 @param {string} code */
    function delegState(code) {
        /** @type {Record<string, string>} */
        var m = {
            ACTIVE: hb.t('js.sysApproval.deleg.st.ACTIVE'), SCHEDULED: hb.t('js.sysApproval.deleg.st.SCHEDULED'),
            ENDED: hb.t('js.sysApproval.deleg.st.ENDED'), REVOKED: hb.t('js.sysApproval.deleg.st.REVOKED')
        };
        return m[code] || code;
    }

    function loadDeleg() {
        return hb.latest('deleg', hb.api(API + '/delegations')).then(function (res) {
            /** @type {AprDeleg[]} */
            var rows = res.list || [];
            var tbody = /** @type {HTMLElement} */ (delegTable.tBodies[0]);
            tbody.textContent = '';
            if (!rows.length) {
                var tr0 = hb.el('tr');
                var td0 = hb.el('td', 'hb-muted', hb.t('js.sysApproval.deleg.empty'));
                td0.colSpan = 6;
                tr0.appendChild(td0);
                tbody.appendChild(tr0);
            }
            rows.forEach(function (d) {
                var tr = hb.el('tr');
                [d.delegatorNm, d.delegateNm, docTypeLabel(d.docType), d.fromDate + ' ~ ' + d.toDate].forEach(function (v) { tr.appendChild(hb.el('td', null, v)); });
                var st = hb.el('td');
                st.appendChild(sysUi.badge('deleg', d.state, delegState(d.state)));
                if (d.revokeCd === 'DELEGATE_OFF') { st.appendChild(document.createTextNode(' (' + hb.t('js.sysApproval.deleg.delegateOff') + ')')); }
                tr.appendChild(st);
                tr.title = d.reason;
                var td = hb.el('td');
                // 위임자 본인만, 회수 안 했고 끝나지 않은 것만 — 판정은 서버(남의 것 404)
                if (delegForm && d.mine && (d.state === 'ACTIVE' || d.state === 'SCHEDULED')) {
                    var b = hb.el('button', 'hb-btn-secondary hb-btn-small', hb.t('js.sysApproval.deleg.revoke'));
                    b.type = 'button';
                    b.addEventListener('click', function () {
                        sysUi.ask('js.sysApproval.deleg.dlg.revoke', hb.t('js.sysApproval.deleg.confirmRevoke', d.delegateNm),
                            'js.sysApproval.deleg.dlg.revokeOk').then(function (ok) {
                            if (!ok) { return; }
                            hb.busy(b, function () {
                                return hb.api(API + '/delegations/' + d.delegId + '/revoke', {method: 'POST', body: {rowVer: d.rowVer}});
                            }).then(function (r) {
                                if (!r) { return; }
                                notify(hb.t('js.sysApproval.deleg.revoked'));
                                return loadDeleg();
                            }, function (err) { notify(err.message, true); });
                        });
                    });
                    td.appendChild(b);
                }
                tr.appendChild(td);
                tbody.appendChild(tr);
            });
        }, function (err) { if (!err.stale) { notify(err.message, true); } });
    }

    /** 문구를 받은 뒤 한 번 — 고르기 목록의 첫 줄도 문구라서 */
    var initDeleg = function () {};
    if (delegForm) {
        var dForm = delegForm;
        /** @param {string} name */
        var dField = function (name) {
            return /** @type {HTMLInputElement | HTMLSelectElement} */ (dForm.querySelector('[data-field="' + name + '"]'));
        };
        var sel = /** @type {HTMLSelectElement} */ (document.getElementById('delegSel'));
        var searchBox = /** @type {HTMLInputElement} */ (document.getElementById('delegSearch'));
        var findCandidates = function () {
            hb.latest('cand', hb.api(API + '/delegations/candidates?q=' + encodeURIComponent(searchBox.value))).then(function (res) {
                sel.textContent = '';
                sel.appendChild(new Option(hb.t('js.sysApproval.deleg.pick'), ''));
                (res.list || []).forEach(function (/** @type {{userId: number, loginId: string, userNm: string}} */ c) {
                    sel.appendChild(new Option(c.userNm + ' (' + c.loginId + ')', String(c.userId)));
                });
            }, function (err) { if (!err.stale) { notify(err.message, true); } });
        };
        /** @type {number | undefined} */
        var timer;
        searchBox.addEventListener('input', function () {
            window.clearTimeout(timer);
            timer = window.setTimeout(findCandidates, 300);
        });
        var typeSel = /** @type {HTMLSelectElement} */ (document.getElementById('docTypeSel'));
        var resetForm = function () {
            dForm.reset();
            dField('fromDate').value = hb.dates.today();
            dField('toDate').value = hb.dates.today();
            hb.showFieldErrors(dForm, []);
        };
        initDeleg = function () {
            typeSel.appendChild(new Option(hb.t('js.sysApproval.deleg.allTypes'), ''));
            hb.api(API + '/delegations/doc-types').then(function (res) {
                (res.list || []).forEach(function (/** @type {{docType: string, name: string}} */ t) {
                    typeSel.appendChild(new Option(t.name, t.docType));
                });
            }, function (err) { notify(err.message, true); });
            resetForm();
            findCandidates();
        };
        var saveBtn = /** @type {HTMLButtonElement} */ (document.getElementById('delegSaveBtn'));
        saveBtn.addEventListener('click', function () {
            hb.showFieldErrors(dForm, []);
            var body = {
                delegateId: sel.value ? Number(sel.value) : null, docType: typeSel.value || null,
                fromDate: dField('fromDate').value || null, toDate: dField('toDate').value || null,
                reason: dField('reason').value
            };
            hb.busy(saveBtn, function () {
                return hb.api(API + '/delegations', {method: 'POST', body: body});
            }).then(function (r) {
                if (!r) { return; }
                resetForm();
                notify(hb.t('js.sysApproval.deleg.created'));
                return loadDeleg();
            }, function (err) {
                notify(err.message, true);
                hb.showFieldErrors(dForm, err.errors);
            });
        });
    }

    tabs.addEventListener('change', function () { notify(''); loadList(); });
    hb.loadMessages().then(function () {
        hb.page.init();
        initDeleg();
        return loadList();
    }).then(null, function (err) { notify(err && err.message, true); });
})();
