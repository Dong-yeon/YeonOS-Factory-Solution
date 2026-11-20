// @ts-check
/*
 * SPC 관리도 (qms-spc) — P3 QMS 2부 Q10 (docs/29_P3 12.6).
 *   관리도 단위 표(품목 × 특성 × 검사 유형 × 공정) → 상세: 조건(부분군 수·끝 날짜·규칙) / X̄·R 관리도(SVG) / 공정능력 / 부분군 표 /
 *   고정 한계(정하기·무효) / 한계 이력. 계산은 모두 서버(SpcCalc). 서버 데이터는 textContent·속성으로만.
 * "위반" 보기(docs/29_P3 19장 SV-7): 위반 원장 목록(hb.grid) → 상세: 한계 스냅샷·처리 이력·처리(ACK·ACTION·CLOSE·VOID).
 *   관리도 상세에도 그 단위의 위반 목록, 이벤트가 남은 점은 테두리.
 */
/**
 * @typedef {{itemId: number, itemCd: string, itemNm: string, charId: number, charNo: string, charNm: string, inspType: string,
 *   procId?: number | null, procCd?: string | null, procNm?: string | null, subgroupCnt: number, lastAt: string, limitId?: number | null,
 *   limitYn?: string, unitKey?: string}} SpcUnit
 * @typedef {{index: number, inspLineId: number, inspNo: string, lotNo: string, at: string, n: number, mean: number, range: number,
 *   xUcl: number, xLcl: number, rCl: number, rUcl: number, rLcl: number, z?: number | null, rules: string[]}} SpcPoint
 * @typedef {{limitId: number, center: number, sigma: number, subgroupCnt: number, fromAt: string, toAt: string, rules: string,
 *   status: string, remark?: string | null, voidReason?: string | null, createdAt: string, createdBy: string, rowVer: number}} SpcLimit
 * @typedef {{n: number, mean: number, sigmaWithin: number, sigmaOverall: number, cp?: number | null, cpk?: number | null,
 *   pp?: number | null, ppk?: number | null}} SpcCap
 * @typedef {{unit: {itemCd: string, itemNm: string, charNo: string, charNm: string, procCd?: string | null, procNm?: string | null,
 *   scale?: number | null, unitNm?: string | null}, inspType: string, source: string, limit?: SpcLimit | null, center?: number | null,
 *   sigma?: number | null, calcCenter?: number | null, calcSigma?: number | null, rules: string[], points: SpcPoint[], excluded: number,
 *   capability?: SpcCap | null, specMixed: boolean, minSubgroups: number, chartType?: string}} SpcChart
 * @typedef {{eventId: number, itemCd: string, itemNm: string, charNo: string, charNm: string, inspType: string, procCd?: string | null,
 *   inspNo: string, lotNo: string, inspLineId: number, ruleCd: string, n: number, pointMean: number, pointRange: number, chartType?: string, z?: number | null,
 *   center: number, sigma: number, limitId: number, occurredAt: string, status: string, lastBy?: string | null}} SpcEvent
 * @typedef {{actId: number, actType: string, note?: string | null, linkType?: string | null, linkNo?: string | null, actedAt: string,
 *   actedBy: string, ocaps?: {ocapId: number, ruleCd?: string | null, actionText: string}[]}} SpcAct
 * @typedef {{ocapId: number, ruleCd?: string | null, actionText: string}} SpcOcapOption
 */
