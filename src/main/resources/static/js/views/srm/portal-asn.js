/*
 * 협력사 포털 — 납품 통지(ASN) 입력·확정 + 납품서 인쇄 (docs/42 7장 3, docs/31 4.13 P-2).
 * 새 ASN: 공장 → 그 공장의 내 열린 발주 행(보낼 수 있는 양) → 행마다 수량·공급사 LOT·날짜 → 저장(작성 중) → 확정(보냄, 저장 뒤).
 * 포털에서 만든(PORTAL) 작성 중 ASN 만 고친다 — 그 밖(거래 회사가 넣은 것·확정 뒤)은 보기만, 취소는 PORTAL 의 작성 중·확정.
 * 판정·검사(한도·같은 LOT 중복·공장)는 서버(내부 ASN 과 같은 AsnService). 서버 글자는 textContent 로만.
 */
(function () {
    'use strict';

    var API = '/api/portal/srm';
    var STATUSES = ['DRAFT', 'SENT', 'PARTIAL_RECEIVED', 'RECEIVED', 'CLOSED', 'CANCELLED'];
    var search = document.getElementById('search');
    var tbody = document.querySelector('#asnTable tbody');
    var detail = document.getElementById('detail');
    var form = document.getElementById('asnForm');
    var lineBody = document.querySelector('#lineTable tbody');
    var plantSel = document.getElementById('plantSel');
    var current = null;     // AsnDetail (새 ASN 이면 null)
    var labels = [];        // 보낸 행 순번 → 화면 글자(행 오류 표시용)

    function field(n) { return form.querySelector('[data-field="' + n + '"]'); }
    function name(group, code) {
        if (!code) { return ''; }
        var key = 'js.portalSrm.' + group + '.' + code;
        var t = hb.t(key);
        return t === key ? code : t;
    }
    function cell(tr, text, cls) { var td = hb.el('td', cls || null, text === null || text === undefined ? '' : text); tr.appendChild(td); return td; }

    function showMsg(box, err) {
        box.textContent = '';
        if (box.id === 'asnMsg') {      // 머리 칸 오류(행 키 없음)는 그 칸 옆에
            hb.showFieldErrors(form.querySelector('.sp-fields'), (err && err.errors || []).filter(function (e) { return e.rowKey === undefined || e.rowKey === null; }));
        }
        if (!err) { box.hidden = true; return; }
        var lines = [err.message];
        (err.errors || []).forEach(function (e) {
            var k = e.rowKey !== undefined && e.rowKey !== null ? Number(e.rowKey) : -1;
            lines.push(k >= 0 ? hb.t('js.portalSrm.rowError', labels[k] || String(k + 1), e.message) : e.message);
        });
        (err.details || []).forEach(function (d) { lines.push(d); });
        lines.forEach(function (l) { box.appendChild(box.tagName === 'UL' ? hb.el('li', null, l) : document.createTextNode(l + ' ')); });
        box.hidden = false;
    }
    function ok(text) {
        var p = document.getElementById('asnOk');
        p.textContent = text || '';
        p.hidden = !text;
    }

    // ---------------------------------------------------------------- 목록

    function load(selectId) {
        var f = new FormData(search);
        var q = new URLSearchParams();
        ['from', 'to', 'status', 'keyword'].forEach(function (k) { if (f.get(k)) { q.set(k, String(f.get(k))); } });
        return hb.api(API + '/asns?' + q.toString()).then(function (res) {
            showMsg(document.getElementById('listMsg'), null);
            tbody.textContent = '';
            (res.data || []).forEach(function (a) {
                var tr = hb.el('tr', 'sp-pick');
                tr.tabIndex = 0;
                tr.setAttribute('data-id', String(a.asnId));
                cell(tr, a.asnNo);
                cell(tr, a.plantNm || a.plantCd);
                cell(tr, a.shipDate);
                cell(tr, a.etaDate);
                cell(tr, a.supplierRef);
                cell(tr, name('asnStatus', a.status));
                cell(tr, name('source', a.source));
                cell(tr, String(a.lineCnt), 'sp-num');
                tr.addEventListener('click', function () { open(a.asnId); });
                tr.addEventListener('keydown', function (e) { if (e.key === 'Enter') { open(a.asnId); } });
                tbody.appendChild(tr);
            });
            if (!res.data || !res.data.length) {
                var tr = hb.el('tr');
                cell(tr, hb.t('js.portalSrm.empty')).colSpan = 8;
                tbody.appendChild(tr);
            }
            return selectId ? open(selectId) : null;
        }, function (err) { showMsg(document.getElementById('listMsg'), err); });
    }

    // ---------------------------------------------------------------- 상세·입력

    function editable() { return !current || current.asn.source === 'PORTAL' && current.asn.status === 'DRAFT'; }

    function loadPlants(selected) {
        return hb.api(API + '/asn-plants').then(function (res) {
            plantSel.textContent = '';
            var blank = hb.el('option', null, hb.t('js.portalSrm.pickPlant'));
            blank.value = '';
            plantSel.appendChild(blank);
            var rows = res.data || [];
            if (selected && !rows.some(function (p) { return p.plantCd === selected; })) {
                rows = rows.concat([{plantCd: selected, plantNm: current ? current.asn.plantNm : ''}]);
            }
            rows.forEach(function (p) {
                var o = hb.el('option', null, p.plantCd + (p.plantNm ? ' · ' + p.plantNm : ''));
                o.value = p.plantCd;
                plantSel.appendChild(o);
            });
            plantSel.value = selected || (rows.length === 1 ? rows[0].plantCd : '');
        });
    }

    function newForm() {
        current = null;
        return openForm();
    }

    function open(asnId) {
        return hb.api(API + '/asns/' + asnId).then(function (res) {
            current = res.data;
            tbody.querySelectorAll('tr').forEach(function (tr) {
                tr.setAttribute('aria-selected', tr.getAttribute('data-id') === String(asnId) ? 'true' : 'false');
            });
            return openForm();
        }, function (err) { showMsg(document.getElementById('listMsg'), err); });
    }

    function openForm() {
        var a = current ? current.asn : null;
        showMsg(document.getElementById('asnMsg'), null);
        ok(null);
        form.reset();
        document.getElementById('detailTitle').textContent = a ? hb.t('js.portalSrm.asnTitle', a.asnNo) : hb.t('js.portalSrm.asnNew');
        document.getElementById('detailInfo').textContent = a ? [name('asnStatus', a.status), name('source', a.source),
            a.sentAt ? hb.t('js.portalSrm.sentAt', hb.fmt.datetime(a.sentAt)) : '', a.cancelReason ? hb.t('js.portalSrm.cancelled', a.cancelReason) : '',
            a.source !== 'PORTAL' ? hb.t('js.portalSrm.readOnlyInternal') : ''].filter(Boolean).join(' · ') : '';
        if (a) {
            field('shipDate').value = a.shipDate || '';
            field('etaDate').value = a.etaDate || '';
            field('supplierRef').value = a.supplierRef || '';
            field('carrierMemo').value = a.carrierMemo || '';
            field('remark').value = a.remark || '';
        } else {
            var today = hb.dates.todayIn(hb.session().timeZone);
            field('shipDate').value = today;
            field('etaDate').value = hb.dates.add(today, 1);
        }
        var edit = editable();
        form.querySelectorAll('.sp-fields input, .sp-fields select').forEach(function (i) { i.disabled = !edit; });
        plantSel.disabled = !edit || !!a;                        // 공장은 만들 때만(행이 그 공장 것)
        document.getElementById('saveBtn').hidden = !edit;
        document.getElementById('sendBtn').hidden = !edit;
        document.getElementById('lineHint').hidden = !edit;
        var cancellable = !!a && a.source === 'PORTAL' && (a.status === 'DRAFT' || a.status === 'SENT');
        document.getElementById('cancelBox').hidden = !cancellable;
        document.getElementById('cancelReason').disabled = !cancellable;
        var link = document.getElementById('printLink');
        link.hidden = !a || a.status === 'CANCELLED';
        if (a) { link.href = '/portal/asn-print?asnId=' + a.asnId; }
        detail.hidden = false;
        return loadPlants(a ? a.plantCd : null).then(function () { return renderLines(); }).then(function () {
            detail.scrollIntoView({block: 'start'});
        });
    }

    /** 행 표 — 고칠 수 있으면 그 공장의 내 열린 발주 행 + 이미 넣은 행, 아니면 넣은 행만 글자로 */
    function renderLines() {
        lineBody.textContent = '';
        if (!editable()) {
            current.lines.forEach(function (l) {
                var tr = hb.el('tr');
                cell(tr, l.poNo + ' · ' + l.poLineNo);
                cell(tr, l.itemCd + ' · ' + (l.itemNm || ''));
                cell(tr, hb.t('js.portalSrm.recv', l.recvQty), 'sp-num');
                cell(tr, l.qty + ' ' + l.unitCd, 'sp-num');
                cell(tr, l.supplierLotNo);
                cell(tr, l.mfgDate);
                cell(tr, l.expiryDate);
                cell(tr, l.packCnt === null || l.packCnt === undefined ? '' : String(l.packCnt), 'sp-num');
                cell(tr, '');
                lineBody.appendChild(tr);
            });
            return Promise.resolve();
        }
        var plant = plantSel.value;
        if (!plant) {
            var tr = hb.el('tr');
            cell(tr, hb.t('js.portalSrm.pickPlantFirst')).colSpan = 9;
            lineBody.appendChild(tr);
            return Promise.resolve();
        }
        var q = new URLSearchParams({plantCd: plant});
        if (current) { q.set('asnId', String(current.asn.asnId)); }
        return hb.api(API + '/asn-po-lines?' + q.toString()).then(function (res) {
            var opts = res.data || [];
            var used = (current ? current.lines : []).slice();
            opts.forEach(function (o) {
                var mine = used.filter(function (l) { return l.poLineId === o.poLineId; });
                if (!mine.length) { addLine(o, null, null); }
                mine.forEach(function (l) { addLine(o, l, null); used.splice(used.indexOf(l), 1); });
            });
            used.forEach(function (l) {      // 그 사이 닫힌 발주 행 — 보여 주되 서버가 저장 때 판정
                addLine({poLineId: l.poLineId, poNo: l.poNo, lineNo: l.poLineNo, itemCd: l.itemCd, itemNm: l.itemNm, unitCd: l.unitCd,
                    notifiableQty: '-'}, l, null);
            });
            if (!lineBody.children.length) {
                var tr = hb.el('tr');
                cell(tr, hb.t('js.portalSrm.noOpenLines')).colSpan = 9;
                lineBody.appendChild(tr);
            }
        }, function (err) { showMsg(document.getElementById('asnMsg'), err); });
    }

    function input(value, label, opts) {
        var i = hb.el('input', opts && opts.wide ? 'sp-wide' : null);
        if (opts && opts.type) { i.type = opts.type; }
        if (opts && opts.mode) { i.inputMode = opts.mode; }
        if (opts && opts.max) { i.maxLength = opts.max; }
        i.value = value === null || value === undefined ? '' : String(value);
        i.setAttribute('aria-label', label);
        return i;
    }

    /** 한 줄 = 발주 행 하나 × 공급사 LOT 하나 — 같은 발주 행을 LOT 별로 나누려면 줄 추가 */
    function addLine(o, l, after) {
        var tr = hb.el('tr');
        tr.setAttribute('data-line', String(o.poLineId));
        var label = o.poNo + ' · ' + o.lineNo;
        tr.setAttribute('data-label', label);
        cell(tr, label);
        cell(tr, o.itemCd + ' · ' + (o.itemNm || ''));
        cell(tr, o.notifiableQty + ' ' + (o.unitCd || ''), 'sp-num');
        cell(tr, null).appendChild(input(l ? l.qty : '', hb.t('js.portalSrm.qtyOf', label), {mode: 'decimal', max: 24}));
        cell(tr, null).appendChild(input(l ? l.supplierLotNo : '', hb.t('js.portalSrm.lotOf', label), {max: 60, wide: true}));
        cell(tr, null).appendChild(input(l ? l.mfgDate : '', hb.t('js.portalSrm.mfgOf', label), {type: 'date', wide: true}));
        cell(tr, null).appendChild(input(l ? l.expiryDate : '', hb.t('js.portalSrm.expiryOf', label), {type: 'date', wide: true}));
        cell(tr, null).appendChild(input(l && l.packCnt !== null && l.packCnt !== undefined ? l.packCnt : '', hb.t('js.portalSrm.packOf', label),
            {mode: 'numeric', max: 5}));
        var btn = hb.el('button', 'sp-secondary', hb.t('js.portalSrm.addLot'));
        btn.type = 'button';
        btn.addEventListener('click', function () { addLine(o, null, tr); });
        cell(tr, null).appendChild(btn);
        if (after) { after.insertAdjacentElement('afterend', tr); } else { lineBody.appendChild(tr); }
    }

    function body() {
        var lines = [];
        labels = [];
        lineBody.querySelectorAll('tr[data-line]').forEach(function (tr) {
            var ins = tr.querySelectorAll('input');
            if (!ins[0].value) { return; }                  // 수량 없는 줄은 빠진다
            labels.push(tr.getAttribute('data-label'));
            lines.push({poLineId: Number(tr.getAttribute('data-line')), qty: ins[0].value, supplierLotNo: ins[1].value || null,
                mfgDate: ins[2].value || null, expiryDate: ins[3].value || null, packCnt: ins[4].value || null});
        });
        return {plantCd: plantSel.value || null, shipDate: field('shipDate').value || null, etaDate: field('etaDate').value || null,
            supplierRef: field('supplierRef').value || null, carrierMemo: field('carrierMemo').value || null,
            remark: field('remark').value || null, rowVer: current ? current.asn.rowVer : null, lines: lines};
    }

    /** 저장 → 새로 읽은 ASN id(확정은 저장 뒤 그 행 버전으로) */
    function save() {
        var b = body();
        var req = current ? hb.api(API + '/asns/' + current.asn.asnId, {method: 'PUT', body: b}).then(function () { return current.asn.asnId; })
            : hb.api(API + '/asns', {method: 'POST', body: b}).then(function (res) { return res.data; });
        return req;
    }

    form.addEventListener('submit', function (e) {
        e.preventDefault();
        hb.busy(document.getElementById('saveBtn'), save).then(function (id) {
            if (!id) { return; }
            return load(id).then(function () { ok(hb.t('js.portalSrm.asnSaved')); });
        }, function (err) { showMsg(document.getElementById('asnMsg'), err); });
    });

    document.getElementById('sendBtn').addEventListener('click', function () {
        hb.busy(/** @type {HTMLButtonElement} */ (document.getElementById('sendBtn')), function () {
            return save().then(function (id) {
                return hb.api(API + '/asns/' + id).then(function (res) {
                    return hb.api(API + '/asns/' + id + '/send', {method: 'POST', body: {rowVer: res.data.asn.rowVer}}).then(function () { return id; },
                        function (err) { current = res.data; throw err; });
                });
            });
        }).then(function (id) {
            if (!id) { return; }
            return load(id).then(function () { ok(hb.t('js.portalSrm.asnSent')); });
        }, function (err) { showMsg(document.getElementById('asnMsg'), err); });
    });

    document.getElementById('cancelBtn').addEventListener('click', function () {
        var id = current.asn.asnId;
        hb.busy(/** @type {HTMLButtonElement} */ (document.getElementById('cancelBtn')), function () {
            return hb.api(API + '/asns/' + id + '/cancel', {method: 'POST',
                body: {rowVer: current.asn.rowVer, reason: document.getElementById('cancelReason').value}});
        }).then(function (res) {
            if (!res) { return; }
            return load(id).then(function () { ok(hb.t('js.portalSrm.asnCancelled')); });
        }, function (err) { showMsg(document.getElementById('asnMsg'), err); });
    });

    plantSel.addEventListener('change', function () { renderLines(); });
    document.getElementById('newBtn').addEventListener('click', function () { newForm(); });
    search.addEventListener('submit', function (e) { e.preventDefault(); detail.hidden = true; load(null); });

    hb.loadMessages().then(function () { return hb.loadSession(); }).then(function () {
        var sel = document.getElementById('statusSel');
        STATUSES.forEach(function (s) {
            var o = hb.el('option', null, name('asnStatus', s));
            o.value = s;
            sel.appendChild(o);
        });
        var today = hb.dates.todayIn(hb.session().timeZone);
        search.elements.namedItem('from').value = hb.dates.add(today, -30);
        search.elements.namedItem('to').value = hb.dates.add(today, 30);
        return load(null);
    });
})();
