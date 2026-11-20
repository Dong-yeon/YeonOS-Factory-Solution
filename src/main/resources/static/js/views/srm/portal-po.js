/*
 * 협력사 포털 — 발주·납기 회신 (docs/42 7장 3, docs/31 4.13 P-1).
 * 목록(발주일 기간) → 행 고르면 상세(행·유효 약속·회신 이력) → 회신 입력(행마다 약속 납기·수량, 나눠 회신 = 줄 추가 / 거절 + 사유).
 * 판정·검사는 서버(대리 입력과 같은 AckService). 서버 글자는 textContent 로만(hb.el). 공통코드 API 는 포털 밖이라 상태 이름은 문구 키로.
 */
(function () {
    'use strict';

    var API = '/api/portal/srm/pos';
    var search = document.getElementById('search');
    var tbody = document.querySelector('#poTable tbody');
    var detail = document.getElementById('detail');
    var ackForm = document.getElementById('ackForm');
    var promiseBody = document.querySelector('#promiseTable tbody');
    var rejectChk = document.getElementById('rejectChk');
    var current = null;     // PoDetail

    function field(n) { return ackForm.querySelector('[data-field="' + n + '"]'); }

    function status(group, code) {
        if (!code) { return ''; }
        var key = 'js.portalSrm.' + group + '.' + code;
        var t = hb.t(key);
        return t === key ? code : t;
    }

    function showMsg(box, err) {
        box.textContent = '';
        if (box.id === 'ackMsg') {      // 머리 칸 오류(행 키 없음)는 그 칸 옆에
            hb.showFieldErrors(ackForm.querySelector('.sp-fields'), (err && err.errors || []).filter(function (e) { return e.rowKey === undefined || e.rowKey === null; }));
        }
        if (!err) { box.hidden = true; return; }
        var lines = [err.message];
        (err.errors || []).forEach(function (e) {
            lines.push(e.rowKey !== undefined && e.rowKey !== null ? hb.t('js.portalSrm.rowError', Number(e.rowKey) + 1, e.message) : e.message);
        });
        (err.details || []).forEach(function (d) { lines.push(d); });
        lines.forEach(function (l) {
            box.appendChild(box.tagName === 'UL' ? hb.el('li', null, l) : document.createTextNode(l + ' '));
        });
        box.hidden = false;
    }

    function cell(tr, text, cls) { var td = hb.el('td', cls || null, text === null || text === undefined ? '' : text); tr.appendChild(td); return td; }

    // ---------------------------------------------------------------- 목록

    function load(selectId) {
        var f = new FormData(search);
        var q = new URLSearchParams();
        ['from', 'to', 'state', 'keyword'].forEach(function (k) { if (f.get(k)) { q.set(k, String(f.get(k))); } });
        return hb.api(API + '?' + q.toString()).then(function (res) {
            showMsg(document.getElementById('listMsg'), null);
            tbody.textContent = '';
            (res.data || []).forEach(function (p) {
                var tr = hb.el('tr', 'sp-pick');
                tr.tabIndex = 0;
                tr.setAttribute('data-id', String(p.poId));
                cell(tr, p.poNo);
                cell(tr, String(p.rev), 'sp-num');
                cell(tr, p.poDate);
                cell(tr, p.plantNm || p.plantCd);
                cell(tr, status('poStatus', p.status));
                cell(tr, p.ackStatus ? status('ackStatus', p.ackStatus) : hb.t('js.portalSrm.noAck'));
                cell(tr, p.ackDueDate);
                cell(tr, (p.totalAmt || '') + ' ' + (p.currencyCd || ''), 'sp-num');
                tr.addEventListener('click', function () { open(p.poId); });
                tr.addEventListener('keydown', function (e) { if (e.key === 'Enter') { open(p.poId); } });
                tbody.appendChild(tr);
            });
            if (!res.data || !res.data.length) {
                var tr = hb.el('tr');
                var td = cell(tr, hb.t('js.portalSrm.empty'));
                td.colSpan = 8;
                tbody.appendChild(tr);
            }
            if (selectId) { return open(selectId); }
            return null;
        }, function (err) { showMsg(document.getElementById('listMsg'), err); });
    }

    // ---------------------------------------------------------------- 상세

    function open(poId) {
        return hb.api(API + '/' + poId).then(function (res) {
            current = res.data;
            tbody.querySelectorAll('tr').forEach(function (tr) {
                tr.setAttribute('aria-selected', tr.getAttribute('data-id') === String(poId) ? 'true' : 'false');
            });
            render();
            detail.hidden = false;
            detail.scrollIntoView({block: 'start'});
        }, function (err) { showMsg(document.getElementById('listMsg'), err); });
    }

    function render() {
        var po = current.po;
        document.getElementById('detailTitle').textContent = hb.t('js.portalSrm.poTitle', po.poNo, po.rev);
        document.getElementById('detailInfo').textContent = [po.poDate, po.plantNm || po.plantCd, status('poStatus', po.status),
            hb.t('js.portalSrm.ackDue', po.ackDueDate || '-')].join(' · ');
        var lb = document.querySelector('#lineTable tbody');
        lb.textContent = '';
        current.lines.forEach(function (l) {
            var tr = hb.el('tr');
            cell(tr, String(l.lineNo), 'sp-num');
            cell(tr, l.itemCd + ' · ' + (l.itemNm || '') + (l.supplierItemCd ? ' (' + l.supplierItemCd + ')' : ''));
            cell(tr, l.qty + ' ' + l.unitCd, 'sp-num');
            cell(tr, l.openQty, 'sp-num');
            cell(tr, l.unitPrice, 'sp-num');
            cell(tr, l.amt, 'sp-num');
            cell(tr, l.dueDate);
            cell(tr, l.promisedQty ? hb.t('js.portalSrm.promised', l.promisedQty, l.promisedFirst,
                l.promisedLast && l.promisedLast !== l.promisedFirst ? '~' + l.promisedLast : '') : '');
            lb.appendChild(tr);
        });
        var hist = document.getElementById('ackHistory');
        hist.textContent = '';
        current.acks.forEach(function (a) {
            var parts = ['#' + a.ackSeq, hb.t('js.portalSrm.rev', a.rev), status('ackStatus', a.ackStatus), a.ackOn,
                status('source', a.source)];
            if (a.ackByNm) { parts.push(a.ackByNm); }
            if (a.rejectReason) { parts.push(hb.t('js.portalSrm.rejectReason', a.rejectReason)); }
            if (a.valid) { parts.push(hb.t('js.portalSrm.valid')); }
            var li = hb.el('li', null, parts.join(' · '));
            current.ackLines.filter(function (x) { return x.ackId === a.ackId; }).forEach(function (x) {
                li.appendChild(hb.el('div', 'pt-muted', hb.t('js.portalSrm.ackLine', x.lineNo, x.itemCd, x.promisedDate, x.promisedQty)));
            });
            hist.appendChild(li);
        });
        if (!current.acks.length) { hist.appendChild(hb.el('li', 'pt-muted', hb.t('js.portalSrm.noAckYet'))); }
        document.getElementById('ackBox').hidden = !po.ackable;
        document.getElementById('ackClosed').hidden = po.ackable;
        resetForm();
    }

    function resetForm() {
        ackForm.reset();
        rejectChk.checked = false;
        toggleReject();
        showMsg(document.getElementById('ackMsg'), null);
        document.getElementById('ackOk').hidden = true;
        promiseBody.textContent = '';
        current.lines.filter(function (l) { return l.lineStatus === 'OPEN' && Number(l.openQty) > 0; }).forEach(function (l) {
            addPromise(l, l.dueDate, l.openQty, null);
        });
    }

    /** 약속 줄 하나 — 같은 행을 여러 번 넣으면 나눠 회신 */
    function addPromise(l, date, qty, after) {
        var tr = hb.el('tr');
        tr.setAttribute('data-line', String(l.poLineId));
        cell(tr, String(l.lineNo), 'sp-num');
        cell(tr, l.itemCd + ' (' + l.openQty + ' ' + l.unitCd + ')');
        var d = hb.el('input');
        d.type = 'date';
        d.value = date || '';
        d.className = 'sp-wide';
        d.setAttribute('aria-label', hb.t('js.portalSrm.promisedDate', l.lineNo));
        cell(tr, null).appendChild(d);
        var q = hb.el('input');
        q.inputMode = 'decimal';
        q.maxLength = 24;
        q.value = qty || '';
        q.setAttribute('aria-label', hb.t('js.portalSrm.promisedQty', l.lineNo));
        cell(tr, null).appendChild(q);
        var r = hb.el('input');
        r.maxLength = 500;
        r.setAttribute('aria-label', hb.t('js.portalSrm.lineRemark', l.lineNo));
        cell(tr, null).appendChild(r);
        var btn = hb.el('button', 'sp-secondary', hb.t('js.portalSrm.split'));
        btn.type = 'button';
        btn.addEventListener('click', function () { addPromise(l, '', '', tr); });
        cell(tr, null).appendChild(btn);
        if (after) { after.insertAdjacentElement('afterend', tr); } else { promiseBody.appendChild(tr); }
    }

    function toggleReject() {
        document.getElementById('reasonBox').hidden = !rejectChk.checked;
        document.getElementById('promiseBox').hidden = rejectChk.checked;
    }
    rejectChk.addEventListener('change', toggleReject);

    ackForm.addEventListener('submit', function (e) {
        e.preventDefault();
        var reject = rejectChk.checked;
        var lines = [];
        if (!reject) {
            promiseBody.querySelectorAll('tr').forEach(function (tr) {
                var ins = tr.querySelectorAll('input');
                if (!ins[0].value && !ins[1].value) { return; }      // 비운 줄은 보내지 않는다
                lines.push({poLineId: Number(tr.getAttribute('data-line')), promisedDate: ins[0].value, promisedQty: ins[1].value,
                    remark: ins[2].value || null});
            });
        }
        var body = {rev: current.po.rev, ackOn: field('ackOn').value || null, ackByNm: field('ackByNm').value || null, reject: reject,
            rejectReason: reject ? field('rejectReason').value : null, remark: field('remark').value || null, lines: reject ? [] : lines};
        var btn = document.getElementById('ackBtn');
        hb.busy(btn, function () { return hb.api(API + '/' + current.po.poId + '/ack', {method: 'POST', body: body}); }).then(function (res) {
            if (!res) { return; }
            var id = current.po.poId;
            return load(id).then(function () {
                var ok = document.getElementById('ackOk');
                ok.textContent = hb.t('js.portalSrm.ackSaved', res.data.ackSeq, status('ackStatus', res.data.ackStatus));
                (res.data.warnings || []).forEach(function (w) {
                    ok.appendChild(hb.el('div', null, hb.t(w.kind === 'LATE' ? 'js.portalSrm.warnLate' : 'js.portalSrm.warnShort', w.lineNo)));
                });
                ok.hidden = false;
            });
        }, function (err) { showMsg(document.getElementById('ackMsg'), err); });
    });

    search.addEventListener('submit', function (e) { e.preventDefault(); detail.hidden = true; load(null); });

    hb.loadMessages().then(function () { return hb.loadSession(); }).then(function () {
        var today = hb.dates.todayIn(hb.session().timeZone);
        search.elements.namedItem('from').value = hb.dates.add(today, -90);
        search.elements.namedItem('to').value = today;
        return load(null);
    });
})();
