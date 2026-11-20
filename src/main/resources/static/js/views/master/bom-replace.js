// @ts-check
/*
 * BOM 일괄 대체 (mst-bom-replace) — P1 3c. 미리보기 → 고르기(+ 변경 번호·사유) → 반영(작성 중 리비전)
 *   → 반영 결과 표에서 고른 것 일괄 확정(/api/master/boms/release-batch — 건마다 확정 검사, 실패는 그 건만, G5·docs/60 R1).
 * 시작일 기본 = 내일(docs/60 R3). 반영 뒤 미리보기를 다시 계산하지 않고 "반영함"으로 남긴다(R4).
 */
/**
 * @typedef {{action: 'DRAFT' | 'NEW_REV' | 'SKIP', sourceBomId: number | null, parentCd: string, parentNm: string, altNo: number,
 *   sourceRev?: string | null, sourceStatus?: string | null, newRev?: string | null, reason?: string | null,
 *   lineCnt?: number | null, unitWarn?: boolean, [field: string]: any}} ReplaceRow
 * @typedef {{fromItemCd: string, fromItemNm: string, toItemCd: string, toItemNm: string, validFrom: string,
 *   rows: ReplaceRow[], [field: string]: any}} ReplacePreview
 * @typedef {{parentCd: string, parentNm: string, bomId: number, rev: string, action: string, lines: number, rowVer: number}} ReplaceDone
 * @typedef {{bomId: number, itemCd: string | null, rev: string | null, ok: boolean, code?: string | null, message?: string | null}} ReleaseOutcome
 */
