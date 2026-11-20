# QA 2026-10-27 — 검사 엑셀 올리기(V196) · OCAP(V198) · S-1 수정(V199) · 현장 단말 앱(V197·#604)

> 독립 QA 세션(docs/29 8장), 상위 지시(사용자 2026-10-27). 기준 main `1c6e00f9`(V199). 제품 코드·마이그레이션은 고치지 않았다.
> 표시 **[코드]**(코드를 읽어 확인) · **[재현]**(시험으로 재현) · **[관찰]**(지금 동작을 시험으로 고정, 판단은 정책 질의).
> 시험: `qa.InspImportQaTest` 4 · `qa.OcapQaTest` 4 · `qa.PdaTerminalQaTest` 4 · `qa.SpcProjectionQaTest` +1(V199 맞춤). 모두 통과(관찰은 지금 동작 고정).

## 0. 요약 — 분류

| ID | 내용 | 분류 | 담당 |
|---|---|---|---|
| **S-2** | SPC 처리 번호(act_id) 순서 ≠ 실제 처리 순서 — 종결된 이벤트의 이력이 "종결 → 확인" 으로 보이고, 투영(V199)의 마지막 처리자가 종결한 사람이 아님 [재현] | **고칠 것**(조용히 틀림, 상태·건수는 맞음) | QMS |
| I-1 | 엑셀의 지난 측정 시각에 계측기 상태를 **오늘** 기준으로 판정 — 측정 시각엔 교정 기한 지난 계측기라도 오늘 재교정했으면 받음 [관찰] | 정책 질의(추천: 측정 시각 기준) | QMS |
| I-2 | 측정 시각 "검사 시작 전" 비교가 분 단위 — 시작 30초 뒤 시작한 검사에 같은 분의 이른 시각(초 포함)이 들어감 [관찰] | 정책 질의(낮음) | QMS |
| O-a | 고르기가 OCAP 항목 잠금을 기다리는 사이 종결 → 409 이지만 코드는 일반 `CONFLICT`("다른 사용자가 먼저 수정") — 화면 사전 확인의 `QMS_SPC_EVENT_STATE` 와 다름 [재현] | 정책 질의(낮음, 문구만) | QMS |
| D-1 | PDA 로 등록한 단말의 **활성화** 요청이 접속 로그에 `POP`(헤더값)로 남음 [관찰] | 정책 질의 | 플랫폼 |
| D-2 | 사무 사용자가 헤더 `X-HB-Client: PDA` 를 보내면 접속 로그가 `PDA` [관찰] | 정책 질의 | 플랫폼 |
| — | #609 hb.grid 전환 5화면 | **미실시**(이번 회차 시간 부족 — 다음 회차 표에 남김) | QA |

## 1. 검사 실행 엑셀 올리기 (V196, docs/29_P3 25장)

모듈 시험 `InspMeasImportTest` 가 덮은 것(화면과 같은 판정·SPC·자동 보류, 행 오류·멱등, 미리보기 뒤 **차례로** 바뀜 409, 파일 둘 교착 없음, 권한·A사로 B사)은 다시 하지 않았다.

