# QA 2026-10-27 — SPC 투영 회귀 시험이 가끔 깨짐: 원인 = 결함 S-1(마지막 처리자 순서)

> 독립 QA 세션(docs/29 8장), 상위 제보: 시스템관리 브랜치 전체 check 에서 `qa.SpcProjectionQaTest.무작위_동시_처리_뒤에도_투영_전체가_원장_계산과_같다` 1회 실패(expected 0 but was 2), 같은 커밋 단독 재실행은 통과.
> **결론: 시험의 흔들림이 아니라 실제 투영 동시성 결함이 드물게 드러난 것.** 기준 main `16feccc0`. 표시 **[코드]** · **[재현]**.

## 1. 원인 [코드]

| 어디 | 무엇을 "마지막 처리" 로 보나 |
|---|---|
| 다시 계산 함수 `hb_qms_spc_event_state_calc()` (V186) | `max(act_id)` 인 처리의 `acted_by`·`acted_at` |
| 투영 트리거 `hb_qms_spc_state_on_act()` (V186) | **마지막에 커밋한** 처리 — INSERT 마다 `last_by = NEW.acted_by, last_at = NEW.acted_at` 로 덮음 |

두 정의가 같으려면 "번호 순서 = 커밋 순서" 여야 한다. 그런데 그렇지 않다.
- `act_id` 는 식별 열 기본값이라 **행을 만들 때**(대상 목록 평가) 받는다.
- 같은 이벤트의 줄 세우기(V157 가드 `pg_advisory_xact_lock(hashtext('qms_spc_event_act'), event_id)`)는 그 **뒤** BEFORE 트리거에서 한다.
- 그래서 번호를 먼저 받은 T1 이 가드 잠금 앞에서 밀리면, 뒤에 번호를 받은 T2 가 먼저 줄 서서 커밋할 수 있다. 그다음 T1 이 커밋해 투영을 자기 값으로 덮는다.
  - 투영: 마지막 처리 = T1(작은 번호).
  - 계산: 마지막 처리 = T2(큰 번호).

- **상태(status)는 갈라지지 않는다.** 종결·무효 뒤 처리는 가드가 거부하므로, 투영의 "마지막 처리 종류" 와 계산의 우선순위(VOID > CLOSE > 그 밖)가 늘 같다.
- 갈라지는 것은 `last_by`·`last_at`(diff 의 양방향 EXCEPT 에 한 행씩 = **2** — 보고된 값과 같음).
- **영향(조용히 틀림)**: SPC 위반 grid 의 "마지막 처리자" 칸과 상세 머리가 투영을 읽는다(`SpcEventMapper.eventCols` → `s.last_by, s.last_at`). 그래서 다른 사람·이른 시각이 보인다. 처리 이력 목록(`findActs`, 원장)은 맞다. 홈 카드 건수·상태 필터는 영향 없음(상태만 씀).

## 2. 재현

| 방법 | 결과 |
|---|---|
| **결정적**: 부록 A 의 시험(`SpcProjectionQaTest` 에 넣을 메서드) — T1 의 INSERT 가 번호를 받은 뒤 `note` 식(대상 목록은 열 순서로 평가, `act_id` 가 먼저)에서 시험이 쥔 advisory 잠금을 기다리게 함 → 그동안 T2(ACK, `early`) 커밋 → 잠금을 놓아 T1(ACTION, `late`) 커밋 | 번호 순서 `late,early`(T1 이 작은 번호), 투영 `ACKED:late` · 계산 `ACKED:early`, **diff 2** — 보고와 같은 모양(이 브랜치에서 돌려 확인) |
| 확률적: 무작위 회귀 시험 `@RepeatedTest(40)`(이 장비, 단독) | 40/40 통과 — 창(번호 받기 ~ 가드 잠금)이 매우 좁아 부하가 큰 전체 check 에서 드물게만 걸린다 |
| 확률적: 전체 `./gradlew check`(이 장비, 2026-10-27, 이 브랜치) | **1회 실패**(expected 0 but was 2) — 보고(시스템관리 브랜치)와 같은 모양. 전체 check 의 부하에서 다시 나타났다 |

- 다시 해 보려면: 부록 A 메서드를 `SpcProjectionQaTest` 에 붙이고 `./gradlew test --tests 'io.smarthappy.qa.SpcProjectionQaTest'`.
- 상위 결정(2026-10-27): 꺼진 시험이 main 에 남지 않게, 결정적 재현 시험은 **QMS S-1 수정 PR 과 함께 켜진 채로** 들어간다(이 QA PR 에는 문서·무작위 시험 주석·진단만).
- 무작위 시험은 시드가 고정(`new Random(42 + t)`)이라 처리 순서는 같다. 결과를 바꾸는 것은 스레드 끼어들기(번호 받은 뒤 줄 서기 전 선점)뿐이다.
- 무작위 회귀 시험의 실패 메시지에 어긋난 이벤트(투영 상태:처리자 | 계산 상태:처리자 | act_id 순 처리)를 찍게 했다(`mismatch()`). S-1 이면 상태는 같고 처리자만 다르다.
- 무작위 회귀 시험은 **끄지 않는다**(상위 지시). 고치기 전에는 드물게 깨질 수 있고, 그 실패는 S-1 이다(시험 주석에 적음).

## 3. 고칠 곳 — QMS (결함, 우선 중간: 조용히 틀림이지만 상태·건수는 맞음)

