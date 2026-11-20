/*
 * 검사계획 관리 (qms-insp-plan) — P3 QMS Q1 (docs/29). 구조는 라우팅 관리(routing-mgt.js)와 같다.
 *   마스터 = hb.grid(계획 머리) + 오른쪽 넓은 상세 / 디테일 = 검사 항목 폼 목록
 * 항목은 "한 벌" 저장(PUT /{id}/lines) — 서버가 id 로 추가·수정·삭제를 맞춘다. 작성 중(DRAFT)만 편집.
 * 특성 선택지는 계획마다 서버가 거른 목록(GET /{id}/chars). 확정본에서 특성 규격이 바뀐 행은 배지 + 지금 규격을 함께 보인다.
 * 서버 데이터는 textContent·value 로만 넣는다.
 */
(function () {
    'use strict';

    /** QMS 화면 공용 — 상태 배지·대화상자(qms-ui.js) */
    var qmsUi = /** @type {QmsUi} */ (/** @type {any} */ (window).qmsUi);

    var PROG = document.body.getAttribute('data-prog');
    var ACTIONS = (document.body.getAttribute('data-actions') || '').split(',');
    var CAN_SAVE = ACTIONS.indexOf('SAVE') >= 0;
    var API = '/api/qms/insp-plans';
    var msg = document.getElementById('msg');
    var search = document.getElementById('search');
    var form = document.getElementById('planForm');
    var formMode = document.getElementById('formMode');
    var specNote = document.getElementById('specNote');
    var lineBox = document.getElementById('lines');
    var current = null;          // {plan, lines}
    var lines = [];              // 행 상태: LineRow + {rowKey, open}
    var chars = [];              // 이 계획의 특성 선택지
    var keySeq = 0;
    var dirtyLines = false;
    var procs = [], classes = [], samplings = [], gauges = [], statusNames = {}, freqNames = {}, specialNames = {}, evidenceNames = {};
    var grid, sp, typeSel;

    function notify(text, error) {
        msg.textContent = text || '';
        msg.classList.toggle('hb-msg-error', !!error);
    }

    function field(name) {
        return form.querySelector('[data-field="' + name + '"]');
    }

    function editable() {
        return CAN_SAVE && (!current || current.plan.status === 'DRAFT');
    }

    function option(sel, value, text) {
        var o = hb.el('option', null, text);
        o.value = value;
        sel.appendChild(o);
    }

    function kind() {
        var r = form.querySelector('input[name="targetKind"]:checked');
        return r ? r.value : 'ITEM';
    }

    function layoutKind() {
        var k = kind();
        form.querySelectorAll('.hb-qms-m').forEach(function (l) { l.hidden = l.getAttribute('data-kind') !== k; });
    }

    function fillSelect(sel, list, blankText, value, valueText) {
        sel.textContent = '';
        if (blankText !== null) { option(sel, '', blankText); }
        list.forEach(function (x) { option(sel, x.cd, x.cd + ' · ' + x.nm); });
        sel.value = value || '';
        if (value && sel.value !== value) {
            // 지금 값이 선택지에 없음(사용 중지) — 그대로 보여 준다
            option(sel, value, valueText || value);
            sel.value = value;
        }
    }

    // ---------- 머리 ----------
    function setForm(p) {
        hb.showFieldErrors(form, []);
        ['itemCd', 'rev', 'validFrom', 'changeNo', 'changeReason', 'remark'].forEach(function (n) {
            var v = p ? p[n] : '';
            field(n).value = v === null || v === undefined ? '' : v;
        });
        typeSel.setValue(p ? p.inspType : '');
        var isClass = !!(p && p.itemClassId);
        form.querySelector('input[name="targetKind"][value="' + (isClass ? 'CLASS' : 'ITEM') + '"]').checked = true;
        fillSelect(field('classCd'), classes, hb.t('js.common.choose'), p ? p.classCd : '',
            p && p.classCd ? p.classCd + ' · ' + (p.classNm || '') + ' — ' + hb.t('js.qms.inactive') : null);
        fillSelect(field('procCd'), procs, hb.t('js.qms.plan.anyProc'), p ? p.procCd : '',
            p && p.procCd ? p.procCd + ' · ' + (p.procNm || '') + ' — ' + hb.t('js.qms.inactive') : null);
        document.getElementById('itemNm').textContent = p && p.itemId ? p.itemNm + (p.itemUseYn === 'Y' ? '' : ' — ' + hb.t('js.qms.inactive')) : '';
        document.getElementById('status').textContent = p ? (statusNames[p.status] || p.status) : '';
        document.getElementById('validTo').textContent = p && p.validTo ? p.validTo : '';
        var edit = editable();
        form.querySelectorAll('input, textarea').forEach(function (i) { i.readOnly = !edit; });
        form.querySelectorAll('select, input[type=radio]').forEach(function (s) { s.disabled = !edit; });
        // 대상 키(유형·품목|클래스·공정)는 만든 뒤 바꾸지 않는다(다른 계획이 됨)
        if (p) {
            field('inspType').disabled = true;
            field('itemCd').readOnly = true;
            field('classCd').disabled = true;
            field('procCd').disabled = true;
            form.querySelectorAll('input[name="targetKind"]').forEach(function (r) { r.disabled = true; });
        }
        var changed = p ? p.specChangedCnt : 0;
        specNote.hidden = !changed;
        specNote.textContent = changed ? hb.t('js.qms.plan.specNote', changed) : '';
        layoutKind();
        hb.dirty.clear();
    }

    function showButtons() {
        var st = current ? current.plan.status : null;
        function show(id, on) {
            var b = document.getElementById(id);
            if (b) { b.hidden = !on; }
        }
        show('saveBtn', !current || st === 'DRAFT');
        show('releaseBtn', st === 'DRAFT');
        show('newRevBtn', st === 'RELEASED' || st === 'OBSOLETE');
        show('obsoleteBtn', st === 'DRAFT' || st === 'RELEASED');
        var tools = document.getElementById('lineTools');
        if (tools) { tools.hidden = st !== 'DRAFT'; }
    }

    function showDetail(detail) {
        current = detail;
        setForm(detail ? detail.plan : null);
        var p = detail ? detail.plan : null;
        formMode.textContent = !p ? hb.t('js.qms.plan.creating')
            : p.status === 'DRAFT' ? hb.t('js.qms.plan.editing', p.targetCd, p.rev)
                : hb.t('js.qms.plan.readonly', p.targetCd, p.rev);
        document.getElementById('lineSection').hidden = !detail;
        dirtyLines = false;
        lines = detail ? detail.lines.map(function (l) {
            // 확정 검사 오류의 행 키는 'L' + lineId (서버 InspPlanService.lineKey)
            return Object.assign({}, l, {rowKey: 'L' + l.lineId, open: false});
        }) : [];
        var load = detail && detail.plan.status === 'DRAFT'
            ? hb.latest('chars', hb.api(API + '/' + detail.plan.inspPlanId + '/chars')).then(function (res) { chars = res.data; },
                function () { chars = []; })
            : Promise.resolve(chars = []);
        return load.then(function () {
            renderLines();
            showButtons();
            renderDoc();
        });
    }

    // ---------- 검사기준서 연결 (Q3) — 선택지는 처음 필요할 때 한 번 ----------
    var docChoices = null, docStatusNames = {};

    function renderDoc() {
        var section = document.getElementById('docSection');
        var p = current ? current.plan : null;
        section.hidden = !p;
        if (!p) { return; }
        var sel = document.getElementById('docSel');
        var note = document.getElementById('docNote');
        var draft = p.status === 'DRAFT' && CAN_SAVE;
        var btn = document.getElementById('docLinkBtn');
        if (btn) { btn.hidden = !draft; }
        sel.disabled = !draft;
        note.textContent = p.docRevStatus === 'OBSOLETE' ? hb.t('js.qms.plan.docObsolete') : '';
        var fill = function () {
            sel.textContent = '';
            option(sel, '', hb.t('js.qms.plan.docNone'));
            (docChoices || []).forEach(function (d) {
                option(sel, String(d.docRevId), d.docNo + ' / ' + d.rev + ' · ' + d.docNm + ' (' + (docStatusNames[d.status] || d.status) + ')');
            });
            if (p.docRevId && !sel.querySelector('option[value="' + p.docRevId + '"]')) {
                option(sel, String(p.docRevId), (p.docLabel || p.docRevId) + ' (' + hb.t('js.qms.inactive') + ')');
            }
            sel.value = p.docRevId ? String(p.docRevId) : '';
        };
        if (docChoices) { fill(); return; }
        Promise.all([hb.api(API + '/docs'), hb.codes('DOC_REV_STATUS')]).then(function (r) {
            docChoices = r[0].data;
            docStatusNames = r[1];
            fill();
        }, function () { docChoices = []; fill(); });
    }

    var docLinkBtn = document.getElementById('docLinkBtn');
    if (docLinkBtn) {
        docLinkBtn.addEventListener('click', function () {
            if (!current) { return; }
            var plan = current.plan;
            var v = document.getElementById('docSel').value;
            hb.busy(docLinkBtn, function () {
                return hb.api(API + '/' + plan.inspPlanId + '/doc', {method: 'PUT', body: {docRevId: v ? Number(v) : null, rowVer: plan.rowVer}});
            }).then(function (res) {
                if (!res) { return; }
                return loadDetail(plan.inspPlanId, true).then(function () { notify(hb.t('js.qms.plan.docLinked')); });
            }, function (err) {
                notify(err.message + (err.errors && err.errors.length ? ' ' + err.errors.map(function (e) { return e.message; }).join(' ') : ''), true);
            });
        });
    }

    function loadDetail(id, keepMsg) {
        if (!keepMsg) { notify(''); }
        return hb.latest('detail', hb.api(API + '/' + id)).then(function (res) { return showDetail(res.data); },
            function (err) { notify(err.message, true); });
    }

    function hasUnsaved() {
        return hb.dirty.isDirty() || dirtyLines;
    }

    function confirmLeave() {
        return qmsUi.leaveOk(hasUnsaved());
    }

    function markDirty() {
        dirtyLines = true;
        hb.dirty.mark();
    }

    // ---------- 항목 ----------
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
        if (o.number) {
            Object.keys(o.number).forEach(function (k) { i.setAttribute('data-' + k, o.number[k]); });
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

    function select(obj, name, label, list, blankText, onChange) {
        var s = hb.el('select');
        s.setAttribute('data-field', name);
        s.setAttribute('aria-label', label);
        s.disabled = !editable();
        if (blankText !== null) { option(s, '', blankText); }
        list.forEach(function (c) { option(s, c.value, c.text); });
        var v = obj[name];
        var sv = v === null || v === undefined ? '' : String(v);
        s.value = sv;
        if (s.value !== sv && sv !== '') {
            // 지금 값이 선택지에 없음(사용 중지된 특성·샘플링·계측기, 확정본) — 그대로 보여 준다
            option(s, sv, obj[name + 'Text'] || sv);
            s.value = sv;
        }
        s.addEventListener('change', function () {
            obj[name] = s.value;
            markDirty();
            if (onChange) { onChange(s.value); }
        });
        return s;
    }

    function labeled(text, control) {
        var lab = hb.el('label');
        lab.appendChild(hb.el('span', null, text));
        lab.appendChild(control);
        return lab;
    }

    function charOptions() {
        return chars.map(function (c) {
            var where = c.itemCd ? c.itemCd : hb.t('js.qms.plan.procChar', c.procCd);
            return {value: String(c.charId), text: c.charNo + ' · ' + c.charNm + ' (' + (c.specText || '') + ') · ' + where};
        });
    }

    function samplingOptions() {
        return samplings.map(function (s) { return {value: s.planCd, text: s.planCd + ' · ' + s.planNm}; });
    }

    function gaugeOptions() {
        return gauges.map(function (g) { return {value: g.code, text: g.name}; });
    }

    function freqOptions() {
        return Object.keys(freqNames).map(function (k) { return {value: k, text: freqNames[k]}; });
    }

    /** 증빙(docs/72 3.2) — NONE / PHOTO(사진 첨부가 있어야 검사 판정) */
    function evidenceOptions() {
        return Object.keys(evidenceNames).map(function (k) { return {value: k, text: evidenceNames[k]}; });
    }

    function removeBtn(fn) {
        var b = hb.el('button', 'hb-btn-secondary hb-btn-small', hb.t('js.common.remove'));
        b.type = 'button';
        b.addEventListener('click', fn);
        return b;
    }

    /** 작성 중 = 고른 특성의 지금 규격, 확정·폐기 = 스냅샷(서버가 고른 값) */
    function specLine(l) {
        var p = hb.el('p', 'hb-qms-spec');
        var c = null;
        if (current.plan.status === 'DRAFT' && l.charId) {
            c = chars.filter(function (x) { return String(x.charId) === String(l.charId); })[0] || null;
        }
        var text = c ? c.specText : l.specText;
        var special = c ? c.specialCls : l.specialCls;
        p.appendChild(document.createTextNode(hb.t('js.qms.plan.spec', text || '-')
            + (special && special !== 'NONE' ? ' · ' + (specialNames[special] || special) : '')));
        if (l.specChanged) {
            var b = hb.el('span', 'hb-qms-badge', hb.t('js.qms.plan.specChanged', l.currentSpecText || '-'));
            p.appendChild(b);
        }
        return p;
    }

    function renderLines() {
        lineBox.textContent = '';
        var edit = editable();
        if (!lines.length) {
            lineBox.appendChild(hb.el('p', 'hb-muted', hb.t('js.qms.plan.noLines')));
        }
        lines.forEach(function (l) {
            l.charIdText = l.charNo ? l.charNo + ' · ' + (l.charNm || '') + (l.charUseYn === 'N' ? ' — ' + hb.t('js.qms.inactive') : '') : '';
            l.samplingPlanCdText = l.samplingPlanCd ? l.samplingPlanCd + ' · ' + (l.samplingPlanNm || '')
                + (l.samplingUseYn === 'N' ? ' — ' + hb.t('js.qms.inactive') : '') : '';
            l.gaugeClassText = l.gaugeClass ? l.gaugeClass + ' — ' + hb.t('js.qms.inactive') : '';
            var row = hb.el('div', 'hb-bom-row');
            row.setAttribute('role', 'listitem');
            row.setAttribute('data-row-key', l.rowKey);
            var main = hb.el('div', 'hb-qms-line-main');
            main.appendChild(input(l, 'lineNo', hb.t('js.qms.plan.col.lineNo'), {number: {scale: 0, min: 1, max: 9999}}));
            main.appendChild(select(l, 'charId', hb.t('js.qms.plan.col.charId'), charOptions(), hb.t('js.common.choose'),
                function () { renderLines(); }));
            main.appendChild(select(l, 'samplingPlanCd', hb.t('js.qms.plan.col.samplingPlanCd'), samplingOptions(), hb.t('js.common.choose')));
            main.appendChild(select(l, 'gaugeClass', hb.t('js.qms.plan.col.gaugeClass'), gaugeOptions(), hb.t('js.common.choose')));
            var more = hb.el('button', 'hb-btn-secondary hb-btn-small', hb.t('js.qms.plan.more') + (l.open ? ' ▲' : ' ▼'));
            more.type = 'button';
            more.setAttribute('aria-expanded', l.open ? 'true' : 'false');
            more.addEventListener('click', function () { l.open = !l.open; renderLines(); });
            main.appendChild(more);
            if (edit) {
                main.appendChild(removeBtn(function () {
                    lines.splice(lines.indexOf(l), 1);
                    markDirty();
                    renderLines();
                }));
            }
            row.appendChild(main);
            row.appendChild(specLine(l));

            var extra = hb.el('div', 'hb-bom-extra');
            extra.hidden = !l.open;
            var freqN = input(l, 'freqN', hb.t('js.qms.plan.col.freqN'), {number: {scale: 0, min: 1, max: 1000000}});
            extra.appendChild(labeled(hb.t('js.qms.plan.col.freqType'), select(l, 'freqType', hb.t('js.qms.plan.col.freqType'),
                freqOptions(), null, function (v) {
                    freqN.disabled = v !== 'EVERY_N' && v !== 'EVERY_HOURS';
                })));
            freqN.disabled = l.freqType !== 'EVERY_N' && l.freqType !== 'EVERY_HOURS';
            extra.appendChild(labeled(hb.t('js.qms.plan.col.freqN'), freqN));
            // 분류·증빙(docs/72 3.2) — 성적서 묶음·사진 필수(외관 검사 등)
            extra.appendChild(labeled(hb.t('js.qms.plan.col.grpNm'), input(l, 'grpNm', hb.t('js.qms.plan.col.grpNm'), {maxLength: 60})));
            if (!l.evidenceReq) { l.evidenceReq = 'NONE'; }
            extra.appendChild(labeled(hb.t('js.qms.plan.col.evidenceReq'), select(l, 'evidenceReq', hb.t('js.qms.plan.col.evidenceReq'),
                evidenceOptions(), null)));
            var mt = labeled(hb.t('js.qms.plan.col.methodNm'), input(l, 'methodNm', hb.t('js.qms.plan.col.methodNm'), {maxLength: 200}));
            mt.classList.add('hb-bom-wide');
            extra.appendChild(mt);
            var rp = labeled(hb.t('js.qms.plan.col.reactionPlan'), input(l, 'reactionPlan', hb.t('js.qms.plan.col.reactionPlan'),
                {maxLength: 500}));
            rp.classList.add('hb-bom-wide');
            extra.appendChild(rp);
            var rm = labeled(hb.t('js.qms.plan.col.remark'), input(l, 'remark', hb.t('js.qms.plan.col.remark'), {maxLength: 500}));
            rm.classList.add('hb-bom-wide');
            extra.appendChild(rm);
            row.appendChild(extra);
            lineBox.appendChild(row);
        });
    }

    function addLine() {
        var max = lines.reduce(function (m, l) { return Math.max(m, Number(l.lineNo) || 0); }, 0);
        var first = samplings[0];
        lines.push({rowKey: 'nl' + (++keySeq), open: false, lineNo: Math.min(9999, Math.floor(max / 10) * 10 + 10),
            freqType: 'EVERY_LOT', samplingPlanCd: samplings.length === 1 && first ? first.planCd : ''});
        markDirty();
        renderLines();
        var last = lineBox.querySelector('.hb-bom-row:last-child [data-field="charId"]');
        if (last) { last.focus(); }
    }

    function payload() {
        return {lines: lines.map(function (l) {
            var n = function (v) { return v === null || v === undefined || v === '' ? null : Number(v); };
            return {rowKey: l.rowKey, lineId: l.lineId || null, lineNo: n(l.lineNo), charId: n(l.charId), methodNm: l.methodNm || null,
                gaugeClass: l.gaugeClass || null, samplingPlanCd: l.samplingPlanCd || null, freqType: l.freqType || null,
                freqN: l.freqType === 'EVERY_N' || l.freqType === 'EVERY_HOURS' ? n(l.freqN) : null,
                reactionPlan: l.reactionPlan || null, remark: l.remark || null, grpNm: l.grpNm || null, evidenceReq: l.evidenceReq || 'NONE'};
        })};
    }

    /** 행·칸 오류 — 키 'L'+id / 새 행 'nl…'. 접힌 칸 오류면 행을 펼친다 */
    function markRowErrors(errors) {
        var reopen = false;
        (errors || []).forEach(function (er) {
            lines.forEach(function (l) {
                if (l.rowKey === er.rowKey && ['freqType', 'freqN', 'methodNm', 'reactionPlan', 'remark', 'grpNm', 'evidenceReq'].indexOf(er.field) >= 0
                        && !l.open) {
                    l.open = true;
                    reopen = true;
                }
            });
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
            var name = target && target.getAttribute('aria-label');
            row.appendChild(hb.el('span', 'hb-field-error', name ? name + ': ' + er.message : er.message));
        });
        var formErrors = (errors || []).filter(function (er) { return !er.rowKey; });
        hb.showFieldErrors(form, formErrors);
        var lineMsg = formErrors.filter(function (er) {
            return ['lines', 'charId', 'samplingPlanCd', 'gaugeClass'].indexOf(er.field) >= 0;
        })[0];
        if (lineMsg) { lineBox.appendChild(hb.el('span', 'hb-field-error', lineMsg.message)); }
        var first = lineBox.querySelector('[aria-invalid="true"]');
        if (first && first.focus) { first.focus(); }
    }

    function reloadAndShow(id, doneText) {
        return grid.reload(false, id).then(function () {
            if (!grid.selected()) { return loadDetail(id, true); }
        }).then(function () { notify(doneText); });
    }

    function saveLines(btn) {
        lineBox.querySelectorAll('input').forEach(function (i) {
            if (i.hbNumber) { i.hbNumber.validate(); }
        });
        if (!dirtyLines) {
            notify(hb.t('js.common.noChanges'));
            return;
        }
        hb.busy(btn, function () {
            return hb.api(API + '/' + current.plan.inspPlanId + '/lines?rowVer=' + current.plan.rowVer, {method: 'PUT', body: payload()});
        }).then(function (res) {
            if (!res) { return; }
            dirtyLines = false;
            hb.dirty.clear();
            return reloadAndShow(current.plan.inspPlanId, hb.t('js.common.saved'));
        }, function (err) {
            notify(err.message, true);
            markRowErrors(err.errors);
        });
    }

    // ---------- 버튼 ----------
    function bind(id, fn) {
        var b = document.getElementById(id);
        if (b) { b.addEventListener('click', function () { fn(b); }); }
    }

    function newPlan() {
        confirmLeave().then(function (ok) {
            if (!ok) { return; }
            showDetail(null);
            hb.page.openDetail();
            field('inspType').focus();
        });
    }
    bind('newBtn', newPlan);
    bind('newBtnList', newPlan);

    document.getElementById('targetKind').addEventListener('change', layoutKind);
    field('itemCd').addEventListener('change', function () {
        var cd = field('itemCd').value.trim();
        var out = document.getElementById('itemNm');
        if (!cd || current) { out.textContent = ''; return; }
        hb.latest('itemLookup', hb.api(API + '/item?itemCd=' + encodeURIComponent(cd))).then(function (res) {
            out.textContent = res.data.itemNm + (res.data.useYn === 'Y' ? '' : ' — ' + hb.t('js.qms.inactive'));
        }, function () { out.textContent = hb.t('js.qms.plan.itemNotFound'); });
    });

    bind('saveBtn', function (btn) {
        var b = {};
        ['rev', 'validFrom', 'changeNo', 'changeReason', 'remark'].forEach(function (n) { b[n] = field(n).value; });
        var editing = !!current;
        if (editing) {
            b.rowVer = current.plan.rowVer;
        } else {
            b.inspType = typeSel.value();
            b.procCd = field('procCd').value || null;
            if (kind() === 'CLASS') { b.classCd = field('classCd').value || null; } else { b.itemCd = field('itemCd').value; }
        }
        hb.busy(btn, function () {
            return editing ? hb.api(API + '/' + current.plan.inspPlanId, {method: 'PUT', body: b}) : hb.api(API, {method: 'POST', body: b});
        }).then(function (res) {
            if (!res) { return; }
            hb.dirty.clear();
            return reloadAndShow(editing ? current.plan.inspPlanId : res.data, hb.t('js.common.saved'));
        }, function (err) {
            notify(err.message, true);
            hb.showFieldErrors(form, err.errors);
        });
    });

    function action(btn, path, confirmText, doneText, body, after) {
        if (!current) { return; }
        if (hasUnsaved()) {
            notify(hb.t('js.qms.plan.saveFirst'), true);
            return;
        }
        (confirmText ? qmsUi.ask(path, confirmText) : Promise.resolve(true)).then(function (yes) {
            if (!yes) { return; }
            hb.busy(btn, function () {
                return hb.api(API + '/' + current.plan.inspPlanId + '/' + path, {method: 'POST', body: body});
            }).then(function (res) {
                if (!res) { return; }
                return reloadAndShow(after ? after(res) : current.plan.inspPlanId, doneText);
            }, function (err) {
                notify(err.message, true);
                markRowErrors(err.errors);
            });
        });
    }

    bind('releaseBtn', function (btn) {
        if (!current) { return; }
        action(btn, 'release', hb.t('js.qms.plan.confirmRelease', current.plan.targetCd, current.plan.rev),
            hb.t('js.qms.plan.released'), {rowVer: current.plan.rowVer});
    });
    bind('obsoleteBtn', function (btn) {
        if (!current) { return; }
        action(btn, 'obsolete', hb.t('js.qms.plan.confirmObsolete', current.plan.targetCd, current.plan.rev),
            hb.t('js.qms.plan.obsoleted'), {rowVer: current.plan.rowVer});
    });
    bind('newRevBtn', function (btn) {
        if (!current) { return; }
        if (hasUnsaved()) { notify(hb.t('js.qms.plan.saveFirst'), true); return; }
        var c = current;
        // 개정 번호는 비워 두면 서버가 다음 번호(필수 아님)
        hb.dialog.prompt({title: hb.t('js.qms.dlg.newRev'), label: hb.t('js.mstBom.revPrompt'), ok: hb.t('js.qms.dlg.newRevOk'), maxLength: 4})
            .then(function (rev) {
                if (rev === null) { return; }
                action(btn, 'new-revision', null, hb.t('js.qms.plan.revCreated'), {rowVer: c.plan.rowVer, rev: rev.trim() || null},
                    function (res) { return res.data; });
            });
    });
    bind('addLineBtn', addLine);
    bind('saveLinesBtn', saveLines);

    // ---------- 시작 ----------
    hb.dirty.watch(form);
    search.addEventListener('submit', function (e) { e.preventDefault(); });
    hb.loadMessages().then(function () {
        hb.page.init();
        typeSel = hb.select(field('inspType'));
        return Promise.all([hb.api(API + '/procs'), hb.api(API + '/classes'), hb.api(API + '/sampling-plans'),
            hb.codeList('GAUGE_CLASS'), hb.codes('BOM_STATUS'), hb.codes('INSP_FREQ'), hb.codes('SPECIAL_CHAR'), typeSel.ready, hb.codes('INSP_EVIDENCE')]);
    }).then(function (r) {
        procs = r[0].data.map(function (p) { return {cd: p.cd, nm: p.nm}; });
        classes = r[1].data.map(function (c) { return {cd: c.cd, nm: c.nm}; });
        samplings = r[2].data;
        gauges = r[3];
        statusNames = r[4];
        freqNames = r[5];
        specialNames = r[6];
        evidenceNames = r[8];
        var sproc = document.getElementById('searchProc');
        option(sproc, '', hb.t('js.common.all'));
        procs.forEach(function (p) { option(sproc, p.cd, p.cd + ' · ' + p.nm); });
        sp = hb.searchPanel(search, {
            onSearch: function () {
                var g = grid;
                return g ? confirmLeave().then(function (ok) { return ok && g ? g.reload(true) : null; }) : null;
            }
        });
        return Promise.all([hb.loadSession(), sp.ready]);
    }).then(function () {
        showDetail(null);
        grid = hb.grid('#planGrid', {
            progCd: PROG, id: 'main', key: 'inspPlanId',
            url: API, columnsUrl: API + '/columns', exportUrl: API + '/export', auditTable: 'qms_insp_plan',
            actions: ACTIONS, params: function () { return sp.values(); },
            filters: {status: 'status', inspType: 'inspType'},
            onFilter: function (param, value) { sp.setValue(param, value); },
            beforeSelect: confirmLeave,
            // 상태 칸 = 배지(톤 표 qms-ui.js), 끝난·쓰지 않는 행은 흐리게(UI 단계 3, docs/40 부록 A.6)
            status: {status: qmsUi.TONES.rev},
            rowClass: function (row) { return row.status === 'OBSOLETE' ? 'hb-tree-dim' : null; },
            onSelect: function (row) { loadDetail(row.inspPlanId); hb.page.openDetail(); },
            notify: notify
        });
        return grid.ready;
    }).then(null, function (err) { notify(err && err.message, true); });
})();
