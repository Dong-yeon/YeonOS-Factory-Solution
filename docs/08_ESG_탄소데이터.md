# 08. 협력사 ESG·탄소 데이터 요구와 SmartHappy(MES)의 역할 — 웹 조사와 반영안

> 작성일 2026-10-15 · **기준 커밋 `b2f4f87`** (P0 4단계까지). 원래 세션의 P0 5단계 진행분은 평가하지 않는다.
> 성격: 백그라운드 조사 결과. **코드·00~05·skill 은 수정하지 않았다.** 원래 세션이 검토 절을 붙인 뒤 00/01 반영 여부를 사용자와 정한다.
> 확인 수준: **[원문]** 1차 출처(공식·발표 원문)를 직접 열어 확인 / **[검색]** 검색 결과 요약·2차 자료로 확인, 인용 전 원문 재확인 권장 / **[추정]** 조사자 판단. 참고문헌 번호는 9장 `T##`.
> 규제 시행일·수치는 2026-10-15 시점이다. EU 규제는 2025~2026 간소화(Omnibus) 이후 계속 바뀌고 있으니 영업·계약 문서에 쓸 때는 EUR-Lex 원문으로 다시 확인한다.

---

## 1. 요약 — 한 줄 결론과 반영 시점

**한 줄 결론**: 중소 자동차·반도체 부품사에 오는 요구는 지금은 대부분 **"사업장 연간 에너지·Scope 1·2 + 고객별 배분"**(자가신고 + 고지서 증빙)이고, 몇 년 안에 **"품번별 PCF(cradle-to-gate, 1차 데이터 비율 표시)"**로 옮겨 가고 있다. PCF 의 자기 공장 몫(gate-to-gate)은 **에너지를 품번·LOT 에 나누는 문제**이고, 그 배분 키(설비 가동 시간·실적 수량·자재 투입·폐기)는 **MES 원장에만 있다.** SmartHappy 가 할 일은 LCA 도구를 만드는 게 아니라 **P1~P2 원장에 배분 키를 빠짐없이 남기는 것**, 그리고 나중에 **PACT 형식으로 내보내는 것**이다.

| # | 할 것 | 근거(절) | 시점 |
|---|---|---|---|
| 1 | **계측점 마스터**(전력·연료·압축공기 계량기 ↔ 장비 계층 노드) | 4.2, 6.1 | **P1** |
| 2 | 품목 **단위 순질량(kg)**·**HS/CN 코드**, 공장 **국가·지역 코드** | 4.3, 2.2 | **P1** |
| 3 | 설비 상태 구간 원장에 **당시 작업지시(`wo_id`) 키** | 4.2 | **P2** |
| 4 | LOT 계보 `CONSUME` 수량에 **단위·질량 환산 가능성** 보장 (자재 실투입) | 4.2 | **P2** |
| 5 | 실적 원장 폐기·재작업 수량 분리(이미 결정) + **폐기 사유 코드**를 탄소 배분에 그대로 사용 | 4.2 | **P2** |
| 6 | **에너지 계측 원장**(구간 사용량, 출처 COLLECTOR/MANUAL/BILL) + Collector 표준 이벤트에 계량값 유형 포함 | 6.1 | **P2**(테이블) |
| 7 | 사업장 **월별 에너지 고지서 실적 + 증빙 첨부** (Scope 2 근거, 계측 합계 대사) | 2.1, 6.1 | P2~P4 |
| 8 | **배출계수 테이블**(출처·연도·유효기간 버전) | 4.4 | P5 |
| 9 | 설비·품번 **에너지 원단위 리포트**(ISO 22400 Amd 1 에너지 KPI와 같은 자리) | 03 2.8 | P5 |
| 10 | 사업장 Scope 1·2 집계 + **고객별 배분표**(CDP 20.12 형식), gate-to-gate PCF 계산, **PACT v3 JSON 내보내기** | 3, 5 | P6 |

**하지 말 것(요약, 상세 7장)**: 자체 LCA 도구·LCI DB, "인증 PCF" 표기, FEMS·피크 제어, Catena-X 커넥터 자체 운영, 고객 Scope 3 대행, 블록체인 이력.

---

## 2. 고객 요구 정리

### 2.1 국내 완성차·부품 대기업

| 고객 | 요구 내용 | 형식·주기 | 검증 수준 | 확인 |
|---|---|---|---|---|
| 현대차·기아 | 협력사 ESG 서면진단(환경 항목: 온실가스·에너지·폐기물·재활용률·유해물질·용수). 70점 미만 고위험, 신규는 6개월 내 재평가 미달 시 거래 불가 | 서면진단 정기, 고위험은 현장 실사. 2025년 2,086개사 서면·127개사 실사, 2026 보고서에서 2차 협력사까지 확대 | 자가진단 + 실사 | [원문] T01 / [검색] T02 |
| 현대차그룹 | **SCEMS**(협력사 탄소배출 이력관리, 2023-07): 원료~부품 제조~운송, 협력사가 "필수 데이터" 입력 → 배출량 자동 산정. 협력사 무상. 입력 항목 목록은 비공개 | 플랫폼 입력 | 블록체인 위변조 방지를 "검증 대응"으로 설명 | [원문] T03 |
| 현대차그룹 | **CDP 공급망**(2023-02 가입): 에너지 사용량, 배출량, 감축목표, 재생에너지 전환 계획·실적 | 연 1회 CDP 응답 | CDP 등급 | [원문] T03 |
| 현대모비스 | 2·3차 협력사까지 Scope 3 관리 고도화, 제3자 검증 범위 확대, 중소 협력사 진단·설비 지원 | 미공개 | 확대 중 | [검색] T04 |
| (루머) | "2027년부터 전 협력사 제3자 검증 의무" | — | — | **업체 블로그만, 미확인** T05 |

- 공급망 감축 로드맵(2022 보고서): 2030년 핵심 협력사 10%↓, 2040년 65%↓, 2045년 공급망 탄소중립 [검색] T06.
- 정부·현대차·기아·협력사 87개사 공급망 탄소감축 협업 행사(중기부·산업부) [검색] T07.

### 2.2 해외 완성차

