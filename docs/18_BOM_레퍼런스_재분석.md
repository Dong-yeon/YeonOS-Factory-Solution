# 18. BOM 레퍼런스 재분석과 설계 제안 (P1 3단계 전)

작성 2026-10-16 (원래 세션). 사용자 지시: "BOM 은 레퍼런스를 다시 찾아 재분석한 뒤 진행 — 정전개·역전개 등 기능을 모두 넣는 완벽한 BOM".
이 문서는 **조사 → 기능 목록 → 데이터 모델 → 알고리즘 → 화면 → 결정 요청** 순서다. 결정(8장)이 확정되면 docs/17 에 3단계 상세로 옮기고 구현한다.
표기: [원문] 제품 공식 문서 확인 · [검색] 검색 요약(2차) · [추정] 이 문서의 판단 · [내부] 기존 docs·고객사 경험.

---

## 1. 결론 먼저

1. **"완벽한 BOM" = 구조(리비전·유효기간·대체) + 전개 엔진(정전개 3종·역전개 2종·비교) + 변경 관리(새 리비전·일괄 대체·순환 금지)**. 원가 집계·MRP·옵션(구성형) BOM 은 BOM 기능이 아니라 그것을 쓰는 다른 모듈의 일 — 이번 범위 밖(7장).
2. 레퍼런스가 모두 같은 답을 주는 핵심 규칙 4개:
   - **유효한 BOM 은 "날짜 + 상태 + 대체 번호"로 고른다** (SAP·D365 공통) [원문]
   - **확정된 버전은 고치지 않고 새 버전을 만든다**, 같은 대상의 활성 버전은 **기간이 겹치면 안 된다** (D365·AX) [원문]
   - **전개는 단계별·다단·합산 세 가지**, 다단에서는 같은 부품이 여러 번 나오면 여러 번 보인다 (SAP) [원문]
   - **역전개(where-used)는 자동으로 만들어지는 조회**이며 레벨 열을 보여 준다 (SAP·AX) [원문]
3. MES 관점에서 ERP 와 다른 점: **투입 방식(백플러시/수동)·공정 연결·대체 자재 검증**이 BOM 에 있어야 P2 실적·LOT 계보가 돌아간다 (Oracle supply type, Infor MES alternative materials) [원문].
4. 구현은 PostgreSQL 14+ 의 **재귀 CTE `SEARCH DEPTH FIRST` + `CYCLE`** 로 한 쿼리 전개 — 순환은 저장 때 막고, 조회 때도 한 번 더 감지 [원문].
5. 3단계를 **3a 구조·리비전 / 3b 전개 엔진·조회 / 3c 변경 도구(비교·복사·일괄 대체)** 로 나눠 커밋한다.

---

## 2. 레퍼런스별 요약

