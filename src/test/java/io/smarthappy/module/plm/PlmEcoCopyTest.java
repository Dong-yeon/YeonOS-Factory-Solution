package io.smarthappy.module.plm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * 지시 복사 (docs/39 27장) — 어느 상태든 새 작성 중 지시로: 머리 재검증(유효일 오늘 이후·담당 = 복사한 사람·고객 승인 기록 없음),
 * 품목·설비·공정 대상만 옮기고 BOM 리비전·사용 중지 품목은 빼서 건수로, 결재·첨부는 안 옮김. 권한·A사로 B사·멱등 키.
 */
@AutoConfigureMockMvc
class PlmEcoCopyTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    String comp;
    long ownerId;
    long copierId;
    RequestPostProcessor copier;
    RequestPostProcessor viewer;
    String today;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("PCP");
        ownerId = accounts.newUser(comp, "eng1", PW);
        copierId = accounts.newUser(comp, "eng2", PW);
        long viewId = accounts.newUser(comp, "view1", PW);
        accounts.grant(comp, copierId, "ENG", "plm-eco", "SEARCH", "SAVE");
        accounts.grant(comp, viewId, "VIEW", "plm-eco", "SEARCH");
        copier = accounts.as(comp, copierId, "eng2");
        viewer = accounts.as(comp, viewId, "view1");
        today = accounts.query(comp, "select " + TestAccounts.COMP_TODAY + "::text", String.class);
        accounts.addPlant(comp, "P1", "1공장");
        for (String i : new String[] {"FG", "OLD", "RM"}) {
            accounts.exec(comp, """
                    insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, created_by, updated_by)
                    values (?, ?, ?, 'FG', 'MAKE', 'EA', 't', 't')""", comp, i, i);
        }
        accounts.exec(comp, "insert into mst_proc (comp_cd, proc_cd, proc_nm, proc_cat, created_by, updated_by) values (?, 'SPOT', '용접', 'WELD', 't', 't')", comp);
        accounts.exec(comp, "insert into mst_cust (comp_cd, cust_cd, cust_nm, cust_type, created_by, updated_by) values (?, 'C1', 'C1', 'SALES', 't', 't')", comp);
    }

    private long id(String sql, Object... args) {
        return accounts.query(comp, sql, Long.class, args);
    }

    /** 적용된 지시(유효일 열흘 전·임시 변경·고객 승인 받음) — 대상: FG 품목, FG 공정 SPOT, OLD 품목(뒤에 사용 중지), FG BOM 작성 중 리비전 */
    private long source() {
        long eco = id("""
                insert into plm_eco (comp_cd, eco_no, title, reason_cd, description, effective_date, owner_id, change_kind, mark_until,
                                     cust_approval_req_yn, cust_id, cust_approval_status, cust_approval_ref, cust_approved_on, created_by, updated_by)
                select ?, 'SRC-1', '브래킷 두께 변경', 'QUALITY', '두께 2.0 → 2.3', cast(? as date) - 10, ?, 'TEMP', cast(? as date) - 3,
                       'Y', cust_id, 'APPROVED', 'PSW-77', cast(? as date) - 11, 't', 't' from mst_cust where cust_cd = 'C1'
                returning eco_id""", comp, today, ownerId, today, today);
        accounts.exec(comp, "insert into plm_eco_target (comp_cd, eco_id, seq, target_type, item_id, change_desc, created_by, updated_by) values (?, ?, 1, 'ITEM', (select item_id from mst_item where item_cd = 'FG'), '두께', 't', 't')", comp, eco);
        accounts.exec(comp, """
                insert into plm_eco_target (comp_cd, eco_id, seq, target_type, item_id, proc_id, created_by, updated_by)
                values (?, ?, 2, 'PROC', (select item_id from mst_item where item_cd = 'FG'), (select proc_id from mst_proc where proc_cd = 'SPOT'), 't', 't')""", comp, eco);
        accounts.exec(comp, "insert into plm_eco_target (comp_cd, eco_id, seq, target_type, item_id, created_by, updated_by) values (?, ?, 3, 'ITEM', (select item_id from mst_item where item_cd = 'OLD'), 't', 't')", comp, eco);
        long bom = id("""
                insert into mst_bom (comp_cd, item_id, rev, valid_from, created_by, updated_by)
                values (?, (select item_id from mst_item where item_cd = 'FG'), 'B', cast(? as date), 't', 't') returning bom_id""", comp, today);
        accounts.exec(comp, """
                insert into plm_eco_target (comp_cd, eco_id, seq, target_type, item_id, bom_id, created_by, updated_by)
                values (?, ?, 4, 'BOM', (select item_id from mst_item where item_cd = 'FG'), ?, 't', 't')""", comp, eco, bom);
        accounts.exec(comp, "update plm_eco set status = 'PENDING' where eco_id = ?", eco);
        accounts.exec(comp, "update plm_eco set status = 'APPROVED', approved_at = now() where eco_id = ?", eco);
        accounts.exec(comp, "update plm_eco set status = 'RELEASED', released_at = now() where eco_id = ?", eco);
        accounts.exec(comp, "update mst_item set use_yn = 'N' where item_cd = 'OLD'");
        return eco;
    }

    private ResultActions copy(RequestPostProcessor who, long eco, String key) throws Exception {
        return mvc.perform(post("/api/plm/ecos/" + eco + "/copy").with(csrf()).with(who).header("X-Idempotency-Key", key));
    }

    @Test
    void 적용된_지시를_새_작성_중_지시로_복사_머리는_재검증_대상은_품목_공정만() throws Exception {
        long src = source();
        MvcResult r = copy(copier, src, UUID.randomUUID().toString()).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.copied").value(2)).andExpect(jsonPath("$.data.skipped").value(2)).andReturn();
        long eco = ((Number) JsonPath.read(r.getResponse().getContentAsString(), "$.data.ecoId")).longValue();
        assertThat(eco).isNotEqualTo(src);
        String row = accounts.query(comp, """
                select concat_ws('|', status, title, reason_cd, description, effective_date::text, owner_id::text, change_kind, mark_until::text,
                                 cust_approval_req_yn, cust_approval_status, coalesce(cust_approval_ref, '-'), coalesce(apr_id::text, '-'),
                                 coalesce(approved_at::text, '-'), eco_no <> 'SRC-1')
                  from plm_eco where eco_id = ?""", String.class, eco);
        // 유효일 = 오늘(지난 날짜는 당김), 임시 변경 끝 날짜도 새 유효일로, 담당 = 복사한 사람, 고객 승인은 필요·대기(기록 없음), 결재 없음
        assertThat(row).isEqualTo(String.join("|", "DRAFT", "브래킷 두께 변경", "QUALITY", "두께 2.0 → 2.3", today, String.valueOf(copierId),
                "TEMP", today, "Y", "PENDING", "-", "-", "-", "t"));
        String targets = accounts.query(comp, """
                select string_agg(t.target_type || ':' || i.item_cd || ':' || coalesce(t.change_desc, '-'), ',' order by t.seq)
                  from plm_eco_target t join mst_item i on i.item_id = t.item_id where t.eco_id = ?""", String.class, eco);
        assertThat(targets).isEqualTo("ITEM:FG:두께,PROC:FG:-");
        // 원본은 그대로
        assertThat(accounts.query(comp, "select status || ':' || (select count(*) from plm_eco_target where eco_id = ?) from plm_eco where eco_id = ?",
                String.class, src, src)).isEqualTo("RELEASED:4");
    }

    @Test
    void 같은_요청_키는_한_번_조회_권한만은_403_A사로_B사는_404() throws Exception {
        long src = source();
        String key = UUID.randomUUID().toString();
        copy(copier, src, key).andExpect(status().isOk());
        copy(copier, src, key).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DUPLICATE_REQUEST"));
        copy(viewer, src, UUID.randomUUID().toString()).andExpect(status().isForbidden());
        assertThat(accounts.query(comp, "select count(*) from plm_eco", Integer.class)).isEqualTo(2);
        String compB = accounts.newComp("PCPB");
        long b = accounts.newUser(compB, "engB", PW);
        accounts.grant(compB, b, "ENG", "plm-eco", "SEARCH", "SAVE");
        copy(accounts.as(compB, b, "engB"), src, UUID.randomUUID().toString()).andExpect(status().isNotFound());
        assertThat(accounts.query(compB, "select count(*) from plm_eco", Integer.class)).isZero();
    }

    @Test
    void 지금_규칙에_안_맞는_머리는_400_새_지시도_안_생긴다() throws Exception {
        long src = source();
        // 고객이 판매 거래처가 아니게 됨 → 머리 재검증 실패
        accounts.exec(comp, "update mst_cust set cust_type = 'PURCHASE' where cust_cd = 'C1'");
        copy(copier, src, UUID.randomUUID().toString()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("custCd"));
        assertThat(accounts.query(comp, "select count(*) from plm_eco", Integer.class)).isEqualTo(1);
    }
}
