/*
 * POP 키오스크 공통 (docs/25 4.6) — hb.js 위에 얹는다. 셸·메뉴 없이 단말 화면만.
 *
 * hb.pop.api(url, options)        → hb.api + X-HB-Client: POP (접속 로그 구분, 참고값)
 * hb.pop.upload(url, formData)    → hb.upload + 같은 헤더(신고 사진)
 * hb.pop.scanner(input, onScan)   → 숨은 입력 칸을 항상 포커스. 스캔은 hb.scan.listen(…, {input}) 이 받는다(docs/50 C6 —
 *                                   웨지 = 그 칸 글자 + Enter, APK 스캐너 SDK = hb:scan 이벤트) → onScan(정리된 값)
 *                                   다른 입력이 포커스를 가져가지 않는 한 되돌린다. {pause, resume} — pause 동안은 듣지 않는다
 *                                   /js/common/hb.scan.js 를 hb.js 뒤에 넣은 화면에서만(POP·창고 PDA)
 * hb.pop.keypad(host, opts)       → 숫자 키패드(0~9·지우기·확정). opts {max, onChange(value), onConfirm(value, button)}
 * hb.pop.feedback(el, kind, text) → 결과 표시(ok/error/info) 3초 + 진동(되는 기기만)
 * hb.pop.network(el)              → 온라인/오프라인 표시
 * hb.pop.queue.send(url, body, opts) → 오프라인 대기열(IndexedDB write-ahead, 같은 clientReqId 재전송). URL 범용(POP 실적·PDA 창고)
 *   opts.label = 보낸 화면이 만든 요약 글자(거부 목록에 그대로 보임 — 화면마다 본문 모양이 달라서)
 *   .flush() → {sent, rejected} / .rejected() / .retry(id) / .discard(id) / .onChange(fn(보낼 건수, 거부된 건수))
 * hb.pop.reqId()                  → 요청 id(UUID)
 * hb.pop.badgeLogin(opts)         → 배지(+PIN) 작업자 로그인 흐름(POP·PDA 공용, docs/25 4.34) — {submit, onPin, cancel, pending, clear, gone}
 * hb.pop.pinDots(el)              → PIN 입력 점 표시 함수(키패드 onChange 에)
 * hb.pop.rejectedList(ul, opts)   → 대기열 거부 목록 그리기(다시 보내기·버리기 확인) — 화면은 줄 글자·문구 키만 넘긴다
 * hb.pop.keepAwake(opts)          → 화면 꺼짐 방지(docs/50 K7): APK = 껍데기 몫, 브라우저 = Wake Lock(다시 보이면 다시 잡음).
 *                                   {state(), release()}, opts.onChange(state) — native·active·released·failed·unsupported·off
 */
