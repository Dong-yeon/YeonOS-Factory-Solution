// @ts-check
/*
 * 검사 실행 (qms-insp) — P3 QMS 2부 Q6 (docs/29_P3 12.2).
 *   목록 표(상한 500) + 새 검사 줄(LOT·유형·공정·로트 수량) + 상세: 머리 줄 / 행 카드(규격·n·Ac·Re·판정 미리보기, 잰 샘플 칩·무효,
 *   붙여넣기 → 다음 빈 샘플 번호부터, 게이지 고름) / 판정 확정·검사 무효.
 * 판정은 서버(InspJudge). 서버 데이터는 textContent·value 로만.
 */
/**
 * @typedef {{inspId: number, inspNo: string, inspType: string, lotNo: string, itemCd: string, itemNm: string, procCd?: string | null,
 *   planRev: string, lotQty: number, status: string, result?: string | null, occurredAt: string, judgedAt?: string | null,
 *   judgedBy?: string | null, voidReason?: string | null, rowVer: number}} QmsInsp
 * @typedef {{inspLineId: number, lineNo: number, charLabel: string, charNm: string, dataType: string, nominal?: number | null,
 *   lsl?: number | null, usl?: number | null, unitNm?: string | null, gaugeClass: string, sampleSize: number, ac: number, re: number,
 *   result?: string | null, measured: number, nonconforming: number, verdict: string}} QmsInspLine
 * @typedef {{measId: number, inspLineId: number, sampleNo: number, value?: number | null, okYn?: string | null, gaugeCd?: string | null,
 *   recordedBy: string}} QmsMeas
 * @typedef {{insp: QmsInsp, lines: QmsInspLine[], meas: QmsMeas[], verdict: string, openNcrNos: string[],
 *   stockQc?: Object<string, number> | null}} QmsInspDetail
 * @typedef {{assetId: number, assetCd: string, assetNm: string, status: string, blocked: boolean}} QmsInspGauge
 * @typedef {{inspId: number, inspNo: string, inspType: string, lotNo: string, status: string, result?: string | null,
 *   judgedAt?: string | null, judgedBy?: string | null}} QmsOffRow
 * @typedef {{state: string, rows: QmsOffRow[]}} QmsOffSide
 * @typedef {{woId?: number | null, procId?: number | null, firstOff: QmsOffSide, lastOff: QmsOffSide, basis?: string | null, truncated?: boolean}} QmsOffPair
 */
