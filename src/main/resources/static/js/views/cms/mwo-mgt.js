/*
 * 보전 작업지시 (cms-mwo) — CMS 1부 C1 (docs/33 4.1, D2).
 * 조회(기간 필수) → 표 → 상세 폼 + 상태 버튼(전이 표대로만 보임) + 공수 + 첨부.
 * 완료 버튼은 폼 내용을 함께 보낸다 — 닫을 때 필수(현상·원인·조치·작업 내용)는 서버가 칸 오류로 돌려준다.
 */
(function () {
    'use strict';

    var API = '/api/cms/mwos';
    var PROG = 'cms-mwo';
    var ACTIONS = (document.body.getAttribute('data-actions') || '').split(',');
    var CAN_SAVE = ACTIONS.indexOf('SAVE') >= 0;
    var msg = document.getElementById('msg');
    var form = document.getElementById('mwoForm');
    var search = document.getElementById('search');
    var formMode = document.getElementById('formMode');
    var stateLine = document.getElementById('stateLine');
    var laborForm = document.getElementById('laborForm');
    var current = null, sp, grid, minutesNum, codes = [], targetHits = {}, targetClassId = null;
    var names = {type: {}, status: {}};
    var EDITABLE = ['REQ', 'OPEN', 'WIP'];

    function notify(text, error) {
        msg.textContent = text || '';
        msg.classList.toggle('hb-msg-error', !!error);
    }

    function field(n) { return form.querySelector('[data-field="' + n + '"]'); }

    function option(sel, value, text) {
        var o = hb.el('option', null, text);
        o.value = value;
        sel.appendChild(o);
    }

    /** 우선순위 문구 — 키는 글자 그대로(I18nKeyGuardTest) */
    function prio(p) {
        return {1: hb.t('js.cms.prio.1'), 2: hb.t('js.cms.prio.2'), 3: hb.t('js.cms.prio.3')}[p] || String(p);
    }

    function local(t) { return t ? String(t).substring(0, 16) : ''; }
    function time(t) { return t ? local(t).replace('T', ' ') : ''; }

    /** 고장 코드 선택지 — 그 유형, 설비 클래스 한정 코드는 그 클래스 대상만. 지금 값이 목록 밖(사용 중지)이면 덧붙여 보여 준다 */
    function fillCodes(type, sel, keep, keepNm) {
        sel.textContent = '';
        option(sel, '', hb.t('js.common.choose'));
        codes.filter(function (c) {
            return c.codeType === type && (!c.equipClassId || c.equipClassId === targetClassId);
        }).forEach(function (c) { option(sel, c.failCd, c.failCd + ' · ' + c.failNm); });
        sel.value = keep || '';
        if (keep && sel.value !== keep) {
            option(sel, keep, keep + ' · ' + (keepNm || '') + ' — ' + hb.t('js.cms.inactive'));
            sel.value = keep;
        }
    }

    function refillCodes(m) {
        fillCodes('SYMPTOM', field('symptomCd'), m ? m.symptomCd : field('symptomCd').value, m && m.symptomNm);
        fillCodes('CAUSE', field('causeCd'), m ? m.causeCd : field('causeCd').value, m && m.causeNm);
        fillCodes('ACTION', field('actionCd'), m ? m.actionCd : field('actionCd').value, m && m.actionNm);
        ISO.forEach(function (x) { fillIso(field(x[0]), isoLists[x[1]] || [], m ? m[x[0]] : field(x[0]).value); });
    }

    /** ISO 14224 칸(G3) — 공통코드 사용 중 목록. 지금 값이 목록 밖(사용 중지)이면 덧붙여 보여 준다 */
    var ISO = [['failMechCd', 'CMS_FAIL_MECH'], ['failDetectCd', 'CMS_FAIL_DETECT'], ['failImpactCd', 'CMS_FAIL_IMPACT']];
    var isoLists = {};

    function fillIso(sel, list, keep) {
        sel.textContent = '';
        option(sel, '', hb.t('js.common.choose'));
        list.forEach(function (c) { option(sel, c.code, c.name); });
        sel.value = keep || '';
        if (keep && sel.value !== keep) {
            option(sel, keep, keep + ' — ' + hb.t('js.cms.inactive'));
            sel.value = keep;
        }
    }

    function setUser(sel, login, nm) {
        sel.querySelectorAll('option[data-stale]').forEach(function (o) { o.remove(); });
        sel.value = login || '';
        if (login && sel.value !== login) {
            option(sel, login, (nm || login) + ' — ' + hb.t('js.cms.inactive'));
            sel.lastChild.setAttribute('data-stale', '');
            sel.value = login;
        }
    }

    function show(m) {
        current = m;
        hb.showFieldErrors(form, []);
        targetClassId = m ? m.targetClassId || null : null;
        field('mwoType').value = m ? m.mwoType : 'BM';
        field('targetType').value = m ? m.targetType : 'EQUIP';
        field('targetCd').value = m ? m.targetCd : '';
        ['title', 'symptomText', 'workText'].forEach(function (n) { field(n).value = m && m[n] ? m[n] : ''; });
        field('priority').value = m ? String(m.priority) : '2';
        ['reportedAt', 'downStartAt', 'downEndAt', 'startedAt', 'finishedAt'].forEach(function (n) {
            field(n).value = m ? local(m[n]) : '';
        });
        field('dueDate').value = m && m.dueDate ? m.dueDate : '';
        field('downYn').value = m ? m.downYn : 'N';
        setUser(field('assigneeLoginId'), m && m.assigneeLoginId, m && m.assigneeNm);
        refillCodes(m);
        var st = m ? m.status : null;
        var editable = !m || EDITABLE.indexOf(st) >= 0;
        form.querySelectorAll('input, select, textarea').forEach(function (i) { i.disabled = !CAN_SAVE || !editable; });
        ['mwoType', 'targetType', 'targetCd'].forEach(function (n) { field(n).disabled = !CAN_SAVE || !!m; });
        field('startedAt').disabled = !CAN_SAVE || st !== 'WIP';
        field('finishedAt').disabled = !CAN_SAVE || st !== 'WIP';
        // ISO 14224 칸은 고장 수리(BM)만 — 다른 유형은 서버가 BM_ONLY 로 막는다
        ISO.forEach(function (x) { if (field('mwoType').value !== 'BM') { field(x[0]).value = ''; field(x[0]).disabled = true; } });
        toggle('saveBtn', editable);
        toggle('acceptBtn', st === 'REQ');
        toggle('startBtn', st === 'OPEN');
        toggle('finishBtn', st === 'WIP');
        toggle('closeBtn', st === 'DONE');
        toggle('cancelBtn', st === 'REQ' || st === 'OPEN');
        formMode.textContent = m ? hb.t('js.cms.mwo.editing', m.mwoNo, names.status[st] || st) : hb.t('js.cms.mwo.creating');
        stateLine.textContent = m ? stateText(m) : '';
        document.getElementById('laborSection').hidden = !m;
        document.getElementById('partSection').hidden = !m;
        var partBox = document.getElementById('partBox');
        if (partBox) { partBox.hidden = !m || (st !== 'OPEN' && st !== 'WIP'); }
        document.getElementById('fileSection').hidden = !m;
        var laborBox = document.getElementById('laborBox');   // .hb-form 은 display:grid — 감싼 div 로 숨긴다
        if (laborBox) { laborBox.hidden = !m || (st !== 'OPEN' && st !== 'WIP'); }
        document.getElementById('runSection').hidden = !m || m.mwoType !== 'PM';
        document.getElementById('downSection').hidden = !m || m.mwoType !== 'BM';
        currentRun = null;
        if (m) {
            loadLabor();
            loadParts();
            loadDowntime();
            loadFiles();
            if (m.mwoType === 'PM') { loadRun(); }
        }
        hb.dirty.clear();
    }

    /** 예방점검(PM) 작업지시의 점검 결과 — 완료 버튼이 이 결과의 필수 항목을 함께 검사한다(서버) */
    var currentRun = null;
    function loadRun() {
        var id = current.mwoId;
        var body = document.querySelector('#runResults tbody');
        return hb.latest('run', hb.api(API + '/' + id + '/run')).then(function (res) {
            currentRun = res.data;
            var editable = CAN_SAVE && currentRun && currentRun.status === 'OPEN' && EDITABLE.indexOf(current.status) >= 0;
            document.getElementById('runLine').textContent = currentRun
                ? hb.t('js.cms.mwo.runLine', currentRun.planCd, currentRun.dueDate, currentRun.result || '') : '';
            var b = document.getElementById('runSaveBtn');
            if (b) { b.hidden = !editable; }
            cmsResults.render(body, currentRun, {editable: editable, canFollowUp: CAN_SAVE, onFollowUp: function (seq, btn) {
                hb.busy(btn, function () {
                    return hb.api(API + '/' + id + '/run/follow-up', {method: 'POST', body: {seq: seq}});
                }).then(function (r) {
                    if (r) { notify(hb.t('js.cms.mwo.created', r.data.mwoNo)); }
                }, function (err) { notify(err.message, true); });
            }});
        }, function (err) { notify(err.message, true); });
    }

    var runSaveBtn = document.getElementById('runSaveBtn');
    if (runSaveBtn) {
        runSaveBtn.addEventListener('click', function () {
            var body = document.querySelector('#runResults tbody');
            var rows = cmsResults.collect(body);
            if (!rows.length || !currentRun) { notify(hb.t('js.cms.run.nothing')); return; }
            hb.busy(runSaveBtn, function () {
                return hb.api(API + '/' + current.mwoId + '/run/results', {method: 'PUT', body: {rowVer: currentRun.rowVer, results: rows}});
            }).then(function (res) {
                if (!res) { return; }
                notify(hb.t('js.common.saved'));
                return loadRun();
            }, function (err) {
                notify(err.message, true);
                cmsResults.showErrors(body, err.errors);
            });
        });
    }

    function stateText(m) {
        // POP 신고(C20) = 작업자(단말), 사무 계정 신고 = 사용자, 둘 다 없으면 정기 작업
        var who = m.reportedWorkerNm ? hb.t('js.cms.mwo.popReporter', m.reportedWorkerNm, m.reportedTerminalCd || '')
            : m.requestedNm || m.requestedLoginId || hb.t('js.cms.mwo.system');
        var parts = [hb.t('js.cms.mwo.requested', who, time(m.reportedAt)),
            hb.t('js.cms.mwo.zone', m.timeZone)];
        if (m.parentMwoNo) { parts.push(hb.t('js.cms.mwo.parent', m.parentMwoNo)); }
        if (m.status === 'CANCELLED') { parts.push(hb.t('js.cms.mwo.cancelled', m.cancelReason)); }
        if (m.closedAt) { parts.push(hb.t('js.cms.mwo.closed', m.closedLoginId || '', time(m.closedAt))); }
        if (m.laborMin) { parts.push(hb.t('js.cms.mwo.laborSum', m.laborMin)); }
        return parts.join(' · ');
    }

    function toggle(id, on) {
        var b = document.getElementById(id);
        if (b) { b.hidden = !on; }
    }

    /** 조회 조건(검색 패널) — grid 가 page·size·sort 를 붙인다 */
    function params() {
        var v = sp.values();
        var q = {};
        ['from', 'to', 'status', 'mwoType', 'plantCd', 'keyword', 'scope', 'source'].forEach(function (k) {
            if (v[k]) { q[k] = v[k]; }
        });
        return q;
    }

    /** 행 → 상세(서버에서 다시 읽는다) */
    function openRow(id) {
        return hb.latest('detail', hb.api(API + '/' + id)).then(function (r) {
            show(r.data);
            hb.page.openDetail();
        }, function (err) { notify(err.message, true); });
    }

    /** 목록 다시 읽기 — selectId 가 이 쪽에 있으면 grid 가 골라 상세를 연다, 없어도(기간 밖·다른 쪽) 방금 만든·고친 것은 상세로 */
    function load(selectId) {
        return grid.reload(true, selectId || undefined).then(function () {
            var sel = grid.selected();
            if (selectId && (!sel || sel.mwoId !== selectId)) { return openRow(selectId); }
            if (!selectId) { show(null); }
        });
    }

    function loadLabor() {
        var id = current.mwoId;
        var body = document.querySelector('#labor tbody');
        return hb.latest('labor', hb.api(API + '/' + id + '/labor')).then(function (res) {
            body.textContent = '';
            if (!res.data.length) {
                var tr = hb.el('tr');
                var td = hb.el('td', 'hb-muted', hb.t('js.cms.mwo.noLabor'));
                td.colSpan = 5;
                tr.appendChild(td);
                body.appendChild(tr);
            }
            var open = current.status === 'OPEN' || current.status === 'WIP';
            res.data.forEach(function (l) {
                var tr = hb.el('tr');
                [l.userNm + ' (' + l.loginId + ')', l.workDate, String(l.minutes), l.remark || '']
                    .forEach(function (v) { tr.appendChild(hb.el('td', null, v)); });
                var act = hb.el('td');
                if (CAN_SAVE && open) {
                    var b = hb.el('button', 'hb-btn-secondary', hb.t('js.cms.delete'));
                    b.type = 'button';
                    b.addEventListener('click', function () {
                        hb.busy(b, function () {
                            return hb.api(API + '/' + id + '/labor/' + l.laborId + '/delete', {method: 'POST'});
                        }).then(function (r) { if (r) { return reloadCurrent(); } }, function (err) { notify(err.message, true); });
                    });
                    act.appendChild(b);
                }
                tr.appendChild(act);
                body.appendChild(tr);
            });
        }, function (err) { notify(err.message, true); });
    }

    /** 수집 정지 구간·알람(C11, 조회만) — 비가동 사유 검토에서 연결한 구간과 이 작업지시를 만든 알람. 수기 정지 구간과 대조 */
    function loadDowntime() {
        var sec = document.getElementById('downSection');
        sec.hidden = current.mwoType !== 'BM';
        if (sec.hidden) { return Promise.resolve(); }
        var body = document.querySelector('#downs tbody');
        var ul = document.getElementById('mwoAlarms');
        return hb.latest('downtime', hb.api(API + '/' + current.mwoId + '/downtime')).then(function (res) {
            var d = res.data;
            body.textContent = '';
            ul.textContent = '';
            if (!d.intervals.length) {
                var tr0 = hb.el('tr');
                var td0 = hb.el('td', 'hb-muted', hb.t('js.cms.mwo.noDowns'));
                td0.colSpan = 4;
                tr0.appendChild(td0);
                body.appendChild(tr0);
            }
            d.intervals.forEach(function (iv) {
                var tr = hb.el('tr');
                [time(iv.startAt), iv.endAt ? time(iv.endAt) : hb.t('js.cms.stateReview.open'), iv.minutes == null ? '' : String(iv.minutes),
                    iv.reasonCd ? iv.reasonCd + ' · ' + iv.reasonNm : '']
                    .forEach(function (v, i) { tr.appendChild(hb.el('td', i === 2 ? 'hb-num' : null, v)); });
                body.appendChild(tr);
            });
            d.alarms.forEach(function (a) {
                ul.appendChild(hb.el('li', null, hb.t('js.cms.mwo.fromAlarm', a.alarmCd + (a.alarmText ? ' ' + a.alarmText : ''), time(a.raisedAt))));
            });
            document.getElementById('downSum').textContent = d.collectedMinutes == null ? hb.t('js.cms.mwo.downHint')
                : hb.t('js.cms.mwo.downCollected', d.collectedMinutes);
        }, function (err) { notify(err.message, true); });
    }

    /** 사용 예비품(C4) — 작업지시가 OPEN·WIP 일 때만 추가·삭제. 후보 = 대상 설비·그 클래스의 예비품 연결 */
    function loadParts() {
        var id = current.mwoId;
        var body = document.querySelector('#parts tbody');
        var open = current.status === 'OPEN' || current.status === 'WIP';
        if (open && CAN_SAVE) {
            hb.latest('part-options', hb.api(API + '/' + id + '/part-options')).then(function (res) {
                var dl = document.getElementById('partOptions');
                dl.textContent = '';
                res.data.forEach(function (o) {
                    var opt = hb.el('option', null, o.itemNm + (o.spec ? ' ' + o.spec : '') + ' (' + o.unitCd + ')'
                        + (o.keySpareYn === 'Y' ? ' ' + hb.t('js.cms.spare.keyMark') : ''));
                    opt.value = o.itemCd;
                    dl.appendChild(opt);
                });
            }, function () { /* 후보는 보조 */ });
        }
        return hb.latest('parts', hb.api(API + '/' + id + '/parts')).then(function (res) {
            body.textContent = '';
            if (!res.data.length) {
                var tr0 = hb.el('tr');
                var td0 = hb.el('td', 'hb-muted', hb.t('js.cms.mwo.noParts'));
                td0.colSpan = 5;
                tr0.appendChild(td0);
                body.appendChild(tr0);
            }
            res.data.forEach(function (p) {
                var tr = hb.el('tr');
                [p.itemCd + ' · ' + p.itemNm, p.qtyText + ' ' + p.unitCd]
                    .forEach(function (v) { tr.appendChild(hb.el('td', null, v)); });
                tr.appendChild(returnedCell(p));
                tr.appendChild(hb.el('td', null, p.remark || ''));
                var act = hb.el('td');
                if (CAN_SAVE && open) {
                    var b = hb.el('button', 'hb-btn-secondary', hb.t('js.cms.delete'));
                    b.type = 'button';
                    b.addEventListener('click', function () {
                        hb.busy(b, function () {
                            return hb.api(API + '/' + id + '/parts/' + p.partId + '/delete', {method: 'POST'});
                        }).then(function (r) { if (r) { return loadParts(); } }, function (err) { notify(err.message, true); });
                    });
                    act.appendChild(b);
                }
                tr.appendChild(act);
                body.appendChild(tr);
            });
            fillReturn(res.data);
        }, function (err) { notify(err.message, true); });
    }

    function fileList(ul, targetType, canDelete) {
        return hb.latest('files-' + targetType, hb.api('/api/platform/files?targetType=' + targetType + '&targetId=' + current.mwoId))
            .then(function (res) {
                ul.textContent = '';
                var list = res.list || [];   // 첨부 API 는 목록 응답({list, total})
                if (!list.length) { ul.appendChild(hb.el('li', 'hb-muted', hb.t('js.cms.mwo.noFiles'))); }
                list.forEach(function (f) {
                    var li = hb.el('li');
                    var a = hb.el('a', null, f.name);
                    a.href = '/api/platform/files/' + f.fileId;
                    li.appendChild(a);
                    if (canDelete) {
                        var b = hb.el('button', 'hb-btn-secondary', hb.t('js.cms.delete'));
                        b.type = 'button';
                        b.addEventListener('click', function () {
                            cmsUi.ask('js.cms.dlg.fileDelete', hb.t('js.cms.mwo.confirmFileDelete', f.name), 'js.cms.delete', true).then(function (yes) {
                                if (!yes) { return; }
                                hb.api('/api/platform/files/' + f.fileId + '/delete', {method: 'POST'})
                                    .then(loadFiles, function (err) { notify(err.message, true); });
                            });
                        });
                        li.appendChild(b);
                    }
                    ul.appendChild(li);
                });
            }, function () {
                ul.textContent = '';   // 신고 화면 조회 권한이 없으면 신고 사진은 보이지 않는다
            });
    }

    function loadFiles() {
        // 완료 뒤에는 사진·보고서를 더할 수는 있어도 지우지는 못하게(화면). 취소·확인 뒤에는 서버가 올리기·삭제를 잠근다(MwoFileLock, docs/33 13장)
        var input = document.getElementById('fileInput');
        if (input) { input.hidden = current.status === 'CANCELLED' || current.status === 'CLOSED'; }
        fileList(document.getElementById('files'), PROG, CAN_SAVE && EDITABLE.indexOf(current.status) >= 0);
        fileList(document.getElementById('reportFiles'), 'cms-breakdown', false);
    }

    function reloadCurrent() {
        return load(current ? current.mwoId : null);
    }

    function body() {
        var b = {};
        ['mwoType', 'targetType', 'targetCd', 'title', 'symptomCd', 'symptomText', 'reportedAt', 'assigneeLoginId', 'dueDate',
            'downYn', 'downStartAt', 'downEndAt', 'startedAt', 'finishedAt', 'causeCd', 'actionCd', 'workText', 'failMechCd', 'failDetectCd',
            'failImpactCd']
            .forEach(function (n) { b[n] = field(n).value; });
        b.priority = Number(field('priority').value);
        b.rowVer = current ? current.rowVer : null;
        return b;
    }

    function act(btn, url, payload, confirmKey) {
        return function () {
            (confirmKey ? cmsUi.ask(confirmKey + 'Title', hb.t(confirmKey), confirmKey + 'Ok', false) : Promise.resolve(true)).then(function (yes) {
                if (!yes) { return; }
                hb.showFieldErrors(form, []);
                hb.busy(btn, function () {
                    return hb.api(url(), {method: 'POST', body: payload()});
                }).then(function (res) {
                    if (!res) { return; }
                    return reloadCurrent().then(function () { notify(hb.t('js.common.saved')); });
                }, function (err) {
                    notify(err.message, true);
                    hb.showFieldErrors(form, err.errors);
                    if (currentRun) { cmsResults.showErrors(document.querySelector('#runResults tbody'), err.errors); }   // PM 완료: 빠진 필수 결과(행 = 순번)
                });
            });
        };
    }

    function newMwo() {
        cmsUi.leaveOk().then(function (ok) {
            if (!ok) { return; }
            show(null);
            hb.page.openDetail();
            field('targetCd').focus();
        });
    }
    ['newBtn', 'newBtnList'].forEach(function (id) {
        var b = document.getElementById(id);
        if (b) { b.addEventListener('click', newMwo); }
    });

    var saveBtn = document.getElementById('saveBtn');
    if (saveBtn) {
        saveBtn.addEventListener('click', function () {
            var editing = !!current;
            hb.busy(saveBtn, function () {
                return editing ? hb.api(API + '/' + current.mwoId, {method: 'PUT', body: body()})
                    : hb.api(API, {method: 'POST', body: body()});
            }).then(function (res) {
                if (!res) { return; }
                return load(editing ? current.mwoId : res.data.mwoId).then(function () {
                    notify(editing ? hb.t('js.common.saved') : hb.t('js.cms.mwo.created', res.data.mwoNo));
                });
            }, function (err) {
                notify(err.message, true);
                hb.showFieldErrors(form, err.errors);
            });
        });
    }

    var b;
    if ((b = document.getElementById('acceptBtn'))) {
        b.addEventListener('click', act(b, function () { return API + '/' + current.mwoId + '/accept'; },
            function () { return {rowVer: current.rowVer, assigneeLoginId: field('assigneeLoginId').value}; }));
    }
    if ((b = document.getElementById('startBtn'))) {
        b.addEventListener('click', act(b, function () { return API + '/' + current.mwoId + '/start'; },
            function () { return {rowVer: current.rowVer}; }));
    }
    if ((b = document.getElementById('finishBtn'))) {
        b.addEventListener('click', act(b, function () { return API + '/' + current.mwoId + '/finish'; }, body,
            'js.cms.mwo.confirmFinish'));
    }
    if ((b = document.getElementById('closeBtn'))) {
        b.addEventListener('click', act(b, function () { return API + '/' + current.mwoId + '/close'; },
            function () { return {rowVer: current.rowVer}; }, 'js.cms.mwo.confirmClose'));
    }
    var cancelBtn = document.getElementById('cancelBtn');
    if (cancelBtn) {
        cancelBtn.addEventListener('click', function () {
            cmsUi.reason('js.cms.dlg.mwoCancel', 'js.cms.mwo.cancelReason', 'js.cms.dlg.mwoCancelOk', true).then(function (reason) {
                if (reason === null) { return; }
                act(cancelBtn, function () { return API + '/' + current.mwoId + '/cancel'; },
                    function () { return {rowVer: current.rowVer, reason: reason}; })();
            });
        });
    }

    var addLaborBtn = document.getElementById('addLaborBtn');
    if (addLaborBtn) {
        addLaborBtn.addEventListener('click', function () {
            hb.showFieldErrors(laborForm, []);
            var f = function (n) { return laborForm.querySelector('[data-field="' + n + '"]').value; };
            var payload = {loginId: f('loginId'), workDate: f('workDate'), minutes: minutesNum.value() === null ? null : Number(minutesNum.value()),
                remark: f('remark')};
            hb.busy(addLaborBtn, function () {
                return hb.api(API + '/' + current.mwoId + '/labor', {method: 'POST', body: payload});
            }).then(function (res) {
                if (!res) { return; }
                laborForm.reset();
                minutesNum.setValue(null);
                return reloadCurrent().then(function () { notify(hb.t('js.common.saved')); });
            }, function (err) {
                notify(err.message, true);
                hb.showFieldErrors(laborForm, err.errors);
            });
        });
        minutesNum = hb.number(laborForm.querySelector('[data-field="minutes"]'));
    }

    // 엑셀(C14) — 지금 조회 조건 그대로
    var excelBtn = document.getElementById('excelBtn');
    if (excelBtn) {
        excelBtn.addEventListener('click', function () {
            hb.busy(/** @type {HTMLButtonElement} */ (excelBtn), function () { return hb.download(API + '/export', sp.values()); })
                .then(null, function (err) { notify(err.message, true); });
        });
    }

    // ---------------------------------------------------------------- 예비품 반납(완료 뒤, WMS 재고 + 행 — docs/33 11.15)
    /** 요청 id 는 반납을 누른 순간 만들고 성공하거나 입력이 바뀌면 버린다 — 응답이 끊겨 다시 눌러도 두 번 반납되지 않는다 */
    var returnReq = null;

    function reqId() {
        if (window.crypto && typeof window.crypto.randomUUID === 'function') { return window.crypto.randomUUID(); }
        var a = new Uint8Array(16);
        window.crypto.getRandomValues(a);
        return Array.prototype.map.call(a, function (/** @type {number} */ x) { return ('0' + x.toString(16)).slice(-2); }).join('');
    }

    /**
     * 반납 누계 칸(C18) — 완료·확인만. 창고 연계가 알려 준 줄 = "누계 (남은 n)", 모르면(연계 없음·출고 기록 없음) "모름" — 0 으로 보이지 않게
     * @param {{returnedText?: string | null, returnableText?: string | null, unitCd: string}} p
     */
    function returnedCell(p) {
        var done = current.status === 'DONE' || current.status === 'CLOSED';
        if (!done) { return hb.el('td'); }
        if (p.returnedText === null || p.returnedText === undefined) {
            var td = hb.el('td', 'hb-muted', hb.t('js.cms.mwo.returnUnknown'));
            td.title = hb.t('js.cms.mwo.returnUnknownTip');
            return td;
        }
        return hb.el('td', null, hb.t('js.cms.mwo.returnedLine', p.returnedText + ' ' + p.unitCd, p.returnableText + ' ' + p.unitCd));
    }

    /** @param {Array<{partId: number, itemCd: string, itemNm: string, qtyText: string, unitCd: string, returnableText?: string | null}>} rows */
    function fillReturn(rows) {
        var box = document.getElementById('returnBox');
        if (!box) { return; }
        var done = current.status === 'DONE' || current.status === 'CLOSED';
        box.hidden = !done || !rows.length;
        var sel = /** @type {HTMLSelectElement} */ (document.getElementById('returnPart'));
        sel.textContent = '';
        rows.forEach(function (p) {
            var o = hb.el('option', null, p.itemCd + ' · ' + p.itemNm + ' (' + p.qtyText + ' ' + p.unitCd + ')'
                + (p.returnableText !== null && p.returnableText !== undefined ? ' — ' + hb.t('js.cms.mwo.returnable', p.returnableText + ' ' + p.unitCd) : ''));
            o.value = String(p.partId);
            sel.appendChild(o);
        });
        returnReq = null;
    }

    var returnForm = document.getElementById('returnForm');
    var returnBtn = document.getElementById('returnBtn');
    if (returnForm && returnBtn) {
        returnForm.addEventListener('input', function () { returnReq = null; });
        returnForm.addEventListener('change', function () { returnReq = null; });
        returnBtn.addEventListener('click', function () {
            var rf = /** @type {HTMLFormElement} */ (returnForm);
            hb.showFieldErrors(rf, []);
            var v = function (/** @type {string} */ n) { return /** @type {HTMLInputElement} */ (rf.querySelector('[data-field="' + n + '"]')).value; };
            if (!returnReq) { returnReq = reqId(); }
            var partId = v('partId');
            var payload = {qty: v('qty'), reason: v('reason'), clientReqId: returnReq};
            hb.busy(/** @type {HTMLButtonElement} */ (returnBtn), function () {
                return hb.api(API + '/' + current.mwoId + '/parts/' + partId + '/return', {method: 'POST', body: payload});
            }).then(function (r) {
                if (!r) { return; }
                rf.reset();
                returnReq = null;
                notify(r.data ? hb.t('js.cms.mwo.returned') : hb.t('js.cms.mwo.returnNoStock'));
                return loadParts();   // 반납 누계 다시(C18)
            }, function (err) {
                hb.showFieldErrors(rf, err.errors);
                notify(err.message, true);
            });
        });
    }

    var addPartBtn = document.getElementById('addPartBtn');
    if (addPartBtn) {
        addPartBtn.addEventListener('click', function () {
            var partForm = document.getElementById('partForm');
            hb.showFieldErrors(partForm, []);
            var f = function (n) { return partForm.querySelector('[data-field="' + n + '"]').value; };
            var payload = {itemCd: f('itemCd'), qty: f('qty'), remark: f('remark')};
            hb.busy(addPartBtn, function () {
                return hb.api(API + '/' + current.mwoId + '/parts', {method: 'POST', body: payload});
            }).then(function (res) {
                if (!res) { return; }
                partForm.reset();
                return loadParts().then(function () { notify(hb.t('js.common.saved')); });
            }, function (err) {
                notify(err.message, true);
                hb.showFieldErrors(partForm, err.errors);
            });
        });
    }

    var fileInput = document.getElementById('fileInput');
    if (fileInput) {
        fileInput.addEventListener('change', function () {
            if (!fileInput.files.length || !current) { return; }
            var fd = new FormData();
            fd.append('targetType', PROG);
            fd.append('targetId', String(current.mwoId));
            fd.append('file', fileInput.files[0]);
            hb.upload('/api/platform/files', fd).then(function () {
                fileInput.value = '';
                notify(hb.t('js.common.saved'));
                loadFiles();
            }, function (err) {
                fileInput.value = '';
                notify(err.message + (err.details && err.details.length ? ' ' + err.details.join(' ') : ''), true);
            });
        });
    }

    // 대상 찾기: 칸에 치면 후보(datalist), 고르면 그 설비 클래스로 고장 코드 선택지를 좁힌다
    var targetInput = field('targetCd');
    var targetTimer = null;
    targetInput.addEventListener('input', function () {
        var key = targetInput.value.trim().toUpperCase();
        var hit = targetHits[field('targetType').value + ':' + key];
        targetClassId = hit ? hit.classId || null : null;
        refillCodes(null);
        clearTimeout(targetTimer);
        targetTimer = setTimeout(function () {
            hb.latest('targets', hb.api(API + '/targets?keyword=' + encodeURIComponent(targetInput.value))).then(function (res) {
                var dl = document.getElementById('targetList');
                dl.textContent = '';
                res.data.filter(function (t) { return t.targetType === field('targetType').value; }).forEach(function (t) {
                    targetHits[t.targetType + ':' + t.code] = t;
                    var o = hb.el('option', null, t.name + (t.locationCd ? ' (' + t.locationCd + ')' : ''));
                    o.value = t.code;
                    dl.appendChild(o);
                });
                var again = targetHits[field('targetType').value + ':' + targetInput.value.trim().toUpperCase()];
                if (again) { targetClassId = again.classId || null; refillCodes(null); }
            }, function () { /* 후보는 보조 — 실패해도 저장 때 서버가 확인 */ });
        }, 250);
    });

    hb.dirty.watch(form);
    // 새 작업지시에서 유형을 바꾸면 ISO 14224 칸(고장 수리만)도 켜고 끈다
    field('mwoType').addEventListener('change', function () {
        var bm = field('mwoType').value === 'BM';
        ISO.forEach(function (x) { if (!bm) { field(x[0]).value = ''; } field(x[0]).disabled = !CAN_SAVE || !bm; });
    });
    search.addEventListener('submit', function (e) { e.preventDefault(); });   // 패널이 준비되기 전 Enter 로 화면이 다시 읽히지 않게
    hb.loadMessages().then(function () {
        hb.page.init();
        return Promise.all([hb.loadSession(), hb.api(API + '/options'), hb.codeList('CMS_MWO_TYPE'), hb.codeList('CMS_MWO_STATUS'),
            hb.codeList('CMS_FAIL_MECH'), hb.codeList('CMS_FAIL_DETECT'), hb.codeList('CMS_FAIL_IMPACT')]);
    }).then(function (r) {
        var ps = document.getElementById('plantSearch');
        option(ps, '', hb.t('js.cms.allPlants'));
        r[1].data.plants.forEach(function (p) { option(ps, p.code, p.code + ' · ' + p.name); });
        var as = field('assigneeLoginId');
        option(as, '', hb.t('js.cms.noOwner'));
        var lu = document.getElementById('laborUser');
        if (lu) { option(lu, '', hb.t('js.cms.mwo.me')); }
        r[1].data.users.forEach(function (u) {
            option(as, u.loginId, u.userNm + ' (' + u.loginId + ')');
            if (lu) { option(lu, u.loginId, u.userNm + ' (' + u.loginId + ')'); }
        });
        codes = r[1].data.failCodes;
        r[2].forEach(function (c) { names.type[c.code] = c.name; });
        r[3].forEach(function (c) { names.status[c.code] = c.name; });
        ISO.forEach(function (x, i) { isoLists[x[1]] = r[4 + i]; });
        // 세션(회사 시간대)을 받은 뒤 만든다 — 프리셋이 회사 날짜로 계산되게
        sp = hb.searchPanel(search, {onSearch: function () {
            return cmsUi.leaveOk().then(function (ok) { return ok ? load() : null; });
        }});
        grid = hb.grid('#mwoGrid', {
            progCd: PROG, id: 'main', key: 'mwoId',
            url: API + '/grid', columnsUrl: API + '/columns', exportUrl: API + '/export', actions: ACTIONS, params: params,
            filters: {mwoType: 'mwoType', status: 'status'},
            format: {priority: prio},
            onFilter: function (param, value) { sp.setValue(param, value); },
            beforeSelect: function () { return cmsUi.leaveOk(); },
            status: {
                status: cmsUi.TONES.mwo,
                dueDate: function (v, row) { return row.overdue ? 'danger' : null; }   // 기한 지남 = 지연 배지(글자 = 기한)
            },
            rowClass: function (row) { return row.status === 'CANCELLED' || row.status === 'CLOSED' ? 'hb-tree-dim' : null; },
            onSelect: function (row) { openRow(row.mwoId); },
            notify: notify
        });
        // grid 가 첫 쪽을 이미 읽었다 — 다시 읽지 않는다. ?mwo=작업지시 id(설비 이력 카드) 면 그 작업지시 상세를 연다(목록 조건 밖이어도)
        var opened = new URLSearchParams(window.location.search).get('mwo');
        return grid.ready.then(function () { return /^\d{1,18}$/.test(opened || '') ? openRow(opened) : show(null); });
    }).then(null, function (err) { notify(err && err.message, true); });
})();
