# QA 2026-10-29 — 소프트 출하 예약 · 가용 정의 통일 · 정위치 점검 · PMS 계획 공수 · QMS 초품·종품 · MES CloseNotice

> 독립 QA 세션(docs/29 8장), 상위 지시(사용자 2026-10-29). 기준 main `5ee4d779`. 계획은 `docs/qa/2026-10-29_round-plan.md`. 제품 코드는 고치지 않았다.
> 표시 **[코드]**(코드를 읽어 확인) · **[재현]**(시험으로 재현) · **[관찰]**(지금 동작을 시험으로 고정, 판단은 정책 질의).
> 시험: `qa.PmsPlanHoursQaTest` 1 · `qa.AvailPathsQaTest` 1(관찰). 결함 W-1 의 재현 시험은 지금 main 에서 실패하므로 부록 A(고칠 PR 에 켠 채로).

## 0. 요약 — 분류

| ID | 내용 | 분류 | 담당 |
|---|---|---|---|
| **W-1** | 같은 수주 행으로 출하 지시 두 개를 **동시에** 만들면 둘 다 남은 수량 확인을 지나 저장 — 수주 행 20 에 열린 지시 40, 소프트 예약도 40 [재현] | **고칠 것**(조용히 틀림: 예약·남은 수량 숫자. 최종은 확정 때 영업 게이트가 막음) | WMS |
| A-1 | 차감 미리보기(그리고 그것을 쓰는 출하 피킹 제안)는 **LOT 보류** 를 보지 않음 — 보류 8 + 정상 5 에서 APS 가용 5, 미리보기는 13 배정·모자람 0 [관찰] | 정책 질의(가용 조각에 보류 LOT 제외를 넣을지) | WMS |
| M-1 | `CloseNotice` 구현 빈이 main 에 없다 — 공정 완료 경고는 지금 늘 빈 목록(QMS 종품 상태 `OffInspService.lastOff` 는 있으나 연결 안 됨) [코드] | 확인 질의 → 상위 답: 예정(QMS 종품 2단계 — `QMS_LAST_PENDING`·`QMS_LAST_NG`) | QMS |
| Q-1 | 초품·종품 짝은 품목·공정의 최근 200건을 읽고 지시로 거름 — 같은 품목·공정에 다른 지시 검사가 많으면 옛 지시의 짝이 "없음" 으로 보이고 "잘림" 표시가 없다 [코드] | 정책 질의(낮음, 27장에 적힌 한계 — 표시만이라도) | QMS |
| L-1 | 정위치 점검 머리는 공장 날짜 하나지만, 자정을 넘겨 PDA 가 어제 머리(OPEN)로 계속 스캔·완료할 수 있고 정확도는 어제 몫으로 셈 [코드] | 정책 질의(낮음) | WMS |
| — | PMS 계획 공수 동시 수정, 정위치 점검 완료 중 이동, 예약 숫자 정의, 가용 칸 규칙 | 이상 없음 | — |

## 1. "조용히 틀림" 후보 4건

### 1.1 PMS 계획 공수 — 자식 둘 동시 수정 [재현] 이상 없음
- 작업 추가·수정·이동·삭제는 모두 프로젝트 머리 `FOR UPDATE`(`TaskService.openForEdit` → `lockProject`) 뒤 트리 전체를 다시 계산(`WbsTree.rollupInPlace`)한다 [코드]. 템플릿 생성·게이트·일정 변경도 `tasks.rollup` 을 부른다.
- `PmsPlanHoursQaTest`: 시험이 프로젝트 머리를 쥐고 자식 둘(4h·3h → 10h·20h) 수정이 모두 기다리는 것을 관찰한 뒤 놓음 → 둘 다 200, 요약 30, 프로젝트 공수 30, 포트폴리오 정의(잎·취소 아님 합) 30.
- 요약 상태는 파생값(모든 자식 취소일 때만 CANCELLED)이라 포트폴리오의 "잎 합" 과 롤업 합이 늘 같다 [코드].

### 1.2 정위치 점검 — 완료 중 이동 [코드] 이상 없음
- 스캔·완료 모두 점검 머리 `FOR UPDATE` → 완료 중 스캔은 기다린 뒤 DONE 을 보고 409. MISSING 은 한 문장(`missingOf`)의 스냅숏에서 만들고 집계(`done`)는 점검 줄만 센다 — 그 사이 재고 이동이 커밋돼도 MISSING 과 집계가 어긋날 경로가 없다.
- 보정은 줄 `FOR UPDATE` → `InventoryService.post`(수량 부족 409) — 판정 뒤 재고가 옮겨지면 409(모듈 시험이 덮음).
- **L-1** [코드]: `start` 는 공장 날짜(`clock.zone(plantCd)`)로 머리를 고르지만 `scan`·`done` 은 점검 id 로만 — 자정을 넘긴 PDA 세션은 어제 머리에 계속 쓰고, 완료하면 "어제 점검" 으로 정확도에 들어간다(그 시각의 장부로). 날짜가 바뀌면 이어 쓰기를 막을지(또는 오늘 머리로 넘길지) 정책.
- 점검 칸이 창고(WH)면 판정·MISSING 은 그 노드에 **직접** 있는 재고만(하위 LOC 제외) — 설계(2.3 "LOC/WH") 그대로로 보이나, 창고 단위 점검을 하위까지로 기대하면 다르다(낮음, 기록만).

