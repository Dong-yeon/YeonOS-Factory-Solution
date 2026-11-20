/*
 * 발주서 인쇄 (srm-po-print) — SRM 1부 S4 (docs/31 D7). ?poId=&rev= → /api/srm/pos/{id}/document 로 그린다(서버 데이터는 textContent 로만).
 * 인쇄 버튼은 도구 막대를 숨기고 window.print(), 인쇄가 끝나면 다시 보인다.
 */
(function () {
    'use strict';

    var params = new URLSearchParams(window.location.search);
    var poId = params.get('poId');
    var rev = params.get('rev');
    var msg = document.getElementById('msg');
    var bar = document.getElementById('printBar');

    function q() { return rev && /^\d{1,3}$/.test(rev) ? '?rev=' + rev : ''; }

    function info(label, value) {
        var dl = document.getElementById('docInfo');
        dl.appendChild(hb.el('dt', null, label));
        dl.appendChild(hb.el('dd', null, value || ''));
    }

    document.getElementById('printBtn').addEventListener('click', function () {
        bar.hidden = true;
        window.print();
    });
    window.addEventListener('afterprint', function () { bar.hidden = false; });
    document.getElementById('excelBtn').addEventListener('click', function () {
        if (poId) { window.location.href = '/api/srm/pos/' + encodeURIComponent(poId) + '/excel' + q(); }
    });

    hb.loadMessages().then(function () {
        if (!poId || !/^\d{1,18}$/.test(poId)) {
            msg.textContent = hb.t('js.srm.print.noPo');
            return null;
        }
        return hb.api('/api/srm/pos/' + poId + '/document' + q()).then(function (res) {
            var d = res.data;
            var po = d.snapshot.po;
            document.getElementById('docTitle').textContent = hb.t('js.srm.print.title', po.poNo, d.rev);
            document.title = po.poNo;
            document.getElementById('draftNote').hidden = !d.draft;
            info(hb.t('js.srm.print.buyer'), d.compNm);
            info(hb.t('js.srm.print.supplier'), po.custNm + ' (' + po.custCd + ')');
            info(hb.t('js.srm.print.poDate'), po.poDate);
            info(hb.t('js.srm.print.place'), po.plantCd + (po.whCd ? ' / ' + po.whCd : ''));
            info(hb.t('js.srm.print.currency'), po.currencyCd);
            info(hb.t('js.srm.print.payTerms'), po.payTermsCd);
            if (po.remark) { info(hb.t('js.srm.print.remark'), po.remark); }
            var body = document.querySelector('#docLines tbody');
            d.snapshot.lines.forEach(function (l) {
                var tr = hb.el('tr');
                [String(l.lineNo), l.itemCd + ' · ' + l.itemNm, l.supplierItemCd || '', l.qty + ' ' + l.unitCd, l.unitPrice, l.amt, l.dueDate]
                    .forEach(function (x) { tr.appendChild(hb.el('td', null, x)); });
                body.appendChild(tr);
            });
            document.getElementById('docTotal').textContent = po.totalAmt + ' ' + po.currencyCd;
        }, function (err) { msg.textContent = err.message; });
    });
})();
