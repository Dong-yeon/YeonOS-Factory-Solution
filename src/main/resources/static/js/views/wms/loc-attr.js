/*
 * 로케이션 속성 (wms-loc) — P4 1부 W1 (docs/30). 공장 선택 → 창고·로케이션 트리(hb.tree) → 오른쪽 속성 폼.
 * 속성 행이 없는 노드는 기본값으로 보이고(attrSet = false), 저장하면 그때 만들어진다(rowVer null → 새로).
 * 스캔 확인 칸은 저장하지 않는다 — 판별·존재 확인 결과만 보여 준다.
 * 공통 hb.* 는 고치지 않는다(병행 규칙, docs/29 6장).
 */
(function () {
    'use strict';

    var API = '/api/wms/loc';
    var ACTIONS = (document.body.getAttribute('data-actions') || '').split(',');
    var CAN_SAVE = ACTIONS.indexOf('SAVE') >= 0;
    var msg = document.getElementById('msg');
    var search = document.getElementById('search');
    var form = document.getElementById('attrForm');
    var formMode = document.getElementById('formMode');
    var summary = document.getElementById('summary');
    var scanForm = document.getElementById('scanForm');
    var scanText = document.getElementById('scanText');
    var scanResult = document.getElementById('scanResult');
    var sp, tree, pickSeq;
    var nodes = [];
    var current = null;
    var usageNames = {};

    function notify(text, error) {
        msg.textContent = text || '';
        msg.classList.toggle('hb-msg-error', !!error);
    }

    function field(n) { return form.querySelector('[data-field="' + n + '"]'); }

    function plantCd() { return sp.values().plantCd; }

    function byId(id) { return nodes.filter(function (n) { return n.nodeId === id; })[0] || null; }

    function fillSelect(sel, list, blankText) {
        sel.textContent = '';
        if (blankText !== null) {
            var b = hb.el('option', null, blankText);
            b.value = '';
            sel.appendChild(b);
        }
        list.forEach(function (c) {
            var o = hb.el('option', null, c.name);
            o.value = c.code;
            sel.appendChild(o);
        });
    }

    function setEnabled(on) {
        form.querySelectorAll('select, input, textarea').forEach(function (i) {
            if (i.tagName === 'TEXTAREA' || (i.tagName === 'INPUT' && i.type !== 'checkbox')) {
                i.readOnly = !on;
            } else {
                i.disabled = !on;
            }
        });
        ['saveBtn', 'renumberBtn'].forEach(function (id) {
            var b = document.getElementById(id);
            if (b) { b.disabled = !on; }
        });
    }

    function show(node) {
        current = node;
        hb.showFieldErrors(form, []);
        if (!node) {
            formMode.textContent = hb.t('js.wmsLoc.selectFirst');
            document.getElementById('path').textContent = '';
            document.getElementById('barcode').textContent = '';
            setEnabled(false);
            hb.dirty.clear();
            return;
        }
        field('locUsage').value = node.locUsage;
        field('defaultStatus').value = node.defaultStatus || '';
        field('feedNodeId').value = node.feedNodeId ? String(node.feedNodeId) : '';
        feedToggle();
        field('stockYn').value = node.stockYn;
        pickSeq.setValue(node.pickSeq);
        field('mixItemYn').value = node.mixItemYn;
        field('mixLotYn').value = node.mixLotYn;
        field('remark').value = node.remark || '';
        field('applyToChildren').checked = false;
        document.getElementById('path').textContent = node.path;
        // 내부 바코드 형식(docs/30 D7) — W2 라벨 템플릿이 같은 값을 찍는다
        document.getElementById('barcode').textContent = 'LOC:' + node.plantCd + ':' + node.nodeCd;
        formMode.textContent = node.attrSet ? hb.t('js.wmsLoc.editing', node.path) : hb.t('js.wmsLoc.defaults', node.path);
        setEnabled(CAN_SAVE);
        field('applyToChildren').disabled = !CAN_SAVE || !node.childCnt;
        var rb = document.getElementById('renumberBtn');
        if (rb) { rb.disabled = !CAN_SAVE || !node.childCnt; }
        hb.dirty.clear();
        loadAllow(node.nodeId);
    }

    // ---------------------------------------------------------------- 적치 허용 품목군 (W-12, docs/30 12.19)

    /** @type {{classId: number, classCd: string, classNm: string}[]} */
    var classOpts = [];
    var allowList = document.getElementById('allowList');
    var allowInfo = document.getElementById('allowInfo');

    /** 체크 상자 목록을 그리고 직접 목록을 체크 @param {number[]} own */
    function drawAllow(own) {
        allowList.textContent = '';
        classOpts.forEach(function (c) {
            var box = /** @type {HTMLInputElement} */ (hb.el('input'));
            box.type = 'checkbox';
            box.value = String(c.classId);
            box.checked = own.indexOf(c.classId) >= 0;
            box.disabled = !CAN_SAVE;
            var lb = hb.el('label', 'hb-check');
            lb.appendChild(box);
            lb.appendChild(hb.el('span', null, c.classCd + ' · ' + c.classNm));
            allowList.appendChild(lb);
        });
        if (!classOpts.length) { allowList.textContent = hb.t('js.wmsLoc.allowNoClass'); }
    }

    /** @param {{own: number[], inheritedFrom: string | null, inherited: string[]}} v */
    function showAllow(v) {
        drawAllow(v.own);
        allowInfo.textContent = v.own.length ? hb.t('js.wmsLoc.allowOwn')
            : v.inheritedFrom ? hb.t('js.wmsLoc.allowInherited', v.inheritedFrom, v.inherited.join(', ')) : hb.t('js.wmsLoc.allowNone');
    }

    /** @param {number} nodeId */
    function loadAllow(nodeId) {
        allowInfo.textContent = '';
        allowList.textContent = '';
        return hb.latest('wmsLocAllow', hb.api(API + '/' + nodeId + '/allow')).then(function (res) {
            if (current && current.nodeId === nodeId) { showAllow(res.data); }
        }, function (err) { if (!err.stale) { notify(err.message, true); } });
    }

    bind('allowSaveBtn', function (btn) {
        if (!current) { notify(hb.t('js.wmsLoc.selectFirst'), true); return; }
        var id = current.nodeId;
        var ids = Array.prototype.map.call(allowList.querySelectorAll('input[type=checkbox]:checked'),
            function (/** @type {HTMLInputElement} */ b) { return Number(b.value); });
        hb.busy(btn, function () { return hb.api(API + '/' + id + '/allow', {method: 'PUT', body: {classIds: ids}}); }).then(function (res) {
            if (!res) { return; }
            if (current && current.nodeId === id) { showAllow(res.data); }
            notify(hb.t('js.wmsLoc.allowSaved'));
        }, function (err) { notify(err.message, true); });
    });

    function load(selectId) {
        var v = sp.values();
        notify('');
        return hb.api(API + '?plantCd=' + encodeURIComponent(v.plantCd || '') + (v.withUnused && v.withUnused.length ? '&withUnused=true' : ''))
            .then(function (res) {
                nodes = res.data;
                tree.setRows(nodes.map(function (n) { return Object.assign({dim: n.useYn !== 'Y'}, n); }));
                var wh = nodes.filter(function (n) { return n.nodeType === 'WH'; }).length;
                var set = nodes.filter(function (n) { return n.attrSet; }).length;
                summary.textContent = hb.t('js.wmsLoc.summary', v.plantCd, wh, nodes.length - wh, set);
                if (selectId && tree.selectWhere(function (n) { return n.nodeId === selectId; })) { return; }
                show(null);
                if (!nodes.length) { notify(hb.t('js.wmsLoc.empty')); }
            }, function (err) { notify(err.message, true); });
    }

    function body() {
        var feed = field('locUsage').value === 'LINESIDE' ? field('feedNodeId').value : '';
        return {locUsage: field('locUsage').value, defaultStatus: field('defaultStatus').value || null,
            feedNodeId: feed ? Number(feed) : null,
            stockYn: field('stockYn').value, pickSeq: pickSeq.numberValue(), mixItemYn: field('mixItemYn').value,
            mixLotYn: field('mixLotYn').value, remark: field('remark').value,
            rowVer: current && current.attrSet ? current.rowVer : null,
            applyToChildren: field('applyToChildren').checked};
    }

    function bind(id, fn) {
        var b = document.getElementById(id);
        if (b) { b.addEventListener('click', function () { fn(b); }); }
    }

    /** 미저장 입력이 있으면 화면 안 대화상자로 묻는다(docs/40 V-7) — 없으면 바로 true @returns {Promise<boolean>} */
    function confirmLeave() {
        if (!hb.dirty.isDirty()) { return Promise.resolve(true); }
        return hb.dialog.confirm({title: hb.t('js.common.leaveTitle'), message: hb.t('js.common.confirmLeave'), ok: hb.t('js.common.leaveOk')});
    }

    /** 보급 라인은 LINESIDE 용도만(D-B12) */
    function feedToggle() {
        var box = document.getElementById('feedBox');
        if (box) { box.hidden = field('locUsage').value !== 'LINESIDE'; }
    }

    /** 그 공장의 LINE·CELL — 보급 라인 선택지 */
    function loadLines() {
        var plant = document.getElementById('plantSel').value;
        if (!plant) { return Promise.resolve(); }
        return hb.api(API + '/lines?plantCd=' + encodeURIComponent(plant)).then(function (res) {
            var sel = field('feedNodeId');
            var keep = sel.value;
            sel.textContent = '';
            var none = hb.el('option', null, hb.t('js.wmsLoc.noFeed'));
            none.value = '';
            sel.appendChild(none);
            res.data.forEach(function (n) {
                var o = hb.el('option', null, n.path + ' · ' + n.nodeNm);
                o.value = String(n.nodeId);
                sel.appendChild(o);
            });
            sel.value = keep;
        });
    }

    // 용도를 QC·폐기로 고르면 기본 상태를 비어 있을 때만 제안한다(강제 아님)
    field('locUsage').addEventListener('change', feedToggle);
    field('locUsage').addEventListener('change', function () {
        var u = field('locUsage').value;
        var s = field('defaultStatus');
        if (s.value) { return; }
        if (u === 'QC') { s.value = 'QC'; } else if (u === 'SCRAP') { s.value = 'BLOCKED'; }
    });

    bind('saveBtn', function (btn) {
        if (!current) { notify(hb.t('js.wmsLoc.selectFirst'), true); return; }
        var b = body();
        (b.applyToChildren ? hb.dialog.confirm({title: hb.t('js.wmsLoc.dlg.apply'), message: hb.t('js.wmsLoc.confirmApply', current.path),
            ok: hb.t('js.wmsLoc.dlg.applyOk')}) : Promise.resolve(true)).then(function (yes) {
            if (!yes) { return; }
            var id = current.nodeId;
            hb.busy(btn, function () { return hb.api(API + '/' + id, {method: 'PUT', body: b}); }).then(function (res) {
                if (!res) { return; }
                hb.dirty.clear();
                return load(id).then(function () {
                    notify(b.applyToChildren ? hb.t('js.wmsLoc.savedChildren', res.data.childrenApplied) : hb.t('js.common.saved'));
                });
            }, function (err) {
                notify(err.message, true);
                hb.showFieldErrors(form, err.errors);
            });
        });
    });

    bind('renumberBtn', function (btn) {
        if (!current) { return; }
        confirmLeave().then(function (ok) {
            if (!ok || !current) { return false; }
            return hb.dialog.confirm({title: hb.t('js.wmsLoc.dlg.renumber'), message: hb.t('js.wmsLoc.confirmRenumber', current.path),
                ok: hb.t('js.wmsLoc.dlg.renumberOk')});
        }).then(function (yes) {
            if (!yes || !current) { return; }
            var id = current.nodeId;
            hb.busy(btn, function () { return hb.api(API + '/' + id + '/renumber-pick', {method: 'POST', body: {step: 10}}); })
                .then(function (res) {
                    if (!res) { return; }
                    return load(id).then(function () { notify(hb.t('js.wmsLoc.renumbered', res.data)); });
                }, function (err) { notify(err.message, true); });
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

    // ---------------------------------------------------------------- 라벨 인쇄 (W2)

    var printForm = document.getElementById('printForm');
    var printCopies = null;

    function loadPrintChoices() {
        if (!printForm) { return null; }
        return Promise.all([hb.api(API + '/printers?plantCd=' + encodeURIComponent(plantCd() || '')), hb.api(API + '/templates')])
            .then(function (r) {
                var ps = document.getElementById('printPrinter');
                var ts = document.getElementById('printTpl');
                ps.textContent = '';
                ts.textContent = '';
                r[0].data.forEach(function (p) {
                    var o = hb.el('option', null, p.printerCd + ' · ' + p.printerNm);
                    o.value = String(p.printerId);
                    ps.appendChild(o);
                });
                var d = hb.el('option', null, hb.t('js.wmsLoc.defaultTpl'));
                d.value = '';
                ts.appendChild(d);
                r[1].data.forEach(function (t) {
                    var o = hb.el('option', null, t.tplCd + ' · ' + t.tplNm + (t.defaultYn === 'Y' ? ' ★' : ''));
                    o.value = String(t.tplId);
                    ts.appendChild(o);
                });
                if (!r[0].data.length) { notify(hb.t('js.wmsLoc.noPrinter')); }
            }, function (err) { notify(err.message, true); });
    }

    bind('printBtn', function (btn) {
        if (!current) { notify(hb.t('js.wmsLoc.selectFirst'), true); return; }
        var p = document.getElementById('printPrinter').value;
        if (!p) { notify(hb.t('js.wmsLoc.noPrinter'), true); return; }
        var t = document.getElementById('printTpl').value;
        var children = document.getElementById('printChildren').checked;
        (children ? hb.dialog.confirm({title: hb.t('js.wmsLoc.dlg.print'), message: hb.t('js.wmsLoc.confirmPrintChildren', current.path),
            ok: hb.t('js.wmsLoc.dlg.printOk')}) : Promise.resolve(true)).then(function (yes) {
            if (!yes) { return; }
            hb.busy(btn, function () {
                return hb.api(API + '/print', {method: 'POST', body: {plantCd: plantCd(), nodeIds: [current.nodeId],
                    includeChildren: children, printerId: Number(p), tplId: t ? Number(t) : null,
                    copies: printCopies.numberValue() || 1}});
            }).then(function (res) {
                if (res) { notify(hb.t('js.wmsLoc.printed', res.data.jobs)); }
            }, function (err) { notify(err.message, true); hb.showFieldErrors(printForm, err.errors); });
        });
    });

    // ---------------------------------------------------------------- 스캔 확인

    var scanning = false;

    function showScan(v) {
        scanResult.textContent = '';
        scanResult.classList.toggle('hb-msg-error', !v.ok);
        var lines = [v.message];
        if (v.type === 'GS1') {
            if (v.gtin) { lines.push('GTIN ' + v.gtin); }
            if (v.sscc) { lines.push('SSCC ' + v.sscc); }
            if (v.batch) { lines.push(hb.t('js.wmsLoc.gs1Batch', v.batch)); }
            if (v.expiry) { lines.push(hb.t('js.wmsLoc.gs1Expiry', v.expiry)); }
            if (v.qty !== null && v.qty !== undefined) { lines.push(hb.t('js.wmsLoc.gs1Qty', v.qty)); }
        }
        if (v.bare) { lines.push(hb.t('js.wmsLoc.bare')); }
        scanResult.textContent = lines.join(' · ');
        // 로케이션이면 트리에서 그 노드로. hb.tree.selectWhere 는 앞서 고른 행의 선택 표시를 지우지 않아(공통 — 병합 뒤 수정 대상)
        // 행을 다시 그린 뒤 고른다
        if (v.nodeId && byId(v.nodeId)) {
            confirmLeave().then(function (ok) {
                if (!ok) { return; }
                tree.setRows(nodes.map(function (n) { return Object.assign({dim: n.useYn !== 'Y'}, n); }));
                tree.selectWhere(function (n) { return n.nodeId === v.nodeId; });
            });
        }
    }

    scanForm.addEventListener('submit', function (e) {
        e.preventDefault();
        if (scanning) { return; }                      // 스캐너 연타(Enter 두 번)
        var text = scanText.value;
        if (!text.trim()) { scanText.focus(); return; }
        scanning = true;
        hb.api(API + '/scan', {method: 'POST', body: {plantCd: plantCd(), text: text}}).then(function (res) {
            showScan(res.data);
        }, function (err) {
            scanResult.textContent = err.message;
            scanResult.classList.add('hb-msg-error');
        }).then(function () {
            scanning = false;
            scanText.select();                         // 다음 스캔이 덮어쓰게
        });
    });

    hb.dirty.watch(form);
    search.addEventListener('submit', function (e) { e.preventDefault(); });
    hb.loadMessages().then(function () {
        hb.page.init();
        pickSeq = hb.number(field('pickSeq'));
        if (printForm) { printCopies = hb.number(document.getElementById('printCopies')); }
        tree = hb.tree('#tree', {onExcel: function () { var b = document.getElementById('excelBtn'); if (b) { b.click(); } }, columnsUrl: API + '/columns', treeField: 'nodeCd', dimField: 'dim',
            emptyText: hb.t('js.wmsLoc.empty'),
            beforeSelect: function () { return confirmLeave(); },            // 미저장이면 묻고, 아니라고 하면 선택이 옮겨 가지 않는다
            onSelect: function (n) {
                show(byId(n.nodeId));
                hb.page.openDetail();
            }});
        return Promise.all([hb.loadSession(), tree.ready, hb.codeList('WMS_LOC_USAGE'), hb.codeList('STOCK_STATUS'),
            hb.api(API + '/plants'), hb.api(API + '/classes')]);
    }).then(function (r) {
        classOpts = r[5].data;
        r[2].forEach(function (c) { usageNames[c.code] = c.name; });
        fillSelect(field('locUsage'), r[2], null);
        fillSelect(field('defaultStatus'), r[3], hb.t('js.wmsLoc.noDefaultStatus'));
        var sel = document.getElementById('plantSel');
        r[4].data.forEach(function (p) {
            var o = hb.el('option', null, p.plantCd + ' · ' + p.plantNm);
            o.value = p.plantCd;
            sel.appendChild(o);
        });
        show(null);
        sp = hb.searchPanel(search, {onSearch: function () { return load(); }});
        var plantShown = sel.value;
        sel.addEventListener('change', function () {
            confirmLeave().then(function (ok) {
                if (!ok) { sel.value = plantShown; return; }                  // 머문다 — 고르기 전 공장으로 되돌림
                plantShown = sel.value;
                loadLines().then(function () { return load(); });
                loadPrintChoices();
            });
        });
        return sp.ready.then(function () {
            return sel.value ? Promise.all([loadLines().then(function () { return load(); }), loadPrintChoices()])
                : notify(hb.t('js.wmsLoc.noPlant'), true);
        });
    }).then(null, function (err) { notify(err && err.message, true); });
})();
