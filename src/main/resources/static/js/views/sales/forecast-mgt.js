/* 내시 수신 (sal-forecast) — P2 2단계 (docs/25 4.3). 거래처 → 버전 hb.grid(UI 단계 3 ①) → 이전 버전과 비교 / 이 버전 행.
   버전은 바꾸지 않는다 — 새로 받으면(엑셀 올리기) 새 버전이 생긴다. */
(function () {
    'use strict';

    var API = '/api/sales/forecasts';
    var msg = document.getElementById('msg');
    var custSel = document.getElementById('custSel');
    var PROG = document.body.getAttribute('data-prog') || '';
    var ACTIONS = (document.body.getAttribute('data-actions') || '').split(',');
    var fromSel = document.getElementById('fromSel');
    var fromBox = document.getElementById('fromBox');
    var changedOnly = document.getElementById('changedOnly');
    var revInfo = document.getElementById('revInfo');
    var summary = document.getElementById('summary');
    var cmpTable = document.getElementById('compare');
    var lineTable = document.getElementById('lines');
    var revs = [], current = null, lastCompare = null, grid = null;
    var BUCKET = {DAY: 'js.salFcst.b.DAY', WEEK: 'js.salFcst.b.WEEK', MONTH: 'js.salFcst.b.MONTH'};
    var SOURCE = {FILE: 'js.salFcst.src.FILE', MANUAL: 'js.salFcst.src.MANUAL', EDI: 'js.salFcst.src.EDI'};

    function notify(text, error) {
        msg.textContent = text || '';
        msg.classList.toggle('hb-msg-error', !!error);
    }

    function view() { return document.querySelector('#viewSeg input:checked').value; }

    function bucketText(type, start) { return hb.t(BUCKET[type] || 'js.salFcst.b.DAY') + ' ' + hb.fmt.date(start); }

    // ---------- 10진 글자 계산(수량은 서버가 글자로 보냄 — Number 로 바꾸면 큰 수가 틀린다)
    function dec(s) {
        var t = s === null || s === undefined || s === '' ? '0' : String(s);
        var neg = t.charAt(0) === '-';
        if (neg) { t = t.substring(1); }
        var p = t.split('.');
        var frac = p[1] || '';
        return {n: BigInt(p[0] + frac) * (neg ? -1n : 1n), scale: frac.length};
    }

    function sub(a, b) {
        var x = dec(a), y = dec(b);
        var sc = Math.max(x.scale, y.scale);
        var n = x.n * 10n ** BigInt(sc - x.scale) - y.n * 10n ** BigInt(sc - y.scale);
        var neg = n < 0n;
        var digits = (neg ? -n : n).toString().padStart(sc + 1, '0');
        var out = sc ? digits.substring(0, digits.length - sc) + '.' + digits.substring(digits.length - sc) : digits;
        out = out.indexOf('.') >= 0 ? out.replace(/0+$/, '').replace(/\.$/, '') : out;
        return (neg ? '-' : '') + out;
    }

    function qtyText(s) {
        if (s === null || s === undefined || s === '') { return ''; }
        var neg = String(s).charAt(0) === '-';
        var p = String(s).replace('-', '').split('.');
        return (neg ? '-' : '') + p[0].replace(/\B(?=(\d{3})+(?!\d))/g, ',') + (p.length > 1 ? '.' + p[1] : '');
    }

    // ---------- 버전 목록

    /**
     * 버전 목록 — 보이는 목록은 그리드(페이지), 비교 대상 고르기에 쓸 모든 버전은 기존 목록 API 로 따로 읽는다(버전은 거래처당 수십 개).
     * 거래처가 없으면 그리드를 만들지 않는다(거래처 필수 400 을 피함). 다 읽으면 selectRevId(없으면 최신)를 고른다.
     */
    function loadRevs(selectRevId) {
        if (!custSel.value) {
            revs = [];
            if (grid) { grid.destroy(); grid = null; }
            show(null);
            return Promise.resolve();
        }
        var cust = custSel.value;
        return hb.latest('revs', hb.api(API + '/revs?custCd=' + encodeURIComponent(cust))).then(function (res) {
            if (custSel.value !== cust) { return null; }   // 기다리는 사이 거래처를 바꿨으면 버린다
            revs = res.data;
            show(null);
            if (!grid) {
                grid = hb.grid('#revGrid', {
                    progCd: PROG, id: 'revs', key: 'revId',
                    url: API + '/revs/grid', columnsUrl: API + '/revs/columns', exportUrl: API + '/revs/export',
                    actions: ACTIONS, params: function () { return {custCd: custSel.value}; },
                    format: {
                        revNo: function (v) { return hb.t('js.salFcst.rev', v); },
                        firstBucket: function (v, row) { return v ? hb.fmt.date(v) + ' ~ ' + hb.fmt.date(row.lastBucket) : ''; }
                    },
                    onSelect: function (row) {
                        show(revs.filter(function (x) { return x.revId === row.revId; })[0] || row);
                        hb.page.openDetail();
                    },
                    notify: notify
                });
            }
            var g = grid;
            return (g.ready).then(function () { return g.reload(true); }).then(function () {
                var sel = revs.filter(function (r) { return r.revId === selectRevId; })[0] || revs[0] || null;
                if (sel) { g.selectById(sel.revId); }
            });
        }, function (err) { notify(err.message, true); });
    }

    // ---------- 상세

    function show(r) {
        current = r;
        summary.textContent = '';
        cmpTable.hidden = true;
        lineTable.hidden = true;
        fromBox.hidden = true;
        if (!r) {
            revInfo.textContent = hb.t('js.salFcst.selectRev');
            return;
        }
        revInfo.textContent = hb.t('js.salFcst.revInfo', r.custCd, r.revNo, hb.fmt.datetime(r.receivedAt));
        // 비교 대상: 이 버전보다 앞 버전들(기본 바로 앞)
        fromSel.textContent = '';
        var older = revs.filter(function (x) { return x.revNo < r.revNo; });
        if (!older.length) {
            var none = hb.el('option', null, hb.t('js.salFcst.noOlder'));
            none.value = '';
            fromSel.appendChild(none);
        }
        older.forEach(function (x) {
            var o = hb.el('option', null, hb.t('js.salFcst.rev', x.revNo) + ' · ' + hb.fmt.datetime(x.receivedAt));
            o.value = String(x.revId);
            fromSel.appendChild(o);
        });
        refresh();
    }

    function refresh() {
        if (!current) { return; }
        if (view() === 'lines') {
            fromBox.hidden = true;
            hb.latest('detail', hb.api(API + '/revs/' + current.revId + '/lines')).then(function (res) {
                cmpTable.hidden = true;
                lineTable.hidden = false;
                var body = lineTable.querySelector('tbody');
                body.textContent = '';
                summary.textContent = hb.t('js.salFcst.lineCount', res.data.length);
                res.data.forEach(function (l) {
                    var tr = hb.el('tr');
                    tr.appendChild(hb.el('td', 'hb-wrap', l.itemCd + ' · ' + l.itemNm));
                    tr.appendChild(hb.el('td', null, bucketText(l.bucketType, l.bucketStart)));
                    tr.appendChild(hb.el('td', 'hb-num', qtyText(l.qty) + ' ' + l.unitCd));
                    tr.appendChild(hb.el('td', null, l.firmYn === 'Y' ? hb.t('js.salFcst.firm') : ''));
                    body.appendChild(tr);
                });
            }, function (err) { notify(err.message, true); });
            return;
        }
        fromBox.hidden = false;
        var q = 'toRevId=' + current.revId + (fromSel.value ? '&fromRevId=' + fromSel.value : '');
        hb.latest('detail', hb.api(API + '/compare?' + q)).then(function (res) {
            lastCompare = res.data;
            renderCompare();
        }, function (err) { notify(err.message, true); });
    }

    function renderCompare() {
        var d = lastCompare;
        lineTable.hidden = true;
        cmpTable.hidden = false;
        var body = cmpTable.querySelector('tbody');
        body.textContent = '';
        var up = 0, down = 0, added = 0, gone = 0;
        d.rows.forEach(function (r) {
            var diff = sub(r.toQty, r.fromQty);
            var kind = r.fromQty === null ? 'added' : r.toQty === null ? 'removed' : diff === '0' ? 'same' : 'changed';
            if (kind === 'added') { added++; } else if (kind === 'removed') { gone++; }
            else if (kind === 'changed') { if (diff.charAt(0) === '-') { down++; } else { up++; } }
            if (changedOnly.checked && kind === 'same' && r.fromFirm === r.toFirm) { return; }
            var tr = hb.el('tr', kind === 'added' ? 'hb-cmp-added' : kind === 'removed' ? 'hb-cmp-removed' : null);
            tr.appendChild(hb.el('td', 'hb-wrap', r.itemCd + ' · ' + r.itemNm));
            tr.appendChild(hb.el('td', null, bucketText(r.bucketType, r.bucketStart)));
            tr.appendChild(hb.el('td', 'hb-num', r.fromQty === null ? hb.t('js.salFcst.absent') : qtyText(r.fromQty)));
            tr.appendChild(hb.el('td', 'hb-num', r.toQty === null ? hb.t('js.salFcst.absent') : qtyText(r.toQty)));
            tr.appendChild(hb.el('td', 'hb-num', kind === 'same' ? '' : (diff.charAt(0) === '-' ? '' : '+') + qtyText(diff)));
            tr.appendChild(hb.el('td', null, (r.fromFirm === 'Y' ? hb.t('js.salFcst.firm') : '-') + ' → '
                + (r.toFirm === 'Y' ? hb.t('js.salFcst.firm') : '-')));
            body.appendChild(tr);
        });
        summary.textContent = d.from
            ? hb.t('js.salFcst.summary', d.from.revNo, d.to.revNo, up, down, added, gone)
            : hb.t('js.salFcst.first', d.to.revNo, added);
    }

    custSel.addEventListener('change', function () { loadRevs(); });
    document.getElementById('search').addEventListener('submit', function (e) { e.preventDefault(); });
    fromSel.addEventListener('change', refresh);
    changedOnly.addEventListener('change', function () { if (lastCompare) { renderCompare(); } });
    document.querySelectorAll('#viewSeg input').forEach(function (i) { i.addEventListener('change', refresh); });

    var importBtn = document.getElementById('importBtn');
    if (importBtn) {
        importBtn.addEventListener('click', function () {
            hb.importDialog({templateCd: 'SAL_FORECAST', onDone: function () { loadRevs(); }});
        });
    }

    hb.loadMessages().then(function () {
        hb.page.init();
        return Promise.all([hb.loadSession(), hb.api(API + '/customers')]);
    }).then(function (r) {
        var pick = hb.el('option', null, hb.t('js.salFcst.pickCust'));
        pick.value = '';
        custSel.appendChild(pick);
        r[1].data.forEach(function (c) {
            var o = hb.el('option', null, c.custCd + ' · ' + c.custNm);
            o.value = c.custCd;
            custSel.appendChild(o);
        });
        show(null);
    }).then(null, function (err) { notify(err && err.message, true); });
})();
