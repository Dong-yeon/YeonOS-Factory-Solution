// @ts-check
/*
 * 사용자 관리 (sys-user) — 시스템관리 1단계 (docs/36). 표준 화면(cust-mgt) 모양: 조회 패널 + hb.grid + 오른쪽 상세.
 * 비밀번호는 입력받지 않는다 — 추가·초기화 응답의 임시 비밀번호를 한 번만 보여 준다(새로고침·다른 행을 고르면 사라짐).
 * 권한그룹 체크는 내가 줄 수 없는 그룹·내 계정이면 잠근다(편의). 판정은 서버(부여 상한·자기 변경·마지막 관리자).
 * 협력사 포털 사용자(docs/42 5장): 새 사용자에서만 유형·소속 협력사를 고른다 — 그 뒤엔 잠김. 협력사면 부서·권한그룹 칸을 숨기고 비워 보낸다.
 */
/**
 * @typedef {{userId: number, loginId: string, userNm: string, email?: string | null, localeCd: string, lockState: string,
 *   lockedUntil?: string | null, pwdChangeReqYn: string, lastLoginAt?: string | null, useYn: string, rowVer: number,
 *   deptId?: number | null, deptNm?: string | null, mfaEnrolled?: boolean, activeDelegatedCnt?: number,
 *   userType?: string, custId?: number | null, custCd?: string | null, custNm?: string | null,
 *   [field: string]: any}} SysUser
 * @typedef {{custId: number, custCd: string, custNm: string, custType: string}} SysUserSupplier
 * @typedef {{authGrpCd: string, authGrpNm: string, adminYn: string, useYn: string, grantable: boolean}} SysUserGroup
 * @typedef {{user: SysUser, authGrpCds: string[], groups: SysUserGroup[], self: boolean, manageable: boolean}} SysUserDetail
 * @typedef {{userId: number, loginId: string, tempPassword: string, rowVer: number}} SysUserTemp
 */
