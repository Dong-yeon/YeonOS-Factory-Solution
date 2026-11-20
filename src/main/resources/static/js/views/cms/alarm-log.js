/*
 * 설비 알람 이력 (cms-alarm) — CMS 2부 C8 (docs/33 11.6). 조회만.
 * 공장·발생일(공장 현지 날짜, 최대 92일) → 상위 알람 20 + 이력(반복은 정책 창 안 발생 수 — 서버 판정).
 */
(function () {
    'use strict';

    var API = '/api/cms/alarms';
    var ACTIONS = (document.body.getAttribute('data-actions') || '').split(',');
    var msg = document.getElementById('msg');
    var search = document.getElementById('search');
    var plantSel = document.getElementById('plantSel');
    var topBody = document.querySelector('#top tbody');
    var sp, grid;
    var sevNames = {};

    function notify(text, error) {
        msg.textContent = text || '';
        msg.classList.toggle('hb-msg-error', !!error);
    }

    function hm(v) { return v ? String(v).substring(0, 16).replace('T', ' ') : ''; }

    function emptyRow(body, cols, text) {
        var tr = hb.el('tr');
        var td = hb.el('td', 'hb-muted', text);
        td.colSpan = cols;
        tr.appendChild(td);
        body.appendChild(tr);
    }

    function row(body, cells, numCols) {
        var tr = hb.el('tr');
        cells.forEach(function (x, i) {
            var td = hb.el('td', numCols.indexOf(i) >= 0 ? 'hb-num' : null);
            if (x instanceof Node) { td.appendChild(x); } else { td.textContent = x; }
            tr.appendChild(td);
        });
        body.appendChild(tr);
        return tr;
    }

    /** 중대도 배지(알람 4단 톤, cms-ui) — 정의 없는 코드는 빈 칸 */
    function sev(s) { return s ? cmsUi.badge('severity', s, sevNames[s] || s) : ''; }

    /** 이력 조건(검색 패널) — grid 가 page·size·sort 를 붙인다 */
    function params() {
        var v = sp.values();
        var q = {};
        ['plantCd', 'from', 'to', 'keyword', 'openOnly'].forEach(function (k) { if (v[k]) { q[k] = v[k]; } });
        return q;
    }

    /** 상위 알람(일반 표) — 이력은 grid 가 따로 읽는다 */
    function loadTop() {
        var v = sp.values();
        var tq = ['plantCd', 'from', 'to'].map(function (k) { return k + '=' + encodeURIComponent(v[k] || ''); });
        hb.showFieldErrors(search, []);
        return hb.latest('top', hb.api(API + '/top?' + tq.join('&'))).then(function (r) {
            notify('');
            topBody.textContent = '';
            if (!r.data.length) { emptyRow(topBody, 7, hb.t('js.cms.alarm.noTop')); }
            r.data.forEach(function (t) {
                row(topBody, [t.equipCd + ' · ' + t.equipNm, t.alarmCd, t.alarmNm || hb.t('js.cms.alarm.undefined'), sev(t.severity),
                    String(t.raiseCount), String(t.activeMinutes), t.openCount ? String(t.openCount) : ''], [4, 5, 6]);
            });
        }, function (err) {
            if (err && err.stale) { return; }
            notify(err.message, true);
            hb.showFieldErrors(search, err.errors);
        });
    }

    /** 조회 = 상위 알람 + 이력 첫 쪽 */
    function load() {
        grid.reload(true);
        return loadTop();
    }

    hb.loadMessages().then(function () {
        hb.page.init();
        return Promise.all([hb.loadSession(), hb.api(API + '/plants'), hb.codeList('CMS_ALARM_SEV')]);
    }).then(function (r) {
        r[1].data.forEach(function (p) {
            var o = hb.el('option', null, p.plantCd + ' · ' + p.plantNm);
            o.value = p.plantCd;
            plantSel.appendChild(o);
        });
        r[2].forEach(function (c) { sevNames[c.code] = c.name; });
        var today = hb.dates.todayIn(hb.session().timeZone);
        document.getElementById('toIn').value = today;
        document.getElementById('fromIn').value = hb.dates.add(today, -6);
        sp = hb.searchPanel(search, {onSearch: function () { return load(); }});
        grid = hb.grid('#alarmGrid', {
            progCd: 'cms-alarm', id: 'main', key: 'alarmId',
            url: API + '/grid', columnsUrl: API + '/columns', exportUrl: API + '/export', actions: ACTIONS, params: params,
            filters: {},
            format: {
                raisedAt: hm,
                clearedAt: hm,
                repeat: function (v) { return v ? hb.t('js.cms.alarm.repeatTag') : ''; }
            },
            status: {severity: cmsUi.TONES.severity},
            rowClass: function (row) { return row.severity === 'CRITICAL' ? 'hb-row-warn' : null; },   // 중대 = 주의 행
            notify: notify
        });
        return Promise.all([sp.ready, grid.ready]);
    }).then(function () {
        if (!plantSel.value) {
            notify(hb.t('js.cms.alarm.noPlant'), true);
            return;
        }
        return loadTop();   // 이력 첫 쪽은 grid 가 이미 읽었다
    }).then(null, function (err) { notify(err && err.message, true); });
})();