| 고객 | 요구 내용 | 확인 |
|---|---|---|
| VW | **S-Rating**(2019-07~): 고위험 직접 협력사의 사업장 단위 평가, 수주마다 확인, 부정 등급이면 원칙적으로 수주 불가. Drive Sustainability **SAQ 5.0** 응답을 외부 검증 | [원문] T08 |
| VW | 요청 시 에너지(MWh)·Scope 1·2·3 를 **제품 단위**로 보고, 배터리 계약에 CO2e 상한, 신규 플랫폼부터 CO2e 를 부품 기술사양에 포함 | [검색] T09 |
| SAQ 5.0 | 환경 절: 방침, 경영시스템(ISO 14001/50001 최고점), 에너지, 배출(Scope 3 포함), 폐기물. **수치는 고지서·인보이스 증빙 필수**, 증빙 없으면 미흡. 사업장당 1건, 회원 OEM 공유 | [원문] T10 |
| Mercedes | "Ambition Letter of Intent"(CO2 중립 부품만 납품) 서명이 신규 수주 조건, 2039년부터 CO2 중립 생산재만 | [검색] T11 |
| BMW | 2020년부터 협력사 CO2 가 수주 기준, 요구는 Tier 1 에 걸고 하위로 전파. 부품 PCF 는 Catena-X PCF Rulebook | [검색] T12, T25 |
| Toyota | 2021년 Tier 1 에 전년 대비 CO2 3% 감축 요청, 북미 GSR 2025 는 Scope 1·2 연 5.5%·Scope 3 연 2.5% 감축 목표 | [검색] T13 |
| GM·Ford | CDP 기후·물 설문, EcoVadis 50점 이상(GM), SBT 수립·요청 시 Scope 1·2·3 보고(Ford) | [검색] T14 |

### 2.3 반도체

| 고객 | 요구 내용 | 확인 |
|---|---|---|
| 삼성전자 | 2025년 협력사 GHG 관리시스템 오픈, **제3자 검증 도입**, 배출량 산정·목표·재생에너지가 평가 항목(가점). DS 는 거래액 5억원 이상 협력사 관리. RBA 기반 자가평가 연 1회 | [원문] T15 / [검색] T16 |
| SK하이닉스 | 1차 협력사 ESG 자가평가(노동·안전·환경·윤리) 2년 주기, 고위험 현장평가, CDP 공급망 응답률 84%(2024), 2026-10 국내 협력사 약 1,700곳 탈탄소 컨설팅 착수 | [원문] T17 / [검색] T18 |
| Apple | Apple 생산분 Scope 1·2 탈탄소, 2030년 재생전력 100% 가 행동강령 요건, 매년 진척 보고·감사 | [원문] T19 |
| TSMC | 주요 배출 협력사와 GREEN Agreement, **2026년 말까지 PCF 제3자 검증**, 2025년부터 탄소 성과가 협력사 선정 기준 | [검색] T20 (원문 접속 차단) |
| SEMI SCC | 2028년까지 반도체 가치사슬 PCF 산정·교환 방법론 개발 목표. **SEMI S23 은 장비 에너지 평가 표준이지 PCF 표준이 아님** | [검색] T21 |

### 2.4 공통 데이터 형식 — CDP SME 설문

Scope 1(20.4)·Scope 2(20.5)·Scope 3(20.7)·**고객별 배분(20.12)**. 20.12 배분 표: 요청 고객, Scope, Scope 2 방법, 배분 수준(회사/사업부/사업장/품목), tCO2e, 불확도 ±%, 주요 배출원, 검증 여부, **배분 방법(질량·수량·금액 등)**, 공급량과 단위 [검색] T22.
→ **"고객별·품목별 공급 수량 × 배분 기준"** 은 MES·출하 데이터로 바로 채울 수 있는 칸이다 [추정].

### 2.5 국내 중소기업 실태

- 대한상의·산단공 수출기업 300개사(2026-09 보도): **99.3%** 가 고객사 탄소 데이터 요청을 받았거나 받을 예정, **42.4%** 가 엑셀·수기 관리, **38.8%** 가 고객사별 서식으로 다시 입력("이중 수작업"). 애로는 구축·연동 비용 64.0%, 인력·시간 부족 62.7% [원문] T23.
- 중기중앙회(2022, 621개사): 20% 가 ESG 평가 요구를 받았고(그중 80.6% 국내 대기업), 18.5% 는 미개선 시 거래 중단·물량 축소를 경험·예상 [원문] T24.
- 2026 국감: 지원 기업이 실제로 데이터를 제출·검증받았는지 중기부가 파악하지 못함, **생산량·에너지가 바뀌면 다시 계산해야 하는 부담** 지적 [원문] T26.

**정리 [추정]**
- **지금 오는 것**: 사업장 연간 에너지(전력·연료 MWh)와 Scope 1·2(tCO2e), 재생전력 비율과 증빙, 감축목표·실적, 고객별 배분. 주기는 연 1회, 수주 시 별도 확인.
- **오고 있는 것**: 품번별 PCF(ISO 14067 / Catena-X / PACT), 1차 데이터 비율, 제3자 검증.
- **경로**: Tier 2/3 는 대부분 Tier 1 의 하향 전파와 **고객마다 다른 서식**으로 받는다.
- **SmartHappy 가 팔 수 있는 가치**: 실적·에너지에서 자동으로 집계하고, 같은 원장으로 고객별 서식을 여러 번 뽑아 주는 것. "이중 수작업"과 "다시 계산" 부담을 직접 해결한다.

---

## 3. 규제 정리

