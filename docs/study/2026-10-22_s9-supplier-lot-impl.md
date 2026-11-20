# S9 (PR 읽기) 같은 공급사 LOT 추가 입고 = 새 내부 LOT

> 2026-10-22 · 결정 docs/29 #43 · 방식 변경(2026-10-22 사용자): **MES 세션이 구현**하고, 사용자는 병합된 PR 을 읽고 2장 예측·5장 설계 메모와 비교한다(워크스루 W1 의 사례). 아래 체크리스트는 MES 구현 기준으로 쓴다 · 걸린 시간: __분

## 1. 문제

WMS R3b(ASN 입고) 시험 중 발견: 같은 ASN 행의 나머지를 **같은 공급사 LOT 번호**로 다시 받으면 거부된다.
원인은 P2 LOT 규칙 "공급사·품목별 공급사 LOT 은 한 번"(V034 의 유일 인덱스). 실제 현장에서는 같은 공급사 LOT 이 여러 번 나눠 들어온다.

**결정 #43**: 입고할 때마다 **새 내부 LOT** 을 만들고, 공급사 LOT 은 속성으로 둔다. 리콜 때는 공급사 LOT 으로 찾으면 관련 내부 LOT 이 모두 나와야 한다.
버린 안: 기존 LOT 에 수량 더하기(만든 양 `qty_created` 고정·원장 대사·계보가 복잡해진다).

## 2. 먼저 예측 (코드 보기 전, 3줄)

> 유일 인덱스를 그냥 지우면 끝일까? 무엇이 함께 깨지거나 바뀌어야 할까?

-

## 3. 볼 곳

- `src/main/resources/db/migration/V034__lot_genealogy.sql:76-78` — 지금 규칙(유일 인덱스)
- `src/main/java/io/smarthappy/module/production/LotService.java:208-212` — 중복이면 칸 오류 `supplierLotNo/DUPLICATE`
- `src/main/resources/mapper/production/LotMapper.xml:33` — 공급사 LOT 으로 찾기(키워드)
- 시험: `src/test/java/io/smarthappy/module/production/LotTest.java:133`, `src/test/java/io/smarthappy/module/wms/inv/MaterialReceiptTest.java:339` — 지금 규칙을 **단정하는** 시험(바뀌어야 함)

## 4. 할 일 체크리스트 (순서는 스스로 정한다)

- [x] 새 마이그레이션 `V135__lot_supplier_lot_repeat.sql`(임시 V9114, MES 대역 — **적용된 V034 는 고치지 않는다**)
  - 유일 인덱스를 지우고, 같은 열로 **일반 조회 인덱스**를 만든다(리콜 조회가 느려지지 않게)
- [ ] `LotService` 의 중복 처리 정리(이제 이 경로에서 `DuplicateKeyException` 이 무엇을 뜻하나?)
- [ ] 시험 바꾸기: "두 번째는 DUPLICATE" → "두 번째는 **새 LOT**, 공급사 LOT 은 같음"
- [ ] 새 시험: 공급사 LOT 으로 찾으면 두 LOT 모두 나온다 / **A사로 B사**: B사가 같은 공급사 LOT 번호로 받아도 A사 것과 섞이지 않는다
- [ ] (생각해 볼 것) 같은 ASN 행을 나눠 받는 흐름(WMS R3b)이 이제 통과하는지
- [ ] docs/25 LOT 절과 docs/testing/mes.md 에 한 줄씩
- [ ] `./gradlew check` 통과(JDK 21)

## 5. 내 설계 메모 (구현 전에 3~5줄)

-

## 6. 리뷰 (Claude — 사용자가 "리뷰해줘" 하면 여기에)

## 7. 기록

| 날짜 | 소요 | 막힌 곳 | 다음에 다르게 할 것 |
|---|---|---|---|
