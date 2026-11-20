// @ts-check
/*
 * BOM 관리 (mst-bom) — P1 3a (docs/18). 구조는 거래처관리(표준 참조 화면)와 같다.
 *   마스터 = hb.grid(BOM 머리) + 오른쪽 넓은 상세 / 디테일 = 구성 행 폼 목록(일괄 저장 규약, B4)
 * 작성 중(DRAFT)만 편집한다. 확정본은 "새 리비전"으로 복사해 고친 뒤 확정한다(B3).
 * 회사·작성자·일시는 서버가 채운다. 서버 데이터는 textContent·value 로만 넣는다.
 */
/**
 * @typedef {{bomId: number, itemCd: string, itemNm: string, itemUnit: string, rev: string, status: string, rowVer: number,
 *   baseQty: string | number, validFrom: string, validTo?: string | null, [field: string]: any}} BomHead
 * @typedef {{lineId: number, [field: string]: any}} BomLineRow
 * @typedef {{bom: BomHead, lines: BomLineRow[]}} BomDetail
 * @typedef {{rowKey: string, state: 'new' | 'old' | 'deleted', dirty: boolean, open: boolean, lineId?: number, rowVer?: number,
 *   [field: string]: any}} Line
 * @typedef {{itemCd: string, itemNm: string, unitCd: string, spec?: string | null, useYn: string}} ItemLookup
 * @typedef {{qty?: string | number | null, unitCd?: string | null, [field: string]: any}} CmpSide
 * @typedef {{status: 'ADDED' | 'REMOVED' | 'CHANGED' | 'SAME', itemCd: string, itemNm: string, left: CmpSide | null,
 *   right: CmpSide | null, changed: string[]}} CmpRow
 * @typedef {{rev: string, baseQty: string | number | null, [field: string]: any}} CmpHead
 * @typedef {{left: CmpHead, right: CmpHead, added: number, removed: number, changedCount: number, same: number,
 *   rows: CmpRow[]}} CmpResult
 * @typedef {{maxLength?: number, number?: Record<string, string | number>}} InputOpts
 */
