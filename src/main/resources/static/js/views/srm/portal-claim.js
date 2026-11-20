/*
 * 협력사 포털 — 클레임 회신 (docs/31 4.13.1 P-4 · 4.15). 목록(보낸 날 기간) → 상세(요구·근거 NCR·회신) → 보냄 상태면 원인·대책 회신.
 * 판정·검사는 서버(ClaimService.answerFromPortal — 보냄만, 글자 수 2000). 서버 글자는 textContent 로만. 상태 이름은 문구 키(포털은 공통코드 API 밖).
 */
(function () {
    'use strict';

    var API = '/api/portal/srm/claims';
    var STATUSES = ['SENT', 'ANSWERED', 'CLOSED', 'CANCELLED'];
    var search = document.getElementById('search');
    var tbody = document.querySelector('#claimTable tbody');
    var detail = document.getElementById('detail');
    var form = document.getElementById('answerForm');
    var current = null;     // ClaimDetail

    function field(n) { return form.querySelector('[data-field="' + n + '"]'); }
    function name(group, code) {
        if (!code) { return ''; }
        var key = 'js.portalSrm.' + group + '.' + code;
        var t = hb.t(key);
        return t === key ? code : t;
    }
    function cell(tr, text, cls) { var td = hb.el('td', cls || null, text === null || text === undefined ? '' : text); tr.appendChild(td); return td; }
    function info(dl, label, value) {
        if (value === null || value === undefined || value === '') { return; }
        dl.appendChild(hb.el('dt', null, label));
        dl.appendChild(hb.el('dd', 'sp-pre', value));
    }

    function showMsg(box, err) {
        box.textContent = '';
        hb.showFieldErrors(form.querySelector('.sp-fields'), err && err.errors || []);
        if (!err) { box.hidden = true; return; }
        [err.message].concat(err.details || []).forEach(function (l) { box.appendChild(hb.el('li', null, l)); });
        box.hidden = false;
    }

    function load(selectId) {
        var f = new FormData(search);
        var q = new URLSearchParams();
        ['from', 'to', 'status'].forEach(function (k) { if (f.get(k)) { q.set(k, String(f.get(k))); } });
        return hb.api(API + '?' + q.toString()).then(function (res) {
            document.getElementById('listMsg').hidden = true;
            tbody.textContent = '';
            (res.data || []).forEach(function (c) {
                var tr = hb.el('tr', 'sp-pick');
                tr.tabIndex = 0;
                tr.setAttribute('data-id', String(c.claimId));
                cell(tr, c.claimNo);
                cell(tr, c.title);
                cell(tr, hb.fmt.date(c.sentAt));
                cell(tr, c.replyDue);
                cell(tr, name('claimStatus', c.status));
                tr.addEventListener('click', function () { open(c.claimId); });
                tr.addEventListener('keydown', function (e) { if (e.key === 'Enter') { open(c.claimId); } });
                tbody.appendChild(tr);
            });
            if (!res.data || !res.data.length) {
                var tr = hb.el('tr');
                cell(tr, hb.t('js.portalSrm.empty')).colSpan = 5;
                tbody.appendChild(tr);
            }
            return selectId ? open(selectId) : null;
        }, function (err) {
            var m = document.getElementById('listMsg');
            m.textContent = err.message;
            m.hidden = false;
        });
    }

    function open(claimId) {
        return hb.api(API + '/' + claimId).then(function (res) {
            current = res.data;
            tbody.querySelectorAll('tr').forEach(function (tr) {
                tr.setAttribute('aria-selected', tr.getAttribute('data-id') === String(claimId) ? 'true' : 'false');
            });
            render();
            detail.hidden = false;
            detail.scrollIntoView({block: 'start'});
        });
    }

    function render() {
        var c = current.claim;
        document.getElementById('detailTitle').textContent = hb.t('js.portalSrm.claimTitle', c.claimNo, c.title);
        document.getElementById('detailInfo').textContent = [name('claimStatus', c.status), hb.t('js.portalSrm.sentAt', hb.fmt.datetime(c.sentAt)),
            c.cancelReason ? hb.t('js.portalSrm.cancelled', c.cancelReason) : ''].filter(Boolean).join(' · ');
        var dl = document.getElementById('claimInfo');
        dl.textContent = '';
        info(dl, hb.t('js.portalSrm.claimAction'), name('claimAction', c.actionCd));
        info(dl, hb.t('js.portalSrm.claimRequest'), c.requestText);
        info(dl, hb.t('js.portalSrm.claimDue'), c.replyDue);
        info(dl, hb.t('js.portalSrm.claimCharge'), c.chargeAmt ? c.chargeAmt + ' ' + (c.currencyCd || '') : null);
        var nb = document.querySelector('#ncrTable tbody');
        nb.textContent = '';
        current.ncrs.forEach(function (n) {
            var tr = hb.el('tr');
            cell(tr, n.ncrNo);
            cell(tr, n.itemCd + ' · ' + (n.itemNm || ''));
            cell(tr, n.ncQty + ' ' + n.unitCd, 'sp-num');
            nb.appendChild(tr);
        });
        var ad = document.getElementById('answerInfo');
        ad.textContent = '';
        document.getElementById('answerShown').hidden = !c.answerCause;
        if (c.answerCause) {
            info(ad, hb.t('js.portalSrm.answerCause'), c.answerCause);
            info(ad, hb.t('js.portalSrm.answerAction'), c.answerAction);
            info(ad, hb.t('js.portalSrm.answeredOn'), c.answeredOn + ' · ' + name('answerSource', c.answerSource || 'MANUAL'));
        }
        form.reset();
        showMsg(document.getElementById('answerMsg'), null);
        document.getElementById('answerOk').hidden = true;
        document.getElementById('answerBox').hidden = c.status !== 'SENT';
    }

    form.addEventListener('submit', function (e) {
        e.preventDefault();
        var id = current.claim.claimId;
        var body = {rowVer: current.claim.rowVer, answerCause: field('answerCause').value, answerAction: field('answerAction').value};
        hb.busy(/** @type {HTMLButtonElement} */ (document.getElementById('answerBtn')), function () {
            return hb.api(API + '/' + id + '/answer', {method: 'POST', body: body});
        }).then(function (res) {
            if (!res) { return; }
            return load(id).then(function () { document.getElementById('answerOk').hidden = false; });
        }, function (err) { showMsg(document.getElementById('answerMsg'), err); });
    });

    search.addEventListener('submit', function (e) { e.preventDefault(); detail.hidden = true; load(null); });

    hb.loadMessages().then(function () { return hb.loadSession(); }).then(function () {
        var sel = document.getElementById('statusSel');
        STATUSES.forEach(function (s) {
            var o = hb.el('option', null, name('claimStatus', s));
            o.value = s;
            sel.appendChild(o);
        });
        var today = hb.dates.todayIn(hb.session().timeZone);
        search.elements.namedItem('from').value = hb.dates.add(today, -90);
        search.elements.namedItem('to').value = today;
        return load(null);
    });
})();
