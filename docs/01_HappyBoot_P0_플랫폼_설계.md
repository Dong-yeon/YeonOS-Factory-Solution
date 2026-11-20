# YeonOS P0 — 플랫폼 계층 상세 설계

> 이름: 처음엔 이 플랫폼 계층을 "HappyBoot" 라 불렀으나 2026-10-20 별도 이름을 없앴다(docs/00 0.1). 이 파일 이름도 그대로 둔다 — 적용된 V001 주석이 이 경로를 가리킨다. 코드 패키지 `io.smarthappy`·저장소 이름·DB 계정(`yeonos_owner`/`yeonos_app`)·DB 이름(`happyboot`)·설정 접두어(`happyboot.*`)·환경 변수 이름은 옛 이름 그대로 둔다(바꾸면 운영·로컬 DB 와 설치본 설정이 깨진다).

> 작성일 2026-10-15 · 전제: `00_SmartHappy_분석보고서.md` 0.1 확정 결정
> (공통 플랫폼 계층 / 자체 조회 그리드 + 무료 UI(Kendo UI Core·OSS, 상용 Kendo 는 PoC 후) / PostgreSQL / 자동차→반도체 부품 / **SaaS 기준 설계 + 설치형 납품**)
> 개정 2026-10-15: `02_표준_연구기반_설계보완.md` 6.2 검토 결과 반영 — RLS 우회 경로 규칙(1.4), TenantRunner, 아웃박스/인박스(5.8), 원장 공통 규약(3.1·5.9), 보존 클래스·법적 보류, 접속 로그 확장
> 개정 2026-10-15: `03_AI_활용_조사.md` 6장 검토 결과 반영 — Spring Boot 4.1 전환, 원장 6종(공정 파라미터 실측 추가), AI 정책 키(5.3), 화면 i18n 메시지 키·help_key(6장), AI 연동 방식(1.3)
> 개정 2026-10-15: `04_Doubly_분석.md` 6장 검토 결과 반영 — 로그인 잠금 자동 해제·IP 시도 제한(3단계 완료), 오류 코드 enum·DB 제약 매핑(4단계), 아웃박스 3단 처리(5.8), 배치 잠금·스케줄 조건부(6단계), 가드 테스트 묶음(8장), 필수/선택 설정 규칙·prod 기동 가드(7장)
> 개정 2026-10-15: `05_보안_랜섬웨어_대비.md` 8장 검토 결과 반영 — 7장 보안 기본값 개정(CSP·세션 절대 수명·비밀번호 NIST·운영자 MFA·지원 세션), 7.1 DB 계정·마이그레이션 분리, 7.2 백업·복구(랜섬웨어), 7.3 탐지·사고 대응·공급망, 7.4 Collector 보안, 설치형 OS Linux 기본 + Windows 지원
> 개정 2026-10-20: `26_회사솔루션_비교_분석.md` 7장 검토 결과 반영(기록만, 코드 변경 없음) — prod 기동 가드에 장치 시뮬레이터 금지(P2), `/ext/**` 토큰 상수 시간 비교, 8장 업무 흐름 테스트·상태 전이 테스트 행

P0의 목표: **빈 PostgreSQL에서 `bootRun` 한 번 → 회사 생성 → 로그인 → 메뉴 → 샘플 화면(CRUD)까지** 동작하고, 이후 모든 업무 모듈(MES/QMS/WMS…)이 그대로 올라탈 수 있는 바닥을 만든다.

---

## 1. 멀티테넌트 설계 (SaaS + 설치형 공용)

### 1.1 방식 선택

| 방식 | 설명 | 판단 |
|---|---|---|
| DB 분리 (고객마다 DB) | 격리 최강 | 고객 100곳이면 DB 100개 마이그레이션 → 1인 운영 불가 |
| 스키마 분리 | 중간 | Flyway·MyBatis가 복잡해짐 |
| **공유 DB + `comp_cd` 컬럼** ✅ | 모든 업무 행에 회사 코드 | 운영 단순, 설치형은 회사 1개로 그대로 동작 |

**3중 방어**로 "다른 회사 데이터가 보이는 사고"를 막는다.

1. **애플리케이션**: 로그인 사용자의 `comp_cd`를 `TenantContext`(요청 스코프)에 보관. Mapper 파라미터에 자동 주입 (`#{compCd}` — 프론트가 보낸 값은 무시).
2. **DB 행 수준 보안(RLS)**: 업무 테이블에 PostgreSQL Row Level Security 정책.
   ```sql
   alter table mst_item enable row level security;
   alter table mst_item force row level security;
   create policy tenant_isolation on mst_item
     using (comp_cd = current_setting('app.comp_cd', true))
     with check (comp_cd = current_setting('app.comp_cd', true));
   ```
   트랜잭션 시작 시 `select set_config('app.comp_cd', ?, true)` (트랜잭션 로컬 → 커넥션 풀 재사용에도 안전).
   설정이 없으면 `current_setting(..., true)`가 NULL → **아무 행도 안 보임(fail-closed)**.
3. **테스트**: "A사 사용자로 B사 데이터 조회·수정·삭제 시도" 자동 테스트를 모든 도메인에 의무화.

> 앱 DB 계정은 테이블 소유자가 아닌 별도 계정(`yeonos_app`)으로 접속해야 RLS가 우회되지 않는다. 마이그레이션은 소유자 계정(`yeonos_owner`)으로 실행.

### 1.2 데이터 분류

| 구분 | 예 | `comp_cd` |
|---|---|---|
| 제품 공통 (전역) | 프로그램(`sys_prog`), 제품 기본 메뉴 템플릿, 시스템 공통코드, 정책 정의 | 없음 |
| 회사별 | 사용자, 권한그룹, 회사 메뉴, 회사 공통코드, 정책 값, 채번 규칙, 모든 업무 데이터 | **필수** |
| 회사 > 공장 | 창고, 설비, 작업지시, 재고 등 | `comp_cd` + `plant_cd` |

### 1.3 SaaS vs 설치형 차이 = 설정만

| 항목 | SaaS (`deploy.mode=saas`) | 설치형 (`deploy.mode=onprem`) |
|---|---|---|
| 회사 수 | N | 1 (설치 시 생성) |
| 회사 식별 | 서브도메인 `{comp}.smarthappy.kr` 또는 로그인 화면 회사코드 | 고정 |
| 회사 가입/관리 화면 | 운영자(Super Admin) 콘솔 노출 | 숨김 |
| 파일 저장 | S3 호환 오브젝트 스토리지 | 로컬 디스크 |
| 라이선스 체크 | 구독 상태 | 라이선스 키(사용자 수·만료일) |
| 업데이트 | 내가 배포 | 설치 패키지(jar + Flyway 자동 마이그레이션) |
| AI | 외부 LLM API(학습 미사용 정책 제공사) | 인터넷 허용: 외부 API / 폐쇄망: 로컬 LLM(Ollama + 상업 이용 가능 모델) 또는 AI 끔 — `LlmPort` 어댑터만 다름 |

큰 SaaS 고객은 같은 스키마의 **전용 DB로 승격**할 수 있다(`comp_cd` 유지). 설치형과 같은 경로라 추가 개발이 없다.

### 1.4 RLS 우회 경로 규칙 (02 6.2-A)

RLS는 테이블에만 걸리므로, 아래 경로로 새면 격리가 깨진다. **카탈로그 검사 테스트(`TenantCatalogRulesTest`)로 강제**한다.

| 우회 경로 | 규칙 | 검사 |
|---|---|---|
| 일반 뷰 (정의자 권한으로 실행) | 모든 뷰 `with (security_invoker = true)` | `pg_class.reloptions`에 없으면 실패 |
| `SECURITY DEFINER` 함수 | 금지. 꼭 필요하면 허용 목록에 등록 | `pg_proc.prosecdef = true` 이고 목록에 없으면 실패 |
| 머티리얼라이즈드 뷰 (RLS 미적용) | 업무 데이터로 만들지 않음. 대시보드 캐시는 일반 테이블 + RLS | `relkind = 'm'` 존재 시 실패 |
| RLS 빠진 회사별 테이블 | `comp_cd` 컬럼이 있으면 ENABLE+FORCE RLS + 정책 필수 | 컬럼은 있는데 RLS·정책 없으면 실패 |
| 요청 밖 실행(스케줄러·아웃박스 릴레이·`@Async`·이벤트 리스너) | `TenantRunner.run(compCd, () -> …)`로만 실행. 회사 순회 배치는 **회사별 트랜잭션 분리** | 코드 리뷰 + ArchUnit(스케줄러 메서드가 TenantRunner 사용) |

