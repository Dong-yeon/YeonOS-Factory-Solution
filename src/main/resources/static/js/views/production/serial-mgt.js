// @ts-check
/* 시리얼 구성 (prd-serial) — docs/77. 장비 시리얼 hb.grid(+ 엑셀) → 상세: 장착 중 부품([탈거]·[교체]) · 장착 등록 · BOM 대비 · 이력.
   저장은 모두 머리 rowVer 를 보내고(남이 먼저 저장하면 409 → 다시 읽기), 멱등 키를 붙인다. 수량은 서버 글자 그대로. */
/**
 * @typedef {{serialId: number, serialNo: string, woId: number, woNo: string, plantCd: string, itemCd: string, itemNm: string,
 *   planQty: string, unitLotId: number, unitLotNo: string, partCnt: number, remark?: string | null, rowVer: number,
 *   createdAt: string, createdBy: string}} PrdSerialRow
 * @typedef {{partId: number, evType: string, partItemCd: string, partItemNm: string, partLotNo?: string | null,
 *   partSerialNo?: string | null, qty: string, unitCd: string, opSeq?: number | null, procNm?: string | null, reason?: string | null,
 *   occurredAt: string, recordedBy: string, workerCd?: string | null, entryMethod: string, removed: boolean}} PrdSerialPart
 * @typedef {{serial: PrdSerialRow, active: PrdSerialPart[], history: PrdSerialPart[]}} PrdSerialDetail
 * @typedef {{status: string, itemCd: string, itemNm: string, altGrp?: string | null, opSeq?: number | null, reqQty: string,
 *   installedQty: string, unitCd: string, installedItems: string[]}} PrdSerialCmp
 * @typedef {{woId: number, woNo: string, status: string, itemCd: string, itemNm: string, planQty: string, serialCnt: number}} PrdSerialWo
 */