| 규제 | 현재 상태(2026-10-15) | 국내 중소 부품사 영향 | 확인 |
|---|---|---|---|
| **EU CBAM** | 확정 기간 **2026-01-01** 시작. 대상: 시멘트·철강·알루미늄·비료·전력·수소. Omnibus(Reg. 2025/2083, 2025-10-20 발효)로 **수입자별 연 50톤 면제**, 인증서 판매 **2027-02-01**, 첫 연간 신고 **2027-09-30**. 50톤 기준은 2026-09-30 검토에서 유지 | 수출 물품이 Annex I CN 코드(철강 72·73장 일부, 볼트·너트 등 체결부품, 알루미늄 76장)에 해당하면 **EU 수입자가 생산 설비 단위 내재 배출량(직접·간접)과 생산 경로**를 요구 | 확정기간·대상 [원문] T30 / 나머지 [검색] T31 |
| CBAM **하류 확장안** | COM(2025) 989(2025-12-17): 철강·알루미늄 집약 하류 제품 약 180개 품목(**자동차 부품 포함**), **2028-01-01 적용 제안**. 이사회 입장 2026-06, 의회 1독회 입장 2026-09-15 → 3자 협상 중. **아직 법 아님** | 확정되면 **강판·알루미늄 가공 부품을 EU 로 직접 수출하는 협력사**가 바로 대상. 2028 은 "가장 빠른 가능 시점" | [검색] T31, T32 |
| **EU 배터리 규정** 2023/1542 | **배터리 여권 2027-02-18 의무**(EV·LMT·2kWh 초과 산업용). **EV 배터리 탄소발자국 산정 위임법 미채택** → 신고 의무는 채택 12개월 후(빨라야 2027 하반기). 2027-02 여권에서 탄소발자국 칸은 "아직 채우지 않음". 배터리 실사 의무 **2027-08-18** 로 연기 | 배터리셀·팩 부품(케이스·버스바·냉각판 등) 협력사는 **셀·팩 제조사의 PCF 산정 요청**을 받는다. 여권 자체는 배터리 제조사 의무 | [검색] T33 |
| **EU CSDDD** | Omnibus I = **Directive (EU) 2026/470**(OJ 2026-02-26, 2026-03-18 발효). 대상 **종업원 5,000명 초과 + 매출 15억 유로 초과**, 국내법 전환 2028-07-26, **적용 2029-07-26 단일화**. 기후 전환계획 의무 삭제. 5,000명 미만 거래상대에게는 **다른 방법으로 얻을 수 없을 때만** 정보 요청 | 직접 의무 없음. 단, EU 대기업 고객의 실사 질문이 **범위를 좁혀** 계속 온다. 독일 LkSG 는 그때까지 유지 | [검색] T34, T35 |
| **EU CSRD** | 같은 Omnibus I 로 대상 **종업원 1,000명 초과 + 매출 4.5억 유로 초과**. 1,000명 이하 거래상대에게 **자발적 표준(VSME 기반)을 넘는 정보 요구 금지**(계약으로도) | 중소 협력사가 받는 ESG 설문의 **상한선**이 생김 → VSME 항목(에너지·Scope 1·2 등)이 사실상 기본 서식이 될 것 [추정] | [검색] T34 |
| **EU ESPR·DPP** | 작업계획 2025–2030(2025-04-16). 중간재로 **철강 2026 Q4(예정)·알루미늄 2027** 위임법, 적용은 채택 후 18개월 이후 | 철강·알루미늄 소재 공급사의 탄소발자국·재활용 함량 → 부품사는 **소재 PCF 를 받아 쓰는 쪽** | [검색] T36 |
| 국내 **ESG 공시**(KSSB) | 금융위 최종안 2026-07-08: **2028년 연결자산 10조원 이상 코스피 상장사**부터 법정공시, 2029년 5조원 이상(보도 엇갈림). **Scope 3 는 3년 유예 → 2031년**. 제3자 인증 2030년. 자본시장법 개정 진행 중 | 고객 대기업의 Scope 3 산정이 2031년부터 법정 → **2029~2030년쯤 협력사 1차 데이터 요구 급증** 예상 [추정] | [검색] T37 |

### 3.1 국내 지원사업·플랫폼 (2026)

| 사업 | 내용 | 확인 |
|---|---|---|
| 중기부·중진공 탄소중립 설비투자 **공급망 트랙** | 원청·협력사 컨소시엄, 최대 3억원, 2026년 30개사 선정(자동차·트레일러 13개사, 금속가공 10개사). 접수 마감 | [검색] T38 |
| 중기부 탄소중립 사업화 지원 | 기술사업화 최대 2억 등, ESG통합플랫폼(esg.kosmes.or.kr) 접수, 2026 마감 | [검색] T38 |
| 산업부 **산업 공급망 탄소데이터 플랫폼** | 대한상의·업종 협회 얼라이언스, 데이터를 저장하지 않는 **데이터 스페이스 방식**, 5대 업종(자동차 포함)부터, **구축 2026~2027** | [검색] T39 |
| 한국자동차연구원 **KADaP-X** | Catena-X 프로토콜 호환 부품정보교환 시스템 준비(2024~) | [검색] T40 |
| 기후에너지환경부 전력배출계수 | 갱신 주기 3년 → **1년**(매년 12월 공표). 최신 2023년도 소비단 **0.4173 tCO2eq/MWh**(2025-12 공표), 2024년도 값은 2026-12 예정. **LCA(전과정)용 전력 계수는 별도**(0.5304, 2022 기준) | [검색] T41 |

---

## 4. 산정 표준과 MES 데이터 매핑

### 4.1 표준

| 표준 | 핵심 | 확인 |
|---|---|---|
| **ISO 14067:2018** | 제품 탄소발자국. 중간재는 **부분 CFP(cradle-to-gate)**, 기능단위 대신 **선언단위**(예: 1개, 1kg). 배분 순서(6.4.6.2): ① **세분화**(공정을 나눠 따로 수집) 또는 시스템 확장으로 회피 → ② **물리적 관계**(질량·에너지 등) → ③ 경제적 가치. 공산물과 폐기물이 같이 나오면 **투입은 공산물에만 배분**(폐기 부담은 양품이 진다). 배분 방법이 여럿 가능하면 민감도 분석 | [검색] T42 (규격 본문 사본 기준, 구매본 확인 권장) |
| GHG Protocol Product Standard | ISO 14067 과 같은 축. 배분 위계 동일 | [검색] T43 |
| **PACT Methodology v3.0** | 2025-04-30 공개(구 Pathfinder Framework). cradle-to-gate, **1차 데이터 비율(PDS)** 계산 필수, 데이터 품질(기술·지리·시간 1~5), 제외 배출 3% 이내 | [검색] T44 |
| **Catena-X PCF Rulebook v4** | 2025-10 경. PACT 와 정렬, PDS 정의, **배분해도 1차 데이터 분류는 유지**(측정 연료로 계산한 공동 운송을 제품에 배분해도 1차) | [검색] T25 |
| SEMI S23 | 반도체 장비 에너지 평가 — PCF 입력값 소스일 뿐 | [검색] T21 |

