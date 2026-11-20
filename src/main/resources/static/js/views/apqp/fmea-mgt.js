// @ts-check
/*
 * PFMEA (apqp-fmea) — P6 APQP A1 (docs/41 4장).
 * 목록(hb.grid, 클릭·Enter = 상세) → 상세: 머리 / 구조 트리 / 고른 요소의 기능·고장 / 고른 단계의 위험 분석표 / 고른 위험행의 조치.
 * 고칠 때마다 서버에 한 줄씩 보내고 상세를 다시 읽는다(S·AP 는 서버가 계산). 고칠 수 있는지는 서버가 준 editable·actionEditable,
 * 판정(수준·짝·확정본·회사)은 서버·트리거 — 화면은 단추만 숨긴다.
 */
(function () {
    'use strict';

    /** DFMEA(apqp-dfmea, A5-2) 도 이 스크립트 — 경로·구조 수준 이름만 다르다. 라우팅·공정 흐름·라우팅 바꾸기·QMS 문서는 PFMEA 만 */
    var PROG = document.body.getAttribute('data-prog') || 'apqp-fmea';
    var DF = PROG === 'apqp-dfmea';
    var API = DF ? '/api/apqp/dfmeas' : '/api/apqp/fmeas';
    /** 하위 행 경로 앞부분(…-nodes·-funcs·-fails·-chains·-risks·-actions) */
    var ROW = DF ? '/api/apqp/dfmea-' : '/api/apqp/fmea-';
    /** 고르기 목록 경로 앞부분(items·defects·projects·users) */
    var PICK = DF ? '/api/apqp/dfmea/' : '/api/apqp/';
    var ROOT = DF ? 'SYSTEM' : 'ITEM';
    var MID = DF ? 'SUBSYSTEM' : 'STEP';
    var LEAF = DF ? 'COMPONENT' : 'ELEMENT';
    var ACTIONS = (document.body.getAttribute('data-actions') || '').split(',');
    var CAN_SAVE = ACTIONS.indexOf('SAVE') >= 0;
    var CAN_APPROVE = ACTIONS.indexOf('APPROVE') >= 0;
    var CAN_PRINT = ACTIONS.indexOf('PRINT') >= 0;
    var msg = /** @type {HTMLElement} */ (document.getElementById('msg'));
    var listView = /** @type {HTMLElement} */ (document.getElementById('listView'));
    var detailView = /** @type {HTMLElement} */ (document.getElementById('detailView'));
    var headForm = /** @type {HTMLFormElement} */ (document.getElementById('headForm'));
    var nodeForm = /** @type {HTMLFormElement} */ (document.getElementById('nodeForm'));
    var riskForm = /** @type {HTMLFormElement} */ (document.getElementById('riskForm'));
    var actionForm = /** @type {HTMLFormElement} */ (document.getElementById('actionForm'));
    /** 서버 상세(FmeaDetail) — 신규 작성 중이면 null @type {any} */
    var d = null;
    /** @type {number | null} */
    var selNode = null;
    /** @type {number | null} */
    var selRisk = null;
    /** @type {number | null} */
    var selAction = null;
    /** @type {any[]} */
    var chars = [];
    /** PFMEA: 같은 품목 확정 DFMEA 의 고장 형태(고장 영향 제안, A5-2b D7) @type {any[]} */
    var dfmeaSources = [];
    /** @type {any[]} */
    var defects = [];
    /** @type {any[]} */
    var items = [];
    /** @type {Record<string, string>} */
    var m4Names = {};
    /** @type {Record<string, string>} */
    var specialNames = {};
    /** @type {Record<string, string>} */
    var actionStatusNames = {};
    /** @type {Record<string, string>} */
    var statusNames = {};
    /** @type {HB.SearchPanel} */
    var sp;
    /** @type {HB.Grid} */
    var grid;

    /** @param {string | null | undefined} text @param {boolean} [error] */
    function notify(text, error) {
        msg.textContent = text || '';
        msg.classList.toggle('hb-msg-error', !!error);
    }

    /** @param {HTMLFormElement | null} [f] */
    function fail(f) {
        /** @param {any} err */
        return function (err) {
            if (err && err.stale) { return; }
            var e0 = err && err.errors && err.errors.length ? err.errors[0] : null;
            notify(e0 ? e0.message : err && err.message, true);
            if (f) { hb.showFieldErrors(f, err && err.errors); }
        };
    }

    /** @param {HTMLFormElement} f @param {string} n */
    function fld(f, n) { return /** @type {HTMLInputElement} */ (f.querySelector('[data-field="' + n + '"]')); }

    /** @param {HTMLSelectElement} sel @param {string} value @param {string} text */
    function option(sel, value, text) {
        var o = /** @type {HTMLOptionElement} */ (hb.el('option', null, text));
        o.value = value;
        sel.appendChild(o);
    }

    /** "3" → 3, 빈 칸 → null, 그 밖은 글자 그대로(서버가 범위 오류) @param {string} v */
    function num(v) {
        var s = v.trim();
        return s === '' ? null : (/^\d+$/.test(s) ? Number(s) : s);
    }

    /** @param {string} title @param {string} message @param {string} ok @param {boolean} [danger] */
    function ask(title, message, ok, danger) {
        return hb.dialog.confirm({title: title, message: message, ok: ok, danger: !!danger});
    }

    function leaveOk() {
        if (!hb.dirty.isDirty()) { return Promise.resolve(true); }
        return hb.dialog.confirm({title: hb.t('js.common.leaveTitle'), message: hb.t('js.common.confirmLeave'), ok: hb.t('js.common.leaveOk')});
    }

    /** @param {string | null | undefined} ap */
    function apBadge(ap) {
        /** @type {Record<string, HB.StatusTone>} */
        var tone = {H: 'danger', M: 'warning', L: 'neutral'};
        return ap ? hb.status(tone[ap] || 'neutral', ap) : hb.el('span', 'hb-muted', '—');
    }

    /** 문구 — 키는 글자 그대로 써 둔다(I18nKeyGuardTest) @param {string} k */
    function kindName(k) {
        /** @type {Record<string, string>} */
        var m = {ITEM: hb.t('js.apqp.kind.ITEM'), STEP: hb.t('js.apqp.kind.STEP'), ELEMENT: hb.t('js.apqp.kind.ELEMENT'),
            SYSTEM: hb.t('js.apqp.kind.SYSTEM'), SUBSYSTEM: hb.t('js.apqp.kind.SUBSYSTEM'), COMPONENT: hb.t('js.apqp.kind.COMPONENT')};
        return m[k] || k;
    }
    /** @param {string} k */
    function actionKindName(k) {
        /** @type {Record<string, string>} */
        var m = {PREVENT: hb.t('js.apqp.action.PREVENT'), DETECT: hb.t('js.apqp.action.DETECT')};
        return m[k] || k;
    }
    /** @param {string} k */
    function addFailLabel(k) {
        /** @type {Record<string, string>} */
        var m = {FE: hb.t('js.apqp.addFail.FE'), FM: hb.t('js.apqp.addFail.FM'), FC: hb.t('js.apqp.addFail.FC')};
        return m[k] || k;
    }
    /** @param {string} k */
    function failPlaceholder(k) {
        /** @type {Record<string, string>} */
        var m = {FE: hb.t('js.apqp.failPlaceholder.FE'), FM: hb.t('js.apqp.failPlaceholder.FM'), FC: hb.t('js.apqp.failPlaceholder.FC')};
        return m[k] || '';
    }
    /** @param {string} k */
    function addNodeTitle(k) {
        if (DF) { return k === MID ? hb.t('js.apqp.dlg.addSub') : hb.t('js.apqp.dlg.addComp'); }
        return k === 'STEP' ? hb.t('js.apqp.dlg.addStep') : hb.t('js.apqp.dlg.addElement');
    }

    // ------------------------------------------------------------------ 목록

    function params() {
        var v = sp.values();
        /** @type {Record<string, unknown>} */
        var q = {};
        ['keyword', 'status'].forEach(function (k) { if (v[k]) { q[k] = v[k]; } });
        return q;
    }

    function openSelected() {
        var row = grid.selected();
        if (row) { openFmea(Number(row.fmeaId)); }
    }

    function showList() {
        leaveOk().then(function (ok) {
            if (!ok) { return; }
            hb.dirty.clear();
            detailView.hidden = true;
            listView.hidden = false;
            d = null;
            grid.reload(true);
        });
    }

    // ------------------------------------------------------------------ 상세 읽기

    /** @param {number} id */
    function openFmea(id) {
        selNode = null;
        selRisk = null;
        selAction = null;
        return load(id).then(function () {
            listView.hidden = true;
            detailView.hidden = false;
        });
    }

    /** 상세를 다시 읽고 그린다(고른 노드·위험행은 남아 있으면 그대로) @param {number} id */
    function load(id) {
        return Promise.all([hb.latest('fmea', hb.api(API + '/' + id)), hb.api(API + '/' + id + '/chars'),
            DF ? Promise.resolve({data: []}) : hb.api(API + '/' + id + '/dfmea-sources')]).then(function (r) {
            d = r[0].data;
            chars = r[1].data;
            dfmeaSources = r[2].data;
            actionIssues = {};
            loadActionIssues();
            if (selNode === null || !nodeOf(selNode)) { selNode = d.nodes.length ? d.nodes[0].nodeId : null; }
            if (selRisk !== null && !d.risks.some(/** @param {any} x */ function (x) { return x.riskId === selRisk; })) { selRisk = null; }
            render();
            loadDoc();
        }, fail());
    }

    function reload() {
        return d ? load(d.fmea.fmeaId) : Promise.resolve();
    }

    /** @param {number} id */
    function nodeOf(id) { return d ? d.nodes.filter(/** @param {any} n */ function (n) { return n.nodeId === id; })[0] : null; }
    /** @param {number} id */
    function funcOf(id) { return d.funcs.filter(/** @param {any} n */ function (n) { return n.funcId === id; })[0]; }
    /** @param {number} id */
    function failOf(id) { return d.fails.filter(/** @param {any} n */ function (n) { return n.failId === id; })[0]; }
    /** @param {number | null} parentId */
    function childrenOf(parentId) {
        return d.nodes.filter(/** @param {any} n */ function (n) { return n.parentId === parentId; })
            .sort(/** @param {any} a @param {any} b */ function (a, b) { return a.seq - b.seq || a.nodeId - b.nodeId; });
    }
    /** @param {number} nodeId @param {string} kind */
    function failsOfNode(nodeId, kind) {
        var funcIds = d.funcs.filter(/** @param {any} f */ function (f) { return f.nodeId === nodeId; })
            .map(/** @param {any} f */ function (f) { return f.funcId; });
        return d.fails.filter(/** @param {any} x */ function (x) { return x.failKind === kind && funcIds.indexOf(x.funcId) >= 0; });
    }

    function render() {
        renderHead();
        renderApproval();
        renderTree();
        renderNode();
        renderRisks();
        renderRiskBox();
    }

    // ------------------------------------------------------------------ 머리

    function renderHead() {
        var f = d ? d.fmea : null;
        var edit = !f || d.editable;
        /** @type {HTMLElement} */ (document.getElementById('title')).textContent = f
            ? f.fmeaNo + ' rev ' + f.rev + ' · ' + f.title : hb.t('js.apqp.creating');
        var st = /** @type {HTMLElement} */ (document.getElementById('statusBox'));
        st.textContent = '';
        if (f) {
            /** @type {Record<string, HB.StatusTone>} */
            var tone = {DRAFT: 'info', IN_APPROVAL: 'warning', RELEASED: 'success', OBSOLETE: 'neutral'};
            st.appendChild(hb.status(tone[f.status] || 'neutral', statusNames[f.status] || f.status));
        }
        /** @type {HTMLElement} */ (document.getElementById('summary')).textContent = f
            ? (DF ? hb.t('js.apqp.dfSummary', f.itemCd + ' ' + f.itemNm, f.bomText || '-', f.riskCnt, f.highCnt, f.openActionCnt)
                : hb.t('js.apqp.summary', f.itemCd + ' ' + f.itemNm, f.routingText || '-', f.riskCnt, f.highCnt, f.openActionCnt))
                + (f.draftExists ? ' · ' + hb.t('js.apqp.draftExists') : '') : '';
        (DF ? ['itemCd', 'title', 'scope', 'team'] : ['itemCd', 'title', 'scope', 'team', 'drawingRev']).forEach(function (n) {
            fld(headForm, n).value = f ? (f[n] || '') : '';
        });
        fld(headForm, 'itemCd').readOnly = !!f;
        (fld(headForm, 'projId')).value = f && f.projId ? String(f.projId) : '';
        (fld(headForm, 'apSetId')).value = f && f.apSetId ? String(f.apSetId) : '';
        headForm.querySelectorAll('input, select, textarea').forEach(function (el) {
            /** @type {HTMLInputElement} */ (el).disabled = !CAN_SAVE || !edit;
        });
        headForm.querySelectorAll('.apqp-create-only').forEach(function (el) { /** @type {HTMLElement} */ (el).hidden = !!f; });
        /** @type {HTMLDetailsElement} */ (document.getElementById('headDetails')).open = !f;
        if (f && DF) {
            loadBoms(f.itemId, f.bomId);
            fillDocRevs(f.docRevId ? [{docRevId: f.docRevId, text: f.docRevText || String(f.docRevId)}] : [], f.docRevId);
        } else if (f) {
            loadRoutings(f.itemId, f.routingId);
        }
        show('saveHeadBtn', CAN_SAVE && edit);
        show('releaseBtn', CAN_APPROVE && !!f && d.editable && !d.approvalOn);
        show('reviseBtn', CAN_SAVE && !!f && f.status === 'RELEASED' && !f.draftExists);
        show('excelBtn', CAN_PRINT && !!f);
        show('flowBtn', !DF && !!f && !!f.routingId);
        /** @type {HTMLElement} */ (document.getElementById('flowBox')).hidden = true;
        /** @type {HTMLElement} */ (document.getElementById('flowBtn')).setAttribute('aria-expanded', 'false');
        show('deleteBtn', CAN_SAVE && !!f && d.editable);
        /** @type {HTMLElement} */ (document.getElementById('work')).hidden = !f;
        /** @type {HTMLElement} */ (document.querySelector('.apqp-risk')).hidden = !f;
        detailView.querySelectorAll('.apqp-edit').forEach(function (el) { /** @type {HTMLElement} */ (el).hidden = !f || !d.editable; });
    }

    /** @param {string} id @param {boolean} on */
    function show(id, on) {
        var b = document.getElementById(id);
        if (b) { b.hidden = !on; }
    }

    /** @param {number} itemId @param {number | null} selected */
    function loadRoutings(itemId, selected) {
        return hb.latest('routings', hb.api('/api/apqp/items/' + itemId + '/routings')).then(function (res) {
            var sel = /** @type {HTMLSelectElement} */ (document.getElementById('routingSel'));
            sel.textContent = '';
            option(sel, '', hb.t('js.apqp.noRouting'));
            res.data.forEach(/** @param {any} r */ function (r) {
                option(sel, String(r.routingId), r.plantCd + ' · ' + r.altNo + ' · ' + r.rev + ' (' + r.status + ', ' + r.validFrom + ')');
            });
            sel.value = selected ? String(selected) : '';
        }, fail());
    }

    /** DFMEA — 머리 품목의 확정 BOM @param {number} itemId @param {number | null} selected */
    function loadBoms(itemId, selected) {
        return hb.latest('boms', hb.api(PICK + 'items/' + itemId + '/boms')).then(function (res) {
            var sel = /** @type {HTMLSelectElement} */ (document.getElementById('bomSel'));
            sel.textContent = '';
            option(sel, '', hb.t('js.apqp.noBom'));
            res.data.forEach(/** @param {any} b */ function (b) {
                option(sel, String(b.bomId), b.altNo + ' · ' + b.rev + ' (' + b.validFrom + ')');
            });
            sel.value = selected ? String(selected) : '';
        }, fail());
    }

    /** DFMEA — 도면 문서 리비전 고르기 @param {{docRevId: number, text: string}[]} list @param {number | null} selected */
    function fillDocRevs(list, selected) {
        var sel = /** @type {HTMLSelectElement} */ (document.getElementById('docRevSel'));
        sel.textContent = '';
        option(sel, '', hb.t('js.apqp.noDrawing'));
        list.forEach(function (x) { option(sel, String(x.docRevId), x.text); });
        sel.value = selected ? String(selected) : '';
    }

    var drawingNo = /** @type {HTMLInputElement | null} */ (document.getElementById('drawingNo'));
    if (drawingNo) {
        var dno = drawingNo;
        dno.addEventListener('change', function () {
            var cur = fld(headForm, 'docRevId').value;
            hb.latest('drawingRevs', hb.api(PICK + 'drawing-revs?docNo=' + encodeURIComponent(dno.value.trim()))).then(function (res) {
                fillDocRevs(res.data.map(/** @param {any} r */ function (r) {
                    return {docRevId: r.docRevId, text: r.docNo + ' rev ' + r.rev + ' · ' + r.docNm + ' (' + r.status + ')'};
                }), cur ? Number(cur) : null);
            }, fail());
        });
    }

    var itemInput = fld(headForm, 'itemCd');
    var itemTimer = 0;
    itemInput.addEventListener('input', function () {
        window.clearTimeout(itemTimer);
        itemTimer = window.setTimeout(function () {
            hb.latest('items', hb.api(PICK + 'items?keyword=' + encodeURIComponent(itemInput.value.trim()))).then(function (res) {
                items = res.data;
                var dl = /** @type {HTMLElement} */ (document.getElementById('itemList'));
                dl.textContent = '';
                items.forEach(function (i) {
                    var o = /** @type {HTMLOptionElement} */ (hb.el('option', null, i.itemNm));
                    o.value = i.itemCd;
                    dl.appendChild(o);
                });
                var hit = items.filter(function (i) { return i.itemCd === itemInput.value.trim(); })[0];
                if (hit) {
                    if (DF) { loadBoms(hit.itemId, null); } else { loadRoutings(hit.itemId, null); }
                }
            }, fail());
        }, 250);
    });

    function headBody() {
        /** @param {string} n */
        function idOf(n) { var v = fld(headForm, n).value; return v ? Number(v) : null; }
        if (DF) {
            return {itemCd: fld(headForm, 'itemCd').value, bomId: idOf('bomId'), docRevId: idOf('docRevId'), projId: idOf('projId'),
                apSetId: idOf('apSetId'), title: fld(headForm, 'title').value, scope: fld(headForm, 'scope').value,
                team: fld(headForm, 'team').value, fromBom: fld(headForm, 'fromBom').checked, rowVer: d ? d.fmea.rowVer : null};
        }
        return {itemCd: fld(headForm, 'itemCd').value, routingId: idOf('routingId'), projId: idOf('projId'), apSetId: idOf('apSetId'),
            title: fld(headForm, 'title').value, scope: fld(headForm, 'scope').value, team: fld(headForm, 'team').value,
            drawingRev: fld(headForm, 'drawingRev').value, fromRouting: fld(headForm, 'fromRouting').checked,
            rowVer: d ? d.fmea.rowVer : null};
    }

    // ------------------------------------------------------------------ 구조 트리

    function renderTree() {
        var ul = /** @type {HTMLElement} */ (document.getElementById('tree'));
        ul.textContent = '';
        if (!d) { return; }
        /** @param {any} n @param {HTMLElement} host */
        function add(n, host) {
            var li = hb.el('li', 'apqp-tree-' + n.nodeKind.toLowerCase());
            li.setAttribute('role', 'treeitem');
            var b = /** @type {HTMLButtonElement} */ (hb.el('button', 'hb-btn-ghost apqp-node-btn'));
            b.type = 'button';
            b.setAttribute('aria-pressed', n.nodeId === selNode ? 'true' : 'false');
            b.appendChild(hb.el('span', 'apqp-kind', kindName(n.nodeKind)));
            b.appendChild(document.createTextNode(' ' + n.nodeNm + (n.m4 ? ' (' + (m4Names[n.m4] || n.m4) + ')' : '')));
            var fm = n.nodeKind === MID ? failsOfNode(n.nodeId, 'FM').length : 0;
            if (fm) { b.appendChild(hb.el('span', 'hb-muted', ' · FM ' + fm)); }
            b.addEventListener('click', function () {
                leaveOk().then(function (ok) {
                    if (!ok) { return; }
                    hb.dirty.clear();
                    selNode = n.nodeId;
                    selRisk = null;
                    render();
                });
            });
            li.appendChild(b);
            var kids = childrenOf(n.nodeId);
            if (kids.length) {
                var sub = hb.el('ul');
                sub.setAttribute('role', 'group');
                kids.forEach(function (/** @type {any} */ k) { add(k, sub); });
                li.appendChild(sub);
            }
            host.appendChild(li);
        }
        childrenOf(null).forEach(function (/** @type {any} */ n) { add(n, ul); });
        var cur = selNode ? nodeOf(selNode) : null;
        /** @type {HTMLButtonElement} */ (document.getElementById('addElemBtn')).disabled = !cur || cur.nodeKind === ROOT;
        /** @type {HTMLButtonElement} */ (document.getElementById('deleteNodeBtn')).disabled = !cur || cur.nodeKind === ROOT;
        detailView.querySelectorAll('[data-move]').forEach(function (b) {
            /** @type {HTMLButtonElement} */ (b).disabled = !cur || cur.nodeKind === ROOT;
        });
    }

    // ------------------------------------------------------------------ 고른 요소: 이름 · 기능 · 고장

    function renderNode() {
        var n = selNode ? nodeOf(selNode) : null;
        /** @type {HTMLElement} */ (document.getElementById('nodeTitle')).textContent = n
            ? kindName(n.nodeKind) + ' · ' + n.nodeNm : '';
        nodeForm.hidden = !n || n.nodeKind === ROOT;
        if (n) {
            fld(nodeForm, 'nodeNm').value = n.nodeNm;
            /** @type {HTMLElement} */ (document.getElementById('stepBox')).hidden = DF || n.nodeKind !== 'STEP';
            /** @type {HTMLElement} */ (document.getElementById('m4Box')).hidden = DF || n.nodeKind !== 'ELEMENT';
            var stepSel = /** @type {HTMLSelectElement} */ (document.getElementById('stepSel'));
            stepSel.textContent = '';
            option(stepSel, '', hb.t('js.apqp.noStep'));
            d.steps.forEach(/** @param {any} s */ function (s) { option(stepSel, String(s.stepId), s.opSeq + ' ' + s.procNm); });
            stepSel.value = n.stepId ? String(n.stepId) : '';
            (fld(nodeForm, 'm4')).value = n.m4 || 'MAN';
            nodeForm.querySelectorAll('input, select').forEach(function (el) { /** @type {HTMLInputElement} */ (el).disabled = !d.editable; });
        }
        var box = /** @type {HTMLElement} */ (document.getElementById('funcs'));
        box.textContent = '';
        if (!n) { return; }
        var kind = n.nodeKind === ROOT ? 'FE' : (n.nodeKind === MID ? 'FM' : 'FC');
        d.funcs.filter(/** @param {any} f */ function (f) { return f.nodeId === n.nodeId; }).forEach(/** @param {any} f */ function (f) {
            var div = hb.el('div', 'apqp-func');
            var head = hb.el('div', 'apqp-line');
            head.appendChild(hb.el('strong', null, f.funcText));
            if (f.charNo) { head.appendChild(hb.el('span', 'apqp-chip', f.charNo + ' ' + f.charNm + (f.charSpecial && f.charSpecial !== 'NONE' ? ' ◆' + f.charSpecial : ''))); }
            if (d.editable) {
                head.appendChild(smallBtn(hb.t('js.apqp.edit'), function () { editFunc(f); }));
                head.appendChild(smallBtn(hb.t('js.apqp.remove'), function () {
                    ask(hb.t('js.apqp.dlg.deleteFunc'), hb.t('js.apqp.confirmDeleteFunc', f.funcText), hb.t('js.apqp.dlg.delete'), true).then(function (ok) {
                        if (ok) { send(ROW + 'funcs/' + f.funcId + '?rowVer=' + f.rowVer, 'DELETE', null, null); }
                    });
                }));
            }
            div.appendChild(head);
            var ul = hb.el('ul', 'apqp-fails');
            d.fails.filter(/** @param {any} x */ function (x) { return x.funcId === f.funcId; }).forEach(/** @param {any} x */ function (x) {
                var li = hb.el('li', 'apqp-line');
                li.appendChild(hb.el('span', 'apqp-kind apqp-kind-' + x.failKind, x.failKind));
                li.appendChild(hb.el('span', null, x.failText));
                if (x.failKind === 'FE') { li.appendChild(hb.el('span', 'apqp-chip', 'S ' + x.severity)); }
                if (x.defectCd) { li.appendChild(hb.el('span', 'apqp-chip', x.defectCd + ' ' + x.defectNm)); }
                if (x.srcText) { li.appendChild(hb.el('span', 'apqp-chip', hb.t('js.apqp.srcOf', x.srcText))); }
                if (d.editable) {
                    li.appendChild(smallBtn(hb.t('js.apqp.edit'), function () { editFail(x); }));
                    li.appendChild(smallBtn(hb.t('js.apqp.remove'), function () {
                        ask(hb.t('js.apqp.dlg.deleteFail'), hb.t('js.apqp.confirmDeleteFail', x.failText), hb.t('js.apqp.dlg.delete'), true).then(function (ok) {
                            if (ok) { send(ROW + 'fails/' + x.failId + '?rowVer=' + x.rowVer, 'DELETE', null, null); }
                        });
                    }));
                }
                ul.appendChild(li);
            });
            div.appendChild(ul);
            if (d.editable) { div.appendChild(failForm(f.funcId, kind, null)); }
            box.appendChild(div);
        });
        if (d.editable) { box.appendChild(funcForm(n, null)); }
    }

    /** @param {string} text @param {() => void} fn */
    function smallBtn(text, fn) {
        var b = /** @type {HTMLButtonElement} */ (hb.el('button', 'hb-btn-ghost apqp-small', text));
        b.type = 'button';
        b.addEventListener('click', fn);
        return b;
    }

    /** 기능 추가·고치기 칸 — 글자 + 특성(이 품목 제품 특성 · 라우팅 공정 특성) @param {any} n @param {any} f */
    function funcForm(n, f) {
        var form = /** @type {HTMLFormElement} */ (hb.el('form', 'apqp-inline'));
        var text = /** @type {HTMLInputElement} */ (hb.el('input'));
        text.setAttribute('data-field', 'funcText');
        text.maxLength = 500;
        text.placeholder = hb.t('js.apqp.funcPlaceholder');
        text.setAttribute('aria-label', hb.t('js.apqp.funcText'));
        text.value = f ? f.funcText : '';
        var sel = /** @type {HTMLSelectElement} */ (hb.el('select'));
        sel.setAttribute('data-field', 'charId');
        sel.setAttribute('aria-label', hb.t('js.apqp.char'));
        option(sel, '', hb.t('js.apqp.noChar'));
        chars.forEach(function (c) {
            option(sel, String(c.charId), (c.charType === 'PRODUCT' ? 'P ' : 'S ') + c.charNo + ' ' + c.charNm
                + (c.inDfmea ? ' · ' + hb.t('js.apqp.inDfmea') : ''));
        });
        sel.value = f && f.charId ? String(f.charId) : '';
        var ok = /** @type {HTMLButtonElement} */ (hb.el('button', 'hb-btn-secondary', f ? hb.t('js.apqp.save') : hb.t('js.apqp.addFunc')));
        ok.type = 'submit';
        form.appendChild(text);
        form.appendChild(sel);
        form.appendChild(ok);
        form.addEventListener('submit', function (e) {
            e.preventDefault();
            var body = {nodeId: n.nodeId, funcText: text.value, charId: sel.value ? Number(sel.value) : null, rowVer: f ? f.rowVer : null};
            send(f ? ROW + 'funcs/' + f.funcId : API + '/' + d.fmea.fmeaId + '/funcs', f ? 'PUT' : 'POST', body, form);
        });
        return form;
    }

    /** @param {any} f */
    function editFunc(f) {
        var box = /** @type {HTMLElement} */ (document.getElementById('funcs'));
        var n = nodeOf(f.nodeId);
        box.insertBefore(funcForm(n, f), box.firstChild);
    }

    /** 고장 추가·고치기 칸 — 종류는 구조 수준(아이템 = 영향 + S, 단계 = 형태 + 불량 유형, 작업요소 = 원인) @param {number} funcId @param {string} kind @param {any} x */
    function failForm(funcId, kind, x) {
        var form = /** @type {HTMLFormElement} */ (hb.el('form', 'apqp-inline'));
        form.appendChild(hb.el('span', 'apqp-kind apqp-kind-' + kind, kind));
        var text = /** @type {HTMLInputElement} */ (hb.el('input'));
        text.setAttribute('data-field', 'failText');
        text.maxLength = 500;
        text.placeholder = failPlaceholder(kind);
        text.setAttribute('aria-label', hb.t('js.apqp.failText'));
        text.value = x ? x.failText : '';
        form.appendChild(text);
        /** @type {HTMLInputElement | null} */
        var sev = null;
        /** @type {HTMLSelectElement | null} */
        var defect = null;
        if (kind === 'FE') {
            sev = /** @type {HTMLInputElement} */ (hb.el('input', 'apqp-rating'));
            sev.setAttribute('data-field', 'severity');
            sev.inputMode = 'numeric';
            sev.maxLength = 2;
            sev.placeholder = 'S';
            sev.setAttribute('aria-label', hb.t('js.apqp.severity'));
            sev.value = x && x.severity ? String(x.severity) : '';
            form.appendChild(sev);
        }
        // PFMEA 고장 영향: 같은 품목 확정 DFMEA 의 고장 형태를 제안(고르면 글자 복사 + 출처) — A5-2b D7
        /** @type {number | null} */
        var src = x ? x.srcFailId : null;
        if (kind === 'FE' && !DF && dfmeaSources.length) {
            var sug = /** @type {HTMLSelectElement} */ (hb.el('select'));
            sug.setAttribute('aria-label', hb.t('js.apqp.suggestFe'));
            option(sug, '', hb.t('js.apqp.suggestFe'));
            dfmeaSources.forEach(/** @param {any} s */ function (s) {
                option(sug, String(s.failId), s.fmeaNo + ' rev ' + s.rev + ' · ' + s.nodeNm + ' · ' + s.failText);
            });
            sug.value = src ? String(src) : '';
            sug.addEventListener('change', function () {
                var hit = dfmeaSources.filter(/** @param {any} s */ function (s) { return String(s.failId) === sug.value; })[0];
                src = hit ? hit.failId : null;
                if (hit) { text.value = hit.failText; }
            });
            form.insertBefore(sug, text);
        }
        if (kind === 'FM') {
            defect = /** @type {HTMLSelectElement} */ (hb.el('select'));
            defect.setAttribute('data-field', 'defectId');
            defect.setAttribute('aria-label', hb.t('js.apqp.defect'));
            option(defect, '', hb.t('js.apqp.noDefect'));
            defects.forEach(function (df) { option(/** @type {HTMLSelectElement} */ (defect), String(df.defectId), df.defectCd + ' ' + df.defectNm); });
            defect.value = x && x.defectId ? String(x.defectId) : '';
            form.appendChild(defect);
        }
        var ok = /** @type {HTMLButtonElement} */ (hb.el('button', 'hb-btn-ghost', x ? hb.t('js.apqp.save') : addFailLabel(kind)));
        ok.type = 'submit';
        form.appendChild(ok);
        form.addEventListener('submit', function (e) {
            e.preventDefault();
            var body = {funcId: funcId, failText: text.value, severity: sev ? num(sev.value) : null,
                defectId: defect && defect.value ? Number(defect.value) : null, rowVer: x ? x.rowVer : null, srcFailId: src};
            send(x ? ROW + 'fails/' + x.failId : API + '/' + d.fmea.fmeaId + '/fails', x ? 'PUT' : 'POST', body, form);
        });
        return form;
    }

    /** @param {any} x */
    function editFail(x) {
        var f = funcOf(x.funcId);
        var box = /** @type {HTMLElement} */ (document.getElementById('funcs'));
        box.insertBefore(failForm(f.funcId, x.failKind, x), box.firstChild);
    }

    // ------------------------------------------------------------------ 위험 분석표 (고른 단계)

    function stepOfSelection() {
        var n = selNode ? nodeOf(selNode) : null;
        if (!n) { return null; }
        if (n.nodeKind === MID) { return n; }
        if (n.nodeKind === LEAF) { return nodeOf(n.parentId); }
        return null;
    }

    function renderRisks() {
        var table = /** @type {HTMLTableElement} */ (document.getElementById('riskTable'));
        var tbody = /** @type {HTMLTableSectionElement} */ (table.tBodies[0]);
        tbody.textContent = '';
        var step = d ? stepOfSelection() : null;
        table.hidden = !step;
        /** @type {HTMLElement} */ (document.getElementById('riskHint')).hidden = !!step;
        /** @type {HTMLElement} */ (document.getElementById('riskTitle')).textContent = step
            ? hb.t('js.apqp.riskOf', step.nodeNm) : hb.t('js.apqp.riskTitle');
        if (!step) { return; }
        var fes = d.fails.filter(/** @param {any} x */ function (x) { return x.failKind === 'FE'; });
        /** @type {any[]} */
        var fcs = [];
        childrenOf(step.nodeId).forEach(/** @param {any} el */ function (el) { fcs = fcs.concat(failsOfNode(el.nodeId, 'FC')); });
        var fms = failsOfNode(step.nodeId, 'FM');
        if (!fms.length) {
            var tr0 = hb.el('tr');
            var td0 = hb.el('td', 'hb-muted', hb.t('js.apqp.noFm'));
            td0.colSpan = 11;
            tr0.appendChild(td0);
            tbody.appendChild(tr0);
        }
        fms.forEach(/** @param {any} fm */ function (fm) {
            var risks = d.risks.filter(/** @param {any} r */ function (r) { return r.fmId === fm.failId; });
            var span = Math.max(1, risks.length);
            var first = hb.el('tr', 'apqp-fm-first');
            var fmTd = hb.el('td', 'apqp-fm');
            fmTd.rowSpan = span;
            fmTd.appendChild(hb.el('div', null, fm.failText));
            var feTd = hb.el('td', 'apqp-fe');
            feTd.rowSpan = span;
            var chains = d.chains.filter(/** @param {any} c */ function (c) { return c.fmId === fm.failId; });
            chains.forEach(/** @param {any} c */ function (c) {
                var fe = failOf(c.feId);
                var chip = hb.el('span', 'apqp-chip', fe.failText + ' (S' + fe.severity + ')');
                if (d.editable) {
                    chip.appendChild(smallBtn('×', function () { send(ROW + 'chains/' + c.chainId, 'DELETE', null, null); }));
                }
                feTd.appendChild(chip);
            });
            if (d.editable) {
                var linked = chains.map(/** @param {any} c */ function (c) { return c.feId; });
                var free = fes.filter(/** @param {any} fe */ function (fe) { return linked.indexOf(fe.failId) < 0; });
                if (free.length) { feTd.appendChild(pickForm(free, hb.t('js.apqp.linkFe'), function (/** @type {number} */ feId) {
                    send(API + '/' + d.fmea.fmeaId + '/chains', 'POST', {fmId: fm.failId, feId: feId}, null);
                }, /** @param {any} fe */ function (fe) { return fe.failText + ' (S' + fe.severity + ')'; })); }
                var paired = risks.map(/** @param {any} r */ function (r) { return r.fcId; });
                var freeFc = fcs.filter(/** @param {any} fc */ function (fc) { return paired.indexOf(fc.failId) < 0; });
                if (freeFc.length) { fmTd.appendChild(pickForm(freeFc, hb.t('js.apqp.addRisk'), function (/** @type {number} */ fcId) {
                    send(API + '/' + d.fmea.fmeaId + '/risks', 'POST', {fmId: fm.failId, fcId: fcId}, null);
                }, /** @param {any} fc */ function (fc) { return fc.failText; })); }
            }
            first.appendChild(fmTd);
            first.appendChild(feTd);
            if (!risks.length) {
                var empty = hb.el('td', 'hb-muted', hb.t('js.apqp.noRisk'));
                empty.colSpan = 9;
                first.appendChild(empty);
                tbody.appendChild(first);
                return;
            }
            risks.forEach(/** @param {any} r @param {number} i */ function (r, i) {
                var tr = i === 0 ? first : hb.el('tr');
                tr.classList.add('hb-row-pick');
                tr.setAttribute('aria-selected', r.riskId === selRisk ? 'true' : 'false');
                var acts = d.actions.filter(/** @param {any} a */ function (a) { return a.riskId === r.riskId; });
                var open = acts.filter(/** @param {any} a */ function (a) { return a.status === 'OPEN'; }).length;
                [failOf(r.fcId).failText, r.prevCtrl || '', r.occ == null ? '' : String(r.occ), r.detCtrl || '',
                    r.det == null ? '' : String(r.det), r.sev == null ? '' : String(r.sev)].forEach(function (v) {
                    var td = hb.el('td', null, v);
                    td.tabIndex = -1;
                    tr.appendChild(td);
                });
                var apTd = hb.el('td');
                apTd.appendChild(apBadge(r.ap));
                tr.appendChild(apTd);
                tr.appendChild(hb.el('td', null, r.specialCls ? (specialNames[r.specialCls] || r.specialCls) : ''));
                tr.appendChild(hb.el('td', null, acts.length ? open + ' / ' + acts.length : ''));
                tr.addEventListener('click', function (e) {
                    if (e.target instanceof Element && e.target.closest('.apqp-fm, .apqp-fe')) { return; }
                    pickRisk(r.riskId);
                });
                if (i > 0) { tbody.appendChild(tr); }
                else { tbody.appendChild(first); }
            });
        });
    }

    /** 고르기 + 단추 한 줄 @param {any[]} list @param {string} label @param {(id: number) => void} fn @param {(x: any) => string} text */
    function pickForm(list, label, fn, text) {
        var form = /** @type {HTMLFormElement} */ (hb.el('form', 'apqp-inline'));
        var sel = /** @type {HTMLSelectElement} */ (hb.el('select'));
        sel.setAttribute('aria-label', label);
        list.forEach(function (x) { option(sel, String(x.failId), text(x)); });
        var ok = /** @type {HTMLButtonElement} */ (hb.el('button', 'hb-btn-ghost apqp-small', label));
        ok.type = 'submit';
        form.appendChild(sel);
        form.appendChild(ok);
        form.addEventListener('submit', function (e) {
            e.preventDefault();
            fn(Number(sel.value));
        });
        return form;
    }

    /** @param {number} riskId */
    function pickRisk(riskId) {
        leaveOk().then(function (ok) {
            if (!ok) { return; }
            hb.dirty.clear();
            selRisk = riskId;
            selAction = null;
            renderRisks();
            renderRiskBox();
            /** @type {HTMLElement} */ (document.getElementById('riskBox')).scrollIntoView({block: 'nearest'});
        });
    }

    // ------------------------------------------------------------------ 위험행 · 조치

    function renderRiskBox() {
        var box = /** @type {HTMLElement} */ (document.getElementById('riskBox'));
        var r = d && selRisk ? d.risks.filter(/** @param {any} x */ function (x) { return x.riskId === selRisk; })[0] : null;
        box.hidden = !r;
        if (!r) { return; }
        /** @type {HTMLElement} */ (document.getElementById('riskBoxTitle')).textContent = failOf(r.fmId).failText + ' ← ' + failOf(r.fcId).failText;
        ['prevCtrl', 'detCtrl'].forEach(function (n) { fld(riskForm, n).value = r[n] || ''; });
        ['occ', 'det'].forEach(function (n) { fld(riskForm, n).value = r[n] == null ? '' : String(r[n]); });
        (fld(riskForm, 'specialCls')).value = r.specialCls || '';
        riskForm.querySelectorAll('input, select').forEach(function (el) { /** @type {HTMLInputElement} */ (el).disabled = !d.editable; });
        hb.showFieldErrors(riskForm, []);
        var tbody = /** @type {HTMLTableSectionElement} */ (/** @type {HTMLTableElement} */ (document.getElementById('actionTable')).tBodies[0]);
        tbody.textContent = '';
        var acts = d.actions.filter(/** @param {any} a */ function (a) { return a.riskId === r.riskId; });
        acts.forEach(/** @param {any} a */ function (a) {
            var tr = hb.el('tr', 'hb-row-pick' + (a.status === 'CANCELLED' ? ' hb-tree-dim' : ''));
            tr.setAttribute('aria-selected', a.actionId === selAction ? 'true' : 'false');
            [actionKindName(a.actionKind), a.actionText, a.ownerNm || '', a.dueDate || ''].forEach(function (v) { tr.appendChild(hb.el('td', null, v)); });
            var st = hb.el('td');
            /** @type {Record<string, HB.StatusTone>} */
            var tone = {OPEN: 'info', DONE: 'success', CANCELLED: 'neutral'};
            st.appendChild(hb.status(tone[a.status] || 'neutral', actionStatusNames[a.status] || a.status));
            if (a.doneOn) { st.appendChild(hb.el('span', 'hb-muted', ' ' + a.doneOn)); }
            tr.appendChild(st);
            tr.appendChild(hb.el('td', null, (a.newOcc || '-') + ' · ' + (a.newDet || '-')));
            var ap = hb.el('td');
            ap.appendChild(apBadge(a.newAp));
            tr.appendChild(ap);
            tr.addEventListener('click', function () { selAction = a.actionId; showAction(a); renderActionSel(); });
            tbody.appendChild(tr);
        });
        showAction(selAction ? acts.filter(/** @param {any} a */ function (a) { return a.actionId === selAction; })[0] || null : null);
    }

    function renderActionSel() {
        var tbody = /** @type {HTMLTableElement} */ (document.getElementById('actionTable')).tBodies[0];
        var acts = d.actions.filter(/** @param {any} a */ function (a) { return a.riskId === selRisk; });
        Array.prototype.forEach.call(tbody.rows, function (/** @type {HTMLTableRowElement} */ tr, /** @type {number} */ i) {
            tr.setAttribute('aria-selected', acts[i] && acts[i].actionId === selAction ? 'true' : 'false');
        });
    }

    /** 조치 칸 — 작성 중 = 모두, 확정본 = 진행 칸만(상태·완료일·증빙·조치 뒤 O·D) @param {any} a */
    function showAction(a) {
        hb.showFieldErrors(actionForm, []);
        (fld(actionForm, 'actionKind')).value = a ? a.actionKind : 'PREVENT';
        fld(actionForm, 'actionText').value = a ? a.actionText : '';
        (fld(actionForm, 'ownerLoginId')).value = a && a.ownerLoginId ? a.ownerLoginId : '';
        fld(actionForm, 'dueDate').value = a && a.dueDate ? a.dueDate : '';
        (fld(actionForm, 'status')).value = a ? a.status : 'OPEN';
        fld(actionForm, 'doneOn').value = a && a.doneOn ? a.doneOn : '';
        fld(actionForm, 'evidence').value = a && a.evidence ? a.evidence : '';
        fld(actionForm, 'newOcc').value = a && a.newOcc ? String(a.newOcc) : '';
        fld(actionForm, 'newDet').value = a && a.newDet ? String(a.newDet) : '';
        var content = ['actionKind', 'actionText', 'ownerLoginId', 'dueDate'];
        actionForm.querySelectorAll('[data-field]').forEach(function (el) {
            var n = el.getAttribute('data-field') || '';
            /** @type {HTMLInputElement} */ (el).disabled = !d.actionEditable || (!d.editable && (content.indexOf(n) >= 0 || !a));
        });
        actionForm.hidden = !d.actionEditable && !a;
        show('actionTools', d.actionEditable);
        show('newActionBtn', d.editable);
        show('deleteActionBtn', d.editable && !!a);
        show('saveActionBtn', d.actionEditable && (d.editable || !!a));
        renderActionIssue(a);
    }

    // ------------------------------------------------------------------ 조치 → PMS 이슈 (A3-5)

    /** 조치 id → 연결 이슈(PMS API 로 읽음 — APQP 서버는 PMS 를 모른다) @type {Record<string, any>} */
    var actionIssues = {};

    function loadActionIssues() {
        if (!d || !d.issueView || !d.actions.length) { return; }
        var mine = d;
        var ids = d.actions.map(/** @param {any} a */ function (a) { return a.actionId; }).join(',');
        hb.api('/api/pms/issues/by-apqp-actions?ids=' + ids).then(function (res) {
            if (d !== mine) { return; }
            actionIssues = {};
            res.data.forEach(/** @param {any} x */ function (x) { actionIssues[String(x.actionId)] = x; });
            var a = selAction ? d.actions.filter(/** @param {any} x */ function (x) { return x.actionId === selAction; })[0] : null;
            renderActionIssue(a || null);
        }, function () { /* 표시만 — 못 읽으면 단추·연결 표시 없이 */ });
    }

    /** @param {any} a */
    function renderActionIssue(a) {
        var linked = a ? actionIssues[String(a.actionId)] : null;
        var can = !!a && d.issueSave && a.status === 'OPEN' && !linked && !!d.fmea.projId;
        show('issueTools', !!a && d.issueView && (!!linked || can || (d.issueSave && a.status === 'OPEN' && !d.fmea.projId)));
        show('toIssueBtn', can);
        var info = /** @type {HTMLElement} */ (document.getElementById('actionIssue'));
        info.textContent = linked ? hb.t('js.apqp.issueLinked', linked.issueNo, issueStatusName(linked.status), linked.projNo)
            : (a && d.issueSave && a.status === 'OPEN' && !d.fmea.projId ? hb.t('js.apqp.issueNeedProject') : '');
    }

    /** 문구 — 키는 글자 그대로(I18nKeyGuardTest) @param {string} s */
    function issueStatusName(s) {
        /** @type {Record<string, string>} */
        var m = {OPEN: hb.t('js.apqp.issue.OPEN'), IN_PROGRESS: hb.t('js.apqp.issue.IN_PROGRESS'), RESOLVED: hb.t('js.apqp.issue.RESOLVED'),
            CLOSED: hb.t('js.apqp.issue.CLOSED')};
        return m[s] || s;
    }

    var toIssueBtn = /** @type {HTMLButtonElement | null} */ (document.getElementById('toIssueBtn'));
    if (toIssueBtn) {
        var tib = toIssueBtn;
        tib.addEventListener('click', function () {
            var a = d.actions.filter(/** @param {any} x */ function (x) { return x.actionId === selAction; })[0];
            if (!a) { return; }
            ask(hb.t('js.apqp.dlg.toIssue'), hb.t('js.apqp.confirmToIssue', a.actionText, d.fmea.projNo || ''), hb.t('js.apqp.dlg.toIssueOk')).then(function (ok) {
                if (!ok) { return; }
                hb.busy(tib, function () { return hb.api('/api/pms/issues/from-apqp/' + a.actionId, {method: 'POST', body: {}}); })
                    .then(function (res) { if (res) { notify(hb.t('js.apqp.issueCreated')); loadActionIssues(); } }, fail());
            });
        });
    }

    // ------------------------------------------------------------------ 보내기

    /** 한 줄 보내고 상세 다시 읽기 @param {string} url @param {'POST' | 'PUT' | 'DELETE'} method @param {unknown} body @param {HTMLFormElement | null} form */
    function send(url, method, body, form) {
        if (form) { hb.showFieldErrors(form, []); }
        return hb.api(url, {method: method, body: body}).then(function (res) {
            hb.dirty.clear();
            notify(hb.t('js.common.saved'));
            return reload().then(function () { return res; });
        }, fail(form));
    }

    /** @type {HTMLElement} */ (document.getElementById('backBtn')).addEventListener('click', showList);

    var newBtn = /** @type {HTMLButtonElement | null} */ (document.getElementById('newBtn'));
    if (newBtn) {
        newBtn.addEventListener('click', function () {
            d = null;
            selNode = null;
            selRisk = null;
            render();
            var rs = document.getElementById(DF ? 'bomSel' : 'routingSel');
            if (rs) { rs.textContent = ''; }
            if (DF) { fillDocRevs([], null); }
            listView.hidden = true;
            detailView.hidden = false;
            fld(headForm, 'itemCd').focus();
        });
    }

    var saveHead = /** @type {HTMLButtonElement | null} */ (document.getElementById('saveHeadBtn'));
    if (saveHead) {
        var shb = saveHead;
        shb.addEventListener('click', function () {
            var editing = !!d;
            hb.showFieldErrors(headForm, []);
            hb.busy(shb, function () {
                return editing ? hb.api(API + '/' + d.fmea.fmeaId, {method: 'PUT', body: headBody()}) : hb.api(API, {method: 'POST', body: headBody()});
            }).then(function (res) {
                if (!res) { return; }
                hb.dirty.clear();
                return (editing ? reload() : openFmea(res.data)).then(function () { notify(hb.t('js.common.saved')); });
            }, fail(headForm));
        });
    }

    var releaseBtn = /** @type {HTMLButtonElement | null} */ (document.getElementById('releaseBtn'));
    if (releaseBtn) {
        var rb = releaseBtn;
        rb.addEventListener('click', function () {
            ask(hb.t('js.apqp.dlg.release'), hb.t('js.apqp.confirmRelease', d.fmea.fmeaNo + ' rev ' + d.fmea.rev), hb.t('js.apqp.dlg.releaseOk')).then(function (ok) {
                if (!ok) { return; }
                hb.busy(rb, function () { return hb.api(API + '/' + d.fmea.fmeaId + '/release', {method: 'POST', body: {rowVer: d.fmea.rowVer}}); })
                    .then(function (res) { if (res) { return reload().then(function () { notify(hb.t('js.apqp.released')); }); } }, function (err) {
                        // 미달 행 사유: rowKey(FAIL:id·RISK:id) → 문구에 고장 글자를 붙인다
                        var lines = (err && err.errors || []).slice(0, 5).map(/** @param {any} e */ function (e) {
                            var k = String(e.rowKey || '').split(':');
                            var x = k[0] === 'FAIL' ? failOf(Number(k[1])) : (k[0] === 'RISK' ? d.risks.filter(/** @param {any} r */ function (r) {
                                return r.riskId === Number(k[1]); })[0] : null);
                            var label = x ? (x.failText || failOf(x.fmId).failText + ' ← ' + failOf(x.fcId).failText) : '';
                            return (label ? label + ': ' : '') + e.message;
                        });
                        notify((err && err.message || '') + (lines.length ? ' ' + lines.join(' / ') : ''), true);
                    });
            });
        });
    }

    // QMS 관리문서로 올리기(A3-3) — 서버가 권한·상태를 판정(canPublish), 화면은 단추만
    function loadDoc() {
        var mine = d;
        show('docBtn', false);
        show('docInfo', false);
        if (!d) { return; }
        hb.api(API + '/' + d.fmea.fmeaId + '/qms-doc').then(function (res) {
            if (d !== mine) { return; }
            var st = res.data;
            show('docBtn', st.canPublish);
            var info = /** @type {HTMLElement} */ (document.getElementById('docInfo'));
            info.textContent = st.linked ? hb.t('js.apqp.doc.linked', st.linked.docNo, st.linked.rev || '', docStatusName(st.linked.status)) : '';
            show('docInfo', !!st.linked);
        }, function () { /* 표시만 */ });
    }

    /** 문구 — 키는 글자 그대로(I18nKeyGuardTest) @param {string | null} s */
    function docStatusName(s) {
        /** @type {Record<string, string>} */
        var m = {DRAFT: hb.t('js.apqp.doc.DRAFT'), IN_APPROVAL: hb.t('js.apqp.doc.IN_APPROVAL'), RELEASED: hb.t('js.apqp.doc.RELEASED'),
            OBSOLETE: hb.t('js.apqp.doc.OBSOLETE')};
        return s ? (m[s] || s) : '';
    }

    var docBtn = /** @type {HTMLButtonElement | null} */ (document.getElementById('docBtn'));
    if (docBtn) {
        var dcb = docBtn;
        dcb.addEventListener('click', function () {
            ask(hb.t('js.apqp.doc.dlg'), hb.t('js.apqp.doc.confirm'), hb.t('js.apqp.doc.ok')).then(function (ok) {
                if (!ok) { return; }
                hb.busy(dcb, function () { return hb.api(API + '/' + d.fmea.fmeaId + '/qms-doc', {method: 'POST', body: {}}); })
                    .then(function (res) { if (res) { notify(hb.t('js.apqp.doc.done', res.data.docNo)); loadDoc(); } }, fail());
            });
        });
    }

    // 엑셀 양식(A4) — 이 리비전 그대로, 확정본이 아니면 파일에 표시(서버)
    var excelBtn = /** @type {HTMLButtonElement | null} */ (document.getElementById('excelBtn'));
    if (excelBtn) {
        var xb = excelBtn;
        xb.addEventListener('click', function () {
            hb.busy(xb, function () { return hb.download(API + '/' + d.fmea.fmeaId + '/excel'); }).then(null, fail());
        });
    }

    // 공정 흐름도(A5-1) — 누를 때마다 서버가 원본(라우팅·위험행·관리계획서)에서 다시 만든다. 다시 누르면 접는다
    var flowBtn = /** @type {HTMLButtonElement} */ (document.getElementById('flowBtn'));
    var flowBox = /** @type {HTMLElement} */ (document.getElementById('flowBox'));
    flowBtn.addEventListener('click', function () {
        if (!flowBox.hidden) {
            flowBox.hidden = true;
            flowBtn.setAttribute('aria-expanded', 'false');
            return;
        }
        hb.busy(flowBtn, function () { return hb.api(API + '/' + d.fmea.fmeaId + '/flow'); }).then(function (res) {
            if (!res) { return; }
            renderFlow(res.data);
            flowBox.hidden = false;
            flowBtn.setAttribute('aria-expanded', 'true');
        }, fail());
    });

    /** 기호 글자 — 키는 글자 그대로(I18nKeyGuardTest) @param {string} s */
    function symbolName(s) {
        /** @type {Record<string, string>} */
        var m = {PROCESS: hb.t('js.apqp.flow.sym.PROCESS'), INSPECT: hb.t('js.apqp.flow.sym.INSPECT'),
            OUTSOURCE: hb.t('js.apqp.flow.sym.OUTSOURCE'), PACK: hb.t('js.apqp.flow.sym.PACK')};
        return m[s] || s;
    }

    /** 세로 흐름 — 단계 한 줄: 기호 · op 공정 · CT / 특성(특별특성 표시) / 관리계획서 행·위험행·AP 높음 @param {any} v */
    function renderFlow(v) {
        var list = /** @type {HTMLElement} */ (document.getElementById('flowList'));
        list.textContent = '';
        var cp = v.cpId ? v.cpNo + ' rev ' + v.cpRev : (v.cpHidden ? hb.t('js.apqp.flow.cpHidden') : hb.t('js.apqp.flow.cpNone'));
        /** @type {HTMLElement} */ (document.getElementById('flowInfo')).textContent = hb.t('js.apqp.flow.info', v.routingText || '-', cp)
            + (v.unplaced ? ' · ' + hb.t('js.apqp.flow.unplaced', v.unplaced) : '');
        if (!v.steps.length) { list.appendChild(hb.el('li', 'hb-muted', hb.t('js.apqp.flow.empty'))); }
        v.steps.forEach(function (/** @type {any} */ s) {
            var li = hb.el('li', 'apqp-flow-step');
            li.appendChild(hb.el('span', 'apqp-sym apqp-sym-' + s.symbol, symbolName(s.symbol)));
            var body = hb.el('div', 'apqp-flow-body');
            body.appendChild(hb.el('strong', null, s.opSeq + ' ' + s.procNm + (s.stdCtSec !== null ? ' · CT ' + hb.fmt.num(s.stdCtSec, 1, 0) + 's' : '')));
            var chars = hb.el('div', 'apqp-flow-chars');
            s.chars.forEach(function (/** @type {any} */ c) {
                var tag = hb.el('span', 'apqp-flow-char' + (c.special ? ' apqp-flow-special' : ''), c.charNo + ' ' + c.charNm
                    + (c.special ? ' [' + c.specialCls + ']' : ''));
                tag.title = (c.inFmea ? 'PFMEA' : '') + (c.inFmea && c.inCp ? ' · ' : '') + (c.inCp ? 'CP' : '');
                chars.appendChild(tag);
            });
            if (!s.chars.length) { chars.appendChild(hb.el('span', 'hb-muted', hb.t('js.apqp.flow.noChars'))); }
            body.appendChild(chars);
            body.appendChild(hb.el('span', s.highCnt ? 'apqp-flow-count apqp-flow-high' : 'apqp-flow-count',
                hb.t('js.apqp.flow.counts', v.cpId ? s.cpLineCnt : '-', s.riskCnt, s.highCnt)));
            li.appendChild(body);
            list.appendChild(li);
        });
    }

    var reviseBtn = /** @type {HTMLButtonElement | null} */ (document.getElementById('reviseBtn'));
    if (reviseBtn) {
        var vb = reviseBtn;
        vb.addEventListener('click', function () {
            ask(hb.t('js.apqp.dlg.revise'), hb.t('js.apqp.confirmRevise', d.fmea.fmeaNo, d.fmea.rev + 1), hb.t('js.apqp.dlg.reviseOk')).then(function (ok) {
                if (!ok) { return; }
                hb.busy(vb, function () { return hb.api(API + '/' + d.fmea.fmeaId + '/revise', {method: 'POST', body: {}}); })
                    .then(function (res) { if (res) { return openFmea(res.data).then(function () { notify(hb.t('js.apqp.revised')); }); } }, fail());
            });
        });
    }

    var deleteBtn = /** @type {HTMLButtonElement | null} */ (document.getElementById('deleteBtn'));
    if (deleteBtn) {
        var db = deleteBtn;
        db.addEventListener('click', function () {
            ask(hb.t('js.apqp.dlg.deleteFmea'), hb.t('js.apqp.confirmDeleteFmea', d.fmea.fmeaNo + ' rev ' + d.fmea.rev), hb.t('js.apqp.dlg.delete'), true)
                .then(function (ok) {
                    if (!ok) { return; }
                    hb.busy(db, function () { return hb.api(API + '/' + d.fmea.fmeaId + '?rowVer=' + d.fmea.rowVer, {method: 'DELETE'}); })
                        .then(function (res) {
                            if (!res) { return; }
                            hb.dirty.clear();
                            detailView.hidden = true;
                            listView.hidden = false;
                            d = null;
                            return grid.reload(true).then(function () { notify(hb.t('js.apqp.deleted')); });
                        }, fail());
                });
        });
    }

    /** @param {string} kind */
    function addNode(kind) {
        var cur = selNode ? nodeOf(selNode) : null;
        var parent = kind === MID ? childrenOf(null)[0] : (cur && cur.nodeKind === LEAF ? nodeOf(cur.parentId) : cur);
        if (!parent) { return; }
        hb.dialog.prompt({title: addNodeTitle(kind), label: hb.t('js.apqp.col.nodeNm'), ok: hb.t('js.apqp.dlg.addOk'),
            required: true, maxLength: 200}).then(function (nm) {
            if (nm === null) { return; }
            hb.api(API + '/' + d.fmea.fmeaId + '/nodes', {method: 'POST', body: {parentId: parent.nodeId, nodeKind: kind, nodeNm: nm, m4: 'MAN'}})
                .then(function (res) {
                    selNode = res.data;
                    return reload().then(function () { notify(hb.t('js.common.saved')); });
                }, fail());
        });
    }
    /** @type {HTMLElement} */ (document.getElementById('addStepBtn')).addEventListener('click', function () { addNode(MID); });
    /** @type {HTMLElement} */ (document.getElementById('addElemBtn')).addEventListener('click', function () { addNode(LEAF); });

    detailView.querySelectorAll('[data-move]').forEach(function (b) {
        b.addEventListener('click', function () {
            var n = selNode ? nodeOf(selNode) : null;
            if (!n) { return; }
            send(ROW + 'nodes/' + n.nodeId + '/move', 'POST', {op: b.getAttribute('data-move'), rowVer: n.rowVer}, null);
        });
    });

    /** @type {HTMLElement} */ (document.getElementById('deleteNodeBtn')).addEventListener('click', function () {
        var n = selNode ? nodeOf(selNode) : null;
        if (!n) { return; }
        ask(hb.t('js.apqp.dlg.deleteNode'), hb.t('js.apqp.confirmDeleteNode', n.nodeNm), hb.t('js.apqp.dlg.delete'), true).then(function (ok) {
            if (!ok) { return; }
            selNode = n.parentId;
            send(ROW + 'nodes/' + n.nodeId + '?rowVer=' + n.rowVer, 'DELETE', null, null);
        });
    });

    /** @type {HTMLElement} */ (document.getElementById('saveNodeBtn')).addEventListener('click', function () {
        var n = selNode ? nodeOf(selNode) : null;
        if (!n) { return; }
        var step = fld(nodeForm, 'stepId').value;
        send(ROW + 'nodes/' + n.nodeId, 'PUT', {nodeNm: fld(nodeForm, 'nodeNm').value, stepId: step ? Number(step) : null,
            m4: fld(nodeForm, 'm4').value, rowVer: n.rowVer}, nodeForm);
    });

    /** @type {HTMLElement} */ (document.getElementById('saveRiskBtn')).addEventListener('click', function () {
        var r = d.risks.filter(/** @param {any} x */ function (x) { return x.riskId === selRisk; })[0];
        if (!r) { return; }
        send(ROW + 'risks/' + r.riskId, 'PUT', {prevCtrl: fld(riskForm, 'prevCtrl').value, detCtrl: fld(riskForm, 'detCtrl').value,
            occ: num(fld(riskForm, 'occ').value), det: num(fld(riskForm, 'det').value), specialCls: fld(riskForm, 'specialCls').value || null,
            rowVer: r.rowVer}, riskForm);
    });

    /** @type {HTMLElement} */ (document.getElementById('deleteRiskBtn')).addEventListener('click', function () {
        var r = d.risks.filter(/** @param {any} x */ function (x) { return x.riskId === selRisk; })[0];
        if (!r) { return; }
        ask(hb.t('js.apqp.dlg.deleteRisk'), hb.t('js.apqp.confirmDeleteRisk', failOf(r.fcId).failText), hb.t('js.apqp.dlg.delete'), true).then(function (ok) {
            if (!ok) { return; }
            selRisk = null;
            send(ROW + 'risks/' + r.riskId + '?rowVer=' + r.rowVer, 'DELETE', null, null);
        });
    });

    /** @type {HTMLElement} */ (document.getElementById('newActionBtn')).addEventListener('click', function () {
        selAction = null;
        showAction(null);
        renderActionSel();
        fld(actionForm, 'actionText').focus();
    });

    /** @type {HTMLElement} */ (document.getElementById('saveActionBtn')).addEventListener('click', function () {
        var a = selAction ? d.actions.filter(/** @param {any} x */ function (x) { return x.actionId === selAction; })[0] : null;
        /** @type {Record<string, unknown>} */
        var body = {riskId: selRisk, rowVer: a ? a.rowVer : null};
        ['actionKind', 'actionText', 'ownerLoginId', 'dueDate', 'status', 'doneOn', 'evidence'].forEach(function (n) { body[n] = fld(actionForm, n).value; });
        body.newOcc = num(fld(actionForm, 'newOcc').value);
        body.newDet = num(fld(actionForm, 'newDet').value);
        send(a ? ROW + 'actions/' + a.actionId : API + '/' + d.fmea.fmeaId + '/actions', a ? 'PUT' : 'POST', body, actionForm)
            .then(function (res) { if (res && !a) { selAction = res.data; renderRiskBox(); } });
    });

    /** @type {HTMLElement} */ (document.getElementById('deleteActionBtn')).addEventListener('click', function () {
        var a = selAction ? d.actions.filter(/** @param {any} x */ function (x) { return x.actionId === selAction; })[0] : null;
        if (!a) { return; }
        ask(hb.t('js.apqp.dlg.deleteAction'), hb.t('js.apqp.confirmDeleteAction', a.actionText), hb.t('js.apqp.dlg.delete'), true).then(function (ok) {
            if (!ok) { return; }
            selAction = null;
            send(ROW + 'actions/' + a.actionId + '?rowVer=' + a.rowVer, 'DELETE', null, null);
        });
    });

    [headForm, nodeForm, riskForm, actionForm].forEach(function (f) { f.addEventListener('input', function () { hb.dirty.mark(); }); });


    // ------------------------------------------------------------------ 결재 상신(A3-7) · 결재 중 · 라우팅 재검토 안내

    /** 상신 전 결재선(화면에만) @type {{userId: number, nm: string, lineType: string}[]} */
    var aprLines = [];

    /** 문구 — 키는 글자 그대로(I18nKeyGuardTest) @param {string} t */
    function aprTypeName(t) {
        /** @type {Record<string, string>} */
        var m = {APPROVE: hb.t('js.apqp.apr.APPROVE'), AGREE: hb.t('js.apqp.apr.AGREE'), REF: hb.t('js.apqp.apr.REF')};
        return m[t] || t;
    }

    /** ECO 종류·4M 이름 — 처음 쓸 때 한 번 읽는다 @type {Promise<Record<string, string>[]> | null} */
    var changeNamesP = null;

    /**
     * 재검토 표시 ②③(docs/41 11.6) — 확정 뒤 이 품목에 적용된 ECO·승인된 4M 변경. 표시만, 서버가 고른 목록 그대로.
     * M4 = 4M 영향 특성과 이 문서 특성의 겹침(null = 세부 없음 → 표시 안 함), E-3(11.10) = 이 리비전을 배포한 ECO 는 따로 한 줄
     */
    function renderChanges() {
        var box = /** @type {HTMLElement} */ (document.getElementById('changeNotice'));
        var ul = /** @type {HTMLElement} */ (document.getElementById('changeList'));
        var ownBox = /** @type {HTMLElement} */ (document.getElementById('ownEcoNotice'));
        var c = d && d.changes ? d.changes : null;
        /** @type {any[]} */
        var rows = c ? c.rows : [];
        /** @type {any[]} */
        var own = c && c.own ? c.own : [];
        ownBox.hidden = own.length === 0;
        ownBox.textContent = own.length ? hb.t('js.apqp.ownEco', own.map(function (/** @type {any} */ r) { return r.ecoNo; }).join(', ')) : '';
        box.hidden = rows.length === 0;
        ul.textContent = '';
        if (!rows.length) {
            return;
        }
        /** @type {HTMLElement} */ (document.getElementById('changeHead')).textContent =
            hb.t('js.apqp.changeStale', rows.length) + (c.truncated ? ' ' + hb.t('js.apqp.changeMore') : '');
        if (!changeNamesP) {
            changeNamesP = Promise.all([hb.codes('PLM_CHANGE_KIND'), hb.codes('QMS_4M_TYPE')]);
        }
        changeNamesP.then(function (n) {
            ul.textContent = '';
            rows.forEach(function (r) {
                var parts = [r.ecoNo, r.title, n[0][r.changeKind] || r.changeKind,
                    r.releasedOn ? hb.t('js.apqp.changeReleased', r.releasedOn) : hb.t('js.apqp.changeApproved', r.approvedOn || '')];
                if (r.m4Types && r.m4Types.length) {
                    parts.push(hb.t('js.apqp.change4m', r.m4Types.map(function (/** @type {string} */ m) { return n[1][m] || m; }).join('·')));
                }
                if (r.overlap) {
                    // 겹침 없음이 "검토 안 해도 됨" 으로 읽히지 않게 — 다른 영향(전후 내용 등)은 ECO·4M 상세에서
                    parts.push(r.overlap.length ? hb.t('js.apqp.overlap', r.overlap.map(function (/** @type {any} */ x) {
                        var nm = x.charNm ? x.charNo + ' ' + x.charNm : x.charNo;
                        return x.special ? hb.t('js.apqp.overlapSpecial', nm) : nm;
                    }).join(', ')) : hb.t('js.apqp.overlapNone'));
                }
                ul.appendChild(hb.el('li', null, parts.join(' · ')));
            });
        });
    }

    // ------------------------------------------------------------------ 라우팅 바꾸기 (11.9)

    /** 미리보기 결과(적용 때 rowVer 만 씀 — 짝은 서버가 다시 계산) @type {any} */
    var relinkPlan = null;

    function renderRelink() {
        var on = CAN_SAVE && !!d && !!d.fmea && d.editable;
        show('relinkBox', on);
        if (!on) { relinkPlan = null; return; }
        if (relinkPlan && relinkPlan.fmeaId !== d.fmea.fmeaId) { relinkPlan = null; }
        var mine = d;
        // DFMEA = BOM 바꾸기(A5-2b D6): 머리 품목의 확정 BOM 중 지금 것 말고
        var listUrl = DF ? PICK + 'items/' + d.fmea.itemId + '/boms' : '/api/apqp/items/' + d.fmea.itemId + '/routings';
        hb.latest('relinkRoutings', hb.api(listUrl)).then(function (res) {
            if (d !== mine) { return; }
            var sel = /** @type {HTMLSelectElement} */ (document.getElementById('relinkSel'));
            var keep = sel.value;
            sel.textContent = '';
            if (DF) {
                res.data.filter(/** @param {any} b */ function (b) { return b.bomId !== d.fmea.bomId; })
                    .forEach(/** @param {any} b */ function (b) { option(sel, String(b.bomId), b.altNo + ' · ' + b.rev + ' (' + b.validFrom + ')'); });
            } else {
                res.data.filter(/** @param {any} r */ function (r) { return r.status === 'RELEASED' && r.routingId !== d.fmea.routingId; })
                    .forEach(/** @param {any} r */ function (r) { option(sel, String(r.routingId), r.plantCd + ' · ' + r.altNo + ' · ' + r.rev + ' (' + r.validFrom + ')'); });
            }
            // 고른 것이 아직 목록에 있을 때만 되살린다(방금 바꾼 라우팅은 목록에서 빠져 빈칸이 된다)
            if (keep && Array.prototype.some.call(sel.options, function (/** @type {HTMLOptionElement} */ o) { return o.value === keep; })) { sel.value = keep; }
            /** @type {HTMLButtonElement} */ (document.getElementById('relinkPreviewBtn')).disabled = sel.options.length === 0;
        }, function () { /* 고를 것 없음 */ });
        renderRelinkPlan();
    }

    function renderRelinkPlan() {
        var ul = /** @type {HTMLElement} */ (document.getElementById('relinkList'));
        ul.textContent = '';
        show('relinkApplyBtn', !!relinkPlan);
        if (!relinkPlan) { return; }
        if (DF) {
            ul.appendChild(hb.el('li', null, hb.t('js.apqp.bomRelink.summary', relinkPlan.bomText, relinkPlan.matched, relinkPlan.unmatched,
                relinkPlan.added.length)));
            relinkPlan.nodes.forEach(/** @param {any} n */ function (n) {
                ul.appendChild(hb.el('li', null, n.matched ? hb.t('js.apqp.bomRelink.matched', n.nodeNm) : hb.t('js.apqp.bomRelink.unmatched', n.nodeNm)));
            });
            relinkPlan.added.forEach(/** @param {any} c */ function (c) {
                ul.appendChild(hb.el('li', null, hb.t('js.apqp.bomRelink.added', c.itemCd + ' ' + c.itemNm)));
            });
            return;
        }
        ul.appendChild(hb.el('li', null, hb.t('js.apqp.relink.summary', relinkPlan.routingText, relinkPlan.matched, relinkPlan.unmatched)));
        relinkPlan.steps.forEach(/** @param {any} st */ function (st) {
            ul.appendChild(hb.el('li', null, st.matched ? hb.t('js.apqp.relink.matched', st.opSeq + ' ' + (st.procNm || st.nodeNm))
                : hb.t('js.apqp.relink.unmatched', st.opSeq + ' ' + (st.procNm || st.nodeNm), st.textNm)));
        });
    }

    var relinkPreviewBtn = /** @type {HTMLButtonElement} */ (document.getElementById('relinkPreviewBtn'));
    relinkPreviewBtn.addEventListener('click', function () {
        var sel = /** @type {HTMLSelectElement} */ (document.getElementById('relinkSel'));
        if (!sel.value) { return; }
        hb.busy(relinkPreviewBtn, function () {
            return hb.api(API + '/' + d.fmea.fmeaId + (DF ? '/bom-relink?bomId=' : '/routing-relink?routingId=') + encodeURIComponent(sel.value));
        })
            .then(function (res) { if (res) { relinkPlan = res.data; relinkPlan.fmeaId = d.fmea.fmeaId; renderRelinkPlan(); } }, fail());
    });

    var relinkApplyBtn = /** @type {HTMLButtonElement} */ (document.getElementById('relinkApplyBtn'));
    relinkApplyBtn.addEventListener('click', function () {
        if (!relinkPlan) { return; }
        var plan = relinkPlan;
        var confirm = DF ? ask(hb.t('js.apqp.bomRelink.dlg'), hb.t('js.apqp.bomRelink.confirm', plan.bomText, plan.unmatched, plan.added.length),
            hb.t('js.apqp.bomRelink.ok'))
            : ask(hb.t('js.apqp.relink.dlg'), hb.t('js.apqp.relink.confirm', plan.routingText, plan.unmatched), hb.t('js.apqp.relink.ok'));
        confirm.then(function (ok) {
            if (!ok) { return; }
            hb.busy(relinkApplyBtn, function () {
                return DF ? hb.api(API + '/' + d.fmea.fmeaId + '/bom', {method: 'POST', body: {bomId: plan.bomId, rowVer: plan.rowVer}})
                    : hb.api(API + '/' + d.fmea.fmeaId + '/routing', {method: 'POST', body: {routingId: plan.routingId, rowVer: plan.rowVer}});
            }).then(function (res) { if (res) { relinkPlan = null; notify(DF ? hb.t('js.apqp.bomRelink.done') : hb.t('js.apqp.relink.done')); return reload(); } }, fail());
        });
    });

    function renderApproval() {
        var h = d ? d.fmea : null;
        var on = !!d && d.approvalOn;
        show('aprBox', CAN_SAVE && !!h && d.editable && on);
        var n = /** @type {HTMLElement} */ (document.getElementById('aprNotice'));
        n.hidden = !(h && h.status === 'IN_APPROVAL');
        n.textContent = h && h.status === 'IN_APPROVAL' ? hb.t('js.apqp.apr.inApproval') : '';
        var rn = /** @type {HTMLElement} */ (document.getElementById('routingNotice'));
        /** @type {string[]} */
        var stale = [];
        if (h && h.routingStale) { stale.push(hb.t('js.apqp.routingStale', h.routingText || '')); }
        if (h && h.bomStale) { stale.push(hb.t('js.apqp.bomStale', h.bomText || '')); }
        if (h && h.drawingStale) { stale.push(hb.t('js.apqp.drawingStale', h.docRevText || '')); }
        rn.hidden = !stale.length;
        rn.textContent = stale.join(' ');
        renderChanges();
        renderRelink();
        var ol = /** @type {HTMLElement} */ (document.getElementById('aprLines'));
        ol.textContent = '';
        aprLines.forEach(function (l, i) {
            var li = hb.el('li', 'apqp-line', l.nm + ' · ' + aprTypeName(l.lineType) + ' ');
            var x = /** @type {HTMLButtonElement} */ (hb.el('button', 'hb-btn-ghost apqp-small', hb.t('js.apqp.apr.remove')));
            x.type = 'button';
            x.addEventListener('click', function () { aprLines.splice(i, 1); renderApproval(); });
            li.appendChild(x);
            ol.appendChild(li);
        });
        if (!aprLines.length) { ol.appendChild(hb.el('li', 'hb-muted', hb.t('js.apqp.apr.empty'))); }
        /** @type {HTMLButtonElement} */ (document.getElementById('aprSubmitBtn')).disabled = !aprLines.length;
    }

    /** @type {HTMLElement} */ (document.getElementById('aprAddBtn')).addEventListener('click', function () {
        var u = /** @type {HTMLSelectElement} */ (document.getElementById('aprUser'));
        var t = /** @type {HTMLSelectElement} */ (document.getElementById('aprType'));
        if (!u.value || aprLines.some(function (l) { return String(l.userId) === u.value; })) { return; }
        aprLines.push({userId: Number(u.value), nm: u.options[u.selectedIndex].text, lineType: t.value});
        renderApproval();
    });

    var aprSubmit = /** @type {HTMLButtonElement} */ (document.getElementById('aprSubmitBtn'));
    aprSubmit.addEventListener('click', function () {
        if (!d || !aprLines.length) { return; }
        if (hb.dirty.isDirty()) { notify(hb.t('js.apqp.apr.saveFirst'), true); return; }
        var h = d.fmea;
        ask(hb.t('js.apqp.apr.dlg'), hb.t('js.apqp.apr.confirm', h.fmeaNo + ' rev ' + h.rev), hb.t('js.apqp.apr.dlgOk')).then(function (ok) {
            if (!ok) { return; }
            hb.busy(aprSubmit, function () {
                return hb.api(API + '/' + d.fmea.fmeaId + '/submit', {method: 'POST', body: {rowVer: h.rowVer,
                    lines: aprLines.map(function (l) { return {userId: l.userId, lineType: l.lineType}; })}});
            }).then(function (res) {
                if (!res) { return; }
                aprLines = [];
                return reload().then(function () { notify(hb.t('js.apqp.apr.submitted')); });
            }, function (err) {
                var lines = (err && err.errors || []).slice(0, 5).map(/** @param {any} e */ function (e) { return e.message; });
                notify((err && err.message || '') + (lines.length ? ' ' + lines.join(' / ') : ''), true);
            });
        });
    });

    /** 결재선 고르기 — 사용 중 사용자 @param {any[]} users */
    function fillAprUsers(users) {
        var s = /** @type {HTMLSelectElement} */ (document.getElementById('aprUser'));
        users.forEach(/** @param {any} u */ function (u) {
            var o = /** @type {HTMLOptionElement} */ (hb.el('option', null, u.userNm + ' (' + u.loginId + ')'));
            o.value = String(u.userId);
            s.appendChild(o);
        });
    }

    // ------------------------------------------------------------------ 시작

    hb.loadMessages().then(function () {
        hb.page.init();
        sp = hb.searchPanel(/** @type {HTMLFormElement} */ (document.getElementById('search')), {onSearch: function () { return grid.reload(true); }});
        return Promise.all([hb.loadSession(), hb.codes('APQP_M4'), hb.codes('SPECIAL_CHAR'), hb.codes('APQP_ACTION_STATUS'),
            hb.codes('APQP_FMEA_STATUS'), hb.api(PICK + 'defects'), hb.api(PICK + 'projects'), hb.api(PICK + 'users'),
            hb.api(DF ? PICK + 'ap-sets' : '/api/apqp/fmea-ap-sets'), sp.ready]);
    }).then(function (r) {
        m4Names = r[1];
        specialNames = r[2];
        actionStatusNames = r[3];
        statusNames = r[4];
        defects = r[5].data;
        var m4Sel = /** @type {HTMLSelectElement} */ (document.getElementById('m4Sel'));
        Object.keys(m4Names).forEach(function (k) { option(m4Sel, k, m4Names[k]); });
        var specialSel = /** @type {HTMLSelectElement} */ (document.getElementById('specialSel'));
        option(specialSel, '', hb.t('js.apqp.none'));
        Object.keys(specialNames).forEach(function (k) { if (k !== 'NONE') { option(specialSel, k, specialNames[k]); } });
        var stSel = /** @type {HTMLSelectElement} */ (document.getElementById('actionStatusSel'));
        Object.keys(actionStatusNames).forEach(function (k) { option(stSel, k, actionStatusNames[k]); });
        var projSel = /** @type {HTMLSelectElement} */ (document.getElementById('projSel'));
        option(projSel, '', hb.t('js.apqp.noProject'));
        r[6].data.forEach(/** @param {any} p */ function (p) { option(projSel, String(p.projId), p.projNo + ' ' + p.projNm); });
        var owner = /** @type {HTMLSelectElement} */ (document.getElementById('ownerSel'));
        option(owner, '', hb.t('js.apqp.noOwner'));
        r[7].data.forEach(/** @param {any} u */ function (u) { option(owner, u.loginId, u.userNm + ' (' + u.loginId + ')'); });
        fillAprUsers(r[7].data);
        var setSel = /** @type {HTMLSelectElement} */ (document.getElementById('apSetSel'));
        option(setSel, '', hb.t('js.apqp.noApSet'));
        r[8].data.forEach(/** @param {any} s */ function (s) { option(setSel, String(s.setId), s.setCd + ' ' + s.setNm); });
        grid = hb.grid('#fmeaGrid', {
            progCd: PROG, id: 'main', key: 'fmeaId',
            url: API + '/grid', columnsUrl: API + '/columns', exportUrl: API + '/export', actions: ACTIONS, params: params,
            status: {status: {DRAFT: 'info', IN_APPROVAL: 'warning', RELEASED: 'success', OBSOLETE: 'neutral'},
                highCnt: function (v) { return v > 0 ? 'danger' : null; },
                // 라우팅 새 리비전이 확정됨(A3-2 ①) — 표시만
                routingText: function (v, row) { return row.routingStale ? 'warning' : null; },
                // 재검토 — 글자는 LABEL 열(X2 서버 문구), 배지 색만 여기서
                review: function (v) { return v ? 'warning' : null; }},
            rowClass: function (row) { return row.status === 'OBSOLETE' ? 'hb-tree-dim' : null; },
            notify: notify
        });
        // 상세가 목록을 덮는 화면 — 클릭·Enter 만 연다(↑↓ 는 고르기만, docs/32 4.11 G1 과 같은 이유)
        var host = /** @type {HTMLElement} */ (document.getElementById('fmeaGrid'));
        host.addEventListener('click', function (e) {
            if (e.target instanceof Element && e.target.closest('tbody tr')) { openSelected(); }
        });
        host.addEventListener('keydown', function (e) {
            if (e.key === 'Enter' && e.target instanceof Element && e.target.closest('.hbg-scroll')) { openSelected(); }
        });
        return grid.ready.then(function () { return grid.reload(true); });
    }).then(null, fail());
})();
