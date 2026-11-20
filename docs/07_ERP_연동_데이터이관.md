# 07. ERP 연동·기준정보 이관 — 국내 중소 제조사 ERP 조사와 SmartHappy 어댑터 설계안

> 작성일 2026-10-15 · **기준 커밋 `b2f4f87`** (P0 4단계까지). 원래 세션의 P0 5단계 진행분은 평가하지 않는다.
> 성격: 백그라운드 조사 결과. **코드·00~05·skill 은 수정하지 않았다.** 원래 세션이 검토 절을 붙인 뒤 00/01 반영 여부를 사용자와 정한다.
> 확인 수준: **[원문]** 1차 출처 페이지를 직접 열어 확인 / **[검색]** 검색 결과 요약으로 확인, 인용 전 원문 재확인 권장 / **[추정]** 조사자 판단. 참고문헌 번호는 9장 `E##`.
> 일반적인 연동 패턴(SAP OData, REST 양방향, 엑셀 업로드)을 공개 자료 기준으로 정리했다.

---

## 1. 요약 — 결론과 우선순위

**한 줄 결론**: 국내 중소 제조사 ERP 중 **공개 API 로 MES 업무 데이터(품목·작업지시·생산입고·재고)를 주고받을 수 있는 것은 이카운트와 SAP Business One 정도**다. 더존(iCUBE·Amaranth10·Smart A)·영림원(K-System·시스템베이/SystemEver)은 **공개 사양이 없고**, 구축사·대리점을 통한 **중간(인터페이스) 테이블 + 에이전트** 또는 **엑셀 양식 업로드**가 현실적인 경로다. 따라서 첫 고객 도입 비용을 가장 크게 줄이는 것은 특정 ERP 어댑터가 아니라 **"엑셀 이관·엑셀 내보내기 + 중간 테이블 어댑터" 범용 2종**이다.

### 1.1 먼저 할 것 Top 7

| # | 할 것 | 시점 | 근거 |
|---|---|---|---|
| 1 | **엑셀 이관 엔진**(업로드 → 스테이징 → 검증 → 미리보기 → 실행 → 대사 리포트)을 P0 6단계 `excel/` 에 **기준정보 이관 용도까지 견딜 구조**로 | P0 6단계 | 모든 ERP 공통 최소 경로. 더존·영림원 클라우드는 사실상 이것뿐 (2.1) |
| 2 | **`COPY FROM` 은 RLS 테이블에 못 쓴다** → 스테이징은 트랜잭션 범위 TEMP 테이블(또는 배열 `unnest` INSERT) | P0 6단계 | PostgreSQL 16 공식 문서 [원문] E20 |
| 3 | `mst_ext_id_map`(02 확정, P1)을 **어댑터·엑셀 이관 공용 키 매핑**으로 확정, 코드 매핑(`if_code_map`: 단위·창고·거래유형) 추가 | P1 | 이카운트 생산입고 단위 오매핑 사례 [검색] E05 |
| 4 | **`ErpPort` + 어댑터 SPI** 만 P1 말에 정의하고 구현은 2종부터: ① 엑셀 파일 어댑터(내보내기) ② **이카운트 OpenAPI** | P1 말 ~ P2 | 이카운트 = 중소 제조 고객 수 최대·API 무료 [원문] E01 |
| 5 | **중간 테이블 어댑터**(고객 DB 의 `IF_*` 테이블에 쓰기·읽기, 플래그 방식) — 더존 iCUBE·K-System·자체 ERP 공용 | P6 (첫 수요 시) | iCUBE 자동전표 중간테이블, K-System DB to DB [검색] E08·E10 |
| 6 | SAP B1 Service Layer 어댑터는 **첫 B1 고객이 생길 때** | P6 | OData v4, 세션 쿠키, 간접 접근 라이선스 비용은 고객 몫 [검색] E14·E16 |
| 7 | ERP 자격증명은 `sys_ext_credential`(회사별, 앱 레벨 봉투 암호화, 마스터 키는 DB 밖) + **외부 송신 고정 IP** 운영 | P6 전 (SaaS 호스팅 결정 때) | 이카운트는 등록 IP 에서만 호출 허용 [검색] E06 |

### 1.2 1인 개발 어댑터 우선순위

```
P0 6단계  엑셀 업로드 엔진(이관 겸용) + 엑셀 다운로드           ← 모든 고객
P1        기준정보 이관 템플릿(품목·BOM·거래처·창고·라우팅) + ext_id_map
P1 말     ErpPort 인터페이스 · if_history 화면(재전송) · 아웃박스 ERP 채널 골격
P2        ① 실적·입고 "ERP 업로드용 엑셀 내보내기" 어댑터(ERP 별 양식 프로필)
          ② 이카운트 OpenAPI 어댑터(품목 조회, 작업지시·생산불출·생산입고 입력)
P6        ③ 중간 테이블 어댑터(JDBC, 고객 사내 에이전트 경유)  ④ SAP B1 Service Layer
          ⑤ 그 밖(더존 계약 API, 영림원 OpenAPI)은 고객·구축사 계약이 생기면
```

이유: ①은 구현이 가장 싸고 모든 ERP 에 통한다. ②는 공개 문서·무료·SaaS 끼리 연동이라 설치 작업이 없다. ③은 고객 사내망·DB 계정·구축사 협조가 필요해 건당 비용이 크므로 수요가 확인될 때. ④는 B1 고객 비중이 중소 제조에서 낮다 [추정].

---

## 2. ERP 별 연동 수단

### 2.1 비교표

