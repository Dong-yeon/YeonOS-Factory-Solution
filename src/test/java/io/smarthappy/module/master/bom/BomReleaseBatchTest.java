package io.smarthappy.module.master.bom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

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
 * 일괄 대체 → 결과에서 일괄 확정 (사용자 결정 G5 2026-11-02, docs/60 R1·R2).
 * 정상: 변경 번호·사유가 새 리비전에 들어가고, 고른 작성 중을 한 번에 확정(이전 확정본 자동 마감).
 * 부분 실패: 버전 어긋남·확정 검사 실패·이미 확정은 그 건만 실패(사유), 나머지는 확정.
 * 이상 사용: 확정 권한 없음 403, 비로그인 401, B사 사용자가 A사 BOM id = 없음(확정 안 됨), 빈·중복·null·1,001건 400, 변경 번호 31자 400.
 */
@AutoConfigureMockMvc
class BomReleaseBatchTest extends PostgresTestBase {

    private static final String PW = "Harbor-violet-58";
    private static final String BATCH = "/api/master/boms/release-batch";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    String comp;
    RequestPostProcessor admin;
    LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("BR");
        long userId = accounts.newUser(comp, "admin1", PW);
        accounts.grant(comp, userId, "MASTER", "mst-bom", "SEARCH", "SAVE", "APPROVE");
        accounts.grant(comp, userId, "MASTER", "mst-bom-replace", "SEARCH", "SAVE");
        admin = accounts.as(comp, userId, "admin1");
        items(comp);
        for (String p : List.of("P1", "P2", "P3")) {
            bom(comp, p, today.minusDays(10));
        }
    }

    private void items(String c) {
        for (String cd : List.of("NUT", "NUT2", "P1", "P2", "P3")) {
            accounts.exec(c, """
                    insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, lot_ctrl_yn, created_by, updated_by)
                    values (?, ?, ?, 'RM', 'BUY', 'EA', 'N', 't', 't')""", c, cd, cd + " 품목");
        }
    }

    /** 확정본 A0 = NUT 4 */
    private long bom(String c, String parent, LocalDate from) {
        long id = accounts.query(c, """
                insert into mst_bom (comp_cd, item_id, rev, valid_from, created_by, updated_by)
                select ?, item_id, 'A0', ?, 't', 't' from mst_item where item_cd = ? returning bom_id""", Long.class, c, from, parent);
        accounts.exec(c, """
                insert into mst_bom_line (comp_cd, bom_id, line_no, item_id, qty, unit_cd, issue_method, created_by, updated_by)
                select ?, ?, 10, item_id, 4, 'EA', 'BACKFLUSH', 't', 't' from mst_item where item_cd = 'NUT'""", c, id);
        accounts.exec(c, "update mst_bom set status = 'RELEASED', released_at = now(), released_by = 't' where bom_id = ?", id);
        return id;
    }

    private ResultActions postJson(String url, String body, RequestPostProcessor who) throws Exception {
        return mvc.perform(post(url).with(csrf()).with(who).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private List<Long> sources() {
        String ids = accounts.query(comp, "select string_agg(bom_id::text, ',' order by bom_id) from mst_bom where rev = 'A0'",
                String.class);
        return java.util.Arrays.stream(ids.split(",")).map(Long::valueOf).toList();
    }

    /** NUT → NUT2 를 내일부터, 결과(모품목 → {bomId, rowVer}) */
    private Map<String, long[]> replace(String extra) throws Exception {
        String ids = sources().stream().map(String::valueOf).collect(Collectors.joining(","));
        String body = postJson("/api/master/bom-replace", "{\"fromItemCd\":\"NUT\",\"toItemCd\":\"NUT2\",\"validFrom\":\""
                + today.plusDays(1) + "\",\"bomIds\":[" + ids + "]" + extra + "}", admin)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<Map<String, Object>> rows = JsonPath.read(body, "$.data");
        return rows.stream().collect(Collectors.toMap(r -> (String) r.get("parentCd"),
                r -> new long[] {((Number) r.get("bomId")).longValue(), ((Number) r.get("rowVer")).longValue()}));
    }

    private static String items(long[]... pairs) {
        return "{\"items\":[" + java.util.Arrays.stream(pairs).map(p -> "{\"bomId\":" + p[0] + ",\"rowVer\":" + p[1] + "}")
                .collect(Collectors.joining(",")) + "]}";
    }

    private String bomStatus(long bomId) {
        return accounts.query(comp, "select status from mst_bom where bom_id = ?", String.class, bomId);
    }

    @Test
    void 변경번호_사유가_새_리비전에_들어가고_결과에서_한번에_확정한다() throws Exception {
        Map<String, long[]> done = replace(",\"changeNo\":\" ECO-77 \",\"changeReason\":\"NUT 단종 — NUT2 로\"");
        assertThat(done).hasSize(3);
        for (long[] d : done.values()) {
            assertThat(accounts.query(comp, "select rev || '|' || status || '|' || change_no || '|' || change_reason from mst_bom where bom_id = ?",
                    String.class, d[0])).isEqualTo("A1|DRAFT|ECO-77|NUT 단종 — NUT2 로");
            // 결과의 rowVer = 지금 머리 버전(그대로 일괄 확정에 쓴다)
            assertThat(accounts.query(comp, "select row_ver from mst_bom where bom_id = ?", Long.class, d[0])).isEqualTo(d[1]);
        }
        postJson(BATCH, items(done.values().toArray(long[][]::new)), admin).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.data[?(@.ok == true)].bomId").value(org.hamcrest.Matchers.hasSize(3)))
                .andExpect(jsonPath("$.data[0].rev").value("A1"));
        done.values().forEach(d -> assertThat(bomStatus(d[0])).isEqualTo("RELEASED"));
        // 이전 확정본은 새 시작일에 마감(확정 1건과 같은 규칙)
        assertThat(accounts.query(comp, "select count(*) from mst_bom where rev = 'A0' and valid_to = ?", Integer.class,
                today.plusDays(1))).isEqualTo(3);
    }

    @Test
    void 변경번호를_비우면_사유는_자동_문구() throws Exception {
        Map<String, long[]> done = replace("");
        assertThat(accounts.query(comp, "select coalesce(change_no, '-') || '|' || change_reason from mst_bom where bom_id = ?",
                String.class, done.get("P1")[0])).isEqualTo("-|일괄 대체: NUT → NUT2");
    }

    @Test
    void 실패한_건만_남고_나머지는_확정된다() throws Exception {
        Map<String, long[]> done = replace("");
        long[] p1 = done.get("P1");
        long[] p2 = done.get("P2");
        long[] p3 = done.get("P3");
        accounts.exec(comp, "delete from mst_bom_line where bom_id = ?", p3[0]);          // 확정 검사 실패(행 없음)
        long released = sources().get(0);                                                   // 이미 확정본(작성 중 아님)
        int relVer = accounts.query(comp, "select row_ver from mst_bom where bom_id = ?", Integer.class, released);
        String body = postJson(BATCH, items(p1, new long[] {p2[0], p2[1] + 5}, p3, new long[] {released, relVer}), admin)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<Map<String, Object>> out = JsonPath.read(body, "$.data");
        assertThat(out).extracting(m -> m.get("ok") + ":" + m.get("code"))
                .containsExactly("true:null", "false:CONFLICT", "false:BOM_RELEASE_CHECK", "false:BOM_NOT_DRAFT");
        assertThat((String) out.get(2).get("message")).contains("행");   // 확정 검사 사유(행 없음)가 문구에
        assertThat(bomStatus(p1[0])).isEqualTo("RELEASED");
        assertThat(bomStatus(p2[0])).isEqualTo("DRAFT");
        assertThat(bomStatus(p3[0])).isEqualTo("DRAFT");
        // 실패한 P2 는 맞는 버전으로 다시 확정할 수 있다
        postJson(BATCH, items(p2), admin).andExpect(jsonPath("$.data[0].ok").value(true));
        // 같은 품목 이전 확정본은 성공한 건만 마감
        assertThat(accounts.query(comp, """
                select count(*) from mst_bom b join mst_item i using (item_id)
                 where b.rev = 'A0' and b.valid_to is not null and i.item_cd in ('P1', 'P2')""", Integer.class)).isEqualTo(2);
        assertThat(accounts.query(comp, """
                select count(*) from mst_bom b join mst_item i using (item_id)
                 where b.rev = 'A0' and b.valid_to is null and i.item_cd = 'P3'""", Integer.class)).isEqualTo(1);
    }

    @Test
    void 기간_겹침으로_가운데_건이_실패해도_뒤_건은_확정되고_실패_건의_변경은_남지_않는다() throws Exception {
        long p2a0 = sources().get(1);
        // P2: 미래 확정본 A9(오늘+5 시작, A0 은 그날 마감) → 그보다 앞 날짜(내일)로 시작하는 작성 중 A1 은 기존 확정 검사에서 BOM_PERIOD_OVERLAP
        accounts.exec(comp, "update mst_bom set valid_to = ? where bom_id = ?", today.plusDays(5), p2a0);
        long a9 = draftOf("P2", "A9", today.plusDays(5));
        accounts.exec(comp, "update mst_bom set status = 'RELEASED', released_at = now(), released_by = 't' where bom_id = ?", a9);
        long p2a1 = draftOf("P2", "A1", today.plusDays(1));
        int p2ver = accounts.query(comp, "select row_ver from mst_bom where bom_id = ?", Integer.class, p2a1);
        // P1·P3 은 일괄 대체로 작성 중
        String ids = sources().get(0) + "," + sources().get(2);
        String rb = postJson("/api/master/bom-replace", "{\"fromItemCd\":\"NUT\",\"toItemCd\":\"NUT2\",\"validFrom\":\""
                + today.plusDays(1) + "\",\"bomIds\":[" + ids + "]}", admin).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long[] p1 = {((Number) JsonPath.read(rb, "$.data[0].bomId")).longValue(), ((Number) JsonPath.read(rb, "$.data[0].rowVer")).longValue()};
        long[] p3 = {((Number) JsonPath.read(rb, "$.data[1].bomId")).longValue(), ((Number) JsonPath.read(rb, "$.data[1].rowVer")).longValue()};
        String body = postJson(BATCH, items(p1, new long[] {p2a1, p2ver}, p3), admin)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<Map<String, Object>> out = JsonPath.read(body, "$.data");
        assertThat(out).extracting(m -> m.get("ok") + ":" + m.get("code"))
                .containsExactly("true:null", "false:BOM_PERIOD_OVERLAP", "true:null");
        assertThat((String) out.get(1).get("message")).contains("A9");          // 겹치는 리비전이 문구에
        assertThat(bomStatus(p1[0])).isEqualTo("RELEASED");
        assertThat(bomStatus(p3[0])).isEqualTo("RELEASED");
        assertThat(bomStatus(p2a1)).isEqualTo("DRAFT");
        // 실패한 건은 세이브포인트로 되돌려 자기 이전 확정본도 건드리지 않았다(A0 마감일·A9 그대로)
        assertThat(accounts.query(comp, """
                select string_agg(b.rev || ':' || b.status || ':' || coalesce(b.valid_to::text, '-'), ',' order by b.rev)
                  from mst_bom b join mst_item i using (item_id) where i.item_cd = 'P2'""", String.class))
                .isEqualTo("A0:RELEASED:" + today.plusDays(5) + ",A1:DRAFT:-,A9:RELEASED:-");
    }

    /** 작성 중 BOM(NUT2 1) — SQL 로 */
    private long draftOf(String parent, String rev, LocalDate from) {
        long id = accounts.query(comp, """
                insert into mst_bom (comp_cd, item_id, rev, valid_from, created_by, updated_by)
                select ?, item_id, ?, ?, 't', 't' from mst_item where item_cd = ? returning bom_id""", Long.class, comp, rev, from, parent);
        accounts.exec(comp, """
                insert into mst_bom_line (comp_cd, bom_id, line_no, item_id, qty, unit_cd, issue_method, created_by, updated_by)
                select ?, ?, 10, item_id, 1, 'EA', 'BACKFLUSH', 't', 't' from mst_item where item_cd = 'NUT2'""", comp, id);
        return id;
    }

    @Test
    void 결과를_본_뒤_다른_사람이_작성_중을_고치면_그_건만_충돌() throws Exception {
        Map<String, long[]> done = replace("");
        // 다른 사용자가 P1 작성 중 머리를 저장(화면 [저장]과 같은 API) → row_ver 증가
        long p1 = done.get("P1")[0];
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/master/boms/" + p1).with(csrf()).with(admin)
                .contentType(MediaType.APPLICATION_JSON).content("{\"itemCd\":\"P1\",\"altNo\":1,\"rev\":\"A1\",\"baseQty\":\"1\",\"validFrom\":\""
                + today.plusDays(1) + "\",\"remark\":\"다른 사람 수정\",\"rowVer\":" + done.get("P1")[1] + "}"))
                .andExpect(status().isOk());
        postJson(BATCH, items(done.get("P1"), done.get("P2")), admin).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].code").value("CONFLICT"))
                .andExpect(jsonPath("$.data[1].ok").value(true));
        assertThat(bomStatus(p1)).isEqualTo("DRAFT");
    }

    @Test
    void 확정_권한_없음_403_비로그인_401() throws Exception {
        Map<String, long[]> done = replace("");
        long u = accounts.newUser(comp, "saver", PW);
        accounts.grant(comp, u, "SAVER", "mst-bom", "SEARCH", "SAVE");   // 저장은 되지만 확정(APPROVE)은 없음(다른 권한 그룹)
        accounts.grant(comp, u, "SAVER", "mst-bom-replace", "SEARCH", "SAVE");
        postJson(BATCH, items(done.get("P1")), accounts.as(comp, u, "saver")).andExpect(status().isForbidden());
        mvc.perform(post(BATCH).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(items(done.get("P1"))))
                .andExpect(status().isUnauthorized());
        assertThat(bomStatus(done.get("P1")[0])).isEqualTo("DRAFT");
    }

    @Test
    void B사_사용자가_A사_BOM_id_를_보내면_없음으로_남고_확정되지_않는다() throws Exception {
        Map<String, long[]> done = replace("");
        String other = accounts.newComp("BRX");
        long bu = accounts.newUser(other, "admin1", PW);
        accounts.grant(other, bu, "MASTER", "mst-bom", "SEARCH", "SAVE", "APPROVE");
        postJson(BATCH, items(done.get("P1")), accounts.as(other, bu, "admin1")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].ok").value(false))
                .andExpect(jsonPath("$.data[0].code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.data[0].itemCd").value(org.hamcrest.Matchers.nullValue()));   // 남의 품목 코드를 비추지 않는다
        assertThat(bomStatus(done.get("P1")[0])).isEqualTo("DRAFT");
    }

    @Test
    void 입력_오류는_400_전부_거부() throws Exception {
        Map<String, long[]> done = replace("");
        long[] p1 = done.get("P1");
        postJson(BATCH, "{\"items\":[]}", admin).andExpect(status().isBadRequest());
        postJson(BATCH, "{}", admin).andExpect(status().isBadRequest());
        postJson(BATCH, items(p1, p1), admin).andExpect(status().isBadRequest());                       // 같은 BOM 두 번
        postJson(BATCH, "{\"items\":[{\"bomId\":" + p1[0] + "}]}", admin).andExpect(status().isBadRequest());   // rowVer 없음
        String many = "{\"items\":[" + IntStream.rangeClosed(1, 1001).mapToObj(i -> "{\"bomId\":" + i + ",\"rowVer\":0}")
                .collect(Collectors.joining(",")) + "]}";
        postJson(BATCH, many, admin).andExpect(status().isBadRequest());
        assertThat(bomStatus(p1[0])).isEqualTo("DRAFT");
    }

    @Test
    void 변경번호_31자는_400_리비전을_만들지_않는다() throws Exception {
        String ids = sources().stream().map(String::valueOf).collect(Collectors.joining(","));
        postJson("/api/master/bom-replace", "{\"fromItemCd\":\"NUT\",\"toItemCd\":\"NUT2\",\"validFrom\":\"" + today.plusDays(1)
                + "\",\"bomIds\":[" + ids + "],\"changeNo\":\"" + "E".repeat(31) + "\"}", admin)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("changeNo"))
                .andExpect(jsonPath("$.errors[0].code").value("TOO_LONG"));
        assertThat(accounts.query(comp, "select count(*) from mst_bom", Integer.class)).isEqualTo(3);
    }
}
