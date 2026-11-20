// @ts-check
/*
 * 초품 확인 (qms-setup) — P3 QMS 2부 Q7 (docs/29_P3 12.3).
 *   최근 판정 표 + ECO 번호로 열기(?ecoId=) → 상세: ECO 줄(OK·NG·미판정 수) / 첫 LOT 카드마다 살아 있는 판정 또는 근거 검사 고름·메모·기록, 무효.
 * 결과는 근거 검사 결과를 서버가 복사. 서버 데이터는 textContent·value 로만.
 */
/**
 * @typedef {{verifId: number, ecoId: number, lotId: number, lotNo: string, inspId: number, inspNo: string, inspType: string, result: string,
 *   status: string, note?: string | null, voidReason?: string | null, createdAt: string, createdBy: string, rowVer: number,
 *   ecoNo?: string | null, ecoTitle?: string | null}} QmsVerdict
 * @typedef {{inspId: number, inspNo: string, inspType: string, result: string, judgedAt: string}} QmsInspOption
 * @typedef {{lotId: number, lotNo: string, seqNo: number, occurredAt: string, verdict?: QmsVerdict | null, candidates: QmsInspOption[]}} QmsFirstLot
 * @typedef {{eco: {ecoId: number, ecoNo: string, title: string, changeKind: string, status: string, effectiveDate: string},
 *   lots: QmsFirstLot[], ok: number, ng: number, pending: number}} QmsSetupDetail
 */
