/*
 * CMS 점검 결과 입력 표 (docs/33 4.3) — 점검 실행 화면(cms-chk-run)과 보전 작업지시 화면(cms-mwo, PM)이 같이 쓴다.
 * 공통(hb.*)이 아니라 CMS 화면 전용 — 병행 작업 규칙(docs/33 6장)상 공통 파일은 고치지 않는다.
 *
 *   cmsResults.render(tbody, run, {editable, canFollowUp, onFollowUp(seq)})  → 행을 그린다
 *   cmsResults.collect(tbody)  → [{seq, value, valueText, judge, remark}] (바뀐 행만)
 * 수치 판정(NUM)은 서버가 한다 — 화면은 입력 중 미리 OK/NG 를 보여 줄 뿐(경계 포함, 소수 자릿수 넘으면 표시 안 함).
 */
(function () {
    'use strict';

    function judgeName(j) {
        return {OK: hb.t('js.cms.run.ok'), NG: hb.t('js.cms.run.ng')}[j] || '';
    }

    function spec(r) {
        var lo = r.lslText, hi = r.uslText, u = r.unitNm ? ' ' + r.unitNm : '';
        if (r.judgeType !== 'NUM') { return ''; }
        if (lo !== null && lo !== undefined && hi !== null && hi !== undefined) { return lo + ' ~ ' + hi + u; }
        if (lo !== null && lo !== undefined) { return '≥ ' + lo + u; }
        return '≤ ' + hi + u;
    }

    /** 입력 중 미리보기 판정 — 서버 판정과 같은 규칙(하한 ≤ 값 ≤ 상한) */
    function preview(r, text) {
        var t = String(text || '').trim();
        if (!/^-?\d+(\.\d+)?$/.test(t)) { return ''; }
        var dec = (t.split('.')[1] || '').replace(/0+$/, '').length;
        if (dec > r.scale) { return ''; }
        var v = Number(t);
        var ok = (r.lslText === null || r.lslText === undefined || v >= Number(r.lslText))
            && (r.uslText === null || r.uslText === undefined || v <= Number(r.uslText));
        return ok ? 'OK' : 'NG';
    }

    function render(tbody, run, opts) {
        opts = opts || {};
        tbody.textContent = '';
        if (!run || !run.results || !run.results.length) {
            var tr0 = hb.el('tr');
            var td0 = hb.el('td', 'hb-muted', hb.t('js.cms.run.noItems'));
            td0.colSpan = 5;
            tr0.appendChild(td0);
            tbody.appendChild(tr0);
            return;
        }
        run.results.forEach(function (r) {
            var tr = hb.el('tr');
            tr.setAttribute('data-seq', String(r.seq));
            tr.setAttribute('data-row-key', String(r.seq));
            tr.cmsResult = r;
            var name = hb.el('td', null, r.seq + '. ' + r.itemNm + (r.requiredYn === 'Y' ? ' *' : ''));
            if (r.methodText) { name.title = r.methodText; }
            tr.appendChild(name);
            tr.appendChild(hb.el('td', 'hb-muted', spec(r)));
            var val = hb.el('td');
            var judgeTd = hb.el('td', null, judgeName(r.judge));
            if (r.judgeType === 'NUM') {
                var inp = hb.el('input');
                inp.setAttribute('inputmode', 'decimal');
                inp.setAttribute('data-field', 'value');
                inp.setAttribute('aria-label', r.itemNm);
                inp.value = r.valueNumText || '';
                inp.defaultValue = inp.value;
                inp.disabled = !opts.editable;
                inp.addEventListener('input', function () { judgeTd.textContent = judgeName(preview(r, inp.value)); });
                val.appendChild(inp);
            } else if (r.judgeType === 'OKNG') {
                ['OK', 'NG'].forEach(function (j) {
                    var b = hb.el('button', r.judge === j ? null : 'hb-btn-secondary', judgeName(j));
                    b.type = 'button';
                    b.setAttribute('data-judge', j);
                    b.setAttribute('aria-pressed', r.judge === j ? 'true' : 'false');
                    b.disabled = !opts.editable;
                    b.addEventListener('click', function () {
                        val.querySelectorAll('button').forEach(function (x) {
                            var on = x === b;
                            x.setAttribute('aria-pressed', on ? 'true' : 'false');
                            x.classList.toggle('hb-btn-secondary', !on);
                        });
                        tr.setAttribute('data-dirty', '1');
                        judgeTd.textContent = judgeName(j);
                    });
                    val.appendChild(b);
                });
            }
            if (r.judgeType !== 'NUM' || r.valueText) {
                var txt = hb.el('input');
                txt.setAttribute('data-field', 'valueText');
                txt.maxLength = 500;
                txt.setAttribute('aria-label', r.itemNm);
                txt.value = r.valueText || '';
                txt.defaultValue = txt.value;
                txt.disabled = !opts.editable;
                val.appendChild(txt);
            }
            tr.appendChild(val);
            tr.appendChild(judgeTd);
            var act = hb.el('td');
            if (opts.canFollowUp && r.judge === 'NG') {
                var fb = hb.el('button', 'hb-btn-secondary', hb.t('js.cms.run.followUp'));
                fb.type = 'button';
                fb.addEventListener('click', function () { opts.onFollowUp(r.seq, fb); });
                act.appendChild(fb);
            }
            tr.appendChild(act);
            tbody.appendChild(tr);
        });
    }

    /** 바뀐 행만 모은다(입력값이 처음과 다르거나 OK/NG 를 눌렀으면) */
    function collect(tbody) {
        var out = [];
        tbody.querySelectorAll('tr[data-seq]').forEach(function (tr) {
            var v = tr.querySelector('[data-field="value"]');
            var t = tr.querySelector('[data-field="valueText"]');
            var pressed = tr.querySelector('button[aria-pressed="true"]');
            var dirty = tr.getAttribute('data-dirty') === '1' || (v && v.value !== v.defaultValue) || (t && t.value !== t.defaultValue);
            if (!dirty) { return; }
            out.push({seq: Number(tr.getAttribute('data-seq')), value: v ? v.value : null, valueText: t ? t.value : null,
                judge: pressed ? pressed.getAttribute('data-judge') : (tr.cmsResult.judgeType === 'OKNG' ? tr.cmsResult.judge : null)});
        });
        return out;
    }

    /** 행 오류(rowKey = 순번)를 그 행 입력에 표시 */
    function showErrors(tbody, errors) {
        tbody.querySelectorAll('[aria-invalid]').forEach(function (el) { el.removeAttribute('aria-invalid'); el.title = ''; });
        (errors || []).forEach(function (er) {
            var tr = tbody.querySelector('tr[data-seq="' + er.rowKey + '"]');
            if (!tr) { return; }
            var el = tr.querySelector('[data-field="' + er.field + '"]') || tr.querySelector('input, button');
            if (el) {
                el.setAttribute('aria-invalid', 'true');
                el.title = er.message;
            }
        });
    }

    window.cmsResults = {render: render, collect: collect, showErrors: showErrors};
})();
