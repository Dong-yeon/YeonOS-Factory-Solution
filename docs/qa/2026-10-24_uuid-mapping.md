# QA 2026-10-24 — MyBatis uuid 칸 읽기·쓰기 (PLM 발견 후속)

> 독립 QA 세션(docs/29 8장), 상위 요청. **시험·문서만 — 제품 코드 수정 없음.** 기준 main `960778a0`.
> 표시: **[코드]** 매퍼·DTO·마이그레이션을 읽어 확인 · **[재현]** `src/test/java/io/smarthappy/qa/UuidMappingQaTest.java`(앱 설정에 시험 문장을 더해 실제 MyBatis 로 실행).

## 0. 요약

- **함정은 읽기 쪽만이다 [재현].**
  - 설정에 UUID 처리기가 없다(`hasTypeHandler(UUID.class) = false`).
  - 그래서 uuid 칸을 **`UUID` 필드로 받으면 오류 없이 null** 이 된다. 자동 매핑이 처리기 없는 형의 칸을 건너뛰기 때문이다.
  - `::text` 로 받아 String 필드에 넣거나, `resultType="map"` 으로 받으면(드라이버가 준 `java.util.UUID`) 된다.
- **쓰기는 된다 [재현].** `#{}` 로 넘긴 UUID 는 `@Param`(맵) 이든 객체 getter(`#{t.moveGrpId}`)든 `setObject` 로 간다. DB 에서 `pg_typeof = uuid` 이고 값도 같다.
- **지금 저장소에는 함정에 빠진 곳이 없다 [코드].**
  - uuid 칸을 읽는 매퍼는 모두 `::text`→String 이거나, 읽지 않는다(조인 조건에만 쓴다).
  - JdbcTemplate 경로(`MailService`·`OutboxRelay`)는 `rs.getObject(…, UUID.class)` 로 직접 읽는다. jOOQ(APQP)는 uuid 칸을 쓰지 않는다.
- **제안(상위 판단)**: 플랫폼에 UUID `TypeHandler` 를 전역 등록한다. 득실은 3장.

## 1. uuid 칸 — 마이그레이션 전체 [코드]

| 표 · 칸 | NULL | 용도 |
|---|---|---|
| `sys_outbox.message_id` | NOT NULL | 아웃박스 메시지 id(수신 멱등 키) |
| `wms_print_job.outbox_message_id` | NOT NULL | 인쇄 작업 ↔ 아웃박스 |
| `inv_trx.move_grp_id` | 빈 값 가능 | 재고 이동 묶음(나가고 들어오는 두 행) |
| `plm_pcn_mail.outbox_id` | NOT NULL | PCN 메일 ↔ 아웃박스(메일 발송 결과 읽기) |

`req_id`·`client_req_id` 등 다른 요청 id 칸은 uuid 형이 아니다(varchar). 위 4개가 전부다.

## 2. 매퍼·DTO 에서 쓰는 곳 [코드]

| 매퍼 · 문장 | 방향 | 자바 쪽 형 | 판정 |
|---|---|---|---|
| `wms/InvMapper.xml#insertTrx` `#{t.moveGrpId}` | 쓰기 | `InvDtos.TrxReq.moveGrpId : UUID`(getter) | 된다 — [재현] 객체 getter |
| `wms/InvMapper.xml#findMoveGroup` `where t.move_grp_id = #{moveGrpId}` | 쓰기(조건) | `@Param UUID` | 된다 — [재현] 맵 |
| `wms/InvMapper.xml` `trxCols` `t.move_grp_id::text as move_grp_id` | 읽기 | `TrxRow.moveGrpId : String` | 된다(`::text`). 서비스가 `UUID.fromString` 으로 되돌림(`InventoryService:234`) |
| `wms/LabelMapper.xml#insertJob` `#{messageId}` → `outbox_message_id` | 쓰기 | `@Param UUID` | 된다 |
| `wms/LabelMapper.xml` `jobFrom` `o.message_id = j.outbox_message_id` | 조인만 | — | 해당 없음(칸을 읽지 않음) |
| `plm/PlmPpapMapper.xml#insertPcnMail` `#{outboxId}` | 쓰기 | `@Param UUID` | 된다 |
| `plm/PlmPpapMapper.xml#findPcnMails` | 읽기 | `PcnMailRow` 에 outbox 칸 없음 | 해당 없음 — 발송 결과는 `MailService.statuses`(JdbcTemplate) |
| (JdbcTemplate) `MailService.statuses`·`OutboxRelay` | 읽기 | `rs.getObject("message_id", UUID.class)` | 된다 — MyBatis 밖 |

- `UUID` 형 필드가 있는 DTO 는 `InvDtos.TrxReq`(파라미터 전용)·`MailStatus`·`OutboxMessage`·`SrmMailbox.Mail` 이다.
- 이 중 **MyBatis `resultType` 으로 쓰이는 것은 없다** — 뒤 셋은 JdbcTemplate·메모리 객체다.
- PLM 이 겪은 경우(PCN 메일 발송 결과를 `UUID` 필드로 받음)는 지금 main 에서는 `MailService` 경로로 바뀌어 있다 **[코드 — 추정: PLM #489 에서 바꿈]**.

## 3. UUID TypeHandler 전역 등록 — 득실

| 안 | 득 | 실 |
|---|---|---|
| **(가) 플랫폼에 `BaseTypeHandler<UUID>` 전역 등록** | 앞으로 누가 uuid 칸을 `UUID` 필드로 받아도 값이 든다 — "조용히 null" 함정이 사라진다. `ConfigurationCustomizer` 한 곳에서 `setObject`/`getObject(…, UUID.class)` 로 몇 줄 | 지금 동작은 바뀌지 않는다. 쓰기는 이미 `setObject` 로 같고, `::text`→String 읽기는 필드 형(String)으로 처리기를 고르므로 그대로다. 위험은 거의 없다 — 이 시험의 "조용히 null" 기대를 "값이 든다" 로 바꾸면 된다 |
| (나) 가드 — `resultType` 클래스에 `UUID` 필드가 있으면 실패 | 함정을 미리 막는다 | 매퍼 XML 의 `resultType`·`resultMap` 을 클래스까지 따라가야 해서 (가)보다 무겁다. (가)를 하면 필요 없다 |
| (다) 그대로 + 규칙 문서화("uuid 는 `::text` 로") | 코드 변경 없음 | 사람이 기억해야 한다. 오류가 안 나서 시험이 없으면 못 찾는다 |

**제안: (가)** — 작고, 지금 동작을 바꾸지 않고, 함정만 없앤다. 맡을 곳은 시스템관리/플랫폼(상위 결정). 등록 뒤에는 `UuidMappingQaTest.읽기_UUID_필드는_조용히_null_text_는_된다` 가 실패하므로 기대를 뒤집는다(그 시험이 등록의 회귀 시험이 된다).

## 4. 시험 — `UuidMappingQaTest` 5건(통과)

| 시험 | 본 것 |
|---|---|
| `설정에_UUID_TypeHandler_가_없다` | 앱 `Configuration` 의 처리기 목록 |
| `읽기_UUID_필드는_조용히_null_text_는_된다` | `'…'::uuid as id` → `Row.id : UUID` null, `::text` → String 값 |
| `읽기_map_은_UUID_객체` | `resultType="map"` → `java.util.UUID` |
| `쓰기_맵_파라미터_UUID_는_uuid_로_간다` | `@Param` 맵 → `pg_typeof = uuid`, uuid 칸과 같음 |
| `쓰기_객체_getter_UUID_도_uuid_로_간다` | `#{p.u}`(record getter) → 오류 없음, `uuid` |
