// @ts-check
/*
 * 접속·보안 로그 (sys-access-log) — 시스템관리 3단계 (docs/36 12장). 조회 패널(기간·이벤트·심각도…) + hb.grid.
 * 자기 회사 로그만(서버 RLS). 경고(ALERT)는 심각도 앞에 ▲. 알림 링크(q.eventCd·q.severity)로 열리면 그 조건으로 조회.
 */
(function () {
    'use strict';

    var PROG = document.body.getAttribute('data-prog') || '';
    var ACTIONS = (document.body.getAttribute('data-actions') || '').split(',');
    var API = '/api/admin/access-logs';
    var msg = /** @type {HTMLElement} */ (document.getElementById('msg'));
    var search = /** @type {HTMLFormElement} */ (document.getElementById('search'));
    /** @type {HB.Grid | undefined} */
    var grid;
    /** @type {HB.SearchPanel} */
    var sp;
    /** @type {Record<string, string>} */
    var severityNames = {};

    /** @param {string | null | undefined} text @param {boolean} [error] */
    function notify(text, error) {
        msg.textContent = text || '';
        msg.classList.toggle('hb-msg-error', !!error);
    }

    search.addEventListener('submit', function (e) { e.preventDefault(); });
    hb.loadMessages().then(function () {
        hb.page.init();
        sp = hb.searchPanel(search, {
            onSearch: function () { return grid ? grid.reload(true) : null; }
        });
        return Promise.all([sp.ready, hb.codes('LOG_SEVERITY')]);
    }).then(function (r) {
        severityNames = r[1];
        grid = hb.grid('#logGrid', {
            progCd: PROG, id: 'main', key: 'logId',
            url: API, columnsUrl: API + '/columns', exportUrl: API + '/export',
            actions: ACTIONS, params: function () { return sp.values(); },
            filters: {eventCd: 'eventCd', severity: 'severity', resultCd: 'resultCd', loginId: 'loginId', userType: 'userType', clientType: 'clientType'},
            status: {severity: sysUi.TONES.severity, resultCd: sysUi.TONES.result},
            format: {
                severity: function (v) {
                    var name = severityNames[v] || v || '';
                    return v === 'ALERT' ? '▲ ' + name : name;
                }
            },
            onFilter: function (param, value) { sp.setValue(param, value); },
            notify: notify
        });
        return grid.ready;
    }).then(null, function (err) { notify(err && err.message, true); });
})();
