/*
 * 협력사 정보 (srm-supplier) — SRM 1부 S1 (docs/31 D2·D3).
 * 조회 패널 → 구매 거래처 표(등록 여부) → 상세: 거래처 기본정보(읽기) + SRM 칸(등록·수정)
 * + 인증서(갱신 = 새 행, 사용 중지, 파일) + 공급 품목(행 고르면 수정) + 단가(새 단가 = 새 행, 행 고르면 끝일·사용만)
 */
(function () {
    'use strict';

    var API = '/api/srm/suppliers';
    var PROG = 'srm-supplier';
    var ACTIONS = (document.body.getAttribute('data-actions') || '').split(',');
    var CAN_SAVE = ACTIONS.indexOf('SAVE') >= 0;
    var msg = document.getElementById('msg');
    var search = document.getElementById('search');
    var supForm = document.getElementById('supForm');
    var certForm = document.getElementById('certForm');
    var srcForm = document.getElementById('srcForm');
    var priceForm = document.getElementById('priceForm');
    var extra = document.getElementById('extra');
    var formMode = document.getElementById('formMode');
    var current = null, curSource = null, curPrice = null, curCert = null, sp;
    var nums = {}, sels = {};

    function notify(text, error) {
        msg.textContent = text || '';
        msg.classList.toggle('hb-msg-error', !!error);
    }

    function fail(form) {
        return function (err) {
            notify(err.errors && err.errors.length ? err.errors[0].message : err.message, true);
            if (form) { hb.showFieldErrors(form, err.errors); }
        };
    }

    function f(form, n) { return form.querySelector('[data-field="' + n + '"]'); }

    /** hb.number 값 — 해석 실패면 글자 그대로 보내 서버가 칸 오류로 돌려주게 */
    function num(form, n) {
        var el = f(form, n);
        var v = el.hbNumber ? el.hbNumber.value() : null;
        return v !== null ? v : (el.value ? el.value : null);
    }

    function setNum(form, n, v) {
        var el = f(form, n);
        if (el.hbNumber) { el.hbNumber.setValue(v === null || v === undefined ? null : String(v)); } else { el.value = v || ''; }
    }

    function emptyRow(body, cols, text) {
        var tr = hb.el('tr');
        var td = hb.el('td', 'hb-muted', text);
        td.colSpan = cols;
        tr.appendChild(td);
        body.appendChild(tr);
    }

    /** 상태 이름 — 키는 글자 그대로(I18nKeyGuardTest) */
    function certStateName(s) {
        return {VALID: hb.t('js.srm.cert.VALID'), DUE_SOON: hb.t('js.srm.cert.DUE_SOON'), EXPIRED: hb.t('js.srm.cert.EXPIRED'),
            NONE: hb.t('js.srm.cert.NONE'), OFF: hb.t('js.srm.cert.OFF')}[s] || s || '';
    }

    function priceStateName(s) {
        return {CURRENT: hb.t('js.srm.price.CURRENT'), FUTURE: hb.t('js.srm.price.FUTURE'), PAST: hb.t('js.srm.price.PAST'),
            OFF: hb.t('js.srm.price.OFF')}[s] || s;
    }

    var tradeNames = {}, certTypeNames = {}, grid;

    // ---------------------------------------------------------------- 목록

    /** 조회 조건(검색 패널) — grid 가 page·size·sort 를 붙인다 */
    function params() {
        var v = sp.values();
        var q = {};
        ['keyword', 'tradeStatus', 'registered'].forEach(function (k) { if (v[k]) { q[k] = v[k]; } });
        return q;
    }

    /** 행 → 상세(서버에서 다시 읽는다) */
    function openRow(id) {
        return hb.latest('detail', hb.api(API + '/' + id)).then(function (r) {
            show(r.data);
            hb.page.openDetail();
        }, fail());
    }

    /** 목록 다시 읽기 — selectId 가 이 쪽에 있으면 grid 가 골라 상세를 연다, 없어도(다른 쪽·조건 밖) 방금 등록·고친 것은 상세로 */
    function load(selectId) {
        return grid.reload(true, selectId || undefined).then(function () {
            var sel = grid.selected();
            if (selectId && (!sel || sel.custId !== selectId)) { return openRow(selectId); }
            if (!selectId) { show(null); }
        });
    }

    // ---------------------------------------------------------------- 상세

    function show(s) {
        current = s;
        hb.showFieldErrors(supForm, []);
        document.getElementById('custInfo').hidden = !s;
        supForm.hidden = !s;
        extra.hidden = !s || !s.registered;
        document.getElementById('qualityBox').hidden = !s || !s.registered;
        if (!s) {
            formMode.textContent = hb.t('js.srm.sup.select');
            hb.dirty.clear();
            return;
        }
        document.getElementById('infoCd').textContent = s.custCd;
        document.getElementById('infoNm').textContent = s.custNm + (s.custUseYn === 'Y' ? '' : ' (' + hb.t('js.yn.N') + ')');
        document.getElementById('infoBiz').textContent = s.bizNo || '';
        document.getElementById('infoTel').textContent = s.tel || '';
        formMode.textContent = s.registered ? hb.t('js.srm.sup.editing', s.custCd, s.custNm) : hb.t('js.srm.sup.registering', s.custCd);
        sels.tradeStatus.setValue(s.tradeStatus || 'ACTIVE');
        sels.currencyCd.setValue(s.currencyCd || 'KRW');
        sels.payTermsCd.setValue(s.payTermsCd || '');
        f(supForm, 'statusReason').value = s.statusReason || '';
        f(supForm, 'buyerLoginId').value = s.buyerLoginId || '';
        f(supForm, 'countryCd').value = s.countryCd || '';
        f(supForm, 'controlCountryCd').value = s.controlCountryCd || '';
        f(supForm, 'secAssessedOn').value = s.secAssessedOn || '';
        f(supForm, 'remark').value = s.remark || '';
        var saveBtn = document.getElementById('saveBtn');
        if (saveBtn) { saveBtn.textContent = s.registered ? hb.t('js.srm.sup.save') : hb.t('js.srm.sup.register'); }
        hb.dirty.clear();
        if (s.registered) {
            document.getElementById('certFiles').hidden = true;
            curCert = null;
            newSource();
            newPrice();
            loadQuality();
            loadCerts();
            loadSources();
            loadPrices();
        }
    }

    var saveBtn = document.getElementById('saveBtn');
    if (saveBtn) {
        saveBtn.addEventListener('click', function () {
            if (!current) { return; }
            var body = {custId: current.custId, tradeStatus: sels.tradeStatus.value(), statusReason: f(supForm, 'statusReason').value,
                currencyCd: sels.currencyCd.value(), payTermsCd: sels.payTermsCd.value(), buyerLoginId: f(supForm, 'buyerLoginId').value,
                countryCd: f(supForm, 'countryCd').value, controlCountryCd: f(supForm, 'controlCountryCd').value,
                secAssessedOn: f(supForm, 'secAssessedOn').value || null, remark: f(supForm, 'remark').value,
                rowVer: current.registered ? current.rowVer : null};
            var editing = current.registered;
            var id = current.custId;
            hb.busy(saveBtn, function () {
                return editing ? hb.api(API + '/' + id, {method: 'PUT', body: body}) : hb.api(API, {method: 'POST', body: body});
            }).then(function (res) {
                if (!res) { return; }
                return load(id).then(function () { notify(hb.t('js.common.saved')); });
            }, fail(supForm));
        });
    }

    // ---------------------------------------------------------------- 인증서

    /** 품질 요약 카드(최근 90일) — QMS 계약 값 + 열린 클레임·출고 대기 반품. 보기만 */
    function loadQuality() {
        var dl = document.getElementById('qualityList');
        var id = current.custId;
        dl.textContent = '';
        return hb.latest('quality', hb.api(API + '/' + id + '/quality')).then(function (res) {
            var q = res.data;
            var ppm = q.ppm !== null ? hb.t('js.srm.sup.quality.ppmVal', q.ppm, q.inspectedQty, q.unitCd)
                : hb.t(q.ppmNote === 'UNITS' ? 'js.srm.sup.quality.ppmUnits' : 'js.srm.sup.quality.ppmNone');
            [[hb.t('js.srm.sup.quality.period'), q.from + ' ~ ' + q.to],
                [hb.t('js.srm.sup.quality.ppm'), ppm],
                [hb.t('js.srm.sup.quality.insp'), hb.t('js.srm.sup.quality.inspVal', q.inspectionCnt, q.ngCnt)],
                [hb.t('js.srm.sup.quality.openNcr'), String(q.openNcrCnt) + (q.ncrTruncated ? ' ' + hb.t('js.srm.sup.quality.truncated') : '')],
                [hb.t('js.srm.sup.quality.openClaim'), String(q.openClaimCnt)],
                [hb.t('js.srm.sup.quality.returnsToShip'), String(q.returnsToShipCnt)]].forEach(function (kv) {
                dl.appendChild(hb.el('dt', null, kv[0]));
                dl.appendChild(hb.el('dd', null, kv[1]));
            });
        }, function (err) { if (!err.stale) { dl.appendChild(hb.el('dd', 'hb-msg-error', err.message)); } });
    }

    function loadCerts() {
        var body = document.querySelector('#certs tbody');
        var id = current.custId;
        return hb.latest('certs', hb.api(API + '/' + id + '/certs')).then(function (res) {
            body.textContent = '';
            if (!res.data.length) { emptyRow(body, 6, hb.t('js.srm.cert.empty')); }
            res.data.forEach(function (c) {
                var tr = hb.el('tr', c.current ? null : 'hb-tree-dim');
                // 지난 인증서(같은 유형의 새 것이 있음)는 판정 톤을 쓰지 않는다 — 지금 판단할 대상이 아니다
                var old = !c.current && c.useYn === 'Y';
                srmUi.cells(tr, [certTypeNames[c.certTypeCd] || c.certTypeCd, c.certNo || '', c.issuedOn, c.expiresOn || hb.t('js.srm.cert.noExpiry'),
                    srmUi.withBadge(null, srmUi.badge('cert', old ? 'OFF' : c.state, certStateName(c.state)), old ? hb.t('js.srm.cert.old') : null)]);
                var act = hb.el('td');
                var fileBtn = hb.el('button', 'hb-btn-ghost', hb.t('js.srm.cert.files'));
                fileBtn.type = 'button';
                fileBtn.addEventListener('click', function () { showCertFiles(c); });
                act.appendChild(fileBtn);
                if (CAN_SAVE && c.useYn === 'Y') {
                    var off = hb.el('button', 'hb-btn-danger-outline', hb.t('js.srm.cert.disable'));
                    off.type = 'button';
                    off.addEventListener('click', function () {
                        srmUi.ask('js.srm.dlg.certDisable', hb.t('js.srm.cert.confirmDisable'), 'js.srm.dlg.certDisableOk', true).then(function (yes) {
                            if (!yes) { return; }
                            hb.busy(off, function () {
                                return hb.api(API + '/' + id + '/certs/' + c.certId, {method: 'PUT', body: {certNo: c.certNo,
                                    issuerNm: c.issuerNm, expiresOn: c.expiresOn, useYn: 'N', remark: c.remark, rowVer: c.rowVer}});
                            }).then(function (r) {
                                if (!r) { return; }
                                notify(hb.t('js.common.saved'));
                                return load(id);
                            }, fail());
                        });
                    });
                    act.appendChild(off);
                }
                tr.appendChild(act);
                body.appendChild(tr);
            });
        }, fail());
    }

    function showCertFiles(c) {
        curCert = c;
        var box = document.getElementById('certFiles');
        box.hidden = false;
        document.getElementById('certFilesTitle').textContent = hb.t('js.srm.cert.filesOf', certTypeNames[c.certTypeCd] || c.certTypeCd,
            c.issuedOn);
        var ul = document.getElementById('certFileList');
        hb.latest('certFiles', hb.api('/api/platform/files?targetType=' + PROG + '&targetId=cert-' + c.certId)).then(function (res) {
            ul.textContent = '';
            var list = res.list || [];
            if (!list.length) { ul.appendChild(hb.el('li', 'hb-muted', hb.t('js.srm.cert.noFiles'))); }
            list.forEach(function (file) {
                var li = hb.el('li');
                var a = hb.el('a', null, file.origName);
                a.href = '/api/platform/files/' + file.fileId;
                li.appendChild(a);
                ul.appendChild(li);
            });
        }, fail());
    }

    var certFileInput = document.getElementById('certFileInput');
    if (certFileInput) {
        certFileInput.addEventListener('change', function () {
            if (!certFileInput.files.length || !curCert) { return; }
            var fd = new FormData();
            fd.append('targetType', PROG);
            fd.append('targetId', 'cert-' + curCert.certId);
            fd.append('file', certFileInput.files[0]);
            hb.upload('/api/platform/files', fd).then(function () {
                certFileInput.value = '';
                notify(hb.t('js.common.saved'));
                showCertFiles(curCert);
            }, function (err) {
                certFileInput.value = '';
                fail()(err);
            });
        });
    }

    var addCertBtn = document.getElementById('addCertBtn');
    if (addCertBtn) {
        addCertBtn.addEventListener('click', function () {
            hb.showFieldErrors(certForm, []);
            var body = {certTypeCd: sels.certTypeCd.value(), certNo: f(certForm, 'certNo').value, issuerNm: f(certForm, 'issuerNm').value,
                issuedOn: f(certForm, 'issuedOn').value || null, expiresOn: f(certForm, 'expiresOn').value || null};
            var id = current.custId;
            hb.busy(addCertBtn, function () {
                return hb.api(API + '/' + id + '/certs', {method: 'POST', body: body});
            }).then(function (res) {
                if (!res) { return; }
                certForm.reset();
                sels.certTypeCd.reset();
                return load(id).then(function () { notify(hb.t('js.common.saved')); });
            }, fail(certForm));
        });
    }

    // ---------------------------------------------------------------- 공급 품목

    function loadSources() {
        var body = document.querySelector('#sources tbody');
        return hb.latest('sources', hb.api(API + '/' + current.custId + '/sources')).then(function (res) {
            body.textContent = '';
            if (!res.data.length) { emptyRow(body, 6, hb.t('js.srm.src.empty')); }
            res.data.forEach(function (s) {
                var tr = hb.el('tr', s.useYn === 'Y' ? 'hb-row-pick' : 'hb-row-pick hb-tree-dim');
                tr.tabIndex = 0;
                tr.title = s.itemNm;
                [s.itemCd, s.supplierItemCd || '', String(s.leadDays), s.moq ? s.moq + ' ' + s.unitCd : '',
                    String(s.priority), s.useYn === 'Y' ? hb.t('js.yn.Y') : hb.t('js.yn.N')]
                    .forEach(function (v) { tr.appendChild(hb.el('td', null, v)); });
                function pick() { editSource(s); }
                tr.addEventListener('click', pick);
                tr.addEventListener('keydown', function (e) { if (e.key === 'Enter') { pick(); } });
                body.appendChild(tr);
            });
        }, fail());
    }

    function editSource(s) {
        curSource = s;
        hb.showFieldErrors(srcForm, []);
        f(srcForm, 'itemCd').value = s ? s.itemCd : '';
        f(srcForm, 'itemCd').readOnly = !!s || !CAN_SAVE;
        f(srcForm, 'supplierItemCd').value = s ? s.supplierItemCd || '' : '';
        f(srcForm, 'supplierItemNm').value = s ? s.supplierItemNm || '' : '';
        setNum(srcForm, 'leadDays', s ? s.leadDays : null);
        setNum(srcForm, 'moq', s ? s.moq : null);
        setNum(srcForm, 'orderMult', s ? s.orderMult : null);
        setNum(srcForm, 'priority', s ? s.priority : 1);
        f(srcForm, 'useYn').value = s ? s.useYn : 'Y';
        document.getElementById('srcMode').textContent = s ? hb.t('js.srm.src.editing', s.itemCd) : hb.t('js.srm.src.creating');
    }

    function newSource() { editSource(null); }

    var srcNewBtn = document.getElementById('srcNewBtn');
    if (srcNewBtn) { srcNewBtn.addEventListener('click', newSource); }
    var srcSaveBtn = document.getElementById('srcSaveBtn');
    if (srcSaveBtn) {
        srcSaveBtn.addEventListener('click', function () {
            hb.showFieldErrors(srcForm, []);
            var body = {itemCd: f(srcForm, 'itemCd').value, supplierItemCd: f(srcForm, 'supplierItemCd').value,
                supplierItemNm: f(srcForm, 'supplierItemNm').value, leadDays: num(srcForm, 'leadDays'), moq: num(srcForm, 'moq'),
                orderMult: num(srcForm, 'orderMult'), priority: num(srcForm, 'priority'), useYn: f(srcForm, 'useYn').value,
                remark: curSource ? curSource.remark : null, rowVer: curSource ? curSource.rowVer : null};
            var id = current.custId;
            var editing = curSource;
            hb.busy(srcSaveBtn, function () {
                return editing ? hb.api(API + '/' + id + '/sources/' + editing.sourceId, {method: 'PUT', body: body})
                    : hb.api(API + '/' + id + '/sources', {method: 'POST', body: body});
            }).then(function (res) {
                if (!res) { return; }
                return load(id).then(function () { notify(hb.t('js.common.saved')); });
            }, fail(srcForm));
        });
    }

    // ---------------------------------------------------------------- 단가

    function loadPrices() {
        var body = document.querySelector('#prices tbody');
        return hb.latest('prices', hb.api(API + '/' + current.custId + '/prices')).then(function (res) {
            body.textContent = '';
            if (!res.data.length) { emptyRow(body, 6, hb.t('js.srm.price.empty')); }
            res.data.forEach(function (p) {
                var tr = hb.el('tr', p.state === 'CURRENT' || p.state === 'FUTURE' ? 'hb-row-pick' : 'hb-row-pick hb-tree-dim');
                tr.tabIndex = 0;
                tr.title = p.itemNm;
                srmUi.cells(tr, [p.itemCd, p.currencyCd, p.unitPrice + ' / ' + p.unitCd, p.validFrom,
                    p.validTo || hb.t('js.srm.price.open'), srmUi.badge('price', p.state, priceStateName(p.state))]);
                function pick() { editPrice(p); }
                tr.addEventListener('click', pick);
                tr.addEventListener('keydown', function (e) { if (e.key === 'Enter') { pick(); } });
                body.appendChild(tr);
            });
        }, fail());
    }

    /** 고른 단가는 끝일·사용만 바꾼다(단가·통화·시작일·품목은 고정 — 새 단가는 새 행) */
    function editPrice(p) {
        curPrice = p;
        hb.showFieldErrors(priceForm, []);
        f(priceForm, 'itemCd').value = p ? p.itemCd : '';
        sels.priceCurrency.setValue(p ? p.currencyCd : (current && current.currencyCd) || 'KRW');
        setNum(priceForm, 'unitPrice', p ? p.unitPrice : null);
        f(priceForm, 'validFrom').value = p ? p.validFrom : '';
        f(priceForm, 'validTo').value = p ? p.validTo || '' : '';
        f(priceForm, 'useYn').value = p ? p.useYn : 'Y';
        ['itemCd', 'unitPrice', 'validFrom'].forEach(function (n) { f(priceForm, n).readOnly = !!p || !CAN_SAVE; });
        f(priceForm, 'currencyCd').disabled = !!p || !CAN_SAVE;
        f(priceForm, 'useYn').disabled = !p || !CAN_SAVE;
        document.getElementById('priceMode').textContent = p ? hb.t('js.srm.price.editing', p.itemCd, p.validFrom) : hb.t('js.srm.price.creating');
    }

    function newPrice() { editPrice(null); }

    var priceNewBtn = document.getElementById('priceNewBtn');
    if (priceNewBtn) { priceNewBtn.addEventListener('click', newPrice); }
    var priceSaveBtn = document.getElementById('priceSaveBtn');
    if (priceSaveBtn) {
        priceSaveBtn.addEventListener('click', function () {
            hb.showFieldErrors(priceForm, []);
            var editing = curPrice;
            var body = editing
                ? {validTo: f(priceForm, 'validTo').value || null, useYn: f(priceForm, 'useYn').value, remark: editing.remark,
                    rowVer: editing.rowVer}
                : {itemCd: f(priceForm, 'itemCd').value, currencyCd: sels.priceCurrency.value(), unitPrice: num(priceForm, 'unitPrice'),
                    validFrom: f(priceForm, 'validFrom').value || null, validTo: f(priceForm, 'validTo').value || null};
            var id = current.custId;
            hb.busy(priceSaveBtn, function () {
                return editing ? hb.api(API + '/' + id + '/prices/' + editing.priceId, {method: 'PUT', body: body})
                    : hb.api(API + '/' + id + '/prices', {method: 'POST', body: body});
            }).then(function (res) {
                if (!res) { return; }
                return load(id).then(function () { notify(hb.t('js.common.saved')); });
            }, fail(priceForm));
        });
    }

    // ---------------------------------------------------------------- 품목 찾기(datalist)

    var itemTimer = null;
    function itemLookup(input) {
        input.addEventListener('input', function () {
            if (input.readOnly) { return; }
            window.clearTimeout(itemTimer);
            itemTimer = window.setTimeout(function () {
                var k = input.value.trim();
                if (k.length < 2) { return; }
                hb.latest('items', hb.api(API + '/items?keyword=' + encodeURIComponent(k))).then(function (res) {
                    var dl = document.getElementById('itemList');
                    dl.textContent = '';
                    res.data.forEach(function (i) {
                        var o = hb.el('option', null, i.itemNm + ' (' + i.unitCd + ')');
                        o.value = i.itemCd;
                        dl.appendChild(o);
                    });
                }, function () { /* 찾기 실패는 입력을 막지 않는다 */ });
            }, 250);
        });
    }

    // ---------------------------------------------------------------- 시작

    hb.dirty.watch(supForm);
    hb.loadMessages().then(function () {
        hb.page.init();
        sels.tradeStatus = hb.select(f(supForm, 'tradeStatus'));
        sels.currencyCd = hb.select(f(supForm, 'currencyCd'));
        sels.payTermsCd = hb.select(f(supForm, 'payTermsCd'));
        sels.priceCurrency = hb.select(f(priceForm, 'currencyCd'));
        if (certForm) { sels.certTypeCd = hb.select(f(certForm, 'certTypeCd')); }
        ['leadDays', 'moq', 'orderMult', 'priority'].forEach(function (n) { nums[n] = hb.number(f(srcForm, n)); });
        nums.unitPrice = hb.number(f(priceForm, 'unitPrice'));
        itemLookup(f(srcForm, 'itemCd'));
        itemLookup(f(priceForm, 'itemCd'));
        if (!CAN_SAVE) {
            [supForm, srcForm, priceForm].forEach(function (fm) {
                fm.querySelectorAll('input, select').forEach(function (i) { i.disabled = true; });
            });
        }
        sp = hb.searchPanel(search, {onSearch: function () {
            return srmUi.leaveOk().then(function (ok) { return ok ? load() : null; });
        }});
        return Promise.all([hb.loadSession(), sp.ready, sels.tradeStatus.ready, sels.currencyCd.ready, sels.payTermsCd.ready,
            sels.priceCurrency.ready, sels.certTypeCd ? sels.certTypeCd.ready : null,
            hb.codes('TRADE_STATUS'), hb.codes('SUPPLIER_CERT')]);
    }).then(function (r) {
        tradeNames = r[7];
        certTypeNames = r[8];
        grid = hb.grid('#supGrid', {
            progCd: 'srm-supplier', id: 'main', key: 'custId',
            url: API + '/grid', columnsUrl: API + '/columns', exportUrl: API + '/export', actions: ACTIONS, params: params,
            filters: {tradeStatus: 'tradeStatus'},
            onFilter: function (param, value) { sp.setValue(param, value); },
            format: {
                registered: function (v) { return v ? hb.t('js.srm.sup.registered') : hb.t('js.srm.sup.notRegistered'); },
                certState: function (v) { return certStateName(v); }
            },
            beforeSelect: function () { return srmUi.leaveOk(); },
            status: {tradeStatus: srmUi.TONES.trade, certState: srmUi.TONES.cert},
            rowClass: function (row) { return row.registered && row.custUseYn === 'Y' ? null : 'hb-tree-dim'; },
            onSelect: function (row) { openRow(row.custId); },
            notify: notify
        });
        return grid.ready.then(function () { return load(); });
    }).then(null, function (err) { notify(err && err.message, true); });
})();
