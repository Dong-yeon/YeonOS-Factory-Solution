// @ts-check
/* COPQ 품질 비용 (qms-copq) — docs/29_P3 29장 1단계. 월별 4구분 합계 + 원장 내역(종결 NCR 처리) + 수동 비용 행. 회계 원장 아님(관리용 추정).
   엑셀(34장) = 지금 조회 조건의 달 범위로 원장 줄 + 수동 행 한 시트. NCR 줄은 [NCR n건] 으로 번호를 펼쳐 NCR 화면으로(36장) */
/**
 * @typedef {{month: string | null, prevention: number | null, appraisal: number | null, internal: number | null, external: number | null,
 *   total: number | null, unpricedCnt: number}} CopqMonth
 * @typedef {{month: string, category: string, source: string, itemCd: string, itemNm: string, qty: number, unitCd: string,
 *   stdPrice: number | null, lineCnt: number, amount: number | null, reason: string | null, ncrNos?: string | null}} CopqLine
 * @typedef {{costId: number, costMonth: string, category: string, costItem: string, amount: number, note: string | null, rowVer: number}} CopqCost
 * @typedef {{fromMonth: string, toMonth: string, months: CopqMonth[], sum: CopqMonth, lines: CopqLine[], costs: CopqCost[]}} CopqView
 */
