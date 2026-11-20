// @ts-check
/*
 * 봉쇄 (qms-contain) — P3 QMS 2부 Q8b (docs/29_P3 12.8).
 *   새 봉쇄(품목·기간·근거 NCR·사유 → 후보 조회 → 고름 → 봉쇄) + 목록 → 상세: LOT 줄(지금 보류 근거) 골라 해제.
 *   ?itemCd=&ncrNo= 로 채워 연다(부적합 화면). 서버 데이터는 textContent·value 로만.
 */
/**
 * @typedef {{holdId: number, lotId: number, source: string, refId?: number | null, reason: string, heldAt: string, heldBy: string}} QmsHold
 * @typedef {{lotId: number, lotNo: string, qtyCreated: number, unitCd: string, occurredAt: string}} QmsCandLot
 * @typedef {{item: {itemId: number, itemCd: string, itemNm: string}, lots: QmsCandLot[], holds: QmsHold[], truncated: boolean}} QmsCandidates
 * @typedef {{containId: number, containNo: string, itemCd: string, itemNm: string, ncrNo?: string | null, fromDate: string, toDate: string,
 *   reason: string, status: string, lotCnt: number, heldCnt: number, createdAt: string, createdBy: string}} QmsContain
 * @typedef {{lotId: number, lotNo: string, qtyCreated: number, unitCd: string, occurredAt: string, releasedAt?: string | null,
 *   releasedBy?: string | null, releaseReason?: string | null}} QmsContainLot
 * @typedef {{contain: QmsContain, lots: QmsContainLot[], holds: QmsHold[]}} QmsContainDetail
 */