| 출처 | 배울 점 | 근거 |
|---|---|---|
| **SAP S/4HANA · ECC PP** | 전개 = 단계별·다단·합산. 유효 BOM 결정 순서: **용도(usage) 우선순위 → 전개일에 유효 → 상태 확인 → 대체 BOM 선택**. 다단 목록에서 중복 부품은 중복 표시. 역전개 다단은 레벨 열. 항목 유형 L(재고)·N(비재고)·T(텍스트)·R(가변 크기), 벌크 자재 표시, 가상 조립(phantom). **대체 항목 그룹 + 우선순위 + 전략 + 사용 확률(%)**. 부품 스크랩(%)은 BOM 행 값이 품목 값보다 우선, 조립 스크랩·공정 스크랩 별도. 변경 유효성은 날짜 또는 시리얼 구간 | [S1][S2][S3][S4][S5][S6] |
| **Dynamics 365 SCM / AX 2012** | BOM **버전**: 승인 → 활성화. 활성화 때 기간·사이트·수량 제약이 기존 활성 버전과 **겹치면 오류**. 계획은 유효기간·승인·수량으로 버전을 고른다. 하위 조립의 유효일은 상위 지시의 공정 일자. 변경 건(product change case)으로 여러 BOM 을 한 날짜에 함께 활성화. Where-used 는 자동 생성, 수정 불가 | [D1][D2][D3][D4] |
| **Oracle EBS / PeopleSoft** | 리비전 기준 또는 날짜 기준 유지 방식 선택. **BOM 비교 보고서**(주·대체·리비전·설계/생산 BOM 간 — 같은 행은 한 줄, 다른 행은 따로). 다단 들여쓰기(indented) 보고서. **공급 유형**(push·조립 pull·공정 pull·phantom), 계획 비율(planning %), 공정 순번 | [O1][O2][O3][O4] |
| **Odoo 18/19** | BOM 유형: 제조·키트(phantom — 판매 시 구성품으로 분해, 지시 없음)·외주. 부산물(by-product), 공정을 BOM 에 포함, 변형(variant)별 구성품 | [OD1][OD2] |
| **SAP Business One** | BOM 유형 4종: 생산·판매·조립·템플릿 (TreeType P/S/A/T). Service Layer `ProductTrees` | [B1][B2] |
| **InvenTree(오픈소스)** | 소요 = 기준 수량 × 조립 수 + **손실률(attrition)** + **준비 수량(setup, 고정)** → **반올림 배수(rounding multiple)** 로 올림. 대체품(substitutes), 소모성(consumable — 할당 안 함), 상속(inherited), 선택(optional). **BOM 체크섬 → 바뀌면 "검증 안 됨"** | [I1][I2] |
| **Infor MES · GE Production Manager** | MES 는 투입 자재가 **BOM 에 있거나 유효한 대체 자재일 때만** 투입으로 인정. 작업자가 BOM 화면에서 LOT 대체·추가 | [M1][M2] |
| **ISA-95 / B2MML** | 품목 = MaterialDefinition, BOM 머리 = ProductDefinition/ManufacturingBill, 구성 = AssemblyManufacturingBill (PTC Windchill–MES 매핑) | [X1] |
| **EBOM ↔ MBOM (Siemens·Aras 등)** | EBOM = "무엇", MBOM = "어떻게 만드나"(공정·라인·조달). 한 EBOM → 공장별 여러 MBOM. 가상 조립은 MBOM 에서 새로 생기기도, 없어지기도 — 변환 규칙을 명시해야. 두 BOM 이 어긋나는 위험 → ECO 로 통제 | [E1][E2] |
| **ERP 일괄 대체 (Deltek·Cetec·Sage)** | 역전개로 영향 범위를 먼저 보여 주고 → 전체 또는 **날짜 유효** 교체, ECO 와 묶어 감사 | [R1][R2][R3] |
| **Low-level code (APICS)** | "품목이 어떤 BOM 에서든 나타나는 가장 낮은 레벨". MRP 가 레벨 순으로 계획하는 데 쓰고 자동 계산 | [L1][L2] |
| **부산물 원가 (SAP·AX)** | 부산물은 BOM 에 **음수 수량** 행, 원가는 주 제품 원가에서 차감. 연산물(co-product)은 별도 표시·배분 | [C1][C2] |
| **PostgreSQL 14+** | 재귀 CTE `SEARCH DEPTH FIRST BY … SET ordercol`(들여쓰기 순서), `CYCLE … SET is_cycle USING path`(순환 감지 후 멈춤). 둘 다 쓰면 중복 계산 → **CYCLE 의 path 로 정렬** 권장 | [P1][P2] |
| **[내부] 고객사 경험** | 다단 BOM(재귀 CTE·순환 체크·REV)이 가장 성숙했던 패턴(00 2장). 트리 편집을 하다 결국 **평면 편집으로 전환**(A사, 09 4.11). BOM 형태는 평면 1레벨 + 조립 순번 / 다단 REV 가 섞여 있었다 → 다단 저장 + 평면 표시(00 3장). ERP BOM 이 평면이면 1레벨로 받는다(07 3.1) | 00·07·09 |

---

## 3. 기능 목록 — "완벽한 BOM" 의 범위

### 3.1 구조

| # | 기능 | 레퍼런스 | 우리 판단 |
|---|---|---|---|
| F1 | 다단 BOM(모품목 → 자품목, 자품목도 BOM 을 가질 수 있음) | 전부 | 필수 |
| F2 | **리비전**(A0, A1 …) + 상태 `DRAFT → RELEASED → OBSOLETE`, RELEASED 는 수정 금지 | D365 승인·활성, Oracle 리비전 | 필수 — IATF 8.5.6.1 변경 기록 |
| F3 | **유효기간**(valid_from ~ valid_to), 같은 모품목·용도·대체 번호의 RELEASED 끼리 **겹침 금지** | D365 [D1] | 필수 — DB 배제 제약(`btree_gist`) |
| F4 | **기준 수량**(base qty — "100개 만들 때 12kg") | SAP·D365 | 필수 — 소량 자재 정밀도 |
| F5 | 행: 순번·자품목·소요량·단위·**손실률(%)**·**고정 수량**(로트당, 곱하지 않음)·비고·조립 위치 | SAP·InvenTree | 필수 |
| F6 | **단위 환산**(BOM 단위 ≠ 자품목 재고 단위, 예: 코일 kg ↔ 장 EA) | 07 4.1 템플릿 05 | 필수 — 품목별 환산 표 |
| F7 | 행 유형: **일반 · 가상 조립(phantom) · 부산물(by-product)** | SAP phantom, Odoo kit·by-product | 필수(3종). 텍스트 행·문서 행은 제외(비고 칸으로 충분) |
| F8 | **투입 방식**: 백플러시(실적 때 자동 차감) / 수동 투입(LOT 스캔) | Oracle supply type, Sage·Cin7 | 필수 — P2 실적·LOT 계보의 입력. LOT 관리 품목은 기본 수동 |
| F9 | **공정 연결**(어느 공정에서 투입) | Oracle 공정 순번, Odoo operations | 칸만 3단계에 두고 값은 5단계 라우팅 후 |
| F10 | **대체 자재 그룹**(같은 그룹 = 서로 대체, 우선순위, 사용 비율 %) | SAP alt item group [S4] | 필수 — P2 투입 검증(Infor MES) |
| F11 | **대체 BOM 번호**(같은 품목의 다른 구성 — 라인·공장별) | SAP alternative BOM, EBOM→다수 MBOM | 칸 + 기본 1. 선택 규칙은 5단계 라우팅·생산 버전과 함께 [추정] |
| F12 | BOM 용도(생산·설계·판매) | SAP usage, B1 type | **생산(MBOM)만** — 8장 B1 |