노이지 네이버 대비: 앱 계정 `statement_timeout`(설치됨: 60초), 엑셀 다운로드 행 수 상한, 로그·메트릭에 `comp_cd` 태그.

**구현 (P0 2단계, 2026-10-15)**
- `TenantAwareTransactionManager`: 트랜잭션 시작마다 `app.comp_cd`·`app.scope`를 트랜잭션 로컬로 설정(범위가 없으면 빈 값으로 덮어씀). MyBatis·JdbcTemplate 공통.
- `TenantCatalogInspector`: 위 규칙을 카탈로그로 검사. **기동 시(`TenantCatalogGuard`) 위반이 있으면 기동 중단** + 테스트. 고객 자체 리포트 객체는 별도 스키마에 만들도록 안내한다.
- **유일한 예외 정책 `sys_comp.system_directory_read`** (V002): 배치가 회사 목록을 얻기 위한 SYSTEM 범위(`app.scope='SYSTEM'`)에서 `sys_comp` SELECT만 허용. 다른 회사별 테이블은 SYSTEM 범위에서도 0건, 회사 생성·수정도 불가. 검사기 허용 목록에 등록.
- RLS 위반 판별은 오류 문구가 아니라 SQLState `42501` + 서버 routine `ExecWithCheckOptions`로 한다(서버 언어 설정과 무관). 같은 42501이라도 DDL 권한 부족 등은 403이 아니라 500.

---

## 2. 프로젝트 구조

**단일 Gradle 프로젝트 + 패키지 경계** (1인 개발에 멀티모듈은 과함). 경계는 **ArchUnit 테스트**로 강제하고, 필요해지면 그때 모듈로 쪼갠다.

```
smarthappy/
├── build.gradle                    Java 25(Temurin), Spring Boot 4.1, MyBatis 4.1, Flyway, PostgreSQL, Testcontainers 2, ArchUnit
├── src/main/java/io/smarthappy/
│   ├── YeonApplication.java          (2026-10-22 SmartHappyApplication 에서 이름 변경)
│   ├── platform/                   ← P0 범위
│   │   ├── tenant/                 TenantContext, TenantFilter, RLS 설정 Aspect, TenantRunner(요청 밖 실행), 회사·공장
│   │   ├── security/               SecurityConfig, LoginUser, 로그인/세션, 401 처리
│   │   ├── auth/                   권한그룹, @ProgAuth + AuthAspect
│   │   ├── menu/                   프로그램·메뉴, PageController(/page/{progCd})
│   │   ├── code/                   공통코드 (시스템 + 회사 오버라이드)
│   │   ├── policy/                 정책 정의·값, PolicyService
│   │   ├── numbering/              채번 규칙·시퀀스, NumberingService
│   │   ├── approval/               결재 요청·결재선·이력, ApprovalFinalizedEvent
│   │   ├── file/                   FileStorage(로컬/S3), 첨부 메타
│   │   ├── audit/                  변경 감사로그, 접속 로그, 보존 클래스·법적 보류·정리 배치
│   │   ├── messaging/              아웃박스(발행 릴레이), 인박스(멱등 수신)
│   │   ├── ledger/                 원장 공통 규약 지원(역분개, 수정·삭제 차단 트리거)
│   │   ├── label/                  라벨 템플릿, 인쇄 큐 (P0는 테이블+API만)
│   │   ├── integration/            IF 이력, 멱등키 (P0는 테이블+공통 클래스만)
│   │   ├── excel/                  엑셀 업로드(미리보기→실행), 다운로드
│   │   └── common/                 ApiResponse, 예외, 페이징, BaseAudit, 유틸
│   └── module/                     ← P1 이후
│       ├── master/  mes/  qms/  wms/  srm/  pms/  cms/
├── src/main/resources/
│   ├── db/migration/               Flyway: V001__platform_tenant.sql …
│   ├── db/seed/                    R__seed_menu_prog.sql (반복 실행, 멱등)
│   ├── mapper/{platform|module}/…/*.xml
│   ├── templates/  layout/ fragments/ platform/ module/
│   └── static/  js/common/ js/views/  css/  vendor/{lib}/{version}/ (Apache·MIT 는 커밋 + LICENSE + SHA-256, 상용 vendor/kendo/ 는 커밋 X)
└── src/test/java/…                 Testcontainers 통합테스트, ArchUnit, 테넌트 격리 테스트
```

**모듈 경계 규칙 (ArchUnit)**
- `module.*`는 `platform.*`을 써도 된다. 반대는 금지.
- `module.qms`가 `module.wms`의 **Mapper·내부 클래스**를 직접 호출 금지 → 공개 `XxxFacade` 또는 도메인 이벤트만.
- Controller → Mapper 직접 호출 금지 (트랜잭션·권한 재확인이 서비스 계층을 건너뛰지 않게).

---

## 3. DB 규칙 (PostgreSQL)

| 항목 | 규칙 |
|---|---|
| 이름 | 소문자 snake_case, 따옴표 없이. 접두어 = 모듈 (`sys_`, `mst_`, `mes_`, `qms_`, `wms_`, `srm_`, `pms_`, `cms_`, `if_`) |
| PK | 업무 테이블 `{table}_id bigint generated always as identity`. 사람이 보는 번호(작업지시번호 등)는 별도 컬럼 + `unique(comp_cd, no)` |
| 테넌트 | `comp_cd varchar(20) not null` (+ 필요시 `plant_cd`). 모든 unique·index의 **첫 컬럼은 comp_cd** |
| 감사 컬럼 | `created_at timestamptz`, `created_by`, `updated_at`, `updated_by` — **MyBatis 인터셉터가 자동 주입**, 수기 입력 금지 |
| 낙관적 락 | `row_ver int not null default 0` — update 시 `where row_ver = #{rowVer}`, 0건이면 409 |
| 사용 여부 | `use_yn char(1) not null default 'Y' check (use_yn in ('Y','N'))` — NULL 원천 차단 |
| 상태값 | 코드값(영문 대문자, 예 `WAIT/RUN/DONE`) + 공통코드로 한글명. 한글 리터럴 저장 금지 |
| 삭제 | 기준정보는 `use_yn='N'`(소프트), 트랜잭션/원장은 삭제 대신 **취소 이력(보상 행)** |
| SQL | `${}` 금지 (정렬 컬럼은 화이트리스트 enum으로만). 빌드 시 grep 검사 |
| 날짜 조건 | 컬럼을 함수로 감싸지 않기 (`where created_at >= :from and created_at < :to`) |
| 뷰·함수 | 뷰는 `security_invoker = true`, `SECURITY DEFINER` 금지, 머티리얼라이즈드 뷰 금지 (1.4) |
| 확장 기능 | PG16 trusted 확장만 사용(`btree_gist`, `pgcrypto` 등 — DB 소유자 권한으로 설치 가능, 슈퍼유저 불필요) |

### 3.1 원장 공통 규약 (02 6.2-D)

원장 = 사실의 누적. **재고 트랜잭션·생산 실적·LOT 계보·설비 상태 구간·측정값·공정 파라미터 실측** 6종에만 적용하고, 나머지는 일반 CRUD + 감사로그.

| 규칙 | 내용 |
|---|---|
| 두 시각 | `occurred_at`(현장에서 일어난 시각) + `recorded_at`(DB에 기록된 시각, default `now()`) 모두 필수 → 늦은 입력·소급 정정을 구분 |
| 대리 입력 | `recorded_by`(입력한 사람·단말) 와 `performed_by`(실제 작업자) 를 따로 둔다 — 반장 대리·POP 공용 단말·구매 대리 입력이 드러나게 (docs/11 D12, P2 원장부터) |
| 입력 방식 | `entry_method varchar(10) NOT NULL CHECK (IN ('AUTO','SCAN','KEY','IMPORT','CORRECT'))` — 설비 자동·스캔·직접 입력·일괄 가져오기·정정. 두 시각과 합쳐 **입력 지연·사후 입력률·수기 비율**(채택 KPI)을 별도 수집 없이 계산 (docs/11 D1, 2026-10-15) |
| 정정 | 원래 행은 그대로 두고 **역분개 행**을 추가 (`reversal_of_id`로 원래 행 참조, 수량 부호 반대) |
| 수정·삭제 차단 | 공용 트리거 함수 `hb_forbid_update_delete()` 부착 + 앱 계정에서 해당 테이블 `UPDATE, DELETE` 권한 REVOKE |
| 기간 마감 | `sys_period_close`(P4) 이후 `occurred_at` 소급 입력은 정책(`BLOCK`/결재 필요)으로 제어 |
| 대사 | 주기 배치로 `sum(원장) = 현재고` 검증, 불일치는 알림만(자동 보정 금지) |

