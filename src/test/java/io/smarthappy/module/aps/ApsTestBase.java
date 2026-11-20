package io.smarthappy.module.aps;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * APS 시험 공통 데이터 (docs/38) — 공장 P1(주간 08~17), 작업장 L1(설비 M1·M2)·L2(설비 없음), 공정 절단·도금(외주),
 * 품목 FG(절단 L1 1시간/개 → 도금)·FG2(절단 L2), 지시1(FG 3개 우선 1, 발행)·지시2(FG 2개 우선 5, 계획). 첫날 = 회사 내일.
 */
@AutoConfigureMockMvc
abstract class ApsTestBase extends PostgresTestBase {

    static final String PW = "Maple-anchor-63";
    static final String A = "/api/aps/plans";
    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    String comp;
    long userId;
    RequestPostProcessor admin;
    LocalDate tomorrow;
    long wo1;
    long wo2;
    long line2;

    @BeforeEach
    void setUp() throws Exception {
        comp = accounts.newComp("APS");
        userId = accounts.newUser(comp, "admin1", PW);
        accounts.grant(comp, userId, "APS", "aps-plan", "SEARCH", "SAVE");
        accounts.grant(comp, userId, "APS", "prd-wo", "SEARCH", "SAVE");
        admin = accounts.as(comp, userId, "admin1");
        accounts.addPlant(comp, "P1", "1공장");
        accounts.addPlant(comp, "P2", "근무조 없는 공장");
        // 9단계 확정 구간(기본 2일)은 이 기반의 1~8단계 시험 뜻을 바꾸지 않게 끈다 — 구간 시험(ApsFenceTest)이 따로 켠다
        accounts.exec(comp, "insert into sys_policy (comp_cd, policy_key, policy_value, created_by, updated_by) values (?, 'APS_FENCE_DAYS', '0', 't', 't')", comp);
        tomorrow = LocalDate.parse(accounts.query(comp, "select (" + TestAccounts.COMP_TODAY + " + 1)::text", String.class));
        accounts.exec(comp, """
                insert into mst_shift (comp_cd, plant_cd, shift_cd, shift_nm, start_time, end_time, created_by, updated_by)
                values (?, 'P1', 'D', '주간', '08:00', '17:00', 't', 't')""", comp);
        long area = node(null, "AREA", "A1");
        long line1 = node(area, "LINE", "L1");
        line2 = node(area, "LINE", "L2");
        long cls = accounts.query(comp, """
                insert into mst_equip_class (comp_cd, class_cd, class_nm, created_by, updated_by)
                values (?, 'PRESS', '프레스', 't', 't') returning class_id""", Long.class, comp);
        for (String m : new String[] {"M1", "M2"}) {
            accounts.exec(comp, """
                    insert into mst_equip (comp_cd, plant_cd, equip_cd, equip_nm, class_id, node_id, created_by, updated_by)
                    values (?, 'P1', ?, ?, ?, ?, 't', 't')""", comp, m, m + " 프레스", cls, line1);
        }
        accounts.exec(comp, """
                insert into mst_proc (comp_cd, proc_cd, proc_nm, proc_cat, outsrc_yn, created_by, updated_by)
                values (?, 'CUT', '절단', 'WELD', 'N', 't', 't'), (?, 'PLATE', '도금', 'WELD', 'Y', 't', 't')""", comp, comp);
        for (String i : new String[] {"FG", "FG2"}) {
            accounts.exec(comp, """
                    insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, created_by, updated_by)
                    values (?, ?, ?, 'FG', 'MAKE', 'EA', 't', 't')""", comp, i, i + " 품목");
        }
        // FG: 절단(L1, 1시간/개) → 도금(외주) / FG2: 절단(L2 — 설비 없음)
        routing("FG", line1, true);
        routing("FG2", line2, false);
        wo1 = wo("FG", "3", 1);
        mvc.perform(json(post("/api/production/wos/" + wo1 + "/release"), "{\"rowVer\":0}").with(admin)).andExpect(status().isOk());
        wo2 = wo("FG", "2", 5);
    }

    // ---------- 도우미 ----------

    long node(Long parent, String type, String cd) {
        return accounts.query(comp, """
                insert into mst_equip_hier (comp_cd, plant_cd, parent_id, node_type, node_cd, node_nm, created_by, updated_by)
                values (?, 'P1', ?, ?, ?, ?, 't', 't') returning node_id""", Long.class, comp, parent, type, cd, cd + " 작업장");
    }

    void routing(String item, long node, boolean plate) {
        long rid = accounts.query(comp, """
                insert into mst_routing (comp_cd, item_id, plant_cd, rev, valid_from, created_by, updated_by)
                values (?, (select item_id from mst_item where item_cd = ?), 'P1', 'A0', (now() at time zone 'Asia/Seoul')::date - 30, 't', 't')
                returning routing_id""", Long.class, comp, item);
        accounts.exec(comp, """
                insert into mst_routing_step (comp_cd, routing_id, op_seq, proc_id, node_id, std_ct_sec, created_by, updated_by)
                values (?, ?, 10, (select proc_id from mst_proc where proc_cd = 'CUT'), ?, 3600, 't', 't')""", comp, rid, node);
        if (plate) {
            accounts.exec(comp, """
                    insert into mst_routing_step (comp_cd, routing_id, op_seq, proc_id, created_by, updated_by)
                    values (?, ?, 20, (select proc_id from mst_proc where proc_cd = 'PLATE'), 't', 't')""", comp, rid);
        }
        accounts.exec(comp, "update mst_routing set status = 'RELEASED', released_at = now(), released_by = 't' where routing_id = ?", rid);
    }

    long wo(String item, String qty, int prio) throws Exception {
        return id(mvc.perform(json(post("/api/production/wos"), "{\"plantCd\":\"P1\",\"itemCd\":\"" + item + "\",\"planQty\":\"" + qty
                + "\",\"planStart\":\"" + tomorrow + "\",\"planEnd\":\"" + tomorrow + "\",\"priority\":" + prio + "}").with(admin)));
    }

    MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String body) {
        return b.with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    static long id(ResultActions r) throws Exception {
        MvcResult m = r.andExpect(status().isOk()).andReturn();
        return ((Number) JsonPath.read(m.getResponse().getContentAsString(), "$.data")).longValue();
    }

    ResultActions calc(RequestPostProcessor who, String plant, LocalDate first, int days) throws Exception {
        return mvc.perform(json(post(A), "{\"plantCd\":\"" + plant + "\",\"horizonStart\":\"" + first + "\",\"horizonDays\":" + days
                + ",\"planNm\":\"ZT 시험\"}").with(who));
    }

    Instant at(LocalDate d, int h) {
        return d.atTime(LocalTime.of(h, 0)).atZone(SEOUL).toInstant();
    }

    String woState() {
        return accounts.query(comp, """
                select string_agg(w.wo_id || ':' || w.status || ':' || w.row_ver || ':' || w.plan_start || ':' || w.updated_at
                                  || ':' || coalesce((select string_agg(p.wo_proc_id || '/' || p.row_ver || '/' || coalesce(p.equip_id, 0),
                                                                        ',' order by p.wo_proc_id)
                                                        from prd_wo_proc p where p.wo_id = w.wo_id), ''), ';' order by w.wo_id)
                  from prd_wo w""", String.class);
    }
}