| 해 본 것 | 결과 | 근거 |
|---|---|---|
| 화면 입력과 엑셀 실행이 **같은 샘플을 동시에** — 시험이 검사 머리(`qms_insp` FOR UPDATE)를 쥐고 두 요청이 모두 기다리는 것을 관찰한 뒤 놓음 | 하나만 성공. 이번 실행은 화면 승 → 엑셀 409 `QMS_INSP_IMPORT_STALE`, 파일의 다른 샘플도 0건(전체 롤백), 작업은 다시 `VALIDATED`. 엑셀이 이기면 화면 400(같은 샘플) — 두 갈래 모두 단언 | [재현] `InspImportQaTest.화면과_엑셀이_…` · [코드] `InspService.record` 머리 잠금 → `takenSamples` 다시 읽음 |
| 미리보기 뒤 상태가 바뀌면 전체 롤백 | 실행은 다시 검증하지 않고 스테이징 행을 넣되, 넣는 경로(`measureImported`)가 머리 잠금 뒤 화면과 같은 `checkEntry` 로 다시 본다 → 하나라도 어긋나면 엔진 트랜잭션 전체 롤백 | [코드] `ImportService.execute` · `QmsInspMeasImportTemplate.insertAll` |
| `IMPORT_MAX_ROWS` 경계(회사 정책 100 = 하한) | 100 행 받음(total 100), 101 행 400 `IMPORT_TOO_MANY`, 작업 행이 남지 않음 | [재현] `최대_행수_경계_…` |
| 회사 시간대 자정 — 검사 시작 오늘 00:00:30 KST | "오늘 00:00" 받음 → 저장 = 전날 15:00 UTC(회사 시간대 해석 맞음). "어제 23:59" → `BEFORE_START` | [재현] `회사_시간대_자정과_검사_시작_직전` |
| 검사 시작 직전(같은 분) | "오늘 00:00:10"(시작 20초 전)도 받음 — 비교가 시작 시각을 **분으로 내린** 값과 함(`withSecond(0)`) → **I-2** | [관찰] 같은 시험 |
| 지난 측정 시각의 계측기 상태 | 측정 시각(어제)엔 OVERDUE(다음 기한 35일 전), 오늘 재교정 OK → 어제 잰 값 받음. 재교정 전엔 GAUGE_BLOCKED → **I-1** | [관찰] `관찰_지난_측정_시각의_계측기_상태는_오늘_기준` · [코드] `InspService.gaugeStatus` → `GaugeCalibService.status`(오늘 기준) |
| 공장 시간대 | 측정 시각은 **회사** 시간대로 해석(공장 `sys_plant.time_zone` 아님). 안내 문구에 "회사 시간대" 라 적혀 있어 결정된 동작으로 봄 | [코드] `measuredAt` · `messages.properties import.guide.qmsInspMeasuredAt` |

**I-1 (정책 질의, 추천: 측정 시각 기준)** — 화면 입력은 측정 시각 = 지금이라 "오늘 상태" 와 "측정 시각 상태" 가 같다. 엑셀은 지난 시각을 적을 수 있어(검사 시작 이후) 둘이 갈린다.
- 받으면 안 되는데 받는 쪽(조용히 틀림): 측정 당시 기한이 지났거나 NG 였던 계측기 값이 들어가고, 원장에는 아무 표시가 없다. 교정 NG 영향 조회(`GaugeCalibService.impact`)는 **측정 시각** 기준이라 규칙이 둘이 된다.
- 반대 경우(측정 당시 OK, 오늘 기한 지남)는 거부되어 다시 교정해야 올릴 수 있다(시끄럽게 틀림).
- 안: `checkEntry` 의 계측기 상태를 `at` 시점으로 계산(그 시각 이전 마지막 유효 교정 + 주기). 결정 전까지 지금 동작을 시험으로 고정했다 — 바꾸면 그 시험의 기대를 뒤집는다.

**I-2 (정책 질의, 낮음)** — 파일 형식이 초를 받으므로(`yyyy-MM-dd HH:mm[:ss]`) 비교도 초 단위로 할지, 형식을 분까지로 줄일지.

## 2. OCAP (V198, docs/29_P3 26장)

모듈 시험 `OcapTest` 가 덮은 것(문구 수정과 고르기 동시, 거부 사유별 처리 행 없음, 권한·A사로 B사 항목·앱 계정 직접 SQL)은 다시 하지 않았다.

| 해 본 것 | 결과 | 근거 |
|---|---|---|
| 사용 중지(커밋 전)와 고르기 동시 — 고르기가 FOR SHARE 를 기다리는 것을 관찰한 뒤 커밋 | 고르기는 기다렸다가 바뀐 행으로 다시 판단 → 400 `INACTIVE`, 처리 행·사본 0 | [재현] `OcapQaTest.사용중지와_규칙변경이_…` · [코드] `OcapService.lockAndCheck`(READ COMMITTED 의 FOR SHARE 는 커밋된 최신 행을 다시 읽음) |
| 공통 → 규칙 WE2 로 바꿈(커밋 전)과 WE1 위반 고르기 동시 | 400 `OCAP_RULE`, 처리 행 0 | [재현] 같은 시험 |
| 고르기가 항목 잠금을 기다리는 사이 다른 사람이 종결 | 고르기는 시작 때 OPEN 을 봤지만 처리 행 INSERT 에서 V157 가드가 거부 → 409, 처리 행·사본 0, 상태 CLOSED. 응답 코드는 `CONFLICT`(일반 문구) → **O-a** | [재현] `고르기가_기다리는_사이_종결되면_…` · [코드] `ApiExceptionHandler` 55000 → CONFLICT |
| 사본 원장 UPDATE·DELETE — **소유자(마이그레이션) 계정** | 트리거 `ck_qms_spc_event_act_ocap_append_only` 로 거부(앱 계정 권한 회수는 모듈 시험) | [재현] `사본_원장은_소유자_계정도_…` |
| 앱 계정 TRUNCATE(사본 원장·목록) | 42501 | [재현] 같은 시험 |
| 소유자 계정 TRUNCATE | 행 트리거 밖이라 막히지 않음 — 소유자는 마이그레이션 전용(운영에서 앱이 쥐지 않음)이라 **범위 밖** | [코드] V198 |
| B사 사용자가 A사 위반 id 로 고르기 목록·조치 | 둘 다 404, A사 원장 그대로 | [재현] `B사가_A사_위반_id로_…` |
| 잠금 순서 | 고를 항목은 `order by ocap_id ... for share` 로 한 방향, 그 뒤 처리 행(이벤트 advisory) — 고르기 둘이 서로 다른 순서로 골라도 교착 경로 없음 | [코드] `OcapMapper.lockForPick` |
| 화면을 연 뒤 문구가 바뀌면 사본은 **바뀐** 문구 | 모듈 시험 D 가 의도로 고정(26장 OC-5) — 결정된 동작 | [코드] |

