package io.smarthappy.module.master.equip;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

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
 * 설비·물리 자산·설치 이력 (P1 4b, docs/19) — 설치 위치 규칙, 설치 원장(한 번 닫기·무효만, 두 곳 동시 금지),
 * 사용 중지 연쇄 규칙, 직접 SQL, A/B사, 권한.
 */
@AutoConfigureMockMvc
class AssetTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";
    private static final DateTimeFormatter MIN = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    String comp;
    RequestPostProcessor admin;
    long line;
    long cell;
    long area;
    long wh;
    long weldClass;

    @BeforeEach
    void setUp() throws Exception {
        comp = accounts.newComp("AS");
        long userId = accounts.newUser(comp, "admin1", PW);
        accounts.grant(comp, userId, "MASTER", "mst-equip", "SEARCH", "SAVE", "DELETE", "EXCEL");
        accounts.grant(comp, userId, "MASTER", "mst-asset", "SEARCH", "SAVE", "DELETE", "EXCEL");
        admin = accounts.as(comp, userId, "admin1");
        accounts.addPlant(comp, "P1", "1공장");
        accounts.addPlant(comp, "P2", "2공장");
        area = node("P1", null, "AREA", "BODY");
        line = node("P1", area, "LINE", "L1");
        cell = node("P1", line, "CELL", "C1");
        wh = node("P1", null, "WH", "WH1");
        weldClass = id(postJson("/api/master/equip-classes", "{\"classCd\":\"weld\",\"classNm\":\"용접기\"}"));
        accounts.exec(comp, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, created_by, updated_by)
                values (?, 'FG1', '제품', 'FG', 'MAKE', 'EA', 't', 't')""", comp);
    }

    private long node(String plant, Long parent, String type, String cd) {
        return accounts.query(comp, """
                insert into mst_equip_hier (comp_cd, plant_cd, parent_id, node_type, node_cd, node_nm, created_by, updated_by)
                values (?, ?, ?, ?, ?, ?, 't', 't') returning node_id""", Long.class, comp, plant, parent, type, cd, cd);
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String body) {
        return b.with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private ResultActions postJson(String url, String body) throws Exception {
        return mvc.perform(json(post(url), body).with(admin));
    }

    private static long id(ResultActions r) throws Exception {
        MvcResult m = r.andExpect(status().isOk()).andReturn();
        return ((Number) JsonPath.read(m.getResponse().getContentAsString(), "$.data")).longValue();
    }

    private ResultActions equip(String cd, Long nodeId) throws Exception {
        return postJson("/api/master/equips", "{\"plantCd\":\"P1\",\"equipCd\":\"" + cd + "\",\"equipNm\":\"" + cd
                + " 설비\",\"classId\":" + weldClass + ",\"nodeId\":" + nodeId + "}");
    }

    private long asset(String cd, String type) throws Exception {
        return id(postJson("/api/master/assets", "{\"assetCd\":\"" + cd + "\",\"assetType\":\"" + type + "\",\"assetNm\":\""
                + cd + " 자산\",\"itemCd\":\"fg1\",\"lifeShots\":\"300,000\"}"));
    }

    private static String at(int minutesAgo) {
        return LocalDateTime.now(ZoneId.of("Asia/Seoul")).minusMinutes(minutesAgo).format(MIN);
    }

    // ---------- 설비 ----------

    @Test
    void 설비는_사용중인_라인_셀에만_위치_미설치_허용_경로_표시() throws Exception {
        long e1 = id(equip("SPOT-01", cell));
        id(equip("SPOT-02", line));
        id(equip("SPARE-01", null));                                          // 보관
        equip("X1", area).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("PLACE"));
        equip("X2", wh).andExpect(jsonPath("$.errors[0].code").value("PLACE"));
        equip("spot-01", null).andExpect(jsonPath("$.errors[0].code").value("DUPLICATE"));
        long p2 = node("P2", null, "AREA", "A2");
        long p2line = node("P2", p2, "LINE", "L9");
        equip("X3", p2line).andExpect(jsonPath("$.errors[0].code").value("NOT_FOUND"));   // 다른 공장 노드
        mvc.perform(get("/api/master/equips/" + e1).with(admin))
                .andExpect(jsonPath("$.data.nodePath").value("P1/BODY/L1/C1"))
                .andExpect(jsonPath("$.data.classCd").value("WELD"));
        mvc.perform(get("/api/master/equips").param("plantCd", "P1").with(admin)).andExpect(jsonPath("$.total").value(3));
        mvc.perform(get("/api/master/equips/places").param("plantCd", "P1").with(admin))
                .andExpect(jsonPath("$.data[*].path").value(org.hamcrest.Matchers.contains("P1/BODY/L1", "P1/BODY/L1/C1")));
        // 설비가 걸린 셀은 사용 중지 불가(장비 계층 트리거)
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "update mst_equip_hier set use_yn = 'N' where node_id = ?", cell)))).contains("ck_mst_equip_hier_equip_use");
    }

    @Test
    void 클래스_중복_사용중_클래스_사용중지_거부() throws Exception {
        postJson("/api/master/equip-classes", "{\"classCd\":\"WELD\",\"classNm\":\"x\"}")
                .andExpect(jsonPath("$.errors[0].code").value("DUPLICATE"));
        id(equip("SPOT-01", cell));
        mvc.perform(json(put("/api/master/equip-classes/" + weldClass), "{\"classCd\":\"WELD\",\"classNm\":\"용접기\",\"useYn\":\"N\",\"rowVer\":0}")
                .with(admin)).andExpect(jsonPath("$.errors[0].code").value("HAS_EQUIP"));
    }

    // ---------- 설치 이력 ----------

    @Test
    void 설치_해체_재설치_이력_지금_설치중이면_해체먼저() throws Exception {
        long e1 = id(equip("PRESS-01", line));
        id(equip("PRESS-02", line));
        long mold = asset("MOLD-A", "MOLD");
        long i1 = id(postJson("/api/master/assets/" + mold + "/install", "{\"equipCd\":\"press-01\",\"installedAt\":\"" + at(120) + "\"}"));
        mvc.perform(get("/api/master/assets/" + mold).with(admin))
                .andExpect(jsonPath("$.data.curEquipCd").value("PRESS-01"))
                .andExpect(jsonPath("$.data.lifeShots").value(300000));
        mvc.perform(get("/api/master/equips/" + e1).with(admin)).andExpect(jsonPath("$.data.assetCnt").value(1));
        // 다른 설비로 바로 설치 → 해체 먼저
        postJson("/api/master/assets/" + mold + "/install", "{\"equipCd\":\"PRESS-02\"}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ASSET_INSTALLED"));
        // 해체: 설치 전 시각·미래는 거부
        postJson("/api/master/assets/installs/" + i1 + "/remove", "{\"removedAt\":\"" + at(180) + "\"}")
                .andExpect(jsonPath("$.errors[0].code").value("BEFORE_INSTALL"));
        postJson("/api/master/assets/installs/" + i1 + "/remove", "{\"removedAt\":\"" + at(-60) + "\"}")
                .andExpect(jsonPath("$.errors[0].code").value("FUTURE"));
        postJson("/api/master/assets/installs/" + i1 + "/remove", "{\"removedAt\":\"" + at(60) + "\"}").andExpect(status().isOk());
        postJson("/api/master/assets/installs/" + i1 + "/remove", "{}").andExpect(status().isConflict());   // 이미 닫힘
        id(postJson("/api/master/assets/" + mold + "/install", "{\"equipCd\":\"PRESS-02\"}"));
        mvc.perform(get("/api/master/assets/" + mold + "/installs").with(admin))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].equipCd").value("PRESS-02"))
                .andExpect(jsonPath("$.data[1].removedAt").isNotEmpty());
    }

    @Test
    void 과거_기간과_겹치는_설치는_OVERLAP_무효로_정정하면_다시_가능() throws Exception {
        id(equip("PRESS-01", line));
        long mold = asset("MOLD-A", "MOLD");
        long i1 = id(postJson("/api/master/assets/" + mold + "/install", "{\"equipCd\":\"PRESS-01\",\"installedAt\":\"" + at(300) + "\"}"));
        postJson("/api/master/assets/installs/" + i1 + "/remove", "{\"removedAt\":\"" + at(100) + "\"}").andExpect(status().isOk());
        // 해체한 기간 안으로 되돌아가 설치(열린 구간) → 앞 기간과 겹침
        postJson("/api/master/assets/" + mold + "/install", "{\"equipCd\":\"PRESS-01\",\"installedAt\":\"" + at(200) + "\"}")
                .andExpect(jsonPath("$.errors[0].code").value("OVERLAP"));
        // 앞 행이 잘못 넣은 것이었다면 무효 → 그 기간이 비어 다시 설치 가능
        postJson("/api/master/assets/installs/" + i1 + "/void", "{\"reason\":\"설비 코드 착오\"}").andExpect(status().isOk());
        id(postJson("/api/master/assets/" + mold + "/install", "{\"equipCd\":\"PRESS-01\",\"installedAt\":\"" + at(200) + "\"}"));
        postJson("/api/master/assets/installs/" + i1 + "/void", "{\"reason\":\"두 번\"}").andExpect(status().isConflict());
        postJson("/api/master/assets/installs/" + i1 + "/void", "{}").andExpect(status().isBadRequest());
    }

    @Test
    void 설치_원장은_직접SQL로_고치거나_지울수_없다() throws Exception {
        id(equip("PRESS-01", line));
        long mold = asset("MOLD-A", "MOLD");
        long i1 = id(postJson("/api/master/assets/" + mold + "/install", "{\"equipCd\":\"PRESS-01\"}"));
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "update mst_asset_install set installed_at = installed_at - interval '1 day' where install_id = ?", i1))))
                .contains("ck_mst_asset_install_ledger");
        assertThat(PgErrors.sqlState(catchThrowable(() -> accounts.exec(comp, "delete from mst_asset_install where install_id = ?", i1))))
                .contains("42501");
        // 같은 자산을 다른 설비에 동시에(직접 SQL) — 배제 제약
        long e2 = id(equip("PRESS-02", line));
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, """
                insert into mst_asset_install (comp_cd, asset_id, equip_id, installed_at, installed_by)
                values (?, ?, ?, now(), 't')""", comp, mold, e2)))).contains("ex_mst_asset_install");
        // 닫은 뒤 다시 열기 금지
        accounts.exec(comp, "update mst_asset_install set removed_at = now(), removed_by = 't' where install_id = ?", i1);
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "update mst_asset_install set removed_at = null where install_id = ?", i1)))).contains("ck_mst_asset_install_ledger");
    }

    @Test
    void 설치중이면_자산_설비_사용중지_거부_해체하면_가능() throws Exception {
        long e1 = id(equip("PRESS-01", line));
        long mold = asset("MOLD-A", "MOLD");
        long i1 = id(postJson("/api/master/assets/" + mold + "/install", "{\"equipCd\":\"PRESS-01\",\"installedAt\":\"" + at(30) + "\"}"));
        postJson("/api/master/assets/delete", "{\"items\":[{\"id\":" + mold + ",\"rowVer\":0}]}")
                .andExpect(jsonPath("$.errors[0].code").value("INSTALLED"));
        postJson("/api/master/equips/delete", "{\"items\":[{\"id\":" + e1 + ",\"rowVer\":0}]}")
                .andExpect(jsonPath("$.errors[0].code").value("HAS_ASSET"));
        postJson("/api/master/assets/installs/" + i1 + "/remove", "{}").andExpect(status().isOk());
        postJson("/api/master/assets/delete", "{\"items\":[{\"id\":" + mold + ",\"rowVer\":0}]}").andExpect(status().isOk());
        postJson("/api/master/equips/delete", "{\"items\":[{\"id\":" + e1 + ",\"rowVer\":0}]}").andExpect(status().isOk());
        // 사용 중지 자산·설비에는 설치 불가
        postJson("/api/master/assets/" + mold + "/install", "{\"equipCd\":\"PRESS-01\"}")
                .andExpect(jsonPath("$.errors[*].code").value(org.hamcrest.Matchers.hasItem("INACTIVE")));
    }

    @Test
    void 자산_검증_수명_교정주기_다음교정일_없는품목() throws Exception {
        postJson("/api/master/assets", "{\"assetCd\":\"G-1\",\"assetType\":\"GAUGE\",\"assetNm\":\"버니어\",\"calibCycleDays\":365,"
                + "\"lastCalibDate\":\"2026-01-10\"}").andExpect(status().isOk());
        mvc.perform(get("/api/master/assets").param("assetType", "GAUGE").with(admin))
                .andExpect(jsonPath("$.list[0].nextCalibDate").value("2027-01-10"));
        postJson("/api/master/assets", "{\"assetCd\":\"x y\",\"assetType\":\"TOOL\",\"assetNm\":\"\",\"itemCd\":\"NOPE\",\"lifeShots\":\"-1\","
                + "\"calibCycleDays\":0,\"lastCalibDate\":\"2999-01-01\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.length()").value(7));
    }

    @Test
    void B사_사용자는_A사_설비_자산을_못보고_설치도_못한다_권한() throws Exception {
        long e1 = id(equip("PRESS-01", line));
        long mold = asset("MOLD-A", "MOLD");
        String other = accounts.newComp("AS");
        long bUser = accounts.newUser(other, "admin1", PW);
        accounts.grant(other, bUser, "MASTER", "mst-equip", "SEARCH", "SAVE");
        accounts.grant(other, bUser, "MASTER", "mst-asset", "SEARCH", "SAVE", "DELETE");
        accounts.addPlant(other, "P1", "B사 공장");
        RequestPostProcessor b = accounts.as(other, bUser, "admin1");
        mvc.perform(get("/api/master/equips/" + e1).with(b)).andExpect(status().isNotFound());
        mvc.perform(get("/api/master/assets").with(b)).andExpect(jsonPath("$.total").value(0));
        mvc.perform(json(post("/api/master/assets/" + mold + "/install"), "{\"equipCd\":\"PRESS-01\"}").with(b))
                .andExpect(status().isNotFound());
        // B사 설비를 A사 노드(line) 에 — 다른 회사 노드
        long bClass = id(mvc.perform(json(post("/api/master/equip-classes"), "{\"classCd\":\"W\",\"classNm\":\"w\"}").with(b)));
        mvc.perform(json(post("/api/master/equips"), "{\"plantCd\":\"P1\",\"equipCd\":\"B1\",\"equipNm\":\"b\",\"classId\":" + bClass
                + ",\"nodeId\":" + line + "}").with(b)).andExpect(jsonPath("$.errors[0].code").value("NOT_FOUND"));
        // A사 클래스 id 로 B사 설비
        mvc.perform(json(post("/api/master/equips"), "{\"plantCd\":\"P1\",\"equipCd\":\"B2\",\"equipNm\":\"b\",\"classId\":" + weldClass
                + "}").with(b)).andExpect(jsonPath("$.errors[0].code").value("INACTIVE"));
        // 직접 SQL: B사 범위에서 A사 자산·설비로 설치 행
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(other, """
                insert into mst_asset_install (comp_cd, asset_id, equip_id, installed_at, installed_by)
                values (?, ?, ?, now(), 't')""", other, mold, e1)))).contains("fk_mst_asset_install_comp");
        // 권한: 자산 조회만 → 설치 403, 무효는 DELETE
        long reader = accounts.newUser(comp, "reader1", PW);
        accounts.grant(comp, reader, "READER", "mst-asset", "SEARCH", "SAVE");
        RequestPostProcessor r = accounts.as(comp, reader, "reader1");
        long i1 = id(mvc.perform(json(post("/api/master/assets/" + mold + "/install"), "{\"equipCd\":\"PRESS-01\"}").with(r)));
        mvc.perform(json(post("/api/master/assets/installs/" + i1 + "/void"), "{\"reason\":\"x\"}").with(r)).andExpect(status().isForbidden());
        mvc.perform(get("/api/master/equips").with(r)).andExpect(status().isForbidden());
        mvc.perform(get("/api/master/assets/targets").with(r)).andExpect(status().isOk());
    }
}
