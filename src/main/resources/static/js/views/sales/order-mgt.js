/* 수주 관리 (sal-order) — P2 2단계 (docs/25 4.3). 조회 조건 → 수주 hb.grid(UI 단계 3 ①, 페이지·정렬) → 상세(머리 폼 + 행 표 + 행 폼).
   새 수주는 행을 화면에 모아 두었다가 [저장] 한 번에, 있는 수주는 행 추가·수정·마감·취소가 바로 저장된다. */
(function () {
    'use strict';

    var API = '/api/sales/orders';
    var ACTIONS = (document.body.getAttribute('data-actions') || '').split(',');
    var CAN_SAVE = ACTIONS.indexOf('SAVE') >= 0;
    var msg = document.getElementById('msg');
    var search = document.getElementById('search');
    var form = document.getElementById('orderForm');
    var lineForm = document.getElementById('lineForm');
    var PROG = document.body.getAttribute('data-prog') || '';
    var lineBody = document.querySelector('#lines tbody');
    var formMode = document.getElementById('formMode');
    var lineMode = document.getElementById('lineMode');
    var listInfo = document.getElementById('listInfo');
    var orderStatus = document.getElementById('orderStatus');
    var linesHint = document.getElementById('linesHint');
    var cancelOrderBtn = document.getElementById('cancelOrderBtn');
    var lineSaveBtn = document.getElementById('lineSaveBtn');
    var current = null, draft = [], editing = null, sp = null, grid = null;
    /** APS 생산 계획을 조회할 수 있을 때만 [납기 답변](서버 ctp-available — 숨김은 편의, 판정은 APS API) */
    var apsOk = false;
    var CTP_EXC = {HORIZON: 'js.salOrder.ctp.exc.HORIZON', MAT: 'js.salOrder.ctp.exc.MAT', NO_NODE: 'js.salOrder.ctp.exc.NO_NODE',
        NO_CT: 'js.salOrder.ctp.exc.NO_CT'};
    var ORDER_ST = {OPEN: 'js.salOrder.st.OPEN', CLOSED: 'js.salOrder.st.CLOSED', CANCELLED: 'js.salOrder.st.CANCELLED'};
    var LINE_ST = {OPEN: 'js.salOrder.lst.OPEN', DONE: 'js.salOrder.lst.DONE', CANCELLED: 'js.salOrder.lst.CANCELLED'};

    function notify(text, error) {
        msg.textContent = text || '';
        msg.classList.toggle('hb-msg-error', !!error);
    }

    function field(n) { return form.querySelector('[data-field="' + n + '"]'); }
    function lfield(n) { return lineForm ? lineForm.querySelector('[data-field="' + n + '"]') : null; }

    function orderSt(s) { return hb.t(ORDER_ST[s] || 'js.salOrder.st.OPEN'); }
    function lineSt(s) { return hb.t(LINE_ST[s] || 'js.salOrder.lst.OPEN'); }

    /** 수량 글자(서버가 trim_scale 글자로 보냄)에 천 단위 쉼표만 — Number 로 바꾸지 않는다(큰 수 정밀도) */
    function qtyText(s) {
        if (s === null || s === undefined || s === '') { return ''; }
        var p = String(s).split('.');
        return p[0].replace(/\B(?=(\d{3})+(?!\d))/g, ',') + (p.length > 1 ? '.' + p[1] : '');
    }

    /** @returns {Promise<boolean>} 저장 안 한 입력이 있으면 화면 안 대화상자로 묻는다 */
    function confirmLeave() {
        return mesUi.leaveOk();
    }

    // ------------------------------------------------------------------ 목록

    var KEYS = ['from', 'to', 'custCd', 'status', 'keyword', 'itemCd', 'dueFrom', 'dueTo'];

    /** 조회 조건 — 빈 칸은 보내지 않는다(기존 목록과 같은 조건) */
    function params() {
        var v = sp.values();
        var q = {};
        KEYS.forEach(function (k) { if (v[k]) { q[k] = v[k]; } });
        return q;
    }

    /**
     * 납기 조건이 있으면 홈 카드(납기 임박)와 같은 단위 — 열린 행 수 — 를 목록 위에 보인다.
     * 그리드 API 는 {list,total} 만 돌려주므로(결정 #45) 이 수는 기존 목록 API 의 dueLineCnt 를 그대로 쓴다.
     */
    function dueInfo() {
        var q = params();
        listInfo.textContent = '';
        if (!q.dueFrom && !q.dueTo) { return Promise.resolve(); }
        var qs = Object.keys(q).map(function (k) { return k + '=' + encodeURIComponent(q[k]); }).join('&');
        return hb.latest('dueLines', hb.api(API + '?' + qs)).then(function (res) {
            if (res.data.dueLineCnt !== null && res.data.dueLineCnt !== undefined) {
                listInfo.textContent = hb.t('js.salOrder.dueLines', res.data.dueLineCnt);
            }
        }, function () { listInfo.textContent = ''; });
    }

    /** 목록 다시 읽기 — selectId 가 있으면 그 수주 상세를 다시 연다(지금 페이지에 있으면 그리드도 그 행을 고른다) */
    function load(selectId) {
        if (!grid) { return Promise.resolve(); }
        dueInfo();
        return grid.reload(false, selectId || undefined).then(function () {
            if (selectId) { return open(selectId); }
        });
    }

    function open(id) {
        return hb.latest('order', hb.api(API + '/' + id)).then(function (res) { show(res.data); },
            function (err) { notify(err.message, true); });
    }

    // ------------------------------------------------------------------ 상세

    function show(d) {
        current = d;
        draft = [];
        hb.showFieldErrors(form, []);
        var o = d ? d.order : null;
        if (o && !field('custCd').querySelector('option[value="' + CSS.escape(o.custCd) + '"]')) {
            // 지금은 사용 중지된 거래처의 수주 — 선택지에 없으므로 그 수주만을 위해 넣는다
            var opt = hb.el('option', null, o.custCd + ' · ' + o.custNm);
            opt.value = o.custCd;
            field('custCd').appendChild(opt);
        }
        field('custCd').value = o ? o.custCd : '';
        field('custCd').disabled = !!o || !CAN_SAVE;
        field('custPoNo').value = o ? (o.custPoNo || '') : '';
        field('orderDate').value = o ? o.orderDate : hb.dates.today();
        field('remark').value = o ? (o.remark || '') : '';
        var open = !o || o.status === 'OPEN';
        ['custPoNo', 'orderDate', 'remark'].forEach(function (n) { field(n).disabled = !open || !CAN_SAVE; });
        orderStatus.textContent = '';
        if (o) { orderStatus.appendChild(mesUi.badge('order', o.status, orderSt(o.status))); }
        formMode.textContent = o ? hb.t('js.salOrder.editing', o.orderNo) : hb.t('js.salOrder.creating');
        if (cancelOrderBtn) {
            cancelOrderBtn.hidden = !o || o.status !== 'OPEN';
            /** @type {HTMLElement} */ (cancelOrderBtn.parentElement).hidden = cancelOrderBtn.hidden; // 떨어진 줄도 같이(빈 줄 여백 방지)
        }
        if (lineForm) { lineForm.hidden = !open; }
        linesHint.hidden = !!o;
        editLine(null);
        renderLines();
        hb.dirty.clear();
    }

    function renderLines() {
        lineBody.textContent = '';
        var rows = current ? current.lines : draft;
        if (!rows.length) {
            var tr = hb.el('tr');
            var td = hb.el('td', 'hb-muted', hb.t('js.salOrder.noLines'));
            td.colSpan = 7;
            tr.appendChild(td);
            lineBody.appendChild(tr);
        }
        rows.forEach(function (l, idx) {
            var tr = hb.el('tr', !l.status || l.status === 'OPEN' ? null : 'hb-tree-dim');
            tr.appendChild(hb.el('td', null, String(l.lineNo || '')));
            tr.appendChild(hb.el('td', 'hb-wrap', l.itemCd + (l.itemNm ? ' · ' + l.itemNm : '')));
            tr.appendChild(hb.el('td', 'hb-num', qtyText(l.qty) + (l.unitCd ? ' ' + l.unitCd : '')));
            // 출하 수량은 WMS 출하만 바꾼다(docs/25 4.22) — 화면은 보기만
            tr.appendChild(hb.el('td', 'hb-num', l.shippedQty ? qtyText(l.shippedQty) : ''));
            tr.appendChild(hb.el('td', null, hb.fmt.date(l.dueDate)));
            var st = hb.el('td');
            st.appendChild(l.status ? mesUi.badge('line', l.status, lineSt(l.status), l.cancelReason || null) : hb.status('neutral', hb.t('js.salOrder.draft')));
            if (l.doneByShipYn === 'Y') { st.title = hb.t('js.salOrder.doneByShip'); }
            tr.appendChild(st);
            var act = hb.el('td');
            if (CAN_SAVE && !current) {
                act.appendChild(button(hb.t('js.salOrder.remove'), function () {
                    draft.splice(idx, 1);
                    renderLines();
                    hb.dirty.mark();
                }));
            } else if (CAN_SAVE && l.status === 'OPEN') {
                act.appendChild(button(hb.t('js.salOrder.edit'), function () { editLine(l); }));
                act.appendChild(button(hb.t('js.salOrder.closeLine'), function (b) { finish(l, 'close', b); }));
                // 행 취소 = 되돌릴 수 없음 — 외곽선 위험 톤, 줄 끝(docs/40 P1 ④)
                var cb = button(hb.t('js.salOrder.cancelLine'), function (b) { finish(l, 'cancel', b); });
                cb.className = 'hb-btn-danger-outline';
                act.appendChild(cb);
            }
            // 납기 답변(APS CTP) — 열린 행·새 행, 읽기 전용이라 저장 권한과 무관
            if (apsOk && (!l.status || l.status === 'OPEN') && l.itemCd && l.qty) {
                act.appendChild(button(hb.t('js.salOrder.ctp.open'), function () { openCtp(l); }));
            }
            tr.appendChild(act);
            lineBody.appendChild(tr);
        });
    }

    // ---------- 납기 답변: APS 계약 POST /api/aps/ctp(docs/38 15.4) 를 HTTP 로만 부른다 — 영업은 APS 클래스를 쓰지 않는다 ----------
    var ctpDialog = document.getElementById('ctpDialog');
    var ctpPlant = document.getElementById('ctpPlant');
    var ctpRun = document.getElementById('ctpRun');
    var ctpResult = document.getElementById('ctpResult');
    var ctpAnswer = document.getElementById('ctpAnswer');
    var ctpLine = null, ctpPlants = null;

    function ctpDt(iso, zone) { return iso ? hb.fmt.datetime(iso, zone) : '-'; }

    function ctpExc(c) { return hb.t(CTP_EXC[c] || 'js.salOrder.ctp.exc.OTHER'); }

    /** 공장 목록(APS 계획 공장)은 처음 열 때 한 번 */
    function loadCtpPlants() {
        if (!ctpPlants) {
            ctpPlants = hb.api('/api/aps/plans/plants').then(function (res) {
                res.data.forEach(function (p) {
                    var o = hb.el('option', null, p.plantCd + ' · ' + p.plantNm);
                    o.value = p.plantCd;
                    ctpPlant.appendChild(o);
                });
                return res.data.length;
            }, function (err) { ctpPlants = null; throw err; });
        }
        return ctpPlants;
    }

    function ctpError(err) {
        if (err && err.stale) { return; }
        // 칸 오류(품목 라우팅 없음·수량 자릿수 등)는 이 대화상자에 칸이 없으니 문구를 그대로 붙인다
        var detail = err && err.errors && err.errors.length ? err.errors.map(function (x) { return x.message; }).join(' / ') : '';
        ctpAnswer.textContent = err && err.status === 403 ? hb.t('js.salOrder.ctp.denied')
            : ((err && err.message) || '') + (detail ? ' — ' + detail : '');
        ctpResult.hidden = false;
        document.getElementById('ctpOps').hidden = true;
        document.getElementById('ctpMats').hidden = true;
    }

    function openCtp(l) {
        ctpLine = l;
        document.getElementById('ctpItem').textContent = l.itemCd + (l.itemNm ? ' · ' + l.itemNm : '');
        document.getElementById('ctpQty').textContent = qtyText(l.qty) + (l.unitCd ? ' ' + l.unitCd : '');
        document.getElementById('ctpDue').textContent = l.dueDate ? hb.fmt.date(l.dueDate) : '-';
        ctpResult.hidden = true;
        ctpRun.disabled = true;
        ctpDialog.showModal();
        loadCtpPlants().then(function (n) {
            ctpRun.disabled = !n;
            if (!n) { ctpError({message: hb.t('js.salOrder.ctp.noPlant')}); }
        }, ctpError);
    }

    function renderCtp(r) {
        ctpResult.hidden = false;
        ctpAnswer.textContent = '';
        var head = r.itemCd + ' · ' + r.itemNm;
        if (r.endAt) {
            ctpAnswer.appendChild(document.createTextNode(hb.t('js.salOrder.ctp.answer', head, qtyText(r.qty), r.unitCd,
                ctpDt(r.endAt, r.timeZone), r.woCnt) + ' '));
            // 늦음 판정은 APS 가 납기(wantDate)로 — 납기 없는 행이면 계산 기간 끝 기준
            ctpAnswer.appendChild(hb.status(r.lateYn === 'Y' ? 'warning' : 'success',
                hb.t(r.lateYn === 'Y' ? 'js.salOrder.ctp.late' : 'js.salOrder.ctp.onTime')));
        } else {
            ctpAnswer.appendChild(hb.status('danger', hb.t('js.salOrder.ctp.cannot', head, qtyText(r.qty), r.unitCd,
                r.excCd === 'NO_BOM' ? hb.t('js.salOrder.ctp.noBom') : ctpExc(r.excCd))));
        }
        if (r.excCd === 'NO_BOM' && r.endAt) {
            ctpAnswer.appendChild(hb.el('span', 'hb-muted', ' ' + hb.t('js.salOrder.ctp.noBom')));
        }
        var ops = document.getElementById('ctpOps');
        var ob = ops.querySelector('tbody');
        ob.textContent = '';
        r.ops.forEach(function (o) {
            var tr = hb.el('tr');
            [o.opSeq + ' · ' + o.procCd + ' · ' + o.procNm, o.resCd || (o.resKind === 'OUTSRC' ? hb.t('js.salOrder.ctp.outsrc') : '-'),
                ctpDt(o.startAt, r.timeZone), o.excCd ? ctpExc(o.excCd) : ctpDt(o.endAt, r.timeZone)].forEach(function (x, i) {
                tr.appendChild(hb.el('td', i === 0 ? 'hb-wrap' : null, x));
            });
            ob.appendChild(tr);
        });
        ops.hidden = !r.ops.length;
        // 자재는 모자란 줄만 — 영업에게는 "무엇 때문에 늦나" 만
        var mats = document.getElementById('ctpMats');
        var mb = mats.querySelector('tbody');
        mb.textContent = '';
        var shorts = r.mats.filter(function (m) { return m.needAt && Number(m.shortQty) > 0; });
        shorts.forEach(function (m) {
            var tr = hb.el('tr');
            tr.appendChild(hb.el('td', 'hb-wrap', (m.itemCd || m.itemId) + (m.itemNm ? ' · ' + m.itemNm : '')));
            tr.appendChild(hb.el('td', 'hb-num', qtyText(m.shortQty) + ' ' + m.unitCd));
            tr.appendChild(hb.el('td', null, ctpDt(m.coveredAt, r.timeZone)));
            mb.appendChild(tr);
        });
        mats.hidden = !shorts.length;
    }

    document.getElementById('ctpClose').addEventListener('click', function () { ctpDialog.close(); });
    // 공장을 바꾸면 앞 답은 다른 공장 것 — 지운다
    ctpPlant.addEventListener('change', function () { ctpResult.hidden = true; });
    ctpRun.addEventListener('click', function () {
        var l = ctpLine;
        if (!l) { return; }
        var body = {plantCd: ctpPlant.value, itemCd: l.itemCd, qty: String(l.qty), wantDate: l.dueDate || null};
        hb.busy(ctpRun, function () { return hb.latest('ctp', hb.api('/api/aps/ctp', {method: 'POST', body: body})); })
            .then(function (res) { if (res) { renderCtp(res.data); } }, ctpError);
    });

    function button(text, fn) {
        var b = hb.el('button', 'hb-btn-secondary', text);
        b.type = 'button';
        b.addEventListener('click', function () { fn(b); });
        return b;
    }

    function editLine(l) {
        editing = l;
        if (!lineForm) { return; }
        hb.showFieldErrors(lineForm, []);
        ['itemCd', 'qty', 'dueDate', 'lineNo', 'remark'].forEach(function (n) {
            lfield(n).value = l ? (l[n] === null || l[n] === undefined ? '' : String(l[n])) : '';
        });
        lfield('itemCd').readOnly = !!l;
        lfield('lineNo').readOnly = !!l;
        lineMode.textContent = l ? hb.t('js.salOrder.lineEditing', l.lineNo) : '';
        lineSaveBtn.textContent = l ? hb.t('js.salOrder.saveLine') : hb.t('js.salOrder.addLine');
        hb.dirty.clear(lineForm);
    }

    function lineBody0() {
        return {itemCd: lfield('itemCd').value.trim(), qty: lfield('qty').value.trim(), dueDate: lfield('dueDate').value,
            lineNo: lfield('lineNo').value.trim() ? Number(lfield('lineNo').value.trim()) : null,
            remark: lfield('remark').value};
    }

    function finish(l, kind, btn) {
        var body = {rowVer: l.rowVer};
        var asked = kind === 'cancel'
            ? hb.dialog.prompt({title: hb.t('js.mes.dlg.lineCancel'), label: hb.t('js.salOrder.cancelPrompt', l.lineNo),
                ok: hb.t('js.mes.dlg.cancelOk'), danger: true, required: true, maxLength: 500}).then(function (reason) {
                if (reason === null) { return false; }
                body.reason = reason;
                return true;
            })
            : mesUi.ask('js.mes.dlg.lineClose', hb.t('js.salOrder.closeConfirm', l.lineNo), 'js.mes.dlg.closeOk');
        asked.then(function (ok) {
            if (!ok) { return; }
            hb.busy(btn, function () { return hb.api(API + '/lines/' + l.lineId + '/' + kind, {method: 'POST', body: body}); })
                .then(function (res) {
                    if (!res) { return; }
                    notify(hb.t('js.common.saved'));
                    return load(current.order.orderId);
                }, function (err) {
                    notify(err.errors && err.errors.length ? err.errors[0].message : err.message, true);
                });
        });
    }

    // ------------------------------------------------------------------ 저장

    if (lineSaveBtn) {
        lineSaveBtn.addEventListener('click', function () {
            hb.showFieldErrors(lineForm, []);
            var b = lineBody0();
            if (!current) {
                // 새 수주: 화면에 모아 두기(검증은 저장 때 서버가)
                if (!b.itemCd || !b.qty || !b.dueDate) {
                    hb.showFieldErrors(lineForm, ['itemCd', 'qty', 'dueDate'].filter(function (n) { return !b[n]; })
                        .map(function (n) { return {field: n, message: hb.t('js.salOrder.required')}; }));
                    return;
                }
                if (!b.lineNo) {
                    b.lineNo = draft.reduce(function (m, x) { return Math.max(m, x.lineNo); }, 0);
                    b.lineNo = (Math.floor(b.lineNo / 10) + 1) * 10;
                }
                b.itemCd = b.itemCd.toUpperCase();
                draft.push(b);
                renderLines();
                editLine(null);
                hb.dirty.mark();
                lfield('itemCd').focus();
                return;
            }
            var url = editing ? API + '/lines/' + editing.lineId : API + '/' + current.order.orderId + '/lines';
            if (editing) { b.rowVer = editing.rowVer; }
            hb.busy(lineSaveBtn, function () { return hb.api(url, {method: editing ? 'PUT' : 'POST', body: b}); })
                .then(function (res) {
                    if (!res) { return; }
                    hb.dirty.clear(lineForm);
                    notify(hb.t('js.common.saved'));
                    return load(current.order.orderId);
                }, function (err) {
                    notify(err.message, true);
                    hb.showFieldErrors(lineForm, err.errors);
                });
        });
        document.getElementById('lineResetBtn').addEventListener('click', function () { editLine(null); });
    }

    var saveBtn = document.getElementById('saveBtn');
    if (saveBtn) {
        saveBtn.addEventListener('click', function () {
            hb.showFieldErrors(form, []);
            var body = {custCd: field('custCd').value, custPoNo: field('custPoNo').value, orderDate: field('orderDate').value,
                remark: field('remark').value};
            var editingOrder = !!current;
            if (editingOrder) {
                body.rowVer = current.order.rowVer;
            } else {
                body.lines = draft;
            }
            hb.busy(saveBtn, function () {
                return editingOrder ? hb.api(API + '/' + current.order.orderId, {method: 'PUT', body: body})
                    : hb.api(API, {method: 'POST', body: body});
            }).then(function (res) {
                if (!res) { return; }
                hb.dirty.clear();
                return load(editingOrder ? current.order.orderId : res.data).then(function () { notify(hb.t('js.common.saved')); });
            }, function (err) {
                notify(err.message, true);
                // 새 수주 행 오류(lines[0].qty)는 행 폼이 아니라 메시지로 — 몇 번째 행인지 함께
                var rowErr = (err.errors || []).filter(function (x) { return /^lines/.test(x.field || ''); })[0];
                if (rowErr) { notify(hb.t('js.salOrder.lineError', rowErr.field, rowErr.message), true); }
                hb.showFieldErrors(form, err.errors);
            });
        });
    }

    if (cancelOrderBtn) {
        cancelOrderBtn.addEventListener('click', function () {
            hb.dialog.prompt({title: hb.t('js.mes.dlg.orderCancel'), label: hb.t('js.salOrder.cancelOrderPrompt', current.order.orderNo),
                ok: hb.t('js.mes.dlg.cancelOk'), danger: true, required: true, maxLength: 500}).then(function (reason) {
                if (reason === null) { return; }
                hb.busy(cancelOrderBtn, function () {
                    return hb.api(API + '/' + current.order.orderId + '/cancel', {method: 'POST',
                        body: {reason: reason, rowVer: current.order.rowVer}});
                }).then(function (res) {
                    if (!res) { return; }
                    notify(hb.t('js.common.saved'));
                    return load(current.order.orderId);
                }, function (err) {
                    notify(err.errors && err.errors.length ? err.errors[0].message : err.message, true);
                });
            });
        });
    }

    function newOrder() {
        confirmLeave().then(function (ok) {
            if (!ok) { return; }
            show(null);
            hb.page.openDetail();
            field('custCd').focus();
        });
    }
    ['newBtn', 'newBtnList'].forEach(function (id) {
        var b = document.getElementById(id);
        if (b) { b.addEventListener('click', newOrder); }
    });

    var importBtn = document.getElementById('importBtn');
    if (importBtn) {
        importBtn.addEventListener('click', function () {
            hb.importDialog({templateCd: 'SAL_ORDER', onDone: function () { load(current ? current.order.orderId : null); }});
        });
    }

    // 품목 찾기(입력 보조): 250ms 멈추면 코드 앞부분·이름으로 20개
    var itemTimer = null;
    if (lineForm) {
        lfield('itemCd').addEventListener('input', function () {
            clearTimeout(itemTimer);
            var q = lfield('itemCd').value.trim();
            if (q.length < 2) { return; }
            itemTimer = setTimeout(function () {
                hb.latest('items', hb.api(API + '/items?q=' + encodeURIComponent(q))).then(function (res) {
                    var dl = document.getElementById('itemList');
                    dl.textContent = '';
                    res.data.forEach(function (i) {
                        var o = hb.el('option', null, i.itemNm + ' (' + i.unitCd + ')');
                        o.value = i.itemCd;
                        dl.appendChild(o);
                    });
                }, function () { /* 입력 보조 — 실패해도 입력은 된다 */ });
            }, 250);
        });
    }

    if (!CAN_SAVE) {
        form.querySelectorAll('input, select').forEach(function (i) { i.disabled = true; });
    }

    hb.dirty.watch(form);
    if (lineForm) { hb.dirty.watch(lineForm); }
    hb.loadMessages().then(function () {
        hb.page.init();
        // APS 생산 계획을 볼 수 있으면 [납기 답변] — 못 읽어도 수주 화면은 그대로(버튼만 없음)
        hb.api(API + '/ctp-available').then(function (res) {
            apsOk = res.data === true;
            if (apsOk) { renderLines(); }
        }, function () { apsOk = false; });
        return Promise.all([hb.loadSession(), hb.api(API + '/customers')]);
    }).then(function (r) {
        var all = hb.el('option', null, hb.t('js.salOrder.allCust'));
        all.value = '';
        document.getElementById('custSel').appendChild(all);
        var pick = hb.el('option', null, hb.t('js.salOrder.pickCust'));
        pick.value = '';
        field('custCd').appendChild(pick);
        r[1].data.forEach(function (c) {
            [document.getElementById('custSel'), field('custCd')].forEach(function (sel) {
                var o = hb.el('option', null, c.custCd + ' · ' + c.custNm);
                o.value = c.custCd;
                sel.appendChild(o);
            });
        });
        sp = hb.searchPanel(search, {onSearch: function () {
            if (!grid) { return null; }
            return confirmLeave().then(function (ok) {
                if (!ok) { return null; }
                dueInfo();
                return grid.reload(true);
            });
        }});
        return sp.ready;
    }).then(function () {
        show(null);
        dueInfo();
        grid = hb.grid('#orderGrid', {
            progCd: PROG, id: 'main', key: 'orderId',
            url: API + '/grid', columnsUrl: API + '/columns', exportUrl: API + '/export',
            actions: ACTIONS, params: params,
            format: {
                custNm: function (v, row) { return row.custCd + ' · ' + v; },
                openCnt: function (v, row) { return v + ' / ' + row.lineCnt; }
            },
            beforeSelect: function () { return confirmLeave(); },
            status: {status: mesUi.TONES.order},                                              // 수주 상태 = 상태 배지(4.29 톤 표)
            rowClass: function (row) { return row.status !== 'OPEN' ? 'hb-tree-dim' : null; },   // 끝난(완료·취소) 수주 흐리게
            onSelect: function (row) { open(row.orderId); hb.page.openDetail(); },
            notify: notify
        });
        return grid.ready;
    }).then(null, function (err) { notify(err && err.message, true); });
})();
