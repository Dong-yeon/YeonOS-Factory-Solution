/*
 * 납품서 인쇄 (srm-asn-print) — SRM 2부 R3a (docs/31 4.11). ?asnId= → /api/srm/asns/{id} 로 그린다(서버 데이터는 textContent 로만).
 * 스캔 키 = ASN:{asn_no}(WMS 입고 화면이 읽는 모양).
 */
(function () {
    'use strict';

    var asnId = new URLSearchParams(window.location.search).get('asnId');
    var msg = document.getElementById('msg');
    var bar = document.getElementById('printBar');

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

    hb.loadMessages().then(function () {
        if (!asnId || !/^\d{1,18}$/.test(asnId)) {
            msg.textContent = hb.t('js.srm.asnPrint.noAsn');
            return null;
        }
        return hb.api('/api/srm/asns/' + asnId).then(function (res) {
            var a = res.data.asn;
            document.getElementById('docTitle').textContent = hb.t('js.srm.asnPrint.title', a.asnNo);
            document.title = a.asnNo;
            document.getElementById('scanKey').textContent = 'ASN:' + a.asnNo;
            info(hb.t('js.srm.asnPrint.supplier'), a.custCd + ' · ' + a.custNm);
            info(hb.t('js.srm.asnPrint.plant'), a.plantCd + (a.plantNm ? ' · ' + a.plantNm : ''));
            info(hb.t('js.srm.asnPrint.shipDate'), a.shipDate);
            info(hb.t('js.srm.asnPrint.etaDate'), a.etaDate);
            info(hb.t('js.srm.asnPrint.supplierRef'), a.supplierRef);
            info(hb.t('js.srm.asnPrint.carrier'), a.carrierMemo);
            var tbody = document.querySelector('#docLines tbody');
            res.data.lines.forEach(function (l) {
                var tr = hb.el('tr');
                [String(l.lineNo), l.poNo + ' · ' + l.poLineNo, l.itemCd + ' · ' + (l.itemNm || ''), l.qty + ' ' + l.unitCd,
                    l.supplierLotNo || '', l.expiryDate || '', l.packCnt === null || l.packCnt === undefined ? '' : String(l.packCnt)]
                    .forEach(function (x) { tr.appendChild(hb.el('td', null, x)); });
                tbody.appendChild(tr);
            });
        });
    }).then(null, function (err) { msg.textContent = err && err.message; });
})();
