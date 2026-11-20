package io.smarthappy.module.cms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneId;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.platform.common.db.PgErrors;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * CMS 1부 C4 (docs/33 4.5) — 설비·클래스 예비품 연결(검증·고정 칸·사용 중지 = 새 참조만 차단·역방향 검색), 작업지시 사용 예비품
 * (후보·단위 자릿수·완료 뒤 불변), 사용 이력, 직접 SQL, 권한, A사로 B사.
 */
@AutoConfigureMockMvc
class CmsSpareTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";
    private static final String S = "/api/cms/spares";
    private static final String M = "/api/cms/mwos";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    String comp;
    RequestPostProcessor admin;
    String today;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("SP");
        long userId = accounts.newUser(comp, "admin1", PW);
        grantAll(comp, userId);
        admin = accounts.as(comp, userId, "admin1");
        accounts.addPlant(comp, "P1", "1공장");
        long press = equipClass(comp, "PRESS");
        equip(comp, "PRS-01", press);
        equip(comp, "PRS-02", press);
        equip(comp, "WLD-01", equipClass(comp, "WELD"));
        accounts.exec(comp, "insert into mst_phys_asset (comp_cd, asset_cd, asset_type, asset_nm, created_by, updated_by) values (?, 'M-100', 'MOLD', 'm', 't', 't')", comp);
        item(comp, "BRG-6204", "베어링 6204", "EA", "Y");
        item(comp, "OIL-68", "유압유 68", "L", "Y");
        item(comp, "OLD-1", "단종 부품", "EA", "N");
        today = LocalDate.now(ZoneId.of("Asia/Seoul")).toString();
    }

    private void grantAll(String c, long u) {
        accounts.grant(c, u, "CMS", "cms-spare", "SEARCH", "SAVE");
        accounts.grant(c, u, "CMS", "cms-mwo", "SEARCH", "SAVE", "APPROVE", "DELETE");
    }

    private long equipClass(String c, String cd) {
        return accounts.query(c, "insert into mst_equip_class (comp_cd, class_cd, class_nm, created_by, updated_by) values (?, ?, ?, 't', 't') returning class_id",
                Long.class, c, cd, cd);
    }

    private long equip(String c, String cd, long classId) {
        return accounts.query(c, """
                insert into mst_equip (comp_cd, plant_cd, equip_cd, equip_nm, class_id, created_by, updated_by)
                values (?, 'P1', ?, ?, ?, 't', 't') returning equip_id""", Long.class, c, cd, cd + " 설비", classId);
    }

    private long item(String c, String cd, String nm, String unit, String useYn) {
        return accounts.query(c, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, use_yn, created_by, updated_by)
                values (?, ?, ?, 'CON', 'BUY', ?, ?, 't', 't') returning item_id""", Long.class, c, cd, nm, unit, useYn);
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String body) {
        return b.with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private ResultActions postJson(String url, String body) throws Exception {
        return mvc.perform(json(post(url), body).with(admin));
    }

    private ResultActions putJson(String url, String body) throws Exception {
        return mvc.perform(json(put(url), body).with(admin));
    }

    private static long id(ResultActions r) throws Exception {
        MvcResult m = r.andExpect(status().isOk()).andReturn();
        Object v = JsonPath.read(m.getResponse().getContentAsString(), "$.data");
        return v instanceof Number n ? n.longValue() : ((Number) JsonPath.read(m.getResponse().getContentAsString(), "$.data.mwoId")).longValue();
    }

    private long spare(String kind, String target, String item, String qty, String key) throws Exception {
        return id(postJson(S, "{\"targetKind\":\"" + kind + "\",\"targetCd\":\"" + target + "\",\"itemCd\":\"" + item + "\",\"installQty\":\""
                + qty + "\",\"keySpareYn\":\"" + key + "\"}"));
    }

    private long cm(String target) throws Exception {
        String type = target.startsWith("M-") ? "ASSET" : "EQUIP";
        return id(postJson(M, "{\"mwoType\":\"CM\",\"targetType\":\"" + type + "\",\"targetCd\":\"" + target + "\",\"title\":\"정비\"}"));
    }

    private int ver(long mwo) {
        return accounts.query(comp, "select row_ver from cms_mwo where mwo_id = ?", Integer.class, mwo);
    }

    // ---------- 연결 ----------

    @Test
    void 연결_설비와_클래스_검증_단위자릿수_중복_고정칸_사용중지는_새참조만_차단_역방향검색() throws Exception {
        long s1 = spare("EQUIP", "prs-01", "brg-6204", "2", "Y");
        spare("CLASS", "PRESS", "OIL-68", "12.5", "N");
        mvc.perform(get(S + "/" + s1).with(admin)).andExpect(jsonPath("$.data.targetKind").value("EQUIP"))
                .andExpect(jsonPath("$.data.installQtyText").value("2")).andExpect(jsonPath("$.data.unitCd").value("EA"));
        // 검증: 단위 EA(0자리)에 1.5, L(3자리)에 1.2345, 0, 없는 품목·사용 중지 품목·없는 설비·종류 코드
        postJson(S, "{\"targetCd\":\"PRS-02\",\"itemCd\":\"BRG-6204\",\"installQty\":\"1.5\"}").andExpect(jsonPath("$.errors[0].code").value("SCALE"));
        postJson(S, "{\"targetCd\":\"PRS-02\",\"itemCd\":\"OIL-68\",\"installQty\":\"1.2345\"}").andExpect(jsonPath("$.errors[0].code").value("SCALE"));
        postJson(S, "{\"targetCd\":\"PRS-02\",\"itemCd\":\"OIL-68\",\"installQty\":\"0\"}").andExpect(jsonPath("$.errors[0].code").value("POSITIVE"));
        postJson(S, "{\"targetCd\":\"PRS-02\",\"itemCd\":\"OLD-1\",\"installQty\":\"1\"}").andExpect(jsonPath("$.errors[0].code").value("INACTIVE"));
        postJson(S, "{\"targetKind\":\"LINE\",\"targetCd\":\"X\",\"itemCd\":\"NOPE\"}")
                .andExpect(jsonPath("$.errors[*].code").value(containsInAnyOrder("CODE", "NO_SUCH_CODE", "REQUIRED")));
        postJson(S, "{\"targetKind\":\"CLASS\",\"targetCd\":\"NOPE\",\"itemCd\":\"OIL-68\",\"installQty\":\"1\"}")
                .andExpect(jsonPath("$.errors[0].field").value("targetCd"));
        postJson(S, "{\"targetCd\":\"PRS-01\",\"itemCd\":\"BRG-6204\",\"installQty\":\"1\"}").andExpect(jsonPath("$.errors[0].code").value("DUPLICATE"));
        // 같은 품목이라도 설비 연결과 클래스 연결은 따로
        spare("CLASS", "PRESS", "BRG-6204", "4", "N");
        // 고정 칸·수정·낡은 rowVer
        putJson(S + "/" + s1, "{\"targetCd\":\"PRS-02\",\"itemCd\":\"OIL-68\",\"installQty\":\"3\",\"rowVer\":0}")
                .andExpect(jsonPath("$.errors[*].code").value(containsInAnyOrder("FIXED", "FIXED")));
        putJson(S + "/" + s1, "{\"installQty\":\"3\",\"keySpareYn\":\"Y\",\"rowVer\":0}").andExpect(status().isOk());
        putJson(S + "/" + s1, "{\"installQty\":\"4\",\"rowVer\":0}").andExpect(status().isConflict());
        // 품목이 사용 중지되어도 이미 있는 연결은 그대로(수정 가능), 다시 사용(N → Y)은 막는다
        putJson(S + "/" + s1, "{\"installQty\":\"3\",\"keySpareYn\":\"Y\",\"useYn\":\"N\",\"rowVer\":1}").andExpect(status().isOk());
        accounts.exec(comp, "update mst_item set use_yn = 'N' where item_cd = 'BRG-6204'");
        putJson(S + "/" + s1, "{\"installQty\":\"3\",\"keySpareYn\":\"Y\",\"useYn\":\"N\",\"remark\":\"단종 예정\",\"rowVer\":2}").andExpect(status().isOk());
        putJson(S + "/" + s1, "{\"installQty\":\"3\",\"useYn\":\"Y\",\"rowVer\":3}").andExpect(jsonPath("$.errors[0].code").value("INACTIVE"));
        // 역방향: 품목으로 찾으면 그 품목을 쓰는 설비·클래스
        mvc.perform(get(S).param("keyword", "brg").param("useYn", "Y").with(admin)).andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].targetKind").value("CLASS"));
        mvc.perform(get(S).param("keyword", "brg").with(admin)).andExpect(jsonPath("$.data.length()").value(2));   // 사용 여부 조건 없음 = 전체
        mvc.perform(get(S).param("targetKind", "CLASS").with(admin)).andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[*].installQtyText").value(containsInAnyOrder("12.500", "4")));
        mvc.perform(get(S).param("targetKind", "LINE").with(admin)).andExpect(status().isBadRequest());
    }

    // ---------- 작업지시 사용 예비품 ----------

    @Test
    void 작업지시_사용예비품_후보는_설비와_클래스_단위스냅샷_완료뒤_불변_사용이력() throws Exception {
        spare("EQUIP", "PRS-01", "BRG-6204", "2", "Y");
        spare("CLASS", "PRESS", "OIL-68", "20", "N");
        spare("CLASS", "PRESS", "BRG-6204", "4", "N");   // 같은 품목은 설비 연결 쪽 하나만 후보
        spare("CLASS", "WELD", "OIL-68", "1", "N");
        long m = cm("PRS-01");
        mvc.perform(get(M + "/" + m + "/part-options").with(admin)).andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].itemCd").value("BRG-6204")).andExpect(jsonPath("$.data[0].source").value("EQUIP"))
                .andExpect(jsonPath("$.data[1].source").value("CLASS"));
        long am = cm("PRS-02");
        mvc.perform(get(M + "/" + am + "/part-options").with(admin)).andExpect(jsonPath("$.data.length()").value(2));   // 클래스 연결만
        // 추가: 단위 자릿수·0·사용 중지 품목·없는 품목, 후보 밖 품목도 코드로
        postJson(M + "/" + m + "/parts", "{\"itemCd\":\"BRG-6204\",\"qty\":\"1.5\"}").andExpect(jsonPath("$.errors[0].code").value("SCALE"));
        postJson(M + "/" + m + "/parts", "{\"itemCd\":\"OIL-68\",\"qty\":\"0\"}").andExpect(jsonPath("$.errors[0].code").value("POSITIVE"));
        postJson(M + "/" + m + "/parts", "{\"itemCd\":\"OLD-1\",\"qty\":\"1\"}").andExpect(jsonPath("$.errors[0].code").value("INACTIVE"));
        postJson(M + "/" + m + "/parts", "{\"itemCd\":\"NOPE\",\"qty\":\"1\"}").andExpect(jsonPath("$.errors[0].code").value("NO_SUCH_CODE"));
        long p1 = id(postJson(M + "/" + m + "/parts", "{\"itemCd\":\"brg-6204\",\"qty\":\"2\"}"));
        id(postJson(M + "/" + m + "/parts", "{\"itemCd\":\"OIL-68\",\"qty\":\"3.25\",\"remark\":\"보충\"}"));
        long p3 = id(postJson(M + "/" + m + "/parts", "{\"itemCd\":\"OIL-68\",\"qty\":\"1\"}"));
        postJson(M + "/" + m + "/parts/" + p3 + "/delete", "").andExpect(status().isOk());
        mvc.perform(get(M + "/" + m + "/parts").with(admin)).andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[1].qtyText").value("3.250")).andExpect(jsonPath("$.data[1].unitCd").value("L"));
        // 품목 단위가 뒤에 바뀌어도 기록 단위는 스냅샷
        accounts.exec(comp, "update mst_item set unit_cd = 'ML' where item_cd = 'OIL-68'");
        mvc.perform(get(M + "/" + m + "/parts").with(admin)).andExpect(jsonPath("$.data[1].unitCd").value("L"));
        // 착수 → 완료 → 추가·삭제 불가(API·직접 SQL)
        postJson(M + "/" + m + "/start", "{\"rowVer\":" + ver(m) + "}").andExpect(status().isOk());
        postJson(M + "/" + m + "/parts", "{\"itemCd\":\"BRG-6204\",\"qty\":\"1\"}").andExpect(status().isOk());   // WIP 는 된다
        postJson(M + "/" + m + "/finish", "{\"title\":\"정비\",\"workText\":\"베어링 교체\",\"rowVer\":" + ver(m) + "}").andExpect(status().isOk());
        postJson(M + "/" + m + "/parts", "{\"itemCd\":\"BRG-6204\",\"qty\":\"1\"}").andExpect(jsonPath("$.code").value("CMS_MWO_STATE"));
        postJson(M + "/" + m + "/parts/" + p1 + "/delete", "").andExpect(jsonPath("$.code").value("CMS_MWO_STATE"));
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, "delete from cms_mwo_part where part_id = ?", p1))))
                .contains("ck_cms_mwo_part_status");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, "update cms_mwo_part set qty = 9 where part_id = ?", p1))))
                .contains("ck_cms_mwo_part_no_update");
        // 사용 이력: 설비·클래스·품목, 기간 필수
        mvc.perform(get(S + "/usage").param("from", today).param("to", today).param("equipCd", "PRS-01").with(admin))
                .andExpect(jsonPath("$.data.length()").value(3));
        mvc.perform(get(S + "/usage").param("from", today).param("to", today).param("classCd", "PRESS").param("itemCd", "BRG-6204").with(admin))
                .andExpect(jsonPath("$.data.length()").value(2)).andExpect(jsonPath("$.data[0].mwoNo").exists());
        mvc.perform(get(S + "/usage").param("from", today).param("to", today).param("classCd", "WELD").with(admin))
                .andExpect(jsonPath("$.data.length()").value(0));
        mvc.perform(get(S + "/usage").param("from", today).param("to", today).param("itemCd", "NOPE").with(admin))
                .andExpect(jsonPath("$.data.length()").value(0));
        mvc.perform(get(S + "/usage").with(admin)).andExpect(status().isBadRequest());
        // 취소된 작업지시에도 추가 불가
        long c = cm("PRS-02");
        postJson(M + "/" + c + "/cancel", "{\"rowVer\":0,\"reason\":\"중복\"}").andExpect(status().isOk());
        postJson(M + "/" + c + "/parts", "{\"itemCd\":\"BRG-6204\",\"qty\":\"1\"}").andExpect(jsonPath("$.code").value("CMS_MWO_STATE"));
        // 금형 작업지시: 후보 없음(설비가 없다), 코드로는 넣을 수 있음
        long mm = cm("M-100");
        mvc.perform(get(M + "/" + mm + "/part-options").with(admin)).andExpect(jsonPath("$.data.length()").value(0));
    }

    // ---------- 직접 SQL · 권한 · A사로 B사 ----------

    @Test
    void 직접SQL_연결_고정칸_삭제_다른단위_권한() throws Exception {
        long s1 = spare("EQUIP", "PRS-01", "BRG-6204", "2", "N");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "update cms_equip_spare set item_id = (select item_id from mst_item where item_cd = 'OIL-68') where spare_id = ?", s1))))
                .contains("ck_cms_equip_spare_fixed");
        assertThat(PgErrors.sqlState(catchThrowable(() -> accounts.exec(comp, "delete from cms_equip_spare where spare_id = ?", s1)))).contains("42501");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, """
                insert into cms_equip_spare (comp_cd, equip_id, equip_class_id, item_id, install_qty, created_by, updated_by)
                select ?, e.equip_id, e.class_id, i.item_id, 1, 't', 't' from mst_equip e, mst_item i
                 where e.equip_cd = 'PRS-02' and i.item_cd = 'OIL-68'""", comp)))).contains("ck_cms_equip_spare_target");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, """
                insert into cms_equip_spare (comp_cd, equip_id, item_id, install_qty, created_by, updated_by)
                select ?, e.equip_id, i.item_id, 1, 't', 't' from mst_equip e, mst_item i
                 where e.equip_cd = 'PRS-02' and i.item_cd = 'OLD-1'""", comp)))).contains("fk_cms_equip_spare_item_comp");
        long m = cm("PRS-01");
        // 품목 단위와 다른 단위로 기록
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, """
                insert into cms_mwo_part (comp_cd, mwo_id, item_id, qty, unit_cd, created_by, updated_by)
                select ?, ?, item_id, 1, 'KG', 't', 't' from mst_item where item_cd = 'BRG-6204'""", comp, m)))).contains("fk_cms_mwo_part_item_comp");
        // 권한: 예비품 조회만 → 저장 403 / 작업지시 조회만 → 예비품 추가 403
        long viewer = accounts.newUser(comp, "viewer1", PW);
        accounts.grant(comp, viewer, "VIEW", "cms-spare", "SEARCH");
        accounts.grant(comp, viewer, "VIEW", "cms-mwo", "SEARCH");
        RequestPostProcessor vw = accounts.as(comp, viewer, "viewer1");
        mvc.perform(json(post(S), "{\"targetCd\":\"PRS-02\",\"itemCd\":\"OIL-68\",\"installQty\":\"1\"}").with(vw)).andExpect(status().isForbidden());
        mvc.perform(json(post(M + "/" + m + "/parts"), "{\"itemCd\":\"OIL-68\",\"qty\":\"1\"}").with(vw)).andExpect(status().isForbidden());
        mvc.perform(get(M + "/" + m + "/parts").with(vw)).andExpect(status().isOk());
    }

    @Test
    void B사로_A사_연결_작업지시예비품_이력_막힘_A사품목을_B사작업지시에_못붙임() throws Exception {
        long s1 = spare("EQUIP", "PRS-01", "BRG-6204", "2", "Y");
        long m = cm("PRS-01");
        long p1 = id(postJson(M + "/" + m + "/parts", "{\"itemCd\":\"BRG-6204\",\"qty\":\"1\"}"));
        long aItem = accounts.query(comp, "select item_id from mst_item where item_cd = 'BRG-6204'", Long.class);
        String other = accounts.newComp("SB");
        long bu = accounts.newUser(other, "admin1", PW);
        grantAll(other, bu);
        RequestPostProcessor b = accounts.as(other, bu, "admin1");
        accounts.addPlant(other, "P1", "B");
        long bEquip = equip(other, "B-01", equipClass(other, "PRESS"));
        long bm = id(mvc.perform(json(post(M), "{\"mwoType\":\"CM\",\"targetCd\":\"B-01\",\"title\":\"b\"}").with(b)));
        mvc.perform(get(S).with(b)).andExpect(jsonPath("$.data.length()").value(0));
        mvc.perform(get(S + "/" + s1).with(b)).andExpect(status().isNotFound());
        mvc.perform(json(put(S + "/" + s1), "{\"installQty\":\"9\",\"rowVer\":0}").with(b)).andExpect(status().isNotFound());
        mvc.perform(get(M + "/" + m + "/parts").with(b)).andExpect(status().isNotFound());
        mvc.perform(json(post(M + "/" + m + "/parts"), "{\"itemCd\":\"X\",\"qty\":\"1\"}").with(b)).andExpect(status().isNotFound());
        mvc.perform(json(post(M + "/" + m + "/parts/" + p1 + "/delete"), "").with(b)).andExpect(status().isNotFound());
        mvc.perform(get(S + "/usage").param("from", today).param("to", today).with(b)).andExpect(jsonPath("$.data.length()").value(0));
        // B사 화면에서 A사 품목 코드 → 없음
        mvc.perform(json(post(M + "/" + bm + "/parts"), "{\"itemCd\":\"BRG-6204\",\"qty\":\"1\"}").with(b))
                .andExpect(jsonPath("$.errors[0].code").value("NO_SUCH_CODE"));
        mvc.perform(json(post(S), "{\"targetCd\":\"B-01\",\"itemCd\":\"BRG-6204\",\"installQty\":\"1\"}").with(b))
                .andExpect(jsonPath("$.errors[0].code").value("NO_SUCH_CODE"));
        // 직접 SQL(B사): A사 품목을 B사 작업지시·연결에, B사 사용 기록을 A사 작업지시에
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(other, """
                insert into cms_mwo_part (comp_cd, mwo_id, item_id, qty, unit_cd, created_by, updated_by) values (?, ?, ?, 1, 'EA', 't', 't')""",
                other, bm, aItem)))).contains("fk_cms_mwo_part_item_comp");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(other, """
                insert into cms_equip_spare (comp_cd, equip_id, item_id, install_qty, created_by, updated_by) values (?, ?, ?, 1, 't', 't')""",
                other, bEquip, aItem)))).contains("fk_cms_equip_spare_item_comp");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(other, """
                insert into cms_mwo_part (comp_cd, mwo_id, item_id, qty, unit_cd, created_by, updated_by) values (?, ?, ?, 1, 'EA', 't', 't')""",
                other, m, aItem)))).contains("ck_cms_mwo_part_status");   // A사 작업지시는 B사에 안 보여 "열린 작업지시 없음"
        assertThat(accounts.exec(other, "delete from cms_mwo_part")).isZero();
        assertThat(accounts.query(comp, "select count(*) from cms_mwo_part", Integer.class)).isEqualTo(1);
    }
}
