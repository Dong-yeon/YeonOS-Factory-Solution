// @ts-check
/*
 * hb.gate — "이동해도 되는지" 확인 결과로 진행한다 (hb.grid·hb.tree 의 beforeSelect, docs/40 단계 3)
 *   hb.gate(beforeSelect(row), function () { … 실제 이동 … });
 * - 결과가 boolean·없음이면 바로(기존 동기 동작 그대로): false 만 멈춤
 * - Promise(예: hb.dialog.confirm) 면 결과를 기다려 false 가 아니면 진행. 거부(reject)도 멈춤 — 묻다가 실패하면 이동하지 않는다
 * - 돌려주는 값: 동기면 진행했는지(boolean), 비동기면 Promise<진행했는지> — 부르는 쪽이 대기 중 다른 이동을 막는 데 쓴다
 * DOM 없는 순수 함수 — node 단위 시험(src/test/js/hb.gate.test.js)
 */
(function (root) {
    'use strict';

    /**
     * @param {boolean | void | null | PromiseLike<boolean | void>} result
     * @param {() => void} proceed
     * @returns {boolean | Promise<boolean>}
     */
    function gate(result, proceed) {
        if (result && typeof (/** @type {PromiseLike<unknown>} */ (result)).then === 'function') {
            return Promise.resolve(result).then(function (ok) {
                if (ok === false) { return false; }
                proceed();
                return true;
            }, function () { return false; });
        }
        if (result === false) { return false; }
        proceed();
        return true;
    }

    if (typeof module === 'object' && module.exports) {
        module.exports = {gate: gate};
        return;
    }
    /** @type {HB.Hb} */ (/** @type {any} */ (root).hb).gate = gate;
})(typeof window !== 'undefined' ? window : globalThis);
