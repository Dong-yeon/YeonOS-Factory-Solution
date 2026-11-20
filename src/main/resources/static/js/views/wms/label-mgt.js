/*
 * 라벨 템플릿·프린터 (wms-label) — P4 1부 W2 (docs/30). 보기 선택(템플릿/프린터) → 그리드 → 오른쪽 폼.
 * 판정(자리표시자·금지 명령·주소 대역)은 서버가 한다 — 화면은 칸 오류를 보여 줄 뿐.
 * 목록은 보기마다 hb.grid(UI 단계 3 ①, /grid·/columns), 미저장 확인은 화면 안 대화상자(비동기 beforeSelect). 공통 hb.* 는 고치지 않는다.
 */
(function () {
    'use strict';

    var TPL = '/api/wms/labels';
    var PRN = '/api/wms/printers';
    var ACTIONS = (document.body.getAttribute('data-actions') || '').split(',');
    var CAN_SAVE = ACTIONS.indexOf('SAVE') >= 0;
    var msg = document.getElementById('msg');
    var search = document.getElementById('search');
    var tplForm = document.getElementById('tplForm');
    var prnForm = document.getElementById('prnForm');
    var PROG = document.body.getAttribute('data-prog') || 'wms-label';
    var grids = {TPL: null, PRN: null};
    var preview = document.getElementById('preview');
    var sp, tab = 'TPL', current = null;
    var nums = {};
    var typeNames = {}, usageNames = {}, prnStateNames = {};
    /** @type {Array<{terminalId:number, terminalCd:string, terminalNm:string, online:boolean}>} */
    var agents = [];

    function notify(text, error) {
        msg.textContent = text || '';
        msg.classList.toggle('hb-msg-error', !!error);
    }

    function f(form, n) { return form.querySelector('[data-field="' + n + '"]'); }

    function fill(sel, list, blank) {
        sel.textContent = '';
        if (blank) {
            var b = hb.el('option', null, blank);
            b.value = '';
            sel.appendChild(b);
        }
        list.forEach(function (c) {
            var o = hb.el('option', null, c.name);
            o.value = c.code;
            sel.appendChild(o);
        });
    }

    /** 미저장 입력이 있으면 화면 안 대화상자로 묻는다(docs/40 V-7) — 없으면 바로 true @returns {Promise<boolean>} */
    function confirmLeave() {
        if (!hb.dirty.isDirty()) { return Promise.resolve(true); }
        return hb.dialog.confirm({title: hb.t('js.common.leaveTitle'), message: hb.t('js.common.confirmLeave'), ok: hb.t('js.common.leaveOk')});
    }

    function lockForm(form, on) {
        form.querySelectorAll('input, textarea').forEach(function (i) { i.readOnly = !on; });
        form.querySelectorAll('select').forEach(function (s) { s.disabled = !on; });
    }

    // ---------------------------------------------------------------- 목록 그리드 (UI 단계 3 ① — /grid·/columns)

    /** 보기(템플릿/프린터)마다 그리드 하나 — 지금 보기의 것 */
    function grid() { return tab === 'TPL' ? grids.TPL : grids.PRN; }

    /**
     * 지금 보기 목록 다시 읽기 — selectId 가 있으면 그 행을 골라 onSelect 로 폼을 채우고, 없으면 폼을 비운다
     * @param {number} [selectId] @param {boolean} [resetPage]
     */
    function load(selectId, resetPage) {
        notify('');
        var g = grid();
        if (!g) { return Promise.resolve(); }
        if (!selectId) { show(null); }
        return g.reload(!!resetPage, selectId || null);
    }

    /** 그리드 하나 — 미저장이면 행을 옮기기 전에 묻는다(비동기 beforeSelect) */
    function makeGrid(host, id, base, key, params) {
        return hb.grid(host, {
            progCd: PROG, id: id, key: key, url: base + '/grid', columnsUrl: base + '/columns', exportUrl: base + '/export',
            actions: ACTIONS, params: params,
            format: {defaultYn: function (v) { return v === 'Y' ? '★' : ''; }},      // 기본 템플릿 표시(예전 표와 같음)
            status: {useYn: {Y: 'normal', N: 'neutral'}},                          // 사용 여부 = 상태 배지(docs/40 부록 A)
            rowClass: function (r) { return r.useYn === 'N' ? 'hb-tree-dim' : null; },   // 미사용 행 흐리게
            beforeSelect: function () { return confirmLeave(); },
            onSelect: function (r) { show(r); hb.page.openDetail(); },
            notify: notify
        });
    }

    // ---------------------------------------------------------------- 폼

    function show(r) {
        current = r;
        document.getElementById('previewBox').hidden = true;
        hb.showFieldErrors(tplForm, []);
        hb.showFieldErrors(prnForm, []);
        if (tab === 'TPL') { showTpl(r); } else { showPrn(r); }
        loadRules();
        hb.dirty.clear();
    }

    function showTpl(r) {
        var editing = !!r;
        f(tplForm, 'labelType').value = r ? r.labelType : (sp.values().labelType || 'LOC');
        f(tplForm, 'tplCd').value = r ? r.tplCd : '';
        f(tplForm, 'tplNm').value = r ? r.tplNm : '';
        nums.widthMm.setValue(r ? r.widthMm : 50);
        nums.heightMm.setValue(r ? r.heightMm : 30);
        f(tplForm, 'dpi').value = String(r ? r.dpi : 203);
        f(tplForm, 'defaultYn').value = r ? r.defaultYn : 'N';
        f(tplForm, 'useYn').value = r ? r.useYn : 'Y';
        f(tplForm, 'body').value = r ? r.body : '';
        f(tplForm, 'remark').value = r ? (r.remark || '') : '';
        lockForm(tplForm, CAN_SAVE);
        f(tplForm, 'labelType').disabled = editing || !CAN_SAVE;
        f(tplForm, 'tplCd').readOnly = editing || !CAN_SAVE;
        document.getElementById('tplMode').textContent = editing ? hb.t('js.wmsLabel.editing', r.tplCd) : hb.t('js.wmsLabel.creatingTpl');
        showFields();
        var tb = document.getElementById('testBtn');
        if (tb) { tb.disabled = !editing; }
    }

    function showFields() {
        var t = f(tplForm, 'labelType').value;
        hb.api(TPL + '/sample?labelType=' + encodeURIComponent(t)).then(function (res) {
            document.getElementById('fields').textContent = hb.t('js.wmsLabel.fields', res.data.fields.map(function (n) {
                return '{{' + n + '}}';
            }).join(' '));
        }, function () { /* 목록 표시만 — 실패해도 저장에는 영향 없음 */ });
    }

    // ---------------------------------------------------------------- 미리보기 SVG (docs/65 18.3 — 서버 배치, 근사)

    var SVG = 'http://www.w3.org/2000/svg';

    function svgEl(tag, attrs, cls) {
        var e = document.createElementNS(SVG, tag);
        Object.keys(attrs).forEach(function (k) { e.setAttribute(k, String(attrs[k])); });
        if (cls) { e.setAttribute('class', cls); }
        return e;
    }

    /** 배치 요소를 그린다 — 글자는 textContent 로만(서버 값) */
    function drawLayout(layout) {
        var svg = document.getElementById('previewSvg');
        while (svg.firstChild) { svg.removeChild(svg.firstChild); }
        var w = Math.max(1, layout.widthDots || 400);
        var h = Math.max(1, layout.heightDots || 240);
        svg.setAttribute('viewBox', '0 0 ' + w + ' ' + h);
        svg.appendChild(svgEl('rect', {x: 0, y: 0, width: w, height: h}, 'wms-z-box'));
        (layout.elements || []).forEach(function (el) {
            var over = el.overflow ? ' wms-z-over' : '';
            if (el.kind === 'TEXT') {
                var t = svgEl('text', {x: el.x, y: el.y + el.h * 0.85, 'font-size': Math.max(1, el.h)}, 'wms-z-text' + over);
                t.textContent = el.text || '';
                svg.appendChild(t);
                if (el.overflow) { svg.appendChild(svgEl('rect', {x: el.x, y: el.y, width: el.w, height: el.h}, 'wms-z-box wms-z-over')); }
            } else if (el.kind === 'BOX') {
                svg.appendChild(svgEl('rect', {x: el.x, y: el.y, width: Math.max(1, el.w), height: Math.max(1, el.h)}, 'wms-z-box' + over));
            } else {
                svg.appendChild(svgEl('rect', {x: el.x, y: el.y, width: Math.max(1, el.w), height: Math.max(1, el.h)}, 'wms-z-code' + over));
            }
        });
    }

    function showWarnings(res) {
        var ul = document.getElementById('previewWarn');
        ul.textContent = '';
        (res.warningTexts || []).forEach(function (t) { ul.appendChild(hb.el('li', null, t)); });
        (res.longWarningTexts || []).forEach(function (t) { ul.appendChild(hb.el('li', null, hb.t('js.wmsLabel.longWarn', t))); });
    }

    // ---------------------------------------------------------------- 템플릿 선택 규칙 (docs/65 18.4)

    var RULE = '/api/wms/label-rules';
    var targetMap = {};

    function loadRules() {
        var pane = document.getElementById('rulePane');
        var rows = document.getElementById('ruleRows');
        rows.textContent = '';
        if (!current || tab !== 'TPL') { pane.hidden = true; return Promise.resolve(); }
        pane.hidden = false;
        var tplId = current.tplId;
        return hb.api(RULE + '?labelType=' + encodeURIComponent(current.labelType)).then(function (res) {
            res.data.filter(function (r) { return r.tplId === tplId; }).forEach(function (r) {
                var tr = hb.el('tr');
                tr.appendChild(hb.el('td', null, r.scopeCd === 'CUST' ? hb.t('js.wmsLabel.scopeCust') : hb.t('js.wmsLabel.scopeItem')));
                tr.appendChild(hb.el('td', null, r.scopeCd === 'CUST' ? (r.custCd + ' · ' + r.custNm) : (r.itemCd + ' · ' + r.itemNm)));
                tr.appendChild(hb.el('td', null, r.useYn === 'Y' ? hb.t('js.wmsLabel.ruleOn') : hb.t('js.wmsLabel.ruleOff')));
                var td = hb.el('td');
                if (CAN_SAVE) {
                    var b = hb.el('button', 'hb-btn-ghost', r.useYn === 'Y' ? hb.t('js.wmsLabel.ruleStop') : hb.t('js.wmsLabel.ruleUse'));
                    b.setAttribute('type', 'button');
                    b.addEventListener('click', function () {
                        hb.busy(b, function () {
                            return hb.api(RULE + '/' + r.ruleId, {method: 'PUT',
                                body: {tplId: r.tplId, useYn: r.useYn === 'Y' ? 'N' : 'Y', remark: r.remark, rowVer: r.rowVer}});
                        }).then(function (x) { if (x) { notify(hb.t('js.common.saved')); return loadRules(); } },
                            function (err) { notify(err.message, true); });
                    });
                    td.appendChild(b);
                }
                tr.appendChild(td);
                rows.appendChild(tr);
            });
        }, function (err) { notify(err.message, true); });
    }

    var ruleQ = document.getElementById('ruleQ');
    if (ruleQ) {
        var lastQ = '';
        ruleQ.addEventListener('input', function () {
            var q = ruleQ.value.trim();
            if (q.length < 1 || q === lastQ || targetMap[q]) { return; }
            lastQ = q;
            var scope = document.getElementById('ruleScope').value;
            hb.latest('ruleTargets', hb.api(RULE + '/targets?scope=' + scope + '&q=' + encodeURIComponent(q))).then(function (res) {
                var dl = document.getElementById('ruleTargets');
                dl.textContent = '';
                targetMap = {};
                res.data.forEach(function (o) {
                    var label = o.cd + ' · ' + o.nm;
                    targetMap[label] = o.id;
                    var opt = hb.el('option');
                    opt.setAttribute('value', label);
                    dl.appendChild(opt);
                });
            }, function () { /* 고르기 도움만 — 실패해도 추가에서 칸 오류로 */ });
        });
    }
    bind('ruleAddBtn', function (b) {
        if (!current) { return; }
        var scope = document.getElementById('ruleScope').value;
        var id = targetMap[ruleQ.value.trim()];
        if (!id) { notify(hb.t('js.wmsLabel.pickTarget'), true); return; }
        var body = {labelType: current.labelType, scopeCd: scope, tplId: current.tplId};
        if (scope === 'CUST') { body.custId = id; } else { body.itemId = id; }
        hb.busy(b, function () { return hb.api(RULE, {method: 'POST', body: body}); }).then(function (res) {
            if (!res) { return; }
            ruleQ.value = '';
            notify(hb.t('js.common.saved'));
            return loadRules();
        }, function (err) { notify((err.errors && err.errors[0] && err.errors[0].message) || err.message, true); });
    });

    // ---------------------------------------------------------------- 프린터

    function showPrnStatus(r) {
        var box = document.getElementById('prnStatusBox');
        box.hidden = !r;
        if (!r) { return; }
        document.getElementById('prnState').textContent = prnStateNames[r.printerState || 'UNKNOWN'] || (r.printerState || '-');
        var d = [];
        if (r.statusErrors) { d.push(r.statusErrors); }
        if (r.statusWarnings) { d.push(r.statusWarnings); }
        if (r.odometer !== null && r.odometer !== undefined) { d.push(hb.t('js.wmsLabel.odometer', r.odometer)); }
        if (r.checkedAt) { d.push(hb.fmt.datetime(r.checkedAt)); }
        document.getElementById('prnStateDetail').textContent = d.join(' · ');
        var pb = document.getElementById('probeBtn');
        if (pb) { pb.hidden = r.connMode !== 'DIRECT'; }
        var ag = r.connMode === 'AGENT' ? agents.filter(function (x) { return x.terminalId === r.agentTerminalId; })[0] : null;
        document.getElementById('prnAgent').textContent = r.connMode !== 'AGENT' ? ''
            : hb.t('js.wmsLabel.agentLine', r.agentTerminalCd || '-', ag && ag.online ? hb.t('js.wmsLabel.agentOnline') : hb.t('js.wmsLabel.agentOffline'),
                r.agentSeenAt ? hb.fmt.datetime(r.agentSeenAt) : '-');
    }

    /** AGENT 일 때만 에이전트 칸 — 장치 키 발급·폐기는 시스템관리(장치 관리) */
    function syncConnMode() {
        var agent = f(prnForm, 'connMode').value === 'AGENT';
        document.getElementById('agentField').style.display = agent ? '' : 'none';
    }
    f(prnForm, 'connMode').addEventListener('change', syncConnMode);

    bind('probeBtn', function (b) {
        if (!current || tab !== 'PRN') { return; }
        var id = current.printerId;
        hb.busy(b, function () { return hb.api(PRN + '/' + id + '/probe', {method: 'POST'}); }).then(function (res) {
            if (!res) { return; }
            notify(hb.t('js.wmsLabel.probed', prnStateNames[res.data.printerState] || res.data.printerState, res.data.printed));
            return load(id);
        }, function (err) { notify(err.message, true); });
    });

    function showPrn(r) {
        var editing = !!r;
        f(prnForm, 'connMode').value = r ? r.connMode : 'DIRECT';
        f(prnForm, 'cjkFontYn').value = r ? r.cjkFontYn : 'N';
        f(prnForm, 'cjkFontFile').value = r ? (r.cjkFontFile || '') : '';
        nums.mediaWidthMm.setValue(r && r.mediaWidthMm !== null && r.mediaWidthMm !== undefined ? r.mediaWidthMm : '');
        f(prnForm, 'statusCheckYn').value = r ? r.statusCheckYn : 'Y';
        var agSel = f(prnForm, 'agentTerminalId');
        agSel.value = r && r.agentTerminalId ? String(r.agentTerminalId) : (agSel.options.length ? agSel.options[0].value : '');
        syncConnMode();
        showPrnStatus(r);
        f(prnForm, 'plantCd').value = r ? r.plantCd : (sp.values().plantCd || f(prnForm, 'plantCd').value);
        f(prnForm, 'printerCd').value = r ? r.printerCd : '';
        f(prnForm, 'printerNm').value = r ? r.printerNm : '';
        f(prnForm, 'host').value = r ? r.host : '';
        f(prnForm, 'port').value = String(r ? r.port : 9100);   // 포트는 천 단위 쉼표 없이(hb.number 아님)
        f(prnForm, 'dpi').value = String(r ? r.dpi : 203);
        f(prnForm, 'usage').value = r ? r.usage : 'ANY';
        f(prnForm, 'useYn').value = r ? r.useYn : 'Y';
        f(prnForm, 'remark').value = r ? (r.remark || '') : '';
        lockForm(prnForm, CAN_SAVE);
        f(prnForm, 'plantCd').disabled = editing || !CAN_SAVE;
        f(prnForm, 'printerCd').readOnly = editing || !CAN_SAVE;
        document.getElementById('prnMode').textContent = editing ? hb.t('js.wmsLabel.editing', r.printerCd) : hb.t('js.wmsLabel.creatingPrn');
    }

    function tplBody() {
        return {labelType: f(tplForm, 'labelType').value, tplCd: f(tplForm, 'tplCd').value, tplNm: f(tplForm, 'tplNm').value,
            widthMm: nums.widthMm.value(), heightMm: nums.heightMm.value(), dpi: Number(f(tplForm, 'dpi').value),
            defaultYn: f(tplForm, 'defaultYn').value, useYn: f(tplForm, 'useYn').value, body: f(tplForm, 'body').value,
            remark: f(tplForm, 'remark').value, rowVer: current ? current.rowVer : null};
    }

    function portValue() {
        var v = f(prnForm, 'port').value.trim();
        return /^[0-9]{1,5}$/.test(v) ? Number(v) : (v === '' ? null : -1);   // 숫자가 아니면 서버가 칸 오류로
    }

    function prnBody() {
        return {plantCd: f(prnForm, 'plantCd').value, printerCd: f(prnForm, 'printerCd').value,
            printerNm: f(prnForm, 'printerNm').value, host: f(prnForm, 'host').value, port: portValue(),
            dpi: Number(f(prnForm, 'dpi').value), usage: f(prnForm, 'usage').value, useYn: f(prnForm, 'useYn').value,
            remark: f(prnForm, 'remark').value, rowVer: current ? current.rowVer : null,
            connMode: f(prnForm, 'connMode').value, cjkFontYn: f(prnForm, 'cjkFontYn').value, cjkFontFile: f(prnForm, 'cjkFontFile').value,
            mediaWidthMm: nums.mediaWidthMm.value() || null, statusCheckYn: f(prnForm, 'statusCheckYn').value,
            agentTerminalId: f(prnForm, 'connMode').value === 'AGENT' && f(prnForm, 'agentTerminalId').value
                ? Number(f(prnForm, 'agentTerminalId').value) : null};
    }

    function save(btn, form, base, idKey, payload) {
        var editing = !!current;
        var id = editing ? current[idKey] : null;
        hb.busy(btn, function () {
            return editing ? hb.api(base + '/' + id, {method: 'PUT', body: payload}) : hb.api(base, {method: 'POST', body: payload});
        }).then(function (res) {
            if (!res) { return; }
            hb.dirty.clear();
            return load(editing ? id : res.data).then(function () { notify(hb.t('js.common.saved')); });
        }, function (err) {
            notify(err.message, true);
            hb.showFieldErrors(form, err.errors);
        });
    }

    function bind(id, fn) {
        var b = document.getElementById(id);
        if (b) { b.addEventListener('click', function () { fn(b); }); }
    }

    bind('newBtn', function () { confirmLeave().then(function (ok) { if (ok) { show(null); hb.page.openDetail(); } }); });
    bind('tplSaveBtn', function (b) { save(b, tplForm, TPL, 'tplId', tplBody()); });
    bind('prnSaveBtn', function (b) { save(b, prnForm, PRN, 'printerId', prnBody()); });
    bind('sampleBtn', function () {
        var area = f(tplForm, 'body');
        (area.value.trim() ? hb.dialog.confirm({title: hb.t('js.wmsLabel.dlg.sample'), message: hb.t('js.wmsLabel.confirmSample'),
            ok: hb.t('js.wmsLabel.dlg.sampleOk')}) : Promise.resolve(true)).then(function (yes) {
            if (!yes) { return; }
            hb.api(TPL + '/sample?labelType=' + encodeURIComponent(f(tplForm, 'labelType').value)).then(function (res) {
                area.value = res.data.body;
                hb.dirty.mark(tplForm);
            }, function (err) { notify(err.message, true); });
        });
    });
    bind('previewBtn', function (b) {
        hb.showFieldErrors(tplForm, []);
        hb.busy(b, function () {
            return hb.api(TPL + '/preview', {method: 'POST', body: {labelType: f(tplForm, 'labelType').value, body: f(tplForm, 'body').value,
                widthMm: nums.widthMm.value() || null, heightMm: nums.heightMm.value() || null, dpi: Number(f(tplForm, 'dpi').value)}});
        }).then(function (res) {
            if (!res) { return; }
            preview.textContent = res.data.zpl;
            drawLayout(res.data.layout || {});
            showWarnings(res.data);
            document.getElementById('previewBox').hidden = false;
            var n = (res.data.warnings || []).length + (res.data.longWarnings || []).length;
            notify(n ? hb.t('js.wmsLabel.previewedWarn', n) : hb.t('js.wmsLabel.previewed'));
        }, function (err) {
            notify(err.message, true);
            hb.showFieldErrors(tplForm, err.errors);
        });
    });
    bind('testBtn', function (b) {
        if (!current || tab !== 'TPL') { return; }
        var p = document.getElementById('testPrinter').value;
        if (!p) { notify(hb.t('js.wmsLabel.pickPrinter'), true); return; }
        if (hb.dirty.isDirty()) { notify(hb.t('js.wmsLabel.saveFirst'), true); return; }
        hb.busy(b, function () {
            return hb.api(TPL + '/' + current.tplId + '/test-print', {method: 'POST', body: {printerId: Number(p)}});
        }).then(function (res) {
            if (res) { notify(hb.t('js.wmsLabel.queued', res.data)); }
        }, function (err) { notify(err.message, true); });
    });
    f(tplForm, 'labelType').addEventListener('change', showFields);

    function setTab(t) {
        tab = t;
        document.getElementById('tplPane').hidden = t !== 'TPL';
        document.getElementById('prnPane').hidden = t !== 'PRN';
        document.getElementById('tplWrap').hidden = t !== 'TPL';
        document.getElementById('prnWrap').hidden = t !== 'PRN';
        // .hb-search label 의 display 가 hidden 속성을 덮어 style 로 숨긴다
        search.querySelector('[name="labelType"]').closest('label').style.display = t === 'TPL' ? '' : 'none';
        document.getElementById('plantSel').closest('label').style.display = t === 'PRN' ? '' : 'none';
    }

    search.querySelectorAll('input[name="tab"]').forEach(function (r) {
        r.addEventListener('change', function () {
            confirmLeave().then(function (ok) {
                if (!ok) { search.querySelector('input[name="tab"][value="' + tab + '"]').checked = true; return; }
                setTab(r.value);
                load(undefined, true);
            });
        });
    });

    function loadTestPrinters() {
        var sel = document.getElementById('testPrinter');
        if (!sel) { return null; }
        return hb.api(PRN + '?useYn=Y').then(function (res) {
            sel.textContent = '';
            res.data.forEach(function (p) {
                var o = hb.el('option', null, p.plantCd + ' · ' + p.printerCd + ' (' + p.host + ':' + p.port + ')');
                o.value = String(p.printerId);
                sel.appendChild(o);
            });
        });
    }

    hb.dirty.watch(tplForm);
    hb.dirty.watch(prnForm);
    search.addEventListener('submit', function (e) { e.preventDefault(); });
    hb.loadMessages().then(function () {
        hb.page.init();
        nums.widthMm = hb.number(f(tplForm, 'widthMm'));
        nums.heightMm = hb.number(f(tplForm, 'heightMm'));
        nums.mediaWidthMm = hb.number(f(prnForm, 'mediaWidthMm'));
        return Promise.all([hb.loadSession(), hb.codeList('WMS_LABEL_TYPE'), hb.codeList('WMS_PRINTER_USAGE'),
            hb.api(PRN + '/plants'), hb.codes('WMS_PRINTER_STATE'), hb.codeList('EQUIP_CONN_MODE'), hb.api(PRN + '/agents')]);
    }).then(function (r) {
        r[1].forEach(function (c) { typeNames[c.code] = c.name; });
        r[2].forEach(function (c) { usageNames[c.code] = c.name; });
        prnStateNames = r[4];
        agents = r[6].data;
        fill(f(prnForm, 'agentTerminalId'), agents.map(function (x) {
            return {code: String(x.terminalId), name: x.terminalCd + ' · ' + x.terminalNm + (x.online ? '' : ' (' + hb.t('js.wmsLabel.agentOffline') + ')')};
        }), null);
        fill(f(tplForm, 'labelType'), r[1], null);
        fill(f(prnForm, 'usage'), r[2], null);
        // 라벨 프린터 연결 방식: DIRECT(서버가 TCP) · AGENT(공장 PC 에이전트) — 장비의 EDGE 는 프린터에 없다
        fill(f(prnForm, 'connMode'), r[5].filter(function (c) { return c.code === 'DIRECT' || c.code === 'AGENT'; }), null);
        var plants = r[3].data.map(function (p) { return {code: p.plantCd, name: p.plantCd + ' · ' + p.plantNm}; });
        fill(document.getElementById('plantSel'), plants, hb.t('js.wmsLabel.allPlants'));
        fill(f(prnForm, 'plantCd'), plants, null);
        setTab('TPL');
        sp = hb.searchPanel(search, {onSearch: function () {
            return confirmLeave().then(function (ok) { return ok ? load(undefined, true) : null; });
        }});
        grids.TPL = makeGrid('#tplGrid', 'tpl', TPL, 'tplId', function () { return {labelType: sp.values().labelType || ''}; });
        grids.PRN = makeGrid('#prnGrid', 'prn', PRN, 'printerId', function () { return {plantCd: sp.values().plantCd || ''}; });
        return Promise.all([sp.ready, grids.TPL.ready, grids.PRN.ready]).then(function () {
            return Promise.all([load(undefined, true), loadTestPrinters()]);
        });
    }).then(null, function (err) { notify(err && err.message, true); });
})();
