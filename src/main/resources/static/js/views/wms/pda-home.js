/* 창고 PDA 홈 (/pop/wh, B7-1 docs/30 12.12) — 배지(+PIN) → 메뉴 → 이동 / 실사 센 수량. 셸·키패드·대기열은 MES POP 공용(hb.pop).
   이동: 출발 위치 스캔 → 그 위치 재고 고르기(품목·LOT 스캔 또는 누름, 1줄이면 자동) → 수량(기본 = 그 줄 전량) → 도착 위치 스캔 → 확정.
   이동 확정은 오프라인 대기열(hb.pop.queue) — 요청 id·발생 시각(공장 현지)을 누른 순간에 정해 재전송해도 같다.
   실사 센 수량은 온라인만(D-B21): 머리 rowVer 가 옛것이면 409 — 친 수량은 지우지 않고 다시 읽어 확인 뒤 저장.
   피킹(B7-2b)도 온라인만(D-B25): 출하 지시 → 줄(누름·품목 스캔) → 출발 칸(제안 누름 또는 위치 스캔 — 제안 밖 칸도 됨, LOT 관리 품목은
   LOT 스캔) → 수량(빈 키패드에서) → 피킹. 409(다른 사람이 먼저)면 친 것은 두고 지시를 다시 읽는다.
   반품 출고(B7-3)도 온라인만: 반품 라벨(RTN:) → 후보 칸 누름·위치 스캔 → 수량 → [칸 추가](여러 칸) → 합 = 지시 수량이면 [반품 출고].
   요청 id 는 누른 순간 만들고, 실패하면 같은 입력으로 다시 누를 때 같은 id(두 번 나가지 않음).
   입고(docs/30 12.12.15)도 온라인만(D-B25): 품목·GS1 라벨 또는 ASN 납품서 스캔 → ASN 행 / 발주 행(또는 발주 없이) → 위치 후보 누름·위치 스캔
   (후보 = 서버 저장 판정, 못 받는 칸은 사유) → 수량(빈 키패드) → 입고. 요청 id 는 누른 순간, 실패 뒤 같은 입력이면 같은 id.
   정위치 점검(docs/55)도 온라인만(대기열 안 씀): 위치 스캔 → 물건 스캔(서버 판정) → 전수 확인 완료. 흐름은 pda-loccheck.js(단위 시험). */
