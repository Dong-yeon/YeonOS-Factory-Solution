package io.smarthappy.module.wms.alloc;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * 출고 후보 미리보기 (P4 W4, docs/30 4.4) — 현재고 + 원장(선입 기준일) → 차감 전략 순수 함수.
 * 전략 결정 순서(화면 → 품목 클래스 속성 → 공장 정책), 유효기한(속성 SHELF_LIFE_DAYS), 잔량 0 뒤 선입일 다시 시작, 이상 입력, A사로 B사, 권한.
 */
@AutoConfigureMockMvc
class AllocPreviewTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    String comp;
    RequestPostProcessor admin;
    long wh;
    long a1;
    long a2;
    long qc;
    long item;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("AL");
        long u = accounts.newUser(comp, "admin1", PW);
        accounts.grant(comp, u, "WMS", "wms-stock", "SEARCH");
        admin = accounts.as(comp, u, "admin1");
        accounts.addPlant(comp, "P1", "1공장");
        accounts.addPlant(comp, "P2", "2공장");
        wh = node(comp, "P1", null, "WH", "WH");
        a1 = node(comp, "P1", wh, "LOC", "A-01");
        a2 = node(comp, "P1", wh, "LOC", "A-02");
        qc = node(comp, "P1", wh, "LOC", "QC-01");
        pickSeq(a1, 20);
        pickSeq(a2, 10);
        item = item(comp, "SUB-1");
        // A-01: 5일 전 10 / A-02: 2일 전 5 / QC-01: 10일 전 7(검사 대기)
        in(a1, "AVAILABLE", 10, 5);
        in(a2, "AVAILABLE", 5, 2);
        in(qc, "QC", 7, 10);
    }

    long node(String c, String plant, Long parent, String type, String cd) {
        return accounts.query(c, """
                insert into mst_equip_hier (comp_cd, plant_cd, parent_id, node_type, node_cd, node_nm, created_by, updated_by)
                values (?, ?, ?, ?, ?, ?, 't', 't') returning node_id""", Long.class, c, plant, parent, type, cd, cd);
    }

    long item(String c, String cd) {
        return accounts.query(c, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, lot_ctrl_yn, created_by, updated_by)
                values (?, ?, ?, 'SUB', 'BUY', 'EA', 'N', 't', 't') returning item_id""", Long.class, c, cd, cd + " 이름");
    }

    void pickSeq(long node, int seq) {
        accounts.exec(comp, "insert into wms_loc_attr (node_id, comp_cd, pick_seq, created_by, updated_by) values (?, ?, ?, 't', 't')",
                node, comp, seq);
    }

    /** 원장 직접 입력(며칠 전) — 정상 경로와 같은 트리거를 지난다 */
    void ledger(long node, String status, String type, int qty, int daysAgo) {
        accounts.exec(comp, """
                insert into inv_trx (comp_cd, plant_cd, trx_type, item_id, node_id, stock_status, qty, unit_cd, src_type, occurred_at,
                                     entry_method, recorded_by)
                values (?, 'P1', ?, ?, ?, ?, ?, 'EA', 'MANUAL', now() - make_interval(days => ?), 'KEY', 't')""",
                comp, type, item, node, status, qty, daysAgo);
    }

    void in(long node, String status, int qty, int daysAgo) {
        ledger(node, status, "ADJ_IN", qty, daysAgo);
    }

    String daysAgo(int d) {
        return accounts.query(comp, "select (" + TestAccounts.COMP_TODAY + " - ?)::text", String.class, d);
    }

    /** 품목 클래스 + 속성 정의(ALLOC_STRATEGY·SHELF_LIFE_DAYS) + 품목 값 */
    void itemAttrs(String json) {
        long cls = accounts.query(comp, """
                insert into mst_item_class (comp_cd, class_cd, class_nm, created_by, updated_by) values (?, 'ZTC', 'ZT 클래스', 't', 't')
                on conflict (comp_cd, class_cd) do update set class_nm = excluded.class_nm returning class_id""", Long.class, comp);
        accounts.exec(comp, """
                insert into mst_class_prop (comp_cd, class_type, class_id, prop_cd, prop_nm, data_type, options, created_by, updated_by)
                values (?, 'ITEM', ?, 'ALLOC_STRATEGY', '차감 전략', 'LIST', '["FIFO","FEFO","LOC_SEQ"]', 't', 't')
                on conflict do nothing""", comp, cls);
        accounts.exec(comp, """
                insert into mst_class_prop (comp_cd, class_type, class_id, prop_cd, prop_nm, data_type, scale, created_by, updated_by)
                values (?, 'ITEM', ?, 'SHELF_LIFE_DAYS', '유효기간(일)', 'NUM', 0, 't', 't') on conflict do nothing""", comp, cls);
        accounts.exec(comp, "update mst_item set item_class_id = ?, attrs = ?::jsonb where item_id = ?", cls, json, item);
    }

    ResultActions preview(String body, RequestPostProcessor who) throws Exception {
        return mvc.perform(post("/api/wms/stock/alloc-preview").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body).with(who));
    }

    ResultActions preview(String body) throws Exception {
        return preview(body, admin);
    }

    // =====================================================================
    // 정상
    // =====================================================================

    @Test
    void 정책_기본_FIFO_는_오래된_입고부터_QC_는_제외() throws Exception {
        preview("{\"plantCd\":\"P1\",\"itemCd\":\"sub-1\",\"qty\":\"12\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.strategy").value("FIFO")).andExpect(jsonPath("$.data.strategySource").value("POLICY"))
                .andExpect(jsonPath("$.data.picks[0].path").value("P1/WH/A-01")).andExpect(jsonPath("$.data.picks[0].takeQty").value("10"))
                .andExpect(jsonPath("$.data.picks[0].fifoDate").value(daysAgo(5)))
                .andExpect(jsonPath("$.data.picks[1].path").value("P1/WH/A-02")).andExpect(jsonPath("$.data.picks[1].takeQty").value("2"))
                .andExpect(jsonPath("$.data.allocated").value("12")).andExpect(jsonPath("$.data.shortage").value("0"))
                .andExpect(jsonPath("$.data.skipped[0].path").value("P1/WH/QC-01"))
                .andExpect(jsonPath("$.data.skipped[0].reason").value("STATUS"));
        // QC 포함이면 가장 오래된 QC 부터
        preview("{\"plantCd\":\"P1\",\"itemCd\":\"SUB-1\",\"qty\":\"12\",\"availableOnly\":false}")
                .andExpect(jsonPath("$.data.picks[*].path").value(containsInAnyOrder("P1/WH/QC-01", "P1/WH/A-01")))
                .andExpect(jsonPath("$.data.picks[0].takeQty").value("7"));
    }

    /** docs/30 12.15.6 — 품질 상태 연동(WMS_QUALITY_LINK = Y) 공장은 "합격분만" 끄기(QC 포함)를 서버가 400. 꺼진 공장(P2)·기본값(합격분만)은 그대로 */
    @Test
    void 품질_연동_공장은_합격분만_끄기를_서버가_거부() throws Exception {
        accounts.exec(comp, "insert into sys_policy (comp_cd, plant_cd, policy_key, policy_value, created_by, updated_by) "
                + "values (?, 'P1', 'WMS_QUALITY_LINK', 'Y', 't', 't')", comp);
        preview("{\"plantCd\":\"P1\",\"itemCd\":\"SUB-1\",\"qty\":\"12\",\"availableOnly\":false}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("availableOnly")).andExpect(jsonPath("$.errors[0].code").value("QUALITY_LINK"));
        preview("{\"plantCd\":\"P1\",\"itemCd\":\"SUB-1\",\"qty\":\"12\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.availableOnly").value(true));
        preview("{\"plantCd\":\"P2\",\"itemCd\":\"SUB-1\",\"qty\":\"1\",\"availableOnly\":false}").andExpect(status().isOk());
    }

    @Test
    void 화면_선택_다음_품목_속성_다음_공장_정책() throws Exception {
        accounts.exec(comp, "insert into sys_policy (comp_cd, plant_cd, policy_key, policy_value, created_by, updated_by) "
                + "values (?, 'P1', 'WMS_ALLOC_STRATEGY', 'LOC_SEQ', 't', 't')", comp);
        preview("{\"plantCd\":\"P1\",\"itemCd\":\"SUB-1\",\"qty\":\"6\"}")
                .andExpect(jsonPath("$.data.strategy").value("LOC_SEQ")).andExpect(jsonPath("$.data.strategySource").value("POLICY"))
                .andExpect(jsonPath("$.data.picks[0].path").value("P1/WH/A-02"));                  // 피킹 순서 10 먼저
        itemAttrs("{\"ALLOC_STRATEGY\":\"FIFO\"}");
        preview("{\"plantCd\":\"P1\",\"itemCd\":\"SUB-1\",\"qty\":\"6\"}")
                .andExpect(jsonPath("$.data.strategySource").value("ITEM")).andExpect(jsonPath("$.data.picks[0].path").value("P1/WH/A-01"));
        preview("{\"plantCd\":\"P1\",\"itemCd\":\"SUB-1\",\"qty\":\"6\",\"strategy\":\"LOC_SEQ\"}")
                .andExpect(jsonPath("$.data.strategySource").value("REQUEST")).andExpect(jsonPath("$.data.picks[0].path").value("P1/WH/A-02"));
    }

    @Test
    void FEFO_유효기간_속성으로_기한_계산_지난_것은_제외_오늘은_사용() throws Exception {
        itemAttrs("{\"SHELF_LIFE_DAYS\":3}");
        // A-01 = 5일 전 + 3 = 2일 전 만료(제외), A-02 = 2일 전 + 3 = 내일
        preview("{\"plantCd\":\"P1\",\"itemCd\":\"SUB-1\",\"qty\":\"8\",\"strategy\":\"FEFO\"}")
                .andExpect(jsonPath("$.data.shelfLifeDays").value(3))
                .andExpect(jsonPath("$.data.picks.length()").value(1)).andExpect(jsonPath("$.data.picks[0].path").value("P1/WH/A-02"))
                .andExpect(jsonPath("$.data.picks[0].expiryDate").value(daysAgo(-1)))
                .andExpect(jsonPath("$.data.shortage").value("3"))
                .andExpect(jsonPath("$.data.skipped[*].reason").value(containsInAnyOrder("EXPIRED", "STATUS")));
        // 기한 당일까지는 쓸 수 있다: A-01 선입일(5일 전) + 5 = 오늘. 오늘 1 을 더 넣어도 잔량이 0 이 된 적이 없으니 선입일은 그대로
        in(a1, "AVAILABLE", 1, 0);
        accounts.exec(comp, "update mst_item set attrs = '{\"SHELF_LIFE_DAYS\": 5}' where item_id = ?", item);
        preview("{\"plantCd\":\"P1\",\"itemCd\":\"SUB-1\",\"qty\":\"1\",\"strategy\":\"FEFO\"}")
                .andExpect(jsonPath("$.data.picks[0].path").value("P1/WH/A-01"))
                .andExpect(jsonPath("$.data.picks[0].expiryDate").value(daysAgo(0)));
        // 기한 검사 끄기
        accounts.exec(comp, "update mst_item set attrs = '{\"SHELF_LIFE_DAYS\": 1}' where item_id = ?", item);
        preview("{\"plantCd\":\"P1\",\"itemCd\":\"SUB-1\",\"qty\":\"16\",\"excludeExpired\":false}")
                .andExpect(jsonPath("$.data.shortage").value("0")).andExpect(jsonPath("$.data.expiredBefore").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void 잔량이_0_이_된_뒤에는_선입일이_다시_시작() throws Exception {
        ledger(a1, "AVAILABLE", "ADJ_OUT", -10, 4);   // A-01 비움(4일 전)
        in(a1, "AVAILABLE", 4, 1);                    // 어제 다시 4
        preview("{\"plantCd\":\"P1\",\"itemCd\":\"SUB-1\",\"qty\":\"9\"}")
                .andExpect(jsonPath("$.data.picks[0].path").value("P1/WH/A-02")).andExpect(jsonPath("$.data.picks[0].fifoDate").value(daysAgo(2)))
                .andExpect(jsonPath("$.data.picks[1].path").value("P1/WH/A-01")).andExpect(jsonPath("$.data.picks[1].fifoDate").value(daysAgo(1)))
                .andExpect(jsonPath("$.data.picks[1].takeQty").value("4"));
    }

    @Test
    void 범위_위치_하위_포함과_부족() throws Exception {
        preview("{\"plantCd\":\"P1\",\"itemCd\":\"SUB-1\",\"qty\":\"100\",\"nodeId\":" + a2 + "}")
                .andExpect(jsonPath("$.data.picks.length()").value(1)).andExpect(jsonPath("$.data.allocated").value("5"))
                .andExpect(jsonPath("$.data.shortage").value("95"));
        preview("{\"plantCd\":\"P1\",\"itemCd\":\"SUB-1\",\"qty\":\"100.5\",\"nodeId\":" + wh + "}")
                .andExpect(jsonPath("$.data.allocated").value("15")).andExpect(jsonPath("$.data.shortage").value("85.5"));
    }

    // =====================================================================
    // 이상 사용
    // =====================================================================

    @Test
    void B_입력값() throws Exception {
        preview("{}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field").value(containsInAnyOrder("plantCd", "qty", "itemCd")));
        for (String q : new String[] {"0", "-1", "abc", "1.1234567", "1000000000000", "1e3x"}) {
            preview("{\"plantCd\":\"P1\",\"itemCd\":\"SUB-1\",\"qty\":\"" + q + "\"}").andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value("qty"));
        }
        preview("{\"plantCd\":\"P1\",\"itemCd\":\"SUB-1\",\"qty\":\"1\",\"strategy\":\"LIFO\"}")
                .andExpect(jsonPath("$.errors[0].field").value("strategy"));
        preview("{\"plantCd\":\"P9\",\"itemCd\":\"SUB-1\",\"qty\":\"1\"}").andExpect(jsonPath("$.errors[0].field").value("plantCd"));
        preview("{\"plantCd\":\"P1\",\"itemCd\":\"NOPE\",\"qty\":\"1\"}").andExpect(jsonPath("$.errors[0].code").value("NO_SUCH_CODE"));
        long p2 = node(comp, "P2", null, "WH", "P2-WH");
        long area = node(comp, "P1", null, "AREA", "BODY");
        preview("{\"plantCd\":\"P1\",\"itemCd\":\"SUB-1\",\"qty\":\"1\",\"nodeId\":" + p2 + "}")
                .andExpect(jsonPath("$.errors[0].field").value("nodeId"));
        preview("{\"plantCd\":\"P1\",\"itemCd\":\"SUB-1\",\"qty\":\"1\",\"nodeId\":" + area + "}")
                .andExpect(jsonPath("$.errors[0].field").value("nodeId"));
        // 품목 속성 값이 엉터리면(속성을 LIST 가 아닌 TEXT 로 정의한 회사) 무시하고 정책으로
        long cls = accounts.query(comp, "insert into mst_item_class (comp_cd, class_cd, class_nm, created_by, updated_by) "
                + "values (?, 'ZTT', 'ZT 글자', 't', 't') returning class_id", Long.class, comp);
        accounts.exec(comp, """
                insert into mst_class_prop (comp_cd, class_type, class_id, prop_cd, prop_nm, data_type, created_by, updated_by)
                values (?, 'ITEM', ?, 'ALLOC_STRATEGY', '차감 전략', 'TEXT', 't', 't')""", comp, cls);
        accounts.exec(comp, "update mst_item set item_class_id = ?, attrs = '{\"ALLOC_STRATEGY\":\"LIFO\"}' where item_id = ?", cls, item);
        preview("{\"plantCd\":\"P1\",\"itemCd\":\"SUB-1\",\"qty\":\"1\"}").andExpect(jsonPath("$.data.strategySource").value("POLICY"));
    }

    /**
     * docs/53 F2·F-a(2026-10-29) — 가용 정의 하나(AvailSql): 출하 대기(STAGING)·검사 용도(QC) 칸의 AVAILABLE 재고는 더 오래됐어도
     * 꺼내는 후보가 아니다(LOCATION). "합격분만" 을 꺼도(QC 상태 포함) 칸 규칙은 그대로
     */
    @Test
    void 출하_대기_칸과_검사_용도_칸의_AVAILABLE_재고는_LOCATION_으로_건너뛴다() throws Exception {
        long stg = node(comp, "P1", wh, "LOC", "STG-01");
        long qcArea = node(comp, "P1", wh, "LOC", "QA-01");
        accounts.exec(comp, "insert into wms_loc_attr (node_id, comp_cd, loc_usage, created_by, updated_by) values (?, ?, 'STAGING', 't', 't'), (?, ?, 'QC', 't', 't')",
                stg, comp, qcArea, comp);
        in(stg, "AVAILABLE", 4, 30);
        in(qcArea, "AVAILABLE", 3, 20);
        preview("{\"plantCd\":\"P1\",\"itemCd\":\"SUB-1\",\"qty\":\"12\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.picks[*].path").value(containsInAnyOrder("P1/WH/A-01", "P1/WH/A-02")))
                .andExpect(jsonPath("$.data.allocated").value("12"))
                .andExpect(jsonPath("$.data.skipped[?(@.path == 'P1/WH/STG-01')].reason").value(contains("LOCATION")))
                .andExpect(jsonPath("$.data.skipped[?(@.path == 'P1/WH/QA-01')].reason").value(contains("LOCATION")));
        preview("{\"plantCd\":\"P1\",\"itemCd\":\"SUB-1\",\"qty\":\"30\",\"availableOnly\":false}").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.allocated").value("22"))                              // A-01 10 + A-02 5 + QC 상태 7
                .andExpect(jsonPath("$.data.skipped[?(@.path == 'P1/WH/STG-01')].reason").value(contains("LOCATION")))
                .andExpect(jsonPath("$.data.skipped[?(@.path == 'P1/WH/QA-01')].reason").value(contains("LOCATION")));
    }

    @Test
    void A사로_B사_품목_위치_재고() throws Exception {
        String other = accounts.newComp("AB");
        accounts.addPlant(other, "P1", "B사");
        long bu = accounts.newUser(other, "badmin", PW);
        accounts.grant(other, bu, "WMS", "wms-stock", "SEARCH");
        RequestPostProcessor b = accounts.as(other, bu, "badmin");
        // B사가 A사 품목 id·코드·위치로
        preview("{\"plantCd\":\"P1\",\"itemId\":" + item + ",\"qty\":\"1\"}", b).andExpect(jsonPath("$.errors[0].code").value("NO_SUCH_CODE"));
        preview("{\"plantCd\":\"P1\",\"itemCd\":\"SUB-1\",\"qty\":\"1\"}", b).andExpect(jsonPath("$.errors[0].code").value("NO_SUCH_CODE"));
        // 같은 품번을 B사도 가지면 B사 재고(0)만
        item(other, "SUB-1");
        preview("{\"plantCd\":\"P1\",\"itemCd\":\"SUB-1\",\"qty\":\"1\"}", b).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.picks.length()").value(0)).andExpect(jsonPath("$.data.shortage").value("1"));
        preview("{\"plantCd\":\"P1\",\"itemCd\":\"SUB-1\",\"qty\":\"1\",\"nodeId\":" + a1 + "}", b)
                .andExpect(jsonPath("$.errors[0].field").value("nodeId"));
    }

    @Test
    void 권한() throws Exception {
        long v = accounts.newUser(comp, "nobody", PW);
        accounts.grant(comp, v, "ETC", "wms-trx", "SEARCH");
        preview("{\"plantCd\":\"P1\",\"itemCd\":\"SUB-1\",\"qty\":\"1\"}", accounts.as(comp, v, "nobody")).andExpect(status().isForbidden());
    }
}
