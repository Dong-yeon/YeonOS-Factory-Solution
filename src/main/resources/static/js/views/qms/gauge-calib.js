// @ts-check
/*
 * 계측기 교정 (qms-gauge-calib) — P3 QMS Q2a (docs/29).
 *   hb.grid(게이지 + 상태 배지) + 상세: 계측기 정보(클래스·주기) / 교정 기록 추가 / 이력(무효) / 성적서 첨부(교정 기록별).
 * 상태 판정은 서버(GaugeStatus). 기록은 고치지 않고 무효 후 다시 넣는다. 서버 데이터는 textContent·value 로만 넣는다.
 */
/**
 * @typedef {{assetId: number, assetCd: string, assetNm: string, serialNo?: string | null, gaugeClass?: string | null,
 *   calibCycleDays?: number | null, lastCalibDate?: string | null, useYn: string, rowVer: number, lastResult?: string | null,
 *   lastLimitNote?: string | null, nextDueDate?: string | null, status: string, daysLeft?: number | null}} QmsGauge
 * @typedef {{calibId: number, calibDate: string, result: string, limitNote?: string | null, agency: string,
 *   custCd?: string | null, custNm?: string | null, certNo?: string | null, cycleDays: number, nextDueDate: string,
 *   remark?: string | null, voidYn: string, voidReason?: string | null, fileCnt: number}} QmsCalib
 * @typedef {{signerNm: string, meaningCd: string, authMethod: string, signedAt: string}} QmsCalibSignView
 * @typedef {{calibId: number, sign: QmsCalibSignView}} QmsCalibSign
 * @typedef {{gauge: QmsGauge, history: QmsCalib[], dueSoonDays: number, esignRequired: boolean, signs: QmsCalibSign[]}} QmsGaugeDetail
 */