(function (global) {
    'use strict';

    var FEEDBACK_MS = 3000;

    function api(url, options) {
        options = options || {};
        var headers = {};
        Object.keys(options.headers || {}).forEach(function (k) { headers[k] = options.headers[k]; });
        headers['X-HB-Client'] = 'POP';
        var o = {};
        Object.keys(options).forEach(function (k) { o[k] = options[k]; });
        o.headers = headers;
        return hb.api(url, o);
    }

    /** hb.upload + X-HB-Client: POP (사진 올리기 — 접속 로그 구분) */
    function upload(url, formData) {
        return hb.upload(url, formData, {'X-HB-Client': 'POP'});
    }

    /**
     * hb.pop.scanner 만들기(C6) — env = {scan: () => hb.scan, doc, defer(fn, ms)} (브라우저는 document·setTimeout, 시험은 가짜).
     * 받는 일은 hb.scan 이 하고(웨지·native 한 경로), 여기는 숨은 칸 포커스 되돌리기와 pause/resume 만 남는다
     */
    function makeScanner(env) {
        return function scanner(input, onScan) {
            var scan = env.scan();
            if (!scan) { throw new Error('hb.pop.scanner: /js/common/hb.scan.js is not loaded'); }
            var paused = false;
            var off = null;
            function listen() {
                off = scan.listen(function (r) { onScan(r.value); }, {input: input});
            }
            function refocus() {
                if (paused) { return; }
                var a = env.doc.activeElement;
                // 버튼을 누른 직후·다른 입력 칸은 건드리지 않는다
                if (a && a !== env.doc.body && a !== input && (a.tagName === 'INPUT' || a.tagName === 'TEXTAREA' || a.tagName === 'SELECT')) {
                    return;
                }
                input.focus({preventScroll: true});
            }
            input.addEventListener('blur', function () { env.defer(refocus, 200); });
            env.doc.addEventListener('click', function () { env.defer(refocus, 0); });
            listen();
            refocus();
            return {
                pause: function () {
                    paused = true;
                    if (off) { off(); off = null; }
                    input.blur();
                },
                resume: function () {
                    paused = false;
                    input.value = '';
                    if (!off) { listen(); }
                    refocus();
                }
            };
        };
    }

    var scanner = makeScanner({
        scan: function () { return global.hb && global.hb.scan; },
        doc: typeof document !== 'undefined' ? document : null,
        defer: function (fn, ms) { setTimeout(fn, ms); }
    });

    function keypad(host, opts) {
        var value = '';
        var max = opts.max || 8;
        host.textContent = '';
        function set(v) {
            value = v;
            if (opts.onChange) { opts.onChange(value); }
        }
        // decimal: 확정 대신 소수점(확정은 화면의 큰 버튼) — 수량 입력용
        var keys = opts.decimal ? ['1', '2', '3', '4', '5', '6', '7', '8', '9', '.', '0', 'DEL']
            : ['1', '2', '3', '4', '5', '6', '7', '8', '9', 'DEL', '0', 'OK'];
        keys.forEach(function (k) {
            var b = hb.el('button', k === 'OK' ? 'hb-pop-primary' : null,
                k === 'DEL' ? hb.t('js.pop.keyDel') : k === 'OK' ? hb.t('js.pop.keyOk') : k);
            b.type = 'button';
            b.setAttribute('data-key', k);
            b.addEventListener('click', function () {
                if (k === 'DEL') {
                    set(value.slice(0, -1));
                } else if (k === 'OK') {
                    if (value && opts.onConfirm) { opts.onConfirm(value, b); }
                } else if (k === '.') {
                    if (value.indexOf('.') < 0 && value.length < max) { set((value || '0') + '.'); }
                } else if (value.length < max) {
                    set(value + k);
                }
            });
            host.appendChild(b);
        });
        return {
            clear: function () { set(''); },
            set: function (v) { value = v || ''; },
            value: function () { return value; }
        };
    }

    var feedbackTimer = null;
    function feedback(el, kind, text) {
        clearTimeout(feedbackTimer);
        el.setAttribute('data-kind', kind);
        el.textContent = text || '';
        if (global.navigator && typeof global.navigator.vibrate === 'function') {
            try { global.navigator.vibrate(kind === 'error' ? [120, 80, 120] : 60); } catch (e) { /* 지원 안 함 */ }
        }
        feedbackTimer = setTimeout(function () {
            el.textContent = '';
            el.removeAttribute('data-kind');
        }, FEEDBACK_MS);
    }

    function network(el) {
        function sync() {
            var on = global.navigator.onLine !== false;
            el.setAttribute('data-online', String(on));
            el.textContent = hb.t(on ? 'js.pop.online' : 'js.pop.offline');
        }
        global.addEventListener('online', sync);
        global.addEventListener('offline', sync);
        sync();
    }

    /*
     * 오프라인 대기열(docs/25 D6, 4.20) — 실적 확정만. 보내기 전에 IndexedDB 에 먼저 쓰고(write-ahead) 성공하면 지운다.
     * 끊김·타임아웃·5xx·401(단말 세션 만료) 는 남겨 두고 온라인이 되면 같은 clientReqId 로 순서대로 다시 보낸다(서버가 처음 응답을 돌려준다).
     * 바로 보낼 때(send)의 그 밖 4xx 는 빼고 호출한 쪽에 오류로(작업자가 화면에서 본다).
     * 다시 보낼 때(flush)의 그 밖 4xx 는 **지우지 않고 "거부됨" 으로 남긴다** — 재고 부족·마감(409)처럼 상태에 따라 달라지는 거부로
     * 현장 실적이 작업자 모르게 사라지지 않게. 작업자가 사유를 보고 다시 보내기·버리기를 고른다.
     * IndexedDB 를 못 쓰면 메모리(창을 닫으면 잃음 — 표시로 알린다)
     */
    var DB_NAME = 'hb-pop', STORE = 'outbox';
    var PENDING = 'PENDING', REJECTED = 'REJECTED';

    /** 다시 보내면 될 오류(끊김·서버·세션 만료) — 그 밖은 서버가 거부한 것 */
    function retryable(err) { return !err || !err.status || err.status >= 500 || err.status === 401; }

    /**
     * 대기열 본체 — 저장소·전송을 받아 만든다(node 단위 시험에서 가짜로).
     * io: {put(item), del(id), all() → 보관 순서 목록, post(url, body) → Promise}
     */
    function makeQueue(io) {
        var listeners = [];
        var flushing = null;
        function pending(list) { return list.filter(function (x) { return (x.state || PENDING) === PENDING; }); }
        function rejectedOf(list) { return list.filter(function (x) { return x.state === REJECTED; }); }
        function notify() {
            return io.all().then(function (l) {
                var p = pending(l).length, r = rejectedOf(l).length;
                listeners.forEach(function (f) { f(p, r); });
            });
        }
        function reject(item, err) {
            var kept = {id: item.id, url: item.url, body: item.body, label: item.label, at: item.at, state: REJECTED,
                error: {status: err.status, code: err.code || '', message: err.message || '',
                    detail: (err.errors || []).map(function (x) { return x.message; }).concat(err.details || []).join(' / ')}};
            return io.put(kept);
        }
        var q = {
            /** body.clientReqId 필수, opts.label = 거부 목록 요약 글자(선택). 결과: {data} 또는 {queued: true} */
            send: function (url, body, opts) {
                var item = {id: body.clientReqId, url: url, body: body, label: opts && opts.label ? String(opts.label) : null, at: Date.now(),
                    state: PENDING};
                return io.put(item).then(function () {
                    return io.post(url, body).then(function (res) {
                        return io.del(item.id).then(function () { notify(); return res; });
                    }, function (err) {
                        if (retryable(err)) { notify(); return {queued: true}; }
                        return io.del(item.id).then(function () { notify(); throw err; });
                    });
                });
            },
            /** 대기 건을 순서대로 다시 보낸다 — 거부된 건은 남기고 다음 건으로. 결과: {sent, rejected}(이번에 보낸·거부된 건수) */
            flush: function () {
                if (flushing) { return flushing; }
                var sent = 0, rejected = 0;
                flushing = io.all().then(function (list) {
                    return pending(list).reduce(function (p, item) {
                        return p.then(function (stop) {
                            if (stop) { return true; }
                            return io.post(item.url, item.body).then(function () {
                                sent++;
                                return io.del(item.id).then(function () { return false; });
                            }, function (err) {
                                if (retryable(err)) { return true; }      // 아직 끊김 — 다음에(순서 유지)
                                rejected++;
                                return reject(item, err).then(function () { return false; });
                            });
                        });
                    }, Promise.resolve(false));
                }).then(function () {
                    flushing = null;
                    return notify().then(function () { return {sent: sent, rejected: rejected}; });
                }, function () {
                    flushing = null;
                    return notify().then(function () { return {sent: sent, rejected: rejected}; });
                });
                return flushing;
            },
            /** 보낼 건수(거부됨 제외) */
            count: function () { return io.all().then(function (l) { return pending(l).length; }); },
            /** 거부된 건(보관 순서) — {id, body, at, error: {status, code, message, detail}} */
            rejected: function () { return io.all().then(rejectedOf); },
            /** 거부된 건을 다시 대기로 돌려 바로 보낸다(같은 clientReqId) */
            retry: function (id) {
                return io.all().then(function (l) {
                    var it = rejectedOf(l).filter(function (x) { return x.id === id; })[0];
                    if (!it) { return {sent: 0, rejected: 0}; }
                    return io.put({id: it.id, url: it.url, body: it.body, label: it.label, at: it.at, state: PENDING})
                        .then(function () { return q.flush(); });
                });
            },
            /** 거부된 건을 버린다(작업자가 고름) */
            discard: function (id) { return io.del(id).then(function () { return notify(); }); },
            /** fn(보낼 건수, 거부된 건수) */
            onChange: function (fn) { listeners.push(fn); notify(); }
        };
        return q;
    }

    var memory = {};
    function openDb() {
        return new Promise(function (resolve) {
            if (!global.indexedDB) { resolve(null); return; }
            var req;
            try { req = global.indexedDB.open(DB_NAME, 1); } catch (e) { resolve(null); return; }
            req.onupgradeneeded = function () { req.result.createObjectStore(STORE, {keyPath: 'id'}); };
            req.onsuccess = function () { resolve(req.result); };
            req.onerror = function () { resolve(null); };
        });
    }
    function store(mode, fn) {
        return openDb().then(function (db) {
            if (!db) { return fn(null); }
            return new Promise(function (resolve, reject) {
                var tx = db.transaction(STORE, mode);
                var out = fn(tx.objectStore(STORE));
                tx.oncomplete = function () { resolve(out && out.result !== undefined ? out.result : out); };
                tx.onerror = function () { reject(tx.error); };
            });
        });
    }
    var queue = makeQueue({
        put: function (item) { return store('readwrite', function (s) { if (!s) { memory[item.id] = item; return null; } return s.put(item); }); },
        del: function (id) { return store('readwrite', function (s) { if (!s) { delete memory[id]; return null; } return s.delete(id); }); },
        all: function () {
            return store('readonly', function (s) {
                if (!s) { return Object.keys(memory).map(function (k) { return memory[k]; }); }
                return s.getAll();
            }).then(function (list) { return (list || []).sort(function (a, b) { return a.at - b.at; }); });
        },
        post: function (url, body) { return api(url, {method: 'POST', body: body}); }
    });

    /**
     * 배지(+PIN) 작업자 로그인 — POP 작업장 홈·PDA 창고 홈 공용(docs/25 4.34). 배지 → 서버가 PIN 을 요구하면 PIN 화면,
     * 맞으면 인사·onSignedIn, 틀린 PIN 은 PIN 화면에 머물고 그 밖 오류는 배지 화면으로. 배지 값은 PIN 을 누르는 동안만 메모리에.
     * deps: {post(body) → Promise<{data}|null>, busy(button, fn), pad: {clear()}, showPanel(name), hello(r), fail(err),
     *        onPinAsked(r), onSignedIn(r) → Promise, onGone()} — node 단위 시험이 가짜로 넣는다
     */
    function makeBadgeLogin(d) {
        var pending = null;
        function submit(value, pin, button) {
            var send = function () { return d.post({badge: value, pin: pin || null}); };
            return (button ? d.busy(button, send) : send()).then(function (res) {
                if (!res) { return; }
                var r = res.data;
                d.pad.clear();
                if (r.needPin) {
                    pending = value;
                    d.onPinAsked(r);
                    d.showPanel('pin');
                    return;
                }
                pending = null;
                d.hello(r);
                return d.onSignedIn(r);
            }, function (err) {
                d.pad.clear();
                if (err.code !== 'PIN_WRONG') {
                    pending = null;
                    d.showPanel('badge');
                }
                d.fail(err);
            });
        }
        return {
            submit: submit,
            /** 키패드 확정 — 배지를 받아 둔 동안만 PIN 으로 */
            onPin: function (pin, button) { return pending ? submit(pending, pin, button) : Promise.resolve(); },
            /** [취소] — 받아 둔 배지를 지우고 배지 화면으로 */
            cancel: function () { pending = null; d.pad.clear(); d.showPanel('badge'); },
            /** 배지를 받아 PIN 을 기다리는 중인가(세션 새로 읽기가 화면을 배지로 돌리지 않게) */
            pending: function () { return pending !== null; },
            /** 작업자가 이미 있음(세션) — 받아 둔 배지를 버린다 */
            clear: function () { pending = null; },
            /** 작업자가 내려갔으면(비활동·다른 단말, 428 POP_NO_WORKER) 배지 화면으로 + onGone. 처리했으면 true */
            gone: function (err) {
                if (err && err.code === 'POP_NO_WORKER') {
                    d.showPanel('badge');
                    d.onGone();
                    return true;
                }
                return false;
            }
        };
    }

    /**
     * 화면용 배지 로그인 — opts: {url(배지 API), pad, pinWho(이름 칸), feedback(결과 칸), showPanel, onSignedIn, onGone, fail}
     */
    function badgeLogin(o) {
        return makeBadgeLogin({
            post: function (body) { return api(o.url, {method: 'POST', body: body}); },
            busy: function (button, fn) { return hb.busy(button, fn); },
            pad: o.pad,
            showPanel: o.showPanel,
            hello: function (r) { feedback(o.feedback, 'ok', hb.t('js.pop.hello', r.workerNm)); },
            onPinAsked: function (r) { o.pinWho.textContent = hb.t('js.pop.hello', r.workerNm); },
            onSignedIn: o.onSignedIn,
            onGone: o.onGone,
            fail: o.fail
        });
    }

    /** PIN 입력 점(누른 자리 수만큼, 숫자는 보이지 않음) — 키패드 onChange 에 */
    function pinDots(el) {
        return function (v) {
            el.textContent = '';
            for (var i = 0; i < v.length; i++) { el.appendChild(hb.el('span')); }
        };
    }

    /**
     * 대기열 거부 목록 — 줄마다 화면이 만든 글자 + 사유 + [다시 보내기]·[버리기](위험 확인). 그리기 함수를 돌려준다.
     * opts: {describe(it) → 글자(없으면 label·url), keys: {none, resend, discard, dlgDiscard, confirmDiscard}, onRetried(res)}
     */
    function rejectedList(ul, o) {
        var describe = o.describe || function (it) { return it.label || it.url; };
        function render() {
            return queue.rejected().then(function (list) {
                ul.textContent = '';
                if (!list.length) {
                    ul.appendChild(hb.el('li', 'hb-pop-sub', hb.t(o.keys.none)));
                    return;
                }
                list.forEach(function (it) {
                    var li = hb.el('li', 'hb-pop-rejected-row');
                    li.appendChild(hb.el('span', null, describe(it)));
                    li.appendChild(hb.el('span', 'hb-pop-reason', (it.error && it.error.message || '') + (it.error && it.error.detail
                        ? ' — ' + it.error.detail : '')));
                    var again = hb.el('button', 'hb-pop-primary', hb.t(o.keys.resend));
                    again.type = 'button';
                    again.addEventListener('click', function () {
                        hb.busy(again, function () { return queue.retry(it.id); }).then(function (res) {
                            if (o.onRetried) { o.onRetried(res); }
                            render();
                        });
                    });
                    var drop = hb.el('button', 'hb-pop-danger', hb.t(o.keys.discard));
                    drop.type = 'button';
                    drop.addEventListener('click', function () {
                        hb.dialog.confirm({title: hb.t(o.keys.dlgDiscard), message: hb.t(o.keys.confirmDiscard), ok: hb.t(o.keys.discard),
                            danger: true}).then(function (ok) {
                            if (!ok) { return; }
                            hb.busy(drop, function () { return queue.discard(it.id); }).then(render);
                        });
                    });
                    li.appendChild(again);
                    li.appendChild(drop);
                    ul.appendChild(li);
                });
            });
        }
        return render;
    }

    /** 요청 id — 단말이 만들어 재전송 때 그대로 쓴다 */
    function reqId() {
        if (global.crypto && typeof global.crypto.randomUUID === 'function') { return global.crypto.randomUUID(); }
        var a = new Uint8Array(16);
        global.crypto.getRandomValues(a);
        return Array.prototype.map.call(a, function (x) { return ('0' + x.toString(16)).slice(-2); }).join('');
    }

    /*
     * 화면 꺼짐 방지 (docs/50 K7·6.1, docs/27 U12) — POP 작업장 홈이 한 번 부른다. env = {nav, doc, native()} (시험은 가짜)
     * - APK(다리 YeonShell 있음): 키오스크가 켜진 동안 껍데기가 FLAG_KEEP_SCREEN_ON(kiosk(true)) — 여기서는 아무것도 안 한다 → 'native'
     * - 브라우저: Screen Wake Lock(navigator.wakeLock.request('screen'), https·localhost 만). 가려지면 OS 가 풀고('released'),
     *   다시 보이면 다시 잡는다('active'). 지원 안 함 = 'unsupported'(아무 일 없음 — OS 화면 꺼짐 설정, 설치 안내),
     *   거절(배터리 절약 등) = 'failed' — 다음에 보일 때 다시 시도
     * - 작업자 비활동 내려감(POP_WORKER_IDLE_MIN)은 그대로 — 화면이 켜져 있어도 서버가 작업자를 내린다(K7)
     * 반환 {state(), release()}. opts.onChange(state) — 상태가 바뀔 때(표시할지·어떻게는 화면 몫)
     */
    function makeKeepAwake(env) {
        return function keepAwake(opts) {
            var onChange = opts && opts.onChange;
            var state = 'idle', sentinel = null, asking = false, stopped = false;
            function set(s) {
                if (state === s) { return; }
                state = s;
                if (onChange) { onChange(s); }
            }
            var handle = {state: function () { return state; }, release: function () { }};
            if (env.native()) { set('native'); return handle; }
            var wl = env.nav && env.nav.wakeLock;
            if (!wl || typeof wl.request !== 'function') { set('unsupported'); return handle; }
            function request() {
                if (stopped || sentinel || asking || env.doc.visibilityState !== 'visible') { return; }
                asking = true;
                Promise.resolve().then(function () { return wl.request('screen'); }).then(function (s) {
                    asking = false;
                    if (stopped) { s.release(); return; }
                    sentinel = s;
                    s.addEventListener('release', function () {
                        if (sentinel !== s) { return; }
                        sentinel = null;
                        if (!stopped) { set('released'); }
                    });
                    set('active');
                }, function () {
                    asking = false;
                    if (!stopped) { set('failed'); }
                });
            }
            function onVisible() { if (env.doc.visibilityState === 'visible') { request(); } }
            env.doc.addEventListener('visibilitychange', onVisible);
            request();
            handle.release = function () {
                if (stopped) { return; }
                stopped = true;
                env.doc.removeEventListener('visibilitychange', onVisible);
                var s = sentinel;
                sentinel = null;
                if (s) { s.release(); }
                set('off');
            };
            return handle;
        };
    }

    var keepAwake = makeKeepAwake({
        nav: global.navigator,
        doc: typeof document !== 'undefined' ? document : null,
        // 다리 판별은 hb.kiosk 와 같다(Capacitor.Plugins.YeonShell.kiosk 가 함수) — hb.kiosk 를 안 싣는 화면에서도
        native: function () {
            var c = global.Capacitor;
            var s = c && c.Plugins && c.Plugins.YeonShell;
            return !!(s && typeof s.kiosk === 'function');
        }
    });

    if (global.hb) {
        global.hb.pop = {api: api, upload: upload, scanner: scanner, keypad: keypad, feedback: feedback, network: network, queue: queue, reqId: reqId,
            badgeLogin: badgeLogin, pinDots: pinDots, rejectedList: rejectedList, keepAwake: keepAwake};
    }
    // node 단위 시험(src/test/js/hb.pop.queue.test.js) — 브라우저에는 module 이 없다
    if (typeof module === 'object' && module.exports) {
        module.exports = {makeQueue: makeQueue, retryable: retryable, makeBadgeLogin: makeBadgeLogin, makeScanner: makeScanner,
            makeKeepAwake: makeKeepAwake};
    }
})(typeof window !== 'undefined' ? window : globalThis);