(function () {
    'use strict';

    /** QMS 화면 공용 — 상태 배지·대화상자(qms-ui.js) */
    var qmsUi = /** @type {QmsUi} */ (/** @type {any} */ (window).qmsUi);

    var PROG = document.body.getAttribute('data-prog') || '';
    var ACTIONS = (document.body.getAttribute('data-actions') || '').split(',');
    var CAN_SAVE = ACTIONS.indexOf('SAVE') >= 0;
    var CAN_DELETE = ACTIONS.indexOf('DELETE') >= 0;
    var API = '/api/qms/insps';
    var msg = /** @type {HTMLElement} */ (document.getElementById('msg'));
    var search = /** @type {HTMLFormElement} */ (document.getElementById('search'));
    var newForm = /** @type {HTMLFormElement | null} */ (document.getElementById('newForm'));
    var formMode = /** @type {HTMLElement} */ (document.getElementById('formMode'));
    var headLine = /** @type {HTMLElement} */ (document.getElementById('headLine'));
    /** @type {QmsInspDetail | null} */
    var detail = null;
    /** @type {HB.SearchPanel} */
    var sp;
    /** @type {HB.SelectControl | null} */
    var typeSel = null;
    /** @type {Record<string, string>} */
    var typeNames = {};
    /** @type {Record<string, string>} */
    var statusNames = {};
    /** @type {Record<string, string>} */
    var resultNames = {};
    /** @type {Record<string, string>} */
    var gaugeNames = {};
    /** 계측기 클래스별 게이지 후보(한 번만 읽음) @type {Record<string, Promise<QmsInspGauge[]>>} */
    var gaugeCache = {};

    /** @param {string | null | undefined} text @param {boolean} [error] */
    function notify(text, error) {
        msg.textContent = text || '';
        msg.classList.toggle('hb-msg-error', !!error);
    }

    /** @param {{message: string, errors?: HB.FieldError[]}} err */
    function errText(err) {
        return err.message + (err.errors && err.errors.length
            ? ' ' + err.errors.map(/** @param {HB.FieldError} e */ function (e) { return (e.rowKey ? '#' + (Number(e.rowKey) + 1) + ' ' : '') + e.message; }).join(' ') : '');
    }

    /** @param {string} id @param {boolean} on */
    function show(id, on) {
        var el = document.getElementById(id);
        if (el) { el.hidden = !on; }
    }

    // ---------- 목록 ----------

    /** 주 목록 = hb.grid(UI 단계 3) — 서버 /grid·/columns, 상태 배지·끝난 행 흐림 @type {HB.Grid | null} */
    var grid = null;

    /** 목록 다시 읽기 — 보던 행을 그대로 고른 채(keepId 는 onSelect 를 부르지 않는다) */
    function loadList() {
        return grid ? grid.reload(false, detail ? detail.insp.inspId : undefined) : Promise.resolve();
    }

    function startGrid() {
        grid = hb.grid('#inspGrid', {
            progCd: PROG, id: 'main', key: 'inspId',
            url: API + '/grid', columnsUrl: API + '/columns', exportUrl: API + '/export', actions: ACTIONS,
            params: function () { return sp.values(); },
            filters: {inspType: 'inspType', status: 'status', result: 'result'},
            onFilter: function (param, value) { sp.setValue(param, value); },
            status: {status: qmsUi.TONES.insp, result: qmsUi.TONES.result},
            rowClass: function (row) { return row.status === 'VOID' ? 'hb-tree-dim' : null; },
            onSelect: function (row) { load(/** @type {number} */ (row.inspId)); hb.page.openDetail(); },
            notify: notify
        });
        return grid.ready;
    }

    // ---------- 상세 ----------

    /** @param {number | null | undefined} v */
    function num(v) {
        return v === null || v === undefined ? '' : String(v);
    }

    /** @param {QmsInspLine} l */
    function specText(l) {
        if (l.dataType === 'ATTRIBUTE') { return hb.t('js.qms.insp.attr'); }
        return hb.t('js.qms.insp.spec', num(l.lsl) || '-', num(l.nominal) || '-', num(l.usl) || '-') + (l.unitNm ? ' ' + l.unitNm : '');
    }

    /** @param {QmsInspLine} l @param {QmsMeas} m */
    function bad(l, m) {
        if (l.dataType === 'ATTRIBUTE') { return m.okYn !== 'Y'; }
        var v = Number(m.value);
        return (l.lsl !== null && l.lsl !== undefined && v < Number(l.lsl)) || (l.usl !== null && l.usl !== undefined && v > Number(l.usl));
    }

    /** @param {QmsInspDetail} d */
    function render(d) {
        detail = d;
        var h = d.insp;
        var open = h.status === 'OPEN';
        formMode.textContent = hb.t('js.qms.insp.editing', h.inspNo, typeNames[h.inspType] || h.inspType, h.lotNo);
        headLine.hidden = false;
        var parts = [statusNames[h.status] || h.status, hb.t('js.qms.insp.plan', h.planRev), hb.t('js.qms.insp.lotQty', hb.fmt.num(h.lotQty))];
        if (h.result) { parts.push(hb.t('js.qms.insp.result', resultNames[h.result] || h.result, h.judgedBy || '')); }
        else { parts.push(hb.t('js.qms.insp.preview', d.verdict === 'OPEN' ? hb.t('js.qms.insp.v.OPEN') : (resultNames[d.verdict] || d.verdict))); }
        if (h.voidReason) { parts.push(hb.t('js.qms.insp.voided', h.voidReason)); }
        headLine.textContent = parts.join(' · ');
        headLine.setAttribute('data-status', h.result === 'NG' || (!h.result && d.verdict === 'NG') ? 'NG' : h.result === 'OK' ? 'OK' : 'DUE_SOON');
        // 판정 확정 NG → 부적합 등록(Q8a)
        var ncrLink = /** @type {HTMLAnchorElement} */ (document.getElementById('ncrLink'));
        ncrLink.hidden = !(h.status === 'JUDGED' && h.result === 'NG');
        ncrLink.href = '/page/qms-ncr?lotNo=' + encodeURIComponent(h.lotNo) + '&inspNo=' + encodeURIComponent(h.inspNo) + '&source=INSP';
        show('judgeBtn', CAN_SAVE && open);
        show('voidBtn', CAN_DELETE && h.status !== 'VOID');
        var box = /** @type {HTMLElement} */ (document.getElementById('lines'));
        box.textContent = '';
        d.lines.forEach(function (l) { box.appendChild(lineCard(d, l, open)); });
        document.querySelectorAll('#inspTable tbody tr').forEach(function (tr) {
            tr.classList.toggle('hb-qms-sel', tr.getAttribute('data-id') === String(h.inspId));
        });
        loadOffPair(h);
    }

    /** 초품·종품 검사일 때만 같은 지시·공정의 짝을 나란히(docs/29_P3 27장) @param {QmsInsp} h */
    function loadOffPair(h) {
        var box = /** @type {HTMLElement} */ (document.getElementById('offPair'));
        if (h.inspType !== 'FIRST_OFF' && h.inspType !== 'LAST_OFF') {
            box.hidden = true;
            return;
        }
        hb.latest('offPair', hb.api(API + '/' + h.inspId + '/off-pair')).then(function (r) { renderOffPair(/** @type {QmsOffPair} */ (r.data)); },
            function (err) { if (err.code !== 'STALE') { box.hidden = true; } });
    }

    /** @param {QmsOffPair} p */
    function renderOffPair(p) {
        var box = /** @type {HTMLElement} */ (document.getElementById('offPair'));
        var note = /** @type {HTMLElement} */ (document.getElementById('offNote'));
        var cols = /** @type {HTMLElement} */ (document.getElementById('offCols'));
        box.hidden = false;
        // 지시 없는 LOT 이면 안내, 상한에 걸려 옛 짝이 빠졌을 수 있으면 잘림 표시(27.4)
        var text = p.woId == null ? hb.t('js.qms.insp.offNoWo') : p.truncated ? hb.t('js.qms.insp.offTruncated') : '';
        note.hidden = !text;
        note.textContent = text;
        cols.textContent = '';
        if (p.woId == null) { return; }
        cols.appendChild(offColumn(hb.t('js.qms.insp.offFirst'), p.firstOff));
        cols.appendChild(offColumn(hb.t('js.qms.insp.offLast'), p.lastOff));
    }

    /** @param {string} title @param {QmsOffSide} side */
    function offColumn(title, side) {
        /** @type {Record<string, string>} */
        var stateNames = {NONE: hb.t('js.qms.insp.offState.NONE'), PENDING: hb.t('js.qms.insp.offState.PENDING'),
            OK: hb.t('js.qms.insp.offState.OK'), NG: hb.t('js.qms.insp.offState.NG')};
        var col = document.createElement('div');
        var head = document.createElement('p');
        head.className = 'hb-qms-status';
        head.setAttribute('data-status', side.state === 'OK' ? 'OK' : side.state === 'NG' ? 'NG' : 'DUE_SOON');
        head.textContent = title + ' · ' + (stateNames[side.state] || side.state);
        col.appendChild(head);
        var ul = document.createElement('ul');
        side.rows.forEach(function (r) {
            var li = document.createElement('li');
            var a = document.createElement('button');
            a.type = 'button';
            a.className = 'hb-btn-secondary hb-link-btn';
            a.textContent = r.inspNo;
            a.addEventListener('click', function () { load(r.inspId); });
            li.appendChild(a);
            li.appendChild(document.createTextNode(' · ' + r.lotNo + ' · ' + (r.result ? (resultNames[r.result] || r.result) : (statusNames[r.status] || r.status))));
            ul.appendChild(li);
        });
        col.appendChild(ul);
        return col;
    }

    /** @param {QmsInspDetail} d @param {QmsInspLine} l @param {boolean} open */
    function lineCard(d, l, open) {
        var card = hb.el('section', 'hb-qms-line');
        card.setAttribute('data-line', String(l.inspLineId));
        var h3 = hb.el('h3');
        h3.appendChild(hb.el('span', null, l.lineNo + '. ' + l.charLabel + ' ' + l.charNm));
        var v = l.result || l.verdict;
        var badge = hb.el('span', 'hb-qms-verdict', v === 'OPEN' ? hb.t('js.qms.insp.v.OPEN') : (resultNames[v] || v));
        badge.setAttribute('data-v', v);
        h3.appendChild(badge);
        card.appendChild(h3);
        card.appendChild(hb.el('p', 'hb-muted', specText(l) + ' · ' + (gaugeNames[l.gaugeClass] || l.gaugeClass) + ' · '
            + hb.t('js.qms.insp.sample', l.measured, l.sampleSize, l.ac, l.re, l.nonconforming)));
        var ul = hb.el('ul', 'hb-qms-samples');
        d.meas.filter(function (m) { return m.inspLineId === l.inspLineId; }).forEach(function (m) {
            var li = hb.el('li');
            li.setAttribute('data-bad', String(bad(l, m)));
            var val = l.dataType === 'ATTRIBUTE' ? (m.okYn === 'Y' ? 'OK' : 'NG') : num(m.value);
            li.appendChild(hb.el('span', null, '#' + m.sampleNo + ' ' + val + (m.gaugeCd ? ' (' + m.gaugeCd + ')' : '')));
            li.title = m.recordedBy;
            if (open && CAN_SAVE) {
                var del = /** @type {HTMLButtonElement} */ (hb.el('button', 'hb-btn-ghost', '×'));
                del.type = 'button';
                del.setAttribute('aria-label', hb.t('js.qms.insp.voidMeas', m.sampleNo));
                del.addEventListener('click', function () { voidMeas(m); });
                li.appendChild(del);
            }
            ul.appendChild(li);
        });
        card.appendChild(ul);
        if (open && CAN_SAVE && l.measured < l.sampleSize) {
            var row = hb.el('div', 'hb-qms-inline');
            var labT = hb.el('label', 'hb-qms-grow');
            labT.appendChild(hb.el('span', null, hb.t(l.dataType === 'ATTRIBUTE' ? 'js.qms.insp.pasteAttr' : 'js.qms.insp.paste')));
            var ta = /** @type {HTMLTextAreaElement} */ (hb.el('textarea'));
            ta.rows = 2;
            labT.appendChild(ta);
            row.appendChild(labT);
            /** @type {HTMLSelectElement | null} */
            var sel = null;
            if (l.gaugeClass !== 'VISUAL') {
                var labG = hb.el('label');
                labG.appendChild(hb.el('span', null, hb.t('js.qms.insp.gauge')));
                var gs = /** @type {HTMLSelectElement} */ (hb.el('select'));
                labG.appendChild(gs);
                row.appendChild(labG);
                sel = gs;
                gauges(l.gaugeClass).then(function (list) {
                    var blank = /** @type {HTMLOptionElement} */ (hb.el('option', null, hb.t('js.qms.insp.pickGauge')));
                    blank.value = '';
                    gs.appendChild(blank);
                    list.forEach(function (g) {
                        var o = /** @type {HTMLOptionElement} */ (hb.el('option', null, g.assetCd + ' ' + g.assetNm + (g.blocked ? ' — ' + hb.t('js.qms.insp.blocked', g.status) : '')));
                        o.value = String(g.assetId);
                        o.disabled = g.blocked;
                        gs.appendChild(o);
                    });
                    var ok = list.filter(function (g) { return !g.blocked; });
                    if (ok.length === 1) { gs.value = String(ok[0].assetId); }
                });
            }
            var btn = /** @type {HTMLButtonElement} */ (hb.el('button', null, hb.t('js.qms.insp.add')));
            btn.type = 'button';
            btn.addEventListener('click', function () { addMeas(d, l, ta.value, sel ? sel.value : '', btn); });
            row.appendChild(btn);
            card.appendChild(row);
        }
        return card;
    }

    /** @param {string} cls */
    function gauges(cls) {
        if (!gaugeCache[cls]) {
            gaugeCache[cls] = hb.api(API + '/gauges?gaugeClass=' + encodeURIComponent(cls)).then(function (r) { return /** @type {QmsInspGauge[]} */ (r.data || []); },
                function () { return []; });
        }
        return gaugeCache[cls];
    }

    /** 붙여넣은 값 → 다음 빈 샘플 번호부터 @param {QmsInspDetail} d @param {QmsInspLine} l @param {string} text @param {string} gauge @param {HTMLButtonElement} btn */
    function addMeas(d, l, text, gauge, btn) {
        var tokens = text.split(/[\s,;]+/).filter(function (t) { return t.length > 0; });
        if (!tokens.length) { notify(hb.t('js.qms.insp.noValues'), true); return; }
        if (l.gaugeClass !== 'VISUAL' && !gauge) { notify(hb.t('js.qms.insp.needGauge'), true); return; }
        var taken = d.meas.filter(function (m) { return m.inspLineId === l.inspLineId; }).map(function (m) { return m.sampleNo; });
        var next = 1;
        /** @type {Record<string, any>[]} */
        var entries = [];
        for (var i = 0; i < tokens.length; i++) {
            while (taken.indexOf(next) >= 0) { next++; }
            var t = tokens[i].toUpperCase();
            /** @type {Record<string, any>} */
            var e = {sampleNo: next, gaugeAssetId: gauge ? Number(gauge) : null};
            if (l.dataType === 'ATTRIBUTE') {
                e.okYn = t === 'OK' || t === 'Y' || t === 'O' ? 'Y' : t === 'NG' || t === 'N' || t === 'X' ? 'N' : t;
            } else {
                e.value = tokens[i];
            }
            entries.push(e);
            next++;
        }
        hb.busy(btn, function () { return hb.api(API + '/' + d.insp.inspId + '/meas', {method: 'POST', body: {inspLineId: l.inspLineId, entries: entries}}); })
            .then(function (res) {
                if (!res) { return; }
                return reload().then(function () { notify(hb.t('js.qms.insp.added', entries.length)); });
            }, function (err) { notify(errText(err), true); });
    }

    /** @param {QmsMeas} m */
    function voidMeas(m) {
        if (!detail) { return; }
        var inspId = detail.insp.inspId;
        qmsUi.ask('voidRec', hb.t('js.qms.insp.confirmVoidMeas', m.sampleNo)).then(function (yes) {
            if (!yes) { return; }
            hb.api(API + '/' + inspId + '/meas/void', {method: 'POST', body: {measId: m.measId}})
                .then(function () { return reload(); }, function (err) { notify(errText(err), true); });
        });
    }

    // ---------- 읽기·동작 ----------

    /** @param {number} id */
    function load(id) {
        return hb.latest('detail', hb.api(API + '/' + id)).then(function (r) { render(r.data); },
            function (err) { if (err.code !== 'STALE') { notify(err.message, true); } });
    }

    function reload() {
        if (!detail) { return Promise.resolve(); }
        var id = detail.insp.inspId;
        return load(id).then(loadList);
    }

    /** @param {string} id @param {(b: HTMLButtonElement) => void} fn */
    function bind(id, fn) {
        var b = /** @type {HTMLButtonElement | null} */ (document.getElementById(id));
        if (b) {
            var btn = b;
            btn.addEventListener('click', function () { fn(btn); });
        }
    }

    // 측정값 엑셀 올리기(docs/29_P3 25장) — 반영되면 열린 상세·목록을 다시 읽는다. 판정은 화면에서
    bind('importBtn', function () {
        hb.importDialog({templateCd: 'QMS_INSP_MEAS', onDone: function () { return detail ? reload() : loadList(); }});
    });

    /**
     * 판정이 재고를 어떻게 움직이는지(WMS B3 — docs/29_P3 21.1 WB-3). 서버가 수입·출하 검사·WMS 연동 공장일 때만 stockQc 를 채운다.
     * OK = QC 재고가 가용으로, NG = QC 재고가 사용 금지로 + 이미 가용인 재고는 그대로(봉쇄 검토, D-B26)
     * @param {QmsInspDetail} d @returns {string}
     */
    function stockNote(d) {
        var s = d.stockQc || {};
        var qc = s.QC || 0;
        var avail = s.AVAILABLE || 0;
        var out = '';
        if (d.verdict === 'OK' && qc > 0) { out += ' ' + hb.t('js.qms.insp.stockOk', hb.fmt.num(qc)); }
        if (d.verdict === 'NG' && qc > 0) { out += ' ' + hb.t('js.qms.insp.stockNg', hb.fmt.num(qc)); }
        if (d.verdict === 'NG' && avail > 0) { out += ' ' + hb.t('js.qms.insp.stockAvail', hb.fmt.num(avail)); }
        return out;
    }

    bind('judgeBtn', function (btn) {
        if (!detail) { return; }
        var d = detail;
        qmsUi.ask('judge', hb.t('js.qms.insp.confirmJudge', d.insp.inspNo) + stockNote(d)).then(function (yes) {
            if (!yes) { return; }
            hb.busy(btn, function () { return hb.api(API + '/' + d.insp.inspId + '/judge', {method: 'POST', body: {rowVer: d.insp.rowVer}}); })
                .then(function (res) {
                    if (!res) { return; }
                    return reload().then(function () { notify(hb.t('js.qms.insp.judged')); });
                }, function (err) {
                    notify(err.message + (err.errors && err.errors.length ? ' ' + hb.t('js.qms.insp.openLines', err.errors.length) : ''), true);
                });
        });
    });

    bind('voidBtn', function (btn) {
        if (!detail) { return; }
        var d = detail;
        // 이 검사를 근거로 한 NCR 이 처리 중이면 먼저 확인(서버는 막지 않고 무효 사유 끝에 번호를 남긴다 — docs/29_P3 16장 QQ-1)
        var warn = d.openNcrNos && d.openNcrNos.length
            ? qmsUi.ask('voidRec', hb.t('js.qms.insp.voidOpenNcr', d.openNcrNos.join(', '))) : Promise.resolve(true);
        warn.then(function (ok) {
            return ok ? qmsUi.reason('voidRec', hb.t('js.qms.insp.voidReason', d.insp.inspNo)) : null;
        }).then(function (reason) {
            if (reason === null) { return; }
            hb.busy(btn, function () { return hb.api(API + '/' + d.insp.inspId + '/void', {method: 'POST', body: {rowVer: d.insp.rowVer, reason: reason}}); })
                .then(function (res) {
                    if (!res) { return; }
                    return reload().then(function () { notify(hb.t('js.qms.insp.voidDone')); });
                }, function (err) { notify(errText(err), true); });
        });
    });

    if (newForm) {
        var nf = newForm;
        nf.addEventListener('submit', function (ev) {
            ev.preventDefault();
            /** @param {string} n */
            function f(n) { return /** @type {HTMLInputElement} */ (nf.querySelector('[data-field="' + n + '"]')).value.trim(); }
            var qty = f('lotQty');
            var body = {lotNo: f('lotNo'), inspType: typeSel ? typeSel.value() : '', procCd: f('procCd') || null, lotQty: qty ? Number(qty) : null};
            hb.api(API, {method: 'POST', body: body}).then(function (res) {
                hb.showFieldErrors(nf, []);
                hb.page.openDetail();
                var id = /** @type {number} */ (res.data);
                return load(id).then(function () { return grid ? grid.reload(true, id) : null; }).then(function () { notify(hb.t('js.qms.insp.created')); });
            }, function (err) {
                notify(errText(err), true);
                hb.showFieldErrors(nf, err.errors || []);
            });
        });
    }

    // ---------- 시작 ----------
    search.addEventListener('submit', function (e) { e.preventDefault(); });
    hb.loadMessages().then(function () {
        hb.page.init();
        var ts = newForm ? /** @type {HTMLSelectElement | null} */ (newForm.querySelector('[data-field="inspType"]')) : null;
        typeSel = ts ? hb.select(ts) : null;
        sp = hb.searchPanel(search, {onSearch: function () { return grid ? grid.reload(true) : null; }});
        return Promise.all([hb.codes('INSP_TYPE'), hb.codes('QMS_INSP_STATUS'), hb.codes('QMS_INSP_RESULT'), hb.codes('GAUGE_CLASS'),
            hb.loadSession(), sp.ready, typeSel ? typeSel.ready : Promise.resolve()]);
    }).then(function (r) {
        typeNames = r[0];
        statusNames = r[1];
        resultNames = r[2];
        gaugeNames = r[3];
        // 조회 패널이 주소 조건(?q.status=…)을 채운 뒤 첫 조회
        return startGrid().then(function () { return sp.search(); });
    }).then(null, function (err) { notify(err && err.message, true); });
})();
