# static/vendor — 오픈소스 브라우저 라이브러리 (docs/10 11.1 H5)

- 규칙: `vendor/{lib}/{version}/` + 라이선스 원문 + 이 폴더의 `SHA256SUMS`(파일별 SHA-256). `VendorChecksumGuardTest` 가 `./gradlew check` 에서 대조한다.
- 파일은 npm 레지스트리 tarball 원본을 **수정 없이** 복사한다(소스맵 주석 포함). 버전을 바꾸면 새 폴더 + `SHA256SUMS` 갱신 + 아래 표 갱신.
- **상용 Kendo(`vendor/kendo/`)는 여기에 두지 않는다**(.gitignore). 아래 `kendo-ui-core`·`kendo-theme-default` 는 Apache-2.0 무료 배포판이다.
- 현재는 편집 그리드·위젯 PoC(`/dev/poc/grid`, local 프로필 전용)만 쓴다. 채택하지 않은 라이브러리는 PoC 결정 후 지운다.

| 폴더 | npm 패키지 | 라이선스 | 가져온 파일 | npm integrity (tarball) |
|---|---|---|---|---|
| tabulator/6.7.1 | tabulator-tables@6.7.1 | MIT | dist/js/tabulator.min.js, dist/css/tabulator.min.css | sha512-6CMy17XdemQaIdg1BOqD5K6nzF2xoD47eRNiBYJBK+QrLWji7sgukyPjKXYrjRdWLwbGENdUsiAYJrNS3XeUfw== |
| jquery/3.6.4 | jquery@3.6.4 | MIT | dist/jquery.min.js (kendo-ui-core 의 의존 범위 `1.10.0 - 3.6` 최신) | sha512-v28EW9DWDFpzcD9O5iyJXg3R3+q+mET5JhnjJzQUZMHOv67bpSIHq81GEYpPNZHG+XXHsfSme3nxp/hndKEcsQ== |
| kendo-ui-core/2026.4.1111 | kendo-ui-core@2026.4.1111 | Apache-2.0 | esm/ 의 위젯별 모듈 30개(DatePicker·ComboBox·DropDownList·NumericTextBox 와 상대 import 의존, 진입점 js/dev/grid-poc/kendo-esm.js), umd/cultures/kendo.culture.ko-KR.min.js. **한 파일 번들 kendo.ui.core.min.js 는 쓰지 않는다**(아래) | sha512-QPkv9f+gQGP7la/zMKaBxW/9xHnz8wvD+APEogovdsYmg1atI9rSDgRcM4vNtKlg9e1xUeEqhX8YGPQckiCw2w== |
| kendo-theme-default/14.10.0 | @progress/kendo-theme-default@14.10.0 | Apache-2.0 (상용 라이선스가 없을 때) | dist/default-main.css | sha512-dfHyh5/yxKGNsSoDAa8qiOEImaW3J+OgDXirrLzOH+PcZid2CooM1y1SSpG5JCeNFrSE6gHB3uZqTy6z+PSntQ== |
| tom-select/2.7.0 | tom-select@2.7.0 | Apache-2.0 | dist/js/tom-select.complete.min.js, dist/css/tom-select.min.css | sha512-i/iuTfrzYfGPAILfgPdU9/CCSm8XcnomjO2bHjAcFQPV7od0ghVngjaMfB5x5Agkrpe+49/tK6soegrwWyi6Lg== |
| vanilla-calendar-pro/3.4.0 | vanilla-calendar-pro@3.4.0 | MIT | index.js → vanilla-calendar-pro.js, styles/index.css → vanilla-calendar-pro.css (이름만 바꿈) | sha512-HLWjzHOWvMIrjI77tXK5bYzp0s5TeBSrZC247STjcTAdWI7kqEwWT6Xvu20vqdwzF498H1Da8l4WpdgVIGtc1Q== |
| split.js/1.6.5 | split.js@1.6.5 | MIT | dist/split.min.js | sha512-mPTnGCiS/RiuTNsVhCm9De9cCAUsrNFFviRbADdKiiV+Kk8HKp/0fWu7Kr8pi3/yBmsqLFHuXGT9UUZ+CNLwFw== |
| pretendard/1.3.9 | pretendard@1.3.9 | SIL OFL 1.1 (예약명 Pretendard 등 — 수정·서브셋 금지) | dist/web/variable/pretendardvariable-dynamic-subset.css + woff2-dynamic-subset/*.woff2 92개(브라우저는 화면에 나온 글자의 조각만 받는다, unicode-range), dist/LICENSE.txt. tokens.css 가 @import (docs/12 V2, docs/40 단계 1) | sha512-PaQAADyLY5v4kYFwkpSJHbSSYIkiriY/1xXw75TKoZ9UQQqeU+tvP05yTdZAWibiIYoo8ZKtRv8PM7w0IaywSw== |
| lucide/1.73.0 | lucide-static@1.73.0 (2026-11-14) | ISC | icons/ 에서 48개만(아이콘 파일 원본 그대로), LICENSE. 화면은 `static/css/icons.css` 의 CSS mask 로 그린다(색 = 글자색, 인라인 SVG·스크립트 없음 — CSP) (docs/12 V7, docs/40 단계 1) | sha512-/FfzdRThAPnDVMk643pzENqYUnxudEON/fyOdh31ZiPiw/seE9VoYeIJPRDTCLDNtnF+5pK6CNwf6IkQMfrJ5w== |

가져온 날: 2026-10-15 (`npm pack` → 압축 해제 → 복사, 별도 폴더에서). Pretendard·Lucide 는 2026-10-22 같은 방법. 2026-11-16 갱신(같은 방법, 가져온 파일 목록 그대로): tabulator 6.7.1·tom-select 2.7.0·kendo-ui-core 2026.4.1111(ESM 진입점 4개의 import 닫힘 30개 — 조각 파일 이름만 바뀜)·kendo-theme-default 14.10.0·lucide 1.73.0(48개 중 circle-help 모양만 바뀜). jQuery 는 kendo-ui-core 2026.4.1111 의존 범위가 그대로 `1.10.0 - 3.6` 이라 3.6.4 유지.

Kendo UI Core 라이선스 키: 패키지 코드로 확인 — `licensing-*.js` 의 `setScriptKey`·워터마크 함수가 **빈 함수**(no-op)라 키 없이 워터마크·배너가 나오지 않는다(docs/10 11.5).

**Kendo UI Core 한 파일 번들을 쓰지 않는 이유**(PoC 2026-10-15): `kendo.ui.core.min.js` 는 `kendo.color` 모듈이 전역 `@progress/kendo-drawing` 을 요구한다. 이 패키지는 kendo-ui-core 의 의존성에 없고 **상용 라이선스**(LICENSE.md: 상용 라이선스가 없으면 체험판 조건)다. 없으면 번들이 로드 중간에 TypeError 로 멈춰 Window·Splitter·Dialog·TabStrip·TimePicker·DateTimePicker·TextBox·MaskedTextBox·ListView·PanelBar·Slider·ColorPicker 가 등록되지 않는다. 위젯별 **UMD** 파일도 쓸 수 없다 — dateinput·datepicker·timepicker·datetimepicker·editable·listview 6개가 브라우저 전역 모드에서 `exports` 를 참조해 ReferenceError. **ESM 빌드**(`esm/`)는 상대 경로 import 만 있어(drawing 은 color 모듈만) `<script type="module">` 로 CSP 안에서 동작한다.
