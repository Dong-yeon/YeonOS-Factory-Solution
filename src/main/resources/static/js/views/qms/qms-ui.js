// @ts-check
/*
 * QMS 화면 공용 표시 (UI 단계 3, docs/40 부록 A·V-7, docs/29_P3 15장) — 공통(hb.*)이 아니라 QMS 화면 전용.
 *
 *   qmsUi.leaveOk(dirty)            → Promise<boolean>        저장 안 한 입력이 있으면 화면 안 대화상자로 묻는다
 *   qmsUi.ask(kind, message)        → Promise<boolean>        확인 대화상자 — 제목·확정 버튼(동사)·위험 톤은 아래 동작 표
 *   qmsUi.reason(kind, label)       → Promise<string|null>    사유 입력(필수, 500자) — 취소 = null(아무 일 없음)
 *   qmsUi.badge(kind, code, text)   → <span class="hb-status">  상태 배지(톤은 아래 톤 표)
 *   qmsUi.cell(kind, code, text, after) → <td> 배지 한 칸(after = 배지 뒤 글자, 무효 사유 등). 값이 없으면 빈 칸
 *   qmsUi.TONES                     → 그리드 GridOptions.status 에도 그대로 쓴다
 *
 * 톤 표 = QMS 상태 의미(부록 A 규칙을 QMS 코드에 적용) — 색은 정하지 않고 톤만(값은 tokens.css).
 * 취소·무효는 사용자가 고른 결과라 빨강이 아니라 neutral(A.3), 판정 NG·기한 지남만 danger.
 */
/**
 * QMS 화면 공용 도구 — 각 화면은 `var qmsUi = /** @type {QmsUi} *\/ (/** @type {any} *\/ (window).qmsUi);` 로 받는다
 * @typedef {{
 *   leaveOk: (dirty: boolean) => Promise<boolean>,
 *   ask: (kind: string, message?: string | null) => Promise<boolean>,
 *   reason: (kind: string, label: string) => Promise<string | null>,
 *   badge: (kind: string, code: string | null | undefined, text?: string | null, title?: string | null) => HTMLSpanElement,
 *   cell: (kind: string, code: string | null | undefined, text?: string | null, after?: string | null) => HTMLTableCellElement,
 *   TONES: Record<string, Record<string, HB.StatusTone>>
 * }} QmsUi
 */
