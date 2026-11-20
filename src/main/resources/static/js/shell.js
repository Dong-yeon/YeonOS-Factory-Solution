// @ts-check
/*
 * 화면 셸: 왼쪽 메뉴(/api/platform/menu) + 탭 + 탭마다 iframe(/page/{progCd}).
 * - 탭마다 iframe 을 따로 둔다 → 탭을 옮겨도 화면 상태가 유지된다
 * - 같은 화면은 한 탭만 (이미 열렸으면 그 탭으로)
 * - 탭 수 상한 MAX_TABS — 넘으면 가장 오래 안 본 탭을 닫고 연다(지금 탭·홈·입력 중인 탭 제외, 2026-10-26 사용자)
 * - 입력 중(hb.dirty)인 탭을 닫으면 확인
 * - 메뉴 이름 등 서버 데이터는 textContent 로만 넣는다 (HTML 해석 금지)
 * - window.hbShell(계약 HB.Shell, types/hb.d.ts): 탭 열기·목록
 */
/** @typedef {{menuId?: number, progCd?: string, name: string, children?: ShellMenuNode[]}} ShellMenuNode /api/platform/menu 노드(progCd 없으면 폴더) */
(function () {
    'use strict';

    var MAX_TABS = 10;
    var PROG_CD = /^[a-z0-9][a-z0-9-]{1,49}$/;
    /** @type {HB.ShellTab[]} */
    var tabs = [];
    /** @type {HB.ShellTab | null} */
    var active = null;
    /** 탭을 마지막으로 본 순번(오래된 탭 고르기) @type {WeakMap<HB.ShellTab, number>} */
    var usedAt = new WeakMap();
    var useSeq = 0;
    /** 역할별 홈 화면 — 오래된 탭 정리에서 뺀다 @type {string | null} */
    var homeProg = null;

    // PWA 셸(docs/45 M-1) — 정적 자원만 다루는 서비스 워커. 없거나 실패해도 셸은 그대로
    if (hb.pwa) { hb.pwa.register(); }

    var menuEl = /** @type {HTMLElement} */ (document.getElementById('hb-menu'));
    var tabBarEl = /** @type {HTMLElement} */ (document.getElementById('hb-tabs'));
    var framesEl = /** @type {HTMLElement} */ (document.getElementById('hb-frames'));
    var emptyEl = /** @type {HTMLElement} */ (document.getElementById('hb-empty'));

    /** @param {string} progCd */
    function findTab(progCd) {
        for (var i = 0; i < tabs.length; i++) {
            if (tabs[i].progCd === progCd) {
                return tabs[i];
            }
        }
        return null;
    }

    /** @param {HB.ShellTab | null} tab */
    function activate(tab) {
        if (tab) {
            expandFor(tab.progCd);
            usedAt.set(tab, ++useSeq);
        }
        tabs.forEach(function (t) {
            var on = t === tab;
            t.tabEl.classList.toggle('active', on);
            t.tabEl.setAttribute('aria-selected', on ? 'true' : 'false');
            t.frameEl.hidden = !on;
        });
        active = tab;
        emptyEl.hidden = tabs.length > 0;
        markCurrent(tab ? tab.progCd : null);
    }

    /** 메뉴의 지금 화면 = 배경 + 왼쪽 3px 막대 + aria-current(탭 전환을 따라감, docs/12 5.8·docs/40 D9) @param {string | null} progCd */
    function markCurrent(progCd) {
        menuItems.forEach(function (m) {
            if (m.progCd === progCd) {
                m.a.setAttribute('aria-current', 'page');
            } else {
                m.a.removeAttribute('aria-current');
            }
        });
    }

    /** @param {HB.ShellTab} tab */
    function frameDirty(tab) {
        try {
            var w = tab.frameEl.contentWindow;
            return !!(w && w.hb && w.hb.dirty && w.hb.dirty.isDirty());
        } catch (e) {
            return false;
        }
    }

    /**
     * 저장하지 않은 내용 확인 — 브라우저 confirm 대신 화면 안 대화상자(docs/40 D7·10.2). 위험 확인이라 기본 포커스는 취소
     * @param {string} title @param {string} message @param {string} ok @returns {Promise<boolean>}
     */
    function askDirty(title, message, ok) {
        return hb.dialog.confirm({title: title, message: message, ok: ok, danger: true});
    }

    /** @param {HB.ShellTab} tab @returns {Promise<boolean>} 입력 중이 아니거나 사용자가 닫기로 했으면 true */
    function confirmClose(tab) {
        if (!frameDirty(tab)) {
            return Promise.resolve(true);
        }
        return askDirty(hb.t('js.shell.closeDirtyTitle'), hb.t('js.shell.confirmCloseDirty', tab.title), hb.t('js.shell.closeDirtyOk'));
    }

    /** 입력 중이면 먼저 묻고 닫는다 @param {HB.ShellTab} tab @returns {Promise<boolean>} 닫았는지 */
    function requestClose(tab) {
        return confirmClose(tab).then(function (yes) {
            if (yes && tabs.indexOf(tab) >= 0) {
                close(tab);
                return true;
            }
            return false;
        });
    }

    /** 확인 없이 닫는다 — 입력 중 확인은 requestClose·closeMany 가 먼저 한다 @param {HB.ShellTab} tab */
    function close(tab) {
        var idx = tabs.indexOf(tab);
        tabs.splice(idx, 1);
        tab.tabEl.remove();
        tab.frameEl.remove();
        if (active === tab) {
            activate(tabs[Math.min(idx, tabs.length - 1)] || null);
        } else {
            emptyEl.hidden = tabs.length > 0;
        }
    }

    /** 여러 탭 닫기 — 입력 중인 화면이 있으면 탭마다가 아니라 한 번만 묻는다 @param {HB.ShellTab[]} list */
    function closeMany(list) {
        var dirty = list.filter(frameDirty);
        var ask = dirty.length
            ? askDirty(hb.t('js.shell.closeDirtyTitle'), hb.t('js.shell.confirmCloseDirtyMany', dirty.length,
                dirty.map(function (t) { return t.title; }).join(', ')), hb.t('js.shell.closeDirtyManyOk'))
            : Promise.resolve(true);
        ask.then(function (yes) {
            if (!yes) { return; }
            list.forEach(function (t) {
                if (tabs.indexOf(t) >= 0) { close(t); }   // 묻는 동안 사용자가 따로 닫은 탭은 건너뛴다
            });
        });
    }

    // ---------- 탭 우클릭 메뉴 ----------
    /** @type {HTMLElement | null} */
    var tabMenuEl = null;

    function closeTabMenu() {
        if (tabMenuEl) {
            tabMenuEl.remove();
            tabMenuEl = null;
        }
    }

    /** @param {HB.ShellTab} tab @param {number} x @param {number} y */
    function openTabMenu(tab, x, y) {
        closeTabMenu();
        var idx = tabs.indexOf(tab);
        /** @type {[string, HB.ShellTab[]][]} */
        var items = [
            ['js.shell.closeTab', [tab]],
            ['js.shell.closeOthers', tabs.filter(function (t) { return t !== tab; })],
            ['js.shell.closeRight', tabs.slice(idx + 1)],
            ['js.shell.closeAll', tabs.slice()]
        ];
        var menu = document.createElement('div');
        menu.className = 'hb-tab-menu';
        menu.setAttribute('role', 'menu');
        items.forEach(function (it) {
            var b = document.createElement('button');
            b.type = 'button';
            b.className = 'hb-tab-menu-item';
            b.setAttribute('role', 'menuitem');
            b.textContent = hb.t(it[0]);
            b.disabled = it[1].length === 0;
            b.addEventListener('click', function () {
                closeTabMenu();
                closeMany(it[1]);
            });
            menu.appendChild(b);
        });
        menu.addEventListener('keydown', function (e) {
            var list = Array.prototype.filter.call(menu.querySelectorAll('button'),
                function (/** @type {HTMLButtonElement} */ b) { return !b.disabled; });
            var i = list.indexOf(document.activeElement);
            if (e.key === 'Escape') { closeTabMenu(); }
            else if (e.key === 'ArrowDown') { list[(i + 1) % list.length].focus(); }
            else if (e.key === 'ArrowUp') { list[(i - 1 + list.length) % list.length].focus(); }
            else { return; }
            e.preventDefault();
        });
        document.body.appendChild(menu);
        // 화면 밖으로 나가지 않게
        var r = menu.getBoundingClientRect();
        menu.style.left = Math.max(0, Math.min(x, window.innerWidth - r.width)) + 'px';
        menu.style.top = Math.max(0, Math.min(y, window.innerHeight - r.height)) + 'px';
        tabMenuEl = menu;
        var firstItem = /** @type {HTMLButtonElement | null} */ (menu.querySelector('button:not(:disabled)'));
        if (firstItem) { firstItem.focus(); }
    }

    document.addEventListener('mousedown', function (e) {
        if (tabMenuEl && !tabMenuEl.contains(/** @type {Node | null} */ (e.target))) { closeTabMenu(); }
    });
    window.addEventListener('blur', closeTabMenu);       // iframe 안을 누르면 셸 창이 포커스를 잃는다
    window.addEventListener('resize', closeTabMenu);

    /** 링크 조건 → 주소 ?q.이름=값 (형식 검사는 화면 쪽 hb.applyLinkQuery·조회 패널이 다시 한다) @param {Record<string, string> | null | undefined} params */
    function linkQuery(params) {
        if (!params) { return ''; }
        var q = Object.keys(params).map(function (k) {
            return encodeURIComponent('q.' + k) + '=' + encodeURIComponent(String(params[k]));
        }).join('&');
        return q ? '?' + q : '';
    }

    /**
     * 탭이 꽉 찼을 때 가장 오래 안 본 탭 하나를 닫는다 — 지금 탭·홈은 빼고, 입력 중이 아닌 탭을 먼저.
     * 모두 입력 중이면 가장 오래된 탭을 닫을지 묻는다(취소하면 새 탭을 열지 않는다)
     * @return {Promise<boolean>} 자리를 만들었는지
     */
    function makeRoom() {
        var cand = tabs.filter(function (t) { return t !== active && t.progCd !== homeProg; })
            .sort(function (a, b) { return (usedAt.get(a) || 0) - (usedAt.get(b) || 0); });
        var clean = cand.filter(function (t) { return !frameDirty(t); });
        var victim = clean[0] || cand[0];
        if (!victim) {
            return Promise.resolve(false);
        }
        return requestClose(victim);   // 입력 중이면 먼저 묻는다
    }

    /** @param {string} progCd @param {string} title @param {Record<string, string> | null} [params] 조회 조건(docs/37 14.2) */
    function open(progCd, title, params) {
        if (!PROG_CD.test(progCd)) {
            return;
        }
        var existing = findTab(progCd);
        if (existing) {
            activate(existing);
            if (params) {
                // 열린 화면의 조회 조건을 바꾸면 목록이 다시 읽힌다 — 입력 중이면 먼저 묻는다
                var target = existing;
                var linkParams = params;
                var ask = frameDirty(target)
                    ? askDirty(hb.t('js.shell.linkDirtyTitle'), hb.t('js.shell.confirmLinkDirty', target.title), hb.t('js.shell.linkDirtyOk'))
                    : Promise.resolve(true);
                ask.then(function (yes) {
                    if (!yes || tabs.indexOf(target) < 0) { return; }
                    try {
                        var w = target.frameEl.contentWindow;
                        if (w && w.hb && w.hb.applyLinkQuery) { w.hb.applyLinkQuery(linkParams); }
                    } catch (e) { /* 화면이 아직 준비 전이면 탭만 연다 */ }
                });
            }
            return;
        }
        if (tabs.length >= MAX_TABS) {
            makeRoom().then(function (ok) {
                // 묻는 동안 같은 화면이 다른 길로 열렸으면 새로 만들지 않는다
                if (ok && !findTab(progCd)) { addTab(progCd, title, params); }
            });
            return;
        }
        addTab(progCd, title, params);
    }

    /** 새 탭 + iframe @param {string} progCd @param {string} title @param {Record<string, string> | null} [params] */
    function addTab(progCd, title, params) {
        var tabEl = document.createElement('div');
        tabEl.className = 'hb-tab';
        tabEl.setAttribute('role', 'tab');
        var label = document.createElement('span');
        label.textContent = title;
        var x = document.createElement('button');
        x.type = 'button';
        x.className = 'hb-tab-close';
        x.setAttribute('aria-label', hb.t('js.shell.closeTab'));
        x.textContent = '×';
        tabEl.appendChild(label);
        tabEl.appendChild(x);

        var frameEl = document.createElement('iframe');
        frameEl.className = 'hb-frame';
        frameEl.title = title;
        frameEl.src = '/page/' + encodeURIComponent(progCd) + linkQuery(params);

        /** @type {HB.ShellTab} */
        var tab = {progCd: progCd, title: title, tabEl: tabEl, frameEl: frameEl};
        label.addEventListener('click', function () { activate(tab); });
        x.addEventListener('click', function (e) { e.stopPropagation(); requestClose(tab); });
        tabEl.addEventListener('contextmenu', function (e) {
            e.preventDefault();
            openTabMenu(tab, e.clientX, e.clientY);
        });
        tabBarEl.appendChild(tabEl);
        framesEl.appendChild(frameEl);
        tabs.push(tab);
        activate(tab);
    }

    /** @param {ShellMenuNode[]} nodes @param {HTMLElement} parentEl @param {number} depth */
    function renderMenu(nodes, parentEl, depth) {
        nodes.forEach(function (node) {
            var progCd = node.progCd;
            var li = document.createElement('li');
            if (progCd) {
                var a = document.createElement('a');
                a.href = '#';
                a.textContent = node.name;
                menuItems.push({progCd: /** @type {string} */ (progCd), name: node.name, a: a, li: li});
                a.addEventListener('click', function (e) {
                    e.preventDefault();
                    open(/** @type {string} */ (progCd), node.name);
                    closeOverlay();
                });
                li.appendChild(a);
            } else {
                // 묶음 = 버튼(Enter·Space 는 버튼 기본 동작) — 그 묶음 <ul> 만 접고 편다 (docs/15 4.4)
                var ul = document.createElement('ul');
                var btn = document.createElement('button');
                btn.type = 'button';
                btn.className = 'hb-menu-folder';
                btn.textContent = node.name;
                if (node.menuId != null) {
                    var id = String(node.menuId);
                    ul.id = 'hb-mf-' + id;
                    btn.setAttribute('aria-controls', ul.id);
                    folderEls[id] = {btn: btn, ul: ul, li: li};
                    btn.addEventListener('click', function () { setFolded(id, !folded.has(id), true); });
                }
                btn.setAttribute('aria-expanded', 'true');
                li.appendChild(btn);
                renderMenu(node.children || [], ul, depth + 1);
                li.appendChild(ul);
            }
            parentEl.appendChild(li);
        });
    }

    /** 역할별 홈 화면을 첫 탭으로 연다 (현장 역할은 메뉴 트리 대신 홈 하나, docs/11 D6) @param {ShellMenuNode[]} nodes */
    function openHome(nodes) {
        hb.api('/api/platform/home').then(function (res) {
            var prog = res && res.data;
            if (!prog) { return; }
            homeProg = prog;
            var name = prog;
            (function find(/** @type {ShellMenuNode[]} */ list) {
                list.forEach(function (n) {
                    if (n.progCd === prog) { name = n.name; }
                    if (n.children) { find(n.children); }
                });
            })(nodes);
            open(prog, name);
        }, function () { /* 홈이 없어도 셸은 쓸 수 있다 */ });
    }

    // ---------- 묶음 접기·펼치기 (docs/15 4.4) — 이 기기 localStorage, 기본 = 모두 펼침, 계산은 shell.menufold.js ----------
    var FOLDERS_KEY = 'hb.shell.folders';
    var fold = window.hbMenuFold;
    /** @type {Record<string, {btn: HTMLButtonElement, ul: HTMLElement, li: HTMLElement}>} */
    var folderEls = {};
    /** 화면 항목(검색용) @type {{progCd: string, name: string, a: HTMLAnchorElement, li: HTMLElement}[]} */
    var menuItems = [];
    /** @type {Set<string>} */
    var folded = new Set();
    /** @type {ShellMenuNode[]} */
    var menuNodes = [];
    /** @type {HTMLButtonElement | null} */
    var foldAllBtn = null;

    /** @param {string} id @param {boolean} on @param {boolean} save */
    function setFolded(id, on, save) {
        var f = folderEls[id];
        if (!f) { return; }
        if (on) { folded.add(id); } else { folded.delete(id); }
        f.ul.hidden = on;
        f.btn.setAttribute('aria-expanded', String(!on));
        if (save) { saveFolded(); }
    }

    function saveFolded() {
        var st = store();
        if (st && fold) {
            try { st.setItem(FOLDERS_KEY, fold.serialize(folded)); } catch (e) { /* 저장 공간 없음 — 이번 화면만 */ }
        }
        syncFoldAll();
    }

    function syncFoldAll() {
        if (!foldAllBtn) { return; }
        var any = Object.keys(folderEls).some(function (id) { return !folded.has(id); });
        foldAllBtn.textContent = any ? hb.t('js.shell.foldAll') : hb.t('js.shell.unfoldAll');
    }

    /** 연 화면이 든 묶음은 펼친다(접어 둔 것도) @param {string} progCd */
    function expandFor(progCd) {
        if (!fold || !menuNodes.length || (menuEl.hasAttribute('data-searching'))) { return; }
        var path = fold.pathTo(menuNodes, progCd);
        var changed = false;
        path.forEach(function (id) {
            if (folded.has(id)) {
                setFolded(id, false, false);
                changed = true;
            }
        });
        if (changed) { saveFolded(); }
    }

    // ---------- 메뉴 검색 (docs/15 4.4 7) — 계산은 shell.menufold.js(search·highlight), 접힘 기억(localStorage)은 건드리지 않는다 ----------
    /** @type {HTMLInputElement | null} */
    var searchEl = null;
    /** @type {HTMLElement | null} */
    var noResultEl = null;

    /** 지금 보이는 검색 결과 링크 */
    function visibleResults() {
        return menuItems.filter(function (m) { return !m.li.hidden; }).map(function (m) { return m.a; });
    }

    /** @param {HTMLAnchorElement} a @param {string} name @param {[number, number] | null} range */
    function paintName(a, name, range) {
        a.textContent = '';
        if (!range) {
            a.textContent = name;
            return;
        }
        a.appendChild(document.createTextNode(name.slice(0, range[0])));
        var mark = document.createElement('mark');
        mark.className = 'hb-menu-hit';
        mark.textContent = name.slice(range[0], range[1]);
        a.appendChild(mark);
        a.appendChild(document.createTextNode(name.slice(range[1])));
    }

    function applySearch() {
        if (!fold || !searchEl) { return; }
        var q = searchEl.value;
        var r = fold.search(menuNodes, q);
        var searching = r !== null;
        menuEl.toggleAttribute('data-searching', searching);
        if (foldAllBtn) { foldAllBtn.disabled = searching; }
        /** @type {Set<string>} */
        var hit = new Set(r ? r.progCds : []);
        menuItems.forEach(function (m) {
            m.li.hidden = searching && !hit.has(m.progCd);
            var h = fold ? fold.highlight(m.name, q) : null;
            paintName(m.a, m.name, searching && hit.has(m.progCd) ? h : null);
        });
        Object.keys(folderEls).forEach(function (id) {
            var f = folderEls[id];
            if (searching && r) {
                var open = r.folders.has(id);
                f.li.hidden = !open;
                f.ul.hidden = !open;
                f.btn.setAttribute('aria-expanded', String(open));
            } else {
                // 검색어를 지우면 원래 접힘 상태로
                f.li.hidden = false;
                f.ul.hidden = folded.has(id);
                f.btn.setAttribute('aria-expanded', String(!folded.has(id)));
            }
        });
        if (noResultEl) { noResultEl.hidden = !searching || hit.size > 0; }
    }

    function initSearch() {
        if (!fold) { return; }
        var box = document.createElement('div');
        box.className = 'hb-menu-search';
        var label = document.createElement('label');
        label.className = 'hb-sr-only';
        label.htmlFor = 'hb-menu-q';
        label.textContent = hb.t('js.shell.menuSearch');
        var input = document.createElement('input');
        input.type = 'search';
        input.id = 'hb-menu-q';
        input.placeholder = hb.t('js.shell.menuSearchPlaceholder');
        input.setAttribute('autocomplete', 'off');
        input.spellcheck = false;
        input.maxLength = 50;
        box.appendChild(label);
        box.appendChild(input);
        var none = document.createElement('p');
        none.className = 'hb-menu-noresult';
        none.textContent = hb.t('js.shell.menuSearchNone');
        none.hidden = true;
        none.setAttribute('role', 'status');
        searchEl = input;
        noResultEl = none;
        input.addEventListener('input', applySearch);
        input.addEventListener('keydown', function (e) {
            var results = visibleResults();
            if (e.key === 'Enter') {
                e.preventDefault();
                if (input.value.trim() && results.length) { results[0].click(); }
            } else if (e.key === 'ArrowDown') {
                if (results.length) {
                    e.preventDefault();
                    results[0].focus();
                }
            } else if (e.key === 'Escape') {
                if (input.value) {
                    e.preventDefault();
                    e.stopPropagation();   // 덮개 닫기보다 검색어 지우기가 먼저
                    input.value = '';
                    applySearch();
                }
            }
        });
        // 결과 사이 위·아래 이동(검색 중), 맨 위에서 위 → 검색 칸
        menuEl.addEventListener('keydown', function (e) {
            if (!menuEl.hasAttribute('data-searching') || (e.key !== 'ArrowDown' && e.key !== 'ArrowUp')) { return; }
            var results = visibleResults();
            var i = results.indexOf(/** @type {HTMLAnchorElement} */ (document.activeElement));
            if (i < 0) { return; }
            e.preventDefault();
            if (e.key === 'ArrowDown') {
                results[Math.min(i + 1, results.length - 1)].focus();
            } else if (i === 0) {
                input.focus();
            } else {
                results[i - 1].focus();
            }
        });
        menuEl.insertBefore(none, menuEl.firstChild);
        menuEl.insertBefore(box, menuEl.firstChild);
        // 단축키 "/" — 입력 칸이 아닌 곳에서(화면 iframe 안의 키는 셸에 오지 않는다)
        document.addEventListener('keydown', function (e) {
            if (e.key !== '/' || e.ctrlKey || e.metaKey || e.altKey) { return; }
            var t = /** @type {HTMLElement | null} */ (e.target);
            var tag = t && t.tagName;
            if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT' || (t && t.isContentEditable)) { return; }
            e.preventDefault();
            if (narrowMq.matches && document.body.getAttribute('data-menu-overlay') !== 'open') {
                document.body.setAttribute('data-menu-overlay', 'open');
                syncToggle();
            } else if (!narrowMq.matches && document.body.getAttribute('data-menu') === 'closed') {
                document.body.setAttribute('data-menu', 'open');
                syncToggle();
            }
            input.focus();
            input.select();
        });
    }

    function initFolders() {
        var ids = Object.keys(folderEls);
        if (!fold || ids.length === 0) { return; }
        var st = store();
        var raw = null;
        try { raw = st ? st.getItem(FOLDERS_KEY) : null; } catch (e) { raw = null; }
        fold.parse(raw, new Set(ids)).forEach(function (id) { setFolded(id, true, false); });
        var b = document.createElement('button');
        b.type = 'button';
        b.className = 'hb-menu-foldall';
        b.addEventListener('click', function () {
            var collapse = ids.some(function (id) { return !folded.has(id); });
            ids.forEach(function (id) { setFolded(id, collapse, false); });
            if (!collapse || !active) {
                saveFolded();
                return;
            }
            expandFor(active.progCd);   // 모두 접어도 지금 화면 묶음은 보이게
            saveFolded();
        });
        foldAllBtn = b;
        menuEl.insertBefore(b, menuEl.firstChild);
        syncFoldAll();
    }

    function loadMenu() {
        hb.api('/api/platform/menu').then(function (res) {
            menuEl.textContent = '';
            var nodes = (res && res.data) || [];
            menuNodes = nodes;
            if (nodes.length === 0) {
                var p = document.createElement('p');
                p.className = 'hb-menu-empty';
                p.textContent = hb.t('js.shell.noMenu');
                menuEl.appendChild(p);
                return;
            }
            var ul = document.createElement('ul');
            renderMenu(nodes, ul, 0);
            menuEl.appendChild(ul);
            initFolders();
            initSearch();
            if (active) {
                expandFor(active.progCd);
                markCurrent(active.progCd);
            }
            openHome(nodes);
        }, function (err) {
            menuEl.textContent = err.message;
        });
    }

    window.hbShell = true;
    /** 화면 쪽 hb.js 가 세션 만료 때 "다른 탭에 입력 중" 인지 묻는다 */
    window.hbAnyDirty = function () { return tabs.some(frameDirty); };
    window.addEventListener('beforeunload', function (e) {
        if (window.hbLeaving) {
            return;     // 세션 만료 확인창에서 이미 "로그인으로" 를 골랐다
        }
        if (tabs.some(frameDirty)) {
            e.preventDefault();
            e.returnValue = '';
        }
    });

    // ---------- 메뉴 접기·덮개 (docs/15 4.4) ----------
    // ≥1024: 펼침/접힘을 이 기기에 기억(localStorage, 값 검증). <1024: 같은 버튼이 덮개를 연다.
    var MENU_KEY = 'hb.shell.menu';
    var toggleEl = /** @type {HTMLElement} */ (document.getElementById('hb-menu-toggle'));
    var narrowMq = window.matchMedia('(max-width: 1023px)');

    function store() {
        try { return window.localStorage; } catch (e) { return null; }
    }

    function savedMenu() {
        var st = store();
        var v = st ? st.getItem(MENU_KEY) : null;
        return v === 'closed' ? 'closed' : 'open';   // 모르는 값은 기본(펼침)
    }

    function syncToggle() {
        var expanded = narrowMq.matches ? document.body.getAttribute('data-menu-overlay') === 'open'
            : document.body.getAttribute('data-menu') !== 'closed';
        toggleEl.setAttribute('aria-expanded', String(expanded));
    }

    function closeOverlay() {
        if (document.body.getAttribute('data-menu-overlay') === 'open') {
            document.body.removeAttribute('data-menu-overlay');
            syncToggle();
        }
    }

    function initMenuToggle() {
        if (!toggleEl) { return; }
        document.body.setAttribute('data-menu', savedMenu());
        syncToggle();
        toggleEl.addEventListener('click', function () {
            if (narrowMq.matches) {
                if (document.body.getAttribute('data-menu-overlay') === 'open') {
                    closeOverlay();
                } else {
                    document.body.setAttribute('data-menu-overlay', 'open');
                    syncToggle();
                    var first = menuEl.querySelector('a');
                    if (first) { first.focus(); }
                }
                return;
            }
            var next = document.body.getAttribute('data-menu') === 'closed' ? 'open' : 'closed';
            document.body.setAttribute('data-menu', next);
            var st = store();
            if (st) { st.setItem(MENU_KEY, next); }
            syncToggle();
        });
        document.addEventListener('keydown', function (e) {
            if (e.key === 'Escape' && document.body.getAttribute('data-menu-overlay') === 'open') {
                closeOverlay();
                toggleEl.focus();
            }
        });
        document.addEventListener('click', function (e) {
            if (document.body.getAttribute('data-menu-overlay') === 'open'
                    && !menuEl.contains(/** @type {Node | null} */ (e.target)) && !toggleEl.contains(/** @type {Node | null} */ (e.target))) {
                closeOverlay();
            }
        });
        narrowMq.addEventListener('change', function () {
            document.body.removeAttribute('data-menu-overlay');
            syncToggle();
        });
        window.addEventListener('resize', syncToggle);   // 구간 변경 이벤트가 늦거나 없을 때도 버튼 상태를 맞춘다
    }

    // ---------- 내 알림 종 버튼 (docs/36 11.4) ----------
    // 주기 조회는 하지 않는다 — 1분마다 부르면 사용자가 자리를 떠도 세션 비활동 30분 만료가 영영 오지 않는다.
    // 다시 세는 때: 셸 시작·탭 열기/바꾸기·창 포커스·화면이 부를 때(알림 읽음 등 hbShell.refreshBell)
    var bell = /** @type {HTMLButtonElement | null} */ (document.getElementById('hb-bell'));
    var bellCount = document.getElementById('hb-bell-count');
    var bellSeq = 0;
    function refreshBell() {
        if (!bell || !bellCount) { return; }
        var b = bell;
        var c = bellCount;
        var seq = ++bellSeq;
        hb.api('/api/platform/inbox/count').then(function (res) {
            if (seq !== bellSeq) { return; }
            var n = Number(res.data && res.data.total) || 0;
            b.hidden = false;
            c.textContent = n > 99 ? '99+' : String(n);
            c.hidden = n === 0;
            b.setAttribute('aria-label', hb.t('js.shell.bellCount', n));
        }, function (err) {
            if (err && err.status === 403) { b.hidden = true; }   // 회사가 화면을 끈 경우
        });
    }
    if (bell) {
        var bellBtn = bell;
        bellBtn.addEventListener('click', function () {
            open('sys-inbox', bellBtn.getAttribute('data-title') || '');
            refreshBell();
        });
        window.addEventListener('focus', refreshBell);
        document.addEventListener('visibilitychange', function () { if (!document.hidden) { refreshBell(); } });
        var tabBar = document.getElementById('hb-tabs');
        if (tabBar) { tabBar.addEventListener('click', refreshBell); }
    }

    window.hbShell = {open: open, tabs: function () { return tabs.slice(); }, refreshBell: refreshBell};
    initMenuToggle();
    hb.loadMessages().then(function () {
        loadMenu();
        refreshBell();
    });
})();
