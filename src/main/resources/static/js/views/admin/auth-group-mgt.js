// @ts-check
/*
 * 권한그룹 관리 (sys-auth-group) — 시스템관리 1단계 (docs/36). 그룹 목록(표) + 오른쪽 상세 + 화면별 권한 표 + 구성원.
 * 권한 표는 "한 벌" 저장(PUT …/perms?rowVer=): 사용 중 화면의 체크 상태 전체를 보내고 서버가 추가·삭제한다.
 * 내가 없는 권한 칸·나보다 큰 그룹은 잠근다(편의). 판정은 서버(부여 상한, 관리자 표시는 관리자만).
 */
/**
 * @typedef {{authGrpCd: string, authGrpNm: string, sortNo: number, homeProgCd?: string | null, adminYn: string,
 *   useYn: string, memberCnt: number, rowVer: number, editable: boolean}} SysGroup
 * @typedef {{progCd: string, progNm: string, moduleCd: string, actions: string[], granted: string[], grantable: string[]}} SysPermRow
 * @typedef {{userId: number, loginId: string, userNm: string, useYn: string}} SysMember
 * @typedef {{group: SysGroup, perms: SysPermRow[], members: SysMember[]}} SysGroupDetail
 */
(function () {
    'use strict';

    var API = '/api/admin/auth-groups';
    var ACTIONS = (document.body.getAttribute('data-actions') || '').split(',');
    var CAN_SAVE = ACTIONS.indexOf('SAVE') >= 0;
    var ACTION_ORDER = ['SEARCH', 'SAVE', 'DELETE', 'EXCEL', 'PRINT', 'APPROVE', 'FN1', 'FN2', 'FN3', 'FN4', 'FN5'];
    var msg = /** @type {HTMLElement} */ (document.getElementById('msg'));
    var form = /** @type {HTMLFormElement} */ (document.getElementById('groupForm'));
    var formMode = /** @type {HTMLElement} */ (document.getElementById('formMode'));
    var search = /** @type {HTMLFormElement} */ (document.getElementById('search'));
    /** @type {HB.Grid | null} */
    var grid = null;
    /** @type {HB.SearchPanel} */
    var sp;
    var permTable = /** @type {HTMLTableElement} */ (document.getElementById('perms'));
    var permNote = /** @type {HTMLElement} */ (document.getElementById('permNote'));
    /** @type {SysGroupDetail | null} */
    var current = null;
    /** @type {HB.NumberControl} */
    var sortNo;
    var permDirty = false;
    /** 내가 관리자 그룹인가 — 관리자 표시 칸을 열지 */
    var iAmAdmin = false;

    /** 액션 이름 — 문구 키는 리터럴로(I18nKeyGuardTest) @type {Record<string, string>} */
    var actionNames = {};

    /** @param {string | null | undefined} text @param {boolean} [error] */
    function notify(text, error) {
        msg.textContent = text || '';
        msg.classList.toggle('hb-msg-error', !!error);
    }

    /** @param {string} n */
    function field(n) {
        return /** @type {HTMLInputElement | HTMLSelectElement} */ (form.querySelector('[data-field="' + n + '"]'));
    }

    // ---------- 권한 표 ----------
    /** @param {SysGroupDetail} d */
    function renderPerms(d) {
        permTable.textContent = '';
        permDirty = false;
        var editable = CAN_SAVE && d.group.editable;
        /** @type {string[]} */
        var cols = ACTION_ORDER.filter(function (a) { return d.perms.some(function (p) { return p.actions.indexOf(a) >= 0; }); });
        var thead = hb.el('thead');
        var htr = hb.el('tr');
        htr.appendChild(hb.el('th', null, hb.t('js.sysAuthGroup.prog')));
        cols.forEach(function (a) {
            var th = hb.el('th', 'hb-perm-cell');
            if (editable) {
                // 열 머리 = 그 액션 전체 켜기/끄기(잠긴 칸 제외)
                var b = hb.el('button', 'hb-btn-ghost hb-btn-small', actionNames[a] || a);
                b.type = 'button';
                b.title = hb.t('js.sysAuthGroup.toggleColumn');
                b.addEventListener('click', function () { toggleAll('[data-action="' + a + '"]'); });
                th.appendChild(b);
            } else {
                th.textContent = actionNames[a] || a;
            }
            htr.appendChild(th);
        });
        thead.appendChild(htr);
        permTable.appendChild(thead);
        var body = hb.el('tbody');
        var module = '';
        d.perms.forEach(function (p) {
            if (p.moduleCd !== module) {
                module = p.moduleCd;
                var mtr = hb.el('tr', 'hb-perm-module');
                var mtd = hb.el('td', null, module);
                mtd.colSpan = cols.length + 1;
                mtr.appendChild(mtd);
                body.appendChild(mtr);
            }
            var tr = hb.el('tr');
            tr.setAttribute('data-prog', p.progCd);
            var name = hb.el('td');
            if (editable) {
                var rb = hb.el('button', 'hb-btn-ghost hb-btn-small', p.progNm);
                rb.type = 'button';
                rb.title = hb.t('js.sysAuthGroup.toggleRow');
                rb.addEventListener('click', function () { toggleAll('tr[data-prog="' + p.progCd + '"] input'); });
                name.appendChild(rb);
            } else {
                name.textContent = p.progNm;
            }
            name.appendChild(hb.el('span', 'hb-muted', ' ' + p.progCd));
            tr.appendChild(name);
            cols.forEach(function (a) {
                var td = hb.el('td', 'hb-perm-cell');
                if (p.actions.indexOf(a) >= 0) {
                    var box = hb.el('input');
                    box.type = 'checkbox';
                    box.checked = p.granted.indexOf(a) >= 0;
                    box.disabled = !editable || p.grantable.indexOf(a) < 0;
                    box.setAttribute('data-action', a);
                    box.setAttribute('aria-label', p.progNm + ' ' + (actionNames[a] || a));
                    box.addEventListener('change', function () { permDirty = true; });
                    td.appendChild(box);
                }
                tr.appendChild(td);
            });
            body.appendChild(tr);
        });
        permTable.appendChild(body);
        permNote.textContent = d.group.adminYn === 'Y' ? hb.t('js.sysAuthGroup.adminNote')
            : (d.group.editable ? hb.t('js.sysAuthGroup.permNote') : hb.t('js.sysAuthGroup.notEditable'));
    }

    /** 선택한 칸 중 켤 수 있는 것: 하나라도 꺼져 있으면 모두 켜고, 아니면 모두 끈다 @param {string} selector */
    function toggleAll(selector) {
        /** @type {HTMLInputElement[]} */
        var boxes = [];
        permTable.querySelectorAll(selector).forEach(function (b) {
            var box = /** @type {HTMLInputElement} */ (b);
            if (box.type === 'checkbox' && !box.disabled) { boxes.push(box); }
        });
        var on = boxes.some(function (b) { return !b.checked; });
        boxes.forEach(function (b) { b.checked = on; });
        if (boxes.length) { permDirty = true; }
    }

    function permBody() {
        /** @type {{progCd: string, actions: string[]}[]} */
        var items = [];
        permTable.querySelectorAll('tbody tr[data-prog]').forEach(function (tr) {
            /** @type {string[]} */
            var acts = [];
            tr.querySelectorAll('input[type="checkbox"]').forEach(function (b) {
                var box = /** @type {HTMLInputElement} */ (b);
                if (box.checked) { acts.push(box.getAttribute('data-action') || ''); }
            });
            items.push({progCd: tr.getAttribute('data-prog') || '', actions: acts});
        });
        return {items: items};
    }

    // ---------- 상세 ----------
    /** @param {SysGroupDetail | null} d */
    function show(d) {
        current = d;
        hb.showFieldErrors(form, []);
        var g = d ? d.group : null;
        field('authGrpCd').value = g ? g.authGrpCd : '';
        /** @type {HTMLInputElement} */ (field('authGrpCd')).readOnly = !!g || !CAN_SAVE;
        field('authGrpNm').value = g ? g.authGrpNm : '';
        sortNo.setValue(g ? g.sortNo : 0);
        field('useYn').value = g ? g.useYn : 'Y';
        field('homeProgCd').value = g && g.homeProgCd ? g.homeProgCd : '';
        var admin = /** @type {HTMLInputElement} */ (field('adminYn'));
        admin.checked = !!g && g.adminYn === 'Y';
        var editable = CAN_SAVE && (!g || g.editable);
        /** @type {HTMLInputElement} */ (field('authGrpNm')).readOnly = !editable;
        field('useYn').disabled = !editable;
        field('homeProgCd').disabled = !editable;
        admin.disabled = !editable || !iAmAdmin;   // 관리자 표시는 관리자만
        formMode.textContent = !g ? hb.t('js.sysAuthGroup.creating')
            : (g.editable ? hb.t('js.sysAuthGroup.editing', g.authGrpCd) : hb.t('js.sysAuthGroup.notEditableShort', g.authGrpCd));
        var saveBtn = document.getElementById('saveBtn');
        if (saveBtn) { saveBtn.hidden = !editable; }
        /** @type {HTMLElement} */ (document.getElementById('permSection')).hidden = !d;
        /** @type {HTMLElement} */ (document.getElementById('memberSection')).hidden = !d;
        if (d) {
            renderPerms(d);
            var ul = /** @type {HTMLElement} */ (document.getElementById('members'));
            ul.textContent = '';
            if (!d.members.length) { ul.appendChild(hb.el('li', 'hb-muted', hb.t('js.sysAuthGroup.noMembers'))); }
            d.members.forEach(function (m) {
                ul.appendChild(hb.el('li', m.useYn === 'Y' ? null : 'hb-muted',
                    m.loginId + ' · ' + m.userNm + (m.useYn === 'Y' ? '' : ' (' + hb.t('js.yn.N') + ')')));
            });
        }
        hb.dirty.clear();
    }

    function hasUnsaved() { return hb.dirty.isDirty() || permDirty; }
    /** 저장 안 한 입력(그룹 폼·권한 표)이 있으면 화면 안 대화상자로 묻는다 @return {Promise<boolean>} */
    function confirmLeave() {
        if (!hasUnsaved()) { return Promise.resolve(true); }
        return hb.dialog.confirm({title: hb.t('js.common.leaveTitle'), message: hb.t('js.common.confirmLeave'), ok: hb.t('js.common.leaveOk')});
    }

    /** @param {string} cd @param {boolean} [keepMsg] */
    function loadDetail(cd, keepMsg) {
        if (!keepMsg) { notify(''); }
        return hb.latest('detail', hb.api(API + '/' + encodeURIComponent(cd))).then(function (res) { show(res.data); },
            function (err) { if (!err.stale) { notify(err.message, true); } });
    }

    /** 다시 읽고 그 그룹을 고른다 — 이 쪽에 없으면(정렬·검색어 밖) 상세만 다시 @param {string} [selectCd] */
    function load(selectCd) {
        if (!grid) { return Promise.resolve(); }
        var g = grid;
        return g.reload(false, selectCd).then(function () {
            if (selectCd && !g.selected()) { return loadDetail(selectCd, true); }
            if (!selectCd) { show(null); }
        });
    }

    var newBtn = document.getElementById('newBtnList');
    if (newBtn) {
        newBtn.addEventListener('click', function () {
            confirmLeave().then(function (ok) {
                if (!ok) { return; }
                show(null);
                hb.page.openDetail();
                field('authGrpCd').focus();
            });
        });
    }

    var saveBtnEl = /** @type {HTMLButtonElement | null} */ (document.getElementById('saveBtn'));
    if (saveBtnEl) {
        var saveBtn = saveBtnEl;
        saveBtn.addEventListener('click', function () {
            var editCd = current ? current.group.authGrpCd : null;   // 저장 중 다른 그룹을 골라도 이 그룹으로 마무리
            var body = {authGrpCd: field('authGrpCd').value, authGrpNm: field('authGrpNm').value,
                sortNo: sortNo.numberValue(), useYn: field('useYn').value, homeProgCd: field('homeProgCd').value,
                adminYn: /** @type {HTMLInputElement} */ (field('adminYn')).checked ? 'Y' : 'N',
                rowVer: current ? current.group.rowVer : null};
            hb.busy(saveBtn, function () {
                return editCd !== null ? hb.api(API + '/' + encodeURIComponent(editCd), {method: 'PUT', body: body})
                    : hb.api(API, {method: 'POST', body: body});
            }).then(function (res) {
                if (!res) { return; }
                hb.dirty.clear();
                permDirty = false;
                return load(editCd !== null ? editCd : res.data).then(function () { notify(hb.t('js.common.saved')); });
            }, function (err) {
                notify(err.message, true);
                hb.showFieldErrors(form, err.errors);
            });
        });
    }

    var permBtnEl = /** @type {HTMLButtonElement | null} */ (document.getElementById('savePermsBtn'));
    if (permBtnEl) {
        var permBtn = permBtnEl;
        permBtn.addEventListener('click', function () {
            if (!current) { return; }
            var g = current.group;
            var body = permBody();
            hb.busy(permBtn, function () {
                return hb.api(API + '/' + encodeURIComponent(g.authGrpCd) + '/perms?rowVer=' + g.rowVer, {method: 'PUT', body: body});
            }).then(function (res) {
                if (!res) { return; }
                permDirty = false;
                return load(g.authGrpCd).then(function () { notify(hb.t('js.sysAuthGroup.permsSaved')); });
            }, function (err) { notify(err.message, true); });
        });
    }

    // ---------- 시작 ----------
    hb.dirty.watch(form);
    hb.loadMessages().then(function () {
        hb.page.init();
        actionNames = {SEARCH: hb.t('js.sysAuthGroup.action.SEARCH'), SAVE: hb.t('js.sysAuthGroup.action.SAVE'),
            DELETE: hb.t('js.sysAuthGroup.action.DELETE'), EXCEL: hb.t('js.sysAuthGroup.action.EXCEL'),
            PRINT: hb.t('js.sysAuthGroup.action.PRINT'), APPROVE: hb.t('js.sysAuthGroup.action.APPROVE'),
            FN1: 'FN1', FN2: 'FN2', FN3: 'FN3', FN4: 'FN4', FN5: 'FN5'};
        sortNo = hb.number(/** @type {HTMLInputElement} */ (field('sortNo')));
        return Promise.all([hb.loadSession(), hb.api(API + '/progs'), hb.api(API + '/my-scope')]);
    }).then(function (r) {
        iAmAdmin = !!(r[2].data && r[2].data.admin);
        var sel = /** @type {HTMLSelectElement} */ (document.getElementById('homeSel'));
        /** @type {{progCd: string, name: string}[]} */ (r[1].list || []).forEach(function (p) {
            var o = hb.el('option', null, p.name + ' (' + p.progCd + ')');
            o.value = p.progCd;
            sel.appendChild(o);
        });
        show(null);
        sp = hb.searchPanel(search, {
            onSearch: function () {
                if (!grid) { return null; }
                var g = grid;
                return confirmLeave().then(function (ok) { return ok ? g.reload(true) : null; });
            }
        });
        return sp.ready;
    }).then(function () {
        grid = hb.grid('#groupGrid', {
            progCd: 'sys-auth-group', id: 'main', key: 'authGrpCd',
            url: API + '/grid', columnsUrl: API + '/columns', exportUrl: API + '/export', auditTable: 'sys_auth_group', actions: ACTIONS,
            params: function () { return sp.values(); },
            format: {adminYn: function (v) { return v === 'Y' ? hb.t('js.sysAuthGroup.admin') : ''; }},
            status: {useYn: sysUi.TONES.use},
            rowClass: function (row) { return row.useYn === 'N' ? 'hb-tree-dim' : null; },
            beforeSelect: function () { return hasUnsaved() ? confirmLeave() : true; },   // 권한 표 미저장도 묻는다
            onSelect: function (row) { loadDetail(row.authGrpCd); hb.page.openDetail(); },
            notify: notify
        });
        return grid.ready;
    }).then(null, function (err) { notify(err && err.message, true); });
})();
