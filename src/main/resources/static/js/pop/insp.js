// @ts-check
/* POP 검사 입력 (/pop 의 패널, docs/25 4.57 · 계약 docs/29_P3 53장) — 공정 화면의 [검사] → 유형(초품·순회(중물)·종품·공정) → 측정값 입력.
   검사 시작은 자동(열린 검사가 있으면 그것), 판정도 자동: 넣을 때마다 서버가 미리보기를 돌려주고 전부 OK 면 그 자리에서 확정,
   NG 면 [불합격 확정]을 한 번 눌러야 확정된다(사용자 결정 2026-11-10). 저장한 측정값은 단말에서 지울 수 없다(무효는 사무 화면) —
   그래서 값은 줄마다 모아 [이 줄 저장]으로 보낸다. 온라인만. 같은 내용 재전송은 같은 요청 id(서버가 처음 결과), 내용을 바꾸면 새 id.
   스캔: 숫자면 고른 칸의 측정값(디지털 게이지 — entry_method SCAN), 그 밖은 계측기 코드로 보고 그 줄의 계측기를 고른다. */
(function () {
    'use strict';

    /**
     * @typedef {{measId: number, sampleNo: number, value: number | null, okYn: string | null, gaugeAssetId: number | null, outOfSpec: boolean}} PopInspMeas
     * @typedef {{inspLineId: number, lineNo: number, charLabel: string, charNm: string, dataType: string, nominal: number | null,
     *   lsl: number | null, usl: number | null, scale: number | null, unitNm: string | null, gaugeClass: string | null, sampleSize: number,
     *   ac: number, re: number, verdict: string, meas: PopInspMeas[]}} PopInspLine
     * @typedef {{inspId: number, inspNo: string, inspType: string, lotNo: string, itemCd: string, itemNm: string, procCd: string,
     *   status: string, result: string | null, preview: string, nextAction: string, lines: PopInspLine[]}} PopInspView
     * @typedef {{inspType: string, suggestedLotNo: string | null, openInspId: number | null, openInspNo: string | null,
     *   lastResult: string | null, lastJudgedAt: string | null}} PopInspDue
     * @typedef {{assetId: number, assetCd: string, assetNm: string, gaugeClass: string, blocked: boolean}} PopInspGauge
     * @typedef {{get: () => string, set: (v: string) => void, el: HTMLElement, lineId: number}} PopInspTarget
     * @typedef {{values: Record<number, string>, gauge: PopInspGauge | null, reqId: string | null, scanned: boolean}} PopInspDraft
     * @typedef {{register: (mod: any) => void, show: (name: string) => void, openProc: (id: number) => Promise<any>,
     *   api: (url: string, opts?: any) => Promise<any>, reqId: () => string, keypad: (host: HTMLElement, opts: any) => any,
     *   say: (kind: string, text: string) => void, fail: (err: any) => void, zone: () => string}} PopInspDoor
     */

    /** @type {PopInspDoor} */
    var home = /** @type {any} */ (window).popHome;
    if (!home) { return; }

    /** @param {string} id */
    function $(id) { return /** @type {HTMLElement} */ (document.getElementById(id)); }

    var panel = $('inspPanel');
    var btn = $('inspBtn');
    var procId = 0;                     // [검사]를 누른 공정
    /** @type {PopInspDue[]} */
    var due = [];
    /** @type {Record<string, string>} */
    var typeNames = {};
    /** @type {PopInspView | null} */
    var view = null;
    /** 줄별 입력 중인 값(아직 안 보낸 것) — 검사를 다시 그려도 남는다 */
    /** @type {Record<number, PopInspDraft>} */
    var drafts = {};
    /** 계측기 종류별 선택지·마지막으로 고른 계측기(같은 종류의 다음 줄에 그대로 쓴다) */
    /** @type {Record<string, PopInspGauge[]>} */
    var gaugeLists = {};
    /** @type {Record<string, PopInspGauge>} */
    var lastGauge = {};
    /** @type {PopInspTarget | null} */
    var target = null;
    /** @type {any} */
    var pad = null;
    /** 열기·불합격 확정의 요청 id(응답을 못 받았을 때 같은 id 로 다시) */
    /** @type {Record<string, string>} */
    var openReq = {};
    /** @type {string | null} */
    var ngReq = null;

    function base() { return '/api/pop/procs/' + procId + '/insp'; }

    /** @param {string} which types | insp */
    function part(which) {
        $('inspTypes').hidden = which !== 'types';
        $('inspTypeInfo').hidden = which !== 'types';
        $('inspCard').hidden = which !== 'insp';
        if (which !== 'insp') { view = null; drafts = {}; setTarget(null); }
    }

    /** @param {string} t */
    function typeName(t) {
        return (typeNames[t] || t) + (t === 'PATROL' ? ' · ' + hb.t('js.pop.insp.mid') : '');
    }

    /** 공정 화면이 열릴 때 — 열 수 있는 검사가 있으면 [검사] 버튼 @param {any} v ProcView */
    function onProc(v) {
        btn.hidden = true;
        if (!v || !v.insp) { return; }
        var id = v.proc.woProcId;
        hb.latest('inspDue', home.api('/api/pop/procs/' + id + '/insp')).then(function (res) {
            procId = id;
            due = res.data.due;
            typeNames = res.data.typeNames || {};
            btn.hidden = !due.length;
            $('inspProc').textContent = v.proc.opSeq + '. ' + v.proc.procCd + ' · ' + v.proc.procNm + ' · ' + v.proc.itemCd + ' ' + v.proc.itemNm;
        }, function () { /* 검사 유형을 못 읽어도 공정 화면은 그대로 */ });
    }

    function loadTypes() {
        return hb.latest('inspDue', home.api(base())).then(function (res) {
            due = res.data.due;
            typeNames = res.data.typeNames || {};
            renderTypes();
            part('types');
        }, home.fail);
    }

    function renderTypes() {
        var host = $('inspTypes');
        host.textContent = '';
        $('inspTypeInfo').textContent = due.length ? hb.t('js.pop.insp.pick') : hb.t('js.pop.insp.none');
        due.forEach(function (d) {
            var li = hb.el('li', 'hb-pop-task');
            var can = !!d.suggestedLotNo || !!d.openInspId;
            li.appendChild(hb.el('h3', null, typeName(d.inspType)));
            if (d.lastResult) {
                li.appendChild(hb.status(d.lastResult === 'OK' ? 'success' : 'danger', hb.t(d.lastResult === 'OK' ? 'js.pop.insp.lastOk' : 'js.pop.insp.lastNg',
                    hb.fmt.datetime(d.lastJudgedAt, home.zone()).substring(5))));
            }
            li.appendChild(hb.el('p', null, d.openInspId ? hb.t('js.pop.insp.resume', d.openInspNo || '', d.suggestedLotNo || '')
                : can ? hb.t('js.pop.insp.start', d.suggestedLotNo || '') : hb.t('js.pop.insp.noLot')));
            if (can) {
                li.tabIndex = 0;
                li.setAttribute('role', 'button');
                li.addEventListener('click', function () { openInsp(d); });
                li.addEventListener('keydown', function (e) { if (e.key === 'Enter') { openInsp(d); } });
            } else {
                li.setAttribute('aria-disabled', 'true');
            }
            host.appendChild(li);
        });
    }

    /** 검사 열기(자동 — 열린 검사가 있으면 그것) @param {PopInspDue} d */
    function openInsp(d) {
        if (!navigator.onLine) { home.say('error', hb.t('js.pop.insp.offline')); return; }
        var key = d.inspType + ':' + (d.suggestedLotNo || '');
        openReq[key] = openReq[key] || home.reqId();
        hb.latest('inspOpen', home.api(base() + '/open', {method: 'POST', body: {inspType: d.inspType, lotNo: d.suggestedLotNo,
            clientReqId: openReq[key]}})).then(function (res) {
            drafts = {};
            ngReq = null;
            show(res.data.insp);
            home.say('info', hb.t(res.data.ref.existing || res.data.ref.duplicate ? 'js.pop.insp.resumed' : 'js.pop.insp.opened', res.data.insp.inspNo));
        }, function (err) {
            if (err && (err.code === 'NETWORK' || err.code === 'TIMEOUT')) { home.say('error', hb.t('js.pop.insp.offline')); return; }
            home.fail(err);
        });
    }

    /** @param {PopInspView} v */
    function show(v) {
        view = v;
        $('inspTitle').textContent = typeName(v.inspType) + ' · ' + v.inspNo;
        $('inspInfo').textContent = [v.lotNo, v.itemCd + ' ' + v.itemNm].join(' · ');
        renderInsp();
        part2();
    }

    function part2() {
        $('inspTypes').hidden = true;
        $('inspTypeInfo').hidden = true;
        $('inspCard').hidden = false;
    }

    /** @param {PopInspTarget | null} t */
    function setTarget(t) {
        target = t;
        document.querySelectorAll('#inspLines .hb-pop-field').forEach(function (b) { b.setAttribute('aria-pressed', String(!!t && b === t.el)); });
        $('inspPadBox').hidden = !t;
        if (t && pad) { pad.set(t.get().replace('-', '')); }
    }

    /** @param {number} lineId @returns {PopInspDraft} */
    function draft(lineId) {
        drafts[lineId] = drafts[lineId] || {values: {}, gauge: null, reqId: null, scanned: false};
        return drafts[lineId];
    }

    /** 규격 밖 미리 보기(판정은 서버) @param {PopInspLine} l @param {string} raw */
    function out(l, raw) {
        if (raw === '' || raw === '-' || isNaN(Number(raw))) { return false; }
        var n = Number(raw);
        return (l.lsl !== null && n < l.lsl) || (l.usl !== null && n > l.usl);
    }

    /** @param {string} v OK | NG | OPEN */
    function verdictBadge(v) {
        return hb.status(v === 'OK' ? 'success' : v === 'NG' ? 'danger' : 'neutral',
            v === 'OK' ? hb.t('js.pop.insp.v.OK') : v === 'NG' ? hb.t('js.pop.insp.v.NG') : hb.t('js.pop.insp.v.OPEN'));
    }

    function renderInsp() {
        if (!view) { return; }
        var v = view;
        var open = v.status === 'OPEN';
        var head = $('inspState');
        head.textContent = '';
        head.appendChild(v.status === 'JUDGED' ? hb.status(v.result === 'OK' ? 'success' : 'danger',
            hb.t(v.result === 'OK' ? 'js.pop.insp.judgedOk' : 'js.pop.insp.judgedNg'))
            : v.status === 'VOID' ? hb.status('neutral', hb.t('js.pop.insp.void')) : verdictBadge(v.preview));
        $('inspNgBox').hidden = !(open && v.nextAction === 'CONFIRM_NG');
        $('inspGuide').textContent = !open ? '' : v.nextAction === 'CONFIRM_NG' ? hb.t('js.pop.insp.needNg') : hb.t('js.pop.insp.guide');
        if (!pad) {
            pad = home.keypad($('inspPad'), {max: 14, decimal: true, onChange: function (/** @type {string} */ x) {
                if (target) { target.set((target.get().charAt(0) === '-' ? '-' : '') + x); }
            }});
        }
        var host = $('inspLines');
        host.textContent = '';
        v.lines.forEach(function (l) { host.appendChild(lineEl(l, open)); });
        setTarget(null);
    }

    /** @param {PopInspLine} l @param {boolean} open */
    function lineEl(l, open) {
        var d = draft(l.inspLineId);
        var li = hb.el('li', 'hb-pop-insp-line');
        li.setAttribute('data-line', String(l.inspLineId));
        var head = hb.el('div', 'hb-pop-insp-head');
        head.appendChild(hb.el('b', null, l.lineNo + '. ' + l.charLabel + ' ' + l.charNm));
        head.appendChild(verdictBadge(l.verdict));
        li.appendChild(head);
        var variable = l.dataType === 'VARIABLE';
        li.appendChild(hb.el('p', 'hb-pop-sub', (variable ? hb.t('js.pop.insp.spec', l.lsl === null ? '' : l.lsl, l.usl === null ? '' : l.usl, l.unitNm || '') + ' · ' : '')
            + hb.t('js.pop.insp.sample', l.sampleSize, l.ac)));
        /** @type {Record<number, PopInspMeas>} */
        var done = {};
        l.meas.forEach(function (m) { done[m.sampleNo] = m; });
        var left = 0;
        var cells = hb.el('div', 'hb-pop-insp-cells');
        for (var n = 1; n <= l.sampleSize; n++) {
            (function (no) {
                var m = done[no];
                if (m) {
                    var saved = hb.el('span', 'hb-pop-insp-saved', no + ': ' + (variable ? String(m.value) : hb.t(m.okYn === 'Y' ? 'js.pop.insp.pass' : 'js.pop.insp.fail')));
                    saved.setAttribute('data-out', String(m.outOfSpec));
                    cells.appendChild(saved);
                    return;
                }
                if (!open) { return; }
                left++;
                if (variable) {
                    var f = hb.el('button', 'hb-pop-field');
                    f.type = 'button';
                    f.appendChild(hb.el('span', null, hb.t('js.pop.insp.sampleNo', no)));
                    var shown = hb.el('b', null, d.values[no] || '');
                    f.appendChild(shown);
                    f.setAttribute('data-out', String(out(l, d.values[no] || '')));
                    /** @type {PopInspTarget} */
                    var t = {el: f, lineId: l.inspLineId, get: function () { return d.values[no] || ''; }, set: function (x) {
                        d.values[no] = x;
                        d.reqId = null;
                        shown.textContent = x;
                        f.setAttribute('data-out', String(out(l, x)));
                    }};
                    f.addEventListener('click', function () { setTarget(t); });
                    cells.appendChild(f);
                } else {
                    var box = hb.el('div', 'hb-pop-okng');
                    box.appendChild(hb.el('span', null, String(no)));
                    ['Y', 'N'].forEach(function (yn) {
                        var b = hb.el('button', null, hb.t(yn === 'Y' ? 'js.pop.insp.pass' : 'js.pop.insp.fail'));
                        b.type = 'button';
                        b.setAttribute('data-judge', yn === 'Y' ? 'OK' : 'NG');
                        b.setAttribute('aria-pressed', String(d.values[no] === yn));
                        b.addEventListener('click', function () {
                            if (d.values[no] === yn) { delete d.values[no]; } else { d.values[no] = yn; }
                            d.reqId = null;
                            box.querySelectorAll('button').forEach(function (x) {
                                x.setAttribute('aria-pressed', String((x.getAttribute('data-judge') === 'OK' ? 'Y' : 'N') === d.values[no]));
                            });
                        });
                        box.appendChild(b);
                    });
                    cells.appendChild(box);
                }
            })(n);
        }
        li.appendChild(cells);
        if (open && left) {
            var acts = hb.el('div', 'hb-pop-insp-acts');
            var needGauge = !!l.gaugeClass && l.gaugeClass !== 'VISUAL';
            if (needGauge) {
                d.gauge = d.gauge || lastGauge[/** @type {string} */ (l.gaugeClass)] || null;
                var g = hb.el('button', null, d.gauge ? hb.t('js.pop.insp.gauge', d.gauge.assetCd) : hb.t('js.pop.insp.pickGauge'));
                g.type = 'button';
                g.addEventListener('click', function () { pickGauge(l, li); });
                acts.appendChild(g);
            }
            if (!variable) {
                var all = hb.el('button', null, hb.t('js.pop.insp.allPass'));
                all.type = 'button';
                all.addEventListener('click', function () {
                    for (var k = 1; k <= l.sampleSize; k++) { if (!done[k]) { d.values[k] = 'Y'; } }
                    d.reqId = null;
                    renderInsp();
                });
                acts.appendChild(all);
            }
            var save = hb.el('button', 'hb-pop-primary', hb.t('js.pop.insp.saveLine'));
            save.type = 'button';
            save.addEventListener('click', function () { saveLine(l, save); });
            acts.appendChild(save);
            li.appendChild(acts);
        }
        li.appendChild(hb.el('div', 'hb-pop-grid hb-pop-insp-gauges'));
        li.appendChild(hb.el('p', 'hb-pop-reason', ''));
        return li;
    }

    /** 계측기 고르기 — 그 종류의 사용 중 계측기(교정 막힌 것은 못 고른다). 스캔으로도 고른다 @param {PopInspLine} l @param {HTMLElement} li */
    function pickGauge(l, li) {
        var cls = /** @type {string} */ (l.gaugeClass);
        var host = /** @type {HTMLElement} */ (li.querySelector('.hb-pop-insp-gauges'));
        /** @param {PopInspGauge[]} list */
        var draw = function (list) {
            host.textContent = '';
            if (!list.length) { home.say('error', hb.t('js.pop.insp.noGauge', cls)); return; }
            list.forEach(function (g) {
                var b = hb.el('button', null, g.assetCd + ' · ' + g.assetNm + (g.blocked ? ' · ' + hb.t('js.pop.insp.gaugeBlocked') : ''));
                b.type = 'button';
                b.disabled = g.blocked;
                b.addEventListener('click', function () { setGauge(l.inspLineId, g); });
                host.appendChild(b);
            });
        };
        if (gaugeLists[cls]) { draw(gaugeLists[cls]); return; }
        hb.latest('inspGauges', home.api(base() + '/gauges?gaugeClass=' + encodeURIComponent(cls))).then(function (res) {
            gaugeLists[cls] = res.data;
            draw(res.data);
        }, home.fail);
    }

    /** @param {number} lineId @param {PopInspGauge} g */
    function setGauge(lineId, g) {
        var d = draft(lineId);
        d.gauge = g;
        d.reqId = null;
        lastGauge[g.gaugeClass] = g;
        renderInsp();
        home.say('ok', hb.t('js.pop.insp.gauge', g.assetCd));
    }

    /** 한 줄의 입력을 보낸다 — 응답이 곧 새 화면(미리보기·자동 확정) @param {PopInspLine} l @param {HTMLElement} button */
    function saveLine(l, button) {
        if (!view) { return; }
        var cur = view;
        var d = draft(l.inspLineId);
        var variable = l.dataType === 'VARIABLE';
        var nos = Object.keys(d.values).map(Number).filter(function (n) { return d.values[n] !== '' && d.values[n] !== '-'; });
        if (!nos.length) { home.say('error', hb.t('js.pop.insp.needValue')); return; }
        var needGauge = !!l.gaugeClass && l.gaugeClass !== 'VISUAL';
        if (needGauge && !d.gauge) { home.say('error', hb.t('js.pop.insp.needGauge')); return; }
        if (!navigator.onLine) { home.say('error', hb.t('js.pop.insp.offline')); return; }
        d.reqId = d.reqId || home.reqId();
        var gaugeId = d.gauge ? d.gauge.assetId : null;
        var body = {inspLineId: l.inspLineId, entryMethod: d.scanned ? 'SCAN' : 'KEY', clientReqId: d.reqId, entries: nos.map(function (n) {
            return {sampleNo: n, value: variable ? d.values[n].replace(/\.$/, '') : null, okYn: variable ? null : d.values[n], gaugeAssetId: needGauge ? gaugeId : null};
        })};
        hb.busy(/** @type {HTMLButtonElement} */ (button), function () {
            return home.api(base() + '/' + cur.inspId + '/meas', {method: 'POST', body: body});
        }).then(function (res) {
            if (!res) { return; }
            delete drafts[l.inspLineId];
            show(res.data.insp);
            var r = res.data.insp;
            if (res.data.judged) {
                home.say('ok', hb.t('js.pop.insp.autoOk', r.inspNo));
            } else if (r.nextAction === 'CONFIRM_NG') {
                home.say('error', hb.t('js.pop.insp.needNg'));
            } else {
                home.say('ok', hb.t('js.pop.insp.saved'));
            }
        }, function (err) {
            if (err && (err.code === 'NETWORK' || err.code === 'TIMEOUT')) { home.say('error', hb.t('js.pop.insp.offline')); return; }
            // 칸 오류(행 키 = 보낸 순번) → 그 줄 아래에
            var li = document.querySelector('#inspLines li[data-line="' + l.inspLineId + '"]');
            var msgs = (err && err.errors || []).map(function (/** @type {any} */ x) {
                var no = nos[Number(x.rowKey)];
                return (no ? hb.t('js.pop.insp.sampleNo', no) + ': ' : '') + x.message;
            });
            if (li && msgs.length) { /** @type {HTMLElement} */ (li.querySelector('.hb-pop-reason')).textContent = msgs.join(' / '); }
            home.fail(err);
        });
    }

    /** [불합격 확정] — 확인 한 번(되돌리려면 사무 화면에서 검사 무효) @param {HTMLElement} button */
    function confirmNg(button) {
        if (!view) { return; }
        var cur = view;
        hb.dialog.confirm({title: hb.t('js.pop.insp.dlgNg'), message: hb.t('js.pop.insp.dlgNgMsg', cur.inspNo, cur.lotNo),
            ok: hb.t('js.pop.insp.dlgNgOk'), danger: true}).then(function (ok) {
            if (!ok) { return; }
            if (!navigator.onLine) { home.say('error', hb.t('js.pop.insp.offline')); return; }
            ngReq = ngReq || home.reqId();
            hb.busy(/** @type {HTMLButtonElement} */ (button), function () {
                return home.api(base() + '/' + cur.inspId + '/confirm-ng', {method: 'POST', body: {clientReqId: ngReq}});
            }).then(function (res) {
                if (!res) { return; }
                ngReq = null;
                show(res.data);
                home.say('error', hb.t('js.pop.insp.ngDone', res.data.inspNo));
            }, function (err) {
                if (err && (err.code === 'NETWORK' || err.code === 'TIMEOUT')) { home.say('error', hb.t('js.pop.insp.offline')); return; }
                home.fail(err);
            });
        });
    }

    /** 스캔 — 숫자 = 고른 칸의 측정값(디지털 게이지), 그 밖 = 계측기 코드(고른 칸의 줄, 없으면 계측기가 필요한 첫 줄) @param {string} v */
    function onScan(v) {
        if (!view || view.status !== 'OPEN') { return; }
        if (/^-?\d+(\.\d+)?$/.test(v)) {
            if (!target) { home.say('info', hb.t('js.pop.insp.pickCell')); return; }
            draft(target.lineId).scanned = true;
            target.set(v);
            return;
        }
        var code = v.split(':').pop() || '';
        var lines = view.lines.filter(function (l) { return !!l.gaugeClass && l.gaugeClass !== 'VISUAL'; });
        var line = target ? lines.filter(function (l) { return target !== null && l.inspLineId === target.lineId; })[0] : lines[0];
        if (!line) { home.say('info', hb.t('js.pop.insp.scanNone', code)); return; }
        var cls = /** @type {string} */ (line.gaugeClass);
        /** @param {PopInspGauge[]} list */
        var pick = function (list) {
            var g = list.filter(function (x) { return x.assetCd === code; })[0];
            if (!g) { home.say('error', hb.t('js.pop.insp.scanNone', code)); return; }
            if (g.blocked) { home.say('error', hb.t('js.pop.insp.scanBlocked', code)); return; }
            setGauge(line.inspLineId, g);
        };
        if (gaugeLists[cls]) { pick(gaugeLists[cls]); return; }
        home.api(base() + '/gauges?gaugeClass=' + encodeURIComponent(cls)).then(function (res) {
            gaugeLists[cls] = res.data;
            pick(res.data);
        }, home.fail);
    }

    home.register({name: 'insp', el: panel, onProc: onProc, onScan: onScan});

    btn.addEventListener('click', function () { home.show('insp'); loadTypes(); });
    $('inspClose').addEventListener('click', function () { part('types'); home.openProc(procId); });
    $('inspBack').addEventListener('click', loadTypes);
    $('inspNg').addEventListener('click', function (e) { confirmNg(/** @type {HTMLElement} */ (e.currentTarget)); });
    $('inspSign').addEventListener('click', function () {
        if (!target) { return; }
        var x = target.get();
        target.set(x.charAt(0) === '-' ? x.substring(1) : '-' + x);
    });
    $('inspPadClose').addEventListener('click', function () { setTarget(null); });
})();
