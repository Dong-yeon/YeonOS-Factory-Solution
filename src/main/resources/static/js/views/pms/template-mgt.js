/*
 * 프로젝트 템플릿 (pms-template) — PMS 1단계 (docs/32 PM1·4.1·4.2).
 * 템플릿 표 → 고르면 WBS·간트(미리보기 기준일로 펼친 모습) + 오른쪽 템플릿 폼·작업 폼.
 * 작업 일정 = 기준일 대비 시작(일) + 기간(일). 요약(하위가 있는 작업)의 일정은 하위에서 계산해 보여 준다.
 */
(function () {
    'use strict';

    var API = '/api/pms/templates';
    var ACTIONS = (document.body.getAttribute('data-actions') || '').split(',');
    var CAN_SAVE = ACTIONS.indexOf('SAVE') >= 0;
    var msg = document.getElementById('msg');
    var tmplForm = document.getElementById('tmplForm');
    var taskForm = document.getElementById('taskForm');
    var taskBox = document.getElementById('taskBox');
    var previewBase = document.getElementById('previewBase');
    var tmpl = null, tasks = [], current = null, newSlot = null, gantt;
    var items = [];            // PM3 산출물 칸·점검 문구(템플릿 전체)
    /** @type {Array<{tdepId: number, predTtId: number, succTtId: number, lagDays: number, depType: string}>} */
    var tdeps = [];            // 템플릿 선후행(docs/32 4.18)
    var typeNames = {};
    var DAY = 86400000;
    var sp, grid;

    function notify(text, error) {
        msg.textContent = text || '';
        msg.classList.toggle('hb-msg-error', !!error);
    }

    function fail(form) {
        return function (err) {
            notify(err && err.errors && err.errors.length ? err.errors[0].message : err && err.message, true);
            if (form) { hb.showFieldErrors(form, err && err.errors); }
        };
    }

    function mf(n) { return tmplForm.querySelector('[data-field="' + n + '"]'); }
    function tf(n) { return taskForm.querySelector('[data-field="' + n + '"]'); }

    function option(sel, value, text) {
        var o = hb.el('option', null, text);
        o.value = value;
        sel.appendChild(o);
    }

    function anchorName(a) {
        return a === 'SOP' ? hb.t('js.pms.anchor.SOP') : hb.t('js.pms.anchor.START');
    }

    /** 'yyyy-MM-dd' + n 일 (UTC 일 번호로 — 시간대·서머타임 무관) */
    function plus(base, n) {
        var p = base.split('-');
        var d = new Date(Date.UTC(+p[0], +p[1] - 1, +p[2]) + n * DAY);
        return d.getUTCFullYear() + '-' + pad(d.getUTCMonth() + 1) + '-' + pad(d.getUTCDate());
    }
    function pad(n) { return n < 10 ? '0' + n : String(n); }

    function dayText(n) {
        return n === 0 ? hb.t('js.pms.dayZero') : (n > 0 ? '+' + n : String(n));
    }

    // ------------------------------------------------------------------ 템플릿 목록·폼

    function params() {
        var v = sp.values();
        var q = {};
        ['keyword', 'useYn'].forEach(function (k) { if (v[k]) { q[k] = v[k]; } });
        return q;
    }

    /** 템플릿 목록 = hb.grid(UI 단계 3 ②). selectId 가 이 쪽에 있으면 grid 가 골라 연다(onSelect), 없으면 바로 연다 */
    function loadList(selectId) {
        return grid.reload(true, selectId || undefined).then(function () {
            var sel = grid.selected();
            if (selectId && (!sel || sel.tmplId !== selectId)) { return open(selectId, null, true); }
        });
    }

    function open(id, selectTaskId, force) {
        return (force ? Promise.resolve(true) : pmsUi.leaveOk()).then(function (ok) {
            if (!ok) { return; }
            return load(id, selectTaskId);
        });
    }

    function load(id, selectTaskId) {
        return hb.latest('template', Promise.all([hb.api(API + '/' + id), hb.api(API + '/' + id + '/items')])).then(function (r) {
            var res = r[0];
            items = r[1].data;
            showTemplate(res.data.template);
            tasks = res.data.tasks;
            tdeps = res.data.deps || [];
            draw();
            var pick = selectTaskId ? tasks.filter(function (t) { return t.ttId === selectTaskId; })[0] : null;
            if (pick) { gantt.select(pick.ttId); }
            newSlot = null;
            showTask(pick || null);
            hb.page.openDetail();
        }, fail());
    }

    function showTemplate(t) {
        tmpl = t;
        hb.showFieldErrors(tmplForm, []);
        ['tmplCd', 'tmplNm', 'remark'].forEach(function (n) { mf(n).value = t ? (t[n] || '') : ''; });
        mf('projType').value = t ? t.projType : 'APQP';
        mf('anchor').value = t ? t.anchor : 'START';
        mf('useYn').value = t ? t.useYn : 'Y';
        mf('tmplCd').readOnly = !!t;
        document.getElementById('tmplMode').textContent = t ? hb.t('js.pms.editingTmpl', t.tmplCd, t.tmplNm) : hb.t('js.pms.creatingTmpl');
        taskBox.hidden = !t;
        document.getElementById('wbsTools').hidden = !t;
        if (!t) { tasks = []; draw(); }
        hb.dirty.clear();
    }

    function draw() {
        var base = previewBase.value || hb.dates.todayIn(hb.session().timeZone);
        gantt.setRows(tasks.map(function (t) {
            var s = plus(base, t.startDay), e = plus(base, t.endDay);
            return {id: t.ttId, lvl: t.lvl, summary: t.isSummary === 'Y', type: t.taskType, start: s, end: e, progress: 0,
                title: t.wbsNo + ' ' + t.taskNm + ' · ' + s + ' ~ ' + e,
                cells: {wbs: t.wbsNo, name: t.taskNm, offset: dayText(t.startDay), dur: String(t.endDay - t.startDay + 1),
                    start: s, end: e, std: t.stdHours === null || t.stdHours === undefined ? '' : hb.fmt.num(t.stdHours, 1, 0)}};
        }), {today: base});
        updateTools();
    }

    // ------------------------------------------------------------------ 작업 폼

    function findTask(id) { return tasks.filter(function (t) { return t.ttId === id; })[0] || null; }

    function showTask(t) {
        current = t;
        hb.showFieldErrors(taskForm, []);
        var adding = !t && !!newSlot;
        tf('taskNm').value = t ? t.taskNm : '';
        tf('taskType').value = t ? t.taskType : 'TASK';
        tf('offsetDays').value = t ? String(t.offsetDays) : (adding && newSlot.parentId ? String(findTask(newSlot.parentId).startDay) : '0');
        tf('durationDays').value = t ? String(t.durationDays || 1) : '1';
        tf('remark').value = t ? (t.remark || '') : '';
        tf('stdHours').value = t && t.stdHours !== null && t.stdHours !== undefined ? String(t.stdHours) : '';
        var summary = t && t.isSummary === 'Y';
        var edit = CAN_SAVE && (adding || !!t);
        taskForm.querySelectorAll('input, select').forEach(function (i) {
            var n = i.getAttribute('data-field');
            i.disabled = !edit || (summary && (n === 'offsetDays' || n === 'durationDays'))
                || ((n === 'durationDays' || n === 'stdHours') && tf('taskType').value !== 'TASK')
                || (summary && n === 'stdHours');
        });
        var par = adding && newSlot.parentId ? findTask(newSlot.parentId) : null;
        document.getElementById('taskMode').textContent = adding
            ? (par ? hb.t('js.pms.addingUnder', par.wbsNo + ' ' + par.taskNm) : hb.t('js.pms.addingRoot'))
            : (t ? hb.t('js.pms.editingTask', t.wbsNo, t.taskNm) : hb.t('js.pms.selectTask'));
        document.getElementById('taskNote').textContent = summary ? hb.t('js.pms.tmplSummaryNote') : '';
        var btn = document.getElementById('saveTaskBtn');
        if (btn) { btn.hidden = !edit; }
        showItems(t);
        showTmplDeps(t);
        updateTools();
        hb.dirty.clear(taskForm);
    }

    /** PM3: 고른 작업의 산출물 칸·점검 문구(점검 문구는 게이트만). 추가·삭제만 — 이미 만든 프로젝트에는 반영되지 않는다 */
    function showItems(t) {
        var box = document.getElementById('itemBox');
        box.hidden = !t;
        var list = document.getElementById('itemList');
        list.textContent = '';
        if (!t) { return; }
        items.filter(function (i) { return i.ttId === t.ttId; }).forEach(function (i) {
            var li = hb.el('li');
            li.appendChild(hb.el('span', 'pms-status', i.kind === 'CHECK' ? hb.t('js.pms.itemKind.CHECK') : hb.t('js.pms.itemKind.DELIV')));
            li.appendChild(hb.el('span', null, i.itemNm));
            if (i.kind === 'DELIV' && i.requiredYn !== 'Y') { li.appendChild(hb.el('span', 'hb-muted', hb.t('js.pms.optional'))); }
            if (i.formDocNo) { li.appendChild(pmsUi.formLink(i.formDocNo)); }   // 빈 서식(QMS 관리문서, docs/32 4.17)
            if (CAN_SAVE) {
                var del = hb.el('button', 'hb-btn-ghost', hb.t('js.pms.removeItem'));
                del.type = 'button';
                del.addEventListener('click', function () {
                    hb.busy(del, function () { return hb.api(API + '/items/' + i.tiId, {method: 'DELETE'}); }).then(function (res) {
                        if (!res) { return; }
                        return reloadItems(t);
                    }, fail());
                });
                li.appendChild(del);
            }
            list.appendChild(li);
        });
        var kind = document.querySelector('#itemForm [data-field="kind"]');
        if (kind) {
            var gateOpt = kind.querySelector('option[value="CHECK"]');
            if (gateOpt) { gateOpt.disabled = t.taskType !== 'GATE'; }
            if (t.taskType !== 'GATE') { kind.value = 'DELIV'; }
        }
    }

    /** 고른 잎 작업의 선행 작업 + 추가 폼(잎끼리, 자기 자신 빼고) — 프로젝트 화면 선행 작업과 같은 모양 */
    function showTmplDeps(t) {
        var box = /** @type {HTMLElement} */ (document.getElementById('tmplDepBox'));
        var list = /** @type {HTMLElement} */ (document.getElementById('tmplPredList'));
        box.hidden = !t || t.isSummary === 'Y';
        list.textContent = '';
        if (box.hidden) { return; }
        var preds = tdeps.filter(function (d) { return d.succTtId === t.ttId; });
        if (!preds.length) { list.appendChild(hb.el('li', 'hb-muted', hb.t('js.pms.noPreds'))); }
        preds.forEach(function (d) {
            var p = findTask(d.predTtId);
            var li = hb.el('li', null, (p ? p.wbsNo + ' ' + p.taskNm : '?') + pmsUi.depTypeTag(d.depType)
                + (d.lagDays ? ' (' + hb.t('js.pms.lag', (d.lagDays > 0 ? '+' : '') + d.lagDays) + ')' : ''));
            if (CAN_SAVE) {
                var del = /** @type {HTMLButtonElement} */ (hb.el('button', 'hb-btn-ghost', hb.t('js.pms.removeDep')));
                del.type = 'button';
                del.addEventListener('click', function () {
                    hb.busy(del, function () { return hb.api(API + '/deps/' + d.tdepId, {method: 'DELETE'}); })
                        .then(function (res) { if (res) { return load(tmpl.tmplId, t.ttId); } }, fail());
                });
                li.appendChild(del);
            }
            list.appendChild(li);
        });
        var form = document.getElementById('tmplDepForm');
        if (!form) { return; }
        var sel = /** @type {HTMLSelectElement} */ (document.getElementById('tmplPredSel'));
        sel.textContent = '';
        tasks.filter(function (x) { return x.isSummary !== 'Y' && x.ttId !== t.ttId; }).forEach(function (x) {
            option(sel, String(x.ttId), x.wbsNo + ' ' + x.taskNm);
        });
        /** @type {HTMLInputElement} */ (form.querySelector('[data-field="lagDays"]')).value = '0';
        /** @type {HTMLSelectElement} */ (form.querySelector('[data-field="depType"]')).value = 'FS';
        hb.showFieldErrors(form, []);
    }

    var addTmplDepBtn = document.getElementById('addTmplDepBtn');
    if (addTmplDepBtn) {
        addTmplDepBtn.addEventListener('click', function () {
            var btn = /** @type {HTMLButtonElement} */ (this);
            var form = /** @type {HTMLFormElement} */ (document.getElementById('tmplDepForm'));
            if (!current || !tmpl) { return; }
            hb.showFieldErrors(form, []);
            var lag = /** @type {HTMLInputElement} */ (form.querySelector('[data-field="lagDays"]')).value;
            var t = current;
            var body = {predTtId: Number(/** @type {HTMLSelectElement} */ (document.getElementById('tmplPredSel')).value), succTtId: t.ttId,
                lagDays: lag === '' ? 0 : Number(lag), depType: /** @type {HTMLSelectElement} */ (form.querySelector('[data-field="depType"]')).value};
            hb.busy(btn, function () { return hb.api(API + '/' + tmpl.tmplId + '/deps', {method: 'POST', body: body}); })
                .then(function (res) {
                    if (!res) { return; }
                    return load(tmpl.tmplId, t.ttId).then(function () { notify(hb.t('js.common.saved')); });
                }, fail(form));     // 서버 칸 이름(predTtId·lagDays) = 폼 칸
        });
    }

    function reloadItems(t) {
        return hb.latest('items', hb.api(API + '/' + tmpl.tmplId + '/items')).then(function (res) {
            items = res.data;
            showItems(t);
        });
    }

    var addItemBtn = document.getElementById('addItemBtn');
    if (addItemBtn) {
        addItemBtn.addEventListener('click', function () {
            var t = current;
            var form = document.getElementById('itemForm');
            if (!t) { return; }
            var b = {};
            ['kind', 'itemNm', 'requiredYn', 'formDocNo'].forEach(function (n) { b[n] = form.querySelector('[data-field="' + n + '"]').value; });
            hb.showFieldErrors(form, []);
            hb.busy(addItemBtn, function () { return hb.api(API + '/tasks/' + t.ttId + '/items', {method: 'POST', body: b}); })
                .then(function (res) {
                    if (!res) { return; }
                    form.querySelector('[data-field="itemNm"]').value = '';
                    form.querySelector('[data-field="formDocNo"]').value = '';
                    return reloadItems(t).then(function () { notify(hb.t('js.common.saved')); });
                }, fail(form));
        });
    }

    tf('taskType').addEventListener('change', function () {
        var point = tf('taskType').value !== 'TASK';
        tf('durationDays').disabled = point || !CAN_SAVE;
        if (point) { tf('durationDays').value = '0'; } else if (tf('durationDays').value === '0') { tf('durationDays').value = '1'; }
        // 표준 공수도 TASK 만(마일스톤·게이트는 기간 0)
        tf('stdHours').disabled = point || !CAN_SAVE || (!!current && current.isSummary === 'Y');
        if (point) { tf('stdHours').value = ''; }
    });

    function updateTools() {
        var on = CAN_SAVE && !!tmpl;
        ['addChildBtn', 'addSiblingBtn', 'deleteTaskBtn'].forEach(function (id) { document.getElementById(id).hidden = !on; });
        document.querySelectorAll('[data-move]').forEach(function (b) { b.hidden = !on; b.disabled = !current; });
        document.getElementById('deleteTaskBtn').disabled = !current;
        document.getElementById('addSiblingBtn').disabled = !current;
    }

    function startAdd(parentId, afterId) {
        pmsUi.leaveOk().then(function (ok) {
            if (!ok) { return; }
            newSlot = {parentId: parentId, afterId: afterId};
            showTask(null);
            hb.page.openDetail();
            tf('taskNm').focus();
        });
    }

    // ------------------------------------------------------------------ 버튼

    var newTmplBtn = document.getElementById('newTmplBtn');
    if (newTmplBtn) {
        newTmplBtn.addEventListener('click', function () {
            pmsUi.leaveOk().then(function (ok) {
                if (!ok) { return; }
                showTemplate(null);
                hb.page.openDetail();
                mf('tmplCd').focus();
            });
        });
    }

    var apqpBtn = document.getElementById('apqpBtn');
    if (apqpBtn) {
        apqpBtn.addEventListener('click', function () {
            hb.busy(apqpBtn, function () { return hb.api(API + '/default-apqp', {method: 'POST', body: {}}); })
                .then(function (res) {
                    if (!res) { return; }
                    return loadList(res.data).then(function () { notify(hb.t('js.pms.apqpCreated')); });
                }, fail());
        });
    }

    var saveTmplBtn = document.getElementById('saveTmplBtn');
    if (saveTmplBtn) {
        saveTmplBtn.addEventListener('click', function () {
            hb.showFieldErrors(tmplForm, []);
            var b = {};
            ['tmplCd', 'tmplNm', 'projType', 'anchor', 'useYn', 'remark'].forEach(function (n) { b[n] = mf(n).value; });
            var editing = !!tmpl;
            if (editing) { b.rowVer = tmpl.rowVer; }
            hb.busy(saveTmplBtn, function () {
                return editing ? hb.api(API + '/' + tmpl.tmplId, {method: 'PUT', body: b}) : hb.api(API, {method: 'POST', body: b});
            }).then(function (res) {
                if (!res) { return; }
                hb.dirty.clear();
                return loadList(editing ? tmpl.tmplId : res.data).then(function () { notify(hb.t('js.common.saved')); });
            }, fail(tmplForm));
        });
    }

    var saveTaskBtn = document.getElementById('saveTaskBtn');
    if (saveTaskBtn) {
        saveTaskBtn.addEventListener('click', function () {
            hb.showFieldErrors(taskForm, []);
            var adding = !current && newSlot;
            if (!current && !adding) { return; }
            var num = function (n) { var v = tf(n).value; return v === '' ? null : Number(v); };
            var b = {taskNm: tf('taskNm').value, taskType: tf('taskType').value, offsetDays: num('offsetDays'),
                durationDays: num('durationDays'), remark: tf('remark').value, stdHours: num('stdHours')};
            if (adding) { b.parentId = newSlot.parentId; b.afterId = newSlot.afterId; } else { b.rowVer = current.rowVer; }
            hb.busy(saveTaskBtn, function () {
                return adding ? hb.api(API + '/' + tmpl.tmplId + '/tasks', {method: 'POST', body: b})
                    : hb.api(API + '/tasks/' + current.ttId, {method: 'PUT', body: b});
            }).then(function (res) {
                if (!res) { return; }
                var id = adding ? res.data : current.ttId;
                hb.dirty.clear();
                return open(tmpl.tmplId, id, true).then(function () { notify(hb.t('js.common.saved')); });
            }, fail(taskForm));
        });
    }

    document.getElementById('addChildBtn').addEventListener('click', function () { startAdd(current ? current.ttId : null, null); });
    document.getElementById('addSiblingBtn').addEventListener('click', function () { if (current) { startAdd(current.parentId, current.ttId); } });

    document.querySelectorAll('[data-move]').forEach(function (b) {
        b.addEventListener('click', function () {
            if (!current) { return; }
            var id = current.ttId;
            hb.busy(b, function () {
                return hb.api(API + '/tasks/' + id + '/move', {method: 'POST', body: {op: b.getAttribute('data-move'), rowVer: current.rowVer}});
            }).then(function (res) {
                if (!res) { return; }
                return open(tmpl.tmplId, id, true).then(function () { gantt.focus(); });
            }, fail());
        });
    });

    document.getElementById('deleteTaskBtn').addEventListener('click', function () {
        if (!current) { return; }
        var btn = this;
        var t = current;
        pmsUi.ask(hb.t('js.pms.dlg.deleteTask'), hb.t('js.pms.confirmDelete', t.wbsNo + ' ' + t.taskNm), hb.t('js.pms.dlg.delete'), true).then(function (ok) {
            if (!ok) { return; }
            var parent = t.parentId;
            hb.busy(btn, function () { return hb.api(API + '/tasks/' + t.ttId, {method: 'DELETE'}); }).then(function (res) {
                if (!res) { return; }
                return open(tmpl.tmplId, parent, true).then(function () { notify(hb.t('js.pms.deleted')); });
            }, fail());
        });
    });

    previewBase.addEventListener('change', draw);
    document.querySelectorAll('#scaleSeg input').forEach(function (r) {
        r.addEventListener('change', function () { if (r.checked) { gantt.setScale(r.value); } });
    });

    // ------------------------------------------------------------------ 시작

    hb.dirty.watch(tmplForm);
    hb.dirty.watch(taskForm);
    hb.loadMessages().then(function () {
        hb.page.init();
        gantt = pmsGantt('#gantt', {
            emptyText: hb.t('js.pms.noTmplTasks'),
            columns: [
                {key: 'wbs', label: hb.t('js.pms.col.wbs'), width: 64},
                {key: 'name', label: hb.t('js.pms.col.taskNm'), width: 240, tree: true},
                {key: 'offset', label: hb.t('js.pms.col.offset'), width: 72, align: 'right'},
                {key: 'dur', label: hb.t('js.pms.col.duration'), width: 56, align: 'right'},
                {key: 'start', label: hb.t('js.pms.col.planStart'), width: 92},
                {key: 'end', label: hb.t('js.pms.col.planEnd'), width: 92},
                {key: 'std', label: hb.t('js.pms.col.stdHours'), width: 72, align: 'right'}],
            onSelect: function (row) {
                var prev = current;
                pmsUi.leaveOk().then(function (ok) {
                    if (!ok) {
                        if (prev) { gantt.select(prev.ttId); }
                        return;
                    }
                    newSlot = null;
                    showTask(row ? findTask(row.id) : null);
                });
            },
            onActivate: function () {
                hb.page.openDetail();
                tf('taskNm').focus();
            }
        });
        gantt.setScale('week');
        return Promise.all([hb.loadSession(), hb.codeList('PMS_PROJ_TYPE')]);
    }).then(function (r) {
        r[1].forEach(function (c) {
            typeNames[c.code] = c.name;
            option(mf('projType'), c.code, c.name);
        });
        previewBase.value = hb.dates.todayIn(hb.session().timeZone);
        if (!CAN_SAVE) { tmplForm.querySelectorAll('input, select').forEach(function (i) { i.disabled = true; }); }
        showTemplate(null);
        sp = hb.searchPanel(document.getElementById('search'), {onSearch: function () { return loadList(); }});
        grid = hb.grid('#tmplGrid', {
            progCd: 'pms-template', id: 'main', key: 'tmplId',
            url: API + '/grid', columnsUrl: API + '/columns', exportUrl: API + '/export', actions: ACTIONS, params: params,
            filters: {useYn: 'useYn'},
            onFilter: function (param, value) { sp.setValue(param, value); },
            format: {anchor: anchorName},
            beforeSelect: function () { return pmsUi.leaveOk(); },     // 미저장 이동 확인(화면 안 대화상자)
            status: {useYn: {Y: 'normal', N: 'neutral'}},
            rowClass: function (row) { return row.useYn === 'N' ? 'hb-tree-dim' : null; },
            onSelect: function (row) { load(row.tmplId); },
            notify: notify
        });
        return grid.ready.then(function () { return loadList(); });
    }).then(null, function (err) { notify(err && err.message, true); });
})();
