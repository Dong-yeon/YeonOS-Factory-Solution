// @ts-check
/*
 * 시스템관리·플랫폼 화면 공용 표시 (UI 단계 3, docs/40 부록 A·V-7, docs/36 17장) — 공통(hb.*)이 아니라 시스템관리 화면 전용.
 *
 *   sysUi.leaveOk()                                → Promise<boolean>  저장 안 한 입력이 있으면 화면 안 대화상자로 묻는다
 *   sysUi.beforeSelect()                           → true | Promise<boolean>  hb.grid·hb.tree beforeSelect 용(입력이 없으면 바로 true — 클릭이 기다리지 않게)
 *   sysUi.ask(titleKey, message, okKey, danger)    → Promise<boolean>  확인 대화상자(제목·버튼 = 동사)
 *   sysUi.reason(titleKey, labelKey, okKey)        → Promise<string|null>  사유 입력(필수, 500자)
 *   sysUi.badge(kind, code, text, title)           → <span class="hb-status">  상태 배지(톤은 아래 표, 모르는 코드는 neutral)
 *   sysUi.TONES[kind]                              → hb.grid status 옵션에 그대로 넘기는 값 → 톤 표
 *   sysUi.tableFind(formId, tbody)                 빠른 찾기 1칸(건수 적은 표 목록, docs/40 P1 ②) — 입력마다 행을 거른다, 표를 다시 그려도 유지
 *   sysUi.treeFind(formId, tree, fields)           빠른 찾기 1칸(트리) — Enter = 다음 맞는 행으로(미저장이면 먼저 묻는다)
 *
 * 톤 표 = 시스템관리 상태 의미(부록 A 규칙을 이 화면들의 코드에 적용). 색은 정하지 않고 톤만 — 값은 tokens.css.
 */
