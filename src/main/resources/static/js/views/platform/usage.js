// @ts-check
/* 활용 현황 (sys-usage) — 기간은 hb.dateRange(회사 시간대, 어제까지, 서버도 DateRanges 로 다시 검사) */
/**
 * @typedef {[field: string, titleKey: string, num?: number]} UsageCol  열: 필드, 제목 키, 숫자 열이면 1
 * @typedef {{days: HB.Row[], programs: HB.Row[]}} UsageResult
 */
(function () {
    'use strict';
    var msg = /** @type {HTMLElement} */ (document.getElementById('msg'));
    var form = /** @type {HTMLFormElement} */ (document.getElementById('search'));
    var periodHost = /** @type {HTMLElement} */ (document.getElementById('period'));

    /** @param {HB.Row[]} rows @param {UsageCol[]} cols */
    function table(rows, cols) {
        var t = hb.el('table', 'hb-table');
        var head = hb.el('tr');
        cols.forEach(function (c) {
            head.appendChild(hb.el('th', c[2] ? 'hb-num' : null, hb.t(c[1])));
        });
        t.appendChild(head);
        rows.forEach(function (r) {
            var tr = hb.el('tr');
            cols.forEach(function (c) {
                tr.appendChild(hb.el('td', c[2] ? 'hb-num' : null, c[2] ? hb.fmt.num(r[c[0]]) : r[c[0]]));
            });
            t.appendChild(tr);
        });
        if (!rows.length) {
            var tr = hb.el('tr');
            var td = hb.el('td', 'hb-muted', hb.t('js.usage.empty'));
            td.colSpan = cols.length;
            tr.appendChild(td);
            t.appendChild(tr);
        }
        return t;
    }

    /** 조회 패널이 검사를 마친 값으로 부른다(조회 중 다시 누름은 패널이 막음) @param {HB.SearchValues} q */
    function search(q) {
        return hb.api('/api/platform/usage?from=' + encodeURIComponent(q.from) + '&to=' + encodeURIComponent(q.to)).then(function (res) {
            /** @type {UsageResult} */
            var data = res.data;
            var days = /** @type {HTMLElement} */ (document.getElementById('days'));
            days.textContent = '';
            days.appendChild(table(data.days, [['date', 'js.usage.col.date'], ['users', 'js.usage.col.users', 1],
                ['logins', 'js.usage.col.logins', 1], ['pageViews', 'js.usage.col.pageViews', 1],
                ['actions', 'js.usage.col.actions', 1]]));
            var progs = /** @type {HTMLElement} */ (document.getElementById('programs'));
            progs.textContent = '';
            progs.appendChild(table(data.programs, [['progCd', 'js.usage.col.prog'],
                ['views', 'js.usage.col.pageViews', 1], ['maxDailyUsers', 'js.usage.col.users', 1]]));
            msg.textContent = '';
        }, function (err) {
            /** @type {HB.FieldError | undefined} */
            var fe = (err.errors || []).filter(/** @param {HB.FieldError} e */ function (e) { return e.field === 'from' || e.field === 'to'; })[0];
            var period = periodHost.hbDateRange;   // 조회 패널이 만들 때 붙인다
            if (fe && fe.field && period) {
                period.showServerError(fe.field, fe.message);   // 서버 기간 오류(정책 상한 등)는 컨트롤에
            } else {
                msg.textContent = err.message;
            }
        });
    }

    form.addEventListener('submit', function (e) { e.preventDefault(); });   // 패널이 준비되기 전 Enter 로 화면이 다시 읽히지 않게
    hb.loadMessages().then(function () { return hb.loadSession(); }).then(function () {
        hb.page.init();
        // 세션(회사 시간대)을 받은 뒤 만든다 — "어제"·프리셋이 회사 날짜로 계산되게. 기간 검사(형식·순서·상한)는 패널이 조회 전에
        var sp = hb.searchPanel(form, {onSearch: search});
        sp.search();
    });
})();