### 4.2 gate-to-gate 계산식과 MES 데이터의 역할

부품사가 **직접 계산하는 몫**은 자기 공장 문 안(gate-to-gate)이고, 나머지는 받아서 더한다 [추정, T42·T44 구조 기반]:

```
PCF(품번 i, 기간 P, 선언단위 1개) =
  [ Σ_자재 m  투입질량(i,m) × 자재 PCF(m)          ← 공급사 PCF(1차) 또는 2차 DB 계수
  + Σ_에너지 e 사용량(i,e) × 배출계수(e)            ← 공장 몫 핵심: 에너지를 품번에 배분
  + 공정 직접배출(i)                                 ← 용접 보호가스(CO2), 냉매 누설 등
  + 폐기물 처리(i) + 입고 운송(i) ]
  ÷ 양품 수량(i, P)                                  ← 폐기분 부담을 양품이 진다
```

| PCF 입력 | MES(SmartHappy) 데이터 | 테이블(00 5.3 기준) | 결정 상태 |
|---|---|---|---|
| 양품 수량(분모) | 실적 `good_qty` | `PRD_RESULT` | P2 결정됨 |
| 폐기·재작업 | `scrap_qty`·`rework_qty` + 폐기 사유 | `PRD_RESULT`, 불량 유형 코드 2단 | P2 결정됨 |
| 자재 실투입 | LOT 계보 `rel_type=CONSUME`·`qty` | `LOT_GENEALOGY` | P2 결정됨, **단위·질량 환산 규칙 필요** |
| 투입 자재의 공급사·LOT | 수입 LOT ↔ 공급사 | `LOT_MASTER`, 수입검사 | P2~P3 |
| 설비 가동 시간(배분 키) | 상태 구간 원장 RUN 구간 | `cms_equip_state_log` | P2 결정됨, **`wo_id` 키 필요** |
| 설비별 에너지 | **없음** | 신규 계측점·계측 원장 | **이 문서 제안** |
| 사업장 에너지 총량(대사·Scope 2) | **없음** | 신규 고지서 실적 | **이 문서 제안** |
| 품목 질량(선언단위 환산, 질량 배분) | 품목 속성 | `mst_item` / `attrs` | **이 문서 제안** |
| 공정 직접배출(보호가스 등) | 부자재 투입으로 근사 가능 | `LOT_GENEALOGY` 또는 출고 | P4 이후 [추정] |

### 4.3 배분 방법 — SmartHappy 의 우선순위

ISO 14067 위계를 MES 데이터로 옮기면 다음 순서가 된다 [추정, T42·T45·T46 기반]:

1. **세분화(최선)** — 설비별 계량기가 있으면 `해당 설비 에너지 × (작업지시 i 의 RUN 시간 / 설비 전체 RUN 시간)`. 상태 구간 원장 + `wo_id` + 계측 원장이 있으면 바로 나온다. 연구 사례도 같은 방식(기계 단위 계측 + 작업지시 번호 결합) [검색] T45.
2. **물리적 관계** — 계량기가 공장·라인 단위뿐이면 그 총량을 **설비 RUN 시간 × 설비 정격(또는 표준) 전력**, 그것도 없으면 **질량**(양품 질량)이나 **수량**으로 배분. 섬유 공정 연구는 질량 배분이 무난하다고 결론 [검색] T46.
3. **비계측 잔여분**(조명·공조·압축공기·사무동) — 계량 합계와 고지서 총량의 차이. 회사 정책(`sys_policy`)으로 "가동시간 비례 / 질량 비례 / 제외(사업장 Scope 2 에만)" 선택.
4. **경제적 가치** — MES 범위 밖. 쓰지 않는다.

배분 규칙은 계산할 때마다 **규칙 코드 + 설명**을 결과에 남긴다 → PACT `allocationRulesDescription` 칸에 그대로 들어간다.

### 4.4 배출계수

- 전력: 국가 전력배출계수(Scope 2 location-based, 매년 갱신)와 **LCA용 전력 계수**(PCF)가 **다르다** [검색] T41. 계수 테이블은 `용도(SCOPE2/PCF) × 에너지 유형 × 적용 연도 × 출처 × 버전`으로 관리한다.
- 자재: 공급사 PCF(1차) → 없으면 2차 DB(국내 LCI DB, ecoinvent 등). **ecoinvent 는 라이선스 재배포 제한이 있어 제품에 계수를 번들하지 않는다** [추정 — 라이선스 원문 미확인, 계약 전 확인]. 고객사가 보유한 계수를 입력하게 한다.

---

## 5. 데이터 교환 표준

### 5.1 PACT Technical Specifications v3.0.3 (2025-11-18)

- **REST API** [원문] T47: OAuth2 client credentials 인증, `GET /3/footprints`(필터: productId 등), `GET /3/footprints/{id}`, `POST /3/events`(요청 생성·이행·거절·공표 이벤트). 검증(assurance)은 v3 에서 선택.
- **주요 데이터 항목** [원문] T48:

| PACT 항목 | 필수 | SmartHappy 출처 |
|---|---|---|
| `productIds` | SHALL | 품번 + 고객 품번(`mst_ext_id_map`) |
| `companyName`/`companyIds` | SHALL | `sys_comp` (+ 사업자번호 URN) |
| `declaredUnitOfMeasurement`/`declaredUnitAmount` | SHALL | 품목 기본 단위(piece/kg) |
| `productMassPerDeclaredUnit` | SHALL | **품목 단위 순질량(kg) — P1 에 필요** |
| `referencePeriodStart/End` | SHALL | 계산 기간(실적 `occurred_at` 범위) |
| `geographyCountry`/`geographyCountrySubdivision` | SHOULD | **공장 국가·ISO 3166-2 코드 — P1 에 필요** |
| `pcfExcludingBiogenicUptake`, `fossilGhgEmissions` | SHALL | 계산 결과 |
| `primaryDataShare` | SHALL | 계측 원장(1차) 비율 vs 추정 배분 비율 |
| `dqi`(기술·지리·시간 1~5) | SHALL(2027) | 계산 메타 |
| `allocationRulesDescription` | SHOULD | 4.3 규칙 코드·설명 |
| `exemptedEmissionsPercent`, `boundaryProcessesDescription` | SHALL/SHOULD | 계산 메타 |
| `verification`(제3자) | 선택 | 외부 검증 결과 첨부 |