(function () {
    'use strict';

    var PROG = document.body.getAttribute('data-prog') || '';
    var ACTIONS = (document.body.getAttribute('data-actions') || '').split(',');
    var CAN_SAVE = ACTIONS.indexOf('SAVE') >= 0;
    var API = '/api/admin/users';
    var msg = /** @type {HTMLElement} */ (document.getElementById('msg'));
    var search = /** @type {HTMLFormElement} */ (document.getElementById('search'));
    var form = /** @type {HTMLFormElement} */ (document.getElementById('userForm'));
    var formMode = /** @type {HTMLElement} */ (document.getElementById('formMode'));
    var groupBox = /** @type {HTMLElement} */ (document.getElementById('groups'));
    var statusOut = /** @type {HTMLElement} */ (document.getElementById('status'));
    var tempBox = /** @type {HTMLElement} */ (document.getElementById('tempBox'));
    var custRow = /** @type {HTMLElement} */ (document.getElementById('custRow'));
    var custKeyword = /** @type {HTMLInputElement} */ (document.getElementById('custKeyword'));
    /** 협력사 소속 선택지(사용 중 구매·겸용) — 추가 권한(SAVE)일 때만 읽는다 @type {SysUserSupplier[]} */
    var suppliers = [];
    /** 선택한 사용자 @type {SysUserDetail | null} */
    var current = null;
    /** 새 사용자용 그룹 선택지 @type {SysUserGroup[]} */
    var newChoices = [];
    /** @type {Record<string, string>} */
    var lockNames = {};
    /** 사용 중 부서 선택지 @type {{deptId: number, deptCd: string, deptNm: string, path: string}[]} */
    var depts = [];
    /** @type {HB.Grid | undefined} */
    var grid;
    /** @type {HB.SearchPanel} */
    var sp;

    /** @param {string | null | undefined} text @param {boolean} [error] */
    function notify(text, error) {
        msg.textContent = text || '';
        msg.classList.toggle('hb-msg-error', !!error);
    }

    /** @param {string} name */
    function field(name) {
        return /** @type {HTMLInputElement | HTMLSelectElement} */ (form.querySelector('[data-field="' + name + '"]'));
    }

    // ---------- 임시 비밀번호 (한 번만) ----------
    /** @param {SysUserTemp | null} t */
    function showTemp(t) {
        var pwd = /** @type {HTMLElement} */ (document.getElementById('tempPwd'));
        /** @type {HTMLElement} */ (document.getElementById('tempLogin')).textContent = t ? t.loginId : '';
        pwd.textContent = t ? t.tempPassword : '';
        tempBox.hidden = !t;
    }

    // ---------- 권한그룹 체크 목록 ----------
    /** @param {SysUserGroup[]} groups @param {string[]} checked @param {boolean} locked 내 계정·관리할 수 없는 사용자 */
    function renderGroups(groups, checked, locked) {
        groupBox.textContent = '';
        if (!groups.length) {
            groupBox.appendChild(hb.el('p', 'hb-muted', hb.t('js.sysUser.noGroups')));
        }
        groups.forEach(function (g) {
            var on = checked.indexOf(g.authGrpCd) >= 0;
            if (g.useYn !== 'Y' && !on) { return; }   // 사용 중지 그룹은 이미 속한 경우만 보인다(빼기용)
            var label = hb.el('label', 'hb-check');
            var box = hb.el('input');
            box.type = 'checkbox';
            box.value = g.authGrpCd;
            box.checked = on;
            box.disabled = !CAN_SAVE || locked || !g.grantable;
            box.addEventListener('change', function () { hb.dirty.mark(); });
            label.appendChild(box);
            var text = g.authGrpNm + ' (' + g.authGrpCd + ')';
            if (g.adminYn === 'Y') { text += ' · ' + hb.t('js.sysUser.adminGroup'); }
            if (g.useYn !== 'Y') { text += ' · ' + hb.t('js.yn.N'); }
            if (!g.grantable) { text += ' · ' + hb.t('js.sysUser.notGrantable'); }
            label.appendChild(hb.el('span', null, text));
            groupBox.appendChild(label);
        });
    }

    function checkedGroups() {
        /** @type {string[]} */
        var out = [];
        groupBox.querySelectorAll('input[type="checkbox"]').forEach(function (b) {
            var box = /** @type {HTMLInputElement} */ (b);
            if (box.checked) { out.push(box.value); }
        });
        return out;
    }

    // ---------- 상세 ----------
    /** @param {SysUser} u */
    function statusText(u) {
        var parts = [lockNames[u.lockState] || u.lockState];
        if (u.lockState === 'FAIL' && u.lockedUntil) { parts.push(hb.t('js.sysUser.until', hb.fmt.datetime(u.lockedUntil))); }
        if (u.pwdChangeReqYn === 'Y') { parts.push(hb.t('js.sysUser.pwdChangePending')); }
        parts.push(u.mfaEnrolled ? hb.t('js.sysUser.mfaOn') : hb.t('js.sysUser.mfaOff'));
        parts.push(hb.t('js.sysUser.lastLogin', u.lastLoginAt ? hb.fmt.datetime(u.lastLoginAt) : '-'));
        return parts.join(' · ');
    }

    /** @param {string} id @param {boolean} visible */
    function toggle(id, visible) {
        var b = document.getElementById(id);
        if (b) { b.hidden = !visible; }
    }

    /** 부서 선택지 — 지금 값이 사용 중지 부서면 "(사용 중지)" 로 남긴다(저장 때 조용히 지워지지 않게)
     * @param {number | null | undefined} value @param {string | null | undefined} name */
    function fillDept(value, name) {
        var s = field('deptId');
        s.textContent = '';
        var none = hb.el('option', null, hb.t('js.sysUser.noDept'));
        none.value = '';
        s.appendChild(none);
        var found = false;
        depts.forEach(function (d) {
            var o = hb.el('option', null, d.deptNm + ' (' + d.path + ')');
            o.value = String(d.deptId);
            if (d.deptId === value) { found = true; }
            s.appendChild(o);
        });
        if (value && !found) {
            var keep = hb.el('option', null, (name || String(value)) + ' (' + hb.t('js.yn.N') + ')');
            keep.value = String(value);
            s.appendChild(keep);
        }
        s.value = value ? String(value) : '';
    }

    /** 소속 협력사 선택지 — 지금 값이 목록에 없으면(검색어 밖·사용 중지) 그 이름으로 남긴다
     * @param {number | null | undefined} value @param {string | null | undefined} name */
    function fillCust(value, name) {
        var s = field('custId');
        s.textContent = '';
        var none = hb.el('option', null, hb.t('js.sysUser.pickCust'));
        none.value = '';
        s.appendChild(none);
        var found = false;
        suppliers.forEach(function (c) {
            var o = hb.el('option', null, c.custNm + ' (' + c.custCd + ')');
            o.value = String(c.custId);
            if (c.custId === value) { found = true; }
            s.appendChild(o);
        });
        if (value && !found) {
            var keep = hb.el('option', null, name || String(value));
            keep.value = String(value);
            s.appendChild(keep);
        }
        s.value = value ? String(value) : '';
    }

    /** 유형에 맞게 칸을 보이고 숨긴다. fixed = 이미 만든 사용자(유형·소속 잠김)
     * @param {string} type @param {boolean} fixed */
    function applyType(type, fixed) {
        var supplier = type === 'SUPPLIER';
        custRow.hidden = !supplier;
        custKeyword.hidden = fixed || !CAN_SAVE;
        toggle('deptRow', !supplier);
        toggle('groupsSec', !supplier);
        toggle('supplierNote', supplier);
        field('userType').disabled = fixed || !CAN_SAVE;
        field('custId').disabled = fixed || !CAN_SAVE;
    }

    /** @param {SysUserDetail | null} d */
    function showDetail(d) {
        current = d;
        hb.showFieldErrors(form, []);
        hb.showFieldErrors(groupBox.parentElement || groupBox, []);
        var u = d ? d.user : null;
        field('loginId').value = u ? u.loginId : '';
        /** @type {HTMLInputElement} */ (field('loginId')).readOnly = !!u || !CAN_SAVE;
        field('userNm').value = u ? u.userNm : '';
        field('email').value = u && u.email ? u.email : '';
        fillDept(u ? u.deptId : null, u ? u.deptNm : null);
        field('localeCd').value = u ? u.localeCd : 'ko';
        field('useYn').value = u ? u.useYn : 'Y';
        field('userType').value = u && u.userType ? u.userType : 'INTERNAL';
        custKeyword.value = '';
        fillCust(u ? u.custId : null, u && u.custNm ? u.custNm + ' (' + (u.custCd || '') + ')' : null);
        applyType(field('userType').value, !!u);
        var editable = CAN_SAVE && (!d || d.manageable);
        ['userNm', 'email'].forEach(function (n) { /** @type {HTMLInputElement} */ (field(n)).readOnly = !editable; });
        field('localeCd').disabled = !editable;
        field('deptId').disabled = !editable;
        field('useYn').disabled = !editable || !!(d && d.self);   // 자기 사용 중지는 서버도 거부
        statusOut.textContent = '';
        if (u) { statusOut.appendChild(sysUi.badge('lock', u.lockState, statusText(u))); }
        formMode.textContent = !d ? hb.t('js.sysUser.creating')
            : (d.self ? hb.t('js.sysUser.editingSelf', u ? u.loginId : '')
                : (d.manageable ? hb.t('js.sysUser.editing', u ? u.loginId : '') : hb.t('js.sysUser.notManageable', u ? u.loginId : '')));
        renderGroups(d ? d.groups : newChoices, d ? d.authGrpCds : [], !!d && (d.self || !d.manageable));
        // 동작 버튼: 대상·상태에 맞는 것만 (자기 자신·나보다 큰 사용자는 숨김)
        var acting = !!d && !d.self && d.manageable;
        toggle('resetBtn', acting);
        toggle('resetMfaBtn', acting && !!u && !!u.mfaEnrolled);
        toggle('lockBtn', acting && !!u && u.lockState !== 'MANUAL');
        toggle('unlockBtn', acting && !!u && u.lockState !== 'NONE');
        toggle('saveBtn', !d || d.manageable);
        hb.dirty.clear();
    }

    /** @param {number} id @param {boolean} [keepMsg] */
    function loadDetail(id, keepMsg) {
        if (!keepMsg) { notify(''); }
        showTemp(null);
        return hb.latest('detail', hb.api(API + '/' + id)).then(function (res) { showDetail(res.data); },
            function (err) { if (!err.stale) { notify(err.message, true); } });
    }

    function reloadGrid(/** @type {unknown} */ keepId) {
        return grid ? grid.reload(false, keepId) : Promise.resolve();
    }


    /** @param {string} id @param {(b: HTMLButtonElement) => void} fn */
    function bind(id, fn) {
        var b = /** @type {HTMLButtonElement | null} */ (document.getElementById(id));
        if (b) {
            var btn = b;
            btn.addEventListener('click', function () { fn(btn); });
        }
    }

    function newUser() {
        sysUi.leaveOk().then(function (ok) {
            if (!ok) { return; }
            showTemp(null);
            showDetail(null);
            hb.page.openDetail();
            field('loginId').focus();
        });
    }
    bind('newBtn', newUser);
    // 엑셀 올리기 (docs/36 Q26) — 비밀번호 칸 없음: 새 사용자는 넘겨줄 때 "비밀번호 초기화" 로 임시 비밀번호를 받는다
    bind('importBtn', function () {
        sysUi.leaveOk().then(function (ok) {
            if (ok) { hb.importDialog({templateCd: 'SYS_USER', onDone: function () { if (grid) { grid.reload(true); } }}); }
        });
    });
    bind('newBtnList', newUser);

    bind('saveBtn', function (btn) {
        var editId = current ? current.user.userId : null;   // 저장 중 다른 행을 골라도 이 사용자로 마무리
        // 사용 중지하면 이 사람이 대리인 진행 중 위임이 끝난다(서버 트리거) — 미리 알린다 (docs/36 16.6)
        var cnt = current && current.user.useYn === 'Y' && field('useYn').value === 'N' ? (current.user.activeDelegatedCnt || 0) : 0;
        (cnt > 0 ? sysUi.ask('js.sysUser.dlg.delegOff', hb.t('js.sysUser.confirmDelegOff', String(cnt)), 'js.sysUser.dlg.delegOffOk')
            : Promise.resolve(true)).then(function (ok) { if (ok) { save(btn, editId); } });
    });

    /** @param {HTMLButtonElement} btn @param {number | null} editId */
    function save(btn, editId) {
        var supplier = field('userType').value === 'SUPPLIER';
        var body = {loginId: field('loginId').value, userNm: field('userNm').value, email: field('email').value,
            localeCd: field('localeCd').value, useYn: field('useYn').value, authGrpCds: supplier ? [] : checkedGroups(),
            deptId: !supplier && field('deptId').value ? Number(field('deptId').value) : null,
            userType: field('userType').value, custId: supplier && field('custId').value ? Number(field('custId').value) : null,
            rowVer: current ? current.user.rowVer : null};
        hb.busy(btn, function () {
            return editId !== null ? hb.api(API + '/' + editId, {method: 'PUT', body: body})
                : hb.api(API, {method: 'POST', body: body});
        }).then(function (res) {
            if (!res) { return; }
            hb.dirty.clear();
            /** @type {SysUserTemp | null} */
            var temp = editId === null ? res.data : null;
            var id = editId !== null ? editId : (temp ? temp.userId : null);
            return reloadGrid(id).then(function () {
                if (id !== null && (!grid || !grid.selected())) { return loadDetail(id, true); }
            }).then(function () {
                if (temp) { showTemp(temp); }
                notify(temp ? hb.t('js.sysUser.created') : hb.t('js.common.saved'));
            });
        }, function (err) {
            notify(err.message, true);
            hb.showFieldErrors(form, err.errors);
            hb.showFieldErrors(groupBox.parentElement || groupBox, err.errors);
        });
    }

    bind('resetBtn', function (btn) {
        if (!current) { return; }
        var u = current.user;
        sysUi.ask('js.sysUser.dlg.reset', hb.t('js.sysUser.confirmReset', u.loginId), 'js.sysUser.dlg.resetOk', true).then(function (ok) {
            if (ok) { resetPassword(btn, u); }
        });
    });

    /** @param {HTMLButtonElement} btn @param {SysUser} u */
    function resetPassword(btn, u) {
        hb.busy(btn, function () {
            return hb.api(API + '/' + u.userId + '/reset-password', {method: 'POST', body: {rowVer: u.rowVer}});
        }).then(function (res) {
            if (!res) { return; }
            /** @type {SysUserTemp} */
            var temp = res.data;
            return reloadGrid(u.userId).then(function () { return loadDetail(u.userId, true); }).then(function () {
                showTemp(temp);
                notify(hb.t('js.sysUser.resetDone', u.loginId));
            });
        }, function (err) { notify(err.message, true); });
    }

    bind('resetMfaBtn', function (btn) {
        if (!current) { return; }
        var u = current.user;
        sysUi.ask('js.sysUser.dlg.resetMfa', hb.t('js.sysUser.confirmResetMfa', u.loginId), 'js.sysUser.dlg.resetMfaOk', true).then(function (ok) {
            if (ok) { resetMfa(btn, u); }
        });
    });

    /** @param {HTMLButtonElement} btn @param {SysUser} u */
    function resetMfa(btn, u) {
        hb.busy(btn, function () {
            return hb.api(API + '/' + u.userId + '/reset-mfa', {method: 'POST', body: {rowVer: u.rowVer}});
        }).then(function (res) {
            if (!res) { return; }
            return reloadGrid(u.userId).then(function () { return loadDetail(u.userId, true); }).then(function () {
                notify(hb.t('js.sysUser.mfaResetDone', u.loginId));
            });
        }, function (err) { notify(err.message, true); });
    }

    /** @param {'lock' | 'unlock'} what @param {HTMLButtonElement} btn */
    function lockAction(what, btn) {
        if (!current) { return; }
        var u = current.user;
        var asked = what === 'lock'
            ? sysUi.ask('js.sysUser.dlg.lock', hb.t('js.sysUser.confirmLock', u.loginId), 'js.sysUser.dlg.lockOk', true)
            : sysUi.ask('js.sysUser.dlg.unlock', hb.t('js.sysUser.confirmUnlock', u.loginId), 'js.sysUser.dlg.unlockOk');
        asked.then(function (ok) { if (ok) { lockRun(what, btn, u); } });
    }

    /** @param {'lock' | 'unlock'} what @param {HTMLButtonElement} btn @param {SysUser} u */
    function lockRun(what, btn, u) {
        hb.busy(btn, function () {
            return hb.api(API + '/' + u.userId + '/' + what, {method: 'POST', body: {rowVer: u.rowVer}});
        }).then(function (res) {
            if (!res) { return; }
            return reloadGrid(u.userId).then(function () { return loadDetail(u.userId, true); }).then(function () {
                notify(what === 'lock' ? hb.t('js.sysUser.locked', u.loginId) : hb.t('js.sysUser.unlocked', u.loginId));
            });
        }, function (err) { notify(err.message, true); });
    }
    bind('lockBtn', function (btn) { lockAction('lock', btn); });
    bind('unlockBtn', function (btn) { lockAction('unlock', btn); });

    bind('copyTempBtn', function () {
        var text = /** @type {HTMLElement} */ (document.getElementById('tempPwd')).textContent || '';
        if (navigator.clipboard) {
            navigator.clipboard.writeText(text).then(function () { notify(hb.t('js.sysUser.copied')); },
                function () { notify(hb.t('js.sysUser.copyFailed'), true); });
        } else {
            notify(hb.t('js.sysUser.copyFailed'), true);
        }
    });
    bind('closeTempBtn', function () { showTemp(null); });

    // ---------- 협력사 사용자 (새 사용자만) ----------
    field('userType').addEventListener('change', function () { applyType(field('userType').value, !!current); });
    /** @param {string} keyword */
    function loadSuppliers(keyword) {
        if (!CAN_SAVE) { return Promise.resolve(); }
        return hb.latest('suppliers', hb.api(API + '/suppliers' + (keyword ? '?keyword=' + encodeURIComponent(keyword) : '')))
            .then(function (res) { suppliers = res.list || []; }, function (err) { if (!err.stale) { notify(err.message, true); } });
    }
    custKeyword.addEventListener('change', function () {
        var sel = /** @type {HTMLSelectElement} */ (field('custId'));
        var keep = sel.value;
        var keepName = keep && sel.selectedOptions[0] ? sel.selectedOptions[0].textContent : null;   // 새 검색 결과 밖이어도 고른 것은 남긴다
        loadSuppliers(custKeyword.value.trim()).then(function () { fillCust(keep ? Number(keep) : null, keepName); });
    });

    // ---------- 시작 ----------
    hb.dirty.watch(form);
    search.addEventListener('submit', function (e) { e.preventDefault(); });
    hb.loadMessages().then(function () {
        hb.page.init();
        return Promise.all([hb.loadSession(), hb.codes('USER_LOCK_STATE'), hb.api(API + '/groups'), hb.api('/api/platform/depts'),
            loadSuppliers('')]);
    }).then(function (r) {
        lockNames = r[1];
        newChoices = r[2].list || [];
        depts = r[3].list || [];
        var dsel = /** @type {HTMLSelectElement} */ (document.getElementById('deptSel'));
        depts.forEach(function (d) {
            var o = hb.el('option', null, d.deptNm + ' (' + d.path + ')');
            o.value = String(d.deptId);
            dsel.appendChild(o);
        });
        var sel = /** @type {HTMLSelectElement} */ (document.getElementById('grpSel'));
        newChoices.forEach(function (g) {
            var o = hb.el('option', null, g.authGrpNm + ' (' + g.authGrpCd + ')');
            o.value = g.authGrpCd;
            sel.appendChild(o);
        });
        sp = hb.searchPanel(search, {
            onSearch: function () {
                if (!grid) { return null; }
                var g = grid;
                return sysUi.leaveOk().then(function (ok) { return ok ? g.reload(true) : null; });
            }
        });
        showDetail(null);
        return sp.ready;
    }).then(function () {
        grid = hb.grid('#userGrid', {
            progCd: PROG, id: 'main', key: 'userId',
            url: API, columnsUrl: API + '/columns', exportUrl: API + '/export', auditTable: 'sys_user',
            actions: ACTIONS, params: function () { return sp.values(); },
            filters: {lockState: 'lockState', useYn: 'useYn', userType: 'userType'},
            format: {pwdChangeReqYn: function (v) { return v === 'Y' ? hb.t('js.sysUser.pending') : ''; },
                custNm: function (v, row) { return v ? v + ' (' + row.custCd + ')' : ''; }},
            onFilter: function (param, value) { sp.setValue(param, value); },
            status: {lockState: sysUi.TONES.lock, useYn: sysUi.TONES.use},
            rowClass: function (row) { return row.useYn === 'N' ? 'hb-tree-dim' : null; },
            beforeSelect: sysUi.beforeSelect,
            onSelect: function (row) { loadDetail(row.userId); hb.page.openDetail(); },
            notify: notify
        });
        return grid.ready;
    }).then(null, function (err) { notify(err && err.message, true); });
})();