P0는 규약과 트리거 함수까지만 만들고, 실제 원장 테이블은 P2(실적·계보·설비 상태)·P3(측정값)·P4(재고)에서 이 규약으로 만든다.

---

## 4. P0 테이블 (초안)

```
── 테넌트 ─────────────────────────────
sys_comp            회사 (comp_cd PK, comp_nm, biz_no, plan_cd, status, …)
sys_plant           공장 (comp_cd, plant_cd)
sys_license         설치형 라이선스 / SaaS 구독 상태

── 사용자·권한 ─────────────────────────
sys_user            사용자 (comp_cd, user_id, login_id unique(comp_cd, login_id), pwd_hash, dept, locale, tz, lock/fail_cnt, pwd_changed_at)
sys_auth_group      권한그룹 (comp_cd, auth_grp_cd)
sys_user_auth       사용자 ↔ 권한그룹
sys_prog            프로그램 (전역: prog_cd PK, prog_nm, view_path, module, 사용 가능 액션 목록)
sys_menu            회사 메뉴 트리 (comp_cd, menu_id, parent_id, prog_cd, sort, menu_grp)
sys_auth_prog       권한그룹 × 프로그램 × 액션 (SEARCH/SAVE/DELETE/EXCEL/PRINT/APPROVE/FN1~5)

── 공통 ───────────────────────────────
sys_code_grp, sys_code          시스템 코드 (전역)
sys_comp_code_grp, sys_comp_code 회사 코드/오버라이드
sys_policy_def      정책 정의 (전역: policy_key, value_type, default_value, 설명, 허용값)
sys_policy          회사별 정책 값 (comp_cd, plant_cd nullable, policy_key, value)
sys_numbering_rule  채번 규칙 (comp_cd, rule_cd, prefix, date_fmt, reset_cycle, seq_len, 구분값 템플릿)
sys_numbering_seq   채번 시퀀스 (comp_cd, rule_cd, period_key, last_no)  PK(comp_cd, rule_cd, period_key)

── 결재 ───────────────────────────────
apr_request         결재 요청 (comp_cd, doc_type, biz_key, title, status, requester)
apr_line            결재선 (순번, 결재자, 유형: 결재/합의/참조)
apr_history         처리 이력 (승인/반려/회수, 의견)

── 첨부·감사·연계·라벨 ──────────────────
sys_file            첨부 메타 (comp_cd, target_type, target_id, storage_key, 원본명, 크기, 해시)
sys_audit_log       변경 감사 (comp_cd, table, pk, action, before/after jsonb, user, at)
sys_access_log      로그인/화면 접속 (comp_cd, user, prog_cd, action: VIEW/SAVE/DELETE/PRINT/EXCEL,
                    client_type: WEB/POP/PDA/API, ip, at) — 정부지원사업 사후관리 활용 로그의 원천
if_history          외부 연계 (comp_cd, system, direction, idem_key unique, req/resp jsonb, status, retry_cnt)
sys_label_template  ZPL 템플릿 (comp_cd, label_cd, zpl, 변수 목록)
sys_print_queue     인쇄 큐 (comp_cd, printer_cd, payload, status, 시도 횟수)

── 메시징 (02 6.2-C) ──────────────────
sys_outbox          발행 대기 (outbox_id, comp_cd, topic, biz_key, payload jsonb, status: NEW/SENT/FAIL,
                    created_at, sent_at, retry_cnt, next_retry_at)
sys_inbox           수신 기록 (consumer_cd, message_id, comp_cd, received_at)  PK(consumer_cd, message_id)

── 보존 (02 6.2-E) ────────────────────
sys_retention_class 보존 클래스 (전역: class_cd, 기준: CREATED_AT/ITEM_EOL/CUSTOM, 기본 기간,
                    purge_allowed_yn — LOG 클래스만 Y)
sys_retention_policy 회사별 기간 override (comp_cd, class_cd, 기간, 근거: 고객 CSR 등)
sys_legal_hold      법적 보류 (comp_cd, target_type, target_id 또는 조건, 사유, 설정자, 해제일)
```

**보존 정책** (로그 테이블 무한 증가 방지 + IATF 기록 보존):
- `sys_audit_log`, `sys_access_log`, `if_history`, `sys_print_queue`, `sys_outbox`, `sys_inbox`는 월 단위 파티션 + 정리 배치를 P0에 포함.
- 정리 배치는 **`purge_allowed_yn = 'Y'`(LOG) 클래스 테이블만** 지우고, `sys_legal_hold` 범위는 건너뛴다.
- (2026-10-21 덧붙임, docs/38 A36) **APS 계획 결과**(`PLAN_RESULT`, 기준 `KEEP` = 공통 정리 `hb_purge_logs()` 대상 아님)는 전용 소유자 프로시저 **`hb_purge_aps_results()`** 가 기간을 판정해 정리한다 — 운영 정리 실행 때 `hb_purge_logs()` 와 함께 소유자 자격증명으로 한 줄 더 호출.
- 품질기록(검사·LOT·승인 기록)은 "기간 경과"가 아니라 "품목 단종 + n년" 같은 업무 기준이라 정리 대상이 아니다. 적용은 P3.

---

## 5. 핵심 공통 서비스

### 5.1 권한 — `@ProgAuth`
```java
@ProgAuth(prog = "mst-item", action = Action.SAVE)
public ItemDto save(ItemSaveRequest req) { … }
```
- `AuthAspect`가 `TenantContext.user().authGroups()` × `sys_auth_prog`를 조회(요청 단위 캐시)해서 없으면 403.
- 화면 버튼 노출은 PageController가 model에 넣은 액션 목록으로 `th:if`. **화면은 편의, 판정은 서버.**
- 서비스에 어노테이션이 없으면 ArchUnit 테스트 실패 → "권한검사 누락 도메인" 재발 방지.

### 5.2 채번 — `NumberingService`
```sql
insert into sys_numbering_seq (comp_cd, rule_cd, period_key, last_no)
values (#{compCd}, #{ruleCd}, #{periodKey}, 1)
on conflict (comp_cd, rule_cd, period_key)
do update set last_no = sys_numbering_seq.last_no + 1
returning last_no;
```
- 한 문장으로 원자적 증가 (행 잠금은 트랜잭션 끝까지). MAX+1 금지.
- 규칙 예: `WO-{yyMM}-{seq:4}` → `WO-2610-0001`, `LOT-{plant}-{yyMMdd}-{seq:3}`.
- 고객이 원하는 번호 체계는 `sys_numbering_rule`만 바꾸면 됨 → 코드 수정 없음.

### 5.3 정책 — `PolicyService`
```java
GateMode mode = policy.get(Policies.QMS_IQC_GATE);   // BLOCK / WARN / OFF
```
- 조회 순서: 공장 값 → 회사 값 → 정의의 기본값. 캐시 + 변경 시 무효화.
- P0에서 정의만 잡아둘 대표 정책: `LOT_POLICY`, `QMS_*_GATE`, `WMS_ALLOC_STRATEGY`, `LOG_RETENTION_DAYS`, `PWD_POLICY`.
- AI 정책 키도 P0 5단계에 정의만 넣는다(기능은 P5): `AI_ENABLED`, `AI_EXTERNAL_API_ALLOWED`, `AI_PROVIDER`(EXTERNAL/LOCAL/NONE), `AI_TRAINING_CONSENT`(서면 동의일·범위, 기본 미동의), `AI_PII_MASKING`. 회사 간 통합 학습은 정책과 무관하게 금지.