### 3.2 전개·조회

| # | 기능 | 내용 |
|---|---|---|
| Q1 | **정전개 — 단계별(1레벨)** | 바로 아래 자품목만 |
| Q2 | **정전개 — 다단(들여쓰기)** | 레벨·경로·누적 소요량, 같은 부품이 여러 번 나오면 여러 번(SAP) |
| Q3 | **정전개 — 합산(소요 집계)** | 말단 자재별 총 소요량(생산 수량 입력) — 자재 준비·구매 요청 근거 |
| Q4 | **역전개 — 다단** | 이 품목을 쓰는 모든 상위 품목, 레벨 열, 경로 |
| Q5 | **역전개 — 최상위(완제품)만** | "이 원자재가 바뀌면 영향받는 제품" — 4M 변경·리콜 범위 |
| Q6 | 기준일 지정 | 전개·역전개 모두 "이 날짜에 유효한 리비전"(기본 오늘, 회사 시간대 BizClock) |
| Q7 | 전체 리비전 보기(역전개) | 날짜 무관 모든 RELEASED·DRAFT 리비전에서 사용처 — 변경 영향 분석용 |
| Q8 | **BOM 비교** | 두 리비전(또는 두 품목) 간 추가·삭제·수량 변경(Oracle [O1]) |
| Q9 | 엑셀 내보내기 | 다단 들여쓰기·합산·역전개 결과 그대로 |
| Q10 | 저레벨 코드(LLC) | 조회용으로 계산(저장은 MRP 생길 때) [L1] |

### 3.3 변경 관리

| # | 기능 | 내용 |
|---|---|---|
| C1 | **새 리비전 만들기**(RELEASED 복사 → DRAFT) | 확정본은 고치지 않는다 |
| C2 | **확정(release)** | 검사: 행 ≥ 1, 자품목 사용 중, 순환 없음, 단위 환산 있음, 기간 겹침 없음. 확정하면 이전 RELEASED 의 valid_to 를 새 valid_from 으로 자동 마감(선택) |
| C3 | **폐기(obsolete)** | 새 전개에서 제외, 기록은 유지 |
| C4 | **BOM 복사**(다른 품목으로) | 비슷한 품목 빠르게 |
| C5 | **일괄 대체**(자품목 X → Y) | 역전개로 영향 BOM 목록 미리보기 → 선택한 BOM 마다 새 리비전 생성(+유효일) → 확정은 따로(Deltek·Cetec 방식) |
| C6 | 변경 번호·사유 | 리비전마다 변경 번호(ECO)·사유 글. 4M 변경 레코드(`qms_change`, P3)와는 나중에 연결 |
| C7 | **순환 금지** | 저장·확정 때 "자품목의 모든 미폐기 리비전 전개에 모품목이 있으면" 거부 — 회사별 잠금으로 동시 저장 경쟁도 막음 |
| C8 | 엑셀 올리기 | 2단계 엔진에 `MST_BOM` 템플릿(모품목·리비전·순번·자품목·소요량·단위·…) — 한 파일에 여러 모품목, DRAFT 로만 생성 |

---

## 4. 데이터 모델 (안)

```text
mst_unit_conv     품목별 단위 환산 : comp_cd, item_id, from_unit, to_unit, factor(>0)   unique(comp, item, from, to)
                  (1 from_unit = factor to_unit). 같은 단위끼리는 1, 품목 무관 공통 환산(kg↔g)은 시스템 표로 [추정]

mst_bom           머리 : bom_id, comp_cd, item_id(모품목), alt_no(기본 1), rev(예 A0), status(DRAFT/RELEASED/OBSOLETE),
                  valid_from(date), valid_to(date, null = 무기한), base_qty(>0, 모품목 단위), change_no, change_reason,
                  released_at/by, remark, row_ver, 감사 4컬럼
                  unique(comp, item, alt_no, rev)
                  EXCLUDE USING gist (comp_cd =, item_id =, alt_no =, daterange(valid_from, valid_to) &&) WHERE status = 'RELEASED'

mst_bom_line      행 : line_id, comp_cd, bom_id, line_no(10, 20 …), item_id(자품목), line_type(NORMAL/PHANTOM/BYPRODUCT),
                  qty(>0, 기준 수량당), unit_cd, scrap_pct(0~99.99), fixed_yn(로트당 고정), issue_method(BACKFLUSH/MANUAL),
                  op_seq(공정 순번, 5단계 연결), alt_grp(대체 그룹 코드), alt_priority, alt_pct, position(조립 위치), remark,
                  row_ver   unique(bom_id, line_no)
                  CHECK 모품목 ≠ 자품목, BYPRODUCT 는 백플러시 불가·대체 그룹 불가
```

