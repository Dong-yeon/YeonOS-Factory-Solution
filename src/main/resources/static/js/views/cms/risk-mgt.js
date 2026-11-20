/*
 * 위험성평가 (cms-risk) — CMS 1부 C6 (docs/33 4.7).
 * 표(평가) → 상세: 머리 폼 + 유해위험요인(작성 중 = 추가·수정·삭제, 확정 뒤 = 개선 진행) + 참여·주지 + 확정 요청(결재 또는 바로).
 * 위험성 수준은 서버가 정한다(RiskMatrix) — 화면 미리보기는 같은 구간표로 안내만.
 */
(function () {
    'use strict';

    var API = '/api/cms/risks';
    var ACTIONS = (document.body.getAttribute('data-actions') || '').split(',');
    var CAN_SAVE = ACTIONS.indexOf('SAVE') >= 0;
    var CAN_DELETE = ACTIONS.indexOf('DELETE') >= 0;
    var CAN_EXCEL = ACTIONS.indexOf('EXCEL') >= 0;
    var msg = document.getElementById('msg');
    var form = document.getElementById('raForm');
    var itemForm = document.getElementById('itemForm');
    var progressForm = document.getElementById('progressForm');
    var memberForm = document.getElementById('memberForm');
    var search = document.getElementById('search');
    var current = null, currentItem = null, sp, grid, quiet = false, sels = {}, users = [];
    var names = {type: {}, status: {}, level: {}, role: {}, cat: {}};

    function notify(text, error) {
        msg.textContent = text || '';
        msg.classList.toggle('hb-msg-error', !!error);
    }

    function f(root, n) { return root.querySelector('[data-field="' + n + '"]'); }

    function option(sel, value, text) {
        var o = hb.el('option', null, text);
        o.value = value;
        sel.appendChild(o);
    }

    function toggle(id, on) {
        var b = document.getElementById(id);
        if (b) { b.hidden = !on; }
    }

    /** RiskMatrix 와 같은 구간(안내용) */
    function previewLevel(l, s, matrix) {
        var score = l * s;
        if (matrix === '5X5') { return score >= 15 ? 'HIGH' : score >= 5 ? 'MEDIUM' : 'LOW'; }
        return score >= 6 ? 'HIGH' : score >= 3 ? 'MEDIUM' : 'LOW';
    }

    function fillScale(sel, n) {
        sel.textContent = '';
        option(sel, '', hb.t('js.common.choose'));
        for (var i = 1; i <= n; i++) { option(sel, String(i), String(i)); }
    }

    function showMethod(root, method) {
        root.querySelectorAll('[data-method]').forEach(function (el) { el.hidden = el.getAttribute('data-method') !== method; });
    }

    function problemText(code) {
        if (code === 'RA_NO_ITEMS') { return hb.t('js.cms.risk.problem.RA_NO_ITEMS'); }
        if (code === 'RA_PLAN_REQUIRED') { return hb.t('js.cms.risk.problem.RA_PLAN_REQUIRED'); }
        return code === 'RA_NO_WORKER' ? hb.t('js.cms.risk.problem.RA_NO_WORKER') : code;
    }

    function levelText(it) {
        var lv = names.level[it.riskLevel] || it.riskLevel;
        return it.likelihood ? it.likelihood + '×' + it.severity + ' ' + lv : lv;
    }

    function show(r) {
        current = r;
        currentItem = null;
        var st = r ? r.status : null;
        var draft = !r || st === 'DRAFT';
        hb.showFieldErrors(form, []);
        ['title', 'scopeText', 'assessDate', 'remark'].forEach(function (n) { f(form, n).value = r && r[n] ? r[n] : ''; });
        sels.raType.setValue(r ? r.raType : 'REGULAR');
        sels.method.setValue(r ? r.method : 'FREQ_SEV');
        f(form, 'plantCd').value = r && r.plantCd ? r.plantCd : '';
        if (!r) { f(form, 'assessDate').value = hb.dates.todayIn(hb.session().timeZone); }
        form.querySelectorAll('input, select').forEach(function (i) { i.disabled = !CAN_SAVE || !draft; });
        f(form, 'method').disabled = !!r || !CAN_SAVE;
        toggle('saveBtn', CAN_SAVE && draft);
        toggle('copyBtn', CAN_SAVE && st === 'RELEASED');
        toggle('obsoleteBtn', CAN_DELETE && (st === 'DRAFT' || st === 'RELEASED'));
        toggle('excelBtn', CAN_EXCEL && !!r);
        document.getElementById('formMode').textContent = r
            ? hb.t('js.cms.risk.editing', r.raNo, names.status[st] || st) : hb.t('js.cms.risk.creating');
        document.getElementById('stateLine').textContent = r ? [
            hb.t('js.cms.risk.matrix', r.matrixCd),
            r.prevRaNo ? hb.t('js.cms.risk.prev', r.prevRaNo) : '',
            r.releasedAt ? hb.t('js.cms.risk.released', String(r.releasedAt).substring(0, 16).replace('T', ' '), r.releasedBy) : '',
            r.overdueCnt ? hb.t('js.cms.risk.overdue', r.overdueCnt) : ''].filter(Boolean).join(' · ') : '';
        ['itemSection', 'memberSection'].forEach(function (id) { document.getElementById(id).hidden = !r; });
        document.getElementById('releaseSection').hidden = !r || !CAN_SAVE || st !== 'DRAFT';
        document.getElementById('approverBox').hidden = !r || !r.approvalRequired;
        if (r) {
            var n = r.matrixCd === '5X5' ? 5 : 3;
            [document.getElementById('lSel'), document.getElementById('sSel'), document.getElementById('rlSel'), document.getElementById('rsSel')]
                .forEach(function (s) { fillScale(s, n); });
            showMethod(itemForm, r.method);
            showMethod(progressForm, r.method);
            renderItems(r);
            renderMembers(r);
            document.getElementById('problems').textContent = (r.problems || []).map(function (p) {
                return (p.seq ? '#' + p.seq + ' ' : '') + problemText(p.code);
            }).join(' · ');
            document.getElementById('itemBox').hidden = !CAN_SAVE || !draft;
            document.getElementById('memberBox').hidden = !CAN_SAVE || !draft;
            pickItem(null);
        }
        hb.dirty.clear();
    }

    function renderItems(r) {
        var body = document.querySelector('#items tbody');
        body.textContent = '';
        if (!r.items.length) {
            var tr0 = hb.el('tr');
            var td0 = hb.el('td', 'hb-muted', hb.t('js.cms.risk.noItems'));
            td0.colSpan = 5;
            tr0.appendChild(td0);
            body.appendChild(tr0);
        }
        r.items.forEach(function (it) {
            var tr = hb.el('tr', 'hb-row-pick');
            tr.tabIndex = 0;
            cmsUi.cells(tr, [String(it.seq), it.taskText + ' — ' + it.hazardText,
                cmsUi.badge('risk', it.riskLevel, levelText(it)),
                it.planMeasure ? cmsUi.withBadge(it.planMeasure + ' (' + (it.ownerNm || '-') + ', ' + (it.dueDate || '-') + ')',
                    it.overdue ? cmsUi.overdue() : null) : '',
                it.doneDate ? it.doneDate + (it.residualLevel ? ' → ' + (names.level[it.residualLevel] || it.residualLevel) : '') : '']);
            tr.addEventListener('click', function () { pickItem(it); });
            tr.addEventListener('keydown', function (e) { if (e.key === 'Enter') { pickItem(it); } });
            body.appendChild(tr);
        });
    }

    function pickItem(it) {
        currentItem = it;
        var r = current;
        hb.showFieldErrors(itemForm, []);
        hb.showFieldErrors(progressForm, []);
        if (r.status === 'DRAFT') {
            var nextSeq = r.items.reduce(function (m, x) { return Math.max(m, x.seq); }, 0) + 1;
            f(itemForm, 'seq').value = it ? String(it.seq) : String(nextSeq);
            ['taskText', 'hazardText', 'currentMeasure', 'equipCd', 'planMeasure', 'dueDate'].forEach(function (n) {
                f(itemForm, n).value = it && it[n] ? it[n] : '';
            });
            sels.hazardCat.setValue(it ? it.hazardCat : '');
            f(itemForm, 'likelihood').value = it && it.likelihood ? String(it.likelihood) : '';
            f(itemForm, 'severity').value = it && it.severity ? String(it.severity) : '';
            sels.riskLevel.setValue(it && !it.likelihood ? it.riskLevel : '');
            f(itemForm, 'ownerLoginId').value = it && it.ownerLoginId ? it.ownerLoginId : '';
            document.getElementById('itemMode').textContent = it ? hb.t('js.cms.risk.itemEditing', it.seq) : hb.t('js.cms.risk.itemNew');
            toggle('deleteItemBtn', !!it);
            paintLevel();
        }
        var prog = r.status === 'RELEASED' && CAN_SAVE && !!it;
        document.getElementById('progressBox').hidden = !prog;
        if (prog) {
            document.getElementById('progressMode').textContent = hb.t('js.cms.risk.progressOf', it.seq, it.hazardText);
            f(progressForm, 'doneDate').value = it.doneDate || '';
            f(progressForm, 'residualL').value = it.residualL ? String(it.residualL) : '';
            f(progressForm, 'residualS').value = it.residualS ? String(it.residualS) : '';
            sels.residualLevel.setValue(it.residualL ? '' : (it.residualLevel || ''));
        }
        loadEvidence(it);
    }

    /** 개선 증빙(C12) — 요인마다 공용 첨부(대상 cms-risk × 요인 id). 폐기된 평가는 서버가 잠근다(RiskFileLock) */
    function loadEvidence(it) {
        var box = document.getElementById('evidenceBox');
        box.hidden = !it;
        if (!it) { return; }
        document.getElementById('evidenceMode').textContent = hb.t('js.cms.risk.evidenceOf', it.seq);
        var ul = document.getElementById('evidence');
        var editable = CAN_SAVE && current.status !== 'OBSOLETE';
        var input = document.getElementById('evidenceInput');
        if (input) { input.hidden = !editable; }
        hb.latest('evidence', hb.api('/api/platform/files?targetType=cms-risk&targetId=' + it.itemId)).then(function (res) {
            ul.textContent = '';
            var list = res.list || [];
            if (!list.length) { ul.appendChild(hb.el('li', 'hb-muted', hb.t('js.cms.risk.noEvidence'))); }
            list.forEach(function (fl) {
                var li = hb.el('li');
                var a = hb.el('a', null, fl.name);
                a.href = '/api/platform/files/' + fl.fileId;
                li.appendChild(a);
                if (editable) {
                    var b = hb.el('button', 'hb-btn-secondary', hb.t('js.cms.delete'));
                    b.type = 'button';
                    b.addEventListener('click', function () {
                        cmsUi.ask('js.cms.dlg.fileDelete', hb.t('js.cms.mwo.confirmFileDelete', fl.name), 'js.cms.delete', true).then(function (yes) {
                            if (!yes) { return; }
                            hb.api('/api/platform/files/' + fl.fileId + '/delete', {method: 'POST'})
                                .then(function () { loadEvidence(currentItem); }, function (err) { notify(err.message, true); });
                        });
                    });
                    li.appendChild(b);
                }
                ul.appendChild(li);
            });
        }, function (err) { if (err.code !== 'STALE') { notify(err.message, true); } });
    }

    var evidenceInput = document.getElementById('evidenceInput');
    if (evidenceInput) {
        evidenceInput.addEventListener('change', function () {
            if (!evidenceInput.files.length || !currentItem) { return; }
            var fd = new FormData();
            fd.append('targetType', 'cms-risk');
            fd.append('targetId', String(currentItem.itemId));
            fd.append('file', evidenceInput.files[0]);
            hb.upload('/api/platform/files', fd).then(function () {
                evidenceInput.value = '';
                notify(hb.t('js.common.saved'));
                loadEvidence(currentItem);
            }, function (err) {
                evidenceInput.value = '';
                notify(err.message + (err.details && err.details.length ? ' ' + err.details.join(' ') : ''), true);
            });
        });
    }

    function paintLevel() {
        var p = document.getElementById('levelPreview');
        var l = Number(f(itemForm, 'likelihood').value);
        var s = Number(f(itemForm, 'severity').value);
        p.textContent = current && current.method === 'FREQ_SEV' && l && s
            ? hb.t('js.cms.risk.levelPreview', l * s, names.level[previewLevel(l, s, current.matrixCd)]) : '';
    }

    function renderMembers(r) {
        var body = document.querySelector('#members tbody');
        body.textContent = '';
        if (!r.members.length) {
            var tr0 = hb.el('tr');
            var td0 = hb.el('td', 'hb-muted', hb.t('js.cms.risk.noMembers'));
            td0.colSpan = 5;
            tr0.appendChild(td0);
            body.appendChild(tr0);
        }
        r.members.forEach(function (m) {
            var tr = hb.el('tr');
            [m.memberNm + (m.loginId ? ' (' + m.loginId + ')' : m.workerCd ? ' (' + hb.t('js.cms.risk.workerMark', m.workerCd) + ')' : ''), names.role[m.roleCd] || m.roleCd, m.participatedOn || '', m.notifiedOn || '']
                .forEach(function (v) { tr.appendChild(hb.el('td', null, v)); });
            var act = hb.el('td');
            if (CAN_SAVE && r.status === 'DRAFT') {
                var del = hb.el('button', 'hb-btn-secondary', hb.t('js.cms.delete'));
                del.type = 'button';
                del.addEventListener('click', function () {
                    hb.busy(del, function () { return hb.api(API + '/' + r.raId + '/members/' + m.memberId + '/delete', {method: 'POST'}); })
                        .then(function (res) { if (res) { return reload(); } }, function (err) { notify(err.message, true); });
                });
                act.appendChild(del);
            }
            if (CAN_SAVE && (r.status === 'DRAFT' || r.status === 'RELEASED') && !m.notifiedOn) {
                var nb = hb.el('button', 'hb-btn-ghost', hb.t('js.cms.risk.notifyToday'));
                nb.type = 'button';
                nb.addEventListener('click', function () {
                    hb.busy(nb, function () {
                        return hb.api(API + '/' + r.raId + '/members/' + m.memberId + '/notify', {method: 'PUT',
                            body: {notifiedOn: hb.dates.todayIn(hb.session().timeZone), rowVer: m.rowVer}});
                    }).then(function (res) { if (res) { return reload(); } }, function (err) { notify(err.message, true); });
                });
                act.appendChild(nb);
            }
            tr.appendChild(act);
            body.appendChild(tr);
        });
    }

    function reload() {
        return open(current.raId).then(function () { return load(current.raId, true); });
    }

    function open(id) {
        return hb.latest('detail', hb.api(API + '/' + id)).then(function (res) { show(res.data); }, function (err) { notify(err.message, true); });
    }

    /** 조회 조건(검색 패널) — grid 가 page·size·sort 를 붙인다 */
    function params() {
        var v = sp.values();
        var q = {};
        ['from', 'to', 'status', 'keyword'].forEach(function (k) { if (v[k]) { q[k] = v[k]; } });
        return q;
    }

    /** 목록 다시 읽기 — selectId 행을 고른 채로. keepDetail 이면 상세는 그대로(방금 다시 읽음), 아니면 그 평가를 연다 */
    function load(selectId, keepDetail) {
        quiet = !!keepDetail;   // grid 가 고른 행을 onSelect 로 다시 열지 않게
        return grid.reload(!keepDetail, selectId || undefined).then(function () {
            quiet = false;
            if (!selectId) { show(null); return null; }
            var sel = grid.selected();
            if (keepDetail || (sel && sel.raId === selectId)) { return null; }
            return open(selectId);   // 다른 쪽·기간 밖이어도 방금 만든 것은 상세로
        });
    }

    function headBody() {
        return {raType: f(form, 'raType').value, method: f(form, 'method').value, title: f(form, 'title').value,
            scopeText: f(form, 'scopeText').value, plantCd: f(form, 'plantCd').value, assessDate: f(form, 'assessDate').value,
            remark: f(form, 'remark').value, rowVer: current ? current.rowVer : null};
    }

    function num(v) { return v === '' ? null : Number(v); }

    function act(btn, call, done) {
        return hb.busy(btn, call).then(function (res) {
            if (!res) { return; }
            return done(res);
        }, function (err) {
            notify(err.message, true);
            return err;
        });
    }

    ['newBtn', 'newBtnList'].forEach(function (id) {
        var b = document.getElementById(id);
        if (b) {
            b.addEventListener('click', function () {
                cmsUi.leaveOk().then(function (ok) {
                    if (!ok) { return; }
                    show(null);
                    hb.page.openDetail();
                    f(form, 'title').focus();
                });
            });
        }
    });

    var saveBtn = document.getElementById('saveBtn');
    if (saveBtn) {
        saveBtn.addEventListener('click', function () {
            var editing = !!current;
            hb.showFieldErrors(form, []);
            act(saveBtn, function () {
                return editing ? hb.api(API + '/' + current.raId, {method: 'PUT', body: headBody()}) : hb.api(API, {method: 'POST', body: headBody()});
            }, function (res) {
                var id = editing ? current.raId : res.data;
                return load(id).then(function () { notify(hb.t('js.common.saved')); });
            }).then(function (err) { if (err && err.errors) { hb.showFieldErrors(form, err.errors); } });
        });
    }

    var saveItemBtn = document.getElementById('saveItemBtn');
    saveItemBtn.addEventListener('click', function () {
        var r = current;
        hb.showFieldErrors(itemForm, []);
        var b = {itemId: currentItem ? currentItem.itemId : null, rowVer: currentItem ? currentItem.rowVer : null,
            seq: num(f(itemForm, 'seq').value), taskText: f(itemForm, 'taskText').value, equipCd: f(itemForm, 'equipCd').value,
            hazardCat: f(itemForm, 'hazardCat').value, hazardText: f(itemForm, 'hazardText').value,
            currentMeasure: f(itemForm, 'currentMeasure').value, likelihood: num(f(itemForm, 'likelihood').value),
            severity: num(f(itemForm, 'severity').value), riskLevel: f(itemForm, 'riskLevel').value,
            planMeasure: f(itemForm, 'planMeasure').value, ownerLoginId: f(itemForm, 'ownerLoginId').value, dueDate: f(itemForm, 'dueDate').value};
        act(saveItemBtn, function () { return hb.api(API + '/' + r.raId + '/items', {method: 'POST', body: b}); },
            function () { return reload().then(function () { notify(hb.t('js.common.saved')); }); })
            .then(function (err) { if (err && err.errors) { hb.showFieldErrors(itemForm, err.errors); } });
    });
    document.getElementById('newItemBtn').addEventListener('click', function () { pickItem(null); });
    var deleteItemBtn = document.getElementById('deleteItemBtn');
    deleteItemBtn.addEventListener('click', function () {
        if (!currentItem) { return; }
        var it = currentItem;
        cmsUi.ask('js.cms.dlg.itemDelete', hb.t('js.cms.risk.itemDeleteConfirm', it.seq), 'js.cms.delete', true).then(function (yes) {
            if (!yes) { return; }
            act(deleteItemBtn, function () { return hb.api(API + '/' + current.raId + '/items/' + it.itemId + '/delete', {method: 'POST'}); },
                function () { return reload(); });
        });
    });
    ['likelihood', 'severity'].forEach(function (n) { f(itemForm, n).addEventListener('change', paintLevel); });

    var progressBtn = document.getElementById('progressBtn');
    progressBtn.addEventListener('click', function () {
        hb.showFieldErrors(progressForm, []);
        var b = {doneDate: f(progressForm, 'doneDate').value, residualL: num(f(progressForm, 'residualL').value),
            residualS: num(f(progressForm, 'residualS').value), residualLevel: f(progressForm, 'residualLevel').value, rowVer: currentItem.rowVer};
        act(progressBtn, function () {
            return hb.api(API + '/' + current.raId + '/items/' + currentItem.itemId + '/progress', {method: 'PUT', body: b});
        }, function () { return reload().then(function () { notify(hb.t('js.common.saved')); }); })
            .then(function (err) { if (err && err.errors) { hb.showFieldErrors(progressForm, err.errors); } });
    });

    var addMemberBtn = document.getElementById('addMemberBtn');
    addMemberBtn.addEventListener('click', function () {
        hb.showFieldErrors(memberForm, []);
        var b = {loginId: f(memberForm, 'loginId').value, workerCd: f(memberForm, 'workerCd').value, memberNm: f(memberForm, 'memberNm').value,
            roleCd: f(memberForm, 'roleCd').value, participatedOn: f(memberForm, 'participatedOn').value};
        act(addMemberBtn, function () { return hb.api(API + '/' + current.raId + '/members', {method: 'POST', body: b}); },
            function () {
                memberForm.reset();
                return reload().then(function () { notify(hb.t('js.common.saved')); });
            }).then(function (err) { if (err && err.errors) { hb.showFieldErrors(memberForm, err.errors); } });
    });

    var releaseBtn = document.getElementById('releaseBtn');
    releaseBtn.addEventListener('click', function () {
        var r = current;
        var approver = document.getElementById('approverSel').value;
        var b = {rowVer: r.rowVer, lines: r.approvalRequired && approver ? [{userId: Number(approver), lineType: 'APPROVE'}] : []};
        act(releaseBtn, function () { return hb.api(API + '/' + r.raId + '/release', {method: 'POST', body: b}); }, function (res) {
            return reload().then(function () { notify(res.data ? hb.t('js.cms.risk.submitted', res.data) : hb.t('js.cms.risk.releasedMsg')); });
        }).then(function (err) {
            if (err && err.errors) {
                document.getElementById('problems').textContent = err.errors.map(function (e) {
                    return (e.rowKey ? '#' + e.rowKey + ' ' : '') + e.message;
                }).join(' · ');
            }
        });
    });

    var copyBtn = document.getElementById('copyBtn');
    if (copyBtn) {
        copyBtn.addEventListener('click', function () {
            act(copyBtn, function () { return hb.api(API + '/' + current.raId + '/copy', {method: 'POST', body: {raType: 'REGULAR'}}); },
                function (res) { return load(res.data).then(function () { notify(hb.t('js.cms.risk.copied')); }); });
        });
    }
    var obsoleteBtn = document.getElementById('obsoleteBtn');
    if (obsoleteBtn) {
        obsoleteBtn.addEventListener('click', function () {
            cmsUi.ask('js.cms.dlg.obsolete', hb.t('js.cms.risk.obsoleteConfirm', current.raNo), 'js.cms.dlg.obsoleteOk', true).then(function (yes) {
                if (!yes) { return; }
                act(obsoleteBtn, function () { return hb.api(API + '/' + current.raId + '/obsolete', {method: 'POST', body: {rowVer: current.rowVer}}); },
                    function () { return reload(); });
            });
        });
    }
    var excelBtn = document.getElementById('excelBtn');
    if (excelBtn) {
        excelBtn.addEventListener('click', function () {
            hb.busy(excelBtn, function () { return hb.download(API + '/' + current.raId + '/export', {}); })
                .then(null, function (err) { notify(err.message, true); });
        });
    }

    hb.dirty.watch(form);
    hb.loadMessages().then(function () {
        hb.page.init();
        // 빈 칸 문구(data-blank)가 문구 로드 뒤에 채워지도록 여기서 만든다 — 앞에서 만들면 코드 목록이 먼저 오면 키가 그대로 보인다
        sels.raType = hb.select(f(form, 'raType'));
        sels.method = hb.select(f(form, 'method'));
        sels.hazardCat = hb.select(f(itemForm, 'hazardCat'));
        sels.riskLevel = hb.select(f(itemForm, 'riskLevel'));
        sels.residualLevel = hb.select(f(progressForm, 'residualLevel'));
        sels.roleCd = hb.select(f(memberForm, 'roleCd'));
        return Promise.all([hb.loadSession(), hb.api(API + '/options'), hb.codeList('CMS_RA_TYPE'), hb.codeList('CMS_RA_STATUS'),
            hb.codeList('CMS_RISK_LEVEL'), hb.codeList('CMS_RA_ROLE'), sels.raType.ready, sels.method.ready, sels.hazardCat.ready,
            sels.riskLevel.ready, sels.residualLevel.ready, sels.roleCd.ready]);
    }).then(function (r) {
        r[2].forEach(function (c) { names.type[c.code] = c.name; });
        r[3].forEach(function (c) { names.status[c.code] = c.name; });
        r[4].forEach(function (c) { names.level[c.code] = c.name; });
        r[5].forEach(function (c) { names.role[c.code] = c.name; });
        var o = r[1].data;
        users = o.users;
        var ps = f(form, 'plantCd');
        option(ps, '', hb.t('js.cms.allPlants'));
        o.plants.forEach(function (p) { option(ps, p.code, p.code + ' · ' + p.name); });
        [document.getElementById('ownerSel'), document.getElementById('memberUserSel')].forEach(function (s) {
            option(s, '', hb.t('js.common.choose'));
            users.forEach(function (u) { option(s, u.loginId, u.userNm + ' (' + u.loginId + ')'); });
        });
        // 현장 작업자(C19) — 사용자와 둘 중 하나. 기준정보 작업자(사용 중)
        var ws = document.getElementById('memberWorkerSel');
        option(ws, '', hb.t('js.common.choose'));
        (o.workers || []).forEach(function (w) { option(ws, w.workerCd, w.workerNm + ' (' + w.workerCd + ')'); });
        var ap = document.getElementById('approverSel');
        option(ap, '', hb.t('js.common.choose'));
        users.forEach(function (u) { option(ap, String(u.userId), u.userNm + ' (' + u.loginId + ')'); });
        var today = hb.dates.todayIn(hb.session().timeZone);
        document.getElementById('toDate').value = today;
        document.getElementById('fromDate').value = today.substring(0, 4) + '-01-01';
        sp = hb.searchPanel(search, {onSearch: function () {
            return cmsUi.leaveOk().then(function (ok) { return ok ? load() : null; });
        }});
        grid = hb.grid('#raGrid', {
            progCd: 'cms-risk', id: 'main', key: 'raId',
            url: API + '/grid', columnsUrl: API + '/columns', exportUrl: API + '/export', actions: ACTIONS, params: params,
            filters: {status: 'status'},
            onFilter: function (param, value) { sp.setValue(param, value); },
            beforeSelect: function () { return cmsUi.leaveOk(); },
            status: {
                status: cmsUi.TONES.ra,
                highCnt: function (v) { return v > 0 ? 'danger' : null; },       // 높음 요인 있음
                overdueCnt: function (v) { return v > 0 ? 'danger' : null; }     // 기한 지난 개선 조치 = 지연
            },
            rowClass: function (row) { return row.status === 'OBSOLETE' ? 'hb-tree-dim' : null; },
            onSelect: function (row) {
                if (quiet) { return; }
                open(row.raId).then(function () { hb.page.openDetail(); });
            },
            notify: notify
        });
        return grid.ready.then(function () { show(null); });   // grid 가 첫 쪽을 이미 읽었다 — 다시 읽지 않는다
    }).then(null, function (err) { notify(err && err.message, true); });
})();