### 5.4 결재 — 이벤트 분리
- 업무 모듈은 `approvalService.request(docType, bizKey, line)`만 호출.
- 최종 승인/반려/회수 시 업무 모듈이 등록한 **`ApprovalDocType.onFinished(bizKey, result)` 를 같은 트랜잭션에서 호출**해 상태 변경(7단계 구현: 스프링 이벤트 대신 인터페이스 — 기본 AFTER_COMMIT 리스너는 다른 트랜잭션이 되고, 리스너가 없어도 조용히 지나가므로). 없는 문서 유형은 상신부터 거부.
- 결재선: APPROVE·AGREE 순차 1명씩(CURRENT), REF 참조. 본인 결재 금지(정책 `APR_SELF_APPROVE_ALLOWED`), 인원 상한 `APR_MAX_LINES`, 회수는 요청자가 아무도 처리하기 전에만, 반려는 사유 필수. 처리 API 는 `X-Idempotency-Key`(인박스 `API:APR_ACT`) + 요청 행 `for update`.
- 결재 엔진은 업무 테이블을 모른다.
- 결재 → 업무 상태 변경은 **같은 트랜잭션**이어야 하므로 동기 리스너로 둔다. 결재 완료 후 알림·ERP 송신처럼 외부로 나가는 것은 아웃박스(5.8)로 보낸다.

### 5.5 감사로그
- 저장/삭제 서비스에 `@Audited(table="mst_item")` → before/after를 jsonb로 기록.
- IATF 16949 / 반도체 고객 감사 대응: "누가 언제 무엇을 바꿨나"를 화면에서 조회.

### 5.6 파일 저장 — `FileStorage` 인터페이스
- `LocalFileStorage`(설치형), `S3FileStorage`(SaaS). 저장 키 = `{comp_cd}/{yyyy}/{MM}/{uuid}`.
- 다운로드 시 `comp_cd` 일치 + 대상 화면 조회권한 확인.

### 5.7 API 규약 (단일)
| 메서드 | URL | 용도 |
|---|---|---|
| GET | `/api/{module}/{resource}` | 목록 (검색조건 query, 서버 페이징 옵션) |
| GET | `/api/{module}/{resource}/{id}` | 상세 |
| POST | `/api/{module}/{resource}` | 생성 |
| PUT | `/api/{module}/{resource}/{id}` | 수정 (`rowVer` 필수) |
| POST | `/api/{module}/{resource}/delete` | 다건 삭제 (`ids`) |
| POST | `/api/{module}/{resource}/{id}/{action}` | 상태 전이 (`confirm`, `cancel`, `start` …) |
| POST | `/api/{module}/{resource}/{id}/{detail}/batch` | 디테일 일괄 저장 `{created, updated, deleted}`(rowKey·rowVer), 한 트랜잭션, 행 오류는 `errors[{rowKey, field, code, message}]` (P0 8단계, 09 5.1) |
| POST | `/api/{module}/{resource}/export` | 서버 엑셀(목록과 같은 조건·정렬 SQL 의 커서, `@ProgAuth(EXCEL)`, 열은 서버 `ColumnSpec` 화이트리스트) (09 6장) |
| GET | `/api/{module}/{resource}/columns` | 열 정의(번역된 제목·타입·코드그룹·정렬·내보내기 가능) — 화면은 폭·고정만 덧붙임 (09 G2) |

응답: `{ "result": "OK", "data": … }` / 목록 `{ "result": "OK", "list": […], "total": n }` / 오류 `{ "result": "ERROR", "code": "CONFLICT", "message": "다른 사용자가 먼저 수정했습니다." }`
- 저장 요청에는 `X-Idempotency-Key` 헤더 지원 (연타 중복 생성 방지). 처리 기록은 `sys_inbox`(consumer `API`)로 통일.
- 목록 쿼리 `?page=1&size=50&sort=필드,asc&sort=필드,desc&total=true` — 정렬은 리소스별 enum 화이트리스트(최대 3), 마지막에 PK 고정, size 는 정책 `GRID_PAGE_SIZE_MAX`. **`sort` 는 `List<String>` 으로 받지 말 것**(스프링이 쉼표로 쪼갬 → `getParameterValues`). 필드 검증 오류는 400 + `errors[{field, code, message}]`(모두 모아서 한 번에).
- **같은 키로 "그때 만든 행"을 돌려줘야 하는 저장**(POP 실적·PDA 입출고, P2·P4)은 업무 행에 `client_req_id` + `unique(comp_cd, client_req_id)` 를 두고, 중복 도착이면 **기존 행을 원래 성공 응답으로** 돌려준다(409 아님). 동시 도착은 unique 위반을 잡아 먼저 저장된 행을 반환 (04 2.4)
- **오류 코드는 enum 한 곳**(`HbErrorCode(status, messageKey)`, 문구는 i18n 키). DB 제약 위반은 SQLState·제약 이름으로 매핑: 23505 → 409 `DUPLICATE`, 23503 → 409 `IN_USE`, 23514 → 400 `INVALID_VALUE`. 본문 파싱 실패·파라미터 타입 불일치·필수값 누락은 400 (P0 4단계, 04 2.2)
- 화면 JS(`hb.api`)는 `code` 로 분기하고 **401·403 일 때만** 로그인으로 보낸다. 네트워크 끊김·타임아웃·5xx 는 세션을 지키고 오류만 표시(공장 Wi-Fi 음영). 기본 타임아웃은 서버 처리 한도보다 짧게 (04 2.3, 4장 2번)

### 5.8 아웃박스 / 인박스 (02 6.2-C)
- **문제**: `@TransactionalEventListener(AFTER_COMMIT)`만 쓰면 커밋 직후 프로세스가 죽을 때 이벤트가 사라진다. 외부 연계는 재전송 근거가 없다.
- **발행**: 업무 저장과 **같은 트랜잭션**에 `sys_outbox` 행을 넣는다. 릴레이(스케줄러 1개)가 `select … for update skip locked limit n`으로 꺼내 처리하고 `SENT`/재시도(지수 백오프)/`FAIL`로 표시. 최소 1회 전달(at-least-once).
- **릴레이는 3단으로** (04 2.5): ① 짧은 트랜잭션에서 `for update skip locked` 로 집어 `SENDING`·`locked_at`·`attempts+1` 로 바꾸고 커밋 ② **트랜잭션 밖에서** 외부 송신 ③ 짧은 트랜잭션에서 `where status = 'SENDING'` 조건부로 결과 기록. 오래된 `SENDING`(예: 15분)은 다시 집는다. **외부 호출을 DB 트랜잭션 안에서 기다리지 않는다** — 커넥션 풀 고갈(Doubly 실제 장애 원인). 재시도 상한은 집을 때 검사
- 재시도는 채널별: 수신측 멱등키가 있는 ERP 송신은 타임아웃도 재시도, 메일·알림은 읽기 타임아웃 재시도 금지(중복이 누락보다 나쁨)
- **수신**: 아웃박스 소비자, `/ext/**`(설비·PDA·콜백), API 멱등키 모두 처리 트랜잭션 안에서 `sys_inbox`에 (consumer_cd, message_id) insert → 중복이면 무시.
- **두 경로 구분**: 모듈 간 같은 트랜잭션이 필요한 것(결재 → 업무 상태, 검사 합격 → 가용재고)은 동기 이벤트, 외부·비동기(ERP 송신, 메일·알림, 라벨 인쇄)는 아웃박스.
- 릴레이는 요청 밖 실행이므로 `TenantRunner`로 행의 `comp_cd`를 설정하고 처리한다.
- Kafka 등 브로커는 쓰지 않는다(단일 DB 모듈러 모놀리스에서 운영 부담만 늘어남).

### 5.9 원장 지원 (02 6.2-D)
- `hb_forbid_update_delete()` 트리거 함수, 역분개 헬퍼(`LedgerSupport.reverse(table, id, reason)`), 원장 테이블 생성 체크리스트(3.1)를 P0에서 제공한다.

### 5.10 TenantRunner (02 6.2-B)
```java
tenantRunner.run("ACME", () -> outboxRelay.processBatch());      // 트랜잭션 + set_config + TenantContext
tenantRunner.forEachActiveComp(comp -> purgeJob.run(comp));      // 회사마다 별도 트랜잭션
```
- 스케줄러·`@Async`·아웃박스 릴레이·정리 배치는 이것으로만 DB에 접근한다. 회사 미설정 상태의 DB 접근은 RLS로 0건이 되므로, "아무 일도 안 했는데 성공"으로 보이지 않게 **회사 미설정 실행은 예외**로 처리한다.

---

## 6. 화면 셸