- 회사 일치 트리거(자품목·모품목·머리가 같은 회사), RLS, 감사, 보존 MASTER, DELETE 회수 — 행 삭제는 **DRAFT 일 때만** 앱이 허용해야 하므로 행 테이블은 예외로 DELETE 허용하고 **트리거로 "RELEASED 머리의 행은 INSERT/UPDATE/DELETE 금지"** [추정].
- 부산물을 음수 수량(SAP)이 아니라 **행 유형**으로 둔다 — 수량은 항상 양수, 의미는 유형으로(음수 실수로 부산물이 되는 사고 방지) [추정].
- 대체 자재는 "같은 대체 그룹의 행들"(SAP 방식). 우선순위 1 이 기본 소요, 나머지는 대체 허용 목록. 사용 비율 합은 100(검사) [S4].

---

## 5. 알고리즘

### 5.1 유효 BOM 선택
`status = 'RELEASED' and valid_from <= :date and (valid_to is null or :date < valid_to) and alt_no = :alt(기본 1)` — 겹침 금지 제약 덕분에 **항상 0 또는 1건**(SAP 결정 순서의 "날짜 → 상태 → 대체" 를 DB 제약으로 보장).

### 5.2 정전개 (다단)
```text
레벨 0: 모품목, 필요 수량 N
행마다: 행 소요 = fixed_yn ? qty : qty / base_qty × 상위 필요량
        × (1 + scrap_pct/100)                       -- 부품 손실(SAP component scrap, InvenTree attrition)
        × 단위 환산(행 단위 → 자품목 재고 단위)
자품목이 유효 BOM 을 가지면 그 아래로 계속(일반·가상 모두). 가상(PHANTOM) 행은 결과에서 "통과" 표시 — 합산에서는 빠지고 그 아래가 상위로 올라간다
부산물(BYPRODUCT)은 전개하지 않고 "산출" 으로 따로 표시
```
- 한 쿼리: `WITH RECURSIVE … CYCLE item_id SET is_cycle USING path` + `ORDER BY path`(들여쓰기 순서). 레벨 상한 30(정책) — 넘으면 오류 [P1][P2].
- 반올림: 계산은 numeric 그대로, 표시만 단위 자릿수(UNIT `scale`). 올림 배수(InvenTree rounding multiple)는 자재 준비 단위가 필요할 때(P4) [I2].
- 합산(Q3): 다단 결과에서 말단(자기 BOM 이 없는 품목) + 가상 아님 → 품목별 합.

### 5.3 역전개
`WITH RECURSIVE` 를 자품목 → 행 → 머리(모품목) 방향으로. 기준일 모드는 각 단계에서 유효 BOM 의 행만, 전체 리비전 모드는 미폐기 전부. 결과: 레벨·모품목·리비전·행 소요량·경로. 최상위 모드는 "더 위가 없는 모품목"만.

### 5.4 순환 금지 (저장·확정)
행 저장 시: `pg_advisory_xact_lock(회사 BOM 구조 잠금)` → "자품목에서 정전개(미폐기 모든 리비전)했을 때 모품목이 나오는가" → 나오면 거부 `BOM_CYCLE`(경로를 오류 문구에). 잠금이 없으면 두 사람이 A→B, B→A 를 동시에 저장해 순환이 생길 수 있다 [추정 — 경쟁 조건].

### 5.5 비교
두 머리의 행을 (자품목, 행 유형, 공정 순번) 키로 짝지어 추가·삭제·변경(소요량·손실률·단위·투입 방식) 목록. 같은 행은 한 줄(Oracle [O1]).

### 5.6 저레벨 코드
역방향으로 "가장 깊은 레벨"을 재귀 계산(조회 전용). MRP 가 생기면 확정 때 저장 [L1].

---

## 6. 화면 (안)

| 화면 | 구성 | 근거 |
|---|---|---|
| **BOM 관리** `mst-bom` | 왼쪽: 모품목·리비전 목록(조회 패널: 품목·상태·기준일) / 가운데: **머리 폼 + 구성 행 평면 편집**(자품목·소요량·단위·손실률·유형·투입 방식·대체 그룹) / 버튼: 새 리비전·확정·폐기·복사 | 09 4.11 "모품목 선택 → 자품목 평면 편집"(트리 편집 실패 교훈) |
| **BOM 전개 조회** `mst-bom-tree` | 기준일·생산 수량·보기(단계별/다단/합산) → 들여쓰기 표(펼침·접기, 레벨 색 띠 대신 들여쓰기 + 레벨 숫자), 엑셀 | SAP 3종 |
| **BOM 역전개 조회** `mst-bom-where` | 품목·기준일·전체 리비전·최상위만 → 들여쓰기 표, 엑셀 | SAP·AX where-used |
| **BOM 비교** | 두 리비전 선택 → 차이 표(추가·삭제·변경 색 + 글자) | Oracle |
| **일괄 대체** | 자품목 X → Y, 적용일 → 영향 BOM 미리보기(역전개) → 선택 → 새 리비전(DRAFT) 생성 | Deltek·Cetec |