(function () {
    'use strict';

    var API = '/api/qms/copq';
    var ACTIONS = (document.body.getAttribute('data-actions') || '').split(',');
    var CATS = ['prevention', 'appraisal', 'internal', 'external'];
    /** 원장 출처 → 문구 키(키는 글자 그대로 — i18n 가드) @type {Record<string, string>} */
    var SOURCE_KEYS = {NCR_SCRAP: 'js.qms.copq.srcScrap', NCR_REWORK: 'js.qms.copq.srcRework', NCR_SORT: 'js.qms.copq.srcSort',
        RTN_SCRAP: 'js.qms.copq.srcRtnScrap', RTN_REWORK: 'js.qms.copq.srcRtnRework', RTN_SORT: 'js.qms.copq.srcRtnSort',
        PRD_SCRAP: 'js.qms.copq.srcPrdScrap', PRD_REWORK: 'js.qms.copq.srcPrdRework', PRD_REPAIR: 'js.qms.copq.srcPrdRepair',
        PRD_RW_SCRAP: 'js.qms.copq.srcPrdRwScrap'};
    var msg = /** @type {HTMLElement} */ (document.getElementById('msg'));
    var search = /** @type {HTMLFormElement} */ (document.getElementById('search'));
    var form = /** @type {HTMLFormElement | null} */ (document.getElementById('costForm'));
    var costMode = document.getElementById('costMode');
    /** @type {HB.SearchPanel} */
    var sp;
    /** @type {HB.NumberControl | undefined} */
    var amount;
    /** @type {HB.SelectControl | undefined} */
    var category;
    /** 고치는 중인 수동 행 @type {CopqCost | null} */
    var current = null;
    /** @type {Record<string, string>} */
    var catNames = {};
    /** 펼친 줄에 한 번에 보일 NCR 번호 수(넘으면 "외 n건") */
    var NCR_SHOW = 50;

    /** @param {string | null | undefined} text @param {boolean} [error] */
    function notify(text, error) {
        msg.textContent = text || '';
        msg.classList.toggle('hb-msg-error', !!error);
    }

    /** 금액 글자 — null 은 빈칸(0원과 다름) @param {number | null | undefined} v */
    function money(v) {
        return v === null || v === undefined ? '' : hb.numbers.format(v, 2);
    }

    /** @param {HTMLElement} tr @param {unknown} text @param {string} [cls] */
    function cell(tr, text, cls) {
        var td = hb.el('td', cls || null, text);
        tr.appendChild(td);
        return td;
    }

    /** @param {CopqMonth} m @param {string} label */
    function monthRow(m, label) {
        var tr = hb.el('tr');
        cell(tr, label);
        CATS.forEach(function (c) {
            var v = /** @type {number | null} */ (m[/** @type {'prevention'} */ (c)]);
            var td = cell(tr, money(v), 'hb-num');
            if (v === null) { td.title = hb.t('js.qms.copq.noPriceCell'); td.textContent = '—'; }
        });
        var t = cell(tr, money(m.total), 'hb-num');
        if (m.total === null) { t.title = hb.t('js.qms.copq.noPriceCell'); t.textContent = '—'; }
        cell(tr, m.unpricedCnt ? hb.t('js.qms.copq.unpricedCnt', m.unpricedCnt) : '', 'hb-num');
        return tr;
    }

    /** @param {CopqView} v */
    function render(v) {
        var mb = /** @type {HTMLElement} */ (document.querySelector('#monthTable tbody'));
        var mf = /** @type {HTMLElement} */ (document.querySelector('#monthTable tfoot'));
        mb.replaceChildren();
        mf.replaceChildren();
        v.months.forEach(function (m) { mb.appendChild(monthRow(m, m.month || '')); });
        if (v.months.length > 1) {
            var s = monthRow(v.sum, hb.t('js.qms.copq.sum'));
            s.className = 'hb-qms-copq-sum';
            mf.appendChild(s);
        }

        var lb = /** @type {HTMLElement} */ (document.querySelector('#lineTable tbody'));
        lb.replaceChildren();
        if (!v.lines.length) {
            var e = hb.el('tr');
            var td = cell(e, hb.t('js.qms.copq.noLines'), 'hb-muted');
            td.colSpan = 7;
            lb.appendChild(e);
        }
        v.lines.forEach(function (l) {
            var tr = hb.el('tr');
            cell(tr, l.month);
            cell(tr, catNames[l.category] || l.category);
            cell(tr, SOURCE_KEYS[l.source] ? hb.t(SOURCE_KEYS[l.source]) : l.source);
            cell(tr, l.itemCd + ' · ' + l.itemNm);
            cell(tr, hb.numbers.format(l.qty, null) + ' ' + l.unitCd, 'hb-num');
            cell(tr, l.stdPrice === null ? '' : hb.numbers.format(l.stdPrice, null), 'hb-num');
            var a = cell(tr, money(l.amount), 'hb-num');
            if (l.reason) {
                a.textContent = l.reason === 'UNIT' ? hb.t('js.qms.copq.reasonUnit') : hb.t('js.qms.copq.reasonNoPrice', l.lineCnt);
                a.classList.add('hb-muted');
            }
            lb.appendChild(tr);
            if (l.ncrNos) { ncrToggle(tr, lb, l.ncrNos.split(',')); }
        });

        var cb = /** @type {HTMLElement} */ (document.querySelector('#costTable tbody'));
        cb.replaceChildren();
        if (!v.costs.length) {
            var ce = hb.el('tr');
            var ctd = cell(ce, hb.t('js.qms.copq.noCosts'), 'hb-muted');
            ctd.colSpan = 6;
            cb.appendChild(ce);
        }
        v.costs.forEach(function (c) {
            var tr = hb.el('tr');
            cell(tr, c.costMonth);
            cell(tr, catNames[c.category] || c.category);
            cell(tr, c.costItem);
            cell(tr, money(c.amount), 'hb-num');
            cell(tr, c.note || '');
            var act = cell(tr, '');
            if (form && ACTIONS.indexOf('SAVE') >= 0) {
                var edit = hb.el('button', 'hb-btn-secondary', hb.t('js.qms.copq.edit'));
                /** @type {HTMLButtonElement} */ (edit).type = 'button';
                edit.addEventListener('click', function () { show(c); });
                act.appendChild(edit);
            }
            if (ACTIONS.indexOf('DELETE') >= 0) {
                var del = hb.el('button', 'hb-btn-danger-outline', hb.t('js.qms.copq.delete'));
                /** @type {HTMLButtonElement} */ (del).type = 'button';
                del.addEventListener('click', function () { remove(c, /** @type {HTMLButtonElement} */ (del)); });
                act.appendChild(del);
            }
            cb.appendChild(tr);
        });
    }

    /**
     * NCR 줄: 출처 칸에 [NCR n건] 단추, 누르면 아래 줄에 번호 링크(NCR 화면 ?ncrNo=)를 펼친다 — 어느 NCR 이 이 금액인지(36장)
     * @param {HTMLElement} tr @param {HTMLElement} tbody @param {string[]} nos
     */
    function ncrToggle(tr, tbody, nos) {
        var btn = /** @type {HTMLButtonElement} */ (hb.el('button', 'hb-btn-ghost', hb.t('js.qms.copq.ncrCount', nos.length)));
        btn.type = 'button';
        btn.setAttribute('aria-expanded', 'false');
        var srcCell = /** @type {HTMLElement} */ (tr.children[2]);
        srcCell.appendChild(document.createTextNode(' '));
        srcCell.appendChild(btn);
        var sub = hb.el('tr', 'hb-qms-copq-ncrs');
        sub.hidden = true;
        var td = /** @type {HTMLTableCellElement} */ (cell(sub, ''));
        td.colSpan = 7;
        nos.slice(0, NCR_SHOW).forEach(function (no) {
            var link = /** @type {HTMLAnchorElement} */ (hb.el('a', 'hb-link-btn', no));
            link.href = '/page/qms-ncr?ncrNo=' + encodeURIComponent(no);
            td.appendChild(link);
            td.appendChild(document.createTextNode(' '));
        });
        if (nos.length > NCR_SHOW) { td.appendChild(hb.el('span', 'hb-muted', hb.t('js.qms.copq.ncrMore', nos.length - NCR_SHOW))); }
        tbody.appendChild(sub);
        btn.addEventListener('click', function () {
            sub.hidden = !sub.hidden;
            btn.setAttribute('aria-expanded', String(!sub.hidden));
        });
    }

    function load() {
        var v = sp.values();
        var q = '?fromMonth=' + encodeURIComponent(String(v.fromMonth || '')) + '&toMonth=' + encodeURIComponent(String(v.toMonth || ''));
        return hb.latest('copq', hb.api(API + q)).then(function (r) {
            render(/** @type {CopqView} */ (r.data));
            notify('');
        }, function (err) { notify(err && err.message, true); });
    }

    // ---------- 수동 비용 폼 ----------
    /** @param {string} n */
    function field(n) {
        return /** @type {HTMLInputElement | HTMLSelectElement} */ (/** @type {HTMLFormElement} */ (form).querySelector('[data-field="' + n + '"]'));
    }

    /** @param {CopqCost | null} c */
    function show(c) {
        if (!form || !amount || !category) { return; }
        current = c;
        hb.showFieldErrors(form, []);
        field('costMonth').value = c ? c.costMonth : String(sp.values().toMonth || '');
        category.setValue(c ? c.category : '');
        field('costItem').value = c ? c.costItem : '';
        amount.setValue(c ? String(c.amount) : '');
        field('note').value = c && c.note ? c.note : '';
        if (costMode) { costMode.textContent = c ? hb.t('js.qms.copq.editing', c.costMonth, c.costItem) : hb.t('js.qms.copq.creating'); }
        if (c) { field('costItem').focus(); }
    }

    /** @param {HTMLButtonElement} btn */
    function save(btn) {
        if (!form || !amount) { return; }
        if (!amount.validate().ok) { field('amount').focus(); return; }
        var body = {costMonth: field('costMonth').value, category: field('category').value, costItem: field('costItem').value,
            amount: amount.value(), note: field('note').value, rowVer: current ? current.rowVer : null};
        var id = current ? current.costId : null;
        hb.busy(btn, function () {
            return id !== null ? hb.api(API + '/costs/' + id, {method: 'PUT', body: body}) : hb.api(API + '/costs', {method: 'POST', body: body});
        }).then(function (r) {
            if (r === null) { return; }
            show(null);
            return load().then(function () { notify(hb.t('js.qms.copq.saved')); });
        }, function (err) {
            hb.showFieldErrors(/** @type {HTMLFormElement} */ (form), err && err.errors);
            notify(err && err.message, true);
        });
    }

    /** @param {CopqCost} c @param {HTMLButtonElement} btn */
    function remove(c, btn) {
        hb.dialog.confirm({title: hb.t('js.qms.copq.deleteTitle'), message: hb.t('js.qms.copq.deleteMsg', c.costMonth, c.costItem),
            ok: hb.t('js.qms.copq.delete'), danger: true}).then(function (ok) {
            if (!ok) { return null; }
            return hb.busy(btn, function () { return hb.api(API + '/costs/' + c.costId + '/delete', {method: 'POST', body: {rowVer: c.rowVer}}); })
                .then(function () {
                    if (current && current.costId === c.costId) { show(null); }
                    return load().then(function () { notify(hb.t('js.qms.copq.deleted')); });
                });
        }).then(null, function (err) { notify(err && err.message, true); });
    }

    hb.loadMessages().then(function () {
        sp = hb.searchPanel(search, {onSearch: function () { return load(); }, onLink: function (q) {
            // 끝 달만 온 링크 = 처음 여는 탭과 같게 시작 달도 그 달(기본값 이번 달로 돌아가 시작 > 끝이 되지 않게)
            if (q.toMonth && !q.fromMonth) { sp.setValue('fromMonth', q.toMonth); }
            return false;
        }});
        if (form) {
            amount = hb.number(/** @type {HTMLInputElement} */ (field('amount')));
            category = hb.select(/** @type {HTMLSelectElement} */ (field('category')));
        }
        return Promise.all([hb.loadSession(), sp.ready, hb.codeNames('QMS_COPQ_CAT'), category ? category.ready : null]);
    }).then(function (r) {
        catNames = r[2];
        var now = hb.dates.today().slice(0, 7);
        // 기본 달 = 이번 달 — setDefault 로 기록해 링크 되돌림·[초기화]도 이번 달로(#769). 주소 ?q. 로 온 달은 그대로 둔다
        var v = sp.values();
        var linkTo = v.toMonth ? String(v.toMonth) : '';
        var linkFrom = v.fromMonth ? String(v.fromMonth) : '';
        sp.setDefault('toMonth', now);
        sp.setDefault('fromMonth', now);
        if (linkTo) { sp.setValue('toMonth', linkTo); }
        sp.setValue('fromMonth', linkFrom || linkTo || now);
        var exportBtn = /** @type {HTMLButtonElement | null} */ (document.getElementById('exportBtn'));
        if (exportBtn) {
            var eb = exportBtn;
            eb.addEventListener('click', function () {
                var v = sp.values();
                var body = {fromMonth: String(v.fromMonth || ''), toMonth: String(v.toMonth || '')};
                hb.busy(eb, function () { return hb.download(API + '/export', body); }).then(null, function (err) { notify(err && err.message, true); });
            });
        }
        var newBtn = document.getElementById('costNewBtn');
        if (newBtn) { newBtn.addEventListener('click', function () { show(null); }); }
        var saveBtn = document.getElementById('costSaveBtn');
        if (saveBtn) {
            var b = /** @type {HTMLButtonElement} */ (saveBtn);
            b.addEventListener('click', function () { save(b); });
        }
        show(null);
        return load();
    }).then(null, function (err) { notify(err && err.message, true); });
})();
