// @ts-check
/*
 * MES 화면 공용 표시 (UI 단계 3 ②~⑥, docs/40 부록 A·V-7, docs/25 4.29) — 공통(hb.*)이 아니라 MES·영업·MES 몫 기준정보 화면 전용.
 * CMS 의 cms-ui.js 와 같은 모양(모듈 경계 — 서로 싣지 않는다).
 *
 *   mesUi.leaveOk()                          → Promise<boolean>  저장 안 한 입력이 있으면 화면 안 대화상자로 묻는다
 *   mesUi.ask(titleKey, message, okKey, danger) → Promise<boolean>  확인 대화상자(제목·버튼 = 동사)
 *   mesUi.reason(titleKey, labelKey, okKey, required) → Promise<string|null>  사유 입력(500자, required 기본 true)
 *   mesUi.badge(kind, code, text, title)     → <span class="hb-status">  상태 배지(톤은 아래 표)
 *
 * 톤 표 = MES 상태 의미(부록 A.3 을 MES 코드에 적용). 색은 정하지 않고 톤만 — 값은 tokens.css.
 */
(function () {
    'use strict';

    /** @type {Object<string, Object<string, HB.StatusTone>>} */
    var TONES = {
        // 작업지시 WO_STATUS — A.3 그대로(취소는 사용자가 고른 결과라 빨강 아님)
        wo: {PLANNED: 'neutral', RELEASED: 'neutral', IN_PROGRESS: 'normal', DONE: 'success', CLOSED: 'success', CANCELLED: 'neutral'},
        // 지시 공정 WO_PROC_STATUS
        proc: {WAIT: 'neutral', READY: 'neutral', RUN: 'normal', PAUSE: 'warning', DONE: 'success'},
        // 수주 머리 SAL_ORDER_STATUS · 행 SAL_LINE_STATUS — 열림 = 진행(색 없음)
        order: {OPEN: 'normal', CLOSED: 'success', CANCELLED: 'neutral'},
        line: {OPEN: 'normal', DONE: 'success', CANCELLED: 'neutral'},
        // LOT 보류 — 확인 필요(주의). 보류 아님은 배지 없음
        hold: {Y: 'warning'},
        // 작업자 자격 상태 — 유효 = 정상, 시작 전 = 안내, 만료 = 확인 필요, 회수·없음 = 사람이 고른 결과(빨강 아님)
        qual: {VALID: 'normal', NOT_YET: 'info', EXPIRED: 'warning', REVOKED: 'neutral', NONE: 'neutral'},
        // 사용 여부 — 사용 = 정상(색 없음), 사용 중지 = 할 일 없음
        use: {Y: 'normal', N: 'neutral'}
    };

    function leaveOk() {
        if (!hb.dirty.isDirty()) { return Promise.resolve(true); }
        return hb.dialog.confirm({title: hb.t('js.mes.dlg.leave'), message: hb.t('js.common.confirmLeave'), ok: hb.t('js.mes.dlg.leaveOk')});
    }

    /**
     * @param {string} titleKey @param {string|null} message @param {string} okKey @param {boolean=} danger
     * @returns {Promise<boolean>}
     */
    function ask(titleKey, message, okKey, danger) {
        return hb.dialog.confirm({title: hb.t(titleKey), message: message || null, ok: hb.t(okKey), danger: !!danger});
    }

    /**
     * @param {string} titleKey @param {string} labelKey @param {string} okKey @param {boolean=} required
     * @returns {Promise<string|null>}
     */
    function reason(titleKey, labelKey, okKey, required) {
        return hb.dialog.prompt({title: hb.t(titleKey), label: hb.t(labelKey), ok: hb.t(okKey), required: required !== false, maxLength: 500});
    }

    /** @param {string} kind @param {string} code @param {string=} text @param {string=} title */
    function badge(kind, code, text, title) {
        var tone = (TONES[kind] && TONES[kind][code]) || 'neutral';
        return hb.status(tone, text || code || '', title || null);
    }

    window.mesUi = {leaveOk: leaveOk, ask: ask, reason: reason, badge: badge, TONES: TONES};
})();
