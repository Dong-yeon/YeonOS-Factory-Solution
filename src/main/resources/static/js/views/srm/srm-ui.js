// @ts-check
/*
 * SRM 화면 공용 표시 (UI 단계 3, docs/40 부록 A·V-7, docs/31 5.1) — 공통(hb.*)이 아니라 SRM 화면 전용.
 *
 *   srmUi.leaveOk()                              → Promise<boolean>  저장 안 한 입력이 있으면 화면 안 대화상자로 묻는다
 *   srmUi.ask(titleKey, message, okKey, danger)  → Promise<boolean>  확인 대화상자(제목·버튼 = 동사)
 *   srmUi.reason(titleKey, labelKey, okKey)      → Promise<string|null>  사유 입력(필수, 500자)
 *   srmUi.badge(kind, code, text, title)         → <span class="hb-status">  상태 배지(톤은 아래 표)
 *   srmUi.flag(tone, text)                       → 상태 코드 없는 표시(지연·늦음 = danger, 임박 = warning)
 *   srmUi.cells(tr, values)                      표 한 줄 칸 — 요소면 그대로, 아니면 글자
 *   srmUi.withBadge(text, badge, after)          글자 + 배지 한 칸
 *   srmUi.openProg(progCd, title, params)        다른 SRM 화면을 그 조회 조건(q.이름)으로 — 셸 탭, 셸 밖이면 그 화면으로 이동(홈 카드 링크와 같은 길)
 *
 * 톤 표 = SRM 상태 의미(부록 A 의 규칙을 SRM 코드에 적용). 색은 정하지 않고 톤만 — 값은 tokens.css.
 */