(function () {
    'use strict';

    /** QMS 화면 공용 — 상태 배지·대화상자(qms-ui.js) */
    var qmsUi = /** @type {QmsUi} */ (/** @type {any} */ (window).qmsUi);

    var PROG = document.body.getAttribute('data-prog') || '';
    var ACTIONS = (document.body.getAttribute('data-actions') || '').split(',');
    var CAN_SAVE = ACTIONS.indexOf('SAVE') >= 0;
    var CAN_DELETE = ACTIONS.indexOf('DELETE') >= 0;
    var API = '/api/qms/spc';
    var SVG = 'http://www.w3.org/2000/svg';
    var W = 600;
    var H = 180;
    var PAD = {l: 8, r: 70, t: 10, b: 10};
    var msg = /** @type {HTMLElement} */ (document.getElementById('msg'));
    var formMode = /** @type {HTMLElement} */ (document.getElementById('formMode'));
    var chartBox = /** @type {HTMLElement} */ (document.getElementById('chartBox'));
    var headLine = /** @type {HTMLElement} */ (document.getElementById('headLine'));
    /** @type {SpcUnit | null} */
    var unit = null;
    /** @type {SpcChart | null} */
    var chart = null;
    /** @type {Record<string, string>} */
    var typeNames = {};
    /** @type {Record<string, string>} */
    var statusNames = {};
    /** @type {Record<string, string>} 규칙 이름(문구 키는 글자 그대로 — 키 가드) */
    var ruleNames = {};
    /** @type {Record<string, string>} */
    var sourceNames = {};
    /** @type {Record<string, string>} */
    var eventStatusNames = {};
    /** @type {Record<string, string>} */
    var actNames = {};
    /** @type {Record<string, string>} */
    var ruleCodeNames = {};
    /** 보기: units | events */
    var view = 'units';
    /** @type {SpcEvent | null} */
    var event = null;
    /** 지금 연 위반 근거로 LOT 이 보류 중인가(SV-6 자동 보류) */
    var eventHeld = false;
    /** 관리도 상세의 이 단위 위반(점 표시용) @type {SpcEvent[]} */
    var unitEvents = [];
    var eventBox = /** @type {HTMLElement} */ (document.getElementById('eventBox'));

    /** @param {string | null | undefined} text @param {boolean} [error] */
    function notify(text, error) {
        msg.textContent = text || '';
        msg.classList.toggle('hb-msg-error', !!error);
    }

    /** @param {{message: string, errors?: HB.FieldError[]}} err */
    function errText(err) {
        return err.message + (err.errors && err.errors.length
            ? ' ' + err.errors.map(/** @param {HB.FieldError} e */ function (e) { return e.message; }).join(' ') : '');
    }

    /** @param {number | null | undefined} v @param {number} [scale] */
    function num(v, scale) {
        return v === null || v === undefined ? '-' : hb.fmt.num(v, scale === undefined ? 4 : scale, 0);
    }

    /** @param {SpcUnit} u */
    function unitKey(u) {
        return 'itemId=' + u.itemId + '&charId=' + u.charId + '&inspType=' + encodeURIComponent(u.inspType)
            + (u.procId ? '&procId=' + u.procId : '');
    }

    /** 주 목록 = hb.grid(UI 단계 3) — 서버 API + '/units'/grid·/columns @type {HB.Grid | null} */
    var grid = null;

    /** 목록 다시 읽기 — 보던 행을 그대로 고른 채(keepId 는 onSelect 를 부르지 않는다) */
    function loadUnits() {
        return grid ? grid.reload(false, unit ? unit.unitKey : undefined) : Promise.resolve();
    }

    /** 위반 목록 = hb.grid — 서버 API + '/events'/grid·/columns @type {HB.Grid | null} */
    var eventGrid = null;

    function startEventGrid() {
        eventGrid = hb.grid('#eventGrid', {
            progCd: PROG, id: 'events', key: 'eventId',
            url: API + '/events' + '/grid', columnsUrl: API + '/events' + '/columns', exportUrl: API + '/events' + '/export', actions: ACTIONS,
            params: function () { return sp.values(); },
            status: {status: qmsUi.TONES.spcEvent},
            onSelect: function (row) { openEvent(/** @type {SpcEvent} */ (/** @type {unknown} */ (row)).eventId, false); },
            notify: notify
        });
        return eventGrid.ready;
    }

    /** 보기 전환 — 조회 조건의 상태 칸은 위반 보기에서만 @param {string} v */
    function setView(v) {
        view = v;
        /** @type {HTMLElement} */ (document.getElementById('unitGrid')).hidden = v !== 'units';
        /** @type {HTMLElement} */ (document.getElementById('eventGrid')).hidden = v !== 'events';
        /** @type {HTMLElement} */ (document.getElementById('statusField')).hidden = v !== 'events';
        hb.page.closeDetail();
        unit = null;
        event = null;
        chartBox.hidden = true;
        eventBox.hidden = true;
        formMode.textContent = v === 'events' ? hb.t('js.qms.spcEvent.select') : hb.t('js.qms.spcEvent.selectUnit');
        formMode.hidden = false;
        var ready = v === 'events' && !eventGrid ? startEventGrid() : Promise.resolve();
        return ready.then(function () { return sp.search(); });
    }

    /** 보기 단추까지 맞춰 바꾸기 — 링크(주소 ?q.view·이미 열린 탭 onLink)용 @param {string} v */
    function pickView(v) {
        var r = /** @type {HTMLInputElement} */ (document.querySelector('#viewSeg input[value=' + v + ']'));
        r.checked = true;
        return setView(v);
    }

    function startGrid() {
        grid = hb.grid('#unitGrid', {
            progCd: PROG, id: 'main', key: 'unitKey',
            url: API + '/units' + '/grid', columnsUrl: API + '/units' + '/columns', exportUrl: API + '/units' + '/export', actions: ACTIONS,
            params: function () { return sp.values(); },
            status: {limitYn: {Y: 'normal', N: 'neutral'}},
            onSelect: function (row) { unit = /** @type {SpcUnit} */ (/** @type {unknown} */ (row));
                eventBox.hidden = true;
                /** @type {HTMLInputElement} */ (document.getElementById('optN')).value = '25';
                /** @type {HTMLInputElement} */ (document.getElementById('optTo')).value = '';
                load(true);
                hb.page.openDetail(); },
            notify: notify
        });
        return grid.ready;
    }

    /** @return {NodeListOf<HTMLInputElement>} */
    function ruleBoxes() {
        return /** @type {NodeListOf<HTMLInputElement>} */ (document.querySelectorAll('#optForm input[name=rule]'));
    }

    /** 조건 → 질의 문자열. first = 고른 직후(규칙은 서버 기본·고정 한계 규칙) @param {boolean} first */
    function query(first) {
        if (!unit) { return ''; }
        var q = unitKey(unit);
        var n = /** @type {HTMLInputElement} */ (document.getElementById('optN')).value;
        var to = /** @type {HTMLInputElement} */ (document.getElementById('optTo')).value;
        if (n) { q += '&n=' + encodeURIComponent(n); }
        if (to) { q += '&to=' + encodeURIComponent(to); }
        if (!first) {
            ruleBoxes().forEach(function (b) { if (b.checked) { q += '&rules=' + b.value; } });
        }
        return q;
    }

    /** @param {boolean} [first] */
    function load(first) {
        if (!unit) { return Promise.resolve(); }
        var u = unit;
        var ev = hb.api(API + '/events/grid?' + unitKey(u) + '&size=50&sort=occurredAt,desc');
        return hb.latest('chart', Promise.all([hb.api(API + '/chart?' + query(!!first)), ev])).then(function (r) {
            unitEvents = /** @type {SpcEvent[]} */ (r[1].list || []);
            render(/** @type {SpcChart} */ (r[0].data));
            renderUnitEvents();
            return loadHistory(u);
        }, function (err) { if (err.code !== 'STALE') { notify(errText(err), true); } });
    }

    /**
     * 관리도 한 장 — 한계는 부분군마다 다를 수 있어 계단선
     * @param {HTMLElement} box
     * @param {SpcPoint[]} pts
     * @param {(p: SpcPoint) => number} val
     * @param {(p: SpcPoint) => number} ucl
     * @param {(p: SpcPoint) => number} cl
     * @param {(p: SpcPoint) => number} lcl
     * @param {(p: SpcPoint) => boolean} alarm
     * @param {number} scale
     * @param {(p: SpcPoint) => boolean} marked 위반 원장에 이벤트가 남은 점
     */
    function draw(box, pts, val, ucl, cl, lcl, alarm, scale, marked) {
        box.textContent = '';
        if (!pts.length) { return; }
        var lo = Infinity;
        var hi = -Infinity;
        pts.forEach(function (p) {
            lo = Math.min(lo, val(p), lcl(p));
            hi = Math.max(hi, val(p), ucl(p));
        });
        if (hi === lo) { hi += 1; lo -= 1; }
        var m = (hi - lo) * 0.08;
        lo -= m;
        hi += m;
        var step = (W - PAD.l - PAD.r) / Math.max(pts.length, 1);
        /** @param {number} i */
        function x(i) { return PAD.l + step * (i + 0.5); }
        /** @param {number} v */
        function y(v) { return PAD.t + (hi - v) / (hi - lo) * (H - PAD.t - PAD.b); }
        var svg = document.createElementNS(SVG, 'svg');
        svg.setAttribute('viewBox', '0 0 ' + W + ' ' + H);
        svg.setAttribute('class', 'hb-spc-svg');
        svg.setAttribute('role', 'img');
        /** @param {string} d @param {string} cls */
        function path(d, cls) {
            var e = document.createElementNS(SVG, 'path');
            e.setAttribute('d', d);
            e.setAttribute('class', cls);
            svg.appendChild(e);
        }
        /** @param {(p: SpcPoint) => number} f */
        function steps(f) {
            return pts.map(function (p, i) {
                return (i === 0 ? 'M' : 'L') + (x(i) - step / 2).toFixed(1) + ' ' + y(f(p)).toFixed(1) + ' H' + (x(i) + step / 2).toFixed(1);
            }).join(' ');
        }
        path(steps(ucl), 'hb-spc-limit');
        path(steps(lcl), 'hb-spc-limit');
        path(steps(cl), 'hb-spc-center');
        path(pts.map(function (p, i) { return (i === 0 ? 'M' : 'L') + x(i).toFixed(1) + ' ' + y(val(p)).toFixed(1); }).join(' '), 'hb-spc-line');
        pts.forEach(function (p, i) {
            var c = document.createElementNS(SVG, 'circle');
            c.setAttribute('cx', x(i).toFixed(1));
            c.setAttribute('cy', y(val(p)).toFixed(1));
            c.setAttribute('r', alarm(p) ? '4' : '2.5');
            c.setAttribute('class', (alarm(p) ? 'hb-spc-pt hb-spc-alarm' : 'hb-spc-pt') + (marked(p) ? ' hb-spc-event' : ''));
            var t = document.createElementNS(SVG, 'title');
            t.textContent = (p.index + 1) + ' · ' + p.lotNo + ' · ' + num(val(p), scale);
            c.appendChild(t);
            svg.appendChild(c);
        });
        var last = pts[pts.length - 1];
        [['UCL', ucl(last)], ['CL', cl(last)], ['LCL', lcl(last)]].forEach(function (l) {
            var tx = document.createElementNS(SVG, 'text');
            tx.setAttribute('x', String(W - PAD.r + 4));
            tx.setAttribute('y', (y(/** @type {number} */ (l[1])) + 4).toFixed(1));
            tx.setAttribute('class', 'hb-spc-label');
            tx.textContent = l[0] + ' ' + num(/** @type {number} */ (l[1]), scale);
            svg.appendChild(tx);
        });
        box.appendChild(svg);
    }

    /** @param {SpcChart} d */
    function render(d) {
        chart = d;
        formMode.hidden = true;
        chartBox.hidden = false;
        var u = d.unit;
        /** p 관리도(계수형, docs/29_P3 28장): 한 장(p), R·공정능력 없음, 규칙 1·4 만 */
        var isP = d.chartType === 'P';
        var scale = isP ? 4 : (u.scale === null || u.scale === undefined ? 2 : u.scale) + 2;
        offP(isP);
        var alarms = d.points.filter(function (p) { return p.rules.length > 0; }).length;
        headLine.textContent = hb.t('js.qms.spc.head', u.itemCd, u.charNo + ' ' + u.charNm, typeNames[d.inspType] || d.inspType,
            u.procCd ? u.procCd : hb.t('js.qms.spc.noProc'), alarms ? hb.t('js.qms.spc.alarm', alarms) : hb.t('js.qms.spc.ok'));
        headLine.setAttribute('data-status', alarms ? 'NG' : 'OK');
        ruleBoxes().forEach(function (b) { b.checked = d.rules.indexOf(b.value) >= 0; });
        var ruleText = d.rules.map(function (r) { return r.replace('WE', ''); }).join('·');
        var line = (sourceNames[d.source] || d.source) + ' — ' + (isP ? hb.t('js.qms.spc.limitsP', num(d.center, scale), ruleText)
            : hb.t('js.qms.spc.limits', num(d.center, scale), num(d.sigma, scale), ruleText));
        if (d.excluded) { line += ' · ' + hb.t('js.qms.spc.excluded', d.excluded); }
        /** @type {HTMLElement} */ (document.getElementById('limitLine')).textContent = d.points.length ? line : hb.t('js.qms.spc.none');
        draw(/** @type {HTMLElement} */ (document.getElementById('xChart')), d.points, function (p) { return p.mean; },
            function (p) { return p.xUcl; }, function () { return /** @type {number} */ (d.center); }, function (p) { return p.xLcl; },
            function (p) { return p.rules.some(function (r) { return r !== 'R1'; }); }, scale,
            function (p) { return hasEvent(p, false); });
        /** @type {HTMLElement} */ (document.getElementById('rChart')).textContent = '';
        if (!isP) {
            draw(/** @type {HTMLElement} */ (document.getElementById('rChart')), d.points, function (p) { return p.range; },
                function (p) { return p.rUcl; }, function (p) { return p.rCl; }, function (p) { return p.rLcl; },
                function (p) { return p.rules.indexOf('R1') >= 0; }, scale, function (p) { return hasEvent(p, true); });
        }
        var c = d.capability;
        /** @type {HTMLElement} */ (document.getElementById('capLine')).textContent = d.specMixed ? hb.t('js.qms.spc.specMixed')
            : c ? hb.t('js.qms.spc.cap', num(c.cp, 2), num(c.cpk, 2), num(c.pp, 2), num(c.ppk, 2), c.n) : '';
        var tbody = /** @type {HTMLElement} */ (document.querySelector('#pointTable tbody'));
        tbody.textContent = '';
        d.points.slice().reverse().forEach(function (p) {
            var tr = hb.el('tr', p.rules.length ? 'hb-spc-row-alarm' : null);
            tr.appendChild(hb.el('td', 'hb-num', String(p.index + 1)));
            tr.appendChild(hb.el('td', 'hb-wrap', p.lotNo));
            tr.appendChild(hb.el('td', null, p.inspNo));
            tr.appendChild(hb.el('td', 'hb-num', String(p.n)));
            tr.appendChild(hb.el('td', 'hb-num', num(p.mean, scale)));
            tr.appendChild(hb.el('td', 'hb-num', isP ? String(p.range) : num(p.range, scale)));
            tr.appendChild(hb.el('td', 'hb-wrap', p.rules.map(function (r) { return ruleNames[r] || r; }).join(', ')));
            tbody.appendChild(tr);
        });
        var fixedLine = /** @type {HTMLElement} */ (document.getElementById('fixedLine'));
        var fixForm = /** @type {HTMLFormElement} */ (document.getElementById('fixForm'));
        var voidForm = /** @type {HTMLFormElement} */ (document.getElementById('voidForm'));
        var l = d.limit;
        if (l) {
            fixedLine.textContent = isP
                ? hb.t('js.qms.spc.fixedP', num(l.center, scale), l.subgroupCnt, hb.fmt.date(l.fromAt), hb.fmt.date(l.toAt),
                    l.rules.replace(/WE/g, '').replace(/,/g, '·'), l.createdBy + ' ' + hb.fmt.datetime(l.createdAt))
                : hb.t('js.qms.spc.fixed', num(l.center, scale), num(l.sigma, scale), l.subgroupCnt, hb.fmt.date(l.fromAt),
                    hb.fmt.date(l.toAt), l.rules.replace(/WE/g, '').replace(/,/g, '·'), l.createdBy + ' ' + hb.fmt.datetime(l.createdAt));
        } else {
            fixedLine.textContent = hb.t('js.qms.spc.noLimit') + (d.points.length < d.minSubgroups
                ? ' ' + hb.t('js.qms.spc.few', d.minSubgroups, d.points.length) : '');
        }
        fixForm.hidden = !CAN_SAVE || !!l || d.points.length < d.minSubgroups;
        voidForm.hidden = !CAN_DELETE || !l;
    }

    /** 관리도 종류에 맞게 제목·칸·규칙 상자를 바꾼다 — p 면 R 관리도 숨김, 규칙 2·3 끔(그림 안 함·서버도 거부) @param {boolean} isP */
    function offP(isP) {
        /** @type {HTMLElement} */ (document.getElementById('xHead')).textContent = isP ? hb.t('js.qms.spc.pHead') : hb.t('js.qms.spc.xHead');
        /** @type {HTMLElement} */ (document.getElementById('rHead')).hidden = isP;
        /** @type {HTMLElement} */ (document.getElementById('rChart')).hidden = isP;
        /** @type {HTMLElement} */ (document.getElementById('colMean')).textContent = isP ? hb.t('js.qms.spc.colP') : hb.t('js.qms.spc.colMean');
        /** @type {HTMLElement} */ (document.getElementById('colR')).textContent = isP ? hb.t('js.qms.spc.colD') : hb.t('js.qms.spc.colR');
        ruleBoxes().forEach(function (b) {
            var off = isP && (b.value === 'WE2' || b.value === 'WE3');
            b.disabled = off;
            if (off) { b.checked = false; }
        });
    }

    /** 이 점에 (무효 아닌) 위반 이벤트가 있나 — R 관리도는 R1, X̄ 는 그 밖 @param {SpcPoint} p @param {boolean} range */
    function hasEvent(p, range) {
        return unitEvents.some(function (e) {
            return e.inspLineId === p.inspLineId && e.status !== 'VOID' && (e.ruleCd === 'R1') === range;
        });
    }

    /** 관리도 상세의 이 단위 위반 표 — 규칙 칸 버튼으로 이벤트 상세 */
    function renderUnitEvents() {
        var tbody = /** @type {HTMLElement} */ (document.querySelector('#unitEventTable tbody'));
        tbody.textContent = '';
        unitEvents.forEach(function (e) {
            var tr = hb.el('tr', e.status === 'VOID' ? 'hb-tree-dim' : null);
            tr.appendChild(hb.el('td', null, hb.fmt.datetime(e.occurredAt)));
            tr.appendChild(qmsUi.cell('spcEvent', e.status, eventStatusNames[e.status]));
            var td = hb.el('td');
            var b = /** @type {HTMLButtonElement} */ (hb.el('button', 'hb-btn-ghost', ruleCodeNames[e.ruleCd] || e.ruleCd));
            b.type = 'button';
            b.addEventListener('click', function () { openEvent(e.eventId, true); });
            td.appendChild(b);
            tr.appendChild(td);
            tr.appendChild(hb.el('td', null, e.inspNo));
            tbody.appendChild(tr);
        });
        if (!unitEvents.length) {
            var tr0 = hb.el('tr');
            var td0 = /** @type {HTMLTableCellElement} */ (hb.el('td', 'hb-muted', hb.t('js.qms.spcEvent.none')));
            td0.colSpan = 4;
            tr0.appendChild(td0);
            tbody.appendChild(tr0);
        }
    }

    /** 이벤트 상세 열기 @param {number} id @param {boolean} fromChart 관리도에서 열었으면 돌아가기 버튼 */
    function openEvent(id, fromChart) {
        return hb.latest('event', hb.api(API + '/events/' + id)).then(function (res) {
            var d = /** @type {{event: SpcEvent, acts: SpcAct[], held: boolean}} */ (res.data);
            eventHeld = !!d.held;
            renderEvent(d.event, d.acts, fromChart);
            hb.page.openDetail();
        }, function (err) { if (err.code !== 'STALE') { notify(errText(err), true); } });
    }

    /** @param {SpcEvent} e @param {SpcAct[]} acts @param {boolean} fromChart */
    function renderEvent(e, acts, fromChart) {
        event = e;
        formMode.hidden = true;
        chartBox.hidden = true;
        eventBox.hidden = false;
        /** @type {HTMLElement} */ (document.getElementById('backToChart')).hidden = !fromChart;
        var head = /** @type {HTMLElement} */ (document.getElementById('eventHead'));
        head.textContent = hb.t('js.qms.spcEvent.head', ruleCodeNames[e.ruleCd] || e.ruleCd, e.itemCd, e.charNo + ' ' + e.charNm,
            eventStatusNames[e.status] || e.status);
        head.setAttribute('data-status', e.status === 'CLOSED' || e.status === 'VOID' ? 'OK' : 'NG');
        var info = /** @type {HTMLElement} */ (document.getElementById('eventInfo'));
        info.textContent = '';
        /** @param {string} k @param {string} v */
        function row(k, v) {
            info.appendChild(hb.el('dt', null, k));
            info.appendChild(hb.el('dd', null, v));
        }
        row(hb.t('js.qms.spcEvent.insp'), e.inspNo + ' · ' + e.lotNo + ' · ' + (typeNames[e.inspType] || e.inspType)
            + (e.procCd ? ' · ' + e.procCd : ''));
        row(hb.t('js.qms.spcEvent.occurred'), hb.fmt.datetime(e.occurredAt));
        var zText = e.z === null || e.z === undefined ? '-' : num(e.z, 2);
        row(hb.t('js.qms.spcEvent.point'), e.chartType === 'P' ? hb.t('js.qms.spcEvent.pointValP', num(e.pointMean, 4), String(e.pointRange), e.n, zText)
            : hb.t('js.qms.spcEvent.pointVal', num(e.pointMean), num(e.pointRange), e.n, zText));
        row(hb.t('js.qms.spcEvent.snapshot'), hb.t('js.qms.spcEvent.snapshotVal', num(e.center), num(e.sigma), e.limitId));
        if (eventHeld) { row(hb.t('js.qms.spcEvent.hold'), hb.t('js.qms.spcEvent.holdOn', e.lotNo)); }
        var tbody = /** @type {HTMLElement} */ (document.querySelector('#actTable tbody'));
        tbody.textContent = '';
        acts.forEach(function (a) {
            var tr = hb.el('tr');
            tr.appendChild(hb.el('td', null, actNames[a.actType] || a.actType));
            var noteTd = hb.el('td', 'hb-wrap', a.note || '');
            // 고른 OCAP 항목 — 고른 순간 문구 사본(목록이 바뀌어도 그때 문구, 26장 OC-8)
            (a.ocaps || []).forEach(function (o) { noteTd.appendChild(hb.el('div', 'hb-muted', '✓ ' + o.actionText)); });
            tr.appendChild(noteTd);
            tr.appendChild(hb.el('td', null, a.linkType ? a.linkType + ' ' + a.linkNo : ''));
            tr.appendChild(hb.el('td', null, a.actedBy + ' ' + hb.fmt.datetime(a.actedAt)));
            tbody.appendChild(tr);
        });
        if (!acts.length) {
            var tr0 = hb.el('tr');
            var td0 = /** @type {HTMLTableCellElement} */ (hb.el('td', 'hb-muted', hb.t('js.qms.spcEvent.noActs')));
            td0.colSpan = 4;
            tr0.appendChild(td0);
            tbody.appendChild(tr0);
        }
        /** @type {HTMLFormElement} */ (document.getElementById('actForm')).hidden = !CAN_SAVE || e.status === 'CLOSED' || e.status === 'VOID';
        loadOcaps(e);
        // 사람이 여는 보류 경로(SV-6): 그 검사로 NCR 등록 / 그 품목 봉쇄 — 만든 번호는 돌아와 조치(ACTION)에 연결
        var why = hb.t('js.qms.spcEvent.linkNote', ruleCodeNames[e.ruleCd] || e.ruleCd, e.charNo + ' ' + e.charNm, e.inspNo);
        /** @type {HTMLElement} */ (document.getElementById('eventLinks')).hidden = e.status === 'CLOSED' || e.status === 'VOID';
        /** @type {HTMLAnchorElement} */ (document.getElementById('evNcrLink')).href = '/page/qms-ncr?lotNo=' + encodeURIComponent(e.lotNo)
            + '&inspNo=' + encodeURIComponent(e.inspNo) + '&source=INSP&desc=' + encodeURIComponent(why);
        /** @type {HTMLAnchorElement} */ (document.getElementById('evContainLink')).href = '/page/qms-contain?itemCd=' + encodeURIComponent(e.itemCd)
            + '&reason=' + encodeURIComponent(why);
        // 출하 추적(35장): 위반 LOT 이 들어간 아래 LOT·출하
        /** @type {HTMLAnchorElement} */ (document.getElementById('evTraceLink')).href = '/page/qms-lot-trace?lotNos=' + encodeURIComponent(e.lotNo)
            + '&direction=FORWARD';
    }

    /** 조치 폼의 OCAP 고르기 목록(그 규칙 + 공통) @param {SpcEvent} e */
    function loadOcaps(e) {
        var list = /** @type {HTMLElement} */ (document.getElementById('ocapList'));
        list.textContent = '';
        syncOcapBox();
        if (!CAN_SAVE || e.status === 'CLOSED' || e.status === 'VOID') { return; }
        hb.latest('ocap', hb.api(API + '/events/' + e.eventId + '/ocaps')).then(function (res) {
            /** @type {SpcOcapOption[]} */
            var opts = res.data || [];
            opts.forEach(function (o) {
                var label = hb.el('label', 'hb-check');
                var box = /** @type {HTMLInputElement} */ (hb.el('input'));
                box.type = 'checkbox';
                box.value = String(o.ocapId);
                label.appendChild(box);
                label.appendChild(hb.el('span', null, (o.ruleCd ? '' : hb.t('js.qms.ocap.common') + ' · ') + o.actionText));
                list.appendChild(label);
            });
            if (!opts.length) { list.appendChild(hb.el('p', 'hb-muted', hb.t('js.qms.spcEvent.ocapNone'))); }
        }, function (err) { if (err.code !== 'STALE') { notify(errText(err), true); } });
    }

    /** OCAP 목록은 조치(ACTION)를 고를 때만 */
    function syncOcapBox() {
        var form = /** @type {HTMLFormElement} */ (document.getElementById('actForm'));
        var type = /** @type {HTMLSelectElement} */ (document.getElementById('actType'));
        /** @type {HTMLElement} */ (document.getElementById('ocapBox')).hidden = form.hidden || type.value !== 'ACTION';
    }
    /** @type {HTMLSelectElement} */ (document.getElementById('actType')).addEventListener('change', syncOcapBox);

    /** @param {SpcUnit} u */
    function loadHistory(u) {
        return hb.latest('history', hb.api(API + '/limits?' + unitKey(u))).then(function (res) {
            var tbody = /** @type {HTMLElement} */ (document.querySelector('#historyTable tbody'));
            tbody.textContent = '';
            /** @type {SpcLimit[]} */
            var rows = res.data || [];
            rows.forEach(function (l) {
                var tr = hb.el('tr', l.status === 'VOID' ? 'hb-tree-dim' : null);
                tr.appendChild(qmsUi.cell('live', l.status, statusNames[l.status], l.voidReason ? '(' + l.voidReason + ')' : null));
                tr.appendChild(hb.el('td', 'hb-num', num(l.center)));
                tr.appendChild(hb.el('td', 'hb-num', num(l.sigma)));
                tr.appendChild(hb.el('td', 'hb-num', String(l.subgroupCnt)));
                tr.appendChild(hb.el('td', null, l.rules.replace(/WE/g, '').replace(/,/g, '·')));
                tr.appendChild(hb.el('td', null, l.createdBy + ' ' + hb.fmt.datetime(l.createdAt)));
                tbody.appendChild(tr);
            });
            if (!rows.length) {
                var tr0 = hb.el('tr');
                var td0 = /** @type {HTMLTableCellElement} */ (hb.el('td', 'hb-muted', hb.t('js.qms.spc.noHistory')));
                td0.colSpan = 6;
                tr0.appendChild(td0);
                tbody.appendChild(tr0);
            }
        }, function (err) { if (err.code !== 'STALE') { notify(err.message, true); } });
    }

    /** 조회 패널(품목·특성 한 칸, UI 단계 3 — ?q.keyword= 주소로도 연다) @type {HB.SearchPanel} */
    var sp;
    /** @type {HTMLFormElement} */ (document.getElementById('optForm')).addEventListener('submit', function (ev) {
        ev.preventDefault();
        notify('');
        load(false);
    });
    /** @type {HTMLFormElement} */ (document.getElementById('actForm')).addEventListener('submit', function (ev) {
        ev.preventDefault();
        if (!event) { return; }
        var e = event;
        var type = /** @type {HTMLSelectElement} */ (document.getElementById('actType'));
        var note = /** @type {HTMLInputElement} */ (document.getElementById('actNote'));
        var linkType = /** @type {HTMLSelectElement} */ (document.getElementById('actLinkType'));
        var linkNo = /** @type {HTMLInputElement} */ (document.getElementById('actLinkNo'));
        var ocapIds = type.value !== 'ACTION' ? [] : Array.prototype.map.call(document.querySelectorAll('#ocapList input:checked'),
            /** @param {HTMLInputElement} x */ function (x) { return Number(x.value); });
        var body = {actType: type.value, note: note.value, linkType: linkType.value || null, linkNo: linkNo.value || null,
            ocapIds: ocapIds.length ? ocapIds : null};
        // 종결·무효는 이 위반 근거의 LOT 보류를 푼다(SV-6, 다른 근거가 남으면 LOT 은 계속 보류)
        var holdNote = eventHeld ? hb.t('js.qms.spcEvent.releaseHold', e.lotNo) : null;
        var go = type.value === 'VOID' ? qmsUi.ask('voidRec', holdNote) : type.value === 'CLOSE' ? qmsUi.ask('close', holdNote) : Promise.resolve(true);
        go.then(function (ok) {
            if (!ok) { return null; }
            return hb.api(API + '/events/' + e.eventId + '/acts', {method: 'POST', body: body}).then(function () {
                note.value = '';
                linkType.value = '';
                linkNo.value = '';
                notify(hb.t('js.qms.spcEvent.saved'));
                var back = !/** @type {HTMLElement} */ (document.getElementById('backToChart')).hidden;
                return Promise.all([openEvent(e.eventId, back), view === 'events' && eventGrid ? eventGrid.reload(false, e.eventId) : null]);
            }, function (err) { notify(errText(err), true); });
        });
    });
    /** @type {HTMLButtonElement} */ (document.getElementById('backToChart')).addEventListener('click', function () {
        eventBox.hidden = true;
        load(false);
    });
    document.querySelectorAll('#viewSeg input[name=view]').forEach(function (r) {
        r.addEventListener('change', function () {
            var v = /** @type {HTMLInputElement} */ (r);
            if (v.checked) { setView(v.value); }
        });
    });
    /** @type {HTMLFormElement} */ (document.getElementById('fixForm')).addEventListener('submit', function (ev) {
        ev.preventDefault();
        if (!unit || !chart) { return; }
        var u = unit;
        var n = /** @type {HTMLInputElement} */ (document.getElementById('optN')).value;
        var to = /** @type {HTMLInputElement} */ (document.getElementById('optTo')).value;
        var remark = /** @type {HTMLInputElement} */ (document.getElementById('fixRemark'));
        hb.api(API + '/limits', {method: 'POST', body: {itemId: u.itemId, charId: u.charId, inspType: u.inspType, procId: u.procId || null,
            n: n ? Number(n) : null, to: to || null, rules: chart.rules, remark: remark.value}}).then(function () {
            remark.value = '';
            notify(hb.t('js.qms.spc.saved'));
            return Promise.all([load(true), loadUnits()]);
        }, function (err) { notify(errText(err), true); });
    });
    /** @type {HTMLFormElement} */ (document.getElementById('voidForm')).addEventListener('submit', function (ev) {
        ev.preventDefault();
        var l = chart && chart.limit;
        if (!l) { return; }
        var reason = /** @type {HTMLInputElement} */ (document.getElementById('voidReason'));
        hb.api(API + '/limits/' + l.limitId + '/void', {method: 'POST', body: {rowVer: l.rowVer, reason: reason.value}}).then(function () {
            reason.value = '';
            notify(hb.t('js.qms.spc.voided'));
            return Promise.all([load(true), loadUnits()]);
        }, function (err) { notify(errText(err), true); });
    });

    hb.loadMessages().then(function () {
        hb.page.init();
        sp = hb.searchPanel(/** @type {HTMLFormElement} */ (document.getElementById('search')), {onSearch: function () {
            var g = view === 'events' ? eventGrid : grid;
            return g ? g.reload(true) : null;
        }, onLink: function (q) {
            // 이미 열린 탭에 홈 카드 링크(view = 조회 패널 밖 키, docs/29_P3 42장) — 보기가 다르면 바꾸고 setView 가 조회한다
            if ((q.view === 'events' || q.view === 'units') && q.view !== view) {
                pickView(q.view).then(null, function (err) { notify(errText(err), true); });
                return true;
            }
            return false;
        }});
        return Promise.all([hb.codes('INSP_TYPE'), hb.codes('QMS_SPC_STATUS'), hb.loadSession(), sp.ready, hb.codes('QMS_SPC_EVENT_STATUS'),
            hb.codes('QMS_SPC_ACT'), hb.codes('QMS_SPC_RULE')]);
    }).then(function (r) {
        typeNames = r[0];
        statusNames = r[1];
        eventStatusNames = r[4];
        actNames = r[5];
        ruleCodeNames = r[6];
        ruleNames = {WE1: hb.t('js.qms.spc.rule.WE1'), WE2: hb.t('js.qms.spc.rule.WE2'), WE3: hb.t('js.qms.spc.rule.WE3'),
            WE4: hb.t('js.qms.spc.rule.WE4'), R1: hb.t('js.qms.spc.rule.R1')};
        sourceNames = {FIXED: hb.t('js.qms.spc.source.FIXED'), CALC: hb.t('js.qms.spc.source.CALC')};
        // 홈 카드 "미처리 SPC 위반" 에서: ?q.view=events 면 위반 보기로 연다(상태 칸은 조회 패널이 ?q.status= 로 채운다)
        if (new URLSearchParams(window.location.search).get('q.view') === 'events') {
            return startGrid().then(function () { return pickView('events'); });
        }
        return startGrid().then(function () { return sp.search(); });
    }).then(null, function (err) { notify(err && err.message, true); });
})();
