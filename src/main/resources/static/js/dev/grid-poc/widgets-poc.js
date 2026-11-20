/*
 * 위젯 비교 PoC (docs/10 8장 4일차·11.3): Kendo UI Core(Apache-2.0) vs Vanilla Calendar Pro·Tom Select·<dialog>·Split.js.
 * 같은 데이터·같은 시나리오(CSP 위반 0, 한글 검색 후 Enter, ko 표시). Kendo 는 함수 템플릿만 — 문자열 템플릿을 넘기지 않는다(G7).
 * 각 위젯 묶음은 <form> 안에 있고, Enter 로 폼이 제출되면 "⚠ form submit" 으로 기록한다(실제 제출은 막음).
 */
(function (global) {
    'use strict';

    var log = global.hbPoc.log;

    var REGIONS = [
        {code: 'R11', name: '서울특별시 강남구'}, {code: 'R12', name: '서울특별시 서초구'}, {code: 'R13', name: '서울특별시 송파구'},
        {code: 'R21', name: '경기도 수원시'}, {code: 'R22', name: '경기도 성남시'}, {code: 'R23', name: '경기도 화성시'},
        {code: 'R31', name: '부산광역시 해운대구'}, {code: 'R32', name: '부산광역시 강서구'}, {code: 'R41', name: '인천광역시 남동구'},
        {code: 'R51', name: '대구광역시 달서구'}, {code: 'R61', name: '울산광역시 북구'}, {code: 'R71', name: '충청남도 아산시'},
        {code: 'R81', name: '경상남도 창원시'}, {code: 'R82', name: '경상남도 김해시'}, {code: 'R91', name: '전라남도 광양시'}
    ];

    function $(id) {
        return document.getElementById(id);
    }

    function watchKeys(container, area) {
        ['keydown', 'compositionstart', 'compositionend'].forEach(function (type) {
            container.addEventListener(type, function (e) {
                log(area, global.hbPoc.describeKey(e));
            }, true);
        });
    }

    function guardForms() {
        Array.prototype.forEach.call(document.querySelectorAll('form.poc-form'), function (form) {
            form.addEventListener('submit', function (e) {
                e.preventDefault();
                log(form.getAttribute('data-area'), '⚠ form submit (Enter 로 폼 제출이 일어남)');
            });
            watchKeys(form, form.getAttribute('data-area'));
        });
    }

    function initKendo() {
        var jq = global.jQuery;
        var kendo = global.kendo;
        if (!jq || !kendo) {
            log('kendo', '로드 실패 (jQuery·kendo 없음)');
            return;
        }
        var t0 = performance.now();
        kendo.culture('ko-KR');
        $('kendoVersion').textContent = kendo.version + ' / culture ' + kendo.culture().name;
        try {
            jq('#kDate').kendoDatePicker({
                format: 'yyyy-MM-dd',
                change: function () { log('kendo:DatePicker', 'change', kendo.toString(this.value(), 'yyyy-MM-dd')); }
            });
            jq('#kCombo').kendoComboBox({
                dataSource: REGIONS, dataTextField: 'name', dataValueField: 'code', filter: 'contains', suggest: false,
                placeholder: '지역 검색 (예: 서울)',
                select: function (e) { log('kendo:ComboBox', 'select', e.dataItem ? e.dataItem.code + ' ' + e.dataItem.name : '-'); },
                change: function () { log('kendo:ComboBox', 'change value=' + this.value(), 'text=' + this.text()); }
            });
            jq('#kNum').kendoNumericTextBox({
                format: 'n2', decimals: 2, min: 0,
                change: function () { log('kendo:NumericTextBox', 'change', String(this.value())); }
            });
            jq('#kDdl').kendoDropDownList({
                dataSource: REGIONS, dataTextField: 'name', dataValueField: 'code', filter: 'contains',
                optionLabel: '선택',
                change: function () { log('kendo:DropDownList', 'change value=' + this.value(), 'text=' + this.text()); }
            });
            global.hbPoc.perf('Kendo 위젯 4종 생성', performance.now() - t0);
        } catch (e) {
            log('kendo', '초기화 오류', String(e && e.message));
        }
    }

    function initOss() {
        var t0 = performance.now();
        // Vanilla Calendar Pro — 입력칸 모드, ko-KR
        var VCP = global.VanillaCalendarPro;
        if (VCP && VCP.Calendar) {
            var cal = new VCP.Calendar('#vcDate', {
                inputMode: true,
                positionToInput: 'auto',
                locale: 'ko-KR',
                onChangeToInput: function (self) {
                    var input = self.context.inputElement;
                    if (!input) {
                        return;
                    }
                    input.value = self.context.selectedDates[0] || '';
                    log('vcp:Calendar', 'change', input.value);
                    self.hide();
                }
            });
            cal.init();
        } else {
            log('vcp', '로드 실패');
        }
        // Tom Select — 한글 검색
        if (global.TomSelect) {
            /* eslint-disable no-new */
            new global.TomSelect('#tsCombo', {
                options: REGIONS.map(function (r) { return {value: r.code, text: r.name}; }),
                maxItems: 1, create: false, placeholder: '지역 검색 (예: 서울)',
                onChange: function (v) { log('tom-select', 'change value=' + v); },
                onItemAdd: function (v) { log('tom-select', 'itemAdd', v); }
            });
        } else {
            log('tom-select', '로드 실패');
        }
        // 네이티브 숫자 (inputmode + Intl, blur 때만 천 단위)
        var nf = new Intl.NumberFormat('ko-KR', {maximumFractionDigits: 2});
        var num = $('nativeNum');
        num.addEventListener('focus', function () { num.value = num.value.replace(/,/g, ''); });
        num.addEventListener('blur', function () {
            var v = global.hbPaste.parseCell('number', num.value, {scale: 2, min: 0});
            num.classList.toggle('poc-input-error', !!v.error);
            if (!v.error && v.value !== null) {
                num.value = nf.format(v.value);
            }
            log('native:number', 'blur', num.value, v.error || 'OK');
        });
        // <dialog>
        var dlg = $('pocDialog');
        $('openDialogBtn').addEventListener('click', function () {
            dlg.showModal();
            log('dialog', 'showModal');
        });
        $('closeDialogBtn').addEventListener('click', function () { dlg.close('ok'); });
        dlg.addEventListener('cancel', function () { log('dialog', 'cancel (Esc) — 조합 중 Esc 로 닫혔는지 확인'); });
        dlg.addEventListener('close', function () { log('dialog', 'close returnValue=' + dlg.returnValue); });
        watchKeys(dlg, 'dialog');
        // Split.js
        if (global.Split) {
            global.Split(['#splitLeft', '#splitRight'], {
                sizes: [40, 60], minSize: 120, gutterSize: 8,
                onDragEnd: function (sizes) { log('split', 'dragEnd', sizes.map(function (s) { return s.toFixed(1); }).join('/')); }
            });
        } else {
            log('split', '로드 실패');
        }
        global.hbPoc.perf('OSS 위젯 생성', performance.now() - t0);
    }

    var domReady = new Promise(function (resolve) { document.addEventListener('DOMContentLoaded', resolve); });
    var kendoReady = new Promise(function (resolve) {
        document.addEventListener('hb:kendo-ready', function (e) { resolve(e.detail); });
    });

    domReady.then(function () {
        guardForms();
        initOss();
        return kendoReady;
    }).then(function (detail) {
        if (!detail.ok) {
            log('kendo', '파일 로드 실패', detail.failed);
            return;
        }
        initKendo();
    });
})(window);