(function () {
    'use strict';

    var PROG = document.body.getAttribute('data-prog') || '';
    var ACTIONS = (document.body.getAttribute('data-actions') || '').split(',');
    var CAN_SAVE = ACTIONS.indexOf('SAVE') >= 0;
    var API = '/api/master/boms';
    var msg = /** @type {HTMLElement} */ (document.getElementById('msg'));
    var search = /** @type {HTMLFormElement} */ (document.getElementById('search'));
    var form = /** @type {HTMLFormElement} */ (document.getElementById('bomForm'));
    var formMode = /** @type {HTMLElement} */ (document.getElementById('formMode'));
    var lineBox = /** @type {HTMLElement} */ (document.getElementById('lines'));
    /** 선택된 BOM @type {BomDetail | null} */
    var current = null;
    /** 행 상태: LineRow + {rowKey, state, dirty, open} @type {Line[]} */
    var lines = [];
    var keySeq = 0;
    /** UNIT 공통코드 @type {HB.Code[]} */
    var units = [];
    /** {grp: {code: name}} @type {Record<string, Record<string, string>>} */
    var codeNames = {};
    /** @type {HB.Grid | undefined} */
    var grid;
    /** 조회 조건 패널 @type {HB.SearchPanel} */
    var sp;
    /** 기준 수량 칸 — 화면 준비 전에는 없음 @type {HB.NumberControl | undefined} */
    var baseQty;

    /** @param {string | null | undefined} text @param {boolean} [error] */
    function notify(text, error) {
        msg.textContent = text || '';
        msg.classList.toggle('hb-msg-error', !!error);
    }

    /** 머리 폼 칸 @param {string} name */
    function field(name) {
        return /** @type {HTMLInputElement | HTMLTextAreaElement} */ (form.querySelector('[data-field="' + name + '"]'));
    }

    function editable() {
        return CAN_SAVE && (!current || current.bom.status === 'DRAFT');
    }

    // ---------- 머리 ----------
    /** @param {BomHead | null} bom */
    function setForm(bom) {
        hb.showFieldErrors(form, []);
        ['itemCd', 'rev', 'validFrom', 'changeNo', 'changeReason', 'remark'].forEach(function (n) {
            var v = bom ? bom[n] : '';
            field(n).value = v === null || v === undefined ? '' : v;
        });
        if (baseQty) { baseQty.setValue(bom ? bom.baseQty : null); }
        /** @type {HTMLElement} */ (document.getElementById('itemNm')).textContent = bom ? bom.itemNm + ' (' + bom.itemUnit + ')' : '';
        /** @type {HTMLElement} */ (document.getElementById('status')).textContent = bom ? (codeNames.BOM_STATUS[bom.status] || bom.status) : '';
        /** @type {HTMLElement} */ (document.getElementById('validTo')).textContent = bom && bom.validTo ? bom.validTo : '';
        var edit = editable();
        form.querySelectorAll('input, textarea').forEach(function (i) {
            /** @type {HTMLInputElement | HTMLTextAreaElement} */ (i).readOnly = !edit;
        });
        field('itemCd').readOnly = !!bom;      // 모품목은 바꾸지 않는다(다른 BOM 이 됨)
        hb.dirty.clear();
    }

    /** @param {HB.NumberControl} qty 기준 수량 칸 */
    function headBody(qty) {
        /** @type {Record<string, unknown>} */
        var b = {};
        ['itemCd', 'rev', 'validFrom', 'changeNo', 'changeReason', 'remark'].forEach(function (n) {
            b[n] = field(n).value;
        });
        b.baseQty = qty.value();
        if (current) { b.rowVer = current.bom.rowVer; }
        return b;
    }

    function showButtons() {
        var st = current ? current.bom.status : null;
        /** 버튼(권한에 따라 없을 수 있음) 보이기 @param {string} id @param {boolean} on */
        function show(id, on) {
            var b = document.getElementById(id);
            if (b) { b.hidden = !on; }
        }
        show('saveBtn', !current || st === 'DRAFT');
        show('releaseBtn', st === 'DRAFT');
        show('newRevBtn', st === 'RELEASED' || st === 'OBSOLETE');
        show('obsoleteBtn', st === 'DRAFT' || st === 'RELEASED');
        show('compareBtn', !!current);
        show('copyBtn', !!current);
        show('toTreeBtn', !!current);
        show('toWhereBtn', !!current);
        var tools = document.getElementById('lineTools');
        if (tools) { tools.hidden = st !== 'DRAFT'; }
    }

    /** @param {BomDetail | null} detail */
    function showDetail(detail) {
        current = detail;
        setForm(detail ? detail.bom : null);
        formMode.textContent = !detail ? hb.t('js.mstBom.creating')
            : detail.bom.status === 'DRAFT' ? hb.t('js.mstBom.editing', detail.bom.itemCd, detail.bom.rev)
                : hb.t('js.mstBom.readonly', detail.bom.itemCd, detail.bom.rev);
        /** @type {HTMLElement} */ (document.getElementById('lineSection')).hidden = !detail;
        lines = detail ? detail.lines.map(function (l) {
            // 확정 검사 오류의 행 키는 'L' + lineId (서버 BomService.lineKey)
            /** @type {Line} */
            var row = Object.assign({rowKey: 'L' + l.lineId, state: /** @type {const} */ ('old'), dirty: false, open: hasMore(l)}, l);
            return row;
        }) : [];
        renderLines();
        showButtons();
    }

    /** @param {number} bomId @param {boolean} [keepMsg] */
    function loadDetail(bomId, keepMsg) {
        if (!keepMsg) { notify(''); }
        return hb.latest('detail', hb.api(API + '/' + bomId)).then(function (res) { showDetail(res.data); },
            function (err) { notify(err.message, true); });
    }

    function hasUnsaved() {
        return hb.dirty.isDirty() || lines.some(function (l) { return l.dirty || l.state !== 'old'; });
    }

    /** 미저장 입력이 있으면 화면 안 대화상자로 묻는다(docs/40 V-7) — 없으면 바로 true @returns {Promise<boolean>} */
    function confirmLeave() {
        if (!hasUnsaved()) { return Promise.resolve(true); }
        return hb.dialog.confirm({title: hb.t('js.common.leaveTitle'), message: hb.t('js.common.confirmLeave'), ok: hb.t('js.common.leaveOk')});
    }

    // ---------- 구성 행 ----------
    /** 기본값이 아닌 상세 값이 있으면 펼친 채로 @param {BomLineRow} l */
    function hasMore(l) {
        return (l.lineType && l.lineType !== 'NORMAL') || Number(l.scrapPct) > 0 || l.fixedYn === 'Y' || !!l.altGrp
            || !!l.position || !!l.remark || l.opSeq !== null && l.opSeq !== undefined;
    }

    /** @param {Line} l @param {string} name @param {string} label @param {InputOpts} [opts] */
    function input(l, name, label, opts) {
        /** @type {InputOpts} */
        var o = opts || {};
        var i = hb.el('input');
        i.setAttribute('data-field', name);
        i.setAttribute('aria-label', label);
        i.placeholder = label;
        if (o.maxLength) { i.maxLength = o.maxLength; }
        i.readOnly = !editable();
        var v = l[name];
        i.value = v === null || v === undefined ? '' : String(v);
        var numOpts = o.number;
        if (numOpts) {
            Object.entries(numOpts).forEach(function (e) { i.setAttribute('data-' + e[0], String(e[1])); });
            i.setAttribute('data-pad', 'false');
            var n = hb.number(i);
            n.setValue(v);
            i.addEventListener('blur', function () { l[name] = n.value(); });
        }
        i.addEventListener('input', function () {
            if (!o.number) { l[name] = i.value; }
            l.dirty = true;
            hb.dirty.mark();
        });
        return i;
    }

    /** @param {Line} l @param {string} name @param {string} label @param {string} grp @param {string | null} [blankText] */
    function selectOf(l, name, label, grp, blankText) {
        var s = hb.el('select');
        s.setAttribute('data-field', name);
        s.setAttribute('aria-label', label);
        s.disabled = !editable();
        if (blankText) {
            var b = hb.el('option', null, blankText);
            b.value = '';
            s.appendChild(b);
        }
        (grp === 'UNIT' ? units : Object.keys(codeNames[grp]).map(function (c) {
            return {code: c, name: codeNames[grp][c]};
        })).forEach(function (c) {
            var opt = hb.el('option', null, grp === 'UNIT' ? c.code : c.name);
            opt.value = c.code;
            s.appendChild(opt);
        });
        s.value = l[name] || '';
        s.addEventListener('change', function () {
            l[name] = s.value;
            l.dirty = true;
            hb.dirty.mark();
            if (name === 'lineType') { renderLines(); }
        });
        return s;
    }

    /**
     * 자품목 코드 → 이름·기본 단위. 늦게 온 응답은 행마다 버린다(행 키별 hb.latest) —
     * 한 키를 함께 쓰면 다른 행을 바로 고쳤을 때 앞 행이 STALE 로 "품목 없음" 이 되고 단위도 안 채워졌다.
     * @param {Line} l @param {HTMLElement} nameEl @param {HTMLSelectElement} unitSel
     */
    function lookupItem(l, nameEl, unitSel) {
        var cd = (l.itemCd || '').trim();
        if (!cd) {
            nameEl.textContent = '';
            return;
        }
        hb.latest('itemLookup:' + l.rowKey, hb.api(API + '/item?itemCd=' + encodeURIComponent(cd))).then(function (res) {
            /** @type {ItemLookup} */
            var it = res.data;
            var prevBase = l.itemUnit;
            l.itemCd = it.itemCd;
            l.itemNm = it.itemNm;
            l.itemUnit = it.unitCd;
            nameEl.textContent = it.itemNm + (it.spec ? ' · ' + it.spec : '') + (it.useYn === 'Y' ? '' : ' — ' + hb.t('js.mstBom.inactive'));
            // 단위가 비었거나 이전 자품목의 기본 단위였으면 새 자품목 기본 단위로 — 일부러 고른 다른 단위는 둔다 (QA 3a #8)
            if (!l.unitCd || l.unitCd === prevBase) {
                l.unitCd = it.unitCd;
                unitSel.value = it.unitCd;
            }
        }, function (/** @type {HB.ApiError} */ err) {
            if (err.stale) { return; }   // 같은 행에 더 나중 조회가 있다
            nameEl.textContent = hb.t('js.mstBom.itemNotFound');
        });
    }

    function renderLines() {
        lineBox.textContent = '';
        var edit = editable();
        var visible = lines.filter(function (l) { return l.state !== 'deleted'; });
        if (!visible.length) {
            lineBox.appendChild(hb.el('p', 'hb-muted', hb.t('js.mstBom.noLines')));
        }
        visible.forEach(function (l) {
            var row = hb.el('div', 'hb-bom-row');
            row.setAttribute('role', 'listitem');
            row.setAttribute('data-row-key', l.rowKey);
            var main = hb.el('div', 'hb-bom-main');
            main.appendChild(input(l, 'lineNo', hb.t('js.mstBom.col.lineNo'), {number: {scale: 0, min: 1, max: 9999}}));
            var cd = input(l, 'itemCd', hb.t('js.mstBom.col.itemCd'), {maxLength: 40});
            var nm = hb.el('span', 'hb-bom-name', l.itemNm ? l.itemNm + (l.itemUseYn === 'N' ? ' — ' + hb.t('js.mstBom.inactive') : '') : '');
            var unit = selectOf(l, 'unitCd', hb.t('js.mstBom.col.unitCd'), 'UNIT', null);
            cd.addEventListener('change', function () { lookupItem(l, nm, unit); });
            main.appendChild(cd);
            main.appendChild(nm);
            main.appendChild(input(l, 'qty', hb.t('js.mstBom.col.qty'),
                {number: {scale: 6, min: '0.000001', max: '999999999999.999999'}}));
            main.appendChild(unit);
            var more = hb.el('button', 'hb-btn-secondary hb-btn-small', l.open ? hb.t('js.mstBom.less') : hb.t('js.mstBom.more'));
            more.type = 'button';
            more.setAttribute('aria-expanded', l.open ? 'true' : 'false');
            more.addEventListener('click', function () { l.open = !l.open; renderLines(); });
            main.appendChild(more);
            if (edit) {
                var del = hb.el('button', 'hb-btn-secondary hb-btn-small', hb.t('js.common.remove'));
                del.type = 'button';
                del.addEventListener('click', function () {
                    if (l.state === 'new') {
                        lines.splice(lines.indexOf(l), 1);
                    } else {
                        l.state = 'deleted';
                    }
                    hb.dirty.mark();
                    renderLines();
                });
                main.appendChild(del);
            }
            row.appendChild(main);

            var extra = hb.el('div', 'hb-bom-extra');
            extra.hidden = !l.open;
            var normal = !l.lineType || l.lineType === 'NORMAL';
            extra.appendChild(labeled(hb.t('js.mstBom.col.lineType'), selectOf(l, 'lineType', hb.t('js.mstBom.col.lineType'), 'BOM_LINE_TYPE')));
            extra.appendChild(labeled(hb.t('js.mstBom.col.scrapPct'), input(l, 'scrapPct', hb.t('js.mstBom.col.scrapPct'),
                {number: {scale: 2, min: 0, max: '99.99'}})));
            var fixed = hb.el('input');
            fixed.type = 'checkbox';
            fixed.setAttribute('data-field', 'fixedYn');
            fixed.checked = l.fixedYn === 'Y';
            fixed.disabled = !edit;
            fixed.addEventListener('change', function () { l.fixedYn = fixed.checked ? 'Y' : 'N'; l.dirty = true; hb.dirty.mark(); });
            var fixedLabel = hb.el('label', 'hb-check');
            fixedLabel.appendChild(fixed);
            fixedLabel.appendChild(hb.el('span', null, hb.t('js.mstBom.col.fixedYn')));
            extra.appendChild(fixedLabel);
            if (normal) {
                extra.appendChild(labeled(hb.t('js.mstBom.col.issueMethod'),
                    selectOf(l, 'issueMethod', hb.t('js.mstBom.col.issueMethod'), 'ISSUE_METHOD', hb.t('js.mstBom.auto'))));
                extra.appendChild(labeled(hb.t('js.mstBom.col.altGrp'), input(l, 'altGrp', hb.t('js.mstBom.col.altGrp'), {maxLength: 10})));
                extra.appendChild(labeled(hb.t('js.mstBom.col.altPriority'), input(l, 'altPriority', hb.t('js.mstBom.col.altPriority'),
                    {number: {scale: 0, min: 1, max: 99}})));
                extra.appendChild(labeled(hb.t('js.mstBom.col.altPct'), input(l, 'altPct', hb.t('js.mstBom.col.altPct'),
                    {number: {scale: 2, min: 0, max: 100}})));
            }
            extra.appendChild(labeled(hb.t('js.mstBom.col.opSeq'), input(l, 'opSeq', hb.t('js.mstBom.col.opSeq'),
                {number: {scale: 0, min: 1, max: 9999}})));
            extra.appendChild(labeled(hb.t('js.mstBom.col.position'), input(l, 'position', hb.t('js.mstBom.col.position'), {maxLength: 100})));
            var rm = labeled(hb.t('js.mstBom.col.remark'), input(l, 'remark', hb.t('js.mstBom.col.remark'), {maxLength: 500}));
            rm.classList.add('hb-bom-wide');
            extra.appendChild(rm);
            row.appendChild(extra);
            lineBox.appendChild(row);
        });
    }

    /** @param {string} text @param {HTMLElement} control */
    function labeled(text, control) {
        var lab = hb.el('label');
        lab.appendChild(hb.el('span', null, text));
        lab.appendChild(control);
        return lab;
    }

    function addLine() {
        var max = lines.reduce(function (m, l) { return Math.max(m, Number(l.lineNo) || 0); }, 0);
        lines.push({rowKey: 'n' + (++keySeq), state: 'new', dirty: true, open: false,
            lineNo: Math.min(9999, Math.floor(max / 10) * 10 + 10), lineType: 'NORMAL', fixedYn: 'N'});
        hb.dirty.mark();
        renderLines();
        var last = /** @type {HTMLInputElement | null} */ (lineBox.querySelector('.hb-bom-row:last-child [data-field="itemCd"]'));
        if (last) { last.focus(); }
    }

    /** 서버로 보낼 행(화면 상태 state·dirty·open 제외) @param {Line} l */
    function lineItem(l) {
        var normal = !l.lineType || l.lineType === 'NORMAL';
        return {rowKey: l.rowKey, lineId: l.lineId, rowVer: l.rowVer, lineNo: l.lineNo === null || l.lineNo === '' ? null : Number(l.lineNo),
            itemCd: l.itemCd, lineType: l.lineType, qty: l.qty, unitCd: l.unitCd, scrapPct: l.scrapPct, fixedYn: l.fixedYn,
            issueMethod: normal ? l.issueMethod : null, opSeq: l.opSeq === null || l.opSeq === '' || l.opSeq === undefined ? null : Number(l.opSeq),
            altGrp: normal ? l.altGrp : null,
            altPriority: normal && l.altPriority !== null && l.altPriority !== '' && l.altPriority !== undefined ? Number(l.altPriority) : null,
            altPct: normal ? l.altPct : null, position: l.position, remark: l.remark};
    }

    /** 행·필드 오류 표시 — 확정 검사 오류는 'L' + lineId 키, 접힌 칸이면 펼친다 @param {HB.FieldError[] | undefined} errors */
    function markRowErrors(errors) {
        var reopen = false;
        (errors || []).forEach(function (er) {
            var l = lines.filter(function (x) { return x.rowKey === er.rowKey; })[0];
            if (l && er.field && ['itemCd', 'qty', 'unitCd', 'lineNo'].indexOf(er.field) < 0 && !l.open) {
                l.open = true;
                reopen = true;
            }
        });
        if (reopen) { renderLines(); }
        lineBox.querySelectorAll('.hb-field-error').forEach(function (e) { e.remove(); });
        lineBox.querySelectorAll('[aria-invalid]').forEach(function (e) { e.removeAttribute('aria-invalid'); });
        (errors || []).forEach(function (er) {
            if (!er.rowKey) { return; }
            var row = lineBox.querySelector('[data-row-key="' + er.rowKey + '"]');
            if (!row) { return; }
            var target = er.field ? row.querySelector('[data-field="' + er.field + '"]') : null;
            (target || row).setAttribute('aria-invalid', 'true');
            row.appendChild(hb.el('span', 'hb-field-error', er.message));
        });
        var formErrors = (errors || []).filter(function (er) { return !er.rowKey; });
        hb.showFieldErrors(form, formErrors);
        var lineMsg = formErrors.filter(function (er) { return er.field === 'lines'; })[0];
        if (lineMsg) { lineBox.appendChild(hb.el('span', 'hb-field-error', lineMsg.message)); }
        var first = /** @type {HTMLElement | null} */ (lineBox.querySelector('[aria-invalid="true"]'));
        if (first && first.focus && !formErrors.length) { first.focus(); }
    }

    /** 목록 다시 읽기 → 같은 행을 못 고르면(조회 조건 밖) 상세만 다시. 그리드 준비 전이면 상세만 @param {number} id */
    function reloadAndShow(id) {
        if (!grid) { return loadDetail(id, true); }
        var g = grid;
        return g.reload(false, id).then(function () {
            if (!g.selected()) { return loadDetail(id, true); }
        });
    }

    /** @param {HTMLButtonElement} btn */
    function saveLines(btn) {
        if (!current) { return Promise.resolve(false); }
        var bom = current.bom;   // 저장 중에 다른 BOM 을 골라도 이 BOM 으로 마무리
        lineBox.querySelectorAll('input').forEach(function (i) {
            if (i.hbNumber) { i.hbNumber.validate(); }
        });
        var batch = {
            created: lines.filter(function (l) { return l.state === 'new'; }).map(lineItem),
            updated: lines.filter(function (l) { return l.state === 'old' && l.dirty; }).map(lineItem),
            deleted: lines.filter(function (l) { return l.state === 'deleted'; }).map(lineItem)
        };
        if (!batch.created.length && !batch.updated.length && !batch.deleted.length) {
            notify(hb.t('js.common.noChanges'));
            return Promise.resolve(true);
        }
        return hb.busy(btn, function () {
            return hb.api(API + '/' + bom.bomId + '/lines/batch?rowVer=' + bom.rowVer,
                {method: 'POST', body: batch});
        }).then(function (res) {
            if (!res) { return false; }
            var id = bom.bomId;
            hb.dirty.clear();
            return reloadAndShow(id).then(function () { notify(hb.t('js.common.saved')); return true; });
        }, function (err) {
            notify(err.message, true);
            markRowErrors(err.errors);
            return false;
        });
    }

    // ---------- 버튼 ----------
    /** 버튼(권한에 따라 없을 수 있음)에 클릭 연결 @param {string} id @param {(b: HTMLButtonElement) => void} fn */
    function bind(id, fn) {
        var b = /** @type {HTMLButtonElement | null} */ (document.getElementById(id));
        if (b) {
            var btn = b;
            btn.addEventListener('click', function () { fn(btn); });
        }
    }

    // 바로가기(docs/60 X2): 이 BOM 의 모품목으로 전개·역전개
    bind('toTreeBtn', function () { if (current) { bomUi.open('mst-bom-tree', {itemCd: current.bom.itemCd}); } });
    bind('toWhereBtn', function () { if (current) { bomUi.open('mst-bom-where', {itemCd: current.bom.itemCd}); } });

    function newBom() {
        confirmLeave().then(function (ok) {
            if (!ok) { return; }
            showDetail(null);
            hb.page.openDetail();
            field('itemCd').focus();
        });
    }
    bind('newBtn', newBom);
    bind('newBtnList', newBom);
    bind('importBtn', function () {
        hb.importDialog({templateCd: 'MST_BOM', onDone: function () { if (grid) { grid.reload(true); } }});
    });

    bind('saveBtn', function (btn) {
        if (!baseQty || !baseQty.validate().ok) { return; }   // 화면 준비 전이면 아무것도 하지 않는다
        var body = headBody(baseQty);
        var editId = current ? current.bom.bomId : null;   // 저장 중에 다른 행을 골라도 이 BOM 으로 마무리
        hb.busy(btn, function () {
            return editId !== null ? hb.api(API + '/' + editId, {method: 'PUT', body: body})
                : hb.api(API, {method: 'POST', body: body});
        }).then(function (res) {
            if (!res) { return; }
            /** @type {number} */
            var id = editId !== null ? editId : res.data;
            hb.dirty.clear();
            return reloadAndShow(id).then(function () { notify(hb.t('js.common.saved')); });
        }, function (err) {
            notify(err.message, true);
            hb.showFieldErrors(form, err.errors);
        });
    });

    /**
     * 상태 동작(확정·폐기·새 리비전) — 저장 안 한 변경이 있으면 먼저 저장하라고 알린다
     * @param {HTMLButtonElement} btn @param {string} path @param {string | null} confirmText @param {string} doneText
     * @param {Record<string, unknown>} body @param {(res: HB.ApiResponse) => number} [after] 결과에서 다시 고를 BOM id
     */
    function action(btn, path, confirmText, doneText, body, after) {
        if (!current) { return; }
        var bomId = current.bom.bomId;   // 처리 중에 다른 행을 골라도 이 BOM 으로 마무리
        if (hasUnsaved()) {
            notify(hb.t('js.mstBom.saveFirst'), true);
            return;
        }
        (confirmText ? hb.dialog.confirm({title: btn.textContent.trim(), message: confirmText,
            ok: btn.textContent.trim(), danger: path === 'obsolete'}) : Promise.resolve(true)).then(function (yes) {
            if (!yes) { return; }
            hb.busy(btn, function () {
                return hb.api(API + '/' + bomId + '/' + path, {method: 'POST', body: body});
            }).then(function (res) {
                if (!res) { return; }
                var id = after ? after(res) : bomId;
                return reloadAndShow(id).then(function () { notify(doneText); });
            }, function (err) {
                notify(err.message, true);
                markRowErrors(err.errors);
            });
        });
    }

    bind('releaseBtn', function (btn) {
        if (!current) { return; }
        action(btn, 'release', hb.t('js.mstBom.confirmRelease', current.bom.itemCd, current.bom.rev),
            hb.t('js.mstBom.released'), {rowVer: current.bom.rowVer});
    });
    bind('obsoleteBtn', function (btn) {
        if (!current) { return; }
        action(btn, 'obsolete', hb.t('js.mstBom.confirmObsolete', current.bom.itemCd, current.bom.rev),
            hb.t('js.mstBom.obsoleted'), {rowVer: current.bom.rowVer});
    });
    bind('newRevBtn', function (btn) {
        if (!current) { return; }
        if (hasUnsaved()) { notify(hb.t('js.mstBom.saveFirst'), true); return; }
        var cur = current;   // 묻는 동안 다른 행을 골라도 누른 그때의 것으로
        // 리비전: 비우면 자동(A0 → A1 … Z9 → AA0), 회사 리비전 체계가 있으면 직접 (QA 3a #4)
        hb.dialog.prompt({title: hb.t('js.mstBom.dlg.newRev'), message: hb.t('js.mstBom.revPrompt'),
            label: hb.t('js.mstBom.revLabel'), required: false, maxLength: 10,
            ok: hb.t('js.mstBom.dlg.newRevOk')}).then(function (rev) {
            if (rev === null) { return; }
            action(btn, 'new-revision', null, hb.t('js.mstBom.revCreated'), {rowVer: cur.bom.rowVer, rev: rev.trim() || null},
                function (res) { return res.data; });
        });
    });
    // ---------- 비교 · 다른 품목으로 복사 (P1 3c) ----------
    /** @param {string} title */
    function dialog(title) {
        var d = hb.el('dialog', 'hb-dialog hb-bom-dialog');
        d.appendChild(hb.el('h2', null, title));
        var body = hb.el('div', 'hb-dialog-body');
        var bar = hb.el('div', 'hb-dialog-bar');
        d.appendChild(body);
        d.appendChild(bar);
        d.addEventListener('close', function () { d.remove(); });
        document.body.appendChild(d);
        return {d: d, body: body, bar: bar};
    }

    /** @param {string} text @param {string | null} cls @param {(b: HTMLButtonElement) => void} fn */
    function button(text, cls, fn) {
        var b = hb.el('button', cls || null, text);
        b.type = 'button';
        b.addEventListener('click', function () { fn(b); });
        return b;
    }

    /** @param {string | number | null | undefined} v */
    function num(v) {
        return v === null || v === undefined ? '' : String(Number(v));
    }

    /** @param {HTMLElement} box @param {CmpResult} res */
    function showCompare(box, res) {
        box.textContent = '';
        box.appendChild(hb.el('p', 'hb-muted', hb.t('js.mstBom.cmp.summary', res.left.rev, res.right.rev, res.added, res.removed,
            res.changedCount, res.same)));
        if (Number(res.left.baseQty) !== Number(res.right.baseQty)) {
            // 기준 수량이 다르면 행 수량은 기준 수량당으로 비교했다 — 같은 숫자가 "변경", 다른 숫자가 "같음" 일 수 있어 알린다
            box.appendChild(hb.el('p', 'hb-notice', hb.t('js.mstBom.cmp.baseQty', num(res.left.baseQty), num(res.right.baseQty))));
        }
        var t = hb.el('table', 'hb-cmp-table');
        var head = hb.el('tr');
        [hb.t('js.mstBom.cmp.status'), hb.t('js.mstBom.col.itemCd'), hb.t('js.mstBom.cmp.name'),
            res.left.rev + ' ' + hb.t('js.mstBom.col.qty'), res.right.rev + ' ' + hb.t('js.mstBom.col.qty'),
            hb.t('js.mstBom.col.unitCd'), hb.t('js.mstBom.cmp.changed')].forEach(function (h) { head.appendChild(hb.el('th', null, h)); });
        t.appendChild(head);
        var label = {ADDED: hb.t('js.mstBom.cmp.ADDED'), REMOVED: hb.t('js.mstBom.cmp.REMOVED'),
            CHANGED: hb.t('js.mstBom.cmp.CHANGED'), SAME: hb.t('js.mstBom.cmp.SAME')};
        /** @type {Record<string, string>} */
        var field = {qty: hb.t('js.mstBom.col.qty'), unitCd: hb.t('js.mstBom.col.unitCd'), scrapPct: hb.t('js.mstBom.col.scrapPct'),
            fixedYn: hb.t('js.mstBom.col.fixedYn'), issueMethod: hb.t('js.mstBom.col.issueMethod'),
            alt: hb.t('js.mstBom.col.altGrp'), position: hb.t('js.mstBom.col.position')};
        res.rows.forEach(function (r) {
            var tr = hb.el('tr', 'hb-cmp-' + r.status.toLowerCase());
            tr.appendChild(hb.el('td', 'hb-cmp-status', label[r.status]));
            tr.appendChild(hb.el('td', null, r.itemCd));
            tr.appendChild(hb.el('td', null, r.itemNm));
            tr.appendChild(hb.el('td', 'hb-num', r.left ? num(r.left.qty) : ''));
            tr.appendChild(hb.el('td', 'hb-num', r.right ? num(r.right.qty) : ''));
            var side = r.right || r.left;
            tr.appendChild(hb.el('td', null, side ? side.unitCd : ''));
            tr.appendChild(hb.el('td', null, r.changed.map(function (c) { return field[c] || c; }).join(', ')));
            t.appendChild(tr);
        });
        box.appendChild(t);
    }

    bind('compareBtn', function () {
        if (!current) { return; }
        var me = current.bom;
        var dl = dialog(hb.t('js.mstBom.cmp.title', me.itemCd));
        var pick = hb.el('select');
        pick.setAttribute('aria-label', hb.t('js.mstBom.cmp.with'));
        var lab = hb.el('label', 'hb-cmp-pick');
        lab.appendChild(hb.el('span', null, hb.t('js.mstBom.cmp.with')));
        lab.appendChild(pick);
        dl.body.appendChild(lab);
        var out = hb.el('div');
        dl.body.appendChild(out);
        dl.bar.appendChild(button(hb.t('js.common.close'), 'hb-btn-secondary', function () { dl.d.close(); }));
        function run() {
            if (!pick.value) { return; }
            hb.latest('compare', hb.api(API + '/compare?leftId=' + pick.value + '&rightId=' + me.bomId)).then(function (res) { showCompare(out, res.data); },
                function (err) { out.textContent = err.message; });
        }
        pick.addEventListener('change', run);
        hb.latest('compareList', hb.api(API + '?itemCd=' + encodeURIComponent(me.itemCd) + '&withObsolete=true&size=100&total=false')).then(function (res) {
            /** @type {BomHead[]} */
            var list = res.list || [];
            var others = list.filter(function (b) { return b.itemCd === me.itemCd && b.bomId !== me.bomId; });
            if (!others.length) {
                out.appendChild(hb.el('p', 'hb-muted', hb.t('js.mstBom.cmp.none')));
                pick.disabled = true;
                return;
            }
            others.forEach(function (b) {
                var o = hb.el('option', null, b.rev + ' · ' + (codeNames.BOM_STATUS[b.status] || b.status) + ' · ' + b.validFrom);
                o.value = String(b.bomId);
                pick.appendChild(o);
            });
            run();
        }, function (err) { out.textContent = err.message; });
        dl.d.showModal();
    });

    bind('copyBtn', function () {
        if (!current) { return; }
        var me = current.bom;
        var dl = dialog(hb.t('js.mstBom.copy.title', me.itemCd, me.rev));
        var f = hb.el('div', 'hb-form');
        var cd = hb.el('input');
        cd.setAttribute('data-field', 'itemCd');
        cd.maxLength = 40;
        var from = hb.el('input');
        from.type = 'date';
        from.setAttribute('data-field', 'validFrom');
        f.appendChild(labeled(hb.t('js.mstBom.copy.target'), cd));
        f.appendChild(labeled(hb.t('js.mstBom.copy.validFrom'), from));
        dl.body.appendChild(hb.el('p', 'hb-muted', hb.t('js.mstBom.copy.hint')));
        dl.body.appendChild(f);
        var err = hb.el('p', 'hb-msg-error');
        dl.body.appendChild(err);
        dl.bar.appendChild(button(hb.t('js.common.close'), 'hb-btn-secondary', function () { dl.d.close(); }));
        dl.bar.appendChild(button(hb.t('js.mstBom.copy.run'), null, function (b) {
            hb.busy(b, function () {
                return hb.api(API + '/' + me.bomId + '/copy', {method: 'POST', body: {itemCd: cd.value, validFrom: from.value || null}});
            }).then(function (res) {
                if (!res) { return; }
                dl.d.close();
                return reloadAndShow(res.data).then(function () { notify(hb.t('js.mstBom.copy.done')); });
            }, function (e) {
                err.textContent = e.message;
                hb.showFieldErrors(f, e.errors);
            });
        }));
        dl.d.showModal();
        cd.focus();
    });

    bind('addLineBtn', addLine);
    bind('saveLinesBtn', saveLines);

    // ---------- 시작 ----------
    hb.dirty.watch(form);
    search.addEventListener('submit', function (e) { e.preventDefault(); });
    hb.loadMessages().then(function () {
        hb.page.init();
        baseQty = hb.number(/** @type {HTMLInputElement} */ (field('baseQty')));
        sp = hb.searchPanel(search, {
            onSearch: function () {
                if (!grid) { return null; }
                var g = grid;
                return confirmLeave().then(function (ok) { return ok ? g.reload(true) : null; });
            }
        });
        return Promise.all([hb.loadSession(), sp.ready, hb.codeList('UNIT'), hb.codes('BOM_STATUS'),
            hb.codes('BOM_LINE_TYPE'), hb.codes('ISSUE_METHOD')]);
    }).then(function (r) {
        units = r[2];
        codeNames = {BOM_STATUS: r[3], BOM_LINE_TYPE: r[4], ISSUE_METHOD: r[5]};
        showButtons();
        var g = hb.grid('#bomGrid', {
            progCd: PROG, id: 'main', key: 'bomId',
            status: {status: {DRAFT: 'neutral', RELEASED: 'success', OBSOLETE: 'neutral'}},   // 작성·확정·폐기 = 배지(docs/40 부록 A)
            rowClass: function (row) { return row.status === 'OBSOLETE' ? 'hb-tree-dim' : null; },   // 폐기 행 흐리게
            url: API, columnsUrl: API + '/columns', exportUrl: API + '/export', auditTable: 'mst_bom',
            actions: ACTIONS, params: function () { return sp.values(); },
            filters: {status: 'status'},
            onFilter: function (param, value) { sp.setValue(param, value); },
            beforeSelect: confirmLeave,
            onSelect: function (row) { loadDetail(row.bomId); hb.page.openDetail(); },
            notify: notify
        });
        grid = g;
        return g.ready;
    }).then(null, function (err) { notify(err && err.message, true); });
})();