(function () {
    'use strict';

    /** @type {Record<string, Record<string, HB.StatusTone>>} */
    var TONES = {
        // 사용 여부 — 사용 = 정상(색 없음), 미사용 = 할 일 없음
        use: {Y: 'normal', N: 'neutral'},
        // 계정 잠금 USER_LOCK_STATE — 실패 잠금은 15분 뒤 풀리는 주의, 관리자 잠금은 정지
        lock: {NONE: 'normal', FAIL: 'warning', MANUAL: 'danger'},
        // 회사 COMP_STATUS — 일시 정지 = 정지, 해지 = 끝남
        comp: {ACTIVE: 'normal', SUSPENDED: 'danger', CLOSED: 'neutral'},
        // 의견 FEEDBACK_STATUS — 새 의견 = 볼 것 있음
        feedback: {NEW: 'info', SEEN: 'neutral', DONE: 'success'},
        // 공지 NOTICE_STATE — 게시 중 = 진행, 예약 = 볼 것 있음
        notice: {SCHEDULED: 'info', POSTED: 'normal', ENDED: 'neutral', OFF: 'neutral'},
        // 로그 등급 LOG_SEVERITY — 알람 색(alarm-*)은 알람 화면 밖에서 쓰지 않는다
        severity: {ALERT: 'danger', WARN: 'warning', INFO: 'neutral'},
        // 로그 결과 LOG_RESULT
        result: {OK: 'success', FAIL: 'error', DENIED: 'danger'},
        // 결재 요청 — 작업 상태(A.3)와 같은 규칙: 진행 = normal, 반려 = danger, 회수 = 사용자가 고른 결과라 neutral
        apr: {PENDING: 'normal', APPROVED: 'success', REJECTED: 'danger', WITHDRAWN: 'neutral'},
        // 결재선 한 줄 — 지금 차례 = 볼 것 있음
        aprLine: {WAIT: 'neutral', CURRENT: 'info', APPROVED: 'success', REJECTED: 'danger', SKIPPED: 'neutral', REF: 'neutral'},
        // 결재 위임 — 진행 중 = 정상, 예약 = 볼 것 있음
        deleg: {ACTIVE: 'normal', SCHEDULED: 'info', ENDED: 'neutral', REVOKED: 'neutral'},
        // 운영 점검 결과
        health: {OK: 'success', WARN: 'warning'}
    };

    function leaveOk() {
        if (!hb.dirty.isDirty()) { return Promise.resolve(true); }
        return hb.dialog.confirm({title: hb.t('js.common.leaveTitle'), message: hb.t('js.common.confirmLeave'), ok: hb.t('js.common.leaveOk')});
    }

    function beforeSelect() { return hb.dirty.isDirty() ? leaveOk() : true; }

    /** @param {string} titleKey @param {string | null} message @param {string} okKey @param {boolean} [danger] */
    function ask(titleKey, message, okKey, danger) {
        return hb.dialog.confirm({title: hb.t(titleKey), message: message || null, ok: hb.t(okKey), danger: !!danger});
    }

    /** @param {string} titleKey @param {string} labelKey @param {string} okKey */
    function reason(titleKey, labelKey, okKey) {
        return hb.dialog.prompt({title: hb.t(titleKey), label: hb.t(labelKey), ok: hb.t(okKey), required: true, maxLength: 500});
    }

    /** @param {string} q @param {string} text */
    function hit(q, text) { return !q || text.toLowerCase().indexOf(q) >= 0; }

    /** @param {string} formId @return {HTMLInputElement | null} */
    function findInput(formId) {
        var form = document.getElementById(formId);
        if (!(form instanceof HTMLFormElement)) { return null; }
        form.addEventListener('submit', function (e) { e.preventDefault(); });
        return /** @type {HTMLInputElement | null} */ (form.querySelector('input'));
    }

    /** 표 빠른 찾기 — 행 글자에 넣은 글자가 있으면 보인다(대소문자 무시). 표를 다시 그리면(조회·저장) 다시 거른다 @param {string} formId @param {HTMLElement} tbody */
    function tableFind(formId, tbody) {
        var input = findInput(formId);
        if (!input) { return; }
        var box = input;
        function apply() {
            var q = box.value.trim().toLowerCase();
            tbody.querySelectorAll('tr').forEach(function (tr) {
                if (tr.hasAttribute('data-empty')) { return; }
                /** @type {HTMLElement} */ (tr).hidden = !hit(q, tr.textContent || '');
            });
        }
        box.addEventListener('input', apply);
        new MutationObserver(function () { if (box.value) { apply(); } }).observe(tbody, {childList: true});
    }

    /**
     * 트리 빠른 찾기 — Enter 마다 지금 고른 행 다음의 맞는 행으로(끝이면 처음부터). 펼침·고르기는 hb.tree.selectWhere
     * @param {string} formId @param {HB.Tree} tree @param {string[]} fields 찾을 필드(코드·이름)
     */
    function treeFind(formId, tree, fields) {
        var input = findInput(formId);
        if (!input) { return; }
        var box = input;
        /** @param {HB.Row} row @param {string} q */
        function match(row, q) { return fields.some(function (f) { return hit(q, String(row[f] == null ? '' : row[f])); }); }
        box.addEventListener('keydown', function (e) {
            if (e.key !== 'Enter' || e.isComposing) { return; }
            e.preventDefault();
            var q = box.value.trim().toLowerCase();
            if (!q) { return; }
            leaveOk().then(function (ok) {
                if (!ok) { return; }
                var cur = tree.selected();
                var passed = !cur;
                var found = tree.selectWhere(function (row) {
                    if (!passed) { passed = row === cur; return false; }
                    return match(row, q);
                }) || tree.selectWhere(function (row) { return match(row, q); });
                box.setAttribute('aria-invalid', found ? 'false' : 'true');
                box.focus();
            });
        });
    }

    /** @param {string} kind @param {string} code @param {string} [text] @param {string | null} [title] */
    function badge(kind, code, text, title) {
        var tone = (TONES[kind] && TONES[kind][code]) || 'neutral';
        return hb.status(tone, text || code || '', title || null);
    }

    /** @type {any} */ (window).sysUi = {leaveOk: leaveOk, beforeSelect: beforeSelect, ask: ask, reason: reason, badge: badge, TONES: TONES,
        tableFind: tableFind, treeFind: treeFind};
})();