## 3. S-1 수정(V199) 확인과 새 문제 S-2

### 3.1 기존 행 맞춤(여러 회사) [재현]
`SpcProjectionQaTest.V199_기존_행_맞춤은_여러_회사에서_틀린_행만_고친다` — A사(ACK 이벤트·CLOSE 이벤트)·B사(ACTION 이벤트)의 투영 행을 트리거를 끄고 틀리게 만든 뒤(A사 `last_by`, B사 `status`), V199 파일의 맞춤 블록(가드 끄기 → 회사마다 `app.comp_cd` → 계산과 다른 행만 고침 → 가드 켜기)을 그대로 실행.
- 두 회사 모두 투영 = 계산, 맞던 행(A사 CLOSE 이벤트)은 그대로, 블록 뒤 앱 계정의 직접 UPDATE 는 다시 거부(가드 켜짐).
- 시험 DB 소유자는 슈퍼유저라 RLS 를 건너뛴다. 운영(소유자 ≠ 슈퍼유저, FORCE RLS)의 회사별 거름은 블록이 회사마다 `set_config` 하는 것을 [코드]로 봄.

### 3.2 무작위 동시 시험 반복 [재현]
`무작위_동시_처리_뒤에도_…` 를 `@RepeatedTest(40)` 으로 임시로 돌림(커밋에는 `@Test` 그대로):
- 36 통과, **4 실패** — 모두 `[종결·무효 뒤에 다른 처리가 없다] expected 0 but was 1`. 투영 = 계산(diff 0)은 40회 모두 통과 → **S-1 은 고쳐짐**, 남은 실패는 새 문제 S-2.

### 3.3 S-2 원인 [코드] · 결정적 재현 [재현]
S-1 과 같은 뿌리: `act_id` 는 행을 만들 때 받고, 같은 이벤트의 줄 세우기(V157 가드 advisory 잠금)는 그 **뒤**다.
1. 종결(T1)이 번호 **10** 을 받고 가드 잠금 앞에서 밀린다(아직 원장에 없음).
2. 확인(T2)이 번호 **11** 을 받고 먼저 가드를 지나 커밋 — 종결이 아직 없으니 허용.
3. T1 이 가드를 지나 커밋 — 종결 "뒤" 처리가 아니니 허용.
4. 원장을 번호 순으로 읽으면 `CLOSE(10), ACK(11)` — 실제 순서(확인 → 종결)와 반대.

| 어디가 틀리게 보이나 | 결과 |
|---|---|
| 처리 이력(`SpcEventMapper.findActs`, `order by act_id`) | 종결 다음에 확인이 있는 이력 — "종결 뒤에도 처리가 된" 것처럼 보임 |
| 투영(V199: 마지막 처리 = `act_id` 최대) | `CLOSED:acker` — 종결된 이벤트의 "마지막 처리자" 가 종결한 사람이 아님. 계산 함수도 같은 정의라 diff 는 0(검사로 안 잡힘) |
| 상태 | 맞음(CLOSED — bool_or) · 홈 건수·상태 필터 영향 없음 |

결정적 재현: 부록 A(S-1 과 같은 기법 — 종결 INSERT 가 번호를 받은 뒤 `note` 식에서 시험이 쥔 advisory 잠금을 기다리게 함). 이 브랜치에서 돌려 실패 확인:
`[S2] 번호순=CLOSE:closer,ACK:acker 투영=CLOSED:acker` → `expected "CLOSED:closer" but was "CLOSED:acker"`.

