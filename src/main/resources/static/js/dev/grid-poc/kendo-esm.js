/*
 * Kendo UI Core 를 ESM(위젯별 모듈)으로 로드한다 — <script type="module"> 하나, CSP script-src 'self' 안에서 동작.
 * PoC 에서 발견한 패키지 결함(docs/10 11.5, vendor/README.md):
 *  - 한 파일 UMD 번들 kendo.ui.core.min.js: kendo.color 가 상용 @progress/kendo-drawing 전역을 요구 → 로드 중 TypeError, 위젯 12종 미등록
 *  - 위젯별 UMD 파일: kendo.dateinput·datepicker·timepicker·datetimepicker·editable·listview 가 브라우저 전역 모드에서
 *    exports 를 참조 → ReferenceError
 *  ESM 빌드는 상대 경로 import 만 있고(drawing 은 color 모듈만) jQuery 는 전역(window.jQuery)을 쓴다 → 이 방식이 깨끗하다.
 * 끝나면 ko-KR culture(일반 스크립트)를 붙이고 document 에 'hb:kendo-ready' 이벤트.
 */
import '/vendor/kendo-ui-core/2026.4.1111/esm/kendo.datepicker.js';
import '/vendor/kendo-ui-core/2026.4.1111/esm/kendo.combobox.js';
import '/vendor/kendo-ui-core/2026.4.1111/esm/kendo.dropdownlist.js';
import '/vendor/kendo-ui-core/2026.4.1111/esm/kendo.numerictextbox.js';

const t0 = performance.now();
const culture = document.createElement('script');
culture.src = '/vendor/kendo-ui-core/2026.4.1111/kendo.culture.ko-KR.min.js';
culture.addEventListener('load', () => {
    if (window.hbPoc) {
        window.hbPoc.perf('Kendo ESM 로드 후 culture', performance.now() - t0);
    }
    document.dispatchEvent(new CustomEvent('hb:kendo-ready', {detail: {ok: true}}));
});
culture.addEventListener('error', () => {
    document.dispatchEvent(new CustomEvent('hb:kendo-ready', {detail: {ok: false, failed: culture.src}}));
});
document.head.appendChild(culture);
