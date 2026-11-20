/*
 * 설비·생산 대시보드 (cms-dashboard) — docs/51 C2 개요 + docs/33 16.4 G1 고장 분석 · 16.5 G2 수리 시간 분해. 조회만.
 * 계산은 서버(/api/cms/dashboard, /failures, /failures/mwos) — 화면은 % ·시간 바꾸기와 누적 % 표시만 한다. 서버 글자는 textContent 로만.
 * 탭은 조건을 함께 쓰고, 조회하면 지금 보이는 탭만 읽는다(다른 탭은 열 때 읽음). 차트는 hb.chart — 라이브러리가 없으면 표만.
 */
(function () {
    'use strict';

    var API = '/api/cms/dashboard';
    var msg = document.getElementById('msg');
    var search = document.getElementById('search');
    var plantSel = document.getElementById('plantSel');
    var periodText = document.getElementById('periodText');
    var panes = {overview: document.getElementById('overview'), fail: document.getElementById('fail')};
    var axisSel = document.getElementById('axisSel');
    var drill = document.getElementById('drill');
    var sp;
    var tab = 'overview';
    var stale = {overview: true, fail: true};
    var lastFail = null;
    var lastCond = null;

    function notify(text, error) {
        msg.textContent = text || '';
        msg.classList.toggle('hb-msg-error', !!error);
    }

    function pct(v) { return v == null ? '' : (Number(v) * 100).toFixed(1) + '%'; }

    function num1(v) { return v == null ? '' : Number(v).toFixed(1); }

    function hours(sec) { return (Number(sec) / 3600).toFixed(1); }

    function minToH(v) { return v == null ? '' : (Number(v) / 60).toFixed(1); }

    function tbodyOf(id) { return document.querySelector('#' + id + ' tbody'); }

    function cells(tr, values) {
        values.forEach(function (x, i) { tr.appendChild(hb.el('td', i === 0 ? null : 'hb-num', x)); });
        return tr;
    }

    function query(extra) {
        var v = lastCond || {};
        var q = [];
        ['plantCd', 'from', 'to', 'keyword', 'classCd'].forEach(function (k) { if (v[k]) { q.push(k + '=' + encodeURIComponent(v[k])); } });
        Object.keys(extra || {}).forEach(function (k) { q.push(k + '=' + encodeURIComponent(extra[k])); });
        return q.join('&');
    }

    /** 작업지시 화면을 연다(셸 탭이면 새 탭, 단독이면 이동) — 설비 이력 카드와 같은 ?mwo= */
    function openMwo(id) {
        var shell = window.top && window.top !== window ? window.top.hbShell : null;
        if (shell && shell !== true) {
            shell.open('cms-mwo', hb.t('js.cms.dash.mwoTab'), {mwo: String(id)});
        } else {
            window.location.href = '/page/cms-mwo?mwo=' + encodeURIComponent(String(id));
        }
    }

    // ------------------------------------------------------------------ 개요 (C2)

    function tile(host, name, value, prevText, target) {
        var box = hb.el('div', 'cmsd-tile');
        box.appendChild(hb.el('span', 'cmsd-tile-name', name));
        box.appendChild(hb.el('span', 'cmsd-tile-value', value || '—'));
        if (prevText) { box.appendChild(hb.el('span', 'cmsd-tile-sub', prevText)); }
        if (target) {
            var t = hb.el('span', 'cmsd-tile-sub', target.text);
            t.setAttribute('data-tone', target.ok ? 'success' : 'danger');
            box.appendChild(t);
        }
        host.appendChild(box);
    }

    function loadOverview() {
        var note = document.getElementById('ovNote');
        note.hidden = true;
        return hb.latest('dash', hb.api(API + '?' + query())).then(function (res) {
            var d = res.data;
            periodText.textContent = hb.t('js.cms.kpi.period', hb.fmt.datetime(d.fromAt, d.timeZone), hb.fmt.datetime(d.until, d.timeZone), d.timeZone);
            var k = d.total.kpi, p = d.prev.kpi, tg = d.targets || {};
            var tiles = document.getElementById('tiles');
            tiles.textContent = '';
            [['oee', 'OEE', tg.oeePct], ['availability', hb.t('js.cms.dash.availability'), tg.availabilityPct],
                ['performance', hb.t('js.cms.dash.performance'), tg.performancePct], ['quality', hb.t('js.cms.dash.quality'), tg.qualityPct]]
                .forEach(function (x) {
                    var v = k[x[0]];
                    tile(tiles, x[1], pct(v), hb.t('js.cms.dash.prev', pct(p[x[0]]) || '—'),
                        x[2] == null || v == null ? null : {text: hb.t('js.cms.dash.target', x[2] + '%'), ok: Number(v) * 100 >= x[2]});
                });
            tile(tiles, hb.t('js.cms.dash.mttr'), k.mttrMin == null ? '' : num1(k.mttrMin), hb.t('js.cms.dash.prev', p.mttrMin == null ? '—' : num1(p.mttrMin)),
                tg.mttrMin == null || k.mttrMin == null ? null : {text: hb.t('js.cms.dash.target', tg.mttrMin), ok: Number(k.mttrMin) <= tg.mttrMin});
            var w = d.total.wo || {};
            tile(tiles, hb.t('js.cms.dash.mtta'), w.mttaMin == null ? '' : num1(w.mttaMin), null, null);

            hb.chart.bar(document.getElementById('trendChart'), {
                categories: d.daily.map(function (x) { return x.day.substring(5); }),
                series: [['oee', 'OEE'], ['availability', hb.t('js.cms.dash.availability')], ['performance', hb.t('js.cms.dash.performance')],
                    ['quality', hb.t('js.cms.dash.quality')]].map(function (s) {
                    return {name: s[1], kind: 'line', data: d.daily.map(function (x) { return x.kpi[s[0]] == null ? null : Number(x.kpi[s[0]]) * 100; })};
                }),
                unit: '%', decimals: 1, height: 260
            });
            hb.chart.pareto(document.getElementById('lossChart'), {
                categories: d.losses.map(function (l) {
                    return (l.reasonNm || hb.t('js.cms.dash.noReason', l.cat)) + ' · ' + (l.group === 'PLANNED' ? hb.t('js.cms.dash.lossGroup.PLANNED') : hb.t('js.cms.dash.lossGroup.UNPLANNED'));
                }),
                values: d.losses.map(function (l) { return Number(l.sec) / 3600; }),
                valueName: hb.t('js.cms.dash.downH'), unit: 'h', decimals: 1, top: 10, height: 260
            });
            document.getElementById('lossUnknown').textContent = d.unknownSec || d.uncoveredSec
                ? hb.t('js.cms.dash.unknown', hours(d.unknownSec), hours(d.uncoveredSec)) : '';
            var wb = tbodyOf('wos');
            wb.textContent = '';
            if (!d.wos.length) { hb.emptyRow(wb, hb.t('js.cms.dash.noWos')); }
            d.wos.forEach(function (o) {
                var tr = hb.el('tr');
                tr.appendChild(hb.el('td', null, o.woNo));
                tr.appendChild(hb.el('td', null, o.itemCd + ' · ' + o.itemNm));
                tr.appendChild(hb.el('td', null, o.planStart));
                [o.planQty + ' ' + o.unitCd, o.goodQty + ' ' + o.unitCd, pct(o.attainment)]
                    .forEach(function (x) { tr.appendChild(hb.el('td', 'hb-num', x)); });
                wb.appendChild(tr);
            });
            stale.overview = false;
        }, function (err) {
            if (err && err.stale) { return; }
            if (err.code === 'INVALID_RANGE') {
                note.hidden = false;
                note.textContent = hb.t('js.cms.dash.overviewRange');
                return;
            }
            notify(err.message, true);
            hb.showFieldErrors(search, err.errors);
        });
    }

    // ------------------------------------------------------------------ 고장 분석 (G1·G2)

    function barLabel(b) {
        if (b.keyCd == null) { return hb.t('js.cms.dash.none'); }
        return b.keyNm ? b.keyCd + ' · ' + b.keyNm : b.keyCd;
    }

    function loadFail() {
        drill.hidden = true;
        return hb.latest('fail', hb.api(API + '/failures?' + query({axis: axisSel.value}))).then(function (res) {
            var d = res.data;
            lastFail = d;
            periodText.textContent = hb.t('js.cms.kpi.period', hb.fmt.datetime(d.fromAt, d.timeZone), hb.fmt.datetime(d.until, d.timeZone), d.timeZone);
            // 탐지 방법(G3) — 기록한 고장 중 예방 활동(점검·예방 보전·상태 감시)으로 찾은 비율
            var detect = d.detectRecorded ? ' · ' + hb.t('js.cms.dash.detect', d.detectRecorded, d.detectPreventive,
                (d.detectPreventive * 100 / d.detectRecorded).toFixed(0)) : '';
            document.getElementById('failSummary').textContent = hb.t('js.cms.dash.failSummary', d.failures, hours(d.downSec), d.repeats.length,
                d.repeatDays) + detect;
            hb.chart.pareto(document.getElementById('paretoChart'), {
                categories: d.bars.map(barLabel),
                values: d.bars.map(function (b) { return b.failures; }),
                valueName: hb.t('js.cms.dash.failures'), top: 10, height: 280, emptyText: hb.t('js.cms.dash.noFail'),
                onClick: function (cat, idx) { if (idx >= 0) { openDrill(d.bars[idx]); } }
            });
            var bb = tbodyOf('bars');
            bb.textContent = '';
            if (!d.bars.length) { hb.emptyRow(bb, hb.t('js.cms.dash.noFail')); }
            var cum = 0;
            d.bars.forEach(function (b) {
                cum += b.failures;
                var tr = cells(hb.el('tr'), [barLabel(b), String(b.failures), (cum * 100 / d.failures).toFixed(1), hours(b.downSec)]);
                tr.tabIndex = 0;
                tr.title = hb.t('js.cms.dash.drillTip');
                tr.addEventListener('click', function () { openDrill(b); });
                tr.addEventListener('keydown', function (ev) { if (ev.key === 'Enter') { openDrill(b); } });
                bb.appendChild(tr);
            });
            renderGroups();
            document.getElementById('repTitle').textContent = hb.t('js.cms.dash.repeatTitle', d.repeatDays);
            var rb = tbodyOf('reps');
            rb.textContent = '';
            if (!d.repeats.length) { hb.emptyRow(rb, hb.t('js.cms.dash.noRepeat', d.repeatDays)); }
            d.repeats.forEach(function (r) {
                var tr = hb.el('tr');
                tr.appendChild(hb.el('td', null, r.equipCd + ' · ' + r.equipNm));
                tr.appendChild(hb.el('td', null, r.causeCd + ' · ' + r.causeNm));
                tr.appendChild(hb.el('td', 'hb-num', String(r.repeats)));
                tr.appendChild(hb.el('td', 'hb-num', num1(r.minGapDays)));
                tr.appendChild(hb.el('td', null, hb.fmt.datetime(r.lastAt, d.timeZone)));
                rb.appendChild(tr);
            });
            stale.fail = false;
        }, function (err) {
            if (err && err.stale) { return; }
            notify(err.message, true);
            hb.showFieldErrors(search, err.errors);
        });
    }

    function renderGroups() {
        if (!lastFail) { return; }
        var group = (document.querySelector('#groupSeg input:checked') || {}).value || 'EQUIP';
        var equip = group === 'EQUIP';
        document.querySelectorAll('#bd .cmsd-eq').forEach(function (th) { th.hidden = !equip; });
        var list = equip ? lastFail.byEquip : lastFail.byAssignee;
        var body = tbodyOf('bd');
        body.textContent = '';
        if (!list.length) { hb.emptyRow(body, hb.t('js.cms.dash.noFail')); }
        list.forEach(function (g) {
            var label = g.keyCd == null ? hb.t('js.cms.dash.noAssignee') : g.keyCd + ' · ' + (g.keyNm || '');
            var vals = [label, String(g.failures), num1(g.avgWaitMin), num1(g.avgRepairMin), num1(g.avgDownMin), num1(g.avgLaborMin)];
            if (equip) { vals = vals.concat([minToH(g.kpiMtbfMin), num1(g.kpiMttrMin), minToH(g.woMtbfMin), num1(g.woMttrMin)]); }
            body.appendChild(cells(hb.el('tr'), vals));
        });
    }

    function openDrill(b) {
        var key = b.keyCd == null ? '-' : b.keyCd;
        drill.hidden = false;
        document.getElementById('drillTitle').textContent = hb.t('js.cms.dash.drillTitle', barLabel(b), b.failures);
        var body = tbodyOf('drillTable');
        body.textContent = '';
        return hb.latest('drill', hb.api(API + '/failures/mwos?' + query({axis: lastFail.axis, key: key}))).then(function (res) {
            var d = res.data;
            if (!d.list.length) { hb.emptyRow(body, hb.t('js.cms.dash.noFail')); }
            d.list.forEach(function (m) {
                var tr = hb.el('tr');
                var td = hb.el('td');
                var a = hb.el('button', 'hb-btn-ghost', m.mwoNo);
                a.type = 'button';
                a.addEventListener('click', function () { openMwo(m.mwoId); });
                td.appendChild(a);
                tr.appendChild(td);
                [hb.fmt.datetime(m.failAt, lastFail.timeZone), m.equipCd + ' · ' + m.equipNm, m.symptomNm || '', m.causeNm || '', m.actionNm || '']
                    .forEach(function (x) { tr.appendChild(hb.el('td', null, x)); });
                tr.appendChild(hb.el('td', 'hb-num', m.downSec == null ? '' : String(Math.floor(m.downSec / 60))));
                tr.appendChild(hb.el('td', null, m.assigneeNm || ''));
                body.appendChild(tr);
            });
            if (d.truncated) { notify(hb.t('js.cms.dash.drillTruncated', d.list.length)); }
            drill.scrollIntoView({block: 'nearest'});
        }, function (err) {
            if (err && err.stale) { return; }
            notify(err.message, true);
        });
    }

    // ------------------------------------------------------------------ 탭·조회

    function show(name) {
        tab = name;
        Object.keys(panes).forEach(function (k) { panes[k].hidden = k !== name; });
        if (stale[name] && lastCond) { return name === 'fail' ? loadFail() : loadOverview(); }
        return Promise.resolve();
    }

    function load() {
        lastCond = sp.values();
        stale = {overview: true, fail: true};
        notify('');
        hb.showFieldErrors(search, []);
        return show(tab);
    }

    document.getElementById('tabs').addEventListener('change', function (ev) { show(ev.target.value); });
    document.getElementById('groupSeg').addEventListener('change', renderGroups);
    axisSel.addEventListener('change', function () { if (lastCond) { loadFail(); } });
    document.getElementById('drillClose').addEventListener('click', function () { drill.hidden = true; });

    hb.loadMessages().then(function () {
        hb.page.init();
        return Promise.all([hb.loadSession(), hb.api(API + '/plants')]);
    }).then(function (r) {
        r[1].data.forEach(function (p) {
            var o = hb.el('option', null, p.plantCd + ' · ' + p.plantNm);
            o.value = p.plantCd;
            plantSel.appendChild(o);
        });
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