### 1.3 소프트 출하 예약 [코드]·[재현]
- 예약 = 열린 지시(DRAFT·PICKING) 줄의 미피킹(PICKED·SHIPPED 합을 뺌) — SQL 조각(`AvailSql.openShipReserve`)과 지시 화면 경고(`ShipService.reserve` 의 자기 몫)가 같은 피킹 정의라 자기 몫이 정확히 빠진다. 단위는 품목 기본 단위(수주·출하 줄에 단위 칸 없음). 피킹한 물건은 출하 대기 칸 = 가용 아님이라 이중으로 세지 않는다 [코드].
- 보류 LOT: 예약 카드의 가용·APS 가용이 같은 정의(보류 제외) [코드].
- 모듈 시험(`ShipTest.소프트_예약은_…`)이 두 지시 같은 품목 경고 숫자·피킹·피킹 취소·지시 취소 즉시 반영·홈 카드·다른 공장·A사로 B사를 덮는다.
- **W-1 [재현]**: 지시 만들기는 남은 수량(수주 − 출하 − 다른 열린 지시)을 **잠금 없이** 읽고 채번 → 저장한다(`ShipService.create`). 같은 수주 행으로 두 지시를 동시에 보내면 둘 다 확인을 지난다. 재현: 시험이 오늘 채번 행(`sys_numbering_seq`, WMS_SHIP)을 쥐고 두 요청이 모두 채번에서 기다리는 것을 관찰한 뒤 놓음 → `200, 200`, 수주 행 20 에 열린 지시 40, 예약 45(앞 지시 5 + 40). 차례로 보내면 둘째는 400 SHIP_OVER_OPEN.
  - 영향(조용히 틀림): 예약·경고·홈 카드 "예약 초과" 가 부풀고, 지시 만들기 목록의 남은 수량이 음수. 두 지시 모두 피킹할 수 있어 물건이 출하 대기 칸으로 더 옮겨지고, 확정 때 영업 게이트(초과율)가 둘째를 막아야 드러난다.
  - 안: 남은 수량 확인 전에 수주 행을 줄 세움 — 영업 계약으로 행 잠금(`FOR UPDATE`, 행 id 오름차순) 또는 수주 행 id 별 advisory 잠금. 재현 시험은 부록 A(고칠 PR 에 켠 채로).

### 1.4 가용 정의 통일 — 다섯 경로 [코드]·[관찰]
- 다섯 경로(출하 피킹·생산 투입·MRO·차감 미리보기·APS)가 같은 조각 `AvailSql.available`/`availLoc` — 칸 규칙(STAGING·QC 칸 자신, SCRAP 칸·조상)은 같다 [코드]. 모듈 시험이 QC 칸(출하 다른 칸·제안·피킹), STAGING(투입), F-a(`AvailableCellQaTest` — WMS 가 기대만 뒤집음, 건너뛰기 없음) 를 덮는다.
- 차감 미리보기의 LOCATION 건너뜀 = `availLoc` 거짓인 칸 → 상태와 무관하게 제외 — 다른 경로에서 빠지는 칸과 같다 [코드].
- **A-1 [관찰]** `AvailPathsQaTest`: 조각 밖으로 남긴 **LOT 보류** — APS 가용·예약 카드·MRO 는 보류 LOT 을 빼고, 생산 투입은 계보 트리거가 막는다. 차감 미리보기는 상태(HOLD·BLOCKED)만 보고 LOT 보류는 보지 않아 보류 8 + 정상 5 에서 13 을 배정·모자람 0(APS 가용은 5). 출하 피킹 제안(`ShipService.suggest`)이 이 미리보기를 써서 보류 LOT 을 제안한다 [코드]. 실제 피킹을 보류 LOT 에서 하면 막히는지는 확인하지 않았다 [추정 — 이동 규칙의 보류 검사]. 안: `AvailSql` 에 "보류 아닌 LOT" 조각을 더해 다섯 경로가 함께 쓰기.

