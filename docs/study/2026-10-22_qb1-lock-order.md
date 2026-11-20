# S1 재고 칸 잠금 순서와 교착 (QA QB-1)

> 2026-10-22 · 관련 PR: WMS #202, MES #208 (main #206·#210), QA 분석 docs/qa/2026-10-22_module-boundary.md · 걸린 시간: __분

**상황 한 줄**: POP 실적 **정정**과 다른 라인의 **실적**이 같은 시각에 들어왔다. 둘 다 비관리 품목 NUT·BOLT 를 재고에서 빼는데, 하나는 NUT → BOLT, 다른 하나는 BOLT → NUT 순서로 지나갔다. 결과는 PostgreSQL `40P01 deadlock detected` → 사용자에게 500.

## 0. 먼저 예측 (코드 보기 전, 3줄)

> 두 트랜잭션이 같은 자원 두 개를 반대 순서로 잡으면 무슨 일이 일어나나? 어떻게 막겠나?

-

## 1. 볼 곳

- `src/test/java/io/smarthappy/qa/PopWmsStockLinkQaTest.java:161` — 재현 시험. 래치(latch)로 두 요청을 "동시에" 만드는 방법을 본다
- `src/main/java/io/smarthappy/module/wms/inv/InventoryService.java:61` — `CELL_ORDER` 정렬 기준
- `src/main/resources/mapper/wms/LotStockMapper.xml:72` — 같은 순서의 SQL `ORDER BY ... nulls first`
- `src/main/java/io/smarthappy/module/pop/PopWorkService.java:359` — MES 실적 투입 정렬
- `src/main/resources/mapper/pop/PopWorkMapper.xml:164` — MES 정정 되돌림 정렬
- `docs/30_P4_WMS_설계.md` 11.6 잠금 순서 절

## 2. 설명할 것 (내 말로)

1. 교착이 생기려면 어떤 조건이 필요한가? 이 사건에서 "자원"은 정확히 무엇(어떤 표의 어떤 행)이었나?
   - 내 설명:
2. LOT 단위로 잠그고 있었는데도 왜 막지 못했나? (힌트: "비관리 품목")
   - 내 설명:
3. WMS #202 만으로는 왜 부족했고, MES #208 이 왜 따로 필요했나? (QA 가 #202 뒤에도 재현된다고 보고했던 이유)
   - 내 설명:
4. Java 의 `Comparator.nullsFirst` 와 SQL 의 `nulls first` 를 왜 굳이 맞춰야 하나? PostgreSQL 의 기본값은?
   - 내 설명:
5. 이 수정을 SQL Server(T-SQL) 프로젝트에 옮긴다면 무엇이 같고 무엇이 다른가?
   - 내 설명:

## 3. 채점 (Claude)

## 4. 퀴즈

> 2장을 쓴 뒤 `스터디 채점: docs/study/2026-10-22_qb1-lock-order.md` 라고 요청하면 Claude 가 3문제를 낸다.

| # | 문제 | 내 답 | 결과 |
|---|---|---|---|

## 5. 기록

| 날짜 | 점수 | 다시 볼 것 |
|---|---|---|
