/*
 * 자동창고 (wms-asrs) — 장비 연동 E4 (docs/65 23·25장). 보기: 요청(입고·출고 요청·보내기·긴급·라인·사건) / 용기(내용 정의·사건) /
 * 대사(차이 줄·바로 조정·확인). 판정(원장 반영·격리·늦은 상태·조정 원장)은 서버 — 화면은 정의·요청·조회만. 공통 hb.* 는 고치지 않는다.
 */
(function () {
    'use strict';

    var API = '/api/wms/asrs';
    var ACTIONS = (document.body.getAttribute('data-actions') || '').split(',');
    var CAN_SAVE = ACTIONS.indexOf('SAVE') >= 0;
    var msg = document.getElementById('msg');
    var search = document.getElementById('search');
    var tab = 'REQ', sp, current = null, diff = null;
    var names = {send: {}, line: {}, ctr: {}, event: {}, type: {}, reason: {}, kind: {}, diffStatus: {}};

    function notify(text, error) {
        msg.textContent = text || '';
        msg.classList.toggle('hb-msg-error', !!error);
    }

    function f(form, n) { return form.querySelector('[data-field="' + n + '"]'); }

    function el(tag, cls, text) { return hb.el(tag, cls, text); }

    function rowsInto(tbody, list, cells, onPick) {
        tbody.textContent = '';
        list.forEach(function (r) {
            var tr = el('tr');
            tr.tabIndex = 0;
            cells(r).forEach(function (c) { tr.appendChild(el('td', c.num ? 'hb-num' : null, c.v === null || c.v === undefined ? '' : String(c.v))); });
            if (onPick) {
                tr.addEventListener('click', function () { onPick(r, tr); });
                tr.addEventListener('keydown', function (ev) { if (ev.key === 'Enter') { onPick(r, tr); } });
            }
            tbody.appendChild(tr);
        });
    }

    function showOnly(id) {
        ['inForm', 'outForm', 'reqPane', 'ctrForm', 'rcnPane'].forEach(function (x) { document.getElementById(x).hidden = x !== id; });
    }

    function events(list) {
        var box = document.getElementById('eventBox');
        box.hidden = false;
        rowsInto(document.getElementById('eventRows'), list, function (e) {
            return [{v: hb.fmt.datetime(e.occurredAt)}, {v: (names.event[e.eventCd] || e.eventCd) + (e.ctrCd ? ' · ' + e.ctrCd : '')},
                {v: e.wesStatus}, {v: [e.reasonCd ? (names.reason[e.reasonCd] || e.reasonCd) : null, e.detail].filter(Boolean).join(' · ')}];
        });
    }

    function errText(err) { return (err.errors && err.errors[0] && err.errors[0].message) || err.message; }

    // ---------------------------------------------------------------- 요청

    function typeText(r) {
        var t = names.type[r.reqType] || r.reqType;
        return r.urgentYn === 'Y' ? t + ' · ' + hb.t('js.wmsAsrs.urgentMark') : t;
    }

    function loadReqs() {
        var a = sp.values().asrsId;
        return hb.api(API + '/reqs' + (a ? '?asrsId=' + encodeURIComponent(a) : '')).then(function (res) {
            rowsInto(document.getElementById('reqRows'), res.data, function (r) {
                return [{v: '#' + r.reqId}, {v: typeText(r)}, {v: names.send[r.sendState] || r.sendState},
                    {v: progressText(r.progress)}, {v: r.doneCnt + '/' + r.lineCnt, num: true},
                    {v: hb.fmt.datetime(r.createdAt)}];
            }, function (r) { openReq(r.reqId); hb.page.openDetail(); });
        });
    }

    /** 진행 상태 문구 — 키는 리터럴로(동적 조합 금지 규칙) */
    function progressText(p) {
        var t = {NOT_STARTED: hb.t('js.wmsAsrs.progress.NOT_STARTED'), IN_PROGRESS: hb.t('js.wmsAsrs.progress.IN_PROGRESS'),
            DONE: hb.t('js.wmsAsrs.progress.DONE'), PARTIAL: hb.t('js.wmsAsrs.progress.PARTIAL'),
            FAILED: hb.t('js.wmsAsrs.progress.FAILED'), CANCELLED: hb.t('js.wmsAsrs.progress.CANCELLED')};
        return t[p] || p;
    }

    function openReq(id) {
        return hb.latest('req', hb.api(API + '/reqs/' + id)).then(function (res) {
            current = res.data.head;
            showOnly('reqPane');
            document.getElementById('mode').textContent = hb.t('js.wmsAsrs.reqTitle', current.reqId, typeText(current),
                names.send[current.sendState] || current.sendState);
            rowsInto(document.getElementById('lineRows'), res.data.lines, function (l) {
                return [{v: l.lineNo, num: true}, {v: l.ctrCd || [l.itemCd, l.lotNo].filter(Boolean).join(' · ')}, {v: l.qty, num: true},
                    {v: names.line[l.status] || l.status}, {v: l.wesStatus}, {v: l.doneQty, num: true}];
            });
            var send = document.getElementById('sendBtn');
            var cancel = document.getElementById('cancelBtn');
            var urgent = document.getElementById('urgentBtn');
            if (send) { send.disabled = current.sendState !== 'DRAFT'; }
            if (cancel) { cancel.disabled = !(current.sendState === 'DRAFT' || current.sendState === 'QUEUED'); }
            if (urgent) { urgent.disabled = !(current.reqType === 'OUT' && current.sendState === 'DRAFT' && current.urgentYn !== 'Y'); }
            events(res.data.events);
        }, function (err) { notify(err.message, true); });
    }

    function act(btn, path, body) {
        if (!current) { return; }
        var id = current.reqId;
        hb.busy(btn, function () { return hb.api(API + '/reqs/' + id + '/' + path, {method: 'POST', body: body || {rowVer: current.rowVer}}); })
            .then(function (res) {
                if (!res) { return; }
                notify(hb.t('js.common.saved'));
                return loadReqs().then(function () { return openReq(id); });
            }, function (err) { notify(errText(err), true); });
    }

    /** "품번,LOT,수량" 줄 → 출고 라인(빈 줄 무시, LOT 비우면 WES 가 고름). 판정은 서버 */
    function parseOut(text) {
        return text.split(/\r?\n/).map(function (s) { return s.trim(); }).filter(Boolean).map(function (s) {
            var p = s.split(',');
            return {itemCd: (p[0] || '').trim(), lotNo: (p[1] || '').trim() || null, qty: (p[2] || '').trim() || null};
        });
    }

    // ---------------------------------------------------------------- 용기

    function loadCtrs() {
        var v = sp.values();
        var q = [];
        if (v.asrsId) { q.push('asrsId=' + encodeURIComponent(v.asrsId)); }
        if (v.status) { q.push('status=' + encodeURIComponent(v.status)); }
        return hb.api(API + '/ctrs' + (q.length ? '?' + q.join('&') : '')).then(function (res) {
            rowsInto(document.getElementById('ctrRows'), res.data, function (c) {
                return [{v: c.ctrCd}, {v: c.typeCd}, {v: names.ctr[c.status] || c.status}, {v: c.contentRows, num: true},
                    {v: c.mixedLot ? hb.t('js.wmsAsrs.mixed') : ''}];
            }, function (c) { openCtr(c.ctrId); hb.page.openDetail(); });
        });
    }

    function openCtr(id) {
        return hb.latest('ctr', hb.api(API + '/ctrs/' + id)).then(function (res) {
            current = res.data.ctr;
            showOnly('ctrForm');
            var form = document.getElementById('ctrForm');
            document.getElementById('mode').textContent = hb.t('js.wmsAsrs.ctrTitle', current.ctrCd, names.ctr[current.status] || current.status);
            f(form, 'contents').value = res.data.contents.map(function (c) {
                return [c.slotNo, c.itemCd, c.lotNo || '', String(c.qty)].join(',');
            }).join('\n');
            var editable = CAN_SAVE && (current.status === 'EMPTY' || current.status === 'LOADED');
            f(form, 'contents').readOnly = !editable;
            var b = document.getElementById('ctrSaveBtn');
            if (b) { b.disabled = !editable; }
            hb.showFieldErrors(form, []);
            events(res.data.events);
        }, function (err) { notify(err.message, true); });
    }

    /** "칸,품번,LOT,수량" 줄 → 내용 줄(빈 줄 무시). 판정은 서버 */
    function parseContents(text) {
        return text.split(/\r?\n/).map(function (s) { return s.trim(); }).filter(Boolean).map(function (s) {
            var p = s.split(',');
            return {slotNo: Number((p[0] || '').trim()) || null, itemCd: (p[1] || '').trim(), lotNo: (p[2] || '').trim() || null,
                qty: (p[3] || '').trim() || null};
        });
    }

    // ---------------------------------------------------------------- 대사

    function loadRecons() {
        var a = sp.values().asrsId;
        return hb.api(API + '/recons' + (a ? '?asrsId=' + encodeURIComponent(a) : '')).then(function (res) {
            rowsInto(document.getElementById('rcnRows'), res.data, function (r) {
                return [{v: '#' + r.reconId}, {v: r.asrsCd}, {v: r.snapKey}, {v: r.ctrCnt, num: true}, {v: r.skippedCnt, num: true},
                    {v: r.diffCnt + ' (' + r.openCnt + ')', num: true}, {v: r.finishedAt ? hb.fmt.datetime(r.finishedAt) : ''}];
            }, function (r) { openRecon(r.reconId); hb.page.openDetail(); });
        });
    }

    function openRecon(id) {
        return hb.latest('rcn', hb.api(API + '/recons/' + id)).then(function (res) {
            current = res.data.head;
            diff = null;
            showOnly('rcnPane');
            document.getElementById('eventBox').hidden = true;
            document.getElementById('mode').textContent = hb.t('js.wmsAsrs.reconTitle', current.reconId, current.snapKey);
            document.getElementById('diffPick').textContent = hb.t('js.wmsAsrs.pickDiff');
            rowsInto(document.getElementById('diffRows'), res.data.diffs, function (d) {
                return [{v: d.ctrCd}, {v: d.slotNo, num: true}, {v: [d.itemCd, d.lotNo].filter(Boolean).join(' · ')}, {v: d.ourQty, num: true},
                    {v: d.wesQty, num: true}, {v: names.kind[d.diffKind] || d.diffKind}, {v: names.diffStatus[d.status] || d.status}];
            }, function (d, tr) {
                diff = d;
                Array.prototype.forEach.call(tr.parentNode.children, function (x) { x.setAttribute('aria-selected', String(x === tr)); });
                document.getElementById('diffPick').textContent = hb.t('js.wmsAsrs.diffPicked', d.ctrCd, d.slotNo, d.itemCd);
                diffButtons();
            });
            diffButtons();
        }, function (err) { notify(err.message, true); });
    }

    function diffButtons() {
        var open = !!diff && diff.status === 'OPEN';
        var adj = document.getElementById('adjustBtn');
        var dis = document.getElementById('dismissBtn');
        if (adj) { adj.disabled = !(open && diff.diffKind === 'QTY'); }
        if (dis) { dis.disabled = !open; }
    }

    function diffAct(btn, path, body) {
        var reconId = current.reconId;
        hb.busy(btn, function () { return hb.api(API + '/recon-diffs/' + diff.diffId + '/' + path, {method: 'POST', body: body}); })
            .then(function (res) {
                if (!res) { return; }
                notify(hb.t('js.common.saved'));
                return loadRecons().then(function () { return openRecon(reconId); });
            }, function (err) { notify(errText(err), true); });
    }

    // ---------------------------------------------------------------- 묶기

    function bind(id, fn) {
        var b = document.getElementById(id);
        if (b) { b.addEventListener('click', function () { fn(b); }); }
    }

    function openNew(formId, modeKey) {
        current = null;
        showOnly(formId);
        document.getElementById('eventBox').hidden = true;
        document.getElementById('mode').textContent = hb.t(modeKey);
        var form = document.getElementById(formId);
        form.querySelectorAll('[data-field]').forEach(function (x) { /** @type {HTMLInputElement} */ (x).value = ''; });
        hb.showFieldErrors(form, []);
        hb.page.openDetail();
    }

    function saveNew(b, formId, path, body) {
        var form = document.getElementById(formId);
        var asrsId = sp.values().asrsId;
        if (!asrsId) { notify(hb.t('js.wmsAsrs.pickAsrs'), true); return; }
        body.asrsId = Number(asrsId);
        body.remark = f(form, 'remark').value;
        hb.busy(b, function () { return hb.api(API + path, {method: 'POST', body: body}); }).then(function (res) {
            if (!res) { return; }
            notify(hb.t('js.common.saved'));
            return loadReqs().then(function () { return openReq(res.data); });
        }, function (err) {
            notify(errText(err), true);
            hb.showFieldErrors(form, err.errors);
        });
    }

    bind('newInBtn', function () { openNew('inForm', 'js.wmsAsrs.newIn'); });
    bind('newOutBtn', function () { openNew('outForm', 'js.wmsAsrs.newOut'); });
    bind('inSaveBtn', function (b) {
        var cds = f(document.getElementById('inForm'), 'ctrCds').value.split(/\r?\n/).map(function (s) { return s.trim(); }).filter(Boolean);
        saveNew(b, 'inForm', '/reqs/in', {ctrCds: cds});
    });
    bind('outSaveBtn', function (b) {
        saveNew(b, 'outForm', '/reqs/out', {lines: parseOut(f(document.getElementById('outForm'), 'lines').value)});
    });
    bind('sendBtn', function (b) { act(b, 'send'); });
    bind('cancelBtn', function (b) {
        hb.dialog.confirm({title: hb.t('js.wmsAsrs.cancelTitle'), message: hb.t('js.wmsAsrs.confirmCancel'), ok: hb.t('js.wmsAsrs.cancelOk')})
            .then(function (yes) { if (yes) { act(b, 'cancel'); } });
    });
    bind('urgentBtn', function (b) {
        // 긴급만 WES 로 우선순위를 보낸다(Q-E4-4) — 사유 필수, 표시는 되돌리지 않는다
        hb.dialog.prompt({title: hb.t('js.wmsAsrs.urgentTitle'), message: hb.t('js.wmsAsrs.urgentWarn'), label: hb.t('js.wmsAsrs.urgentReason'),
            ok: hb.t('js.wmsAsrs.urgentOk'), required: true, maxLength: 200}).then(function (reason) {
            if (reason === null) { return; }
            act(b, 'urgent', {rowVer: current.rowVer, reason: reason});
        });
    });
    bind('urgentStatBtn', function (b) {
        hb.busy(b, function () { return hb.api(API + '/urgent-stats?days=7'); }).then(function (res) {
            if (!res) { return; }
            var s = res.data;
            notify(hb.t('js.wmsAsrs.urgentStats', s.days, s.outCnt, s.urgentCnt,
                s.byUser.map(function (u) { return u.markedBy + ' ' + u.cnt; }).join(', ') || '-'));
        }, function (err) { notify(err.message, true); });
    });
    bind('ctrSaveBtn', function (b) {
        if (!current) { return; }
        var id = current.ctrId;
        var form = document.getElementById('ctrForm');
        hb.busy(b, function () {
            return hb.api(API + '/ctrs/' + id + '/contents', {method: 'PUT', body: {lines: parseContents(f(form, 'contents').value), rowVer: current.rowVer}});
        }).then(function (res) {
            if (!res) { return; }
            notify(res.data.indexOf('MIXED_LOT') >= 0 ? hb.t('js.wmsAsrs.savedMixed') : hb.t('js.common.saved'), res.data.length > 0);
            return loadCtrs().then(function () { return openCtr(id); });
        }, function (err) {
            notify(errText(err), true);
            hb.showFieldErrors(form, err.errors);
        });
    });
    bind('adjustBtn', function (b) {
        if (!diff) { return; }
        // 원장은 재고 조정 서비스 경유(Q-E4-1) — 사유 = 재고 조정 사유 공통코드
        hb.dialog.reasonCode({title: hb.t('js.wmsAsrs.adjustTitle'), message: hb.t('js.wmsAsrs.adjustMsg', diff.ourQty, diff.wesQty),
            code: 'WMS_ADJ_REASON', codeLabel: hb.t('js.wmsAsrs.adjustReason'), label: hb.t('js.common.memo'), ok: hb.t('js.wmsAsrs.adjustOk'),
            maxLength: 400}).then(function (r) {
            if (r === null) { return; }
            diffAct(b, 'adjust', {reasonCd: r.code, remark: r.memo || null, rowVer: diff.rowVer});
        });
    });
    bind('dismissBtn', function (b) {
        if (!diff) { return; }
        hb.dialog.prompt({title: hb.t('js.wmsAsrs.dismissTitle'), label: hb.t('js.wmsAsrs.dismissLabel'), ok: hb.t('js.wmsAsrs.dismissOk'),
            required: true, maxLength: 400}).then(function (remark) {
            if (remark === null) { return; }
            diffAct(b, 'dismiss', {remark: remark, rowVer: diff.rowVer});
        });
    });

    function load() { return tab === 'REQ' ? loadReqs() : tab === 'CTR' ? loadCtrs() : loadRecons(); }

    function setTab(t) {
        tab = t;
        document.getElementById('reqWrap').hidden = t !== 'REQ';
        document.getElementById('ctrWrap').hidden = t !== 'CTR';
        document.getElementById('rcnWrap').hidden = t !== 'RCN';
        ['newInBtn', 'newOutBtn', 'urgentStatBtn'].forEach(function (id) {
            var nb = document.getElementById(id);
            if (nb) { nb.style.display = t === 'REQ' ? '' : 'none'; }
        });
        search.querySelector('[name="status"]').closest('label').style.display = t === 'CTR' ? '' : 'none';
        showOnly(null);
        document.getElementById('eventBox').hidden = true;
        document.getElementById('mode').textContent = hb.t('js.wmsAsrs.selectFirst');
    }

    search.querySelectorAll('input[name="tab"]').forEach(function (r) {
        r.addEventListener('change', function () { setTab(r.value); load().then(null, function (e) { notify(e.message, true); }); });
    });
    search.addEventListener('submit', function (e) { e.preventDefault(); });
    hb.loadMessages().then(function () {
        hb.page.init();
        return Promise.all([hb.loadSession(), hb.api(API), hb.codes('WMS_ASRS_SEND'), hb.codes('WMS_ASRS_LINE_STATUS'), hb.codes('WMS_CTR_STATUS'),
            hb.codes('WMS_ASRS_EVENT'), hb.codes('WMS_ASRS_REQ_TYPE'), hb.codes('WMS_ASRS_EXC_REASON'), hb.codes('WMS_ASRS_DIFF_KIND'),
            hb.codes('WMS_ASRS_DIFF_STATUS')]);
    }).then(function (r) {
        names = {send: r[2], line: r[3], ctr: r[4], event: r[5], type: r[6], reason: r[7], kind: r[8], diffStatus: r[9]};
        var sel = document.getElementById('asrsSel');
        sel.textContent = '';
        var all = el('option', null, hb.t('js.wmsAsrs.allAsrs'));
        all.value = '';
        sel.appendChild(all);
        r[1].data.forEach(function (a) {
            var o = el('option', null, a.plantCd + ' · ' + a.asrsCd + ' · ' + a.asrsNm);
            o.value = String(a.asrsId);
            sel.appendChild(o);
        });
        sp = hb.searchPanel(search, {onSearch: load});
        setTab('REQ');
        return sp.ready.then(loadReqs);
    }).then(null, function (err) { notify(err && err.message, true); });
})();