| ERP (형태) | 공개 API | 다른 경로 | 인증 | 주요 제약 | 확인 |
|---|---|---|---|---|---|
| **이카운트** (SaaS) | ✅ OpenAPI — 거래처·품목 입력, 품목 조회, 견적·주문·판매, 발주 조회·구매 입력, **작업지시·생산불출·생산입고 입력**, 재고현황·창고별 재고 조회 등. **추가 요금 없음** | 엑셀 업로드 | 회사코드 → Zone 조회 → 로그인(회사코드·사용자ID·API 인증키) → `SESSION_ID` 를 URL 에 넣어 POST | 인증키는 최고관리자만 발급, **허용 사용자 ID 에 묶임**, 유효기간 1년(연장 버튼), 테스트 키(`sboapi`) → 검증 후 정식 키(`oapi`), **등록 IP 에서만 호출**, 호출 간격 제한(저장 10초에 1회 등 — 비공식), 1회 저장 최대 건수 제한, 응답 `QUANTITY_INFO` 로 남은 허용량 확인 | 목록·요금 [원문] E01 / 흐름 [검색] E02·E03 / 제한 수치 [검색·비공식] E04 / IP [검색] E06 / 연장 [검색] E07 |
| **SAP Business One** (설치형·파트너 호스팅) | ✅ **Service Layer** (OData v4, `/b1s/v2`) — `Items`, `ProductTrees`(BOM), `ProductionOrders`, `InventoryGenExits`/`InventoryGenEntries`(BaseType 202 = 생산 불출·입고), 판매·구매 문서 | DI API(COM, Windows), 엑셀(DTW) | `POST /Login` (CompanyDB·UserName·Password) → `B1SESSION`·`ROUTEID` 쿠키, 비활동 30분 만료 → 401 시 재로그인 | **간접 접근(Indirect Access) 라이선스** 필요(Service Layer 는 기술적으로 검사하지 않지만 계약상 필요), 매 호출 로그인 금지(라이선스·부하), 고객사 서버 → 방화벽·인증서 | 세션 [검색] E14 / 엔티티 [검색] E15 / 라이선스 [검색] E16 |
| **더존 iCUBE** (설치형, MS-SQL) | ❌ 공개 없음 | **자동전표 중간테이블**(회계, `SAUTODOCUD` 계열) — 더존은 테이블 오픈·전표 발행만, 적재 작업은 타 시스템 측 책임. 제품 구성상 MES/POP·WMS "내부연동" 모듈 존재. **원본 테이블 직접 접근은 제한·일부 암호화**, 유료 API 제공 사례(비용 수백만 원 언급) | 중간테이블 DB 계정(더존·대리점 발급) | 물류·생산 IF 명세 비공개 → 대리점 요청, 버전별 차이 | 연동 모듈 [원문] E08 / 중간테이블 [검색] E09 / 커뮤니티 증언 [검색·비공식] E10 |
| **더존 Amaranth10** (클라우드·설치형) | ❌ 공개 포털 없음 | 계약·파트너 경유 API(전자결재 interlock 등 커뮤니티 언급), 설치형은 "기존 시스템 연동·추가 개발 가능"(제안서) | 비공식 분석상 토큰 + HMAC 서명 헤더 | **화면용 내부 API 를 역분석해 쓰지 말 것**(약관·버전 변경 위험) | [검색] E11·E12 |
| **더존 Smart A 10** (소규모, 설치형/WEHAGO) | ❌ | **엑셀 양식 업로드**(전표·물류 입출고, 타사 데이터 항목 자동 맞춤 기능 발표) | — | MES 직접 연동 공식 자료 없음 | [검색] E13 |
| **영림원 K-System** (설치형 중심, 중견) | △ **K-System OpenAPI** — ACE 이상은 함께 설치, 그 이하는 구축사에 설치 요청. "Ksystem API 등록" 화면에서 외부 시스템 인증 ID·키와 **SP 공개 설정** | 영림원 자체 MES 와는 **DB to DB**, 데이터 연동 도구(DataManager), Stitch API Hub(외부 서비스용) | 외부 시스템 인증 ID + 키, 호출 대상은 공개 설정한 저장 프로시저 | 고객사 방화벽 안이면 중계 서버 IP 허용 필요, 명세는 구축사 제공 | OpenAPI [원문] E17 / DB to DB·도구 [검색] E18 |
| **영림원 SystemEver** (SaaS, 소규모) | ❌ **FAQ: "API 제공되지 않습니다"**(2022 갱신) | 영림원이 정한 표준 연동(전자결재·세금계산서·은행 등)만, 엑셀 | — | 고객 맞춤 기능 추가 불가 | [검색] E19 (페이지 직접 열람은 403) |
| 기타 (UNIERP, 비젠트로, 자체 ERP, 회계사랑·세무사랑 등) | 제품별 | 중간 테이블 / 엑셀 | — | 같은 연계 업체도 ERP 별로 "API·에이전트(Temp DB)·엑셀" 3방식으로 나눠 지원 | 비즈플레이 문서 구조 [원문] E21 |

### 2.2 시장 참고 (점유율)

- 중소기업만 따로 집계한 공식 점유율은 찾지 못했다. 국내 ERP 전체(한국IDC, 2024 신규계약 매출 기준): SAP 19.6%, 더존비즈온 14.7%, 영림원 4.5% [검색] E22.
- 이카운트는 점유율 집계에 없지만 **유료 기업 고객 8만 곳 이상(2024.12), 제조업 약 41.6%** [검색] E23 → 고객 **수** 로는 소규모 제조사에서 가장 자주 마주칠 ERP 로 본다 [추정].
- 더존은 매출·지원망 기준 중소·중견 1위로 평가되지만 제품이 여럿(iCUBE·Amaranth10·Smart A·WEHAGO)이라 **"더존 어댑터" 하나로 묶을 수 없다** [추정].

### 2.3 연동 방식 4가지와 SmartHappy 관점 평가

| 방식 | 장점 | 단점 | SmartHappy 판단 |
|---|---|---|---|
| **REST/OData API** (이카운트, B1) | 사양 공개, 상대 업무 규칙(검증·전표 생성)을 거침, 아웃바운드만 | 호출 제한·세션 관리, IP 등록 | **1순위** — 아웃박스 3단 릴레이와 정확히 맞음 |
| **중간 테이블**(인터페이스 테이블 + 처리 플래그) | 국내 구축 관행, ERP 업무 규칙은 ERP 측 배치가 적용 | 고객 DB 접속 필요, 명세 비공개, 상태 동기화 지연 | **에이전트 경유로만**(5.5). SaaS 가 고객 DB 에 인바운드로 붙지 않음 |
| **원본 테이블 직접 연계** | 빠름 | ERP 업무 규칙 우회, 버전업에 깨짐, 더존은 제한·암호화, 보증 상실 | **하지 않는다**(7장) |
| **엑셀/CSV 파일** | 모든 ERP, 구현 최저 비용, 사람이 검토 가능 | 수작업, 실시간 아님, 중복 업로드 위험 | **기본 경로**(P0 6단계부터) |

---

## 3. MES ↔ ERP 표준 연동 데이터 범위

### 3.1 데이터별 방향·주기·키

ISA-95 Part 2/5(B2MML)의 경계와 국내 관행을 합친 표준안 [검색] E24·E25 + [추정]. "주인(System of Record)"을 한 곳으로 정하는 것이 핵심이다.

