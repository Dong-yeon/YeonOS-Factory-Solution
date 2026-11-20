// @ts-check
/*
 * 작업자 관리 (mst-worker) — P2 1단계 (docs/25 4.2·D3).
 * 조회 패널 → 작업자 hb.grid(UI 단계 3 ①, 페이지·정렬) → 상세 폼 + 배지(지정 여부만 보임) + 보유 자격(갱신 = 새 행, 취소) + 공정 자격 확인(게이트 미리보기)
 */
/**
 * @typedef {{workerId: number, workerCd: string, workerNm: string, plantCd?: string | null, plantNm?: string | null,
 *   deptNm?: string | null, useYn: string, badgeSet?: boolean, qualCnt?: number, rowVer: number, [field: string]: any}} WorkerRow
 * @typedef {{wqId: number, qualCd: string, qualNm: string, acquiredOn: string, expiresOn?: string | null,
 *   revokedOn?: string | null, state: string, certNo?: string | null, remark?: string | null, rowVer: number}} WorkerQual
 * @typedef {{qualCd: string, qualNm: string, gateMode: string, reason: string}} WorkerGateMissing
 * @typedef {{mode: 'OK' | 'WARN' | 'BLOCK', policyMode: string, missing: WorkerGateMissing[]}} WorkerGate
 * @typedef {HTMLInputElement | HTMLSelectElement} WorkerField
 */
