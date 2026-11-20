// @ts-check
/*
 * 홈 (home-main) — "내 할 일" 카드 (docs/37). 카드 계산·권한은 서버(HomeService), 화면은 그리기만.
 * - 상태는 기호 + 글자 + 색(docs/12 2.6). 정상은 회색, 확인 필요 ▲, 지연 ■, 불러오지 못함 ✕
 * - 서버 글자(카드 제목·항목·보류 사유)는 textContent 로만(hb.el)
 * - 새로 고침: 버튼(hb.busy) + 5분 자동(화면이 보일 때만). 늦게 온 앞 응답은 hb.latest 로 버린다
 * - "화면 열기" = 셸 탭(window.top.hbShell.open), 셸 밖 단독이면 그 화면으로 이동
 * - 2단계(docs/37 14장): 항목 번호를 누르면 그 조회 조건으로 화면을 연다(링크), "카드 설정" 대화상자 = 보이기·순서(사용자별)
 * - 3단계(15장): 내 설정이 없으면 역할(권한그룹) 기본 세트 순서 — 서버가 겹쳐 쓴 결과를 그대로 그린다
 * - 4단계(16장): 카드마다 "다시 읽기"(그 카드만, hb.latest 카드별 키), 카드 link 가 있으면 숫자 = 그 조건으로 목록 열기
 * - 5단계(17장): 공장 선택(공장이 둘 이상일 때만 보임) — 고른 공장은 이 브라우저에만 기억(localStorage, 실패해도 "전체"), 공장 카드에 "공장 기준" 표시
 */
/**
 * @typedef {'NEUTRAL' | 'INFO' | 'WARN' | 'ALERT'} HomeTone
 * @typedef {{label: string, value: number, tone: HomeTone}} HomeFact
 * @typedef {{title: string, detail: string, tone: HomeTone, link: Record<string, string> | null}} HomeItem
 * @typedef {{code: string, title: string, desc: string | null, progCd: string | null, progTitle: string | null,
 *            count: number | null, tone: HomeTone, facts: HomeFact[], items: HomeItem[],
 *            link: Record<string, string> | null, plantScoped: boolean, error: boolean}} HomeCardView
 * @typedef {{asOf: string, card: HomeCardView}} HomeCardReload
 * @typedef {{asOf: string, today: string, cards: HomeCardView[], hiddenCount: number, plant: string | null, plantToday: string}} HomeView
 * @typedef {{plantCd: string, plantNm: string}} HomePlant
 * @typedef {{code: string, title: string, desc: string | null, hidden: boolean}} HomeSetting
 */
