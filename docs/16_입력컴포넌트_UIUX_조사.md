# 16 입력 컴포넌트 UI/UX 조사 — 선택 위젯·날짜·기간·숫자·검색 조건 통일

> 작성 2026-10-16 (조사 세션). 코드 수정 없음 — 이 문서만 새로 씀.
> 목적: P1 기준정보 화면을 만들기 전에 입력 UI 를 하나로 통일한다. 사용자 요청의 중심은 **기간(From~To) 입력을 달력 하나에서 두 날짜를 고르는 구조로** 바꾸는 것.
> 관련: 10 11장(편집 그리드·위젯 PoC, IME), 11(현장 채택), 12(디자인 토큰·밀도·폼 규칙), **15(반응형 — 화면 폭·breakpoint 는 15 를 따른다. 이 문서는 겹치는 부분을 다시 쓰지 않는다)**.

**근거 표기**
- [논문] 학술지·학회 논문(동료 심사) — DOI 또는 URL
- [보고서] 정부 기관 연구 보고서(동료 심사 아님)
- [가이드] 디자인 시스템·표준 문서(NN/g·Baymard 포함 — 실무 연구 기관, 상세는 유료인 것도 있음)
- [코드] 이 저장소 vendor 파일을 직접 grep 해서 확인(2026-10-16)
- [실측] 10 11.5 PoC 결과 인용
- [추정] 위 근거에서 이 문서가 끌어낸 판단 — 검토 때 가장 먼저 의심할 것

---

## 0. 요약

### 0.1 한 줄 결론

**입력은 "API 는 hb 가 소유하고, 엔진은 갈아 끼울 수 있게" 만든다.** 기간 선택기는 `hb.dateRange` 하나로 통일한다 — **한 컨트롤 안에 시작·종료 입력 칸 2개(키보드 직접 입력) + 팝업 1개(왼쪽 프리셋 목록, 오른쪽 2개월 달력, 클릭 두 번으로 범위)**. 오늘·프리셋은 **회사 시간대**로 계산하고, 최대 기간·시작≤종료는 **서버가 최종 검사**한다. 달력 그리드는 자체 제작(APG grid 키보드 전부)을 권고하고 vanilla-calendar-pro 3.4.0 은 예비안으로 둔다. Kendo UI Core 에는 DateRangePicker 가 없다(상용 전용).

### 0.2 상위 결정 (8장에 전체)

| # | 결정 | 권고 | 근거 요약 |
|---|---|---|---|
| **I1** | 기간 입력 구조 | `hb.dateRange` = 입력 2칸 + 팝업 1개(프리셋 + 2개월 달력). 기존 `<input type="date">` 두 개 방식 폐기 | NN/g "범위는 2개월 나란히" [가이드], Census 2026 "가까운 날짜는 달력이 더 빠르고 정확" [보고서], Bargas-Avila 2011 "한 칸 + 형식 표시가 가장 빠름" [논문] → **둘 다 제공** |
| **I2** | 날짜 엔진 | 달력 그리드 **자체 제작**(APG 키 전부, 회사 시간대 "오늘", 최대 기간 동적 비활성). VCP 3.4.0 은 예비안 | VCP 에 Home/End/PageUp/PageDown 없음 [코드], Kendo Core 에 DateRangePicker·MultiViewCalendar 없음 [가이드] |
| **I3** | 시작 > 종료 | **달력 클릭 = 자동 교환**(두 점이 범위를 정함), **직접 입력 = 오류 표시 + 조회 차단**(조용히 바꾸지 않음), **범위 붙여넣기 = 정렬해서 넣음**. 서버는 항상 400 | [추정] — 입력 경로별로 의도 확실성이 다름 |
| **I4** | 최대 기간 | 화면별 `maxDays`(화면 정의) + 회사 정책 상한 `SEARCH_MAX_RANGE_DAYS`(새 정책 키 제안), 서버 공통 검사기 `DateRanges.check` 와 오류 코드 `RANGE_TOO_LONG` | 현행 활용 현황 API 는 기간 검사가 없음 [코드] |
| **I5** | 단일 선택 | 2~5개 = 라디오(폼)·세그먼트(조회 조건·보기 전환), 6~15개 고정 목록 = 네이티브 `<select>`, 16개 이상·기준정보 = `hb.combo`(서버 검색) | Baymard "5개 미만·10개 초과는 드롭다운 부적합" [가이드], Jensen 2020 [논문], Liu 2020 CHI [논문] |
| (I6) | 콤보 엔진 | 자체 `hb.combo`(APG combobox + IME 조합 가드). Tom Select·Kendo ComboBox 는 PoC H10 결과에 따라 예비 | 두 라이브러리 모두 조합 이벤트 처리 코드 0 [코드] |
| (I7) | 숫자 | `type="number"` 금지 → `type="text" inputmode="decimal"` + `hb.number`(포커스 시 원값, 벗어나면 천 단위) | GOV.UK 2020 [가이드], Healey 2007 스크롤 오조작 [논문] |

### 0.3 기간 선택기 권고안 한눈에

```
 기간  [2026-10-01 ] ~ [2026-10-15 ] [📅]   (15일)
        └ 시작 칸      └ 종료 칸     └ 팝업 열기(Alt+↓)
 ┌──────────────┬──────────────────────────────────────────┐
 │ 오늘           │   ◀  2026년 9월            2026년 10월  ▶   │
 │ 어제           │  일 월 화 수 목 금 토    일 월 화 수 목 금 토   │
 │ 이번 주        │  ...                     [1]━━━━━━━━━━━━━━   │
 │ 지난 7일  ✓    │                          ━━━━━━━━━━━━━[15]  │
 │ 이번 달        │                                          │
 │ 지난달         │  시작 2026-10-01 · 종료 2026-10-15 · 15일     │
 │ 직접 선택      │  최대 92일                  [취소] [적용]   │
 └──────────────┴──────────────────────────────────────────┘
```

- 프리셋은 클릭 즉시 두 칸과 달력에 반영(달력과 항상 일치), 각 프리셋 옆에 실제 날짜를 회색으로 표시.
- 달력: 첫 클릭 = 시작, 두 번째 클릭 = 종료(마우스를 올리면 범위 미리보기). 시작 이후 `maxDays` 밖 날짜는 비활성.
- 키보드만으로: 시작 칸에 `20261001` → Tab → 종료 칸 `1015` → Enter = 조회. 팝업은 몰라도 된다.
- 붙여넣기: `2026-10-01 ~ 2026-10-15`, 엑셀 두 칸(탭 구분), `2026.10.01-2026.10.15` 를 어느 칸에 붙여도 두 칸에 나눠 들어간다.
- 조회 조건 저장은 **프리셋 키**(`last7`)로 — 내일 다시 열면 내일 기준 7일(SAP Fiori Dynamic Date Range 방식).

---

## 1. 범위와 방법

- 대상: 드롭다운(단일), 콤보박스·자동완성, 멀티셀렉트(태그형·체크 목록형), 날짜, **기간**, 체크박스, 라디오, 토글, 세그먼트, 숫자, 검색 조건 패널.
- 단말 구분: **사무**(1366~1920 폭, 키보드·마우스, compact 밀도 — 12 5.3) / **POP**(터치·장갑, `data-surface="pop"`, 컨트롤 56px 이상 — 12 5.3·5.9). 폭별 배치는 15 를 따른다.
- 방법: 웹 검색 + 논문 초록·본문(열람 가능한 것) + vendor 파일 직접 확인. 앱은 띄우지 않았다(10 11.5 PoC 실측 결과를 인용).
- 하지 않은 것: 사용자 실험, 스크린리더 실측, 벤더 유료 연구(Baymard Premium) 본문 열람.

---

## 2. 근거 정리

### 2.1 논문·연구 보고서

| # | 출처 | 무엇을 보였나 | YeonOS 에 주는 뜻 |
|---|---|---|---|
| P1 | Bargas-Avila et al. 2011, *Working towards Usable Forms on the WWW: Optimizing Date Entry Input Fields*, Advances in HCI — https://doi.org/10.1155/2011/202701 [논문] | 날짜 입력 6방식 비교(n=172). **드롭다운·달력은 형식 오류 0**, **한 칸 + 형식 표시(왼쪽·안쪽)가 가장 빠르고 만족도 높음**. 달력은 느리고 "엉뚱한 날짜" 오류가 더 많았다 | 키보드 사용자는 한 칸 직접 입력이 최선, 달력은 **보조**로 같이 둔다. 형식 표시(`YYYY-MM-DD`)는 칸 안·옆에 항상 보이게 |
| P2 | Rivas, Nichols, Horwitz 2026, *Differences in Usability Between Three Date-entry Designs*, U.S. Census Bureau RSM2026-08 — https://www.census.gov/library/working-papers/2026/adrm/rsm2026-08.html [보고서] | 1,000명, 텍스트·드롭다운·달력. **가까운 날짜·요일이 중요한 과제에서 달력이 효과(정확)·효율(속도) 모두 우세**. 체감 난이도 차이 없음. "과제 성격과 떼어 평가할 수 없다" | MES 조회 기간은 대부분 "지난주·이번 달" 같은 **가까운 날짜 + 요일 의식** → 달력 가치가 크다. P1 과 합치면 "입력 + 달력 둘 다" |
| P3 | Romikaitytė et al. 2022, *A Comparison of Date Selection Elements on Mobile Touch Devices in eCommerce Sites*, CHIRA 2022 — https://www.scitepress.org/Papers/2022/115778/115778.pdf [논문] | 모바일 터치에서 스피너·드롭다운·라디오 비교(GOMS + 온라인 실험) → **드롭다운이 시간·만족·오류 모두 우세**(익숙함) | POP 에서 낯선 위젯(스피너·휠)은 피한다. 익숙한 모양 우선 |
| P4 | Healey 2007, *Drop Downs and Scroll Mice*, Social Science Computer Review 25(1) — https://doi.org/10.1177/0894439306293888 [논문] | 2,400명. 라디오·드롭다운 완료율·시간 차이 없음. 단 **드롭다운은 스크롤 휠로 값이 의도치 않게 바뀌는 오조작** | 값이 휠로 바뀌는 컨트롤(구형 select, `type=number`) 금지 근거. 숫자는 텍스트 입력(I7) |
| P5 | Heerwegh & Loosveldt 2002, *An Evaluation of the Effect of Response Formats on Data Quality in Web Surveys*, SSCR 20(4) — https://doi.org/10.1177/089443902237323 [논문] | 드롭다운이 라디오보다 조작이 어렵고 이탈이 많았다(익숙하지 않은 사용자) | 항목이 적으면 **보이는 선택지**(라디오·세그먼트) |
| P6 | Jensen, Hansen, Eika, Sandnes 2020, *Country Selection on Web Forms: Dropdown, Radio, Autocomplete*, IMCOM 2020 — https://doi.org/10.1109/IMCOM48794.2020.9001795 [논문] | 국가 선택(긴 목록) n=17. **자동완성이 가장 빠름(드롭다운과 유의차 없음), 라디오는 둘보다 유의하게 느림**. 결론: 드롭다운 + 접두어 입력을 함께 쓰는 **다중 모드 컨트롤** 지지 | 긴 목록은 `hb.combo`: 열어서 훑기 + 타이핑 필터 둘 다. 표본이 작아(17명) 방향만 참고 |
| P7 | Cockburn, Gutwin, Greenberg 2007, *A Predictive Model of Menu Performance*, CHI '07 — https://doi.org/10.1145/1240624.1240723 [논문] | 메뉴 선택 시간 = Fitts(포인팅) + 초보의 **시각 탐색(항목 수에 선형)** + 숙련자의 Hick-Hyman(로그) + 학습 전이. 예측이 실측과 잘 맞음. 빈도 기반 분할 메뉴가 유리 | (a) 목록 **순서를 고정**해야 숙련이 생긴다(최근 사용 순으로 재정렬 금지) (b) 자주 쓰는 항목은 **별도 "최근 사용" 묶음**을 위에 — 원 목록은 그대로 |
| P8 | Cockburn & Gutwin 2009, *A Predictive Model of Human Performance With Scrolling and Hierarchical Lists*, HCI 24(3), 273–314 (DOI 미확인) [논문] | 스크롤·계층 목록의 선택 시간 모델 | 긴 드롭다운(스크롤 필요)은 비용이 커진다 → 일정 개수 넘으면 타이핑 필터 |
| P9 | Liu, Gori, Rioul, Beaudouin-Lafon, Guiard 2020, *How Relevant is Hick's Law for HCI?*, CHI '20 — https://doi.org/10.1145/3313831.3376878 [논문] | Hick 법칙은 오히려 **"적을수록 좋다"를 지지하지 않는다**(로그라서 항목 추가 비용은 작다). HCI 과제에선 선택 반응 시간이 거의 상수, **시각 탐색·의사결정을 따로 보라** | "선택지 줄이기" 를 위해 공통코드를 자르지 않는다. 대신 **정렬·그룹·타이핑 필터로 탐색 비용**을 낮춘다 |
| P10 | Parhi, Karlson, Bederson 2006, *Target Size Study for One-Handed Thumb Use on Small Touchscreen Devices*, MobileHCI '06 — https://doi.org/10.1145/1152215.1152260 [논문] | 단일 탭 9.2mm, 연속 탭 9.6mm 이상이면 오류 증가 없음 | POP 달력 칸·세그먼트 최소 크기의 하한. 장갑이면 더 크게(P11). CSS mm 는 물리 길이가 아니므로 px + 단말 실측(12 V10) |
| P11 | Sun, Plocher, Qu, *An Empirical Study on the Smallest Comfortable Button/Icon Size on Touch Screen* — http://enginpsych.psych.ac.cn/upload/201304100616401487561624.PDF [논문] | 장갑 착용·간격은 수행에 영향 없고 **크기만 영향**(40×40px 이상에서 정확도 89%→98%) | 장갑 대응 = 간격보다 **크기**. 표본·화면 조건이 오래돼 수치는 참고만 |
| P12 | Tao et al. 2018, *Effects of button design characteristics on performance and perceptions of touchscreen use*, Int. J. Industrial Ergonomics 64 — https://www.sciencedirect.com/science/article/abs/pii/S0169814117302366 [논문] | 7.5~27.5mm 비교, **17.5mm 이상 정사각 버튼**이 수행 우수 | POP 주요 버튼(프리셋·확정)은 17mm 급(단말 실측으로 px 환산) |
| P13 | *A Comparison of Multiple Selections Using Multiple Checkbox Selections and List Boxes*, HCII 2021 Late Breaking Posters — https://www.researchgate.net/publication/355948257 [논문, 포스터] | 24명. 다중 선택은 **체크박스가 리스트박스(Ctrl+클릭)보다 빠르고 선호** | 멀티셀렉트는 항목마다 체크 표시가 보이는 형태(체크 목록형). Ctrl+클릭식 `<select multiple>` 금지 |

