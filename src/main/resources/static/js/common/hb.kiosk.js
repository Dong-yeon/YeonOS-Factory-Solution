// @ts-check
/*
 * 키오스크 다리 연결 (docs/50 K3~K6·Q7, MES 계약 docs/25 4.52) — hb.js·hb.pop.js 뒤에 넣는다(POP 작업장 홈).
 *
 * 화면은 APK 다리(window.Capacitor.Plugins.YeonShell)를 직접 부르지 않고 이것만 쓴다(docs/50 6.1).
 * 다리가 없으면(크롬 등 브라우저) 모두 아무 일도 하지 않는다 — 서버도 부르지 않는다. 잠금은 기기 쪽 설정(설치 안내).
 *
 * hb.kiosk.available()      → 다리 있음(APK 웹뷰)
 * hb.kiosk.lock()           → 고정 켬 kiosk(true) + 다시 잠금 타이머 정리 → 켰는가. 승인 없음(홈이 열릴 때·작업자가 바뀔 때)
 * hb.kiosk.exit(badge, pin) → POST /api/pop/kiosk/exit → **성공 응답을 받은 뒤에만** kiosk(false), relockAfterSec 뒤 lock().
 *                             실패 응답은 그대로 reject(고정 유지). 다리 없으면 null(서버 안 부름)
 *                             - 응답을 기다리는 사이 다시 잠갔으면(작업자 바뀜·lock) 늦게 온 성공으로 풀지 않는다 → reject {code: KIOSK_STALE} (KS8, QA K-c)
 *                             - 서버는 성공으로 적었는데 풀지 못했으면(늦은 응답 KS8 · 기기가 kiosk(false) 거절) 응답의 exitRef 로
 *                               POST /api/pop/kiosk/unlock-failed {exitRef, reason: 'stale' | 'refused'} 을 한 번 알린다(KS9, QA K-d —
 *                               서버 API 는 MES 몫. exitRef 가 없는 응답이면 알리지 않는다. 알림 실패는 화면에 영향 없음)
 * hb.kiosk.attach()         → POP 홈 한 줄: lock() + 화면 오른쪽 위 구석(48px) 5초 길게 누르기 → 감독자 대화상자(배지 스캔 → PIN).
 *                             반환 {isOpen, badge(value), worker(code), open, close}
 *                             - 배지: 스캐너 SDK(hb:scan)는 화면의 스캐너가 받아 isOpen() 이면 badge(v) 로 넘긴다(한 화면에 듣는 곳 하나 — hb.scan C1).
 *                               키보드 웨지는 showModal 이 대화상자 밖(화면의 숨은 스캔 칸)을 막으므로 **대화상자 안 숨은 칸**이 받는다
 *                               — 열 때 그 칸에 포커스, 그 칸의 Enter = 배지(웨지 Enter 가 [취소]를 누르지 않게, KS10·QA K-e)
 *                             - worker(code): 화면이 지금 작업자를 알릴 때마다 — 앞과 다르면 lock()(작업자 바뀜 = 다시 잠금)
 */