(function () {
    'use strict';

    var AUTO_MS = 5 * 60 * 1000;
    var cardsEl = /** @type {HTMLElement} */ (document.getElementById('cards'));
    var msg = /** @type {HTMLElement} */ (document.getElementById('msg'));
    var asOfEl = /** @type {HTMLElement} */ (document.getElementById('asOf'));
    var refreshBtn = /** @type {HTMLButtonElement} */ (document.getElementById('refreshBtn'));
    var settingsBtn = /** @type {HTMLButtonElement} */ (document.getElementById('settingsBtn'));
    var hiddenNote = /** @type {HTMLElement} */ (document.getElementById('hiddenNote'));
    var plantBox = /** @type {HTMLElement} */ (document.getElementById('plantBox'));
    var plantSel = /** @type {HTMLSelectElement} */ (document.getElementById('plantSel'));
    var PLANT_KEY = 'hb.home.plant';
    /** @type {number | null} */
    var timer = null;

    /** 고른 공장('' = 전체) */
    function plant() { return plantSel.value || ''; }

    /** @param {string} url */
    function withPlant(url) {
        var p = plant();
        return p ? url + (url.indexOf('?') >= 0 ? '&' : '?') + 'plant=' + encodeURIComponent(p) : url;
    }

    /** @param {string} v */
    function rememberPlant(v) {
        try { if (v) { window.localStorage.setItem(PLANT_KEY, v); } else { window.localStorage.removeItem(PLANT_KEY); } } catch (e) { /* 저장 불가 — 이번 화면에서만 */ }
    }

    function savedPlant() {
        try { return window.localStorage.getItem(PLANT_KEY) || ''; } catch (e) { return ''; }
    }

    /** 공장 선택지 — 공장이 둘 이상일 때만 보인다(하나면 고를 것이 없다) */
    function loadPlants() {
        return hb.api('/api/home/plants').then(function (res) {
            var list = /** @type {HomePlant[]} */ (res.list || []);
            plantSel.textContent = '';
            var all = /** @type {HTMLOptionElement} */ (hb.el('option', null, hb.t('js.home.plantAll')));
            all.value = '';
            plantSel.appendChild(all);
            list.forEach(function (p) {
                var o = /** @type {HTMLOptionElement} */ (hb.el('option', null, p.plantNm + ' (' + p.plantCd + ')'));
                o.value = p.plantCd;
                plantSel.appendChild(o);
            });
            var saved = savedPlant();
            plantSel.value = list.some(function (p) { return p.plantCd === saved; }) ? saved : '';
            plantBox.hidden = list.length < 2;
        }, function () { plantBox.hidden = true; });
    }

    /**
     * 배지 톤·글자 — 색만으로 알리지 않는다. 모양 기호는 공통 hb-status 가 톤으로 그린다(docs/40 부록 A)
     * @param {HomeTone} tone @param {number | null} count @return {[HB.StatusTone, string]}
     */
    function status(tone, count) {
        if (tone === 'ALERT') { return ['danger', hb.t('js.home.tone.alert')]; }
        if (tone === 'WARN') { return ['warning', hb.t('js.home.tone.warn')]; }
        if (tone === 'INFO') { return ['info', hb.t('js.home.tone.info')]; }
        return count ? ['normal', hb.t('js.home.tone.normal')] : ['neutral', hb.t('js.home.tone.neutral')];
    }

    /** 보조 숫자·항목 배지 톤 — 확인(WARN)·지연(ALERT)만, 나머지는 배지 없이 글자 @param {HomeTone} tone @return {HB.StatusTone | null} */
    function markTone(tone) {
        return tone === 'ALERT' ? 'danger' : tone === 'WARN' ? 'warning' : null;
    }

    /** @param {HomeTone} tone */
    function toneAttr(tone) {
        return tone === 'ALERT' ? 'alert' : tone === 'WARN' ? 'warn' : tone === 'INFO' ? 'info' : 'neutral';
    }

    /** @param {string} progCd @param {string} title @param {Record<string, string> | null} [params] 조회 조건 */
    function openScreen(progCd, title, params) {
        var shell = window.top && window.top !== window ? window.top.hbShell : null;
        if (shell && shell !== true) {
            shell.open(progCd, title, params || null);
        } else {
            var q = params ? Object.keys(params).map(function (k) {
                return encodeURIComponent('q.' + k) + '=' + encodeURIComponent(params[k]);
            }).join('&') : '';
            window.location.href = '/page/' + encodeURIComponent(progCd) + (q ? '?' + q : '');
        }
    }

    /** @param {HomeCardView} c */
    function card(c) {
        var sec = hb.el('section', 'hb-home-card');
        sec.setAttribute('data-tone', c.error ? 'neutral' : toneAttr(c.tone));
        sec.setAttribute('data-card', c.code);
        var head = hb.el('header', 'hb-home-card-head');
        var h = hb.el('h2', null, c.title);
        h.id = 'hc-' + c.code;
        sec.setAttribute('aria-labelledby', h.id);
        head.appendChild(h);
        /** @type {[HB.StatusTone, string]} */
        var st = c.error ? ['error', hb.t('js.home.error')] : status(c.tone, c.count);
        head.appendChild(hb.status(st[0], st[1]));
        if (c.plantScoped && plant()) {
            // 공장 카드 — 고른 공장으로 거른 숫자(나머지 카드는 회사 전체)
            sec.setAttribute('data-plant', plant());
        }
        var re = /** @type {HTMLButtonElement} */ (hb.el('button', 'hb-btn-ghost hb-home-reload', '↻'));
        re.type = 'button';
        re.setAttribute('aria-label', hb.t('js.home.reload', c.title));
        re.title = hb.t('js.home.reload', c.title);
        re.addEventListener('click', function () { hb.busy(re, function () { return reloadCard(c.code); }); });
        head.appendChild(re);
        sec.appendChild(head);
        if (c.desc) {
            sec.appendChild(hb.el('p', 'hb-home-desc', c.desc));
        }
        if (c.plantScoped && plant()) {
            sec.appendChild(hb.el('p', 'hb-home-plant-tag', hb.t('js.home.plantScoped', plant())));
        }
        if (!c.error) {
            var num = hb.el('p', 'hb-home-count');
            var cardLink = c.link;
            if (cardLink && c.progCd && c.count) {
                // 숫자 = 그 숫자의 목록(서버가 형식 검사한 조회 조건, 화면이 조건을 모두 받는 카드만 link 를 준다)
                var nb = /** @type {HTMLButtonElement} */ (hb.el('button', 'hb-home-num hb-home-num-link', hb.fmt.num(c.count, 0)));
                nb.type = 'button';
                nb.title = hb.t('js.home.openList', c.title);
                nb.setAttribute('aria-label', hb.t('js.home.openList', c.title) + ' ' + hb.fmt.num(c.count, 0));
                var lp = c.progCd;
                var lt = c.progTitle || c.progCd;
                var lparams = cardLink;
                nb.addEventListener('click', function () { openScreen(lp, lt, lparams); });
                num.appendChild(nb);
            } else {
                num.appendChild(hb.el('span', 'hb-home-num', hb.fmt.num(c.count, 0)));
            }
            var unit = hb.t('js.home.unit');
            if (unit) { num.appendChild(hb.el('span', 'hb-home-unit', unit)); }
            sec.appendChild(num);
            if (c.facts.length) {
                var facts = hb.el('ul', 'hb-home-facts');
                c.facts.forEach(function (f) {
                    var li = hb.el('li');
                    li.setAttribute('data-tone', toneAttr(f.tone));
                    // 확인·지연 보조 숫자는 공통 배지(모양 + 글자, docs/40 부록 A) — 색만으로 알리지 않는다
                    var ft = markTone(f.tone);
                    li.appendChild(ft ? hb.status(ft, f.label) : hb.el('span', null, f.label));
                    li.appendChild(hb.el('b', 'hb-home-fact-num', hb.fmt.num(f.value, 0)));
                    facts.appendChild(li);
                });
                sec.appendChild(facts);
            }
            if (c.items.length) {
                var items = hb.el('ul', 'hb-home-items');
                c.items.forEach(function (it) {
                    var li = hb.el('li');
                    li.setAttribute('data-tone', toneAttr(it.tone));
                    var link = it.link;
                    var itHead = hb.el('div', 'hb-home-item-head');
                    li.appendChild(itHead);
                    if (link && c.progCd) {
                        // 항목 번호 = 그 조건으로 화면 열기(링크 조건은 서버가 형식 검사한 조회 칸 이름·값만)
                        var a = /** @type {HTMLButtonElement} */ (hb.el('button', 'hb-home-item-title hb-home-item-link', it.title));
                        a.type = 'button';
                        a.title = hb.t('js.home.openItem', it.title);
                        var to = c.progCd;
                        var toTitle = c.progTitle || c.progCd;
                        var params = link;
                        a.addEventListener('click', function () { openScreen(to, toTitle, params); });
                        itHead.appendChild(a);
                    } else {
                        itHead.appendChild(hb.el('b', 'hb-home-item-title', it.title));
                    }
                    // 늦은·확인할 항목: 왼쪽 막대 색에 더해 배지(모양 + 글자)
                    var mt = markTone(it.tone);
                    if (mt) { itHead.appendChild(hb.status(mt, hb.t(it.tone === 'ALERT' ? 'js.home.tone.alert' : 'js.home.tone.warn'))); }
                    var d = hb.el('span', 'hb-home-item-detail', it.detail);
                    d.title = it.detail;   // 줄임표로 잘린 글자 전체
                    li.appendChild(d);
                    items.appendChild(li);
                });
                sec.appendChild(items);
            }
        }
        var progCd = c.progCd;
        if (progCd) {
            var b = /** @type {HTMLButtonElement} */ (hb.el('button', 'hb-btn-secondary hb-home-open', hb.t('js.home.open')));
            b.type = 'button';
            var title = c.progTitle || progCd;
            b.setAttribute('aria-describedby', h.id);
            b.addEventListener('click', function () { openScreen(/** @type {string} */ (progCd), title); });
            sec.appendChild(b);
        }
        return sec;
    }

    /** @param {HomeView} view */
    function render(view) {
        cardsEl.textContent = '';
        if (!view.cards.length) {
            cardsEl.appendChild(hb.el('p', 'hb-muted hb-home-empty', hb.t('js.home.empty')));
        }
        view.cards.forEach(function (c) { cardsEl.appendChild(card(c)); });
        asOfEl.textContent = hb.t('js.home.asOf', hb.fmt.datetime(view.asOf))
            + (view.plant && view.plantToday !== view.today ? ' · ' + hb.t('js.home.plantToday', view.plant, view.plantToday) : '');
        hiddenNote.textContent = view.hiddenCount ? hb.t('js.home.hiddenCount', view.hiddenCount) : '';
        cardsEl.setAttribute('aria-busy', 'false');
    }

    /** 카드 하나만 다시 읽어 그 자리에 바꿔 넣는다 — 숨겼거나 권한을 잃은 카드(404)는 전체를 다시 읽는다 @param {string} code */
    function reloadCard(code) {
        return hb.latest('home.card.' + code, hb.api(withPlant('/api/home/cards/' + encodeURIComponent(code)))).then(function (res) {
            var r = /** @type {HomeCardReload} */ (res.data);
            var old = cardsEl.querySelector('[data-card="' + code + '"]');
            if (old) {
                var next = card(r.card);
                old.replaceWith(next);
                var b = /** @type {HTMLButtonElement | null} */ (next.querySelector('.hb-home-reload'));
                if (b) { b.focus(); }
            }
            msg.textContent = '';
        }, function (/** @type {HB.ApiError} */ err) {
            if (err.stale) { return; }
            if (err.status === 404) { return load(); }
            msg.textContent = err.message;
        });
    }

    /** @return {Promise<void>} */
    function load() {
        cardsEl.setAttribute('aria-busy', 'true');
        return hb.latest('home.cards', hb.api(withPlant('/api/home/cards'))).then(function (res) {
            render(/** @type {HomeView} */ (res.data));
            msg.textContent = '';
        }, /** @return {Promise<void> | undefined} */ function (/** @type {HB.ApiError} */ err) {
            if (err.stale) { return; }
            if (err.status === 400 && plant()) {
                // 기억한 공장이 사라졌거나 사용 중지 — 전체로 되돌리고 다시
                plantSel.value = '';
                rememberPlant('');
                return load();
            }
            cardsEl.setAttribute('aria-busy', 'false');
            msg.textContent = err.message;
        });
    }

    function schedule() {
        if (timer !== null) { window.clearInterval(timer); timer = null; }
        if (document.visibilityState === 'visible') {
            timer = window.setInterval(load, AUTO_MS);
        }
        stream();
    }

    // ---------- 실시간 신호(SSE 시범, docs/70 3장) ----------
    // 서버는 "알림함이 바뀜" 신호만 보낸다(데이터 없음) → 카드 API 를 다시 읽는다. 5분 주기 읽기는 그대로 둔다(신호를 못 받는 경우의 대비:
    // 다른 서버 인스턴스·프록시가 끊음·연결 상한). 보이는 동안만 연결, 가려지면 닫는다. 로그아웃·권한 없음(401·403)이면 브라우저가 다시 붙지 않는다.
    /** @type {EventSource | null} */
    var es = null;
    /** @type {number | null} */
    var signalTimer = null;

    function stream() {
        var visible = document.visibilityState === 'visible';
        if (!visible || typeof window.EventSource !== 'function') {
            if (es) { es.close(); es = null; }
            return;
        }
        if (es && es.readyState !== EventSource.CLOSED) { return; }
        es = new EventSource('/api/home/stream');
        es.addEventListener('inbox', function () {
            // 결재 여러 건이 한꺼번에 와도 한 번만 다시 읽는다
            if (signalTimer !== null) { window.clearTimeout(signalTimer); }
            signalTimer = window.setTimeout(function () { signalTimer = null; load(); }, 800);
        });
    }

    // ---------- 카드 설정(보이기·순서) ----------

    /** @param {HomeSetting[]} list */
    function openSettings(list) {
        var rows = list.slice();
        var d = /** @type {HTMLDialogElement} */ (hb.el('dialog', 'hb-dialog hb-home-settings'));
        d.setAttribute('aria-labelledby', 'homeSettingsTitle');
        var h = hb.el('h2', null, hb.t('js.home.settings'));
        h.id = 'homeSettingsTitle';
        d.appendChild(h);
        d.appendChild(hb.el('p', 'hb-muted', hb.t('js.home.settingsGuide')));
        d.appendChild(hb.el('p', 'hb-muted', hb.t('js.home.settingsGuide3')));
        var ul = hb.el('ul', 'hb-home-set-list');
        d.appendChild(ul);
        var out = hb.el('p', 'hb-msg');
        out.setAttribute('role', 'status');
        d.appendChild(out);

        /** @param {number} focusIdx @param {string} [focusDir] */
        function draw(focusIdx, focusDir) {
            ul.textContent = '';
            rows.forEach(function (r, i) {
                var li = hb.el('li');
                var label = hb.el('label', 'hb-home-set-name');
                var cb = /** @type {HTMLInputElement} */ (hb.el('input'));
                cb.type = 'checkbox';
                cb.checked = !r.hidden;
                cb.addEventListener('change', function () { r.hidden = !cb.checked; });
                label.appendChild(cb);
                label.appendChild(hb.el('span', null, r.title));
                li.appendChild(label);
                if (r.desc) { li.appendChild(hb.el('span', 'hb-home-set-desc hb-muted', r.desc)); }
                var tools = hb.el('span', 'hb-home-set-tools');
                [['up', '↑', 'js.home.moveUp', -1], ['down', '↓', 'js.home.moveDown', 1]].forEach(function (m) {
                    var b = /** @type {HTMLButtonElement} */ (hb.el('button', 'hb-btn-ghost', m[1]));
                    b.type = 'button';
                    b.setAttribute('data-dir', String(m[0]));
                    b.setAttribute('aria-label', m[2] === 'js.home.moveUp' ? hb.t('js.home.moveUp', r.title) : hb.t('js.home.moveDown', r.title));
                    var j = i + /** @type {number} */ (m[3]);
                    b.disabled = j < 0 || j >= rows.length;
                    b.addEventListener('click', function () {
                        var tmp = rows[i];
                        rows[i] = rows[j];
                        rows[j] = tmp;
                        draw(j, String(m[0]));
                    });
                    tools.appendChild(b);
                });
                li.appendChild(tools);
                ul.appendChild(li);
            });
            // 옮긴 뒤 같은 방향 버튼에 포커스를 남긴다(끝에 닿아 꺼졌으면 반대 방향) — 키보드로 계속 옮길 수 있게
            if (focusDir) {
                var row = ul.children[focusIdx];
                var btn = row && /** @type {HTMLButtonElement | null} */ (row.querySelector('button[data-dir="' + focusDir + '"]:not(:disabled)')
                    || row.querySelector('button:not(:disabled)'));
                if (btn) { btn.focus(); }
            }
        }
        draw(0);

        var bar = hb.el('div', 'hb-dialog-bar');
        var save = /** @type {HTMLButtonElement} */ (hb.el('button', null, hb.t('js.home.save')));
        save.type = 'button';
        // 내 설정 지우기는 되돌릴 수 없다 — 위험 외곽선 + 저장과 떨어진 자리(docs/40 7장 P1 ④)
        var def = /** @type {HTMLButtonElement} */ (hb.el('button', 'hb-btn-danger-outline hb-home-danger', hb.t('js.home.resetSettings')));
        def.type = 'button';
        var close = /** @type {HTMLButtonElement} */ (hb.el('button', 'hb-btn-secondary', hb.t('js.common.close')));
        close.type = 'button';
        bar.appendChild(save);
        bar.appendChild(close);
        bar.appendChild(def);
        d.appendChild(bar);
        close.addEventListener('click', function () { d.close(); });
        d.addEventListener('close', function () { d.remove(); });
        save.addEventListener('click', function () {
            hb.busy(save, function () {
                return hb.api('/api/home/settings', {method: 'PUT',
                    body: rows.map(function (r) { return {code: r.code, hidden: r.hidden}; })}).then(function () {
                    d.close();
                    return load();
                }, function (/** @type {HB.ApiError} */ err) { out.textContent = err.message; });
            });
        });
        def.addEventListener('click', function () {
            hb.dialog.confirm({title: hb.t('js.home.dlg.reset'), message: hb.t('js.home.dlg.resetMsg'), ok: hb.t('js.home.dlg.resetOk'),
                danger: true}).then(function (yes) {
                if (!yes) { return; }
                hb.busy(def, function () {
                    return hb.api('/api/home/settings/reset', {method: 'POST'}).then(function () {
                        d.close();
                        return load();
                    }, function (/** @type {HB.ApiError} */ err) { out.textContent = err.message; });
                });
            });
        });
        document.body.appendChild(d);
        d.showModal();
    }

    settingsBtn.addEventListener('click', function () {
        hb.busy(settingsBtn, function () {
            return hb.api('/api/home/settings').then(function (res) {
                openSettings(/** @type {HomeSetting[]} */ (res.data));
            }, function (/** @type {HB.ApiError} */ err) { msg.textContent = err.message; });
        });
    });

    refreshBtn.addEventListener('click', function () { hb.busy(refreshBtn, load); });
    document.addEventListener('visibilitychange', function () {
        // 가려져 있는 동안은 묻지 않는다. 다시 보이면 바로 한 번 읽고 주기를 다시 건다
        if (document.visibilityState === 'visible') { load(); }
        schedule();
    });
    plantSel.addEventListener('change', function () {
        rememberPlant(plant());
        load();
    });
    hb.loadMessages().then(function () {
        return loadPlants();
    }).then(function () {
        load();
        schedule();
    });
})();
