// @ts-check
/*
 * 부서 관리 (sys-dept) — 시스템관리 2단계 (docs/36 9.1). 트리(hb.tree, 사용 중지 포함·흐리게) → 오른쪽 부서 폼.
 * 상위 선택지는 자기·자기 하위를 뺀 사용 중 부서. 판정(순환·10단·사용 중 하위)은 서버·DB 트리거.
 * 부서장 선택지 = 사용 중 회사 사용자 — 지금 장이 사용 중지면 그 사람을 "(사용 중지)" 로 남겨 다른 칸 저장이 장을 지우지 않게 한다.
 */
/**
 * @typedef {{deptId: number, parentId: number | null, lvl: number, deptCd: string, deptNm: string, path: string,
 *   sortNo: number, useYn: string, memberCnt: number, dim: boolean, rowVer: number, headUserId: number | null,
 *   headNm: string | null, headLoginId: string | null, headActive: boolean, [field: string]: any}} SysDeptNode
 */
/** @typedef {{userId: number, loginId: string, userNm: string}} SysDeptUser */
(function () {
    'use strict';

    var API = '/api/admin/depts';
    var ACTIONS = (document.body.getAttribute('data-actions') || '').split(',');
    var CAN_SAVE = ACTIONS.indexOf('SAVE') >= 0;
    var msg = /** @type {HTMLElement} */ (document.getElementById('msg'));
    var form = /** @type {HTMLFormElement} */ (document.getElementById('deptForm'));
    var formMode = /** @type {HTMLElement} */ (document.getElementById('formMode'));
    var summary = /** @type {HTMLElement} */ (document.getElementById('summary'));
    /** @type {HB.Tree} */
    var tree;
    /** @type {HB.NumberControl} */
    var sortNo;
    var ready = false;
    /** @type {SysDeptNode[]} */
    var nodes = [];
    /** 고치는 부서 또는 null(새로) @type {SysDeptNode | null} */
    var current = null;
    /** 부서장 선택지 @type {SysDeptUser[]} */
    var users = [];

    /** @param {string | null | undefined} text @param {boolean} [error] */
    function notify(text, error) {
        msg.textContent = text || '';
        msg.classList.toggle('hb-msg-error', !!error);
    }

    /** @param {string} n */
    function field(n) {
        return /** @type {HTMLInputElement | HTMLSelectElement} */ (form.querySelector('[data-field="' + n + '"]'));
    }

    /** @param {unknown} id */
    function byId(id) { return nodes.filter(function (n) { return n.deptId === id; })[0] || null; }

    /** 이 부서와 그 하위 id — 상위 선택지에서 뺀다 @param {SysDeptNode | null} node */
    function subtreeIds(node) {
        /** @type {Record<number, boolean>} */
        var ids = {};
        if (!node) { return ids; }
        var start = nodes.indexOf(node);
        ids[node.deptId] = true;
        for (var i = start + 1; i < nodes.length && nodes[i].lvl > node.lvl; i++) { ids[nodes[i].deptId] = true; }
        return ids;
    }

    /** @param {number | null} value @param {Record<number, boolean>} exclude */
    function fillParent(value, exclude) {
        var s = field('parentId');
        s.textContent = '';
        var top = hb.el('option', null, hb.t('js.sysDept.top'));
        top.value = '';
        s.appendChild(top);
        nodes.forEach(function (n) {
            // 사용 중 부서만 새 상위로 — 지금 상위가 사용 중지면 그대로 남긴다
            if (exclude[n.deptId] || (n.useYn !== 'Y' && n.deptId !== value)) { return; }
            var o = hb.el('option', null, '  '.repeat(n.lvl - 1) + n.deptNm + ' (' + n.deptCd + ')'
                + (n.useYn === 'Y' ? '' : ' (' + hb.t('js.yn.N') + ')'));
            o.value = String(n.deptId);
            s.appendChild(o);
        });
        s.value = value === null || value === undefined ? '' : String(value);
        s.disabled = !CAN_SAVE;
    }

    /** @param {SysDeptNode | null} node */
    function fillHead(node) {
        var s = field('headUserId');
        s.textContent = '';
        var none = hb.el('option', null, hb.t('js.sysDept.noHead'));
        none.value = '';
        s.appendChild(none);
        var cur = node && node.headUserId ? node.headUserId : null;
        var listed = false;
        users.forEach(function (u) {
            var o = hb.el('option', null, u.userNm + ' (' + u.loginId + ')');
            o.value = String(u.userId);
            if (u.userId === cur) { listed = true; }
            s.appendChild(o);
        });
        if (node && cur && !listed) {
            // 사용 중지된 지금 장 — 고르기 목록엔 없지만 그대로 두고 저장할 수 있게
            var o = hb.el('option', null, (node.headNm || '') + ' (' + (node.headLoginId || '') + ') (' + hb.t('js.yn.N') + ')');
            o.value = String(cur);
            s.appendChild(o);
        }
        s.value = cur ? String(cur) : '';
        s.disabled = !CAN_SAVE;
    }

    /** @param {SysDeptNode | null} node @param {SysDeptNode | null} [parent] */
    function show(node, parent) {
        current = node;
        hb.showFieldErrors(form, []);
        fillParent(node ? node.parentId : (parent ? parent.deptId : null), node ? subtreeIds(node) : {});
        field('deptCd').value = node ? node.deptCd : '';
        /** @type {HTMLInputElement} */ (field('deptCd')).readOnly = !!node || !CAN_SAVE;
        field('deptNm').value = node ? node.deptNm : '';
        /** @type {HTMLInputElement} */ (field('deptNm')).readOnly = !CAN_SAVE;
        sortNo.setValue(node ? node.sortNo : 0);
        field('useYn').value = node ? node.useYn : 'Y';
        field('useYn').disabled = !CAN_SAVE;
        fillHead(node);
        formMode.textContent = node ? hb.t('js.sysDept.editing', node.path, node.memberCnt)
            : hb.t('js.sysDept.creating', parent ? parent.path : hb.t('js.sysDept.top'));
        hb.dirty.clear();
    }

    /** @param {number} [selectId] */
    function load(selectId) {
        notify('');
        return hb.latest('depts', hb.api(API)).then(function (res) {
            nodes = res.list || [];
            tree.setRows(nodes);
            summary.textContent = hb.t('js.sysDept.summary', nodes.length,
                nodes.reduce(function (sum, n) { return sum + n.memberCnt; }, 0));
            if (selectId && tree.selectWhere(function (n) { return n.deptId === selectId; })) { return; }
            show(null, null);
        }, function (err) { if (!err.stale) { notify(err.message, true); } });
    }

    /** @param {string} id @param {(b: HTMLButtonElement) => void} fn */
    function bind(id, fn) {
        var b = /** @type {HTMLButtonElement | null} */ (document.getElementById(id));
        if (b) {
            var btn = b;
            btn.addEventListener('click', function () { if (ready) { fn(btn); } });
        }
    }

    bind('addRootBtn', function () {
        sysUi.leaveOk().then(function (ok) {
            if (!ok) { return; }
            show(null, null);
            hb.page.openDetail();
            field('deptCd').focus();
        });
    });
    bind('addChildBtn', function () {
        var sel = tree.selected();
        if (!sel) { notify(hb.t('js.sysDept.pickFirst'), true); return; }
        var parent = sel;
        sysUi.leaveOk().then(function (ok) {
            if (!ok) { return; }
            show(null, byId(parent.deptId));
            hb.page.openDetail();
            field('deptCd').focus();
        });
    });
    bind('saveBtn', function (btn) {
        var editId = current ? current.deptId : null;   // 저장 중 다른 부서를 골라도 이 부서로 마무리
        var p = field('parentId').value;
        var h = field('headUserId').value;
        var body = {parentId: p ? Number(p) : null, deptCd: field('deptCd').value, deptNm: field('deptNm').value,
            sortNo: sortNo.numberValue(), useYn: field('useYn').value, rowVer: current ? current.rowVer : null,
            headUserId: h ? Number(h) : null};
        hb.busy(btn, function () {
            return editId !== null ? hb.api(API + '/' + editId, {method: 'PUT', body: body}) : hb.api(API, {method: 'POST', body: body});
        }).then(function (res) {
            if (!res) { return; }
            hb.dirty.clear();
            return load(editId !== null ? editId : res.data).then(function () { notify(hb.t('js.common.saved')); });
        }, function (err) {
            notify(err.message, true);
            hb.showFieldErrors(form, err.errors);
        });
    });
    bind('expandBtn', function () { tree.expandAll(); });
    bind('collapseBtn', function () { tree.collapseTo(1); });

    hb.dirty.watch(form);
    hb.loadMessages().then(function () {
        hb.page.init();
        sortNo = hb.number(/** @type {HTMLInputElement} */ (field('sortNo')));
        tree = hb.tree('#tree', {columnsUrl: API + '/columns', treeField: 'deptNm', dimField: 'dim',
            emptyText: hb.t('js.sysDept.empty'),
            beforeSelect: sysUi.beforeSelect,   // 미저장이면 화면 안 대화상자(취소하면 선택이 옮겨지지 않는다)
            onSelect: function (n) {
                show(byId(n.deptId));
                hb.page.openDetail();
            }});
        sysUi.treeFind('quickFind', tree, ['deptCd', 'deptNm']);
        return Promise.all([hb.loadSession(), tree.ready,
            hb.api(API + '/head-candidates').then(function (res) { users = res.data || []; })]);
    }).then(function () {
        ready = true;
        return load();
    }).then(null, function (err) { notify(err && err.message, true); });
})();