**고칠 곳 — QMS(결함, 중간)**. 안(고르는 것은 QMS):

| 안 | 내용 | 득실 |
|---|---|---|
| (가) 줄 선 뒤 순번 | 가드가 advisory 잠금을 잡은 **뒤** 이벤트 안 순번(`act_seq` = 그 이벤트 max+1)을 매기고, 이력·투영·계산이 그 순번으로 정렬 | 잠금 안이라 순번 = 실제 처리 순서. 열 추가(V2xx) + 기존 행 채우기(act_id 순 — 과거 역전은 못 고침) |
| (나) 종결 뒤 처리 행 판정을 시각으로 | `acted_at`(트랜잭션 시작 시각)도 커밋 순서가 아니라 같은 문제 | 비추천 |
| (다) 정의 바꾸기 | "마지막 처리 = 종결·무효가 있으면 그것" 으로 투영·계산을 정의 | 이력 표시 역전은 남음 |

- 다시 해 보려면: 부록 A 메서드를 `SpcProjectionQaTest` 에 붙이고 `./gradlew test --tests 'io.smarthappy.qa.SpcProjectionQaTest'`.
- 상위 규칙(옵션 (a)): 꺼진 시험이 main 에 남지 않게, 재현 시험은 QMS 수정 PR 과 함께 켜진 채로 들어간다. 무작위 회귀 시험은 끄지 않는다(주석에 S-2 를 적음).

## 4. 현장 단말 앱 (V197 #601 · 접속 로그 #604)

모듈 시험 `PdaAppTest`·`TerminalClientTypeTest` 가 덮은 것(세 칸, 401/403, 경로 조작 파라미터·링크, A사로 B사, 활성화 뒤 PDA/POP, 단말의 헤더 위조 무시)은 다시 하지 않았다.

| 해 본 것 | 결과 | 근거 |
|---|---|---|
| 로그인 없이 공개 경로 이웃 — `/pda/version.json/`·`;jsessionid=`·대소문자·`.bak`·`/pda/release.json`·`/pda/yeonos-pda.apk`·`/pda/../api/...`·내려받기 API | 모두 200 아님. POST 는 403/405 | [재현] `PdaTerminalQaTest.공개_경로_허용이_…` · [코드] SecurityConfig permitAll 은 정확히 `GET /pda/version.json` |
| 내려받기 파일 경로 조작 | 파일 이름은 고정(`yeonos-pda.apk`·`release.json`), 요청 값으로 경로를 만들지 않음, NOFOLLOW_LINKS·realPath 확인 | [코드] `PdaAppService` |
| PDA 로 등록한 단말의 활성화 요청 | 접속 로그 `POP`(헤더값 — 활성화 시점엔 단말 주체가 없음) → **D-1** | [관찰] `관찰_PDA_단말의_활성화_요청은_…` |
| 등록 유형을 POP → PDA 로 바꾼 뒤 | 다음 요청부터 `PDA`(단말 주체는 요청마다 DB 에서 만듦) | [재현] `등록_유형을_바꾸면_…` |
| 사무 사용자가 `X-HB-Client: PDA` | 접속 로그 `PDA` — 단말 종류 조건으로 거르면 사무 요청이 섞임 → **D-2** | [관찰] `관찰_사무_사용자가_PDA_헤더를_…` |

- **D-1(정책 질의)**: 활성화 코드로 서버가 단말 유형을 아는 시점이므로 그 요청의 로그를 등록 유형으로 남길지. 모듈 시험이 "활성화는 헤더 그대로 POP" 를 단언하고 있어 지금은 의도로 보임 — 확인만.
- **D-2(정책 질의)**: 단말 주체가 아닌 요청은 헤더 POP·PDA 를 WEB 으로 내릴지(헤더는 WEB·API 만 받기). 감사 조회에서 "PDA 요청" 을 믿으려면 필요.

## 5. 미실시
- #609 hb.grid 전환 5화면(목록 대 grid 행·순서·건수, 정렬 허용 목록, 권한) — 이번 회차 시간 부족. 다음 QA 회차 표에 남긴다.

## 6. 결정 (2026-10-27 사용자 — 추천대로, 상위 기록)

