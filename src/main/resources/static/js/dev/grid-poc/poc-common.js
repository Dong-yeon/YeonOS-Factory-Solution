/*
 * PoC 공통 (head 에서 가장 먼저 로드 — vendor 스크립트·스타일의 CSP 위반도 잡기 위해).
 *
 * hbPoc.csp   : securitypolicyviolation 이벤트를 모아 #cspCount·#cspList 에 표시 (콘솔에도 브라우저가 따로 찍는다)
 * hbPoc.log   : IME·키 이벤트 기록 (#eventLog, 최근 400건). 사람이 시나리오를 해 보고 기록을 그대로 복사해 판정 근거로 남긴다
 * hbPoc.perf  : 측정값 기록 (#perfTable)
 * 모든 DOM 출력은 textContent 로만.
 */
(function (global) {
    'use strict';

    var violations = [];
    var pendingLog = [];
    var MAX_LOG = 400;
    var t0 = performance.now();

    function $(id) {
        return document.getElementById(id);
    }

    function renderCsp() {
        var count = $('cspCount');
        var list = $('cspList');
        if (!count || !list) {
            return;
        }
        count.textContent = String(violations.length);
        count.classList.toggle('poc-bad', violations.length > 0);
        count.classList.toggle('poc-good', violations.length === 0);
        while (list.firstChild) {
            list.removeChild(list.firstChild);
        }
        violations.forEach(function (v) {
            var li = document.createElement('li');
            li.textContent = v.directive + ' ← ' + v.blocked + ' @ ' + v.source + ':' + v.line + (v.sample ? ' 「' + v.sample + '」' : '');
            list.appendChild(li);
        });
    }

    document.addEventListener('securitypolicyviolation', function (e) {
        violations.push({
            directive: e.effectiveDirective || e.violatedDirective,
            blocked: e.blockedURI || '(inline)',
            source: (e.sourceFile || '').replace(location.origin, ''),
            line: e.lineNumber || 0,
            sample: e.sample || '',
            disposition: e.disposition
        });
        renderCsp();
    });

    function appendLog(entry) {
        var ol = $('eventLog');
        if (!ol) {
            pendingLog.push(entry);
            return;
        }
        var li = document.createElement('li');
        li.textContent = entry;
        ol.appendChild(li);
        while (ol.childNodes.length > MAX_LOG) {
            ol.removeChild(ol.firstChild);
        }
        ol.scrollTop = ol.scrollHeight;
    }

    function log(area, what) {
        var ms = (performance.now() - t0).toFixed(1);
        var parts = Array.prototype.slice.call(arguments, 2).map(function (v) {
            return typeof v === 'string' ? JSON.stringify(v) : String(v);
        });
        appendLog(ms + 'ms [' + area + '] ' + what + (parts.length ? ' ' + parts.join(' ') : ''));
    }

    function perf(name, ms, note) {
        var tbody = $('perfBody');
        if (!tbody) {
            return;
        }
        var tr = document.createElement('tr');
        [new Date().toTimeString().substring(0, 8), name, ms.toFixed(1), String(Math.round(global.devicePixelRatio * 100)) + '%',
            note || ''].forEach(function (v) {
            var td = document.createElement('td');
            td.textContent = v;
            tr.appendChild(td);
        });
        tbody.appendChild(tr);
        log('perf', name, ms.toFixed(1) + 'ms', note || '');
    }

    /** 키·조합 이벤트를 한 줄로 (어느 시나리오에서 무엇이 왔는지 사람이 비교할 수 있게) */
    function describeKey(e) {
        if (e.type.indexOf('composition') === 0) {
            return e.type + ' data=' + JSON.stringify(e.data);
        }
        if (e.type === 'input') {
            return 'input inputType=' + e.inputType + ' isComposing=' + e.isComposing + ' value=' + JSON.stringify(e.target.value);
        }
        return e.type + ' key=' + JSON.stringify(e.key) + ' code=' + e.code + ' keyCode=' + e.keyCode + ' isComposing=' + e.isComposing;
    }

    document.addEventListener('DOMContentLoaded', function () {
        renderCsp();
        pendingLog.splice(0).forEach(appendLog);
        var ua = $('uaInfo');
        if (ua) {
            ua.textContent = navigator.userAgent + ' / 배율 ' + Math.round(global.devicePixelRatio * 100) + '% / 언어 ' + navigator.language;
        }
        var clear = $('clearLogBtn');
        if (clear) {
            clear.addEventListener('click', function () {
                var ol = $('eventLog');
                while (ol.firstChild) {
                    ol.removeChild(ol.firstChild);
                }
            });
        }
        var copy = $('copyLogBtn');
        if (copy) {
            copy.addEventListener('click', function () {
                var text = Array.prototype.map.call($('eventLog').childNodes, function (li) { return li.textContent; }).join('\n');
                if (navigator.clipboard) {
                    navigator.clipboard.writeText(text);
                }
            });
        }
    });

    global.hbPoc = {
        log: log,
        perf: perf,
        describeKey: describeKey,
        violations: function () { return violations.slice(); }
    };
})(window);
