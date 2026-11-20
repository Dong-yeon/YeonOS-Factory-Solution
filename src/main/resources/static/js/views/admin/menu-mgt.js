// @ts-check
/*
 * 메뉴 관리 (sys-menu) — 시스템관리 1단계 (docs/36). 트리(hb.tree, 사용 안 함 포함·흐리게) → 오른쪽 메뉴 폼.
 * 폴더·화면 구분은 추가 때 정해지고 바뀌지 않는다. 상위 선택지는 폴더만(자기·자기 하위 제외) — 판정은 서버·DB 트리거.
 */
/**
 * @typedef {{menuId: number, parentId: number | null, lvl: number, name: string, menuNm?: string | null,
 *   menuNmKey?: string | null, progCd?: string | null, sortNo: number, useYn: string, kind: string, dim: boolean,
 *   rowVer: number, [field: string]: any}} SysMenuNode
 */
(function () {
    'use strict';

    var API = '/api/admin/menus';
    var ACTIONS = (document.body.getAttribute('data-actions') || '').split(',');
    var CAN_SAVE = ACTIONS.indexOf('SAVE') >= 0;
    var msg = /** @type {HTMLElement} */ (document.getElementById('msg'));
    var form = /** @type {HTMLFormElement} */ (document.getElementById('menuForm'));
    var formMode = /** @type {HTMLElement} */ (document.getElementById('formMode'));
    var summary = /** @type {HTMLElement} */ (document.getElementById('summary'));
    var progRow = /** @type {HTMLElement} */ (document.getElementById('progRow'));
    /** @type {HB.Tree} */
    var tree;
    /** @type {HB.NumberControl} */
    var sortNo;
    var ready = false;
    /** @type {SysMenuNode[]} */
    var nodes = [];
    /** @type {{progCd: string, name: string, moduleCd: string}[]} */
    var progs = [];
    /** 고치는 메뉴 또는 null(새로) @type {SysMenuNode | null} */
    var current = null;
    /** 새로 만들 종류 @type {'FOLDER' | 'PROG'} */
    var newKind = 'FOLDER';

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
    function byId(id) { return nodes.filter(function (n) { return n.menuId === id; })[0] || null; }

    /** 이 메뉴와 그 하위 id — 이동 대상에서 뺀다(자기 하위 아래로는 순환) @param {SysMenuNode | null} node */
    function subtreeIds(node) {
        /** @type {Record<number, boolean>} */
        var ids = {};
        if (!node) { return ids; }
        var start = nodes.indexOf(node);
        ids[node.menuId] = true;
        for (var i = start + 1; i < nodes.length && nodes[i].lvl > node.lvl; i++) { ids[nodes[i].menuId] = true; }
        return ids;
    }

    /** @param {number | null} value @param {Record<number, boolean>} exclude */
    function fillParent(value, exclude) {
        var s = field('parentId');
        s.textContent = '';
        var top = hb.el('option', null, hb.t('js.sysMenu.top'));
        top.value = '';
        s.appendChild(top);
        nodes.forEach(function (n) {
            if (n.kind !== 'FOLDER' || exclude[n.menuId]) { return; }
            var o = hb.el('option', null, '  '.repeat(n.lvl - 1) + n.name + (n.useYn === 'Y' ? '' : ' (' + hb.t('js.yn.N') + ')'));
            o.value = String(n.menuId);
            s.appendChild(o);
        });
        s.value = value === null || value === undefined ? '' : String(value);
        s.disabled = !CAN_SAVE;
    }

    /** 화면 선택지 — 지금 값이 목록 밖(사용 중지 화면)이면 그 값을 남긴다(저장 때 조용히 지워지지 않게) @param {string | null | undefined} value */
    function fillProg(value) {
        var s = field('progCd');
        s.textContent = '';
        var found = false;
        progs.forEach(function (p) {
            var o = hb.el('option', null, p.name + ' (' + p.progCd + ')');
            o.value = p.progCd;
            if (p.progCd === value) { found = true; }
            s.appendChild(o);
        });
        if (value && !found) {
            var keep = hb.el('option', null, value + ' (' + hb.t('js.yn.N') + ')');
            keep.value = value;
            s.insertBefore(keep, s.firstChild);
        }
        s.value = value || (progs[0] ? progs[0].progCd : '');
        s.disabled = !CAN_SAVE;
    }

    /** @param {SysMenuNode | null} node @param {SysMenuNode | null} [parent] */
    function show(node, parent) {
        current = node;
        hb.showFieldErrors(form, []);
        var kind = node ? node.kind : newKind;
        progRow.hidden = kind !== 'PROG';
        if (node) {
            fillParent(node.parentId, subtreeIds(node));
            fillProg(node.progCd);
            field('menuNm').value = node.menuNm || '';
            /** @type {HTMLInputElement} */ (field('menuNm')).placeholder = node.menuNm ? '' : node.name;
            sortNo.setValue(node.sortNo);
            field('useYn').value = node.useYn;
            formMode.textContent = kind === 'PROG' ? hb.t('js.sysMenu.editingProg', node.name) : hb.t('js.sysMenu.editingFolder', node.name);
        } else {
            fillParent(parent ? parent.menuId : null, {});
            fillProg(null);
            field('menuNm').value = '';
            /** @type {HTMLInputElement} */ (field('menuNm')).placeholder = kind === 'PROG' ? hb.t('js.sysMenu.namePlaceholder') : '';
            sortNo.setValue(0);
            field('useYn').value = 'Y';
            formMode.textContent = kind === 'PROG' ? hb.t('js.sysMenu.creatingProg') : hb.t('js.sysMenu.creatingFolder');
        }
        /** @type {HTMLInputElement} */ (field('menuNm')).readOnly = !CAN_SAVE;
        field('useYn').disabled = !CAN_SAVE;
        hb.dirty.clear();
    }

    /** @param {number} [selectId] */
    function load(selectId) {
        notify('');
        return hb.latest('menus', hb.api(API)).then(function (res) {
            nodes = res.list || [];
            tree.setRows(nodes);
            var folders = nodes.filter(function (n) { return n.kind === 'FOLDER'; }).length;
            summary.textContent = hb.t('js.sysMenu.summary', folders, nodes.length - folders);
            if (selectId && tree.selectWhere(function (n) { return n.menuId === selectId; })) { return; }
            show(null, null);
        }, function (err) { if (!err.stale) { notify(err.message, true); } });
    }

    /** 선택한 메뉴가 폴더면 그 폴더, 화면이면 그 상위 폴더 */
    function targetFolder() {
        var sel = tree.selected();
        var n = sel ? byId(sel.menuId) : null;
        if (!n) { return null; }
        return n.kind === 'FOLDER' ? n : byId(n.parentId);
    }

    /** @param {string} id @param {(b: HTMLButtonElement) => void} fn */
    function bind(id, fn) {
        var b = /** @type {HTMLButtonElement | null} */ (document.getElementById(id));
        if (b) {
            var btn = b;
            btn.addEventListener('click', function () { if (ready) { fn(btn); } });
        }
    }

    bind('addFolderBtn', function () {
        sysUi.leaveOk().then(function (ok) {
            if (!ok) { return; }
            newKind = 'FOLDER';
            show(null, targetFolder());
            hb.page.openDetail();
            field('menuNm').focus();
        });
    });
    bind('addProgBtn', function () {
        sysUi.leaveOk().then(function (ok) {
            if (!ok) { return; }
            newKind = 'PROG';
            show(null, targetFolder());
            hb.page.openDetail();
            field('progCd').focus();
        });
    });
    bind('saveBtn', function (btn) {
        var editId = current ? current.menuId : null;   // 저장 중 다른 메뉴를 골라도 이 메뉴로 마무리
        var kind = current ? current.kind : newKind;
        var p = field('parentId').value;
        var body = {parentId: p ? Number(p) : null, menuNm: field('menuNm').value,
            progCd: kind === 'PROG' ? field('progCd').value : null, sortNo: sortNo.numberValue(),
            useYn: field('useYn').value, rowVer: current ? current.rowVer : null};
        hb.busy(btn, function () {
            return editId !== null ? hb.api(API + '/' + editId, {method: 'PUT', body: body}) : hb.api(API, {method: 'POST', body: body});
        }).then(function (res) {
            if (!res) { return; }
            hb.dirty.clear();
            return load(editId !== null ? editId : res.data).then(function () { notify(hb.t('js.sysMenu.saved')); });
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
        tree = hb.tree('#tree', {columnsUrl: API + '/columns', treeField: 'name', dimField: 'dim',
            emptyText: hb.t('js.sysMenu.empty'),
            beforeSelect: sysUi.beforeSelect,   // 미저장이면 화면 안 대화상자(취소하면 선택이 옮겨지지 않는다)
            onSelect: function (n) {
                show(byId(n.menuId));
                hb.page.openDetail();
            }});
        sysUi.treeFind('quickFind', tree, ['name', 'progCd']);
        return Promise.all([hb.loadSession(), tree.ready, hb.api(API + '/progs')]);
    }).then(function (r) {
        progs = r[2].list || [];
        ready = true;
        return load();
    }).then(null, function (err) { notify(err && err.message, true); });
})();
