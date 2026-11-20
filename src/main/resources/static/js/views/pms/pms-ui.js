// @ts-check
/*
 * PMS 화면 공용 표시 (UI 단계 3, docs/40 부록 A·V-7, docs/32 5장) — 공통(hb.*)이 아니라 PMS 화면 전용. 각 화면 스크립트보다 먼저 싣는다.
 *
 *   pmsUi.leaveOk(dirty)                    → Promise<boolean>  저장 안 한 입력이 있으면 화면 안 대화상자로 묻는다(없으면 바로 true).
 *                                                               dirty 생략 = hb.dirty, 자체 표시를 쓰는 화면(내 공수)은 넘긴다
 *   pmsUi.ask(title, message, ok, danger)   → Promise<boolean>  확인 대화상자(제목·버튼 = 동사). danger = 되돌리기 어려운 동작
 *   pmsUi.reason(title, label, ok, required) → Promise<string|null>  사유 입력(500자, 취소 = null)
 *   pmsUi.badge(kind, code, text, title)    → <span class="hb-status">  상태 배지(톤은 아래 표, 표에 없는 값 = neutral)
 *   pmsUi.hours(h, hoursPerDay)             → "12h · 1.5MD"  공수 글자(저장은 시간, MD = 시간 ÷ 회사 하루 시간 — docs/48 R-2). 빈 값 = ""
 *   pmsUi.formLink(docNo)                   → <button>  빈 서식 = QMS 관리문서(docs/32 4.17, D6) — 셸 탭으로 QMS 문서 화면을 그 번호로 연다
 *   pmsUi.depTypeTag(type)                  → " · SS(시작→시작)"  선후행 종류 꼬리표(FS = 빈 글자, docs/32 4.27)
 *
 * 톤 표 = PMS 상태 의미(부록 A 규칙을 PMS 코드에 적용). 색은 정하지 않고 톤만 — 값은 tokens.css.
 * 취소·면제는 사용자가 고른 결과라 빨강이 아니다(A.3). 지연·높음만 danger.
 */
(function () {
    'use strict';

    /** @type {Record<string, Record<string, HB.StatusTone>>} */
    var TONES = {
        // 프로젝트 PMS_PROJ_STATUS
        project: {DRAFT: 'neutral', ACTIVE: 'normal', ON_HOLD: 'warning', CLOSED: 'success', CANCELLED: 'neutral'},
        // 작업 PMS_TASK_STATUS — A.3 작업 상태와 같은 규칙
        task: {NOT_STARTED: 'neutral', IN_PROGRESS: 'normal', DONE: 'success', CANCELLED: 'neutral'},
        // 산출물 — 제출 = 볼 것 있음, 반려 = 다시 낼 것(주의)
        deliv: {NONE: 'neutral', SUBMITTED: 'info', APPROVED: 'success', REJECTED: 'warning', WAIVED: 'neutral'},
        // 게이트 심사
        review: {DRAFT: 'neutral', IN_APPROVAL: 'info', DECIDED: 'success', CANCELLED: 'neutral'},
        // 게이트 결정 — 조건부 = 조건 이슈가 남음, 불가 = 정지
        decision: {GO: 'success', CONDITIONAL: 'warning', NO_GO: 'danger'},
        // 이슈 상태·심각도
        issue: {OPEN: 'info', IN_PROGRESS: 'normal', RESOLVED: 'success', CLOSED: 'neutral'},
        severity: {HIGH: 'danger', MID: 'warning', LOW: 'neutral'},
        // 하루 공수
        day: {NONE: 'neutral', DRAFT: 'info', CONFIRMED: 'success'},
        // 일정변경 요청 — 처리 대기 = 볼 것 있음, 반려 = 주의
        chg: {REQUESTED: 'info', IN_APPROVAL: 'info', APPROVED: 'success', REJECTED: 'warning', WITHDRAWN: 'neutral'}
    };

    /** @param {boolean} [dirty] */
    function leaveOk(dirty) {
        if (!(dirty === undefined ? hb.dirty.isDirty() : dirty)) { return Promise.resolve(true); }
        return hb.dialog.confirm({title: hb.t('js.common.leaveTitle'), message: hb.t('js.common.confirmLeave'), ok: hb.t('js.common.leaveOk')});
    }

    /** @param {string} title @param {string | null} message @param {string} ok @param {boolean} [danger] */
    function ask(title, message, ok, danger) {
        return hb.dialog.confirm({title: title, message: message || null, ok: ok, danger: !!danger});
    }

    /** @param {string} title @param {string} label @param {string} ok @param {boolean} [required] */
    function reason(title, label, ok, required) {
        return hb.dialog.prompt({title: title, label: label, ok: ok, required: required !== false, maxLength: 500});
    }

    /** @param {string} kind @param {string | null | undefined} code @param {string} [text] @param {string | null} [title] */
    function badge(kind, code, text, title) {
        var tone = (TONES[kind] && code && TONES[kind][code]) || 'neutral';
        return hb.status(tone, text || code || '', title || null);
    }

    /** @param {number | string | null | undefined} h @param {number} hoursPerDay */
    function hours(h, hoursPerDay) {
        if (h === null || h === undefined || h === '') { return ''; }
        var n = Number(h);
        var md = Math.round(n / (hoursPerDay || 8) * 10) / 10;
        return hb.t('js.pms.hoursMd', hb.fmt.num(n, 1, 0), hb.fmt.num(md, 1, 0));
    }

    /**
     * 빈 서식 단추 — QMS 관리문서 화면을 조회 조건(keyword = 문서 번호)으로 연다. 셸 탭, 셸 밖이면 그 화면 주소로(SRM openProg 와 같은 길).
     * 받기 권한은 QMS 문서 조회 권한 그대로(PMS 는 파일을 갖지 않는다)
     * @param {string} docNo
     */
    function formLink(docNo) {
        var b = /** @type {HTMLButtonElement} */ (hb.el('button', 'hb-btn-ghost pms-form-link', hb.t('js.pms.form.link', docNo)));
        b.type = 'button';
        b.title = hb.t('js.pms.form.title');
        b.addEventListener('click', function (e) {
            e.stopPropagation();
            var top = /** @type {any} */ (window.top);
            var shell = top && top !== window ? top.hbShell : null;
            if (shell && shell !== true) {
                shell.open('qms-doc', hb.t('js.pms.form.tab'), {keyword: docNo});
                return;
            }
            window.location.href = '/page/qms-doc?' + encodeURIComponent('q.keyword') + '=' + encodeURIComponent(docNo);
        });
        return b;
    }

    /**
     * 선후행 종류 꼬리표(docs/32 4.27) — FS(기본)는 빈 글자, SS·FF 는 " · SS(시작→시작)" 처럼. 키는 글자 그대로(I18nKeyGuardTest)
     * @param {string|null|undefined} type
     */
    function depTypeTag(type) {
        if (type === 'SS') { return ' · ' + hb.t('js.pms.dep.SS'); }
        if (type === 'FF') { return ' · ' + hb.t('js.pms.dep.FF'); }
        return '';
    }

    window.pmsUi = {leaveOk: leaveOk, ask: ask, reason: reason, badge: badge, hours: hours, formLink: formLink, depTypeTag: depTypeTag,
        TONES: TONES};
})();
