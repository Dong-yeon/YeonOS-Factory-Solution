// @ts-check
/*
 * 라우팅 관리 (mst-routing) — P1 5단계 (docs/20). 구조는 BOM 관리(bom-mgt.js)와 같다.
 *   마스터 = hb.grid(라우팅 머리) + 오른쪽 넓은 상세 / 디테일 = 공정 단계 폼 목록 + 단계별 파라미터 표
 * 단계·파라미터는 "한 벌" 저장(PUT /{id}/steps) — 서버가 id 로 추가·수정·삭제를 맞춘다. 작성 중(DRAFT)만 편집.
 * 서버 데이터는 textContent·value 로만 넣는다.
 */
/**
 * @typedef {{routingId: number, itemCd: string, itemNm?: string, plantCd: string, rev: string, status: string,
 *   validTo?: string | null, rowVer: number, [field: string]: any}} RoutingHead
 * @typedef {{stepId: number, params: {paramId: number, [field: string]: any}[], [field: string]: any}} RoutingStepData
 * @typedef {{bomRev: string, lineNo: number, itemCd: string, itemNm: string, opSeq: number}} RoutingBomOp
 * @typedef {{routing: RoutingHead, steps: RoutingStepData[], unmatchedBomOps: RoutingBomOp[]}} RoutingDetail
 * @typedef {{rowKey: string, paramId?: number, sortNo?: number, [field: string]: any}} RoutingParam
 * @typedef {{rowKey: string, open: boolean, stepId?: number, params: RoutingParam[], [field: string]: any}} RoutingStep
 * @typedef {{value: string, text: string}} RoutingOption
 */