(function () {
    'use strict';

    var API = '/api/production/serials';
    var PROG = document.body.getAttribute('data-prog') || '';
    var ACTIONS = (document.body.getAttribute('data-actions') || '').split(',');
    var CAN_SAVE = ACTIONS.indexOf('SAVE') >= 0;
    var msg = /** @type {HTMLElement} */ (document.getElementById('msg'));
    var search = /** @type {HTMLFormElement} */ (document.getElementById('search'));
    var info = /** @type {HTMLElement} */ (document.getElementById('info'));
    var facts = /** @type {HTMLElement} */ (document.getElementById('facts'));
    var lotLink = /** @type {HTMLAnchorElement} */ (document.getElementById('lotLink'));
    var activeSection = /** @type {HTMLElement} */ (document.getElementById('activeSection'));
    var activeBody = /** @type {HTMLElement} */ (document.querySelector('#activeTable tbody'));
    var compareSection = /** @type {HTMLElement} */ (document.getElementById('compareSection'));
    var compareInfo = /** @type {HTMLElement} */ (document.getElementById('compareInfo'));
    var compareBody = /** @type {HTMLElement} */ (document.querySelector('#compareTable tbody'));
    var historySection = /** @type {HTMLElement} */ (document.getElementById('historySection'));
    var historyBody = /** @type {HTMLElement} */ (document.querySelector('#historyTable tbody'));
    var newBtn = /** @type {HTMLButtonElement | null} */ (document.getElementById('newBtn'));
    var createForm = /** @type {HTMLFormElement | null} */ (document.getElementById('createForm'));
    var woNoInput = /** @type {HTMLInputElement | null} */ (document.getElementById('woNoInput'));
    var woFindBtn = /** @type {HTMLButtonElement | null} */ (document.getElementById('woFindBtn'));
    var woSel = /** @type {HTMLSelectElement | null} */ (document.getElementById('woSel'));
    var countInput = /** @type {HTMLInputElement | null} */ (document.getElementById('countInput'));
    var createBtn = /** @type {HTMLButtonElement | null} */ (document.getElementById('createBtn'));
    var installForm = /** @type {HTMLFormElement | null} */ (document.getElementById('installForm'));
    var installTitle = /** @type {HTMLElement | null} */ (document.getElementById('installTitle'));
    var installBtn = /** @type {HTMLButtonElement | null} */ (document.getElementById('installBtn'));
    var installCancelBtn = /** @type {HTMLButtonElement | null} */ (document.getElementById('installCancelBtn'));
    var STATUS = {OK: 'js.prdSerial.cmp.OK', MISSING: 'js.prdSerial.cmp.MISSING', SHORT: 'js.prdSerial.cmp.SHORT',
        OVER: 'js.prdSerial.cmp.OVER', SUBST: 'js.prdSerial.cmp.SUBST', EXTRA: 'js.prdSerial.cmp.EXTRA'};
    /** @type {PrdSerialDetail | null} */
    var current = null;
    /** 교체 중인 장착 행(없으면 새 장착) @type {PrdSerialPart | null} */
    var replacing = null;
    /** @type {HB.SearchPanel | null} */
    var sp = null;
    /** @type {HB.Grid | null} */
    var grid = null;

    /** @param {string | null | undefined} text @param {boolean} [error] */
    function notify(text, error) {
        msg.textContent = text || '';
        msg.classList.toggle('hb-msg-error', !!error);
    }

    function requestKey() {
        return typeof crypto !== 'undefined' && crypto.randomUUID ? crypto.randomUUID()
            : String(Date.now()) + '-' + Math.random().toString(36).slice(2);
    }

    /** @param {unknown} v */
    function q(v) { return v == null || v === '' ? '' : hb.fmt.num(v, 6, 0); }

    /** @param {string} tag @param {string | null} cls @param {unknown} x */
    function cell(tag, cls, x) {
        var td = hb.el(tag, cls);
        if (x instanceof Node) { td.appendChild(x); } else { td.textContent = x == null ? '' : String(x); }
        return td;
    }

    // ------------------------------------------------------------------ 목록

    function params() {
        /** @type {Record<string, string>} */
        var out = {};
        if (!sp) { return out; }
        var v = sp.values();
        ['from', 'to', 'keyword', 'itemCd'].forEach(function (k) { if (v[k]) { out[k] = String(v[k]); } });
        return out;
    }

    /** 목록 다시 읽기(그 시리얼 그대로) + 상세 @param {number} [selectId] */
    function reload(selectId) {
        if (!grid) { return Promise.resolve(); }
        return grid.reload(false, selectId).then(function () {
            if (selectId) { return open(selectId); }
        });
    }

    // ------------------------------------------------------------------ 상세

    /** @param {number} id */
    function open(id) {
        return hb.latest('serial', hb.api(API + '/' + id)).then(function (res) {
            show(/** @type {PrdSerialDetail} */ (res.data));
            return hb.latest('compare', hb.api(API + '/' + id + '/compare'));
        }).then(function (res) {
            if (res) { drawCompare(res.data); }
        }, /** @param {HB.ApiError} err */ function (err) { if (!err.stale) { notify(err.message, true); } });
    }

    /** @param {string} dt @param {unknown} dd */
    function fact(dt, dd) {
        facts.appendChild(hb.el('dt', null, dt));
        facts.appendChild(cell('dd', null, dd == null || dd === '' ? '-' : dd));
    }

    /** @param {PrdSerialDetail} d */
    function show(d) {
        var changed = !current || current.serial.serialId !== d.serial.serialId;
        current = d;
        var s = d.serial;
        if (createForm) { createForm.hidden = true; }
        info.textContent = s.serialNo;
        facts.textContent = '';
        facts.hidden = false;
        fact(hb.t('js.prdSerial.f.wo'), s.woNo + ' (' + s.plantCd + ')');
        fact(hb.t('js.prdSerial.f.item'), s.itemCd + ' · ' + s.itemNm);
        fact(hb.t('js.prdSerial.f.unitLot'), s.unitLotNo);
        fact(hb.t('js.prdSerial.f.parts'), hb.t('js.prdSerial.partCnt', s.partCnt));
        fact(hb.t('js.prdSerial.f.created'), hb.fmt.datetime(s.createdAt) + ' · ' + s.createdBy);
        if (s.remark) { fact(hb.t('js.prdSerial.f.remark'), s.remark); }
        lotLink.hidden = false;
        lotLink.href = '/page/prd-lot-report?lotNo=' + encodeURIComponent(s.unitLotNo);
        drawActive(d.active);
        drawHistory(d.history);
        if (installForm) {
            installForm.hidden = false;
            // 다른 시리얼로 옮기면 앞 시리얼에 넣던 값을 비운다(잘못 장착 방지 — 2026-11-16 브라우저 확인)
            if (changed) {
                installForm.reset();
                hb.dirty.clear(installForm);
            }
            setReplacing(null);
        }
    }

    /** @param {PrdSerialPart[]} list */
    function drawActive(list) {
        activeSection.hidden = false;
        activeBody.textContent = '';
        if (!list.length) {
            var tr0 = hb.el('tr');
            var td0 = cell('td', 'hb-muted', hb.t('js.prdSerial.noParts'));
            td0.setAttribute('colspan', '7');
            tr0.appendChild(td0);
            activeBody.appendChild(tr0);
            return;
        }
        list.forEach(function (p) {
            var tr = hb.el('tr');
            tr.appendChild(cell('td', null, p.partItemCd + ' ' + p.partItemNm));
            tr.appendChild(cell('td', null, p.partLotNo || ''));
            tr.appendChild(cell('td', null, p.partSerialNo || ''));
            tr.appendChild(cell('td', 'hb-num prd-serial-nowrap', q(p.qty) + ' ' + p.unitCd));
            tr.appendChild(cell('td', null, p.opSeq == null ? '' : p.opSeq + (p.procNm ? ' ' + p.procNm : '')));
            tr.appendChild(cell('td', null, hb.fmt.datetime(p.occurredAt)));
            var act = hb.el('td', 'prd-serial-act');
            if (CAN_SAVE) {
                var rm = hb.el('button', 'hb-btn-danger-outline', hb.t('js.prdSerial.remove'));
                rm.type = 'button';
                rm.setAttribute('data-tip-key', 'js.tip.prd-serial.removeBtn');
                rm.addEventListener('click', function () { removePart(p, /** @type {HTMLButtonElement} */ (rm)); });
                var rp = hb.el('button', 'hb-btn-secondary', hb.t('js.prdSerial.replace'));
                rp.type = 'button';
                rp.setAttribute('data-tip-key', 'js.tip.prd-serial.replaceBtn');
                rp.addEventListener('click', function () { setReplacing(p); });
                act.appendChild(rm);
                act.appendChild(rp);
            }
            tr.appendChild(act);
            activeBody.appendChild(tr);
        });
    }

    /** @param {PrdSerialPart[]} list */
    function drawHistory(list) {
        historySection.hidden = false;
        historyBody.textContent = '';
        list.slice().reverse().forEach(function (p) {
            var tr = hb.el('tr', p.evType === 'INSTALL' && p.removed ? 'hb-tree-dim' : null);
            tr.appendChild(cell('td', null, p.evType === 'INSTALL' ? hb.t('js.prdSerial.ev.INSTALL') : hb.t('js.prdSerial.ev.REMOVE')));
            tr.appendChild(cell('td', null, p.partItemCd));
            tr.appendChild(cell('td', null, p.partLotNo || ''));
            tr.appendChild(cell('td', null, p.partSerialNo || ''));
            tr.appendChild(cell('td', 'hb-num', q(p.qty)));
            tr.appendChild(cell('td', null, hb.fmt.datetime(p.occurredAt)));
            tr.appendChild(cell('td', null, p.recordedBy + (p.workerCd ? ' / ' + p.workerCd : '') + (p.entryMethod === 'CORRECT' ? ' ' + hb.t('js.prdSerial.correct') : '')));
            tr.appendChild(cell('td', null, p.reason || ''));
            historyBody.appendChild(tr);
        });
    }

    /** @param {{serialNo: string, woNo: string, planQty: string, lines: PrdSerialCmp[]}} v */
    function drawCompare(v) {
        compareSection.hidden = false;
        compareBody.textContent = '';
        var bad = v.lines.filter(function (l) { return l.status !== 'OK'; }).length;
        compareInfo.textContent = v.lines.length ? hb.t('js.prdSerial.cmpInfo', v.lines.length, bad) : hb.t('js.prdSerial.noBom');
        v.lines.forEach(function (l) {
            var tr = hb.el('tr', l.status === 'MISSING' || l.status === 'SHORT' ? 'hb-row-warn' : null);
            /** @type {HB.StatusTone} */
            var tone = l.status === 'OK' ? 'success' : l.status === 'MISSING' || l.status === 'SHORT' ? 'warning'
                : l.status === 'EXTRA' || l.status === 'OVER' ? 'info' : 'neutral';
            var st = hb.status(tone, hb.t(STATUS[/** @type {keyof typeof STATUS} */ (l.status)] || 'js.prdSerial.cmp.OK'));
            tr.appendChild(cell('td', null, st));
            tr.appendChild(cell('td', null, l.itemCd + ' ' + l.itemNm + (l.altGrp ? ' [' + hb.t('js.prdSerial.altGrp', l.altGrp) + ']' : '')));
            tr.appendChild(cell('td', null, l.opSeq == null ? '' : String(l.opSeq)));
            tr.appendChild(cell('td', 'hb-num', q(l.reqQty) + ' ' + l.unitCd));
            tr.appendChild(cell('td', 'hb-num', q(l.installedQty)));
            tr.appendChild(cell('td', null, l.installedItems.join(', ')));
            compareBody.appendChild(tr);
        });
    }

    // ------------------------------------------------------------------ 저장

    /** 저장 실패 — 409 = 남이 먼저 저장(다시 읽기), 칸 오류는 폼에 @param {HB.ApiError} err @param {ParentNode | null} form */
    function saveFailed(err, form) {
        if (err.status === 409 && err.code === 'CONFLICT' && current) {
            notify(hb.t('js.prdSerial.conflict'), true);
            reload(current.serial.serialId);   // 목록의 장착 수도 함께
            return;
        }
        if (form && err.errors && err.errors.length) { hb.showFieldErrors(form, err.errors); }
        notify(err.message, true);
    }

    /** @param {HTMLFormElement} f */
    function formValues(f) {
        /** @type {Record<string, string | number | null>} */
        var out = {};
        f.querySelectorAll('[data-field]').forEach(function (el) {
            var i = /** @type {HTMLInputElement} */ (el);
            var v = i.value.trim();
            out[i.getAttribute('data-field') || ''] = v === '' ? null : v;
        });
        return out;
    }

    /** 교체 모드 — 그 행의 품목을 채우고 제목·취소 버튼을 바꾼다 @param {PrdSerialPart | null} p */
    function setReplacing(p) {
        replacing = p;
        if (!installForm || !installTitle || !installCancelBtn || !installBtn) { return; }
        hb.showFieldErrors(installForm, []);
        installTitle.textContent = p ? hb.t('js.prdSerial.replaceTitle', p.partItemCd + (p.partSerialNo ? ' / ' + p.partSerialNo : ''))
            : hb.t('js.prdSerial.installTitle');
        installBtn.textContent = p ? hb.t('js.prdSerial.replaceSave') : hb.t('js.prdSerial.installSave');
        installCancelBtn.hidden = !p;
        if (p) {
            var item = /** @type {HTMLInputElement} */ (installForm.querySelector('[data-field="itemCd"]'));
            item.value = p.partItemCd;
            var op = /** @type {HTMLInputElement} */ (installForm.querySelector('[data-field="opSeq"]'));
            op.value = p.opSeq == null ? '' : String(p.opSeq);   // 교체품도 같은 공정에(빠지면 새 장착에 공정이 비었다)
            var lot = /** @type {HTMLInputElement} */ (installForm.querySelector('[data-field="lotNo"]'));
            lot.focus();
        }
    }

    /** @param {PrdSerialPart} p @param {HTMLButtonElement} btn */
    function removePart(p, btn) {
        if (!current) { return; }
        var s = current.serial;
        hb.dialog.prompt({title: hb.t('js.prdSerial.removeTitle', p.partItemCd), label: hb.t('js.prdSerial.reason'),
            ok: hb.t('js.prdSerial.remove'), required: true, maxLength: 500}).then(function (reason) {
            if (reason === null) { return; }
            return hb.busy(btn, function () {
                return hb.api(API + '/' + s.serialId + '/parts/' + p.partId + '/remove', {method: 'POST',
                    body: {rowVer: s.rowVer, reason: reason}, headers: {'X-Idempotency-Key': requestKey()}});
            }).then(function (r) {
                if (r === null) { return; }
                notify(hb.t('js.prdSerial.removed'));
                return reload(s.serialId);
            }, /** @param {HB.ApiError} err */ function (err) { saveFailed(err, null); });
        });
    }

    if (installForm && installBtn && installCancelBtn) {
        var iForm = installForm, iBtn = installBtn;
        iBtn.addEventListener('click', function () {
            if (!current) { return; }
            var s = current.serial;
            var body = formValues(iForm);
            if (body.opSeq != null) { body.opSeq = Number(body.opSeq); }
            var rep = replacing;
            var ask = rep ? hb.dialog.prompt({title: hb.t('js.prdSerial.replaceTitle', rep.partItemCd), label: hb.t('js.prdSerial.reason'),
                ok: hb.t('js.prdSerial.replaceSave'), required: true, maxLength: 500}) : Promise.resolve('');
            ask.then(function (reason) {
                if (reason === null) { return; }
                hb.showFieldErrors(iForm, []);
                return hb.busy(iBtn, function () {
                    var url = rep ? API + '/' + s.serialId + '/parts/' + rep.partId + '/replace' : API + '/' + s.serialId + '/parts';
                    var payload = rep ? {rowVer: s.rowVer, reason: reason, install: body} : Object.assign({rowVer: s.rowVer}, body);
                    return hb.api(url, {method: 'POST', body: payload, headers: {'X-Idempotency-Key': requestKey()}});
                }).then(function (r) {
                    if (r === null) { return; }
                    notify(hb.t(rep ? 'js.prdSerial.replaced' : 'js.prdSerial.installed'));
                    iForm.reset();
                    hb.dirty.clear(iForm);
                    return reload(s.serialId);
                }, /** @param {HB.ApiError} err */ function (err) { saveFailed(err, iForm); });
            });
        });
        installCancelBtn.addEventListener('click', function () { setReplacing(null); });
        hb.dirty.watch(iForm);
    }

    // ------------------------------------------------------------------ 시리얼 만들기

    function findWos() {
        if (!woSel || !woNoInput) { return Promise.resolve(); }
        var sel = woSel;
        var no = woNoInput.value.trim();
        return hb.latest('wos', hb.api(API + '/wos' + (no ? '?woNo=' + encodeURIComponent(no) : ''))).then(function (res) {
            /** @type {PrdSerialWo[]} */
            var list = res.data;
            sel.textContent = '';
            if (!list.length) {
                var none = hb.el('option', null, hb.t('js.prdSerial.noWo'));
                none.value = '';
                sel.appendChild(none);
                return;
            }
            list.forEach(function (w) {
                var o = hb.el('option', null, hb.t('js.prdSerial.woOption', w.woNo, w.itemCd, q(w.planQty), w.serialCnt));
                o.value = String(w.woId);
                sel.appendChild(o);
            });
        }, /** @param {HB.ApiError} err */ function (err) { if (!err.stale) { notify(err.message, true); } });
    }

    if (newBtn && createForm && woFindBtn && createBtn && woSel && countInput) {
        var cForm = createForm, cBtn = createBtn, cSel = woSel, cCount = countInput, fBtn = woFindBtn;
        newBtn.addEventListener('click', function () {
            current = null;
            info.textContent = hb.t('js.prdSerial.newInfo');
            facts.hidden = true;
            lotLink.hidden = true;
            [activeSection, compareSection, historySection].forEach(function (x) { x.hidden = true; });
            if (installForm) { installForm.hidden = true; }
            cForm.hidden = false;
            hb.showFieldErrors(cForm, []);
            hb.page.openDetail();
            findWos();
        });
        fBtn.addEventListener('click', function () { hb.busy(fBtn, findWos); });
        cBtn.addEventListener('click', function () {
            var woId = cSel.value ? Number(cSel.value) : null;
            var body = formValues(cForm);
            hb.showFieldErrors(cForm, []);
            hb.busy(cBtn, function () {
                return hb.api(API, {method: 'POST', body: {woId: woId, count: cCount.value.trim() === '' ? null : Number(cCount.value),
                    remark: body.remark}, headers: {'X-Idempotency-Key': requestKey()}});
            }).then(function (r) {
                if (r === null) { return; }
                /** @type {number[]} */
                var ids = r.data;
                notify(hb.t('js.prdSerial.created', ids.length));
                hb.dirty.clear(cForm);
                cForm.hidden = true;
                return reload(ids[0]);
            }, /** @param {HB.ApiError} err */ function (err) { saveFailed(err, cForm); });
        });
        hb.dirty.watch(cForm);
    }

    // ------------------------------------------------------------------ 시작

    search.addEventListener('submit', function (e) { e.preventDefault(); });
    hb.loadMessages().then(function () {
        hb.page.init();
        return hb.loadSession();
    }).then(function () {
        sp = hb.searchPanel(search, {onSearch: function () { return grid ? grid.reload(true) : null; }});
        return sp.ready;
    }).then(function () {
        grid = hb.grid('#serialGrid', {
            progCd: PROG, id: 'main', key: 'serialId',
            url: API + '/grid', columnsUrl: API + '/columns', exportUrl: API + '/export',
            actions: ACTIONS, params: params,
            onSelect: function (row) { open(/** @type {number} */ (row.serialId)); hb.page.openDetail(); },
            notify: notify
        });
        return grid.ready;
    }).then(function () {
        // 역추적의 [시리얼 구성 열기](?q.keyword=번호&open=id) — 그 시리얼 상세를 바로 연다
        var openId = Number(new URLSearchParams(location.search).get('open'));
        if (openId > 0 && grid) {
            grid.selectById(openId);
            hb.page.openDetail();
            return open(openId);
        }
    }).then(null, function (err) { notify(err && err.message, true); });
})();