(function () {
    'use strict';

    /** QMS 화면 공용 — 상태 배지·대화상자(qms-ui.js) */
    var qmsUi = /** @type {QmsUi} */ (/** @type {any} */ (window).qmsUi);

    var PROG = document.body.getAttribute('data-prog') || '';
    var ACTIONS = (document.body.getAttribute('data-actions') || '').split(',');
    var CAN_SAVE = ACTIONS.indexOf('SAVE') >= 0;
    var CAN_DELETE = ACTIONS.indexOf('DELETE') >= 0;
    var API = '/api/qms/gauges';
    var msg = /** @type {HTMLElement} */ (document.getElementById('msg'));
    var search = /** @type {HTMLFormElement} */ (document.getElementById('search'));
    var profile = /** @type {HTMLFormElement} */ (document.getElementById('profileForm'));
    var calibForm = /** @type {HTMLFormElement | null} */ (document.getElementById('calibForm'));   // SAVE 권한 없으면 없음
    var formMode = /** @type {HTMLElement} */ (document.getElementById('formMode'));
    var statusLine = /** @type {HTMLElement} */ (document.getElementById('statusLine'));
    var calibSection = /** @type {HTMLElement} */ (document.getElementById('calibSection'));
    var fileBox = /** @type {HTMLElement} */ (document.getElementById('fileBox'));
    /** 선택된 계측기 @type {QmsGauge | null} */
    var current = null;
    /** 첨부를 보고 있는 교정 기록 @type {QmsCalib | null} */
    var fileCalib = null;
    /** @type {HB.Grid | undefined} */
    var grid;
    /** @type {HB.SearchPanel} */
    var sp;
    /** @type {HB.SelectControl} */
    var classSel;
    /** @type {HB.NumberControl} */
    var cycle;
    /** @type {HB.SelectControl | null} */
    var resultSel = null;
    /** @type {HB.SelectControl | null} */
    var agencySel = null;
    /** @type {HB.NumberControl | null} */
    var calibCycle = null;
    /** @type {Record<string, string>} */
    var statusNames = {};
    /** @type {Record<string, string>} */
    var resultNames = {};
    /** @type {Record<string, string>} */
    var agencyNames = {};
    /** 영향 LOT 표의 검사 결과·상태 이름 @type {Record<string, string>} */
    var inspNames = {};

    /** @param {string | null | undefined} text @param {boolean} [error] */
    function notify(text, error) {
        msg.textContent = text || '';
        msg.classList.toggle('hb-msg-error', !!error);
    }

    /** @param {HTMLFormElement} f @param {string} n */
    function fieldOf(f, n) {
        return /** @type {HTMLInputElement | HTMLSelectElement} */ (f.querySelector('[data-field="' + n + '"]'));
    }

    /** 상태 한 줄: 배지 이름 · 다음 교정일 · 남은 날 · 제한 내용 @param {QmsGauge} g */
    function statusText(g) {
        var parts = [statusNames[g.status] || g.status];
        if (g.nextDueDate) {
            parts.push(hb.t('js.qms.gauge.nextDue', hb.fmt.date(g.nextDueDate)));
            if (g.daysLeft !== null && g.daysLeft !== undefined) {
                parts.push(g.daysLeft >= 0 ? hb.t('js.qms.gauge.daysLeft', g.daysLeft) : hb.t('js.qms.gauge.daysOver', -g.daysLeft));
            }
        }
        if (g.lastResult === 'LIMITED' && g.lastLimitNote) { parts.push(hb.t('js.qms.gauge.limited', g.lastLimitNote)); }
        return parts.join(' · ');
    }

    /** 사용 중이 아닌 회사 코드도 지금 값이면 "(사용 중지)" 선택지로 남긴다 — 저장 때 조용히 지워지지 않게 @param {string | null | undefined} v */
    function setClass(v) {
        var s = /** @type {HTMLSelectElement} */ (fieldOf(profile, 'gaugeClass'));
        classSel.setValue(v || '');
        if (v && s.value !== v) {
            var o = hb.el('option', null, v + ' (' + hb.t('js.qms.inactive') + ')');
            /** @type {HTMLOptionElement} */ (o).value = v;
            s.appendChild(o);
            s.value = v;
        }
    }

    /** 교정 등록에 서명 필수(정책, docs/29_P3 12.10) */
    var esignRequired = false;
    /** 교정 행별 서명 @type {Record<number, QmsCalibSignView[]>} */
    var signsByCalib = {};

    /** @param {QmsGaugeDetail} d */
    function keepSigns(d) {
        esignRequired = d.esignRequired;
        signsByCalib = {};
        d.signs.forEach(function (x) { (signsByCalib[x.calibId] = signsByCalib[x.calibId] || []).push(x.sign); });
    }

    /** @param {number} calibId */
    function signText(calibId) {
        /** @type {Record<string, string>} */
        var meanings = {AUTHOR: hb.t('js.esign.meaning.AUTHOR'), REVIEW: hb.t('js.esign.meaning.REVIEW'), APPROVE: hb.t('js.esign.meaning.APPROVE')};
        /** @type {Record<string, string>} */
        var methods = {PWD: hb.t('js.qms.esign.method.PWD'), PWD_TOTP: hb.t('js.qms.esign.method.PWD_TOTP')};
        return (signsByCalib[calibId] || []).map(function (sg) {
            return ' · ' + hb.t('js.qms.esign.row', sg.signerNm, meanings[sg.meaningCd] || sg.meaningCd, methods[sg.authMethod] || sg.authMethod,
                hb.fmt.datetime(sg.signedAt));
        }).join('');
    }

    /** @param {QmsGaugeDetail | null} d */
    function show(d) {
        var g = d ? d.gauge : null;
        current = g;
        hb.showFieldErrors(profile, []);
        profile.hidden = !g;
        calibSection.hidden = !g;
        statusLine.hidden = !g;
        fileBox.hidden = true;
        fileCalib = null;
        if (!g) {
            formMode.textContent = hb.t('js.qms.gauge.select');
            hb.dirty.clear(profile);
            return;
        }
        keepSigns(/** @type {QmsGaugeDetail} */ (d));
        formMode.textContent = hb.t('js.qms.gauge.editing', g.assetCd);
        fieldOf(profile, 'assetCd').value = g.assetCd;
        fieldOf(profile, 'assetNm').value = g.assetNm;
        fieldOf(profile, 'serialNo').value = g.serialNo || '';
        fieldOf(profile, 'lastCalibDate').value = g.lastCalibDate ? hb.fmt.date(g.lastCalibDate) : '';
        setClass(g.gaugeClass);
        cycle.setValue(g.calibCycleDays);
        statusLine.textContent = statusText(g);
        statusLine.setAttribute('data-status', g.status);
        hb.dirty.clear(profile);
        resetCalibForm(g);
        renderHistory(d ? d.history : []);
        /** @type {HTMLElement} */ (document.getElementById('impactBox')).hidden = true;
    }

    /** @param {QmsGauge} g */
    function resetCalibForm(g) {
        if (!calibForm || !resultSel || !agencySel || !calibCycle) { return; }
        var f = calibForm;
        hb.showFieldErrors(f, []);
        ['limitNote', 'custCd', 'certNo', 'remark'].forEach(function (n) { fieldOf(f, n).value = ''; });
        fieldOf(f, 'calibDate').value = hb.dates.today();
        resultSel.setValue('OK');
        agencySel.setValue('INTERNAL');
        calibCycle.setValue(null);
        /** @type {HTMLInputElement} */ (fieldOf(f, 'cycleDays')).placeholder = g.calibCycleDays ? String(g.calibCycleDays) : '';
        var inactive = g.useYn !== 'Y';
        /** @type {NodeListOf<HTMLInputElement | HTMLSelectElement>} */ (f.querySelectorAll('input, select'))
            .forEach(function (i) { i.disabled = inactive; });
        var add = document.getElementById('addCalibBtn');
        if (add) { /** @type {HTMLButtonElement} */ (add).disabled = inactive; }
        calibLayout();
        hb.dirty.clear(f);
    }

    /** 제한 사용이면 제한 내용, 외부 기관이면 거래처 칸 */
    function calibLayout() {
        if (!resultSel || !agencySel) { return; }
        /** @type {HTMLElement} */ (document.getElementById('limitBox')).hidden = resultSel.value() !== 'LIMITED';
        /** @type {HTMLElement} */ (document.getElementById('custBox')).hidden = agencySel.value() !== 'EXTERNAL';
    }

    /** @param {QmsCalib[]} list */
    function renderHistory(list) {
        var tbody = /** @type {HTMLElement} */ (document.querySelector('#historyTable tbody'));
        tbody.textContent = '';
        list.forEach(function (c) {
            var tr = hb.el('tr', c.voidYn === 'Y' ? 'hb-tree-dim hb-qms-click' : 'hb-qms-click');
            tr.appendChild(hb.el('td', null, hb.fmt.date(c.calibDate)));
            tr.appendChild(qmsUi.cell('calib', c.result, resultNames[c.result], c.limitNote ? '— ' + c.limitNote : null));
            tr.appendChild(hb.el('td', null, (agencyNames[c.agency] || c.agency) + (c.custCd ? ' · ' + c.custCd + ' ' + (c.custNm || '') : '')));
            tr.appendChild(hb.el('td', null, hb.fmt.date(c.nextDueDate) + ' (' + hb.t('js.qms.gauge.cycle', c.cycleDays) + ')'));
            tr.appendChild(hb.el('td', null, (c.certNo || '') + (c.fileCnt ? ' · ' + hb.t('js.qms.gauge.fileCnt', c.fileCnt) : '')));
            tr.appendChild(hb.el('td', null, (c.voidYn === 'Y' ? hb.t('js.qms.gauge.voided', c.voidReason || '') : hb.t('js.qms.gauge.valid'))
                + signText(c.calibId)));
            var td = hb.el('td');
            if (c.voidYn !== 'Y' && c.result === 'NG') {
                var ib = /** @type {HTMLButtonElement} */ (hb.el('button', 'hb-btn-secondary hb-btn-small', hb.t('js.qms.gauge.impact')));
                ib.type = 'button';
                ib.addEventListener('click', function (e) {
                    e.stopPropagation();
                    showImpact(c);
                });
                td.appendChild(ib);
            }
            if (c.voidYn !== 'Y' && CAN_DELETE) {
                var b = /** @type {HTMLButtonElement} */ (hb.el('button', 'hb-btn-secondary hb-btn-small', hb.t('js.qms.gauge.void')));
                b.type = 'button';
                b.addEventListener('click', function (e) {
                    e.stopPropagation();
                    voidCalib(b, c);
                });
                td.appendChild(b);
            }
            tr.appendChild(td);
            tr.addEventListener('click', function () { showFiles(c); });
            tbody.appendChild(tr);
        });
        if (!list.length) {
            var tr = hb.el('tr');
            var td = /** @type {HTMLTableCellElement} */ (hb.el('td', 'hb-muted', hb.t('js.qms.gauge.noHistory')));
            td.colSpan = 7;
            tr.appendChild(td);
            tbody.appendChild(tr);
        }
    }

    /** 교정 NG 영향 LOT (2부 Q9) — 직전 유효 교정일부터 NG 교정일까지 이 게이지로 잰 LOT·검사 @param {QmsCalib} c */
    function showImpact(c) {
        if (!current) { return; }
        var gauge = current;
        var box = /** @type {HTMLElement} */ (document.getElementById('impactBox'));
        hb.latest('impact', hb.api(API + '/' + gauge.assetId + '/calibs/' + c.calibId + '/impact')).then(function (res) {
            /** @type {{fromDate?: string | null, ngDate: string, lotCnt: number, truncated: boolean, rows: {lotNo: string, itemCd: string, itemNm: string,
             *   inspNo: string, inspStatus: string, inspResult?: string | null, measCnt: number, lastAt: string, lotDate: string}[]}} */
            var r = res.data;
            box.hidden = false;
            /** @type {HTMLElement} */ (document.getElementById('impactLine')).textContent = hb.t('js.qms.gauge.impactLine',
                r.fromDate ? hb.fmt.date(r.fromDate) : hb.t('js.qms.gauge.impactStart'), hb.fmt.date(r.ngDate), r.lotCnt)
                + (r.truncated ? ' ' + hb.t('js.qms.gauge.impactTruncated') : '');
            // 봉쇄 후보로 넘기기(13.4): 품목마다 봉쇄 화면을 품목·LOT 발생 기간·영향 LOT 을 고른 채 연다(봉쇄는 품목 하나씩)
            var links = /** @type {HTMLElement} */ (document.getElementById('impactContain'));
            links.textContent = '';
            /** @type {Record<string, {lots: string[], from: string, to: string}>} */
            var byItem = {};
            r.rows.forEach(function (x) {
                var g = byItem[x.itemCd] || (byItem[x.itemCd] = {lots: [], from: x.lotDate, to: x.lotDate});
                if (g.lots.indexOf(x.lotNo) < 0) { g.lots.push(x.lotNo); }
                if (x.lotDate < g.from) { g.from = x.lotDate; }
                if (x.lotDate > g.to) { g.to = x.lotDate; }
            });
            var why = hb.t('js.qms.gauge.impactReason', gauge.assetCd, hb.fmt.date(r.ngDate));
            Object.keys(byItem).sort().forEach(function (cd) {
                var g = byItem[cd];
                var a = /** @type {HTMLAnchorElement} */ (hb.el('a', 'hb-btn-secondary hb-link-btn', hb.t('js.qms.gauge.impactContain', cd, g.lots.length)));
                a.href = '/page/qms-contain?itemCd=' + encodeURIComponent(cd) + '&from=' + g.from + '&to=' + g.to
                    + '&lots=' + encodeURIComponent(g.lots.join(',')) + '&reason=' + encodeURIComponent(why);
                links.appendChild(a);
            });
            links.hidden = !r.rows.length;
            var tbody = /** @type {HTMLElement} */ (document.querySelector('#impactTable tbody'));
            tbody.textContent = '';
            r.rows.forEach(function (x) {
                var tr = hb.el('tr');
                tr.appendChild(hb.el('td', 'hb-wrap', x.lotNo));
                tr.appendChild(hb.el('td', 'hb-wrap', x.itemCd + ' ' + x.itemNm));
                tr.appendChild(hb.el('td', null, x.inspNo));
                tr.appendChild(hb.el('td', null, inspNames[x.inspResult || x.inspStatus] || x.inspResult || x.inspStatus));
                tr.appendChild(hb.el('td', 'hb-num', String(x.measCnt)));
                tr.appendChild(hb.el('td', null, hb.fmt.datetime(x.lastAt)));
                tbody.appendChild(tr);
            });
            if (!r.rows.length) {
                var tr0 = hb.el('tr');
                var td0 = /** @type {HTMLTableCellElement} */ (hb.el('td', 'hb-muted', hb.t('js.qms.gauge.impactNone')));
                td0.colSpan = 6;
                tr0.appendChild(td0);
                tbody.appendChild(tr0);
            }
            box.scrollIntoView({block: 'nearest'});
        }, function (err) { if (err.code !== 'STALE') { notify(err.message, true); } });
    }

    /** @param {HTMLButtonElement} btn @param {QmsCalib} c */
    function voidCalib(btn, c) {
        if (!current) { return; }
        var id = current.assetId;   // 처리 중에 다른 행을 골라도 이 계측기로 마무리
        qmsUi.reason('voidRec', hb.t('js.qms.gauge.voidPrompt', hb.fmt.date(c.calibDate))).then(function (reason) {
            if (reason === null) { return; }
            hb.busy(btn, function () {
                return hb.api(API + '/calibs/' + c.calibId + '/void', {method: 'POST', body: {reason: reason}});
            }).then(function (res) {
                if (!res) { return; }
                return after(id).then(function () { notify(hb.t('js.qms.gauge.voidedMsg')); });
            }, function (err) {
                notify(err.message + (err.errors && err.errors.length
                    ? ' ' + err.errors.map(/** @param {HB.FieldError} e */ function (e) { return e.message; }).join(' ') : ''), true);
            });
        });
    }

    // ---------- 첨부(교정 기록별 성적서) ----------
    /** @param {QmsCalib} c */
    function showFiles(c) {
        fileCalib = c;
        fileBox.hidden = false;
        /** @type {HTMLElement} */ (document.getElementById('fileTitle')).textContent = hb.t('js.qms.gauge.files', hb.fmt.date(c.calibDate));
        loadFiles();
    }

    function loadFiles() {
        var ul = /** @type {HTMLElement} */ (document.getElementById('files'));
        ul.textContent = '';
        if (!fileCalib) { return; }
        hb.latest('files', hb.api('/api/platform/files?targetType=' + PROG + '&targetId=' + fileCalib.calibId)).then(function (res) {
            (res.list || []).forEach(/** @param {{fileId: number, name: string, size: number}} f */ function (f) {
                var li = hb.el('li');
                var a = /** @type {HTMLAnchorElement} */ (hb.el('a', null, f.name));
                a.href = '/api/platform/files/' + f.fileId;
                li.appendChild(a);
                li.appendChild(hb.el('span', 'hb-muted', ' ' + hb.fmt.num(Math.ceil(f.size / 1024)) + ' KB'));
                if (CAN_SAVE) {
                    var del = /** @type {HTMLButtonElement} */ (hb.el('button', 'hb-btn-secondary hb-btn-small', hb.t('js.common.remove')));
                    del.type = 'button';
                    del.addEventListener('click', function () {
                        qmsUi.ask('fileDelete', hb.t('js.qms.gauge.confirmFileDelete', f.name)).then(function (yes) {
                            if (!yes) { return; }
                            hb.api('/api/platform/files/' + f.fileId + '/delete', {method: 'POST'})
                                .then(loadFiles, function (err) { notify(err.message, true); });
                        });
                    });
                    li.appendChild(del);
                }
                ul.appendChild(li);
            });
            if (!(res.list || []).length) { ul.appendChild(hb.el('li', 'hb-muted', hb.t('js.qms.gauge.noFiles'))); }
        }, function (err) { if (err.code !== 'STALE') { notify(err.message, true); } });
    }

    var fileInput = /** @type {HTMLInputElement | null} */ (document.getElementById('fileInput'));
    if (fileInput) {
        var upInput = fileInput;
        upInput.addEventListener('change', function () {
            var file = upInput.files && upInput.files[0];
            if (!file || !fileCalib || !current) { return; }
            var fd = new FormData();
            fd.append('targetType', PROG);
            fd.append('targetId', String(fileCalib.calibId));
            fd.append('file', file);
            var id = current.assetId;
            hb.upload('/api/platform/files', fd).then(function () {
                upInput.value = '';
                notify(hb.t('js.common.saved'));
                loadFiles();
                return reloadHistory(id);
            }, function (err) {
                upInput.value = '';
                notify(err.message + (err.details && err.details.length ? ' ' + err.details.join(' ') : ''), true);
            });
        });
    }

    /** 첨부 수만 다시 — 상세 전체를 다시 읽으면 입력 중인 교정 폼이 지워진다 @param {number} id */
    function reloadHistory(id) {
        return hb.latest('history', hb.api(API + '/' + id)).then(function (r) {
            if (current && current.assetId === id) {
                keepSigns(/** @type {QmsGaugeDetail} */ (r.data));
                renderHistory(/** @type {QmsGaugeDetail} */ (r.data).history);
            }
        }, function () { /* 첨부는 이미 올라감 — 이력 수만 못 갱신 */ });
    }

    /** 목록을 다시 읽고 그 행을 다시 고른다(조회 조건 밖이면 상세만) @param {number} id */
    function after(id) {
        var g = grid;
        return (g ? g.reload(false, id) : Promise.resolve()).then(function () {
            if (!g || !g.selected()) { return load(id); }
        });
    }

    /** @param {number} id */
    function load(id) {
        return hb.latest('detail', hb.api(API + '/' + id)).then(function (r) { show(r.data); },
            function (err) { if (err.code !== 'STALE') { notify(err.message, true); } });
    }

    /** 버튼(권한에 따라 없을 수 있음)에 클릭 연결 @param {string} id @param {(b: HTMLButtonElement) => void} fn */
    function bind(id, fn) {
        var b = /** @type {HTMLButtonElement | null} */ (document.getElementById(id));
        if (b) {
            var btn = b;
            btn.addEventListener('click', function () { fn(btn); });
        }
    }

    function confirmLeave() { return qmsUi.leaveOk(hb.dirty.isDirty()); }

    bind('saveBtn', function (btn) {
        if (!current || !cycle.validate().ok) { return; }
        var g = current;   // 저장 중에 다른 행을 골라도 이 계측기로 마무리
        var b = {gaugeClass: classSel.value() || null, calibCycleDays: cycle.numberValue(), rowVer: g.rowVer};
        hb.busy(btn, function () { return hb.api(API + '/' + g.assetId, {method: 'PUT', body: b}); }).then(function (res) {
            if (!res) { return; }
            hb.dirty.clear(profile);
            return after(g.assetId).then(function () { notify(hb.t('js.common.saved')); });
        }, function (err) { notify(err.message, true); hb.showFieldErrors(profile, err.errors); });
    });

    bind('addCalibBtn', function (btn) {
        if (!current || !calibForm || !resultSel || !agencySel || !calibCycle || !calibCycle.validate().ok) { return; }
        var f = calibForm;
        var g = current;
        var b = {calibDate: fieldOf(f, 'calibDate').value, result: resultSel.value(), limitNote: fieldOf(f, 'limitNote').value,
            agency: agencySel.value(), custCd: fieldOf(f, 'custCd').value, certNo: fieldOf(f, 'certNo').value,
            cycleDays: calibCycle.numberValue(), remark: fieldOf(f, 'remark').value};
        if (b.result !== 'LIMITED') { b.limitNote = ''; }
        if (b.agency !== 'EXTERNAL') { b.custCd = ''; }
        hb.showFieldErrors(f, []);
        // 정책이 켜졌으면 서명(대상 = 계측기, 서버가 정규 글자에 교정 id 를 넣어 이 교정에 묶는다)
        (esignRequired ? hb.esign({meaning: 'APPROVE', targetType: 'qms_gauge', targetId: g.assetId, title: g.assetCd})
            : Promise.resolve(undefined)).then(function (token) {
            if (token === null) { return; }
            return hb.busy(btn, function () {
                return hb.api(API + '/' + g.assetId + '/calibs', {method: 'POST', body: Object.assign({esignToken: token || null}, b)});
            });
        }).then(function (res) {
            if (!res) { return; }
            hb.dirty.clear(f);
            return after(g.assetId).then(function () { notify(hb.t('js.qms.gauge.added')); });
        }, function (err) { notify(err.message, true); hb.showFieldErrors(f, err.errors); });
    });

    // ---------- 시작 ----------
    hb.dirty.watch(profile);
    if (calibForm) { hb.dirty.watch(calibForm); }
    search.addEventListener('submit', function (e) { e.preventDefault(); });
    hb.loadMessages().then(function () {
        hb.page.init();
        classSel = hb.select(/** @type {HTMLSelectElement} */ (fieldOf(profile, 'gaugeClass')));
        cycle = hb.number(/** @type {HTMLInputElement} */ (fieldOf(profile, 'calibCycleDays')));
        /** @type {Promise<unknown>[]} */
        var waits = [classSel.ready];
        if (calibForm) {
            var f = calibForm;
            resultSel = hb.select(/** @type {HTMLSelectElement} */ (fieldOf(f, 'result')));
            agencySel = hb.select(/** @type {HTMLSelectElement} */ (fieldOf(f, 'agency')));
            calibCycle = hb.number(/** @type {HTMLInputElement} */ (fieldOf(f, 'cycleDays')));
            waits.push(resultSel.ready, agencySel.ready);
            fieldOf(f, 'result').addEventListener('change', calibLayout);
            fieldOf(f, 'agency').addEventListener('change', calibLayout);
        }
        if (!CAN_SAVE) {
            /** @type {NodeListOf<HTMLInputElement | HTMLSelectElement>} */ (profile.querySelectorAll('input, select'))
                .forEach(function (i) { i.disabled = true; });
        }
        sp = hb.searchPanel(search, {onSearch: function () { var g = grid;
            return g ? confirmLeave().then(function (ok) { return ok && g ? g.reload(true) : null; }) : null; }});
        return Promise.all([hb.codes('GAUGE_STATUS'), hb.codes('CALIB_RESULT'), hb.codes('CALIB_AGENCY'), hb.codes('QMS_INSP_RESULT'), hb.codes('QMS_INSP_STATUS'),
            Promise.all([hb.loadSession(), sp.ready].concat(waits))]);
    }).then(function (r) {
        statusNames = r[0];
        resultNames = r[1];
        agencyNames = r[2];
        inspNames = Object.assign({}, r[4], r[3]);
        // 육안은 계측기 클래스가 아니다(검사계획 행 전용)
        var visual = /** @type {HTMLSelectElement} */ (fieldOf(profile, 'gaugeClass')).querySelector('option[value="VISUAL"]');
        if (visual) { visual.remove(); }
        grid = hb.grid('#gaugeGrid', {
            progCd: PROG, id: 'main', key: 'assetId',
            url: API, columnsUrl: API + '/columns', exportUrl: API + '/export',
            actions: ACTIONS, params: function () { return sp.values(); },
            filters: {gaugeClass: 'gaugeClass', status: 'status'},
            onFilter: function (param, value) { sp.setValue(param, value); },
            beforeSelect: confirmLeave,
            // 상태 칸 = 배지(톤 표 qms-ui.js), 끝난·쓰지 않는 행은 흐리게(UI 단계 3, docs/40 부록 A.6)
            status: {status: qmsUi.TONES.gauge, lastResult: qmsUi.TONES.calib, useYn: qmsUi.TONES.useYn},
            rowClass: function (row) { return row.useYn === 'N' ? 'hb-tree-dim' : null; },
            onSelect: function (row) { load(row.assetId); hb.page.openDetail(); },
            notify: notify
        });
        return grid.ready.then(function () { show(null); });
    }).then(null, function (err) { notify(err && err.message, true); });
})();