(function () {
    'use strict';

    var API = '/api/master/bom-replace';
    var msg = /** @type {HTMLElement} */ (document.getElementById('msg'));
    var search = /** @type {HTMLFormElement} */ (document.getElementById('search'));
    var summary = /** @type {HTMLElement} */ (document.getElementById('summary'));
    var tbody = /** @type {HTMLTableSectionElement} */ (document.querySelector('#rows tbody'));
    var empty = /** @type {HTMLElement} */ (document.getElementById('empty'));
    var allChk = /** @type {HTMLInputElement} */ (document.getElementById('allChk'));
    var runBtn = /** @type {HTMLButtonElement | null} */ (document.getElementById('runBtn'));   // SAVE 권한 없으면 없음
    var releaseBtn = /** @type {HTMLButtonElement | null} */ (document.getElementById('releaseBtn'));
    var doneSection = /** @type {HTMLElement} */ (document.getElementById('doneSection'));
    var doneBody = /** @type {HTMLTableSectionElement} */ (document.querySelector('#done tbody'));
    var doneSummary = /** @type {HTMLElement} */ (document.getElementById('doneSummary'));
    var allDone = /** @type {HTMLInputElement} */ (document.getElementById('allDone'));
    var changeNo = /** @type {HTMLInputElement | null} */ (document.getElementById('changeNo'));
    var changeReason = /** @type {HTMLInputElement | null} */ (document.getElementById('changeReason'));
    /** 마지막 반영 결과(확정 대상) @type {ReplaceDone[]} */
    var doneList = [];
    /** bomId → 일괄 확정 결과 @type {Record<number, ReleaseOutcome>} */
    var outcomes = {};
    /** 조회 조건 패널 @type {HB.SearchPanel} */
    var sp;
    /** 마지막으로 받은 미리보기 @type {ReplacePreview | null} */
    var preview = null;
    /** BOM 상태 코드 → 이름 @type {Record<string, string>} */
    var statusNames = {};

    /** @param {string | null | undefined} text @param {boolean} [error] */
    function notify(text, error) {
        msg.textContent = text || '';
        msg.classList.toggle('hb-msg-error', !!error);
    }

    /** @param {string | null | undefined} code */
    function reasonText(code) {
        switch (code) {
        case 'CYCLE': return hb.t('js.bomReplace.reason.CYCLE');
        case 'DRAFT_OTHER': return hb.t('js.bomReplace.reason.DRAFT_OTHER');
        case 'NO_EFFECTIVE': return hb.t('js.bomReplace.reason.NO_EFFECTIVE');
        case 'SAME_DAY': return hb.t('js.bomReplace.reason.SAME_DAY');
        case 'FUTURE_REV': return hb.t('js.bomReplace.reason.FUTURE_REV');
        default: return code || '';
        }
    }

    /** @param {ReplaceRow} r */
    function actionText(r) {
        if (r.action === 'DRAFT') { return hb.t('js.bomReplace.action.DRAFT', r.sourceRev); }
        if (r.action === 'NEW_REV') { return hb.t('js.bomReplace.action.NEW_REV', r.newRev, r.sourceRev); }
        return hb.t('js.bomReplace.action.SKIP', reasonText(r.reason));
    }

    function selected() {
        return Array.from(tbody.querySelectorAll('input[type=checkbox]:checked')).map(function (c) {
            return Number(/** @type {HTMLInputElement} */ (c).value);
        });
    }

    function refreshRun() {
        if (runBtn) { runBtn.disabled = selected().length === 0; }
    }

    /** 기본 시작일 = 내일(회사 날짜) */
    function tomorrow() { return hb.dates.add(hb.dates.today(), 1); }

    // ---------- 반영 결과 · 일괄 확정 ----------
    function doneSelected() {
        return Array.from(doneBody.querySelectorAll('input[type=checkbox]:checked')).map(function (c) {
            return Number(/** @type {HTMLInputElement} */ (c).value);
        });
    }

    function refreshRelease() {
        if (releaseBtn) { releaseBtn.disabled = doneSelected().length === 0; }
    }

    function renderDone() {
        doneBody.textContent = '';
        var open = 0, ok = 0, fail = 0;
        doneList.forEach(function (d) {
            var res = outcomes[d.bomId];
            var tr = hb.el('tr', res ? (res.ok ? 'hb-cmp-added' : 'hb-cmp-removed') : null);
            var td = hb.el('td');
            if (!res || !res.ok) {   // 확정된 건은 다시 고를 수 없다. 실패한 건은 고쳐서 다시 확정할 수 있게 남긴다(버전이 그대로면)
                var c = hb.el('input');
                c.type = 'checkbox';
                c.value = String(d.bomId);
                c.checked = !res;
                c.setAttribute('aria-label', d.parentCd + ' ' + d.rev);
                c.addEventListener('change', refreshRelease);
                td.appendChild(c);
                open++;
            }
            tr.appendChild(td);
            tr.appendChild(hb.el('td', null, d.parentCd));
            tr.appendChild(hb.el('td', null, d.parentNm));
            tr.appendChild(hb.el('td', null, d.rev));
            tr.appendChild(hb.el('td', 'hb-num', String(d.lines)));
            var text = !res ? hb.t('js.bomReplace.result.draft') : res.ok ? hb.t('js.bomReplace.result.released')
                : hb.t('js.bomReplace.result.failed', res.message || res.code || '');
            tr.appendChild(hb.el('td', null, text));
            if (res) { if (res.ok) { ok++; } else { fail++; } }
            doneBody.appendChild(tr);
        });
        doneSection.hidden = doneList.length === 0;
        allDone.checked = open > 0 && doneSelected().length === open;
        allDone.disabled = open === 0;
        doneSummary.textContent = hb.t('js.bomReplace.doneSummary', doneList.length, ok, fail);
        refreshRelease();
    }

    allDone.addEventListener('change', function () {
        doneBody.querySelectorAll('input[type=checkbox]').forEach(function (c) { /** @type {HTMLInputElement} */ (c).checked = allDone.checked; });
        refreshRelease();
    });

    if (releaseBtn) {
        var relBtn = releaseBtn;
        relBtn.addEventListener('click', function () {
            var ids = doneSelected();
            if (!ids.length) { return; }
            var items = doneList.filter(function (d) { return ids.indexOf(d.bomId) >= 0; })
                .map(function (d) { return {bomId: d.bomId, rowVer: d.rowVer}; });   // 묻기 전에 대상을 잡는다
            hb.dialog.confirm({title: hb.t('js.bomReplace.dlg.release', items.length), message: hb.t('js.bomReplace.confirmRelease', items.length),
                ok: hb.t('js.bomReplace.dlg.releaseOk')}).then(function (yes) {
                if (!yes) { return; }
                hb.busy(relBtn, function () {
                    return hb.api('/api/master/boms/release-batch', {method: 'POST', body: {items: items}});
                }).then(function (res) {
                    if (!res) { return; }
                    /** @type {ReleaseOutcome[]} */
                    var list = res.data;
                    var okN = 0;
                    list.forEach(function (o) { outcomes[o.bomId] = o; if (o.ok) { okN++; } });
                    renderDone();
                    notify(hb.t('js.bomReplace.released', okN, list.length - okN), list.length - okN > 0);
                }, function (err) { notify(err.message, true); });
            });
        });
    }
    /** @type {HTMLButtonElement} */ (document.getElementById('toMgtBtn')).addEventListener('click', function () {
        bomUi.open('mst-bom', {status: 'DRAFT'});
    });

    /** @param {ReplacePreview} p */
    function render(p) {
        tbody.textContent = '';
        var can = 0;
        p.rows.forEach(function (r) {
            var tr = hb.el('tr', r.action === 'SKIP' ? 'hb-cmp-removed' : null);
            var td = hb.el('td');
            if (r.action !== 'SKIP') {
                var c = hb.el('input');
                c.type = 'checkbox';
                c.value = String(r.sourceBomId);
                c.checked = true;
                c.setAttribute('aria-label', r.parentCd);
                c.addEventListener('change', refreshRun);
                td.appendChild(c);
                can++;
            }
            tr.appendChild(td);
            tr.appendChild(hb.el('td', null, r.parentCd + (r.altNo > 1 ? ' (' + hb.t('js.bomReplace.alt', r.altNo) + ')' : '')));
            tr.appendChild(hb.el('td', null, r.parentNm));
            tr.appendChild(hb.el('td', null, r.sourceRev ? r.sourceRev + ' · ' + (statusNames[r.sourceStatus || ''] || r.sourceStatus) : ''));
            tr.appendChild(hb.el('td', null, actionText(r)));
            tr.appendChild(hb.el('td', 'hb-num', r.lineCnt ? String(r.lineCnt) : ''));
            tr.appendChild(hb.el('td', r.unitWarn ? 'hb-warn' : null, r.unitWarn ? hb.t('js.bomReplace.unitWarn') : ''));
            tbody.appendChild(tr);
        });
        empty.hidden = p.rows.length > 0;
        if (!p.rows.length) { empty.textContent = hb.t('js.bomReplace.noUse', p.fromItemCd); }
        allChk.checked = can > 0;
        allChk.disabled = can === 0;
        summary.textContent = hb.t('js.bomReplace.summary', p.fromItemCd, p.fromItemNm, p.toItemCd, p.toItemNm, p.validFrom,
            p.rows.length, can);
        refreshRun();
    }

    function load() {
        var v = sp.values();
        notify('');
        var q = 'fromItemCd=' + encodeURIComponent(v.fromItemCd || '') + '&toItemCd=' + encodeURIComponent(v.toItemCd || '')
            + (v.validFrom ? '&validFrom=' + encodeURIComponent(v.validFrom) : '');
        return hb.latest('preview', hb.api(API + '/preview?' + q)).then(function (res) {
            /** @type {ReplacePreview} */
            var p = res.data;
            preview = p;
            render(p);
        }, function (err) {
            preview = null;
            tbody.textContent = '';
            summary.textContent = '';
            empty.hidden = false;
            refreshRun();
            // 없는 품목은 품목 코드가 들어간 문구로, 그 밖은 서버 문구 + 칸 오류
            var t = bomUi.errorText(err, 'fromItemCd', v.fromItemCd);
            if (t === err.message) { t = bomUi.errorText(err, 'toItemCd', v.toItemCd); }
            notify(t !== err.message ? t
                : err.message + (err.errors ? ' ' + err.errors.map(function (/** @type {HB.FieldError} */ e) { return e.message; }).join(' ') : ''), true);
        });
    }

    allChk.addEventListener('change', function () {
        tbody.querySelectorAll('input[type=checkbox]').forEach(function (c) { /** @type {HTMLInputElement} */ (c).checked = allChk.checked; });
        refreshRun();
    });

    if (runBtn) {
        var btn = runBtn;
        btn.addEventListener('click', function () {
            var ids = selected();
            var p = preview;   // 반영 중에 다시 미리보기를 해도 누른 시점의 조건으로 보낸다
            if (!p || !ids.length) { return; }
            var body = {fromItemCd: p.fromItemCd, toItemCd: p.toItemCd, validFrom: p.validFrom, bomIds: ids,
                changeNo: changeNo ? changeNo.value : null, changeReason: changeReason ? changeReason.value : null};
            hb.dialog.confirm({title: hb.t('js.bomReplace.dlg.apply', ids.length), message: hb.t('js.bomReplace.confirm', ids.length, p.fromItemCd, p.toItemCd), ok: hb.t('js.bomReplace.dlg.applyOk')}).then(function (yes) {
                if (!yes) { return; }
                hb.busy(btn, function () {
                    return hb.api(API, {method: 'POST', body: body});
                }).then(function (res) {
                    if (!res) { return; }
                    /** @type {ReplaceDone[]} */
                    var list = res.data;
                    markApplied(list);
                    doneList = list;
                    outcomes = {};
                    renderDone();
                    notify(hb.t('js.bomReplace.done', list.length));
                    doneSection.scrollIntoView({block: 'nearest'});
                }, function (err) { notify(err.message, true); markRowErrors(err); });
            });
        });
    }

    /** 반영한 미리보기 행은 "반영함 → 리비전" 으로 바꾸고 고르기를 없앤다(다시 계산하면 "건너뜀"으로 보여 실패처럼 읽혔다 — R4)
     * @param {ReplaceDone[]} list */
    function markApplied(list) {
        /** @type {Record<string, ReplaceDone>} */
        var by = {};
        list.forEach(function (d) { by[d.parentCd] = d; });
        tbody.querySelectorAll('tr').forEach(function (tr) {
            var c = /** @type {HTMLInputElement | null} */ (tr.querySelector('input[type=checkbox]'));
            var cells = tr.querySelectorAll('td');
            var code = cells[1] ? cells[1].textContent || '' : '';
            var d = by[code.split(' (')[0]];
            if (c) { c.remove(); }
            if (d && cells[4]) {
                cells[4].textContent = hb.t('js.bomReplace.applied', d.rev);
                tr.className = 'hb-cmp-added';
            }
        });
        allChk.checked = false;
        allChk.disabled = true;
        refreshRun();
    }

    /** 변경 번호·사유 칸 오류(길이) @param {HB.ApiError} err */
    function markRowErrors(err) {
        (err.errors || []).forEach(function (/** @type {HB.FieldError} */ e) {
            var el = e.field === 'changeNo' ? changeNo : e.field === 'changeReason' ? changeReason : null;
            if (el) { el.setAttribute('aria-invalid', 'true'); }
        });
    }

    search.addEventListener('submit', function (e) { e.preventDefault(); });
    hb.loadMessages().then(function () {
        hb.page.init();
        sp = hb.searchPanel(search, {onSearch: function () { return load(); },
            onReset: function () { if (sp) { sp.setValues({validFrom: tomorrow()}); } }});
        if (!new URLSearchParams(window.location.search).has('q.validFrom')) { sp.setValues({validFrom: tomorrow()}); }
        return Promise.all([hb.loadSession(), sp.ready, hb.codes('BOM_STATUS')]);
    }).then(function (r) {
        statusNames = r[2];
        // 역전개에서 넘어오면(바꿀 자품목만 채움) 새 자품목 칸으로
        var to = /** @type {HTMLInputElement | null} */ (search.querySelector('[name=toItemCd]'));
        if (sp.values().fromItemCd && to && !to.value) { to.focus(); }
    }, function (err) { notify(err && err.message, true); });
})();