(function (global) {
    'use strict';

    var EXIT_URL = '/api/pop/kiosk/exit';
    var UNLOCK_FAILED_URL = '/api/pop/kiosk/unlock-failed';
    var DEFAULT_RELOCK_SEC = 600;   // 서버가 값을 주지 않을 때(지금 서버는 늘 600 — 4.52 ①)
    var MAX_RELOCK_SEC = 3600;
    var HOLD_MS = 5000;             // 구석 길게 누르기(K4)
    var CORNER_PX = 48;             // 오른쪽 위 구석 크기

    /** 오른쪽 위 구석 안인가 @param {number} x @param {number} y @param {number} width */
    function inCorner(x, y, width) { return y >= 0 && y <= CORNER_PX && x <= width && x >= width - CORNER_PX; }

    /**
     * 화면과 떨어진 핵심 — node 시험이 가짜 다리·서버·타이머로 부른다
     * @param {{bridge: () => ({kiosk: (o: {on: boolean}) => unknown} | null),
     *          post: (body: {badge: string, pin: string}) => Promise<{data: HB.KioskExitReply}>,
     *          report: (body: {exitRef: string, reason: 'stale' | 'refused'}) => Promise<unknown>,
     *          setTimer: (fn: () => void, ms: number) => unknown, clearTimer: (id: unknown) => void}} d
     */
    function makeKiosk(d) {
        /** @type {unknown} */
        var timer = null;
        /** @type {string | null | undefined} 아직 모름 = undefined */
        var lastWorker;
        /** 잠금 세대 — lock() 마다 하나씩. 빠져나오기 응답이 왔을 때 보낼 때와 다르면 그사이 다시 잠근 것(KS8) */
        var gen = 0;

        function stopTimer() {
            if (timer !== null) {
                d.clearTimer(timer);
                timer = null;
            }
        }

        /** @returns {Promise<boolean>} */
        function lock() {
            gen++;
            stopTimer();
            var b = d.bridge();
            if (!b) { return Promise.resolve(false); }
            var bridge = b;
            // OS 가 화면 고정을 거절해도 화면은 계속 쓴다(MDM 없는 기기는 OS 가 확인을 묻는다 — K3)
            return Promise.resolve().then(function () { return bridge.kiosk({on: true}); }).then(function () { return true; }, function () { return false; });
        }

        /** @param {unknown} sec */
        function relockMs(sec) {
            var n = typeof sec === 'number' && isFinite(sec) && sec > 0 ? sec : DEFAULT_RELOCK_SEC;
            return Math.min(n, MAX_RELOCK_SEC) * 1000;
        }

        /**
         * @param {string} badge @param {string} pin
         * @returns {Promise<HB.KioskExitReply | null>}
         */
        function exit(badge, pin) {
            var b = d.bridge();
            if (!b) { return Promise.resolve(null); }
            var bridge = b;
            var sent = gen;
            return d.post({badge: badge, pin: pin}).then(function (res) {
                var reply = res && res.data;
                if (!reply) { throw new Error('kiosk exit: empty reply'); }   // 확인 응답이 아니면 풀지 않는다
                var ok = reply;
                if (gen !== sent) {   // 기다리는 사이 다시 잠갔다(작업자 바뀜) — 늦은 성공으로 풀지 않는다
                    report(ok, 'stale');
                    throw stale();
                }
                return Promise.resolve().then(function () { return bridge.kiosk({on: false}); }).then(function () {
                    if (gen !== sent) { throw stale(); }   // 다리를 부르는 사이 다시 잠갔다 — 다리 쪽 순서대로 잠긴 상태, 타이머 없음
                    stopTimer();
                    timer = d.setTimer(function () { timer = null; lock(); }, relockMs(ok.relockAfterSec));
                    return ok;
                }, function (/** @type {unknown} */ err) {
                    report(ok, 'refused');   // 서버는 "빠져나옴" 으로 적었는데 기기가 거절 — 잠긴 채
                    throw err;
                });
            });
        }

        /** KS9 — exitRef 가 있는 응답만, 한 번, 실패는 삼킨다 @param {HB.KioskExitReply} reply @param {'stale' | 'refused'} reason */
        function report(reply, reason) {
            var ref = reply.exitRef;
            if (typeof ref !== 'string' || !ref) { return; }
            var exitRef = ref;
            Promise.resolve().then(function () { return d.report({exitRef: exitRef, reason: reason}); }).catch(function () { /* 알림만 — 화면 흐름과 무관 */ });
        }

        function stale() {
            return Object.assign(new Error('kiosk exit: relocked while waiting'), {code: 'KIOSK_STALE'});
        }

        /** 작업자 알림 — 처음 알림은 기준만 잡고(홈이 열릴 때 이미 lock), 앞과 다르면 다시 잠근다 @param {string | null | undefined} code */
        function worker(code) {
            var c = code || null;
            if (lastWorker !== undefined && lastWorker !== c) { lock(); }
            lastWorker = c;
        }

        return {
            available: function () { return !!d.bridge(); },
            lock: lock,
            exit: exit,
            worker: worker,
            /** 다시 잠금 대기 중(빠져나온 상태) — 시험·화면 참고용 */
            unlocked: function () { return timer !== null; }
        };
    }

    /** APK 다리 — 서버 화면에서도 Capacitor 가 넣는다(허용 출처 = 저장한 서버 하나, S3). 없으면 null */
    function findBridge() {
        var w = /** @type {any} */ (global);
        var s = w.Capacitor && w.Capacitor.Plugins && w.Capacitor.Plugins.YeonShell;
        return s && typeof s.kiosk === 'function' ? s : null;
    }

    var core = makeKiosk({
        bridge: findBridge,
        post: function (body) {
            var pop = /** @type {any} */ (global.hb).pop;
            return pop.api(EXIT_URL, {method: 'POST', body: body});
        },
        report: function (body) {
            var pop = /** @type {any} */ (global.hb).pop;
            return pop.api(UNLOCK_FAILED_URL, {method: 'POST', body: body});
        },
        setTimer: function (fn, ms) { return global.setTimeout(fn, ms); },
        clearTimer: function (id) { global.clearTimeout(/** @type {number} */ (id)); }
    });

    /** 다리 없을 때 attach 가 돌려주는 것 — 화면 코드는 분기 없이 같은 줄을 쓴다 */
    var INERT = {
        isOpen: function () { return false; },
        badge: function (/** @type {string} */ _v) { },
        worker: function (/** @type {string | null | undefined} */ _c) { },
        open: function () { },
        close: function () { }
    };

    /** @returns {HB.KioskHome} */
    function attach() {
        if (!core.available()) { return INERT; }
        /** @type {HB.Hb} */
        var hb = global.hb;
        var pop = /** @type {any} */ (hb).pop;
        core.lock();

        var dlg = /** @type {HTMLDialogElement} */ (hb.el('dialog', 'hb-dialog hb-kiosk-dialog'));
        dlg.setAttribute('aria-labelledby', 'hbKioskTitle');
        var title = hb.el('h2', null, hb.t('js.kiosk.title'));
        title.id = 'hbKioskTitle';
        var step = hb.el('p', 'hb-kiosk-step', hb.t('js.kiosk.scan'));
        var pinBox = hb.el('div', 'hb-kiosk-pin');
        pinBox.hidden = true;
        var dots = hb.el('div', 'hb-pop-dots');
        dots.setAttribute('aria-hidden', 'true');
        var pad = hb.el('div', 'hb-pop-keypad');
        pinBox.appendChild(hb.el('p', null, hb.t('js.kiosk.pin')));
        pinBox.appendChild(dots);
        pinBox.appendChild(pad);
        var msg = hb.el('p', 'hb-kiosk-msg');
        msg.setAttribute('role', 'alert');
        var cancel = /** @type {HTMLButtonElement} */ (hb.el('button', 'hb-btn-secondary', hb.t('js.kiosk.cancel')));
        cancel.type = 'button';
        // 웨지 스캐너용 숨은 칸(KS10) — 화면의 #scan 과 같은 모양(화면 밖·화상 키보드 없음)
        var scanIn = /** @type {HTMLInputElement} */ (hb.el('input', 'hb-pop-scan'));
        scanIn.setAttribute('inputmode', 'none');
        scanIn.autocomplete = 'off';
        scanIn.setAttribute('aria-label', hb.t('js.kiosk.scan'));
        [title, step, pinBox, msg, scanIn, cancel].forEach(function (x) { dlg.appendChild(x); });
        document.body.appendChild(dlg);

        /** @type {string | null} */
        var badge = null;
        var keypad = pop.keypad(pad, {max: 8, onChange: pop.pinDots(dots), onConfirm: submit});

        /** @param {boolean} badgeRead */
        function showStep(badgeRead) {
            step.hidden = badgeRead;
            pinBox.hidden = !badgeRead;
            keypad.clear();
        }
        /** 숨은 칸으로 포커스 — 키패드·빈 곳을 누른 뒤에도(웨지 Enter 가 눌린 단추를 다시 누르지 않게) */
        function focusScan() {
            if (dlg.open && document.activeElement !== scanIn) { scanIn.focus({preventScroll: true}); }
        }
        function open() {
            if (dlg.open) { return; }
            badge = null;
            msg.textContent = '';
            showStep(false);
            scanIn.value = '';
            dlg.showModal();
            focusScan();   // showModal 의 첫 포커스([취소])를 숨은 칸으로
        }
        function close() {
            badge = null;
            keypad.clear();
            if (dlg.open) { dlg.close(); }
        }
        /** @param {string} v */
        function readBadge(v) {
            if (!dlg.open || !v) { return; }
            badge = v;
            msg.textContent = '';
            showStep(true);
        }
        /** @param {string} pin @param {HTMLButtonElement} button */
        function submit(pin, button) {
            if (!badge) { return; }
            var b = badge;
            hb.busy(button, function () { return core.exit(b, pin); }).then(function (/** @type {HB.KioskExitReply | null} */ reply) {
                if (reply) { close(); }
            }, function (/** @type {{code?: string, message?: string}} */ err) {
                // 실패면 고정 유지 — 배지부터 다시(틀린 배지·자격 없음일 수 있다, 4.37 승인과 같은 흐름)
                badge = null;
                showStep(false);
                msg.textContent = err && err.code === 'KIOSK_STALE' ? hb.t('js.kiosk.stale')
                    : (err && err.message) || hb.t('js.kiosk.failed');
                focusScan();
            });
        }
        scanIn.addEventListener('keydown', function (/** @type {KeyboardEvent} */ e) {
            if (e.key !== 'Enter' || e.isComposing) { return; }
            e.preventDefault();   // 대화상자의 단추를 누르지 않는다
            var raw = scanIn.value;
            scanIn.value = '';
            var scan = hb.scan;
            readBadge(scan && scan.clean ? scan.clean(raw) : raw.trim());
        });
        dlg.addEventListener('click', function (/** @type {Event} */ e) {
            if (e.target !== cancel) { global.setTimeout(focusScan, 0); }
        });
        cancel.addEventListener('click', close);
        dlg.addEventListener('cancel', function (/** @type {Event} */ e) { e.preventDefault(); close(); });   // Esc

        // 구석 길게 누르기 — 덮는 요소 없이 문서에서 자리만 본다(머리 단추를 막지 않게). 손을 떼거나 구석을 벗어나면 취소,
        // 대화상자를 연 뒤 손을 뗄 때의 클릭 한 번은 밑의 단추에 가지 않게 버린다
        /** @type {number | null} */
        var hold = null;
        var swallowClick = false;
        function release() {
            if (hold !== null) { global.clearTimeout(hold); hold = null; }
        }
        /** @param {PointerEvent} e */
        function corner(e) { return inCorner(e.clientX, e.clientY, global.innerWidth); }
        document.addEventListener('pointerdown', function (/** @type {PointerEvent} */ e) {
            release();
            swallowClick = false;
            if (dlg.open || !corner(e)) { return; }
            hold = global.setTimeout(function () { hold = null; swallowClick = true; open(); }, HOLD_MS);
        }, true);
        document.addEventListener('pointermove', function (/** @type {PointerEvent} */ e) { if (hold !== null && !corner(e)) { release(); } }, true);
        document.addEventListener('pointerup', release, true);
        document.addEventListener('pointercancel', release, true);
        document.addEventListener('click', function (/** @type {Event} */ e) {
            if (swallowClick) { swallowClick = false; e.preventDefault(); e.stopPropagation(); }
        }, true);

        return {isOpen: function () { return dlg.open; }, badge: readBadge, worker: core.worker, open: open, close: close};
    }

    if (global.hb) {
        global.hb.kiosk = {available: core.available, lock: core.lock, exit: core.exit, attach: attach};
    }
    // node 단위 시험(src/test/js/hb.kiosk.test.js) — 브라우저에는 module 이 없다
    if (typeof module === 'object' && module && module.exports) {
        module.exports = {makeKiosk: makeKiosk, findBridge: findBridge, inCorner: inCorner};
    }
})(typeof window !== 'undefined' ? window : /** @type {any} */ (globalThis));