- 행 편집 방식이 결정 사항(8장 B4): 편집 그리드는 아직 PoC 결론 전(docs/10 H3·H4, 한글 IME 사람 판정 남음).
- 들여쓰기 표는 새 공통 컴포넌트(`hb.tree` — 펼침·접기·키보드 ←→)가 필요하다 [추정].

---

## 7. 이번에 하지 않을 것 (근거)

| 제외 | 이유 |
|---|---|
| 원가 집계(costed BOM rollup) | 원가 모듈 없음 — BOM 은 수량 구조만. 나중에 같은 전개 엔진을 쓴다 |
| MRP·소요량 계획(시점·재고 차감) | 소요 "계산"(합산 전개)까지만. 재고·발주를 엮는 계획은 P4·P5 |
| 구성형·옵션 BOM(variant/configurable, 150% BOM) | 자동차 2·3차 부품사는 품번 = 사양이 대부분 [추정]. 필요하면 2차 |
| 시리얼 유효성(SAP SERNR) | 날짜 유효성으로 충분, 시리얼 추적은 2차 반도체 |
| 설계 BOM(EBOM)·판매 BOM | MES 는 생산 BOM 을 받는다(07 3.1 — ERP/PLM 이 설계 BOM 주인) |
| 텍스트·문서 행 | 비고·첨부로 충분 |
| 연산물(co-product) 원가 배분 | 원가 모듈 때. 부산물 행은 산출 수량만 |

---

## 8. 결정 요청 (사용자)

| # | 질문 | 제안 | 대안 |
|---|---|---|---|
| **B1** | BOM 용도 | **생산 BOM(MBOM) 하나** — 설계 BOM 은 ERP·PLM 이 주인 | 용도 코드(생산·설계) 칸을 두고 생산만 사용 |
| **B2** | 유효성 단위 | **머리(리비전) 단위 기간** + 겹침 금지 | 행 단위 유효기간(SAP 변경 번호 방식 — 강력하지만 화면·검증이 복잡) |
| **B3** | 확정본 수정 | **불가 — 바꾸려면 새 리비전**(IATF 변경 기록) | 사유를 남기고 수정 허용 |
| **B4** | 구성 행 편집 UI | **지금은 행 폼 목록**(거래처 담당자 방식, 키보드로 충분) → 편집 그리드 결정 뒤 교체 | 편집 그리드 PoC(Tabulator)를 지금 정식 도입 — 한글 IME 사람 판정이 먼저 필요 |
| **B5** | 행 유형 | **일반·가상 조립·부산물 3종**, 부산물은 양수 + 유형 | 일반만(가장 단순) / SAP 처럼 음수 수량 |
| **B6** | 대체 자재·대체 BOM | **대체 자재 그룹은 3단계에, 대체 BOM 번호는 칸만(기본 1)** | 둘 다 나중 |
| **B7** | 순환 검사 범위 | **미폐기 모든 리비전 기준**(미래 리비전에서도 순환 금지) | 기준일에 유효한 것만 |
| **B8** | 진행 단위 | **3a 구조·리비전·단위 환산·순환 금지 + 편집 화면 + 엑셀 올리기 → 3b 전개 엔진(정전개 3종·역전개 2종)·조회 화면·엑셀 → 3c 비교·복사·일괄 대체·저레벨 코드** | 한 번에 |

결정이 오면: docs/17 에 3단계 상세(이 문서 4~6장 확정본)를 옮기고 → 3a 부터 구현(정상·이상·A사/B사·순환·동시 저장 테스트) → 3b 는 실측(10레벨 × 행 1,000)까지.

## 9. 출처

