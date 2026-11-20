# 12 UI/UX 시각 디자인 방향 — 색·타이포·밀도·상태 표시·디자인 토큰

> 작성: 2026-10-15 (조사 세션). 원래 세션이 10장 "검토 결과"를 붙여 00/01·skill 에 반영한다. 이 문서는 코드·다른 문서를 고치지 않았다.
> 표시: **[원문]** 출처 페이지를 직접 열어 읽음 · **[검색]** 검색 결과 요약·2차 인용으로만 확인(원문 재확인 권장) · **[계산]** WCAG 2.x 상대 휘도 공식으로 이 문서가 직접 계산한 대비값 · **[추정]** 근거를 바탕으로 한 이 문서의 판단.
> 연결: 현장 채택·입력 최소화·POP 컴포넌트(`hb.pop`)·터치 mm 관행은 [11 2.3·4장](11_사용성_현장채택_트렌드.md), 그리드 기능은 [09](09_그리드_분석.md)·[10](10_그리드_자체제작_비교.md), AI 보조 원칙은 [03](03_AI_활용_조사.md) — **여기서는 시각 디자인(보이는 방식)만** 다루고 기능·흐름은 연결만 한다.
> 범위: 사무 화면(WEB) + 현장 화면(POP·안돈·대시보드). 고객사 화면·코드는 보지 않았고 공개 디자인 시스템·표준·연구의 **패턴만** 가져왔다.

---

## 0. 요약

### 0.1 한 줄 결론

엔터프라이즈·산업용 디자인 시스템(Siemens iX, SAP Fiori, IBM Carbon, Fluent 2, SLDS 2, Primer, KRDS)과 ISA-101 고성능 HMI 는 같은 방향을 가리킨다: **① 중립 회색이 화면의 대부분, 브랜드색은 "누를 것·선택된 것"에만, 상태색은 "의미가 있을 때만" ② 상태는 색 + 아이콘 + 글자로(색만 금지) ③ 값은 하드코딩하지 않고 3단 토큰(원시 → 의미 → 컴포넌트)으로 ④ 밀도는 사용자·단말이 고르는 설정(compact/normal/spacious) ⑤ 숫자는 고정폭(tabular)·오른쪽 정렬.** 현장 화면은 여기에 **"정상은 회색, 이상만 색"**(ISA-101)과 **거리·장갑에 맞춘 크기**가 더해진다.

SmartHappy 는 업무 화면이 거래처관리 1개뿐인 지금이 토큰을 넣을 **가장 싼 시점**이다. 현재 CSS 3개(173줄)에 색 값이 직접 박혀 있고(서로 다른 hex 25종), 입력 테두리 대비가 WCAG 기준에 못 미치며(1.47:1, 기준 3:1), 글꼴은 Windows 전용 시스템 글꼴 이름에만 기대고 있다. **P0 안에 `tokens.css` + Pretendard 자체 서빙 + 상태 배지 규칙 + "원시 색 값은 tokens.css 밖 금지" 가드 테스트**를 넣으면 이후 모든 모듈 화면이 같은 미감을 공짜로 얻는다 [추정].

### 0.2 꼭 할 것 Top 12 (시점·근거)

| # | 할 것 | 시점 | 근거 |
|---|---|---|---|
| 1 | **`static/css/tokens.css` 도입 — 3단 토큰**(원시 `--hb-p-*` → 의미 `--hb-*` → 컴포넌트 `--hbg-*` 등). page/shell/login/hb.grid CSS 의 직접 색·크기 값을 모두 의미 토큰으로 교체 | **P0 9단계 전**(화면 2번째가 생기기 전) | Fluent 2·Primer·KRDS 모두 3단 [검색], 지금 CSS 173줄·hex 25종(4.1) — 나중엔 화면 수만큼 비싸짐 |
| 2 | **가드 테스트: `tokens.css` 밖 CSS 에 `#hex`·`rgb(`·`hsl(` 금지**(예외: `transparent`·`currentColor`·`inherit`). 기존 `TemplateSecurityGuardTest` 와 같은 방식 | 1과 같이 | 토큰 규칙은 강제하지 않으면 1인 개발에서도 무너진다 [추정]. CSP 가드와 같은 "검사로 지키는 규칙" 방식 |
| 3 | **입력·컨트롤 테두리 대비 3:1 이상**(현행 `#d1d5db` 1.47:1 → `--hb-border-control` `#8a929c` 3.15:1), 포커스 링 2px `--hb-focus` 를 모든 컨트롤에 | 1과 같이 | WCAG 2.2 SC 1.4.11 비텍스트 대비 3:1(입력 경계·상태·포커스 포함) [검색], [계산] 4.2 |
| 4 | **상태 표시 = `hb-status` 배지(아이콘 + 글자 + 색) 하나로 통일**. 색만으로 상태를 말하는 셀·점·행 배경 금지. 흑백 인쇄해도 구분돼야 함 | P1 첫 상태 컬럼 전(결재 상태는 7단계에 이미 있음) | SAP Fiori "색에는 반드시 텍스트를"·Fiori elements 기본 아이콘 [검색], ISA-101 실무 "흑백 인쇄 검사" [검색], 한국 성인 남성 색각이상 6.5% [검색 A11] |
| 5 | **색의 역할 고정**: 파랑(브랜드) = 동작·선택·링크만 / 빨강 = 오류·위험·알람만 / 노랑·주황 = 경고 / 초록 = 성공 피드백(일시적)만, **"정상·가동 중"을 초록으로 칠하지 않음** | 1과 같이(규칙은 skill 체크리스트) | ISA-101·Rockwell "알람 색은 다른 용도로 쓰지 않는다·정상은 회색" [검색 H1·H3], Fiori "장식용 의미색 금지" [검색] |
| 6 | **Pretendard 자체 서빙**(`static/vendor/pretendard/1.3.9/` 공식 dynamic-subset woff2 + OFL 전문 + SHA-256). 폰트 스택 `Pretendard → Malgun Gothic → Apple SD Gothic Neo → Noto Sans KR → sans-serif` | P0 9단계 | OFL 1.1(상업·임베딩·재배포 허용, 단품 판매·예약명 변경만 제약) [검색 F1], CSP `font-src 'self'` 라 CDN 불가, 맑은 고딕은 웹폰트 배포 불가·Linux 설치형·태블릿에 없음 [검색 F3] |
| 7 | **숫자는 `font-variant-numeric: tabular-nums` + 오른쪽 정렬 + 천 단위 구분 + 단위는 헤더로** — 그리드 `hbg-int`·`hbg-dec`, KPI 타일, POP 수량 전부 | 6과 같이 | Pretendard 는 tnum 지원 [검색 F2], Carbon·Primer 데이터 표 관행 [검색] |
| 8 | **밀도 3단 + 현장 1단**: `data-density="compact|normal|spacious"`(사무, 사용자 설정) / `pop`(현장, 단말 설정). 토큰만 바뀌고 컴포넌트 CSS 는 그대로 | 토큰 P0 9단계 / 사용자 설정 저장은 P1 | SLDS 2 comfy/compact 사용자 설정·Primer condensed/normal/spacious·Fiori cozy/compact·Carbon 행 24~64px [검색] |
| 9 | **현장 화면은 ISA-101 식 "회색 기반·이상만 색"** 별도 테마(`data-theme="floor"`): 배경 연회색(흰색 아님), 정상값은 진회색 글자, 알람 4단계(P1 빨강·P2 주황·P3 노랑·P4 보라) + 모양 아이콘, 미확인 알람만 깜빡임(초당 3회 미만) | POP 골격 P0(`pop-layout`) / 안돈·설비 상태 P2·CMS | ISA-101·ASM, Rockwell PlantPAx 심각도 4색, AVEVA SA "Technicolor 금지" [검색 H1~H4], WCAG 2.3.1 |
| 10 | **안돈·원거리 화면 글자 크기 = 거리 기반 공식**(가장 먼 작업자 거리 ÷ 172 = 최소 글자 높이, ÷ 115 = 권장) — 고객 현장마다 정책값 `ANDON_VIEW_DISTANCE_M` | P2 안돈 설계 | MIL-STD-1472·인간공학 시각도 20′ 최소·30′ 권장(현장 = critical) [검색 H6] |
| 11 | **AI 생성·제안 값 표시 규칙**: `hb-ai` 라벨(아이콘 + "AI 제안") + 근거 펼침 + 사람이 확정하기 전까지 "미확정" 스타일. AI 색은 장식에 쓰지 않음 | P5(LLM 도움말·초안) — **규칙은 지금 문서에** | IBM Carbon for AI "AI label 필수·장식 금지", SAP Local AI Notice 의무(고위험 결정) [검색 G1·G2], 03 "AI 는 제안·사람이 확정" |
| 12 | **화면 체크리스트에 "시각·접근성" 절**(9.4) 추가 — 대비·색만 금지·포커스·tnum·밀도·흑백 인쇄·200% 확대 | 1과 같이(skill `checklists.md` §2) | 11 4.3 사용성 절과 짝. 거래처관리에 시범 적용 |

### 0.3 하지 않을 것 (상세 7장)

다크 모드 사무 화면 P0 구현 · 사용자 임의 색 테마/브랜드 색 편집기 · 그라데이션·3D·그림자 장식 · 상태를 행 배경색 전체로 칠하기 · 초록 = 정상 신호등식 대시보드 · 아이콘만 있는 버튼(툴팁·라벨 없이) · CDN 글꼴·아이콘 · 맑은 고딕 파일 배포 · Pretendard 를 직접 서브셋해 같은 이름으로 배포 · 상용 아이콘 폰트(Font Awesome Pro 등) · 아이콘 폰트(글리프 폰트) 방식 · CSS 프레임워크(Tailwind·Bootstrap) 도입 · 애니메이션 많은 차트·게이지 장식 · 마케팅식 큰 여백을 업무 화면에.

---

## 1. 레퍼런스 분석 — 엔터프라이즈·산업용 디자인 시스템

### 1.1 한눈에

