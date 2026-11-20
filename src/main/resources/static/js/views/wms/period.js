// @ts-check
/* 기간 마감 (wms-period) — P4 1부 W5 (docs/30 4.5, D10). 공장 × 연도 12달 hb.grid(docs/40 §10.2 D1 — /grid·/columns, 상태 칸 배지)
   → 오른쪽: 마감 전 점검(경고) + 마감 / 재개(사유). 마감·재개 뒤에는 그리드를 다시 읽고 같은 월을 다시 고른다.
   마감·재개 판정(지난 달까지만·버전·권한)은 서버. 마감된 달의 원장 입력을 막는 것은 원장 트리거. 공통 hb.* 는 고치지 않는다. */
/**
 * @typedef {{closeId?: number | null, plantCd: string, period: string, status?: string | null, closedAt?: string | null,
 *   closedBy?: string | null, reopenedAt?: string | null, reopenedBy?: string | null, reason?: string | null,
 *   rowVer?: number | null}} WmsPeriodRow
 * @typedef {{plantCd: string, period: string, openCounts: number, lastMismatch?: number | null, lastReconcileAt?: string | null,
 *   future: boolean}} WmsPeriodCheck
 */
(function () {
    'use strict';

    var API = '/api/wms/periods';
    var ACTIONS = (document.body.getAttribute('data-actions') || '').split(',');
    var PROG = document.body.getAttribute('data-prog') || 'wms-period';
    /** 상태 → hb-status 톤(docs/40 부록 A) — 마감 = 끝(success), 재개 = 확인 필요(warning), 열림 = neutral. 그리드는 행 없는 달을 OPEN 으로 준다
     * @type {Record<string, HB.StatusTone>} */
    var TONE = {CLOSED: 'success', REOPENED: 'warning', OPEN: 'neutral'};
    var msg = /** @type {HTMLElement} */ (document.getElementById('msg'));
    var search = /** @type {HTMLFormElement} */ (document.getElementById('search'));
    var plantSel = /** @type {HTMLSelectElement} */ (document.getElementById('plantSel'));
    /** 월 목록 그리드 @type {HB.Grid | null} */
    var grid = null;
    var info = /** @type {HTMLElement} */ (document.getElementById('periodInfo'));
    var checkList = /** @type {HTMLElement} */ (document.getElementById('checkList'));
    var closeBox = /** @type {HTMLElement | null} */ (document.getElementById('closeBox'));
    var reopenBox = /** @type {HTMLElement | null} */ (document.getElementById('reopenBox'));
    var reopenForm = /** @type {HTMLFormElement | null} */ (document.getElementById('reopenForm'));
    /** @type {WmsPeriodRow | null} */
    var current = null;
    /** @type {HB.SearchPanel} */
    var sp;

    /** @param {string | null | undefined} text @param {boolean} [error] */
    function notify(text, error) {
        msg.textContent = text || '';
        msg.classList.toggle('hb-msg-error', !!error);
    }

    /** @param {HB.ApiError} err */
    function errText(err) {
        return err.errors && err.errors.length ? err.errors.map(function (x) { return x.message; }).join(' / ') : err.message;
    }

    /** @param {string} p yyyyMM */
    function ym(p) { return p.substring(0, 4) + '-' + p.substring(4); }

    /** @param {string | null | undefined} st 상태 코드(OPEN·null = 열림) */
    function stText(st) {
        return st === 'CLOSED' ? hb.t('js.wmsPeriod.closed') : st === 'REOPENED' ? hb.t('js.wmsPeriod.reopened') : hb.t('js.wmsPeriod.open');
    }

    /** 그리드 다시 읽기 — keep 월이 이 쪽에 있으면 다시 골라 onSelect 가 상세(점검·버전)를 새로 채운다 @param {string} [keep] */
    function load(keep) {
        return grid ? grid.reload(false, keep) : Promise.resolve();
    }

    /** @param {WmsPeriodRow | null} r */
    function show(r) {
        current = r;
        checkList.textContent = '';
        if (closeBox) { closeBox.hidden = true; }
        if (reopenBox) { reopenBox.hidden = true; }
        if (!r) {
            info.textContent = hb.t('js.wmsPeriod.selectFirst');
            return;
        }
        info.textContent = hb.t('js.wmsPeriod.title', r.plantCd, ym(r.period), stText(r.status));
        if (r.status === 'CLOSED') {
            if (reopenBox && reopenForm) {
                reopenBox.hidden = false;
                /** @type {HTMLInputElement} */ (reopenForm.querySelector('[data-field="reason"]')).value = '';
                hb.showFieldErrors(reopenForm, []);
            }
            return;
        }
        var period = r.period;
        hb.latest('wmsPeriodCheck', hb.api(API + '/check?plantCd=' + encodeURIComponent(r.plantCd) + '&period=' + period)).then(function (res) {
            var c = /** @type {WmsPeriodCheck} */ (res.data);
            if (!current || current.period !== period) { return; }
            /** @param {string} text @param {boolean} warn */
            var item = function (text, warn) { checkList.appendChild(hb.el('li', warn ? 'hb-warn' : null, text)); };
            if (c.future) { item(hb.t('js.wmsPeriod.notPast'), true); }
            item(c.openCounts ? hb.t('js.wmsPeriod.openCounts', c.openCounts) : hb.t('js.wmsPeriod.noOpenCounts'), c.openCounts > 0);
            if (c.lastMismatch == null) {
                item(hb.t('js.wmsPeriod.neverReconciled'), true);
            } else {
                item(hb.t('js.wmsPeriod.lastReconcile', hb.fmt.datetime(c.lastReconcileAt), c.lastMismatch), c.lastMismatch > 0);
            }
            if (closeBox) { closeBox.hidden = c.future; }
        }, function (err) { if (!err.stale) { notify(errText(err), true); } });
    }

    var closeBtn = /** @type {HTMLButtonElement | null} */ (document.getElementById('closeBtn'));
    if (closeBtn) {
        var cb = closeBtn;
        cb.addEventListener('click', function () {
            if (!current) { return; }
            var r = current;
            var warn = checkList.querySelectorAll('li.hb-warn').length;
            hb.dialog.confirm({title: hb.t('js.wmsPeriod.dlg.close', ym(r.period)), message: hb.t('js.wmsPeriod.confirmClose', ym(r.period), warn),
                ok: hb.t('js.wmsPeriod.dlg.closeOk')}).then(function (yes) {
                if (!yes) { return; }
                hb.busy(cb, function () {
                    return hb.api(API + '/close', {method: 'POST', body: {plantCd: r.plantCd, period: r.period, rowVer: r.rowVer}});
                }).then(function (res) {
                    if (!res) { return; }
                    return load(r.period).then(function () { notify(hb.t('js.wmsPeriod.didClose', ym(r.period))); });
                }, function (err) { notify(errText(err), true); if (err.status === 409) { load(r.period); } });
            });
        });
    }

    var reopenBtn = /** @type {HTMLButtonElement | null} */ (document.getElementById('reopenBtn'));
    if (reopenBtn && reopenForm) {
        var rb = reopenBtn;
        var rf = reopenForm;
        rb.addEventListener('click', function () {
            if (!current) { return; }
            var r = current;
            hb.showFieldErrors(rf, []);
            var reason = /** @type {HTMLInputElement} */ (rf.querySelector('[data-field="reason"]')).value;
            hb.dialog.confirm({title: hb.t('js.wmsPeriod.dlg.reopen', ym(r.period)), message: hb.t('js.wmsPeriod.confirmReopen', ym(r.period)),
                ok: hb.t('js.wmsPeriod.dlg.reopenOk'), danger: true}).then(function (yes) {
                if (!yes) { return; }
                hb.busy(rb, function () {
                    return hb.api(API + '/reopen', {method: 'POST', body: {plantCd: r.plantCd, period: r.period, rowVer: r.rowVer, reason: reason}});
                }).then(function (res) {
                    if (!res) { return; }
                    hb.dirty.clear(rf);
                    return load(r.period).then(function () { notify(hb.t('js.wmsPeriod.didReopen', ym(r.period))); });
                }, function (err) {
                    notify(errText(err), true);
                    hb.showFieldErrors(rf, err.errors);
                    if (err.status === 409) { load(r.period); }
                });
            });
        });
        rf.addEventListener('submit', function (e) { e.preventDefault(); });
        hb.dirty.watch(rf);
    }

    hb.loadMessages().then(function () {
        hb.page.init();
        return Promise.all([hb.loadSession(), hb.api(API + '/plants')]);
    }).then(function (r) {
        /** @type {{plantCd: string, plantNm: string}[]} */ (r[1].data).forEach(function (p) {
            var o = /** @type {HTMLOptionElement} */ (hb.el('option', null, p.plantCd + ' · ' + p.plantNm));
            o.value = p.plantCd;
            plantSel.appendChild(o);
        });
        sp = hb.searchPanel(search, {onSearch: function () { show(null); return grid ? grid.reload(true) : null; }});
        grid = hb.grid('#periodGrid', {
            progCd: PROG, id: 'main', key: 'period',
            url: API + '/grid', columnsUrl: API + '/columns', exportUrl: API + '/export',
            actions: ACTIONS, params: function () { return sp.values(); },
            format: {
                period: function (v) { return ym(String(v)); },
                status: function (v) { return stText(/** @type {string} */ (v)); }
            },
            status: {status: TONE},                                                         // 상태 칸 = 배지
            rowClass: function (row) { return row.status === 'CLOSED' ? null : 'hb-tree-dim'; },   // 마감 안 된 달은 흐리게(기존 표와 같음)
            onSelect: function (row) { show(/** @type {WmsPeriodRow} */ (row)); hb.page.openDetail(); },
            notify: notify
        });
        return sp.ready;
    }).then(function () {
        // 올해를 기본값으로도 기록 — [초기화]·링크 되돌림이 빈 칸(필수 오류)이 아니라 올해로(#769 setDefault). 링크로 온 해는 그대로
        var linked = sp.values().year;
        sp.setDefault('year', hb.dates.today().substring(0, 4));
        if (linked) { sp.setValue('year', linked); }
        show(null);
        if (!grid) { return null; }
        var g = grid;
        return g.ready.then(function () { return g.reload(true); });
    }).then(null, function (err) { notify(err && err.message, true); });
})();