| 데이터 | 방향 | 주인 | 주기(기본) | 키 | 비고 |
|---|---|---|---|---|---|
| 품목(자재·반제품·제품) | ERP → MES | ERP | 변경 시 또는 일 1회 | ERP 품목코드 ↔ `mst_ext_id_map` | MES 전용 속성(LOT 정책·검사 규격·라벨)은 MES 가 주인 — **덮어쓰지 않는 필드 목록**을 둔다 |
| 단위·환산 | ERP → MES | ERP | 품목과 함께 | 단위코드 매핑 | BOX/EA 오매핑이 실제 사고 원인(E05) |
| BOM | ERP → MES | ERP(설계 BOM) / MES(제조 BOM 보정) | 변경 시 | 모품목+리비전 | 다단 저장(00 3장), ERP 가 평면이면 1레벨로 받음 |
| 라우팅·공정 | 대개 MES 주인 | MES | — | — | 중소 ERP 는 라우팅이 약하거나 없음 [추정] |
| 거래처 | ERP → MES | ERP | 일 1회 | 사업자번호 + ERP 코드 | 협력사 평가(SRM)는 MES 측 |
| 창고·로케이션 | ERP 창고 → MES, 로케이션은 MES | 혼합 | 변경 시 | 창고코드 매핑 | ERP 창고 1 : MES 로케이션 N |
| 수주 | ERP → MES | ERP | 확정 시·수 분 | 수주번호+행 | 계획 참고용, MES 에서 수정 금지 |
| 생산지시(오더) | ERP → MES **또는** MES 자체 | 고객 정책 | 확정(Release) 시 | ERP 오더번호 ↔ 작업지시 | 정책 `ERP_WO_SOURCE` = ERP/MES (5.6) |
| 작업 실적(양품·불량) | MES → ERP | MES | **작업지시 완료 또는 교대 마감 단위 합산** | 아웃박스 `biz_key` = 실적 묶음 ID | 실시간 건건 송신 대신 합산 — ERP 호출 제한·전표 폭증 방지 |
| 자재 투입(불출) | MES → ERP | MES | 실적과 같은 묶음 | 같은 묶음 ID | B1 은 GenExit(BaseType 202), 이카운트는 생산불출 |
| 생산 입고 | MES → ERP | MES | 실적과 같은 묶음 | 같은 묶음 ID | 이카운트 생산입고, B1 GenEntry |
| 구매 입고·수입검사 결과 | MES(WMS/QMS) → ERP | MES | 입고 확정 시 | 입고번호 | 발주는 ERP → MES(조회) |
| 출하 | MES → ERP | MES | 출하 확정 시 | 출하번호 | 판매 전표는 ERP 가 생성 |
| 재고 | **대사만** (양방향 비교) | 품목·창고별 정책 | 일 마감 | 품목×창고 | 원장 동기화가 아니라 **차이 리포트** (4.6) |
| 원가 | MES → ERP (실적·투입·공수) | ERP 가 계산 | 월 마감 | — | MES 는 원가를 계산하지 않음 |

### 3.2 실패 처리 관행 → SmartHappy 규칙

국내 관행은 인터페이스 테이블 `처리 플래그 N/P/Y/E + 오류 메시지 + 재시도 횟수` 와 모니터링 화면의 재전송이다 [검색] E25. SmartHappy 에서는:

1. **송신 = 아웃박스**(01 5.8): 업무 저장과 같은 트랜잭션에 `sys_outbox`(topic `ERP.PROD_RESULT` 등), 릴레이 3단, 결과는 `if_history`(요청·응답 원문, `idem_key`).
2. **재시도 분류**: 네트워크·5xx·세션 만료(재로그인 후 1회) = 재시도 / 4xx 검증 오류(품목 없음·단위 불일치) = **즉시 FAIL + 사람 조치**(재시도해도 같음) / 호출 제한(이카운트 412 등) = 다음 시도 시각을 늦춤(백오프, 시도 횟수 미증가).
3. **타임아웃 재시도는 상대가 멱등일 때만**: 이카운트·B1 은 **상대측 멱등키가 없다** [추정 — 공개 문서에서 멱등 헤더 확인 못 함]. 그래서 읽기 타임아웃 시 바로 재전송하지 않고 **"상태 확인 필요(UNKNOWN)"** 로 두고 상대 조회 API(전표 조회·비고란의 `biz_key`)로 확인 후 재전송. → 01 5.8 "수신측 멱등키가 있는 ERP 송신은 타임아웃도 재시도" 의 전제가 **두 ERP 에서 성립하지 않을 수 있음** — 결정 D3.
4. **순서 의존**: 품목 없는 실적은 실패한다 → 송신 전 `mst_ext_id_map` 확인, 미매핑이면 송신하지 않고 `HOLD`(매핑되면 자동 재개).
5. **정정**: MES 원장은 역분개만(01 3.1) → ERP 로도 **취소 전표(역분개) 송신**. 이미 ERP 마감된 기간이면 다음 기간 조정 전표로(정책).
6. **마감 후 실적**: ERP 월 마감 이후 발생분은 `HOLD` + 관리자 알림 [추정 — 관행].

---

## 4. 기준정보 엑셀 이관 설계

### 4.1 템플릿 구성 (P1 기준정보 이관 묶음)

**로드 순서가 곧 템플릿 번호다**(참조되는 것 먼저) — Dynamics 365 DMF 의 엔터티 순서 원칙 [원문] E26.

| 순서 | 템플릿 | 필수 열(예) | 참조 검증 |
|---|---|---|---|
| 01 | 공통코드(단위·품목유형·창고유형) | 코드그룹, 코드, 명 | — |
| 02 | 공장·창고·로케이션 | 공장, 창고, 로케이션 | 공장 |
| 03 | 거래처 | 거래처코드, 명, 사업자번호, 유형(고객/협력사) | 사업자번호 형식·체크섬 |
| 04 | 품목 | 품목코드, 명, 규격, 단위, 유형, 기본창고, **ERP 품목코드** | 단위·창고·유형 |
| 05 | 단위환산 | 품목, 기준단위, 환산단위, 비율 | 품목 |
| 06 | BOM | 모품목, 리비전, 순번, 자품목, 소요량, 단위, 유효기간 | 품목, **순환 참조 금지** |
| 07 | 공정·라우팅 | 품목, 공정순번, 공정코드, 작업장, 표준 사이클타임 | 품목·공정·설비 |
| 08 | 설비 | 설비코드, 명, 계층 노드 | 장비 계층 |
| 09 | 초기 재고 | 품목, 창고, 로케이션, LOT, 수량, 기준일 | 전부 + **원장 기초 입력**(역분개 가능한 `OPENING` 거래) |

