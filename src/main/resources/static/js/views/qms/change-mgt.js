// @ts-check
/*
 * 4M 변경 (qms-change) — P3 QMS 2부 Q5 (docs/29_P3 11장·12.1).
 *   목록 표(상한 200) + 상세: ECO 머리(읽기 전용) / 상신 막힘 미리보기 / 4M 폼 / 영향 행 표(한 벌, ECO 대상에서 고름).
 * 키는 ECO id(?ecoId= 또는 ECO 번호로 열기). ECO 가 작성 중이 아니면 읽기 전용. 서버 데이터는 textContent·value 로만.
 */
/**
 * @typedef {{ecoId: number, ecoNo: string, ecoTitle?: string | null, ecoStatus?: string | null, changeKind?: string | null,
 *   m4Types: string[], charCnt: number, updatedAt: string}} QmsChangeRow
 * @typedef {{ecoId: number, ecoNo: string, title: string, changeKind: string, status: string, effectiveDate: string,
 *   markUntil?: string | null, custApprovalStatus: string, equipCnt: number, procCnt: number}} QmsEcoHead
 * @typedef {{m4Types: string[], beforeDesc: string, afterDesc: string, reasonDesc?: string | null, impactDesc?: string | null,
 *   revertCond?: string | null, rowVer: number}} QmsChangeHead
 * @typedef {{refType: string, charId?: number | null, inspPlanId?: number | null, refLabel?: string | null, refNm?: string | null,
 *   impactDesc?: string | null}} QmsChangeChar
 * @typedef {{eco: QmsEcoHead, change: QmsChangeHead | null, chars: QmsChangeChar[], editable: boolean, required: boolean,
 *   issues: HB.FieldError[]}} QmsChangeDetail
 * @typedef {{refType: string, refId: number, refLabel: string, refNm?: string | null}} QmsChoice
 * @typedef {{revertId: number, revertType: string, revertedOn: string, note: string, status: string, voidReason?: string | null,
 *   voidedBy?: string | null, rowVer: number, createdAt: string, createdBy: string}} QmsRecRevert
 * @typedef {{effectId: number, result: string, note: string, baseDate: string, basisN: number, basisDays: number, status: string,
 *   voidReason?: string | null, voidedBy?: string | null, rowVer: number, createdAt: string, createdBy: string}} QmsRecEffect
 * @typedef {{esignId: number, signerNm: string, meaningCd: string, authMethod: string, signedAt: string, current: boolean}} QmsRecSign
 * @typedef {{ecoId: number, ecoNo: string, hasChange: boolean, esignRequired: boolean, signatures: QmsRecSign[],
 *   revert: {temp: boolean, ready: boolean, revertCond?: string | null, releasedOn?: string | null, markUntil?: string | null,
 *     due?: string | null, markGap?: string | null, live?: QmsRecRevert | null, history: QmsRecRevert[]},
 *   effect: {ready: boolean, releasedOn?: string | null, live?: QmsRecEffect | null, history: QmsRecEffect[]}}} QmsRecView
 * @typedef {{n: number, mean?: number | null, sd?: number | null, ppk?: number | null, outOfSpec: number}} QmsRecVar
 * @typedef {{n: number, ng: number, rate?: number | null}} QmsRecAttr
 * @typedef {{inspCnt: number, inspNg: number, ngRate?: number | null, ncrCnt: number, ncrQty: string}} QmsRecItemSide
 * @typedef {{ready: boolean, baseDate?: string | null, n: number, days: number, afterPartial: boolean, afterDaysElapsed: number,
 *   chars: {refLabel: string, charNm?: string | null, dataType: string, lsl?: number | null, usl?: number | null, scale?: number | null,
 *     unitNm?: string | null, beforeVar?: QmsRecVar | null, afterVar?: QmsRecVar | null, beforeAttr?: QmsRecAttr | null,
 *     afterAttr?: QmsRecAttr | null}[],
 *   items: {itemCd: string, itemNm?: string | null, unitCd?: string | null, before: QmsRecItemSide, after: QmsRecItemSide}[]}} QmsRecCompare
 * @typedef {{ecoId: number, ecoNo: string, ecoStatus: string, custId?: number | null, custCd?: string | null, custNm?: string | null,
 *   custApprovalStatus: string, state: string, level?: string | null, basis: {m4Type: string, level: string, csrNo: string, csrRev: string}[],
 *   warnings: HB.FieldError[], pcns: {pcnId: number, pcnNo: string, notifiedOn: string, responseDue: string, status: string,
 *   respondedOn?: string | null, overdue: boolean}[], otherCustPcns: number, gate: string}} QmsNotifyView
 */
