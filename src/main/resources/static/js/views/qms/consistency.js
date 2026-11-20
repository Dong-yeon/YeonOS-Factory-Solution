// @ts-check
/*
 * 품질 정합 점검 (qms-consistency, 읽기 전용) — P3 QMS Q3 (docs/29).
 * 점검 종류(전체·하나)를 골라 서버가 찾은 항목을 표로. 바로가기 = 특성 관리·검사계획 관리 화면. 서버 데이터는 textContent 로만.
 */
/**
 * @typedef {{checkCd: string, refId: number, targetCd: string, targetNm?: string | null, detail?: string | null}} QmsFinding
 */
(function () {
    'use strict';

    /** QMS 화면 공용 — 상태 배지·대화상자(qms-ui.js) */
    var qmsUi = /** @type {QmsUi} */ (/** @type {any} */ (window).qmsUi);

    var API = '/api/qms/consistency';
    var msg = /** @type {HTMLElement} */ (document.getElementById('msg'));
    var search = /** @type {HTMLFormElement} */ (document.getElementById('search'));
    var summary = /** @type {HTMLElement} */ (document.getElementById('summary'));
    /** @type {HB.SearchPanel} */
    var sp;
    /** @type {Record<string, string>} */
    var checkNames = {};

    /** @param {string | null | undefined} text @param {boolean} [error] */
    function notify(text, error) {
        msg.textContent = text || '';
        msg.classList.toggle('hb-msg-error', !!error);
    }

    /** @param {{list: QmsFinding[], truncated: boolean}} r */
    function render(r) {
        var tbody = /** @type {HTMLElement} */ (document.querySelector('#resultTable tbody'));
        tbody.textContent = '';
        r.list.forEach(function (f) {
            var tr = hb.el('tr');
            tr.appendChild(hb.el('td', null, checkNames[f.checkCd] || f.checkCd));
            tr.appendChild(hb.el('td', null, f.targetCd));
            tr.appendChild(hb.el('td', null, f.targetNm || ''));
            tr.appendChild(hb.el('td', 'hb-wrap', f.detail || ''));
            var td = hb.el('td');
            var a = /** @type {HTMLAnchorElement} */ (hb.el('a', null, f.checkCd === 'SPECIAL_NOT_IN_PLAN' ? hb.t('js.qms.cons.toChar') : hb.t('js.qms.cons.toPlan')));
            a.href = f.checkCd === 'SPECIAL_NOT_IN_PLAN' ? '/page/mst-char' : '/page/qms-insp-plan';
            td.appendChild(a);
            tr.appendChild(td);
            tbody.appendChild(tr);
        });
        summary.hidden = false;
        summary.setAttribute('data-status', r.list.length ? 'DUE_SOON' : 'OK');
        summary.textContent = (r.list.length ? hb.t('js.qms.cons.count', r.list.length) : hb.t('js.qms.cons.none'))
            + (r.truncated ? ' · ' + hb.t('js.qms.cons.truncated') : '');
    }

    function run() {
        var v = sp.values();
        notify('');
        return hb.latest('cons', hb.api(API + (v.checkCd ? '?checkCd=' + encodeURIComponent(v.checkCd) : ''))).then(function (res) {
            render(res.data);
        }, function (err) { if (err.code !== 'STALE') { notify(err.message, true); } });
    }

    var exportBtn = /** @type {HTMLButtonElement | null} */ (document.getElementById('exportBtn'));
    if (exportBtn) {
        var eb = exportBtn;
        eb.addEventListener('click', function () {
            var v = sp.values();
            hb.busy(eb, function () { return hb.download(API + '/export', {checkCd: v.checkCd || null}); })
                .then(null, function (err) { notify(err.message, true); });
        });
    }

    search.addEventListener('submit', function (e) { e.preventDefault(); });
    hb.loadMessages().then(function () {
        hb.page.init();
        sp = hb.searchPanel(search, {onSearch: run});
        return Promise.all([hb.codes('QMS_CHECK'), hb.loadSession(), sp.ready]);
    }).then(function (r) {
        checkNames = r[0];
        return run();
    }).then(null, function (err) { notify(err && err.message, true); });
})();
