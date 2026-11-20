// @ts-check
/* 창고 PDA 정위치 점검 흐름 (docs/55) — 화면(pda-home.js)과 떼어 단위 시험한다(src/test/js/pda-loccheck.test.js).
   온라인만(상위 검토 2026-10-29): 판정이 "서버가 받은 때의 장부" 라 오프라인에 쌓았다 늦게 보내면 판정·스냅숏이 조용히 틀린다 —
   그래서 대기열(hb.pop.queue)을 쓰지 않고, 오프라인이면 요청을 보내지 않는다(버튼도 비활성 — canAct). 요청 id 는 스캔마다(응답이 끊겨 같은 것을
   다시 보내면 서버가 같은 줄). 위치 스캔 = 시작(오늘 그 칸 점검을 만들거나 이어 쓰기), 그다음 스캔 = 물건 한 줄, [전수 확인 완료] = 없음 채움. */
(function (global) {
    'use strict';

    var API = '/api/pop/wh/loc-checks';
    /** @type {Record<string, string>} */
    var RESULT = {OK: 'js.pdaWh.lcResult.OK', MISPLACED: 'js.pdaWh.lcResult.MISPLACED', MISSING: 'js.pdaWh.lcResult.MISSING',
        UNKNOWN: 'js.pdaWh.lcResult.UNKNOWN'};

    /**
     * @param {{post: (url: string, body: any) => Promise<any>, online: () => boolean, render: (view: any) => void,
     *   feedback: (kind: string, key: string, args?: any[]) => void, newReqId: () => string, now: () => string}} d
     */
    function makeLocCheck(d) {
        /** @type {any} */
        var view = null;

        function canAct() {
            return d.online() && !!view && view.head.status === 'OPEN';
        }

        function offline() {
            d.feedback('error', 'js.pdaWh.lcOffline');
            return Promise.resolve(null);
        }

        /** 날짜가 지난 열린 점검(서버 409 WMS_LOC_CHECK_STALE, L-1)은 버린다 — 다음 위치 스캔이 오늘 점검을 연다 @param {any} err */
        function fail(err) {
            if (err && err.code === 'WMS_LOC_CHECK_STALE') {
                view = null;
                d.render(null);
            }
            d.feedback('error', '', [err]);
            return null;
        }

        /** 위치 스캔 → 시작 @param {string} text */
        function start(text) {
            if (!d.online()) { return offline(); }
            return d.post(API + '/scan', {text: text}).then(function (res) {
                view = res.data;
                d.render(view);
                d.feedback('ok', 'js.pdaWh.lcStarted', [view.head.path]);
                return view;
            }, fail);
        }

        /** 물건 스캔 한 줄(점검 중이 아니면 위치로 보고 시작) @param {string} text */
        function scan(text) {
            if (!view || view.head.status !== 'OPEN') { return start(text); }
            if (!d.online()) { return offline(); }
            var id = view.head.checkId;
            return d.post(API + '/' + id + '/lines', {text: text, clientReqId: d.newReqId(), scannedAt: d.now()}).then(function (res) {
                var r = res.data;
                view.head = r.head;
                var i = view.lines.findIndex(function (/** @type {any} */ l) { return l.lineId === r.line.lineId; });
                if (i < 0) { view.lines.push(r.line); }
                d.render(view);
                d.feedback(r.line.result === 'OK' ? 'ok' : 'error', r.duplicate ? 'js.pdaWh.lcDuplicate' : (RESULT[r.line.result] || RESULT.UNKNOWN),
                    [r.line.itemCd || r.line.scanCode || '']);
                return r;
            }, fail);
        }

        /** 전수 확인 완료 */
        function done() {
            if (!view) { return Promise.resolve(null); }
            if (!d.online()) { return offline(); }
            return d.post(API + '/' + view.head.checkId + '/done', {}).then(function (res) {
                view = res.data;
                d.render(view);
                d.feedback(view.head.accurateYn === 'Y' ? 'ok' : 'error', 'js.pdaWh.lcDone', [view.head.missingCnt || 0]);
                return view;
            }, fail);
        }

        function reset() {
            view = null;
            d.render(null);
        }

        return {start: start, scan: scan, done: done, reset: reset, canAct: canAct, view: function () { return view; }};
    }

    if (typeof module !== 'undefined' && module.exports) {
        module.exports = {makeLocCheck: makeLocCheck};
    } else {
        /** @type {any} */ (global).wpdaLocCheck = makeLocCheck;
    }
})(typeof window !== 'undefined' ? window : globalThis);
