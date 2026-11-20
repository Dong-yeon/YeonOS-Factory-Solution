// @ts-check
/*
 * YeonOS 화면 공통 (셸·화면 공용). jQuery·Kendo 에 의존하지 않는다.
 *
 * hb.api(url, {method, body, timeoutMs, headers}) → Promise<data|list 응답>
 *   - CSRF: XSRF-TOKEN 쿠키 → X-XSRF-TOKEN 헤더
 *   - 오류는 {status, code, message} 로 정규화. 네트워크 끊김·타임아웃은 status 0 (브라우저 영문 메시지를 내보내지 않음)
 *   - 401 일 때만 로그인 화면으로 (최상위 창). 403·5xx·끊김은 세션을 지키고 오류만 알린다 (docs/04 2.3)
 * hb.t(key, ...args) → 화면 문구 (js.* 키, /api/platform/messages)
 * hb.busy(button, promiseFn) → 저장 버튼 연타 방지 (동기 플래그)
 * hb.dirty → 입력 중 이탈 확인 (셸이 탭을 닫을 때 확인, 셸 밖에서 단독으로 연 화면은 beforeunload)
 * hb.latest(key, promise) → 같은 key 로 나중에 보낸 요청이 있으면 앞 응답은 버린다(늦은 응답이 다른 행 자리에 그려지지 않게, docs/24 2.1)
 * 파일 드롭: 파일 칸·[data-hb-dropzone] 밖에 떨어뜨린 파일은 막는다(화면이 파일로 바뀌어 입력이 사라지지 않게, docs/24 2.2)
 * 세션 만료(401) 때 입력 중이면 먼저 묻는다 — 머무르면 입력을 옮겨 적을 수 있다(docs/24 2.3)
 * 공개 API 계약(타입)은 types/hb.d.ts — 이 파일들은 // @ts-check 로 그 계약에 맞는지 검사받는다(npm run typecheck)
 */
