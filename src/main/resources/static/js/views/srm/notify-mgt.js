/*
 * 발주 통지 이력 (srm-notify) — SRM 1부 S4 (docs/31 D8). 아웃박스 통지 목록 + 실패 건 다시 보내기 + 가짜 메일함.
 */
(function () {
    'use strict';

    var API = '/api/srm/notices';
    var ACTIONS = (document.body.getAttribute('data-actions') || '').split(',');
    var msg = document.getElementById('msg');
    var search = document.getElementById('search');
    var sp, grid;

    function notify(text, error) {
        msg.textContent = text || '';
        msg.classList.toggle('hb-msg-error', !!error);
    }

    function kindName(t) {
        return {SRM_PO_ISSUED: hb.t('js.srm.notify.ISSUED'), SRM_PO_CANCELLED: hb.t('js.srm.notify.CANCELLED'),
            SRM_CLAIM_SENT: hb.t('js.srm.notify.CLAIM')}[t] || t;
    }

    function statusName(s) {
        return {NEW: hb.t('js.srm.notify.NEW'), SENDING: hb.t('js.srm.notify.SENDING'), SENT: hb.t('js.srm.notify.SENT'),
            FAIL: hb.t('js.srm.notify.FAIL'), CLOSED_MANUAL: hb.t('js.srm.notify.CLOSED_MANUAL')}[s] || s;
    }

    /** 조회 조건(검색 패널) — grid 가 page·size·sort 를 붙인다 */
    function params() {
        var v = sp.values();
        var q = {};
        ['from', 'to', 'status'].forEach(function (k) { if (v[k]) { q[k] = v[k]; } });
        return q;
    }

    var resendBtn = document.getElementById('resendBtn');
    var picked = null;

    /** 고른 행이 실패 통지일 때만 [다시 보내기] */
    function pick(row) {
        picked = row;
        if (resendBtn) { resendBtn.disabled = !(row && row.status === 'FAIL'); }
    }

    if (resendBtn) {
        resendBtn.addEventListener('click', function () {
            if (!picked || picked.status !== 'FAIL') { return; }
            var id = picked.outboxId;
            hb.busy(resendBtn, function () { return hb.api(API + '/' + id + '/resend', {method: 'POST', body: {}}); })
                .then(function (res) {
                    if (!res) { return; }
                    notify(hb.t('js.srm.notify.resent'));
                    return load();
                }, function (err) { notify(err.message, true); });
        });
    }

    /** 가짜 메일함(1부 통지 채널) */
    function loadMailbox() {
        return hb.api(API + '/mailbox').then(function (r) {
            var ul = document.getElementById('mailbox');
            ul.textContent = '';
            if (!r.data.length) { ul.appendChild(hb.el('li', 'hb-muted', hb.t('js.srm.notify.emptyBox'))); }
            r.data.forEach(function (m) {
                var li = hb.el('li');
                li.appendChild(hb.el('strong', null, m.subject));
                li.appendChild(hb.el('span', 'hb-muted', ' · ' + m.to + ' · ' + hb.fmt.datetime(m.receivedAt)));
                li.appendChild(hb.el('pre', null, m.body));
                ul.appendChild(li);
            });
        }, function (err) { notify(err.message, true); });
    }

    function load() {
        pick(null);
        return Promise.all([grid.reload(true), loadMailbox()]);
    }

    search.addEventListener('submit', function (e) { e.preventDefault(); });
    hb.loadMessages().then(function () { return hb.loadSession(); }).then(function () {
        hb.page.init();
        sp = hb.searchPanel(search, {onSearch: load});
        grid = hb.grid('#noticeGrid', {
            progCd: 'srm-notify', id: 'main', key: 'outboxId',
            url: API + '/grid', columnsUrl: API + '/columns', exportUrl: API + '/export', actions: ACTIONS, params: params,
            format: {topic: kindName, status: statusName, toAddrText: function (v) { return v || hb.t('js.srm.notify.noAddr'); }},
            status: {status: srmUi.TONES.notify},
            rowClass: function (row) { return row.status === 'FAIL' ? null : 'hb-tree-dim'; },   // 볼 것 = 실패 행
            onSelect: pick,
            notify: notify
        });
        return Promise.all([sp.ready, grid.ready]);
    }).then(function () { return load(); }).then(null, function (err) { notify(err && err.message, true); });
})();
