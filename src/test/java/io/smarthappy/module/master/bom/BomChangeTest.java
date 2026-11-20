package io.smarthappy.module.master.bom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.module.master.bom.BomChangeDtos.Edge;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/** BOM 변경 도구 (P1 3c) — 비교·다른 품목으로 복사·일괄 대체(미리보기 판정 6종·실행·전부 아니면 전무)·저레벨 코드·A/B사·권한 */
@AutoConfigureMockMvc
class BomChangeTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    String comp;
    RequestPostProcessor admin;
    LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("BC");
        long userId = accounts.newUser(comp, "admin1", PW);
        accounts.grant(comp, userId, "MASTER", "mst-bom", "SEARCH", "SAVE");
        accounts.grant(comp, userId, "MASTER", "mst-bom-replace", "SEARCH", "SAVE");
        accounts.grant(comp, userId, "MASTER", "mst-bom-where", "SEARCH");
        admin = accounts.as(comp, userId, "admin1");
        for (String cd : List.of("FG", "FG2", "SA", "RM1", "NUT", "NUT2", "BOLT", "P1", "P2", "P3", "P4", "P5", "P6")) {
            accounts.exec(comp, """
                    insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, lot_ctrl_yn,
                                          created_by, updated_by)
                    values (?, ?, ?, 'RM', 'BUY', ?, 'N', 't', 't')""", comp, cd, cd + " 품목", "RM1".equals(cd) ? "KG" : "EA");
        }
    }

    /** lines = {자품목, 수량} — 순번 10, 20 … (첫 원소가 "#n" 이면 그 순번부터) */
    private long bom(String parent, String rev, LocalDate from, boolean release, String... pairs) {
        long id = accounts.query(comp, """
                insert into mst_bom (comp_cd, item_id, rev, valid_from, created_by, updated_by)
                select ?, item_id, ?, ?, 't', 't' from mst_item where item_cd = ? returning bom_id""",
                Long.class, comp, rev, from, parent);
        for (int i = 0; i < pairs.length; i += 2) {
            accounts.exec(comp, """
                    insert into mst_bom_line (comp_cd, bom_id, line_no, item_id, qty, unit_cd, issue_method, created_by, updated_by)
                    select ?, ?, ?, item_id, ?::numeric, unit_cd, 'BACKFLUSH', 't', 't' from mst_item where item_cd = ?""",
                    comp, id, (i / 2 + 1) * 10, pairs[i + 1], pairs[i]);
        }
        if (release) {
            accounts.exec(comp, "update mst_bom set status = 'RELEASED', released_at = now(), released_by = 't' where bom_id = ?", id);
        }
        return id;
    }

    private ResultActions postJson(String url, String body, RequestPostProcessor who) throws Exception {
        return mvc.perform(post(url).with(csrf()).with(who).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    // ---------- 비교 ----------

    @Test
    void 비교는_자품목_유형_공정_키로_추가_삭제_변경_같음을_나눈다() throws Exception {
        long a0 = bom("FG", "A0", today.minusDays(30), true, "SA", "2", "RM1", "1", "NUT", "4");
        long a1 = bom("FG", "A1", today, false, "RM1", "1", "SA", "3", "BOLT", "2");   // RM1 은 순번만 바뀜
        MvcResult r = mvc.perform(get("/api/master/boms/compare").param("leftId", "" + a0).param("rightId", "" + a1)
                .with(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.added").value(1))
                .andExpect(jsonPath("$.data.removed").value(1))
                .andExpect(jsonPath("$.data.changedCount").value(1))
                .andExpect(jsonPath("$.data.same").value(1))
                .andExpect(jsonPath("$.data.left.rev").value("A0"))
                .andExpect(jsonPath("$.data.right.rev").value("A1"))
                .andReturn();
        String body = r.getResponse().getContentAsString();
        Map<String, Object> sa = JsonPath.<List<Map<String, Object>>>read(body, "$.data.rows[?(@.itemCd=='SA')]").get(0);
        assertThat(sa.get("status")).isEqualTo("CHANGED");
        assertThat(String.valueOf(sa.get("changed"))).isEqualTo("[\"qty\"]");
        assertThat(JsonPath.<List<String>>read(body, "$.data.rows[?(@.itemCd=='RM1')].status")).containsExactly("SAME");
        assertThat(JsonPath.<List<String>>read(body, "$.data.rows[?(@.itemCd=='BOLT')].status")).containsExactly("ADDED");
        assertThat(JsonPath.<List<String>>read(body, "$.data.rows[?(@.itemCd=='NUT')].status")).containsExactly("REMOVED");
    }

    // ---------- 복사 ----------

    @Test
    void 다른_품목으로_복사_같은품목_작성중있음_순환_사용중지_거부() throws Exception {
        long src = bom("FG", "A0", today, true, "SA", "2", "NUT", "4");
        MvcResult r = postJson("/api/master/boms/" + src + "/copy", "{\"itemCd\":\"fg2\"}", admin)
                .andExpect(status().isOk()).andReturn();
        long copy = ((Number) JsonPath.read(r.getResponse().getContentAsString(), "$.data")).longValue();
        assertThat(accounts.query(comp, "select rev || status || ':' || change_reason from mst_bom where bom_id = ?",
                String.class, copy)).isEqualTo("A0DRAFT:복사: FG A0");
        assertThat(accounts.query(comp, "select count(*) from mst_bom_line where bom_id = ?", Integer.class, copy)).isEqualTo(2);
        postJson("/api/master/boms/" + src + "/copy", "{\"itemCd\":\"FG\"}", admin).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("SAME_ITEM_BOM"));
        postJson("/api/master/boms/" + src + "/copy", "{\"itemCd\":\"FG2\"}", admin).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BOM_DRAFT_EXISTS"));
        // SA 를 쓰는 BOM 을 SA 로 복사 = SA 가 자기 자신을 포함
        postJson("/api/master/boms/" + src + "/copy", "{\"itemCd\":\"SA\"}", admin).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BOM_CYCLE_AT"));
        assertThat(accounts.query(comp, "select count(*) from mst_bom b join mst_item i using (item_id) where i.item_cd = 'SA'",
                Integer.class)).isZero();                                 // 전부 취소
        accounts.exec(comp, "update mst_item set use_yn = 'N' where item_cd = 'BOLT'");
        postJson("/api/master/boms/" + src + "/copy", "{\"itemCd\":\"BOLT\"}", admin).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("INACTIVE"));
    }

    // ---------- 일괄 대체 ----------

    private void replaceFixture() {
        bom("P1", "A0", today.minusDays(10), true, "NUT", "4");                      // NEW_REV
        bom("P2", "A0", today.minusDays(10), true, "NUT", "4");
        bom("P2", "A1", today, false, "NUT", "6", "BOLT", "1");                      // DRAFT (작성 중이 NUT 사용)
        bom("P3", "A0", today, true, "NUT", "4");                                    // SAME_DAY
        bom("P4", "A0", today.minusDays(10), true, "NUT", "4");
        bom("P4", "A1", today, false, "BOLT", "4");                                  // DRAFT_OTHER
        bom("P5", "A0", today.minusDays(10), true, "NUT", "4");
        bom("NUT2", "A0", today.minusDays(10), true, "P5", "1");                     // CYCLE (NUT2 아래에 P5)
        bom("P6", "A0", today.plusDays(10), true, "NUT", "4");                       // NO_EFFECTIVE
    }

    private ResultActions preview(String from, String to, String date) throws Exception {
        var req = get("/api/master/bom-replace/preview").param("fromItemCd", from).param("toItemCd", to);
        if (date != null) {
            req.param("validFrom", date);
        }
        return mvc.perform(req.with(admin));
    }

    @Test
    void 일괄대체_미리보기는_모품목마다_반영방법과_건너뛰는_이유() throws Exception {
        replaceFixture();
        MvcResult r = preview("nut", "NUT2", null).andExpect(status().isOk()).andReturn();
        String body = r.getResponse().getContentAsString();
        Map<String, String> actions = new java.util.TreeMap<>();
        List<Map<String, Object>> rows = JsonPath.read(body, "$.data.rows");
        rows.forEach(x -> actions.put((String) x.get("parentCd"),
                x.get("action") + (x.get("reason") == null ? "" : ":" + x.get("reason"))));
        assertThat(actions).containsExactlyEntriesOf(new java.util.TreeMap<>(Map.of(
                "P1", "NEW_REV", "P2", "DRAFT", "P3", "SKIP:SAME_DAY", "P4", "SKIP:DRAFT_OTHER", "P5", "SKIP:CYCLE",
                "P6", "SKIP:NO_EFFECTIVE")));
        assertThat(JsonPath.<List<String>>read(body, "$.data.rows[?(@.parentCd=='P1')].newRev")).containsExactly("A1");
        // 입력 검사
        preview("NUT", "NUT", today.minusDays(1).toString()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].code", hasItem("SAME_ITEM_BOM")))
                .andExpect(jsonPath("$.errors[*].code", hasItem("PAST")));
        preview("NOPE", "NUT2", null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("NOT_FOUND"));
    }

    @Test
    void 일괄대체_실행_새리비전과_작성중에_반영_확정본은_그대로_대상이_바뀌면_전부_취소() throws Exception {
        replaceFixture();
        long p1a0 = accounts.query(comp, "select bom_id from mst_bom b join mst_item i using (item_id) where i.item_cd = 'P1'", Long.class);
        long p2a1 = accounts.query(comp, "select bom_id from mst_bom b join mst_item i using (item_id) where i.item_cd = 'P2' and rev = 'A1'", Long.class);
        long p3a0 = accounts.query(comp, "select bom_id from mst_bom b join mst_item i using (item_id) where i.item_cd = 'P3'", Long.class);
        // 건너뛸 대상(P3)을 넣으면 전부 취소
        postJson("/api/master/bom-replace", "{\"fromItemCd\":\"NUT\",\"toItemCd\":\"NUT2\",\"bomIds\":[" + p1a0 + "," + p3a0 + "]}",
                admin).andExpect(status().isConflict());
        assertThat(accounts.query(comp, "select count(*) from mst_bom", Integer.class)).isEqualTo(9);
        postJson("/api/master/bom-replace", "{\"fromItemCd\":\"NUT\",\"toItemCd\":\"NUT2\",\"bomIds\":[" + p1a0 + "," + p2a1 + "]}",
                admin).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].action").value("NEW_REV"))
                .andExpect(jsonPath("$.data[0].rev").value("A1"))
                .andExpect(jsonPath("$.data[1].action").value("DRAFT"));
        assertThat(accounts.query(comp, """
                select string_agg(i.item_cd, ',') from mst_bom_line l join mst_item i on i.item_id = l.item_id where l.bom_id = ?""",
                String.class, p1a0)).isEqualTo("NUT");                    // 확정본은 그대로
        assertThat(accounts.query(comp, """
                select i.item_cd || ':' || b.status || ':' || b.change_reason from mst_bom b
                  join mst_bom_line l on l.bom_id = b.bom_id join mst_item i on i.item_id = l.item_id
                  join mst_item p on p.item_id = b.item_id where p.item_cd = 'P1' and b.rev = 'A1'""", String.class))
                .isEqualTo("NUT2:DRAFT:일괄 대체: NUT → NUT2");
        assertThat(accounts.query(comp, """
                select string_agg(i.item_cd || '=' || l.qty::int, ',' order by l.line_no) from mst_bom_line l
                  join mst_item i on i.item_id = l.item_id where l.bom_id = ?""", String.class, p2a1)).isEqualTo("NUT2=6,BOLT=1");
        // 다시 미리보기: P1 은 작성 중이 생겼고 NUT 를 안 쓰므로 DRAFT_OTHER, P2 는 사용처에서 빠짐
        preview("NUT", "NUT2", null).andExpect(jsonPath("$.data.rows[?(@.parentCd=='P1')].reason").value(hasItem("DRAFT_OTHER")))
                .andExpect(jsonPath("$.data.rows[?(@.parentCd=='P2')].action").value(hasItem("SKIP")));
    }

    // ---------- 저레벨 코드 ----------

    @Test
    void 저레벨_코드는_가장_깊은_레벨() throws Exception {
        bom("FG", "A0", today, true, "SA", "1", "RM1", "1");          // RM1 은 1 레벨에도 나오지만
        bom("SA", "A0", today, true, "RM1", "2");                     // SA 아래(2 레벨)가 더 깊다
        mvc.perform(get("/api/master/bom-llc").with(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].itemCd").value(org.hamcrest.Matchers.contains("FG", "SA", "RM1")))
                .andExpect(jsonPath("$.data[*].llc").value(org.hamcrest.Matchers.contains(0, 1, 2)));
        mvc.perform(get("/api/master/bom-where").param("itemCd", "RM1").with(admin))
                .andExpect(jsonPath("$.data.llc").value(2));
    }

    @Test
    void 저레벨_계산_다이아몬드와_순환() {
        // 1 → 2 → 4, 1 → 3 → 4, 4 → 5 / 순환 6 ↔ 7
        List<Edge> edges = new java.util.ArrayList<>();
        long[][] pairs = {{1, 2}, {1, 3}, {2, 4}, {3, 4}, {4, 5}, {6, 7}, {7, 6}};
        for (long[] p : pairs) {
            Edge e = new Edge();
            e.setParentId(p[0]);
            e.setChildId(p[1]);
            edges.add(e);
        }
        Map<Long, Integer> m = BomChangeService.computeLlc(edges);
        assertThat(m).containsEntry(1L, 0).containsEntry(4L, 2).containsEntry(5L, 3).containsEntry(6L, -1).containsEntry(7L, -1);
    }

    // ---------- 회사·권한 ----------

    @Test
    void B사_사용자_비교_복사_대체_불가_권한없으면_403() throws Exception {
        long a0 = bom("FG", "A0", today, true, "NUT", "4");
        String other = accounts.newComp("BC");
        long bUser = accounts.newUser(other, "admin1", PW);
        accounts.grant(other, bUser, "MASTER", "mst-bom", "SEARCH", "SAVE");
        accounts.grant(other, bUser, "MASTER", "mst-bom-replace", "SEARCH", "SAVE");
        RequestPostProcessor b = accounts.as(other, bUser, "admin1");
        mvc.perform(get("/api/master/boms/compare").param("leftId", "" + a0).param("rightId", "" + a0).with(b))
                .andExpect(status().isNotFound());
        postJson("/api/master/boms/" + a0 + "/copy", "{\"itemCd\":\"FG\"}", b).andExpect(status().isNotFound());
        mvc.perform(get("/api/master/bom-replace/preview").param("fromItemCd", "NUT").param("toItemCd", "NUT2").with(b))
                .andExpect(status().isBadRequest());
        postJson("/api/master/bom-replace", "{\"fromItemCd\":\"NUT\",\"toItemCd\":\"NUT2\",\"bomIds\":[" + a0 + "]}", b)
                .andExpect(status().isBadRequest());
        long reader = accounts.newUser(comp, "reader1", PW);
        accounts.grant(comp, reader, "READER", "mst-bom-replace", "SEARCH");
        RequestPostProcessor r = accounts.as(comp, reader, "reader1");
        mvc.perform(get("/api/master/bom-replace/preview").param("fromItemCd", "NUT").param("toItemCd", "NUT2").with(r))
                .andExpect(status().isOk());
        postJson("/api/master/bom-replace", "{\"fromItemCd\":\"NUT\",\"toItemCd\":\"NUT2\",\"bomIds\":[" + a0 + "]}", r)
                .andExpect(status().isForbidden());
        postJson("/api/master/boms/" + a0 + "/copy", "{\"itemCd\":\"FG2\"}", r).andExpect(status().isForbidden());
    }
}
