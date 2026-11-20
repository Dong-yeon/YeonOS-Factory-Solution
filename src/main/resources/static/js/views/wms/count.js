// @ts-check
/* 재고 실사 (wms-count) — P4 1부 W5 (docs/30 4.5). 실사 목록 → 오른쪽: 머리·상태 버튼·줄(센 수량 칸)·줄 추가.
   장부 수량은 서버가 "센 시각까지 원장 합"으로 계산하고, 블라인드 실사는 세는 동안 장부·차이를 보내지 않는다.
   상태를 바꾸는 요청은 머리 rowVer 를 함께 보낸다(다른 사람이 먼저 바꿨으면 409 → 다시 읽기). 공통 hb.* 는 고치지 않는다. */
/**
 * @typedef {{countId: number, plantCd: string, countNo: string, countType: string, scopeNodeId: number, scopePath: string,
 *   itemCd?: string | null, blindYn: string, tolPct: string | number, status: string, remark?: string | null, startedAt?: string | null,
 *   postedAt?: string | null, postedBy?: string | null, cancelReason?: string | null, rowVer: number, createdAt: string,
 *   createdBy: string, lineCount: number, countedCount: number, diffCount: number}} WmsCountHead
 * @typedef {{lineId: number, nodeId: number, path: string, itemCd: string, itemNm: string, unitCd: string, stockStatus: string,
 *   lotNo?: string | null,
 *   recountNo: number, bookQty?: string | null, countedQty?: string | null, countedAt?: string | null, countedBy?: string | null,
 *   countedWorkerCd?: string | null, countedWorkerNm?: string | null,
 *   diffQty?: string | null, diffPct?: string | null, overTol: boolean, adjTrxId?: number | null, latest: boolean}} WmsCountLine
 * @typedef {{head: WmsCountHead, blindHidden: boolean, lines: WmsCountLine[]}} WmsCountView
 * @typedef {{nodeId: number, nodeCd: string, nodeNm: string, path: string}} WmsCountNode
 */
