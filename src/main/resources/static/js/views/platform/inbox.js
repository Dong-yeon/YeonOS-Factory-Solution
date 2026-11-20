// @ts-check
/*
 * 내 알림 (sys-inbox) — 시스템관리 2b (docs/36 11.4). 개인 화면: 서버가 "나" 의 것만 준다.
 * 알림: 누르면 읽음 + 링크 화면을 셸 탭으로 / 공지: 게시 중만, 열면 읽음 / 설정: 이벤트별 받기.
 * 읽은 뒤에는 셸 종 버튼 숫자를 다시 세게 한다(hbShell.refreshBell). 서버 글자는 textContent 로만.
 */
/**
 * @typedef {{label: string, count: number, progCd: string | null, progNm: string | null}} InboxLine
 * @typedef {{notifyId: number, eventCd: string, eventNm: string, title: string, linkProg: string | null,
 *   linkProgNm: string | null, linkParams: Record<string, string>, lines: InboxLine[], createdAt: string,
 *   readAt: string | null}} InboxItem
 * @typedef {{noticeId: number, catCd: string, title: string, pinYn: string, postFrom: string, postTo: string | null,
 *   fileCnt: number, unread: boolean}} InboxNotice
 * @typedef {{noticeId: number, title: string, body: string, catCd: string, postFrom: string, postTo: string | null,
 *   fileCnt: number}} InboxNoticeDoc
 * @typedef {{eventCd: string, eventNm: string, defaultOn: boolean, inboxYn: string, mailYn: string, mailAvailable: boolean}} InboxSub
 */
