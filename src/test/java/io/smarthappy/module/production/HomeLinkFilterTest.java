package io.smarthappy.module.production;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.module.home.HomeService;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * 홈 카드 링크용 조회 칸 (docs/25 4.13, 홈 H17) — prd-wo `status=ACTIVE`, prd-lot `holdOnly=Y`, sal-order `dueFrom`~`dueTo`, 세 목록의 열린 기간.
 * 핵심 단언: **카드 숫자 = 링크 조건으로 연 목록 건수**(지시·LOT) / 납기 행 수(수주 — 카드와 같은 행 단위).
 * 이상 사용: 값 코드·날짜 형식·순서·상한, 열린 기간이 366일 상한을 넘는 오래된 행도 잡는지, A사로 B사.
 */
@AutoConfigureMockMvc
class HomeLinkFilterTest extends PostgresTestBase {

    private static final String PW = "Willow-ember-27";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    String comp;
    RequestPostProcessor user;
    LocalDate today;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("HLF");
        user = seed(comp, "u1");
        today = LocalDate.parse(accounts.query(comp, "select " + TestAccounts.COMP_TODAY + "::text", String.class));
        seedData(comp);
    }

    /** 사용자(세 화면 + 홈 조회) + 품목 FG·라우팅·거래처 */
    private RequestPostProcessor seed(String c, String loginId) {
        long u = accounts.newUser(c, loginId, PW);
        for (String prog : new String[] {WoService.PROG, LotService.PROG, "sal-order", HomeService.PROG}) {
            accounts.grant(c, u, "VIEW", prog, "SEARCH");
        }
        accounts.addPlant(c, "P1", "1공장");
        accounts.exec(c, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, created_by, updated_by)
                values (?, 'FG', 'FG 품목', 'FG', 'MAKE', 'EA', 't', 't')""", c);
        accounts.exec(c, """
                insert into mst_proc (comp_cd, proc_cd, proc_nm, proc_cat, created_by, updated_by)
                values (?, 'SPOT', '스폿', 'WELD', 't', 't')""", c);
        long rid = accounts.query(c, """
                insert into mst_routing (comp_cd, item_id, plant_cd, rev, valid_from, created_by, updated_by)
                values (?, (select item_id from mst_item where item_cd = 'FG'), 'P1', 'A0', (now() at time zone 'Asia/Seoul')::date - 900, 't', 't')
                returning routing_id""", Long.class, c);
        accounts.exec(c, """
                insert into mst_routing_step (comp_cd, routing_id, op_seq, proc_id, created_by, updated_by)
                values (?, ?, 10, (select proc_id from mst_proc where proc_cd = 'SPOT'), 't', 't')""", c, rid);
        accounts.exec(c, "update mst_routing set status = 'RELEASED', released_at = now(), released_by = 't' where routing_id = ?", rid);
        accounts.exec(c, """
                insert into mst_cust (comp_cd, cust_cd, cust_nm, cust_type, created_by, updated_by)
                values (?, 'C1', 'C1 상사', 'SALES', 't', 't')""", c);
        return accounts.as(c, u, loginId);
    }

    /**
     * 지시: 발행(400일 전 — 366일 상한 밖)·진행 중(어제)·발행(사흘 뒤)·계획(오늘)
     * LOT: 보류·잔량(500일 전 등록)·보류·잔량 0·보류 아님
     * 수주: 400일 전 수주(열린 행 납기 그제·닷새 뒤·30일 뒤) + 오늘 수주(열린 행 내일, 취소 행 오늘)
     */
    private void seedData(String c) {
        LocalDate d = LocalDate.parse(accounts.query(c, "select " + TestAccounts.COMP_TODAY + "::text", String.class));
        wo(c, "W-OLD", d.minusDays(400), "RELEASED");
        wo(c, "W-RUN", d.minusDays(1), "IN_PROGRESS");
        wo(c, "W-NEXT", d.plusDays(3), "RELEASED");
        wo(c, "W-PLAN", d, null);

        long held = lot(c, "L-HELD", 10, d.minusDays(500));
        long empty = lot(c, "L-EMPTY", 4, d);
        long sink = lot(c, "L-SINK", 1, d);
        lot(c, "L-OK", 3, d);
        // L-EMPTY 를 다 쓴 뒤 보류(보류 LOT 은 투입의 부모가 될 수 없다)
        accounts.exec(c, """
                insert into lot_genealogy (comp_cd, parent_lot_id, child_lot_id, rel_type, qty, unit_cd, occurred_at, entry_method, recorded_by)
                values (?, ?, ?, 'CONSUME', 4, 'EA', now(), 'KEY', 't')""", c, empty, sink);
        accounts.exec(c, "insert into lot_hold (comp_cd, lot_id, source, reason, held_by) select comp_cd, lot_id, 'MANUAL', '검사', 't' from lot_master where lot_id in (?, ?)", held, empty);

        long o1 = order(c, "SO-OLD", d.minusDays(400));
        line(c, o1, 1, d.minusDays(2), null);
        line(c, o1, 2, d.plusDays(5), null);
        line(c, o1, 3, d.plusDays(30), null);
        long o2 = order(c, "SO-NEW", d);
        line(c, o2, 1, d.plusDays(1), null);
        line(c, o2, 2, d, "고객 취소");
    }

    private void wo(String c, String no, LocalDate start, String status) {
        long id = accounts.query(c, """
                insert into prd_wo (comp_cd, wo_no, plant_cd, item_id, plan_qty, unit_cd, plan_start, plan_end, routing_id, created_by, updated_by)
                select ?, ?, 'P1', i.item_id, 10, 'EA', ?, ?, r.routing_id, 't', 't'
                  from mst_item i join mst_routing r on r.item_id = i.item_id where i.item_cd = 'FG'
                returning wo_id""", Long.class, c, no, start, start);
        if (status != null) {
            accounts.exec(c, "update prd_wo set status = 'RELEASED', released_at = now(), released_by = 't' where wo_id = ?", id);
            if ("IN_PROGRESS".equals(status)) {
                accounts.exec(c, "update prd_wo set status = 'IN_PROGRESS' where wo_id = ?", id);
            }
        }
    }

    private long lot(String c, String no, int qty, LocalDate createdOn) {
        return accounts.query(c, """
                insert into lot_master (comp_cd, lot_no, item_id, origin, qty_created, unit_cd, occurred_at, created_at, created_by, updated_by)
                select ?, ?, item_id, 'RECEIVED', ?, unit_cd, now(), (cast(? as date) + time '12:00') at time zone 'Asia/Seoul', 't', 't'
                  from mst_item where item_cd = 'FG' returning lot_id""", Long.class, c, no, qty, createdOn);
    }

    private long order(String c, String no, LocalDate date) {
        return accounts.query(c, """
                insert into sal_order (comp_cd, order_no, cust_id, order_date, created_by, updated_by)
                select ?, ?, cust_id, ?, 't', 't' from mst_cust where cust_cd = 'C1' returning order_id""", Long.class, c, no, date);
    }

    private void line(String c, long orderId, int no, LocalDate due, String cancelReason) {
        // 행은 열린 상태로만 태어난다(트리거) → 취소는 뒤에
        long id = accounts.query(c, """
                insert into sal_order_line (comp_cd, order_id, line_no, item_id, qty, due_date, created_by, updated_by)
                select ?, ?, ?, item_id, 5, ?, 't', 't' from mst_item where item_cd = 'FG' returning line_id""",
                Long.class, c, orderId, no, due);
        if (cancelReason != null) {
            accounts.exec(c, "update sal_order_line set status = 'CANCELLED', cancel_reason = ? where line_id = ?", cancelReason, id);
        }
    }

    private ResultActions list(RequestPostProcessor who, String url) throws Exception {
        return mvc.perform(get(url).with(who));
    }

    private long cardCount(RequestPostProcessor who, String code) throws Exception {
        String body = mvc.perform(get("/api/home/cards/" + code).with(who)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.card.error").value(false)).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.data.card.count")).longValue();
    }

    // ---------- 정상 ----------

    @Test
    void 지시_발행_진행_중_묶음과_열린_기간은_카드_숫자와_같다() throws Exception {
        // 카드: 발행·진행 중 + 계획 시작 ≤ 오늘 → W-OLD(400일 전)·W-RUN
        long card = cardCount(user, "prd-wo-today");
        assertThat(card).isEqualTo(2);
        // 링크 조건: status=ACTIVE, from 비움, to=오늘 — 366일 상한 밖의 오래된 지시도 나온다
        list(user, "/api/production/wos?status=ACTIVE&to=" + today).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rows.length()").value((int) card))
                .andExpect(jsonPath("$.data.rows[*].woNo", Matchers.containsInAnyOrder("W-OLD", "W-RUN")));
        // 묶음만(기간 없음) → 앞날 발행 지시까지, 계획 상태는 빠진다
        list(user, "/api/production/wos?status=ACTIVE").andExpect(jsonPath("$.data.rows[*].woNo",
                Matchers.containsInAnyOrder("W-OLD", "W-RUN", "W-NEXT")));
        // 기존 조건과 함께: 닫힌 기간 + 묶음 / 단일 상태는 그대로
        list(user, "/api/production/wos?status=ACTIVE&from=" + today.minusDays(7) + "&to=" + today)
                .andExpect(jsonPath("$.data.rows[*].woNo", Matchers.contains("W-RUN")));
        list(user, "/api/production/wos?status=RELEASED&from=" + today.minusDays(7) + "&to=" + today.plusDays(7))
                .andExpect(jsonPath("$.data.rows[*].woNo", Matchers.contains("W-NEXT")));
        list(user, "/api/production/wos?status=ACTIVE&keyword=RUN").andExpect(jsonPath("$.data.rows[*].woNo", Matchers.contains("W-RUN")));
    }

    @Test
    void LOT_보류만은_잔량_있는_보류_LOT_이고_카드_숫자와_같다() throws Exception {
        long card = cardCount(user, "prd-lot-hold");
        assertThat(card).isEqualTo(1);
        // 링크 조건: holdOnly=Y, 기간 없음 — 500일 전 등록 LOT 도
        list(user, "/api/production/lots?holdOnly=Y").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rows.length()").value((int) card))
                .andExpect(jsonPath("$.data.rows[0].lotNo").value("L-HELD"));
        // 닫힌 기간(최근 30일)과 함께면 그 안에서만 — 잔량 0 보류 LOT 은 빠진다
        list(user, "/api/production/lots?holdOnly=Y&from=" + today.minusDays(29) + "&to=" + today)
                .andExpect(jsonPath("$.data.rows.length()").value(0));
        // 끄면(N·빈 값) 기존 그대로 — 기간 안 전부
        list(user, "/api/production/lots?holdOnly=N&from=" + today.minusDays(29) + "&to=" + today)
                .andExpect(jsonPath("$.data.rows[*].lotNo", Matchers.containsInAnyOrder("L-EMPTY", "L-SINK", "L-OK")));
    }

    @Test
    void 수주_납기_기간은_열린_행만_보고_납기_행_수가_카드_숫자와_같다() throws Exception {
        // 카드: 열린 행 + 납기 ≤ 오늘+7 → 그제·닷새 뒤(SO-OLD)·내일(SO-NEW) = 행 3
        long card = cardCount(user, "sal-due-soon");
        assertThat(card).isEqualTo(3);
        // 링크 조건: dueTo=오늘+7, 수주일 기간 비움 — 목록은 수주 2건, 납기 행 수 = 카드
        list(user, "/api/sales/orders?dueTo=" + today.plusDays(7)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rows[*].orderNo", Matchers.containsInAnyOrder("SO-OLD", "SO-NEW")))
                .andExpect(jsonPath("$.data.dueLineCnt").value((int) card));
        // 오늘 납기는 취소 행뿐 → 해당 없음
        list(user, "/api/sales/orders?dueFrom=" + today + "&dueTo=" + today)
                .andExpect(jsonPath("$.data.rows.length()").value(0)).andExpect(jsonPath("$.data.dueLineCnt").value(0));
        // 다른 조건과 함께: 수주일 기간이 있으면 그 안의 수주만, 행 수도 그 수주의 행만
        list(user, "/api/sales/orders?from=" + today.minusDays(7) + "&to=" + today + "&dueTo=" + today.plusDays(7))
                .andExpect(jsonPath("$.data.rows[*].orderNo", Matchers.contains("SO-NEW"))).andExpect(jsonPath("$.data.dueLineCnt").value(1));
        // 납기 조건이 없으면 행 수 없음(기존 응답 그대로 + null)
        list(user, "/api/sales/orders?from=" + today.minusDays(7) + "&to=" + today)
                .andExpect(jsonPath("$.data.dueLineCnt").doesNotExist());
    }

    // ---------- 이상 사용 ----------

    @Test
    void A_값_형식_순서_상한() throws Exception {
        list(user, "/api/production/wos?status=active").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("status")).andExpect(jsonPath("$.errors[0].code").value("CODE"));
        list(user, "/api/production/wos?from=" + today + "&to=" + today.minusDays(1)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("INVALID_RANGE"));
        list(user, "/api/production/wos?from=" + today.minusDays(400) + "&to=" + today).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("RANGE_TOO_LONG"));
        list(user, "/api/production/wos?to=2026-13-01").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("to")).andExpect(jsonPath("$.errors[0].code").value("FORMAT"));
        list(user, "/api/production/lots?holdOnly=yes").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("holdOnly")).andExpect(jsonPath("$.errors[0].code").value("CODE"));
        list(user, "/api/sales/orders?dueFrom=" + today + "&dueTo=" + today.minusDays(1)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("dueTo")).andExpect(jsonPath("$.errors[0].code").value("INVALID_RANGE"));
        list(user, "/api/sales/orders?dueFrom=x").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("dueFrom")).andExpect(jsonPath("$.errors[0].code").value("FORMAT"));
        // 권한: 조회 권한 없는 사용자는 칸이 있어도 403
        long noAuth = accounts.newUser(comp, "nobody", PW);
        list(accounts.as(comp, noAuth, "nobody"), "/api/production/lots?holdOnly=Y").andExpect(status().isForbidden());
    }

    @Test
    void B_A사로_B사_같은_조건이어도_자기_회사_것만() throws Exception {
        String other = accounts.newComp("HLX");
        RequestPostProcessor bUser = seed(other, "u1");
        seedData(other);
        // B사에 하나씩 더 — A사 숫자가 그대로인지
        accounts.exec(other, "insert into lot_hold (comp_cd, lot_id, source, reason, held_by) select comp_cd, lot_id, 'MANUAL', 'B', 't' from lot_master where lot_no = 'L-OK'");
        assertThat(cardCount(bUser, "prd-lot-hold")).isEqualTo(2);
        assertThat(cardCount(user, "prd-lot-hold")).isEqualTo(1);
        list(user, "/api/production/lots?holdOnly=Y").andExpect(jsonPath("$.data.rows.length()").value(1));
        list(bUser, "/api/production/lots?holdOnly=Y").andExpect(jsonPath("$.data.rows.length()").value(2));
        list(user, "/api/production/wos?status=ACTIVE").andExpect(jsonPath("$.data.rows.length()").value(3));
        list(user, "/api/sales/orders?dueTo=" + today.plusDays(7)).andExpect(jsonPath("$.data.rows.length()").value(2))
                .andExpect(jsonPath("$.data.dueLineCnt").value(3));
    }
}