(function () {
    'use strict';

    var PROG = document.body.getAttribute('data-prog') || '';
    var ACTIONS = (document.body.getAttribute('data-actions') || '').split(',');
    var CAN_SAVE = ACTIONS.indexOf('SAVE') >= 0;
    var API = '/api/master/routings';
    var msg = /** @type {HTMLElement} */ (document.getElementById('msg'));
    var search = /** @type {HTMLFormElement} */ (document.getElementById('search'));
    var form = /** @type {HTMLFormElement} */ (document.getElementById('routingForm'));
    var formMode = /** @type {HTMLElement} */ (document.getElementById('formMode'));
    var stepBox = /** @type {HTMLElement} */ (document.getElementById('steps'));
    var bomCheck = /** @type {HTMLElement} */ (document.getElementById('bomCheck'));
    /** 선택된 라우팅 @type {RoutingDetail | null} */
    var current = null;
    /** 단계 상태: StepRow + {rowKey, open, params: [ParamRow + {rowKey}]} @type {RoutingStep[]} */
    var steps = [];
    var keySeq = 0;
    var dirtySteps = false;
    /** @type {{procCd: string, procNm: string, outsrcYn: string}[]} */
    var procs = [];
    /** @type {{plantCd: string, plantNm: string}[]} */
    var plants = [];
    /** @type {{nodeId: number, path: string, nodeNm: string}[]} */
    var places = [];
    /** 공학 단위 선택지(MEAS_UNIT, docs/68 Q2) @type {HB.Code[]} */
    var unitCodes = [];
    /** @type {Record<string, string>} */
    var statusNames = {};
    /** @type {HB.Grid | undefined} */
    var grid;
    /** 조회 조건 패널 @type {HB.SearchPanel} */
    var sp;

    /** @param {string | null | undefined} text @param {boolean} [error] */
    function notify(text, error) {
        msg.textContent = text || '';
        msg.classList.toggle('hb-msg-error', !!error);
    }

    /** 템플릿에 항상 있는 요소 @param {string} id */
    function byId(id) {
        return /** @type {HTMLElement} */ (document.getElementById(id));
    }

    /** 머리 폼 칸 @param {string} name */
    function field(name) {
        return /** @type {HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement} */ (form.querySelector('[data-field="' + name + '"]'));
    }

    function editable() {
        return CAN_SAVE && (!current || current.routing.status === 'DRAFT');
    }

    /** @param {HTMLElement} sel @param {string} value @param {string} text */
    function option(sel, value, text) {
        var o = hb.el('option', null, text);
        o.value = value;
        sel.appendChild(o);
    }

    /** @param {HTMLElement} sel @param {string | null} blankText */
    function fillPlants(sel, blankText) {
        sel.textContent = '';
        if (blankText) { option(sel, '', blankText); }
        plants.forEach(function (p) { option(sel, p.plantCd, p.plantCd + ' · ' + p.plantNm); });
    }

    // ---------- 머리 ----------
    /** @param {RoutingHead | null} r */
    function setForm(r) {
        hb.showFieldErrors(form, []);
        ['itemCd', 'rev', 'validFrom', 'changeNo', 'changeReason', 'remark'].forEach(function (n) {
            var v = r ? r[n] : '';
            field(n).value = v === null || v === undefined ? '' : v;
        });
        field('plantCd').value = r ? r.plantCd : (plants[0] ? plants[0].plantCd : '');
        byId('itemNm').textContent = r ? (r.itemNm || '') : '';
        byId('status').textContent = r ? (statusNames[r.status] || r.status) : '';
        byId('validTo').textContent = r && r.validTo ? r.validTo : '';
        var edit = editable();
        form.querySelectorAll('input, textarea').forEach(function (i) {
            /** @type {HTMLInputElement | HTMLTextAreaElement} */ (i).readOnly = !edit;
        });
        /** @type {HTMLInputElement} */ (field('itemCd')).readOnly = !!r;       // 품목·공장은 바꾸지 않는다(다른 라우팅이 됨)
        field('plantCd').disabled = !!r || !edit;
        hb.dirty.clear();
    }

    function showButtons() {
        var st = current ? current.routing.status : null;
        /** @param {string} id @param {boolean} on */
        function show(id, on) {
            var b = document.getElementById(id);
            if (b) { b.hidden = !on; }
        }
        show('saveBtn', !current || st === 'DRAFT');
        show('releaseBtn', st === 'DRAFT');
        show('newRevBtn', st === 'RELEASED' || st === 'OBSOLETE');
        show('obsoleteBtn', st === 'DRAFT' || st === 'RELEASED');
        var tools = document.getElementById('stepTools');
        if (tools) { tools.hidden = st !== 'DRAFT'; }
    }

    /** @param {RoutingDetail | null} detail */
    function showDetail(detail) {
        current = detail;
        setForm(detail ? detail.routing : null);
        formMode.textContent = !detail ? hb.t('js.mstRouting.creating')
            : detail.routing.status === 'DRAFT' ? hb.t('js.mstRouting.editing', detail.routing.itemCd, detail.routing.rev)
                : hb.t('js.mstRouting.readonly', detail.routing.itemCd, detail.routing.rev);
        byId('stepSection').hidden = !detail;
        dirtySteps = false;
        steps = detail ? detail.steps.map(function (s) {
            // 확정 검사 오류의 행 키는 'S' + stepId (서버 RoutingService.stepKey)
            return Object.assign({}, s, {rowKey: 'S' + s.stepId, open: false, params: s.params.map(function (p) {
                return Object.assign({}, p, {rowKey: 'P' + p.paramId});
            })});
        }) : [];
        // 작업장 목록 — 라우팅을 빨리 바꾸면 앞 요청은 STALE 로 끝난다. 그때는 아무것도 하지 않는다
        // (빈 목록으로 다시 그리면 뒤에 고른 라우팅의 작업장 선택지가 비어 버린다)
        var load = detail ? hb.latest('places', hb.api(API + '/places?plantCd=' + encodeURIComponent(detail.routing.plantCd)))
            .then(function (res) { places = res.data; return true; },
                function (err) { if (err && err.stale) { return false; } places = []; return true; }) : Promise.resolve(true);
        return load.then(function (fresh) {
            if (!fresh) { return; }
            renderSteps();
            renderBomCheck();
            showButtons();
        });
    }

    /** @param {number} id @param {boolean} [keepMsg] */
    function loadDetail(id, keepMsg) {
        if (!keepMsg) { notify(''); }
        return hb.latest('detail', hb.api(API + '/' + id)).then(function (res) { return showDetail(res.data); },
            function (err) { notify(err.message, true); });
    }

    function hasUnsaved() {
        return hb.dirty.isDirty() || dirtySteps;
    }

    /** 미저장 입력이 있으면 화면 안 대화상자로 묻는다(docs/40 V-7) — 없으면 바로 true @returns {Promise<boolean>} */
    function confirmLeave() {
        if (!hasUnsaved()) { return Promise.resolve(true); }
        return hb.dialog.confirm({title: hb.t('js.common.leaveTitle'), message: hb.t('js.common.confirmLeave'), ok: hb.t('js.common.leaveOk')});
    }

    function markDirty() {
        dirtySteps = true;
        hb.dirty.mark();
    }

    // ---------- 단계 ----------
    /**
     * @param {Record<string, any>} obj @param {string} name @param {string} label
     * @param {{maxLength?: number, number?: Record<string, string | number>}} [opts]
     */
    function input(obj, name, label, opts) {
        var o = opts || {};
        var i = hb.el('input');
        i.setAttribute('data-field', name);
        i.setAttribute('aria-label', label);
        i.placeholder = label;
        if (o.maxLength) { i.maxLength = o.maxLength; }
        i.readOnly = !editable();
        var v = obj[name];
        i.value = v === null || v === undefined ? '' : String(v);
        var numOpts = o.number;
        if (numOpts) {
            var nums = numOpts;
            Object.keys(nums).forEach(function (k) { i.setAttribute('data-' + k, String(nums[k])); });
            i.setAttribute('data-pad', 'false');
            var n = hb.number(i);
            n.setValue(v);
            i.addEventListener('blur', function () { obj[name] = n.value(); });
        }
        i.addEventListener('input', function () {
            if (!o.number) { obj[name] = i.value; }
            markDirty();
        });
        return i;
    }

    /**
     * @param {Record<string, any>} obj @param {string} name @param {string} label @param {RoutingOption[]} list
     * @param {string | null} blankText @param {(value: string) => void} [onChange]
     */
    function select(obj, name, label, list, blankText, onChange) {
        var s = hb.el('select');
        s.setAttribute('data-field', name);
        s.setAttribute('aria-label', label);
        s.disabled = !editable();
        if (blankText !== null) { option(s, '', blankText); }
        list.forEach(function (c) { option(s, c.value, c.text); });
        var v = obj[name];
        s.value = v === null || v === undefined ? '' : String(v);
        if (s.value !== (v === null || v === undefined ? '' : String(v)) && v !== null && v !== undefined && v !== '') {
            // 지금 값이 선택지에 없음(사용 중지된 공정·작업장) — 그대로 보여 준다
            option(s, String(v), obj[name + 'Text'] || String(v));
            s.value = String(v);
        }
        s.addEventListener('change', function () {
            obj[name] = s.value;
            markDirty();
            if (onChange) { onChange(s.value); }
        });
        return s;
    }

    /**
     * 공학 단위 고르기(MEAS_UNIT, docs/68 Q2). 코드를 고르면 이름은 서버가 채운다(unitNm 은 보내지 않음).
     * 옛 행(코드 없이 글자만): 같은 이름 코드가 하나면 그 코드로 고르고(서버도 같은 규칙), 없으면 빈 선택지에 "글자 (목록에 없음)"을 보여
     * 그대로 저장한다 — 화면을 열었다 저장했다고 단위가 지워지지 않게
     * @param {RoutingParam} p @param {string} label
     */
    function unitSelect(p, label) {
        if (!p.unitCd && p.unitNm) {
            var hit = unitCodes.filter(function (c) { return c.name === p.unitNm; });
            if (hit.length === 1) { p.unitCd = hit[0].code; }
        }
        var legacy = p.unitCd ? null : (p.unitNm || null);
        p.unitCdText = p.unitNm;   // 지금 코드가 목록에 없을 때(사용 중지·숨김) 보일 이름
        var list = unitCodes.map(function (c) { return {value: c.code, text: c.name}; });
        return select(p, 'unitCd', label, list, legacy ? hb.t('js.common.codeNotListed', legacy) : '', function (v) {
            p.unitNm = v ? null : legacy;
        });
    }

    /** @param {string} text @param {HTMLElement} control */
    function labeled(text, control) {
        var lab = hb.el('label');
        lab.appendChild(hb.el('span', null, text));
        lab.appendChild(control);
        return lab;
    }

    /** @return {RoutingOption[]} */
    function procOptions() {
        return procs.map(function (p) {
            return {value: p.procCd, text: p.procCd + ' · ' + p.procNm + (p.outsrcYn === 'Y' ? ' (' + hb.t('js.proc.outsrc') + ')' : '')};
        });
    }

    /** @return {RoutingOption[]} */
    function placeOptions() {
        return places.map(function (n) { return {value: String(n.nodeId), text: n.path + ' · ' + n.nodeNm}; });
    }

    /** @param {() => void} fn */
    function removeBtn(fn) {
        var b = hb.el('button', 'hb-btn-secondary hb-btn-small', hb.t('js.common.remove'));
        b.type = 'button';
        b.addEventListener('click', fn);
        return b;
    }

    function renderSteps() {
        stepBox.textContent = '';
        var edit = editable();
        if (!steps.length) {
            stepBox.appendChild(hb.el('p', 'hb-muted', hb.t('js.mstRouting.noSteps')));
        }
        steps.forEach(function (s) {
            s.procCdText = s.procCd ? s.procCd + ' · ' + (s.procNm || '') + (s.procUseYn === 'N' ? ' — ' + hb.t('js.mstRouting.inactive') : '') : '';
            s.nodeIdText = s.nodePath ? s.nodePath + ' · ' + (s.nodeNm || '') + (s.nodeUseYn === 'N' ? ' — ' + hb.t('js.mstRouting.inactive') : '') : '';
            var row = hb.el('div', 'hb-bom-row');
            row.setAttribute('role', 'listitem');
            row.setAttribute('data-row-key', s.rowKey);
            var main = hb.el('div', 'hb-routing-main');
            main.appendChild(input(s, 'opSeq', hb.t('js.mstRouting.col.opSeq'), {number: {scale: 0, min: 1, max: 9999}}));
            main.appendChild(select(s, 'procCd', hb.t('js.mstRouting.col.procCd'), procOptions(), hb.t('js.common.choose')));
            main.appendChild(select(s, 'nodeId', hb.t('js.mstRouting.col.nodeId'), placeOptions(), hb.t('js.mstRouting.noNode')));
            main.appendChild(input(s, 'stdCtSec', hb.t('js.mstRouting.col.stdCtSec'), {number: {scale: 3, min: '0.001', max: '999999999.999'}}));
            var more = hb.el('button', 'hb-btn-secondary hb-btn-small',
                hb.t('js.mstRouting.params', s.params.length) + (s.open ? ' ▲' : ' ▼'));
            more.type = 'button';
            more.setAttribute('aria-expanded', s.open ? 'true' : 'false');
            more.addEventListener('click', function () { s.open = !s.open; renderSteps(); });
            main.appendChild(more);
            if (edit) {
                main.appendChild(removeBtn(function () {
                    steps.splice(steps.indexOf(s), 1);
                    markDirty();
                    renderSteps();
                }));
            }
            row.appendChild(main);

            var extra = hb.el('div', 'hb-bom-extra');
            extra.hidden = !s.open;
            extra.appendChild(labeled(hb.t('js.mstRouting.col.unitsPerCycle'), input(s, 'unitsPerCycle', hb.t('js.mstRouting.col.unitsPerCycle'),
                {number: {scale: 0, min: 1, max: 999}})));
            extra.appendChild(labeled(hb.t('js.mstRouting.col.setupMin'), input(s, 'setupMin', hb.t('js.mstRouting.col.setupMin'),
                {number: {scale: 2, min: 0, max: '999999.99'}})));
            var rm = labeled(hb.t('js.mstRouting.col.remark'), input(s, 'remark', hb.t('js.mstRouting.col.remark'), {maxLength: 500}));
            rm.classList.add('hb-bom-wide');
            extra.appendChild(rm);
            extra.appendChild(paramTable(s, edit));
            row.appendChild(extra);
            stepBox.appendChild(row);
        });
    }

    /** @param {RoutingStep} s @param {boolean} edit */
    function paramTable(s, edit) {
        var box = hb.el('div', 'hb-routing-params');
        var t = hb.el('table', 'hb-cmp-table');
        var head = hb.el('tr');
        /** @type {Record<string, string>} */
        var label = {paramCd: hb.t('js.mstRouting.col.paramCd'), paramNm: hb.t('js.mstRouting.col.paramNm'),
            unitNm: hb.t('js.mstRouting.col.unitNm'), lsl: hb.t('js.mstRouting.col.lsl'),
            targetVal: hb.t('js.mstRouting.col.targetVal'), usl: hb.t('js.mstRouting.col.usl')};
        ['paramCd', 'paramNm', 'unitNm', 'lsl', 'targetVal', 'usl'].forEach(function (c) {
            head.appendChild(hb.el('th', null, label[c]));
        });
        if (edit) { head.appendChild(hb.el('th', null, '')); }
        t.appendChild(head);
        s.params.forEach(function (p) {
            var tr = hb.el('tr');
            tr.setAttribute('data-row-key', p.rowKey);
            /** @param {HTMLElement} control */
            function cell(control) {
                var td = hb.el('td');
                td.appendChild(control);
                tr.appendChild(td);
            }
            cell(input(p, 'paramCd', label.paramCd, {maxLength: 30}));
            cell(input(p, 'paramNm', label.paramNm, {maxLength: 100}));
            cell(unitSelect(p, label.unitNm));
            ['lsl', 'targetVal', 'usl'].forEach(function (n) {
                cell(input(p, n, label[n], {number: {scale: 6, min: '-999999999999.999999',
                    max: '999999999999.999999', negative: 'true'}}));
            });
            if (edit) {
                cell(removeBtn(function () {
                    s.params.splice(s.params.indexOf(p), 1);
                    markDirty();
                    renderSteps();
                }));
            }
            t.appendChild(tr);
        });
        box.appendChild(t);
        if (!s.params.length) {
            box.appendChild(hb.el('p', 'hb-muted', hb.t('js.mstRouting.noParams')));
        }
        if (edit) {
            var add = hb.el('button', 'hb-btn-secondary hb-btn-small', hb.t('js.mstRouting.addParam'));
            add.type = 'button';
            add.addEventListener('click', function () {
                s.params.push({rowKey: 'np' + (++keySeq), sortNo: (s.params.length + 1) * 10});
                markDirty();
                renderSteps();
                var last = /** @type {HTMLElement | null} */ (stepBox.querySelector('[data-row-key="' + s.rowKey + '"] .hb-routing-params tr:last-child [data-field="paramCd"]'));
                if (last) { last.focus(); }
            });
            box.appendChild(add);
        }
        return box;
    }

    function addStep() {
        var max = steps.reduce(function (m, s) { return Math.max(m, Number(s.opSeq) || 0); }, 0);
        steps.push({rowKey: 'ns' + (++keySeq), open: false, opSeq: Math.min(9999, Math.floor(max / 10) * 10 + 10),
            unitsPerCycle: 1, setupMin: 0, params: []});
        markDirty();
        renderSteps();
        var last = /** @type {HTMLElement | null} */ (stepBox.querySelector('.hb-bom-row:last-child [data-field="procCd"]'));
        if (last) { last.focus(); }
    }

    /** @param {unknown} v */
    function num(v) {
        return v === null || v === undefined || v === '' ? null : String(v);
    }

    function structure() {
        return {steps: steps.map(function (s) {
            return {rowKey: s.rowKey, stepId: s.stepId || null, opSeq: s.opSeq === null || s.opSeq === '' ? null : Number(s.opSeq),
                procCd: s.procCd || null, nodeId: s.nodeId ? Number(s.nodeId) : null, stdCtSec: num(s.stdCtSec),
                unitsPerCycle: s.unitsPerCycle === null || s.unitsPerCycle === '' ? null : Number(s.unitsPerCycle),
                setupMin: num(s.setupMin), remark: s.remark || null,
                params: s.params.map(function (p) {
                    return {rowKey: p.rowKey, paramId: p.paramId || null, paramCd: p.paramCd || null, paramNm: p.paramNm || null,
                        unitCd: p.unitCd || null, unitNm: p.unitCd ? null : (p.unitNm || null), targetVal: num(p.targetVal), lsl: num(p.lsl), usl: num(p.usl),
                        sortNo: p.sortNo === undefined ? null : Number(p.sortNo)};
                })};
        })};
    }

    /** 행·칸 오류 — 단계 키 'S'+id / 새 단계 'ns…', 파라미터 키 'P'+id / 'np…'. 파라미터·접힌 칸 오류면 단계를 펼친다
     * @param {HB.FieldError[] | undefined} errors */
    function markRowErrors(errors) {
        var reopen = false;
        (errors || []).forEach(function (er) {
            steps.forEach(function (s) {
                var inParam = s.params.some(function (p) { return p.rowKey === er.rowKey; });
                var hidden = s.rowKey === er.rowKey && ['unitsPerCycle', 'setupMin', 'remark'].indexOf(er.field || '') >= 0;
                if ((inParam || hidden) && !s.open) {
                    s.open = true;
                    reopen = true;
                }
            });
        });
        if (reopen) { renderSteps(); }
        stepBox.querySelectorAll('.hb-field-error').forEach(function (e) { e.remove(); });
        stepBox.querySelectorAll('[aria-invalid]').forEach(function (e) { e.removeAttribute('aria-invalid'); });
        (errors || []).forEach(function (er) {
            if (!er.rowKey) { return; }
            var row = stepBox.querySelector('[data-row-key="' + er.rowKey + '"]');
            if (!row) { return; }
            var target = er.field ? row.querySelector('[data-field="' + er.field + '"]') : null;
            (target || row).setAttribute('aria-invalid', 'true');
            // 파라미터 행(TR)은 언제나 .hb-routing-params 안
            var holder = /** @type {Element} */ (row.tagName === 'TR' ? row.closest('.hb-routing-params') : row);
            // 한 줄에 칸이 여럿이라 어느 칸인지 이름을 앞에 붙인다 ("작업장: 필수 입력입니다.")
            var name = target && target.getAttribute('aria-label');
            holder.appendChild(hb.el('span', 'hb-field-error', name ? name + ': ' + er.message : er.message));
        });
        var formErrors = (errors || []).filter(function (er) { return !er.rowKey; });
        hb.showFieldErrors(form, formErrors);
        var stepMsg = formErrors.filter(function (er) { return er.field === 'steps' || er.field === 'procCd' || er.field === 'nodeId'; })[0];
        if (stepMsg) { stepBox.appendChild(hb.el('span', 'hb-field-error', stepMsg.message)); }
        var first = /** @type {HTMLElement | null} */ (stepBox.querySelector('[aria-invalid="true"]'));
        if (first) { first.focus(); }
    }

    /** 목록을 다시 읽고 그 라우팅을 보인다 — 그리드가 아직 준비 전이면(첫 조회 전 저장) 상세만 다시
     * @param {number} id @param {string} doneText */
    function reloadAndShow(id, doneText) {
        var g = grid;
        var reload = g ? g.reload(false, id) : Promise.resolve();
        return reload.then(function () {
            if (!g || !g.selected()) { return loadDetail(id, true); }
        }).then(function () { notify(doneText); });
    }

    /** @param {HTMLButtonElement} btn */
    function saveSteps(btn) {
        if (!current) { return; }
        var routing = current.routing;   // 저장 중에 다른 라우팅을 골라도 이 라우팅으로 마무리
        stepBox.querySelectorAll('input').forEach(function (i) {
            if (i.hbNumber) { i.hbNumber.validate(); }
        });
        if (!dirtySteps) {
            notify(hb.t('js.common.noChanges'));
            return;
        }
        hb.busy(btn, function () {
            return hb.api(API + '/' + routing.routingId + '/steps?rowVer=' + routing.rowVer,
                {method: 'PUT', body: structure()});
        }).then(function (res) {
            if (!res) { return; }
            dirtySteps = false;
            hb.dirty.clear();
            return reloadAndShow(routing.routingId, hb.t('js.common.saved'));
        }, function (err) {
            notify(err.message, true);
            markRowErrors(err.errors);
        });
    }

    function renderBomCheck() {
        var list = current ? current.unmatchedBomOps : [];
        bomCheck.textContent = '';
        bomCheck.hidden = !list.length;
        if (!list.length) { return; }
        bomCheck.appendChild(hb.el('p', null, hb.t('js.mstRouting.bomCheck', list[0].bomRev, list.length)));
        var ul = hb.el('ul');
        list.forEach(function (b) {
            ul.appendChild(hb.el('li', null, hb.t('js.mstRouting.bomCheckRow', b.lineNo, b.itemCd, b.itemNm, b.opSeq)));
        });
        bomCheck.appendChild(ul);
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

    function newRouting() {
        confirmLeave().then(function (ok) {
            if (!ok) { return; }
            showDetail(null);
            hb.page.openDetail();
            field('itemCd').focus();
        });
    }
    bind('newBtn', newRouting);
    bind('newBtnList', newRouting);

    field('itemCd').addEventListener('change', function () {
        var cd = field('itemCd').value.trim();
        var out = byId('itemNm');
        if (!cd || current) { return; }
        hb.latest('itemLookup', hb.api(API + '/item?itemCd=' + encodeURIComponent(cd))).then(function (res) {
            out.textContent = res.data.itemNm + (res.data.useYn === 'Y' ? '' : ' — ' + hb.t('js.mstRouting.inactive'));
        }, function () { out.textContent = hb.t('js.mstRouting.itemNotFound'); });
    });

    bind('saveBtn', function (btn) {
        /** @type {Record<string, unknown>} */
        var b = {};
        ['itemCd', 'plantCd', 'rev', 'validFrom', 'changeNo', 'changeReason', 'remark'].forEach(function (n) { b[n] = field(n).value; });
        var editId = current ? current.routing.routingId : null;   // 저장 중에 다른 행을 골라도 이 라우팅으로 마무리
        if (current) { b.rowVer = current.routing.rowVer; }
        hb.busy(btn, function () {
            return editId !== null ? hb.api(API + '/' + editId, {method: 'PUT', body: b})
                : hb.api(API, {method: 'POST', body: b});
        }).then(function (res) {
            if (!res) { return; }
            hb.dirty.clear();
            return reloadAndShow(editId !== null ? editId : res.data, hb.t('js.common.saved'));
        }, function (err) {
            notify(err.message, true);
            hb.showFieldErrors(form, err.errors);
        });
    });

    /**
     * @param {HTMLButtonElement} btn @param {string} path @param {string | null} confirmText @param {string} doneText
     * @param {Record<string, unknown>} body @param {(res: HB.ApiResponse) => number} [after]
     */
    function action(btn, path, confirmText, doneText, body, after) {
        if (!current) { return; }
        var id = current.routing.routingId;   // 처리 중에 다른 라우팅을 골라도 이 라우팅으로 마무리
        if (hasUnsaved()) {
            notify(hb.t('js.mstRouting.saveFirst'), true);
            return;
        }
        (confirmText ? hb.dialog.confirm({title: btn.textContent.trim(), message: confirmText,
            ok: btn.textContent.trim(), danger: path === 'obsolete'}) : Promise.resolve(true)).then(function (yes) {
            if (!yes) { return; }
            hb.busy(btn, function () {
                return hb.api(API + '/' + id + '/' + path, {method: 'POST', body: body});
            }).then(function (res) {
                if (!res) { return; }
                return reloadAndShow(after ? after(res) : id, doneText);
            }, function (err) {
                notify(err.message, true);
                markRowErrors(err.errors);
            });
        });
    }

    bind('releaseBtn', function (btn) {
        if (!current) { return; }
        action(btn, 'release', hb.t('js.mstRouting.confirmRelease', current.routing.itemCd, current.routing.rev),
            hb.t('js.mstRouting.released'), {rowVer: current.routing.rowVer});
    });
    bind('obsoleteBtn', function (btn) {
        if (!current) { return; }
        action(btn, 'obsolete', hb.t('js.mstRouting.confirmObsolete', current.routing.itemCd, current.routing.rev),
            hb.t('js.mstRouting.obsoleted'), {rowVer: current.routing.rowVer});
    });
    bind('newRevBtn', function (btn) {
        if (!current) { return; }
        if (hasUnsaved()) { notify(hb.t('js.mstRouting.saveFirst'), true); return; }
        var cur = current;   // 묻는 동안 다른 행을 골라도 누른 그때의 것으로
        hb.dialog.prompt({title: hb.t('js.mstRouting.dlg.newRev'), message: hb.t('js.mstBom.revPrompt'),
            label: hb.t('js.mstBom.revLabel'), required: false, maxLength: 10,
            ok: hb.t('js.mstBom.dlg.newRevOk')}).then(function (rev) {
            if (rev === null) { return; }
            action(btn, 'new-revision', null, hb.t('js.mstRouting.revCreated'), {rowVer: cur.routing.rowVer, rev: rev.trim() || null},
                function (res) { return res.data; });
        });
    });
    bind('addStepBtn', addStep);
    bind('saveStepsBtn', saveSteps);

    // ---------- 시작 ----------
    hb.dirty.watch(form);
    search.addEventListener('submit', function (e) { e.preventDefault(); });
    hb.loadMessages().then(function () {
        hb.page.init();
        return Promise.all([hb.api(API + '/plants'), hb.api(API + '/procs'), hb.codes('BOM_STATUS'), hb.codeList('MEAS_UNIT')]);
    }).then(function (r) {
        plants = r[0].data;
        procs = r[1].data;
        statusNames = r[2];
        unitCodes = r[3];
        fillPlants(byId('searchPlant'), hb.t('js.common.all'));
        fillPlants(field('plantCd'), null);
        sp = hb.searchPanel(search, {
            onSearch: function () {
                if (!grid) { return null; }
                var g = grid;
                return confirmLeave().then(function (ok) { return ok ? g.reload(true) : null; });
            }
        });
        return Promise.all([hb.loadSession(), sp.ready]);
    }).then(function () {
        showButtons();
        grid = hb.grid('#routingGrid', {
            progCd: PROG, id: 'main', key: 'routingId',
            status: {status: {DRAFT: 'neutral', RELEASED: 'success', OBSOLETE: 'neutral'}},   // 작성·확정·폐기 = 배지(docs/40 부록 A)
            rowClass: function (row) { return row.status === 'OBSOLETE' ? 'hb-tree-dim' : null; },   // 폐기 행 흐리게
            url: API, columnsUrl: API + '/columns', exportUrl: API + '/export', auditTable: 'mst_routing',
            actions: ACTIONS, params: function () { return sp.values(); },
            filters: {status: 'status', plantCd: 'plantCd'},
            onFilter: function (param, value) { sp.setValue(param, value); },
            beforeSelect: confirmLeave,
            onSelect: function (row) { loadDetail(row.routingId); hb.page.openDetail(); },
            notify: notify
        });
        return grid.ready;
    }).then(null, function (err) { notify(err && err.message, true); });
})();