**SAP**
- [S1] Analyzing BOMs (SAP Learning): https://learning.sap.com/courses/exploring-basic-data-for-manufacturing-and-product-management-in-sap-s-4hana/analyzing-boms
- [S2] Determining the Valid BOM: https://help.sap.com/SAPHelp_46c/helpdata/EN/f4/7d2a9c44af11d182b40000e829fbfe/content.htm
- [S3] Multi-Level / where-used: https://help.sap.com/saphelp_46c/helpdata/en/57/613275e24bd111950d0060b03c6b76/content.htm · https://help.sap.com/saphelp_470/helpdata/EN/ea/e9bdaa4c7211d189520000e829fbbd/content.htm
- [S4] Alternative Items: https://help.sap.com/saphelp_46c/helpdata/en/f4/7d2b9344af11d182b40000e829fbfe/content.htm · https://help.sap.com/saphelp_46C/helpdata/EN/f4/7d2ba044af11d182b40000e829fbfe/content.htm
- [S5] BOM item categories: https://help.sap.com/saphelp_me60/helpdata/en/21/02b753128eb44ce10000000a174cb4/content.htm · https://help.sap.com/saphelp_46C/helpdata/EN/4b/9d5de6a45911d19db10000e8323350/content.htm
- [S6] Scrap types (SAP Learning·커뮤니티): https://learning.sap.com/courses/exploring-basic-data-for-manufacturing-and-product-management-in-sap-s-4hana/routing-scrap · https://blogs.sap.com/2021/03/06/summary-different-types-of-scrap-in-sap-pp-net-scrap-indicator/ · 변경 유효성: https://blogs.sap.com/2019/09/30/engineering-change-management-parameter-effectivity/

**Microsoft**
- [D1] BOM and formula versions: https://learn.microsoft.com/en-au/training/modules/create-bill-materials-dyn365-supply-chain-mgmt/2-formula-version
- [D2] React to last-minute changes: https://learn.microsoft.com/en-us/dynamics365/supply-chain/production-control/react-to-last-minute-changes
- [D3] Planning Optimization production planning: https://learn.microsoft.com/en-us/dynamics365/supply-chain/master-planning/planning-optimization/production-planning
- [D4] AX 2012 Where-used / BOM versions: https://learn.microsoft.com/da-dk/dynamicsax-2012/where-used-form · https://technet.microsoft.com/library/aa620619(v=ax.60) · 반올림 사례: https://community.dynamics.com/article/KA-23761

**Oracle**
- [O1] Comparing Bills of Material: https://docs.oracle.com/cd/A60725_05/html/comnls/us/bom/bomtas12.htm · https://docs.oracle.com/cd/A60725_05/html/comnls/us/bom/rbcmp.htm
- [O2] Maintaining BOMs by Revisions or Effectivity Dates: https://docs.oracle.com/cd/G47724_01/fscm92pbr55/eng/fscm/smfg/MaintainingBOMsbyRevisionsorEffectivityDates-9f2ad8.html
- [O3] Supply Types (WIP): https://docs.oracle.com/cd/E26401_01/doc.122/e48905/T228107T228127.htm
- [O4] Mass BOM changes: https://docs.oracle.com/cd/G17334_01/fscm92pbr52/eng/fscm/segg/UnderstandingMassBOMChanges-9f13c6.html · Process manufacturing report options: https://docs.oracle.com/cd/E59116_01/doc.94/e58771/ch_proc_manf_proc_opt.htm

**Odoo · SAP Business One · InvenTree · MES**
- [OD1] Odoo 19 Bill of materials: https://www.odoo.com/documentation/19.0/applications/inventory_and_mrp/manufacturing/basic_setup/bill_configuration.html
- [OD2] Odoo 18 Bill of materials: https://www.odoo.com/documentation/18.0/applications/inventory_and_mrp/manufacturing/basic_setup/bill_configuration.html
- [B1] SAP B1 OITT(TreeType): https://help.sap.com/doc/089315d8d0f8475a9fc84fb919b501a3/10.0/en-US/SDKHelp/OITT.html
- [B2] SAP B1 BOM (SAP Learning): https://learning.sap.com/courses/discovering-sap-business-one-web-client-logistics/utilizing-bill-of-materials-bom-in-sap-business-one-web-client_ca0dce08-c6a2-42a7-abbb-f28ff526fe4e
- [I1] InvenTree BOM: https://docs.inventree.org/en/latest/manufacturing/bom/
- [I2] InvenTree required quantity: https://docs.inventree.org/en/latest/manufacturing/required/
- [M1] Infor MES Alternative materials: https://docs.infor.com/mes/2025.x/en-us/inv_guide/ggf1679057601803.html
- [M2] GE Digital Production Manager BOM: https://www.ge.com/digital/documentation/production-manager/c_ug_bill_of_material.html

