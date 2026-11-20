# QA 2026-10-23 — QMS 협력사 품질 조회 계약(SupplierQualityQuery) 경계

> 독립 QA 세션(docs/29 8장). 기준 main `9d054b8b`(계약은 #341 `7bed1804`). 제품 코드는 고치지 않는다.
> 표시: **[코드]** 코드를 읽어 확인 · **[재현]** 시험으로 재현 · **[기록]** 실행 기록(docs/testing/qa.md).
> 시험: `src/test/java/io/smarthappy/qa/SupplierQualityQaTest.java` (5건: 통과 4 · 결함 재현 `@Disabled` 1).
> SRM 쪽 연결(R4a, RECV_PPM)은 아직 없어서 이번 회차는 **계약 자체**만 본다. SRM 이 붙으면 같은 시험 위에 "계약 호출·NONE·A사로 B사·롤백" 경계를 잇는다.

## 0. 요약

| ID | 구분 | 내용 | 조치 |
|---|---|---|---|
| QQ-1 | 정책 질의 | 수입검사 NG 근거 NCR 이 살아 있는데 같은 LOT 을 무효 없이 다시 검사(OK)하거나, NCR 을 그대로 둔 채 검사를 무효로 하면 그 NCR 불량이 계약 값 어디에도 안 나온다 | 상위·QMS 판단 |
| QQ-2 | 결함(작음) | 극단 날짜(연도 > 294276, `LocalDate.MAX`)에서 "예외 없음·잘못된 인자는 빈 값" 약속이 깨진다 — DB 오류로 부르는 쪽 트랜잭션이 깨지거나 `DateTimeException` | **QMS 세션** `@Disabled` 재현 |
| QQ-3 | 정책 질의 | 반품 목록 상한 500 을 넘으면 앞 500 줄만, 잘렸다는 표시 없음 — 부르는 쪽이 합을 내면 말없이 모자람 | 상위·SRM 연결 때 판단 |
| — | 확인 | 회사 시간대(LA) 날짜 경계, 다른 트랜잭션이 행을 잡고 있어도 기다리지 않음 | 이상 없음 |

모듈 시험(`SupplierQualityQueryTest`)이 이미 다루는 것 — JUDGED 최신 1건·특채(USE_AS_IS) 차감·VOID/취소 제외·서울 날짜 경계·A사로 B사·NONE — 은 다시 쓰지 않았다.

## 1. QQ-1 — 근거 검사가 바뀐 NCR 의 불량이 사라짐 (정책 질의)

**[코드]** `QmsLinkMapper.xml#receiptQuality`
- `live` = LOT 마다 가장 최근 `JUDGED` 수입검사 하나.
- `rejected_qty` = **그 검사**(`t.insp_id = v.insp_id`)를 근거로 한 출처 INSP NCR 만.
- `other` = 출처가 INSP **아닌** NCR 만.
→ 출처 INSP 인데 근거 검사가 "최신 JUDGED" 가 아닌 NCR 은 둘 다에 안 걸린다.

**[코드]** 서버가 이 상태를 막지 않는다.
- `InspService.create` 는 같은 LOT 에 이미 JUDGED 수입검사가 있어도 새 검사를 만든다.
- `InspService.void` 는 그 검사를 근거로 한 살아 있는 NCR 을 확인하지 않는다.
- `NcrService` 는 NCR 을 만들 때만 근거 검사가 JUDGED 인지 본다.

**[재현]** `재검사_무효_뒤_남은_NCR_불량은_계약_값에서_빠진다_현행`

| 흐름 | 계약 값(검사량/불량 · 판정/NG · 처리 중 · 기타) | NCR |
|---|---|---|
| L1 100: NG → NCR 9 | `100/9 1/1 open1 other0` | OPEN |
| L1 무효 없이 두 번째 검사 OK | `100/0 1/0 open0 other0` | **OPEN 그대로, LOT 보류** |
| L2 50: NG → NCR 6 → 검사 무효(200) | 빈 목록 | **OPEN 그대로, LOT 보류** |

모듈 시험은 "NCR 을 먼저 취소한 뒤 무효" 순서만 본다. 순서를 지키지 않아도 화면·API 가 받아 주므로, 실제로 불량이 난 LOT 이 수입 PPM 에서 조용히 빠질 수 있다.

**질문:** 다음 셋 중 무엇이 의도인가?
- (가) 재검사·무효 때 살아 있는 출처 INSP NCR 을 막거나 경고한다.
- (나) 근거 검사가 최신이 아니어도 그 LOT 의 출처 INSP NCR 을 rejected 에 넣는다.
- (다) 현행 그대로 둔다(재검사 결과가 이긴다). 이 경우 계약 주석에 적는다.

시험은 현행 값을 고정해 두었다. 정책이 바뀌면 이 시험도 바뀌어야 한다.

같은 맥락에서 정책으로 정할 것이 하나 더 있다 **[코드]**. "LOT 마다 최신 판정, 판정 시각이 기간 안" 이므로, 1분기에 NG 였던 LOT 을 2분기에 다시 판정하면 이미 낸 1분기 값이 나중에 다시 조회할 때 바뀐다(소급). SRM 평가가 결과를 저장해 둔다면 문제없다.

## 2. QQ-2 — 극단 날짜에서 예외 (결함, 작음)

**[코드]** `QmsSupplierQualityQuery`
- `valid()` 는 null·from>to·1년 초과만 거른다.
- `LocalDate.MAX` 이면 `from.plusYears(1)`·`to.plusDays(1)` 에서 `DateTimeException` 이 난다.
- Java 범위 안이지만 PostgreSQL `timestamptz` 범위(서기 294276년) 밖인 날짜는 SQL 까지 가서 `timestamp out of range` 가 난다. 그러면 PostgreSQL 트랜잭션이 aborted 가 되어 같은 트랜잭션의 다음 SQL 이 모두 실패한다.

이는 계약 주석의 "예외 없음·잘못된 인자도 빈 값 — 부르는 쪽 트랜잭션을 rollback-only 로 만들지 않는다" 와 어긋난다.

**[재현]** `결함QQ2_극단_날짜도_예외없이_빈값_트랜잭션_계속`(`@Disabled`) — 연도 300000 → `DataIntegrityViolationException`(timestamp out of range), `LocalDate.MAX` → `DateTimeException`.

실제로 일어날 가능성은 낮다. SRM 평가 기간은 화면 입력이고, 날짜 형식 검증을 통과한 9999-12-31 은 PostgreSQL 범위 안이라 문제없다. 그래도 계약이 명시적으로 약속한 성질이므로 결함으로 분류했다. 수정안(제안): `valid()` 에서 연도 범위(예: 1 ~ 9999)를 함께 거른다.

## 3. QQ-3 — 반품 목록 상한이 말없이 자름 (정책 질의)

**[재현]** `반품_목록_501줄은_말없이_500줄` — 닫힌 NCR 26건의 RETURN 줄 501개를 만들었다.
- 계약은 앞 500 줄을 돌려준다(합 500).
- DB 의 합은 501 이다.
- `List` 만 돌려주므로 부르는 쪽은 잘렸는지 알 수 없다.

계약 주석에 "상한 MAX_ROWS" 는 적혀 있다. 다만 SRM 이 반품 수량 합이나 건수를 평가 점수에 쓴다면 조용히 틀린다. 한 협력사·1년 기간에 500 줄은 드물지만 불가능하지 않다(NCR 당 처리 줄 최대 20).

**질문:** SRM R4a 가 이 목록을 합산에 쓰는가? 쓴다면 다음 중 하나가 필요하다.
- 합계 전용 조회
- 잘림 표시(`MAX_ROWS + 1` 개를 읽어 판단)
- 기간 분할

## 4. 이상 없음

| 해 본 것 | 결과 |
|---|---|
| **[재현]** 회사 시간대 LA: 판정 시각 LA 3/10 23:59(서울 3/11) / LA 3/11 00:00 | 3/10 조회 = 앞 것만, 3/11 조회 = 뒤 것만 — 회사 시간대 날짜 |
| **[재현]** 다른 트랜잭션이 이 회사의 `qms_insp`·`qms_ncr`·`qms_ncr_disp`·`lot_master` 를 FOR UPDATE 로 잡은 동안(잡은 것 확인 후) 세 조회 | `lock_timeout 2s` 안에 모두 응답 — 잠금 없는 읽기 |
| **[코드]** 품목·단위별 줄 | LOT 이 있는 품목은 기본 단위를 바꿀 수 없다(`hb_check_item_unit_in_use` 트리거). 그래서 한 품목·한 협력사에 단위가 둘 생기는 경로는 사실상 없다. 시험은 쓰지 않음 |
| **[코드]** NCR 한 건 `ncr()` 의 inner join(`qms_defect`) | `defect_id NOT NULL` + FK 라 존재하는 NCR 이 빈 값이 되는 경우 없음 |

## 5. SRM 연결(R4a) 회차에 볼 것

- SRM 이 `SupplierQualityQuery.of(provider)` 로 받는지, QMS 빈이 없는 설치본(NONE)에서 평가가 "0 PPM" 이 아니라 "자료 없음" 으로 나오는지.
- SRM 평가 트랜잭션 안에서 계약을 부른 뒤 SRM 저장이 실패할 때의 롤백. 계약은 쓰기가 없으므로 SRM 쪽만 확인하면 된다.
- A사 평가에 B사 협력사 id 를 넣을 때(계약은 빈 값). SRM 이 이를 "0 PPM 우수" 로 저장하지 않는지.
- QQ-1·QQ-3 의 결정이 SRM 계산식에 반영됐는지.

## 6. 재확인 — QMS #348(main `76bddb82`)

**[재현]** `SupplierQualityQaTest` 5건 모두 통과. 기대값은 QMS 세션이 #348 에서 새 정책으로 바꿨고, QA 가 같은 시험으로 돌려 봤다.

| ID | 결정·수정 | 확인 |
|---|---|---|
| QQ-1 | 사용자 결정 = NCR 기준 집계 + 무효 경고(docs/29_P3 16.1). 서버는 재검사·무효를 막지 않고, 무효 사유 끝에 처리 중 NCR 번호를 붙인다 | 재검사 OK 뒤 `100/9 1/0 open1`, 무효 뒤 `0/6 0/0 open1`, 무효 사유 `… [OPEN NCR …]` ✅ |
| QQ-2 | `valid()` 가 극단 날짜를 거른다 | 연도 300000·`LocalDate.MAX`·서기전 5000년 → 빈 값, 같은 트랜잭션에서 다음 조회 정상 ✅ |
| QQ-3 | `returnsToSupplier` → `ReturnPage(rows, truncated)` | 501줄 → 500줄 + `truncated = true` ✅ |

SRM 쪽 경계(R4a)는 `docs/qa/2026-10-23_pda-srm-ppm.md` 2장.
