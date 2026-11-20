# 43. jOOQ 시범 — 플랫폼 기반 (2026-10-23, 결정 docs/29 #52)

> 사용자 결정 #52: **jOOQ 를 새 APQP 모듈에서 시범**, 기존 모듈은 MyBatis 그대로. 이 문서는 시스템관리(플랫폼) 몫 — 의존성·코드 생성·트랜잭션/RLS·가드·오류 — 을 정한다. APQP 업무 설계는 PMS(docs/41).
> 끝에 검토 절.

## 1. 결론 먼저

| # | 정한 것 | 근거 |
|---|---|---|
| J1 | **jOOQ OSS(Apache 2.0) 3.19.x**(지금 3.19.30) — Boot 4.1 기본 3.21.7 을 `ext['jooq.version']` 로 낮춘다(**사용자 결정 2026-10-23: A**) | jOOQ OSS 는 판마다 지원 PostgreSQL 최소 버전이 다르다(각 jar 의 `SQLDialect.POSTGRES` 필요 버전을 직접 확인): **3.21 = PG 18+, 3.20 = PG 17+, 3.19 = PG 15+**. 우리는 PG 16 — 3.21 로 생성하면 "Database version is older than what dialect POSTGRES supports: 16.15" 경고, 낮은 버전 지원은 상용판 몫. 3.19.30 은 Boot 4.1 jOOQ 자동 설정과 함께 기동·생성·조회까지 시험으로 확인(2장). **상위·사용자 확인 대상**(7장 Q1) |
| J2 | 스타터 `spring-boot-starter-jooq` — `DSLContext` 하나, **같은 DataSource·Spring 트랜잭션** | 자동 설정이 `TransactionAwareDataSourceProxy` 로 Spring 이 묶은 연결을 쓴다 → `TenantAwareTransactionManager` 가 트랜잭션 시작 때 건 `app.comp_cd`(RLS)가 그대로. 별도 연결·트랜잭션 관리자를 만들지 않는다 |
| J3 | **코드 생성 = Flyway 를 올린 실제 PostgreSQL 에서**, 생성물은 **저장소에 커밋**(`src/main/jooq`) | 스키마의 원본은 V 파일 하나(CLAUDE.md). bootRun·compile 이 Docker 없이 된다. 재생성은 `./gradlew jooqGenerate`(Docker 필요) |
| J4 | 생성 대상 = **apqp_ 표 + apqp_ 가 FK 로 가리키는 표**(mst_item·mst_cust·mst_routing·mst_routing_step·mst_proc·qms_char·qms_defect·pms_project·sys_user) + APQP 가 조인에 쓰는 sys_numbering_rule(PMS #374) 만 — sys_code·sys_comp_code 는 #374 에서 넣었다가 PMS #392 에서 뺐다(코드 사용 가능 판정은 `Routines.hbCodeUsable`, 3.1) | 시범 범위를 코드로 고정 — 생성물이 커지면 다른 모듈이 "있으니 쓰는" 일이 생긴다. apqp_ 가 새 표를 가리키면 `INCLUDES` 에 더하고 재생성 |
| J5 | **어긋남 가드**: 시험 DB(모든 V 파일)로 생성기를 다시 돌려 커밋된 생성물과 파일·내용이 같아야 통과 | 스키마를 바꾸고 재생성을 잊으면 `check` 가 잡는다 |
| J6 | **plain SQL 금지**(MyBatis `${}` 금지의 jOOQ 판) + **jOOQ 는 module.apqp 안에서만** — 가드 시험 2개 | 문자열 SQL 이 들어오는 길을 막고, 시범이 번지지 않게 |
| J7 | 오류는 지금과 같은 길 — jOOQ 예외가 **Spring `DataAccessException`** 으로 바뀌어 `ApiExceptionHandler`·`PgErrors`(RLS 42501 → 403, 제약 이름 → 칸 오류)가 그대로 | Boot 자동 설정의 예외 번역 리스너. 시험으로 확인(4장) |

## 2. 판(버전) — 왜 3.19 (사용자 결정 2026-10-23: A)

| jOOQ OSS 판 | 지원하는 PostgreSQL 최소 | PG 16 |
|---|---|---|
| 3.21.7 (Boot 4.1.1 기본) | 18 | 미지원(경고) |
| 3.20.11 | 17 | 미지원 |
| **3.19.30 (채택)** | **15** | 지원 |

- **확인 방법**: 각 판 jar 에서 `SQLDialect.POSTGRES` 의 `requiredVersion`(OSS 가 지원하는 최소 버전)·`supportsDatabaseVersion(major, minor, 문자열)` 을 직접 불러 봤다(웹 지원표는 이 환경에서 열리지 않았다). 3.21 로 생성하면 "Database version is older than what dialect POSTGRES supports: 16.15" 경고가 실제로 나온다.
- 3.19.30 + Boot 4.1.1 자동 설정: 기동, `DSLContext` 주입, 코드 생성, 조회·쓰기·롤백·예외 번역까지 시험 통과, 버전 경고 없음.
- 대가: Boot 기본보다 두 단 낮은 판. 3.20·3.21 에서 생긴 API 는 못 쓴다(시범 범위의 CRUD·조인·집계에는 필요 없음).
- **고정 가드** `JooqVersionGuardTest`: jOOQ 가 3.19 줄인지 + 그 판의 POSTGRES 방언이 **시험 DB 의 실제 버전**을 OSS 로 지원하는지. Boot 를 올리면서 기본판이 조용히 따라 올라오면 `check` 가 실패한다.
- **재검토 조건**: PostgreSQL 을 18 로 올릴 때 jOOQ 도 3.21+(Boot 기본)로 함께 올리고 고정·가드 기대값을 고친다. 그 전에는 올리지 않는다.
- **패치 확인 주기**: 의존성을 갱신할 때마다(그리고 적어도 분기에 한 번) Maven Central 의 3.19 줄 최신 패치로 올린다. 3.19 줄 패치가 끊기면(보안 수정이 3.20+ 에만) 그때 재검토 — PG 18 이전 상향 또는 시범 정리.
- 다른 안(7장 Q1): PostgreSQL 18 로 올림(로컬·시험 이미지·설치형·운영·백업 절차 전부 — 시범 하나로 하기엔 큼) / 3.21 을 PG 16 에(공식 미지원, PG 18 전용 SQL 을 낼 위험) / 시범 보류.

## 3. 코드 생성

| 항목 | 정한 것 |
|---|---|
| 생성기 | `src/test/java/io/smarthappy/support/jooq/JooqCodegen` — `generate(url, user, pw, dir)` 하나를 **재생성 태스크와 가드가 같이** 쓴다 |
| 재생성 | `./gradlew jooqGenerate` — 일회용 PostgreSQL(로컬·시험과 같은 이미지·init 스크립트) + Flyway(소유자 계정, `app_role` 자리표시) → `src/main/jooq` 를 지우고 다시 쓴다 |
| 생성 옵션 | 표·레코드·키 + **허용 목록의 판정 함수**(3.1). 그 밖 루틴·시퀀스·인덱스·**CHECK 식**(문자열 plain SQL 로 생성됨 — 판정은 DB 몫) 제외. `generatedAnnotation=false`(날짜·버전을 박지 않아 다시 생성해도 같은 글자), 스키마 이름을 SQL 에 박지 않음, 시간 칸은 `OffsetDateTime`·`LocalDate` |
| 패키지 | `io.smarthappy.module.apqp.jooq` — 시범 모듈 안 |
| 배포물 | 생성기·`jooq-codegen` 은 test 의존성 — jar 에는 `jooq` 런타임만 |
| 다른 안 | (a) 빌드마다 생성(Docker 없이 compile 불가 — 설치형·IDE 불편) (b) DDL 파일 파싱 생성(`jooq-meta-extensions` DDLDatabase — 우리 V 파일의 RLS·트리거·DO 블록을 못 읽고, 실제 PG 와 다른 해석 위험) |

### 3.1 DB 함수 — 판정용 순수 함수만 (2026-10-23 상위 결정, PMS #374 요청)

- 배경: APQP 가 `hb_code_usable`(코드 사용 가능 판정, V024)과 같은 조건을 Java 로 다시 써야 했다 — 함수가 바뀌면 조용히 어긋난다(정의 두 벌).
- 정한 것: `JooqCodegen.ROUTINES` **이름 허용 목록**(지금 `hb_code_usable` 하나)만 생성 → `Routines.hbCodeUsable(...)`. SQL 안의 함수 호출로 그려지고 인자는 바인드(plain SQL 아님), where 절에서도 쓴다. 함수 서명이 바뀌면 어긋남 가드가 실패하고 호출하는 쪽은 컴파일 오류 — 어긋남이 드러난다.
- 넣는 기준: **STABLE·IMMUTABLE**(PostgreSQL 이 쓰기를 막는다), **SECURITY DEFINER 아님**(RLS 우회 금지), 트리거 함수 아님, 회사 범위·소속 검사(`hb_current_*`·`hb_check_*`) 아님 — 회사 범위는 지금처럼 세션 변수 + RLS.
- 가드 `JooqRoutinesGuardTest`: 생성된 routines = 허용 목록, 허용 목록 함수가 위 기준을 만족(pg_proc 확인), 생성 함수 호출이 회사 범위 안에서 동작.

## 4. 트랜잭션·회사 범위·오류 (시험 `JooqTenantTest`)

- **트랜잭션 밖 = 0건**(회사 범위 없음 → RLS), MyBatis 와 같다. DB 접근은 지금처럼 `@Transactional`·`TenantRunner` 안에서만.
- **A사로 B사**: B사 범위에서 A사 행 조회 0건, 고치기 0건. 다른 회사 `comp_cd` 로 쓰기 → RLS WITH CHECK 위반이 `DataAccessException` + `PgErrors.isRlsViolation` → 403 `TENANT_FORBIDDEN`.
- **MyBatis·JdbcTemplate 와 한 트랜잭션**: 같은 연결 — 서로 쓴 것을 보고, 롤백되면 같이 롤백.
- 앱 계정 권한도 그대로(예: 기준정보 DELETE 권한 없음 → 42501, RLS 위반과 구분).
- 제약 위반 → `DataIntegrityViolationException` + `PgErrors.constraint`(이름) — 서비스의 `CONSTRAINT_FIELD` 칸 오류 변환을 MyBatis 와 똑같이 쓴다.

## 5. 가드

| 가드 | 내용 | 한계 |
|---|---|---|
| `JooqUsageGuardTest` · 위치 | `org.jooq`·생성물 패키지 import 는 `module/apqp/` 안에서만 | — |
| `JooqUsageGuardTest` · plain SQL | jOOQ 를 쓰는 파일에서 **첫 인자가 문자열 리터럴인** SQL API 호출 금지(`sql`·`field`·`table`·`condition`·`where`·`fetch`·`execute`·`raw`·`orderBy`·`join` …). 허용은 `ALLOWED`(파일 → 근거), 지금 비어 있음. 검사기 자체 시험 포함 | 변수에 담은 문자열은 못 본다 → 리뷰. 더 엄격하게는 jOOQ `@PlainSQL` 검사기(Checker Framework 주석 처리기) — 빌드가 무거워 시범 뒤 판단 |
| `JooqGeneratedGuardTest` | 생성물 = 스키마(3장) | Docker 필요(다른 통합 시험과 같음) |
| `JooqVersionGuardTest` | jOOQ 3.19 줄 + 그 판이 실제 DB 버전을 OSS 로 지원(2장) | — |
| `JooqRoutinesGuardTest` | 생성된 DB 함수 = 허용 목록, 판정용 순수 함수 기준(3.1) | 함수 본문이 다른 표를 읽는지는 리뷰 |

- 값은 언제나 바인드 변수(생성된 칸 비교·`DSL.val`). 칸·표 이름을 사용자 입력으로 고르는 정렬은 **허용 목록 enum → 생성된 칸**(지금 `SortField` 와 같은 원칙).

## 6. 쓰는 법 (APQP 세션)

1. 표·도메인·서비스는 먼저(V9408) — 표를 넣은 PR 에서 `./gradlew jooqGenerate` 를 돌려 생성물을 같이 커밋(가드가 잊음을 잡는다).
2. 데이터 접근 클래스는 `module.apqp` 안, `DSLContext` 주입, 생성된 `Tables.*` 만.
3. 회사 범위·트랜잭션·오류·RLS 체크리스트는 MyBatis 와 같다(skill checklists §1·§2). jOOQ 쓸 때 절: skill `references/checklists.md` §7(초안).

## 7. 결정 필요

| # | 질문 | 추천 | 다른 안 |
|---|---|---|---|
| Q1 | jOOQ 판 | **3.19.x 고정**(PG 16 OSS 지원) — ✅ 사용자 결정 A(2026-10-23) | PG 18 로 올리고 Boot 기본 3.21 / 3.21 을 PG 16 에(미지원) / 보류 |
| Q2 | plain SQL 엄격 검사 | 문자열 리터럴 가드 + 리뷰(지금) | `@PlainSQL` 검사기(주석 처리기) — 시범 뒤 |
| Q3 | 시범 뒤 넓힐지 | APQP 1차 끝난 뒤 비교 기록(코드량·시험·리뷰 난이도)으로 판단 | — |

## 8. 검토 (상위)

(검토 결과를 여기에 — 채택·수정·보류)