| 시스템 | 성격 | 색 체계 | 밀도 | 토큰 구조 | 다크 모드 | SmartHappy 에 가져올 점 |
|---|---|---|---|---|---|---|
| **Siemens iX** | 산업용(HMI·엔지니어링 툴) | 배경 층(color-0~), 컴포넌트 상호작용, **상태 6종: alarm / critical / warning / success / info / neutral** 각각 hover·active·contrast(글자색)·반투명 변형, 텍스트 4단(std/soft/weak/alarm), 테두리 6단(contrast/hard/std/soft/weak/x-weak), 차트 축·격자 색 [원문 I1] | 정보 미확인 | CSS 변수 `--theme-color-*`, `data-ix-theme`·`data-ix-color-schema=light|dark|system` [검색 I2] | Classic light/dark 기본 제공, 구버전 기본값이 **dark** [검색 I2] | **알람(alarm)과 위험(critical)을 구분**, "상태색을 글자색으로 쓰지 말고 아이콘·배경에, 글자는 contrast 짝으로" [원문 I1] |
| **SAP Fiori (Horizon)** | ERP 업무 화면 | 의미색 5종 Negative·Critical·Positive·Neutral·Information, **파랑 글자는 링크 전용**, 장식용 의미색 금지 [검색 S2] | **cozy(44px 터치) / compact(32px 마우스) / condensed(표 전용, compact 위에만)** — 부모 컨테이너에 클래스, 섞지 않음 [검색 S1] | 테마 파라미터 | Horizon 다크 제공 [검색] | 밀도를 **화면 단위 컨테이너 속성**으로, condensed 는 표에만. "색 + 텍스트 필수" |
| **IBM Carbon** | 엔터프라이즈 데이터 | 회색 중심(Gray 10·100 테마), 의미색 | 데이터 표 행 **xs 24 / sm 32 / md 40(기본) / lg 48 / xl 64px**, 헤더 행은 데이터 행과 같은 높이 [검색 C1] | 토큰 | Gray 90/100 테마 [검색] | 행 높이 5단을 토큰으로. **Carbon for AI: AI label + 설명 팝오버 필수, AI 스타일 장식 금지** [검색 G1] |
| **Microsoft Fluent 2** | 오피스·업무 | **중립(Neutral) / 브랜드(Brand) / 공유 상태(빨강 위험·초록 성공·노랑 경고·파랑 정보)** 3팔레트 [검색 M1] | 컴팩트 변형 | **Global → Alias → Component 3단**, 별칭 토큰이 light/dark/고대비마다 다른 원시값을 가리킴. 상태: rest → hover(진하게) → pressed(더 진하게), 포커스는 색이 아니라 두꺼운 선 [검색 M1] | 기본 | 3단 토큰과 **포커스 = 두께**, 고대비 대응을 별칭 층에서 |
| **Salesforce SLDS 2 (Cosmos)** | CRM 업무 | 둥근 요소·정제된 팔레트 | **comfy(기본) / compact 사용자 설정** — 간격 4/8/12/16 → 2/4/8/12px, 글자 1rem → 0.875rem, 줄높이 1.5 → 1.2, 라벨이 위 → 옆 [검색 L1] | `--slds-g-*` 전역 styling hooks, 밀도 인식 훅 | **Winter '26 부터 다크 모드**(관리자 활성화 → 사용자 전환) [검색 L1] | **밀도 = 사용자 설정 + 토큰만 바뀜**, compact 에서 라벨 옆 배치 |
| **Atlassian** | 협업 SaaS | 의미 토큰 | 8px 기준 간격 `space.025(2)~1000(80)`, 0~8px 는 compact 용 [검색 A1] | `--ds-*`, `data-theme` 로 color·spacing·typography 따로 | 지원 | 간격 이름 = 기준의 % (100 = 8px) |
| **GitHub Primer** | 개발 도구 | base → **functional**(fg/bg/border × accent·success·attention·danger·done…) → component, 데이터 시각화 색은 emphasis/muted 짝 [검색 P1] | `--control-*-paddingInline-{condensed|normal|spacious}`, `--stack-gap-*`, DataTable cellPadding 3단 [검색 P1] | 3단, "base 토큰은 직접 쓰지 않는다" | `data-color-mode` | **"원시 토큰은 코드에서 직접 금지"** 규칙(→ Top #2 가드) |
| **KRDS (범정부)** | 한국 공공 | 정부 로고 기반 24색, 12 → **13단계(95 추가)로 고대비 모드와 짝**, 적록·청황 색각이상 고려해 주색 재조정, Primary `#256ef4` [검색 K1] | 8pt 격자, 본문 17px(고령·저시력, Pretendard GOV 가 작아 보여 +1px) [검색 K1] | **primitive → semantic → component 3단** [검색 K1] | 고대비 모드 | 한국어 UI 에서 **Pretendard 계열이 공공 표준**이 됨(2025-01), 한글 크기 감각 근거 |
| **Rockwell PlantPAx / AVEVA SA / Tulip** | 현장 HMI·앱 | 2장 참조. Tulip 템플릿: 회색 단계 + 성공 초록 + 파괴 빨강 + 주버튼 파랑, 강조색은 2개 이내 [검색 T1] | 현장 크기 | Tulip: 색을 변수로 저장 | Tulip: 밝은/어두운 기본 레이아웃 | "주버튼이 화면에서 가장 진한 색", "모바일 글자 20px 이상" [검색 T1] |

### 1.2 색 체계 — 공통 구조

모든 시스템이 같은 4층으로 수렴한다 [검색 종합 → 추정]:

1. **중립(회색) 10~13단** — 배경 층(페이지 < 패널 < 카드), 테두리 3~6단, 글자 3~4단(기본·보조·흐림·비활성). 화면 면적의 90% 이상.
2. **브랜드(강조) 1색 + 단계** — 주버튼·선택·포커스·링크. 상태 의미 없음.
3. **상태(의미)색 4~6종** — 위험/오류, 경고, 성공, 정보(+중립). 각각 **진한 전경(아이콘·테두리)·연한 배경·배경 위 글자색(contrast)** 3짝. iX 는 alarm 과 critical 을 분리.
4. **데이터 시각화 색** — 상태색과 **분리된** 범주색(Primer `--data-*`, iX chart data). 차트 범주색에 빨강·초록을 쓰면 상태로 오독된다.

**공통 규칙**: 상태색을 본문 글자색으로 쓰지 않음(iX) [원문 I1] · 의미 없는 장식에 의미색 금지(Fiori) [검색 S2] · 파랑 글자 = 링크(Fiori) · 색 + 글자/아이콘 필수(Fiori, KRDS, ISA-101 실무).

### 1.3 타이포그래피·숫자

- **한 글꼴, 굵기 2~3개**(400·600·700)가 업무 화면 관행. 크기 단계는 5~7개 [추정 — Carbon·Fluent 공통 관찰].
- **숫자 고정폭**: 오른쪽 정렬 숫자 열은 `tabular-nums`(OpenType `tnum`) 없으면 1·0·8 폭 차이로 자릿수가 들쭉날쭉 [검색 F2]. CSS `font-variant-numeric` 은 2020-01 부터 모든 브라우저 Baseline [검색]. Pretendard 는 tnum 지원, 1.3.7 에서 고정폭 숫자 + 문맥 대체 시 콜론 위치를 고쳤다 [원문 F4].
- **한글 크기 감각**: 같은 font-size 에서 한글은 라틴보다 커 보이지만 Pretendard(GOV)는 다른 한글 글꼴보다 작아 보여 KRDS 는 본문을 17px 로 잡았다 [검색 K1]. 업무 표(밀도 우선)는 13~14px 이 국내 ERP 관행 [추정].
- 영문 전부 대문자 금지 — 상승부·하강부가 단어 인식을 돕는다 [검색 H6].

### 1.4 정보 밀도

| 시스템 | 단계 | 표 행 높이 / 컨트롤 |
|---|---|---|
| Carbon | xs·sm·md·lg·xl | 24·32·40·48·64px [검색 C1] |
| Fiori | condensed·compact·cozy | ≈24·32·44~48px(터치 2.75rem) [검색 S1 — 표 행 값은 2차 자료] |
| SLDS 2 | compact·comfy | 간격 절반 수준, 글자 0.875rem·줄높이 1.2 [검색 L1] |
| Primer | condensed·normal·spacious | 패딩 3단 [검색 P1] |

결론 [추정]: 밀도는 **"화면 단위·사용자 설정"**이고 **같은 화면 안에 섞지 않는다**(Fiori). 사무용 기본은 마우스·키보드 기준 compact(행 28~32px), 터치 사무(태블릿 결재 등)는 normal/spacious(40~48px), POP 은 별도(2.3).

### 1.5 표·그리드·대시보드 패턴

- **표**: 헤더 행 = 데이터 행 높이(Carbon) [검색 C1] · 숫자 오른쪽·코드/날짜 가운데·문자 왼쪽(현행 hb.grid 와 같음) · 얼룩말 줄무늬 대신 가는 행 구분선 + hover · 선택 행은 배경 + **왼쪽 강조 막대 또는 체크 표시**(색만으로 선택 표시 금지 — 1.4.11) · 일괄 작업 바는 행 밀도와 짝(Carbon: 큰 바 ↔ lg/xl 행) [검색 C1].
- **대시보드**: KPI 타일 = 숫자(크게·tnum) + 단위 + 기간 + 목표 대비(화살표·글자) — 색은 목표 이탈 때만 [추정, ISA-101 원칙 응용]. 차트 격자·축은 가장 약한 테두리색(iX chart infrastructure) [원문 I1].
- **AVEVA SA / ISA-101 아날로그 지시계**: 숫자만 보여 주지 말고 **정상 범위 띠(연한 파랑 또는 옅은 음영) 위의 현재값 막대** — 이상일 때만 경고·알람색 [검색 H4]. → SPC·설비 측정값 화면에 적용(P3·CMS).

### 1.6 다크 모드

- 산업용(iX)은 다크 우선이었고, 업무 SaaS 는 최근에야 다크를 넣는다(SLDS 2 Winter '26) [검색 I2·L1].
- 연구: **조명과 상호작용** — 어두운 방에서 다크가, 밝은 방에서 라이트가 더 잘 읽힘(가독성 평점 어두운 방 다크 5.67 vs 라이트 4.42, 밝은 방 라이트 5.78 vs 다크 4.29) [검색 D1]. 지속 집중·세부 읽기는 라이트 선호 [검색 D1].
- 관제실 업계(Center for Operator Performance): 회색 배경이 관행이지만 **실증 근거는 부족**, "밝되 순백은 아닌 배경 + 높은 휘도 대비 전경"을 권함 [검색 H5].
- 결론 [추정]: **사무실·현장(수백~1000 lux) = 라이트**, **어두운 관제실·야간 대형 안돈 = 다크 옵션**. 어느 테마든 **알람색의 의미는 같아야** 한다 [검색 H5]. 토큰이 의미층으로 돼 있으면 다크는 "값 파일 하나"다(Fluent 별칭 토큰 방식).

### 1.7 아이콘

| 세트 | 라이선스 | 비고 |
|---|---|---|
| **Lucide** | **ISC**(MIT 와 동등, 저작권·라이선스 문구 유지) [검색 N1] | Feather 포크, 24px 격자 선 아이콘 약 1,700+, 일부 사이트가 MIT 로 잘못 표기 |
| Tabler Icons | MIT [검색 N1] | 약 6,000+, 24px 격자 |
| Material Symbols | Apache-2.0(NOTICE 유지) [검색 N1] | 매우 많음, 가변 아이콘 폰트 방식이 기본 |
| Font Awesome Pro 등 상용 | 유료·재배포 제약 | **쓰지 않음** |

[추정] **Lucide(ISC) SVG 를 필요한 것만 골라 `static/vendor/lucide/{ver}/` 스프라이트 1개**(`<svg><use href="/vendor/.../sprite.svg#check">`)로 쓴다 — 아이콘 폰트는 글자로 읽히는 접근성 문제·FOIT 가 있고, 개별 SVG 를 템플릿에 넣으면 CSP 와 무관하지만 반복이 많다. 상태 배지용 모양(원·삼각형·마름모·사각형·육각형)은 직접 그린 단순 SVG 로 충분.

### 1.8 국내 제품

- **더존 Amaranth 10**: 자체 가이드 "TANGO"(Tangram·Alive·Natural·Grid·One), 검색창 중심 포털, 30여 포틀릿 개인화 홈. 이전 iCUBE 대비 UI 개선 평가가 많음 [검색 K2].
- **영림원 K-System Ace**: 업무 프로세스 메뉴, 엑셀 같은 시트(열 숨김·필터·고정·다중 정렬·단축키), 사용자 유형별 홈 위젯 [검색 K2].
- **국내 클라우드 MES·POP**(미라콤 Cloud MES, 엠아이큐브, 팩앤롤 등): 공개 화면 자료가 적다. 공급사 설명은 "상태 색 구분, 관리자용·작업자용 분리, 큰 버튼, 클릭 최소화" 수준 [검색 K3]. 2026 년 공개 저장소 이슈에서 **"POP 화면마다 제각각인 여백·글자·버튼 크기·상태 문구를 하나의 표시 규격으로"** 정돈하는 작업이 보인다 [검색 K3] — 표시 규격(토큰)이 없으면 화면이 늘수록 이런 정돈 비용이 생긴다는 방증 [추정].
- 관찰 [추정]: 국내 업무 제품은 기능 밀도·엑셀 친화가 강점이고, 시각 규칙(토큰·상태 표시·접근성)을 공개한 곳은 거의 없다. **"보기 좋고 읽기 쉬운 국산 MES"는 차별화 여지**가 있다.

---

## 2. 현장(산업) 특화

### 2.1 ISA-101 고성능 HMI

- ISA-101.01-2015 는 **색을 지정하지 않는다** — 수명주기와 원칙을 정하고, 각 조직이 자체 HMI 철학·스타일 가이드를 만든다 [검색 H1]. "회색 화면"은 ASM 컨소시엄·실무 적용에서 나온 관행.
- 원칙 [검색 H1·H2·H3]:
  - **정상 운전 = 저대비 회색**, 색은 이상·조작자 행동이 필요한 상태에만. 모든 것이 늘 색칠돼 있으면 이상 때 아무것도 눈에 띄지 않는다.
  - **알람 색은 다른 용도로 쓰지 않는다**(Rockwell: P1 을 빨강으로 쓰면 "가동 중 펌프"에 빨강 금지). 정상·가동에 초록을 기본으로 쓰지 않는다.
  - 실시간 값은 차분한 차가운 색(짙은 파랑·짙은 초록), 강조는 색 대신 **선 두께**, 그라데이션·3D·사진·애니메이션 금지.
  - 화면 계층 L1 개요 → L2 공정 영역 → L3 상세 → L4 진단.
  - 응답 30~50% 빨라진다는 수치는 벤더 주장 [검색 H1].
- **알람 우선순위 색**(사이트 철학마다 다름): 흔한 매핑 P1 빨강·P2 주황·P3 노랑·P4 파랑, **Rockwell PlantPAx 기본은 심각도 1~250 보라 / 251~500 노랑 / 501~750 주황 / 751~1000 빨강**, 객체 테두리가 가장 높은 활성 알람색, **미확인 알람만 깜빡임** [검색 H3]. 알람 관리 자체는 ISA-18.2.
- **배경**: 중간~연한 회색(예 RGB 192/221, `#E8E8E8` 등), 검정 배경은 장시간 피로·색 구분 저하, 흰 배경은 눈부심 [검색 H2·H5].

[추정] SmartHappy 는 PLC 를 직접 그리는 SCADA 가 아니지만 **설비 상태판(CMS)·안돈·SPC·POP 결과 표시**는 같은 원칙을 따르는 것이 맞다. 특히 국내 MES 대시보드에서 흔한 "가동 = 초록, 정지 = 빨강 신호등 타일"은 ISA-101 과 정면으로 어긋난다 — 계획 정지·비가동도 빨강이 되어 진짜 이상이 묻힌다.

### 2.2 공장 조명·원거리 가독성

- 거리 기반 글자 높이 [검색 H6]:

| 기준 | 글자 높이 / 거리 | 10m 일 때 |
|---|---|---|
| ISO 9241-3 (사무 VDT) | 거리 ÷ 155~175 | 57~65mm |
| 인간공학 시각도 16′(critical 최소) | 거리 × 0.00465 | 47mm |
| MIL-STD-1472 컬러 화면 21′ 최소 / 30′ 권장 | × 0.0061 / × 0.0087 | 61 / 87mm |
| 사인물 경험칙 | 10ft 당 1inch(≈ ÷ 120) | 83mm |

- 적용 계산 [계산·추정]: 55" FHD(화면 높이 ≈ 685mm, 1px ≈ 0.63mm)에서 10m 거리 20′(≈ 58mm) 숫자 → 숫자 높이 ≈ 92px → font-size ≈ 130px(숫자 높이 ≈ font-size 의 70% 가정). **즉 55" 한 화면에 큰 숫자 3~4줄이 한계** — 안돈은 "라인당 숫자 2~3개(계획·실적·달성률)"로 설계해야 한다.
- 노안·반사·비스듬한 시야각 여유를 두고 **실제 설치 위치에서 확인** [검색 H6]. 밝은 공장 조명에서는 라이트 배경이 반사에 유리 [검색 H5·D1].

### 2.3 장갑·터치 타깃

- 11 2.3 의 관행(맨손 12mm / 얇은 장갑 15mm / 작업 장갑 19mm / 안전 25mm, 간격 3~5mm; Rockwell 버튼 최소 40×40px)을 그대로 쓴다 — 여기서 중복 조사하지 않음.
- **추가 주의 [추정 — CSS Values 명세 기준, PoC 때 실측]**: CSS 의 `mm`·`cm` 은 물리 길이가 아니라 **1in = 96px 에 고정된 참조 단위**다. 10.1" 1280×800 태블릿(약 150 DPI)에서 `15mm`(≈ 57 CSS px)는 실제 약 15mm 가 아니다(장치 픽셀 비율·OS 배율에 따라 다름). 따라서 11 이 제안한 `--hb-touch-min: 15mm` 는 **px 값 + 단말별 보정 정책**(`POP_TOUCH_SIZE`)으로 두고, 단말 기종마다 자로 재서 정한다.
- WCAG 2.2 SC 2.5.8(AA) 최소 24×24 CSS px, 2.5.5(AAA) 44×44 [검색 W1] — 사무 화면 하한선. 아이콘 버튼은 아이콘 16px + 패딩으로 24px 이상.

### 2.4 색약(적록) 대응

- 한국 성인(19~49세) 국민건강영양조사 2013: 전체 3.9%, **남성 6.5%**, 여성 1.1%, 남성 실패자 중 deutan(녹색약) 64.2% [검색 A11]. 생산직 남성 비율이 높은 현장에서는 **작업자 15~20명 중 1명** 꼴 [추정].
- 대책 [검색 A12 → 추정]:
  1. **색만으로 구분 금지**(색 + 모양 + 글자 + 위치). 알람 우선순위는 모양으로도 구분(예: P1 ◆ 마름모, P2 ▲ 삼각형, P3 ■ 사각형, P4 ● 원 — 정확한 모양은 우리가 정함).
  2. 빨강/초록 쌍에 의존하지 않기 — 양/불은 **"합격 ✓ / 불합격 ✕" 글자·아이콘**이 1차, 색은 2차.
  3. 차트 범주색은 **Okabe-Ito**(#E69F00 주황·#56B4E9 하늘·#009E73 청록·#F0E442 노랑·#0072B2 파랑·#D55E00 주홍·#CC79A7 자주) 계열 [검색 A12]. 2계열 비교는 파랑/주황.
  4. 시험: 브라우저 개발자 도구의 색각 시뮬레이션(deuteranopia·protanopia) + **흑백 인쇄 검사**(ISA-101 실무) [검색 H1].

### 2.5 WCAG 2.2 대비·조작

| 기준 | 내용 | 적용 |
|---|---|---|
| 1.4.3 (AA) | 본문 글자 4.5:1, 큰 글자(18.66px 굵게 / 24px) 3:1 | 모든 글자 |
| **1.4.11 (AA)** | **UI 컴포넌트 경계(입력 테두리)·상태(선택·체크·포커스)·의미 있는 그래픽 3:1** — 비활성은 예외 [검색 W2] | 입력 테두리, 선택 행 표시, 체크박스, 상태 아이콘, 차트 선 |
| 1.4.1 (A) | 색만으로 정보 전달 금지 | 상태 배지, 그리드 셀, 차트 |
| 2.4.7 / 2.4.11 (AA) | 포커스 보임 / 포커스가 다른 요소에 가려지지 않음(2.2 신규) | 고정 헤더·sticky 열·하단 확정 바 아래로 포커스가 숨지 않게 |
| 2.5.8 (AA) | 타깃 24×24 CSS px 또는 간격 [검색 W1] | 페이저·정렬·닫기(×) 버튼 |
| 1.4.4 / 1.4.10 | 200% 확대, 리플로(데이터 표는 2차원 스크롤 예외) [추정 — Understanding 문서 재확인] | 셸·폼은 확대 시 깨지지 않게, 그리드는 가로 스크롤 허용 |
| 2.3.1 (A) | 초당 3회 넘는 깜빡임 금지 | 알람 깜빡임 ≤ 2Hz |

### 2.6 상태 표시를 색만으로 하지 않기 — 정리

모든 상태 표시는 **[아이콘(모양)] + [글자] + (색)** 3요소 중 **아이콘·글자 2개는 필수**, 색은 강화 수단 [검색 S2·H1 → 추정]. 표 셀이 좁으면 아이콘 + 약어 + `title`/`aria-label` 전체 글자.

---

## 3. 최근 방향 (2024~2026)

### 3.1 사무 화면 vs 현장 화면 분리

- Opcenter X 2607 "New Operator Terminal", 2507 전체 화면 자재 보기 — 사무용 화면과 다른 **작업자 단말 전용 화면**을 별도 제품 표면으로 [검색 O1]. Tulip "앱 하나 = 역할 하나", 장갑이면 터치 대신 스캐너·발판 [검색 T1]. 국내 공급사도 "관리자용·작업자용 분리" [검색 K3].
- [추정] 두 표면은 **같은 토큰(색 의미·글꼴·아이콘)을 공유하고 밀도·크기·배경 테마만 다르게** 가는 것이 유지비가 가장 낮다. SmartHappy 는 `body[data-surface="office|pop|andon"]` + `data-density` 로 나눈다.

### 3.2 디자인 토큰 표준화

- **W3C Design Tokens Community Group 형식 2025.10 — 첫 안정판(2025-10-28)**: `.tokens.json`, `$value`·`$type`, `$extends` 로 light/dark·접근성 변형·브랜드 테마, Display P3·OKLCH 지원. 단, **W3C 권고안(표준 트랙)이 아니라 커뮤니티 그룹 보고서** [검색 W3].
- [추정] 1인 개발에 빌드 도구(Style Dictionary 등)는 과하다. **지금은 `tokens.css` 손 관리**, 고객별 브랜딩·다크·고대비가 실제로 필요해지면(P5~) DTCG JSON → CSS 생성으로 옮긴다. 토큰 이름을 DTCG 계층(color.bg.surface 등)과 1:1 대응되게 지어 두면 이전 비용이 거의 없다.

### 3.3 밀도 = 사용자 설정

- SLDS 2 compact/comfy 를 사용자 프로필에서 고름, 밀도 인식 토큰을 쓰면 컴포넌트 코드 수정 없이 전체가 바뀜 [검색 L1]. Confluence 도 2025~26 에 Compact Mode 추가 [검색 A1]. Fiori S/4HANA 2302 "큰 화면에서 흰 공간이 너무 많다"는 피드백으로 밀도 개선 [검색 S1].
- [추정] 국내 사무 사용자(엑셀 숙련)는 compact 를 선호할 가능성이 높다 — 기본 compact, 사용자 설정으로 normal.

### 3.4 AI 보조 UI 표시

- **IBM Carbon for AI**: AI 가 관여한 컴포넌트에는 **AI label(아이콘) 필수**, 그것이 **설명 팝오버**의 진입점(What·Why·How 를 짧게 → 상세는 요청 시). "AI 스타일을 장식으로 쓰지 말 것" [검색 G1].
- **SAP Fiori Local AI Notice**: AI 생성·편집 정보가 있는 UI 에 **의무**(고위험 결정), 표 전체면 표 제목 옆, 섹션이면 상위 섹션 제목 옆, 객체 전체면 객체 제목 옆. "사용 전 검토·사실 확인" 안내, 긴 설명은 링크 [검색 G2]. AI Acknowledgement(1.122), Explainable AI(필요한 역할에게만 상세) [검색 G2].
- [추정] SmartHappy 규칙(P5 전에 문서로): ① AI 가 만든 값·문장 옆 `hb-ai` 라벨(아이콘 + "AI 제안") ② 근거(참조 문서·데이터 기간) 펼침 ③ **사람이 확정하기 전에는 "제안" 상태 스타일**(점선 테두리 + 연한 배경), 확정 후 일반 값과 같아지고 이력에 "AI 제안 → 사용자 확정" 기록(03 원칙, 11 D12 대리 입력 표시와 같은 방식) ④ AI 색(보라 계열 등)을 상태색·브랜드색과 겹치지 않게 별도 1색 ⑤ 현장 POP 에는 AI 문장을 띄우지 않고 결과(추천 작업·사유 후보)만 타일로.

### 3.5 그 밖

- 다크 모드가 업무 SaaS 에 기본 옵션화(SLDS 2 Winter '26) [검색 L1] — 1.6 결론대로 P0 에는 토큰만.
- 공공 한국어 UI 의 글꼴이 **Pretendard GOV** 로 표준화(2025-01) [검색 K1] — 국내 사용자에게 Pretendard 는 "익숙하고 정돈된" 인상.

---

## 4. SmartHappy 현재 상태 진단 (2026-10-15, 커밋 `b583396` 기준)

### 4.1 CSS 감사

| 항목 | 현재 | 문제 | 근거 |
|---|---|---|---|
| 색 값 | page.css·shell.css·login.css 에 직접 `#hex` 25종(rgba 별도, Tailwind 기본 팔레트 slate/gray/blue 값과 비슷), 토큰 없음 | 화면이 늘면 색 통일·다크·고객 브랜딩 불가 | 파일 열람 |
| 글꼴 | `"Malgun Gothic", "Apple SD Gothic Neo", sans-serif` (세 파일에 각각 반복) | Linux 설치형 서버의 **브라우저 단말**(Linux·Android 태블릿 POP)에 맑은 고딕 없음 → 기본 글꼴로 떨어져 화면마다 모양이 다름. 맑은 고딕은 파일 배포 불가(이름 호출만 OK) [검색 F3] | 파일 열람 |
| 숫자 | `hbg-int`·`hbg-dec` 오른쪽 정렬만, tnum 없음 | 맑은 고딕 숫자는 비례폭 → 자릿수 정렬 흔들림 | |
| 입력 테두리 | `#d1d5db` on `#fff` = **1.47:1** | **WCAG 1.4.11 미달**(입력 경계 3:1) | [계산] |
| 비활성 버튼 | 배경 `#93c5fd` 위 흰 글자 1.80:1 | 비활성은 1.4.3 예외지만 "흐린 파랑 = 처리 중(cursor: wait)"과 "권한 없음"이 같은 모양 | [계산] |
| 선택 행 | `#dbeafe` 배경만(흰 행 대비 1.22:1) | **선택 상태를 색만으로**(1.4.11·1.4.1) | [계산] |
| 셀 구분선 | `#eef2f7`(1.12:1) | 장식선이라 기준 대상 아님. 다만 밝은 현장 조명·저가 모니터에서 안 보일 수 있음 [추정] | [계산] |
| 포커스 | 그리드 스크롤 영역·로그인 입력만 있음, 버튼·폼 입력은 브라우저 기본 | 일관성 없음, 셸 메뉴는 `outline: none` 후 배경만 | 파일 열람 |
| 상태 표시 | 오류는 빨강 글자·`aria-invalid` 배경, 성공·정보는 파랑 글자 | 정보 메시지 색(`#1d4ed8`)이 브랜드·링크 파랑과 같음 | |
| 셸 헤더 | `#1e3a8a` 진한 남색 | 문제 아님 — 브랜드 영역으로 유지 가능 | |
| 크기 | 본문 13px, 제목 18/15/14px, 패딩 3~20px 산발 | 간격 단계 없음(3·4·5·6·7·8·10·12·14·16·18·20px) | |

좋은 점: 인라인 스타일 0(CSP 가드), 폭만 CSSOM, sticky 헤더·첫 열, `aria-selected`·`aria-invalid` 를 스타일 훅으로 쓰는 방식 — **ARIA 상태를 스타일 선택자로 쓰는 것은 접근성과 시각이 함께 맞는 좋은 패턴**이니 유지 [추정].

### 4.2 대비 계산 [계산]

| 조합 | 대비 | 판정 |
|---|---|---|
| 현행 본문 `#1f2937` / 흰 | 14.68 | 통과 |
| 현행 라벨 `#64748b` / 흰 | 4.76 | 본문 통과(여유 적음) |
| 현행 입력 테두리 `#d1d5db` / 흰 | **1.47** | **1.4.11 미달** |
| 현행 주버튼 흰 / `#2563eb` | 5.17 | 통과 |
| 현행 선택 행 `#dbeafe` / 흰 | 1.22 | 색만으로 상태 → 보조 표시 필요 |
| 제안 컨트롤 테두리 `#8a929c` / 흰 | 3.15 | 통과 |
| 제안 컨트롤 테두리 `#8a929c` / `#f4f6f8` | 2.91 | **미달 → 회색 배경 위 입력은 흰 배경 유지**(5.6) |
| 제안 주버튼 흰 / `#1d5bd6` | 5.97 | 통과 |
| 제안 위험 `#b42318` / 흰 · / `#fdecea` | 6.57 · 5.75 | 통과 |
| 제안 경고 글자 `#8a5300` / `#fff4d6` | 5.78 | 통과 |
| 제안 성공 `#1a7a43` / 흰 · / `#e7f5ec` | 5.37 · 4.78 | 통과 |
| 제안 정보 `#0b6aa3` / 흰 · / `#e6f2fa` | 5.83 · 5.12 | 통과 |
| 알람 P1 흰 / `#d92d20` | 4.83 | 통과 |
| 알람 P2 진회색 `#1b1f24` / `#e8590c` | 4.63 | 통과(**흰 글자 금지** — 3.58) |
| 알람 P3 진회색 / `#f5b301` | 8.94 | 통과 |
| 알람 P4 흰 / `#7a3fc4` | 6.30 | 통과 |
| 다크 본문 `#e6edf3` / `#11151a` | 15.51 | 통과 |
| 다크 주색 `#5b9cff` / `#11151a` | 6.67 | 통과 |

---

## 5. SmartHappy 제안

### 5.1 토큰 체계 — 3단, CSS 변수

```
static/css/tokens.css     원시(--hb-p-*) + 의미(--hb-*) + 테마/밀도 재정의 (색·크기 값은 이 파일에만)
static/css/base.css       리셋·글꼴·포커스·공통 요소 (현 page.css 상단 + shell/login 공통부)
static/css/page.css       업무 화면 레이아웃 (의미 토큰만 사용)
static/css/hb.grid.css    그리드 (컴포넌트 토큰 --hbg-* → 의미 토큰)
static/css/shell.css, login.css, (P2) pop.css, andon.css
```

- **원시 토큰** `--hb-p-gray-50 … --hb-p-gray-950`, `--hb-p-blue-*`, `--hb-p-red-*` … — **컴포넌트 CSS 에서 직접 쓰지 않는다**(Primer 규칙) → Top #2 가드가 `tokens.css` 밖의 원시 색 값과 함께 `var(--hb-p-` 사용도 금지.
- **의미 토큰** `--hb-bg-page`, `--hb-bg-surface`, `--hb-bg-subtle`, `--hb-text`, `--hb-text-muted`, `--hb-border`, `--hb-border-control`, `--hb-action`, `--hb-status-danger-fg/bg/on` … — 다크·고대비·현장 테마는 **이 층만 재정의**.
- **컴포넌트 토큰** `--hbg-row-h`, `--hbg-header-bg`, `--hb-btn-h` … — 밀도가 여기를 바꾼다.
- 테마·밀도 전환: 서버 템플릿이 `<body th:attr="data-surface=…,data-density=…">` 로 넣는다(사용자 설정·단말 설정에서). **인라인 style 이 아니라 속성 선택자** → CSP `style-src 'self'` 그대로. JS 로 바꿀 때도 `dataset` 변경만(CSSOM 도 불필요).
- 이름은 DTCG 경로와 1:1(예 `--hb-status-danger-bg` ↔ `color.status.danger.bg`) — 나중에 JSON 생성으로 이전 가능(3.2).

### 5.2 색 초안 값 (라이트, 사무 기본)

**중립(회색) — 약간 푸른 기가 도는 회색, 현행 slate 계열과 연속성 유지** [추정]

| 원시 | 값 | 주 용도(의미 토큰) |
|---|---|---|
| gray-0 | `#ffffff` | `--hb-bg-surface`(카드·폼·그리드 본문), 입력 배경 |
| gray-25 | `#f9fafb` | `--hb-bg-page`(업무 화면 바탕) |
| gray-50 | `#f4f6f8` | `--hb-bg-subtle`(조회 조건 영역·그리드 헤더·푸터) |
| gray-100 | `#e9ecf0` | `--hb-bg-muted`(탭 비활성·hover) |
| gray-200 | `#dde1e6` | `--hb-border-subtle`(셀 구분선 — 현행보다 약간 진하게) |
| gray-300 | `#c3c9d1` | `--hb-border`(패널·구획 경계, 장식) |
| gray-500 | `#8a929c` | `--hb-border-control`(입력·체크박스 경계, **3:1**) |
| gray-600 | `#6b7480` | `--hb-text-subtle`(placeholder 아닌 보조 정보, 4.74:1) |
| gray-700 | `#4a525d` | `--hb-text-muted`(라벨·보조 글자, 7.9:1) |
| gray-900 | `#1b1f24` | `--hb-text`(본문, 16.6:1) |

**브랜드·동작**

| 의미 토큰 | 값 | 규칙 |
|---|---|---|
| `--hb-action` | `#1d5bd6` | 주버튼 배경·링크·선택 표시·포커스. **상태 의미로 쓰지 않음** |
| `--hb-action-hover` / `-pressed` | `#174bb3` / `#123c8f` | Fluent 식 진하게 |
| `--hb-action-subtle` | `#dce8fb` | 선택 행 배경(+ 왼쪽 3px `--hb-action` 막대) |
| `--hb-focus` | `#1d5bd6` 2px + 1px 흰 간격(`outline-offset`) | 모든 포커스 가능한 요소 `:focus-visible` |
| `--hb-brand-header` | `#1e3a8a` | 셸 머리띠(현행 유지) |

**상태(의미) — 각 3짝: fg(아이콘·테두리·글자) / bg(연한 배경) / on(진한 배경 위 글자)**

| 상태 | fg | bg | 아이콘(모양) | 쓰는 곳 |
|---|---|---|---|---|
| danger(오류·위험·불합격·반려) | `#b42318` | `#fdecea` | ✕ 원 / 팔각 | 검증 오류, 삭제 확인, 불합격, 반려 |
| warning(경고·주의·지연) | `#8a5300`(글자) / `#e8a200`(아이콘) | `#fff4d6` | ▲ 삼각 | 납기 임박, 재고 부족 예상, 잠금 임박 |
| success(완료 피드백) | `#1a7a43` | `#e7f5ec` | ✓ 체크 | **저장 완료 토스트 등 일시적 피드백**, 합격 판정 |
| info(안내) | `#0b6aa3` | `#e6f2fa` | i 원 | 안내 배너, 도움말. 브랜드 파랑과 구분되게 청록 쪽 |
| neutral(일반 상태) | `#4a525d` | `#e9ecf0` | ● 원(테두리) | 작성·대기·사용 안 함 등 대부분의 업무 상태 |
| ai(AI 제안) | `#6941c6` | `#f2edfb` | ✦ 반짝이 | 3.4 규칙 — 장식 금지 |

**현장 알람(ISA-101 식, `data-surface="pop|andon"` 및 설비 상태판에서만)**

| 우선순위 | 배경 | 글자 | 모양 | 동작 |
|---|---|---|---|---|
| P1 긴급 | `#d92d20` | 흰 | ◆ 마름모 | 미확인 시 1Hz 깜빡임 + 소리 |
| P2 높음 | `#e8590c` | `#1b1f24`(흰 금지) | ▲ 삼각 | 미확인 시 깜빡임 |
| P3 중간 | `#f5b301` | `#1b1f24` | ■ 사각 | 깜빡임 없음 |
| P4 낮음 | `#7a3fc4` | 흰 | ● 원 | — |
| 정상 | 배경 회색, 값 `#1b1f24` | — | 없음 | 색 없음 |

- 업무 상태 코드(결재 `DRAFT/PENDING/APPROVED/REJECTED`, 작업지시 `PLAN/RUN/DONE/HOLD` 등)는 **공통코드에 "표시 톤"(`neutral|info|warning|danger|success`)을 속성으로** 두고 배지가 그 톤을 따른다 [추정] — 색을 화면 JS 마다 정하지 않게. (공통코드 테이블 컬럼 추가는 원래 세션 결정 V5)
- **업무 상태의 대부분은 neutral** 이다. "진행 중"을 파랑, "완료"를 초록으로 칠하면 화면 전체가 신호등이 된다 — 주의가 필요한 상태(반려·지연·보류)만 색 [추정, ISA-101 원칙의 사무 화면 응용].
- 차트 범주색: Okabe-Ito 기반 6색(파랑 `#0072B2`, 주황 `#E69F00`, 청록 `#009E73`, 하늘 `#56B4E9`, 주홍 `#D55E00`, 자주 `#CC79A7`) — 상태색과 분리, P3 차트 조사(10 H7) 때 확정.

**다크(준비만)**: `--hb-bg-page #0d1014`, `--hb-bg-surface #11151a`, `--hb-text #e6edf3`(15.5:1), `--hb-text-muted #a8b2bd`(8.5:1), `--hb-border-control #8a929c`(5.8:1), `--hb-action #5b9cff`(6.7:1), danger `#ff6b5e`(6.6:1) [계산]. 구현은 V3 결정 후.

### 5.3 간격·크기·모서리·그림자·글꼴 크기

| 종류 | 토큰 | 값 | 비고 |
|---|---|---|---|
| 간격(4px 기준) | `--hb-space-1…8` | 2·4·6·8·12·16·24·32px | Atlassian 8px 기준 + 2·6 보조. 현행 산발 값(3·5·7·10·14·18·20)을 이 단계로 |
| 모서리 | `--hb-radius-sm/md/lg` | 3·4·8px | 입력·버튼 4, 배지 3(또는 완전 둥근 999), 대화상자 8. 현행 4~6·10 을 정리 |
| 그림자 | `--hb-shadow-1/2` | `0 1px 2px rgb(16 24 40/.08)` / `0 8px 24px rgb(16 24 40/.16)` | 1 = 떠 있는 메뉴, 2 = 대화상자. **카드·패널은 그림자 대신 테두리** |
| 선 | `--hb-border-w` / `-strong` | 1px / 2px | 강조는 색보다 두께(ISA-101·Fluent) |
| 글꼴 크기 | `--hb-fs-xs/sm/md/lg/xl/2xl` | 12·13·14·16·18·22px | compact 본문 13, normal 본문 14. 12px 는 보조 정보만(최소) |
| 줄높이 | `--hb-lh-tight/base` | 1.25 / 1.45 | 표·컨트롤 tight, 본문 base |
| 굵기 | `--hb-fw-regular/semibold/bold` | 400·600·700 | 3개만 |
| z-index | `--hb-z-sticky/menu/dialog/toast` | 2·50·100·200 | 현행 2·3·20·50 산발 정리 |

**밀도 표 (컴포넌트 토큰이 이렇게 바뀐다)**

| 토큰 | compact(사무 기본) | normal | spacious | pop(현장) |
|---|---|---|---|---|
| 본문 글자 `--hb-fs-body` | 13px | 14px | 15px | 20px |
| 그리드 행 `--hbg-row-h` | 28px | 34px | 40px | 그리드 대신 카드(09 4.10) |
| 컨트롤 높이 `--hb-ctl-h` | 28px | 32px | 40px | 56px 이상(정책 `POP_TOUCH_SIZE`, 2.3) |
| 버튼 최소 폭 | 56px | 64px | 72px | 120px |
| 폼 라벨 | 위 | 위 | 위 | 위(굵게) |
| 간격 단위 | ×1 | ×1.25 | ×1.5 | ×2 |
| 숫자(KPI·수량) | 18px | 20px | 22px | 32~48px |

- 현행 hb.grid(패딩 5px 8px + 13px)는 행 ≈ 27px 로 compact 와 거의 같다 — 시각 변화 없이 토큰화 가능.
- 안돈(`data-surface="andon"`)은 밀도 표 대신 **거리 공식(2.2)** 으로 `--hb-fs-andon-value` 를 단말 정책에서 계산.

### 5.4 글꼴

| 후보 | 라이선스 | 장단점 | 판정 |
|---|---|---|---|
| **Pretendard 1.3.9** (Variable dynamic subset) | SIL OFL 1.1, 예약명 Pretendard·Source·Inter·M PLUS 1 [검색 F1] | 한글·라틴(Inter 기반) 조화, tnum 지원, KRDS 표준의 원형, 굵기 축 하나로 400~700 | **채택 권고** |
| Pretendard GOV | OFL 1.1 | 공공 기호 보강판(1.3.7 추가, 1.3.9 기호 개편) [원문 F4] | 공공 납품 시 고려 |
| Noto Sans KR | OFL 1.1, 예약명 Source [검색 F3] | 무난, 파일 큼, 라틴 숫자 모양이 다소 넓음 | 폴백 |
| IBM Plex Sans KR | OFL 1.1, 예약명 Plex(+ IBM 상표) [검색 F5] | 개성 강함, 굵기 적음 | 비채택 |
| 나눔고딕 | OFL 1.1(배포 파일에 전문 없음 → 직접 동봉) [검색 F3] | 오래됨, 숫자 정렬 약함 | 비채택 |
| D2Coding | OFL 1.1(NAVER) [검색 F5] | 고정폭 — **코드·LOT 번호·바코드 문자열 표시용 보조 글꼴** 후보 | P2 검토 |
| 맑은 고딕 | Microsoft 독점, 파일 배포·웹폰트 불가 [검색 F3] | Windows 에만 있음 | **이름 폴백으로만** |

- 배포: 공식 릴리스의 `pretendard-dynamic-subset`(가변) woff2 조각 + CSS 를 **수정 없이** `static/vendor/pretendard/1.3.9/` 에, `LICENSE`(OFL 전문) + SHA-256 목록과 함께 커밋(10 H5 규칙). 조각 수는 수십~백여 개·총 수 MB 로 추정되나 **브라우저는 화면에 나온 글자의 조각만** 받는다(`unicode-range`) [검색 F1 — 개수는 릴리스에서 확인]. 직접 서브셋하면 OFL "수정본"이 되어 **예약명 Pretendard 를 쓸 수 없으니 하지 않는다** [검색 F1].
- 공식 CSS 가 상대 경로 `url()` 만 쓰는지 확인(CSP `font-src 'self'` — 외부 URL 이 있으면 차단됨) [추정 — 적용 시 확인].
- `font-display: swap`(공식 CSS 기본 확인) — 폴백(맑은 고딕)과 높이 차이로 레이아웃이 흔들리지 않게 `size-adjust` 는 쓰지 않고 그대로 둔다(차이 작음) [추정].
- 폰트 스택: `--hb-font: "Pretendard Variable", Pretendard, "Malgun Gothic", "Apple SD Gothic Neo", "Noto Sans KR", system-ui, sans-serif;` `--hb-font-mono: "D2Coding", ui-monospace, Consolas, monospace;`
- 숫자: `.hbg-int, .hbg-dec, .hb-num { font-variant-numeric: tabular-nums; }` — 본문 문장 속 숫자는 비례폭 유지. **dynamic subset 에서 tnum 글리프가 빠지지 않았는지 실측**(Playwright 로 계산 스타일만 보지 말고 렌더 폭 비교) [검색 F2].
- 다국어(11 2.5 vi): Pretendard 는 라틴 확장(베트남어 성조 포함) 지원 여부를 **적용 시 확인**(Inter 기반이라 지원 가능성 높음) [추정].

### 5.5 상태 배지·메시지 규칙

- 컴포넌트 `hb-status`(클래스 + `data-tone="neutral|info|warning|danger|success|ai"`): `[아이콘 12~14px][글자]`, 배경 bg·글자 fg, 높이 = 컨트롤 높이 − 8px, 모서리 3px. 셀 폭이 좁으면 아이콘 + 짧은 글자 + `title`.
- hb.grid 컬럼 타입 `status` 추가 제안 — 코드값 → 공통코드 이름 + 톤(5.2). **행 전체 배경색 칠하기 금지**(선택 행 표시와 충돌, 색만 전달) — 행 강조가 꼭 필요하면 왼쪽 3px 막대 + 아이콘 [추정].
- 화면 메시지(`#msg`): 성공은 토스트(3~5초 후 사라짐, `role="status"`), 오류는 사라지지 않는 배너(`role="alert"`) + 해당 필드 `aria-invalid` + 필드 아래 글자. 오류 배너는 **"무엇이 잘못됐고 무엇을 하면 되는지"**(11 4.3).
- 정보 메시지 색을 브랜드 파랑(`--hb-action`)과 분리(현행 `#1d4ed8` 은 링크와 같음).

### 5.6 그리드 규칙 (hb.grid 시각)

- 헤더 = 행 높이, 배경 `--hb-bg-subtle`, 글자 600, 정렬 표시는 ▲▼ 모양 + `aria-sort`(색만 X).
- 행 구분선 `--hb-border-subtle`, 열 구분선은 더 옅게 또는 없음(compact 에서만 표시) — 얼룩말 줄무늬 없음, hover `--hb-bg-muted`.
- **선택 행**: `--hb-action-subtle` 배경 + **왼쪽 3px `--hb-action` 막대**(첫 열 `box-shadow: inset 3px 0 0`) → 1.4.11 충족. 포커스 셀은 2px `--hb-focus` 안쪽 테두리.
- 숫자 tnum·오른쪽, 음수는 `−` 기호 + `--hb-status-danger-fg`(색 + 기호), 0 은 흐린 글자 옵션.
- 단위는 헤더에 `수량(EA)`, 셀에는 숫자만.
- 빈 상태·오류 오버레이는 아이콘 + 문장 + (가능하면) 다음 행동 버튼.
- 고정 열 경계에 2px 구분선(스크롤 중 경계 인지) [추정].
- 그리드 안 입력(편집 그리드, P1 PoC)은 셀 테두리 `--hb-border-control` 로 "편집 가능"을 보이고, 읽기 전용 셀과 배경 차이 + 커서 모양.

### 5.7 폼·버튼·대화상자 규칙

**폼**
- 입력 테두리 `--hb-border-control`(3:1), 배경은 항상 `--hb-bg-surface`(흰) — 회색 영역(조회 조건) 위에서도 흰 입력이라 대비 유지(4.2 의 2.91 문제 회피).
- 라벨 위·`--hb-text-muted`·12~13px, **필수 표시 = 라벨 뒤 `*` + `aria-required`**(빨간 별만으로 의미 전달 금지 → 화면 상단 "* 필수" 안내 1회).
- 읽기 전용 = 배경 `--hb-bg-subtle` + 테두리 없음(비활성과 구분: 비활성은 글자 흐림 + 커서 not-allowed).
- 오류 = 테두리 2px danger + 아이콘 + 필드 아래 글자(현행 `aria-invalid` 연한 빨강 배경 유지 가능).

**버튼 — 위계 4종만**
| 종류 | 모양 | 규칙 |
|---|---|---|
| primary | `--hb-action` 배경·흰 글자 | **한 영역에 1개**(저장·확정). Tulip "주버튼이 화면에서 가장 진한 색" [검색 T1] |
| secondary | 흰 배경·`--hb-border-control` 테두리·본문 글자 | 신규·취소·조회 조건 보조 |
| danger | 흰 배경·danger 테두리·danger 글자 (확인 대화상자 안에서만 빨강 배경) | 사용 중지·삭제. 현행 빨강 꽉 찬 버튼은 툴바에서 너무 강함 [추정] |
| ghost | 배경 없음·hover 시 `--hb-bg-muted` | 그리드 툴바 아이콘, 닫기 |
- 아이콘 버튼은 반드시 `aria-label` + 툴팁(title), 타깃 24px 이상(2.5.8).
- 처리 중 = 버튼 안 스피너 + 글자 유지 + `aria-busy`, 비활성(권한·조건) 과 모양 구분.
- 순서: 오른쪽 끝이 주버튼(현행 `hb-dialog-bar` 오른쪽 정렬과 일치), 위험 버튼은 주버튼과 떨어뜨림.

**대화상자**
- `<dialog>` 유지(현행), 폭 3단(sm 400 / md 560 / lg 800px), 모서리 8px, `--hb-shadow-2`, 배경막 `rgb(15 23 42 / .4)`.
- 제목 = 무엇을 하는지(동사), 본문 = 결과·되돌릴 수 있는지, 버튼 = 동사("사용 중지", "취소") — "확인/예" 지양.
- 열 때 첫 입력(또는 안전한 버튼)에 포커스, 닫으면 연 버튼으로 복귀, Esc 닫기 — 위험 확인 대화상자는 기본 포커스를 **취소**에.

### 5.8 셸·내비게이션

- 머리띠 남색(`--hb-brand-header`) 유지, 높이 44px(현행). 회사·사용자·(P2) 공장 표시 — **다른 회사로 착각 방지가 멀티테넌트 시각 요구사항**: 회사명을 머리띠에 항상 표시, 설치형/운영 지원 세션(05)은 머리띠 색을 바꿔 구분(예: 지원 세션 = 주황 띠 + "지원 세션" 글자) [추정].
- 메뉴 hover·현재 화면 = 배경 + 왼쪽 막대(색만 X), 포커스 `outline` 제거 금지(현행 `outline: none` → `:focus-visible` 링으로 교체).
- 탭: 활성 탭 = 흰 배경 + 위쪽 2px `--hb-action` 선.

### 5.9 현장 화면(POP·안돈·대시보드)

- `data-surface="pop"`: 배경 `#e3e6ea`(밝은 회색, 흰색 아님 — 반사·눈부심 완화 [검색 H5]), 카드 흰색, 글자 `#1b1f24`(13.2:1 [계산]), 밀도 pop(5.3), 하단 확정 바 고정(11 `confirmBar`), 결과 피드백은 **전체 화면 테두리 색 + 아이콘 + 글자 + 소리**(성공 ✓ 짧은 소리, 오류 ✕ 긴 소리) — 소음 현장 진동·소리 병행(11 2.3).
- `data-surface="andon"`: 라이트 기본, 어두운 공간이면 다크 옵션. 라인당 숫자 2~3개, 정상은 회색 글자, 목표 이탈만 경고·알람색 + 모양. 글자 크기 = 거리 공식(2.2). 깜빡임은 미확인 알람만 ≤2Hz, `prefers-reduced-motion` 이면 깜빡임 대신 굵은 테두리.
- 설비 상태(CMS): 가동 = 회색 + "가동" 글자, 계획 정지 = 회색 + "계획 정지", **비계획 정지·고장만 알람색** — "가동 = 초록" 금지(2.1).
- 측정값(SPC·설비): 숫자만 보여 주지 말고 정상 범위 띠 + 현재값(1.5).
- POP 화면 표시 규격은 **토큰 하나로** — 화면마다 여백·버튼 크기를 따로 정하지 않는다(1.8 국내 사례의 정돈 비용 회피).

### 5.10 다크 모드

- **P0~P1 사무 화면: 하지 않는다**(토큰 의미층만 준비). 근거: 사무실·현장 조명은 밝음 → 라이트가 가독성 우위 [검색 D1], 1인 개발 시험 범위 2배.
- **P2 안돈·P5 관제 대시보드: 다크 옵션 검토**(어두운 공간·야간 대형 화면). 알람색 의미는 같게, 값은 다크용 별도 [검색 H5].
- `prefers-color-scheme` 자동 전환은 하지 않는다 — 공용 단말·현장에서 OS 설정이 화면을 바꾸면 혼란 [추정].
- Windows 고대비(`forced-colors: active`): 테두리·포커스가 시스템 색으로 보이도록 **배경색만으로 경계를 만들지 않는다**(입력·버튼·선택 행에 실제 border/outline) [추정].

### 5.11 아이콘

- **Lucide(ISC)** 에서 필요한 30~60개만 골라 SVG 스프라이트 1개 + LICENSE, `static/vendor/lucide/{ver}/`. 선 굵기 1.75~2px, 크기 16(compact)·20(normal)·28+(pop).
- 아이콘은 **글자와 함께**가 기본, 아이콘 단독은 툴바(닫기·새로고침·엑셀 등 관례 아이콘)만 + `aria-label`.
- 상태 모양(◆▲■●✓✕i)은 자체 SVG(라이선스 문제 없음).
- 아이콘 폰트 방식·CDN·상용 세트 쓰지 않음.

### 5.12 접근성·시각 체크리스트 (요약 — 9.4 에 체크리스트 원문)

대비(글자 4.5, 큰 글자·컨트롤 경계·상태·포커스 3) / 색만 금지 / 포커스 보임·가림 없음 / 타깃 24px(사무)·정책값(POP) / tnum / 200% 확대 / 흑백 인쇄·색각 시뮬레이션 / 깜빡임 ≤2Hz·reduced-motion / forced-colors 경계 / AI 값 표시.

### 5.13 CSP 와의 관계 (현행 규칙 유지)

- 색·크기·테마는 **클래스·data 속성 선택자**로만, 인라인 `style=` 금지 유지.
- JS 가 동적으로 바꾸는 것은 **폭·위치(CSSOM)** 와 `dataset`(테마·밀도·톤) 뿐. 상태 톤은 `el.dataset.tone = 'danger'` 처럼.
- 사용자별 색 지정(그리드 셀 조건부 색 등) 요구가 와도 **임의 색 값이 아니라 톤 이름**만 허용 → CSS 규칙으로 해석. 임의 색을 CSSOM `setProperty` 로 넣는 길도 열지 않는다(토큰 규칙 붕괴) [추정].

### 5.14 시점별 작업

| 시점 | 작업 | 비용 |
|---|---|---|
| **지금(P0 9단계 전)** | tokens.css(라이트 + 밀도 compact/normal) · base.css 분리 · page/shell/login/hb.grid 를 의미 토큰으로 교체 · 입력 테두리·포커스·선택 행 막대 수정 · 원시 색 금지 가드 테스트 · 체크리스트 9.4 | 중(1~2일) |
| P0 9단계 | Pretendard vendor + tnum · Lucide 스프라이트(셸·그리드·툴바 아이콘) · `hb-status` 배지 · 운영 지원 세션 머리띠 | 중 |
| P1 | 사용자 밀도 설정 저장(사용자 설정 테이블 또는 `sys_grid_state` 옆) · 공통코드 표시 톤 · hb.grid `status` 컬럼 · 편집 그리드(PoC 결과) 시각을 토큰에 맞추기 | 하~중 |
| P2 | `pop.css`(surface=pop, 터치 정책값) · 알람 4단계·모양 · 안돈 거리 공식 · 다크 안돈 옵션 검토 · D2Coding(LOT·바코드 문자열) | 중 |
| P3 | 차트 색(Okabe-Ito)·SPC 정상 범위 띠 — 10 H7 차트 조사와 함께 | 중 |
| P5 | AI 표시 `hb-ai` 구현 · 고객 브랜딩(머리띠 색·로고만) · 토큰 JSON(DTCG) 이전 여부 | 중 |

---

## 6. 위험과 대응

| 위험 | 대응 |
|---|---|
| 토큰 교체 중 화면 깨짐 | 거래처관리·내 정보·로그인 3화면뿐 — 교체 전후 스크린샷 비교(Playwright 이미 있으면 그대로) |
| Pretendard 조각 수·용량이 vendor 커밋을 키움 | 가변 dynamic subset 한 벌만, static·otf 제외. 용량은 릴리스에서 확인 후 결정(V2) |
| 맑은 고딕 → Pretendard 로 바뀌면 사용자가 "글자가 작아졌다"고 느낌 | Pretendard 는 같은 크기에서 작아 보임(KRDS 근거) → compact 13 / normal 14 로 시작하고 피드백 버튼(8단계)으로 확인 |
| 상태 "neutral 대부분" 원칙에 고객이 "색이 없어 밋밋하다" | 규칙 근거(ISA-101·색약 6.5%)를 도움말·제안서에 설명. 강조는 지연·반려 등 행동이 필요한 상태에만 — 고객 요청 시 톤 매핑(공통코드)만 바꾸면 됨 |
| 고객이 자사 브랜드 색을 원함 | P5 에 **머리띠 색·로고만** 허용, `--hb-action` 은 대비 검사(4.5:1) 통과 값만 |
| CSS mm 단위 오해로 POP 버튼이 실제보다 작음 | 2.3 — 단말 기종별 실측 후 px 정책값 |

---

## 7. 무엇을 하지 않는가

| 하지 않을 것 | 이유 |
|---|---|
| 사무 화면 다크 모드 P0 구현 | 밝은 환경에서 라이트 우위 [검색 D1], 시험 2배. 토큰만 준비 |
| 사용자 임의 색 테마·색 편집기 | 토큰·대비 규칙 붕괴, CSP 상 임의 값 주입 경로 |
| 그라데이션·3D·장식 그림자·사진 배경 | ISA-101·Rockwell [검색 H3] |
| 행 전체 배경을 상태색으로 | 색만 전달·선택 표시와 충돌 |
| "가동 = 초록 / 정지 = 빨강" 신호등 대시보드 | ISA-101 정면 위반, 계획 정지까지 빨강 → 진짜 이상이 묻힘 |
| 아이콘만 있는 버튼(라벨·툴팁 없이) | 1.1.1·현장 학습 비용 |
| CDN 글꼴·아이콘 | CSP `font-src 'self'`, 설치형 폐쇄망 |
| 맑은 고딕 파일 배포 | 라이선스 [검색 F3] |
| Pretendard 를 직접 서브셋해 같은 이름으로 | OFL 예약명 조항 [검색 F1] |
| 상용 아이콘(Font Awesome Pro 등)·아이콘 폰트 방식 | 라이선스·접근성 |
| CSS 프레임워크(Tailwind·Bootstrap) 도입 | 빌드 도구·클래스 폭증, 지금 CSS 는 200줄 미만 — 토큰만으로 충분 [추정] |
| 애니메이션 게이지·3D 차트 | 정보량 대비 주의 분산 |
| AI 색을 장식·브랜드 강조에 | Carbon for AI 명시 금지 [검색 G1] |
| `prefers-color-scheme` 자동 전환 | 공용 단말 혼란 |

---

## 8. 원래 세션에 넘길 결정

| # | 결정 | 선택지 | 권고 |
|---|---|---|---|
| V1 | 토큰 도입 시점·범위 | 지금(9단계 전) / P1 / 필요할 때 | **지금** — 화면 3개일 때 1~2일. 원시 색 금지 가드 포함 |
| V2 | 기본 글꼴 | **Pretendard(vendor)** / Noto Sans KR / 시스템 글꼴 유지 | Pretendard 1.3.9 가변 dynamic subset, 수정 없이. vendor 용량 확인 후 커밋 |
| V3 | 다크 모드 | P0 구현 / **토큰만 준비, 안돈 P2 에서 검토** / 안 함 | 토큰만 준비 |
| V4 | 사무 기본 밀도·글자 크기 | **compact 13px** / normal 14px | compact 13px 기본 + 사용자 설정 normal. 거래처관리 피드백으로 재확인 |
| V5 | 업무 상태 색 결정 위치 | 화면 JS / **공통코드 "표시 톤" 속성** / 별도 테이블 | 공통코드 속성(새 V 파일로 컬럼 추가 — 적용된 V 수정 금지) |
| V6 | 현장 알람 색 체계 | 흔한 매핑(P4 파랑) / **P4 보라(Rockwell 식)** | 보라 — 파랑은 브랜드·정보와 겹침. 모양 ◆▲■● 함께. 고객 알람 철학이 있으면 그것을 정책으로 |
| V7 | 아이콘 세트 | **Lucide(ISC)** / Tabler(MIT) / Material Symbols(Apache) | Lucide 스프라이트(필요분만) |
| V8 | AI 표시 규칙 문서화 시점 | **지금 01·03 에 원칙만** / P5 | 지금 원칙만(라벨·근거·미확정 스타일·확정 이력) |
| V9 | 화면 체크리스트 "시각·접근성" 절(9.4) | 전부 / 일부 | 거래처관리 토큰 교체 때 시범 적용 후 확정 |
| V10 | POP 터치 크기 단위 | CSS mm(11 제안) / **px + 단말별 실측 정책** | px + 실측(2.3) — 11 D·2.3 토큰 문구 수정 필요 |
| V11 | 운영 지원 세션 시각 구분(05) | 없음 / **머리띠 색 + 글자** | 머리띠 색 + "지원 세션: 회사명" — 05 지원 세션 설계와 함께 |
| V12 | 고객 브랜딩 범위 | 없음 / **머리띠·로고만(P5)** / 주색까지 | 머리띠·로고만. 주색 변경은 대비 검사 통과 시만 |

---

## 9. 참고문헌

### 디자인 시스템 [I·S·C·M·L·A·P·K·T]
- [I1] Siemens iX, Colors — https://ix.siemens.io/docs/styles/colors (2026-10-15 열람) [원문]
- [I2] Siemens iX, Theming(developers/designers), v2.7 Themes — https://ix.siemens.io/docs/styles/theming/usage-developers , https://ix.siemens.io/version-2.7/docs/guidelines/theme/ ; WinCC OA iX 토큰 — https://www.winccoa.com/documentation/WinCCOA/latest/en_US/Dashboard/widget_generation/knowledge/ix-colors_2.html [검색]
- [S1] SAP Fiori, Content Density (Cozy and Compact) — https://www.sap.com/design-system/fiori-design-web/v1-136/foundations/visual/cozy-compact ; SAPUI5 content densities — https://github.com/SAP-docs/sapui5/blob/main/docs/04_Essentials/content-densities-e54f729.md [검색]
- [S2] SAP Fiori, Using Semantic and Industry-Specific Colors — https://www.sap.com/design-system/fiori-design-web/v1-84/foundations/best-practices/ui-elements/how-to-use-semantic-colors ; Fiori elements Status Colors and Icons — https://github.com/SAP-docs/sapui5/blob/main/docs/06_SAP_Fiori_Elements/status-colors-and-icons-1641180.md ; SAP Community "status icons and semantic colors" [검색]
- [C1] IBM Carbon, Data table style — https://carbondesignsystem.com/components/data-table/style/ [검색]
- [M1] Fluent UI token pipeline naming — https://microsoft.github.io/fluentui-token-pipeline/naming.html ; Fluent UI style reference — https://microsoft.github.io/fluentui-design-tokens/ ; fluentui-apple/android Design Tokens wiki [검색]
- [L1] SLDS 2, Display Density — https://www.lightningdesignsystem.com/2e1ef8501/p/805bbe ; Global Styling Hooks ; Salesforce Help "Cosmos Theme and SLDS 2 Availability" [검색]
- [A1] Atlassian, Spacing — https://atlassian.design/foundations/spacing ; Confluence Compact Mode 커뮤니티 글 [검색]
- [P1] GitHub Primer, primitives DESIGN_TOKENS_GUIDE — https://github.com/primer/primitives/blob/main/DESIGN_TOKENS_GUIDE.md ; Color usage — https://primer.style/product/getting-started/foundations/color-usage/ ; DataTable — https://primer.style/components/data-table [검색]
- [K1] KRDS 디자인 토큰 — https://www.krds.go.kr/html/site/style/style_07.html ; 디자인 스타일 — https://www.krds.go.kr/html/site/style/style_01.html ; 위키백과 "프리텐다드 거브" [검색]
- [K2] 더존 Amaranth 10 — https://www.douzone.com/product/amaranth10.jsp , 이노베이션랩 사례 https://innovationlab.co.kr/project/amaranth10_4/ , 요즘IT 리뷰 ; 영림원 K-System Ace — https://www.ksystem.co.kr/k-system-ace/ , 공공 제안서 PDF [검색]
- [K3] 국내 MES/POP 공급사 페이지(팩앤롤 http://smart.facnroll.co.kr/product/mes.php , 엠아이큐브 https://www.micube.co.kr/smartfactory , JNKC https://www.jnkc.kr/m/solution/mes.php ), CREFLEINC omf-mes-client 이슈 #829·#1332 (2026) [검색]
- [T1] Tulip, App design best practices — https://support.tulip.co/docs/app-design-best-practices ; UI template — https://support.tulip.co/docs/design-template ; Community UI/UX tips [검색]
- [O1] Siemens Opcenter X 2507·2607 What's new — https://blogs.sw.siemens.com/opcenter/whats-new-in-opcenter-x-2607/ [검색]

### 현장·HMI [H]
- [H1] ISA-101 개요·원칙 정리(HMI Library https://hmilibrary.com/standards/isa-101 , itotconvergentie https://itotconvergentie.nl/en/isa-101 , plcprogramming.io 체크리스트) [검색]
- [H2] Adroit ISA 101 High Performance HMI — https://adroit-europe.com/hphmi [검색]
- [H3] Rockwell Automation Process HMI Style Guide (PROCES-WP023) — https://literature.rockwellautomation.com/idc/groups/literature/documents/wp/proces-wp023_-en-p.pdf ; PlantPAx Display and Library Guidelines (PROCES-RM200) [검색 — PDF 본문 미열람]
- [H4] AVEVA Situational Awareness — https://www.aveva.com/en/solutions/operations/situational-awareness/ ; AVEVA Community SAL 색 문의 [검색]
- [H5] Center for Operator Performance, Background Color for HMI — https://centerforoperatorperformance.org/projects/background-color-hmi [검색]
- [H6] 거리별 글자 높이: Spectra Displays https://spectra-displays.co.uk/help-and-support/technical-info/text-height-and-viewing-distance/ , ISO 9241-3·MIL-STD-1472F 시각도 2차 인용, AV-INFO projection rules [검색 — 표준 원문 미열람]

### 접근성·색각 [W·A]
- [W1] WCAG 2.2 SC 2.5.8 Target Size (Minimum) 해설 — https://wcag22aa.org/new-criteria/target-size/ , TetraLogical "What's new in WCAG 2.2" [검색]
- [W2] WCAG SC 1.4.11 Non-text Contrast 해설 — https://www.digitala11y.com/understanding-sc-1-4-11-non-text-contrast/ [검색]
- [W3] Design Tokens Community Group 2025.10 첫 안정판 — https://www.designtokens.org/ , https://lists.w3.org/Archives/Public/public-design-tokens/2025Oct/0003.html , FAQ [검색]
- [A11] Prevalence of Color Vision Deficiency in an Adult Population in South Korea (Optom Vis Sci 2019, KNHANES 2013) — https://pubmed.ncbi.nlm.nih.gov/31688694 [검색]
- [A12] Okabe-Ito 팔레트 해설 — https://sciencediagram.com/en/blog/okabe-ito-color-palette-hex-codes ; Wong, Nature Methods 2011 2차 인용 [검색]
- [D1] Dark vs light mode & ambient lighting (Hazadi & Kamaruzaman 2024 등 2차 요약), Ergonomics 2025 "The dark side of the interface" — https://www.tandfonline.com/doi/full/10.1080/00140139.2025.2483451 [검색]

### AI 표시 [G]
- [G1] IBM Carbon for AI — https://carbondesignsystem.com/guidelines/carbon-for-ai/ ; AI label — https://carbondesignsystem.com/components/ai-label/usage/ [검색]
- [G2] SAP Fiori Local AI Notice — https://www.sap.com/design-system/fiori-design-web/v1-136/ui-elements/local-ai-notice/usage ; Joule AI Notice ; What's New 1.122·1.126 ; Explainable AI [검색]

### 글꼴·아이콘 [F·N]
- [F1] Pretendard LICENSE — https://github.com/orioncactus/pretendard/blob/main/LICENSE ; README(en) ; 자체 서빙 PR 사례(TravelGuide-v2 #3) [검색]
- [F2] Pretendard Discussion #110 고정폭 숫자 — https://github.com/orioncactus/pretendard/discussions/110 ; MDN font-variant-numeric [검색]
- [F3] 맑은 고딕 라이선스(Microsoft Q&A, 한경 2016, 삼성SDS Enterprise UX 글꼴), Noto Sans KR(Fontsource·KERIS), 나눔고딕(공공누리) [검색]
- [F4] Pretendard Releases (v1.3.9 최신, 1.3.6 가변 dynamic subset, 1.3.7 GOV 추가·고정폭 숫자 콜론 수정) — https://github.com/orioncactus/pretendard/releases (2026-10-15 열람) [원문]
- [F5] IBM Plex Sans KR (Fontsource) ; NAVER D2Coding OFL — https://github.com/naver/d2codingfont/wiki/Open-Font-License [검색]
- [N1] Lucide (ISC) — https://github.com/lucide-icons/lucide ; Iconify 세트 목록(Tabler MIT, Material Symbols Apache-2.0) — https://icon-sets.iconify.design/ [검색]

### 내부 문서
- 저장소 `CLAUDE.md`, `.claude/skills/smarthappy/SKILL.md`, docs/09·10(그리드)·11(사용성), `static/css/page.css`·`shell.css`·`login.css`, `templates/master/cust-mgt.html`, `SecurityConfig` CSP 문자열 (커밋 `b583396`).

### 확인하지 못한 것
- ISA-101 표준 본문(유료), Rockwell PROCES-RM200 의 알람 아이콘 모양, AVEVA SAL 정상 범위 색 — 2차 자료만.
- Fiori Horizon 의 표 행 높이 정확값(2차 자료의 24/32/48px).
- Pretendard 1.3.9 dynamic subset 의 조각 수·총 용량, tnum 글리프 포함 여부, 베트남어 성조 지원 — 적용 시 실측.
- Siemens iX 상태색 hex 값(페이지에 값 없음, npm 패키지 CSS 에 있음).
- 국내 클라우드 MES 실제 화면(공개 자료 적음).
- WCAG 1.4.10 데이터 표 예외의 정확한 문구(Understanding 문서 재확인).

---

## 9.4 부록 — 화면 체크리스트 "시각·접근성" 절 (skill `checklists.md` §2 추가 제안)

- [ ] 색·크기 값을 직접 쓰지 않고 **의미 토큰**만 썼다(가드 테스트 통과).
- [ ] 글자 대비 4.5:1(큰 글자 3:1), **입력 경계·선택·체크·포커스·상태 아이콘 3:1** 이상이다.
- [ ] 상태는 **아이콘 + 글자**로 말하고 색은 보조다. 흑백으로 출력해도 구분된다. 색각 시뮬레이션(deuteranopia)으로 봤다.
- [ ] 업무 상태 대부분은 neutral 톤이고, 색은 행동이 필요한 상태(반려·지연·오류)에만 썼다. "정상 = 초록"을 쓰지 않았다.
- [ ] 주버튼은 영역당 1개다. 위험 버튼은 주버튼과 떨어져 있고 확인 대화상자의 기본 포커스는 취소다.
- [ ] 모든 포커스 가능한 요소에 `:focus-visible` 링이 보이고, sticky 헤더·하단 바에 가리지 않는다.
- [ ] 숫자 열은 tnum·오른쪽 정렬, 단위는 헤더에 있다.
- [ ] 타깃 24×24px 이상(사무), POP 은 정책값 이상이다.
- [ ] 200% 확대에서 셸·폼이 깨지지 않는다(그리드는 가로 스크롤 허용).
- [ ] 깜빡임은 미확인 알람만, 초당 2회 이하, `prefers-reduced-motion` 대안이 있다.
- [ ] AI 가 만든 값에는 `hb-ai` 라벨·근거·미확정 표시가 있다(해당 시).
- [ ] 밀도(compact/normal)를 바꿔도 화면이 깨지지 않는다.

---

## 10. 검토 결과 (원래 세션이 작성)


검토일 2026-10-16. 진단(4장: hex 25종, 입력 테두리 대비 1.47:1, Windows 전용 글꼴)을 확인했다. 9단계에서 화면이 5개(거래처·지원 세션·활용 현황·운영자 로그인·콘솔)로 늘었으므로, 토큰 도입은 **P1 첫 업무 화면 전에 하는 별도 단계(P0 마무리 다음)** 로 잡는다. 포트폴리오에서 화면 인상은 첫 몇 초에 결정되므로 우선순위를 높인다.

| # | 판정 | 반영 |
|---|---|---|
| V1 토큰 시점 | **채택 — P1 첫 화면 전** | `tokens.css` 3단 + 기존 CSS 교체 + **원시 색 금지 가드 테스트**(TemplateSecurityGuardTest 방식). 편집 그리드 PoC CSS 도 같이 |
| V2 Pretendard | **채택** | 1.3.9 dynamic subset, 수정 없이 `vendor/pretendard/1.3.9/` + OFL + SHA256SUMS(VendorChecksumGuardTest 대상). 용량 확인 후 커밋 |
| V3 다크 모드 | 채택(토큰만) | 안돈은 P2 에서 검토 |
| V4 밀도 | **채택** | compact 13px 기본 + normal. 사용자 저장은 P1 |
| V5 상태 색 위치 | **채택** | 공통코드 "표시 톤" 속성 — 새 V 파일로 컬럼 추가 |
| V6 알람 색 | 채택(P2) | P4 보라 + 모양 |
| V7 아이콘 | **채택** | Lucide 스프라이트(필요분만, ISC) |
| V8 AI 표시 원칙 | 채택 | 01·03 에 원칙만(라벨·근거·미확정·확정 이력) — 토큰 단계 때 문서 반영 |
| V9 시각·접근성 체크리스트 | 채택(시범) | 거래처관리 토큰 교체 때 시범 적용 후 skill `checklists.md` §2 에 확정 |
| V10 POP 터치 단위 | 채택 | px + 단말 실측 정책. 11 문구는 P2 POP 설계 때 수정 |
| V11 지원 세션 머리띠 | **보류** | 9단계 지원 세션은 운영자가 고객 화면에 들어가는 방식이 아니라 콘솔에서 허락 범위의 자료만 읽는 방식이라 머리띠를 붙일 화면이 없다. 운영자가 고객 화면을 대신 보는 기능이 생기면 그때 |
| V12 고객 브랜딩 | 보류(P5) | 판매하지 않으므로 우선순위 낮음 |

"하지 않을 것"(0.3)은 그대로 채택한다 — 특히 Tailwind·Bootstrap 미도입, CDN 글꼴·아이콘 금지(CSP `font-src 'self'`), "정상 = 초록" 금지.

### 10.1 로그인 화면 (2026-10-16, 사용자 요청 "로그인 화면이 단출하다")

**레퍼런스 조사 요약**
- 업무 SaaS 로그인의 주류는 **분할 배치**(폼 + 브랜드·가치 패널)다. 패널은 제품 가치·그림·제품 미리보기를 담되, 폼에서 주의를 빼앗지 않게 하고, 좁은 화면에서는 접거나 없앤다 [eleken 로그인 예시 50+, authgear 2025 가이드].
- 제조 SaaS: **Plex** 는 회사 코드 + 사용자 ID + 비밀번호 3칸이고, 계정 문제는 고객사 보안 관리자에게 넘긴다. **MachineMetrics** 는 사무 웹과 작업자 태블릿 로그인을 나누고 단말 승인을 둔다. **Tulip** 은 회사별 URL 로 먼저 보낸다. → YeonOS 의 회사코드 로그인·관리자 문의·POP 단말 분리(11 D4)와 같은 방향.
- 접근성: 라벨 유지(placeholder 만 쓰지 않기), 대비 4.5:1·컨트롤 3:1, 포커스 표시, `autocomplete`, 붙여넣기 허용(WCAG 2.2 3.3.8), 장식 그림은 `alt=""`, 폼을 DOM 앞에.
- 서버 상태 표시가 로그인 이탈을 줄인 사례가 있다 → 9단계 `status.json` 을 로그인에 연결.
- 출처: https://www.eleken.co/blog-posts/login-page-examples · https://www.authgear.com/post/login-signup-ux-guide/ · https://docs.plex.com/main/en-us/about/applications.htm · https://docs.machinemetrics.com/docs/getting-started/accessing-machinemetrics-and-support/ · https://tulip.co/login/

**적용**
| 항목 | 내용 |
|---|---|
| 배치 | 왼쪽 브랜드 패널(짙은 남색, 단색 — 그라데이션 장식 없음) + 오른쪽 흰 폼. 900px 미만은 패널이 머리띠로 줄고 그림·설명은 숨김 |
| 그림 | **자체 제작 SVG** `images/yeonos-platform.svg` — 連(잇다): 아래 플랫폼 계층(회사별 격리·권한·감사·원장·아웃박스)이 받치고, MES·QMS·WMS·SRM·PMS·CMS 가 가운데 같은 키(품목·LOT·설비·작업지시)와 서로를 여러 방향으로 잇는다. 위에 같은 데이터를 보는 사무 화면·현장 단말(2026-10-20 개편 — 이전 `yeonos-line.svg` 의 한 줄 라인 그림은 실제 구조와 달라 교체). 그림 속 글자도 화면 언어를 따른다: 영어 화면은 `yeonos-platform-en.svg`(배치 같고 글자만 영어). `<img>` 안의 SVG `systemLanguage` 는 브라우저 언어만 보고 사용자 `locale_cd` 를 모르므로 쓰지 않고, 템플릿이 `#locale` 로 파일을 고른다. 스톡 이미지는 라이선스·CSP 문제로 쓰지 않음. SVG 도 앱 CSP 를 받으므로 스타일·스크립트 요소 없이 표현 속성만(가드 테스트) |
| 마크·파비콘 | `images/yeonos-mark.svg` — 세 점을 잇는 선 |
| 폼 | 입력 높이 44px, 테두리 `#8a929c`(3.15:1), 포커스 2px 링, 라벨 굵게, Caps Lock 안내, **회사코드 기억(선택, 이 기기 localStorage, 형식이 틀린 값은 무시)** |
| 안내 | 장애·점검 공지(`/status.json`, 글자로만 표시), "계정 문제는 회사 관리자에게"(Plex 방식 — 셀프 비밀번호 재설정은 메일 발송 기능이 생기는 P5 이후 검토), 하단에 "가상 데이터·포트폴리오" 표기 |
| 하지 않은 것 | 배경 사진·동영상, 애니메이션(움직임 줄이기 설정 대응 부담), 로그인 화면 마케팅 문구 과다, 외부 글꼴 CDN |

토큰 단계(V1)에서 `login.css` 의 `--lg-*` 변수는 공통 의미 토큰으로 옮긴다.

### 10.2 V1 토큰 적용 (2026-10-16)

V1(토큰)·V4(밀도 compact 기본)·V5 일부를 이번 단계에서 적용한다. `tokens.css`(원시 → 의미 → 컴포넌트 + docs/15 레이아웃 층), `page.css`·`shell.css`·`login.css` 의 직접 색 값을 의미 토큰으로 교체, **원시 색 금지 가드 테스트**(tokens.css 밖 `#hex`·`rgb(`·`hsl(`·`var(--hb-p-` 금지, dev PoC CSS 는 local 전용이라 예외). Pretendard(V2)·Lucide(V7)는 외부 파일을 받아 vendor 에 들여야 해서 다음 단계로 미룬다 — 글꼴 스택 첫 자리는 비워 두지 않고 지금처럼 맑은 고딕부터.

### 10.3 UI 디자인 단계 1 공통 부품 적용 (2026-10-22, docs/40 V-1~V-7, docs/29 #44)

- V2 Pretendard 1.3.9 dynamic subset vendor(`tokens.css` 가 @import, 폰트 스택 맨 앞) — 숫자 열은 `tabular-nums`(`.hb-num` 추가).
- V7 Lucide 1.50.0 원본 아이콘 48개 vendor + `static/css/icons.css`(CSS mask, 색 = 글자색 — 인라인 SVG·스크립트 없음).
- 5.5 `hb-status` 공통 배지(톤 × 모양 × 글자) + `--hb-status-success-line`·알람 4단 토큰 — 상태 의미 사전은 docs/40 부록 A.
- 5.8 셸: 메뉴 지금 화면 = 배경 + 왼쪽 3px 막대 + `aria-current`(탭 전환 연동), 활성 탭 위 2px 선, 메뉴 포커스는 `:focus-visible` 링(`outline: none` 제거).
- 5.7 대화상자: `hb.dialog.confirm/prompt`(숫자는 hb.numbers 검증) — 기존 confirm/prompt 교체는 모듈이 화면을 고칠 때(V-7 점진).
- 5.9 "가동 = 초록 금지"는 **현장 화면에만** 적용(V-1 화면별) — 사무 화면 타임라인·홈은 초록 유지.