(function (global) {
    'use strict';

    var DEFAULT_TIMEOUT_MS = 15000;
    /** @type {Record<string, string>} */
    var messages = {};

    /** @param {string} name */
    function cookie(name) {
        var parts = document.cookie ? document.cookie.split('; ') : [];
        for (var i = 0; i < parts.length; i++) {
            var eq = parts[i].indexOf('=');
            if (parts[i].substring(0, eq) === name) {
                return decodeURIComponent(parts[i].substring(eq + 1));
            }
        }
        return null;
    }

    /**
     * @param {string} key
     * @return {string}
     */
    function t(key) {
        var text = Object.prototype.hasOwnProperty.call(messages, key) ? messages[key] : key;
        var args = Array.prototype.slice.call(arguments, 1);
        return text.replace(/\{(\d+)\}/g, function (m, i) {
            return args[+i] !== undefined ? String(args[+i]) : m;
        });
    }

    function toLogin() {
        var top = global.top || global;
        // 운영자 콘솔 등 다른 로그인 화면을 쓰는 페이지는 body[data-login-url] 로 정한다
        var custom = document.body && document.body.getAttribute('data-login-url');
        top.location.href = custom || '/login?expired';
    }

    /** 이 화면이나 셸의 다른 탭에 저장 안 한 입력이 있는지 */
    function anyDirty() {
        var top = global.top || global;
        try {
            if (top !== global && typeof top.hbAnyDirty === 'function') { return dirtyFlag || top.hbAnyDirty(); }
        } catch (e) { /* 다른 출처 */ }
        return dirtyFlag;
    }

    /**
     * 401 처리. 입력 중이 아니면 바로 로그인 화면. 입력 중이면 "저장되지 않았다" 를 먼저 알리고 고르게 한다.
     * 머무르면 true — 호출한 쪽은 오류 문구를 "저장되지 않음" 으로 바꾼다.
     */
    function expired() {
        if (anyDirty() && !global.confirm(t('js.session.expiredDirty'))) {
            return true;
        }
        try { (global.top || global).hbLeaving = true; } catch (e) { /* 다른 출처 */ }   // 셸 이탈 경고를 두 번 띄우지 않게
        toLogin();
        return false;
    }

    /** @param {string} text @return {any} JSON 이 아니면 null */
    function parseJson(text) {
        try { return text ? JSON.parse(text) : null; } catch (e) { return null; }
    }

    /**
     * 오류 응답 → hb.api·hb.download·hb.upload 공통 오류 모양. 401 이면 expired()
     * @param {Response} res
     * @param {any} json
     * @return {HB.ApiError}
     */
    function failure(res, json) {
        var stayed = res.status === 401 && expired();
        return {
            status: res.status,
            code: json && json.code ? json.code : 'HTTP_' + res.status,
            message: stayed ? t('js.session.unsaved') : (json && json.message ? json.message : t('js.error.server')),
            details: json && json.details ? json.details : [],
            errors: json && json.errors ? json.errors : []
        };
    }

    /** @type {Record<string, number>} */
    var latestSeq = {};
    /**
     * key 마다 마지막 요청만 결과를 넘긴다. 앞선 요청은 결과 대신 STALE 로 거부 — 성공 처리(그리기)는 건너뛰고,
     * 이 결과를 기다리던 연타 방지(hb.busy)·체인은 풀린다(끝나지 않는 Promise 로 두면 버튼이 잠긴 채 남는다).
     * STALE 은 문구가 비어 있어 오류 표시도 비운다.
     */
    /** @type {HB.ApiError} */
    var STALE = {status: 0, code: 'STALE', message: '', details: [], errors: [], stale: true};
    /** @type {HB.Hb['latest']} */
    function latest(key, promise) {
        var my = (latestSeq[key] || 0) + 1;
        latestSeq[key] = my;
        return Promise.resolve(promise).then(function (v) {
            if (latestSeq[key] !== my) { throw STALE; }
            return v;
        }, function (e) {
            throw latestSeq[key] === my ? e : STALE;
        });
    }

    /** fetch 자체 실패(끊김·중단) → status 0. 브라우저 영문 메시지를 내보내지 않는다 @param {any} err @return {HB.ApiError} */
    function networkError(err) {
        var timedOut = !!err && err.name === 'AbortError';
        return {status: 0, code: timedOut ? 'TIMEOUT' : 'NETWORK', message: t(timedOut ? 'js.error.timeout' : 'js.error.network')};
    }

    /** 제한 시간이 지나면 요청을 끊는다. AbortController 가 없는 브라우저는 제한 없음 @param {number} ms */
    function deadline(ms) {
        var controller = typeof AbortController !== 'undefined' ? new AbortController() : null;
        var timer = controller ? setTimeout(function () { if (controller) { controller.abort(); } }, ms) : undefined;
        return {signal: controller ? controller.signal : undefined, done: function () { clearTimeout(timer); }};
    }

    /** @type {HB.Hb['api']} */
    function api(url, options) {
        var o = options || {};
        var method = (o.method || 'GET').toUpperCase();
        /** @type {Record<string, string>} */
        var headers = {};
        // 추가 헤더(예: X-Idempotency-Key). Accept·CSRF·Content-Type 은 아래 값이 우선
        Object.assign(headers, o.headers || {});
        headers['Accept'] = 'application/json';
        var token = cookie('XSRF-TOKEN');
        if (method !== 'GET' && token) {
            headers['X-XSRF-TOKEN'] = token;
        }
        var body;
        if (o.body !== undefined) {
            headers['Content-Type'] = 'application/json';
            body = JSON.stringify(o.body);
        }
        var limit = deadline(o.timeoutMs || DEFAULT_TIMEOUT_MS);

        return fetch(url, {
            method: method, headers: headers, body: body, credentials: 'same-origin', signal: limit.signal
        }).then(function (res) {
            limit.done();
            return res.text().then(function (text) {
                var json = parseJson(text);
                if (res.ok) {
                    return json;
                }
                throw failure(res, json);
            });
        }, function (err) {
            limit.done();
            throw networkError(err);
        });
    }

    /**
     * 화면 문구 — 페이지당 한 번만 받는다(여러 곳이 불러도 같은 요청). 실패하면 다음 호출이 다시 받는다.
     * hb.select 처럼 문구가 필요한 공통 부품도 이것을 기다리므로, 화면이 부품을 문구 로드 전에 만들어도 키가 노출되지 않는다.
     * @type {Promise<Record<string, string>> | null}
     */
    var messagesLoad = null;
    function loadMessages() {
        if (!messagesLoad) {
            messagesLoad = api('/api/platform/messages').then(function (res) {
                messages = (res && res.data) || {};
                return messages;
            }, function () {
                messagesLoad = null;
                return messages;
            });
        }
        return messagesLoad;
    }

    /**
     * 버튼 연타 방지: 처리 중이면 무시(null). 끝나면(성공·실패 모두) 해제
     * @template T
     * @param {HTMLButtonElement} button
     * @param {() => T | PromiseLike<T>} fn
     * @return {Promise<T | null>}
     */
    function busy(button, fn) {
        if (button.getAttribute('data-busy') === '1') {
            return Promise.resolve(null);
        }
        button.setAttribute('data-busy', '1');
        button.disabled = true;
        var done = function () {
            button.removeAttribute('data-busy');
            button.disabled = false;
        };
        /** @type {Promise<T>} */
        var p;
        try { p = Promise.resolve(fn()); } catch (e) { p = Promise.reject(e); }
        return p.then(function (v) { done(); return v; }, function (e) { done(); throw e; });
    }

    /**
     * 입力 중 표시. 폼마다 따로 — 부분 저장(속성 저장·외부 품번 추가) 뒤 clear(그 폼)은 그 폼만 지운다.
     * clear() 는 전부(상세를 새로 그릴 때). mark() 는 화면 전체 표시
     */
    /** @type {Array<Document | Element>} */
    var dirtyRoots = [];
    var dirtyAll = false;
    var dirtyFlag = false;          // 아래 beforeunload 가 본다 = isDirty()
    function syncDirty() { dirtyFlag = dirtyAll || dirtyRoots.length > 0; }
    /** @type {HB.Dirty} */
    var dirty = {
        mark: function (root) {
            if (root) { if (dirtyRoots.indexOf(root) < 0) { dirtyRoots.push(root); } } else { dirtyAll = true; }
            syncDirty();
        },
        clear: function (root) {
            if (root) { dirtyRoots = dirtyRoots.filter(function (r) { return r !== root; }); } else { dirtyRoots = []; dirtyAll = false; }
            syncDirty();
        },
        isDirty: function () { return dirtyFlag; },
        /** 입력 요소가 바뀌면 자동 표시(폼 단위 위임 — 칸을 다시 그려도 된다) */
        watch: function (root) {
            var r = root || document;
            r.addEventListener('input', function () { dirty.mark(root || null); }, true);
            r.addEventListener('change', function () { dirty.mark(root || null); }, true);
        }
    };

    /**
     * 파일 받기 (서버 엑셀 내보내기 등). POST + CSRF, 응답을 Blob 으로 받아 저장한다.
     * 오류 응답(JSON)은 hb.api 와 같은 형태로 throw. 파일명은 Content-Disposition 의 filename* (RFC 5987)
     * @type {HB.Hb['download']}
     */
    function download(url, body, timeoutMs) {
        /** @type {Record<string, string>} */
        var headers = {'Accept': '*/*', 'Content-Type': 'application/json'};
        var token = cookie('XSRF-TOKEN');
        if (token) {
            headers['X-XSRF-TOKEN'] = token;
        }
        var limit = deadline(timeoutMs || 300000);
        return fetch(url, {
            method: 'POST', headers: headers, body: JSON.stringify(body || {}), credentials: 'same-origin', signal: limit.signal
        }).then(function (res) {
            limit.done();
            if (!res.ok) {
                return res.text().then(function (text) {
                    throw failure(res, parseJson(text));
                });
            }
            var cd = res.headers.get('Content-Disposition') || '';
            var m = /filename\*=UTF-8''([^;]+)/i.exec(cd);
            var name = m ? decodeURIComponent(m[1]) : 'download';
            return res.blob().then(function (blob) {
                var a = document.createElement('a');
                var href = URL.createObjectURL(blob);
                a.href = href;
                a.download = name;
                document.body.appendChild(a);
                a.click();
                document.body.removeChild(a);
                setTimeout(function () { URL.revokeObjectURL(href); }, 10000);
                return name;
            });
        }, function (err) {
            limit.done();
            throw networkError(err);
        });
    }

    /**
     * multipart 올리기 (첨부) — CSRF 헤더, 오류는 hb.api 와 같은 형태. 제한 시간 없음(큰 파일). extraHeaders = 더할 헤더(POP 구분 등)
     * @type {HB.Hb['upload']}
     */
    function upload(url, formData, extraHeaders) {
        /** @type {Record<string, string>} */
        var headers = {'Accept': 'application/json'};
        Object.keys(extraHeaders || {}).forEach(function (k) { headers[k] = /** @type {Record<string, string>} */ (extraHeaders)[k]; });
        var token = cookie('XSRF-TOKEN');
        if (token) {
            headers['X-XSRF-TOKEN'] = token;
        }
        return fetch(url, {method: 'POST', headers: headers, body: formData, credentials: 'same-origin'})
            .then(function (res) {
                return res.text().then(function (text) {
                    var json = parseJson(text);
                    if (res.ok) { return json; }
                    throw failure(res, json);
                });
            }, function () {
                throw networkError(null);
            });
    }

    /** 로그인 사용자·회사 시간대 (한 번만 조회 — 실패하면 다음 호출이 다시 조회) */
    /** @type {Promise<HB.Session> | null} */
    var sessionPromise = null;
    /** @type {HB.Session} */
    var session = {timeZone: 'Asia/Seoul', localeCd: 'ko'};
    function loadSession() {
        if (!sessionPromise) {
            sessionPromise = api('/api/platform/session').then(function (res) {
                session = res.data;
                return session;
            }, function (err) {
                sessionPromise = null;
                throw err;
            });
        }
        return sessionPromise;
    }

    /**
     * 공통코드 목록 (그룹별 한 번만 조회, 서버 정렬 순서 그대로). 실패하면 빈 목록 — 캐시하지 않아 다음 호출이 다시 조회
     * @type {Record<string, Promise<HB.Code[]>>}
     */
    var codeCache = {};
    /** @param {string} grp */
    function codeList(grp) {
        if (!codeCache[grp]) {
            codeCache[grp] = api('/api/platform/codes?grp=' + encodeURIComponent(grp)).then(function (res) {
                return res.list || res.data || [];
            }, function () {
                delete codeCache[grp];
                return [];
            });
        }
        return codeCache[grp];
    }

    /**
     * 표시용 이름표 → Promise<{code: name}> — **사용 중지된 코드도**(docs/36 18.9 X4). 그리드·트리 CODE 칸용. 선택 목록은 codeList(사용 중만)
     * @type {Record<string, Promise<Record<string, string>>>}
     */
    var nameCache = {};
    /** @param {string} grp */
    function codeNames(grp) {
        if (!nameCache[grp]) {
            nameCache[grp] = api('/api/platform/codes/names?grp=' + encodeURIComponent(grp)).then(function (res) {
                return /** @type {Record<string, string>} */ (res.data || {});
            }, function () {
                delete nameCache[grp];
                return {};
            });
        }
        return nameCache[grp];
    }

    /** 공통코드 이름 → Promise<{code: name}> @param {string} grp */
    function codes(grp) {
        return codeList(grp).then(function (list) {
            /** @type {Record<string, string>} */
            var map = {};
            list.forEach(function (c) { map[c.code] = c.name; });
            return map;
        });
    }

    /**
     * 표시 서식. 일시는 서버가 오프셋 포함 ISO 로 보내고, 표시는 **회사 시간대**(브라우저 시간대 아님, docs/09 4.8)
     * @type {HB.Fmt}
     */
    var fmt = {
        /** scale 자리까지 반올림, minScale(기본 = scale) 자리까지 끝 0 을 채운다 */
        num: function (v, scale, minScale) {
            if (v === null || v === undefined || v === '') { return ''; }
            var n = Number(v);
            if (isNaN(n)) { return String(v); }
            var max = scale || 0;
            var min = minScale === undefined || minScale === null ? max : Math.min(minScale, max);
            return n.toLocaleString(session.localeCd === 'en' ? 'en-US' : 'ko-KR',
                {minimumFractionDigits: min, maximumFractionDigits: max});
        },
        date: function (v) {
            return v ? String(v).substring(0, 10) : '';
        },
        /** tz 를 주면 그 시간대(예: 공장 시간대), 없으면 회사 시간대 */
        datetime: function (v, tz) {
            if (!v) { return ''; }
            var d = new Date(/** @type {string | number | Date} */ (v));
            if (isNaN(d.getTime())) { return String(v); }
            var parts = new Intl.DateTimeFormat('en-CA', {
                timeZone: tz || session.timeZone, year: 'numeric', month: '2-digit', day: '2-digit',
                hour: '2-digit', minute: '2-digit', hour12: false
            }).formatToParts(d);
            /** @type {Record<string, string>} */
            var p = {};
            parts.forEach(function (x) { p[x.type] = x.value; });
            return p.year + '-' + p.month + '-' + p.day + ' ' + (p.hour === '24' ? '00' : p.hour) + ':' + p.minute;
        },
        bizNo: function (v) {
            return v && /^\d{10}$/.test(v) ? v.substring(0, 3) + '-' + v.substring(3, 5) + '-' + v.substring(5) : (v || '');
        },
        /** LABEL 칸(docs/36 18.9 X2) — 서버가 내려준 표로 값 → 글자. 쉼표 값은 나눠 ", " 로 잇고, 표에 없는 값은 그대로(서버 엑셀과 같은 규칙) */
        label: function (labels, v) {
            if (v === null || v === undefined || v === '') { return ''; }
            var map = labels || {};
            return String(v).split(',').map(function (s) { return s.trim(); }).filter(function (s) { return s !== ''; })
                .map(function (s) { return Object.prototype.hasOwnProperty.call(map, s) ? map[s] : s; }).join(', ');
        }
    };

    /**
     * 요소 만들기 — 글자는 textContent 로만 (innerHTML 금지)
     * @param {string} tag
     * @param {string | null} [className]
     * @param {unknown} [text]
     */
    function el(tag, className, text) {
        var e = document.createElement(tag);
        if (className) { e.className = className; }
        if (text !== undefined && text !== null) { e.textContent = String(text); }
        return e;
    }

    var STATUS_TONES = ['neutral', 'normal', 'info', 'success', 'warning', 'danger', 'error', 'alarm-1', 'alarm-2', 'alarm-3', 'alarm-4'];
    /**
     * 상태 배지 hb-status — 톤 × 모양 기호(CSS) × 글자 3중 부호화 (docs/40 부록 A). 모르는 톤은 neutral
     * @type {HB.Hb['status']}
     */
    function status(tone, text, title) {
        var s = /** @type {HTMLSpanElement} */ (el('span', 'hb-status', text));
        s.setAttribute('data-tone', STATUS_TONES.indexOf(tone) >= 0 ? tone : 'neutral');
        if (title) { s.title = title; }
        return s;
    }

    /**
     * 일반 표의 빈 목록 한 줄 — hb.grid 의 "조회된 자료가 없습니다"와 같은 문구·톤(docs/40 10.2 N1). 칸 수는 머리줄에서 센다.
     * 표마다 따로 만들던 빈 줄(문구 제각각·머리줄만 남는 표)을 이것 하나로. text 를 주면 그 화면 문구(예: "오늘 입고가 없습니다")
     * @type {HB.Hb['emptyRow']}
     */
    function emptyRow(tbody, text) {
        var table = tbody.closest('table');
        var head = table ? table.querySelector('thead tr') : null;
        var span = 0;
        if (head) {
            Array.prototype.forEach.call(head.children, function (c) { span += /** @type {HTMLTableCellElement} */ (c).colSpan || 1; });
        }
        var tr = /** @type {HTMLTableRowElement} */ (el('tr', 'hb-row-empty'));
        var td = /** @type {HTMLTableCellElement} */ (el('td', 'hb-empty-row', text || t('js.grid.empty')));
        td.colSpan = Math.max(span, 1);
        tr.appendChild(td);
        tbody.appendChild(tr);
        return tr;
    }

    /** 폼 필드 오류 표시: errors = [{field, message}] → [data-field=..] 요소 옆 @type {HB.Hb['showFieldErrors']} */
    function showFieldErrors(root, errors) {
        root.querySelectorAll('.hb-field-error').forEach(function (e) { e.remove(); });
        root.querySelectorAll('[aria-invalid="true"]').forEach(function (e) { e.removeAttribute('aria-invalid'); });
        /** @type {HTMLElement | null} */
        var first = null;
        (errors || []).forEach(function (er) {
            var input = /** @type {HTMLElement | null} */ (root.querySelector('[data-field="' + er.field + '"]'));
            if (!input) { return; }
            input.setAttribute('aria-invalid', 'true');
            var msg = el('span', 'hb-field-error', er.message);
            input.insertAdjacentElement('afterend', msg);
            if (!first) { first = input; }
        });
        if (first) { /** @type {HTMLElement} */ (first).focus(); }
    }

    // ---------- 파일 드롭 막기 / 단독 화면 이탈 경고 ----------
    /** @param {EventTarget | null} target */
    function dropAllowed(target) {
        var node = /** @type {Node | null} */ (target);
        var e = node && node.nodeType === 1 ? /** @type {Element} */ (node) : node && node.parentElement;
        return !!(e && e.closest && e.closest('input[type=file], [data-hb-dropzone]'));
    }
    document.addEventListener('dragover', function (e) {
        if (!dropAllowed(e.target)) {
            e.preventDefault();
            if (e.dataTransfer) { e.dataTransfer.dropEffect = 'none'; }
        }
    });
    document.addEventListener('drop', function (e) {
        if (!dropAllowed(e.target)) { e.preventDefault(); }
    });
    global.addEventListener('beforeunload', function (e) {
        // 셸은 자기 경고가 있다(hbShell). 셸 안 iframe 은 셸이 본다. 셸 밖에서 직접 연 화면만 여기서
        if (global.hbShell || global.hbLeaving || (global.top && global.top !== global) || !dirtyFlag) { return; }
        e.preventDefault();
        e.returnValue = '';
    });

    // 모듈(hb.grid·hb.page …)은 각 파일이 붙인다 — 계약은 types/hb.d.ts
    global.hb = /** @type {HB.Hb} */ ({api: api, latest: latest, t: t, loadMessages: loadMessages, busy: busy, dirty: dirty,
        cookie: cookie, download: download, upload: upload, loadSession: loadSession, codes: codes, codeList: codeList, codeNames: codeNames, fmt: fmt,
        el: /** @type {HB.Hb['el']} */ (el), showFieldErrors: showFieldErrors, session: function () { return session; },
        status: status, emptyRow: emptyRow});
})(window);