- 템플릿 파일 = 시트 1(데이터) + 시트 2(열 설명·허용값·예시) + 숨김 메타(템플릿 코드·버전). **버전이 다른 템플릿은 거부**(열 순서 바뀐 옛 파일이 엉뚱한 열로 들어가는 사고 방지).
- 열은 **헤더 이름으로 매핑**(위치 아님), 미지정 필수 열이면 업로드 단계에서 거부.
- 각 템플릿은 `sys_excel_template`(전역, Flyway 시드) + 열 정의 JSON 으로 둔다 [추정].

### 4.2 검증 규칙

| 단계 | 검사 | 결과 |
|---|---|---|
| 형식 | 필수값, 길이, 숫자·날짜 파싱(엑셀 날짜 일련번호 포함), 코드 형식, 앞뒤 공백·전각 문자, **수식 셀은 값만**(수식 문자열 거부) | 행 오류 |
| 파일 내 중복 | 같은 키 2행 이상 | 둘 다 오류(어느 쪽이 맞는지 시스템이 정하지 않음) |
| DB 대비 | 기존 키 존재 → 모드에 따라 `INSERT_ONLY`=오류 / `UPSERT`=변경 / `SKIP` | 행 상태 NEW/CHANGE/SAME/ERROR |
| 참조 | 같은 파일 + DB 에서 참조 대상 존재(예: BOM 자품목) | 오류 |
| 코드 매핑 | 고객 표기(예: "EA", "개") → 표준 코드: `if_code_map` 사전 | 미매핑 목록을 따로 보여주고 **화면에서 매핑 추가 후 재검증** |
| 업무 규칙 | BOM 순환, 소요량 > 0, 단위환산 비율 > 0, 사업자번호 체크섬 | 오류 |
| 회사 범위 | `comp_cd` 열은 **받지 않는다** — 로그인 회사로만(엑셀에 다른 회사 코드를 넣는 이상 사용 차단) | — |

### 4.3 미리보기 → 실행 2단계

```
업로드 ─▶ 스테이징 적재(job_id) ─▶ 검증 ─▶ 미리보기 화면
                                                │ (NEW n / CHANGE n / SAME n / ERROR n, 오류 엑셀 다운로드)
                                                ▼
                                   실행(오류 0건일 때만, 또는 "오류 행 제외" 명시 선택)
                                                ▼
                                   본 테이블 반영 + 감사로그 + 대사 리포트
```

- Odoo 의 **Test → Import**, D365 의 **스테이징 → Validate → 대상 반영** 과 같은 구조 [원문] E27·E26. Odoo 사례의 교훈: 테스트 실행에서도 생성 로직이 돌아 **외부 연동이 일어날 수 있다** → 검증 단계는 **본 테이블·아웃박스에 쓰지 않는다**(dryrun 플래그가 아니라 아예 다른 코드 경로) [원문] E27.
- 실행은 **검증 시점의 스테이징 스냅샷 기준**. 검증 후 DB 가 바뀌었으면(낙관적 락 `row_ver`) CHANGE 행은 재검증 요구.
- 실행 버튼 이중 클릭·두 탭 동시 실행 → `job` 상태 `VALIDATED → RUNNING` 조건부 UPDATE 1건만 성공(멱등) [추정].
- 권한: 미리보기 = `@ProgAuth(SEARCH)`, 실행 = `SAVE` + 템플릿별 별도 액션(예: 초기 재고는 `FN1`) [추정].

### 4.4 대량(수만 행) 처리

| 문제 | 해법 | 근거 |
|---|---|---|
| POI 사용자 모델(`XSSFWorkbook`)은 20MB 파일에 GB 단위 힙 | **XSSF 이벤트(SAX) 모델**(`XSSFReader` + `SheetContentsHandler`)로 행 스트리밍. SXSSF 는 **쓰기 전용** — 오류 리포트·내보내기에 사용 | [검색] E28 |
| **`COPY FROM` 은 RLS 테이블 미지원** | 스테이징을 RLS 회사별 테이블로 만들면 COPY 불가 → ① 트랜잭션 안 `CREATE TEMP TABLE … ON COMMIT DROP`(세션 전용이라 회사 간 노출 없음)에 COPY 후 `INSERT … SELECT` 로 RLS 스테이징/본 테이블에 넣거나 ② 배치 INSERT(`reWriteBatchedInserts=true`) 또는 열별 배열 `unnest` INSERT | [원문] E20, 성능 [검색] E29 |
| 성능 규모 | 1만 행 미만은 배치 INSERT 로 충분, 그 이상은 COPY 가 2~4배 [검색] E29. 수만 행 기준정보는 **배치 INSERT 로 시작**, 실측 후 TEMP+COPY | [추정] |
| `ON CONFLICT DO UPDATE` 같은 키 2번 → 오류 | 파일 내 중복을 검증 단계에서 먼저 제거(4.2) | [검색] E29 |
| 긴 트랜잭션 | 검증은 청크(예: 2,000행) 단위 짧은 트랜잭션, 실행은 템플릿 1개 = 트랜잭션 1개(전부 아니면 전무) — 단 `statement_timeout` 60초(01) 를 넘을 규모면 **비동기 작업**(TenantRunner) + 진행률 | [추정] |
| 업로드 크기 | 파일 크기·행 수 상한(정책), zip bomb(압축 해제 크기) 검사 | [추정] — 01 6단계 첨부 검증과 공용 |

### 4.5 테이블 (안)

```text
sys_excel_template   전역: template_cd, version, target, 열 정의 jsonb, load_order   (Flyway 시드)
sys_import_job       회사별 RLS: job_id, comp_cd, template_cd, version, file_id(sys_file), mode(INSERT_ONLY/UPSERT),
                     status(UPLOADED/VALIDATING/VALIDATED/RUNNING/DONE/FAILED/CANCELED),
                     cnt_new/change/same/error, created_by/at, executed_by/at, row_ver
sys_import_row       회사별 RLS: job_id, row_no, raw jsonb(원본 셀), norm jsonb(정규화), row_status, errors jsonb
                     — 보존 클래스 LOG(정리 대상), 원본 파일은 sys_file 로 보존 기간 별도
if_code_map          회사별 RLS: system_cd(EXCEL/ECOUNT/SAPB1/…), code_type(UOM/WH/TRX_TYPE/ITEM_TYPE), ext_code, int_code
mst_ext_id_map       (02 확정, P1) system_cd, entity_type, internal_id, external_id, unique(comp_cd, system_cd, entity_type, external_id)
```

### 4.6 이관 후 대사

