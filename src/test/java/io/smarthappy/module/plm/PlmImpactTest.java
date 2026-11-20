package io.smarthappy.module.plm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * PLM 영향 대상 후보 보기 (docs/39 20.7) — 자품목 X 를 쓰는 상위 BOM(ECO 유효일 기준 확정 리비전, master 역전개) +
 * 작성 중 리비전 상태(ADDABLE·NO_DRAFT·OPEN_ECO·IN_THIS), 전체 레벨의 품목 후보(ITEM). 유효일 경계, A사로 B사, 권한.
 */
@AutoConfigureMockMvc
class PlmImpactTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";
    private static final String ECO = "/api/plm/ecos";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    String comp;
    long ownerId;
    RequestPostProcessor owner;
    LocalDate today;
    LocalDate eff;
    long eco;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("PIM");
        ownerId = accounts.newUser(comp, "eng1", PW);
        accounts.grant(comp, ownerId, "ENG", EcoService.PROG, "SEARCH", "SAVE");
        owner = accounts.as(comp, ownerId, "eng1");
        today = LocalDate.parse(accounts.query(comp, "select " + TestAccounts.COMP_TODAY + "::text", String.class));
        eff = today.plusDays(5);
        seed(comp);
        eco = ecoOf(comp, ownerId, "ECO-MAIN");
    }

    private long id(String c, String sql, Object... args) {
        return accounts.query(c, sql, Long.class, args);
    }

    private long item(String c, String cd) {
        return id(c, "select item_id from mst_item where item_cd = ?", cd);
    }

    /** 확정(또는 작성 중) BOM 한 리비전 — 자품목 하나 */
    private long bom(String c, String parent, String rev, String child, LocalDate from, LocalDate to, boolean released) {
        long b = id(c, """
                insert into mst_bom (comp_cd, item_id, rev, valid_from, valid_to, created_by, updated_by)
                values (?, ?, ?, ?, ?, 't', 't') returning bom_id""", c, item(c, parent), rev, from, to);
        accounts.exec(c, """
                insert into mst_bom_line (comp_cd, bom_id, line_no, item_id, qty, unit_cd, line_type, issue_method, created_by, updated_by)
                values (?, ?, 10, ?, 1, 'EA', 'NORMAL', 'MANUAL', 't', 't')""", c, b, item(c, child));
        if (released) {
            accounts.exec(c, "update mst_bom set status = 'RELEASED', released_at = now(), released_by = 't' where bom_id = ?", b);
        }
        return b;
    }

    /**
     * X 를 쓰는 곳: P1(작성 중 → 이 ECO 대상), P2(작성 중 없음), P3(작성 중 → 다른 열린 ECO), SA(작성 중 → 넣을 수 있음),
     * P4(유효일 다음 날부터 확정 — 안 보임), P5(유효일 전날까지 — 안 보임), P6(유효일부터 — 보임). FG 는 SA 를 쓴다(레벨 2)
     */
    private void seed(String c) {
        for (String i : new String[] {"X", "SA", "P1", "P2", "P3", "P4", "P5", "P6", "FG"}) {
            accounts.exec(c, """
                    insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, lot_ctrl_yn, created_by, updated_by)
                    values (?, ?, ?, 'SFG', 'MAKE', 'EA', 'N', 't', 't')""", c, i, i + " 품목");
        }
        LocalDate old = today.minusDays(30);
        for (String p : new String[] {"SA", "P1", "P2", "P3"}) {
            bom(c, p, "A0", "X", old, null, true);
        }
        for (String p : new String[] {"SA", "P1", "P3"}) {
            bom(c, p, "A1", "X", eff.plusDays(30), null, false);
        }
        bom(c, "P4", "A0", "X", eff.plusDays(1), null, true);
        bom(c, "P5", "A0", "X", old, eff, true);
        bom(c, "P6", "A0", "X", eff, null, true);
        bom(c, "FG", "A0", "SA", old, null, true);
    }

    private long ecoOf(String c, long ownerUser, String no) {
        return id(c, """
                insert into plm_eco (comp_cd, eco_no, title, reason_cd, effective_date, owner_id, created_by, updated_by)
                values (?, ?, '영향', 'QUALITY', ?, ?, 't', 't') returning eco_id""", c, no, eff, ownerUser);
    }

    private void target(String c, long ecoId, int seq, String type, String itemCd, Long bomId) {
        accounts.exec(c, """
                insert into plm_eco_target (comp_cd, eco_id, seq, target_type, item_id, bom_id, created_by, updated_by)
                values (?, ?, ?, ?, ?, ?, 't', 't')""", c, ecoId, seq, type, item(c, itemCd), bomId);
    }

    private long draftOf(String c, String parent) {
        return id(c, "select bom_id from mst_bom where item_id = ? and status = 'DRAFT'", item(c, parent));
    }

    private ResultActions impact(RequestPostProcessor who, long ecoId, String itemCd, String scope) throws Exception {
        var r = get(ECO + "/" + ecoId + "/impact").with(who);
        if (itemCd != null) {
            r.param("itemCd", itemCd);
        }
        if (scope != null) {
            r.param("scope", scope);
        }
        return mvc.perform(r);
    }

    private String body(ResultActions r) throws Exception {
        return r.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private void others() {
        target(comp, eco, 1, "BOM", "P1", draftOf(comp, "P1"));
        long other = ecoOf(comp, ownerId, "ECO-OTHER");
        target(comp, other, 1, "BOM", "P3", draftOf(comp, "P3"));
    }

    @Test
    void 일단계_상위는_작성_중_리비전_상태와_함께_유효일_기준으로_나온다() throws Exception {
        others();
        String b = body(impact(owner, eco, "x", null));
        assertThat(JsonPath.<String>read(b, "$.data.itemCd")).isEqualTo("X");
        assertThat(JsonPath.<String>read(b, "$.data.on")).isEqualTo(eff.toString());
        assertThat(JsonPath.<String>read(b, "$.data.scope")).isEqualTo("PARENT");
        // P4(유효일 다음 날부터)·P5(유효일 전날까지)는 안 보이고 P6(유효일부터)은 보인다 — 유효일 경계
        assertThat(JsonPath.<List<String>>read(b, "$.data.rows[*].parentCd")).containsExactly("P1", "P2", "P3", "P6", "SA");
        assertThat(JsonPath.<List<String>>read(b, "$.data.rows[*].state"))
                .containsExactly("IN_THIS", "NO_DRAFT", "OPEN_ECO", "NO_DRAFT", "ADDABLE");
        assertThat(JsonPath.<List<String>>read(b, "$.data.rows[?(@.parentCd == 'P3')].openEcoNo")).containsExactly("ECO-OTHER");
        assertThat(JsonPath.<List<Integer>>read(b, "$.data.rows[?(@.parentCd == 'SA')].draftBomId"))
                .containsExactly((int) draftOf(comp, "SA"));
        assertThat(JsonPath.<List<String>>read(b, "$.data.rows[?(@.parentCd == 'SA')].rev")).containsExactly("A0");
        assertThat(JsonPath.<List<String>>read(b, "$.data.rows[?(@.parentCd == 'SA')].draftRev")).containsExactly("A1");
    }

    @Test
    void 전체_레벨은_이단계_이상을_품목_대상_후보로_보이고_품목_대상이_있으면_IN_THIS() throws Exception {
        String b = body(impact(owner, eco, "X", "ALL"));
        assertThat(JsonPath.<List<String>>read(b, "$.data.rows[?(@.parentCd == 'FG')].state")).containsExactly("ITEM");
        assertThat(JsonPath.<List<Integer>>read(b, "$.data.rows[?(@.parentCd == 'FG')].level")).containsExactly(2);
        target(comp, eco, 1, "ITEM", "FG", null);
        b = body(impact(owner, eco, "X", "ALL"));
        assertThat(JsonPath.<List<String>>read(b, "$.data.rows[?(@.parentCd == 'FG')].state")).containsExactly("IN_THIS");
        // 레벨 순 → 코드 순
        assertThat(JsonPath.<List<Integer>>read(b, "$.data.rows[*].level")).last().isEqualTo(2);
    }

    @Test
    void 후보를_본_뒤_다른_지시가_먼저_가져가면_대상_저장에서_OPEN_ECO() throws Exception {
        String b = body(impact(owner, eco, "X", null));
        assertThat(JsonPath.<List<String>>read(b, "$.data.rows[?(@.parentCd == 'SA')].state")).containsExactly("ADDABLE");
        long other = ecoOf(comp, ownerId, "ECO-FAST");
        target(comp, other, 1, "BOM", "SA", draftOf(comp, "SA"));
        int ver = accounts.query(comp, "select row_ver from plm_eco where eco_id = ?", Integer.class, eco);
        mvc.perform(put(ECO + "/" + eco + "/targets").with(owner).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"rowVer\":" + ver + ",\"targets\":[{\"rowKey\":\"n1\",\"targetType\":\"BOM\",\"refId\":" + draftOf(comp, "SA") + "}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("OPEN_ECO"));
        // 다시 보면 상태가 바뀌어 있다
        b = body(impact(owner, eco, "X", null));
        assertThat(JsonPath.<List<String>>read(b, "$.data.rows[?(@.parentCd == 'SA')].state")).containsExactly("OPEN_ECO");
    }

    @Test
    void E_값_품목_없음_범위_코드_행_상한() throws Exception {
        impact(owner, eco, null, null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("itemCd")).andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        impact(owner, eco, "NOPE", null).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("NOT_FOUND"));
        impact(owner, eco, "X", "UP").andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("scope"));
        // 쓰는 곳이 없는 품목 → 빈 목록
        String b = body(impact(owner, eco, "FG", null));
        assertThat(JsonPath.<List<Object>>read(b, "$.data.rows")).isEmpty();
        // 행 상한(BOM 정책) — BOM 역전개와 같은 오류
        accounts.exec(comp, "insert into sys_policy (comp_cd, policy_key, policy_value, created_by, updated_by) values (?, 'BOM_EXPLODE_MAX_ROWS', '3', 't', 't')", comp);
        impact(owner, eco, "X", null).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("BOM_TOO_MANY_ROWS"));
    }

    @Test
    void A_권한_설계_변경_조회가_없으면_403_BOM_역전개_권한은_보지_않는다() throws Exception {
        long plain = accounts.newUser(comp, "plain1", PW);
        accounts.grant(comp, plain, "BOMV", "mst-bom-where", "SEARCH");
        impact(accounts.as(comp, plain, "plain1"), eco, "X", null).andExpect(status().isForbidden());
        // 설계 변경 조회만 있는 사용자(owner)는 BOM 역전개 권한 없이 본다
        impact(owner, eco, "X", null).andExpect(status().isOk());
    }

    @Test
    void B_A사로_B사_ECO_는_404_B사_품목_코드는_A사에서_찾지_못한다() throws Exception {
        String compB = accounts.newComp("PIMB");
        long userB = accounts.newUser(compB, "engB", PW);
        accounts.grant(compB, userB, "ENG", EcoService.PROG, "SEARCH", "SAVE");
        RequestPostProcessor b = accounts.as(compB, userB, "engB");
        impact(b, eco, "X", null).andExpect(status().isNotFound());
        accounts.exec(compB, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, created_by, updated_by)
                values (?, 'BX', 'B 품목', 'RM', 'BUY', 'EA', 't', 't')""", compB);
        impact(owner, eco, "BX", null).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("NOT_FOUND"));
    }
}