(function () {
    'use strict';

    var ACTIONS = (document.body.getAttribute('data-actions') || '').split(',');
    var CAN_SAVE = ACTIONS.indexOf('SAVE') >= 0;
    var API = '/api/platform/inbox';
    var NOTICE_API = '/api/platform/notices';
    var PAGE = 30;
    var msg = /** @type {HTMLElement} */ (document.getElementById('msg'));
    var tabs = /** @type {HTMLElement} */ (document.getElementById('tabs'));
    var notifyList = /** @type {HTMLElement} */ (document.getElementById('notifyList'));
    var noticeList = /** @type {HTMLElement} */ (document.getElementById('noticeList'));
    var subList = /** @type {HTMLElement} */ (document.getElementById('subList'));
    var unreadOnly = /** @type {HTMLInputElement} */ (document.getElementById('unreadOnly'));
    var moreBtn = /** @type {HTMLButtonElement} */ (document.getElementById('moreBtn'));
    var noticeCat = /** @type {HTMLSelectElement} */ (document.getElementById('noticeCat'));
    /** 알림 목록의 마지막 id(더 보기) @type {number | null} */
    var lastId = null;
    /** @type {Record<string, string>} */
    var catNames = {};
    var subsLoaded = false;

    /** @param {string | null | undefined} text @param {boolean} [error] */
    function notify(text, error) {
        msg.textContent = text || '';
        msg.classList.toggle('hb-msg-error', !!error);
    }

    function shell() {
        var s = window.top && window.top !== window ? window.top.hbShell : null;
        return s && s !== true ? s : null;
    }

    /** @param {string} progCd @param {string} title @param {Record<string, string> | null} params */
    function openScreen(progCd, title, params) {
        var s = shell();
        if (s) {
            s.open(progCd, title, params);
            return;
        }
        var q = params ? Object.keys(params).map(function (k) {
            return encodeURIComponent('q.' + k) + '=' + encodeURIComponent(params[k]);
        }).join('&') : '';
        window.location.href = '/page/' + encodeURIComponent(progCd) + (q ? '?' + q : '');
    }

    /** 탭 숫자 + 셸 종 버튼 */
    function refreshCounts() {
        var s = shell();
        if (s) { s.refreshBell(); }
        return hb.latest('count', hb.api(API + '/count')).then(function (res) {
            /** @type {Record<string, number>} */
            var c = res.data || {};
            badge('notifyCnt', c.notify || 0);
            badge('noticeCnt', c.notice || 0);
        }, function () { /* 숫자는 없어도 쓸 수 있다 */ });
    }

    /** @param {string} id @param {number} n */
    function badge(id, n) {
        var el = /** @type {HTMLElement} */ (document.getElementById(id));
        el.textContent = n > 99 ? '99+' : String(n);
        el.hidden = n === 0;
    }

    // ---------- 알림 ----------
    /** @param {InboxItem} it */
    function notifyItem(it) {
        var li = hb.el('li');
        li.setAttribute('data-unread', it.readAt ? 'false' : 'true');
        var btn = hb.el('button', 'hb-inbox-item');
        btn.type = 'button';
        btn.appendChild(hb.el('span', 'hb-inbox-title', it.title));
        btn.appendChild(hb.el('span', 'hb-inbox-meta', it.eventNm + ' · ' + hb.fmt.datetime(it.createdAt)
            + (it.linkProgNm ? ' · ' + hb.t('js.sysInbox.opens', it.linkProgNm) : '')));
        btn.addEventListener('click', function () {
            read(it, li).then(function () {
                if (it.linkProg) { openScreen(it.linkProg, it.linkProgNm || it.linkProg, it.linkParams || null); }
            });
        });
        li.appendChild(btn);
        if (it.lines && it.lines.length) {
            var ul = hb.el('ul', 'hb-inbox-lines');
            it.lines.forEach(function (l) {
                var row = hb.el('li');
                var b = hb.el('button', null, l.label + (l.progNm ? ' — ' + l.progNm : ''));
                b.type = 'button';
                b.disabled = !l.progCd;
                b.addEventListener('click', function () {
                    var prog = l.progCd;
                    if (!prog) { return; }
                    var p = prog;
                    read(it, li).then(function () { openScreen(p, l.progNm || p, null); });
                });
                row.appendChild(b);
                ul.appendChild(row);
            });
            li.appendChild(ul);
        }
        return li;
    }

    /** 읽음(이미 읽었으면 그냥 통과) @param {InboxItem} it @param {HTMLElement} li */
    function read(it, li) {
        if (it.readAt || !CAN_SAVE) { return Promise.resolve(); }
        return hb.api(API + '/' + it.notifyId + '/read', {method: 'POST'}).then(function () {
            it.readAt = new Date().toISOString();
            li.setAttribute('data-unread', 'false');
            refreshCounts();
        }, function (err) { notify(err.message, true); });
    }

    /** @param {boolean} reset */
    function loadNotify(reset) {
        if (reset) { lastId = null; }
        var q = '?size=' + PAGE + '&unread=' + (unreadOnly.checked ? 'true' : 'false') + (lastId ? '&before=' + lastId : '');
        return hb.latest('notify', hb.api(API + q)).then(function (res) {
            /** @type {InboxItem[]} */
            var list = res.list || [];
            if (reset) { notifyList.textContent = ''; }
            list.forEach(function (it) { notifyList.appendChild(notifyItem(it)); });
            if (reset && !list.length) {
                notifyList.appendChild(hb.el('li', 'hb-inbox-empty',
                    unreadOnly.checked ? hb.t('js.sysInbox.noUnread') : hb.t('js.sysInbox.empty')));
            }
            lastId = list.length ? list[list.length - 1].notifyId : lastId;
            moreBtn.hidden = list.length < PAGE;
        }, function (err) { if (!err.stale) { notify(err.message, true); } });
    }

    // ---------- 공지 ----------
    function loadNotices() {
        var cat = noticeCat.value;
        return hb.latest('notices', hb.api(NOTICE_API + (cat ? '?catCd=' + encodeURIComponent(cat) : ''))).then(function (res) {
            /** @type {InboxNotice[]} */
            var list = res.list || [];
            noticeList.textContent = '';
            if (!list.length) { noticeList.appendChild(hb.el('li', 'hb-inbox-empty', hb.t('js.sysInbox.noNotice'))); }
            list.forEach(function (n) {
                var li = hb.el('li');
                li.setAttribute('data-unread', n.unread ? 'true' : 'false');
                var btn = hb.el('button', 'hb-inbox-item');
                btn.type = 'button';
                btn.appendChild(hb.el('span', 'hb-inbox-title', (n.pinYn === 'Y' ? '[' + hb.t('js.sysInbox.pinned') + '] ' : '') + n.title));
                btn.appendChild(hb.el('span', 'hb-inbox-meta', (catNames[n.catCd] || n.catCd) + ' · ' + n.postFrom
                    + (n.fileCnt ? ' · ' + hb.t('js.sysInbox.files', n.fileCnt) : '')));
                btn.addEventListener('click', function () {
                    noticeList.querySelectorAll('[aria-current]').forEach(function (e) { e.removeAttribute('aria-current'); });
                    btn.setAttribute('aria-current', 'true');
                    openNotice(n, li);
                });
                li.appendChild(btn);
                noticeList.appendChild(li);
            });
        }, function (err) { if (!err.stale) { notify(err.message, true); } });
    }

    /** @param {InboxNotice} n @param {HTMLElement} li */
    function openNotice(n, li) {
        notify('');
        hb.latest('notice', hb.api(NOTICE_API + '/' + n.noticeId)).then(function (res) {
            /** @type {InboxNoticeDoc} */
            var d = res.data;
            /** @type {HTMLElement} */ (document.getElementById('noticeEmpty')).hidden = true;
            /** @type {HTMLElement} */ (document.getElementById('noticeDoc')).hidden = false;
            /** @type {HTMLElement} */ (document.getElementById('noticeTitle')).textContent = d.title;
            /** @type {HTMLElement} */ (document.getElementById('noticeMeta')).textContent = (catNames[d.catCd] || d.catCd)
                + ' · ' + d.postFrom + (d.postTo ? ' ~ ' + d.postTo : '');
            /** @type {HTMLElement} */ (document.getElementById('noticeBody')).textContent = d.body;
            hb.page.openDetail();
            if (n.unread) {
                n.unread = false;
                li.setAttribute('data-unread', 'false');
                refreshCounts();
            }
            loadNoticeFiles(d);
        }, function (err) {
            if (!err.stale) { notify(err.message, true); }
        });
    }

    /** @param {InboxNoticeDoc} d */
    function loadNoticeFiles(d) {
        var box = /** @type {HTMLElement} */ (document.getElementById('noticeFileBox'));
        var ul = /** @type {HTMLElement} */ (document.getElementById('noticeFiles'));
        ul.textContent = '';
        box.hidden = !d.fileCnt;
        if (!d.fileCnt) { return; }
        hb.latest('noticeFiles', hb.api('/api/platform/files?targetType=sys-notice&targetId=' + d.noticeId)).then(function (res) {
            (res.list || []).forEach(function (/** @type {{fileId: number, name: string, size: number}} */ f) {
                var li = hb.el('li');
                var a = hb.el('a', null, f.name);
                a.href = '/api/platform/files/' + f.fileId;
                li.appendChild(a);
                li.appendChild(hb.el('span', 'hb-muted', ' ' + hb.fmt.num(Math.ceil(f.size / 1024)) + ' KB'));
                ul.appendChild(li);
            });
        }, function (err) { if (!err.stale) { notify(err.message, true); } });
    }

    // ---------- 설정 ----------
    function loadSubs() {
        return hb.api(API + '/subscriptions').then(function (res) {
            /** @type {InboxSub[]} */
            var list = res.list || [];
            subList.textContent = '';
            // 메일 칸은 메일 설정이 켜졌고 내 메일 주소가 있을 때만(docs/36 18.3) — 보내지 않는데 켜짐으로 보이지 않게
            var mailOk = list.length > 0 && list[0].mailAvailable;
            list.forEach(function (s) {
                var row = hb.el('div', 'hb-inbox-sub');
                var label = hb.el('label', 'hb-switch');
                var box = hb.el('input');
                box.type = 'checkbox';
                box.setAttribute('role', 'switch');
                box.setAttribute('data-sub', 'inbox');
                box.value = s.eventCd;
                box.checked = s.inboxYn === 'Y';
                box.disabled = !CAN_SAVE;
                label.appendChild(box);
                label.appendChild(hb.el('span', null, s.eventNm));
                row.appendChild(label);
                if (mailOk) {
                    var mlabel = hb.el('label', 'hb-inbox-mail');
                    var mail = hb.el('input');
                    mail.type = 'checkbox';
                    mail.setAttribute('data-sub', 'mail');
                    mail.value = s.eventCd;
                    mail.checked = s.mailYn === 'Y';
                    // 메일은 알림함에 들어온 것만 — 알림함을 끄면 메일도 못 켠다
                    mail.disabled = !CAN_SAVE || !box.checked;
                    box.addEventListener('change', function () { mail.disabled = !CAN_SAVE || !box.checked; });
                    mlabel.appendChild(mail);
                    mlabel.appendChild(hb.el('span', null, hb.t('js.sysInbox.mailToo')));
                    row.appendChild(mlabel);
                }
                subList.appendChild(row);
            });
            subsLoaded = true;
            hb.dirty.clear(subList);
        }, function (err) { notify(err.message, true); });
    }

    var saveSub = /** @type {HTMLButtonElement | null} */ (document.getElementById('saveSubBtn'));
    if (saveSub) {
        var saveBtn = saveSub;
        saveBtn.addEventListener('click', function () {
            /** @type {{eventCd: string, inboxYn: string, mailYn: string | null}[]} */
            var body = [];
            subList.querySelectorAll('.hb-inbox-sub').forEach(function (row) {
                var box = /** @type {HTMLInputElement} */ (row.querySelector('input[data-sub="inbox"]'));
                var mail = /** @type {HTMLInputElement | null} */ (row.querySelector('input[data-sub="mail"]'));
                // 메일 칸이 없으면 null = 지금 값 그대로(서버). 알림함을 끄면 메일도 끈다
                body.push({eventCd: box.value, inboxYn: box.checked ? 'Y' : 'N',
                    mailYn: mail ? (box.checked && mail.checked ? 'Y' : 'N') : null});
            });
            hb.busy(saveBtn, function () { return hb.api(API + '/subscriptions', {method: 'PUT', body: body}); })
                .then(function (res) {
                    if (!res) { return; }
                    hb.dirty.clear(subList);
                    notify(hb.t('js.common.saved'));
                }, function (err) { notify(err.message, true); });
        });
    }

    // ---------- 보기 바꾸기 ----------
    function currentTab() {
        var on = /** @type {HTMLInputElement | null} */ (tabs.querySelector('input[name="tab"]:checked'));
        return on ? on.value : 'notify';
    }

    function showTab() {
        var t = currentTab();
        /** @type {HTMLElement} */ (document.getElementById('notifyView')).hidden = t !== 'notify';
        /** @type {HTMLElement} */ (document.getElementById('noticeView')).hidden = t !== 'notice';
        /** @type {HTMLElement} */ (document.getElementById('subView')).hidden = t !== 'sub';
        notify('');
        if (t === 'notice') { loadNotices(); }
        if (t === 'sub' && !subsLoaded) { loadSubs(); }
    }

    // ---------- 시작 ----------
    hb.dirty.watch(subList);
    tabs.addEventListener('change', showTab);
    unreadOnly.addEventListener('change', function () { loadNotify(true); });
    moreBtn.addEventListener('click', function () { loadNotify(false); });
    noticeCat.addEventListener('change', loadNotices);
    var readAll = /** @type {HTMLButtonElement | null} */ (document.getElementById('readAllBtn'));
    if (readAll) {
        var readAllBtn = readAll;
        readAllBtn.addEventListener('click', function () {
            hb.busy(readAllBtn, function () { return hb.api(API + '/read-all', {method: 'POST'}); }).then(function (res) {
                if (!res) { return; }
                notify(hb.t('js.sysInbox.readAllDone', res.data));
                refreshCounts();
                return loadNotify(true);
            }, function (err) { notify(err.message, true); });
        });
    }
    hb.loadMessages().then(function () {
        hb.page.init();
        hb.select(noticeCat);
        return hb.codes('NOTICE_CAT');
    }).then(function (names) {
        catNames = names;
        refreshCounts();
        return loadNotify(true);
    }).then(null, function (err) { notify(err && err.message, true); });
})();