(function () {
    'use strict';

    /** 실사 상태 → hb-status 톤(docs/40 부록 A — 검토 = 확인 필요) @type {Record<string, HB.StatusTone>} */
    var TONE = {DRAFT: 'neutral', COUNTING: 'normal', REVIEW: 'warning', POSTED: 'success', CANCELLED: 'neutral'};

    var API = '/api/wms/counts';
    var ACTIONS = (document.body.getAttribute('data-actions') || '').split(',');
    var CAN_SAVE = ACTIONS.indexOf('SAVE') >= 0;
    var msg = /** @type {HTMLElement} */ (document.getElementById('msg'));
    var search = /** @type {HTMLFormElement} */ (document.getElementById('search'));
    var plantSel = /** @type {HTMLSelectElement} */ (document.getElementById('plantSel'));
    var PROG = document.body.getAttribute('data-prog') || 'wms-count';
    /** 실사 목록 그리드(UI 단계 3 ① — /grid·/columns) @type {HB.Grid | null} */
    var grid = null;
    /** 동작 직후 목록을 다시 읽을 때 — 이미 그린 상세를 그리드 onSelect 가 한 번 더 읽지 않게 @type {number | null} */
    var quietId = null;
    var lineBody = /** @type {HTMLTableSectionElement} */ (document.querySelector('#lines tbody'));
    var info = /** @type {HTMLElement} */ (document.getElementById('countInfo'));
    var newBox = /** @type {HTMLElement} */ (document.getElementById('newBox'));
    var newForm = /** @type {HTMLFormElement} */ (document.getElementById('newForm'));
    var actBox = /** @type {HTMLElement} */ (document.getElementById('actBox'));
    var lineHost = /** @type {HTMLElement} */ (document.getElementById('lineHost'));
    var lineTools = /** @type {HTMLElement} */ (document.getElementById('lineTools'));
    var pasteHint = /** @type {HTMLElement} */ (document.getElementById('pasteHint'));
    var addBox = /** @type {HTMLElement} */ (document.getElementById('addBox'));
    var addForm = /** @type {HTMLFormElement} */ (document.getElementById('addForm'));
    /** @type {WmsCountView | null} */
    var current = null;
    /** @type {HB.SearchPanel} */
    var sp;
    /** 공장별 선택지를 마지막으로 읽은 공장 — 열린 탭에 다른 공장 링크가 오면 다시 읽는다(QA L-b) @type {string | null} */
    var optPlant = null;
    /** @type {Record<string, string>} */
    var typeNames = {};
    /** @type {Record<string, string>} */
    var statusNames = {};
    /** @type {Record<string, string>} */
    var stockNames = {};

    /**
     * 저장하지 않은 입력(센 수량 줄·신규 폼)이 있으면 화면 안 대화상자로 묻는다(docs/40 V-7) — 없으면 바로 true.
     * QA QU-2: 줄 칸은 상세를 그릴 때마다 새로 만들어 hb.dirty 가 몰랐다 → 다른 실사·신규·다른 동작에서 말없이 사라졌다
     * @returns {Promise<boolean>}
     */
    function confirmLeave() {
        if (!hb.dirty.isDirty()) { return Promise.resolve(true); }
        return hb.dialog.confirm({title: hb.t('js.common.leaveTitle'), message: hb.t('js.common.confirmLeave'), ok: hb.t('js.common.leaveOk')});
    }

    /** @param {string | null | undefined} text @param {boolean} [error] */
    function notify(text, error) {
        msg.textContent = text || '';
        msg.classList.toggle('hb-msg-error', !!error);
    }

    /** @param {HB.ApiError} err */
    function errText(err) {
        return err.errors && err.errors.length ? err.errors.map(function (x) { return x.message; }).join(' / ') : err.message;
    }

    /** @param {string | number | null | undefined} v */
    function q(v) { return v == null ? '' : hb.fmt.num(v, 6, 0); }

    /** @param {HTMLSelectElement} sel @param {string} value @param {string} text */
    function option(sel, value, text) {
        var o = /** @type {HTMLOptionElement} */ (hb.el('option', null, text));
        o.value = value;
        sel.appendChild(o);
    }

    /** @param {string} id */
    function btn(id) { return /** @type {HTMLButtonElement | null} */ (document.getElementById(id)); }

    /** @param {HTMLFormElement} f @param {string} name */
    function ff(f, name) { return /** @type {HTMLInputElement | HTMLSelectElement} */ (f.querySelector('[data-field="' + name + '"]')); }

    /** @param {HTMLElement} body @param {number} cols @param {string} text */
    function emptyRow(body, cols, text) {
        var tr = hb.el('tr');
        var td = /** @type {HTMLTableCellElement} */ (hb.el('td', 'hb-muted', text));
        td.colSpan = cols;
        tr.appendChild(td);
        body.appendChild(tr);
    }

    // ------------------------------------------------------------------ 목록

    /**
     * 목록 다시 읽기 — selectId(새로 만든 실사)는 그리드가 골라 onSelect 로 연다. 없으면 지금 연 실사를 그대로 고른다(상세는 다시 읽지 않음)
     * @param {number} [selectId]
     */
    function load(selectId) {
        if (!grid) { return Promise.resolve(); }
        var keep = selectId != null ? selectId : current ? current.head.countId : null;
        quietId = selectId != null ? null : keep;
        return grid.reload(false, keep).then(function () { quietId = null; });
    }

    /** @param {number} id */
    function open(id) {
        return hb.latest('wmsCount', hb.api(API + '/' + id)).then(function (res) {
            show(/** @type {WmsCountView} */ (res.data));
        }, function (err) { if (!err.stale) { notify(errText(err), true); } });
    }

    // ------------------------------------------------------------------ 상세

    /** @param {WmsCountView | null} v */
    function show(v) {
        current = v;
        newBox.hidden = true;
        lineBody.textContent = '';
        if (!v) {
            info.textContent = hb.t('js.wmsCount.selectFirst');
            actBox.hidden = true;
            lineHost.hidden = true;
            lineTools.hidden = true;
            addBox.hidden = true;
            return;
        }
        var h = v.head;
        info.textContent = hb.t('js.wmsCount.title', h.countNo, typeNames[h.countType] || h.countType, statusNames[h.status] || h.status,
            h.scopePath, h.blindYn === 'Y' ? hb.t('js.wmsCount.blindOn') : hb.t('js.wmsCount.blindOff'), q(h.tolPct))
            + (h.cancelReason ? ' — ' + h.cancelReason : '')
            + (h.postedAt ? ' — ' + hb.t('js.wmsCount.postedBy', hb.fmt.datetime(h.postedAt), h.postedBy || '') : '');
        var st = h.status;
        actBox.hidden = !CAN_SAVE || st === 'POSTED' || st === 'CANCELLED';
        /** @type {Record<string, boolean>} */
        var showBtn = {startBtn: st === 'DRAFT', saveCountsBtn: st === 'COUNTING', submitBtn: st === 'COUNTING', recountBtn: st === 'REVIEW',
            postBtn: st === 'REVIEW', cancelBtn: st !== 'POSTED' && st !== 'CANCELLED'};
        Object.keys(showBtn).forEach(function (k) {
            var b = btn(k);
            if (b) { b.hidden = !showBtn[k]; }
        });
        addBox.hidden = !CAN_SAVE || (st !== 'DRAFT' && st !== 'COUNTING');
        lineHost.hidden = false;
        lineTools.hidden = false;
        pasteHint.hidden = !(CAN_SAVE && st === 'COUNTING');
        if (!v.lines.length) { emptyRow(lineBody, 5, h.countType === 'OPENING' ? hb.t('js.wmsCount.noLinesOpening') : hb.t('js.wmsCount.noLines')); }
        v.lines.forEach(function (l) {
            var tr = hb.el('tr', l.latest ? (l.overTol ? 'hb-tree-flag' : null) : 'hb-tree-dim');
            tr.setAttribute('data-line', String(l.lineId));
            var pick = hb.el('td');
            if (st === 'REVIEW' && l.latest) {
                var cb = /** @type {HTMLInputElement} */ (hb.el('input'));
                cb.type = 'checkbox';
                cb.className = 'wms-recount';
                cb.value = String(l.lineId);
                cb.setAttribute('aria-label', hb.t('js.wmsCount.recountPick', l.path));
                pick.appendChild(cb);
            }
            tr.appendChild(pick);
            tr.appendChild(hb.el('td', 'hb-wrap', l.path + ' · ' + l.itemCd + ' ' + l.itemNm + (l.lotNo ? ' · LOT ' + l.lotNo : '')
                + (l.stockStatus === 'AVAILABLE' ? '' : ' · ' + (stockNames[l.stockStatus] || l.stockStatus))
                + (l.recountNo ? ' · ' + hb.t('js.wmsCount.recountNo', l.recountNo) : '')));
            tr.appendChild(hb.el('td', 'hb-num', v.blindHidden ? '—' : q(l.bookQty)));
            var cell = hb.el('td');
            if (st === 'COUNTING' && l.latest && CAN_SAVE) {
                var inp = /** @type {HTMLInputElement} */ (hb.el('input', 'wms-counted'));
                inp.inputMode = 'decimal';
                inp.maxLength = 30;
                inp.value = l.countedQty == null ? '' : q(l.countedQty);
                inp.setAttribute('data-line', String(l.lineId));
                inp.setAttribute('aria-label', hb.t('js.wmsCount.countedFor', l.path, l.itemCd));
                cell.appendChild(inp);
            } else {
                cell.textContent = l.countedQty == null ? '-' : q(l.countedQty) + ' ' + l.unitCd;
            }
            tr.appendChild(cell);
            tr.appendChild(hb.el('td', 'hb-num', v.blindHidden || l.diffQty == null ? '' : q(l.diffQty)
                + (l.diffPct ? ' (' + q(l.diffPct) + '%)' : '') + (l.adjTrxId ? ' ✓' : '')));
            // 센 사람 — PDA 면 배지 작업자(이름·코드), 사무 입력이면 로그인 아이디(단말 행위자는 작업자가 없을 때만)
            tr.appendChild(hb.el('td', 'hb-wrap', l.countedQty == null ? '' : l.countedWorkerCd
                ? l.countedWorkerNm + ' (' + l.countedWorkerCd + ')' : (l.countedBy || '')));
            lineBody.appendChild(tr);
        });
        hb.dirty.clear();
    }

    // ------------------------------------------------------------------ 동작

    /**
     * 머리 rowVer 와 함께 상태 동작. 409(낡음)면 다시 읽는다. 성공했을 때만 true 로 끝난다(묻고 머묾·실패 = 아님)
     * @param {HTMLButtonElement} b @param {string} path @param {Record<string, unknown>} [extra] @param {string} [method]
     * @returns {Promise<boolean | void>}
     */
    function act(b, path, extra, method) {
        if (!current) { return Promise.resolve(); }
        var cur = current;
        // [수량 저장] 이 아닌 동작은 성공하면 줄을 다시 그린다 — 저장 안 한 센 수량이 있으면 먼저 묻는다(QU-2)
        var go = path === '/counts' ? Promise.resolve(true) : confirmLeave();
        return go.then(function (ok) { return ok && current === cur ? run() : undefined; });

        function run() {
            var id = cur.head.countId;
            var body = Object.assign({rowVer: cur.head.rowVer}, extra || {});
            return hb.busy(b, function () {
                return hb.api(API + '/' + id + path, {method: /** @type {HB.ApiOptions['method']} */ (method || 'POST'), body: body});
            }).then(function (res) {
                if (!res) { return; }
                show(/** @type {WmsCountView} */ (res.data));
                return load().then(function () { notify(hb.t('js.wmsCount.done')); return true; });
            }, function (err) {
                notify(errText(err), true);
                if (err.errors) { markLineErrors(err.errors); }
                if (err.status === 409) { open(id); }
            });
        }
    }

    /** 줄 오류(rowKey = lineId)를 그 줄 입력 칸에 @param {HB.FieldError[]} errors */
    function markLineErrors(errors) {
        lineBody.querySelectorAll('input.wms-counted').forEach(function (i) { i.removeAttribute('aria-invalid'); i.removeAttribute('title'); });
        errors.forEach(function (e) {
            if (!e.rowKey) { return; }
            var inp = lineBody.querySelector('input.wms-counted[data-line="' + e.rowKey + '"]');
            if (inp) {
                inp.setAttribute('aria-invalid', 'true');
                inp.setAttribute('title', e.message);
            }
        });
    }

    /** @param {string} id @param {(b: HTMLButtonElement) => void} fn */
    function bind(id, fn) {
        var b = btn(id);
        if (b) {
            var bb = b;
            bb.addEventListener('click', function () { fn(bb); });
        }
    }

    bind('startBtn', function (b) { act(b, '/start'); });
    bind('submitBtn', function (b) { act(b, '/submit'); });
    bind('postBtn', function (b) {
        if (!current) { return; }
        var diffs = current.lines.filter(function (l) { return l.latest && l.diffQty && l.diffQty !== '0'; }).length;
        hb.dialog.confirm({title: hb.t('js.wmsCount.dlg.post', current.head.countNo), message: hb.t('js.wmsCount.confirmPost', current.head.countNo, diffs),
            ok: hb.t('js.wmsCount.dlg.postOk')}).then(function (yes) { if (yes) { act(b, '/post'); } });
    });
    bind('cancelBtn', function (b) {
        if (!current) { return; }
        hb.dialog.prompt({title: hb.t('js.wmsCount.dlg.cancel', current.head.countNo), message: hb.t('js.wmsCount.cancelPrompt', current.head.countNo),
            label: hb.t('js.wmsCount.reason'), required: true, maxLength: 500, ok: hb.t('js.wmsCount.dlg.cancelOk'), danger: true})
            .then(function (reason) { if (reason !== null) { act(b, '/cancel', {reason: reason}); } });
    });
    bind('recountBtn', function (b) {
        var ids = Array.prototype.map.call(lineBody.querySelectorAll('input.wms-recount:checked'),
            function (/** @type {HTMLInputElement} */ c) { return Number(c.value); });
        if (!ids.length) { notify(hb.t('js.wmsCount.pickRecount'), true); return; }
        act(b, '/recount', {lineIds: ids});
    });
    bind('saveCountsBtn', function (b) {
        /** @type {{lineId: number, countedQty: string}[]} */
        var rows = [];
        lineBody.querySelectorAll('input.wms-counted').forEach(function (i) {
            var inp = /** @type {HTMLInputElement} */ (i);
            if (inp.value.trim() !== '') { rows.push({lineId: Number(inp.getAttribute('data-line')), countedQty: inp.value.trim().replace(/,/g, '')}); }
        });
        if (!rows.length) { notify(hb.t('js.wmsCount.nothingToSave'), true); return; }
        act(b, '/counts', {rows: rows}, 'PUT');
    });
    bind('exportBtn', function (b) {
        if (!current) { return; }
        var id = current.head.countId;
        hb.busy(b, function () { return hb.download(API + '/' + id + '/export', {}); })
            .then(function (name) { if (name) { notify(hb.t('js.wmsCount.exported', name)); } }, function (err) { notify(err.message, true); });
    });
    bind('addBtn', function (b) {
        if (!current) { return; }
        hb.showFieldErrors(addForm, []);
        var body = {nodeId: ff(addForm, 'nodeId').value ? Number(ff(addForm, 'nodeId').value) : null, itemCd: ff(addForm, 'itemCd').value,
            lotNo: ff(addForm, 'lotNo').value, stockStatus: ff(addForm, 'stockStatus').value || 'AVAILABLE'};
        // 더한 때만 칸을 비운다 — 묻고 머물렀거나 실패했으면 입력 그대로
        act(b, '/lines', body).then(function (done) { if (done) { ff(addForm, 'itemCd').value = ''; ff(addForm, 'lotNo').value = ''; } });
    });
    addForm.addEventListener('submit', function (e) { e.preventDefault(); });
    newForm.addEventListener('submit', function (e) { e.preventDefault(); });
    hb.dirty.watch(newForm);
    /**
     * 엑셀 붙여넣기(③) — 센 수량 칸에 여러 줄을 붙이면 채운다(저장은 [센 수량 저장] — rowVer 규칙 그대로).
     * ① 한 열: 붙인 칸부터 아래로 차례대로 ② 두 열 "줄 번호 · 센 수량" ③ 내보낸 엑셀 행 그대로(첫 열 줄 번호, 10번째 열 센 수량).
     * ②③은 줄 번호로 맞춘다 — 순서가 달라도 엉뚱한 줄에 들어가지 않게. 빈 값은 건너뛴다(지우지 않음), 숫자 아닌 값은 세어 알린다
     */
    lineBody.addEventListener('paste', function (e) {
        var t = e.target;
        if (!(t instanceof HTMLInputElement) || !t.classList.contains('wms-counted') || !e.clipboardData) { return; }
        var text = e.clipboardData.getData('text/plain').replace(/\r/g, '');
        var rows = text.split('\n');
        while (rows.length && rows[rows.length - 1].trim() === '') { rows.pop(); }
        if (rows.length <= 1 && rows.every(function (r) { return r.indexOf('\t') < 0; })) { return; }   // 한 칸 붙여넣기는 그대로
        e.preventDefault();
        var inputs = /** @type {HTMLInputElement[]} */ (Array.prototype.slice.call(lineBody.querySelectorAll('input.wms-counted')));
        /** @type {Record<string, HTMLInputElement>} */
        var byLine = {};
        inputs.forEach(function (i) { byLine[String(i.getAttribute('data-line'))] = i; });
        var num = /^-?\d+(\.\d+)?$/;
        var filled = 0, skipped = 0, bad = 0;
        /** @param {HTMLInputElement | undefined} inp @param {string} raw */
        function put(inp, raw) {
            var v = (raw || '').trim().replace(/,/g, '');
            if (v === '') { return; }
            if (!inp) { skipped++; return; }
            if (!num.test(v)) { bad++; return; }
            inp.value = v;
            filled++;
        }
        var keyed = rows.every(function (r) { return r.indexOf('\t') >= 0; });
        if (keyed) {
            rows.forEach(function (r) {
                var c = r.split('\t');
                if (!/^\d+$/.test(c[0].trim())) { return; }                 // 머리 줄 등
                put(byLine[c[0].trim()], c.length >= 10 ? c[9] : c[1]);
            });
        } else {
            var start = inputs.indexOf(t);
            rows.forEach(function (r, k) { put(inputs[start + k], r); });
        }
        hb.dirty.mark(lineBody);
        notify(hb.t('js.wmsCount.pasted', filled, skipped, bad), skipped + bad > 0);
    });
    lineBody.addEventListener('input', function (e) {
        var t = e.target;
        if (t instanceof HTMLInputElement && t.classList.contains('wms-counted')) { hb.dirty.mark(lineBody); }
    });

    /** 공장 노드 선택지(범위·줄 추가) */
    function loadNodes() {
        var plant = plantSel.value;
        optPlant = plant;
        return hb.latest('wmsCountNodes', hb.api(API + '/nodes?plantCd=' + encodeURIComponent(plant))).then(function (res) {
            var list = /** @type {WmsCountNode[]} */ (res.data);
            [/** @type {HTMLSelectElement} */ (document.getElementById('scopeSel')),
                /** @type {HTMLSelectElement} */ (document.getElementById('addNodeSel'))].forEach(function (sel) {
                sel.textContent = '';
                option(sel, '', hb.t('js.common.choose'));
                list.forEach(function (n) { option(sel, String(n.nodeId), n.path + ' · ' + n.nodeNm); });
            });
        }, function (err) { if (!err.stale) { notify(errText(err), true); } });
    }

    bind('newBtn', function () {
        confirmLeave().then(function (ok) { if (ok) { startNew(); } });
    });
    function startNew() {
        show(null);
        info.textContent = hb.t('js.wmsCount.newTitle', plantSel.value);
        newBox.hidden = false;
        newForm.reset();                                   // 앞 실사에서 고른 값(블라인드 아니오 등)이 남지 않게
        hb.showFieldErrors(newForm, []);
        hb.page.openDetail();
    }
    bind('createBtn', function (b) {
        hb.showFieldErrors(newForm, []);
        var body = {plantCd: plantSel.value, countType: ff(newForm, 'countType').value, scopeNodeId: ff(newForm, 'scopeNodeId').value
            ? Number(ff(newForm, 'scopeNodeId').value) : null, itemCd: ff(newForm, 'itemCd').value, blindYn: ff(newForm, 'blindYn').value,
            remark: ff(newForm, 'remark').value};
        hb.busy(b, function () { return hb.api(API, {method: 'POST', body: body}); }).then(function (res) {
            if (!res) { return; }
            var v = /** @type {WmsCountView} */ (res.data);
            hb.dirty.clear(newForm);
            return load(v.head.countId).then(function () { notify(hb.t('js.wmsCount.created', v.head.countNo)); });
        }, function (err) {
            notify(errText(err), true);
            hb.showFieldErrors(newForm, err.errors);
        });
    });

    plantSel.addEventListener('change', function () { loadNodes(); });

    hb.loadMessages().then(function () {
        hb.page.init();
        return Promise.all([hb.loadSession(), hb.api(API + '/plants'), hb.codes('WMS_COUNT_TYPE'), hb.codes('WMS_COUNT_STATUS'),
            hb.codes('STOCK_STATUS')]);
    }).then(function (r) {
        typeNames = r[2];
        statusNames = r[3];
        stockNames = r[4];
        /** @type {{plantCd: string, plantNm: string}[]} */ (r[1].data).forEach(function (p) {
            option(plantSel, p.plantCd, p.plantCd + ' · ' + p.plantNm);
        });
        hb.select(/** @type {HTMLSelectElement} */ (ff(newForm, 'countType')));
        hb.select(/** @type {HTMLSelectElement} */ (ff(addForm, 'stockStatus')));
        sp = hb.searchPanel(search, {onSearch: function () { return grid ? grid.reload(true) : null; },
            // 열린 탭에 다른 공장 링크 — 공장 칸을 값으로 바꾸면 change 가 안 나므로 범위 칸 선택지를 여기서 다시 읽는다(QA L-b). 조회는 그대로
            onLink: function () { if (plantSel.value !== optPlant) { loadNodes(); } return false; }});
        grid = hb.grid('#countGrid', {
            progCd: PROG, id: 'main', key: 'countId',
            url: API + '/grid', columnsUrl: API + '/columns', exportUrl: API + '/export',
            actions: ACTIONS, params: function () { return sp.values(); },
            status: {status: TONE},                                                // 상태 칸 = 배지
            rowClass: function (row) { return row.status === 'CANCELLED' ? 'hb-tree-dim' : null; },   // 취소 실사 흐리게
            beforeSelect: function () { return confirmLeave(); },                  // 센 수량을 고친 채 다른 실사로 옮기면 묻는다(QU-2)
            onSelect: function (row) {
                var id = Number(row.countId);
                if (quietId === id) { quietId = null; return; }
                open(id);
                hb.page.openDetail();
            },
            notify: notify
        });
        // 홈 링크(?q.plantCd)는 sp.ready 에서 채워진다 — 그 뒤 공장으로 범위 칸을 읽는다(docs/37 20장)
        return sp.ready.then(function () { return loadNodes(); });
    }).then(function () {
        show(null);
        if (!grid) { return null; }
        var g = grid;
        return g.ready.then(function () { return g.reload(true); });
    }).then(null, function (err) { notify(err && err.message, true); });
})();
