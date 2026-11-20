// @ts-check
/*
 * 납기 회신 (srm-ack) — SRM 1부 S5 (docs/31 4.5).
 * 조회(발주일 기간 필수) → 발주 표(유효 회신 상태·기한) → 상세: 발주 행별 분할 줄(약속 납기·수량) 입력 → [회신 저장] = 새 회신 묶음,
 * [거절로 기록](사유) / 발주 행·유효 회신 표 / 회신 이력(고르면 그 묶음의 약속 줄). 엑셀 회신 올리기(템플릿 SRM_PO_ACK).
 */
(function () {
    'use strict';

    /**
     * @typedef {{poId: number, poNo: string, rev: number, poDate: string, custCd: string, custNm: string, status: string,
     *   issuedAt: string|null, lineCnt: number, ackSeq: number|null, ackStatus: string|null, ackOn: string|null,
     *   ackDueDate: string|null, overdue: boolean}} SrmAckPo
     * @typedef {{poLineId: number, lineNo: number, itemCd: string, itemNm: string, unitCd: string, qty: string, openQty: string,
     *   dueDate: string, lineStatus: string, promisedQty: string|null, promisedFirst: string|null, promisedLast: string|null}} SrmAckPoLine
     * @typedef {{ackId: number, ackSeq: number, rev: number, source: string, ackStatus: string, rejectReason: string|null,
     *   ackByNm: string|null, ackOn: string, remark: string|null, createdAt: string, createdBy: string, valid: boolean, oldRev: boolean}} SrmAckRow
     * @typedef {{ackId: number, poLineId: number, lineNo: number, itemCd: string, promisedDate: string, promisedQty: string,
     *   remark: string|null}} SrmAckLineRow
     * @typedef {{po: SrmAckPo, lines: SrmAckPoLine[], acks: SrmAckRow[], ackLines: SrmAckLineRow[]}} SrmAckDetail
     * @typedef {{key: string, date: string, qty: string}} SrmAckSplit
     * @typedef {{lineNo: number, kind: string}} SrmAckWarning
     */

    /** SRM 화면 공용 표시(srm-ui.js) */
    var srmUi = /** @type {any} */ (window).srmUi;
    var API = '/api/srm/acks';
    var ACTIONS = (document.body.getAttribute('data-actions') || '').split(',');
    var CAN_SAVE = ACTIONS.indexOf('SAVE') >= 0;
    var msg = /** @type {HTMLElement} */ (document.getElementById('msg'));
    var form = /** @type {HTMLFormElement} */ (document.getElementById('ackForm'));
    var search = /** @type {HTMLFormElement} */ (document.getElementById('search'));
    var lineBox = /** @type {HTMLElement} */ (document.getElementById('lines'));
    /** @type {SrmAckDetail|null} */
    var current = null;
    /** 발주 행 id → 분할 줄 @type {Record<string, SrmAckSplit[]>} */
    var splits = {};
    var keySeq = 0;
    /** @type {HB.SearchPanel} */
    var sp;
    /** @type {HB.Grid} */
    var grid;
    /** @type {Record<string, string>} */
    var poStatusNames = {};
    /** @type {Record<string, string>} */
    var ackStatusNames = {};

    /** @param {string|null|undefined} text @param {boolean} [error] */
    function notify(text, error) {
        msg.textContent = text || '';
        msg.classList.toggle('hb-msg-error', !!error);
    }

    /** @param {string} n */
    function field(n) { return /** @type {HTMLInputElement} */ (form.querySelector('[data-field="' + n + '"]')); }

    /** 유효 회신 칸 — 회신 상태 배지 + 묶음 번호, 기한 지남은 지연 배지 @param {SrmAckPo} p */
    function ackCell(p) {
        var f = document.createDocumentFragment();
        if (p.ackStatus) {
            f.appendChild(srmUi.withBadge(null, srmUi.badge('ack', p.ackStatus, ackStatusNames[p.ackStatus]), '#' + p.ackSeq));
        } else {
            f.appendChild(document.createTextNode(hb.t('js.srm.ack.none')));
        }
        if (p.overdue) { f.appendChild(document.createTextNode(' ')); f.appendChild(srmUi.flag('danger', hb.t('js.srm.ack.overdue'))); }
        return f;
    }

    // ---------------------------------------------------------------- 목록

    /** 조회 조건(검색 패널) — grid 가 page·size·sort 를 붙인다 */
    function params() {
        var v = sp.values();
        /** @type {Record<string, unknown>} */
        var q = {};
        ['from', 'to', 'state', 'keyword'].forEach(function (k) { if (v[k]) { q[k] = v[k]; } });
        return q;
    }

    /** 행 → 상세(서버에서 다시 읽는다) @param {number} id */
    function openRow(id) {
        return hb.latest('ackDetail', hb.api(API + '/pos/' + id)).then(function (r) {
            show(r.data);
            hb.page.openDetail();
        }, function (err) { if (!(err && err.stale)) { notify(err.message, true); } });
    }

    /** 목록 다시 읽기 — selectId 가 이 쪽에 있으면 grid 가 골라 상세를 연다, 없어도(다른 쪽·조건 밖) 방금 회신한 발주는 상세로 @param {number} [selectId] */
    function load(selectId) {
        return grid.reload(true, selectId || undefined).then(function () {
            var sel = grid.selected();
            if (selectId && (!sel || sel.poId !== selectId)) { return openRow(selectId); }
            if (!selectId) { show(null); }
        });
    }

    // ---------------------------------------------------------------- 상세

    /** @param {SrmAckDetail|null} d */
    function show(d) {
        current = d;
        hb.showFieldErrors(form, []);
        var p = d ? d.po : null;
        var open = !!p && (p.status === 'ISSUED' || p.status === 'ACKED');
        /** @type {HTMLElement} */ (document.getElementById('formMode')).textContent = p
            ? hb.t('js.srm.ack.viewing', p.poNo, p.rev, p.custNm) : hb.t('js.srm.ack.select');
        var stateLine = /** @type {HTMLElement} */ (document.getElementById('stateLine'));
        stateLine.textContent = '';
        if (p) {
            stateLine.appendChild(srmUi.badge('po', p.status, poStatusNames[p.status]));
            stateLine.appendChild(document.createTextNode(' · '));
            stateLine.appendChild(ackCell(p));
            var tail = '';
            if (p.ackDueDate) { tail += ' · ' + hb.t('js.srm.ack.dueLine', p.ackDueDate); }
            if (!open) { tail += ' · ' + hb.t('js.srm.ack.closedPo'); }
            stateLine.appendChild(document.createTextNode(tail));
        }
        /** @type {HTMLElement} */ (document.getElementById('entryBox')).hidden = !(open && CAN_SAVE);
        /** @type {HTMLElement} */ (document.getElementById('poLinesBox')).hidden = !d;
        /** @type {HTMLElement} */ (document.getElementById('histBox')).hidden = !(d && d.acks.length);
        field('ackByNm').value = '';
        field('ackOn').value = '';
        field('remark').value = '';
        // 분할 줄 시작값: 유효 회신이 있으면 그 약속, 없으면 요구 납기·열린 수량(협력사가 그대로 받는 경우가 보통)
        splits = {};
        if (d) {
            var valid = d.acks.filter(function (a) { return a.valid && a.ackStatus !== 'REJECTED'; })[0];
            d.lines.forEach(function (l) {
                if (l.lineStatus !== 'OPEN') { return; }
                var mine = valid ? d.ackLines.filter(function (x) { return x.ackId === valid.ackId && x.poLineId === l.poLineId; }) : [];
                splits[String(l.poLineId)] = mine.length
                    ? mine.map(function (x) { return {key: 's' + (++keySeq), date: x.promisedDate, qty: x.promisedQty}; })
                    : [{key: 's' + (++keySeq), date: l.dueDate, qty: l.openQty}];
            });
        }
        renderPoLines();
        renderSplits([]);
        renderHistory();
        hb.dirty.clear();
    }

    function renderPoLines() {
        var body = /** @type {HTMLElement} */ (document.querySelector('#poLines tbody'));
        body.textContent = '';
        (current ? current.lines : []).forEach(function (l) {
            var tr = hb.el('tr', l.lineStatus === 'OPEN' ? null : 'hb-muted');
            var promised = l.promisedQty === null ? hb.t('js.srm.ack.noPromise')
                : l.promisedQty + ' · ' + l.promisedFirst + (l.promisedLast !== l.promisedFirst ? ' ~ ' + l.promisedLast : '');
            var late = !!l.promisedLast && l.promisedLast > l.dueDate;
            srmUi.cells(tr, [String(l.lineNo), l.itemCd + ' · ' + l.itemNm, l.openQty + ' ' + l.unitCd, l.dueDate,
                srmUi.withBadge(promised, late ? srmUi.flag('danger', hb.t('js.srm.ack.lateFlag')) : null)]);
            body.appendChild(tr);
        });
    }

    /** 보낼 줄 순서(서버 행 키 = 배열 순번) @returns {{lineId: string, split: SrmAckSplit}[]} */
    function flat() {
        /** @type {{lineId: string, split: SrmAckSplit}[]} */
        var out = [];
        (current ? current.lines : []).forEach(function (l) {
            (splits[String(l.poLineId)] || []).forEach(function (s) { out.push({lineId: String(l.poLineId), split: s}); });
        });
        return out;
    }

    /** @param {HB.FieldError[]} errors */
    function renderSplits(errors) {
        lineBox.textContent = '';
        /** @type {Record<string, HB.FieldError[]>} 줄 키 → 오류 */
        var byKey = {};
        var order = flat();
        (errors || []).forEach(function (e) {
            if (e.rowKey !== null && e.rowKey !== undefined && order[Number(e.rowKey)]) {
                var k = order[Number(e.rowKey)].split.key;
                (byKey[k] = byKey[k] || []).push(e);
            }
        });
        var top = (errors || []).filter(function (e) { return e.field === 'lines'; });
        /** @type {HTMLElement} */ (document.getElementById('linesErrorBox')).hidden = !top.length;
        /** @type {HTMLElement} */ (document.getElementById('linesError')).textContent = top.length ? top[0].message : '';
        if (!current) { return; }
        current.lines.forEach(function (l) {
            var list = splits[String(l.poLineId)];
            if (!list) { return; }
            var block = hb.el('div', 'hb-form');
            block.appendChild(hb.el('p', 'hb-muted hb-wide', hb.t('js.srm.ack.lineHead', l.lineNo, l.itemNm, l.openQty + ' ' + l.unitCd, l.dueDate)));
            list.forEach(function (s, i) {
                var errs = byKey[s.key] || [];
                /** @param {'date'|'qty'} name @param {string} label @param {string} type @param {string} server */
                function cell(name, label, type, server) {
                    var lab = hb.el('label');
                    lab.appendChild(hb.el('span', null, label));
                    var inp = hb.el('input');
                    inp.type = type;
                    inp.value = s[name] || '';
                    if (name === 'qty') { inp.inputMode = 'decimal'; inp.maxLength = 24; }
                    var err = errs.filter(function (e) { return e.field === server; })[0];
                    if (err) { inp.setAttribute('aria-invalid', 'true'); }
                    inp.addEventListener('input', function () { s[name] = inp.value; hb.dirty.mark(); });
                    lab.appendChild(inp);
                    if (err) { lab.appendChild(hb.el('span', 'hb-field-error', err.message)); }
                    block.appendChild(lab);
                }
                cell('date', hb.t('js.srm.ack.col.date', i + 1), 'date', 'promisedDate');
                cell('qty', hb.t('js.srm.ack.col.qty', l.unitCd), 'text', 'promisedQty');
                errs.filter(function (e) { return e.field !== 'promisedDate' && e.field !== 'promisedQty'; }).forEach(function (e) {
                    block.appendChild(hb.el('p', 'hb-wide hb-field-error', e.message));
                });
                if (list.length > 1) {
                    var bar0 = hb.el('div', 'hb-toolbar hb-wide');
                    var del = hb.el('button', 'hb-btn-ghost', hb.t('js.srm.ack.removeSplit'));
                    del.type = 'button';
                    del.addEventListener('click', function () {
                        list.splice(list.indexOf(s), 1);
                        hb.dirty.mark();
                        renderSplits([]);
                    });
                    bar0.appendChild(del);
                    block.appendChild(bar0);
                }
            });
            var bar = hb.el('div', 'hb-toolbar hb-wide');
            var add = hb.el('button', 'hb-btn-secondary', hb.t('js.srm.ack.addSplit'));
            add.type = 'button';
            add.addEventListener('click', function () {
                list.push({key: 's' + (++keySeq), date: '', qty: ''});
                hb.dirty.mark();
                renderSplits([]);
            });
            bar.appendChild(add);
            var none = hb.el('button', 'hb-btn-ghost', hb.t('js.srm.ack.noSplit'));
            none.type = 'button';
            none.addEventListener('click', function () {
                list.length = 0;
                hb.dirty.mark();
                renderSplits([]);
            });
            if (list.length) { bar.appendChild(none); }
            block.appendChild(bar);
            lineBox.appendChild(block);
        });
    }

    function renderHistory() {
        var ul = /** @type {HTMLElement} */ (document.getElementById('histList'));
        ul.textContent = '';
        if (!current) { return; }
        var d = current;
        d.acks.forEach(function (a) {
            var li = hb.el('li', a.valid ? null : 'hb-muted');
            var parts = ['#' + a.ackSeq, hb.t('js.srm.ack.rev', a.rev), a.source === 'IMPORT' ? hb.t('js.srm.ack.byImport') : a.source === 'PORTAL' ? hb.t('js.srm.ack.byPortal') : hb.t('js.srm.ack.byHand'),
                ackStatusNames[a.ackStatus] || a.ackStatus, a.ackOn, a.ackByNm || '', hb.fmt.datetime(a.createdAt) + ' ' + a.createdBy];
            if (a.valid) { parts.push(hb.t('js.srm.ack.valid')); }
            if (a.oldRev) { parts.push(hb.t('js.srm.ack.oldRev')); }
            var btn = hb.el('button', 'hb-btn-ghost', parts.filter(Boolean).join(' · '));
            btn.type = 'button';
            btn.setAttribute('aria-expanded', 'false');
            var detail = hb.el('ul');
            detail.hidden = true;
            if (a.rejectReason) { detail.appendChild(hb.el('li', null, hb.t('js.srm.ack.rejected', a.rejectReason))); }
            if (a.remark) { detail.appendChild(hb.el('li', null, a.remark)); }
            d.ackLines.filter(function (x) { return x.ackId === a.ackId; }).forEach(function (x) {
                detail.appendChild(hb.el('li', null, hb.t('js.srm.ack.histLine', x.lineNo, x.itemCd, x.promisedDate, x.promisedQty)));
            });
            btn.addEventListener('click', function () {
                detail.hidden = !detail.hidden;
                btn.setAttribute('aria-expanded', detail.hidden ? 'false' : 'true');
            });
            li.appendChild(btn);
            li.appendChild(detail);
            ul.appendChild(li);
        });
    }

    // ---------------------------------------------------------------- 저장

    /** @param {SrmAckWarning[]} w */
    function warningText(w) {
        if (!w.length) { return ''; }
        return ' ' + w.map(function (x) {
            return x.kind === 'LATE' ? hb.t('js.srm.ack.warnLate', x.lineNo) : hb.t('js.srm.ack.warnShort', x.lineNo);
        }).join(' · ');
    }

    /** @param {HTMLButtonElement} btn @param {string|null} rejectReason 거절이면 사유(대화상자에서 받음) */
    function send(btn, rejectReason) {
        if (!current) { return; }
        var d = current;
        var order = flat();
        var reject = rejectReason !== null;
        var body = {rev: d.po.rev, ackOn: field('ackOn').value || null, ackByNm: field('ackByNm').value, remark: field('remark').value,
            reject: reject, rejectReason: rejectReason,
            lines: reject ? [] : order.map(function (o) {
                return {poLineId: Number(o.lineId), promisedDate: o.split.date || null, promisedQty: o.split.qty};
            })};
        hb.busy(btn, function () { return hb.api(API + '/pos/' + d.po.poId, {method: 'POST', body: body}); }).then(function (res) {
            if (!res) { return; }
            hb.dirty.clear();
            var r = res.data;
            return load(d.po.poId).then(function () {
                notify(hb.t('js.srm.ack.saved', r.ackSeq, ackStatusNames[r.ackStatus] || r.ackStatus) + warningText(r.warnings));
            });
        }, function (err) {
            notify(err.errors && err.errors.length ? err.errors[0].message : err.message, true);
            hb.showFieldErrors(form, (err.errors || []).filter(function (/** @type {HB.FieldError} */ e) {
                return e.rowKey === null || e.rowKey === undefined;
            }));
            renderSplits(err.errors || []);
        });
    }

    var saveBtn = /** @type {HTMLButtonElement|null} */ (document.getElementById('saveBtn'));
    var rejectBtn = /** @type {HTMLButtonElement|null} */ (document.getElementById('rejectBtn'));
    if (saveBtn) { var sb = saveBtn; sb.addEventListener('click', function () { send(sb, null); }); }
    if (rejectBtn) {
        var rb = rejectBtn;
        // 거절 = 협력사가 받지 않았다는 기록 — 사유 필수, 대화상자 취소는 아무 일 없음
        rb.addEventListener('click', function () {
            hb.dialog.prompt({title: hb.t('js.srm.dlg.ackReject'), message: hb.t('js.srm.ack.confirmReject'), label: hb.t('js.srm.ack.rejectLabel'),
                ok: hb.t('js.srm.dlg.ackRejectOk'), required: true, maxLength: 500, danger: true}).then(function (reason) {
                if (reason !== null) { send(rb, reason); }
            });
        });
    }

    var importBtn = document.getElementById('importBtn');
    if (importBtn) {
        importBtn.addEventListener('click', function () {
            hb.importDialog({templateCd: 'SRM_PO_ACK', onDone: function () {
                var id = current ? current.po.poId : undefined;
                load(id);
            }});
        });
    }

    // ---------------------------------------------------------------- 시작

    hb.dirty.watch(form);
    search.addEventListener('submit', function (e) { e.preventDefault(); });
    hb.loadMessages().then(function () { return hb.loadSession(); }).then(function () {
        hb.page.init();
        sp = hb.searchPanel(search, {onSearch: function () {
            return srmUi.leaveOk().then(function (/** @type {boolean} */ ok) { return ok ? load() : null; });
        }});
        return Promise.all([sp.ready, hb.codes('PO_STATUS'), hb.codes('PO_ACK_STATUS')]);
    }).then(function (r) {
        poStatusNames = r[1];
        ackStatusNames = r[2];
        grid = hb.grid('#ackGrid', {
            progCd: 'srm-ack', id: 'main', key: 'poId',
            url: API + '/pos/grid', columnsUrl: API + '/pos/columns', exportUrl: API + '/pos/export', actions: ACTIONS, params: params,
            beforeSelect: function () { return srmUi.leaveOk(); },
            status: {
                ackStatus: srmUi.TONES.ack,
                ackDueDate: function (v, row) { return row.overdue ? 'danger' : null; },   // 기한 지남 = 회신 기한 칸 위험 배지
                status: srmUi.TONES.po
            },
            rowClass: function (row) { return row.status === 'ISSUED' || row.status === 'ACKED' ? null : 'hb-tree-dim'; },
            onSelect: function (row) { openRow(Number(row.poId)); },
            notify: notify
        });
        return grid.ready.then(function () { return load(); });
    }).then(null, function (err) { notify(err && err.message, true); });
})();
