// @ts-check
/*
 * 장비 계층·창고 (mst-equip-hier) — P1 4a. 공장 선택 → 트리(hb.tree) → 오른쪽 노드 폼.
 * 상위·유형 선택지는 부모 규칙에 맞는 것만 보여 준다(편의). 판정은 서버·DB 트리거(직접 SQL 까지).
 */
/**
 * @typedef {{nodeId: number, parentId: number | null, nodeType: string, nodeCd: string, nodeNm: string, sortNo: number,
 *   useYn: string, remark?: string | null, path: string, lvl: number, rowVer: number, [field: string]: any}} EquipNode
 */
(function () {
    'use strict';

    var API = '/api/master/equip-hier';
    var ACTIONS = (document.body.getAttribute('data-actions') || '').split(',');
    var CAN_SAVE = ACTIONS.indexOf('SAVE') >= 0;
    // 부모 유형(없으면 공장 바로 아래 '') → 둘 수 있는 유형 — V020 hb_equip_parent_ok 와 같은 표
    /** @type {Record<string, string[]>} */
    var CHILD_TYPES = {'': ['AREA', 'WH'], AREA: ['LINE', 'WH'], LINE: ['CELL'], CELL: [], WH: ['LOC'], LOC: ['LOC']};
    var msg = /** @type {HTMLElement} */ (document.getElementById('msg'));
    var search = /** @type {HTMLFormElement} */ (document.getElementById('search'));
    var form = /** @type {HTMLFormElement} */ (document.getElementById('nodeForm'));
    var formMode = /** @type {HTMLElement} */ (document.getElementById('formMode'));
    var summary = /** @type {HTMLElement} */ (document.getElementById('summary'));
    var pathOut = /** @type {HTMLElement} */ (document.getElementById('path'));
    // 조회 패널·트리·순서 칸은 메시지·공장 목록을 읽은 뒤 만든다 — ready 전 버튼은 무시
    /** @type {HB.SearchPanel} */
    var sp;
    /** @type {HB.Tree} */
    var tree;
    /** @type {HB.NumberControl} */
    var sortNo;
    var ready = false;
    /** @type {Record<string, string>} */
    var typeNames = {};
    /** 지금 트리 행 @type {EquipNode[]} */
    var nodes = [];
    /** 고친 노드 또는 null(새로) @type {EquipNode | null} */
    var current = null;
    /** 새로 만들 때 상위 노드 또는 null(공장 바로 아래) @type {EquipNode | null} */
    var newParent = null;

    /** @param {string | null | undefined} text @param {boolean} [error] */
    function notify(text, error) {
        msg.textContent = text || '';
        msg.classList.toggle('hb-msg-error', !!error);
    }

    /** 상세 폼 칸 @param {string} n */
    function field(n) {
        return /** @type {HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement} */ (form.querySelector('[data-field="' + n + '"]'));
    }

    /** @return {string} */
    function plantCd() { return sp.values().plantCd; }

    /** @param {unknown} id */
    function byId(id) { return nodes.filter(function (n) { return n.nodeId === id; })[0] || null; }

    /** 이 노드와 그 하위 id (이동 대상에서 빼려고 — 자기 하위 아래로는 순환) @param {EquipNode | null} node */
    function subtreeIds(node) {
        /** @type {Record<number, boolean>} */
        var ids = {};
        if (!node) { return ids; }
        var start = nodes.indexOf(node);
        ids[node.nodeId] = true;
        for (var i = start + 1; i < nodes.length && nodes[i].lvl > node.lvl; i++) { ids[nodes[i].nodeId] = true; }
        return ids;
    }

    /** @param {string[]} types @param {string} value @param {boolean} fixed */
    function fillType(types, value, fixed) {
        var s = field('nodeType');
        s.textContent = '';
        (fixed ? [value] : types).forEach(function (t) {
            var o = hb.el('option', null, typeNames[t] || t);
            o.value = t;
            s.appendChild(o);
        });
        s.value = value || (types[0] || '');
        s.disabled = !!fixed || !CAN_SAVE;
    }

    /** 상위 선택지: 지금 유형을 둘 수 있는 상위만(자기·자기 하위 제외)
     * @param {string} type @param {number | null} value @param {Record<number, boolean>} exclude */
    function fillParent(type, value, exclude) {
        var s = field('parentId');
        s.textContent = '';
        if (CHILD_TYPES[''].indexOf(type) >= 0) {
            var top = hb.el('option', null, hb.t('js.equipHier.plantTop', plantCd()));
            top.value = '';
            s.appendChild(top);
        }
        nodes.forEach(function (n) {
            if (exclude[n.nodeId] || n.useYn !== 'Y' || (CHILD_TYPES[n.nodeType] || []).indexOf(type) < 0) { return; }
            var o = hb.el('option', null, n.path + ' (' + (typeNames[n.nodeType] || n.nodeType) + ')');
            o.value = String(n.nodeId);
            s.appendChild(o);
        });
        s.value = value === null || value === undefined ? '' : String(value);
        s.disabled = !CAN_SAVE;
    }

    /** @param {EquipNode | null} node @param {EquipNode | null} [parent] */
    function show(node, parent) {
        var types = node ? [] : CHILD_TYPES[parent ? parent.nodeType : ''];
        if (!node && !types.length) {
            // 하위를 둘 수 없는 유형 — 고르던 노드를 그대로 고치게 둔다(current 를 비우면 저장이 새로 만들기가 됨)
            notify(hb.t('js.equipHier.noChild', parent ? typeNames[parent.nodeType] : ''), true);
            return;
        }
        current = node;
        newParent = node ? null : (parent || null);
        hb.showFieldErrors(form, []);
        if (node) {
            fillType([node.nodeType], node.nodeType, true);
            fillParent(node.nodeType, node.parentId, subtreeIds(node));
            field('nodeCd').value = node.nodeCd;
            field('nodeNm').value = node.nodeNm;
            sortNo.setValue(node.sortNo);
            field('useYn').value = node.useYn;
            field('remark').value = node.remark || '';
            pathOut.textContent = node.path;
            formMode.textContent = hb.t('js.equipHier.editing', node.path);
        } else {
            fillType(types, types[0], false);
            fillParent(types[0], parent ? parent.nodeId : null, {});
            ['nodeCd', 'nodeNm', 'remark'].forEach(function (n) { field(n).value = ''; });
            sortNo.setValue(0);
            field('useYn').value = 'Y';
            pathOut.textContent = (parent ? parent.path : plantCd()) + '/…';
            formMode.textContent = hb.t('js.equipHier.creating', parent ? parent.path : plantCd());
            field('nodeCd').focus();
        }
        form.querySelectorAll('input, textarea').forEach(function (i) {
            /** @type {HTMLInputElement | HTMLTextAreaElement} */ (i).readOnly = !CAN_SAVE;
        });
        field('useYn').disabled = !CAN_SAVE;
        hb.dirty.clear();
    }

    field('nodeType').addEventListener('change', function () {
        if (!current) { fillParent(field('nodeType').value, field('parentId').value ? Number(field('parentId').value) : null, {}); }
    });

    /** 트리 — 공장을 빨리 바꾸면 늦게 온 이전 공장 트리는 hb.latest 가 버린다 @param {number} [selectId] */
    function load(selectId) {
        var v = sp.values();
        notify('');
        return hb.latest('nodes', hb.api(API + '?plantCd=' + encodeURIComponent(v.plantCd || '') + (v.withUnused && v.withUnused.length ? '&withUnused=true' : '')))
            .then(function (res) {
                nodes = res.data;
                tree.setRows(nodes.map(function (n) { return Object.assign({dim: n.useYn !== 'Y'}, n); }));
                var areas = nodes.filter(function (n) { return n.nodeType !== 'WH' && n.nodeType !== 'LOC'; }).length;
                summary.textContent = hb.t('js.equipHier.summary', v.plantCd, nodes.length, areas, nodes.length - areas);
                if (selectId && tree.selectWhere(function (n) { return n.nodeId === selectId; })) { return; }
                show(null, null);
            }, function (err) { notify(err.message, true); });
    }

    function body() {
        var p = field('parentId').value;
        return {plantCd: plantCd(), parentId: p ? Number(p) : null, nodeType: field('nodeType').value,
            nodeCd: field('nodeCd').value, nodeNm: field('nodeNm').value, sortNo: sortNo.numberValue(),
            useYn: field('useYn').value, remark: field('remark').value, rowVer: current ? current.rowVer : null};
    }

    /** 버튼(권한에 따라 없을 수 있음)에 클릭 연결 — 화면 준비 전 클릭은 무시 @param {string} id @param {(b: HTMLButtonElement) => void} fn */
    function bind(id, fn) {
        var b = /** @type {HTMLButtonElement | null} */ (document.getElementById(id));
        if (b) {
            var btn = b;
            btn.addEventListener('click', function () { if (ready) { fn(btn); } });
        }
    }

    /** 미저장 입력이 있으면 화면 안 대화상자로 묻는다(docs/40 V-7) — 없으면 바로 true @returns {Promise<boolean>} */
    function confirmLeave() {
        if (!hb.dirty.isDirty()) { return Promise.resolve(true); }
        return hb.dialog.confirm({title: hb.t('js.common.leaveTitle'), message: hb.t('js.common.confirmLeave'), ok: hb.t('js.common.leaveOk')});
    }

    bind('addRootBtn', function () { confirmLeave().then(function (ok) { if (ok) { show(null, null); hb.page.openDetail(); } }); });
    bind('addChildBtn', function () {
        var sel = tree.selected();
        if (!sel) { notify(hb.t('js.equipHier.pickFirst'), true); return; }
        var parent = byId(sel.nodeId);
        confirmLeave().then(function (ok) { if (ok) { show(null, parent); hb.page.openDetail(); } });
    });
    bind('saveBtn', function (btn) {
        var editId = current ? current.nodeId : null;   // 저장 중에 다른 노드를 골라도 이 노드로 마무리
        var b = body();
        hb.busy(btn, function () {
            return editId !== null ? hb.api(API + '/' + editId, {method: 'PUT', body: b}) : hb.api(API, {method: 'POST', body: b});
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
    bind('excelBtn', function (btn) {
        var v = sp.values();
        hb.busy(btn, function () {
            return hb.download(API + '/export', {plantCd: v.plantCd, withUnused: !!(v.withUnused && v.withUnused.length)});
        }).then(function () { notify(hb.t('js.grid.exported')); }, function (err) { notify(err.message, true); });
    });

    hb.dirty.watch(form);
    search.addEventListener('submit', function (e) { e.preventDefault(); });
    hb.loadMessages().then(function () {
        hb.page.init();
        sortNo = hb.number(/** @type {HTMLInputElement} */ (field('sortNo')));
        tree = hb.tree('#tree', {onExcel: function () { var b = document.getElementById('excelBtn'); if (b) { b.click(); } }, columnsUrl: API + '/columns', treeField: 'nodeCd', dimField: 'dim',
            emptyText: hb.t('js.equipHier.empty'),
            beforeSelect: function () { return confirmLeave(); },            // 미저장이면 묻고, 아니라고 하면 선택이 옮겨 가지 않는다
            onSelect: function (n) {
                show(byId(n.nodeId));
                hb.page.openDetail();
            }});
        return Promise.all([hb.loadSession(), tree.ready, hb.codes('EQUIP_NODE_TYPE'), hb.api(API + '/plants')]);
    }).then(function (r) {
        typeNames = r[2];
        var sel = /** @type {HTMLSelectElement} */ (document.getElementById('plantSel'));
        /** @type {{plantCd: string, plantNm: string}[]} */ (r[3].data).forEach(function (p) {
            var o = hb.el('option', null, p.plantCd + ' · ' + p.plantNm);
            o.value = p.plantCd;
            sel.appendChild(o);
        });
        sp = hb.searchPanel(search, {onSearch: function () { return load(); }});
        ready = true;
        sel.addEventListener('change', function () { load(); });
        return sp.ready.then(function () { return sel.value ? load() : notify(hb.t('js.equipHier.noPlant'), true); });
    }).then(null, function (err) { notify(err && err.message, true); });
})();