→ **내부 모델은 PACT v3 항목 집합으로 한 번 정의**하고, 고객별 엑셀 서식·SCEMS 입력·CDP 20.12 는 그 모델에서 뽑는 **내보내기 템플릿**으로 처리한다 [추정].

### 5.2 Catena-X 와 중소 협력사 현실

- 운영사 Cofinity-X: 연 사용료가 매출 구간별(1억 유로 미만·거래상대 20곳 미만 연 1,500유로 등) + **커넥터(EDC) 별도**(연 1,200유로~), 앱 연 약 7,200유로, 통합비 별도. 협회 회원은 선택(소기업 연 2,000유로). 체험 환경 월 125유로 [검색] T49.
- 지원: Data Space Accelerator 최대 3만 유로(PCF·추적성 등 사용 사례) [검색] T49.
- 국내: KETA–Cofinity-X MOU(2026-05), LG유플러스·IBCT 원스톱 지원(2026-09), SK AX 온보딩 서비스, 국제지속가능인증원 Catena-X PCF 검증·인증 자격 [검색] T40.
- **판단 [추정]**: 중소 Tier 2/3 가 Catena-X 에 직접 붙을 일은 드물다. 유럽 완성차와 직접 거래하는 Tier 1 이나 국내 플랫폼(KADaP-X, 산업 공급망 탄소데이터 플랫폼)이 중계할 가능성이 크다. SmartHappy 는 **PACT v3 JSON 을 정확히 만들어 내는 것**까지만 하고, 커넥터는 고객이 고른 서비스(IBCT·SK AX·국내 플랫폼)에 맡긴다. 그래도 PACT 데이터 모델이 Catena-X PCF 모델과 정렬돼 있어 손실이 거의 없다.

---

## 6. SmartHappy 반영안 (테이블·키 수준)

테이블명은 01 소문자 규칙. 회사별 테이블은 전부 `comp_cd` + RLS. 원장은 01 3.1 규약.

### 6.1 지금 잡을 데이터 키 (P1~P2)

| 테이블·키 | 내용 | 시점 | 비고 |
|---|---|---|---|
| **`mst_meter`** (신규) | `meter_cd`, `energy_type`(ELEC/LNG/LPG/DIESEL/AIR/STEAM/WATER), `unit`(kWh/Nm³/L…), `hier_node_id`(`mst_equip_hier` 의 공장·라인·설비 어느 노드든), `parent_meter_cd`(하위 계량), `is_bill_meter`(한전 등 과금 계량기), `use_yn` | **P1** | 계량기가 하나도 없는 공장도 "공장 = 고지서 계량기 1개"로 등록 |
| `mst_item` 컬럼 | **`net_mass_kg`**(단위 순질량), **`hs_cd`**(HS/CN 10자리) | **P1** | jsonb `attrs` 가 아닌 **정식 컬럼** 권장(PACT SHALL·CBAM 판정·질량 배분에 쓰임). 결정 D3 |
| `sys_plant` 컬럼 | `country_cd`(ISO 3166-1), `subdivision_cd`(ISO 3166-2, 예 KR-48) | **P1** | PACT geography |
| `cms_equip_state_log` 키 | **`wo_id`**(그 구간에 걸린 작업지시, null 허용) | **P2** | 없으면 실적 시각으로 사후 조인해야 하고 겹침·교대에서 틀린다. 결정 D4 |
| `LOT_GENEALOGY` 규칙 | `CONSUME` 의 `qty` + `uom` 저장, 품목에 질량 환산(`net_mass_kg` 또는 단위 환산) 가능 보장 | **P2** | BOM 표준이 아닌 **실투입** |
| `PRD_RESULT` | `scrap_qty`·`rework_qty`·폐기 사유 코드(이미 결정) | P2 | 변경 없음. 폐기 부담 → 양품 분모 |
| **`cms_energy_reading`** (신규, 원장) | `meter_cd`, `period_start`, `period_end`, `qty`(구간 사용량), `reading_src`(COLLECTOR/MANUAL/BILL), `occurred_at`, `recorded_at`, `reversal_of_id` | **P2 테이블** / 수집은 P6 Collector 또는 수기 | 원장 7종으로 할지 결정 D2. 누적값이 아닌 **구간 사용량** 저장(계량기 교체·리셋 문제 회피) |
| Collector 표준 이벤트 | 계량값 이벤트 유형(`METER_READING`) 포함 | **P2**(스키마 확정 시) | 00 6장 "Collector 표준 이벤트 스키마 확정"에 끼워 넣기만 |
| `env_energy_bill` (신규) | 사업장·월·에너지 유형별 고지서 사용량·금액 + **증빙 첨부 파일** | P2~P4 | SAQ 증빙·Scope 2 근거, 계측 합계와 대사(불일치는 알림만) |

**P1~P2 비용 추정 [추정]**: 신규 테이블 2개(+P2 원장 1개), 컬럼 5개, 키 1개. 화면은 기준정보 그리드에 끼워 넣는 수준이고, 계산 로직은 없다. 늦게 넣으면 **과거 실적에 `wo_id`·질량이 없어 소급 계산이 불가능**하므로 지금 넣는 게 싸다.

### 6.2 나중 기능