(function () {
    'use strict';

    var API = '/api/master/workers';
    var ACTIONS = (document.body.getAttribute('data-actions') || '').split(',');
    var CAN_SAVE = ACTIONS.indexOf('SAVE') >= 0;
    var msg = /** @type {HTMLElement} */ (document.getElementById('msg'));
    var form = /** @type {HTMLFormElement} */ (document.getElementById('workerForm'));
    var search = /** @type {HTMLFormElement} */ (document.getElementById('search'));
    var PROG = document.body.getAttribute('data-prog') || '';
    var heldBody = /** @type {HTMLTableSectionElement} */ (document.querySelector('#held tbody'));
    var extra = /** @type {HTMLElement} */ (document.getElementById('workerExtra'));
    var badgeForm = /** @type {HTMLFormElement} */ (document.getElementById('badgeForm'));
    var pinForm = /** @type {HTMLFormElement} */ (document.getElementById('pinForm'));
    var heldForm = /** @type {HTMLFormElement | null} */ (document.getElementById('heldForm'));   // SAVE 권한 없으면 없음
    var gateForm = /** @type {HTMLFormElement} */ (document.getElementById('gateForm'));
    var gateResult = /** @type {HTMLElement} */ (document.getElementById('gateResult'));
    var formMode = /** @type {HTMLElement} */ (document.getElementById('formMode'));
    /** 선택된 작업자 @type {WorkerRow | null} */
    var current = null;
    /** 조회 조건 패널 @type {HB.SearchPanel} */
    var sp;
    /** @type {HB.Grid | undefined} */
    var grid;

    /** @param {string | null | undefined} text @param {boolean} [error] */
    function notify(text, error) {
        msg.textContent = text || '';
        msg.classList.toggle('hb-msg-error', !!error);
    }

    /** 상세 폼 칸 @param {string} n */
    function field(n) { return /** @type {WorkerField} */ (form.querySelector('[data-field="' + n + '"]')); }

    /** @param {Element} sel @param {string} value @param {string} text */
    function option(sel, value, text) {
        var o = hb.el('option', null, text);
        o.value = value;
        sel.appendChild(o);
    }

    /** 자격 상태·게이트 이유 — 키는 글자 그대로(I18nKeyGuardTest) @param {string} s */
    function stateName(s) {
        /** @type {Record<string, string>} */
        var names = {VALID: hb.t('js.worker.state.VALID'), EXPIRED: hb.t('js.worker.state.EXPIRED'),
            REVOKED: hb.t('js.worker.state.REVOKED'), NOT_YET: hb.t('js.worker.state.NOT_YET'), NONE: hb.t('js.worker.state.NONE')};
        return names[s] || s;
    }

    /** @param {WorkerRow | null} w */
    function show(w) {
        current = w;
        hb.showFieldErrors(form, []);
        ['workerCd', 'workerNm', 'deptNm', 'loginId', 'remark'].forEach(function (n) { field(n).value = w ? (w[n] || '') : ''; });
        var ps = /** @type {HTMLSelectElement} */ (field('plantCd'));
        ps.querySelectorAll('option[data-stale]').forEach(function (o) { o.remove(); });
        ps.value = w && w.plantCd ? w.plantCd : '';
        if (w && w.plantCd && ps.value !== w.plantCd) {
            option(ps, w.plantCd, w.plantCd + ' — ' + hb.t('js.worker.inactive'));     // 사용 중지된 공장 — 지우지 않고 보여 준다
            /** @type {HTMLOptionElement} */ (ps.lastElementChild).setAttribute('data-stale', '');
            ps.value = w.plantCd;
        }
        field('useYn').value = w ? w.useYn : 'Y';
        /** @type {HTMLInputElement} */ (field('workerCd')).readOnly = !!w || !CAN_SAVE;
        formMode.textContent = w ? hb.t('js.worker.editing', w.workerCd, w.workerNm) : hb.t('js.worker.creating');
        extra.hidden = !w;
        gateResult.textContent = '';
        if (w) {
            /** @type {HTMLElement} */ (document.getElementById('badgeState')).textContent = w.badgeSet ? hb.t('js.worker.badgeOn') : hb.t('js.worker.badgeOff');
            var b = /** @type {HTMLInputElement | null} */ (badgeForm.querySelector('[data-field="badge"]'));   // SAVE 권한 없으면 없음
            if (b) { b.value = ''; }
            /** @type {HTMLElement} */ (document.getElementById('pinState')).textContent = w.pinSet ? hb.t('js.worker.pinOn') : hb.t('js.worker.pinOff');
            var pinBox = /** @type {HTMLInputElement | null} */ (pinForm.querySelector('[data-field="pin"]'));
            if (pinBox) { pinBox.value = ''; }
            loadHeld();
        }
        hb.dirty.clear();
    }

    /** 목록 다시 읽기 — selectId 가 이 페이지에 있으면 다시 골라 onSelect 가 상세를 다시 읽는다 @param {number} [selectId] */
    function load(selectId) {
        return grid ? grid.reload(false, selectId) : Promise.resolve();
    }

    /** 행을 고르면 상세(보유 자격·배지 상태 포함)를 서버에서 다시 읽는다 @param {number} workerId */
    function openWorker(workerId) {
        return hb.latest('detail', hb.api(API + '/' + workerId)).then(function (r) {
            show(r.data);
            hb.page.openDetail();
        }, function (err) { notify(err.message, true); });
    }

    function loadHeld() {
        if (!current) { return; }
        var id = current.workerId;
        return hb.latest('held', hb.api(API + '/' + id + '/quals')).then(function (res) {
            /** @type {WorkerQual[]} */
            var list = res.data;
            heldBody.textContent = '';
            if (!list.length) {
                var tr = hb.el('tr');
                var td = hb.el('td', 'hb-muted', hb.t('js.worker.noQual'));
                td.colSpan = 5;
                tr.appendChild(td);
                heldBody.appendChild(tr);
            }
            list.forEach(function (h) {
                var tr = hb.el('tr', h.state === 'VALID' ? null : 'hb-tree-dim');
                [h.qualCd + ' · ' + h.qualNm, h.acquiredOn, h.expiresOn || hb.t('js.qual.noExpiry'),
                    mesUi.badge('qual', h.state, stateName(h.state) + (h.revokedOn ? ' (' + h.revokedOn + ')' : ''))]
                    .forEach(function (v) { var td = hb.el('td'); td.append(v); tr.appendChild(td); });
                var act = hb.el('td');
                if (CAN_SAVE && !h.revokedOn) {
                    var btn = hb.el('button', 'hb-btn-danger-outline hb-btn-small', hb.t('js.worker.revoke'));
                    btn.type = 'button';
                    btn.addEventListener('click', function () {
                        mesUi.ask('js.mes.dlg.qualRevoke', hb.t('js.worker.confirmRevoke', h.qualNm), 'js.mes.dlg.qualRevokeOk', true).then(function (ok) {
                        if (!ok) { return; }
                        var today = hb.dates ? hb.dates.todayIn(hb.session().timeZone) : null;
                        hb.busy(btn, function () {
                            return hb.api(API + '/' + id + '/quals/' + h.wqId, {method: 'PUT',
                                body: {expiresOn: h.expiresOn, revokedOn: today, certNo: h.certNo, remark: h.remark, rowVer: h.rowVer}});
                        }).then(function (res) {
                            if (!res) { return; }
                            notify(hb.t('js.common.saved'));
                            return load(id);
                        }, function (err) {
                            notify(err.errors && err.errors.length ? err.errors[0].message : err.message, true);
                        });
                        });
                    });
                    act.appendChild(btn);
                }
                tr.appendChild(act);
                heldBody.appendChild(tr);
            });
        }, function (err) { notify(err.message, true); });
    }

    function newWorker() {
        mesUi.leaveOk().then(function (ok) {
            if (!ok) { return; }
            show(null);
            hb.page.openDetail();
            field('workerCd').focus();
        });
    }

    /** 버튼(권한에 따라 없을 수 있음)에 클릭 연결 @param {string} id @param {(b: HTMLButtonElement) => void} fn */
    function bind(id, fn) {
        var b = /** @type {HTMLButtonElement | null} */ (document.getElementById(id));
        if (b) {
            var btn = b;
            btn.addEventListener('click', function () { fn(btn); });
        }
    }
    bind('newBtn', newWorker);
    bind('newBtnList', newWorker);

    bind('saveBtn', function (saveBtn) {
        var body = {workerCd: field('workerCd').value, workerNm: field('workerNm').value, plantCd: field('plantCd').value,
            deptNm: field('deptNm').value, loginId: field('loginId').value, useYn: field('useYn').value,
            remark: field('remark').value, rowVer: current ? current.rowVer : null};
        var editId = current ? current.workerId : null;   // 저장 중에 다른 행을 골라도 이 작업자로 마무리
        hb.busy(saveBtn, function () {
            return editId !== null ? hb.api(API + '/' + editId, {method: 'PUT', body: body})
                : hb.api(API, {method: 'POST', body: body});
        }).then(function (res) {
            if (!res) { return; }
            return load(editId !== null ? editId : res.data).then(function () { notify(hb.t('js.common.saved')); });
        }, function (err) {
            notify(err.message, true);
            hb.showFieldErrors(form, err.errors);
        });
    });

    /** @param {string} value @param {HTMLButtonElement} btn */
    function badge(value, btn) {
        if (!current) { return; }
        var w = current;   // 처리 중에 다른 행을 골라도 이 작업자로 마무리
        hb.showFieldErrors(badgeForm, []);
        hb.busy(btn, function () {
            return hb.api(API + '/' + w.workerId + '/badge', {method: 'PUT', body: {badge: value, rowVer: w.rowVer}});
        }).then(function (res) {
            if (!res) { return; }
            return load(w.workerId).then(function () { notify(hb.t('js.common.saved')); });
        }, function (err) {
            notify(err.message, true);
            hb.showFieldErrors(badgeForm, err.errors);
        });
    }
    var badgeBtnEl = /** @type {HTMLButtonElement | null} */ (document.getElementById('badgeBtn'));   // SAVE 권한 없으면 없음(입력 칸·지우기 버튼도)
    if (badgeBtnEl) {
        var badgeBtn = badgeBtnEl;
        var badgeInput = /** @type {HTMLInputElement} */ (badgeForm.querySelector('[data-field="badge"]'));
        badgeBtn.addEventListener('click', function () { badge(badgeInput.value, badgeBtn); });
        // 배지 스캐너는 끝에 Enter 를 보낸다 — 스캔 한 번으로 지정
        badgeInput.addEventListener('keydown', function (e) {
            if (e.key === 'Enter' && !e.isComposing) { e.preventDefault(); badge(badgeInput.value, badgeBtn); }
        });
        bind('badgeClearBtn', function (clearBtn) {
            mesUi.ask('js.mes.dlg.badgeClear', hb.t('js.worker.confirmBadgeClear'), 'js.mes.dlg.clearOk', true).then(function (ok) {
                if (ok) { badge('', clearBtn); }
            });
        });
    }

    // POP PIN — 값은 다시 보이지 않는다(지정 여부만)
    /** @param {string} value @param {HTMLButtonElement} btn */
    function pin(value, btn) {
        if (!current) { return; }
        var id = current.workerId, ver = current.rowVer;   // 처리 중에 다른 행을 골라도 이 작업자로 마무리
        hb.showFieldErrors(pinForm, []);
        hb.busy(btn, function () {
            return hb.api(API + '/' + id + '/pin', {method: 'PUT', body: {pin: value, rowVer: ver}});
        }).then(function (res) {
            if (!res) { return; }
            return load(id).then(function () { notify(hb.t('js.common.saved')); });
        }, function (err) {
            notify(err.message, true);
            hb.showFieldErrors(pinForm, err.errors);
        });
    }
    var pinInput = /** @type {HTMLInputElement | null} */ (pinForm ? pinForm.querySelector('[data-field="pin"]') : null);
    bind('pinBtn', function (pinBtn) { if (pinInput) { pin(pinInput.value, pinBtn); } });
    bind('pinClearBtn', function (pinClearBtn) {
        mesUi.ask('js.mes.dlg.pinClear', hb.t('js.worker.confirmPinClear'), 'js.mes.dlg.clearOk', true).then(function (ok) {
            if (ok) { pin('', pinClearBtn); }
        });
    });

    if (heldForm) {
        var hf = heldForm;
        bind('addHeldBtn', function (addHeldBtn) {
            if (!current) { return; }
            var id = current.workerId;   // 처리 중에 다른 행을 골라도 이 작업자로 마무리
            hb.showFieldErrors(hf, []);
            /** @param {string} n */
            var f = function (n) { return /** @type {WorkerField} */ (hf.querySelector('[data-field="' + n + '"]')).value; };
            var body = {qualCd: f('qualCd'), acquiredOn: f('acquiredOn'), expiresOn: f('expiresOn'), certNo: f('certNo')};
            hb.busy(addHeldBtn, function () {
                return hb.api(API + '/' + id + '/quals', {method: 'POST', body: body});
            }).then(function (res) {
                if (!res) { return; }
                hf.reset();
                return load(id).then(function () { notify(hb.t('js.common.saved')); });
            }, function (err) {
                notify(err.message, true);
                hb.showFieldErrors(hf, err.errors);
            });
        });
    }

    bind('gateBtn', function (gateBtn) {
        if (!current) { return; }
        var w = current;
        hb.showFieldErrors(gateForm, []);
        var procCd = /** @type {HTMLSelectElement} */ (gateForm.querySelector('[data-field="procCd"]')).value;
        var day = /** @type {HTMLInputElement} */ (gateForm.querySelector('[data-field="workDay"]')).value;
        hb.busy(gateBtn, function () {
            return hb.latest('gate', hb.api(API + '/' + w.workerId + '/gate?procCd=' + encodeURIComponent(procCd)
                + (day ? '&workDay=' + encodeURIComponent(day) : '')));
        }).then(function (res) {
            if (!res || current !== w) { return; }   // 기다리는 사이 다른 작업자를 골랐으면 그 작업자 자리에 쓰지 않는다
            /** @type {WorkerGate} */
            var g = res.data;
            gateResult.className = 'hb-wide hb-gate hb-gate-' + g.mode.toLowerCase();
            var text = {OK: hb.t('js.worker.gate.OK'), WARN: hb.t('js.worker.gate.WARN'), BLOCK: hb.t('js.worker.gate.BLOCK')}[g.mode];
            if (g.missing.length) {
                text += ' — ' + g.missing.map(function (m) {
                    return m.qualNm + ' (' + stateName(m.reason) + ')';
                }).join(', ');
            }
            if (g.missing.length && g.policyMode !== 'BLOCK') {
                // 공정은 차단을 요구해도 공장 정책 상한이 경고면 경고 — 왜 경고인지 함께
                text += ' · ' + (g.policyMode === 'OFF' ? hb.t('js.worker.gate.policyOff') : hb.t('js.worker.gate.policyWarn'));
            }
            gateResult.textContent = text;
        }, function (err) {
            if (current !== w) { return; }
            gateResult.textContent = '';
            notify(err.message, true);
            hb.showFieldErrors(gateForm, err.errors);
        });
    });

    if (!CAN_SAVE) {
        form.querySelectorAll('input, select').forEach(function (i) { /** @type {WorkerField} */ (i).disabled = true; });
    }

    hb.dirty.watch(form);
    hb.loadMessages().then(function () {
        hb.page.init();
        sp = hb.searchPanel(search, {onSearch: function () {
            if (!grid) { return null; }
            var g = grid;
            return mesUi.leaveOk().then(function (ok) { return ok ? g.reload(true) : null; });
        }});
        return Promise.all([hb.loadSession(), hb.api(API + '/plants'), hb.api(API + '/quals'), hb.api(API + '/procs')]);
    }).then(function (r) {
        var plantSearch = /** @type {HTMLSelectElement} */ (document.getElementById('plantSearch'));
        var plantSel = field('plantCd');
        option(plantSearch, '', hb.t('js.worker.allPlants'));
        option(plantSel, '', hb.t('js.worker.noPlant'));
        /** @type {{plantCd: string, plantNm: string}[]} */ (r[1].data).forEach(function (p) {
            option(plantSearch, p.plantCd, p.plantCd + ' · ' + p.plantNm);
            option(plantSel, p.plantCd, p.plantCd + ' · ' + p.plantNm);
        });
        var qualSel = /** @type {HTMLSelectElement | null} */ (document.getElementById('qualSel'));   // SAVE 권한 없으면 없음
        if (qualSel) {
            var qs = qualSel;
            /** @type {{qualCd: string, qualNm: string, validMonths?: number | null}[]} */ (r[2].data).forEach(function (q) {
                option(qs, q.qualCd, q.qualCd + ' · ' + q.qualNm + (q.validMonths ? ' (' + hb.t('js.worker.months', q.validMonths) + ')' : ''));
            });
        }
        var procSel = /** @type {HTMLSelectElement} */ (document.getElementById('procSel'));
        /** @type {{procCd: string, procNm: string}[]} */ (r[3].data).forEach(function (p) { option(procSel, p.procCd, p.procCd + ' · ' + p.procNm); });
        show(null);
        grid = hb.grid('#workerGrid', {
            progCd: PROG, id: 'main', key: 'workerId',
            url: API + '/grid', columnsUrl: API + '/columns', exportUrl: API + '/export',
            actions: ACTIONS, params: function () { return sp.values(); },
            format: {badgeSet: function (v) { return v ? hb.t('js.worker.badgeYes') : ''; }},
            beforeSelect: function () { return mesUi.leaveOk(); },
            status: {useYn: mesUi.TONES.use},                                              // 사용 여부 = 상태 배지
            rowClass: function (row) { return row.useYn === 'N' ? 'hb-tree-dim' : null; },   // 미사용 행 흐리게
            onSelect: function (row) { openWorker(row.workerId); },
            notify: notify
        });
        return grid.ready;
    }).then(null, function (err) { notify(err && err.message, true); });
})();