**표준·개념**
- [X1] PTC Windchill MES data exchange (B2MML 매핑): https://support.ptc.com/help/wnc/r12.0.2.0/en/Windchill_Help_Center/MES_Impl_DataExchange.html
- [E1] Siemens Teamcenter EBOM/MBOM: https://blogs.sw.siemens.com/teamcenter/?p=1685
- [E2] Aras MBOM: https://aras.com/en/glossary/mbom · PLM 데이터 모델: https://virtualdutchman.com/2015/09/27/the-importance-of-a-plm-data-model-ebom-mbom/
- [L1] Low level code (Infor CSI): https://docs.infor.com/csi/10.x/en-us/csbiolh/inventory_user_cl_sl/lsm1454144401657.html · https://docs.infor.com/csi/9.01.x/en-us/csbiolh/lsm1454143869646.html
- [L2] Dynamics NAV low-level codes: https://archerpoint.com/using-low-level-codes-microsoft-dynamics-nav/
- [C1] By-product costs (SAP): https://help.sap.com/saphelp_46c/helpdata/en/7e/cb856143a311d189ee0000e81ddfac/content.htm
- [C2] Co-product cost allocation (AX 2012): https://learn.microsoft.com/en-us/previous-versions/dynamicsax-2012/co-product-cost-allocation-form
- [R1] Deltek Costpoint mass component change: https://help.deltek.com/PRODUCT/Costpoint/6.1%20(SP2)/GA/ClientServer/bmpmcc.htm
- [R2] Cetec ERP BOM use / mass replace: https://cetecerp.com/support/how-to/how-to-use-the-bom-use-screen-to-replace-components/
- [R3] Sage 100 replace component: https://www.cleverence.com/articles/sage-documentation/replace-or-delete-a-component-sage-100-help-4827
- [P1] PostgreSQL SELECT (SEARCH·CYCLE, 14+): https://manpages.opensuse.org/Leap-15.6/postgresql14/SELECT.7pg14.en.html (postgresql.org 원문 대신 미러 확인)
- [P2] SEARCH/CYCLE 커밋·패치: https://postgresql.org/message-id/attachment/116415/v4-0001-SEARCH-and-CYCLE-clauses.patch · 사용 주의: https://docs.arenadata.io/en/ADPG/current/how-to/queries/common-table-expressions.html
- IATF 16949 8.5.6.1 변경관리 일반: https://blog.aiag.org/core-tools-and-iatf-169492016-overview (BOM 에 대한 구체 요구는 원문·고객 CSR 확인 필요 — 확인 못 함)

**확인하지 못한 것**: SAP S/4HANA 최신판 한 페이지에 모은 공식 설명(대부분 4.6C·4.70 도움말), D365 where-used 최신 문서, IATF 16949 원문의 BOM 관련 문구, 한국 MES 제품의 정전개·역전개 화면 공개 자료.

## 10. 검토 결과

2026-10-16 사용자: **"제안대로 진행, 3a 부터"** — B1~B8 모두 제안대로 채택.

| # | 판정 |
|---|---|
| B1 | 채택 — 생산 BOM(MBOM) 하나, 용도 칸 없음 |
| B2 | 채택 — 머리(리비전) 단위 기간 + RELEASED 끼리 겹침 금지(DB 배제 제약) |
| B3 | 채택 — RELEASED 는 행·머리 수정 불가(DB 트리거), 바꾸려면 새 리비전 |
| B4 | 채택 — 구성 행은 행 폼 목록(편집 그리드 결정 뒤 교체) |
| B5 | 채택 — 행 유형 NORMAL·PHANTOM·BYPRODUCT, 수량은 항상 양수 |
| B6 | 채택 — 대체 자재 그룹은 3a, 대체 BOM 번호는 칸만(기본 1) |
| B7 | 채택 — 순환 검사는 미폐기 모든 리비전 기준 |
| B8 | 채택 — 3a(구조·리비전·단위 환산·순환 금지·편집 화면·엑셀 올리기) → 3b(전개 엔진·조회) → 3c(비교·복사·일괄 대체·저레벨 코드) |

구현은 docs/17 "3단계" 진행 표에 단계별로 적는다.

### 10.1 보충 — 엑셀 올리기의 역할 (2026-10-16, 사용자 질문 "BOM 은 엑셀로 관리하기 어렵지 않나")

업계 관행: BOM 원본은 PLM → ERP(대기업) 또는 ERP(중견·중소)에 있고 MES 는 받는다. 자체 관리하는 곳도 **엑셀 올리기는 초기 이관·신제품 대량 등록용**이고, 일상 변경은 화면에서 *복사해 새 리비전 → 바뀐 행만 수정 → 비교 → 확정*, 부품 교체 지시는 *역전개 → 일괄 대체*(SAP CEWB)로 한다. 엑셀 양식은 단단계 쌍(SAP 이관·D365·Odoo)과 다단 들여쓰기(CAD 출력) 두 가지.

제안(사용자 확인 대기):
- 3a 엑셀 올리기는 유지하되 **단단계 쌍 형식, DRAFT 생성 전용**(확정본 불가) — 이관 도구로 위치를 좁힌다.
- 일상 편집 보강: 행 목록에 **엑셀 범위 붙여넣기**(기존 `paste-parser.js`) + 복사해 새 리비전(3a 범위 그대로).
- 다단 들여쓰기 양식 올리기는 3b 다단 정전개 내려받기 양식과 맞춰 **3b 로**.
- ERP·PLM 수신 인터페이스는 외부 연계 단계(`sys_inbox` 멱등)로.