| # | 결정 | 담당 |
|---|---|---|
| S-2 | 고칠 것 — 가드 잠금 뒤 이벤트 안 순번으로 이력·투영·계산 정렬, 재현 시험(부록 A) 켜진 상태로 | QMS #628(V201) |
| O-a | 고칠 것 — 잠금 대기 중 종결이면 `QMS_SPC_EVENT_STATE` | QMS(S-2 와 같은 PR) |
| I-1 | **측정 시각 기준**으로 통일 — 엑셀의 지난 측정도 그 시각의 계측기 교정 상태로 판정(교정 NG 영향 조회와 같은 규칙) | QMS |
| I-2 | **초 단위 비교**로 바로잡음 — 검사 시작 전 측정은 같은 분 안이라도 거부 | QMS |
| D-1 | 그대로 — 활성화 요청은 유형을 아직 몰라 헤더값(POP). 도움말·docs/50 에 이미 기록 | — |
| D-2 | 바로잡음 — `X-HB-Client` 헤더는 **단말 세션일 때만** 인정, 사무 세션은 WEB 로 기록 | 시스템관리 |

## 부록 A — S-2 결정적 재현 시험(`SpcProjectionQaTest` 에 붙일 메서드)

지금 main 에서는 **실패**한다(그래서 이 QA PR 에는 넣지 않음). QMS 수정 PR 에 켠 채로 넣는다. 정렬 열을 바꾸는 안이면 마지막 단언의 `order by act_id` 를 그 열로 바꾼다.

```java
    /**
     * 결함 S-2 재현(2026-10-27): 처리 번호(act_id)는 줄 세우기(V157 가드 잠금) 전에 받으므로 "번호 순서 ≠ 실제 처리 순서". 번호를 먼저 받은 종결(T1)이
     * 가드 앞에서 밀린 사이 큰 번호의 확인(T2)이 먼저 들어가고, 그다음 종결이 들어간다(종결 뒤가 아니므로 가드가 허용). 원장을 번호 순으로 읽으면
     * "종결 → 확인" 이 되어 ① 처리 이력이 거꾸로 보이고 ② 투영(V199, act_id 최대)의 마지막 처리자가 종결한 사람이 아니라 확인한 사람이 된다
     */
    @Test
    void 결함S2_번호를_먼저_받은_종결이_늦게_들어가도_마지막_처리는_종결이다() throws Exception {
        long ev = event();
        long gate = 800_000L + ev;
        try (Connection hold = DriverManager.getConnection(jdbcUrl(), OWNER_USER, testPassword()); Statement st = hold.createStatement()) {
            st.execute("select pg_advisory_lock(" + gate + ")");
            int before = lockWaiters();
            Future<Throwable> late = POOL.submit(() -> catchThrowable(() -> runner.call(comp, () -> jdbc.update("""
                    insert into qms_spc_event_act (comp_cd, event_id, act_type, note, acted_by)
                    values (hb_current_comp(), ?, 'CLOSE', (select 'QA 종결' from (select pg_advisory_xact_lock(?)) z), 'closer')""", ev, gate))));
            for (int i = 0; i < 200 && lockWaiters() <= before; i++) {
                Thread.sleep(50);
            }
            assertThat(lockWaiters()).as("종결이 번호를 받은 뒤 기다린다").isGreaterThan(before);
            runner.call(comp, () -> jdbc.update("insert into qms_spc_event_act (comp_cd, event_id, act_type, acted_by) values (hb_current_comp(), ?, 'ACK', 'acker')",
                    ev));
            st.execute("select pg_advisory_unlock(" + gate + ")");
            assertThat(late.get(20, TimeUnit.SECONDS)).as("종결은 확인 뒤라 가드가 허용").isNull();
        }
        System.out.println("[S2] 번호순=" + accounts.query(comp, "select string_agg(act_type || ':' || acted_by, ',' order by act_id) from qms_spc_event_act where event_id = ?",
                String.class, ev) + " 투영=" + accounts.query(comp, "select status || ':' || last_by from qms_spc_event_state where event_id = ?", String.class, ev));
        assertThat(accounts.query(comp, "select status || ':' || last_by from qms_spc_event_state where event_id = ?", String.class, ev))
                .as("종결된 이벤트의 마지막 처리자 = 종결한 사람").isEqualTo("CLOSED:closer");
        assertThat(accounts.query(comp, "select string_agg(act_type, ',' order by act_id) from qms_spc_event_act where event_id = ?", String.class, ev))
                .as("원장 순서 = 실제 처리 순서(확인 → 종결)").isEqualTo("ACK,CLOSE");
    }
```