- 실행 직후 **대사 리포트**: 템플릿별 파일 행 수 = NEW+CHANGE+SAME+ERROR(제외) 검증, 본 테이블 건수 전후 비교, 초기 재고는 **품목×창고 수량 합계**를 파일 합계와 비교. D365 가이드도 "잡 성공 ≠ 이관 성공, 건수·변환 결과를 원천과 비교"를 강조 [검색] E26.
- 운영 중 재고 대사(ERP 연동 고객): 일 마감 배치가 ERP 재고 조회 API(이카운트 창고별 재고, B1 `Items` 창고 재고) 또는 ERP 재고 엑셀을 받아 **품목×창고 차이 리포트**만 만든다. **자동 보정하지 않는다**(누가 맞는지 시스템이 모름) [추정].

---

## 5. 어댑터 설계 제안 (테이블·인터페이스 수준)

### 5.1 구조

```
업무 모듈(mes/wms/qms) ── 같은 트랜잭션 ──▶ sys_outbox(topic=ERP.*, biz_key, payload=표준 JSON)
                                                  │ 릴레이 3단 (TenantRunner, 01 5.8)
                                                  ▼
                                        ErpOutboundDispatcher
                                           │ 회사의 erp 설정(sys_ext_system)으로 어댑터 선택
              ┌─────────────────┬──────────┴────────┬──────────────────┐
         ExcelExportAdapter  EcountAdapter     SapB1Adapter     StagingTableAdapter(에이전트)
              │                 │                  │                  │
              └──── if_history(요청·응답 원문, idem_key, 상태) ◀──────┘

수신(ERP → MES): 스케줄 폴링(TenantRunner) 또는 /ext/erp/** (에이전트 push)
   → sys_inbox(consumer_cd, message_id) 멱등 → 표준 JSON → 기준정보/지시 반영(엑셀 이관과 같은 검증기 재사용)
```

- **표준 페이로드**는 B2MML 축소판(02 2.1): 수신 `ProductionSchedule`·품목·BOM, 송신 `ProductionPerformance`(실적·투입·입고 묶음). 어댑터는 **표준 JSON ↔ ERP 형식 변환만** 한다 → 업무 모듈은 ERP 를 모른다.
- **수신 반영 로직 = 엑셀 이관 검증기**(4.2)와 공용. "ERP 에서 온 품목"과 "엑셀로 올린 품목"이 같은 규칙을 거친다(00 4장 규칙 2: 규칙 SQL·로직 1곳).

### 5.2 인터페이스 (Java, 안)

```java
public interface ErpPort {
    ErpCapabilities capabilities();                       // 지원 topic, 일괄 최대 건수, 호출 간격, 멱등 지원 여부
    SendResult send(ErpMessage msg);                      // 트랜잭션 밖에서만 호출 (릴레이 2단)
    LookupResult lookup(String biz_key, String topic);    // 타임아웃 후 "들어갔나?" 확인 (UNKNOWN 해소)
    PullResult pull(String entity, Cursor since);         // ERP → MES 변경분 (지원 시)
}

public record SendResult(Outcome outcome,                 // OK / RETRY / RATE_LIMITED / REJECTED / UNKNOWN
                         String extDocNo, String rawResponse, Duration retryAfter, String errorCode) {}
```

- `RETRY` 만 시도 횟수 증가, `RATE_LIMITED` 는 `next_retry_at` 만 늦춤, `REJECTED` 는 FAIL + 알림, `UNKNOWN` 은 `lookup` 성공 전까지 재전송 금지.
- 어댑터별 **호출 간격 제어(토큰 버킷)는 회사×ERP 단위**. 이카운트 제한은 회사(ERP 계정) 단위로 보이므로 [추정] 여러 앱 인스턴스가 나눠 쓰면 `pg_try_advisory_xact_lock(회사, 'ERP')` 로 회사당 송신 워커 1개(01 6단계 배치 잠금과 같은 방식).
- 세션형 인증(이카운트 `SESSION_ID`, B1 `B1SESSION`)은 **회사별 메모리 캐시 + 401/세션 오류 시 1회 재로그인**. 호출마다 로그인 금지(B1 라이선스·부하, 이카운트 로그인 호출 제한) [검색] E14·E04.

### 5.3 테이블 (안)

```text
sys_ext_system       회사별 RLS: system_cd(ECOUNT/SAPB1/STAGING/EXCEL), use_yn, base_url, 옵션 jsonb
                     (zone, company_db, 일괄 크기, 송신 묶음 단위, ERP_WO_SOURCE …), row_ver
sys_ext_credential   회사별 RLS: system_cd, cred_type(API_KEY/PASSWORD/DB), enc_value bytea, key_ver,
                     expires_at(이카운트 1년), rotated_at/by — 화면은 "설정됨/만료일"만, 값 재표시 없음
if_history           (P0 테이블) + topic, biz_key, ext_doc_no, outcome, http_status, duration_ms 추가 제안
if_code_map / mst_ext_id_map   (4.5)
if_reconcile_run     회사별 RLS: 대사 실행(일자, 대상, 차이 건수), 상세는 if_reconcile_diff
```

### 5.4 이카운트 어댑터 (P2 후보)

| 항목 | 내용 | 확인 |
|---|---|---|
| 송신 | 작업지시서 입력(ERP 가 주인이 아닌 경우), **생산불출·생산입고 입력**, 구매 입력(입고 확정) | 목록 [원문] E01 / 엔드포인트 이름 일부 [검색] E04 |
| 수신 | 품목 조회 → 품목 동기화, 창고별 재고 조회 → 대사 | [원문] E01 |
| 묶음 | 저장 API 1회에 여러 행(상한은 공식 문서로 확인), 저장 간격 제한 → **작업지시·교대 단위 합산 송신** | [검색·비공식] E04 |
| 키 | 이카운트 전표에 `biz_key` 를 비고·외부 참조 필드로 실어 `lookup` 에 사용 | [추정 — 필드 존재는 공식 문서 확인 필요] |
| 개발 | 테스트 키(sboapi)로 검증 → 정식 키 전환. **고객별로 테스트 키 검증 절차가 필요할 수 있음** | [검색] E02·E03 |
| SaaS 운영 | **고정 송신 IP** 필요(이카운트 허용 IP), 설치형은 고객 공장 공인 IP | [검색] E06 |

### 5.5 중간 테이블 어댑터 (P6, 더존 iCUBE·K-System·자체 ERP)