| 안 | 내용 | 득실 |
|---|---|---|
| **(가) 추천** | `hb_qms_spc_state_on_act` 가 `NEW` 대신 원장에서 다시 읽는다: `select acted_by, acted_at from qms_spc_event_act where event_id = NEW.event_id order by act_id desc limit 1`(상태는 지금처럼 NEW 종류 또는 같은 방식으로 우선순위 계산). AFTER 트리거는 가드 잠금을 쥔 뒤이고 plpgsql 새 문장 스냅숏이라, 먼저 커밋한 큰 번호가 보인다 | 트리거 한 곳. 정의가 계산 함수와 같아진다. 이벤트당 처리 수가 적어 비용 무시 |
| (나) | 번호를 줄 세운 뒤 받게: 가드에서 잠근 다음 `NEW.act_id := nextval(...)` | 식별 열 GENERATED ALWAYS 와 부딪혀 열 정의 변경 필요 — 무겁다 |
| (다) | "마지막" 정의를 커밋 순서로(계산도 그렇게) | 커밋 순서를 원장에서 알 수 없다(`acted_at` = 트랜잭션 시작 시각) — 불가 |

QMS 가 고칠 때 부록 A 메서드를 켜진 채로 함께 넣으면 회귀 시험이 된다(고치기 전에는 diff 2 로 실패, 고친 뒤 통과).

## 부록 A — 결정적 재현 시험(QMS S-1 수정 PR 에 켜진 채로 넣을 것)

`src/test/java/io/smarthappy/qa/SpcProjectionQaTest.java` 에 그대로 붙인다. 필요한 것: `event()`·`diff()`·`lockWaiters()`·`POOL`·`runner`·`jdbc`·`accounts` 는 이미 클래스에 있다. `java.sql.Connection`·`DriverManager`·`Statement`·`Future`·`TimeUnit`·`catchThrowable` import 도 이미 있다.
고치기 전 결과: 번호 순서 `late,early`, 투영 `ACKED:late` · 계산 `ACKED:early`, diff 2.

```java
    /**
     * 결함 S-1 재현(2026-10-27, 무작위 회귀 시험이 가끔 2 로 깨진 원인): 처리 번호(act_id, 식별 열 기본값)는 행을 만들 때 받고, 같은 이벤트의
     * 줄 세우기(V157 가드 advisory)는 그 뒤 BEFORE 트리거에서 한다. 그래서 번호를 먼저 받은 처리가 줄은 늦게 서서 나중에 커밋할 수 있다.
     * 투영(V186 hb_qms_spc_state_on_act)은 "마지막에 커밋한 처리" 로 last_by·last_at 을 덮고, 다시 계산 함수는 "act_id 가 가장 큰 처리" 를
     * 마지막으로 본다 → 마지막 처리자·시각이 조용히 갈라진다(상태는 같음 — 종결·무효 뒤는 가드가 거부).
     * 결정적으로: T1 의 INSERT 가 번호를 받은 뒤 note 식에서 시험이 쥔 advisory 잠금을 기다리게 하고(대상 목록은 열 순서로 평가 — act_id 가
     * 먼저), 그동안 T2 가 큰 번호로 들어가 커밋, 그다음 T1 이 커밋
     */
    @Test
    void 결함S1_번호를_먼저_받은_처리가_늦게_커밋해도_투영의_마지막_처리가_계산과_같다() throws Exception {
        long ev = event();
        long gate = 700_000L + ev;
        try (Connection hold = DriverManager.getConnection(jdbcUrl(), OWNER_USER, testPassword()); Statement st = hold.createStatement()) {
            st.execute("select pg_advisory_lock(" + gate + ")");
            int before = lockWaiters();
            Future<Throwable> late = POOL.submit(() -> catchThrowable(() -> runner.call(comp, () -> jdbc.update("""
                    insert into qms_spc_event_act (comp_cd, event_id, act_type, note, acted_by)
                    values (hb_current_comp(), ?, 'ACTION', (select 'QA 늦게' from (select pg_advisory_xact_lock(?)) z), 'late')""", ev, gate))));
            for (int i = 0; i < 200 && lockWaiters() <= before; i++) {
                Thread.sleep(50);
            }
            assertThat(lockWaiters()).as("T1 이 번호를 받은 뒤 note 식에서 기다린다").isGreaterThan(before);
            runner.call(comp, () -> jdbc.update("insert into qms_spc_event_act (comp_cd, event_id, act_type, acted_by) values (hb_current_comp(), ?, 'ACK', 'early')",
                    ev));
            st.execute("select pg_advisory_unlock(" + gate + ")");
            assertThat(late.get(20, TimeUnit.SECONDS)).isNull();
        }
        assertThat(accounts.query(comp, "select string_agg(acted_by, ',' order by act_id) from qms_spc_event_act where event_id = ?", String.class, ev))
                .as("번호 순서: 늦게 커밋한 T1 이 작은 번호").isEqualTo("late,early");
        System.out.println("[S1] 투영=" + accounts.query(comp, "select status || ':' || last_by from qms_spc_event_state where event_id = ?", String.class, ev)
                + " 계산=" + accounts.query(comp, "select status || ':' || last_by from hb_qms_spc_event_state_calc() where event_id = ?", String.class, ev));
        assertThat(diff()).as("투영의 마지막 처리(커밋 순서) = 계산의 마지막 처리(act_id 최대)").isZero();
    }
```
