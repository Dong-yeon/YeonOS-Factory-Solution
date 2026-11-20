// @ts-check
/* 출하 전 검수 (qms-preship) — docs/78. 출하 목록(hb.grid) + 상세(검수 머리 폼 + 품목별 항목 판정 카드 + 사진). 판정은 서버가 확정 */
/**
 * @typedef {{shipId: number, shipNo: string, planDate: string, custNm: string, shipStatus: string, requiredYn: string,
 *   inspId: number | null, inspNo: string | null, inspStatus: string, resultCnt: number, judgedCnt: number, openPunchCnt: number,
 *   [field: string]: any}} PreshipShipRow
 * @typedef {{inspId: number, inspNo: string, shipId: number, shipStatus: string, status: string, inspectorNm: string | null,
 *   witnessNm: string | null, remark: string | null, rowVer: number}} PreshipHead
 * @typedef {{resultId: number, itemId: number, itemCd: string, itemNm: string, seq: number, grpNm: string | null, checkText: string,
 *   naAllowedYn: string, photoReqYn: string, judge: string | null, remark: string | null, judgedBy: string | null, rowVer: number,
 *   fileCnt: number, openPunchId: number | null, openPunchAllowYn: string | null}} PreshipResult
 * @typedef {{ship: PreshipShipRow, requiredItems: string[], insp: PreshipHead | null, results: PreshipResult[]}} PreshipDetail
 * @typedef {{fileId: number, name: string}} PreshipFile
 */