- **SaaS 서버가 고객 DB 에 직접 붙지 않는다.** 고객 사내에 **SmartHappy 에이전트**(Collector 와 같은 원칙: 아웃바운드 HTTPS 전용, 01 7.4)를 두고, 에이전트가 ① 서버의 `/ext/erp/outbound` 를 폴링해 받은 메시지를 고객 `IF_*` 테이블에 INSERT ② 고객 `IF_*` 의 처리 결과·ERP 발 데이터를 읽어 `/ext/erp/inbound` 로 push. 비즈플레이 Temp DB 에이전트도 같은 구조(아웃바운드만, 인바운드 없음) [원문] E21.
- 설치형은 같은 에이전트를 같은 서버에 둬도 되고, 앱이 직접 JDBC 로 붙어도 된다(그래도 **중간 테이블만**).
- 인터페이스 테이블 표준 열(제안): `if_seq`, `if_type`, `biz_key`(멱등), `payload 열들`, `proc_flag`(N/P/Y/E), `proc_msg`, `created_at`, `proc_at`. **명세는 고객 ERP 쪽이 정하는 경우가 대부분**이라 열 매핑은 어댑터 설정(JSON)으로 [추정].
- 에이전트 DB 계정은 **`IF_*` 테이블에만 INSERT/SELECT/UPDATE(플래그 열)**, ERP 원본 테이블 권한 없음(6장).

### 5.6 정책 키 (안, `sys_policy_def`)

| 키 | 값 | 기본 |
|---|---|---|
| `ERP_SYSTEM` | NONE / EXCEL / ECOUNT / SAPB1 / STAGING | NONE |
| `ERP_WO_SOURCE` | ERP(지시를 ERP 에서 받음) / MES | MES |
| `ERP_RESULT_SEND_UNIT` | WO_COMPLETE / SHIFT / DAILY | WO_COMPLETE |
| `ERP_ITEM_SYNC_MODE` | PULL / EXCEL / OFF | EXCEL |
| `ERP_SEND_ON_UNMAPPED` | HOLD / FAIL | HOLD |

선택 연동이므로 미설정이면 기동은 되고 그 기능만 `ERP_NOT_CONFIGURED`(503) — 01 7장 규칙과 같다.

---

## 6. 보안

| 위험 | 대책 | 근거 |
|---|---|---|
| ERP 자격증명 유출(DB 덤프·백업·로그) | `sys_ext_credential` 는 **앱 레벨 봉투 암호화**(DEK 로 값 암호화, DEK 는 마스터 키로 감쌈, `key_ver`), **마스터 키는 DB·백업과 다른 곳**(SOPS+age → systemd-creds/Docker secrets, 01 7장과 같은 경로). AES-256-GCM | OWASP Secrets/Key Management [검색] E30·E31 |
| 화면·로그 노출 | 값은 쓰기 전용(저장 후 재표시 없음, "설정됨 · 만료 2027-10-15"만), `if_history` 원문에서 인증 헤더·세션 ID·비밀번호 **마스킹 후 저장**, 감사로그엔 "변경됨"만 | 01 7.3(토큰 미기록) |
| 공유 계정 | 고객 ERP 에 **연동 전용 사용자** 생성 요청(이카운트 허용 사용자 ID, B1 전용 사용자 + 간접 접근 라이선스) — 사람 계정 재사용 금지, 퇴사자 계정 연동 끊김 방지 | E02·E16 |
| 최소 권한 | 이카운트: 연동 사용자 권한을 필요한 메뉴로 제한 / B1: 연동 사용자 권한 최소 / 중간 테이블: `IF_*` 만 | [추정] |
| **DB 직접 연계** | 원본 테이블 접근 = ERP 업무 규칙 우회 + 고객 DB 에 쓰기 권한을 가진 외부 계정 = 공격 경로. SaaS → 고객 DB 인바운드 포트 개방은 랜섬웨어 대비 원칙(05, 01 7.4)과 정면 충돌. **원본 직접 연계 금지, 중간 테이블은 에이전트 아웃바운드로만** | [추정] + E10 |
| 키 만료·교체 | 이카운트 키 1년 → `expires_at` 30일 전 관리자 알림, 교체 절차(새 키 저장 → 테스트 호출 → 활성)를 화면에 | E07 |
| 운영자 접근 | 운영자(Super Admin)도 고객 ERP 자격증명 **복호화 값 열람 불가**. 지원 세션(01 7장)으로도 값은 안 보임 | [추정] |
| 송신 IP | SaaS 는 고정 egress IP(NAT 게이트웨이) 를 고객에게 안내, IP 변경은 사전 공지 | E06 |
| TLS | 상대가 사설 인증서(B1 기본 설치 등)면 **신뢰 저장소에 그 인증서만 추가**, 검증 끄기 금지 | [추정] |
| 수신 데이터 | ERP 발 데이터도 사용자 입력처럼 검증(4.2), 엑셀 수식 주입 방지 — 내보내기 시 `=`·`+`·`-`·`@` 시작 셀 이스케이프 | [추정] |

---

## 7. 하지 말 것

1. **ERP 원본 테이블에 직접 INSERT/UPDATE** — 더존은 제한·암호화, 업무 규칙 우회, 버전업에 깨짐(E10).
2. **SaaS 서버에서 고객 사내 DB 로 인바운드 접속** — 에이전트 아웃바운드로만.
3. **화면용 내부 API 역분석으로 연동**(Amaranth10 등) — 약관·버전 위험, 고객 계약 문제.
4. **호출마다 로그인** — B1 라이선스·부하, 이카운트 로그인 호출 제한.
5. **실적을 건건 실시간 송신** — 호출 제한·ERP 전표 폭증. 합산 단위 송신.
6. **타임아웃 즉시 재전송**(상대 멱등키 없을 때) — 이중 전표. `UNKNOWN` → 조회 확인.
7. **외부 호출을 DB 트랜잭션 안에서**(01 5.8 재확인).
8. **엑셀 미리보기 단계에서 본 테이블·아웃박스 쓰기**(Odoo 교훈, E27).
9. **`XSSFWorkbook` 으로 업로드 파일 열기**(OOM), **RLS 테이블에 `COPY FROM`**(미지원 오류).
10. **엑셀 파일의 `comp_cd` 열을 신뢰**.
11. **재고를 ERP ↔ MES 자동 동기화로 덮어쓰기** — 대사 리포트 + 사람 판단.
12. **특정 ERP 전용 컬럼을 업무 테이블에 추가**(예: `prd_result.ecount_doc_no`) — `if_history.ext_doc_no`·`mst_ext_id_map` 으로만.

---

## 8. 원래 세션에 넘길 결정

