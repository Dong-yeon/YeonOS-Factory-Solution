package io.smarthappy.module.master.equip;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.platform.common.db.PgErrors;
import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * QA P1 4b — 설비·설비 클래스·물리 자산·설치 이력 이상 사용 (docs/qa/equip-4.md). TESTING.md "P1 4b" 표에 없는 것만:
 * 다시 사용 때 위치·클래스 규칙, 사용 중지 품목 참조, 설치 원장 시간 경계(같은 시각·마이크로초·허용 오차), 원장 열 변조,
 * 동시 설치·동시 사용 중지, 모든 API 의 A사로 B사 id, 권한 조합, 엑셀, 변경 이력.
 * 결함은 고친 뒤의 기대 동작으로 쓰고 {@code @Disabled("결함n: …")} — 수정은 개발 세션.
 */
@AutoConfigureMockMvc
class AssetAbuseTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";
    private static final DateTimeFormatter MIN = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    @Autowired
    TenantRunner runner;

    @Autowired
    JdbcTemplate jdbc;

    String comp;
    RequestPostProcessor admin;
    long area;
    long line;
    long cell;
    long weldClass;

    @BeforeEach
    void setUp() throws Exception {
        base = LocalDateTime.now(SEOUL);
        comp = accounts.newComp("AS");
        long userId = accounts.newUser(comp, "admin1", PW);
        accounts.grant(comp, userId, "MASTER", "mst-equip", "SEARCH", "SAVE", "DELETE", "EXCEL");
        accounts.grant(comp, userId, "MASTER", "mst-asset", "SEARCH", "SAVE", "DELETE", "EXCEL");
        admin = accounts.as(comp, userId, "admin1");
        accounts.addPlant(comp, "P1", "1공장");
        area = node(null, "AREA", "BODY");
        line = node(area, "LINE", "L1");
        cell = node(line, "CELL", "C1");
        weldClass = id(postJson("/api/master/equip-classes", "{\"classCd\":\"WELD\",\"classNm\":\"용접기\"}"));
        accounts.exec(comp, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, created_by, updated_by)
                values (?, 'FG1', '제품', 'FG', 'MAKE', 'EA', 't', 't')""", comp);
    }

    private long node(Long parent, String type, String cd) {
        return accounts.query(comp, """
                insert into mst_equip_hier (comp_cd, plant_cd, parent_id, node_type, node_cd, node_nm, created_by, updated_by)
                values (?, 'P1', ?, ?, ?, ?, 't', 't') returning node_id""", Long.class, comp, parent, type, cd, cd);
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
        return ((Number) JsonPath.read(m.getResponse().getContentAsString(), "$.data")).longValue();
    }

    private String equipBody(String cd, Long nodeId, long classId, String useYn, Integer rowVer) {
        return "{\"plantCd\":\"P1\",\"equipCd\":\"" + cd + "\",\"equipNm\":\"" + cd + " 설비\",\"classId\":" + classId
                + ",\"nodeId\":" + nodeId + ",\"useYn\":\"" + useYn + "\",\"rowVer\":" + rowVer + "}";
    }

    private long equip(String cd, Long nodeId) throws Exception {
        return id(postJson("/api/master/equips", equipBody(cd, nodeId, weldClass, "Y", null)));
    }

    private long asset(String cd, String type) throws Exception {
        return id(postJson("/api/master/assets", "{\"assetCd\":\"" + cd + "\",\"assetType\":\"" + type + "\",\"assetNm\":\""
                + cd + " 자산\"}"));
    }

    private int ver(String table, String idCol, long id) {
        return accounts.query(comp, "select row_ver from " + table + " where " + idCol + " = ?", Integer.class, id);
    }

    /** 시험마다 한 번 정한 기준 시각 — 호출마다 now() 를 읽으면 분 경계에서 at(60)·at(61) 이 같은 분이 된다 */
    private LocalDateTime base;

    private String at(int minutesAgo) {
        return base.minusMinutes(minutesAgo).format(MIN);
    }

    private ResultActions install(long assetId, String equipCd, String when) throws Exception {
        return postJson("/api/master/assets/" + assetId + "/install",
                "{\"equipCd\":\"" + equipCd + "\"" + (when == null ? "" : ",\"installedAt\":\"" + when + "\"") + "}");
    }

    private ResultActions remove(long installId, String when) throws Exception {
        return postJson("/api/master/assets/installs/" + installId + "/remove", when == null ? "{}" : "{\"removedAt\":\"" + when + "\"}");
    }

    private CompletableFuture<Void> holdTx(Runnable work, CountDownLatch done, long holdMs) {
        return CompletableFuture.runAsync(() -> runner.run(comp, () -> {
            work.run();
            done.countDown();
            try {
                Thread.sleep(holdMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
    }

    private CompletableFuture<MvcResult> async(MockHttpServletRequestBuilder req) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return mvc.perform(req).andReturn();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
    }

    // =====================================================================
    // 1. 사용 중지 연쇄 — 다시 사용할 때
    // =====================================================================

    @Test
    void 결함6_사용중지된_셀에_있는_설비를_다시_사용하면_거부() throws Exception {
        long e1 = equip("SPOT-01", cell);
        postJson("/api/master/equips/delete", "{\"items\":[{\"id\":" + e1 + ",\"rowVer\":0}]}").andExpect(status().isOk());
        accounts.exec(comp, "update mst_equip_hier set use_yn = 'N' where node_id = ?", cell);   // 이제 걸린 사용 중 설비가 없음
        putJson("/api/master/equips/" + e1, equipBody("SPOT-01", cell, weldClass, "Y", ver("mst_equip", "equip_id", e1)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("nodeId"));
    }

    @Test
    void 결함6_사용중지된_클래스의_설비를_다시_사용하면_거부() throws Exception {
        long e1 = equip("SPOT-01", cell);
        postJson("/api/master/equips/delete", "{\"items\":[{\"id\":" + e1 + ",\"rowVer\":0}]}").andExpect(status().isOk());
        putJson("/api/master/equip-classes/" + weldClass, "{\"classCd\":\"WELD\",\"classNm\":\"용접기\",\"useYn\":\"N\",\"rowVer\":0}")
                .andExpect(status().isOk());
        putJson("/api/master/equips/" + e1, equipBody("SPOT-01", cell, weldClass, "Y", ver("mst_equip", "equip_id", e1)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("classId"));
    }

    @Test
    void 결함7_사용중지_품목을_자산_대상품목으로_새로_지정하면_거부() throws Exception {
        accounts.exec(comp, "update mst_item set use_yn = 'N' where item_cd = 'FG1'");
        postJson("/api/master/assets", "{\"assetCd\":\"M1\",\"assetType\":\"MOLD\",\"assetNm\":\"m\",\"itemCd\":\"FG1\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("itemCd"));
    }

    // =====================================================================
    // 2. 설치 원장 — 시간 경계
    // =====================================================================

    @Test
    void 시간경계_같은_분에_해체하고_다른설비에_재설치_1분_앞이면_OVERLAP() throws Exception {
        equip("PRESS-01", line);
        equip("PRESS-02", line);
        long mold = asset("MOLD-A", "MOLD");
        long i1 = id(install(mold, "PRESS-01", at(120)));
        remove(i1, at(60)).andExpect(status().isOk());
        install(mold, "PRESS-02", at(61)).andExpect(jsonPath("$.errors[0].code").value("OVERLAP"));
        long i2 = id(install(mold, "PRESS-02", at(60)));                               // [설치, 해체) 반열림 — 같은 시각 허용
        assertThat(i2).isGreaterThan(i1);
        // 같은 시각 해체(= 설치 시각)는 BEFORE_INSTALL
        remove(i2, at(60)).andExpect(jsonPath("$.errors[0].code").value("BEFORE_INSTALL"));
    }

    @Test
    void 시간경계_입력형식_초_나노초_오프셋_2000년_미래허용오차() throws Exception {
        equip("PRESS-01", line);
        long m1 = asset("M1", "MOLD");
        // 관찰: 형식 설명은 yyyy-MM-ddTHH:mm 이지만 초·나노초도 받는다(DB 와 같은 µs 로 자름)
        long i1 = id(install(m1, "PRESS-01", LocalDateTime.now(SEOUL).minusHours(3).withNano(123456789).toString()));
        assertThat(accounts.query(comp, "select extract(microseconds from installed_at)::bigint % 1000000 from mst_asset_install where install_id = ?",
                Long.class, i1)).isEqualTo(123456L);                                         // 123456.789 µs → 서버가 µs 로 자름(QA #8)
        install(m1, "PRESS-01", "2026-10-19T10:00+09:00").andExpect(status().isBadRequest());
        install(m1, "PRESS-01", "2026-10-19 10:00").andExpect(jsonPath("$.errors[0].code").value("FORMAT"));
        long m2 = asset("M2", "MOLD");
        install(m2, "PRESS-01", "1999-12-31T23:59").andExpect(jsonPath("$.errors[0].code").value("FORMAT"));
        install(m2, "PRESS-01", "2000-01-01T00:00").andExpect(status().isOk());
        long m3 = asset("M3", "MOLD");
        install(m3, "PRESS-01", at(-10)).andExpect(jsonPath("$.errors[0].code").value("FUTURE"));
        install(m3, "PRESS-01", "+10000-01-01T00:00").andExpect(status().isBadRequest());
    }

    @Test
    void 결함8_마이크로초_미만_차이_해체는_BEFORE_INSTALL() throws Exception {
        equip("PRESS-01", line);
        long mold = asset("MOLD-A", "MOLD");
        String base = LocalDateTime.now(SEOUL).minusHours(1).withSecond(0).withNano(0).toString();
        long i1 = id(install(mold, "PRESS-01", base));
        remove(i1, base + ":00.0000001").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("BEFORE_INSTALL"));
    }

    @Test
    void 결함9_허용오차_미래로_설치한_직후_지금_해체_가능() throws Exception {
        equip("PRESS-01", line);
        long mold = asset("MOLD-A", "MOLD");
        long i1 = id(install(mold, "PRESS-01", at(-4)));
        remove(i1, null).andExpect(status().isOk());
    }

    // =====================================================================
    // 3. 설치 원장 — 직접 SQL 변조
    // =====================================================================

    @Test
    void 결함10_원장_해체자_무효자_열_변조_거부() throws Exception {
        equip("PRESS-01", line);
        long mold = asset("MOLD-A", "MOLD");
        long i1 = id(install(mold, "PRESS-01", at(60)));
        remove(i1, at(30)).andExpect(status().isOk());
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "update mst_asset_install set removed_by = 'someone' where install_id = ?", i1)))).contains("ck_mst_asset_install_ledger");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "update mst_asset_install set voided_by = 'x', voided_at = now() where install_id = ?", i1))))
                .contains("ck_mst_asset_install_ledger");
    }

    @Test
    void 원장_무효행_되살리기_무효사유_지우기_삭제_TRUNCATE_불가() throws Exception {
        equip("PRESS-01", line);
        long mold = asset("MOLD-A", "MOLD");
        long i1 = id(install(mold, "PRESS-01", at(60)));
        postJson("/api/master/assets/installs/" + i1 + "/void", "{\"reason\":\"착오\"}").andExpect(status().isOk());
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "update mst_asset_install set void_yn = 'N', void_reason = null where install_id = ?", i1))))
                .contains("ck_mst_asset_install_ledger");
        assertThat(PgErrors.sqlState(catchThrowable(() -> accounts.exec(comp, "truncate mst_asset_install")))).contains("42501");
        // 새 행을 무효로 바로 넣기
        long e1 = accounts.query(comp, "select equip_id from mst_equip where equip_cd = 'PRESS-01'", Long.class);
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, """
                insert into mst_asset_install (comp_cd, asset_id, equip_id, installed_at, installed_by, void_yn, void_reason)
                values (?, ?, ?, now(), 't', 'Y', 'x')""", comp, mold, e1)))).contains("ck_mst_asset_install_ledger");
        // 무효 행은 해체·다시 무효 불가(API)
        remove(i1, null).andExpect(status().isConflict());
    }

    // =====================================================================
    // 4. 동시성
    // =====================================================================

    @Test
    void 동시_같은_자산_두_설비에_설치_뒤쪽은_기다린뒤_OVERLAP_한곳만() throws Exception {
        long e1 = equip("PRESS-01", line);
        equip("PRESS-02", line);
        long mold = asset("MOLD-A", "MOLD");
        CountDownLatch locked = new CountDownLatch(1);
        CompletableFuture<Void> first = holdTx(() -> jdbc.update("""
                insert into mst_asset_install (comp_cd, asset_id, equip_id, installed_at, installed_by)
                values (?, ?, ?, now(), 't')""", comp, mold, e1), locked, 1500);
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<MvcResult> second = async(json(post("/api/master/assets/" + mold + "/install"), "{\"equipCd\":\"PRESS-02\"}")
                .with(admin));
        first.get(10, TimeUnit.SECONDS);
        MvcResult r = second.get(10, TimeUnit.SECONDS);
        // 설치는 자산 행을 먼저 잠근다 → 뒤쪽은 앞쪽 커밋 뒤 "지금 설치 중" 으로 409(QA 관찰: 전에는 installedAt OVERLAP 400)
        assertThat(r.getResponse().getStatus()).isEqualTo(409);
        assertThat(r.getResponse().getContentAsString()).contains("ASSET_INSTALLED");
        assertThat(accounts.query(comp, "select count(*) from mst_asset_install where asset_id = ? and void_yn = 'N'", Integer.class, mold))
                .isEqualTo(1);
    }

    @Test
    void 결함11_자산_사용중지_중에_설치하면_한쪽이_실패해야_한다() throws Exception {
        equip("PRESS-01", line);
        long mold = asset("MOLD-A", "MOLD");
        assertThat(raceDisableAssetAndInstall(mold)).isZero();
    }

    @Test
    void 결함11_반대순서_설치_커밋전에_자산_설비_사용중지하면_기다린뒤_거부() throws Exception {
        long e1 = equip("PRESS-01", line);
        long mold = asset("MOLD-A", "MOLD");
        CountDownLatch locked = new CountDownLatch(1);
        CompletableFuture<Void> first = holdTx(() -> jdbc.update("""
                insert into mst_asset_install (comp_cd, asset_id, equip_id, installed_at, installed_by)
                values (?, ?, ?, now(), 't')""", comp, mold, e1), locked, 1500);
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<MvcResult> a = async(json(post("/api/master/assets/delete"), "{\"items\":[{\"id\":" + mold + ",\"rowVer\":0}]}")
                .with(admin));
        CompletableFuture<MvcResult> e = async(json(post("/api/master/equips/delete"), "{\"items\":[{\"id\":" + e1 + ",\"rowVer\":0}]}")
                .with(admin));
        first.get(10, TimeUnit.SECONDS);
        assertThat(a.get(10, TimeUnit.SECONDS).getResponse().getContentAsString()).contains("INSTALLED");
        assertThat(e.get(10, TimeUnit.SECONDS).getResponse().getContentAsString()).contains("HAS_ASSET");
        assertThat(accounts.query(comp, "select use_yn from mst_phys_asset where asset_id = ?", String.class, mold)).isEqualTo("Y");
        assertThat(accounts.query(comp, "select use_yn from mst_equip where equip_id = ?", String.class, e1)).isEqualTo("Y");
    }

    @Test
    void 결함12_사용중지_위치의_사용중지_설비는_위치를_그대로_저장할_수_있다_다시사용만_거부() throws Exception {
        long e1 = equip("SPOT-01", cell);
        postJson("/api/master/equips/delete", "{\"items\":[{\"id\":" + e1 + ",\"rowVer\":0}]}").andExpect(status().isOk());
        accounts.exec(comp, "update mst_equip_hier set use_yn = 'N' where node_id = ?", cell);
        // 화면이 지금 위치를 선택지로 남겨 보내는 값 — 사용 중지 상태로 비고만 수정
        putJson("/api/master/equips/" + e1, equipBody("SPOT-01", cell, weldClass, "N", ver("mst_equip", "equip_id", e1)))
                .andExpect(status().isOk());
        assertThat(accounts.query(comp, "select node_id from mst_equip where equip_id = ?", Long.class, e1)).isEqualTo(cell);
    }

    @Test
    void 위치에_사용중_설비가_있는_노드_사용중지는_API_칸오류_NODE_HAS_EQUIP() throws Exception {
        long u = accounts.newUser(comp, "hier1", PW);
        accounts.grant(comp, u, "HIER", "mst-equip-hier", "SEARCH", "SAVE");
        RequestPostProcessor h = accounts.as(comp, u, "hier1");
        equip("SPOT-01", cell);
        mvc.perform(json(put("/api/master/equip-hier/" + cell), "{\"parentId\":" + line + ",\"nodeCd\":\"C1\",\"nodeNm\":\"C1\",\"useYn\":\"N\",\"rowVer\":"
                + ver("mst_equip_hier", "node_id", cell) + "}").with(h))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("useYn"))
                .andExpect(jsonPath("$.errors[0].code").value("NODE_HAS_EQUIP"));
    }

    /** 사용 중지(서비스와 같은 순서: 설치 중인지 확인 → UPDATE) 를 커밋 전에 붙잡고 설치 API — 사용 중지 자산의 열린 설치 수 */
    private int raceDisableAssetAndInstall(long mold) throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CompletableFuture<Void> first = holdTx(() -> {
            Integer open = jdbc.queryForObject(
                    "select count(*) from mst_asset_install where asset_id = ? and removed_at is null and void_yn = 'N'", Integer.class, mold);
            assertThat(open).isZero();
            jdbc.update("update mst_phys_asset set use_yn = 'N', row_ver = row_ver + 1 where asset_id = ?", mold);
        }, locked, 1500);
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<MvcResult> second = async(json(post("/api/master/assets/" + mold + "/install"), "{\"equipCd\":\"PRESS-01\"}")
                .with(admin));
        second.get(10, TimeUnit.SECONDS);
        first.get(10, TimeUnit.SECONDS);
        return accounts.query(comp, """
                select count(*) from mst_asset_install i join mst_phys_asset a on a.asset_id = i.asset_id
                 where a.asset_id = ? and a.use_yn = 'N' and i.removed_at is null and i.void_yn = 'N'""", Integer.class, mold);
    }

    @Test
    void 동시성_row_ver_옛버전_설비_자산_클래스_수정은_409() throws Exception {
        long e1 = equip("PRESS-01", line);
        long mold = asset("MOLD-A", "MOLD");
        putJson("/api/master/equips/" + e1, equipBody("PRESS-01", line, weldClass, "Y", 7)).andExpect(status().isConflict());
        putJson("/api/master/assets/" + mold, "{\"assetCd\":\"MOLD-A\",\"assetType\":\"MOLD\",\"assetNm\":\"x\",\"rowVer\":7}")
                .andExpect(status().isConflict());
        putJson("/api/master/equip-classes/" + weldClass, "{\"classCd\":\"WELD\",\"classNm\":\"x\",\"rowVer\":7}").andExpect(status().isConflict());
        putJson("/api/master/equip-classes/" + weldClass, "{\"classCd\":\"WELD\",\"classNm\":\"x\"}").andExpect(status().isBadRequest());
        postJson("/api/master/equips/delete", "{\"items\":[{\"id\":" + e1 + ",\"rowVer\":7}]}").andExpect(status().isConflict());
        postJson("/api/master/equips/delete", "{\"items\":[null]}").andExpect(status().isBadRequest());
        postJson("/api/master/equips/delete", "{\"items\":[]}").andExpect(status().isBadRequest());
    }

    // =====================================================================
    // 5. 경계값
    // =====================================================================

    @Test
    void 경계_수명타수_교정주기_교정일_유형_소문자() throws Exception {
        String base = "\"assetType\":\"MOLD\",\"assetNm\":\"m\"";
        postJson("/api/master/assets", "{\"assetCd\":\"L12\"," + base + ",\"lifeShots\":\"999999999999\"}").andExpect(status().isOk());
        postJson("/api/master/assets", "{\"assetCd\":\"L13\"," + base + ",\"lifeShots\":\"1000000000000\"}")
                .andExpect(jsonPath("$.errors[0].code").value("POSITIVE"));
        postJson("/api/master/assets", "{\"assetCd\":\"L0\"," + base + ",\"lifeShots\":\"0\"}").andExpect(jsonPath("$.errors[0].code").value("POSITIVE"));
        postJson("/api/master/assets", "{\"assetCd\":\"LF\"," + base + ",\"lifeShots\":\"１２\"}").andExpect(jsonPath("$.errors[0].code").value("POSITIVE"));
        // 쉼표는 천 단위 자리일 때만(hb.numbers GROUPED 와 같은 규칙) — "1,23"·"0,5"·"1,0,0" 이 조용히 123·5·100 이 되던 것을 FORMAT 으로
        for (String bad : new String[] {"1,0,0", "1,23", "0,5", ",100", "100,", "1,2345", "01,000", "1,,000"}) {
            postJson("/api/master/assets", "{\"assetCd\":\"LC\"," + base + ",\"lifeShots\":\"" + bad + "\"}")
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("lifeShots"))
                    .andExpect(jsonPath("$.errors[0].code").value("FORMAT"));
        }
        assertThat(accounts.query(comp, "select count(*) from mst_phys_asset where asset_cd = 'LC'", Integer.class)).isZero();
        long grouped = id(postJson("/api/master/assets", "{\"assetCd\":\"LG\"," + base + ",\"lifeShots\":\" 1,234,567 \"}"));
        assertThat(accounts.query(comp, "select life_shots from mst_phys_asset where asset_id = ?", Long.class, grouped)).isEqualTo(1234567L);
        String g = "\"assetType\":\"GAUGE\",\"assetNm\":\"g\"";
        postJson("/api/master/assets", "{\"assetCd\":\"C3650\"," + g + ",\"calibCycleDays\":3650,\"lastCalibDate\":\"2026-01-01\"}")
                .andExpect(status().isOk());
        mvc.perform(get("/api/master/assets/" + accounts.query(comp, "select asset_id from mst_phys_asset where asset_cd = 'C3650'", Long.class))
                .with(admin)).andExpect(jsonPath("$.data.nextCalibDate").value("2035-12-30"));
        postJson("/api/master/assets", "{\"assetCd\":\"C3651\"," + g + ",\"calibCycleDays\":3651}").andExpect(jsonPath("$.errors[0].code").value("CODE"));
        postJson("/api/master/assets", "{\"assetCd\":\"CBIG\"," + g + ",\"calibCycleDays\":3000000000}").andExpect(status().isBadRequest());
        String today = LocalDate.now(SEOUL).toString();
        postJson("/api/master/assets", "{\"assetCd\":\"CT\"," + g + ",\"lastCalibDate\":\"" + today + "\"}").andExpect(status().isOk());
        postJson("/api/master/assets", "{\"assetCd\":\"CF\"," + g + ",\"lastCalibDate\":\"" + LocalDate.now(SEOUL).plusDays(1) + "\"}")
                .andExpect(jsonPath("$.errors[0].code").value("FUTURE"));
        postJson("/api/master/assets", "{\"assetCd\":\"C49\"," + g + ",\"lastCalibDate\":\"1949-12-31\"}")
                .andExpect(jsonPath("$.errors[0].code").value("FORMAT"));
        // 관찰: 유형은 대문자 정리 안 함(코드는 함) / 금형에 교정 주기, 게이지에 수명 타수도 저장된다
        postJson("/api/master/assets", "{\"assetCd\":\"T1\",\"assetType\":\"mold\",\"assetNm\":\"m\"}")
                .andExpect(jsonPath("$.errors[0].code").value("CODE"));
        postJson("/api/master/assets", "{\"assetCd\":\"T2\",\"assetType\":\"MOLD\",\"assetNm\":\"m\",\"calibCycleDays\":30}")
                .andExpect(status().isOk());
        postJson("/api/master/assets", "{\"assetCd\":\"T3\",\"assetType\":\"GAUGE\",\"assetNm\":\"g\",\"lifeShots\":\"10\"}")
                .andExpect(status().isOk());
    }

    @Test
    void 경계_설비코드_30_31자_설치일_미래_클래스코드_한글() throws Exception {
        postJson("/api/master/equips", equipBody("E".repeat(30), null, weldClass, "Y", null)).andExpect(status().isOk());
        postJson("/api/master/equips", equipBody("E".repeat(31), null, weldClass, "Y", null))
                .andExpect(jsonPath("$.errors[0].code").value("FORMAT"));
        // 관찰: 설비 설치일은 미래(2999)도 저장된다 — 자산 교정일은 미래 금지
        postJson("/api/master/equips", "{\"plantCd\":\"P1\",\"equipCd\":\"FUT\",\"equipNm\":\"f\",\"classId\":" + weldClass
                + ",\"installDate\":\"2999-12-31\"}").andExpect(status().isOk());
        postJson("/api/master/equip-classes", "{\"classCd\":\"용접\",\"classNm\":\"x\"}").andExpect(jsonPath("$.errors[0].code").value("FORMAT"));
        postJson("/api/master/equip-classes", "{\"classCd\":\"S\",\"classNm\":\"x\",\"sortNo\":-5}").andExpect(status().isOk());   // 관찰: 순서 음수 허용(계층은 거부)
        postJson("/api/master/equips", "{\"plantCd\":\"NOPE\",\"equipCd\":\"X\",\"equipNm\":\"x\",\"classId\":" + weldClass + "}")
                .andExpect(jsonPath("$.errors[0].field").value("plantCd"));
    }

    // =====================================================================
    // 6. A사로 B사 — 모든 API
    // =====================================================================

    @Test
    void B사_사용자_A사_id로_모든_설비_자산_API() throws Exception {
        long e1 = equip("PRESS-01", line);
        long mold = asset("MOLD-A", "MOLD");
        long i1 = id(install(mold, "PRESS-01", at(30)));
        String other = accounts.newComp("AS");
        long bUser = accounts.newUser(other, "admin1", PW);
        accounts.grant(other, bUser, "MASTER", "mst-equip", "SEARCH", "SAVE", "DELETE", "EXCEL");
        accounts.grant(other, bUser, "MASTER", "mst-asset", "SEARCH", "SAVE", "DELETE", "EXCEL");
        accounts.addPlant(other, "P1", "B사 공장");
        RequestPostProcessor b = accounts.as(other, bUser, "admin1");
        long bClass = id(mvc.perform(json(post("/api/master/equip-classes"), "{\"classCd\":\"WELD\",\"classNm\":\"b\"}").with(b)));
        long bEquip = id(mvc.perform(json(post("/api/master/equips"), "{\"plantCd\":\"P1\",\"equipCd\":\"PRESS-01\",\"equipNm\":\"b\",\"classId\":"
                + bClass + "}").with(b)));
        // 읽기
        mvc.perform(get("/api/master/equips").with(b)).andExpect(jsonPath("$.total").value(1));
        mvc.perform(get("/api/master/equips").param("classId", String.valueOf(weldClass)).with(b)).andExpect(jsonPath("$.total").value(0));
        mvc.perform(get("/api/master/equips/places").param("plantCd", "P1").with(b)).andExpect(jsonPath("$.data.length()").value(0));
        mvc.perform(get("/api/master/equip-classes").with(b)).andExpect(jsonPath("$.data.length()").value(1));
        mvc.perform(get("/api/master/assets/" + mold).with(b)).andExpect(status().isNotFound());
        mvc.perform(get("/api/master/assets/" + mold + "/installs").with(b)).andExpect(status().isNotFound());
        mvc.perform(get("/api/master/assets/targets").with(b)).andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].equipId").value(bEquip));
        // 쓰기
        mvc.perform(json(put("/api/master/equips/" + e1), equipBody("PRESS-01", null, bClass, "Y", 0)).with(b)).andExpect(status().isNotFound());
        mvc.perform(json(put("/api/master/equips/" + bEquip), equipBody("PRESS-01", cell, bClass, "Y", 0)).with(b))
                .andExpect(jsonPath("$.errors[0].code").value("NOT_FOUND"));                                   // A사 셀
        mvc.perform(json(put("/api/master/equips/" + bEquip), equipBody("PRESS-01", null, weldClass, "Y", 0)).with(b))
                .andExpect(jsonPath("$.errors[0].field").value("classId"));                                    // A사 클래스
        mvc.perform(json(post("/api/master/equips/delete"), "{\"items\":[{\"id\":" + e1 + ",\"rowVer\":0}]}").with(b))
                .andExpect(status().isNotFound());
        mvc.perform(json(put("/api/master/equip-classes/" + weldClass), "{\"classCd\":\"WELD\",\"classNm\":\"x\",\"useYn\":\"N\",\"rowVer\":0}")
                .with(b)).andExpect(status().isNotFound());
        mvc.perform(json(put("/api/master/assets/" + mold), "{\"assetCd\":\"MOLD-A\",\"assetType\":\"MOLD\",\"assetNm\":\"x\",\"rowVer\":0}")
                .with(b)).andExpect(status().isNotFound());
        mvc.perform(json(post("/api/master/assets/delete"), "{\"items\":[{\"id\":" + mold + ",\"rowVer\":0}]}").with(b))
                .andExpect(status().isNotFound());
        mvc.perform(json(post("/api/master/assets/installs/" + i1 + "/remove"), "{}").with(b)).andExpect(status().isNotFound());
        mvc.perform(json(post("/api/master/assets/installs/" + i1 + "/void"), "{\"reason\":\"x\"}").with(b)).andExpect(status().isNotFound());
        // B사 자산을 같은 코드의 B사 설비에 — 코드 PRESS-01 은 B사 것으로 풀린다
        mvc.perform(json(post("/api/master/assets"), "{\"assetCd\":\"MOLD-A\",\"assetType\":\"MOLD\",\"assetNm\":\"b\",\"itemCd\":\"FG1\"}")
                .with(b)).andExpect(jsonPath("$.errors[0].code").value("NOT_FOUND"));                           // A사 품목 코드
        long bm = id(mvc.perform(json(post("/api/master/assets"), "{\"assetCd\":\"MOLD-A\",\"assetType\":\"MOLD\",\"assetNm\":\"b\"}").with(b)));
        id(mvc.perform(json(post("/api/master/assets/" + bm + "/install"), "{\"equipCd\":\"PRESS-01\"}").with(b)));
        assertThat(accounts.query(other, "select equip_id from mst_asset_install where asset_id = ?", Long.class, bm)).isEqualTo(bEquip);
        // 내보내기 — B사 행만
        MvcResult x = mvc.perform(json(post("/api/master/equips/export"), "{}").with(b)).andExpect(status().isOk()).andReturn();
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(x.getResponse().getContentAsByteArray()))) {
            assertThat(wb.getSheetAt(0).getLastRowNum()).isEqualTo(1);
        }
        // 변경 이력: A사 설비 키
        mvc.perform(get("/api/platform/audit").param("table", "mst_equip").param("key", String.valueOf(e1)).with(b))
                .andExpect(jsonPath("$.total").value(0));
        // A사 데이터 그대로
        mvc.perform(get("/api/master/assets/" + mold).with(admin)).andExpect(jsonPath("$.data.curEquipCd").value("PRESS-01"))
                .andExpect(jsonPath("$.data.useYn").value("Y"));
    }

    // =====================================================================
    // 7. 권한 조합 · 엑셀 · 변경 이력
    // =====================================================================

    @Test
    void 권한_조합_설비조회만_자산저장만_자산DELETE없음_엑셀없음() throws Exception {
        long e1 = equip("PRESS-01", line);
        long mold = asset("MOLD-A", "MOLD");
        long u = accounts.newUser(comp, "u1", PW);
        accounts.grant(comp, u, "MIX", "mst-equip", "SEARCH");
        accounts.grant(comp, u, "MIX", "mst-asset", "SEARCH", "SAVE");
        RequestPostProcessor r = accounts.as(comp, u, "u1");
        mvc.perform(json(post("/api/master/equip-classes"), "{\"classCd\":\"X\",\"classNm\":\"x\"}").with(r)).andExpect(status().isForbidden());
        mvc.perform(json(put("/api/master/equips/" + e1), equipBody("PRESS-01", line, weldClass, "Y", 0)).with(r)).andExpect(status().isForbidden());
        mvc.perform(json(post("/api/master/equips/export"), "{}").with(r)).andExpect(status().isForbidden());
        mvc.perform(json(post("/api/master/assets/export"), "{}").with(r)).andExpect(status().isForbidden());
        mvc.perform(json(post("/api/master/assets/delete"), "{\"items\":[{\"id\":" + mold + ",\"rowVer\":0}]}").with(r))
                .andExpect(status().isForbidden());
        // 관찰: 자산 SAVE 로 사용 여부 N 저장(수정 API)은 된다 — 목록의 "삭제"(DELETE 권한)를 우회하는 경로
        mvc.perform(json(put("/api/master/assets/" + mold), "{\"assetCd\":\"MOLD-A\",\"assetType\":\"MOLD\",\"assetNm\":\"m\",\"useYn\":\"N\",\"rowVer\":0}")
                .with(r)).andExpect(status().isOk());
        // 설치(SAVE)·해체(SAVE) 가능, 무효(DELETE) 불가
        long m2 = asset("MOLD-B", "MOLD");
        long i1 = id(mvc.perform(json(post("/api/master/assets/" + m2 + "/install"), "{\"equipCd\":\"PRESS-01\"}").with(r)));
        mvc.perform(json(post("/api/master/assets/installs/" + i1 + "/remove"), "{}").with(r)).andExpect(status().isOk());
        // 권한 없는 화면의 변경 이력
        mvc.perform(get("/api/platform/audit").param("table", "mst_equip").param("key", String.valueOf(e1)).with(r)).andExpect(status().isOk());
        long none = accounts.newUser(comp, "none1", PW);
        accounts.grant(comp, none, "NONE", "mst-asset", "SEARCH");
        RequestPostProcessor n = accounts.as(comp, none, "none1");
        mvc.perform(get("/api/platform/audit").param("table", "mst_equip").param("key", String.valueOf(e1)).with(n))
                .andExpect(status().isForbidden());
    }

    @Test
    void 엑셀_설비_자산_수식주입_건수_열선택() throws Exception {
        postJson("/api/master/equips", "{\"plantCd\":\"P1\",\"equipCd\":\"F1\",\"equipNm\":\"=cmd|' /C calc'!A0\",\"classId\":" + weldClass
                + ",\"nodeId\":" + cell + "}").andExpect(status().isOk());
        equip("F2", null);
        postJson("/api/master/assets", "{\"assetCd\":\"A1\",\"assetType\":\"MOLD\",\"assetNm\":\"@SUM(1)\"}").andExpect(status().isOk());
        MvcResult x = postJson("/api/master/equips/export", "{\"columns\":[\"equipCd\",\"equipNm\",\"nodePath\"]}").andExpect(status().isOk()).andReturn();
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(x.getResponse().getContentAsByteArray()))) {
            Sheet s = wb.getSheetAt(0);
            assertThat(s.getLastRowNum()).isEqualTo(2);
            assertThat(s.getRow(1).getCell(1).getCellType()).isEqualTo(CellType.STRING);
            assertThat(s.getRow(1).getCell(1).getStringCellValue()).doesNotStartWith("=");
            assertThat(s.getRow(1).getCell(2).getStringCellValue()).isEqualTo("P1/BODY/L1/C1");
        }
        MvcResult y = postJson("/api/master/assets/export", "{\"assetType\":\"MOLD\"}").andExpect(status().isOk()).andReturn();
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(y.getResponse().getContentAsByteArray()))) {
            Sheet s = wb.getSheetAt(0);
            assertThat(s.getLastRowNum()).isEqualTo(1);
            for (int c = 0; c < s.getRow(1).getLastCellNum(); c++) {
                if (s.getRow(1).getCell(c) != null && s.getRow(1).getCell(c).getCellType() == CellType.STRING) {
                    assertThat(s.getRow(1).getCell(c).getStringCellValue()).doesNotStartWith("@");
                }
            }
        }
        postJson("/api/master/assets/export", "{\"assetType\":\"TOOL\"}").andExpect(status().isBadRequest());
        postJson("/api/master/equips/export", "{\"sort\":[\"equip_cd; drop table x\"]}").andExpect(status().isBadRequest());
    }

    @Test
    void 관찰_변경이력_클래스_설치이력_공장은_조회_불가_설비_자산은_가능() throws Exception {
        long e1 = equip("PRESS-01", line);
        long mold = asset("MOLD-A", "MOLD");
        long i1 = id(install(mold, "PRESS-01", at(10)));
        mvc.perform(get("/api/platform/audit").param("table", "mst_equip").param("key", String.valueOf(e1)).with(admin))
                .andExpect(jsonPath("$.total").value(1));
        mvc.perform(get("/api/platform/audit").param("table", "mst_phys_asset").param("key", String.valueOf(mold)).with(admin))
                .andExpect(jsonPath("$.total").value(1));
        // 감사 트리거는 있지만 TABLE_PROG 에 없어 화면에서 볼 수 없다
        mvc.perform(get("/api/platform/audit").param("table", "mst_equip_class").param("key", String.valueOf(weldClass)).with(admin))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/platform/audit").param("table", "mst_asset_install").param("key", String.valueOf(i1)).with(admin))
                .andExpect(status().isBadRequest());
        assertThat(accounts.query(comp, "select count(*) from sys_audit_log where table_nm = 'mst_asset_install' and row_key = ?",
                Integer.class, String.valueOf(i1))).isEqualTo(1);
    }
}
