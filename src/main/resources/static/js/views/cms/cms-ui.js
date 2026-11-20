/*
 * CMS 화면 공용 표시 (UI 단계 3, docs/40 부록 A·V-7, docs/33 15장) — 공통(hb.*)이 아니라 CMS 화면 전용.
 *
 *   cmsUi.leaveOk()                       → Promise<boolean>  저장 안 한 입력이 있으면 화면 안 대화상자로 묻는다
 *   cmsUi.ask(titleKey, message, okKey, danger) → Promise<boolean>  확인 대화상자(제목·버튼 = 동사)
 *   cmsUi.reason(titleKey, labelKey, okKey, danger) → Promise<string|null>  사유 입력(필수, 500자). 취소·무효·건너뛰기 = danger(위험 톤, 기본 포커스 취소)
 *   cmsUi.badge(kind, code, text, title)  → <span class="hb-status">  상태 배지(톤은 아래 표)
 *   cmsUi.overdue(text)                   → 지연 배지(danger)
 *
 * 톤 표 = CMS 상태 의미(부록 A 의 규칙을 CMS 코드에 적용). 색은 정하지 않고 톤만 — 값은 tokens.css.
 */
(function () {
    'use strict';

    /** @type {Object<string, Object<string, string>>} */
    var TONES = {
        // 보전 작업지시 CMS_MWO_STATUS — A.3 작업 상태와 같은 규칙(취소는 사용자가 고른 결과라 빨강 아님)
        mwo: {REQ: 'neutral', OPEN: 'neutral', WIP: 'normal', DONE: 'success', CLOSED: 'success', CANCELLED: 'neutral'},
        // 점검 실행 CMS_RUN_STATUS
        run: {OPEN: 'normal', DONE: 'success', SKIPPED: 'neutral'},
        // 예정 상태(점검 계획·실행) — 기한 임박 = 주의, 지남 = 지연
        due: {UPCOMING: 'neutral', DUE: 'warning', OVERDUE: 'danger'},
        // 위험성평가 CMS_RA_STATUS
        ra: {DRAFT: 'neutral', IN_APPROVAL: 'info', RELEASED: 'success', OBSOLETE: 'neutral'},
        // 위험성 수준 CMS_RISK_LEVEL
        risk: {HIGH: 'danger', MEDIUM: 'warning', LOW: 'neutral'},
        // 알람 중대도 — 알람 4단(A.5): 중대 = P1, 경고 = P2, 정보 = P4. 알람 화면 안에서만 쓴다
        severity: {CRITICAL: 'alarm-1', WARN: 'alarm-2', INFO: 'alarm-4'},
        // 설비 상태 STATE_CAT — 사무 화면 표현(A.2): 가동 초록·대기 호박·엔지니어링·계획 정지 파랑 계열·비계획 정지 빨강·비가동·통신 끊김 회색
        stateCat: {PRD: 'success', SBY: 'warning', ENG: 'info', SDT: 'info', UDT: 'danger', NST: 'neutral', UNKNOWN: 'neutral'}
    };

    function leaveOk() {
        if (!hb.dirty.isDirty()) { return Promise.resolve(true); }
        return hb.dialog.confirm({title: hb.t('js.cms.dlg.leave'), message: hb.t('js.common.confirmLeave'), ok: hb.t('js.cms.dlg.leaveOk')});
    }

    function ask(titleKey, message, okKey, danger) {
        return hb.dialog.confirm({title: hb.t(titleKey), message: message || null, ok: hb.t(okKey), danger: !!danger});
    }

    function reason(titleKey, labelKey, okKey, danger) {
        return hb.dialog.prompt({title: hb.t(titleKey), label: hb.t(labelKey), ok: hb.t(okKey), required: true, maxLength: 500, danger: !!danger});
    }

    function badge(kind, code, text, title) {
        var tone = (TONES[kind] && TONES[kind][code]) || 'neutral';
        return hb.status(/** @type {any} */ (tone), text || code || '', title || null);
    }

    function overdue(text) {
        return hb.status('danger', text || hb.t('js.cms.overdue'));
    }

    /** 표 한 줄의 칸들 — 값이 요소(배지 등)면 그대로, 아니면 글자(textContent) */
    function cells(tr, values) {
        values.forEach(function (v) {
            var td = hb.el('td');
            if (v instanceof Node) { td.appendChild(v); } else { td.textContent = v === null || v === undefined ? '' : String(v); }
            tr.appendChild(td);
        });
    }

    /** 글자 + 배지(사이 띄움) 한 칸. after = 배지 뒤에 붙일 글자(취소 사유 등) */
    function withBadge(text, badgeEl, after) {
        var f = document.createDocumentFragment();
        if (text) { f.appendChild(document.createTextNode(text + ' ')); }
        if (badgeEl) { f.appendChild(badgeEl); }
        if (after) { f.appendChild(document.createTextNode(' ' + after)); }
        return f;
    }

    window.cmsUi = {leaveOk: leaveOk, ask: ask, reason: reason, badge: badge, overdue: overdue, cells: cells, withBadge: withBadge, TONES: TONES};
})();