| # | 결정할 것 | 권고 | 영향 |
|---|---|---|---|
| D1 | P0 6단계 엑셀 업로드를 **기준정보 이관 겸용 구조**(job·row 스테이징, 미리보기→실행, 대사 리포트)로 만들지 | **예** — 나중에 바꾸면 거래처관리 표준 화면(8단계)까지 다시 손봄 | P0 6·8단계, 01 4장 테이블 추가 |
| D2 | 대량 적재 방식: 배치 INSERT 로 시작 vs TEMP+COPY | **배치 INSERT(`reWriteBatchedInserts`)로 시작**, 5만 행 실측 후 판단. TEMP 테이블은 `TenantCatalogInspector` 검사 범위(pg_temp)와 충돌 없는지 확인 | 6단계, 카탈로그 검사 |
| D3 | 01 5.8 "ERP 송신은 타임아웃도 재시도" 문구 | **"상대 멱등 보장이 확인된 채널만"으로 수정 + `UNKNOWN` 상태 추가**(이카운트·B1 멱등 헤더 미확인) | 01 5.8, 7단계 `sys_outbox.status` |
| D4 | 첫 ERP 어댑터 | **엑셀 내보내기 + 이카운트**(P2), 중간 테이블·B1 은 P6 수요 시 | 00 로드맵 P2/P6 |
| D5 | `sys_ext_system`·`sys_ext_credential`·`if_code_map` 을 P0/P1 어디에 둘지 | 테이블은 **P1**(ext_id_map 과 같이), 자격증명 암호화 방식만 P0 9단계 prod 가드와 함께 확정 | 01 4장, 7장 |
| D6 | SaaS 고정 송신 IP | 호스팅 결정(05 D2) 때 같이 — 이카운트 연동 고객이 있으면 필수 | 운영 |
| D7 | 고객 사내 에이전트를 Collector(P2)와 **같은 프로세스**로 할지 | **같은 배포물, 기능 분리**(설치·업데이트·서명 검증 1벌) | P2 Collector 설계 |
| D8 | ERP 가 주인인 필드를 MES 화면에서 잠글지 | 정책 `ERP_ITEM_SYNC_MODE=PULL` 일 때 ERP 주인 필드는 읽기 전용 | P1 품목 화면 |

---

## 9. 참고문헌

| # | 출처 | 확인 |
|---|---|---|
| E01 | ECOUNT Open API 제품 소개 — https://www.ecount.com/us/ecount/product/erp_open-api | [원문] |
| E02 | 인프런, "[이카운트] ECOUNT API 연동(2)~(6)" — https://www.inflearn.com/blogs/3853 (~3857) | [검색] |
| E03 | 예스넷, 이카운트 API 개발 가이드 — https://yesnet.kr/docs/ecount-api/ | [검색] |
| E04 | 비공식 이카운트 MCP 서버·클라이언트 정리(호출 간격·허용량) — https://glama.ai/mcp/servers/unohee/ecount-mcp , https://github.com/popo7542015-source/ecount-mcp/pull/25 | [검색·비공식] |
| E05 | 이카운트 생산입고 연동 사례(품목·단위 매핑) — https://github.com/lee1812-oss/wonryo/pull/31 | [검색·비공식] |
| E06 | 이카운트 허용 IP 대응 사례 — https://github.com/lee1812-oss/wonryo/pull/19 | [검색·비공식] |
| E07 | 이카운트, OAPI 인증키 [유효기간 연장] 기능 추가 — https://www.ecount.com/us/service/system-upgrade-view/erp_oapi-key-extend-validity-period-add | [검색] |
| E08 | 더존 iCUBE 제품 페이지(연동 모듈) — https://www.douzone.com/product/icube.jsp | [원문] |
| E09 | 더존 iCUBE 자동전표처리(일반) 매뉴얼(2017) — https://www.douzoneon.com/s1/down/manual/icube_manual_Accounting_05.pdf | [검색] |
| E10 | sharedit Q&A "더존 아이큐브 DB 명세서" — https://www.sharedit.co.kr/qnaboards/26679 | [검색·비공식] |
| E11 | sharedit Q&A "더존 아마란스 오픈 API" — https://www.sharedit.co.kr/qnaboards/27429 | [검색·비공식] (직접 열람 402) |
| E12 | Amaranth 10 구성도 — https://www.douzone.com/product/amaranth10.jsp | [검색] |
| E13 | 더존비즈온 Smart A 10 물류관리 고도화 소식 — https://www.douzone.site/post/더존비즈온-위하고-스마트-a-10-물류관리-전면-고도화 / 비즈플레이 스마트A 엑셀 연계 — https://docs.bizplay.co.kr/system_if/erp-excel/smarta | [검색] |
| E14 | SAP, Working with SAP Business One Service Layer — https://help.sap.com/doc/fc2f5477516c404c8bf9ad1315a17238/10.0/en-US/Working_with_SAP_Business_One_Service_Layer.pdf | [검색] |
| E15 | SAP, Service Layer API Reference — https://help.sap.com/doc/056f69366b5345a386bb8149f1700c19/10.0/en-US/Service%20Layer%20API%20Reference.html ; SAP Community Goods Receipt — https://community.sap.com/t5/enterprise-resource-planning-q-a/business-one-service-layer-goods-receipt/qaq-p/650862 | [검색] |
| E16 | SAP B1 License Guide 10.0 — https://help.sap.com/doc/14c6d92982fd4a9dbf7df66b5108433b/10.0/en-US/LicenseGuide.pdf ; ASUG 라이선스 변경 — https://www.asug.com/insights/b1-sap-business-one-pricing-and-licensing-changes-you-need-to-know ; SAP Community Service Layer Licensing — https://community.sap.com/t5/enterprise-resource-planning-q-a/sap-business-one-service-layer-licensing/qaq-p/12199302 | [검색] |
| E17 | Flextudio, K-System 연동 — https://docs.flextudio.com/flextudio/reference/data-connection/k-system | [원문] |
| E18 | 영림원 K-System MES — https://www.ksystem.co.kr/k-system-mes/ ; K-Studio — https://www.ksystem.co.kr/k-studio/ | [검색] |
| E19 | SystemEver 고객지원 "API 제공이 되나요?" — https://support.systemever.co.kr/hc/ko/articles/6733317119897 ; FAQ — https://www.systemever.kr/html/faq.html | [검색] (직접 열람 403) |
| E20 | PostgreSQL 16 문서, COPY — https://www.postgresql.org/docs/16/sql-copy.html ("COPY FROM is not supported for tables with row-level security") | [원문] |
| E21 | 비즈플레이 연계 문서 색인 — https://docs.bizplay.co.kr/llms.txt ; AGENT(Temp DB) — https://docs.bizplay.co.kr/system_if/link-method-guide/agent-tempdb.md | [원문] |
| E22 | 다음 뉴스, "ERP 빅2, 역대 최대 실적"(한국IDC 2024 점유율 인용) — https://v.daum.net/v/20260211060013532 ; 위시켓 ERP 비교 가이드 2026 — https://blog.wishket.com/blog/erp-program-korea-market-guide | [검색] |
| E23 | Korea Herald, "Ecount surpasses 80,000 corporate clients" — https://www.koreaherald.com/article/10504770 | [검색] |
| E24 | AWS 권장 가이드, MES 다른 엔터프라이즈 애플리케이션과의 인터페이스 — https://docs.aws.amazon.com/ko_kr/prescriptive-guidance/latest/mes-on-aws/enterprise-interface.html ; docs/02 2.1(ISA-95·B2MML) | [검색] |
| E25 | MES 10 카탈로그(ERP 연동 정보) — https://kr.object.ncloudstorage.com/nowis/erpnmore2024/MES%2010_catalog.pdf ; 알파카랩스 MES 구축 가이드 — https://www.alphaca.kr/mes-implementation-guide ; 중소기업정보관리책임자 카페 ERP-MES 연동 문의 — https://m.cafe.daum.net/joongso/LLyi/712 | [검색] |
| E26 | Microsoft Learn, Data import and export jobs overview — https://learn.microsoft.com/en-us/dynamics365/fin-ops-core/fin-ops/data-entities/data-import-export-job ; DynamicsCommunities 이관 계획 — https://dynamicscommunities.com/ug/dynamics-fo-ax-ug/data-migration-planning-for-d365-fscm-best-practices-and-ai-driven-validation/ | [원문]/[검색] |
| E27 | Odoo 19 Export and import data — https://www.odoo.com/documentation/19.0/applications/essentials/export_import_data.html ; Odoo 포럼 test import 부작용 — https://www.odoo.com/forum/help-1/differ-context-on-test-import-and-real-import-177657 | [검색] |
| E28 | Apache POI, The New Halloween Document(SAX 이벤트 모델) — https://poi.apache.org/components/spreadsheet/how-to.html ; HowToDoInJava POI SAX — https://howtodoinjava.com/java/library/poi-read-excel-with-sax-parser/ | [검색] |
| E29 | Java → PostgreSQL 적재 9가지 벤치마크 — https://sqg.dev/blog/java-postgres-insert-benchmark/ ; 스테이징 upsert — https://overflow.no/blog/2025/1/5/using-staging-tables-for-faster-bulk-upserts-with-python-and-postgresql/ | [검색] |
| E30 | OWASP Secrets Management Cheat Sheet — https://cheatsheetseries.owasp.org/cheatsheets/Secrets_Management_Cheat_Sheet.html | [검색] |
| E31 | OWASP Key Management Cheat Sheet — https://cheatsheetseries.owasp.org/cheatsheets/Key_Management_Cheat_Sheet.html | [검색] |