| 기능 | 내용 | 시점 |
|---|---|---|
| 배출계수 | `env_emission_factor`(전역 기본값: 용도·에너지 유형·연도·출처·버전) + 회사별 덮어쓰기 | P5 |
| 원단위 리포트 | 설비·라인·품번별 kWh/개, kWh/kg — ISO 22400 Amd 1 에너지 KPI 와 같이 `sys_kpi_def` 로 | P5 |
| 사업장 Scope 1·2 | 연간·월간 집계(고지서 × 계수), 재생전력 증빙 첨부 | P5~P6 |
| **고객별 배분표** | 고객·품목별 출하 수량 × 배분 기준 → CDP 20.12·고객 서식 엑셀 | P6 |
| **gate-to-gate PCF** | 4.2 식, 4.3 배분 위계, 결과 스냅샷 `env_pcf_calc`(입력 버전·규칙·PDS·DQI 보관, 재계산은 새 버전) | P6 |
| **PACT v3 내보내기** | JSON 파일 내보내기 → 그다음 `/3/footprints` 읽기 API(고객 요청 시), 송신은 아웃박스 | P6 |
| 공급사 PCF 수신 | SRM 에서 공급사 PACT JSON·엑셀 수신 → 자재 계수로 사용, 수신 멱등 `sys_inbox` | P5(SRM)~P6 |
| CBAM 데이터 시트 | HS/CN 코드 해당 품목의 기간별 생산량·에너지 → EU 수입자용 커뮤니케이션 템플릿 | 2차(하류 확장 확정 시) |
| 반도체 2차 | 재료 선언(00 6장)과 같은 자리에서 PCF 제공, 고객 포털 서식 | 2차 |

---

## 7. 하지 말 것

| 하지 말 것 | 이유 |
|---|---|
| **자체 LCA 도구·LCI DB 개발/번들** | 전문 영역(SimaPro·GaBi·국내 전문업체). ecoinvent 등은 재배포 라이선스 문제. MES 는 **1차 활동 데이터 공급자**로 남는다 |
| "인증 PCF", "검증된 탄소발자국" 표기 | 검증은 제3자(Catena-X/TfS 검증 체계, 인증기관) 몫. 제품 화면·보고서에는 "산정값(미검증)" 표기 |
| FEMS·피크 제어·실시간 탄소 대시보드 | 03 2.8 결정 유지. 한국 전력계수는 **연 단위**라 실시간 탄소 강도의 의미가 작다 |
| Catena-X 커넥터(EDC)·데이터 스페이스 자체 운영 | 비용·인증·운영 부담. PACT JSON 까지만, 연결은 파트너 서비스 |
| 고객사 Scope 3 전체 산정 대행 | 범위 밖(구매·출장·사용 단계 등). 부품사 자기 몫(cradle-to-gate)과 배분표까지만 |
| 블록체인 이력 | 00 6장 "하지 않는 것"과 동일. 위변조 방지는 원장 규약(역분개·수정 차단)과 감사로그로 충분 |
| 탄소 배출권 거래·상쇄 기능 | 금융·규제 영역 |
| 규칙 계산을 "AI 탄소 산정"으로 표기 | 03 원칙(규칙·최적화를 AI 로 표기하지 않음) |
| 계량기 없는 설비에 "추정 1차 데이터" 표시 | PDS 를 부풀리는 그린워싱 위험. 추정 배분분은 2차로 분류 |

---

## 8. 원래 세션에 넘길 결정

| # | 결정 사항 | 권장 |
|---|---|---|
| D1 | 탄소 데이터를 00 로드맵에 넣는 방식 | 6.1 의 **데이터 키만 P1~P2 에 추가**, 기능은 P5~P6. 00 0.1 결정표에 "ESG·탄소: 키 먼저, LCA 는 하지 않음" 1줄 |
| D2 | `cms_energy_reading` 을 **원장 7종**으로 넣을지 | 넣는다(정정은 역분개, 소급 입력 통제). 01 3.1 의 "6종" 문구 변경 필요 |
| D3 | 품목 질량·HS 코드를 정식 컬럼으로 | 정식 컬럼(`net_mass_kg`, `hs_cd`). jsonb 는 업종별 선택 속성만 |
| D4 | 설비 상태 구간에 `wo_id` | 넣는다(null 허용). OEE 성능 계산에도 같은 키가 필요 |
| D5 | 배출계수는 전역 테이블(제품 운영자가 매년 12월 갱신) + 회사별 덮어쓰기 | 전역 테이블은 Flyway 시드가 아닌 **운영자 관리 데이터**(매년 바뀜). 01 의 "전역 = Flyway 로만" 규칙과의 관계 정리 필요 |
| D6 | 내부 PCF 모델 = PACT v3 항목 집합 | 고객 서식·SCEMS·CDP 는 내보내기 템플릿 |
| D7 | 데모 시나리오(00 6장 "용접 조립 부품 공장")에 에너지 계량 1~2개 포함 | 포함 권장 — 영업 시 "협력사 탄소 데이터 대응"을 보여 주는 가장 싼 방법 |
| D8 | docs/02 의 ISO 22400 Amd 1(에너지 KPI)과 03 2.8 원단위 리포트를 이 문서 6.2 와 하나로 묶을지 | 묶는다(P5 KPI 와 같은 자리) |

---

## 9. 참고문헌

확인 수준은 본문 표기와 같다. 접속 차단(403)된 원문은 [검색].