## 2. QMS 초품·종품 나란히 (#659, V202)
- 짝 = 같은 품목·공정의 FIRST_OFF·LAST_OFF(무효 제외)를 최근순 200건 읽고 같은 지시로 거름(`OffInspService.pair`), 상태 = 가장 최근 판정의 결과 [코드].
- 정렬 `occurred_at desc, insp_id desc` 로 같은 시각도 흔들리지 않음, 부분 인덱스 `ix_qms_insp_off` 가 그 정렬·조건과 같은 모양 [코드].
- **Q-1**: 200건 상한을 지시 거름 **전에** 적용 — 같은 품목·공정의 다른 지시 검사가 200건을 넘으면 옛 지시의 짝이 "NONE" 으로 보이고 잘렸다는 표시가 없다. 27장에 한계로 적혀 있음. 안: 지시의 LOT 들로 SQL 에서 거른 뒤 상한(또는 잘림 표시).

## 3. MES CloseNotice (#661)
- 수집기 `CloseNotices` 는 빈마다 세이브포인트(NESTED)로 부르고 예외는 로그만(막지 않음), 미리보기와 판정이 같은 문맥(산출 LOT·공정)을 넘긴다 [코드].
- **M-1**: main 에 `CloseNotice` 구현 빈이 없다(`grep CloseNotice` — module/production 밖 0건). QMS 의 종품 상태(`OffInspService.lastOff`)는 있으나 연결되지 않아 공정 완료 경고는 지금 늘 빈 목록. 다음 PR 예정인지 확인 질의 → 상위 답(2026-10-29): QMS 종품 2단계 PR 에서 구현 빈(QMS_LAST_PENDING·QMS_LAST_NG) — 그때 미리보기·판정 같은 안내인지 다시 확인.

## 4. 이번 회차 실행
| 시험 | 결과 |
|---|---|
| `PmsPlanHoursQaTest` | 통과 |
| `AvailPathsQaTest`(관찰 A-1) | 통과(지금 동작 고정) |
| W-1 재현(부록 A) | **실패** — `200,200`, 열린 지시 40 > 20, 예약 45 (이 PR 에 넣지 않음 — WMS 수정 PR 에 켠 채로) |
| 전체 `./gradlew check`(이 브랜치 `1a4bc2aa`) | 통과 — 2,740건, 실패 0, 건너뜀 15 |

## 부록 A — W-1 재현 시험(`qa.ShipCreateRaceQaTest`, WMS 수정 PR 에 켠 채로)

