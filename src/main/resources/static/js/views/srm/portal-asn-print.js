/*
 * 협력사 포털 — 납품서 인쇄 (docs/42 7장 3). ?asnId= → /api/portal/srm/asns/{id}(내 ASN 만) 로 그린다. 서버 글자는 textContent 로만.
 * 스캔 키 = ASN:{asn_no}(WMS 입고 화면이 읽는 모양). 인쇄 단추 줄은 인쇄 때 숨긴다(.sp-noprint).
 */
(function () {
    'use strict';

    var asnId = new URLSearchParams(window.location.search).get('asnId');
    var msg = document.getElementById('msg');

    function info(label, value) {
        var dl = document.getElementById('docInfo');
        dl.appendChild(hb.el('dt', null, label));
        dl.appendChild(hb.el('dd', null, value || ''));
    }

    document.getElementById('printBtn').addEventListener('click', function () { window.print(); });

    hb.loadMessages().then(function () {
        if (!asnId || !/^\d{1,18}$/.test(asnId)) {
            msg.textContent = hb.t('js.portalSrm.noAsn');
            return null;
        }
        return hb.api('/api/portal/srm/asns/' + asnId).then(function (res) {
            var a = res.data.asn;
            document.getElementById('docTitle').textContent = hb.t('js.portalSrm.printTitle', a.asnNo);
            document.title = a.asnNo;
            document.getElementById('scanKey').textContent = 'ASN:' + a.asnNo;
            info(hb.t('js.portalSrm.supplier'), document.body.getAttribute('data-supplier') || '');
            info(hb.t('js.portalSrm.plant'), a.plantCd + (a.plantNm ? ' · ' + a.plantNm : ''));
            info(hb.t('js.portalSrm.shipDate'), a.shipDate);
            info(hb.t('js.portalSrm.etaDate'), a.etaDate);
            info(hb.t('js.portalSrm.supplierRef'), a.supplierRef);
            info(hb.t('js.portalSrm.carrier'), a.carrierMemo);
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