(function () {
    'use strict';

    var API = '/api/qms/preship';
    var FILES = '/api/platform/files';
    var ACTIONS = (document.body.getAttribute('data-actions') || '').split(',');
    var CAN_SAVE = ACTIONS.indexOf('SAVE') >= 0;
    var PROG = document.body.getAttribute('data-prog') || '';
    var msg = /** @type {HTMLElement} */ (document.getElementById('msg'));
    var head = /** @type {HTMLFormElement} */ (document.getElementById('headForm'));
    var resultsBox = /** @type {HTMLElement} */ (document.getElementById('results'));
    var shipInfo = /** @type {HTMLElement} */ (document.getElementById('shipInfo'));
    var requiredInfo = /** @type {HTMLElement} */ (document.getElementById('requiredInfo'));
    var search = /** @type {HTMLFormElement} */ (document.getElementById('search'));
    /** @type {PreshipDetail | null} */
    var detail = null;
    /** @type {HB.SearchPanel} */
    var sp;
    /** @type {HB.Grid | undefined} */
    var grid;
    /** @type {Record<string, string>} */
    var shipNames = {};
    /** 판정·검수 상태 이름 — 키는 리터럴로(I18nKeyGuardTest) @type {Record<string, string>} */
    var judgeNames = {};
    /** @type {Record<string, string>} */
    var statusNames = {};
    /** @type {Record<string, string>} */
    var judgeTips = {OK: 'js.tip.qms-preship.judgeOK', NG: 'js.tip.qms-preship.judgeNG', NA: 'js.tip.qms-preship.judgeNA'};

    /** @param {string | null | undefined} text @param {boolean} [error] */
    function notify(text, error) {
        msg.textContent = text || '';
        msg.classList.toggle('hb-msg-error', !!error);
    }

    /** 오류 → 한 줄(칸 오류 문구 포함) @param {HB.ApiError} err */
    function errorText(err) {
        if (err && err.errors && err.errors.length) {
            return err.message + ' ' + err.errors.map(function (x) { return x.message; }).join(' / ');
        }
        return err && err.message;
    }

    /** @param {string} id */
    function btn(id) {
        return /** @type {HTMLButtonElement | null} */ (document.getElementById(id));
    }

    /** @param {string} n */
    function field(n) {
        return /** @type {HTMLInputElement} */ (head.querySelector('[data-field="' + n + '"]'));
    }

    /** @param {string} id @param {boolean} on */
    function showBtn(id, on) {
        var b = btn(id);
        if (b) { b.hidden = !on; }
    }

    /** 사진 목록 + 올리기 @param {number} resultId @param {boolean} canUpload */
    function photos(resultId, canUpload) {
        var box = hb.el('div', 'hb-qms-preship-photos');
        var list = hb.el('ul');
        box.appendChild(list);
        function refresh() {
            return hb.latest('preship-files-' + resultId, hb.api(FILES + '?targetType=' + PROG + '&targetId=' + resultId)).then(function (res) {
                list.textContent = '';
                /** @type {PreshipFile[]} */ (res.list || []).forEach(function (f) {
                    var li = hb.el('li');
                    var a = hb.el('a', null, f.name);
                    a.href = FILES + '/' + f.fileId;
                    li.appendChild(a);
                    list.appendChild(li);
                });
            }, function (err) { if (!err.stale) { notify(err.message, true); } });
        }
        if (canUpload) {
            var lab = hb.el('label', 'hb-check');
            lab.appendChild(hb.el('span', null, hb.t('js.qms.preship.addPhoto')));
            var input = hb.el('input');
            input.type = 'file';
            input.accept = 'image/*';
            lab.appendChild(input);
            box.appendChild(lab);
            input.addEventListener('change', function () {
                if (!input.files || !input.files.length) { return; }
                var fd = new FormData();
                fd.append('targetType', PROG);
                fd.append('targetId', String(resultId));
                fd.append('file', input.files[0]);
                hb.upload(FILES, fd).then(function () {
                    input.value = '';
                    notify(hb.t('js.qms.preship.photoAdded'));
                    return refresh();
                }, function (err) { notify(err.message, true); });
            });
        }
        refresh();
        return box;
    }

    /** 항목 카드 — 판정 단추는 검수 OPEN·SAVE 일 때만 @param {PreshipResult} r @param {boolean} open */
    function card(r, open) {
        var c = hb.el('div', 'hb-qms-preship-card');
        var title = hb.el('p', null, r.seq + '. ' + (r.grpNm ? '[' + r.grpNm + '] ' : '') + r.checkText);
        c.appendChild(title);
        var tags = hb.el('p', 'hb-muted');
        if (r.photoReqYn === 'Y') { tags.appendChild(hb.status('info', hb.t('js.qms.preship.photoReq'))); }
        if (r.naAllowedYn === 'Y') { tags.appendChild(hb.status('neutral', hb.t('js.qms.preship.naAllowed'))); }
        if (r.judge) {
            var tone = r.judge === 'OK' ? 'normal' : r.judge === 'NG' ? 'danger' : 'neutral';
            tags.appendChild(hb.status(/** @type {HB.StatusTone} */ (tone), judgeNames[r.judge] || r.judge, r.judgedBy));
        } else {
            tags.appendChild(hb.status('warning', hb.t('js.qms.preship.unjudged')));
        }
        if (r.openPunchId) {
            tags.appendChild(hb.status('danger', r.openPunchAllowYn === 'Y' ? hb.t('js.qms.preship.punchAllowed') : hb.t('js.qms.preship.punchOpen')));
        }
        c.appendChild(tags);
        var remarkLab = hb.el('label', 'hb-wide');
        remarkLab.appendChild(hb.el('span', null, hb.t('js.qms.preship.remark')));
        var remark = hb.el('input');
        remark.maxLength = 500;
        remark.value = r.remark || '';
        // 열린 미결이 있는 줄은 판정을 바꾸지 않는다(서버 409) — 단추 대신 미결 화면 안내
        var locked = !!r.openPunchId;
        remark.disabled = !open || !CAN_SAVE || locked;
        remarkLab.appendChild(remark);
        c.appendChild(remarkLab);
        if (locked) {
            c.appendChild(hb.el('p', 'hb-muted', hb.t('js.qms.preship.punchHint')));
        } else if (open && CAN_SAVE) {
            var bar = hb.el('div', 'hb-toolbar');
            ['OK', 'NG', 'NA'].forEach(function (j) {
                if (j === 'NA' && r.naAllowedYn !== 'Y') { return; }
                var b = hb.el('button', j === 'OK' ? null : 'hb-btn-secondary', judgeNames[j] || j);
                b.type = 'button';
                b.setAttribute('data-tip-key', judgeTips[j]);
                b.addEventListener('click', function () { judge(r, j, remark.value, b); });
                bar.appendChild(b);
            });
            c.appendChild(bar);
        }
        c.appendChild(photos(r.resultId, open && CAN_SAVE || !!r.openPunchId && CAN_SAVE));
        return c;
    }

    /** @param {PreshipResult} r @param {string} j @param {string} remark @param {HTMLButtonElement} b */
    function judge(r, j, remark, b) {
        if (!detail || !detail.insp) { return; }
        var inspId = detail.insp.inspId;
        hb.busy(b, function () {
            return hb.api(API + '/insps/' + inspId + '/judge', {method: 'POST', body: {resultId: r.resultId, rowVer: r.rowVer, judge: j, remark: remark}});
        }).then(function (res) {
            if (!res) { return; }
            notify(res.data.punchId ? hb.t('js.qms.preship.punchRaised') : hb.t('js.qms.preship.judged'));
            return refresh();
        }, function (err) { notify(errorText(err), true); });
    }

    /** @param {PreshipDetail} d */
    function render(d) {
        detail = d;
        var s = d.ship;
        shipInfo.textContent = s.shipNo + ' · ' + s.custNm + ' · ' + (shipNames[s.shipStatus] || s.shipStatus) + ' · ' + hb.fmt.date(s.planDate);
        requiredInfo.textContent = d.requiredItems.length
            ? hb.t('js.qms.preship.requiredItems', d.requiredItems.join(', '))
            : hb.t('js.qms.preship.notRequired');
        var h = d.insp;
        var open = !!h && h.status === 'OPEN';
        var shipOpen = s.shipStatus === 'DRAFT' || s.shipStatus === 'PICKING';
        showBtn('startBtn', !h && d.requiredItems.length > 0 && shipOpen);
        showBtn('saveHeadBtn', open);
        showBtn('completeBtn', open);
        showBtn('cancelBtn', open);
        showBtn('reopenBtn', !!h && h.status === 'DONE' && s.shipStatus !== 'SHIPPED');
        head.hidden = !h;
        hb.showFieldErrors(head, []);
        field('inspNo').textContent = h ? h.inspNo : '';
        field('status').textContent = h ? statusNames[h.status] || h.status : '';
        field('inspectorNm').value = h && h.inspectorNm ? h.inspectorNm : (h ? hb.session().userNm || '' : '');
        field('witnessNm').value = h && h.witnessNm ? h.witnessNm : '';
        field('remark').value = h && h.remark ? h.remark : '';
        ['inspectorNm', 'witnessNm', 'remark'].forEach(function (n) { field(n).disabled = !open || !CAN_SAVE; });
        resultsBox.textContent = '';
        /** @type {number | null} */
        var lastItem = null;
        d.results.forEach(function (r) {
            if (r.itemId !== lastItem) {
                resultsBox.appendChild(hb.el('h3', 'hb-qms-preship-item', r.itemCd + ' ' + r.itemNm));
                lastItem = r.itemId;
            }
            resultsBox.appendChild(card(r, open));
        });
        hb.dirty.clear(head);
    }

    /** @param {number} shipId */
    function load(shipId) {
        return hb.latest('preship-ship', hb.api(API + '/ships/' + shipId)).then(function (res) {
            render(/** @type {PreshipDetail} */ (res.data));
        }, function (err) { if (!err.stale) { notify(err.message, true); } });
    }

    function refresh() {
        if (!detail) { return Promise.resolve(); }
        var id = detail.ship.shipId;
        return (grid ? grid.reload(false, id) : Promise.resolve()).then(function () { return load(id); });
    }

    function headBody() {
        return {rowVer: detail && detail.insp ? detail.insp.rowVer : null, inspectorNm: field('inspectorNm').value,
            witnessNm: field('witnessNm').value, remark: field('remark').value};
    }

    /** @param {string} id @param {() => Promise<HB.ApiResponse | null>} call @param {string} doneKey */
    function action(id, call, doneKey) {
        var b = btn(id);
        if (!b) { return; }
        var button = b;
        button.addEventListener('click', function () {
            if (!detail) { return; }
            hb.busy(button, call).then(function (res) {
                if (!res) { return; }
                notify(hb.t(doneKey));
                return refresh();
            }, function (err) {
                notify(errorText(err), true);
                hb.showFieldErrors(head, err.errors);
            });
        });
    }

    action('startBtn', function () {
        return hb.api(API + '/insps', {method: 'POST', body: {shipId: detail ? detail.ship.shipId : null}});
    }, 'js.qms.preship.started');
    action('saveHeadBtn', function () {
        return hb.api(API + '/insps/' + (detail && detail.insp ? detail.insp.inspId : 0), {method: 'PUT', body: headBody()});
    }, 'js.common.saved');
    action('completeBtn', function () {
        var id = detail && detail.insp ? detail.insp.inspId : 0;
        return hb.dialog.confirm({title: hb.t('js.qms.preship.completeTitle'), message: hb.t('js.qms.preship.completeMsg'), ok: hb.t('js.qms.preship.complete')})
            .then(function (ok) { return ok ? hb.api(API + '/insps/' + id + '/complete', {method: 'POST', body: headBody()}) : null; });
    }, 'js.qms.preship.completed');
    action('reopenBtn', function () {
        var id = detail && detail.insp ? detail.insp.inspId : 0;
        var rowVer = detail && detail.insp ? detail.insp.rowVer : null;
        return hb.dialog.confirm({title: hb.t('js.qms.preship.reopenTitle'), message: hb.t('js.qms.preship.reopenMsg'), ok: hb.t('js.qms.preship.reopen')})
            .then(function (ok) { return ok ? hb.api(API + '/insps/' + id + '/reopen', {method: 'POST', body: {rowVer: rowVer}}) : null; });
    }, 'js.qms.preship.reopened');
    action('cancelBtn', function () {
        var id = detail && detail.insp ? detail.insp.inspId : 0;
        var rowVer = detail && detail.insp ? detail.insp.rowVer : null;
        return hb.dialog.prompt({title: hb.t('js.qms.preship.cancelTitle'), label: hb.t('js.qms.preship.reason'), required: true, maxLength: 500,
            ok: hb.t('js.qms.preship.cancel'), danger: true})
            .then(function (reason) {
                return reason === null ? null : hb.api(API + '/insps/' + id + '/cancel', {method: 'POST', body: {rowVer: rowVer, reason: reason}});
            });
    }, 'js.qms.preship.cancelled');

    hb.dirty.watch(head);
    search.addEventListener('submit', function (e) { e.preventDefault(); });
    hb.loadMessages().then(function () {
        hb.page.init();
        sp = hb.searchPanel(search, {
            onSearch: function () { return grid ? grid.reload(true) : null; }
        });
        return Promise.all([hb.loadSession(), hb.codeNames('WMS_SHIP_STATUS'), sp.ready]);
    }).then(function (r) {
        shipNames = r[1];
        // 출하는 앞으로 계획된 것이 많다 — 기본 기간 = 오늘-7 ~ 오늘+30(초기화도 이 값으로)
        var today = hb.dates.today();
        var period = {from: hb.dates.add(today, -7), to: hb.dates.add(today, 30)};
        sp.setDefault('from', period);
        sp.setValue('from', period.from);
        sp.setValue('to', period.to);
        judgeNames = {OK: hb.t('js.qms.preship.judgeOK'), NG: hb.t('js.qms.preship.judgeNG'), NA: hb.t('js.qms.preship.judgeNA')};
        statusNames = {OPEN: hb.t('js.qms.preship.statusOPEN'), DONE: hb.t('js.qms.preship.statusDONE'), CANCELLED: hb.t('js.qms.preship.statusCANCELLED')};
        grid = hb.grid('#shipGrid', {
            progCd: PROG, id: 'main', key: 'shipId',
            url: API + '/ships/grid', columnsUrl: API + '/ships/columns', exportUrl: API + '/ships/export',
            actions: ACTIONS,
            params: function () {
                var v = sp.values();
                return {from: v.from, to: v.to, keyword: v.keyword, inspStatus: v.inspStatus, onlyRequired: v.onlyRequired && v.onlyRequired.length ? 'true' : ''};
            },
            status: {inspStatus: {NONE: 'warning', OPEN: 'info', DONE: 'normal'}, requiredYn: {Y: 'info', N: 'neutral'}},
            rowClass: function (row) { return row.openPunchCnt > 0 ? 'hb-row-warn' : null; },
            onSelect: function (row) { load(/** @type {PreshipShipRow} */ (row).shipId); hb.page.openDetail(); },
            notify: notify
        });
        return grid.ready;
    }).then(null, function (err) { notify(err && err.message, true); });
})();