- T01. 넷제로뉴스, 현대차 협력사 ESG 평가(2024 지속가능성보고서 인용) — http://www.net-zero.kr/news/articleView.html?idxno=1285 [원문]
- T02. 현대자동차 2026 지속가능성보고서 — https://www.hyundai.com/content/dam/hyundai/kr/ko/data/company-report/2026/07/15/hmc-sr-kor-2026.pdf [검색]
- T03. 현대차그룹 뉴스, 협력사 탄소배출 이력관리 시스템(SCEMS)·CDP 공급망 — https://www.hyundaimotorgroup.com/ko/news/CONT0000000000103217 [원문]
- T04. 현대차그룹 뉴스, 현대모비스 공급망 ESG — https://www.hyundaimotorgroup.com/ko/news/hyundai-mobis-supplier-payment-157-trillion-supply-chain-esg [검색]
- T05. 카본링크 블로그(제3자 검증 의무 주장, 미확인) — https://blog.carbonlink.co.kr/151715 [검색]
- T06. 현대자동차 2022 지속가능성보고서 — https://www.hyundai.com/content/dam/hyundai/kr/ko/images/company-intro/sustain-manage/2022/hmc-sr-kor-0704-v06.pdf [검색]
- T07. 정책브리핑, 중기부·산업부·현대차·기아 공급망 탄소감축 — https://www.korea.kr/briefing/pressReleaseView.do?newsId=156728700 [검색]
- T08. Volkswagen Group Annual Report 2025, Sustainability in the supply chain — https://annualreport2025.volkswagen-group.com/sustainability-report/social-information/sustainability-in-the-supplychain.html [원문]
- T09. VW Group Supply, Environment — https://www.vwgroupsupply.com/one-kbp-pub/en/kbp_public/information/nachhaltigkeit_neu_pub_2019/environment/environment_1.html [검색]
- T10. Drive Sustainability SAQ supplier guide — https://www.csrdpro.com/en/drive-sustainability-saq-supplier-guide/ [원문]
- T11. Mercedes-Benz Ambition 2039 supply chain — https://www.prnewswire.com/news-releases/next-milestone-ambition-2039-the-global-mercedes-benz-supply-chain-is-becoming-co2-neutral-301187302.html [검색]
- T12. Climate Week NYC, BMW Group interview — https://www.climateweeknyc.org/news/qa-bmw-group-vp-sustainability-and-mobility-strategy-bmw-group-dr-thomas-becker-driving [검색]
- T13. Nikkei Asia, Toyota 3% — https://asia.nikkei.com/business/automobiles/toyota-asks-main-suppliers-to-cut-co2-emissions-by-3-this-year ; Toyota NA Environmental Report 2025 — https://www.toyota.com/content/dam/tusa/environmentreport/downloads/2025NAER_Final_2.pdf [검색]
- T14. GM — https://news.gm.com/home.detail.html/Pages/news/us/en/2022/apr/0425-gm.html ; Ford CDP — https://corporate.ford.com/content/dam/corporate/us/en-us/documents/reports/ford-cdp-climate-water-report.pdf [검색]
- T15. Samsung Electronics, 협력사 기후 대응 — https://www.samsung.com/global/sustainability/popup/popup_doc/AYUqwJLKD38AIx_C [원문]
- T16. Samsung Electronics Sustainability Report 2025/2026 — https://www.samsung.com/global/sustainability/media/pdf/Samsung_Electronics_Sustainability_Report_2026_ENG.pdf [검색]
- T17. SK hynix 뉴스룸, 협력사 ESG 자가평가 — https://news.skhynix.co.kr/partners-esg-self-assessment/ [원문]
- T18. SK하이닉스 지속가능경영보고서 2024 — https://kind.krx.co.kr/external/2024/07/23/000345/20240723001065/SK%ED%95%98%EC%9D%B4%EB%8B%89%EC%8A%A4%20%EC%A7%80%EC%86%8D%EA%B0%80%EB%8A%A5%EA%B2%BD%EC%98%81%EB%B3%B4%EA%B3%A0%EC%84%9C%202024.pdf ; 글로벌이코노믹 2026-10 — https://www.g-enews.com/article/Industry/2026/10/2026100809333857063084322ec9_1 [검색]
- T19. Apple Newsroom 2022-10 — https://www.apple.com/newsroom/2022/10/apple-calls-on-global-supply-chain-to-decarbonize-by-2030/ [원문]
- T20. TSMC ESG — https://esg.tsmc.com/en-US/articles/340 [검색]
- T21. SEMI Semiconductor Climate Consortium — https://www.semi.org/en/industry-groups/semiconductor-climate-consortium ; SEMI S23 — https://www.semiconductor-digest.com/semis-s23-standard-save-energy-save-money-save-the-planet/ [검색]
- T22. CDP SME Questionnaire Modules 14-21 — https://assets.ctfassets.net/v7uy4j80khf8/5jaubP76UEYtQfASBd9JgK/b443421754a817ec84e2a45584037f03/SME_Questionnaire_Modules_14-21.pdf [검색]
- T23. 더브리프, 대한상의·산단공 수출기업 300개사 조사(2026-09-12) — https://www.thebriefai.kr/news/articleView.html?idxno=13427 [원문]
- T24. 이데일리, 중기중앙회 ESG 조사(2022) — https://edaily.co.kr/News/Read?mediaCodeNo=257&newsId=02922486632357784 [원문]
- T25. Catena-X PCF Rulebook v3.0 — https://catenax-ev.github.io/assets/files/CX-NFR-PCF-Rulebook_v.3.0-04874a80a6d27511df06e07ae3049278.pdf ; v4 — https://catena-x.net/wp-content/uploads/2025/10/Catena-X-Product-Carbon-Footprint-Rulebook_v4-with-line-numbers.pdf [검색]
- T26. 뉴스핌, 2026 국감 중기부 탄소 지원 — https://www.newspim.com/news/view/20261007001502 [원문]
- T30. European Commission, Carbon Border Adjustment Mechanism — https://taxation-customs.ec.europa.eu/carbon-border-adjustment-mechanism_en [원문] (확정기간 2026-01-01·대상 부문만)
- T31. Norton Rose Fulbright, CBAM 간소화 — https://www.nortonrosefulbright.com/en/knowledge/publications/00216f5d/new-cbam-legislative-proposal-major-reduction-of-cbam-scope-and-burden ; Karbonet CBAM 일정 — https://karbonet.com/cbam-deadlines [검색]
- T32. DecarbonPro, CBAM 하류 확장 의회 입장 2026 — https://www.decarbonpro.com/en/articles/cbam-downstream-extension-parliament-position-2026 [검색]
- T33. EU 배터리 규정 여권·탄소발자국 지연 — https://environmentenergyleader.com/stories/battery-passport-becomes-mandatory-february-18-2027,142747 ; https://www.battery.mba/resources/eu-battery-regulation ; https://psqr.eu/publications-resources/carbon-footprint-batteries/ [검색]
- T34. Clifford Chance, Omnibus I CSDDD·CSRD — https://www.cliffordchance.com/insights/resources/blogs/business-and-human-rights-insights/2026/02/omnibus-i-the-european-union-concludes-csddd-and-csrd-reforms.html ; Covington, OJ 게재 — https://www.cov.com/en/news-and-insights/insights/2026/02/eu-csddd-csrd-omnibus-published-in-official-journal-transposition-delegated-acts-and-guidelines-are-next [검색]
- T35. European Commission, Corporate sustainability due diligence — https://commission.europa.eu/topics/business-and-industry/company-law-and-corporate-governance/corporate-sustainability-due-diligence_en [검색]
- T36. ESPR 작업계획 2025–2030·위임법 현황 — https://dpp-tool.com/en/blog/espr-delegated-acts/ ; https://www.eadtrust.eu/en/blog/espr-2025-2030-working-plan/ [검색]
- T37. 금융위원회, 지속가능성 공시 제도화 방안(최종안) — https://www.fsc.go.kr/no010101/87280 ; 파이낸셜뉴스 2026-07-08 — https://www.fnnews.com/news/202607081447169796 [검색]
- T38. ESG경제, 중기부 공급망 트랙 — https://www.esgeconomy.com/news/articleView.html?idxno=15209 ; 뉴시스 — https://www.newsis.com/view/NISX20260410_0003586844 [검색]
- T39. 산업부 산업 공급망 탄소데이터 플랫폼 — https://pcccr.go.kr/base/board/read?boardManagementNo=43&boardNo=3564 ; 산업부 보도자료 2024-07-25 — https://www.motir.go.kr/attach/down/095a2dda9c864e1d90d751f7668a1117/0814650a95a97f4f89b5085b51ba5dea [검색]
- T40. 국내 Catena-X 동향 — 에너지신문 https://www.energy-news.co.kr/news/articleView.html?idxno=215540 ; 벤처스퀘어(KETA–Cofinity-X) https://www.venturesquare.net/1082008 ; 시장경제(LG유플러스) https://www.meconomynews.com/news/articleView.html?idxno=201992 ; SK AX https://www.skax.co.kr/insight/trend/3670 ; 이넷뉴스(IGSC) https://www.enetnews.co.kr/news/articleView.html?idxno=47339 [검색]
- T41. 정책브리핑, 전력배출계수 갱신 주기 1년 — https://www.korea.kr/briefing/pressReleaseView.do?newsId=156735726 ; EG-TIPS 전력 배출계수 — https://tips.energy.or.kr/carbon/Ggas_tatistics03.do [검색]
- T42. ISO 14067:2018 본문 사본(배분 6.4.6.2) — https://www.ditan.com/static/upload/file/20240522/1716345585128585.pdf ; 부분 CFP 설명 — https://greencalculus.com/methodology/iso-14067-cradle-to-gate-methodology/ [검색]
- T43. GHG Protocol Product Life Cycle Accounting and Reporting Standard — https://ghgprotocol.org/sites/default/files/standards/Product-Life-Cycle-Accounting-Reporting-Standard_041613.pdf [검색]
- T44. WBCSD PACT Methodology v3.0 — https://www.wbcsd.org/resources/pact-methodology-version-3/ ; PDF https://docs.carbon-transparency.org/tr/methodology/3.0/pact-methodology-v3.0.pdf ; 정렬 체크리스트 https://wbcsd.github.io/tr/websitedocs/PACT_Methodology_Alignment_Checklists.pdf [검색]
- T45. Carbon Footprint for SMEs: An Open-Source Framework for Real-Time Monitoring in Manufacturing (Springer, 2026) — https://link.springer.com/chapter/10.1007/978-3-032-21157-6_19 [검색]
- T46. Li, Chen & Ding, Allocation Methodology of Process-Level Carbon Footprint Calculation (Sustainability 2019) — https://doi.org/10.3390/su11164471 [검색]
- T47. PACT Technical Specifications v3.0.3 — https://wbcsd.github.io/tr/data-exchange-protocol/latest/ [원문]
- T48. PACT Simplified Data Model v3 — https://docs.carbon-transparency.org/tr/data-exchange-protocol/3.0/pact-simplified.html [원문]
- T49. Cofinity-X 가격 — https://www.cofinity-x.com/price-info ; Catena-X Adopter Journey — https://userjourney.catena-x.net/ [검색]

