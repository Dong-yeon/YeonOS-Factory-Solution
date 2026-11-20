/* 설계 변경 화면 공용 (PLM 1단계, docs/39) — 알림 줄 · 첨부(플랫폼 FileService, 대상 화면 = prog) · 품목 찾기 보조 */
(function (global) {
    'use strict';

    var msg = document.getElementById('msg');

    function notify(text, error) {
        msg.textContent = text || '';
        msg.classList.toggle('hb-msg-error', !!error);
    }

    /** 오류 응답 → 한 줄 (칸 오류가 있으면 그 문구들) */
    function errorText(err) {
        if (err && err.errors && err.errors.length) {
            return err.message + ' ' + err.errors.map(function (x) { return x.message; }).join(' / ');
        }
        return err && err.message;
    }

    function option(sel, value, text) {
        var o = hb.el('option', null, text);
        o.value = value === null || value === undefined ? '' : String(value);
        sel.appendChild(o);
    }

    /**
     * 첨부 목록·올리기·삭제. getId() 가 null 이면 비운다. canEdit() 이 false 면 삭제 버튼을 숨긴다(판정은 서버 권한).
     * markAfter(f) 가 true 면 "상신 뒤 추가" 표시 — 결재받은 뒤에 바뀐 도면을 드러낸다.
     */
    function files(prog, getId, canEdit, markAfter) {
        var section = document.getElementById('fileSection');
        var ul = document.getElementById('files');
        var input = document.getElementById('fileInput');

        function load() {
            ul.textContent = '';
            var id = getId();
            section.hidden = id === null;
            if (id === null) { return Promise.resolve(); }
            return hb.latest('files', hb.api('/api/platform/files?targetType=' + prog + '&targetId=' + id)).then(function (res) {
                var list = res.list || [];
                if (!list.length) { ul.appendChild(hb.el('li', 'hb-muted', hb.t('js.plm.noFiles'))); }
                list.forEach(function (f) {
                    var li = hb.el('li');
                    var a = hb.el('a', null, f.name);
                    a.href = '/api/platform/files/' + f.fileId;
                    li.appendChild(a);
                    li.appendChild(hb.el('span', 'hb-muted', ' ' + hb.fmt.num(Math.ceil(f.size / 1024)) + ' KB · '
                        + hb.fmt.datetime(f.createdAt) + ' · ' + f.createdBy));
                    if (markAfter && markAfter(f)) { li.appendChild(hb.el('span', 'hb-warn', ' ' + hb.t('js.plm.fileAfter'))); }
                    if (input && canEdit()) {
                        var del = hb.el('button', 'hb-btn-secondary hb-btn-small', hb.t('js.common.remove'));
                        del.type = 'button';
                        del.addEventListener('click', function () {
                            ask('js.plm.dlg.fileDelete', hb.t('js.plm.confirmFileDelete', f.name), 'js.plm.dlg.fileDeleteOk', true).then(function (ok) {
                                if (!ok) { return; }
                                hb.api('/api/platform/files/' + f.fileId + '/delete', {method: 'POST'})
                                    .then(load, function (err) { notify(err.message, true); });
                            });
                        });
                        li.appendChild(del);
                    }
                    ul.appendChild(li);
                });
            }, function (err) { notify(err.message, true); });
        }

        if (input) {
            input.addEventListener('change', function () {
                var file = input.files && input.files[0];
                var id = getId();
                if (!file || id === null) { return; }
                var fd = new FormData();
                fd.append('targetType', prog);
                fd.append('targetId', String(id));
                fd.append('file', file);
                hb.upload('/api/platform/files', fd).then(function () {
                    input.value = '';
                    notify(hb.t('js.common.saved'));
                    load();
                }, function (err) {
                    input.value = '';
                    notify(err.message + (err.details && err.details.length ? ' ' + err.details.join(' ') : ''), true);
                });
            });
        }
        return {load: load};
    }

    /** 품목 코드 입력 보조 — 2자 이상이면 찾기 API 로 datalist 채움(실패해도 입력은 된다) */
    function itemAssist(url, input, datalist) {
        var timer = null;
        input.addEventListener('input', function () {
            clearTimeout(timer);
            var q = input.value.trim();
            if (q.length < 2) { return; }
            timer = setTimeout(function () {
                hb.latest('items', hb.api(url + '?q=' + encodeURIComponent(q))).then(function (res) {
                    datalist.textContent = '';
                    res.data.forEach(function (i) { option(datalist, i.itemCd, i.itemNm); });
                }, function () { /* 입력 보조 */ });
            }, 250);
        });
    }

    // ------------------------------------------------------------------ UI 단계 3 (docs/40 P1·V-7·부록 A, docs/39 21장)

    /**
     * 상태 톤 표 — PLM 코드에 부록 A 규칙을 적용(색은 정하지 않고 톤만, 값은 tokens.css).
     * 취소·반려는 사용자가 고른 결과라 빨강이 아니다(A.3). 고객이 거절한 판정만 danger
     */
    var TONES = {
        eco: {DRAFT: 'neutral', PENDING: 'info', APPROVED: 'info', RELEASED: 'success', CANCELLED: 'neutral'},
        ecr: {OPEN: 'info', ACCEPTED: 'success', REJECTED: 'neutral', CANCELLED: 'neutral'},
        cust: {NA: 'neutral', PENDING: 'warning', APPROVED: 'success', REJECTED: 'danger'},
        ppap: {DRAFT: 'neutral', SUBMITTED: 'info', APPROVED: 'success', INTERIM: 'warning', REJECTED: 'danger'},
        pcn: {SENT: 'info', ACK: 'success', APPROVED: 'success', REJECTED: 'danger'},
        mail: {PENDING: 'info', SENT: 'success', FAILED: 'danger'},
        apqp: {RELEASED: 'success', OBSOLETE: 'warning', IN_APPROVAL: 'info', DRAFT: 'neutral'},
        aprLine: {WAIT: 'neutral', CURRENT: 'info', APPROVED: 'success', REJECTED: 'neutral', SKIPPED: 'neutral', REF: 'neutral'}
    };

    /** 저장 안 한 입력이 있으면 화면 안 대화상자로 묻는다(window.confirm 대체) */
    function leaveOk() {
        if (!hb.dirty.isDirty()) { return Promise.resolve(true); }
        return hb.dialog.confirm({title: hb.t('js.plm.dlg.leave'), message: hb.t('js.common.confirmLeave'), ok: hb.t('js.plm.dlg.leaveOk')});
    }

    /** 확인 대화상자 — 제목·버튼은 동사, danger = 되돌리기 어려운 동작 */
    function ask(titleKey, message, okKey, danger) {
        return hb.dialog.confirm({title: hb.t(titleKey), message: message || null, ok: hb.t(okKey), danger: !!danger});
    }

    /** 사유 입력(필수, 500자) — 취소 = null(아무 일 없음) */
    function reason(titleKey, labelKey, okKey) {
        return hb.dialog.prompt({title: hb.t(titleKey), label: hb.t(labelKey), ok: hb.t(okKey), required: true, maxLength: 500});
    }

    /** 상태 배지 — 톤은 위 표, 글자는 코드 이름 */
    function badge(kind, code, text) {
        var tone = (TONES[kind] && TONES[kind][code]) || 'neutral';
        return hb.status(/** @type {any} */ (tone), text || code || '');
    }

    /** 요소 내용을 배지 + 뒤 글자로(출력 칸·표 칸) */
    function setBadge(el, badgeEl, after) {
        el.textContent = '';
        if (badgeEl) { el.appendChild(badgeEl); }
        if (after) { el.appendChild(document.createTextNode(' ' + after)); }
    }

    /** 서명 의미 문구 — 키를 글자로 이어 붙이지 않는다(문구 키 검사) */
    function meaningText(code) {
        return code === 'AUTHOR' ? hb.t('js.esign.meaning.AUTHOR') : code === 'REVIEW' ? hb.t('js.esign.meaning.REVIEW')
            : code === 'APPROVE' ? hb.t('js.esign.meaning.APPROVE') : code;
    }

    /** 전자서명 목록(docs/39 19장) — 서명자 · 의미 · 방식 · 시각, 지금 내용과 다르면 "서명 뒤 바뀜" */
    function signs(ul, list) {
        if (!ul) { return; }
        ul.textContent = '';
        ul.hidden = !list || !list.length;
        (list || []).forEach(function (s) {
            var li = hb.el('li', s.changed ? 'hb-msg-error' : null, [hb.t('js.plm.signed'), s.signerNm, meaningText(s.meaningCd),
                s.authMethod === 'PWD_TOTP' ? hb.t('js.plm.signMethod.PWD_TOTP') : hb.t('js.plm.signMethod.PWD'),
                hb.fmt.datetime(s.signedAt)].join(' · ')
                + (s.changed ? ' — ' + hb.t('js.plm.signChanged') : ''));
            ul.appendChild(li);
        });
    }

    global.plm = {notify: notify, errorText: errorText, option: option, files: files, itemAssist: itemAssist, signs: signs,
        TONES: TONES, leaveOk: leaveOk, ask: ask, reason: reason, badge: badge, setBadge: setBadge};
})(window);