(function () {
    'use strict';

    var hb = window.hb;

    /** @type {Record<string, Record<string, HB.StatusTone>>} */
    var TONES = {
        // 부적합 QMS_NCR_STATUS — 처리 중 = 진행, 특채 결재 중 = 결재(info), 종결 = 완료
        ncr: {OPEN: 'normal', PENDING: 'info', CLOSED: 'success', CANCELLED: 'neutral'},
        // 봉쇄 QMS_CONTAIN_STATUS
        contain: {OPEN: 'warning', CLOSED: 'success'},
        // 검사 상태 QMS_INSP_STATUS / 판정 QMS_INSP_RESULT
        insp: {OPEN: 'normal', JUDGED: 'success', VOID: 'neutral'},
        result: {OK: 'success', NG: 'danger'},
        // 초품 판정(셋업 검증) 결과 — 미판정 = 주의, 무효 QMS_SETUP_STATUS.VOID
        setup: {OK: 'success', NG: 'danger', PENDING: 'warning', VOID: 'neutral'},
        // 시정조치(8D) 상태 CAPA_STATUS — 단계 진행 중 = 진행, 종결 결재 = info
        capa: {OPEN: 'normal', CONTAINED: 'normal', ANALYZED: 'normal', ACTIONED: 'normal', VERIFIED: 'normal', CLOSING: 'info',
            CLOSED: 'success', CANCELLED: 'neutral'},
        // 기한 상태 CAPA_DUE_STATE(시정조치) — 임박 = 주의, 지남 = 지연
        due: {ON_TIME: 'neutral', CONTAIN_OVERDUE: 'danger', CLOSE_OVERDUE: 'danger'},
        // 문서 리비전 DOC_REV_STATUS · 검사계획 BOM_STATUS(같은 수명주기)
        rev: {DRAFT: 'neutral', IN_APPROVAL: 'info', RELEASED: 'success', OBSOLETE: 'neutral'},
        // 계측기 교정 판정 GAUGE_STATUS / 교정 결과 CALIB_RESULT
        gauge: {OK: 'success', DUE_SOON: 'warning', OVERDUE: 'danger', NG: 'danger', NO_RECORD: 'warning', INACTIVE: 'neutral'},
        calib: {OK: 'success', LIMITED: 'warning', NG: 'danger'},
        // MSA 연구 MSA_STATUS / 판정 MSA_VERDICT
        msa: {DRAFT: 'neutral', DONE: 'success', CANCELLED: 'neutral'},
        msaVerdict: {ACCEPT: 'success', CONDITIONAL: 'warning', REJECT: 'danger'},
        // 기록 유효·무효(초품 판정 QMS_SETUP_STATUS, SPC 관리한계 QMS_SPC_STATUS)
        live: {ACTIVE: 'normal', VOID: 'neutral'},
        // SPC 위반 QMS_SPC_EVENT_STATUS — 미확인 = 위험, 처리 중 = 주의
        spcEvent: {OPEN: 'danger', ACKED: 'warning', CLOSED: 'success', VOID: 'neutral'},
        // PLM 설계 변경 PLM_ECO_STATUS(4M 변경 목록에 보이는 ECO 상태)
        eco: {DRAFT: 'neutral', PENDING: 'info', APPROVED: 'info', RELEASED: 'success', CANCELLED: 'neutral'},
        // 사용 여부
        useYn: {Y: 'normal', N: 'neutral'}
    };

    /** 동작 표 — [제목, 확정 버튼, 위험] (문구 키는 이어 붙이지 않고 그대로 — I18nKeyGuardTest) @returns {Record<string, [string, string, boolean]>} */
    function acts() {
        return {
            leave: [hb.t('js.qms.dlg.leave'), hb.t('js.qms.dlg.leaveOk'), false],
            fileDelete: [hb.t('js.qms.dlg.fileDelete'), hb.t('js.qms.dlg.fileDeleteOk'), true],
            release: [hb.t('js.qms.dlg.release'), hb.t('js.qms.dlg.releaseOk'), false],
            obsolete: [hb.t('js.qms.dlg.obsolete'), hb.t('js.qms.dlg.obsoleteOk'), true],
            submit: [hb.t('js.qms.dlg.submit'), hb.t('js.qms.dlg.submitOk'), false],
            close: [hb.t('js.qms.dlg.close'), hb.t('js.qms.dlg.closeOk'), false],
            cancel: [hb.t('js.qms.dlg.cancel'), hb.t('js.qms.dlg.cancelOk'), true],
            voidRec: [hb.t('js.qms.dlg.void'), hb.t('js.qms.dlg.voidOk'), true],
            advance: [hb.t('js.qms.dlg.advance'), hb.t('js.qms.dlg.advanceOk'), false],
            judge: [hb.t('js.qms.dlg.judge'), hb.t('js.qms.dlg.judgeOk'), false],
            complete: [hb.t('js.qms.dlg.complete'), hb.t('js.qms.dlg.completeOk'), false],
            remove: [hb.t('js.qms.dlg.remove'), hb.t('js.qms.dlg.removeOk'), true],
            newRev: [hb.t('js.qms.dlg.newRev'), hb.t('js.qms.dlg.newRevOk'), false]
        };
    }

    /** @param {string} kind @param {string | null} [message] @returns {Promise<boolean>} */
    function ask(kind, message) {
        var a = acts()[kind];
        return hb.dialog.confirm({title: a[0], message: message || null, ok: a[1], danger: a[2]});
    }

    /** @param {string} kind @param {string} label @returns {Promise<string | null>} */
    function reason(kind, label) {
        var a = acts()[kind];
        return hb.dialog.prompt({title: a[0], label: label, ok: a[1], danger: a[2], required: true, maxLength: 500});
    }

    /** @param {boolean} dirty @returns {Promise<boolean>} */
    function leaveOk(dirty) {
        return dirty ? ask('leave', hb.t('js.common.confirmLeave')) : Promise.resolve(true);
    }

    /** @param {string} kind @param {string | null | undefined} code @param {string | null | undefined} [text] @param {string | null} [title] */
    function badge(kind, code, text, title) {
        var tone = (TONES[kind] && code && TONES[kind][code]) || 'neutral';
        return hb.status(tone, text || code || '', title || null);
    }

    /** @param {string} kind @param {string | null | undefined} code @param {string | null} [text] @param {string | null} [after] */
    function cell(kind, code, text, after) {
        var td = /** @type {HTMLTableCellElement} */ (hb.el('td'));
        if (code) { td.appendChild(badge(kind, code, text)); }
        if (after) { td.appendChild(document.createTextNode(' ' + after)); }
        return td;
    }

    /** @type {any} */ (window).qmsUi = {leaveOk: leaveOk, ask: ask, reason: reason, badge: badge, cell: cell, TONES: TONES};
})();
