package io.smarthappy.module.aps;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.OffsetDateTime;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

/**
 * APS 6단계 CTP 납기 답변 (docs/38 15장, A37~A39) — 지금 지시 + 가상 지시 1개를 같은 엔진으로.
 * 읽기 전용(계획·지시·고정 전후 같음), 우선순위(기본 맨 뒤), 자재 DELAY(입고 예정 뒤로 끝), 기간 밖 HORIZON·작업장 없음 NO_NODE,
 * 라우팅 없음·입력 오류 400, 권한 403, A사로 B사.
 */
class ApsCtpTest extends ApsTestBase {

    static final String CTP = "/api/aps/ctp";

    private ResultActions ctp(RequestPostProcessor who, String body) throws Exception {
        return mvc.perform(json(post(CTP), body).with(who));
    }

    private String body(String item, String qty, String extra) {
        return "{\"plantCd\":\"P1\",\"itemCd\":\"" + item + "\",\"qty\":\"" + qty + "\"" + (extra == null ? "" : "," + extra) + "}";
    }

    private static String read(ResultActions r, String path) throws Exception {
        Object v = JsonPath.read(r.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), path);
        return v == null ? null : String.valueOf(v);
    }

    /** 계획·지시·고정 표의 지금 모습 — 전후 비교로 읽기 전용을 증명 */
    private String snapshot() {
        return accounts.query(comp, """
                select (select count(*) from aps_plan) || '|' || (select count(*) from aps_plan_wo) || '|' || (select count(*) from aps_pin)
                    || '|' || (select string_agg(wo_id || ':' || status || ':' || row_ver || ':' || plan_start || ':' || plan_end, ',' order by wo_id) from prd_wo)
                    || '|' || (select string_agg(wo_proc_id || ':' || row_ver || ':' || coalesce(equip_id::text, '-'), ',' order by wo_proc_id) from prd_wo_proc)
                    || '|' || (select count(*) from prd_wo)""", String.class);
    }

    @Test
    void 지금_지시_뒤에_넣은_끝_시각을_답하고_계획과_지시는_바꾸지_않는다() throws Exception {
        String before = snapshot();
        ResultActions r = ctp(admin, body("FG", "1", null));
        r.andExpect(jsonPath("$.data.woCnt").value(2))                       // 지시1(발행)·지시2(계획)를 함께 계산
                .andExpect(jsonPath("$.data.priority").value(10))           // 기본 = 맨 뒤
                .andExpect(jsonPath("$.data.ops[0].procCd").value("CUT"))
                .andExpect(jsonPath("$.data.ops[0].resKind").value("EQUIP"))
                .andExpect(jsonPath("$.data.ops[1].procCd").value("PLATE"))
                .andExpect(jsonPath("$.data.excCd").doesNotExist());
        OffsetDateTime end = OffsetDateTime.parse(read(r, "$.data.endAt"));
        OffsetDateTime cutStart = OffsetDateTime.parse(read(r, "$.data.ops[0].startAt"));
        assertThat(end).isAfter(cutStart);
        // 우선순위를 주면 그 값으로 줄 선다(맨 뒤보다 늦게 시작하지 않는다 — 순서 규칙 자체는 FiniteSchedulerTest)
        ResultActions p1 = ctp(admin, body("FG", "1", "\"priority\":1")).andExpect(jsonPath("$.data.priority").value(1));
        assertThat(OffsetDateTime.parse(read(p1, "$.data.ops[0].startAt"))).isBeforeOrEqualTo(cutStart);
        // 읽기 전용: 계획 버전·결과·고정·지시·공정(row_ver·설비 배정) 그대로, 답도 저장하지 않음
        assertThat(snapshot()).isEqualTo(before);
        // 희망 납기가 계산 끝보다 이르면 늦음
        ctp(admin, body("FG", "1", "\"wantDate\":\"" + LocalDate.now().minusDays(1) + "\"")).andExpect(jsonPath("$.data.lateYn").value("Y"));
    }

    @Test
    void 자재가_없으면_DELAY_로_입고_예정_뒤에_끝나고_안_덮이면_MAT() throws Exception {
        // FG3 = 절단(L1) 한 공정, BOM RM 2 EA/개 — 재고 없음
        accounts.exec(comp, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, lot_ctrl_yn, created_by, updated_by)
                values (?, 'FG3', 'FG3 품목', 'FG', 'MAKE', 'EA', 'N', 't', 't'), (?, 'RM', '원자재', 'RM', 'BUY', 'EA', 'N', 't', 't')""", comp, comp);
        long rm = accounts.query(comp, "select item_id from mst_item where item_cd = 'RM'", Long.class);
        routing("FG3", accounts.query(comp, "select node_id from mst_equip_hier where node_cd = 'L1'", Long.class), false);
        long bom = accounts.query(comp, """
                insert into mst_bom (comp_cd, item_id, rev, valid_from, base_qty, created_by, updated_by)
                select ?, item_id, 'A0', (now() at time zone 'Asia/Seoul')::date - 30, 1, 't', 't' from mst_item where item_cd = 'FG3' returning bom_id""", Long.class, comp);
        accounts.exec(comp, """
                insert into mst_bom_line (comp_cd, bom_id, line_no, item_id, qty, unit_cd, line_type, op_seq, issue_method, created_by, updated_by)
                values (?, ?, 10, ?, 2, 'EA', 'NORMAL', 10, 'MANUAL', 't', 't')""", comp, bom, rm);
        accounts.exec(comp, "update mst_bom set status = 'RELEASED', released_at = now(), released_by = 't' where bom_id = ?", bom);

        // 공급 없음 → 기간 안에 안 덮임 = MAT, 끝 시각 없음
        ctp(admin, body("FG3", "1", null)).andExpect(jsonPath("$.data.excCd").value("MAT"))
                .andExpect(jsonPath("$.data.endAt").doesNotExist())
                .andExpect(jsonPath("$.data.ops[0].excCd").value("MAT"))
                .andExpect(jsonPath("$.data.mats[0].itemCd").value("RM"))
                .andExpect(jsonPath("$.data.mats[0].needQty").value(2));

        // 발주 RM 10 — 납기 = 모레 → 그날 0시(공장 시간) 뒤에 시작
        accounts.grant(comp, userId, "PUR", "srm-po", "SEARCH", "SAVE", "APPROVE");
        accounts.exec(comp, "insert into sys_policy (comp_cd, policy_key, policy_value, created_by, updated_by) values (?, 'SRM_PO_APPROVAL', 'N', 't', 't')", comp);
        long cust = accounts.query(comp, """
                insert into mst_cust (comp_cd, cust_cd, cust_nm, cust_type, created_by, updated_by)
                values (?, 'V100', 'ZT 공급사', 'PURCHASE', 't', 't') returning cust_id""", Long.class, comp);
        accounts.exec(comp, "insert into srm_supplier (cust_id, comp_cd, currency_cd, created_by, updated_by) values (?, ?, 'KRW', 't', 't')", cust, comp);
        LocalDate due = tomorrow.plusDays(1);
        long po = id(mvc.perform(json(post("/api/srm/pos"), "{\"custCd\":\"V100\",\"plantCd\":\"P1\",\"lines\":[{\"itemCd\":\"RM\",\"qty\":\"10\","
                + "\"unitPrice\":\"1\",\"dueDate\":\"" + due + "\"}]}").with(admin)));
        for (String a : new String[] {"confirm", "issue"}) {
            int rv = JsonPath.read(mvc.perform(get("/api/srm/pos/" + po).with(admin)).andReturn().getResponse().getContentAsString(), "$.data.po.rowVer");
            mvc.perform(json(post("/api/srm/pos/" + po + "/" + a), "{\"rowVer\":" + rv + "}").with(admin)).andExpect(status().isOk());
        }
        ResultActions r = ctp(admin, body("FG3", "1", null));
        r.andExpect(jsonPath("$.data.excCd").doesNotExist())
                .andExpect(jsonPath("$.data.mats[0].shortQty").value(0))
                .andExpect(jsonPath("$.data.mats[0].allocQty").value(2));
        OffsetDateTime start = OffsetDateTime.parse(read(r, "$.data.ops[0].startAt"));
        assertThat(start.toInstant()).isAfterOrEqualTo(due.atStartOfDay(SEOUL).toInstant());
        assertThat(OffsetDateTime.parse(read(r, "$.data.mats[0].coveredAt")).toInstant()).isEqualTo(due.atStartOfDay(SEOUL).toInstant());
    }

    @Test
    void 기간_밖이면_HORIZON_작업장이_없으면_NO_NODE() throws Exception {
        // 절단 1시간/개 × 500개 — 하루 9시간 근무로 2일 안에 못 끝냄
        ctp(admin, body("FG", "500", "\"horizonDays\":2")).andExpect(jsonPath("$.data.excCd").value("HORIZON"))
                .andExpect(jsonPath("$.data.endAt").doesNotExist())
                .andExpect(jsonPath("$.data.ops[1].excCd").value("PREV"));
        // 작업장 없는 사내 공정(외주 아님) → NO_NODE
        accounts.exec(comp, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, created_by, updated_by)
                values (?, 'FG5', 'FG5 품목', 'FG', 'MAKE', 'EA', 't', 't')""", comp);
        long rid = accounts.query(comp, """
                insert into mst_routing (comp_cd, item_id, plant_cd, rev, valid_from, created_by, updated_by)
                values (?, (select item_id from mst_item where item_cd = 'FG5'), 'P1', 'A0', (now() at time zone 'Asia/Seoul')::date - 30, 't', 't')
                returning routing_id""", Long.class, comp);
        accounts.exec(comp, """
                insert into mst_routing_step (comp_cd, routing_id, op_seq, proc_id, std_ct_sec, created_by, updated_by)
                values (?, ?, 10, (select proc_id from mst_proc where proc_cd = 'CUT'), 60, 't', 't')""", comp, rid);
        accounts.exec(comp, "update mst_routing set status = 'RELEASED', released_at = now(), released_by = 't' where routing_id = ?", rid);
        ctp(admin, body("FG5", "1", null)).andExpect(jsonPath("$.data.excCd").value("NO_NODE"));
    }

    @Test
    void 입력_오류와_라우팅_없음은_400_권한_없으면_403() throws Exception {
        accounts.exec(comp, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, created_by, updated_by)
                values (?, 'NOR', '라우팅 없음', 'FG', 'MAKE', 'EA', 't', 't')""", comp);
        ctp(admin, body("NOR", "1", null)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("itemCd"));
        ctp(admin, body("NOPE", "1", null)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("itemCd"));
        ctp(admin, body("FG", "0", null)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("qty"));
        ctp(admin, body("FG", "-1", null)).andExpect(status().isBadRequest());
        ctp(admin, body("FG", "1.5", null)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("qty")); // EA 자릿수 0
        ctp(admin, body("FG", "abc", null)).andExpect(status().isBadRequest());
        ctp(admin, body("FG", "1", "\"priority\":0")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("priority"));
        ctp(admin, body("FG", "1", "\"horizonDays\":32")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("horizonDays"));
        ctp(admin, body("FG", "1", "\"wantDate\":\"2026-13-40\"")).andExpect(status().isBadRequest());
        ctp(admin, "{\"itemCd\":\"FG\",\"qty\":\"1\"}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("plantCd"));
        mvc.perform(json(post(CTP), "{\"plantCd\":\"P2\",\"itemCd\":\"FG\",\"qty\":\"1\"}").with(admin)).andExpect(status().isBadRequest());
        // 화면 권한 없음 → 403, 조회만 있어도 답한다(저장 없음)
        long none = accounts.newUser(comp, "none1", PW);
        accounts.grant(comp, none, "PRD", "prd-wo", "SEARCH");
        ctp(accounts.as(comp, none, "none1"), body("FG", "1", null)).andExpect(status().isForbidden());
        long viewer = accounts.newUser(comp, "view1", PW);
        accounts.grant(comp, viewer, "APS", "aps-plan", "SEARCH");
        ctp(accounts.as(comp, viewer, "view1"), body("FG", "1", null)).andExpect(status().isOk());
    }

    @Test
    void B사는_A사_품목으로_묻지_못하고_A사_지시를_세지_않는다() throws Exception {
        String compB = accounts.newComp("APSC");
        long ub = accounts.newUser(compB, "admin1", PW);
        accounts.grant(compB, ub, "APS", "aps-plan", "SEARCH");
        RequestPostProcessor b = accounts.as(compB, ub, "admin1");
        accounts.addPlant(compB, "P1", "B 1공장");
        // A사 품목 코드 → B사에는 없는 품목
        ctp(b, body("FG", "1", null)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("itemCd"));
        // B사 자기 품목·라우팅·근무조 — A사 지시 2건은 세지 않는다
        accounts.exec(compB, """
                insert into mst_shift (comp_cd, plant_cd, shift_cd, shift_nm, start_time, end_time, created_by, updated_by)
                values (?, 'P1', 'D', '주간', '08:00', '17:00', 't', 't')""", compB);
        long area = accounts.query(compB, """
                insert into mst_equip_hier (comp_cd, plant_cd, parent_id, node_type, node_cd, node_nm, created_by, updated_by)
                values (?, 'P1', null, 'AREA', 'BA1', 'B 구역', 't', 't') returning node_id""", Long.class, compB);
        long node = accounts.query(compB, """
                insert into mst_equip_hier (comp_cd, plant_cd, parent_id, node_type, node_cd, node_nm, created_by, updated_by)
                values (?, 'P1', ?, 'LINE', 'BL1', 'B 라인', 't', 't') returning node_id""", Long.class, compB, area);
        accounts.exec(compB, """
                insert into mst_proc (comp_cd, proc_cd, proc_nm, proc_cat, outsrc_yn, created_by, updated_by)
                values (?, 'CUT', '절단', 'WELD', 'N', 't', 't')""", compB);
        accounts.exec(compB, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, created_by, updated_by)
                values (?, 'FG', 'B 품목', 'FG', 'MAKE', 'EA', 't', 't')""", compB);
        long rid = accounts.query(compB, """
                insert into mst_routing (comp_cd, item_id, plant_cd, rev, valid_from, created_by, updated_by)
                values (?, (select item_id from mst_item where item_cd = 'FG'), 'P1', 'A0', (now() at time zone 'Asia/Seoul')::date - 30, 't', 't')
                returning routing_id""", Long.class, compB);
        accounts.exec(compB, """
                insert into mst_routing_step (comp_cd, routing_id, op_seq, proc_id, node_id, std_ct_sec, created_by, updated_by)
                values (?, ?, 10, (select proc_id from mst_proc where proc_cd = 'CUT'), ?, 60, 't', 't')""", compB, rid, node);
        accounts.exec(compB, "update mst_routing set status = 'RELEASED', released_at = now(), released_by = 't' where routing_id = ?", rid);
        ctp(b, body("FG", "1", null)).andExpect(status().isOk()).andExpect(jsonPath("$.data.woCnt").value(0))
                .andExpect(jsonPath("$.data.itemNm").value("B 품목"));
    }
}