> 논문의 한계: 대부분 일반인 웹 설문·전자상거래 과제이고, **MES 숙련 사용자가 하루 수십 번 같은 화면을 쓰는 상황**을 직접 다룬 연구는 찾지 못했다. 그래서 P7·P9(숙련자 모델)를 무겁게, 설문 연구(P4·P5)는 "오조작·익숙함" 근거로만 썼다 [추정].

### 2.2 가이드

| # | 출처 | 요점 |
|---|---|---|
| G1 | NN/g *Date-Input Form Fields* — https://www.nngroup.com/articles/date-input/ [가이드] | 달력은 **1년 이내 가까운 날짜**에, 먼 날짜는 직접 입력이 빠르다. **범위 선택에 달력이 특히 유용 — 두 달을 나란히**, 시작·종료를 다른 색으로, 범위는 옅은 배경. "오늘" 표시 |
| G2 | NN/g *Toggle-Switch Guidelines* — https://www.nngroup.com/articles/toggle-switch-guidelines/ [가이드] | 토글은 **즉시 반영**될 때만. 저장 버튼을 눌러야 반영되면 체크박스 |
| G3 | NN/g *Listboxes vs. Dropdown Lists* — https://www.nngroup.com/articles/listbox-dropdown/ [가이드] | 다중 선택은 체크박스가 보이는 리스트박스. 수식키(Ctrl/Shift) 의존 금지 |
| G4 | Baymard *Drop-Down Usability* — https://baymard.com/research-articles/drop-down-usability [가이드] | **5개 미만·10개 초과는 드롭다운이 대체로 부적합** — 작으면 시간 낭비, 크면 이해·선택 오류. 대안: 라디오·텍스트 입력·자동 선택. 맞춤 드롭다운 31% 가 키보드 문제(https://baymard.com/blog/custom-dropdowns-cause-issues) |
| G5 | GOV.UK *Date input* / *Dates* 패턴 — https://design-system.service.gov.uk/components/date-input/ [가이드] | 아는 날짜는 입력 칸. 달력은 **가까운 과거·미래, 요일, 다른 날짜와의 관계**가 필요할 때만. 칸 사이 자동 포커스 이동 금지 |
| G6 | GOV.UK *Why we changed the input type for numbers* (2020) — https://technology.blog.gov.uk/2020/02/24/why-the-gov-uk-design-system-team-changed-the-input-type-for-numbers/ [가이드] | `type=number` 문제: 비숫자 조용히 버림, **화살표·스크롤로 의도치 않게 증감**, 스크린리더 라벨 누락, 큰 수 지수 표기 → `type="text" inputmode="numeric"` |
| G7 | KRDS 날짜 입력 필드 — https://www.krds.go.kr/html/site/component/component_09_01.html , 달력 — https://www.krds.go.kr/html/site/component/component_04_03.html [가이드] | 단일 입력 필드 + 기억하기 어려운 날짜면 날짜 선택기 병행. **형식 안내는 플레이스홀더만으로 하지 말고 도움말 텍스트로**. 원본 날짜는 **복사·붙여넣기 가능하게**. 특정하기 어려우면 **범위 입력**. 달력은 요일 헤딩 필수, **의미를 색으로만 구분 금지** |
| G8 | Carbon *Date picker* — https://carbondesignsystem.com/components/date-picker/usage/ [가이드] | 범위 피커: 두 칸 각각 **직접 입력 또는 달력**, 시작·종료 라벨 분리, 오류는 **문제 칸만** 표시, 달력은 항상 타이핑도 허용 |
| G9 | SAP Fiori *Dynamic Date Range* — https://www.sap.com/design-system/fiori-design-web/v1-96/ui-elements/dynamic-date-range-2/usage [가이드] | 절대 날짜 + **상대 날짜(오늘·어제·지난 X일·이번 달…)** 를 한 컨트롤에서. 기본 29종. 단순 범위면 쓰지 말 것 |
| G10 | Material 3 Segmented button — https://m3.material.io/components/segmented-buttons/guidelines [가이드] | 2~5개, 더 많으면 칩 등 다른 컴포넌트(M3 Expressive 는 connected button group 으로 대체) |
| G11 | WAI-ARIA APG Combobox — https://www.w3.org/WAI/ARIA/apg/patterns/combobox/ , Select-only 예 — https://www.w3.org/WAI/ARIA/apg/patterns/combobox/examples/combobox-select-only/ , List autocomplete 예 — https://www.w3.org/WAI/ARIA/apg/patterns/combobox/examples/combobox-autocomplete-list/ [가이드] | `role=combobox` 는 **포커스 받는 input 에**, `aria-expanded`·`aria-controls`·`aria-activedescendant`, 옵션 `role=option` + `aria-selected`. activedescendant 대상은 브라우저가 스크롤해 주지 않으니 JS 가 scrollIntoView |
| G12 | WAI-ARIA APG Date Picker Dialog — https://www.w3.org/WAI/ARIA/apg/patterns/dialog-modal/examples/datepicker-dialog/ , Grid — https://www.w3.org/WAI/ARIA/apg/patterns/grid/ [가이드] | 날짜 칸: ←→ 하루, ↑↓ 일주, **Home/End 주 처음·끝, PageUp/PageDown 한 달, Shift+PageUp/Down 1년**, Enter/Space 선택, Esc 닫고 버튼 복귀. `role=grid`, 날짜 칸 roving tabindex, 선택 칸 `aria-selected`, 월 제목 `aria-live=polite`, 요일 헤더 `abbr` |
| G13 | WAI-ARIA APG Listbox — https://www.w3.org/WAI/ARIA/apg/patterns/listbox/ [가이드] | 다중 선택 `aria-multiselectable`, Space 토글, Shift+↑↓ 확장, Ctrl+A 전체 |
| G14 | Fluent UI — https://github.com/microsoft/fluentui-blazor/discussions/1980 [가이드, 2024] | Fluent(Blazor) 에는 기간 선택기 없음 — DatePicker 두 개로 대체 권고. 즉 "통합 기간 선택기"는 디자인 시스템마다 편차가 큼 |
| G15 | 범위 프리셋 사례 — Nord Date Range Picker https://nordhealth.design/components/date-range-picker/ , GitLab 이슈 https://gitlab.com/gitlab-org/gitlab/-/issues/408656 [가이드] | 프리셋 목록을 기본 표시, **선택 일수 표시**, 종료일 포함(inclusive), 범위를 URL 에 담아 비교 가능하게 |

### 2.3 근거에서 뽑은 원칙 (이후 장의 기준)

1. **입력 경로를 둘 다 연다** — 키보드 직접 입력(P1·G8) + 달력(P2·G1). 어느 하나만 강제하지 않는다.
2. **보이는 선택지 > 숨은 선택지** (항목 2~5개) — P5·G4·G10. 단 공간이 없거나 항목이 늘 수 있으면 select.
3. **긴 목록은 타이핑 필터** — P6·P8. 순서는 고정(P7), "최근 사용"은 별도 묶음.
4. **선택지를 억지로 줄이지 않는다** — P9. 대신 탐색 비용을 줄인다.
5. **값이 몰래 바뀌는 조작 금지** — 휠·화살표 증감(P4·G6), 직접 입력한 기간의 조용한 교환(I3).
6. **즉시 반영 = 토글, 저장 후 반영 = 체크박스** — G2.
7. **POP 은 크기가 정확도를 정한다** — P10~P12. 간격보다 크기.
8. **APG 키보드 패턴을 그대로** — 키보드 위주 MES 사무 사용자 + 접근성(G11~G13).

---

## 3. 현행 진단 (2026-10-16, `40a7f08` + 작업 트리)

| # | 위치 | 상태 | 문제 |
|---|---|---|---|
| C1 | `templates/platform/usage.html` | `<input type="date" id="from"> ~ <input type="date" id="to">` | 사용자 요청 대상. 두 칸이 별개 위젯 — 범위가 한눈에 안 보이고, 브라우저마다 모양·형식 다름(Chrome 은 OS 로캘 형식) |
| C2 | `js/views/platform/usage.js:47-49` | `new Date(...).toISOString().substring(0,10)` 로 기본값 | **UTC 날짜**다. 한국 00:00~08:59 에 열면 "어제"가 이틀 전으로 계산된다. 회사 시간대(`hb.js` 의 `session.timeZone`)를 써야 함 — CLAUDE.md "시간대 없는 now() 금지" 의 화면 쪽 위반 [코드] |
| C3 | `UsageApiController` | `from`·`to` 를 그대로 서비스에 | **시작≤종료·최대 기간 검사 없음**. `from=2000-01-01&to=2099-12-31` 같은 요청이 그대로 집계 쿼리로 간다(이상 사용 E류) [코드] |
| C4 | `templates/master/cust-mgt.html` | `<form id="search" class="hb-search">` + 텍스트 입력 | 검색 패널 규칙의 출발점. 기간·코드 선택이 아직 없음 |
| C5 | `dev/grid-poc` | Kendo Core·Tom Select·VCP 비교 페이지 | IME 판정(10 11.5.2 H10·H14) 사람 결과 대기 |

> C2·C3 는 이 문서 범위 밖의 수정이지만 `hb.dateRange` 도입 1단계에 같이 고치도록 9장에 넣었다.

---

## 4. 언제 무엇을 쓰나 — 결정표

### 4.1 단일 선택