**한계**: 이카운트 공식 API 문서(로그인 후 열람)·더존/영림원 인터페이스 명세·이카운트 호출 제한 공식 수치·B1 라이선스 현행 가격은 1차 원문을 보지 못했다. 이카운트 어댑터 구현 전에 공식 문서로 엔드포인트·필드(외부 참조 필드 유무)·제한을 다시 확인하고, 더존·영림원은 첫 고객의 구축사·대리점에서 명세를 받아야 한다.

---

## 10. 검토 결과 (원래 세션, 2026-10-16)

목적이 포트폴리오·학습으로 바뀌어 **실제 ERP 계약 연동(이카운트·B1·중간 테이블)은 하지 않는다**. 대신 "어댑터 경계를 어떻게 그었는가"와 "엑셀 이관을 어떻게 안전하게 했는가"는 MES 실무를 가장 잘 보여 주는 설계라 구조는 채택한다.

| # | 판정 | 반영 |
|---|---|---|
| D1 엑셀 업로드 = 이관 겸용 구조 | **채택(P1) — 구현 2026-10-16(docs/17 3b, 거래처·품목). 템플릿 정의는 테이블이 아니라 Java 빈(docs/17 P1-8)** | 6단계에서는 다운로드만 만들었고(8단계 서버 엑셀), 업로드는 아직 없다. P1 기준정보 첫 단계에서 **job·row 스테이징 → 검증 → 미리보기 → 실행 → 대사 리포트**로 만든다. 미리보기 단계에서 본 테이블·아웃박스 쓰기 금지(7장 8), 파일의 `comp_cd` 열 무시(7장 10), 업로드는 SAX/스트리밍 읽기(7장 9) |
| D2 적재 방식 | **채택** | 배치 INSERT 로 시작하고 5만 행 실측. `COPY FROM` 은 RLS 테이블 불가 — skill 함정에 기록 |
| D3 타임아웃 재시도·`UNKNOWN` | **원칙 채택, 구현은 첫 외부 송신 채널 때** | 현재 `sys_outbox.status` 는 NEW/SENDING/SENT/FAIL. 상대 멱등이 보장되지 않는 채널을 붙일 때 새 V 파일로 `UNKNOWN` 추가 + 조회 확인 후 재전송. 01 5.8 문구는 "상대 멱등이 확인된 채널만 타임아웃 재시도"로 고친다 |
| D4 첫 ERP 어댑터 | **변경 채택** | 실제 ERP 대신 **`ErpPort` + "ERP 업로드용 엑셀 내보내기" 어댑터 + 테스트용 가짜 ERP(멱등 없음·호출 제한·4xx 재현)** 로 P2 에서 실패 처리 규칙을 테스트로 보여 준다 |
| D5 `sys_ext_system`·`if_code_map` | **채택(P1)** | `mst_ext_id_map` 과 함께 P1. 자격증명 테이블은 실제 연동이 없으니 보류(암호화는 9단계 `SecretBox` 방식 재사용으로 충분) |
| D6 고정 송신 IP | 기각 | 운영 SaaS 없음 |
| D7 에이전트 = Collector 같은 배포물 | 보류 | P2 Collector 설계 때 |
| D8 ERP 주인 필드 잠금 | **채택(P1 품목)** | 정책 `ERP_ITEM_SYNC_MODE` 로 품목 화면 필드 읽기 전용 — 정책 기반 설계의 예시로 좋다 |

"하지 말 것" 12개는 그대로 P1·P2 설계 체크리스트에 넣는다(특히 7: 외부 호출을 DB 트랜잭션 안에서 하지 않기 — 7단계 아웃박스 3단 릴레이로 이미 지킴, 12: ERP 전용 컬럼을 업무 테이블에 넣지 않기).