3a 반영(2026-10-19): 엑셀 올리기 = 단단계 쌍·DRAFT 전용(확정본 불변, 작성 중이 없으면 새 리비전) ✅. 행 목록 엑셀 붙여넣기 — **미완**(사용자 확인 대기). 다단 들여쓰기 양식 — 3b.
추가 결정: 확정 시작일은 오늘 이후(지난 날짜 확정 금지 — 실적의 "그때 BOM" 보존). 새 리비전 기본 시작일은 오늘, 원본 확정본이 오늘 이후 시작이면 그 다음 날.

3b 반영(2026-10-19): 5.2·5.3 그대로 구현(`BomMapper.explode`·`whereUsed`, 재귀 CTE 한 번). 정해 둔 것 —
대체 그룹 행의 필요량 = 사용 비율만큼(60/40 → 각각 60%·40%), 고정 수량 행 = 상위 필요량과 무관(손실률·환산은 적용),
환산이 없는 단위 = 1 로 계산하고 `noConv` 표시(작성 중 미리보기에서만 생긴다 — 확정 검사가 막음), 레벨·행 상한을 넘으면 결과 대신 오류.
PostgreSQL `CYCLE` 절 대신 품목 배열로 순환 표시(정렬 경로와 함께 한 번에), 정렬 = 순번 + 행 id 경로.

3a QA 반영(2026-10-19, `docs/qa/bom-3a.md`): 결함 7건 수정. 정해 둔 것 —
머리의 모품목·대체 번호는 작성 중에도 불변(V017), 대체 그룹은 확정 때 우선순위 중복 금지 + 1(기본 자재) 필수, 환산 수정 때 `to_unit` 을 지금 기본 단위로.
**품목 기본 단위 변경 금지(사용자 결정 2026-10-19 "IN_USE 로 막자")**: 그 품목이 BOM 머리(모품목 — 기준 수량이 그 단위)·BOM 행(자품목, 상태 무관 — 폐기본도 새 리비전으로 되살릴 수 있다)·사용 중 단위 환산 중 하나에 쓰이면 기본 단위를 바꿀 수 없다(409 `IN_USE`, 칸 `unitCd`).
이유: 행 수량 "1 KG"·환산 "1 ROLL = 850 KG" 의 뜻이 품목 단위 변경으로 조용히 바뀌거나 사라진다(확정본은 고칠 수도 없다). 바꿔야 하면 새 품목 코드로.
막는 곳: 품목 화면 저장·품목 엑셀 올리기(칸 오류) + DB 트리거 `fk_mst_item_unit_in_use`(직접 SQL·경쟁 — V018). 사용 안 함 환산은 막지 않는다(되살릴 때 지금 기본 단위로 다시 맞춤).
P2 이후 원장(재고·실적)이 품목 단위를 참조하면 같은 트리거에 조건을 더한다.

3c 반영(2026-10-19): 비교(5.5 키 그대로, 순번만 바뀐 것은 변경 아님), 다른 품목으로 복사(C4 — 새 작성 중), 일괄 대체(C5), 저레벨 코드(5.6).
정해 둔 것 — 일괄 대체 대상: 모품목(대체 번호별)에 X 를 쓰는 작성 중이 있으면 거기, 없으면 시작일 유효 확정본을 복사한 새 리비전.
건너뜀 4종(순환·작성 중이 X 를 안 씀·유효 확정본이 X 를 안 씀·확정본이 시작일과 같은 날 시작 — 확정할 때 자동 마감 불가).
소요량·단위는 그대로 옮기고 단위가 Y 와 맞지 않으면 경고만(확정 검사가 막는다). 실행은 미리보기를 다시 계산해 고른 대상이 그대로일 때만.
저레벨 코드는 재귀 경로 열거 대신 간선 + 위상 정렬(가장 긴 경로) — 갈래가 많은 구조에서 경로 수가 폭발하지 않게. 저장은 MRP 때.

QA 3b·3c 수정(2026-10-20, docs/qa/bom-routing-3b-5.md):
- 전개·역전개 재귀는 **정렬 전에** 행 상한에서 끊는다(정렬 뒤 LIMIT 은 상한을 넘는 구조도 끝까지 계산 — 같은 자품목 여러 행이 이어지면 경로 2^n).
  역전개 최상위 표시는 SQL 상관 서브쿼리 대신 서비스가 자품목 집합으로(재귀 CTE 재참조는 행 수² — 상한 안쪽 16,382행이 30초 초과).
  필요량은 레벨마다 소수 20자리 반올림, 응답 12자리(자릿수가 레벨마다 쌓여 10단 400자리였다).
- 전개 표(`hb.tree`)는 보이는 범위만 그린다(16,382행 그리기 13.5초 → 창 렌더).
- 일괄 대체 건너뜀 5종째 `FUTURE_REV`: 시작일 뒤에 시작하는 확정본이 있으면 새 리비전을 만들지 않는다 — 그것과 겹쳐 확정할 수 없고 그 리비전은 X 를 계속 쓴다.
- 비교의 수량은 기준 수량당으로 판정(고정 수량 행은 그대로), 기준 수량이 다르면 비교 화면에 알림.