| 항목 수 | 목록 성격 | 사무 — 입력 폼 | 사무 — 검색 조건 | POP |
|---|---|---|---|---|
| 2 (예/아니오) | 고정 | **체크박스 1개**(저장 시 반영) / 의미가 대칭이면 라디오 2개 | **세그먼트**(전체·예·아니오 = 3개) | 큰 세그먼트 |
| 3~5 | 고정(공통코드) | **라디오**(가로 배치) | **세그먼트** 또는 select(조건이 많아 폭이 모자랄 때) | 큰 세그먼트(2줄 허용) |
| 6~15 | 고정(공통코드) | **네이티브 `<select>`** | 네이티브 select (+ "전체") | 버튼 그리드(3~4열) 또는 전체 화면 목록 |
| 16~100 | 고정 또는 반고정 | **`hb.combo`**(클라이언트 필터) | `hb.combo` | 검색어 + 큰 목록, 스캔 우선 |
| 100+ / 기준정보(거래처·품목·설비·LOT) | 서버 데이터 | **`hb.combo`(서버 검색)** + F2 상세 검색 대화상자 | 같음 | **바코드 스캔이 1순위**, 검색은 보조 |

- 근거: G4(5 미만·10 초과 드롭다운 부적합), P5·G10(적으면 보이게), P6·P8(길면 타이핑), P9(줄이지 말 것).
- 경계값 6·16 은 G4 의 5·10 을 **사무 숙련 사용자 + 공통코드(순서 고정, 숙련 시 타이핑 점프 가능)** 에 맞춰 조금 늘린 값 [추정]. 네이티브 select 는 첫 글자 타이핑 점프가 되므로 15개까지 무리가 적다.
- **세그먼트 vs 라디오**: 세그먼트는 "보기·필터를 바꾸면 결과가 즉시 바뀌는 곳"(검색 조건, 그리드 보기 전환), 라디오는 "저장 버튼으로 반영되는 폼 값". 시각적으로 비슷하지만 역할을 섞지 않는다 [추정, G2 의 즉시/저장 구분을 차용].
- 같은 공통코드라도 화면마다 위젯이 달라질 수 있다 — 공통코드 정의에 위젯을 넣지 않고 **화면이 고른다**. 단 "항목 수가 늘 수 있는 코드"(회사가 추가하는 코드)는 처음부터 select/combo.

### 4.2 다중 선택

| 항목 수 | 사무 — 폼 | 사무 — 검색 조건 | POP |
|---|---|---|---|
| 2~7 | **체크박스 그룹** | 체크박스 그룹 또는 다중 세그먼트 | 큰 토글 버튼(선택 = 체크 아이콘 + 테두리, 색만 X) |
| 8~30 | `hb.multiSelect` **체크 목록형**(검색칸 + 전체 선택 + "3개 선택" 요약) | 체크 목록형(높이 고정) | 전체 화면 체크 목록 |
| 30+ / 기준정보 | `hb.multiSelect` **태그형**(서버 검색, 선택은 태그) | 체크 목록형 + 서버 검색(태그가 줄을 넘쳐 패널 높이가 흔들리지 않게) | 스캔 누적 목록 |

- 근거: P13·G3(체크 표시가 보이는 형태), 검색 패널 높이 고정은 레이아웃 흔들림 방지 [추정].
- `<select multiple>`(Ctrl+클릭) 금지.

### 4.3 불리언·상태

| 상황 | 위젯 | 근거 |
|---|---|---|
| 폼 값(저장 버튼으로 반영) — `use_yn` 등 | **체크박스** + 라벨 "사용" | G2 |
| 즉시 반영 설정(그리드 설정 "줄무늬", 개인 설정, 알림 켜기) | **토글**(`<input type="checkbox" role="switch">`) — 바뀌면 바로 저장·적용, 실패 시 되돌림 | G2. Kendo Core 에 Switch 없음 [가이드] → CSS 만으로 |
| 검색 조건의 사용 여부 | **세그먼트** "전체·사용·미사용" | 3상태가 필요 |
| 위험한 설정(회사 사용 중지 등) | 토글 금지 — 버튼 + 확인 대화상자(12 5.7) | G2 |

### 4.4 날짜·기간

| 상황 | 위젯 | 근거 |
|---|---|---|
| 가까운 날짜 1개(납기·입고일·작업일) | `hb.date` = 입력 칸 + 달력 팝업 | P1·P2·G1 |
| 먼 날짜(생년월일·설비 도입일·인증 만료일) | `hb.date` 의 `calendar:false`(입력 칸만, 형식 안내) | G1·G5 |
| 조회 기간 | **`hb.dateRange`** | 5장 |
| 월 단위(월 마감·월별 집계) | `hb.month`(연·월 입력 + 12개월 격자) — 기간이 필요하면 `hb.dateRange` 의 월 모드 | [추정] |
| 일시(설비 정지 시각 등) | `hb.date` + 시각 칸(`HH:mm`, 24시간) — 원장 `occurred_at` 은 회사 시간대로 입력, 서버가 timestamptz 로 | CLAUDE.md BizClock |
| POP | 프리셋 큰 버튼(오늘·어제·이번 주) + 1개월 큰 달력, 직접 입력은 숫자 키패드 | P10~P12 |

### 4.5 숫자

| 상황 | 위젯 |
|---|---|
| 수량·금액·소수(단위별 자릿수) | `hb.number`(`type="text" inputmode="decimal"`) — 포커스 시 원값, blur 시 천 단위·자릿수 |
| 코드처럼 생긴 숫자(사업자번호·전화·LOT 번호) | 일반 텍스트(`inputmode="numeric"`) — 숫자로 다루지 않음(G6 "숫자처럼 보이지만 숫자가 아닌 것") |
| POP 수량 | 큰 입력 + ±버튼(스텝) + 화면 키패드 선택 [추정] |

- `type="number"` 금지(G6·P4). ↑↓ 증감·휠 증감 없음(사무). POP ±버튼은 눈에 보이는 명시적 조작이라 허용.

### 4.6 결정 흐름 (요약)

```
값이 날짜인가? ── 예 ─ 범위? ─ 예 → hb.dateRange
                           └ 아니오 → 가까운 날짜? → hb.date(달력) / 먼 날짜 → hb.date(입력만)
아니오
값이 숫자량인가? ── 예 → hb.number
아니오
여러 개 고르나? ── 예 → ≤7 체크박스 / ≤30 체크 목록형 / 그 이상 태그형
아니오
예/아니오인가? ── 예 → 즉시 반영? 토글 : 체크박스  (검색 조건이면 세그먼트 3상태)
아니오
항목 수 ≤5 → 폼: 라디오 / 검색·보기: 세그먼트
항목 수 6~15 고정 → 네이티브 select
그 이상·기준정보 → hb.combo (POP 은 스캔 우선)
```

---

## 5. 기간 선택기 `hb.dateRange` 상세 설계

### 5.1 구조 선택 — 왜 "입력 2칸 + 팝업 1개" 인가

| 후보 | 장점 | 단점 | 판단 |
|---|---|---|---|
| A. 현행: 네이티브 date 2개 | 코드 0, 접근성 브라우저 몫 | 범위가 안 보임, 브라우저마다 형식·달력 다름, 프리셋 없음, 회사 시간대 "오늘" 표시 불가 | 폐기 |
| B. 읽기 전용 칸 1개 "2026-10-01 ~ 2026-10-15" + 팝업 | 깔끔 | **키보드 직접 입력 불가**(P1 의 가장 빠른 경로를 막음), 칸 하나에 두 값이라 오류 위치 표시가 애매 | 기각 |
| C. 편집 가능한 칸 1개(범위 문자열 파싱) | 붙여넣기 편함 | 커서 위치별 편집이 어렵고, 스크린리더가 "시작/종료" 구분 못 함 | 기각(붙여넣기 파싱은 D 에 흡수) |
| **D. 한 컨트롤 안에 시작·종료 칸 2개 + 공유 팝업 1개** | 칸마다 직접 입력·오류 표시(G8), 라벨 분리, 달력은 한 번에 두 날짜(G1), 프리셋 | 구현량 | **채택** |

D 의 화면 모양(사무, compact):

```
<기간 *>  ┌─────────────────────────────────────────┐
          │ 2026-10-01  ~  2026-10-15        📅 │  15일
          └─────────────────────────────────────────┘
           형식 YYYY-MM-DD · 최대 92일          ← 도움말(KRDS: 플레이스홀더만으로 안내 금지)
```

- 한 테두리 안에 두 칸(시각적으로 한 컨트롤), DOM 은 `<input>` 2개 — 각각 `aria-label="시작일"`·`"종료일"`, 묶음은 `role="group"` + `aria-labelledby`(라벨 "기간").
- 칸 폭은 `YYYY-MM-DD` 10자 + 여유. 전체 폭 ≈ 240px(compact) [추정]. 좁은 폭 배치는 15 를 따른다.
- 일수 표시("15일")는 컨트롤 오른쪽 회색 글자 — 최대 기간 초과 시 오류색 + 아이콘(G15).

### 5.2 팝업 구성

```
┌ 프리셋(목록) ┬ 달력(2개월) ────────────────────────────────┐
│ 오늘    10-16 │  ◀ 2026년 9월               2026년 10월 ▶     │
│ 어제    10-15 │  (요일 헤더 일~토, 주 시작은 정책)              │
│ 이번 주 10-12~│                                              │
│ 지난 7일 ✓    │                                              │
│ 이번 달       │                                              │
│ 지난달        │                                              │
│ 직접 선택     │  시작 10-01 · 종료 10-15 · 15일 (aria-live)     │
│               │                        [취소]  [적용]         │
└───────────────┴──────────────────────────────────────────────┘
```

- **열기**: 📅 버튼 클릭, 입력 칸에서 `Alt+↓`(APG combobox 관례), POP 은 칸 터치. **입력 칸 포커스만으로는 열지 않는다** — 키보드 사용자가 Tab 으로 지나갈 때 팝업이 가리는 것 방지 [추정, G11 의 "포커스 시 자동으로 열지 않음"].
- **달 수**: 사무 2개월(G1), 폭이 모자라면 1개월(15 기준). 처음 보여 줄 달 = 종료일이 있는 달이 오른쪽.
- **적용 방식**: 프리셋 클릭 = 즉시 적용하고 닫기(한 번 클릭으로 끝나야 프리셋 의미가 있다). 달력에서 두 번째 날짜 클릭 = 즉시 적용·닫기. [취소]·[적용] 버튼은 **키보드로 달력을 돌아다니다 확정할 때**와 스크린리더용으로 둔다(APG Date Picker Dialog 의 OK/Cancel) — 마우스 사용자는 누를 일이 거의 없다 [추정].
- **Esc**: 변경 취소 + 닫기 + 포커스는 연 칸(또는 버튼)으로.
- **달력과 프리셋 동기화**: 프리셋을 고르면 달력도 그 범위를 보여 준다. 달력에서 직접 고르면 해당 프리셋과 같으면 그 프리셋에 ✓, 아니면 "직접 선택" 에 ✓ (G15 "버튼만 바뀌고 달력이 다르면 함정").

### 5.3 달력 상호작용 규칙

| # | 상황 | 동작 |
|---|---|---|
| D1 | 팝업을 연 직후 | 기존 범위 표시. 포커스 = 시작 칸에서 열었으면 시작일, 종료 칸에서 열었으면 종료일(없으면 오늘) |
| D2 | 첫 클릭 | 그 날을 **새 시작일**로, 종료일 비움. 안내 "종료일을 고르세요"(live region) |
| D3 | 첫 클릭 후 마우스 이동 | 시작~마우스 위치를 옅은 배경으로 미리보기 + 일수 표시 |
| D4 | 두 번째 클릭이 시작일 **이후** | 종료일로 확정 → 적용·닫기 |
| D5 | 두 번째 클릭이 시작일 **이전** | **두 날짜를 교환**해 범위로 확정(클릭한 날 = 시작, 첫 클릭 = 종료). 두 클릭이 범위의 양 끝이라는 의도가 분명하므로 [추정, I3] |
| D6 | 두 번째 클릭이 시작일과 **같은 날** | 하루짜리 범위(시작 = 종료)로 확정 |
| D7 | `maxDays` 가 있을 때 첫 클릭 후 | 시작 ± (maxDays−1) 밖 날짜를 **비활성**(`aria-disabled`, 줄무늬 + 흐림 — 색만 X). 안내 "최대 92일" |
| D8 | `min`·`max`(예: 활용 현황은 어제까지) | 밖 날짜는 처음부터 비활성. 프리셋도 이 경계로 잘림(5.4) |
| D9 | 키보드 | APG 날짜 그리드 키 전부(G12) + **Enter 첫 번째 = 시작, 두 번째 = 종료**. Shift+화살표로 범위 늘리기는 하지 않음(학습 비용 대비 효용 낮음) [추정] |
| D10 | 오늘 표시 | **회사 시간대 기준** 오늘에 테두리 + "오늘" 글자(스크린리더 `aria-current="date"`) |
| D11 | 주말·휴일 | 토·일 글자색 + **요일 헤더로도 구분**(색만 X — G7). 회사 휴일 달력은 P2 이후 연동(`holidays` 옵션만 열어 둠) |