(function () {
    'use strict';

    /** QMS 화면 공용 — 상태 배지·대화상자(qms-ui.js) */
    var qmsUi = /** @type {QmsUi} */ (/** @type {any} */ (window).qmsUi);

    var ACTIONS = (document.body.getAttribute('data-actions') || '').split(',');
    var CAN_SAVE = ACTIONS.indexOf('SAVE') >= 0;
    var CAN_DELETE = ACTIONS.indexOf('DELETE') >= 0;
    var API = '/api/qms/setups';
    var msg = /** @type {HTMLElement} */ (document.getElementById('msg'));
    var formMode = /** @type {HTMLElement} */ (document.getElementById('formMode'));
    var headLine = /** @type {HTMLElement} */ (document.getElementById('headLine'));
    /** @type {QmsSetupDetail | null} */
    var detail = null;
    /** @type {Record<string, string>} */
    var typeNames = {};
    /** @type {Record<string, string>} */
    var resultNames = {};
    /** @type {Record<string, string>} */
    var statusNames = {};
    /** @type {Record<string, string>} */
    var kindNames = {};

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

    function loadList() {
        return hb.latest('list', hb.api(API)).then(function (res) {
            var tbody = /** @type {HTMLElement} */ (document.querySelector('#recentTable tbody'));
            tbody.textContent = '';
            /** @type {QmsVerdict[]} */ (res.data || []).forEach(function (r) {
                var tr = hb.el('tr', 'hb-qms-click' + (r.status === 'VOID' ? ' hb-tree-dim' : ''));
                tr.appendChild(hb.el('td', null, r.ecoNo || String(r.ecoId)));
                tr.appendChild(hb.el('td', 'hb-wrap', r.lotNo));
                tr.appendChild(hb.el('td', null, r.inspNo));
                tr.appendChild(qmsUi.cell('setup', r.result, resultNames[r.result]));
                tr.appendChild(qmsUi.cell('live', r.status, statusNames[r.status]));
                tr.appendChild(hb.el('td', null, r.createdBy + ' ' + hb.fmt.datetime(r.createdAt)));
                tr.addEventListener('click', function () { load(r.ecoId); hb.page.openDetail(); });
                tbody.appendChild(tr);
            });
            if (!(res.data || []).length) {
                var tr0 = hb.el('tr');
                var td0 = /** @type {HTMLTableCellElement} */ (hb.el('td', 'hb-muted', hb.t('js.qms.setup.none')));
                td0.colSpan = 6;
                tr0.appendChild(td0);
                tbody.appendChild(tr0);
            }
        }, function (err) { if (err.code !== 'STALE') { notify(err.message, true); } });
    }

    /** @param {QmsSetupDetail} d */
    function render(d) {
        detail = d;
        formMode.textContent = hb.t('js.qms.setup.editing', d.eco.ecoNo, d.eco.title);
        headLine.hidden = false;
        headLine.textContent = [kindNames[d.eco.changeKind] || d.eco.changeKind, hb.t('js.qms.setup.counts', d.ok, d.ng, d.pending)].join(' · ');
        headLine.setAttribute('data-status', d.ng > 0 ? 'NG' : d.pending > 0 ? 'DUE_SOON' : 'OK');
        var box = /** @type {HTMLElement} */ (document.getElementById('lots'));
        box.textContent = '';
        if (!d.lots.length) { box.appendChild(hb.el('p', 'hb-muted', hb.t('js.qms.setup.noLots'))); }
        d.lots.forEach(function (l) { box.appendChild(card(d, l)); });
    }

    /** @param {QmsSetupDetail} d @param {QmsFirstLot} l */
    function card(d, l) {
        var c = hb.el('section', 'hb-qms-line');
        var h3 = hb.el('h3');
        h3.appendChild(hb.el('span', null, hb.t('js.qms.setup.lot', l.seqNo, l.lotNo)));
        var v = l.verdict;
        var badge = hb.el('span', 'hb-qms-verdict', v ? (resultNames[v.result] || v.result) : hb.t('js.qms.setup.pending'));
        badge.setAttribute('data-v', v ? v.result : 'OPEN');
        h3.appendChild(badge);
        c.appendChild(h3);
        c.appendChild(hb.el('p', 'hb-muted', hb.t('js.qms.setup.occurred', hb.fmt.datetime(l.occurredAt))));
        if (v) {
            c.appendChild(hb.el('p', null, hb.t('js.qms.setup.evidence', v.inspNo, typeNames[v.inspType] || v.inspType, v.createdBy)
                + (v.note ? ' · ' + v.note : '')));
            if (v.result === 'NG') {
                // 초품 NG → 부적합 등록(Q8a) — 그 LOT·근거 검사로 채워 연다
                var ncr = /** @type {HTMLAnchorElement} */ (hb.el('a', 'hb-btn-secondary hb-btn-small hb-link-btn', hb.t('js.qms.ncr.open')));
                ncr.href = '/page/qms-ncr?lotNo=' + encodeURIComponent(l.lotNo) + '&inspNo=' + encodeURIComponent(v.inspNo) + '&source=SETUP';
                c.appendChild(ncr);
            }
            if (CAN_DELETE) {
                var vb = /** @type {HTMLButtonElement} */ (hb.el('button', 'hb-btn-secondary hb-btn-small', hb.t('js.qms.setup.void')));
                vb.type = 'button';
                var verdict = v;
                vb.addEventListener('click', function () { voidVerdict(verdict); });
                c.appendChild(vb);
            }
            return c;
        }
        if (!CAN_SAVE) { return c; }
        if (!l.candidates.length) {
            c.appendChild(hb.el('p', 'hb-muted', hb.t('js.qms.setup.noInsp')));
            return c;
        }
        var row = hb.el('div', 'hb-qms-inline');
        var labS = hb.el('label');
        labS.appendChild(hb.el('span', null, hb.t('js.qms.setup.pick')));
        var sel = /** @type {HTMLSelectElement} */ (hb.el('select'));
        l.candidates.forEach(function (o) {
            var op = /** @type {HTMLOptionElement} */ (hb.el('option', null, o.inspNo + ' ' + (typeNames[o.inspType] || o.inspType) + ' — '
                + (resultNames[o.result] || o.result)));
            op.value = String(o.inspId);
            sel.appendChild(op);
        });
        labS.appendChild(sel);
        row.appendChild(labS);
        var labN = hb.el('label', 'hb-qms-grow');
        labN.appendChild(hb.el('span', null, hb.t('js.qms.setup.note')));
        var note = /** @type {HTMLInputElement} */ (hb.el('input'));
        note.maxLength = 1000;
        labN.appendChild(note);
        row.appendChild(labN);
        var btn = /** @type {HTMLButtonElement} */ (hb.el('button', null, hb.t('js.qms.setup.record')));
        btn.type = 'button';
        btn.addEventListener('click', function () {
            hb.busy(btn, function () {
                return hb.api(API + '/' + d.eco.ecoId + '/verdicts', {method: 'POST', body: {lotId: l.lotId, inspId: Number(sel.value), note: note.value}});
            }).then(function (res) {
                if (!res) { return; }
                return load(d.eco.ecoId).then(loadList).then(function () { notify(hb.t('js.qms.setup.recorded')); });
            }, function (err) { notify(errText(err), true); });
        });
        row.appendChild(btn);
        c.appendChild(row);
        return c;
    }

    /** @param {QmsVerdict} v */
    function voidVerdict(v) {
        if (!detail) { return; }
        var ecoId = detail.eco.ecoId;
        qmsUi.reason('voidRec', hb.t('js.qms.setup.voidReason', v.lotNo)).then(function (reason) {
            if (reason === null) { return; }
            hb.api(API + '/verdicts/' + v.verifId + '/void', {method: 'POST', body: {rowVer: v.rowVer, reason: reason}})
                .then(function () { return load(ecoId).then(loadList).then(function () { notify(hb.t('js.qms.setup.voided')); }); },
                    function (err) { notify(errText(err), true); });
        });
    }

    /** @param {number} ecoId */
    function load(ecoId) {
        return hb.latest('detail', hb.api(API + '/' + ecoId)).then(function (r) { render(r.data); },
            function (err) { if (err.code !== 'STALE') { notify(err.message, true); } });
    }

    var openForm = /** @type {HTMLFormElement} */ (document.getElementById('openForm'));
    openForm.addEventListener('submit', function (ev) {
        ev.preventDefault();
        var no = /** @type {HTMLInputElement} */ (document.getElementById('openNo')).value.trim();
        if (!no) { return; }
        hb.api(API + '/by-no?ecoNo=' + encodeURIComponent(no)).then(function (res) {
            hb.page.openDetail();
            return load(/** @type {number} */ (res.data));
        }, function (err) { notify(err.code === 'NOT_FOUND' ? hb.t('js.qms.change.noEco', no) : err.message, true); });
    });

    hb.loadMessages().then(function () {
        hb.page.init();
        return Promise.all([hb.codes('INSP_TYPE'), hb.codes('QMS_INSP_RESULT'), hb.codes('QMS_SETUP_STATUS'), hb.codes('PLM_CHANGE_KIND'), hb.loadSession()]);
    }).then(function (r) {
        typeNames = r[0];
        resultNames = r[1];
        statusNames = r[2];
        kindNames = r[3];
        var ecoId = Number(new URLSearchParams(window.location.search).get('ecoId'));
        return loadList().then(function () {
            if (ecoId > 0) {
                hb.page.openDetail();
                return load(ecoId);
            }
        });
    }).then(null, function (err) { notify(err && err.message, true); });
})();
