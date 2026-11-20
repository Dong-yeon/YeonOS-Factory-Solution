/*
 * 설비 KPI (cms-kpi) — CMS 2부 C9 (docs/33 11.8). 조회만.
 * 서버가 낸 비율(0~1)을 %로, 초를 시간으로 보여 준다. 빈칸 = 낼 수 없음(수집 없음·표준 사이클 없음·고장 0).
 */
(function () {
    'use strict';

    var API = '/api/cms/kpi';
    var msg = document.getElementById('msg');
    var search = document.getElementById('search');
    var plantSel = document.getElementById('plantSel');
    var tbody = document.querySelector('#kpi tbody');
    var sp;

    function notify(text, error) {
        msg.textContent = text || '';
        msg.classList.toggle('hb-msg-error', !!error);
    }

    function pct(v) { return v == null ? '' : (Number(v) * 100).toFixed(1) + '%'; }

    function hours(sec) { return (Number(sec) / 3600).toFixed(1); }

    /** 분 → 시간(소수 1자리), 빈 값은 빈칸 */
    function minToH(v) { return v == null ? '' : (Number(v) / 60).toFixed(1); }

    function line(l, label, total) {
        var k = l.kpi, r = l.raw, w = l.wo || {};
        var tr = hb.el('tr');
        var gap = r.unknownSec + k.uncoveredSec;
        // 작업지시 기준(docs/33 16.3 F3)은 수집 없는 설비(·그 합)만 서버가 채운다 — 수집 있는 설비는 빈칸(상태 원장 값과 다투지 않게)
        [label, k.collected ? hours(k.plannedSec) : hb.t('js.cms.kpi.noCollector'), k.collected ? hours(r.prdSec) : '', pct(k.availability),
            pct(k.performance), pct(k.quality), pct(k.oee), k.collected ? String(r.failures) : '',
            minToH(k.mtbfMin), k.mttrMin == null ? '' : String(k.mttrMin),
            gap ? hours(gap) : '', k.pmCompliance == null ? '' : pct(k.pmCompliance) + ' (' + r.pmOnTime + '/' + r.pmDone + ')',
            w.failures == null ? '' : String(w.failures), minToH(w.mtbfMin), w.mttrMin == null ? '' : String(w.mttrMin),
            w.mttaMin == null ? '' : String(w.mttaMin)]
            .forEach(function (x, i) {
                var td = hb.el('td', i === 0 ? null : 'hb-num');
                td.appendChild(total ? hb.el('strong', null, x) : document.createTextNode(x));
                tr.appendChild(td);
            });
        if (k.performance == null && r.ctMissing > 0) { tr.title = hb.t('js.cms.kpi.ctMissing', r.ctMissing); }
        tbody.appendChild(tr);
    }

    var trendBody = document.querySelector('#trend tbody');
    var trendTarget = document.getElementById('trendTarget');
    var lastCond = null;

    /** 일별 추이(C11) — 공장 합계 또는 설비 하나, 31일까지. 기간이 길면 안내만 */
    function loadTrend() {
        if (!lastCond) { return Promise.resolve(); }
        trendBody.textContent = '';
        var q = ['plantCd', 'from', 'to', 'basis'].map(function (k) { return k + '=' + encodeURIComponent(lastCond[k] || ''); });
        if (trendTarget.value) { q.push('equipCd=' + encodeURIComponent(trendTarget.value)); }
        return hb.latest('trend', hb.api(API + '/daily?' + q.join('&'))).then(function (res) {
            if (!res.data.length) { emptyRow(trendBody, 8, hb.t('js.cms.kpi.noTrend')); }
            res.data.forEach(function (d) {
                var k = d.kpi, r = d.raw;
                var tr = hb.el('tr');
                [d.day, k.collected ? hours(k.plannedSec) : hb.t('js.cms.kpi.noCollector'), k.collected ? hours(r.prdSec) : '',
                    pct(k.availability), pct(k.performance), pct(k.quality), pct(k.oee), k.collected ? String(r.failures) : '']
                    .forEach(function (x, i) { tr.appendChild(hb.el('td', i === 0 ? null : 'hb-num', x)); });
                trendBody.appendChild(tr);
            });
        }, function (err) {
            if (err && err.stale) { return; }
            emptyRow(trendBody, 8, err.code === 'INVALID_RANGE' ? hb.t('js.cms.kpi.trendTooLong') : err.message);
        });
    }

    function emptyRow(body, cols, text) {
        var tr0 = hb.el('tr');
        var td0 = hb.el('td', 'hb-muted', text);
        td0.colSpan = cols;
        tr0.appendChild(td0);
        body.appendChild(tr0);
    }

    function fillTargets(equips) {
        var keep = trendTarget.value;
        trendTarget.textContent = '';
        var o0 = hb.el('option', null, hb.t('js.cms.kpi.plantOnly'));
        o0.value = '';
        trendTarget.appendChild(o0);
        equips.forEach(function (e) {
            var o = hb.el('option', null, e.code + ' · ' + e.name);
            o.value = e.code;
            trendTarget.appendChild(o);
        });
        trendTarget.value = equips.some(function (e) { return e.code === keep; }) ? keep : '';
    }

    trendTarget.addEventListener('change', loadTrend);

    var excelBtn = document.getElementById('excelBtn');
    if (excelBtn) {
        excelBtn.addEventListener('click', function () {
            hb.busy(excelBtn, function () { return hb.download(API + '/export', sp.values()); })
                .then(null, function (err) { notify(err.message, true); });
        });
    }

    function load() {
        var v = sp.values();
        var q = [];
        ['plantCd', 'from', 'to', 'keyword', 'basis'].forEach(function (k) { if (v[k]) { q.push(k + '=' + encodeURIComponent(v[k])); } });
        hb.showFieldErrors(search, []);
        return hb.latest('kpi', hb.api(API + '?' + q.join('&'))).then(function (res) {
            notify('');
            var d = res.data;
            document.getElementById('period').textContent = hb.t('js.cms.kpi.period', hb.fmt.datetime(d.from, d.timeZone),
                hb.fmt.datetime(d.until, d.timeZone), d.timeZone);
            tbody.textContent = '';
            if (!d.equips.length) {
                var tr0 = hb.el('tr');
                var td0 = hb.el('td', 'hb-muted', hb.t('js.cms.kpi.none'));
                td0.colSpan = 16;
                tr0.appendChild(td0);
                tbody.appendChild(tr0);
                return;
            }
            line(d.plant, hb.t('js.cms.kpi.plantTotal', d.plantCd), true);
            lastCond = v;
            fillTargets(d.equips);
            loadTrend();
            d.lines.forEach(function (l) {
                line(l, hb.t('js.cms.kpi.lineTotal', l.code ? l.code + ' · ' + (l.name || '') : hb.t('js.cms.kpi.noLine')), true);
                d.equips.filter(function (e) { return (e.nodeCd || '') === (l.nodeCd || ''); }).forEach(function (e) {
                    line(e, e.code + ' · ' + e.name, false);
                });
            });
        }, function (err) {
            if (err && err.stale) { return; }
            notify(err.message, true);
            hb.showFieldErrors(search, err.errors);
        });
    }

    hb.loadMessages().then(function () {
        hb.page.init();
        return Promise.all([hb.loadSession(), hb.api(API + '/plants')]);
    }).then(function (r) {
        r[1].data.forEach(function (p) {
            var o = hb.el('option', null, p.plantCd + ' · ' + p.plantNm);
            o.value = p.plantCd;
            plantSel.appendChild(o);
        });
        var today = hb.dates.todayIn(hb.session().timeZone);
        document.getElementById('toIn').value = today;
        document.getElementById('fromIn').value = hb.dates.add(today, -6);
        sp = hb.searchPanel(search, {onSearch: function () { return load(); }});
        return sp.ready;
    }).then(function () {
        if (!plantSel.value) {
            notify(hb.t('js.cms.kpi.noPlant'), true);
            return;
        }
        return load();
    }).then(null, function (err) { notify(err && err.message, true); });
})();
