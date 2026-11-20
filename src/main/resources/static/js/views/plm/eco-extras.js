/* 설계 변경 지시 (plm-eco) 4단계 영역 (docs/39 14장) — 일괄 대체(작성 중) · PPAP 패키지 · 고객 통지(PCN).
   eco-mgt.js 가 지시를 열 때 plm.ecoExtras.show(상세, 다시 읽기) 를 부른다. 판정은 모두 서버. */
(function () {
    'use strict';

    var API = '/api/plm/ecos';
    var ACTIONS = (document.body.getAttribute('data-actions') || '').split(',');
    var CAN_SAVE = ACTIONS.indexOf('SAVE') >= 0;
    var CAN_APPROVE = ACTIONS.indexOf('APPROVE') >= 0;
    var notify = plm.notify;
    var cur = null, reload = null, names = {}, custs = [], ppap = null, ppapSigns = [], elems = [], files = [], evidence = [], preview = null;
    /** PPAP 제출 메일 이력·메일 켜짐(37장) */
    var ppapMails = [], ppapMailOn = false;
    /** 이 사람이 요소 6·7 근거로 고를 수 있는 APQP 종류(38장 Q4 — 그 APQP 화면 SEARCH) */
    var apqpKinds = [];

    function $(id) { return document.getElementById(id); }
    function show_(el, on) { if (el) { el.hidden = !on; } }
    function nm(grp, code) { return (names[grp] && names[grp][code]) || code || ''; }
    function after(promise, okMsg) {
        return promise.then(function (res) {
            if (!res) { return; }
            return reload().then(function () { notify(okMsg || hb.t('js.common.saved')); });
        }, function (err) { notify(plm.errorText(err), true); });
    }

    // ------------------------------------------------------------------ 일괄 대체 (작성 중 지시만)

    var repBody = document.querySelector('#repRows tbody');

    function renderReplace() {
        var on = !!cur && cur.eco.status === 'DRAFT' && CAN_SAVE;
        show_($('replaceSection'), on);
        if (!on) { preview = null; }
        show_($('repRows'), !!preview);
        show_($('repRunBtn'), !!preview && preview.rows.some(function (r) { return r.action !== 'SKIP'; }));
        repBody.textContent = '';
        if (!preview) { return; }
        preview.rows.forEach(function (r) {
            var tr = hb.el('tr');
            var td = hb.el('td');
            if (r.action !== 'SKIP') {
                var cb = hb.el('input');
                cb.type = 'checkbox';
                cb.checked = true;
                cb.value = String(r.sourceBomId);
                cb.setAttribute('aria-label', r.parentCd);
                td.appendChild(cb);
            }
            tr.appendChild(td);
            tr.appendChild(hb.el('td', 'hb-wrap', r.parentCd + ' · ' + (r.parentNm || '')));
            tr.appendChild(hb.el('td', null, (r.sourceRev || '') + (r.newRev ? ' → ' + r.newRev : '')));
            tr.appendChild(hb.el('td', r.action === 'SKIP' ? 'hb-muted' : null, r.action + (r.reason ? ' · ' + r.reason : '')
                + (r.unitWarn ? ' · ' + hb.t('js.plmEco.unitWarn') : '')));
            repBody.appendChild(tr);
        });
    }

    var repPreviewBtn = $('repPreviewBtn');
    repPreviewBtn.addEventListener('click', function () {
        var from = $('repFrom').value.trim(), to = $('repTo').value.trim();
        hb.busy(repPreviewBtn, function () {
            return hb.api(API + '/' + cur.eco.ecoId + '/replace-preview?fromItemCd=' + encodeURIComponent(from)
                + '&toItemCd=' + encodeURIComponent(to));
        }).then(function (res) {
            if (!res) { return; }
            preview = res.data;
            preview.ecoId = cur.eco.ecoId;
            renderReplace();
            notify(hb.t('js.plmEco.replaceRows', preview.rows.length));
        }, function (err) { preview = null; renderReplace(); notify(plm.errorText(err), true); });
    });

    var repRunBtn = $('repRunBtn');
    repRunBtn.addEventListener('click', function () {
        var ids = Array.prototype.map.call(repBody.querySelectorAll('input[type=checkbox]:checked'), function (c) { return Number(c.value); });
        if (!ids.length) { return; }
        plm.ask('js.plmEco.dlg.replace', hb.t('js.plmEco.replaceConfirm', preview.fromItemCd, preview.toItemCd, ids.length),
            'js.plmEco.dlg.replaceOk').then(function (ok) {
            if (!ok) { return; }
            after(hb.busy(repRunBtn, function () {
                return hb.api(API + '/' + cur.eco.ecoId + '/replace', {method: 'POST', body: {rowVer: cur.eco.rowVer,
                    fromItemCd: preview.fromItemCd, toItemCd: preview.toItemCd, bomIds: ids}});
            }).then(function (res) { if (res) { preview = null; } return res; }), hb.t('js.plmEco.replaced'));
        });
    });

    // ------------------------------------------------------------------ PPAP

    var elemBody = document.querySelector('#ppapElems tbody');

    function levelOptions(sel, value) {
        sel.textContent = '';
        for (var i = 1; i <= 5; i++) { plm.option(sel, i, hb.t('js.plmEco.level', i)); }
        sel.value = String(value || 3);
    }

    function codeSelect(grp, value, label) {
        var sel = /** @type {HTMLSelectElement} */ (hb.el('select'));
        Object.keys(names[grp] || {}).forEach(function (k) { plm.option(sel, k, names[grp][k]); });
        sel.value = value;
        sel.setAttribute('aria-label', label);
        return sel;
    }

    function loadPpap() {
        var e = cur.eco;
        var id = e.ecoId;
        return Promise.all([hb.api(API + '/' + id + '/ppap'),
            hb.api('/api/platform/files?targetType=plm-eco&targetId=' + id)]).then(function (r) {
            if (!cur || cur.eco.ecoId !== id) { return; }
            ppap = r[0].data.ppap;
            elems = r[0].data.elems;
            ppapSigns = r[0].data.signs || [];
            ppapMails = r[0].data.mails || [];
            ppapMailOn = !!r[0].data.mailEnabled;
            apqpKinds = r[0].data.apqpKinds || [];
            files = r[1].list || [];
            // 2단계: PPAP 증빙(plm-eco × PPAP-{id}) — 요소 첨부는 ECO 첨부 + 증빙 중에서
            if (!ppap) { evidence = []; renderPpap(); return; }
            var ppapId = ppap.ppapId;
            return hb.api('/api/platform/files?targetType=plm-eco&targetId=PPAP-' + ppapId).then(function (f) {
                // QA QS-2: 그 사이 다른 지시를 열었으면(또는 패키지가 바뀌었으면) 늦게 온 증빙은 버린다
                if (!cur || cur.eco.ecoId !== id || !ppap || ppap.ppapId !== ppapId) { return; }
                evidence = f.list || [];
                renderPpap();
            });
        }, function (err) { notify(err.message, true); });
    }

    function renderPpap() {
        var e = cur.eco;
        var section = $('ppapSection');
        var canCreate = CAN_SAVE && !ppap && e.status !== 'CANCELLED';
        show_(section, !!ppap || canCreate);
        show_($('ppapCreate'), canCreate);
        if (canCreate) {
            var cs = $('ppapCust');
            cs.textContent = '';
            plm.option(cs, '', hb.t('js.plmEco.ppapCustEco'));
            custs.forEach(function (c) { plm.option(cs, c.custCd, c.custCd + ' · ' + c.custNm); });
            levelOptions($('ppapLevel'), 3);
        }
        elemBody.textContent = '';
        show_($('ppapElems'), !!ppap);
        var draft = !!ppap && ppap.status === 'DRAFT';
        var editable = draft && CAN_SAVE;
        show_($('ppapTools'), !!ppap && CAN_SAVE && (draft || ppap.status === 'INTERIM' || ppap.status === 'REJECTED'));
        show_($('ppapSaveBtn'), editable);
        show_($('ppapSubmitBtn'), editable);
        show_($('ppapLevelEdit'), editable);
        show_($('ppapReopenBtn'), !!ppap && (ppap.status === 'INTERIM' || ppap.status === 'REJECTED'));
        show_($('ppapDecide'), !!ppap && ppap.status === 'SUBMITTED' && CAN_APPROVE);
        show_($('ppapDoc'), !!ppap);
        renderPpapMail();
        renderEvidence(draft);
        plm.signs($('ppapSigns'), ppapSigns);
        if (!ppap) { $('ppapInfo').textContent = ''; return; }
        // 상태는 배지(부록 A), 나머지는 글자
        plm.setBadge($('ppapInfo'), plm.badge('ppap', ppap.status, nm('PLM_PPAP_STATUS', ppap.status)),
            [ppap.custCd + ' · ' + ppap.custNm, hb.t('js.plmEco.level', ppap.submitLevel),
                ppap.submittedOn ? hb.t('js.plmEco.submittedOn', hb.fmt.date(ppap.submittedOn)) : '',
                ppap.decidedOn ? hb.t('js.plmEco.decidedOn', hb.fmt.date(ppap.decidedOn)) + (ppap.decisionRef ? ' · ' + ppap.decisionRef : '')
                    + (ppap.decisionNote ? ' · ' + ppap.decisionNote : '') : ''].filter(Boolean).join(' · '));
        if (editable) { levelOptions($('ppapLevelEdit'), ppap.submitLevel); }
        if (ppap.status === 'SUBMITTED') {
            var ds = $('ppapDecision');
            if (ds && !ds.options.length) { ['APPROVED', 'INTERIM', 'REJECTED'].forEach(function (k) { plm.option(ds, k, nm('PLM_PPAP_STATUS', k)); }); }
            if ($('ppapDecidedOn')) { $('ppapDecidedOn').value = hb.dates.today(); }
        }
        elems.forEach(function (el) {
            var tr = hb.el('tr');
            tr.setAttribute('data-row-key', 'e' + el.elemNo);
            tr.appendChild(hb.el('td', 'hb-num', String(el.elemNo)));
            tr.appendChild(hb.el('td', 'hb-wrap', nm('PLM_PPAP_ELEM', String(el.elemNo))));
            if (editable) {
                var req = codeSelect('PLM_PPAP_REQ', el.reqCd, hb.t('js.plmEco.reqFor', el.elemNo));
                req.addEventListener('change', function () { el.reqCd = req.value; hb.dirty.mark(); });
                var st = codeSelect('PLM_PPAP_ELEM_STATUS', el.status, hb.t('js.plmEco.statusFor', el.elemNo));
                st.addEventListener('change', function () { el.status = st.value; hb.dirty.mark(); });
                var fs = /** @type {HTMLSelectElement} */ (hb.el('select'));
                plm.option(fs, '', '-');
                files.concat(evidence).forEach(function (f) { plm.option(fs, f.fileId, f.name); });
                fs.value = el.fileId ? String(el.fileId) : '';
                fs.setAttribute('aria-label', hb.t('js.plmEco.fileFor', el.elemNo));
                fs.addEventListener('change', function () { el.fileId = fs.value ? Number(fs.value) : null; hb.dirty.mark(); });
                var note = /** @type {HTMLInputElement} */ (hb.el('input'));
                note.maxLength = 1000;
                note.value = el.note || '';
                note.setAttribute('aria-label', hb.t('js.plmEco.noteFor', el.elemNo));
                note.addEventListener('input', function () { el.note = note.value; hb.dirty.mark(); });
                [req, st, fs, note].forEach(function (x) {
                    var td = hb.el('td');
                    td.appendChild(x);
                    if (x === fs) { appendApqp(td, el, true); }
                    tr.appendChild(td);
                });
            } else {
                tr.appendChild(hb.el('td', null, nm('PLM_PPAP_REQ', el.reqCd)));
                tr.appendChild(hb.el('td', null, nm('PLM_PPAP_ELEM_STATUS', el.status)));
                var ftd = hb.el('td');
                if (el.fileId) {
                    var a = hb.el('a', null, el.fileName || String(el.fileId));
                    a.href = '/api/platform/files/' + el.fileId;
                    ftd.appendChild(a);
                }
                appendApqp(ftd, el, false);
                tr.appendChild(ftd);
                tr.appendChild(hb.el('td', 'hb-wrap', el.note || ''));
            }
            elemBody.appendChild(tr);
        });
    }

    /**
     * 요소 6·7 APQP 근거(38장) — 연결한 때의 label + 지금 상태 배지(폐기 = 경고). 작성 중·저장 권한·그 APQP 조회 권한이면 [APQP 에서 고르기]·[연결 끊기]
     * @param {HTMLElement} td
     * @param {any} el
     * @param {boolean} editable
     */
    function appendApqp(td, el, editable) {
        var kind = el.elemNo === 6 ? 'FMEA' : el.elemNo === 7 ? 'CP' : null;
        if (!kind) { return; }
        var can = editable && apqpKinds.indexOf(kind) >= 0;
        if (!el.apqpLabel && !can) { return; }
        var box = hb.el('div', 'hb-apqp');
        if (el.apqpLabel) {
            var line = hb.el('div', null, hb.t('js.plmEco.apqp.label', el.apqpLabel) + ' ');
            if (el.apqpStatus) {
                line.appendChild(plm.badge('apqp', el.apqpStatus, el.apqpStatus === 'OBSOLETE' ? hb.t('js.plmEco.apqp.obsolete')
                    : el.apqpStatus === 'RELEASED' ? hb.t('js.plmEco.apqp.released') : el.apqpStatus));
            }
            box.appendChild(line);
        }
        if (can) {
            var pick = hb.el('button', 'hb-btn-secondary hb-btn-small', hb.t('js.plmEco.apqp.pick'));
            pick.setAttribute('type', 'button');
            pick.setAttribute('aria-label', hb.t('js.plmEco.apqp.pickFor', el.elemNo));
            pick.addEventListener('click', function () { openApqp(el, kind, box, pick); });
            box.appendChild(pick);
            if (el.apqpLabel) {
                var un = hb.el('button', 'hb-btn-secondary hb-btn-small', hb.t('js.plmEco.apqp.unlink'));
                un.setAttribute('type', 'button');
                un.addEventListener('click', function () { linkApqp(el, null, un); });
                box.appendChild(un);
            }
        }
        td.appendChild(box);
    }

    /**
     * 후보(지시 대상 품목의 현행 확정본) 고르기 — 저장 안 한 요소 입력이 있으면 먼저 저장하게(연결은 패키지 row_ver 를 올린다)
     * @param {any} el
     * @param {string} kind
     * @param {HTMLElement} box
     * @param {HTMLElement} btn
     */
    function openApqp(el, kind, box, btn) {
        if (hb.dirty.isDirty()) { notify(hb.t('js.plmEco.apqp.saveFirst'), true); return; }
        hb.busy(btn, function () { return hb.api('/api/plm/ppaps/' + ppap.ppapId + '/apqp-candidates'); }).then(function (res) {
            if (!res) { return; }
            var list = kind === 'FMEA' ? res.data.fmea : res.data.cp;
            btn.hidden = true;
            if (!list.length) { box.appendChild(hb.el('div', 'hb-muted', hb.t('js.plmEco.apqp.none'))); return; }
            var sel = /** @type {HTMLSelectElement} */ (hb.el('select'));
            sel.setAttribute('aria-label', hb.t('js.plmEco.apqp.pickFor', el.elemNo));
            list.forEach(function (/** @type {any} */ c) {
                plm.option(sel, String(c.revId), [c.itemCd, c.no + ' rev ' + c.rev, c.phase ? nm('APQP_CP_PHASE', c.phase) : '', c.title,
                    hb.fmt.datetime(c.releasedAt)].filter(Boolean).join(' · '));
            });
            if (el.apqpRevId) { sel.value = String(el.apqpRevId); }
            var ok = hb.el('button', 'hb-btn-primary hb-btn-small', hb.t('js.plmEco.apqp.link'));
            ok.setAttribute('type', 'button');
            ok.addEventListener('click', function () { linkApqp(el, Number(sel.value), ok); });
            box.appendChild(sel);
            box.appendChild(ok);
            sel.focus();
        }, function (err) { notify(plm.errorText(err), true); });
    }

    /**
     * @param {any} el
     * @param {number|null} revId
     * @param {HTMLElement} btn
     */
    function linkApqp(el, revId, btn) {
        hb.busy(btn, function () {
            return hb.api('/api/plm/ppaps/' + ppap.ppapId + '/apqp', {method: 'PUT', body: {rowVer: ppap.rowVer, elemNo: el.elemNo, revId: revId}});
        }).then(function (res) {
            if (!res) { return; }
            return reload().then(function () { notify(hb.t('js.common.saved')); });
        }, markElems);
    }

    /** 요소 행에 칸 오류 표시 — 행으로 보이므로 알림 줄은 요약만(요소 18개 문구를 잇지 않는다) */
    /** PPAP 제출 메일(37장) — 제출(SUBMITTED)·저장 권한·메일 켜짐·취소 아닌 지시일 때 [메일 보내기], 이력 줄은 늘 */
    function renderPpapMail() {
        var box = $('ppapMail');
        if (!box) { return; }
        var lines = $('ppapMailLines');
        lines.textContent = '';
        var canSend = !!ppap && ppap.status === 'SUBMITTED' && CAN_SAVE && ppapMailOn && cur.eco.status !== 'CANCELLED';
        show_($('ppapMailBtn'), canSend);
        ppapMails.forEach(function (m) { lines.appendChild(mailLine(m, m.summaryFileId, m.summaryFileNm)); });
        show_(box, canSend || ppapMails.length > 0);
    }
    (function () {
        var btn = $('ppapMailBtn');
        if (!btn) { return; }
        btn.setAttribute('aria-expanded', 'false');
        btn.addEventListener('click', function () {
            toggleMail({key: 'PPAP-' + ppap.ppapId, base: '/api/plm/ppaps/' + ppap.ppapId, title: hb.t('js.plmEco.ppapMail.title'),
                section: 'ppapSection', noContactKey: 'js.plmEco.ppapMail.noContact', fixedAttachKey: 'js.plmEco.ppapMail.attach'}, btn);
        });
    })();

    function renderEvidence(draft) {
        var box = $('ppapEvidence');
        show_(box, !!ppap);
        var ul = $('ppapFiles');
        ul.textContent = '';
        show_($('ppapFileInput'), draft && CAN_SAVE);
        if (!ppap) { return; }
        if (!evidence.length) { ul.appendChild(hb.el('li', 'hb-muted', hb.t('js.plmEco.noPpapFiles'))); }
        evidence.forEach(function (f) {
            var li = hb.el('li');
            var a = hb.el('a', null, f.name);
            a.href = '/api/platform/files/' + f.fileId;
            li.appendChild(a);
            li.appendChild(hb.el('span', 'hb-muted', ' ' + hb.fmt.datetime(f.createdAt) + ' · ' + f.createdBy));
            ul.appendChild(li);
        });
    }

    var ppapFileInput = /** @type {HTMLInputElement|null} */ ($('ppapFileInput'));
    if (ppapFileInput) {
        ppapFileInput.addEventListener('change', function () {
            var file = ppapFileInput.files && ppapFileInput.files[0];
            if (!file || !ppap) { return; }
            var fd = new FormData();
            fd.append('targetType', 'plm-eco');
            fd.append('targetId', 'PPAP-' + ppap.ppapId);
            fd.append('file', file);
            hb.upload('/api/platform/files', fd).then(function () {
                ppapFileInput.value = '';
                return loadPpap().then(function () { notify(hb.t('js.common.saved')); });
            }, function (err) { ppapFileInput.value = ''; notify(plm.errorText(err), true); });
        });
    }

    function markElems(err) {
        notify((err.errors || []).some(function (x) { return x.rowKey; }) ? err.message : plm.errorText(err), true);
        elemBody.querySelectorAll('.hb-field-error').forEach(function (x) { x.remove(); });
        (err.errors || []).forEach(function (x) {
            var tr = x.rowKey ? elemBody.querySelector('tr[data-row-key="' + x.rowKey + '"]') : null;
            if (tr && tr.lastChild) { tr.lastChild.appendChild(hb.el('span', 'hb-field-error', x.message)); }
        });
    }

    var ppapCreateBtn = $('ppapCreateBtn');
    if (ppapCreateBtn) {
        ppapCreateBtn.addEventListener('click', function () {
            hb.busy(ppapCreateBtn, function () {
                return hb.api(API + '/' + cur.eco.ecoId + '/ppap', {method: 'POST',
                    body: {custCd: $('ppapCust').value || null, submitLevel: Number($('ppapLevel').value)}});
            }).then(function (res) { if (res) { return loadPpap().then(function () { notify(hb.t('js.common.saved')); }); } },
                function (err) { notify(plm.errorText(err), true); });
        });
    }

    function ppapAct(btnId, path, body, okKey) {
        var btn = $(btnId);
        if (!btn) { return; }
        btn.addEventListener('click', function () {
            hb.busy(btn, function () {
                return hb.api('/api/plm/ppaps/' + ppap.ppapId + path, {method: path ? 'POST' : 'PUT', body: body()});
            }).then(function (res) {
                if (!res) { return; }
                hb.dirty.clear();
                return reload().then(function () { notify(hb.t(okKey)); });
            }, markElems);
        });
    }
    ppapAct('ppapSaveBtn', '', function () {
        return {rowVer: ppap.rowVer, submitLevel: Number($('ppapLevelEdit').value), remark: ppap.remark,
            elems: elems.map(function (el) { return {elemNo: el.elemNo, reqCd: el.reqCd, status: el.status, fileId: el.fileId || null, note: el.note || ''}; })};
    }, 'js.common.saved');
    ppapAct('ppapSubmitBtn', '/submit', function () { return {rowVer: ppap.rowVer}; }, 'js.plmEco.ppapSubmitted');
    ppapAct('ppapReopenBtn', '/reopen', function () { return {rowVer: ppap.rowVer}; }, 'js.common.saved');
    // 고객 제출용 요약서 엑셀(docs/39 31장) — 저장된 내용 기준
    (function () {
        var btn = $('ppapSummaryBtn');
        if (!btn) { return; }
        btn.addEventListener('click', function () {
            hb.busy(btn, function () { return hb.download('/api/plm/ppaps/' + ppap.ppapId + '/summary'); })
                .then(function () { notify(hb.t('js.plmEco.ppapSummaryDone')); }, function (err) { notify(err.message, true); });
        });
    })();
    // 판정 기록 = 전자서명(19장, 의미 AUTHOR) — 대화상자를 취소하면 보내지 않는다
    (function () {
        var btn = $('ppapDecideBtn');
        if (!btn) { return; }
        btn.addEventListener('click', function () {
            hb.esign({meaning: 'AUTHOR', targetType: 'plm_ppap', targetId: ppap.ppapId,
                title: cur.eco.ecoNo + ' · PPAP · ' + nm('PLM_PPAP_STATUS', $('ppapDecision').value)})
                .then(function (token) {
                    if (!token) { return null; }
                    return hb.busy(btn, function () {
                        return hb.api('/api/plm/ppaps/' + ppap.ppapId + '/decide', {method: 'POST',
                            body: {rowVer: ppap.rowVer, decision: $('ppapDecision').value, decidedOn: $('ppapDecidedOn').value,
                                ref: $('ppapRef').value, note: $('ppapNote').value, esignToken: token}});
                    });
                })
                .then(function (res) {
                    if (!res) { return; }
                    hb.dirty.clear();
                    return reload().then(function () { notify(hb.t('js.common.saved')); });
                }, markElems);
        });
    })();

    // ------------------------------------------------------------------ PCN

    var pcnBody = document.querySelector('#pcns tbody');

    function loadPcns() {
        var id = cur.eco.ecoId;
        show_($('pcnSection'), true);
        show_($('pcnTools'), cur.eco.status !== 'CANCELLED');
        var cs = $('pcnCust');
        if (cs && !cs.options.length) {
            plm.option(cs, '', hb.t('js.common.choose'));
            custs.forEach(function (c) { plm.option(cs, c.custCd, c.custCd + ' · ' + c.custNm); });
        }
        if ($('pcnOn') && !$('pcnOn').value) { $('pcnOn').value = hb.dates.today(); }
        return hb.api(API + '/' + id + '/pcns').then(function (res) {
            if (!cur || cur.eco.ecoId !== id) { return; }
            closeMail();
            pcnBody.textContent = '';
            if (!res.data.length) {
                var tr0 = hb.el('tr');
                var td0 = hb.el('td', 'hb-muted', hb.t('js.plmEco.noPcns'));
                td0.colSpan = 6;
                tr0.appendChild(td0);
                pcnBody.appendChild(tr0);
            }
            res.data.forEach(function (n) {
                var tr = hb.el('tr');
                tr.appendChild(hb.el('td', null, n.pcnNo));
                tr.appendChild(hb.el('td', 'hb-wrap', n.custCd + ' · ' + n.custNm));
                tr.appendChild(hb.el('td', null, hb.fmt.date(n.notifiedOn)));
                var due = hb.el('td', null, hb.fmt.date(n.responseDue));
                if (n.overdue) { due.appendChild(hb.el('span', 'hb-warn', ' ' + hb.t('js.plmEco.overdue'))); }
                tr.appendChild(due);
                var pst = hb.el('td');
                pst.appendChild(plm.badge('pcn', n.status, nm('PLM_PCN_STATUS', n.status)));
                tr.appendChild(pst);
                var resp = hb.el('td', 'hb-wrap');
                if (n.status === 'SENT' && CAN_SAVE) {
                    var sel = /** @type {HTMLSelectElement} */ (hb.el('select'));
                    ['ACK', 'APPROVED', 'REJECTED'].forEach(function (k) { plm.option(sel, k, nm('PLM_PCN_STATUS', k)); });
                    sel.setAttribute('aria-label', hb.t('js.plmEco.responseFor', n.pcnNo));
                    var on = /** @type {HTMLInputElement} */ (hb.el('input'));
                    on.type = 'date';
                    on.value = hb.dates.today();
                    on.setAttribute('aria-label', hb.t('js.plmEco.respondedOnFor', n.pcnNo));
                    var btn = hb.el('button', 'hb-btn-secondary hb-btn-small', hb.t('js.plmEco.record'));
                    btn.type = 'button';
                    btn.addEventListener('click', function () {
                        after(hb.busy(btn, function () {
                            return hb.api('/api/plm/pcns/' + n.pcnId + '/response', {method: 'POST',
                                body: {rowVer: n.rowVer, status: sel.value, respondedOn: on.value}});
                        }));
                    });
                    [sel, on, btn].forEach(function (x) { resp.appendChild(x); });
                    if (n.mailEnabled) {
                        var mailBtn = hb.el('button', 'hb-btn-secondary hb-btn-small', hb.t('js.plmEco.mail.open'));
                        mailBtn.type = 'button';
                        mailBtn.setAttribute('aria-expanded', 'false');
                        mailBtn.addEventListener('click', function () {
                            toggleMail({key: 'PCN-' + n.pcnId, base: '/api/plm/pcns/' + n.pcnId, title: hb.t('js.plmEco.mail.title', n.pcnNo),
                                section: 'pcnSection', notice: true}, mailBtn);
                        });
                        resp.appendChild(mailBtn);
                    }
                } else {
                    resp.textContent = (n.respondedOn ? hb.fmt.date(n.respondedOn) : '') + (n.responseNote ? ' · ' + n.responseNote : '')
                        + (n.note ? (n.respondedOn ? ' / ' : '') + n.note : '');
                }
                // 메일 발송 이력(23장) — 받는 이름·주소는 보낸 때 그대로
                // 발송 결과(32장) — 배지 + 실패·재시도 이유, 보냄은 메일 서버 접수까지(읽음은 모른다 — 툴팁)
                // 붙인 통지서 사본(33장) — 고객이 받은 그 파일
                (n.mails || []).forEach(function (m) { resp.appendChild(mailLine(m, m.noticeFileId, m.noticeFileNm)); });
                tr.appendChild(resp);
                pcnBody.appendChild(tr);
            });
        }, function (err) { notify(err.message, true); });
    }

    /** 요청 멱등 키 — randomUUID 가 없는 문맥(http 내부망)도 */
    function reqId() {
        if (window.crypto && typeof window.crypto.randomUUID === 'function') { return window.crypto.randomUUID(); }
        var a = new Uint8Array(16);
        window.crypto.getRandomValues(a);
        return Array.prototype.map.call(a, function (/** @type {number} */ x) { return ('0' + x.toString(16)).slice(-2); }).join('');
    }

    /**
     * 고객 담당자에게 메일(docs/39 23장) — PCN 표 아래 펼침: 담당자 고르기(대표 기본 체크) + 제목·본문 미리보기 → [보내기] → 확인 대화상자.
     * 받는 사람은 서버가 준 담당자 중에서만(주소 입력 칸 없음). 판정은 서버. 펼침은 표 밖(좁은 화면에서 표 가로 스크롤에 갇히지 않게)
     */
    /**
     * 고객 메일 발송 이력 한 줄(PCN 23·32·33장, PPAP 37장) — 받는 이름·주소는 보낸 때 그대로, 결과 배지 + 실패·재시도 이유
     * (보냄은 메일 서버 접수까지 — 읽음은 모른다, 툴팁), 붙인 사본 링크
     * @param {any} m @param {number|null|undefined} fileId @param {string|null|undefined} fileNm
     */
    function mailLine(m, fileId, fileNm) {
        var line = hb.el('div', 'hb-muted', hb.t('js.plmEco.mail.line', m.toNm + ' <' + m.toAddr + '>', hb.fmt.datetime(m.sentAt), m.sentBy));
        if (m.mailState) {
            var b = plm.badge('mail', m.mailState, m.stateNm);
            if (m.mailState === 'SENT') {
                b.title = hb.t('js.plmEco.mail.sentTip') + (m.acceptedAt ? ' · ' + hb.fmt.datetime(m.acceptedAt) : '');
            }
            line.appendChild(document.createTextNode(' '));
            line.appendChild(b);
            if (m.reasonNm) { line.appendChild(document.createTextNode(' ' + m.reasonNm)); }
        }
        if (fileId) {
            line.appendChild(document.createTextNode(' · ' + hb.t('js.plmEco.mail.attached') + ' '));
            var a = hb.el('a', null, fileNm || '');
            a.href = '/api/platform/files/' + fileId;
            line.appendChild(a);
        }
        return line;
    }

    var mailPanel = null, mailPanelFor = null, mailPanelBtn = null;

    function closeMail() {
        if (mailPanel && mailPanel.parentNode) { mailPanel.parentNode.removeChild(mailPanel); }
        if (mailPanelBtn) { mailPanelBtn.setAttribute('aria-expanded', 'false'); }
        mailPanel = mailPanelFor = mailPanelBtn = null;
    }

    /**
     * 고객 담당자에게 메일 펼침 — PCN(통지서 첨부 체크, 33장)·PPAP(요약서 첨부 고정, 37장) 공통.
     * @param {{key: string, base: string, title: string, section: string, notice?: boolean, noContactKey?: string, fixedAttachKey?: string}} cfg
     * @param {HTMLElement} btn
     */
    function toggleMail(cfg, btn) {
        var same = mailPanelFor === cfg.key;
        closeMail();
        if (same) { return; }
        hb.api(cfg.base + '/mail-targets').then(function (res) {
            var t = res.data;
            var panel = hb.el('div', 'hb-sub');
            panel.appendChild(hb.el('h3', null, cfg.title));
            if (!t.enabled) {
                panel.appendChild(hb.el('p', 'hb-muted', hb.t('js.plmEco.mail.off')));
            } else if (!t.contacts.length) {
                panel.appendChild(hb.el('p', 'hb-muted', hb.t(cfg.noContactKey || 'js.plmEco.mail.noContact')));
            } else {
                var fs = hb.el('fieldset', 'hb-check-list');
                fs.appendChild(hb.el('legend', null, hb.t('js.plmEco.mail.to')));
                /** @type {{box: HTMLInputElement, text: string}[]} */
                var picks = [];
                t.contacts.forEach(function (c) {
                    var text = c.contactNm + (c.deptNm ? ' (' + c.deptNm + ')' : '') + ' <' + c.email + '>';
                    var lb = hb.el('label', 'hb-check');
                    var cb = /** @type {HTMLInputElement} */ (hb.el('input'));
                    cb.type = 'checkbox';
                    cb.value = String(c.contactId);
                    cb.checked = !!c.main;
                    lb.appendChild(cb);
                    lb.appendChild(document.createTextNode(text));
                    picks.push({box: cb, text: text});
                    fs.appendChild(lb);
                });
                panel.appendChild(fs);
                // 변경 통지서 엑셀 첨부(33장) — 보낼 때 그 시점 내용으로 사본을 만든다. 결재 전이면 초안 표시가 찍힌다
                var attBox = /** @type {HTMLInputElement} */ (hb.el('input'));
                if (cfg.notice) {
                    var att = hb.el('label', 'hb-check');
                    attBox.type = 'checkbox';
                    attBox.checked = true;
                    att.appendChild(attBox);
                    att.appendChild(document.createTextNode(hb.t('js.plmEco.mail.attachNotice')));
                    panel.appendChild(att);
                    if (cur.eco.status === 'DRAFT' || cur.eco.status === 'PENDING') {
                        panel.appendChild(hb.el('p', 'hb-muted', hb.t('js.plmEco.mail.attachDraft')));
                    }
                } else if (cfg.fixedAttachKey) {
                    // PPAP(37장 P4) — 요약서 한 장 고정(고를 것 없음)
                    panel.appendChild(hb.el('p', null, hb.t(cfg.fixedAttachKey)));
                }
                panel.appendChild(hb.el('p', null, hb.t('js.plmEco.mail.subject', t.subject)));
                // 본문 미리보기 — 글자 메일 그대로(줄바꿈 유지, 화면 글자로만 넣는다)
                var pv = hb.el('fieldset');
                pv.appendChild(hb.el('legend', null, hb.t('js.plmEco.mail.preview')));
                String(t.preview || '').split('\n').forEach(function (line, i) {
                    if (i) { pv.appendChild(hb.el('br')); }
                    pv.appendChild(document.createTextNode(line));
                });
                panel.appendChild(pv);
                var bar = hb.el('div', 'hb-toolbar');
                var send = hb.el('button', 'hb-btn-primary', hb.t('js.plmEco.mail.send'));
                send.type = 'button';
                send.addEventListener('click', function () {
                    var picked = picks.filter(function (x) { return x.box.checked; });
                    if (!picked.length) { notify(hb.t('js.plmEco.mail.pick'), true); return; }
                    var who = picked.map(function (x) { return x.text; }).join(', ');
                    plm.ask('js.plmEco.mail.confirm', hb.t('js.plmEco.mail.confirmMsg', picked.length, who), 'js.plmEco.mail.send')
                        .then(function (ok) {
                            if (!ok) { return; }
                            var key = reqId();   // 연타·재전송이 메일을 두 번 보내지 않게(서버 필수)
                            after(hb.busy(send, function () {
                                /** @type {any} */
                                var body = {contactIds: picked.map(function (x) { return Number(x.box.value); })};
                                if (cfg.notice) { body.attachNotice = attBox.checked; }
                                return hb.api(cfg.base + '/mail', {method: 'POST', headers: {'X-Idempotency-Key': key}, body: body});
                            }), hb.t('js.plmEco.mail.sent', picked.length));
                        });
                });
                bar.appendChild(send);
                var close = hb.el('button', 'hb-btn-secondary', hb.t('js.plmEco.mail.close'));
                close.type = 'button';
                close.addEventListener('click', closeMail);
                bar.appendChild(close);
                panel.appendChild(bar);
            }
            var section = $(cfg.section);
            section.appendChild(panel);
            mailPanel = panel;
            mailPanelFor = cfg.key;
            mailPanelBtn = btn;
            btn.setAttribute('aria-expanded', 'true');
            panel.scrollIntoView({block: 'nearest'});
        }, function (err) { notify(plm.errorText(err), true); });
    }

    var pcnAddBtn = $('pcnAddBtn');
    if (pcnAddBtn) {
        pcnAddBtn.addEventListener('click', function () {
            after(hb.busy(pcnAddBtn, function () {
                return hb.api(API + '/' + cur.eco.ecoId + '/pcns', {method: 'POST', body: {custCd: $('pcnCust').value,
                    notifiedOn: $('pcnOn').value, responseDue: $('pcnDue').value, note: $('pcnNote').value}});
            }).then(function (res) { if (res) { $('pcnNote').value = ''; } return res; }));
        });
    }

    // ------------------------------------------------------------------ 진입

    var ready = hb.loadMessages().then(function () {
        return Promise.all([hb.codes('PLM_PPAP_STATUS'), hb.codes('PLM_PPAP_ELEM'), hb.codes('PLM_PPAP_REQ'),
            hb.codes('PLM_PPAP_ELEM_STATUS'), hb.codes('PLM_PCN_STATUS'), hb.api(API + '/custs'), hb.codeNames('APQP_CP_PHASE')]);
    }).then(function (r) {
        names = {PLM_PPAP_STATUS: r[0], PLM_PPAP_ELEM: r[1], PLM_PPAP_REQ: r[2], PLM_PPAP_ELEM_STATUS: r[3], PLM_PCN_STATUS: r[4],
            APQP_CP_PHASE: r[6]};
        custs = r[5].data;
    });

    plm.ecoExtras = {
        /** 지시를 열거나 비울 때 — d 가 null 이면 모두 숨김 */
        show: function (d, reloadFn) {
            cur = d;
            reload = reloadFn;
            if (!d) {
                ['replaceSection', 'ppapSection', 'pcnSection'].forEach(function (x) { show_($(x), false); });
                return;
            }
            if (preview && preview.ecoId !== d.eco.ecoId) { preview = null; }
            ready.then(function () {
                if (cur !== d) { return; }
                renderReplace();
                loadPpap();
                loadPcns();
            });
        }
    };
})();