(function () {
    'use strict';

    /** @type {Object<string, Object<string, HB.StatusTone>>} */
    var TONES = {
        // 구매요청 PR_STATUS — 반려는 다시 써야 하는 일이라 주의, 취소는 사용자가 고른 결과라 빨강 아님(A.3)
        pr: {DRAFT: 'neutral', REQUESTED: 'info', APPROVED: 'success', REJECTED: 'warning', CLOSED: 'success', CANCELLED: 'neutral'},
        // 발주 PO_STATUS — 결재·승인 = 볼 것 있음, 발행~입고 중 = 진행, 다 받음·마감 = 완료
        po: {DRAFT: 'neutral', IN_APPROVAL: 'info', APPROVED: 'info', ISSUED: 'normal', ACKED: 'normal', PARTIAL_RECEIVED: 'normal',
            RECEIVED: 'success', CLOSED: 'success', CANCELLED: 'neutral'},
        // 납기 회신 PO_ACK_STATUS — 부분 = 확인 필요, 거절 = 납기 위험
        ack: {ACCEPTED: 'success', PARTIAL: 'warning', REJECTED: 'danger'},
        // 사전 납품 통지 ASN_STATUS
        asn: {DRAFT: 'neutral', SENT: 'normal', PARTIAL_RECEIVED: 'normal', RECEIVED: 'success', CLOSED: 'success', CANCELLED: 'neutral'},
        // 반품 지시 RETURN_STATUS — 확정 = 출고 기다림(진행), 출고 = 완료
        ret: {DRAFT: 'neutral', CONFIRMED: 'normal', SHIPPED: 'success', CANCELLED: 'neutral'},
        // 클레임 CLAIM_STATUS — 회신 받음 = 볼 것 있음(검토·종결)
        claim: {DRAFT: 'neutral', SENT: 'normal', ANSWERED: 'info', CLOSED: 'success', CANCELLED: 'neutral'},
        // 평가 템플릿 리비전
        tmpl: {DRAFT: 'neutral', RELEASED: 'success', OBSOLETE: 'neutral'},
        // 협력사 평가
        eval: {DRAFT: 'neutral', CONFIRMED: 'success', CANCELLED: 'neutral'},
        // 통지(아웃박스) — 실패 = 처리 실패(error ✕)
        notify: {NEW: 'neutral', SENDING: 'info', SENT: 'success', FAIL: 'error'},
        // 협력사 거래 상태
        trade: {ACTIVE: 'normal', HOLD: 'warning', BLOCKED: 'danger'},
        // 인증서 판정(CertStatus) — 없음 = 확인 필요
        cert: {VALID: 'success', DUE_SOON: 'warning', EXPIRED: 'danger', NONE: 'warning', OFF: 'neutral'},
        // 단가 유효 구간(PriceState) — 지금 쓰는 단가 = 진행, 앞으로 = 볼 것 있음
        price: {CURRENT: 'normal', FUTURE: 'info', PAST: 'neutral', OFF: 'neutral'}
    };

    function leaveOk() {
        if (!hb.dirty.isDirty()) { return Promise.resolve(true); }
        return hb.dialog.confirm({title: hb.t('js.srm.dlg.leave'), message: hb.t('js.common.confirmLeave'), ok: hb.t('js.srm.dlg.leaveOk')});
    }

    /** @param {string} titleKey @param {string|null} message @param {string} okKey @param {boolean} [danger] */
    function ask(titleKey, message, okKey, danger) {
        return hb.dialog.confirm({title: hb.t(titleKey), message: message || null, ok: hb.t(okKey), danger: !!danger});
    }

    /** @param {string} titleKey @param {string} labelKey @param {string} okKey */
    function reason(titleKey, labelKey, okKey) {
        return hb.dialog.prompt({title: hb.t(titleKey), label: hb.t(labelKey), ok: hb.t(okKey), required: true, maxLength: 500});
    }

    /** @param {string} kind @param {string|null|undefined} code @param {string} [text] @param {string|null} [title] */
    function badge(kind, code, text, title) {
        var tone = (TONES[kind] && code && TONES[kind][code]) || 'neutral';
        return hb.status(tone, text || code || '', title || null);
    }

    /** @param {HB.StatusTone} tone @param {string} text */
    function flag(tone, text) { return hb.status(tone, text, null); }

    /** 표 한 줄의 칸들 — 값이 요소(배지 등)면 그대로, 아니면 글자(textContent) @param {HTMLElement} tr @param {any[]} values */
    function cells(tr, values) {
        values.forEach(function (v) {
            var td = hb.el('td');
            if (v instanceof Node) { td.appendChild(v); } else { td.textContent = v === null || v === undefined ? '' : String(v); }
            tr.appendChild(td);
        });
    }

    /** 글자 + 배지(사이 띄움) 한 칸 @param {string|null} text @param {Node|null} badgeEl @param {string|null} [after] */
    function withBadge(text, badgeEl, after) {
        var f = document.createDocumentFragment();
        if (text) { f.appendChild(document.createTextNode(text + ' ')); }
        if (badgeEl) { f.appendChild(badgeEl); }
        if (after) { f.appendChild(document.createTextNode(' ' + after)); }
        return f;
    }

    /**
     * 다른 화면을 조회 조건과 함께 연다 — 셸 탭(window.top.hbShell.open), 셸 밖 단독이면 ?q.이름= 주소로 이동
     * @param {string} progCd @param {string} title @param {Object<string, string>} params
     */
    function openProg(progCd, title, params) {
        var top = /** @type {any} */ (window.top);
        var shell = top && top !== window ? top.hbShell : null;
        if (shell && shell !== true) {
            shell.open(progCd, title, params);
            return;
        }
        var q = Object.keys(params).map(function (k) { return encodeURIComponent('q.' + k) + '=' + encodeURIComponent(params[k]); }).join('&');
        window.location.href = '/page/' + encodeURIComponent(progCd) + (q ? '?' + q : '');
    }

    /** @type {any} */ (window).srmUi = {leaveOk: leaveOk, ask: ask, reason: reason, badge: badge, flag: flag, cells: cells, withBadge: withBadge,
        openProg: openProg, TONES: TONES};
})();