- 화면 셸 구조: 로그인 → `/main`(사이드바 메뉴 + 탭 + iframe) → `/page/{progCd}` → `sys_prog.view_path` 템플릿.
- 화면 JS 표준 = `init → initSearch → initGrid → initForm → initButtons → initCodes` 클로저 1개 (cust-mgt 스타일).
- 공통 JS (`js/common/`):
  - `hb.api` — fetch 래퍼 (401→로그인, 409→재조회 안내, 403/400 메시지 표시, 멱등키 자동 첨부)
  - `hb.grid` — **자체 제작 조회 그리드**(서버 페이징·정렬 화이트리스트, sticky 헤더·고정 열, 서버 엑셀 내보내기 권한 연동, 컬럼 상태 저장 — docs/09 4장, docs/10 3장). 편집 그리드 엔진은 PoC 후 결정(docs/10 11장)
  - `hb.code` — 공통코드 드롭다운
  - `hb.ui` — alert/confirm/toast, 저장 중 버튼 잠금
- POP 키오스크 레이아웃(`layout/pop-layout.html`)은 P0에서 골격만: 큰 버튼, 터치, 메뉴 없음.
- **화면 문구는 처음부터 i18n 메시지 키**(`messages_ko.properties` 기본, `en`·`vi` 등 추가 가능)로 쓴다. 외국인 작업자 POP 다국어 대비(03 Top 10 #8), 나중에 넣으면 전 화면 수정.
- **`help_key`**: 화면·주요 필드마다 도움말 키를 단다(`{progCd}.{field}`). P5 RAG 도움말·매뉴얼 생성의 앵커.
- UI 라이브러리(2026-10-15 결정, docs/10 11장): 상용 Kendo 구매는 PoC 이후, 그 전에는 **무료 범위만** — Kendo UI Core(Apache-2.0, Grid 없음) 또는 MIT·Apache 오픈소스. 무료 라이브러리는 `static/vendor/{lib}/{version}/` 에 LICENSE·SHA-256 목록과 함께 커밋. 상용 Kendo(`static/vendor/kendo/`)는 산 뒤에도 커밋하지 않음. 어떤 라이브러리든 **함수 템플릿만**(문자열 템플릿·eval 금지).

---

## 7. 보안 기본값

- 비밀번호 저장: DelegatingPasswordEncoder(`{bcrypt}`). 정책은 아래 "비밀번호 정책". **연속 5회 실패 → 15분 잠금 후 자동 해제**(V004 `locked_until`), 관리자 수동 잠금은 자동 해제 없음·기존 세션도 끊음. 실패 잠금은 기존 세션을 끊지 않는다(남이 틀려서 정상 사용자 작업이 끊기지 않게). **접속 주소 기준 시도 제한**(IP+회사 20회·IP 100회/10분, 인메모리 — 서버 여러 대면 대수만큼 느슨). 모두 설정값 (3단계 완료)
- **필수 설정 = 기동 중단 / 선택 연동(ERP·메일·AI) = 그 기능만 503 `*_NOT_CONFIGURED`** (04 2.6)
- **prod 기동 가드**(9단계): 예시 비밀번호(`change-me`) 그대로·세션 쿠키 Secure 꺼짐·배포 모드 이상이면 기동 중단. 헬스체크는 얕은 liveness(항상 UP) / 깊은 readiness(DB) 분리, Hikari `leak-detection-threshold` 로 트랜잭션 안 외부 호출 회귀 탐지. **P2 추가(26 검토)**: 장치 시뮬레이터·가짜 장비 서버 빈이 prod 에서 활성이면 기동 중단 — 회사 프로젝트에서 시뮬레이터 플래그가 운영 프로필까지 켜져 있던 사례
- 리버스 프록시 뒤 배포 시 `X-Forwarded-For` 를 직접 파싱하지 않는다 — `server.forward-headers-strategy: native` + 신뢰 프록시 지정(`TRUSTED_PROXIES`, CIDR 또는 정규식, **비우면 아무도 안 믿음**)으로만(시도 제한·접속 로그 IP 위조 방지). prod 기동 가드는 신뢰 범위가 공인 IP 까지 넓으면 기동 중단 (2026-10-20 구현 — 설계에 있었으나 설정이 빠져 프록시 뒤에서는 모든 사용자가 프록시 IP 하나로 보였음, docs/17 E6)
- 세션 쿠키 `HttpOnly`, `Secure`(운영), `SameSite=Lax`. 비활동 30분 + **절대 수명 12시간**(로그인 시각을 세션에 기록, 활동과 관계없이 만료 → 공장 공용 PC 대비, 4단계 완료). CSRF 는 화면·API 모두(쿠키 → 헤더).
- **보안 헤더**(4단계 완료): CSP `default-src 'self'; script-src 'self'; style-src 'self'; object-src 'none'; frame-ancestors 'self'; base-uri 'self'; form-action 'self'`, Referrer-Policy same-origin, Permissions-Policy 최소, nosniff. **템플릿에 인라인 스크립트·스타일·on* 속성·`th:utext` 금지, JS 에서 innerHTML·eval 금지**(가드 테스트). UI 라이브러리를 붙일 때(8단계)도 eval 계열은 열지 않는다(함수 템플릿만, docs/10 11장 H6), style-src 는 PoC 실측(05 D11). HSTS 는 prod(9단계).
- **비밀번호 정책**(5단계, 05 D9 — NIST SP 800-63B-4 방향): 최소 12자(MFA 사용 계정 8자), 최대 64자(bcrypt 72바이트 사전 차단 유지), **흔한·유출 비밀번호 차단 목록 + 회사코드·아이디·회사명 포함 금지**, **조합 규칙·주기 변경은 기본 끔**(고객이 정책으로 켜면 허용된 동작으로 기록). 공장 NAT 대비 **회사별 신뢰 IP**(등록 IP 는 IP 단독 한도 완화, 05 G14).
- 외부 콜백·PDA·설비 API는 `/ext/**`로 분리 → API 키 + IP 허용목록. 콜백은 즉시 202 응답 후 비동기 처리. 키는 해시로 저장하고 **상수 시간 비교**, 기본값 키 금지(prod 가드), 테스트·모의 콜백 경로는 local 프로필에만(26 검토).
- 설정의 비밀값은 환경변수 / `.env`(커밋 금지). 운영은 SOPS+age 로 암호화 보관 → systemd-creds/Docker secrets 로 전달(Vault 는 쓰지 않음). 기본 프로필 = `local`, 운영은 명시적 활성화. 로컬 DB 포트는 `127.0.0.1` 에만 바인딩(4단계 완료).
- 오류 응답에 SQL·스택트레이스·제약 이름·입력값 노출 금지.
- **운영자(Super Admin) 접근**(9단계, 05 D4·D8): 별도 경로 `/ops/**` + 허용 IP + **MFA 필수**(패스키 하드웨어 키 + TOTP 백업, 메일 OTT 는 MFA 요소 아님) + 세션 15분. 회사 데이터 열람은 **고객 관리자가 승인한 지원 세션**(`sys_support_grant`: 기간·범위·사유)으로만, 전건 감사로그·고객 열람 가능. "모든 회사 조회" 경로는 만들지 않는다. break-glass 계정 1개 봉인. 회사 관리자 MFA 는 정책 `MFA_REQUIRED_FOR_ADMIN`(운영 전), 일반 작업자·POP 은 MFA 강제 안 함.
- **운영자 활용 지표(docs/11 D3, 2026-10-15 사용자 결정)**: 운영자는 회사별 **일 집계 숫자만**(`sys_usage_daily`: 회사·일·화면·기능·단말 구분별 사용자 수·건수 — 사용자 ID 없음) 본다. 읽기는 운영자 범위(`app.scope = 'OPS'`) 전용 SELECT 정책 하나로만(카탈로그 허용 목록, 근거 주석). 업무 데이터·로그 원문은 지원 세션으로만.
- **POP 인증(D4, 사용자 결정)**: 관리자가 **단말을 등록**(단말 키, 회사·공장·작업장 바인딩, 폐기 가능)하고, 작업자는 **사번 배지 스캔**으로 2초 안에 전환(회사 정책으로 PIN 추가). 단말 세션은 작업자 세션과 분리, 실적·접속 로그에 `terminal_cd` + 작업자. 구현 P2.
- **협력사 접속(D11, 사용자 결정)**: 기본은 **1회용 링크 토큰**(건별 범위·만료, DB 에는 해시만, 발행 회사 범위로 TenantContext, `client_type` 에 `EXT`), 거래량이 큰 협력사만 정식 계정. 구현 P5 SRM.

### 7.1 DB 계정과 마이그레이션 실행 (05 D1, G1·G2·G4)

| 계정 | 용도 | 속성 | 런타임 앱이 아나? |
|---|---|---|---|
| `postgres`(슈퍼유저) | 설치·확장·롤 생성 | 원격 로그인 금지(local peer 만) | 아니오 |
| `yeonos_owner` | Flyway·테이블 소유 | **NOSUPERUSER**, NOBYPASSRLS, NOCREATEROLE | **아니오** — 마이그레이션 1회 실행에만 |
| `yeonos_app` | 런타임 | 현행(슈퍼유저·BYPASSRLS·소유 금지, 기동 검사) | 예 |
| 백업 계정(OS 사용자 + replication) | 백업 | 최소 권한 | 아니오 |

- **운영(prod)**: `spring.flyway.enabled=false`. 배포 스크립트가 **같은 jar 를 마이그레이션 전용 모드로 1회 실행**(소유자 자격증명은 그 프로세스에만). 앱은 기동 시 `flyway_schema_history` 를 앱 계정으로 읽어 **"jar 가 기대하는 최신 버전 = DB 버전"이 아니면 기동 중단**. prod 에서 `flyway.enabled=true` 이면 기동 중단(9단계 prod 가드).
- local·test 는 현행대로 기동 시 Flyway(개발 편의). 로컬 docker 의 소유자 = 슈퍼유저는 개발 전용 — **설치 가이드에 운영 구성(소유자 ≠ 슈퍼유저)을 명시**.
- `DatabaseRoleGuard` 에 "앱 계정이 소유자 롤의 멤버가 아님(`pg_has_role`)" 검사 추가(9단계).
- 권한 축소: 원장 6종·`sys_audit_log`·`sys_access_log`·`sys_backup_check` 는 앱 계정 INSERT/SELECT 만(UPDATE/DELETE REVOKE + 트리거). 보존 정리는 **파티션 DETACH/DROP 을 소유자 권한의 별도 작업**으로. 카탈로그 검사에 "앱 계정이 TRUNCATE·REFERENCES·TRIGGER 권한을 가진 테이블" 추가.

### 7.2 백업·복구 — 랜섬웨어 대비 (05 3장, G3)

**원칙 3-2-1-1-0**: 사본 3, 매체 2, 오프사이트 1, **불변·오프라인 1**, **복구 검증 오류 0**. 공격자는 백업부터 지운다(국내 피해 기업의 상당수가 백업까지 감염 — 05 3.1).

| 항목 | 내용 |
|---|---|
| DB | 물리 백업 + WAL 아카이브 + **PITR**(pgBackRest, Linux). `pg_dump` 를 유일한 백업으로 쓰지 않음(PITR 불가, RLS 상태에서 앱 계정 덤프 누락). 주기: 전체 주 1 + 차등 일 1 + WAL 연속(`archive_timeout` 60s) |
| 불변 사본 | SaaS: S3 호환 Object Lock **Compliance 모드**, 운영과 **다른 계정(가능하면 다른 사업자)**(05 D2 — 호스팅 결정 때). 설치형: 오프라인 매체 교대 보관 또는 NAS WORM 스냅샷 |
| 자격증명 분리 | 백업 저장소 키는 쓰기만(삭제·보존 변경 불가), 만료 삭제는 저장소 수명주기 규칙. 백업 암호화 키는 DB 서버와 다른 곳에 봉인 |
| 첨부 | SaaS 버킷 버전 관리 + Object Lock, 앱 키에 객체 삭제 권한 없음(삭제 표시 + 수명주기). 설치형은 DB 백업과 같은 시점 묶음, `sys_file` 해시로 복원 후 검증 |
| 복구 리허설 | 자동 복원 시험(일회용 컨테이너 복원 → 앱 계정으로 기동 → 기동 가드 통과 → 회사별 행 수·원장 합계·파일 해시 표본 비교) SaaS 주 1·설치형 월 1, PITR 시험 분기 1, 전체 재해 훈련 연 1 |
| RPO/RTO (05 D3) | SaaS: RPO 5분·RTO 4시간으로 시작(1인 운영 야간 대응 포함해 정직하게 고지), 설치형: 계약서에 명시. 첫 재해 훈련 실측으로 확정 |
| 제품 기능(9단계) | `sys_backup_check`(전역: kind, status, 시각, detail) + **백업 건강 상태**(마지막 성공 백업·WAL 지연·마지막 복원 시험일)를 관리자 화면·기동 로그에 표시, 기준 초과 시 경고(기동은 막지 않음) |
| 회사 단위 복구 | 물리 PITR 은 클러스터 단위 → 별도 인스턴스에 PITR 후 그 회사 행만 앱 기능으로 내보내기·가져오기(런북에 절차) |

**설치형 지원 OS (05 D5, 2026-10-15 결정)**: **Linux(Docker 포함) 기본 + Windows 서버도 지원**. Windows 는 PostgreSQL 공식 설치판 + **제품이 제공하는 백업 스크립트**(`pg_basebackup` + WAL `archive_command`, 덮어쓰기 금지, 복원 시험 스크립트 포함)로 같은 3-2-1-1-0 원칙을 맞춘다. pgBackRest 의 Windows 미지원 여부는 구현 전에 확인한다. 설치 가이드는 OS 별로 둔다.

### 7.3 탐지·사고 대응·운영 (05 3.5·3.6·4.9)

- **보안 이벤트 로그**(6단계): 인증 성공·실패·잠금, 인가 실패, `SESSION_REVOKED/EXPIRED`, 사용자·권한·정책 변경, 엑셀 내보내기·인쇄, 개인정보 조회, 운영자 지원 세션, 백업·복원 결과. 비밀번호·토큰·세션 ID 는 남기지 않는다. 접속기록 보존 기본 2년.
- **탐지 규칙**: `TENANT_FORBIDDEN` 은 정상 사용에서 생길 수 없으므로 **1건이라도 알림**, 로그인 실패 급증·여러 계정 동시 실패, 관리자 새 IP·업무 외 시간, 권한 변경 전건, 대량 조회·내보내기·다건 삭제, WAL 아카이브·백업 실패, DB 소유자 로그인·DDL(배포 시간 외). 알림은 운영 메일과 분리된 휴대폰 푸시 1채널 + 일일 요약.
- **로그 무결성(05 D6)**: 감사·접속 로그는 앱 계정 INSERT/SELECT 만 + **일 단위 다이제스트 체인**(`sys_log_digest`)을 불변 백업 저장소로 반출. 행 단위 해시 체인은 02 결정대로 고객 요구 시.
- **대량 변경 제동**: 다건 삭제는 상한(예: 1,000건), 초과 시 비동기 + 관리자 승인.
- **운영 전**: 사고 대응 런북(인지 시각 기록 → 격리 → 범위 판단 → **24시간(정보통신망법)·72시간(개인정보) 신고** → 근절·전 자격증명 재발급 → 침투 이전 시점 PITR 복구 → 사후 기록), `/.well-known/security.txt` + VDP(05 D7: 저장소 private 유지), 패치 목표(Critical 7일·High 30일, PostgreSQL·JDK·Spring 보안 마이너 30일 내), 설치형 메이저 버전별 보안 지원 3년부터(D10), 연 1회 외부 점검. 법령 기한·시행일은 원문 확인 후 문서화.
- **공급망**: 의존성 잠금 + `verification-metadata.xml`(5단계, 생성 직후 검토), CI 도입 시 OSV 스캔·Dependabot·Gradle wrapper 검증·**서드파티 Action 커밋 SHA 고정**, 첫 설치형 납품 전 SBOM(CycloneDX)·릴리스 서명(서명 키는 개발 PC 밖), 설치형 업데이트는 서명 검증 실패 시 거부·다운그레이드 금지. → 서명을 뺀 나머지 완료(2026-10-27, 05 8.5·docs/52 Y10).
- **운영 지표·로그**(2026-10-27, docs/52 Y2): Micrometer + `/actuator/prometheus`(허용 IP·토큰, 밖은 404; 운영은 기본 닫힘 → 열면 관리 포트 분리·내부 주소·토큰을 기동 가드가 확인), 업무 지표 `hb_*`(아웃박스 대기·실패·가장 오래 기다림, 수신 중복, POP 재전송, 로그인 실패·시도 제한, RLS 403, 엑셀 거절·시간, 작업 지연, 백업 경과). 회사 꼬리표는 SaaS 에서만 켤 수 있고 기본 끔(Q2). 운영 로그는 JSON(ECS) + MDC `compCd`·`reqId`·`userId`, 응답 헤더 `X-Request-Id`. 경보·대시보드 예시는 `deploy/monitoring/`.
- **설치형 원격 유지보수**: 상시 VPN·RDP·공용 계정 금지 → 고객이 여는 세션형 원격 접속 + 개인 계정 + MFA + 기록. 운영 PC 와 개발 PC(최소한 계정·브라우저 프로필) 분리.
- **SaaS/설치형 책임 분담표**(05 3.7)를 계약서 부속·설치 가이드에 넣는다 — "백업은 누가 하나"가 사고 후 다툼이 되지 않게.

### 7.4 설비 연동(Collector) 보안 원칙 (05 4.8, 구현 P2)

- **아웃바운드 전용**: Collector → 서버 HTTPS push, 서버 → Collector 명령은 Collector 폴링으로만. **공장 안으로 인바운드 포트를 열지 않는다.**
- OPC UA 는 `SignAndEncrypt` + 인증서 신뢰 목록(자동 신뢰·anonymous 금지), Modbus 는 **읽기 기본**·쓰기는 별도 활성화 + 감사.
- `/ext/**` 는 기기별 API 키(회사·기기 바인딩, 폐기 가능) → 장기적으로 mTLS. 설치형 MES = Purdue L3, Collector = L2/L3 경계.
- 보안 절차 문서는 IEC 62443-4-1 의 8 practice 목차로, 4-2 FR1~7 자체 매핑표(목표 SL2)를 둔다(인증은 받지 않음).

---

## 8. 테스트 기반

| 종류 | 도구 | P0 필수 케이스 |
|---|---|---|
| 통합 | JUnit 5 + Testcontainers(PostgreSQL) + Flyway | 빈 DB 마이그레이션 → 시드 → 로그인 |
| 테넌트 격리 | 공통 테스트 베이스 | A사 사용자로 B사 데이터 조회/수정/삭제 → 0건/403 |
| RLS 우회 경로 | `TenantCatalogRulesTest` (DB 카탈로그 검사) | invoker 아닌 뷰, 허용 목록 밖 SECURITY DEFINER, 머티리얼라이즈드 뷰, RLS 없는 comp_cd 테이블 → 실패 |
| 가드 테스트 (04 2.1) | 소스·카탈로그 스캔 | Flyway 번호 중복(4), i18n 키 ko/en/vi 동기화(4), 존 없는 `LocalDate.now()`·`LocalDateTime.now()` 금지 + 테스트 JVM 시간대 고정(5), `comp_cd` 테이블 ↔ 보존 클래스·회사 해지 목록 커버리지(6), 필터 단계 401/403 봉투(3 ✅) — 예외는 이유 주석과 함께 허용 목록 |
| 테스트 컨텍스트 | | `@SpringBootTest` 설정 조합을 공통 베이스 1개로 고정(조합마다 컨텍스트·커넥션 풀이 생김). 스케줄은 test 프로필에서 끄고 작업 메서드를 직접 호출 |
| 아웃박스 | | 저장 롤백 시 아웃박스 행도 없음, 릴레이 2개 동시 실행 시 중복 처리 0, 같은 message_id 재수신 무시 |
| 원장 | | 원장 행 UPDATE/DELETE → 거부, 역분개 후 합계 0 |
| 보존 | | legal hold 범위·비LOG 클래스는 정리 배치가 지우지 않음 |
| 권한 | | 권한 없는 액션 호출 → 403, `@ProgAuth` 누락 서비스 → ArchUnit 실패 |
| 채번 | 동시 100스레드 | 중복 0건, 결번 허용 여부 명시 |
| 낙관적 락 | | 같은 행 동시 수정 → 하나 409 |
| 이상 사용 | 전역 규정(dev-playbook `playbook/testing/abuse-testing.md`, A~K) | 연타, 뒤로가기 재전송, URL 직접 입력, 다른 탭 동시 수정, 세션 만료 중 저장, 2MB 문자열, 음수/0/소수 수량, 특수문자·이모지 |
| 업무 흐름 (P2~, 26 검토) | Testcontainers + 실제 서비스 호출 | 모듈마다 `*FlowTest` 1개 이상: 처음부터 끝까지(예: 작업지시 → POP 실적 → 완료판정 → 입고) + 원장 합계 = 현재고 불변식 + A사/B사. 공유 개발 DB 금지 |
| 상태 전이 (P2~, 26 검토) | 순수 함수 단위 테스트 | 상태 × 사건 표 전체(허용·거부), DB 반영은 조건부 UPDATE 0건 → 409 테스트 |

`TESTING.md`를 프로젝트 루트에 만들어 실행 방법·테스트 데이터 규칙·사례를 누적.

---

## 9. P0 작업 순서 (마일스톤)

| # | 작업 | 확인 방법 |
|---|---|---|
| 1 ✅ | Gradle 골격, 프로필(local/test/prod), PostgreSQL docker-compose, Flyway V001(회사·공장 + RLS), 기동 시 DB 계정 검사 | `bootRun` + 빈 DB 마이그레이션 성공, 테스트 18건 (2026-10-15 완료) |
| 2 ✅ | TenantContext·회사 범위 트랜잭션 매니저·**TenantRunner**(+V002 회사 목록 범위), RLS 위반 → 403 오류처리, **RLS 우회 경로 카탈로그 검사(기동 시 + 테스트)** | 테스트 38건 (2026-10-15 완료). 웹 요청의 회사 범위 설정은 로그인과 함께 3단계 |
| 2.5 ✅ | **Spring Boot 4.1.1 전환** (03 6.1-3): 모듈 스타터, Testcontainers 2, MyBatis starter 4.1 | 테스트 38건 통과, 로컬 기동 health UP (2026-10-15) |
| 3 ✅ | 사용자(V003 sys_user, V004 잠금 자동 해제)·로그인(회사코드+아이디, Spring Security 7)·**IP+회사 시도 제한**·세션·401/302·CSRF(쿠키+헤더)·**웹 요청 회사 범위**·매 요청 계정 유효성 확인·로컬 개발 계정 | 테스트 75건, 실제 브라우저 로그인·로그아웃 확인 (2026-10-15) |
| 4 ✅ | 프로그램(전역, V005)·메뉴·권한그룹·`@ProgAuth`/`@ProgAuthExempt` + 화면 셸(메뉴·탭·탭별 iframe, **Kendo 없이** — Kendo 는 8단계 첫 그리드 화면부터) + i18n(ko/en, 사용자 언어 우선)·help_key + 오류 코드 enum·DB 제약 매핑·`hb.api` + 가드 테스트(@ProgAuth 커버리지·i18n 키·Flyway 번호·전역 테이블 쓰기 권한) + **보안 보완(05): CSP·보안 헤더·인라인 스크립트 분리·세션 절대 수명 12시간·로컬 DB 포트 127.0.0.1** | 테스트 112건, 브라우저: 메뉴·탭·권한별 버튼·영어 전환·메뉴 이름 XSS 무해화 확인 (2026-10-15) |
| 5 ✅ | 공통코드(시스템 전역 + 회사 확장)·정책(정의 전역 + 회사/공장 값, AI·비밀번호·신뢰 IP 키)·채번(패턴 토큰·초기화 주기 정합 검사·회사 시간대·**결번 없음**)·원장 트리거 `hb_forbid_update_delete` + 카탈로그 원장 규칙·BizClock + 시간대 없는 now() 금지 가드·테스트 JVM UTC·**비밀번호 정책(D9)·비밀번호 변경**·신뢰 IP·의존성 잠금(`gradle.lockfile`)·체크섬 검증(`verification-metadata.xml`) | 테스트 148건(신규 33), 브라우저에서 비밀번호 정책 위반 표시 확인 (2026-10-15). 역분개 헬퍼는 첫 원장 테이블(P2)과 함께 |
| 6 ✅ | **보안 이벤트 로그·탐지 규칙·일 다이제스트(7.3)**·첨부 검증(확장자·MIME·크기·SHA-256)·감사로그·**접속 로그(prog_cd·action·client_type, 본 요청과 분리된 REQUIRES_NEW — 그 트랜잭션에도 회사 범위가 설정되는지 테스트)**·엑셀 업/다운 + **보존 클래스·법적 보류·정리 배치** + 스케줄 조건부 활성·작업별 `pg_try_advisory_xact_lock`(다중 인스턴스·무중단 배포 중복 실행 방지) + comp_cd 커버리지 가드 | 테스트 198건(신규 50), 로컬 기동·실제 HTTP 업로드·내려받기·로그 확인 (2026-10-15). **엑셀 업/다운은 docs/09 그리드 분석 검토 뒤 별도 진행**. 로그 테이블 월 파티션은 보류(일반 테이블 + 인덱스, 정리는 DELETE) — 로그량 측정 후 9단계·운영 전 판단. 정리는 소유자 전용 `hb_purge_logs()`(앱 EXECUTE 없음) |
| 7 ✅ | 결재 엔진 + 문서 유형 핸들러 + **아웃박스/인박스**(멱등키 통일, **릴레이 3단: 집기 → 트랜잭션 밖 송신 → 결과 기록**) + 원장 규약 `entry_method`(docs/11 D1, 카탈로그 가드) | 테스트 239건(신규 41): 상신→순차 결재→업무 상태 변경(같은 트랜잭션)·반려·회수·알림(NOTIFY 아웃박스), 동시 결재·멱등키·롤백·A사/B사, 릴레이 동시 실행 중복 0·오래된 SENDING 회수·늦은 결과 폐기. 로컬 기동 v008 (2026-10-15). 알림 실제 채널은 P5(지금은 로그) |
| 8 ✅ | **샘플 화면 1개**: 거래처관리(마스터-디테일, 첨부, 엑셀, 감사로그, **피드백 버튼·도움말 패널**) — 이후 모든 화면의 표준 참조본. **자체 조회 그리드 `hb.grid`** + 폼, 디테일은 폼 목록 또는 PoC 편집 그리드. 병행: 1주 PoC(Tabulator 편집 그리드 + 위젯 Kendo UI Core vs OSS, docs/10 8·11장) | 테스트 267건(신규 28): 목록·상세·생성·수정·사용중지·담당자 일괄 저장·엑셀(실제 XLSX 파싱)·그리드 설정·피드백·변경 이력, A사/B사·수식 주입·상한·동시 내보내기·정렬·LIKE 조작. 브라우저: 셸 메뉴→탭, 필드 오류 표시, 행 오류, 우클릭 메뉴, 변경 이력, 도움말, 의견, 1366×768, CSP 위반 0 (2026-10-15). **남음: 1주 PoC(Tabulator 편집 그리드·위젯 Kendo UI Core vs OSS, 한글 IME 수동 확인 필요)** |
| 9 ✅ | 활용 일 집계 `sys_usage_daily`·역할별 홈 `home_prog_cd`(docs/11 D2·D6) + POP 인증·운영자 활용 지표·협력사 인증 원칙 결정(D3·D4·D11) + 운영자 콘솔(회사 생성, 초기 관리자, 기본 메뉴 복사, 관리자 잠금 해제, **MFA·IP 제한·고객 승인 지원 세션**) + **prod 기동 가드(flyway 비활성·버전 일치·소유자 롤 멤버 아님·예시 비밀번호)·헬스체크 분리·HSTS·장애 공지 status.json** + **마이그레이션 전용 실행 모드(7.1)·백업 건강 상태·설치형 백업/복원 스크립트(Linux·Windows)(7.2)** + 설치형 초기화 스크립트 | 테스트 300건(신규 33): 운영 기동 가드·소유자 롤 멤버·migrate 모드·status.json·HSTS·헬스 분리, 활용 집계·역할별 홈, 운영자 허용 IP·잠금·TOTP 등록·재사용 거부·비활동 만료·역할 분리, 회사 생성 → 초기 관리자 실제 로그인, 지원 세션 허락·범위·만료·회수·다른 운영자, 백업 건강. 브라우저: 운영자 로그인(TOTP)·회사 생성·지원 거부 → 고객 허락 화면에 거부 기록 표시 (2026-10-15). **설계와 다르게 한 것**: 백업 결과는 `sys_backup_check` 표 대신 상태 파일(백업 도구에 DB 쓰기 권한을 주지 않으려고), 운영자 MFA 는 TOTP 만(패스키는 이후), 백업 스크립트는 문법 검사까지(실서버 미실행) |
| 9.5 ✅ | **P0 마무리**: 편집 그리드 PoC 를 최신 코드에 합침(VendorChecksumGuard·local 전용 `/dev/poc/grid`), 조사 문서 06·07·08·12·13 검토, 로컬 데모 데이터(가상 자동차 2·3차 협력사 거래처 24곳), README 아키텍처 그림, **로그인 화면 개편**(분할 배치·자체 SVG·회사코드 기억·Caps Lock·장애 공지) | 테스트 309건(신규 9: 데모 4·로그인 2·병합 PoC 3), 브라우저: 1366·모바일 배치, 회사코드 기억·조작값 무시, 공지 `<script>` 글자 표시, 콘솔 오류 0 (2026-10-16). 시간대 경계에서만 실패하던 테스트 2건(UTC `current_date` 비교) 수정. **남음: 한글 IME 수동 판정(docs/10 11.5.2), Temurin 21, 디자인 토큰(P1 전)** |
| 9.6 ✅ | **디자인 토큰·반응형·기간 선택기**(docs/12 V1, 15, 16 0·1단계): `tokens.css`(3단 + 레이아웃) + 원시 색 금지·브레이크포인트 가드, 셸 메뉴 접기·덮개, 상세 서랍, 그리드 채우기, `hb.dateRange`(직접 입력·프리셋·2개월 달력·APG 키) + 서버 `DateRanges`·정책 `SEARCH_MAX_RANGE_DAYS`(V012) | 테스트 321건(신규 6: 토큰 가드 4·기간 API 2), node 21건. 브라우저: 775·1366×641·1920·3440·911×427 실측, 키보드 기간 선택, 이상 입력 6종, 콘솔 오류 0 (2026-10-16) |
| 9.7 ✅ | **입력 컴포넌트 2·3단계**(docs/16 9장): `hb.searchPanel`(Enter 조회·한글 조합 중 Enter 무시·조회 전 검사·연타 무시·values/setValues/reset, I12 자동 포커스는 처음 열 때만) + `hb.select`(공통코드, 서버 순서) + 세그먼트·체크·토글 CSS + `hb.number`(문자열 10진 값, 반올림 없이 자릿수 오류, 휠 증감 없음) — 거래처관리·활용 현황 조회 조건에 적용, 숫자 규칙은 `hb.numbers` 하나(붙여넣기 파서 공유) | 테스트 321건(변화 없음 — 가드가 새 문구 키·CSS 검사), node 28건(신규 7). 브라우저: Enter·IME 합성 이벤트·3연타·초기화·세그먼트 키·상태 복원(목록 밖 값)·숫자 이상 입력 7종·셸 탭 포커스 3경우·775 폭, 콘솔 오류 0 (2026-10-16). **남음: 실제 한글 IME 판정, hb.number 첫 화면은 P1 품목** |
| P2 ✅ | **P2 MES 코어 완료 2026-10-21**(docs/25 1~7단계 + 7c·PLM 연결·홈 링크 칸·입고 → 재고·공장 휴일 달력): V030~V036·V051·V055·V056·V066·V077·V081·V086. 완료 검토(한 것·버린 것·남긴 것·교훈) docs/25 11장 | 마지막 전체 check 1,439건(main c81edfa) |
| P3 2부(일부) ✅ | **P3 QMS 2부 Q5 4M·Q6 검사·측정값 원장·Q7 초품·Q9a 교정 NG 영향 LOT·Q10 SPC 2026-10-21**: V094·V099·V104·V106. 중간 정리 docs/29_P3 13장. Q8 NCR·Q9 게이트는 MES `lot_hold`·확장점 뒤(→ 그 뒤 Q8a·Q8b·Q9 완료, docs/29_P3) | 전체 check 1,603건 |

---

## 10. 다음에 정할 것 (P0 진행 중 결정해도 됨)

1. 저장소: GitHub private 저장소 생성 여부, 패키지명 `io.smarthappy` 확정 (도메인 보유 여부에 따라 `kr.smarthappy` 등)
2. 로컬 개발 DB: Docker Desktop으로 PostgreSQL 16+ 실행 vs Windows 직접 설치
3. ~~화면 다국어~~ → **결정: P0 4단계부터 i18n 메시지 키** (03 6.2)
4. ~~결번 허용~~ → **결정(P0 5단계): 결번 없음** — 채번은 업무 트랜잭션 안에서만(MANDATORY), 롤백되면 번호도 되돌아감. 같은 규칙·기간을 받는 트랜잭션은 커밋까지 순서대로 대기(업무 트랜잭션을 짧게)