---

## 10. 검토 결과 (원래 세션, 2026-10-16)

결론("LCA 도구가 아니라 **원장에 배분 키를 남기는 것**")에 동의한다. 키 몇 개는 늦게 넣으면 과거 데이터에 소급할 수 없으므로 P1~P2 에 넣고, 계산·보고 기능은 포트폴리오 범위에서 후순위로 둔다.

| # | 판정 | 반영 |
|---|---|---|
| D1 로드맵 방식 | **채택** | 00 0.1 결정표에 "ESG·탄소: 데이터 키 먼저(P1~P2), LCA 는 하지 않음" 1줄 |
| D2 `cms_energy_reading` 원장 | **채택(P2)** | 원장 규약(01 3.1) 대상에 추가. 원장 목록은 숫자("6종") 대신 `TenantCatalogInspector` 원장 목록을 원본으로 한다 |
| D3 `net_mass_kg`·`hs_cd` 정식 컬럼 | **채택(P1 품목)** | jsonb `attrs` 는 업종별 선택 속성만 |
| D4 설비 상태 구간 `wo_id` | **채택(P2)** | OEE 성능 계산에도 필요 |
| D5 배출계수 전역 데이터 | 보류(P5) | "전역 = Flyway 로만" 규칙과 충돌 — 운영자 콘솔(9단계)에서 관리하는 전역 참조 데이터라는 새 분류가 필요. P5 에서 결정 |
| D6 PCF 모델 = PACT v3 | 채택(P6) | — |
| D7 데모 시나리오에 계량기 | **채택** | 데모 데이터에 공장 고지서 계량기 1개 + 라인 계량기 1개(`mst_meter` 가 생기는 P1 이후) |
| D8 ISO 22400 에너지 KPI 묶기 | 채택(P5) | — |

`mst_meter`·`sys_plant.country_cd/subdivision_cd` 는 P1 장비 계층(`mst_equip_hier`)과 같은 단계에 넣는다.