시각 규칙(12 토큰 사용): 시작·종료 = `--hb-action` 배경 + 흰 글자 + 모서리 둥근 끝, 사이 = `--hb-action-subtle` 배경, 오늘 = 2px 테두리. 시작·종료는 모양(끝 모서리)으로도 구분(G7 색만 X).

### 5.4 프리셋

**기본 목록**(사용자 요청 그대로): 오늘 · 어제 · 이번 주 · 지난 7일 · 이번 달 · 지난달 · 직접 선택.
**추가 가능**(화면이 고름): 지난주 · 지난 30일 · 지난 3개월 · 올해 · 작년 · 이번 분기.

정의 — 모두 **회사 시간대의 오늘(T)** 기준, **종료일 포함(inclusive)**:

| 키 | 라벨 | 시작 | 종료 | 비고 |
|---|---|---|---|---|
| `today` | 오늘 | T | T | |
| `yesterday` | 어제 | T−1 | T−1 | |
| `thisWeek` | 이번 주 | 이번 주 첫날 | T | 주 시작 = 회사 정책(기본 **월요일** — ISO 8601·KS X ISO 8601, 국내 업무 관행) [추정]. 달력 표시 첫 요일과 같은 값을 쓴다 |
| `lastWeek` | 지난주 | 지난주 첫날 | 지난주 마지막 날 | |
| `last7` | 지난 7일 | T−6 | T | **오늘 포함 7일**(G15 GitLab 정의). 라벨 옆 날짜로 모호성 제거 |
| `last30` | 지난 30일 | T−29 | T | |
| `thisMonth` | 이번 달 | 이번 달 1일 | T | "말일까지"가 아니라 오늘까지 — 미래 날짜 조회 방지 |
| `lastMonth` | 지난달 | 지난달 1일 | 지난달 말일 | |
| `custom` | 직접 선택 | — | — | 달력·입력으로 바꾸면 자동으로 이 상태 |

**기준일 보정(anchor)**: 화면에 `max` 가 있으면(예: 활용 현황은 "전날까지 집계" → `max = T−1`) 프리셋의 T 를 `min(T, max)` 로 바꾼다. 활용 현황의 "지난 7일" = T−7~T−1 [추정 — 화면별 의미를 라벨 옆 날짜로 보여 주므로 혼동이 적다]. `today` 처럼 max 밖으로 완전히 나가는 프리셋은 목록에서 숨긴다.

**저장·복원**: 조회 조건을 저장(그리드 설정 `sys_grid_state`, URL 쿼리, 화면 기본값)할 때는 **프리셋 키**를 저장하고, 열 때 다시 계산한다(G9 Fiori 의 상대 날짜). `custom` 일 때만 절대 날짜 저장. URL 은 `?range=last7` 또는 `?from=2026-10-01&to=2026-10-15`(G15 "URL 로 비교 가능").

**자정 넘김**: 페이지를 23:59 에 열고 00:01 에 프리셋을 누르면 → **누른 순간** 회사 시간대로 다시 계산. 조회 버튼을 누를 때 프리셋 상태면 그때 다시 계산해 보낸다(열어 둔 화면이 어제 기준으로 조회하는 일 방지) [추정].

**근무일 경계(교대)**: 생산·POP 에서 "오늘"이 달력 날짜가 아니라 **근무일**(예: 06:00~다음 날 05:59)일 수 있다. 이 문서는 달력 날짜만 정의하고, 근무일 프리셋(`thisShiftDay`)은 P2 생산실적 설계 때 공장 교대 정책과 같이 정한다 → 8장 I9.

### 5.5 키보드 직접 입력

**받는 형식**(칸마다, blur 또는 Enter 때 해석해 `YYYY-MM-DD` 로 정리):

| 입력 | 해석 | 비고 |
|---|---|---|
| `2026-10-01`, `2026.10.01`, `2026/10/01`, `2026 10 01` | 2026-10-01 | 구분자 `-./` 와 공백 |
| `20261001` | 2026-10-01 | 8자리 숫자 — **가장 빠른 경로**, 한/영 상태와 무관(숫자는 한글 IME 에서도 그대로) |
| `2026-1-5` | 2026-01-05 | 한 자리 월·일 |
| `1001`, `10-01`, `10/1` | **올해**(회사 시간대) 10-01 | 연도 생략 = 올해 [추정] |
| `01` | 이번 달 1일 | 두 자리 이하 = 일 [추정 — 검토 필요] |
| `2026년 10월 1일` | 2026-10-01 | 문서에서 복사한 한국어 날짜(G7 붙여넣기) |
| `26-10-01`, `261001` | **거부** | 두 자리 연도는 1926/2026 모호 → 오류 "연도는 4자리로" [추정] |
| `2026-02-30`, `2026-13-01` | **거부**(원문 유지 + 오류) | 자동 보정(3월 2일로 넘김) 금지 — 10 PoC 붙여넣기 규칙과 같음 [실측 R6] |
| `46296`(엑셀 일련번호) | 거부 | 10 PoC 규칙과 같음 |
| 빈 칸 | 필수면 오류, 아니면 "열린 범위"(한쪽만) | 5.7 |