```java
package io.smarthappy.qa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.module.wms.link.StockQuery;
import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * QA — 출하 지시 만들기 동시(docs/53 (가) 소프트 예약 확인 R3). 모듈 시험(ShipTest)이 덮은 것(다른 수주 행 두 지시 → 둘 다 저장·경고 숫자, 피킹·
 * 피킹 취소·지시 취소 즉시 반영, 홈 카드, A사로 B사)은 다시 하지 않는다. 여기서는 **같은 수주 행**으로 두 지시를 동시에 — 남은 수량 확인(수주 − 출하 −
 * 다른 열린 지시)이 잠금 없이 읽기라, 둘 다 확인을 지나 채번에서 기다리게 한 뒤 놓는다. 분석 docs/qa/2026-10-29_pms-wms-qms-mes.md
 */
@AutoConfigureMockMvc
class ShipCreateRaceQaTest extends PostgresTestBase {

    static final String PW = "Qa-ship-race-31";
    static final String S = "/api/wms/ships";
    static final ExecutorService POOL = Executors.newFixedThreadPool(2);

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    @Autowired
    TenantRunner runner;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    StockQuery stockQuery;

    String comp;
    RequestPostProcessor admin;
    long stg;
    long fg;

    @AfterAll
    static void stop() {
        POOL.shutdownNow();
    }

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("QW");
        long u = accounts.newUser(comp, "qw1", PW);
        accounts.grant(comp, u, "SALES", "sal-order", "SEARCH", "SAVE");
        accounts.grant(comp, u, "WMS", "wms-ship", "SEARCH", "SAVE");
        admin = accounts.as(comp, u, "qw1");
        accounts.addPlant(comp, "P1", "1공장");
        accounts.exec(comp, "insert into mst_cust (comp_cd, cust_cd, cust_nm, cust_type, created_by, updated_by) values (?, 'C-001', 'C-001 상사', 'SALES', 't', 't')",
                comp);
        fg = accounts.query(comp, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, lot_ctrl_yn, created_by, updated_by)
                values (?, 'FG-100', 'FG 품목', 'FG', 'MAKE', 'EA', 'N', 't', 't') returning item_id""", Long.class, comp);
        long wh = node(null, "WH", "FG-WH");
        long a1 = node(wh, "LOC", "A-01");
        stg = node(wh, "LOC", "STG-01");
        accounts.exec(comp, "insert into wms_loc_attr (node_id, comp_cd, loc_usage, created_by, updated_by) values (?, ?, 'STAGING', 't', 't')", stg, comp);
        accounts.exec(comp, """
                insert into inv_trx (comp_cd, plant_cd, trx_type, item_id, node_id, stock_status, qty, unit_cd, src_type, occurred_at, entry_method, recorded_by)
                values (?, 'P1', 'ADJ_IN', ?, ?, 'AVAILABLE', 100, 'EA', 'MANUAL', now(), 'KEY', 't')""", comp, fg, a1);
    }

    long node(Long parent, String type, String cd) {
        return accounts.query(comp, """
                insert into mst_equip_hier (comp_cd, plant_cd, parent_id, node_type, node_cd, node_nm, created_by, updated_by)
                values (?, 'P1', ?, ?, ?, ?, 't', 't') returning node_id""", Long.class, comp, parent, type, cd, cd);
    }

    long orderLine(String qty) throws Exception {
        MvcResult m = mvc.perform(post("/api/sales/orders").with(csrf()).with(admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"custCd\":\"C-001\",\"lines\":[{\"itemCd\":\"FG-100\",\"qty\":\"" + qty + "\",\"dueDate\":\"2026-11-30\"}]}"))
                .andExpect(status().isOk()).andReturn();
        long order = ((Number) JsonPath.read(m.getResponse().getContentAsString(), "$.data")).longValue();
        return accounts.query(comp, "select line_id from sal_order_line where order_id = ? and line_no = 10", Long.class, order);
    }

    MvcResult create(long orderLine, int qty) throws Exception {
        return mvc.perform(post(S).with(csrf()).with(admin).contentType(MediaType.APPLICATION_JSON).content("{\"plantCd\":\"P1\",\"custCd\":\"C-001\","
                + "\"stageNodeId\":" + stg + ",\"planDate\":\"2026-11-20\",\"lines\":[{\"orderLineId\":" + orderLine + ",\"qty\":" + qty + "}]}")).andReturn();
    }

    int lockWaiters() {
        return jdbc.queryForObject("select count(*) from pg_stat_activity where wait_event_type = 'Lock' and datname = current_database()",
                Integer.class);
    }

    /**
     * 결함 후보 W-1: 같은 수주 행(20)으로 지시 두 개(각 20)를 동시에 — 둘 다 남은 수량 20 을 보고 저장돼 수주 행에 열린 지시 40(남은 수량 −20),
     * 예약도 40. 차례로 보내면 둘째는 400 SHIP_OVER_OPEN. 기대(고친 뒤): 하나만 저장, 다른 하나 SHIP_OVER_OPEN
     */
    @Test
    void 같은_수주_행으로_동시에_만든_두_지시는_남은_수량을_넘지_않는다() throws Exception {
        create(orderLine("5"), 5);                                  // 오늘 채번 행을 만들어 둔다(잠글 대상)
        long line = orderLine("20");
        try (Connection hold = DriverManager.getConnection(jdbcUrl(), OWNER_USER, testPassword()); Statement st = hold.createStatement()) {
            hold.setAutoCommit(false);
            st.execute("select 1 from sys_numbering_seq where comp_cd = '" + comp + "' and rule_cd = 'WMS_SHIP' for update");
            int before = lockWaiters();
            Future<MvcResult> f1 = POOL.submit(() -> create(line, 20));
            Future<MvcResult> f2 = POOL.submit(() -> create(line, 20));
            for (int i = 0; i < 200 && lockWaiters() - before < 2; i++) {
                Thread.sleep(50);
            }
            int waiting = lockWaiters() - before;
            hold.commit();
            int s1 = f1.get(30, TimeUnit.SECONDS).getResponse().getStatus();
            int s2 = f2.get(30, TimeUnit.SECONDS).getResponse().getStatus();
            BigDecimal inShips = accounts.query(comp, "select coalesce(sum(qty), 0) from wms_ship_line where order_line_id = ?", BigDecimal.class, line);
            BigDecimal reserved = runner.call(comp, () -> stockQuery.reservedByShip("P1", List.of(fg))).get(fg);
            System.out.println("[QW] 기다린 요청=" + waiting + " 응답=" + s1 + "," + s2 + " 수주 행 20 의 열린 지시 합=" + inShips + " 예약=" + reserved);
            assertThat(waiting).as("두 만들기 모두 남은 수량 확인을 지나 채번에서 기다린다(모두 관찰한 뒤 놓음)").isGreaterThanOrEqualTo(2);
            assertThat(inShips).as("수주 행 남은 수량(20)을 넘는 열린 지시가 없다").isLessThanOrEqualTo(new BigDecimal("20"));
        }
    }
}
```
