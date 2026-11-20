/*
 * 비가동 사유 검토 (cms-state-review) — CMS 2부 C7 (docs/33 11.2, D18).
 * 공장·기간(공장 현지 날짜, 최대 31일) → 상태 구간 표(실효 분류 = 사후 사유 우선) → 상세: 사후 사유 지정·무효, 고장 작업지시 연결·무효.
 * 사후 사유는 닫힌 구간만, 같은 분류(비계획 ↔ 계획 정지만 재분류). 연결은 실효 분류가 비계획 정지(UDT)인 구간만. 최종 판정은 서버.
 */
(function () {
    'use strict';

    var API = '/api/cms/state-review';
    var ACTIONS = (document.body.getAttribute('data-actions') || '').split(',');
    var CAN_SAVE = ACTIONS.indexOf('SAVE') >= 0;
    var CAN_VOID = ACTIONS.indexOf('DELETE') >= 0;
    var msg = document.getElementById('msg');
    var search = document.getElementById('search');
    var plantSel = document.getElementById('plantSel');
    var noteForm = document.getElementById('noteForm');
    var linkForm = document.getElementById('linkForm');
    var noteBody = document.querySelector('#notes tbody');
    var linkBody = document.querySelector('#links tbody');
    var info = document.getElementById('ivInfo');
    var sp, grid, current = null, zone = null;
    var catNames = {}, reasons = [];

    function notify(text, error) {
        msg.textContent = text || '';
        msg.classList.toggle('hb-msg-error', !!error);
    }

    function hm(v) { return v ? String(v).substring(0, 16).replace('T', ' ') : ''; }

    function nfield(n) { return noteForm.querySelector('[data-field="' + n + '"]'); }

    function lfield(n) { return linkForm.querySelector('[data-field="' + n + '"]'); }

    function catName(c) { return catNames[c] || c; }

    function dt(v) { return v ? hb.fmt.datetime(v, zone) : ''; }

    /** 서버 규칙과 같은 거름: 같은 분류, 비계획 ↔ 계획 정지만 서로 */
    function reasonFits(logCat, reasonCat) {
        return reasonCat === logCat || ((reasonCat === 'UDT' || reasonCat === 'SDT') && (logCat === 'UDT' || logCat === 'SDT'));
    }

    function reasonText(r) {
        if (r.noteReasonCd) { return r.noteReasonCd + ' · ' + r.noteReasonNm + ' ' + hb.t('js.cms.stateReview.afterMark'); }
        if (r.logReasonCd) { return r.logReasonCd + ' · ' + r.logReasonNm; }
        return r.stateCat === 'PRD' || r.stateCat === 'UNKNOWN' ? '' : hb.t('js.cms.stateReview.noReason');
    }

    function emptyRow(body, cols, text) {
        var tr = hb.el('tr');
        var td = hb.el('td', 'hb-muted', text);
        td.colSpan = cols;
        tr.appendChild(td);
        body.appendChild(tr);
    }

    function catText(r) {
        return catName(r.effCat) + (r.effCat !== r.stateCat ? ' ' + hb.t('js.cms.stateReview.reclassified', catName(r.stateCat)) : '');
    }

    /** 조회 조건(검색 패널) — grid 가 page·size·sort 를 붙인다 */
    function params() {
        var v = sp.values();
        var q = {};
        ['plantCd', 'from', 'to', 'keyword', 'effCat', 'pending', 'reasonCd'].forEach(function (k) { if (v[k]) { q[k] = v[k]; } });
        return q;
    }

    /** 목록 다시 읽기 — 고른 구간(selectId)은 이 쪽에 없어도 상세로(사유 기록 뒤 등) */
    function load(selectId) {
        hb.showFieldErrors(search, []);
        return grid.reload(true, selectId || undefined).then(function () {
            var sel = grid.selected();
            if (selectId && (!sel || sel.stateLogId !== selectId)) { return open(selectId); }
            if (!selectId) { show(null); }
        });
    }

    function open(id) {
        return hb.latest('detail', hb.api(API + '/' + id)).then(function (res) {
            show(res.data);
            if (res.data.row.effCat === 'UDT') { return loadOptions(id); }
        }, function (err) { notify(err.message, true); });
    }

    function show(d) {
        current = d;
        zone = d ? d.timeZone : null;
        hb.showFieldErrors(noteForm, []);
        hb.showFieldErrors(linkForm, []);
        info.textContent = '';
        info.hidden = !d;
        document.getElementById('noteSection').hidden = !d;
        document.getElementById('linkSection').hidden = !d;
        if (!d) {
            document.getElementById('formMode').textContent = hb.t('js.cms.stateReview.select');
            return;
        }
        var r = d.row;
        document.getElementById('formMode').textContent = hb.t('js.cms.stateReview.viewing', r.equipCd, catText(r));
        [[hb.t('js.cms.stateReview.period'), String(r.startAt).substring(0, 16).replace('T', ' ') + ' ~ '
            + (r.endAt ? String(r.endAt).substring(0, 16).replace('T', ' ') : hb.t('js.cms.stateReview.open'))
            + (r.minutes == null ? '' : ' (' + hb.t('js.cms.stateReview.minutes', r.minutes) + ')')],
         [hb.t('js.cms.stateReview.logReason'), r.logReasonCd ? r.logReasonCd + ' · ' + r.logReasonNm : hb.t('js.cms.stateReview.noReason')],
         [hb.t('js.cms.stateReview.source'), r.source + (r.woNo ? ' · ' + r.woNo : '')]]
            .forEach(function (x) {
                info.appendChild(hb.el('dt', null, x[0]));
                info.appendChild(hb.el('dd', null, x[1]));
            });
        drawNotes(d);
        drawLinks(d);
    }

    function drawNotes(d) {
        var r = d.row;
        var sel = nfield('reasonCd');
        sel.textContent = '';
        var fits = reasons.filter(function (x) { return reasonFits(r.stateCat, x.stateCat); });
        fits.forEach(function (x) {
            var o = hb.el('option', null, catName(x.stateCat) + ' · ' + x.reasonCd + ' · ' + x.reasonNm);
            o.value = x.reasonCd;
            sel.appendChild(o);
        });
        nfield('noteText').value = '';
        var hint;
        if (r.stateCat === 'UNKNOWN') { hint = hb.t('js.cms.stateReview.hintUnknown'); }
        else if (!r.endAt) { hint = hb.t('js.cms.stateReview.hintOpen'); }
        else if (r.noteId) { hint = hb.t('js.cms.stateReview.hintNoted'); }
        else if (!fits.length) { hint = hb.t('js.cms.stateReview.hintNoReasons'); }
        else { hint = ''; }
        document.getElementById('noteHint').textContent = hint;
        var blocked = !CAN_SAVE || !!hint;
        noteForm.querySelectorAll('input, select').forEach(function (i) { i.disabled = blocked; });
        var btn = document.getElementById('noteBtn');
        if (btn) { btn.disabled = blocked; }
        noteBody.textContent = '';
        if (!d.notes.length) { emptyRow(noteBody, 5, hb.t('js.cms.stateReview.noNotes')); }
        d.notes.forEach(function (n) {
            var tr = hb.el('tr', n.voidYn === 'Y' ? 'hb-tree-dim' : null);
            [n.reasonCd + ' · ' + n.reasonNm, n.noteText || '', String(n.recordedAt).substring(0, 16).replace('T', ' ') + ' ' + n.recordedBy,
                n.voidYn === 'Y' ? hb.t('js.cms.stateReview.voided', n.voidReason) : hb.t('js.cms.stateReview.live')]
                .forEach(function (x) { tr.appendChild(hb.el('td', null, x)); });
            var td = hb.el('td');
            if (n.voidYn === 'N' && CAN_VOID) {
                var b = hb.el('button', 'hb-btn-secondary', hb.t('js.cms.stateReview.void'));
                b.type = 'button';
                b.addEventListener('click', function () { voidRow('notes', n.noteId, b); });
                td.appendChild(b);
            }
            tr.appendChild(td);
            noteBody.appendChild(tr);
        });
    }

    function drawLinks(d) {
        var r = d.row;
        var hint = r.effCat !== 'UDT' ? hb.t('js.cms.stateReview.hintNotUdt') : (r.linkId ? hb.t('js.cms.stateReview.hintLinked') : '');
        document.getElementById('linkHint').textContent = hint;
        var blocked = !CAN_SAVE || !!hint;
        var sel = lfield('mwoId');
        sel.textContent = '';
        sel.disabled = blocked;
        var btn = document.getElementById('linkBtn');
        if (btn) { btn.disabled = blocked; }
        linkBody.textContent = '';
        if (!d.links.length) { emptyRow(linkBody, 5, hb.t('js.cms.stateReview.noLinks')); }
        d.links.forEach(function (l) {
            var tr = hb.el('tr', l.voidYn === 'Y' ? 'hb-tree-dim' : null);
            var manual = l.downStartAt ? String(l.downStartAt).substring(0, 16).replace('T', ' ') + ' ~ '
                + (l.downEndAt ? String(l.downEndAt).substring(0, 16).replace('T', ' ') : '') : hb.t('js.cms.stateReview.noManual');
            [l.mwoNo + ' · ' + l.title, manual + diffText(r, l), String(l.recordedAt).substring(0, 16).replace('T', ' ') + ' ' + l.recordedBy,
                l.voidYn === 'Y' ? hb.t('js.cms.stateReview.voided', l.voidReason) : hb.t('js.cms.stateReview.live')]
                .forEach(function (x) { tr.appendChild(hb.el('td', null, x)); });
            var td = hb.el('td');
            if (l.voidYn === 'N' && CAN_VOID) {
                var b = hb.el('button', 'hb-btn-secondary', hb.t('js.cms.stateReview.void'));
                b.type = 'button';
                b.addEventListener('click', function () { voidRow('links', l.linkId, b); });
                td.appendChild(b);
            }
            tr.appendChild(td);
            linkBody.appendChild(tr);
        });
    }

    /** 수기 정지 구간(작업지시)과 수집 구간의 시작·끝 차이(분) — 대조만, 고치지 않는다 */
    function diffText(r, l) {
        if (!l.downStartAt || !r.endAt) { return ''; }
        var ds = Math.round((Date.parse(l.downStartAt) - Date.parse(r.startAt)) / 60000);
        var de = l.downEndAt ? Math.round((Date.parse(l.downEndAt) - Date.parse(r.endAt)) / 60000) : null;
        return ' ' + hb.t('js.cms.stateReview.diff', (ds > 0 ? '+' : '') + ds, de == null ? '-' : (de > 0 ? '+' : '') + de);
    }

    function loadOptions(id) {
        if (!CAN_SAVE || current.row.linkId) { return Promise.resolve(); }
        return hb.latest('options', hb.api(API + '/' + id + '/mwo-options')).then(function (res) {
            var sel = lfield('mwoId');
            sel.textContent = '';
            if (!res.data.length) {
                document.getElementById('linkHint').textContent = hb.t('js.cms.stateReview.hintNoMwo');
                sel.disabled = true;
                document.getElementById('linkBtn').disabled = true;
            }
            res.data.forEach(function (o) {
                var opt = hb.el('option', null, o.mwoNo + ' · ' + o.title + ' (' + dt(o.reportedAt) + ')');
                opt.value = String(o.mwoId);
                sel.appendChild(opt);
            });
        }, function () { /* 후보는 보조 */ });
    }

    function voidRow(kind, rowId, btn) {
        cmsUi.reason('js.cms.dlg.void', 'js.cms.stateReview.voidReason', 'js.cms.stateReview.void', true).then(function (reason) {
            if (reason === null) { return; }
            var id = current.row.stateLogId;
            hb.busy(btn, function () {
                return hb.api(API + '/' + id + '/' + kind + '/' + rowId + '/void', {method: 'POST', body: {reason: reason}});
            }).then(function (res) {
                if (!res) { return; }
                return load(id).then(function () { notify(hb.t('js.cms.stateReview.voidDone')); });
            }, function (err) {
                notify(err.errors && err.errors.length ? err.errors.map(function (x) { return x.message; }).join(' / ') : err.message, true);
            });
        });
    }

    var noteBtn = document.getElementById('noteBtn');
    if (noteBtn) {
        noteBtn.addEventListener('click', function () {
            var id = current.row.stateLogId;
            hb.busy(noteBtn, function () {
                return hb.api(API + '/' + id + '/notes', {method: 'POST', body: {reasonCd: nfield('reasonCd').value, noteText: nfield('noteText').value}});
            }).then(function (res) {
                if (!res) { return; }
                return load(id).then(function () { notify(hb.t('js.common.saved')); });
            }, function (err) {
                notify(err.message, true);
                hb.showFieldErrors(noteForm, err.errors);
            });
        });
    }

    var linkBtn = document.getElementById('linkBtn');
    if (linkBtn) {
        linkBtn.addEventListener('click', function () {
            var id = current.row.stateLogId;
            var mwo = lfield('mwoId').value;
            hb.busy(linkBtn, function () {
                return hb.api(API + '/' + id + '/links', {method: 'POST', body: {mwoId: mwo ? Number(mwo) : null}});
            }).then(function (res) {
                if (!res) { return; }
                return load(id).then(function () { notify(hb.t('js.common.saved')); });
            }, function (err) {
                notify(err.message, true);
                hb.showFieldErrors(linkForm, err.errors);
            });
        });
    }

    hb.loadMessages().then(function () {
        hb.page.init();
        return Promise.all([hb.loadSession(), hb.api(API + '/plants'), hb.codeList('STATE_CAT'), hb.api(API + '/reasons')]);
    }).then(function (r) {
        r[1].data.forEach(function (p) {
            var o = hb.el('option', null, p.plantCd + ' · ' + p.plantNm);
            o.value = p.plantCd;
            plantSel.appendChild(o);
        });
        r[2].forEach(function (c) {
            catNames[c.code] = c.name;
            var o = hb.el('option', null, c.name);
            o.value = c.code;
            document.getElementById('catSearch').appendChild(o);
        });
        reasons = r[3].data;
        var today = hb.dates.todayIn(hb.session().timeZone);
        document.getElementById('toIn').value = today;
        document.getElementById('fromIn').value = hb.dates.add(today, -6);
        sp = hb.searchPanel(search, {onSearch: function () { return load(); }});
        return sp.ready;
    }).then(function () {
        if (!plantSel.value) {
            notify(hb.t('js.cms.stateReview.noPlant'), true);
            return;
        }
        grid = hb.grid('#reviewGrid', {
            progCd: 'cms-state-review', id: 'main', key: 'stateLogId',
            url: API + '/grid', columnsUrl: API + '/columns', exportUrl: API + '/export', actions: ACTIONS, params: params,
            filters: {effCat: 'effCat'},
            format: {startAt: hm, endAt: hm},   // 공장 현지 시각(서버가 그 시간대로) — 열린 구간의 끝은 빈 칸
            onFilter: function (param, value) { sp.setValue(param, value); },
            status: {effCat: cmsUi.TONES.stateCat},
            onSelect: function (row) { open(row.stateLogId).then(function () { hb.page.openDetail(); }); },
            notify: notify
        });
        return grid.ready.then(function () { show(null); });
    }).then(null, function (err) { notify(err && err.message, true); });
})();