(function () {
    'use strict';

    var POP = '/api/pop';
    var API = '/api/pop/wh';
    var feedback = document.getElementById('feedback');
    var switchBtn = document.getElementById('switchBtn');
    var PANELS = ['badge', 'pin', 'menu', 'rcv', 'move', 'count', 'pick', 'return', 'loccheck', 'rejected'];
    var panel = 'badge';
    var zone = null, pad = null;
    /** 배지(+PIN) 로그인(hb.pop 공용, docs/25 4.34) — 키패드가 준비된 뒤 만든다 */
    var login = null;
    var mv = null;          // 이동 진행 {from, stock, line, qty, to}
    var ct = null;          // 실사 진행 {view, nodeId, line, qty}
    var pk = null;          // 피킹 진행 {view, line, sug, from, lotNo, qty}
    var rt = null;          // 반품 진행 {view, rows, cur, qty, req}
    var rc = null;          // 입고 진행 {asn, item, supLot, hint, poMode, po, asnLineId, poLines, locs, node, qty, req}

    function el(id) { return document.getElementById(id); }

    function fail(err) {
        if (err && err.stale) { return; }
        var detail = err && err.errors && err.errors.length ? err.errors.map(function (x) { return x.message; }).join(' / ') : '';
        hb.pop.feedback(feedback, 'error', ((err && err.message) || hb.t('js.error.server')) + (detail ? ' — ' + detail : ''));
    }

    function qtyText(s) {
        if (s === null || s === undefined || s === '') { return ''; }
        var p = String(s).split('.');
        return p[0].replace(/\B(?=(\d{3})+(?!\d))/g, ',') + (p.length > 1 ? '.' + p[1] : '');
    }

    function showPanel(which) {
        panel = which;
        PANELS.forEach(function (p) { el(p + 'Panel').hidden = p !== which; });
    }

    /** 공장 현지 시각 yyyy-MM-ddTHH:mm — 이동 발생 시각(누른 순간, 대기 뒤 재전송해도 그대로) */
    function localNow() {
        var parts = {};
        new Intl.DateTimeFormat('en-CA', {timeZone: zone || undefined, year: 'numeric', month: '2-digit', day: '2-digit',
            hour: '2-digit', minute: '2-digit', hourCycle: 'h23'}).formatToParts(new Date()).forEach(function (p) { parts[p.type] = p.value; });
        return parts.year + '-' + parts.month + '-' + parts.day + 'T' + parts.hour + ':' + parts.minute;
    }

    function lineText(l) {
        return l.itemCd + ' · ' + l.itemNm + (l.lotNo ? ' · ' + l.lotNo : '');
    }

    // ---------------------------------------------------------------- 세션·배지

    function refresh() {
        return hb.latest('session', hb.pop.api(POP + '/session')).then(function (res) {
            var s = res.data;
            zone = s.zone;
            el('where').textContent = s.terminalNm + ' · ' + s.plantCd;
            el('worker').textContent = s.workerCd ? hb.t('js.pop.workerIs', s.workerNm, s.workerCd) : '';
            switchBtn.hidden = !s.workerCd;
            if (s.workerCd && !returnsChecked) {
                returnsChecked = true;
                hb.pop.api(API + '/returns/available').then(function (r) { el('goReturn').hidden = !r.data; }, function () {});
            }
            if (!s.workerCd) {
                if (!login || !login.pending()) { showPanel('badge'); }
            } else if (panel === 'badge' || panel === 'pin') {
                showPanel('menu');
            }
        }, fail);
    }

    /** 작업자가 내려갔으면(비활동·다른 단말) 배지 화면으로 */
    function workerGone(err) {
        return !!login && login.gone(err);
    }

    // ---------------------------------------------------------------- 이동

    var mvPad = null;

    function moveReset() {
        mv = {from: null, stock: [], line: null, qty: '', to: null};
        renderMove();
    }

    function renderMove() {
        el('mvFrom').textContent = mv.from ? mv.from.path : '';
        el('mvWhat').textContent = mv.line ? lineText(mv.line) : '';
        el('mvQty').textContent = mv.line ? qtyText(mv.qty) + ' ' + mv.line.unitCd : '';
        el('mvTo').textContent = mv.to ? mv.to.path : '';
        var step = !mv.from ? 0 : !mv.line ? 1 : !mv.to ? 3 : -1;
        document.querySelectorAll('.wpda-steps li').forEach(function (li, i) {
            if (i === step) { li.setAttribute('aria-current', 'step'); } else { li.removeAttribute('aria-current'); }
        });
        el('mvGuide').textContent = !mv.from ? hb.t('js.pdaWh.scanFrom') : !mv.line ? hb.t('js.pdaWh.pickStock')
            : !mv.to ? hb.t('js.pdaWh.scanTo') : hb.t('js.pdaWh.readyMove');
        var ul = el('mvStock');
        ul.textContent = '';
        if (mv.from && !mv.line) {
            if (!mv.stock.length) { ul.appendChild(hb.el('li', 'hb-pop-sub', hb.t('js.pdaWh.noStock'))); }
            mv.stock.forEach(function (l) {
                var li = hb.el('li');
                var b = hb.el('button', 'wpda-pick', lineText(l) + ' — ' + qtyText(l.qty) + ' ' + l.unitCd);
                b.type = 'button';
                b.addEventListener('click', function () { pickLine(l); });
                li.appendChild(b);
                ul.appendChild(li);
            });
        }
        el('mvQtyBox').hidden = !mv.line;
        el('mvConfirm').disabled = !(mv.from && mv.line && mv.to && Number(mv.qty) > 0);
    }

    function pickLine(l) {
        mv.line = l;
        mv.qty = String(l.qty);
        mvPad.set(mv.qty);
        renderMove();
    }

    function scanFor(text) {
        return hb.latest('scan', hb.pop.api(API + '/scan?text=' + encodeURIComponent(text))).then(function (res) { return res.data; });
    }

    function moveScan(text) {
        scanFor(text).then(function (d) {
            var h = d.hit;
            if (!h.ok) { hb.pop.feedback(feedback, 'error', h.message); return; }
            if (h.type === 'LOC') {
                if (!mv.from || !mv.line) {                     // 출발 위치(다시 읽으면 바꾼다)
                    mv = {from: {nodeId: h.nodeId, path: h.path}, stock: d.stock, line: null, qty: '', to: null};
                    if (d.stock.length === 1) { pickLine(d.stock[0]); } else { renderMove(); }
                } else {
                    mv.to = {nodeId: h.nodeId, path: h.path};
                    renderMove();
                }
                return;
            }
            if (mv.from && !mv.line && (h.type === 'ITEM' || h.type === 'LOT')) {
                var hits = mv.stock.filter(function (l) { return l.itemId === h.itemId && (h.type !== 'LOT' || l.lotNo === h.lotNo); });
                if (hits.length === 1) { pickLine(hits[0]); return; }
                hb.pop.feedback(feedback, 'error', hits.length ? hb.t('js.pdaWh.pickOne') : hb.t('js.pdaWh.notHere', h.itemCd || h.lotNo));
                return;
            }
            hb.pop.feedback(feedback, 'info', !mv.from ? hb.t('js.pdaWh.scanFrom') : hb.t('js.pdaWh.scanTo'));
        }, function (err) { if (!workerGone(err)) { fail(err); } });
    }

    function confirmMove(button) {
        var m = mv;
        var body = {clientReqId: hb.pop.reqId(), occurredAt: localNow(), itemId: m.line.itemId, lotNo: m.line.lotNo || null,
            stockStatus: m.line.stockStatus, fromNodeId: m.from.nodeId, toNodeId: m.to.nodeId, qty: m.qty, entryMethod: 'SCAN'};
        var label = hb.t('js.pdaWh.moveLabel', m.line.itemCd + (m.line.lotNo ? ' ' + m.line.lotNo : ''), qtyText(m.qty), m.line.unitCd,
            m.from.path, m.to.path);
        hb.busy(button, function () { return hb.pop.queue.send(API + '/moves', body, {label: label}); }).then(function (res) {
            if (!res) { return; }
            var warn = !res.queued && res.data && res.data.warnings && res.data.warnings.length ? res.data.warnings.join(' ') : '';   // 적치 검사 경고(W-12)
            hb.pop.feedback(feedback, res.queued || warn ? 'info' : 'ok', res.queued ? hb.t('js.pdaWh.queued')
                : hb.t('js.pdaWh.moved', label) + (warn ? ' ' + warn : ''));
            moveReset();
        }, function (err) { if (!workerGone(err)) { fail(err); } });
    }

    // ---------------------------------------------------------------- 실사

    var ctPad = null;

    function countList() {
        ct = null;
        el('ctTitle').textContent = hb.t('js.pdaWh.countTitle');
        el('ctBody').hidden = true;
        var ul = el('ctList');
        ul.hidden = false;
        ul.textContent = '';
        return hb.latest('counts', hb.pop.api(API + '/counts')).then(function (res) {
            if (!res.data.length) { ul.appendChild(hb.el('li', 'hb-pop-sub', hb.t('js.pdaWh.noCounts'))); }
            res.data.forEach(function (c) {
                var li = hb.el('li');
                var b = hb.el('button', 'wpda-pick', c.countNo + ' · ' + c.scopePath + ' · ' + hb.t('js.pdaWh.counted', c.countedCount, c.lineCount));
                b.type = 'button';
                b.addEventListener('click', function () { openCount(c.countId, null); });
                li.appendChild(b);
                ul.appendChild(li);
            });
        }, function (err) { if (!workerGone(err)) { fail(err); } });
    }

    /** keep = 저장 못 한 입력({lineId, qty}) — 다시 읽어도 지우지 않는다 */
    function openCount(id, keep) {
        return hb.latest('count', hb.pop.api(API + '/counts/' + id)).then(function (res) {
            var nodeId = ct ? ct.nodeId : null;
            ct = {view: res.data, nodeId: nodeId, line: null, qty: ''};
            if (keep) {
                ct.line = res.data.lines.filter(function (l) { return l.lineId === keep.lineId; })[0] || null;
                ct.qty = ct.line ? keep.qty : '';
            }
            ctPad.set(ct.qty);
            el('ctTitle').textContent = res.data.head.countNo + ' · ' + res.data.head.scopePath;
            el('ctList').hidden = true;
            el('ctBody').hidden = false;
            renderCount();
        }, function (err) { if (!workerGone(err)) { fail(err); } });
    }

    function renderCount() {
        var v = ct.view;
        // 재실사로 대체된 줄은 빼고, 위치를 읽었으면 그 위치 줄만
        var lines = v.lines.filter(function (l) { return l.latest && (!ct.nodeId || l.nodeId === ct.nodeId); });
        el('ctGuide').textContent = ct.line ? hb.t('js.pdaWh.enterQty') : !ct.nodeId ? hb.t('js.pdaWh.scanLoc') : hb.t('js.pdaWh.pickLine');
        var ul = el('ctLines');
        ul.textContent = '';
        if (ct.nodeId && !lines.length) { ul.appendChild(hb.el('li', 'hb-pop-sub', hb.t('js.pdaWh.noLines'))); }
        if (ct.nodeId) {
            lines.forEach(function (l) {
                var li = hb.el('li');
                var b = hb.el('button', 'wpda-pick', l.path + ' · ' + lineText(l)
                    + (l.countedQty !== null && l.countedQty !== undefined ? ' — ' + qtyText(l.countedQty) + ' ' + l.unitCd : ''));
                b.type = 'button';
                if (ct.line && ct.line.lineId === l.lineId) { b.setAttribute('aria-pressed', 'true'); }
                if (l.countedQty !== null && l.countedQty !== undefined) { b.classList.add('wpda-done'); }
                b.addEventListener('click', function () {
                    // 키패드는 빈 칸에서 — 앞 값을 채워 두면 누른 숫자가 뒤에 붙어 조용히 다른 수량(7 → 77)이 된다
                    ct.line = l;
                    ct.qty = '';
                    ctPad.clear();
                    renderCount();
                });
                li.appendChild(b);
                ul.appendChild(li);
            });
        }
        el('ctQtyBox').hidden = !ct.line;
        if (ct.line) {
            var prev = ct.line.countedQty;
            el('ctLineInfo').textContent = ct.line.path + ' · ' + lineText(ct.line)
                + (prev !== null && prev !== undefined ? ' · ' + hb.t('js.pdaWh.prevCount', qtyText(prev), ct.line.unitCd) : '');
            el('ctQty').textContent = (qtyText(ct.qty) || '0') + ' ' + ct.line.unitCd;
        }
        el('ctSave').disabled = !(ct.line && ct.qty !== '');
    }

    function countScan(text) {
        if (!ct) { hb.pop.feedback(feedback, 'info', hb.t('js.pdaWh.pickCount')); return; }
        scanFor(text).then(function (d) {
            var h = d.hit;
            if (!h.ok) { hb.pop.feedback(feedback, 'error', h.message); return; }
            if (h.type === 'LOC') {
                ct.nodeId = h.nodeId;
                ct.line = null;
                renderCount();
                return;
            }
            if (ct.nodeId && (h.type === 'ITEM' || h.type === 'LOT')) {
                var hits = ct.view.lines.filter(function (l) {
                    return l.latest && l.nodeId === ct.nodeId && l.itemId === h.itemId && (h.type !== 'LOT' || l.lotNo === h.lotNo);
                });
                if (hits.length === 1) {
                    ct.line = hits[0];
                    ct.qty = '';
                    ctPad.clear();
                    renderCount();
                    return;
                }
                hb.pop.feedback(feedback, 'error', hits.length ? hb.t('js.pdaWh.pickOne') : hb.t('js.pdaWh.notInCount', h.itemCd || h.lotNo));
                return;
            }
            hb.pop.feedback(feedback, 'info', hb.t('js.pdaWh.scanLoc'));
        }, function (err) { if (!workerGone(err)) { fail(err); } });
    }

    function saveCount(button) {
        var c = ct;
        var keep = {lineId: c.line.lineId, qty: c.qty};
        hb.busy(button, function () {
            return hb.pop.api(API + '/counts/' + c.view.head.countId + '/counts', {method: 'POST',
                body: {rowVer: c.view.head.rowVer, rows: [{lineId: c.line.lineId, countedQty: c.qty}]}});
        }).then(function (res) {
            if (!res) { return; }
            hb.pop.feedback(feedback, 'ok', hb.t('js.pdaWh.countSaved', c.line.itemCd, qtyText(c.qty), c.line.unitCd));
            ct.view = res.data;
            ct.line = null;
            ct.qty = '';
            ctPad.clear();
            renderCount();
        }, function (err) {
            if (workerGone(err)) { return; }
            if (err && err.status === 409) {
                // 다른 사람·사무 화면이 먼저 저장 — 친 수량은 남기고 지금 값을 다시 읽어 보여 준다
                hb.pop.feedback(feedback, 'error', hb.t('js.pdaWh.countConflict'));
                openCount(c.view.head.countId, keep);
                return;
            }
            fail(err);
        });
    }

    // ---------------------------------------------------------------- 피킹 (B7-2b, 온라인만)

    var pkPad = null;

    function left(l) { return Math.max(Number(l.qty) - Number(l.pickedQty || 0), 0); }

    function pickList() {
        pk = null;
        el('pkTitle').textContent = hb.t('js.pdaWh.pickTitle');
        el('pkBody').hidden = true;
        var ul = el('pkList');
        ul.hidden = false;
        ul.textContent = '';
        return hb.latest('ships', hb.pop.api(API + '/ships')).then(function (res) {
            if (!res.data.length) { ul.appendChild(hb.el('li', 'hb-pop-sub', hb.t('js.pdaWh.noShips'))); }
            res.data.forEach(function (h) {
                var li = hb.el('li');
                var b = hb.el('button', 'wpda-pick', h.shipNo + ' · ' + h.custNm + ' · ' + hb.t('js.pdaWh.shipLines', h.lineCount)
                    + (h.status === 'PICKING' ? ' · ' + hb.t('js.pdaWh.picking') : '')
                    + (h.noteCnt ? ' · ' + hb.t(h.noteOpenCnt ? 'js.pdaWh.notesOpen' : 'js.pdaWh.notes', h.noteCnt) : ''));
                if (h.noteOpenCnt) { b.classList.add('wpda-note-open'); }
                b.type = 'button';
                b.addEventListener('click', function () { openShip(h.shipId, null); });
                li.appendChild(b);
                ul.appendChild(li);
            });
        }, function (err) { if (!workerGone(err)) { fail(err); } });
    }

    /** keep = 확정 못 한 입력({lineId, from, lotNo, qty}) — 다시 읽어도 지우지 않는다 */
    function openShip(id, keep) {
        return hb.latest('ship', hb.pop.api(API + '/ships/' + id)).then(function (res) {
            pk = {view: res.data, line: null, sug: [], from: null, lotNo: null, qty: ''};
            el('pkTitle').textContent = res.data.head.shipNo + ' · ' + res.data.head.custNm;
            el('pkList').hidden = true;
            el('pkBody').hidden = false;
            var line = keep ? res.data.lines.filter(function (l) { return l.shipLineId === keep.lineId; })[0] : null;
            if (line && left(line) > 0) {
                return openLine(line).then(function () {
                    pk.from = keep.from;
                    pk.lotNo = keep.lotNo;
                    pk.qty = keep.qty;
                    pkPad.set(keep.qty);
                    renderPick();
                });
            }
            renderPick();
        }, function (err) { if (!workerGone(err)) { fail(err); } });
    }

    function openLine(l) {
        pk.line = l;
        pk.from = null;
        pk.lotNo = null;
        pk.qty = '';
        pkPad.clear();
        pk.sug = [];
        renderPick();
        return hb.latest('suggest', hb.pop.api(API + '/ships/' + pk.view.head.shipId + '/lines/' + l.shipLineId + '/suggest'))
            .then(function (res) { if (pk && pk.line === l) { pk.sug = res.data; renderPick(); } },
                function (err) { if (!workerGone(err)) { fail(err); } });
    }

    function useSuggest(sg) {
        pk.from = {nodeId: sg.nodeId, path: sg.path};
        pk.lotNo = sg.lotNo || null;
        renderPick();
    }

    /** 출하 특이사항(docs/56) — 확인 필수인데 아직 확인 안 한 규칙이 이 줄에 걸렸나 */
    function needAck(l) {
        return (pk.view.notes || []).some(function (n) { return n.ackRequiredYn === 'Y' && !n.acked && n.lineIds.indexOf(l.shipLineId) >= 0; });
    }

    /** 특이사항 목록 — 확인 필수 미확인은 [확인](배지 작업자로 기록, 온라인만 — 피킹과 같음) */
    function renderNotes() {
        var ul = el('pkNotes');
        var list = (pk.view.notes || []).filter(function (n) { return !pk.line || n.lineIds.indexOf(pk.line.shipLineId) >= 0; });
        ul.textContent = '';
        ul.hidden = !list.length;
        list.forEach(function (n) {
            var need = n.ackRequiredYn === 'Y' && !n.acked;
            var li = hb.el('li', 'wpda-note' + (need ? ' wpda-note-open' : ''));
            li.appendChild(hb.el('b', null, n.title));
            if (n.body) { li.appendChild(hb.el('div', 'hb-pop-sub', n.body)); }
            if (n.acked) {
                li.appendChild(hb.el('div', 'hb-pop-sub', hb.t('js.pdaWh.noteAckBy', n.ackWorkerNm || n.ackedBy || '')));
            } else if (n.ackRequiredYn === 'Y') {
                var b = hb.el('button', 'hb-pop-primary', hb.t('js.pdaWh.noteAck'));
                b.type = 'button';
                b.addEventListener('click', function () { ackNote(n, b); });
                li.appendChild(b);
            }
            ul.appendChild(li);
        });
    }

    function ackNote(n, button) {
        var id = pk.view.head.shipId;
        hb.busy(button, function () {
            return hb.pop.api(API + '/ships/' + id + '/notes/' + n.noteId + '/ack', {method: 'POST', body: {}});
        }).then(function (res) {
            if (!res || !pk || pk.view.head.shipId !== id) { return; }
            pk.view = res.data;
            if (pk.line) { pk.line = res.data.lines.filter(function (l) { return l.shipLineId === pk.line.shipLineId; })[0] || null; }
            hb.pop.feedback(feedback, 'ok', hb.t('js.pdaWh.noteAcked', n.title));
            renderPick();
        }, function (err) { if (!workerGone(err)) { fail(err); } });
    }

    function renderPick() {
        var v = pk.view;
        var lotCtrl = pk.line && pk.line.lotCtrlYn === 'Y';
        renderNotes();
        el('pkGuide').textContent = pk.line && needAck(pk.line) ? hb.t('js.pdaWh.noteFirst') : !pk.line ? hb.t('js.pdaWh.pickLineGuide')
            : !pk.from ? hb.t('js.pdaWh.pickFromGuide') : lotCtrl && !pk.lotNo ? hb.t('js.pdaWh.pickLotGuide')
            : !(Number(pk.qty) > 0) ? hb.t('js.pdaWh.pickQtyGuide') : hb.t('js.pdaWh.readyPick');
        var ul = el('pkLines');
        ul.textContent = '';
        ul.hidden = !!pk.line;
        if (!pk.line) {
            v.lines.forEach(function (l) {
                var li = hb.el('li');
                var b = hb.el('button', 'wpda-pick', l.lineNo + '. ' + l.itemCd + ' · ' + l.itemNm + ' — '
                    + hb.t('js.pdaWh.pickedOf', qtyText(l.pickedQty || 0), qtyText(l.qty), l.unitCd)
                    + (needAck(l) ? ' · ' + hb.t('js.pdaWh.noteNeed') : ''));
                b.type = 'button';
                if (needAck(l)) { b.classList.add('wpda-note-open'); }
                if (left(l) <= 0) { b.classList.add('wpda-done'); b.disabled = true; }
                b.addEventListener('click', function () { openLine(l); });
                li.appendChild(b);
                ul.appendChild(li);
            });
        }
        el('pkLineBox').hidden = !pk.line;
        if (!pk.line) { el('pkConfirm').disabled = true; return; }
        el('pkLineInfo').textContent = pk.line.itemCd + ' · ' + pk.line.itemNm + ' · '
            + hb.t('js.pdaWh.pickLeft', qtyText(left(pk.line)), pk.line.unitCd);
        var sl = el('pkSuggest');
        sl.textContent = '';
        if (!pk.sug.length) { sl.appendChild(hb.el('li', 'hb-pop-sub', hb.t('js.pdaWh.noSuggest'))); }
        pk.sug.forEach(function (sg) {
            var li = hb.el('li');
            var b = hb.el('button', 'wpda-pick', sg.path + (sg.lotNo ? ' · ' + sg.lotNo : '') + ' — '
                + hb.t('js.pdaWh.suggestTake', qtyText(sg.takeQty), qtyText(sg.availQty), pk.line.unitCd));
            b.type = 'button';
            if (pk.from && pk.from.nodeId === sg.nodeId && (pk.lotNo || null) === (sg.lotNo || null)) { b.setAttribute('aria-pressed', 'true'); }
            b.addEventListener('click', function () { useSuggest(sg); });
            li.appendChild(b);
            sl.appendChild(li);
        });
        el('pkFrom').textContent = pk.from ? hb.t('js.pdaWh.pickFromIs', pk.from.path + (pk.lotNo ? ' · ' + pk.lotNo : '')) : '';
        el('pkQty').textContent = (qtyText(pk.qty) || '0') + ' ' + pk.line.unitCd;
        el('pkConfirm').disabled = !(pk.from && (!lotCtrl || pk.lotNo) && Number(pk.qty) > 0) || needAck(pk.line);
    }

    function pickScan(text) {
        if (!pk) { hb.pop.feedback(feedback, 'info', hb.t('js.pdaWh.pickShip')); return; }
        scanFor(text).then(function (d) {
            var h = d.hit;
            if (!h.ok) { hb.pop.feedback(feedback, 'error', h.message); return; }
            if (!pk.line) {
                if (h.type === 'ITEM' || h.type === 'LOT') {
                    var hits = pk.view.lines.filter(function (l) { return l.itemId === h.itemId && left(l) > 0; });
                    if (hits.length === 1) {
                        openLine(hits[0]).then(function () { if (h.type === 'LOT') { pk.lotNo = h.lotNo; renderPick(); } });
                        return;
                    }
                    hb.pop.feedback(feedback, 'error', hits.length ? hb.t('js.pdaWh.pickOne') : hb.t('js.pdaWh.notInShip', h.itemCd || h.lotNo));
                    return;
                }
                hb.pop.feedback(feedback, 'info', hb.t('js.pdaWh.pickLineGuide'));
                return;
            }
            if (h.type === 'LOC') {
                // 제안 칸이면 그 LOT, 제안 밖 칸이면 LOT 는 비움(LOT 관리 품목은 LOT 스캔) — 판정은 서버(12.7 B7 몫)
                var sg = pk.sug.filter(function (x) { return x.nodeId === h.nodeId; });
                pk.from = {nodeId: h.nodeId, path: h.path};
                pk.lotNo = sg.length === 1 ? sg[0].lotNo || null : null;
                renderPick();
                return;
            }
            if (h.type === 'LOT') {
                if (h.itemId !== pk.line.itemId) { hb.pop.feedback(feedback, 'error', hb.t('js.pdaWh.notThisItem', h.lotNo)); return; }
                pk.lotNo = h.lotNo;
                if (!pk.from) {
                    var at = pk.sug.filter(function (x) { return x.lotNo === h.lotNo; });
                    if (at.length === 1) { pk.from = {nodeId: at[0].nodeId, path: at[0].path}; }
                }
                renderPick();
                return;
            }
            hb.pop.feedback(feedback, 'info', hb.t('js.pdaWh.pickFromGuide'));
        }, function (err) { if (!workerGone(err)) { fail(err); } });
    }

    function confirmPick(button) {
        var p = pk;
        var keep = {lineId: p.line.shipLineId, from: p.from, lotNo: p.lotNo, qty: p.qty};
        var body = {rowVer: p.view.head.rowVer, clientReqId: hb.pop.reqId(),
            picks: [{shipLineId: p.line.shipLineId, fromNodeId: p.from.nodeId, lotNo: p.lotNo, qty: p.qty}]};
        var label = p.line.itemCd + (p.lotNo ? ' ' + p.lotNo : '') + ' ' + qtyText(p.qty) + ' ' + p.line.unitCd + ' · ' + p.from.path;
        hb.busy(button, function () {
            return hb.pop.api(API + '/ships/' + p.view.head.shipId + '/picks', {method: 'POST', body: body});
        }).then(function (res) {
            if (!res) { return; }
            hb.pop.feedback(feedback, 'ok', hb.t('js.pdaWh.picked', label));
            pk.view = res.data;
            var line = res.data.lines.filter(function (l) { return l.shipLineId === keep.lineId; })[0];
            if (line && left(line) > 0) {
                openLine(line);
            } else {
                pk.line = null;
                renderPick();
            }
        }, function (err) {
            if (workerGone(err)) { return; }
            if (err && err.status === 409 && err.code !== 'INSUFFICIENT_STOCK') {
                // 다른 단말·사무 화면이 먼저 바꿈 — 친 것은 남기고 지시를 다시 읽어 확인 뒤 다시 피킹
                fail(err);
                openShip(p.view.head.shipId, keep);
                return;
            }
            fail(err);
        });
    }

    // ---------------------------------------------------------------- 반품 출고 (B7-3, 온라인만)

    var rtPad = null;
    /** SRM 없는 설치본은 반품 메뉴를 감춘다 — 작업자가 들어온 뒤 한 번 묻는다(작업자 없으면 428) */
    var returnsChecked = false;

    function returnStart() {
        rt = null;
        el('rtTitle').textContent = hb.t('js.pdaWh.returnTitle');
        el('rtBody').hidden = true;
        el('rtGuide').textContent = hb.t('js.pdaWh.scanReturn');
    }

    function openReturn(no) {
        return hb.latest('return', hb.pop.api(API + '/returns?returnNo=' + encodeURIComponent(no))).then(function (res) {
            var v = res.data;
            rt = {view: v, rows: [], cur: null, qty: '', req: null};
            rtPad.clear();
            el('rtTitle').textContent = v.ret.returnNo;
            el('rtBody').hidden = false;
            renderReturn();
        }, function (err) { if (!workerGone(err)) { fail(err); } });
    }

    function rtTotal() {
        var t = rt.rows.reduce(function (a, r) { return a + Number(r.qty); }, 0);
        return Math.round(t * 1e6) / 1e6;
    }

    function renderReturn() {
        var v = rt.view, r = v.ret;
        var shippable = r.status === 'CONFIRMED';
        var want = Number(r.baseQty);
        var total = rtTotal();
        el('rtInfo').textContent = v.itemCd + ' · ' + v.itemNm + ' · LOT ' + r.lotNo + ' · '
            + hb.t('js.pdaWh.returnQty', qtyText(r.baseQty), r.baseUnitCd) + (r.replace ? ' · ' + hb.t('js.pdaWh.replaceY') : '');
        el('rtGuide').textContent = !shippable ? hb.t('js.pdaWh.returnShipped', r.returnNo)
            : !v.stock.length ? hb.t('js.pdaWh.returnNoStock')
            : total === want ? hb.t('js.pdaWh.readyReturn')
            : total > want ? hb.t('js.pdaWh.returnOver', qtyText(total), qtyText(r.baseQty), r.baseUnitCd)
            : !rt.cur ? hb.t('js.pdaWh.returnPickBin') : hb.t('js.pdaWh.returnEnterQty');
        var ul = el('rtStock');
        ul.textContent = '';
        ul.hidden = !shippable;
        v.stock.forEach(function (s) {
            var li = hb.el('li');
            var b = hb.el('button', 'wpda-pick', s.path + ' · ' + s.stockStatus + ' — ' + hb.t('js.pdaWh.onHand', qtyText(s.qty), r.baseUnitCd));
            b.type = 'button';
            if (rt.cur && rt.cur.nodeId === s.nodeId && rt.cur.stockStatus === s.stockStatus) { b.setAttribute('aria-pressed', 'true'); }
            b.addEventListener('click', function () { rt.cur = s; rt.qty = ''; rtPad.clear(); renderReturn(); });
            li.appendChild(b);
            ul.appendChild(li);
        });
        var rows = el('rtRows');
        rows.textContent = '';
        rt.rows.forEach(function (x, i) {
            var li = hb.el('li');
            var b = hb.el('button', 'wpda-pick wpda-done', x.path + ' · ' + x.stockStatus + ' − ' + qtyText(x.qty) + ' ' + r.baseUnitCd
                + ' ' + hb.t('js.pdaWh.tapRemove'));
            b.type = 'button';
            b.addEventListener('click', function () { rt.rows.splice(i, 1); rt.req = null; renderReturn(); });
            li.appendChild(b);
            rows.appendChild(li);
        });
        el('rtCur').textContent = rt.cur ? hb.t('js.pdaWh.pickFromIs', rt.cur.path + ' · ' + rt.cur.stockStatus)
            + ' · ' + hb.t('js.pdaWh.returnTotal', qtyText(total), qtyText(r.baseQty), r.baseUnitCd) : '';
        el('rtQty').textContent = rt.cur ? (qtyText(rt.qty) || '0') + ' ' + r.baseUnitCd : '';
        el('rtPad').hidden = !rt.cur;
        el('rtAdd').disabled = !(shippable && rt.cur && Number(rt.qty) > 0);
        el('rtConfirm').disabled = !(shippable && total === want && rt.rows.length);
    }

    function addReturnRow() {
        var c = rt.cur, q = Number(rt.qty);
        if (!c || !(q > 0)) { return; }
        if (q > Number(c.qty)) { hb.pop.feedback(feedback, 'error', hb.t('js.pdaWh.overBin', qtyText(c.qty))); return; }
        // 같은 칸·상태를 다시 넣으면 바꾼다(더하지 않음 — 두 번 눌러 두 배가 되는 조용한 틀림 방지)
        rt.rows = rt.rows.filter(function (x) { return !(x.nodeId === c.nodeId && x.stockStatus === c.stockStatus); });
        rt.rows.push({nodeId: c.nodeId, path: c.path, stockStatus: c.stockStatus, qty: rt.qty});
        rt.cur = null;
        rt.qty = '';
        rt.req = null;
        rtPad.clear();
        renderReturn();
    }

    function returnScan(text) {
        var t = text.trim();
        if (!rt || /^RTN:/i.test(t)) { openReturn(t); return; }
        scanFor(t).then(function (d) {
            var h = d.hit;
            if (!h.ok) { hb.pop.feedback(feedback, 'error', h.message); return; }
            if (h.type === 'LOC') {
                var at = rt.view.stock.filter(function (s) { return s.nodeId === h.nodeId; });
                if (at.length === 1) { rt.cur = at[0]; rt.qty = ''; rtPad.clear(); renderReturn(); return; }
                hb.pop.feedback(feedback, 'error', at.length ? hb.t('js.pdaWh.pickOne') : hb.t('js.pdaWh.returnNotHere', h.path));
                return;
            }
            hb.pop.feedback(feedback, 'info', hb.t('js.pdaWh.returnPickBin'));
        }, function (err) { if (!workerGone(err)) { fail(err); } });
    }

    function confirmReturn(button) {
        var x = rt;
        if (!x.req) { x.req = hb.pop.reqId(); }
        var body = {returnNo: x.view.ret.returnNo, clientReqId: x.req, occurredAt: localNow(), entryMethod: 'SCAN',
            rows: x.rows.map(function (r) { return {nodeId: r.nodeId, stockStatus: r.stockStatus, qty: r.qty}; })};
        hb.busy(button, function () { return hb.pop.api(API + '/returns', {method: 'POST', body: body}); }).then(function (res) {
            if (!res) { return; }
            var s = res.data.shipped;
            hb.pop.feedback(feedback, 'ok', hb.t('js.pdaWh.returned', x.view.ret.returnNo, qtyText(rtTotal()), x.view.ret.baseUnitCd)
                + (s && s.openQty !== null && s.openQty !== undefined ? ' · ' + hb.t('js.pdaWh.waitReplace', qtyText(s.openQty)) : ''));
            returnStart();
        }, function (err) {
            if (workerGone(err)) { return; }
            fail(err);
            if (err && err.status && err.status !== 409 && err.status < 500) { x.req = null; }   // 고쳐서 다시 — 새 요청
        });
    }

    // ---------------------------------------------------------------- 입고 (docs/30 12.12.15, 온라인만)

    var rcPad = null;
    var LOC_SHOW = 8;
    /** 못 받는 칸 사유 → 문구(키는 글자 그대로 — i18n 가드가 쓰는 키를 찾는다) */
    var RC_REASON = {STOCK_NOT_ALLOWED: 'js.pdaWh.rcReason.STOCK_NOT_ALLOWED', STAGING_NOT_ALLOWED: 'js.pdaWh.rcReason.STAGING_NOT_ALLOWED',
        SCRAP_LOCATION: 'js.pdaWh.rcReason.SCRAP_LOCATION'};
    /** 발주 행 입고를 쓰는 설치본인가(SRM) — 작업자가 들어온 뒤 한 번 묻는다 */
    var rcPoEnabled = null;

    function rcvStart() {
        rc = {asn: null, item: null, supLot: null, hint: null, poMode: null, po: null, asnLineId: null, poLines: null, locs: null, node: null,
            qty: '', req: null};
        if (rcPad) { rcPad.clear(); }
        if (rcPoEnabled === null) {
            hb.pop.api(API + '/rcv/po-enabled').then(function (r) { rcPoEnabled = !!r.data; if (rc) { renderRcv(); } },
                function (err) { if (!workerGone(err)) { fail(err); } });
        }
        renderRcv();
    }

    function rcUnit() {
        return rc.po && rc.po.unitCd ? rc.po.unitCd : rc.item ? rc.item.unitCd : '';
    }

    function rcButton(text, pressed, fn) {
        var li = hb.el('li');
        var b = hb.el('button', 'wpda-pick', text);
        b.type = 'button';
        if (pressed) { b.setAttribute('aria-pressed', 'true'); }
        b.addEventListener('click', fn);
        li.appendChild(b);
        return li;
    }

    function renderRcv() {
        var x = rc;
        el('rcWhat').textContent = x.item ? x.item.itemCd + ' · ' + x.item.itemNm + (x.supLot ? ' · ' + x.supLot : '') : '';
        el('rcPo').textContent = x.poMode === 'PO' ? x.po.label : x.poMode === 'NONE' ? hb.t('js.pdaWh.rcNoPo') : '';
        el('rcTo').textContent = x.node ? x.node.path + ' · ' + x.node.status : '';
        el('rcQty').textContent = x.qty ? qtyText(x.qty) + ' ' + rcUnit() : '';
        var ul = el('rcList');
        ul.textContent = '';
        var guide;
        if (!x.item) {
            if (x.asn) {
                guide = hb.t('js.pdaWh.rcPickAsnLine', x.asn.head.asnNo);
                x.asn.lines.forEach(function (l) {
                    ul.appendChild(rcButton(l.itemCd + ' · ' + l.itemNm + ' · ' + l.poNo + '-' + l.poLineNo + ' — '
                        + hb.t('js.pdaWh.rcRemain', qtyText(l.remainQty), l.unitCd), false, function () { useAsnLine(l); }));
                });
            } else {
                guide = hb.t('js.pdaWh.rcScanItem');
            }
        } else if (!x.poMode) {
            guide = x.poLines === null ? hb.t('js.pdaWh.loading') : hb.t('js.pdaWh.rcPickPo');
            (x.poLines || []).forEach(function (o) {
                ul.appendChild(rcButton(o.poNo + '-' + o.lineNo + ' · ' + o.supplierCd + ' · ' + o.dueDate + ' — '
                    + hb.t('js.pdaWh.rcOpen', qtyText(o.openQty), o.unitCd), false, function () {
                    x.poMode = 'PO';
                    x.po = {poLineId: o.poLineId, label: o.poNo + '-' + o.lineNo, unitCd: o.unitCd};
                    x.hint = hb.t('js.pdaWh.rcOpen', qtyText(o.openQty), o.unitCd);
                    x.req = null;
                    renderRcv();
                }));
            });
            if (x.poLines !== null) {
                ul.appendChild(rcButton(hb.t('js.pdaWh.rcWithoutPo'), false, function () { x.poMode = 'NONE'; x.req = null; renderRcv(); }));
            }
        } else if (!x.node) {
            guide = x.locs === null ? hb.t('js.pdaWh.loading') : hb.t('js.pdaWh.rcPickLoc');
            (x.locs || []).filter(function (n) { return !n.reasonCd; }).slice(0, LOC_SHOW).forEach(function (n) {
                ul.appendChild(rcButton(n.path + ' · ' + n.status, false, function () { x.node = n; x.req = null; renderRcv(); }));
            });
        } else {
            guide = hb.t('js.pdaWh.rcEnterQty');
        }
        el('rcGuide').textContent = guide;
        el('rcQtyBox').hidden = !x.node;
        el('rcHint').textContent = x.hint || '';
        el('rcConfirm').disabled = !(x.item && x.poMode && x.node && Number(x.qty) > 0);
    }

    function rcLoadLocs() {
        var x = rc;
        hb.pop.api(API + '/rcv/locs?itemId=' + x.item.itemId).then(function (r) { if (rc === x) { x.locs = r.data; renderRcv(); } },
            function (err) { if (!workerGone(err)) { fail(err); } });
    }

    /** 품목이 정해짐(품목·GS1 스캔) — 발주 행을 고르게(SRM 없으면 발주 없이) */
    function useItem(item, supLot, gs1Qty) {
        rcvStart();
        var x = rc;
        x.item = item;
        x.supLot = supLot || null;
        x.hint = gs1Qty ? hb.t('js.pdaWh.rcLabelQty', qtyText(gs1Qty), item.unitCd) : null;
        rcLoadLocs();
        if (rcPoEnabled === false) {
            x.poMode = 'NONE';
            renderRcv();
            return;
        }
        renderRcv();
        hb.pop.api(API + '/rcv/po-lines?itemId=' + item.itemId).then(function (r) { if (rc === x) { x.poLines = r.data; renderRcv(); } },
            function (err) { if (!workerGone(err)) { fail(err); } });
    }

    /** ASN 행 — 품목·발주 행·공급사 LOT 이 함께 정해진다 */
    function useAsnLine(l) {
        var asn = rc.asn;
        rcvStart();
        var x = rc;
        x.asn = asn;
        x.item = {itemId: l.itemId, itemCd: l.itemCd, itemNm: l.itemNm, unitCd: l.unitCd};
        x.poMode = 'PO';
        x.po = {poLineId: l.poLineId, label: l.poNo + '-' + l.poLineNo, unitCd: l.unitCd};
        x.asnLineId = l.asnLineId;
        x.supLot = l.supplierLotNo || null;
        x.hint = hb.t('js.pdaWh.rcRemain', qtyText(l.remainQty), l.unitCd);
        rcLoadLocs();
        renderRcv();
    }

    function openAsn(key) {
        return hb.latest('rcvAsn', hb.pop.api(API + '/rcv/asn?key=' + encodeURIComponent(key))).then(function (r) {
            rcvStart();
            rc.asn = r.data;
            renderRcv();
        }, function (err) { if (!workerGone(err)) { fail(err); } });
    }

    function rcvScan(text) {
        hb.latest('rcvScan', hb.pop.api(API + '/rcv/scan?text=' + encodeURIComponent(text.trim()))).then(function (res) {
            var d = res.data;
            if (d.type === 'ASN' && d.asnNo) { openAsn(d.asnNo); return; }
            if (!d.ok) { hb.pop.feedback(feedback, 'error', d.message); return; }
            if (d.type === 'LOC' && d.nodeId) {
                if (!rc.item || !rc.poMode) { hb.pop.feedback(feedback, 'error', hb.t('js.pdaWh.rcItemFirst')); return; }
                if (rc.locs === null) { hb.pop.feedback(feedback, 'info', hb.t('js.pdaWh.loading')); return; }
                var n = rc.locs.filter(function (c) { return c.nodeId === d.nodeId; })[0];
                if (!n) { hb.pop.feedback(feedback, 'error', hb.t('js.pdaWh.rcNotHere', d.path)); return; }
                if (n.reasonCd) { hb.pop.feedback(feedback, 'error', hb.t(RC_REASON[n.reasonCd] || RC_REASON.STOCK_NOT_ALLOWED, n.path)); return; }
                rc.node = n;
                rc.req = null;
                renderRcv();
                return;
            }
            if (d.itemId) {
                useItem({itemId: d.itemId, itemCd: d.itemCd, itemNm: d.itemNm, unitCd: d.unitCd}, d.supplierLotNo, d.qty);
                return;
            }
            hb.pop.feedback(feedback, 'info', d.message);     // 우리 LOT 라벨 등 — 입고 대상 아님
        }, function (err) { if (!workerGone(err)) { fail(err); } });
    }

    function confirmRcv(button) {
        var x = rc;
        if (!x.req) { x.req = hb.pop.reqId(); }
        var body = {clientReqId: x.req, itemId: x.item.itemId, qty: x.qty, nodeId: x.node.nodeId,
            poLineId: x.poMode === 'PO' ? x.po.poLineId : null, asnLineId: x.asnLineId, supplierLotNo: x.supLot, entryMethod: 'SCAN'};
        hb.busy(button, function () { return hb.pop.api(API + '/rcv', {method: 'POST', body: body}); }).then(function (res) {
            if (!res) { return; }
            var warn = res.data.warnings && res.data.warnings.length ? res.data.warnings.join(' ') : '';   // 적치 검사 경고(W-12)
            hb.pop.feedback(feedback, warn ? 'info' : 'ok', hb.t('js.pdaWh.received', res.data.lotNo, qtyText(x.qty), rcUnit(), x.node.path)
                + (warn ? ' ' + warn : ''));
            var asn = x.asn;
            rcvStart();
            if (asn) { openAsn(asn.head.asnNo); }        // 같은 ASN 의 다음 행으로(남은 양을 다시 읽는다)
        }, function (err) {
            if (workerGone(err)) { return; }
            fail(err);
            if (err && err.status && err.status !== 409 && err.status < 500) { x.req = null; }   // 고쳐서 다시 — 새 요청
            if (rc === x) { renderRcv(); }
        });
    }

    // ---------------------------------------------------------------- 정위치 점검 (docs/55, 온라인만 — 흐름은 pda-loccheck.js)

    function lcOnline() { return navigator.onLine !== false; }

    var LC_NAME = {OK: 'js.pdaWh.lcName.OK', MISPLACED: 'js.pdaWh.lcName.MISPLACED', MISSING: 'js.pdaWh.lcName.MISSING',
        UNKNOWN: 'js.pdaWh.lcName.UNKNOWN'};

    /** @param {any} v */
    function renderLc(v) {
        el('lcOffline').hidden = lcOnline();
        el('lcGuide').textContent = !v ? hb.t('js.pdaWh.lcScanLoc') : v.head.status === 'OPEN' ? hb.t('js.pdaWh.lcScanItem')
            : hb.t('js.pdaWh.lcFinished');
        el('lcHead').textContent = !v ? '' : v.head.path + ' · ' + hb.t('js.pdaWh.lcCount', v.lines.length)
            + (v.head.status === 'DONE' ? ' · ' + hb.t(v.head.accurateYn === 'Y' ? 'js.pdaWh.lcAccurate' : 'js.pdaWh.lcNotAccurate') : '');
        var ul = el('lcLines');
        ul.textContent = '';
        (v ? v.lines.slice().reverse() : []).forEach(function (l) {
            var li = hb.el('li', 'wpda-lc wpda-lc-' + l.result.toLowerCase());
            var what = l.itemCd ? l.itemCd + (l.lotNo ? ' · ' + l.lotNo : '') : (l.scanCode || '');
            li.appendChild(hb.el('b', null, hb.t(LC_NAME[l.result] || LC_NAME.UNKNOWN)));
            li.appendChild(document.createTextNode(' ' + what));
            if (l.result === 'MISPLACED' && l.bookJson.length) {
                li.appendChild(hb.el('div', 'hb-pop-sub', hb.t('js.pdaWh.lcBook', l.bookJson.map(function (b) { return b.path; }).join(', '))));
            }
            ul.appendChild(li);
        });
        el('lcDone').disabled = !lc.canAct();
    }

    var lc = wpdaLocCheck({
        post: function (url, body) { return hb.pop.api(url, {method: 'POST', body: body}); },
        online: lcOnline,
        render: renderLc,
        feedback: function (kind, key, args) {
            if (!key) { if (!workerGone(args && args[0])) { fail(args && args[0]); } return; }
            hb.pop.feedback(feedback, kind, hb.t.apply(null, [key].concat(args || [])));
        },
        newReqId: function () { return 'lc-' + Date.now() + '-' + Math.random().toString(36).slice(2, 10); },
        now: function () { return new Date().toISOString(); }
    });

    function lcDone(btn) {
        hb.dialog.confirm({title: hb.t('js.pdaWh.dlg.lcDone'), message: hb.t('js.pdaWh.confirmLcDone'), ok: hb.t('js.pdaWh.lcDoneBtn')})
            .then(function (yes) {
                if (!yes) { return; }
                // hb.busy 는 끝나면 버튼을 다시 켠다 — 완료(DONE) 뒤에는 꺼져 있어야 하므로 상태를 다시 맞춘다
                hb.busy(btn, function () { return lc.done(); }).then(function () { el('lcDone').disabled = !lc.canAct(); });
            });
    }

    // ---------------------------------------------------------------- 보내지 못한 이동

    /** 줄 글자 = 확정 때 남긴 요약(label) — 그리기·다시 보내기·버리기 확인은 hb.pop 공용 */
    var renderRejected = hb.pop.rejectedList(el('rejectedList'), {
        keys: {none: 'js.pdaWh.rejectedNone', resend: 'js.pdaWh.resend', discard: 'js.pdaWh.discard', dlgDiscard: 'js.pdaWh.dlg.discard',
            confirmDiscard: 'js.pdaWh.confirmDiscard'}
    });

    // ---------------------------------------------------------------- 버튼

    el('goRcv').addEventListener('click', function () { rcvStart(); showPanel('rcv'); });
    el('rcReset').addEventListener('click', function () { rcvStart(); });
    el('rcConfirm').addEventListener('click', function (e) { if (rc && rc.node && Number(rc.qty) > 0) { confirmRcv(e.currentTarget); } });
    el('goMove').addEventListener('click', function () { moveReset(); showPanel('move'); });
    el('goCount').addEventListener('click', function () { showPanel('count'); countList(); });
    el('goPick').addEventListener('click', function () { showPanel('pick'); pickList(); });
    el('goReturn').addEventListener('click', function () { showPanel('return'); returnStart(); });
    el('goLocCheck').addEventListener('click', function () { lc.reset(); showPanel('loccheck'); });
    el('lcNext').addEventListener('click', function () { lc.reset(); });
    el('lcDone').addEventListener('click', function (e) { if (lc.canAct()) { lcDone(e.currentTarget); } });
    ['online', 'offline'].forEach(function (ev) {
        window.addEventListener(ev, function () { if (panel === 'loccheck') { renderLc(lc.view()); } });
    });
    el('rtAdd').addEventListener('click', function () { if (rt) { addReturnRow(); } });
    el('rtConfirm').addEventListener('click', function (e) { if (rt && rt.rows.length) { confirmReturn(e.currentTarget); } });
    document.querySelectorAll('.wpda-back').forEach(function (b) {
        b.addEventListener('click', function () { mv = null; ct = null; pk = null; rt = null; rc = null; showPanel('menu'); });
    });
    el('pkLinesBtn').addEventListener('click', function () { if (pk) { pk.line = null; renderPick(); } });
    el('pkConfirm').addEventListener('click', function (e) { if (pk && pk.line && pk.from) { confirmPick(e.currentTarget); } });
    el('mvReset').addEventListener('click', moveReset);
    el('ctSave').addEventListener('click', function (e) { if (ct && ct.line && ct.qty !== '') { saveCount(e.currentTarget); } });
    el('mvConfirm').addEventListener('click', function (e) { confirmMove(e.currentTarget); });
    el('pinCancel').addEventListener('click', function () { if (login) { login.cancel(); } });
    switchBtn.addEventListener('click', function () {
        hb.busy(switchBtn, function () { return hb.pop.api(POP + '/worker/logout', {method: 'POST', body: {}}); })
            .then(function (res) { if (res) { mv = null; ct = null; pk = null; rt = null; rc = null; rcPoEnabled = null; showPanel('badge'); return refresh(); } }, fail);
    });
    var langBtn = el('langBtn');
    langBtn.addEventListener('click', function () {
        var next = document.documentElement.lang === 'en' ? 'ko' : 'en';
        hb.busy(langBtn, function () { return hb.pop.api(POP + '/lang', {method: 'POST', body: {lang: next}}); })
            .then(function (res) { if (res) { window.location.reload(); } }, fail);
    });

    hb.loadMessages().then(function () {
        hb.pop.network(el('net'));
        pad = hb.pop.keypad(el('keypad'), {max: 8, onChange: hb.pop.pinDots(el('pinDots')), onConfirm: function (v, b) { login.onPin(v, b); }});
        login = hb.pop.badgeLogin({url: POP + '/badge', pad: pad, pinWho: el('pinWho'), feedback: feedback, showPanel: showPanel,
            onSignedIn: function () { showPanel('menu'); return refresh(); }, onGone: refresh, fail: fail});
        mvPad = hb.pop.keypad(el('mvPad'), {max: 18, decimal: true, onChange: function (v) {
            if (mv && mv.line) { mv.qty = v; renderMove(); }
        }});
        ctPad = hb.pop.keypad(el('ctPad'), {max: 18, decimal: true, onChange: function (v) {
            if (ct && ct.line) {
                ct.qty = v;
                el('ctQty').textContent = (qtyText(v) || '0') + ' ' + ct.line.unitCd;
                el('ctSave').disabled = v === '';
            }
        }});
        // 키패드는 빈 칸에서(앞 값을 채우면 누른 숫자가 뒤에 붙는다 — 실사 7 → 77 과 같은 이유)
        pkPad = hb.pop.keypad(el('pkPad'), {max: 18, decimal: true, onChange: function (v) {
            if (pk && pk.line) { pk.qty = v; renderPick(); }
        }});
        rtPad = hb.pop.keypad(el('rtPad'), {max: 18, decimal: true, onChange: function (v) {
            if (rt && rt.cur) { rt.qty = v; renderReturn(); }
        }});
        rcPad = hb.pop.keypad(el('rcPad'), {max: 18, decimal: true, onChange: function (v) {
            if (rc && rc.node) { rc.qty = v; rc.req = null; renderRcv(); }
        }});
        // 스캔 = 화면마다 다르게: 작업자 없으면 배지, 이동이면 위치·품목, 실사면 위치·품목, 피킹이면 품목·위치·LOT
        hb.pop.scanner(el('scan'), function (v) {
            if (panel === 'rcv') { rcvScan(v); return; }
            if (panel === 'move') { moveScan(v); return; }
            if (panel === 'count') { countScan(v); return; }
            if (panel === 'pick') { pickScan(v); return; }
            if (panel === 'return') { returnScan(v); return; }
            if (panel === 'loccheck') { lc.scan(v); return; }
            if (panel === 'badge') { login.submit(v, null, null); }
        });
        var pendingEl = el('pending');
        var rejectedBtn = el('rejectedBtn');
        hb.pop.queue.onChange(function (n, r) {
            pendingEl.hidden = n === 0;
            pendingEl.textContent = hb.t('js.pdaWh.pending', n);
            rejectedBtn.hidden = !r;
            rejectedBtn.textContent = hb.t('js.pdaWh.rejectedCount', r || 0);
            if (panel === 'rejected') { renderRejected(); }
        });
        rejectedBtn.addEventListener('click', function () { showPanel('rejected'); renderRejected(); });
        var flush = function () {
            hb.pop.queue.flush().then(function (res) {
                if (res.sent > 0) { hb.pop.feedback(feedback, 'ok', hb.t('js.pdaWh.flushed', res.sent)); }
                if (res.rejected > 0) { hb.pop.feedback(feedback, 'error', hb.t('js.pdaWh.flushRejected', res.rejected)); }
            });
        };
        window.addEventListener('online', flush);
        setInterval(flush, 30000);
        flush();
        return refresh();
    });
})();