(function () {
    'use strict';

    /** QMS 화면 공용 — 상태 배지·대화상자(qms-ui.js) */
    var qmsUi = /** @type {QmsUi} */ (/** @type {any} */ (window).qmsUi);

    var PROG = document.body.getAttribute('data-prog') || '';
    var ACTIONS = (document.body.getAttribute('data-actions') || '').split(',');
    var CAN_SAVE = ACTIONS.indexOf('SAVE') >= 0;
    var CAN_DELETE = ACTIONS.indexOf('DELETE') >= 0;
    var API = '/api/qms/changes';
    /** 4M 유형 — 공통코드 QMS_4M_TYPE 순서 그대로(손 목록 대신, docs/68 Q3). 코드 목록을 받은 뒤 채운다 @type {string[]} */
    var M4 = [];
    var FIELDS = ['beforeDesc', 'afterDesc', 'reasonDesc', 'impactDesc', 'revertCond'];
    var msg = /** @type {HTMLElement} */ (document.getElementById('msg'));
    var search = /** @type {HTMLFormElement} */ (document.getElementById('search'));
    var form = /** @type {HTMLFormElement} */ (document.getElementById('changeForm'));
    var formMode = /** @type {HTMLElement} */ (document.getElementById('formMode'));
    var ecoLine = /** @type {HTMLElement} */ (document.getElementById('ecoLine'));
    /** @type {QmsChangeDetail | null} */
    var detail = null;
    /** 영향 행 편집 중 @type {{key: string, impactDesc: string}[]} */
    var rows = [];
    /** @type {QmsChoice[]} */
    var choices = [];
    var rowsDirty = false;
    /** @type {HB.SearchPanel} */
    var sp;
    /** @type {Record<string, string>} */
    var m4Names = {};
    /** @type {Record<string, string>} */
    var kindNames = {};
    /** @type {Record<string, string>} */
    var statusNames = {};

    /** @param {string | null | undefined} text @param {boolean} [error] */
    function notify(text, error) {
        msg.textContent = text || '';
        msg.classList.toggle('hb-msg-error', !!error);
    }

    /** @param {{message: string, errors?: HB.FieldError[]}} err */
    function errText(err) {
        return err.message + (err.errors && err.errors.length
            ? ' ' + err.errors.map(/** @param {HB.FieldError} e */ function (e) { return e.message; }).join(' ') : '');
    }

    /** @param {string} n */
    function field(n) {
        return /** @type {HTMLTextAreaElement} */ (form.querySelector('[data-field="' + n + '"]'));
    }

    function editable() {
        return CAN_SAVE && !!detail && detail.editable;
    }

    // ---------- 목록 ----------

    /** 주 목록 = hb.grid(UI 단계 3) — 서버 API/grid·/columns @type {HB.Grid | null} */
    var grid = null;

    /** 목록 다시 읽기 — 보던 행을 그대로 고른 채(keepId 는 onSelect 를 부르지 않는다) */
    function loadList() {
        return grid ? grid.reload(false, detail ? detail.eco.ecoId : undefined) : Promise.resolve();
    }

    function startGrid() {
        grid = hb.grid('#changeGrid', {
            progCd: PROG, id: 'main', key: 'ecoId',
            url: API + '/grid', columnsUrl: API + '/columns', exportUrl: API + '/export', actions: ACTIONS,
            params: function () { return sp.values(); },
            filters: {ecoStatus: 'ecoStatus'},
            format: {m4Text: function (v) { return String(v || '').split(',').filter(Boolean).map(function (t) { return m4Names[t] || t; }).join(', '); }},
            beforeSelect: function () { return confirmLeave(); },
            status: {ecoStatus: qmsUi.TONES.eco},
            rowClass: function (row) { return row.ecoStatus === 'CANCELLED' ? 'hb-tree-dim' : null; },
            onSelect: function (row) { load(/** @type {number} */ (row.ecoId)); hb.page.openDetail(); },
            notify: notify
        });
        return grid.ready;
    }

    // ---------- 상세 ----------

    /** @param {QmsChangeDetail} d */
    function render(d) {
        detail = d;
        var e = d.eco;
        var c = d.change;
        hb.showFieldErrors(form, []);
        formMode.textContent = hb.t(c ? 'js.qms.change.editing' : 'js.qms.change.creating', e.ecoNo, e.title);
        ecoLine.hidden = false;
        var parts = [statusNames[e.status] || e.status, kindNames[e.changeKind] || e.changeKind, hb.t('js.qms.change.effective', e.effectiveDate)];
        if (e.markUntil) { parts.push(hb.t('js.qms.change.markUntil', e.markUntil)); }
        parts.push(hb.t('js.qms.change.targets', e.equipCnt, e.procCnt));
        if (!d.editable) { parts.push(hb.t('js.qms.change.readonly')); }
        ecoLine.textContent = parts.join(' · ');
        ecoLine.setAttribute('data-status', d.issues.length ? 'DUE_SOON' : 'OK');
        var ul = /** @type {HTMLElement} */ (document.getElementById('issues'));
        ul.textContent = '';
        d.issues.forEach(function (i) { ul.appendChild(hb.el('li', null, i.message)); });
        if (d.issues.length) { ul.insertBefore(hb.el('li', 'hb-qms-issue-head', hb.t('js.qms.change.issuesHead')), ul.firstChild); }
        ul.hidden = !d.issues.length;
        /** @type {HTMLAnchorElement} */ (document.getElementById('setupLink')).href = '/page/qms-setup?ecoId=' + e.ecoId;
        show('setupLinkBox', e.status === 'RELEASED');
        form.hidden = false;
        var box = /** @type {HTMLElement} */ (document.getElementById('m4Box'));
        box.textContent = '';
        M4.forEach(function (t) {
            var lab = hb.el('label');
            var cb = /** @type {HTMLInputElement} */ (hb.el('input'));
            cb.type = 'checkbox';
            cb.value = t;
            cb.checked = !!c && c.m4Types.indexOf(t) >= 0;
            cb.disabled = !editable();
            lab.appendChild(cb);
            lab.appendChild(hb.el('span', null, m4Names[t] || t));
            box.appendChild(lab);
        });
        FIELDS.forEach(function (n) {
            var v = c ? /** @type {Record<string, any>} */ (c)[n] : null;
            field(n).value = v || '';
            field(n).readOnly = !editable();
        });
        field('revertCond').required = e.changeKind === 'TEMP';
        rows = d.chars.map(function (x) {
            return {key: (x.refType === 'CHAR' ? 'CHAR:' + x.charId : 'PLAN:' + x.inspPlanId), impactDesc: x.impactDesc || ''};
        });
        rowsDirty = false;
        /** @type {HTMLElement} */ (document.getElementById('impactSection')).hidden = false;
        renderRows();
        show('saveBtn', editable());
        show('deleteBtn', CAN_DELETE && d.editable && !!c);
        show('addCharBtn', editable());
        document.querySelectorAll('#changeTable tbody tr').forEach(function (tr) {
            tr.classList.toggle('hb-qms-sel', tr.getAttribute('data-id') === String(e.ecoId));
        });
        hb.dirty.clear(form);
    }

    /** @param {string} id @param {boolean} on */
    function show(id, on) {
        var el = document.getElementById(id);
        if (el) { el.hidden = !on; }
    }

    /** 고정 행(지금 후보에 없는 기존 행)도 이름이 보이게 */
    function labelOf(/** @type {string} */ key) {
        var c = choices.filter(function (x) { return x.refType + ':' + x.refId === key; })[0];
        if (c) { return c.refLabel + (c.refNm ? ' ' + c.refNm : ''); }
        var d = detail ? detail.chars.filter(function (x) { return (x.refType === 'CHAR' ? 'CHAR:' + x.charId : 'PLAN:' + x.inspPlanId) === key; })[0] : null;
        return d ? (d.refLabel || '') + (d.refNm ? ' ' + d.refNm : '') : key;
    }

    function renderRows() {
        var tbody = /** @type {HTMLElement} */ (document.querySelector('#charTable tbody'));
        tbody.textContent = '';
        var edit = editable();
        rows.forEach(function (r, i) {
            var tr = hb.el('tr');
            tr.setAttribute('data-row', String(i));
            var tdRef = hb.el('td');
            if (edit) {
                var sel = /** @type {HTMLSelectElement} */ (hb.el('select'));
                sel.setAttribute('data-col', 'refId');
                var blank = /** @type {HTMLOptionElement} */ (hb.el('option', null, hb.t('js.qms.change.pick')));
                blank.value = '';
                sel.appendChild(blank);
                var keys = choices.map(function (c) { return c.refType + ':' + c.refId; });
                if (r.key && keys.indexOf(r.key) < 0) { keys.unshift(r.key); }
                keys.forEach(function (k) {
                    var o = /** @type {HTMLOptionElement} */ (hb.el('option', null, (k.indexOf('PLAN:') === 0 ? hb.t('js.qms.change.plan') : hb.t('js.qms.change.char')) + ' ' + labelOf(k)));
                    o.value = k;
                    sel.appendChild(o);
                });
                sel.value = r.key;
                sel.addEventListener('change', function () { r.key = sel.value; rowsDirty = true; });
                tdRef.appendChild(sel);
            } else {
                tdRef.textContent = labelOf(r.key);
            }
            tr.appendChild(tdRef);
            var tdNote = hb.el('td');
            var inp = /** @type {HTMLInputElement} */ (hb.el('input'));
            inp.setAttribute('data-col', 'impactDesc');
            inp.value = r.impactDesc;
            inp.maxLength = 1000;
            inp.readOnly = !edit;
            inp.placeholder = hb.t('js.qms.change.notePh');
            inp.addEventListener('input', function () { r.impactDesc = inp.value; rowsDirty = true; });
            tdNote.appendChild(inp);
            tr.appendChild(tdNote);
            var tdAct = hb.el('td');
            if (edit) {
                var del = /** @type {HTMLButtonElement} */ (hb.el('button', 'hb-btn-secondary hb-btn-small', hb.t('js.common.remove')));
                del.type = 'button';
                del.addEventListener('click', function () { rows.splice(i, 1); rowsDirty = true; renderRows(); });
                tdAct.appendChild(del);
            }
            tr.appendChild(tdAct);
            tbody.appendChild(tr);
        });
        if (!rows.length) {
            var tr0 = hb.el('tr');
            var td0 = /** @type {HTMLTableCellElement} */ (hb.el('td', 'hb-muted', hb.t('js.qms.change.noChars')));
            td0.colSpan = 3;
            tr0.appendChild(td0);
            tbody.appendChild(tr0);
        }
    }

    /** @param {HB.FieldError[] | undefined} errs */
    function markRows(errs) {
        document.querySelectorAll('#charTable [data-col]').forEach(function (i) { i.removeAttribute('aria-invalid'); i.removeAttribute('title'); });
        (errs || []).forEach(function (e) {
            if (e.rowKey === undefined || e.rowKey === null) { return; }
            var el = document.querySelector('#charTable tr[data-row="' + e.rowKey + '"] [data-col="' + (e.field === 'impactDesc' ? 'impactDesc' : 'refId') + '"]');
            if (el) {
                el.setAttribute('aria-invalid', 'true');
                el.setAttribute('title', e.message);
            }
        });
    }

    // ---------- 임시 변경 복귀 · 변경 효과 (docs/29_P3 51장) ----------

    /** @type {QmsRecView | null} */
    var rec = null;
    var revertForm = /** @type {HTMLFormElement | null} */ (document.getElementById('revertForm'));
    var effectForm = /** @type {HTMLFormElement | null} */ (document.getElementById('effectForm'));
    /** @type {Record<string, string>} */
    var revertTypeNames = {};
    /** @type {Record<string, string>} */
    var effectNames = {};

    /** @param {HTMLFormElement | null} f @param {string} n */
    function recField(f, n) {
        return f ? /** @type {HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement | null} */ (f.querySelector('[data-field="' + n + '"]')) : null;
    }

    /** @param {number} ecoId */
    function loadRecords(ecoId) {
        return hb.latest('records', hb.api(API + '/' + ecoId + '/records')).then(function (res) {
            renderRecords(/** @type {QmsRecView} */ (res.data));
        }, function (err) { if (err.code !== 'STALE') { notify(err.message, true); } });
    }

    /** @param {QmsRecRevert} r */
    function revertText(r) {
        return hb.t('js.qms.change.revertRow', revertTypeNames[r.revertType] || r.revertType, r.revertedOn, r.note, r.createdBy,
            hb.fmt.datetime(r.createdAt));
    }

    /** @param {QmsRecEffect} r */
    function effectText(r) {
        return hb.t('js.qms.change.effectRow', effectNames[r.result] || r.result, r.note, r.baseDate, r.basisN, r.basisDays, r.createdBy,
            hb.fmt.datetime(r.createdAt));
    }

    /**
     * 기록 이력 목록 — 무효 행은 흐리게 + 무효 사유
     * @param {HTMLElement} ul @param {{status: string, voidReason?: string | null, voidedBy?: string | null}[]} list @param {(x: any) => string} text
     */
    function history(ul, list, text) {
        ul.textContent = '';
        list.forEach(function (x) {
            var li = hb.el('li', x.status === 'VOID' ? 'hb-tree-dim' : null, text(x));
            if (x.status === 'VOID') { li.appendChild(hb.el('span', 'hb-muted', ' ' + hb.t('js.qms.change.voided', x.voidReason || '', x.voidedBy || ''))); }
            ul.appendChild(li);
        });
        ul.hidden = !list.length;
    }

    /** @param {QmsRecView} r */
    function renderRecords(r) {
        rec = r;
        var rv = r.revert;
        show('revertSection', rv.temp);
        var line = /** @type {HTMLElement} */ (document.getElementById('revertLine'));
        var parts = [hb.t('js.qms.change.revertCond', rv.revertCond || '-')];
        if (rv.markUntil) { parts.push(hb.t('js.qms.change.markUntil', rv.markUntil)); }
        if (!rv.ready) {
            parts.push(hb.t(r.hasChange ? 'js.qms.change.revertNotReady' : 'js.qms.change.recNoChange'));
        } else if (rv.due === 'OVERDUE') {
            parts.push(hb.t('js.qms.change.revertOverdue'));
        } else if (rv.due === 'DONE') {
            parts.push(hb.t('js.qms.change.revertDone'));
        } else {
            parts.push(hb.t('js.qms.change.revertDueOpen'));
        }
        line.textContent = parts.join(' · ');
        line.setAttribute('data-status', rv.due === 'OVERDUE' ? 'OVERDUE' : rv.due === 'DONE' ? 'OK' : 'DUE_SOON');
        var gap = /** @type {HTMLElement} */ (document.getElementById('revertGap'));
        gap.hidden = !rv.markGap;
        gap.textContent = rv.markGap === 'EARLY' ? hb.t('js.qms.change.gapEarly', rv.live ? rv.live.revertedOn : '', rv.markUntil || '')
            : rv.markGap === 'LATE' ? hb.t('js.qms.change.gapLate', rv.markUntil || '', rv.live ? rv.live.revertedOn : '') : '';
        history(/** @type {HTMLElement} */ (document.getElementById('revertHist')), rv.history, revertText);
        var canRevert = CAN_SAVE && rv.ready && !rv.live;
        if (revertForm) {
            revertForm.hidden = !canRevert;
            hb.showFieldErrors(revertForm, []);
            if (canRevert) {
                var d = recField(revertForm, 'revertedOn');
                if (d && !d.value) { d.value = hb.dates ? hb.dates.today() : ''; }
            }
        }
        show('revertBtn', canRevert);
        show('revertVoidBtn', CAN_DELETE && !!rv.live);

        var ev = r.effect;
        show('effectSection', ev.ready || ev.history.length > 0);
        var st = /** @type {HTMLElement} */ (document.getElementById('effectState'));
        st.hidden = !ev.live;
        st.textContent = ev.live ? effectText(ev.live) : '';
        st.setAttribute('data-status', ev.live && ev.live.result === 'NG' ? 'NG' : 'OK');
        history(/** @type {HTMLElement} */ (document.getElementById('effectHist')), ev.history.filter(function (x) { return x.status === 'VOID'; }), effectText);
        var canEffect = CAN_SAVE && ev.ready && !ev.live;
        if (effectForm) {
            effectForm.hidden = !canEffect;
            hb.showFieldErrors(effectForm, []);
        }
        show('effectBtn', canEffect);
        show('effectVoidBtn', CAN_DELETE && !!ev.live);
        clearCompare(ev.ready ? hb.t('js.qms.change.effHint') : '');

        show('recSignBox', r.esignRequired || r.signatures.length > 0);
        /** @type {HTMLElement} */ (document.getElementById('recEsignLine')).textContent = r.esignRequired ? hb.t('js.qms.esign.required') : '';
        var ul = /** @type {HTMLElement} */ (document.getElementById('recSigns'));
        ul.textContent = '';
        var meanings = {AUTHOR: hb.t('js.esign.meaning.AUTHOR'), REVIEW: hb.t('js.esign.meaning.REVIEW'), APPROVE: hb.t('js.esign.meaning.APPROVE')};
        /** @type {Record<string, string>} */
        var methods = {PWD: hb.t('js.qms.esign.method.PWD'), PWD_TOTP: hb.t('js.qms.esign.method.PWD_TOTP')};
        r.signatures.forEach(function (sg) {
            ul.appendChild(hb.el('li', sg.current ? null : 'hb-msg-error', hb.t('js.qms.esign.row', sg.signerNm,
                /** @type {Record<string, string>} */ (meanings)[sg.meaningCd] || sg.meaningCd, methods[sg.authMethod] || sg.authMethod,
                hb.fmt.datetime(sg.signedAt)) + (sg.current ? '' : ' ' + hb.t('js.qms.esign.changed'))));
        });
        if (!r.signatures.length) { ul.appendChild(hb.el('li', 'hb-muted', hb.t('js.qms.esign.none'))); }
    }

    /** @param {string} text */
    function clearCompare(text) {
        ['effCharTable', 'effItemTable'].forEach(function (id) {
            var t = /** @type {HTMLTableElement} */ (document.getElementById(id));
            /** @type {HTMLElement} */ (t.tHead).textContent = '';
            /** @type {HTMLElement} */ (t.tBodies[0]).textContent = '';
            t.hidden = true;
        });
        /** @type {HTMLElement} */ (document.getElementById('effectLine')).textContent = text;
    }

    /** 숫자 표시 — null = 자료 없음 @param {number | null | undefined} v @param {number} digits */
    function num(v, digits) {
        return v === null || v === undefined ? hb.t('js.qms.change.noData') : v.toFixed(digits);
    }

    /** @param {number | null | undefined} v */
    function pct(v) {
        return v === null || v === undefined ? hb.t('js.qms.change.noData') : (v * 100).toFixed(1) + '%';
    }

    /** @param {HTMLTableElement} t @param {string[]} head @param {string[][]} rows */
    function fillTable(t, head, rows) {
        var thead = /** @type {HTMLElement} */ (t.tHead);
        var tbody = /** @type {HTMLElement} */ (t.tBodies[0]);
        thead.textContent = '';
        tbody.textContent = '';
        var hr = hb.el('tr');
        head.forEach(function (h) { hr.appendChild(hb.el('th', null, h)); });
        thead.appendChild(hr);
        rows.forEach(function (cells) {
            var tr = hb.el('tr');
            cells.forEach(function (c) { tr.appendChild(hb.el('td', null, c)); });
            tbody.appendChild(tr);
        });
        t.hidden = false;
    }

    /** @param {QmsRecVar | null | undefined} s @param {number} sc */
    function varCells(s, sc) {
        if (!s) { return ['0', '-', '-', '-', '-']; }
        return [String(s.n), num(s.mean, sc + 2), num(s.sd, sc + 2), num(s.ppk, 2), String(s.outOfSpec)];
    }

    /** @param {QmsRecAttr | null | undefined} s */
    function attrCells(s) {
        if (!s) { return ['0', '-', '-', '-', '-']; }
        return [String(s.n), '-', '-', pct(s.rate), String(s.ng)];
    }

    /** @param {QmsRecCompare} c */
    function renderCompare(c) {
        if (!c.ready) { clearCompare(hb.t('js.qms.change.effNotReady')); return; }
        var lineText = hb.t('js.qms.change.effBase', c.baseDate, c.n, c.days);
        if (c.afterPartial) { lineText += ' · ' + hb.t('js.qms.change.effPartial', c.afterDaysElapsed, c.days); }
        /** @type {HTMLElement} */ (document.getElementById('effectLine')).textContent = lineText;
        var side = [hb.t('js.qms.change.effColN'), hb.t('js.qms.change.effColMean'), hb.t('js.qms.change.effColSd'),
            hb.t('js.qms.change.effColPpk'), hb.t('js.qms.change.effColOut')];
        var head = [hb.t('js.qms.change.effColChar'), hb.t('js.qms.change.effColSpec')]
            .concat(side.map(function (s) { return hb.t('js.qms.change.effBefore', s); }))
            .concat(side.map(function (s) { return hb.t('js.qms.change.effAfter', s); }));
        var charTable = /** @type {HTMLTableElement} */ (document.getElementById('effCharTable'));
        if (c.chars.length) {
            fillTable(charTable, head, c.chars.map(function (x) {
                var attr = x.dataType === 'ATTRIBUTE';
                var sc = x.scale === null || x.scale === undefined ? 2 : x.scale;
                var spec = attr ? hb.t('js.qms.change.effAttr') : (x.lsl === null || x.lsl === undefined ? '' : String(x.lsl)) + ' ~ '
                    + (x.usl === null || x.usl === undefined ? '' : String(x.usl)) + (x.unitNm ? ' ' + x.unitNm : '');
                return [x.refLabel + ' ' + (x.charNm || ''), spec]
                    .concat(attr ? attrCells(x.beforeAttr) : varCells(x.beforeVar, sc))
                    .concat(attr ? attrCells(x.afterAttr) : varCells(x.afterVar, sc));
            }));
        } else {
            charTable.hidden = true;
        }
        var itemTable = /** @type {HTMLTableElement} */ (document.getElementById('effItemTable'));
        var iside = [hb.t('js.qms.change.effColInsp'), hb.t('js.qms.change.effColNgRate'), hb.t('js.qms.change.effColNcr'), hb.t('js.qms.change.effColNcrQty')];
        if (c.items.length) {
            fillTable(itemTable, [hb.t('js.qms.change.effColItem')]
                .concat(iside.map(function (s) { return hb.t('js.qms.change.effBefore', s); }))
                .concat(iside.map(function (s) { return hb.t('js.qms.change.effAfter', s); })),
            c.items.map(function (x) {
                /** @param {QmsRecItemSide} s */
                function cells(s) { return [s.inspNg + '/' + s.inspCnt, pct(s.ngRate), String(s.ncrCnt), s.ncrQty + ' ' + (x.unitCd || '')]; }
                return [x.itemCd + ' ' + (x.itemNm || '')].concat(cells(x.before)).concat(cells(x.after));
            }));
        } else {
            itemTable.hidden = true;
        }
        if (!c.chars.length && !c.items.length) {
            /** @type {HTMLElement} */ (document.getElementById('effectLine')).textContent = lineText + ' · ' + hb.t('js.qms.change.effEmpty');
        }
    }

    /** 비교 조건 — 숫자가 아니면 기본값 @param {string} id @param {number} dflt */
    function intOf(id, dflt) {
        var v = /** @type {HTMLInputElement} */ (document.getElementById(id)).value.trim();
        return /^\d{1,3}$/.test(v) ? Number(v) : dflt;
    }

    var effectQuery = /** @type {HTMLFormElement} */ (document.getElementById('effectQuery'));
    effectQuery.addEventListener('submit', function (ev) {
        ev.preventDefault();
        if (!rec) { return; }
        var id = rec.ecoId;
        hb.latest('effect', hb.api(API + '/' + id + '/effect?n=' + intOf('effN', 30) + '&days=' + intOf('effDays', 30))).then(function (res) {
            renderCompare(/** @type {QmsRecCompare} */ (res.data));
        }, function (err) { if (err.code !== 'STALE') { notify(err.message, true); } });
    });

    /** 정책이 켜졌으면 서명 대화상자 → 토큰, 아니면 undefined, 취소 = null @returns {Promise<string | null | undefined>} */
    function recSign() {
        if (!rec || !rec.esignRequired) { return Promise.resolve(undefined); }
        return hb.esign({meaning: 'APPROVE', targetType: 'qms_change', targetId: rec.ecoId, title: rec.ecoNo});
    }

    /** @param {HTMLFormElement | null} f @param {string} n */
    function recValue(f, n) {
        var el = recField(f, n);
        return el ? el.value : null;
    }

    bind('revertBtn', function (btn) {
        if (!rec) { return; }
        var r = rec;
        recSign().then(function (token) {
            if (token === null) { return; }
            return hb.busy(btn, function () {
                return hb.api(API + '/' + r.ecoId + '/reverts', {method: 'POST', body: {revertType: recValue(revertForm, 'revertType'),
                    revertedOn: recValue(revertForm, 'revertedOn'), note: recValue(revertForm, 'note'), esignToken: token || null}});
            });
        }).then(function (res) {
            if (!res) { return; }
            var rn = recField(revertForm, 'note');
            if (rn) { rn.value = ''; }
            return loadRecords(r.ecoId).then(loadList).then(function () { notify(hb.t('js.qms.change.revertSaved')); });
        }, function (err) {
            notify(errText(err), true);
            if (revertForm) { hb.showFieldErrors(revertForm, err.errors || []); }
        });
    });

    bind('effectBtn', function (btn) {
        if (!rec) { return; }
        var r = rec;
        recSign().then(function (token) {
            if (token === null) { return; }
            return hb.busy(btn, function () {
                return hb.api(API + '/' + r.ecoId + '/effects', {method: 'POST', body: {result: recValue(effectForm, 'result'),
                    note: recValue(effectForm, 'note'), n: intOf('effN', 30), days: intOf('effDays', 30), esignToken: token || null}});
            });
        }).then(function (res) {
            if (!res) { return; }
            var en = recField(effectForm, 'note');
            if (en) { en.value = ''; }
            return loadRecords(r.ecoId).then(function () { notify(hb.t('js.qms.change.effectSaved')); });
        }, function (err) {
            notify(errText(err), true);
            if (effectForm) { hb.showFieldErrors(effectForm, err.errors || []); }
        });
    });

    /** @param {string} url @param {number} rowVer */
    function voidRecord(url, rowVer) {
        if (!rec) { return; }
        var id = rec.ecoId;
        qmsUi.reason('voidRec', hb.t('js.qms.change.voidReason')).then(function (reason) {
            if (reason === null) { return; }
            return hb.api(url, {method: 'POST', body: {rowVer: rowVer, reason: reason}}).then(function () {
                return loadRecords(id).then(loadList).then(function () { notify(hb.t('js.qms.change.recordVoided')); });
            });
        }).then(null, function (err) { notify(errText(err), true); });
    }

    bind('revertVoidBtn', function () {
        var live = rec && rec.revert.live;
        if (live) { voidRecord(API + '/reverts/' + live.revertId + '/void', live.rowVer); }
    });

    bind('effectVoidBtn', function () {
        var live = rec && rec.effect.live;
        if (live) { voidRecord(API + '/effects/' + live.effectId + '/void', live.rowVer); }
    });

    // ---------- 고객 통지 판단 (docs/29_P3 52장) ----------

    /** @type {Record<string, string>} */
    var notifyNames = {};
    /** @type {Record<string, string>} */
    var pcnStatusNames = {};

    /** @param {number} ecoId */
    function loadNotify(ecoId) {
        return hb.latest('notify', hb.api(API + '/' + ecoId + '/cust-notify')).then(function (res) {
            renderNotify(/** @type {QmsNotifyView} */ (res.data));
        }, function (err) { if (err.code !== 'STALE') { notify(err.message, true); } });
    }

    /** @param {QmsNotifyView} n */
    function renderNotify(n) {
        show('notifySection', true);
        var line = /** @type {HTMLElement} */ (document.getElementById('notifyLine'));
        var parts = [];
        if (n.state === 'NO_CUSTOMER') {
            parts.push(hb.t('js.qms.change.notifyNoCust'));
        } else {
            parts.push(hb.t('js.qms.change.notifyCust', n.custCd || '', n.custNm || ''));
            parts.push(n.state === 'NO_4M' ? hb.t('js.qms.change.notifyNo4m')
                : hb.t('js.qms.change.notifyLevel', notifyNames[n.level || 'NONE'] || n.level || ''));
            parts.push(hb.t(n.custApprovalStatus === 'NA' ? 'js.qms.change.notifyApprovalOff' : 'js.qms.change.notifyApprovalOn'));
        }
        if (n.gate === 'BLOCK') { parts.push(hb.t('js.qms.change.notifyGateBlock')); }
        line.textContent = parts.join(' · ');
        line.setAttribute('data-status', n.warnings.length ? 'OVERDUE' : n.state === 'JUDGED' && n.level !== 'NONE' ? 'DUE_SOON' : 'OK');
        var warn = /** @type {HTMLElement} */ (document.getElementById('notifyWarn'));
        warn.textContent = '';
        n.warnings.forEach(function (w) { warn.appendChild(hb.el('li', null, w.message)); });
        warn.hidden = !n.warnings.length;
        var basis = /** @type {HTMLElement} */ (document.getElementById('notifyBasis'));
        basis.textContent = '';
        n.basis.forEach(function (b) {
            basis.appendChild(hb.el('li', null, hb.t('js.qms.change.notifyBasis', m4Names[b.m4Type] || b.m4Type, notifyNames[b.level] || b.level,
                b.csrNo, b.csrRev)));
        });
        if (n.otherCustPcns > 0) { basis.appendChild(hb.el('li', null, hb.t('js.qms.change.notifyOtherPcn', n.otherCustPcns))); }
        basis.hidden = !basis.childElementCount;
        var t = /** @type {HTMLTableElement} */ (document.getElementById('pcnTable'));
        var thead = /** @type {HTMLElement} */ (t.tHead);
        var tbody = /** @type {HTMLElement} */ (t.tBodies[0]);
        thead.textContent = '';
        tbody.textContent = '';
        var hr = hb.el('tr');
        [hb.t('js.qms.change.pcnNo'), hb.t('js.qms.change.pcnNotified'), hb.t('js.qms.change.pcnDue'), hb.t('js.qms.change.pcnStatus')]
            .forEach(function (h) { hr.appendChild(hb.el('th', null, h)); });
        thead.appendChild(hr);
        n.pcns.forEach(function (p) {
            var tr = hb.el('tr');
            tr.appendChild(hb.el('td', null, p.pcnNo));
            tr.appendChild(hb.el('td', null, p.notifiedOn));
            tr.appendChild(hb.el('td', p.overdue ? 'hb-msg-error' : null, p.responseDue + (p.overdue ? ' ' + hb.t('js.qms.change.pcnOverdue') : '')));
            tr.appendChild(hb.el('td', null, (pcnStatusNames[p.status] || p.status) + (p.respondedOn ? ' (' + p.respondedOn + ')' : '')));
            tbody.appendChild(tr);
        });
        t.hidden = !n.pcns.length;
        /** @type {HTMLAnchorElement} */ (document.getElementById('ecoLink')).href = '/page/plm-eco?q.keyword=' + encodeURIComponent(n.ecoNo);
    }

    // ---------- 읽기·저장 ----------

    /** @param {number} ecoId */
    function load(ecoId) {
        return hb.latest('detail', Promise.all([hb.api(API + '/' + ecoId), hb.api(API + '/' + ecoId + '/choices')])).then(function (r) {
            choices = /** @type {QmsChoice[]} */ (r[1].data || []);
            render(/** @type {QmsChangeDetail} */ (r[0].data));
            return Promise.all([loadRecords(ecoId), loadNotify(ecoId)]);
        }, function (err) { if (err.code !== 'STALE') { notify(err.message, true); } });
    }

    function confirmLeave() {
        return qmsUi.leaveOk(hb.dirty.isDirty() || rowsDirty);
    }

    /** @param {string} id @param {(b: HTMLButtonElement) => void} fn */
    function bind(id, fn) {
        var b = /** @type {HTMLButtonElement | null} */ (document.getElementById(id));
        if (b) {
            var btn = b;
            btn.addEventListener('click', function () { fn(btn); });
        }
    }

    bind('addCharBtn', function () {
        rows.push({key: '', impactDesc: ''});
        rowsDirty = true;
        renderRows();
    });

    bind('saveBtn', function (btn) {
        if (!detail) { return; }
        var d = detail;
        /** @type {Record<string, any>} */
        var b = {m4Types: Array.prototype.map.call(document.querySelectorAll('#m4Box input:checked'), /** @param {HTMLInputElement} i */ function (i) { return i.value; }),
            chars: rows.map(function (r) {
                var p = r.key.split(':');
                return {refType: p[0] || null, refId: p[1] ? Number(p[1]) : null, impactDesc: r.impactDesc};
            }),
            rowVer: d.change ? d.change.rowVer : null};
        FIELDS.forEach(function (n) { b[n] = field(n).value; });
        hb.busy(btn, function () { return hb.api(API + '/' + d.eco.ecoId, {method: 'PUT', body: b}); }).then(function (res) {
            if (!res) { return; }
            hb.dirty.clear(form);
            rowsDirty = false;
            return load(d.eco.ecoId).then(loadList).then(function () { notify(hb.t('js.common.saved')); });
        }, function (err) {
            notify(errText(err), true);
            hb.showFieldErrors(form, (err.errors || []).filter(/** @param {HB.FieldError} e */ function (e) { return !e.rowKey; }));
            markRows(err.errors);
        });
    });

    bind('deleteBtn', function (btn) {
        if (!detail || !detail.change) { return; }
        var d = detail;
        var ver = d.change ? d.change.rowVer : 0;
        qmsUi.ask('remove', hb.t('js.qms.change.confirmDelete', d.eco.ecoNo)).then(function (yes) {
            if (!yes) { return; }
            hb.busy(btn, function () { return hb.api(API + '/' + d.eco.ecoId + '/delete', {method: 'POST', body: {rowVer: ver}}); }).then(function (res) {
                if (!res) { return; }
                return load(d.eco.ecoId).then(loadList).then(function () { notify(hb.t('js.qms.change.deleted')); });
            }, function (err) { notify(errText(err), true); });
        });
    });

    var openForm = /** @type {HTMLFormElement} */ (document.getElementById('openForm'));
    openForm.addEventListener('submit', function (ev) {
        ev.preventDefault();
        var no = /** @type {HTMLInputElement} */ (document.getElementById('openNo')).value.trim();
        if (!no) { return; }
        confirmLeave().then(function (ok) {
            if (!ok) { return; }
            hb.api(API + '/by-no?ecoNo=' + encodeURIComponent(no)).then(function (res) {
                hb.page.openDetail();
                return load(/** @type {number} */ (res.data));
            }, function (err) { notify(err.code === 'NOT_FOUND' ? hb.t('js.qms.change.noEco', no) : err.message, true); });
        });
    });

    // ---------- 시작 ----------
    hb.dirty.watch(form);
    search.addEventListener('submit', function (e) { e.preventDefault(); });
    hb.loadMessages().then(function () {
        hb.page.init();
        sp = hb.searchPanel(search, {onSearch: function () { return confirmLeave().then(function (ok) { return ok && grid ? grid.reload(true) : null; }); }});
        [revertForm, effectForm].forEach(function (f) {
            var s = f ? /** @type {HTMLSelectElement | null} */ (f.querySelector('select[data-hb="select"]')) : null;
            if (s) { hb.select(s); }
        });
        return Promise.all([hb.codes('QMS_4M_TYPE'), hb.codes('PLM_CHANGE_KIND'), hb.codes('PLM_ECO_STATUS'), hb.loadSession(), sp.ready,
            hb.codes('QMS_4M_REVERT_TYPE'), hb.codes('QMS_4M_EFFECT'), hb.codes('QMS_4M_NOTIFY'), hb.codes('PLM_PCN_STATUS')]);
    }).then(function (r) {
        m4Names = r[0];
        M4 = Object.keys(m4Names);
        kindNames = r[1];
        statusNames = r[2];
        revertTypeNames = r[5];
        effectNames = r[6];
        notifyNames = r[7];
        pcnStatusNames = r[8];
        var ecoId = Number(new URLSearchParams(window.location.search).get('ecoId'));
        return startGrid().then(function () { return sp.search(); }).then(function () {
            if (ecoId > 0) {
                hb.page.openDetail();
                return load(ecoId);
            }
        });
    }).then(null, function (err) { notify(err && err.message, true); });
})();