- 형식 안내는 칸 아래 도움말로 항상 표시(G7·G8·P1 "형식 요구를 보이게").
- **자동 포커스 이동 금지**: 시작 칸에 8자리를 다 쳐도 종료 칸으로 자동으로 넘기지 않는다(G5). 사용자는 Tab 으로 넘어간다.
- **↑↓ 로 날짜 증감 없음**(G6 의 의도치 않은 증감 문제). 대신 `Alt+↓` = 팝업.
- 해석은 **`input` 이벤트가 아니라 blur·Enter 에서만** — 입력 도중 "2026-1" 을 1월로 바꿔 쓰지 않는다.
- **IME**: 한글 모드에서 숫자를 치면 Windows 에서 keydown `keyCode 229` 가 올 수 있다 [가이드 10 R1·R3]. 키 이벤트로 글자를 거르지 말고(**keydown 으로 입력을 막는 마스킹 금지**) 값만 해석한다. Enter 는 `isComposing`·229 면 무시하고 compositionend 뒤 Enter 를 받는다(10 11.5 R12 와 같은 규칙). 한국어는 후보 선택 단계가 없으므로 compositionend 직후 Enter 를 삼키지 않는다(Japanese/Chinese 와 다름) [가이드 — 2.2 이후 IME 검색 결과, https://azukiazusa.dev/en/blog/ime-enter-submit/].
- `inputmode`: 사무는 지정하지 않음(구분자 `-` 입력 필요), POP·모바일은 `inputmode="numeric"` + 8자리 입력 유도.

### 5.6 붙여넣기

어느 칸에 붙이든 클립보드 텍스트에서 **날짜 토큰을 최대 2개** 찾는다:

| 붙여넣은 값 | 결과 |
|---|---|
| `2026-10-01 ~ 2026-10-15` / `2026-10-01 - 2026-10-15` / `2026.10.01~2026.10.15` | 시작·종료 둘 다 채움 |
| `2026-10-01\t2026-10-15`(엑셀 두 칸) / 두 줄 | 둘 다 채움 |
| `2026-10-15 ~ 2026-10-01`(거꾸로) | **정렬해서** 시작 10-01, 종료 10-15 + 안내 "시작·종료를 날짜 순으로 넣었습니다"(live region) — 두 날짜가 한꺼번에 와서 범위 의도가 분명 [추정, I3] |
| 날짜 1개 | 붙인 칸에만 |
| 날짜 3개 이상, 해석 불가 텍스트 | 기본 붙여넣기(그 칸에 텍스트 그대로) → blur 때 오류 |
| 1천 자 초과 | 해석하지 않고 거부(성능·이상 사용) |

- 날짜 토큰 정규식은 `\d{4}[-./]\d{1,2}[-./]\d{1,2}` · `\d{8}` · `\d{4}년 ?\d{1,2}월 ?\d{1,2}일` 만. `2026-10-01-2026-10-15` 처럼 하이픈이 구분자와 겹쳐도 토큰 단위로 먼저 자르므로 안전.
- 그리드 셀 붙여넣기 파서(10 PoC `paste-parser`)의 날짜 해석 함수를 **같은 모듈**로 쓴다(규칙이 두 곳에서 달라지지 않게).

### 5.7 검증 — 시작 > 종료, 빈 칸, 최대 기간

| 경우 | 화면 | 서버 |
|---|---|---|
| 직접 입력으로 시작 > 종료 | 종료 칸에 오류(`aria-invalid`, 오류 문구 "종료일이 시작일보다 빠릅니다"), **조회 버튼 눌러도 요청 안 보냄** + 오류 칸으로 포커스. **조용히 바꾸지 않는다** — 오타(10-15 를 10-05 로)일 가능성이 있어 사용자가 고쳐야 함 [추정, 원칙 5] | 400 `INVALID_RANGE` |
| 달력 클릭으로 역순 | 교환(D5) | — |
| 붙여넣기로 역순 | 정렬 + 안내(5.6) | — |
| 한쪽만 입력 | `required` 면 빈 칸 오류. 아니면 **열린 범위**: 시작만 = "시작일 이후", 종료만 = "종료일까지" — 단 `maxDays` 가 있으면 열린 범위 금지(무한 기간이 되므로) | 같음 |
| 기간 > `maxDays` | 일수 표시 오류색 + "최대 92일까지 조회할 수 있습니다" + 조회 차단 | 400 `RANGE_TOO_LONG` (details: `maxDays`) |
| `min`/`max` 밖 | 칸 오류 "2026-10-15 까지 조회할 수 있습니다" | 400 `RANGE_OUT_OF_BOUNDS` |
| 존재하지 않는 날짜 | 원문 유지 + 오류 | 400(바인딩 실패 → 기존 공통 처리) |

- 서버가 최종 판정한다(화면 검사는 편의). URL·API 를 직접 고친 요청도 같은 400.
- 오류 코드는 `HbErrorCode` 에, 문구는 i18n 키(ko/en) — 8장 I4 에서 새 코드 3개 제안.

### 5.8 최대 기간 정책

- **왜 필요한가**: 원장(재고 트랜잭션·실적·측정값)은 행 수가 크다. 10년 범위 조회 한 번이 DB·화면을 멈출 수 있다(05 가용성). 지금 활용 현황 API 에는 상한이 없다(C3).
- **두 단계**:
  1. **화면 정의값** `maxDays` — 화면을 만드는 사람이 데이터 성격으로 정한다(예: 실적 상세 31일, 일별 집계 366일, 활용 현황 92일) [추정 — 값은 화면별로 검토].
  2. **회사 정책 상한** `SEARCH_MAX_RANGE_DAYS`(제안: INT, 기본 366, 범위 1~3660) — 설치형 고객이 서버 사양에 맞춰 더 줄이거나 늘린다. 실제 상한 = `min(화면 maxDays, 정책)`. 정책 키는 **새 V 파일**로 `sys_policy_def` 에 추가(V006 수정 금지).
- **서버 공통**: `DateRanges.check(from, to, maxDays)` → 위 오류 코드. 컨트롤러마다 직접 비교하지 않는다(가드 테스트 후보: `from`/`to` `LocalDate` 파라미터를 받는 `@ProgAuth` API 는 이 검사를 거쳐야 함 — 구현 방식은 검토 때).
- **화면 전달**: `hb.dateRange` 는 화면 템플릿의 `data-max-days` 를 읽고, 정책 상한은 페이지 로드 시 이미 내려오는 세션 정보(`hb.js` `session`)에 넣는 안 [추정].
- 상한에 걸릴 때의 대안 안내: "기간이 길면 월별 집계 화면을 쓰세요" 같은 화면별 문구(선택).

### 5.9 회사 시간대

- **"오늘"은 회사 시간대**로 계산한다. 화면: `hb.js` 의 `session.timeZone`(이미 있음) + `Intl.DateTimeFormat(…, {timeZone})` 로 `YYYY-MM-DD` 를 만든다. `new Date().toISOString()`·`getDate()`(브라우저 시간대)는 날짜 계산에 쓰지 않는다 — C2 같은 버그 방지. 가드: `hb.dateRange`·`hb.date` 안에서만 오늘을 만들고, 화면 JS 에서 `toISOString().substring(0, 10)` 패턴을 금지하는 정적 검사 후보 [추정].
- **날짜 계산은 문자열 `YYYY-MM-DD` 와 정수 일수로만**(UTC 자정 기준 `Date.UTC` 로 더하기·빼기) — 시간대·서머타임이 끼어들 틈을 없앤다. 라이브러리(dayjs 등) 불필요.
- **서버 전달**: `from`·`to` = `LocalDate`(시간대 없음, 종료일 포함). 서버가 회사 시간대로 **반열린 구간** `[from 00:00, to+1 00:00)` 의 `timestamptz` 로 바꿔 원장의 `occurred_at` 을 조회 — `BETWEEN … 23:59:59` 금지(밀리초 누락). 일 집계 테이블(`usage_date` 같은 date 컬럼)은 그대로 `BETWEEN from AND to`.
- **브라우저 시간대 ≠ 회사 시간대**(해외 출장자, 베트남 공장 관리자가 한국 회사 화면을 봄): 달력의 "오늘"·프리셋은 회사 기준. 머리띠에 회사 시간대가 브라우저와 다를 때만 "기준 시간: Asia/Seoul" 작게 표시 [추정].
- **공장별 시간대**(P2 공장 도입 후, 한 회사에 한국·베트남 공장): 기준이 회사인지 공장인지 화면별로 정해야 한다 → 8장 I9.

### 5.10 POP 변형

- 2개월 달력 대신 **프리셋 큰 버튼 3~4개**(오늘·어제·이번 주·직접)가 주, "직접" 을 누르면 전체 화면 1개월 달력(칸 = POP 터치 토큰 이상, P10~P12).
- 직접 입력은 숫자 키패드(8자리), 구분자 자동.
- 범위 선택 중 상태를 화면 위쪽 큰 글자로("시작 10-01 → 종료를 누르세요"), 확정은 하단 확정 바(12 5.9).
- 장갑: 두 날짜를 연속 탭하는 연속 과제이므로 P10 의 연속 탭 하한(9.6mm)보다 크게, 단말 실측으로 px 정함(12 V10).

### 5.11 접근성 (기간 선택기 전용)

- 두 칸: 보이는 라벨 "기간" + 각 칸 `aria-label`("시작일", "종료일"), 형식 안내는 `aria-describedby`.
- 📅 버튼: `aria-label="기간 달력 열기"`, `aria-haspopup="dialog"`, `aria-expanded`.
- 팝업: `role="dialog"` + `aria-label="기간 선택"`. **비모달**로 두되(사무 화면에서 바깥 클릭으로 닫힘) 포커스는 팝업 안에서 시작, Tab 순서 = 프리셋 목록 → 이전/다음 달 → 달력 그리드 → 취소·적용.
- 프리셋 목록: `role="listbox"` + 옵션 `aria-selected`(현재 프리셋), 각 옵션 접근성 이름에 실제 날짜 포함("지난 7일, 10월 10일부터 10월 16일").
- 달력: `role="grid"`, 날짜 칸 roving tabindex, 칸 접근성 이름 "2026년 10월 1일 목요일", 시작·종료 칸 `aria-selected="true"` + 설명 "시작일"/"종료일", 범위 안 칸은 `aria-describedby` 로 "선택 범위 안" [추정 — APG 에 범위 전용 패턴 없음].
- 상태 안내 live region(polite): "시작일 10월 1일. 종료일을 고르세요", "10월 1일부터 15일까지, 15일 선택".
- 대비: 범위 배경 위 글자 4.5:1, 시작·종료 표시는 비텍스트 3:1(12 9.4).

### 5.12 이상 사용 테스트 (구현 때 TESTING.md 로)

| 분류 | 케이스 | 기대 |
|---|---|---|
| A 값 | `2026-02-30`, `0000-01-01`, `9999-12-31`, `26-10-01`, `46296`, 10만 자, `<img onerror>` | 거부·원문 유지·오류, 실행 0 |
| A 값 | 시작 `2026-10-15` 종료 `2026-10-01` 직접 입력 후 조회 | 요청 0, 종료 칸 오류 |
| B 순서 | 프리셋 연타 10번 / 달력 열고 닫기 반복 / 첫 클릭 후 Esc | 마지막 상태만, 요청 0, Esc 는 원래 범위 |
| B 순서 | 23:59 에 열고 00:01 에 조회(시계 조작) | 프리셋은 새 날짜 기준 |
| C 환경 | 브라우저 시간대 UTC·America/Los_Angeles, 회사 Asia/Seoul / 회사 시간대 Asia/Ho_Chi_Minh | 오늘·프리셋 = 회사 기준 |
| C 환경 | 한글 IME 켠 채 `20261001` + Enter (mac·Win × Chrome·Edge) | 값 정상, 이중 조회 0 |
| D 권한 | URL `?from=2000-01-01&to=2099-12-31` 직접 / API 직접 호출 | 400 `RANGE_TOO_LONG` |
| E 회사 | A사 사용자가 B사 데이터 기간 조회(파라미터만 바꿔도) | RLS 로 0건 — 기간 컴포넌트와 무관함을 확인 |
| 붙여넣기 | 엑셀 두 칸, 거꾸로, 날짜 3개, 1천 자 초과 | 5.6 표대로 |

### 5.13 엔진 선택 — 자체 vs vanilla-calendar-pro vs Kendo UI Core

| 항목 | 자체 제작 | vanilla-calendar-pro 3.4.0 | Kendo UI Core 2026.3.1015 |
|---|---|---|---|
| 라이선스 | — | MIT | Apache-2.0 |
| 범위 선택 | 설계대로 | **있음** — `selectionDatesMode: 'multiple-ranged'`, `enableEdgeDatesOnly`(시작·끝만 반환), `disableDatesGaps` [가이드·코드] | **DateRangePicker 없음**(Professional 전용), **MultiViewCalendar(2개월) 없음** [가이드 https://github.com/telerik/kendo-ui-core]. 단 Core 의 `kendo.calendar.js` ESM 에 `RangeSelectable`·`_rangeSelection` 코드가 있음 [코드] — 1개월 Calendar 에서 범위 선택이 공식 지원되는지는 **미확인**(문서상 범위는 MultiViewCalendar 기능) |
| 2개월 표시 | 설계대로 | `type:'multiple'` + `displayMonthsCount:2` — vendor UMD 파일은 확장(months·time·weeks 등)이 **기본 포함**이라 바로 됨 [코드: 파일 끝 `extensions:sn`] | 없음(상용) |
| 키보드(APG 날짜 그리드) | 전부 | ←→↑↓ 만, **Home/End·PageUp/PageDown·Shift+PageUp 없음** [코드: 해당 키 문자열 0]. 3.3.1 에 접근성 정비(그리드 단일 탭 정지, 화살표 이동, 포커스 관리)[가이드 https://github.com/uvarov-frontend/vanilla-calendar-pro/releases] | Calendar 에 PAGEUP 처리 있음 [코드] |
| ARIA | 설계대로 | `role=grid/row/gridcell/columnheader`, `aria-selected`, `aria-multiselectable`, `aria-live` [코드] | `aria-activedescendant`·`aria-selected`·`aria-multiselectable` [코드] |
| 최대 기간 동적 비활성 | 설계대로 | 전용 옵션 없음 — `onClickDate` 에서 `dateMin/dateMax` 를 바꿔 `set()` 해야 함 [코드·추정] | — |
| 회사 시간대 "오늘" | 설계대로 | `dateToday` 옵션으로 주입 가능 [코드] | `value`/`max` 주입 |
| 프리셋 | 설계대로 | 없음(바깥에서 `set()`) | 없음 |
| CSP | 설계대로(CSSOM 만) | 위반 0 [실측 R1] — `style.top/left/visibility` 등 CSSOM 만, `innerHTML` 은 `sanitizerHTML` 경유 [코드] | 위반 0 — **ESM 로드만**(한 파일 번들·UMD 결함) [실측 R3~R5] |
| 의존성·크기 | 0 / 추정 15~20KB(미압축 ~500줄) [추정] | 0 / 82KB + CSS | jQuery + ESM 30개 ≈1MB [실측 R5] |
| 테마 | 12 토큰 직접 | 자체 CSS 를 토큰으로 덮어써야 함 | Kendo 테마(토큰 연결 별도) |
| IME | 칸은 어차피 hb 소유 | 입력 칸 조합 처리 코드 0 [코드] | 0 [코드] |

**권고(I2)**: 달력 그리드를 **자체 제작**한다.
- 이유 ① 키보드 위주 사무 사용자에게 PageUp/PageDown(월 이동)은 범위 선택의 핵심 키인데 VCP 에 없다. ② 최대 기간 동적 비활성·프리셋 동기화·회사 시간대·근무일 같은 요구가 모두 **바깥에서 라이브러리 상태를 계속 고치는** 구조가 된다. ③ 같은 그리드를 `hb.date`·`hb.month`·POP 큰 달력에 재사용한다. ④ 날짜 계산이 문자열·정수뿐이라 구현 난도가 낮다(달력 그리드는 7×6 표). ⑤ 포트폴리오 관점에서 APG grid 패턴 구현은 설명 가능한 결과물이다(CLAUDE.md 목적).
- **예비안**: 일정이 밀리면 VCP 를 엔진으로 쓰고 `hb.dateRange` 셸(입력 칸·프리셋·검증·시간대)은 그대로 — API 가 엔진과 독립이므로 교체 가능. 이때 PageUp/PageDown 은 VCP 의 `set()` 으로 hb 가 보완.
- Kendo UI Core 는 단일 `DatePicker` 용으로도 쓰지 않는 것을 권고 — 기간을 자체로 만들면 단일 날짜도 같은 그리드가 더 일관되고, jQuery 1MB 를 날짜 하나 때문에 싣지 않는다. 단 H4(위젯 세트) 결정이 "Kendo Core 우선" 이 되면 단일 날짜만 Kendo 를 써도 사용자에게 보이는 모양이 달라지는 것이 비용 [추정].

---

## 6. 나머지 컴포넌트 설계

### 6.1 `hb.select` — 네이티브 select 감싸기

- 원칙: **네이티브 `<select>` 를 그대로** 쓰고 hb 는 데이터 채우기·스타일·필수 표시만. 키보드 타이핑 점프·스크린리더·모바일 피커를 브라우저가 해 준다 — 맞춤 드롭다운의 키보드 결함(G4 31%)을 피한다.
- 데이터: `data-code="UNIT"` → 공통코드(사용 중인 것만, `sort_order` 고정 — P7), "전체"·"선택" 첫 항목은 옵션으로.
- 스타일: 12 토큰(테두리 3:1, 높이 `--hb-ctl-h`). 최신 Chrome 의 맞춤 select(`appearance: base-select`)는 브라우저 지원이 고르지 않아 쓰지 않음 [추정].
- 휠: 닫힌 select 위 휠로 값이 바뀌는 브라우저 동작(P4)은 현대 Chrome·Edge 에서 기본 없음 [추정 — 사람 확인 항목].

### 6.2 `hb.combo` — 콤보박스·자동완성

- 패턴: APG **editable combobox, list autocomplete, 수동 선택**(G11). 입력하면 목록이 좁혀지고, 첫 항목이 자동 선택되지 않는다(엉뚱한 첫 항목 선택 방지 — 10 H7).
- **엄격 모드(strict, 기본)**: 값은 목록에 있는 것만. 목록에서 고르지 않고 벗어나면(blur) — 입력 텍스트가 정확히 한 항목의 코드 또는 이름과 같으면 그 항목, 아니면 **값 비우고 오류**("목록에서 고르세요"). 자유 텍스트가 조용히 남지 않게 [추정, APG 의 기본 예(자유 텍스트 유지)와 다름 — MES 는 코드 무결성 우선].
- 표시: `코드 · 이름` 두 열(기준정보), 검색은 코드 앞부분 + 이름 부분 일치. 한글 **초성 검색**(`ㄱㅅ` → 거래처 "경상…")은 선택 옵션(서버 쿼리 비용 고려) [추정].
- 서버 검색: `minChars`(기본 1, 한글은 조합 끝난 뒤 1자), 디바운스 200ms, 최대 50건 + "더 있음, 검색어를 늘리세요", 요청 순서 꼬임 방지(마지막 요청만 반영).
- **IME 규칙**(가장 중요): 조합 중(`isComposing`·229) 의 Enter·Tab·Esc·↑↓ 는 **캡처 단계에서 위젯 처리 안 함**, 검색은 `compositionend` 와 조합 아닌 `input` 에서만. 한국어는 compositionend 직후 Enter 를 정상 확정으로 처리. Enter 가 바깥 form submit 으로 새지 않게(조회 패널 안에서 Enter = 조회와 충돌 — 목록이 열려 있으면 Enter 는 선택만).
- 최근 사용: 열 때 검색어가 없으면 **"최근 사용" 5개 묶음**을 위에(분할 메뉴, P7), 아래는 원래 순서. 저장은 브라우저 `localStorage`(사용자·회사·화면별 키) — 서버 저장은 하지 않음(11 근로자 감시 우려 회피) [추정].
- 상세 검색: F2 또는 🔍 버튼 → 대화상자(조건 + 그리드)로 고르기. 기존 MES 사용자가 익숙한 "코드 팝업" 습관을 수용 [추정].
- 엔진(I6): **자체**. Tom Select 2.6.2·Kendo ComboBox ESM 둘 다 조합 이벤트(`isComposing`·`compositionend`) 처리 코드가 없다 [코드] — 10 H10 사람 판정에서 문제가 없으면 Tom Select 를 예비 엔진으로 쓸 수 있으나, 그때도 hb 가 캡처 단계 IME 가드를 씌운다. Tom Select 는 Apache-2.0, `aria-activedescendant`·`aria-expanded`·`aria-controls` 지원 [코드], Enter 가 form submit 으로 새는 이슈 이력(#389) [가이드 https://github.com/orchidjs/tom-select/issues/389].

### 6.3 `hb.multiSelect`

- 모드 `check`(체크 목록형): 닫힌 상태 = "창고: 3개 선택"(1개면 이름, 전부면 "전체"), 열면 검색칸 + "전체 선택/해제" + 체크 목록. APG listbox `aria-multiselectable`(G13), Space 토글, Ctrl+A 전체.
- 모드 `tag`(태그형): `hb.combo` 와 같은 검색 엔진 + 선택 항목을 태그로, Backspace 로 마지막 태그 삭제(빈 입력일 때만), 태그 ✕ 버튼 `aria-label="창고 A 제거"`.
- 검색 조건에서는 `check` 기본(높이 고정), 폼에서 30개 넘으면 `tag`.
- 서버 전달: 같은 이름 반복 파라미터 `whCd=A&whCd=B`(Spring `List<String>`), 상한(예: 100개) 서버 검사.

### 6.4 체크박스·라디오·토글·세그먼트

- 모두 **네이티브 input** 기반(`<input type="checkbox|radio">`), 모양만 CSS. 토글 = `<input type="checkbox" role="switch">`, 세그먼트 = 라디오 그룹(`<fieldset>` + `<legend>`)을 버튼 모양으로 — 키보드(화살표로 그룹 안 이동)와 스크린리더를 공짜로 얻는다.
- 클릭 영역: 라벨 전체(`<label>` 감싸기), 사무 24px 이상(WCAG 2.5.8 — 12), POP 은 토큰.
- 상태 표시: 체크·선택은 **아이콘 + 색**(색만 X), 세그먼트 선택 = 배경 + 굵은 글자 + 체크 아이콘(선택).
- 세그먼트 개수 2~5(G10), 6 이상이면 select.
- 토글은 즉시 저장 — 저장 중 비활성 + 실패 시 원래 상태로 되돌리고 메시지.

### 6.5 `hb.number`

- `type="text" inputmode="decimal"`(정수 전용은 `numeric`), 오른쪽 정렬, 고정폭 숫자(`font-variant-numeric: tabular-nums`, 12 1.3).
- 포커스: 원값(`12345.5`), blur: 천 단위·자릿수(`12,345.50`). 자릿수 = 옵션 `scale` 또는 단위 공통코드(UNIT 의 소수 자릿수 — 속성이 없으면 새 V 파일로 제안) [추정].
- 입력 해석은 10 PoC 붙여넣기 파서의 숫자 규칙 재사용(천 단위 쉼표, 전각 숫자, 회계 괄호 `(1,000)` = 음수, 지수 표기 거부) [실측 R6].
- 검증: `min`·`max`·`allowNegative`(기본 false)·자릿수 초과(반올림하지 않고 오류 — 수량을 조용히 바꾸지 않음, 원칙 5).
- 휠·↑↓ 증감 없음. 값은 문자열로 서버에 보내고 서버가 `BigDecimal` 로(부동소수 오차 방지).

### 6.6 검색 조건 패널 `hb.searchPanel`

- 배치: 사무는 **라벨 왼쪽 인라인**, 한 줄에 조건 3~4개, 최대 2줄 + "조건 더 보기"(접기) — 그리드 세로 공간 확보 [추정, 12 5.7 은 폼 라벨 위 — 검색 패널만 예외로 둘지 검토]. 폭별 줄바꿈은 15.
- 조회 버튼: 패널 오른쪽 끝, `primary` 는 아님(화면의 primary 는 저장) — secondary + 돋보기 아이콘. 초기화 버튼은 그 왼쪽 ghost.
- **Enter = 조회**(어느 조건 칸에서든) — 단 IME 조합 중 Enter, 열린 콤보 목록의 Enter 는 제외. 이것이 지금 `<form id="search">` submit 동작과 같은 규칙.
- 첫 조건 칸 자동 포커스: 사무 화면 진입 시 첫 조건에 포커스(키보드 사용자가 바로 입력) [추정 — 스크린리더는 페이지 제목부터 읽는 편이 좋다는 반론이 있어 검토 필요].
- 기본값: 화면 정의(`data-default`) — 기간은 프리셋 키. 마지막 조회 조건 기억은 화면 설정에서 켜기(기본 끔) [추정].
- 필수 조건 미입력 → 조회하지 않고 그 칸 오류 + 포커스.
- 조회 중 조회 버튼 `aria-busy` + 중복 요청 방지(이미 hb.api 관례).
- 직렬화: `hb.searchPanel.values()` → `{custCd, from, to, whCd:[…]}` 하나로 — 화면 JS 가 칸을 하나씩 읽지 않는다.

---

## 7. 공통 컴포넌트 API 초안

> 원칙: ① **HTML 선언 + data 속성**으로 쓰고(인라인 스크립트 금지 CSP 와 맞음), `hb.page` 가 페이지 로드 때 `[data-hb]` 를 찾아 초기화. ② 값은 **폼 이름 그대로 숨은 input 이 아니라 실제 input 의 name** 으로 — 일반 `FormData` 로도 읽힌다. ③ 모든 컴포넌트가 같은 메서드 이름(`value()`·`setValue()`·`validate()`·`setDisabled()`·`destroy()`)과 같은 이벤트(`hb:change`). ④ 엔진은 내부 — 바깥에서 엔진 객체를 만지지 않는다.

### 7.1 `hb.dateRange`

```html
<!-- 조회 조건: 기간. data-max = 'today' | 'yesterday' | 'YYYY-MM-DD' -->
<div class="hb-field" data-hb="dateRange"
     data-label-key="usage.period"
     data-from-name="from" data-to-name="to"
     data-presets="today,yesterday,thisWeek,last7,thisMonth,lastMonth"
     data-default="last7"
     data-max="yesterday"
     data-max-days="92"
     data-required="true"></div>
```

```js
const r = hb.dateRange(el, {          // 또는 data 속성만으로 hb.page 가 생성
  fromName: 'from', toName: 'to',
  presets: ['today', 'yesterday', 'thisWeek', 'last7', 'thisMonth', 'lastMonth'],
  defaultValue: 'last7',              // 프리셋 키 또는 {from:'2026-10-01', to:'2026-10-15'}
  min: null, max: 'yesterday',
  maxDays: 92,                        // 실제 상한 = min(maxDays, 회사 정책 SEARCH_MAX_RANGE_DAYS)
  required: true,
  months: 2,                          // 사무 2, 좁은 폭·POP 1 (15 기준으로 자동)
  surface: 'office',                  // 'office' | 'pop'  (기본: body[data-surface])
  weekStart: null                     // null = 회사 정책(기본 월요일)
});
r.value();        // {from:'2026-10-10', to:'2026-10-16', preset:'last7', days:7}
r.setValue('lastMonth');              // 또는 {from, to}
r.validate();     // {ok:false, code:'RANGE_TOO_LONG', field:'to'} — 화면에 오류 표시까지
r.toQuery();      // {from:'2026-10-10', to:'2026-10-16'} — 프리셋이면 이 순간 다시 계산
r.on('hb:change', e => {/* e.detail = value() */});
r.setDisabled(true); r.destroy();
```

공통 날짜 유틸(같은 파일, 다른 화면도 씀):

```js
hb.dates.today();                 // 회사 시간대 'YYYY-MM-DD'
hb.dates.add('2026-10-31', 1);    // '2026-11-01'  (Date.UTC 정수 연산)
hb.dates.parse('20261001');       // {ok:true, value:'2026-10-01'} | {ok:false, code:'INVALID_DATE'}
hb.dates.parseRange(text);        // 붙여넣기용: [from, to] 정렬 여부 포함
hb.dates.preset('last7', {anchor}); // {from, to}
hb.dates.diffDays(from, to);      // 종료일 포함 일수
```

서버(제안):

```java
public record DateRange(LocalDate from, LocalDate to) { long days() {…} }
DateRanges.check(from, to, maxDaysForScreen);   // 정책 상한과 min 적용, 위반 시 HbException
// HbErrorCode: INVALID_RANGE(400), RANGE_TOO_LONG(400, details.maxDays), RANGE_OUT_OF_BOUNDS(400)
DateRanges.toInstantRange(range, zone);          // [from 00:00, to+1 00:00) — 원장 조회용
```

### 7.2 `hb.date`

```html
<div data-hb="date" data-name="dueDt" data-required="true" data-calendar="true"></div>
```
`value()` → `'2026-10-16'` | `null`. 옵션 `min`·`max`·`calendar`(false = 입력만)·`withTime`(시각 칸). 키·해석 규칙은 5.5 와 동일.

### 7.3 `hb.select`

```html
<select name="unitCd" data-hb="select" data-code="UNIT" data-blank="select"></select>
<!-- data-blank: 'all'(전체) | 'select'(선택하세요) | 없음 -->
```
`hb.select(el).reload({code:'UNIT'})`, `value()`/`setValue()`. 공통코드 캐시는 페이지 단위.

### 7.4 `hb.combo`

```html
<!-- data-source 대신 data-code="WH" 면 공통코드 클라이언트 필터 -->
<div data-hb="combo" data-name="custCd"
     data-source="/api/master/cust/lookup"
     data-display="codeName" data-strict="true"
     data-min-chars="1" data-recent="5" data-detail-search="cust"></div>
```
```js
hb.combo(el, {
  source: (q, signal) => hb.api.get('/api/master/cust/lookup', {q, limit: 50}, {signal}),
  valueField: 'custCd', textField: 'custNm',
  strict: true, minChars: 1, debounce: 200, recent: 5,
  detailSearch: 'cust'        // F2 → 상세 검색 대화상자 id
});
// value() → 'C0001' | null ;  item() → {custCd, custNm, ...}
```
조회 API 규약(제안): `GET …/lookup?q=&limit=50` → `{items:[{value,text,…}], more:true}`. `@ProgAuth` 는 **그 기준정보를 쓰는 화면들의 권한**으로 허용해야 하므로 lookup 전용 권한 규칙을 별도로 정해야 한다 → 8장 I10.

### 7.5 `hb.multiSelect`

```html
<div data-hb="multiSelect" data-name="whCd" data-code="WH" data-mode="check" data-max="100"></div>
```
`value()` → `['A','B']`. `mode: 'check'|'tag'`, `source`·`strict` 는 combo 와 같음.

### 7.6 `hb.number`

```html
<input name="qty" data-hb="number" data-scale="3" data-min="0" data-max="9999999" data-unit-from="unitCd">
```
`value()` → `'1234.500'`(문자열), `numberValue()` → `1234.5`(표시·계산용만). `data-unit-from` = 같은 폼의 단위 칸을 보고 자릿수 변경.

### 7.7 `hb.segmented` · 토글

```html
<fieldset data-hb="segmented" class="hb-seg">
  <legend>사용 여부</legend>
  <label><input type="radio" name="useYn" value="" checked>전체</label>
  <label><input type="radio" name="useYn" value="Y">사용</label>
  <label><input type="radio" name="useYn" value="N">미사용</label>
</fieldset>
<label class="hb-switch"><input type="checkbox" role="switch" data-hb="toggle" data-save="/api/me/setting/stripe">줄무늬</label>
```
JS 가 거의 없다(CSS + 이벤트 위임). 토글의 `data-save` 는 즉시 저장 + 실패 시 되돌림.

### 7.8 `hb.searchPanel`

```html
<form data-hb="searchPanel" data-rows="2" data-autofocus="true" data-remember="false">
  … 조건들 …
  <button type="reset">초기화</button><button type="submit">조회</button>
</form>
```
```js
const sp = hb.searchPanel(form, { onSearch: values => grid.load(values) });
sp.values();     // 모든 hb 컴포넌트 + 일반 input 값 → 객체 (프리셋은 이 순간 해석)
sp.validate();   // 첫 오류 칸 포커스
sp.reset();      // data-default 로
```

### 7.9 파일 배치(제안)

```
static/js/common/hb.dates.js       날짜 유틸(회사 시간대·해석·프리셋) — 그리드 붙여넣기 파서와 공유
static/js/common/hb.calendar.js    달력 그리드(APG) — dateRange·date·month·POP 공용
static/js/common/hb.input.js       select·combo·multiSelect·number·segmented·toggle·searchPanel
static/css/hb.input.css            12 토큰만 사용 (원시 색 금지 — 12 V1 가드)
src/test/js/hb.dates.test.js       node --test (10 PoC 와 같은 방식)
```

### 7.10 접근성 체크리스트 (모든 입력 컴포넌트)

- [ ] 보이는 라벨이 있고 `<label for>` 또는 `aria-labelledby` 로 연결(플레이스홀더를 라벨로 쓰지 않음 — G7)
- [ ] 형식·제약 안내(형식, 최대 기간, 자릿수)가 `aria-describedby` 로 연결, 화면에 항상 보임
- [ ] 필수 = 라벨 `*` + `aria-required`(12 5.7), 오류 = `aria-invalid` + `aria-describedby` 오류 문구 + 아이콘(색만 X)
- [ ] 키보드만으로 모든 조작: Tab 순서 = 보이는 순서, 팝업 위젯은 APG 키(combobox: ↓·Alt+↓ 열기, Esc 닫기 / grid: 화살표·Home/End·PageUp/Down / listbox: Space·Ctrl+A)
- [ ] 팝업을 닫으면 포커스가 연 요소로 복귀, 포커스 링 `:focus-visible` 제거 금지
- [ ] `aria-expanded`·`aria-controls`·`aria-activedescendant` 가 실제 상태와 일치, activedescendant 대상은 scrollIntoView
- [ ] 상태 변화(범위 선택 단계, 검색 결과 n건, 선택 n개)는 `aria-live="polite"` 로 한 번만
- [ ] 대비: 글자 4.5:1, 테두리·포커스·선택 표시 3:1(12 4.2·9.4)
- [ ] 타깃: 사무 24×24px 이상(WCAG 2.5.8), POP 은 토큰(56px 이상, 단말 실측)
- [ ] IME: 조합 중 Enter·Tab·Esc·화살표를 위젯이 처리하지 않음, 조합 끝난 뒤 한국어 Enter 는 정상 처리(mac·Win × Chrome·Edge 사람 확인)
- [ ] 값이 사용자 모르게 바뀌지 않음: 휠·화살표 증감 없음, 직접 입력 값 자동 교정 없음(오류로 알림), 자동 포커스 이동 없음
- [ ] 200% 확대·좁은 폭에서 팝업이 화면 밖으로 나가지 않음(15 참조)
- [ ] `prefers-reduced-motion` 이면 팝업 애니메이션 없음
- [ ] CSP: 인라인 style·script 없음, 스타일은 클래스·CSSOM 만(가드 테스트 통과)

---

## 8. 원래 세션에 넘길 결정

| # | 결정 | 권고 | 대안 | 시점 |
|---|---|---|---|---|
| **I1** | 기간 입력 구조 | 입력 2칸 + 팝업 1개(프리셋 + 2개월 달력) `hb.dateRange` | 읽기 전용 칸 1개 + 팝업(키보드 직접 입력 포기) | P1 전 |
| **I2** | 달력 엔진 | 자체 제작(APG grid) | VCP 3.4.0 + hb 셸 / Kendo Core(단일 날짜만, 범위 없음) | P1 전 |
| **I3** | 시작 > 종료 | 달력 = 교환, 직접 입력 = 오류·조회 차단, 붙여넣기 = 정렬 + 안내, 서버 항상 400 | 모든 경로 자동 교환(편하지만 오타를 숨김) | I1 과 함께 |
| **I4** | 최대 기간 | 화면 `maxDays` + 회사 정책 `SEARCH_MAX_RANGE_DAYS`(새 V 파일) + `DateRanges.check` + 오류 코드 3개 | 화면값만(설치형 고객 조정 불가) | P1 전 |
| **I5** | 단일 선택 기준 | 4.1 표(2~5 라디오/세그먼트, 6~15 select, 16+ combo) | Baymard 그대로(5·10 경계) | P1 전 |
| I6 | 콤보 엔진 | 자체 `hb.combo` + IME 가드 | Tom Select + hb IME 가드(10 H10 통과 시) | 10 H10 판정 후 |
| I7 | 숫자 | `type=text inputmode=decimal` + `hb.number`, `type=number` 금지(정적 검사 후보) | — | P1 전 |
| I8 | 프리셋 "지난 7일" 정의 | 오늘 포함 7일(T−6~T), 화면 `max` 가 있으면 기준일 보정 | 어제까지 7일 | I1 과 함께 |
| I9 | 근무일·공장 시간대 | 이 문서는 달력 날짜·회사 시간대만. 근무일 프리셋·공장 시간대는 P2 생산실적 설계에서 | — | P2 |
| I10 | lookup API 권한 | 기준정보 lookup 은 "그 기준정보를 쓰는 화면 중 하나라도 SEARCH 권한" 규칙 — `@ProgAuth` 확장 필요 | 로그인만 되면 허용(`@ProgAuthExempt` — 회사 범위 RLS 는 유지) | `hb.combo` 서버 검색 구현 전 |
| I11 | 검색 패널 라벨 위치 | 라벨 왼쪽 인라인(12 5.7 폼 규칙의 예외) | 라벨 위(12 와 통일, 높이 증가) | 12 V 결정과 함께 |
| I12 | 첫 조건 자동 포커스 | 사무 화면 켬 | 끔(스크린리더 우선) | P1 전 |
| I13 | vendor 정리 | I2·I6 이 자체로 결정되면 VCP·Tom Select 는 PoC 종료 후 vendor 에서 제거(vendor README 규칙) | 예비로 유지 | H3·H4 결정 시 |

**곧바로 고칠 수 있는 결함**(결정과 무관): C2 `usage.js` UTC 날짜 → 회사 시간대, C3 활용 현황 API 기간 검사(시작≤종료, 상한).

---

## 9. 도입 순서

| 단계 | 할 일 | 첫 적용 화면 | 크기 [추정] | 완료 기준 |
|---|---|---|---|---|
| 0 | `hb.dates`(회사 시간대 오늘·해석·프리셋·일수) + node 단위 테스트 / 서버 `DateRanges.check` + 오류 코드 + i18n / C2·C3 수정 | 활용 현황 | 1일 | 단위 테스트(5.5·5.6 표 전부), API 400 3종, `toISOString` 날짜 계산 0 |
| 1 | `hb.calendar`(APG grid) + `hb.dateRange`(사무) + CSS(토큰) | **활용 현황** — 사용자 요청 | 3~4일 | 5.12 이상 사용 표, APG 키 표, CSP 0, 사람 IME 확인 |
| 2 | `hb.searchPanel` + `hb.select` + 세그먼트·체크박스·토글 CSS | 거래처관리 검색 패널 | 1~2일 | Enter 조회·IME, 값 직렬화, 상태 복원 |
| 3 | `hb.number` | 거래처관리(또는 P1 품목 단위·중량) | 1일 | 10 PoC 숫자 파서 공유, 자릿수 오류 |
| 4 | `hb.combo`(클라이언트 → 서버 검색, 최근 사용, F2 상세) + lookup API 권한(I10) | P1 품목·BOM 의 거래처·단위 | 3~4일 | APG combobox, IME 시나리오(10 H7·H10 재사용), 요청 경쟁 |
| 5 | `hb.multiSelect`(check → tag) | P1 조회 화면의 창고·공정 다중 조건 | 2일 | listbox 키, 상한 |
| 6 | `hb.date`·`hb.month`(같은 그리드 재사용) | P1 기준정보 유효기간 | 1일 | 1단계 규칙 공유 |
| 7 | POP 변형(큰 달력·프리셋 버튼·키패드) | P2 POP | 2일 | 단말 실측(12 V10), 장갑 시나리오 |

- 각 단계는 skill `references/checklists.md` §2(새 화면 체크리스트)에 "입력 컴포넌트" 항목을 추가하는 것으로 마무리(이 문서 7.10) — 반영은 원래 세션이 결정.
- 편집 그리드(hb.grid `mode:'edit'`) 셀 editor 는 이 컴포넌트들의 **해석 함수만** 공유하고 팝업 UI 는 그리드 쪽 결정(10 H3)을 따른다.

---

## 10. 참고문헌

### 논문·보고서
- P1 Bargas-Avila, J. A., Brenzikofer, O., Tuch, A. N., Roth, S. P., Opwis, K. (2011). Working towards Usable Forms on the World Wide Web: Optimizing Date Entry Input Fields. *Advances in Human-Computer Interaction*, 202701. https://doi.org/10.1155/2011/202701
- P2 Rivas, A. G., Nichols, E. M., Horwitz, R. T. (2026). Differences in Usability Between Three Date-entry Designs. U.S. Census Bureau, RSM2026-08. https://www.census.gov/library/working-papers/2026/adrm/rsm2026-08.html (관련 포스터 https://census.gov/content/dam/Census/newsroom/press-kits/2023/aapor/aapor-poster-data-entry-design-nichols.pdf)
- P3 Romikaitytė, A. et al. (2022). A Comparison of Date Selection Elements on Mobile Touch Devices in eCommerce Sites. *CHIRA 2022*, 217–224. https://www.scitepress.org/Papers/2022/115778/115778.pdf (DOI 표기가 출처마다 달라 PDF 링크로)
- P4 Healey, B. (2007). Drop Downs and Scroll Mice. *Social Science Computer Review*, 25(1), 111–128. https://doi.org/10.1177/0894439306293888
- P5 Heerwegh, D., Loosveldt, G. (2002). An Evaluation of the Effect of Response Formats on Data Quality in Web Surveys. *SSCR*, 20(4), 471–484. https://doi.org/10.1177/089443902237323
- P6 Jensen, E. T., Hansen, M., Eika, E., Sandnes, F. E. (2020). Country Selection on Web Forms: A Comparison of Dropdown Menus, Radio Buttons and Text Field with Autocomplete. *IMCOM 2020*. https://doi.org/10.1109/IMCOM48794.2020.9001795
- P7 Cockburn, A., Gutwin, C., Greenberg, S. (2007). A Predictive Model of Menu Performance. *CHI '07*, 627–636. https://doi.org/10.1145/1240624.1240723 (PDF https://www.csse.canterbury.ac.nz/andrew.cockburn/papers/paper191-cockburn.pdf)
- P8 Cockburn, A., Gutwin, C. (2009). A Predictive Model of Human Performance With Scrolling and Hierarchical Lists. *Human–Computer Interaction*, 24(3), 273–314. (DOI 미확인 — 학술지 권호로 인용)
- P9 Liu, W., Gori, J., Rioul, O., Beaudouin-Lafon, M., Guiard, Y. (2020). How Relevant is Hick's Law for HCI? *CHI '20*. https://doi.org/10.1145/3313831.3376878 (HAL https://universite-paris-saclay.hal.science/DIVA/hal-02867301v1)
- P10 Parhi, P., Karlson, A. K., Bederson, B. B. (2006). Target Size Study for One-Handed Thumb Use on Small Touchscreen Devices. *MobileHCI '06*, 203–210. https://doi.org/10.1145/1152215.1152260
- P11 Sun, X., Plocher, T., Qu, W. An Empirical Study on the Smallest Comfortable Button/Icon Size on Touch Screen. http://enginpsych.psych.ac.cn/upload/201304100616401487561624.PDF
- P12 Tao, D. et al. (2018). Effects of button design characteristics on performance and perceptions of touchscreen use. *International Journal of Industrial Ergonomics*, 64, 59–68. https://www.sciencedirect.com/science/article/abs/pii/S0169814117302366
- P13 A Comparison of Multiple Selections Using Multiple Checkbox Selections and List Boxes (2021). *HCI International 2021 – Late Breaking Posters*. https://www.researchgate.net/publication/355948257

### 가이드
- G1 NN/g Date-Input Form Fields https://www.nngroup.com/articles/date-input/
- G2 NN/g Toggle-Switch Guidelines https://www.nngroup.com/articles/toggle-switch-guidelines/
- G3 NN/g Listboxes vs. Dropdown Lists https://www.nngroup.com/articles/listbox-dropdown/
- G4 Baymard Drop-Down Usability https://baymard.com/research-articles/drop-down-usability , Custom drop-downs https://baymard.com/blog/custom-dropdowns-cause-issues
- G5 GOV.UK Date input https://design-system.service.gov.uk/components/date-input/ , 날짜 선택기 논의 https://github.com/alphagov/govuk-design-system/discussions/2375
- G6 GOV.UK number input 변경 https://technology.blog.gov.uk/2020/02/24/why-the-gov-uk-design-system-team-changed-the-input-type-for-numbers/ , PR https://github.com/alphagov/govuk-frontend/pull/1704
- G7 KRDS 날짜 입력 필드 https://www.krds.go.kr/html/site/component/component_09_01.html , 달력 https://www.krds.go.kr/html/site/component/component_04_03.html
- G8 Carbon Date picker https://carbondesignsystem.com/components/date-picker/usage/
- G9 SAP Fiori Dynamic Date Range https://www.sap.com/design-system/fiori-design-web/v1-96/ui-elements/dynamic-date-range-2/usage (403 으로 본문 직접 열람 실패 — 검색 요약 기준)
- G10 Material 3 Segmented buttons https://m3.material.io/components/segmented-buttons/guidelines , Date pickers https://m3.material.io/components/date-pickers/guidelines
- G11 WAI-ARIA APG Combobox https://www.w3.org/WAI/ARIA/apg/patterns/combobox/
- G12 WAI-ARIA APG Date Picker Dialog https://www.w3.org/WAI/ARIA/apg/patterns/dialog-modal/examples/datepicker-dialog/ , Grid https://www.w3.org/WAI/ARIA/apg/patterns/grid/
- G13 WAI-ARIA APG Listbox https://www.w3.org/WAI/ARIA/apg/patterns/listbox/
- G14 Fluent UI Blazor Date Range 논의 https://github.com/microsoft/fluentui-blazor/discussions/1980
- G15 Nord Date Range Picker https://nordhealth.design/components/date-range-picker/ , GitLab 기간 프리셋 이슈 https://gitlab.com/gitlab-org/gitlab/-/issues/408656
- IME Enter 처리(isComposing·229·한국어 예외) https://azukiazusa.dev/en/blog/ime-enter-submit/ , 같은 결함 사례 https://github.com/opensearch-project/oui/issues/1835

### 라이브러리
- Kendo UI Core 구성표 https://github.com/telerik/kendo-ui-core , 소개 https://www.telerik.com/kendo-jquery-ui/documentation/introduction
- vanilla-calendar-pro 설정 https://vanilla-calendar.pro/docs/reference/settings , 릴리스(3.3.1 접근성) https://github.com/uvarov-frontend/vanilla-calendar-pro/releases
- Tom Select https://github.com/orchidjs/tom-select , Enter form submit 이슈 https://github.com/orchidjs/tom-select/issues/389

### 내부
- 10 11.2·11.5(위젯 PoC·IME·Kendo ESM), 11(현장), 12 5.3·5.7·5.9·9.4(토큰·폼·POP·체크리스트), 15(반응형), vendor/README.md, `hb.js`(`session.timeZone`), `usage.html`·`usage.js`·`UsageApiController`

### 확인하지 못한 것
- Baymard 날짜 선택기·드롭다운 상세 지침(Premium), SAP Fiori 본문(403), Kendo Core `Calendar` 의 1개월 범위 선택 공식 지원 여부, 실제 스크린리더(NVDA·VoiceOver) 동작, MES 숙련 사용자 대상 논문.

---

## 11. 검토 결과 (원래 세션이 작성)


검토일 2026-10-16. 사용자 요청 범위: 검토 → 디자인 토큰과 함께 공통 컴포넌트 구현(첫 대상 `hb.dateRange`, 활용 현황에 먼저 적용) → 반응형 적용. 결론("API 는 hb 가 소유, 엔진은 교체 가능")과 기간 선택기 구조에 동의한다.

| # | 판정 | 반영 |
|---|---|---|
| I1 기간 입력 구조 | **채택 — 이번 단계 구현** | `hb.dateRange` = 입력 2칸(직접 입력) + 팝업 1개(프리셋 + 2개월 달력). 활용 현황의 `<input type="date">` 2개 대체 |
| I2 달력 엔진 | **채택(자체 제작)** | `hb.calendar` APG grid(←→↑↓·Home/End·PageUp/PageDown·Shift+PageUp/Down). VCP 는 예비안으로 vendor 에 남겨 두고 H3·H4 결정 때 정리(I13) |
| I3 시작 > 종료 | **채택** | 달력 = 교환, 직접 입력 = 오류·조회 차단, 붙여넣기 = 정렬 + 안내, 서버는 항상 400 |
| I4 최대 기간 | **채택** | 화면 `maxDays` + 회사 정책 `SEARCH_MAX_RANGE_DAYS`(새 V012), 서버 `DateRanges.check`, 오류 코드 `INVALID_RANGE`·`RANGE_TOO_LONG` (`RANGE_OUT_OF_BOUNDS` 는 min/max 가 필요한 화면이 생길 때) |
| I5 단일 선택 기준 | 채택(규칙) | skill checklists §2 에 결정표 요약. 구현은 2단계(`hb.select`·세그먼트)부터 |
| I6 콤보 엔진 | 보류 | 10 H10(IME 사람 판정) 후 |
| I7 숫자 | 채택(규칙) | `type=number` 금지는 지금 규칙으로, `hb.number` 구현은 3단계 |
| I8 "지난 7일" | **채택** | 오늘 포함 7일(T−6~T), 화면 `max` 가 있으면 그 날 기준(활용 현황은 max=어제 → 어제 포함 7일) |
| I9 근무일·공장 시간대 | 보류(P2) | — |
| I10 lookup 권한 | 보류 | `hb.combo` 서버 검색 구현 전 |
| I11 검색 패널 라벨 | **채택(왼쪽 인라인)** | 조회 조건 줄만 예외, 상세 폼은 12 규칙(라벨 위) |
| I12 첫 조건 자동 포커스 | 보류 | 화면이 iframe 탭이라 탭 전환 때 포커스를 뺏을 수 있음 — 2단계 `hb.searchPanel` 때 결정 |
| I13 vendor 정리 | 보류 | H3·H4 결정 때 |

**곧바로 고칠 결함**: C2(활용 현황 기본값이 UTC 날짜) · C3(활용 현황 API 기간 검사 없음) — 이번 단계에서 함께 고친다.
**이번에 하지 않는 것**: 도입 순서 2~7단계(`hb.select`·`hb.combo`·`hb.multiSelect`·`hb.number`·POP 변형). 조회 조건 저장(프리셋 키)은 `sys_grid_state` 와 같은 "화면 설정 저장"이 생길 때.

**구현 결과 (2026-10-16)**: 0·1단계 완료 — `hb.dates`(node 테스트 7건) · `hb.calendar`(APG 키 전부) · `hb.dateRange` · 서버 `DateRanges` + 정책 `SEARCH_MAX_RANGE_DAYS`(V012, 기본 366) + 오류 코드 `INVALID_RANGE`·`RANGE_TOO_LONG`, 활용 현황 적용. 설계와 다른 점: ① 라벨은 `data-label-key` 대신 템플릿이 푼 `th:data-label`(화면 JS 는 `js.*` 문구만 받음) ② 프리셋·달력 두 번째 클릭은 **바로 적용하고 닫는다**(적용 버튼 없음 — 한 번 덜 누름, 취소는 Esc·닫기) ③ C3 정정: 활용 현황 서비스에는 이미 기간 검사(`INVALID_VALUE`, 366일)가 있었다 — 서비스 검사는 방어선으로 두고 컨트롤러가 구체 코드를 먼저 돌려준다(상한은 같은 상수).

**2·3단계 구현 결과 (2026-10-16)**: `hb.searchPanel` · `hb.select` · 세그먼트·체크박스·토글 CSS · `hb.number`(+ 순수 함수 `hb.numbers`, node 테스트 7건). 첫 적용 = 거래처관리 조회 조건(구분 select·사용 여부 세그먼트) + 상세 구분 select, 활용 현황 조회 조건(기간 + 조회 패널).
- **I12 결정: 채택(조건부)** — `data-autofocus="true"` 인 화면을 **처음 열 때, 그 탭이 보일 때만** 첫 조건에 포커스. 탭을 오갈 때·숨은 iframe·이미 다른 요소에 포커스가 있을 때는 건드리지 않는다(브라우저 확인). 스크린리더 사용자는 첫 칸 라벨이 "거래처코드"로 읽혀 위치를 알 수 있다 — 제목부터 읽어야 한다는 반론은 실제 사용자 확인 때 다시 본다.
- **I5 반영**: 사용 여부(3개) = 세그먼트, 구분(공통코드) = select. 결정표는 skill checklists §2.
- 설계와 다른 점: ① 파일은 7.9 제안대로 `hb.input.js` 하나(select·number·searchPanel) + `hb.numbers.js`(순수, 붙여넣기 파서와 공유 — 10 PoC 숫자 규칙을 옮기고 파서는 이것을 부른다). CSS 는 `hb.input.css` 를 따로 두지 않고 page.css 의 입력 컴포넌트 절(dateRange 와 같은 곳) ② 숫자는 끝의 0 을 자릿수로 세지 않는다(`1.230` 은 scale 2 에서 통과 — 값이 바뀌지 않으므로) ③ 공통코드 select 는 서버 순서를 지키려고 `hb.codeList`(목록) 를 새로 두고 `hb.codes`(맵)는 그 위에서 만든다(숫자 같은 코드는 객체 키 순서가 바뀜) ④ "조건 더 보기"(2줄 넘으면 접기)와 조회 조건 기억(`data-remember`)은 **쓰는 화면이 생길 때**(지금 화면은 1~2줄) ⑤ 토글 즉시 저장(`data-save`)은 쓰는 화면이 없어 CSS 만 ⑥ `data-unit-from`(단위별 자릿수)은 UNIT 공통코드에 소수 자릿수 속성이 생길 때(P1 품목, 새 V 파일) ⑦ `hb.number` 의 첫 실제 화면도 P1 품목 — 거래처에는 숫자 칸이 없어 스키마를 늘리지 않았다(브라우저 확인은 화면에 칸을 붙여서).
- 남은 사람 확인: 한글 IME 조합 중 Enter(합성 이벤트로는 규칙만 확인, docs/10 11.5.2 와 함께), 스크린리더로 세그먼트·오류 읽기.