(function () {
    'use strict';

    /** QMS 화면 공용 — 상태 배지·대화상자(qms-ui.js) */
    var qmsUi = /** @type {QmsUi} */ (/** @type {any} */ (window).qmsUi);

    var PROG = document.body.getAttribute('data-prog') || '';
    var ACTIONS = (document.body.getAttribute('data-actions') || '').split(',');
    var CAN_SAVE = ACTIONS.indexOf('SAVE') >= 0;
    var API = '/api/qms/contains';
    var msg = /** @type {HTMLElement} */ (document.getElementById('msg'));
    var newForm = /** @type {HTMLFormElement | null} */ (document.getElementById('newForm'));
    var search = /** @type {HTMLFormElement} */ (document.getElementById('search'));
    /** 조회 패널 — 홈 숫자 링크(?q.status=…)를 준비 단계에서 채운다 @type {HB.SearchPanel} */
    var sp;
    /** @type {QmsContainDetail | null} */
    var detail = null;
    /** @type {QmsCandidates | null} */
    var cand = null;
    /** @type {Record<string, string>} */
    var statusNames = {};
    /** @type {Record<string, string>} 보류 근거 출처 — MES LOT 화면과 같은 문구 */
    var srcNames = {};

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

    /** @param {ParentNode} root @param {string} name */
    function field(root, name) {
        return /** @type {HTMLInputElement} */ (root.querySelector('[data-field="' + name + '"]'));
    }

    /** 한 LOT 의 살아 있는 근거 요약 @param {QmsHold[]} holds @param {number} lotId */
    function holdText(holds, lotId) {
        return holds.filter(function (h) { return h.lotId === lotId; })
            .map(function (h) { return (srcNames[h.source] || h.source) + (h.reason ? ' (' + h.reason + ')' : ''); }).join(', ');
    }

    /** @param {HTMLElement} tbody @param {number} cols @param {string} text */
    function empty(tbody, cols, text) {
        var tr = hb.el('tr');
        var td = /** @type {HTMLTableCellElement} */ (hb.el('td', 'hb-muted', text));
        td.colSpan = cols;
        tr.appendChild(td);
        tbody.appendChild(tr);
    }

    /** @param {string} tableId */
    function checked(tableId) {
        return Array.prototype.map.call(document.querySelectorAll('#' + tableId + ' tbody input[type=checkbox]:checked'),
            /** @param {HTMLInputElement} c */ function (c) { return Number(c.value); });
    }

    function candLine() {
        if (!cand) { return; }
        /** @type {HTMLElement} */ (document.getElementById('candLine')).textContent = hb.t('js.qms.contain.candLine', cand.lots.length,
            checked('candTable').length) + (cand.truncated ? ' ' + hb.t('js.qms.contain.truncated') : '');
    }

    /** 교정 불합격 영향 LOT 에서 연 경우 미리 고를 LOT 번호(?lots=) — 첫 후보 조회 뒤 비운다 @type {string[] | null} */
    var preselect = null;

    /** @param {QmsCandidates} c */
    function renderCand(c) {
        /* preselect: 주소 ?lots= 로 온 LOT 번호(첫 후보 조회 한 번만) */
        cand = c;
        /** @type {HTMLElement} */ (document.getElementById('candBox')).hidden = false;
        var tbody = /** @type {HTMLElement} */ (document.querySelector('#candTable tbody'));
        tbody.textContent = '';
        c.lots.forEach(function (l) {
            var tr = hb.el('tr');
            var box = /** @type {HTMLInputElement} */ (hb.el('input'));
            box.type = 'checkbox';
            box.value = String(l.lotId);
            box.checked = preselect ? preselect.indexOf(l.lotNo) >= 0 : true;
            box.addEventListener('change', candLine);
            var td0 = hb.el('td');
            td0.appendChild(box);
            tr.appendChild(td0);
            tr.appendChild(hb.el('td', 'hb-wrap', l.lotNo));
            tr.appendChild(hb.el('td', 'hb-num', hb.fmt.num(l.qtyCreated, 3, 0) + ' ' + l.unitCd));
            tr.appendChild(hb.el('td', null, hb.fmt.datetime(l.occurredAt)));
            tr.appendChild(hb.el('td', 'hb-wrap', holdText(c.holds, l.lotId)));
            tbody.appendChild(tr);
        });
        if (!c.lots.length) { empty(tbody, 5, hb.t('js.qms.contain.noCand')); }
        /** @type {HTMLInputElement} */ (document.getElementById('candAll')).checked = c.lots.length > 0
            && checked('candTable').length === c.lots.length;
        candLine();
        if (preselect) {
            // 교정 불합격 영향 LOT 에서 왔을 때: 그 LOT 만 체크, 후보(품목 × 기간, 상한)에 없는 LOT 은 알린다
            var have = c.lots.map(function (l) { return l.lotNo; });
            var missing = preselect.filter(function (no) { return have.indexOf(no) < 0; });
            notify(hb.t('js.qms.contain.preselected', preselect.length - missing.length)
                + (missing.length ? ' ' + hb.t('js.qms.contain.preselectMissing', missing.length, missing.join(', ')) : ''), missing.length > 0);
            preselect = null;
        }
    }

    /** 주 목록 = hb.grid(UI 단계 3) — 서버 /grid·/columns, 상태 배지·끝난 행 흐림 @type {HB.Grid | null} */
    var grid = null;

    /** 목록 다시 읽기 — 보던 행을 그대로 고른 채(keepId 는 onSelect 를 부르지 않는다) */
    function loadList() {
        return grid ? grid.reload(false, detail ? detail.contain.containId : undefined) : Promise.resolve();
    }

    function startGrid() {
        grid = hb.grid('#containGrid', {
            progCd: PROG, id: 'main', key: 'containId',
            url: API + '/grid', columnsUrl: API + '/columns', exportUrl: API + '/export', actions: ACTIONS,
            params: function () { return sp.values(); },
            filters: {status: 'status'},
            onFilter: function (param, value) { sp.setValue(param, value); },
            status: {status: qmsUi.TONES.contain},
            rowClass: function (row) { return row.status === 'CLOSED' ? 'hb-tree-dim' : null; },
            onSelect: function (row) { load(/** @type {number} */ (row.containId)); hb.page.openDetail(); },
            notify: notify
        });
        return grid.ready;
    }

    /** @param {number} id */
    function load(id) {
        return hb.latest('detail', hb.api(API + '/' + id)).then(function (res) { render(res.data); },
            function (err) { if (err.code !== 'STALE') { notify(err.message, true); } });
    }

    /** @param {QmsContainDetail} d */
    function render(d) {
        detail = d;
        /** @type {HTMLElement} */ (document.getElementById('formMode')).hidden = true;
        /** @type {HTMLElement} */ (document.getElementById('detailBox')).hidden = false;
        var c = d.contain;
        var head = /** @type {HTMLElement} */ (document.getElementById('headLine'));
        head.textContent = hb.t('js.qms.contain.head', c.containNo, statusNames[c.status] || c.status, c.itemCd, c.itemNm, hb.fmt.date(c.fromDate),
            hb.fmt.date(c.toDate), c.heldCnt + ' / ' + c.lotCnt) + (c.ncrNo ? ' · NCR ' + c.ncrNo : '');
        head.setAttribute('data-status', c.status === 'CLOSED' ? 'OK' : 'NG');
        /** @type {HTMLElement} */ (document.getElementById('reasonLine')).textContent = c.reason + ' — ' + c.createdBy + ' '
            + hb.fmt.datetime(c.createdAt);
        var open = c.status === 'OPEN' && CAN_SAVE;
        var tbody = /** @type {HTMLElement} */ (document.querySelector('#lotTable tbody'));
        tbody.textContent = '';
        d.lots.forEach(function (l) {
            var tr = hb.el('tr', l.releasedAt ? 'hb-tree-dim' : null);
            var td0 = hb.el('td');
            if (open && !l.releasedAt) {
                var box = /** @type {HTMLInputElement} */ (hb.el('input'));
                box.type = 'checkbox';
                box.value = String(l.lotId);
                td0.appendChild(box);
            }
            tr.appendChild(td0);
            tr.appendChild(hb.el('td', 'hb-wrap', l.lotNo));
            tr.appendChild(hb.el('td', 'hb-num', hb.fmt.num(l.qtyCreated, 3, 0) + ' ' + l.unitCd));
            tr.appendChild(hb.el('td', 'hb-wrap', holdText(d.holds, l.lotId)));
            tr.appendChild(hb.el('td', 'hb-wrap', l.releasedAt ? hb.t('js.qms.contain.released1', l.releasedBy, hb.fmt.datetime(l.releasedAt),
                l.releaseReason) : ''));
            tbody.appendChild(tr);
        });
        /** @type {HTMLElement} */ (document.getElementById('releaseForm')).hidden = !open;
        /** @type {HTMLInputElement} */ (document.getElementById('lotAll')).disabled = !open;
    }

    // ---------- 동작 ----------
    /** @param {string} allId @param {string} tableId @param {(() => void) | undefined} after */
    function toggleAll(allId, tableId, after) {
        var all = /** @type {HTMLInputElement} */ (document.getElementById(allId));
        all.addEventListener('change', function () {
            document.querySelectorAll('#' + tableId + ' tbody input[type=checkbox]').forEach(function (c) {
                /** @type {HTMLInputElement} */ (c).checked = all.checked;
            });
            if (after) { after(); }
        });
    }
    toggleAll('candAll', 'candTable', candLine);
    toggleAll('lotAll', 'lotTable', undefined);

    if (newForm) {
        var nf = newForm;
        var findBtn = /** @type {HTMLButtonElement} */ (document.getElementById('findBtn'));
        findBtn.addEventListener('click', function () {
            var q = 'itemCd=' + encodeURIComponent(field(nf, 'itemCd').value.trim()) + '&from=' + encodeURIComponent(field(nf, 'fromDate').value)
                + '&to=' + encodeURIComponent(field(nf, 'toDate').value);
            hb.busy(findBtn, function () { return hb.api(API + '/candidates?' + q); }).then(function (res) {
                if (!res) { return; }
                hb.showFieldErrors(nf, []);
                notify('');
                renderCand(res.data);
            }, function (err) { notify(errText(err), true); hb.showFieldErrors(nf, err.errors || []); });
        });
        var runBtn = /** @type {HTMLButtonElement} */ (document.getElementById('runBtn'));
        runBtn.addEventListener('click', function () {
            var ids = checked('candTable');
            if (!ids.length) { notify(hb.t('js.qms.contain.pickLots'), true); return; }
            hb.busy(runBtn, function () {
                return hb.api(API, {method: 'POST', body: {itemCd: field(nf, 'itemCd').value.trim(), fromDate: field(nf, 'fromDate').value || null,
                    toDate: field(nf, 'toDate').value || null, ncrNo: field(nf, 'ncrNo').value.trim() || null, reason: field(nf, 'reason').value,
                    lotIds: ids}});
            }).then(function (res) {
                if (!res) { return; }
                hb.showFieldErrors(nf, []);
                /** @type {HTMLElement} */ (document.getElementById('candBox')).hidden = true;
                cand = null;
                notify(hb.t('js.qms.contain.created'));
                hb.page.openDetail();
                var id = /** @type {number} */ (res.data);
                return Promise.all([load(id), grid ? grid.reload(true, id) : null]);
            }, function (err) { notify(errText(err), true); hb.showFieldErrors(nf, err.errors || []); });
        });
    }

    search.addEventListener('submit', function (e) { e.preventDefault(); });
    var releaseForm = /** @type {HTMLFormElement} */ (document.getElementById('releaseForm'));
    releaseForm.addEventListener('submit', function (ev) {
        ev.preventDefault();
        if (!detail) { return; }
        var id = detail.contain.containId;
        var ids = checked('lotTable');
        if (!ids.length) { notify(hb.t('js.qms.contain.pickLots'), true); return; }
        var btn = /** @type {HTMLButtonElement} */ (releaseForm.querySelector('button[type=submit]'));
        var reason = /** @type {HTMLInputElement} */ (document.getElementById('releaseReason'));
        hb.busy(btn, function () { return hb.api(API + '/' + id + '/release', {method: 'POST', body: {lotIds: ids, reason: reason.value}}); })
            .then(function (res) {
                if (!res) { return; }
                reason.value = '';
                notify(hb.t('js.qms.contain.released'));
                return Promise.all([load(id), loadList()]);
            }, function (err) { notify(errText(err), true); });
    });

    hb.loadMessages().then(function () {
        hb.page.init();
        sp = hb.searchPanel(search, {onSearch: function () { return grid ? grid.reload(true) : null; }});
        return Promise.all([hb.codes('QMS_CONTAIN_STATUS'), hb.loadSession(), sp.ready]);
    }).then(function (r) {
        statusNames = r[0];
        srcNames = {MANUAL: hb.t('js.prdLot.src.MANUAL'), MES_CORRECT: hb.t('js.prdLot.src.MES_CORRECT'),
            QMS_NCR: hb.t('js.prdLot.src.QMS_NCR'), QMS_CONTAIN: hb.t('js.prdLot.src.QMS_CONTAIN')};
        var qs = new URLSearchParams(window.location.search);
        if (newForm && qs.get('itemCd')) {
            field(newForm, 'itemCd').value = qs.get('itemCd') || '';
            field(newForm, 'ncrNo').value = qs.get('ncrNo') || '';
            field(newForm, 'reason').value = qs.get('ncrNo') ? 'NCR ' + qs.get('ncrNo') : (qs.get('reason') || '').slice(0, 500);
            // 교정 불합격 영향 LOT 에서: ?from=&to=&lots= → 기간을 채우고 후보를 찾아 그 LOT 만 체크
            var iso = /^\d{4}-\d{2}-\d{2}$/;
            if (iso.test(qs.get('from') || '') && iso.test(qs.get('to') || '')) {
                field(newForm, 'fromDate').value = qs.get('from') || '';
                field(newForm, 'toDate').value = qs.get('to') || '';
                var lots = (qs.get('lots') || '').split(',').map(function (x) { return x.trim(); }).filter(Boolean).slice(0, 500);
                preselect = lots.length ? lots : null;
                /** @type {HTMLButtonElement} */ (document.getElementById('findBtn')).click();
            }
        }
        // 조회 패널이 주소 조건(?q.status=…)을 채운 뒤 첫 조회
        return startGrid().then(function () { return sp.search(); });
    }).then(null, function (err) { notify(err && err.message, true); });
})();
