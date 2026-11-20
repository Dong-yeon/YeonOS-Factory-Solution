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
 * QA P1 4a — 공장·장비 계층 이상 사용 (docs/qa/equip-4.md). TESTING.md "P1 4a" 표에 없는 것만:
 * 경계값·유니코드, 하위가 있는 노드 이동과 깊이, 직접 SQL 유형 변경·다시 사용, 동시 이동, A/B사 같은 공장 코드, 엑셀, 권한 조합.
 * 결함은 고친 뒤의 기대 동작으로 쓰고 {@code @Disabled("결함n: …")} — 수정은 개발 세션.
 */
@AutoConfigureMockMvc
class EquipHierAbuseTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";
    private static final String HIER = "/api/master/equip-hier";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    @Autowired
    TenantRunner runner;

    @Autowired
    JdbcTemplate jdbc;

    String comp;
    long userId;
    RequestPostProcessor admin;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("EQ");
        userId = accounts.newUser(comp, "admin1", PW);
        accounts.grant(comp, userId, "MASTER", "mst-plant", "SEARCH", "SAVE");
        accounts.grant(comp, userId, "MASTER", "mst-equip-hier", "SEARCH", "SAVE", "EXCEL");
        admin = accounts.as(comp, userId, "admin1");
        accounts.addPlant(comp, "P1", "1공장");
        accounts.addPlant(comp, "P2", "2공장");
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String body) {
        return b.with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private ResultActions create(String plant, Long parent, String type, String cd) throws Exception {
        return createNm(plant, parent, type, cd, cd + " 이름");
    }

    private ResultActions createNm(String plant, Long parent, String type, String cd, String nm) throws Exception {
        return mvc.perform(json(post(HIER), "{\"plantCd\":\"" + plant + "\",\"parentId\":" + parent + ",\"nodeType\":\""
                + type + "\",\"nodeCd\":\"" + cd + "\",\"nodeNm\":\"" + nm + "\"}").with(admin));
    }

    private long node(String plant, Long parent, String type, String cd) throws Exception {
        return id(create(plant, parent, type, cd));
    }

    private static long id(ResultActions r) throws Exception {
        MvcResult m = r.andExpect(status().isOk()).andReturn();
        return ((Number) JsonPath.read(m.getResponse().getContentAsString(), "$.data")).longValue();
    }

    private int ver(long id) {
        return accounts.query(comp, "select row_ver from mst_equip_hier where node_id = ?", Integer.class, id);
    }

    private String cd(long id) {
        return accounts.query(comp, "select node_cd from mst_equip_hier where node_id = ?", String.class, id);
    }

    private ResultActions update(long id, Long parent, String useYn) throws Exception {
        String c = cd(id);
        return mvc.perform(json(put(HIER + "/" + id), "{\"parentId\":" + parent + ",\"nodeCd\":\"" + c
                + "\",\"nodeNm\":\"" + c + " 이름\",\"useYn\":\"" + useYn + "\",\"rowVer\":" + ver(id) + "}").with(admin));
    }

    private int maxLevelInTree(String plant) throws Exception {
        MvcResult r = mvc.perform(get(HIER).param("plantCd", plant).with(admin)).andExpect(status().isOk()).andReturn();
        java.util.List<Integer> lv = JsonPath.read(r.getResponse().getContentAsString(), "$.data[*].lvl");
        return lv.stream().mapToInt(Integer::intValue).max().orElse(0);
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
    // 1. 경계값 · 유니코드
    // =====================================================================

    @Test
    void 경계_코드_30자_31자_공백_소문자_특수문자_한글_전각() throws Exception {
        String c30 = "A".repeat(30);
        node("P1", null, "AREA", c30);
        create("P1", null, "AREA", "B".repeat(31)).andExpect(jsonPath("$.errors[0].code").value("FORMAT"));
        long a = node("P1", null, "AREA", "  body  ");
        assertThat(cd(a)).isEqualTo("BODY");
        create("P1", null, "AREA", "Body").andExpect(jsonPath("$.errors[0].code").value("DUPLICATE"));
        for (String bad : new String[] {"-A", ".A", "A B", "A/B", "차체", "Ａ１", "A\\u0000"}) {
            create("P1", null, "AREA", bad).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value("nodeCd"));
        }
        // 점·밑줄·하이픈은 허용(로케이션 바코드 A-01.03)
        node("P1", null, "WH", "A_1-2.3");
    }

    @Test
    void 관찰_유니코드_대문자_변환으로_다른_글자가_같은_코드가_된다() throws Exception {
        // toUpperCase(Locale.ROOT): 터키어 점 없는 ı → I, 긴 s ſ → S, ß → SS — 입력과 다른 코드로 조용히 저장
        long a = node("P1", null, "AREA", "lıne");
        assertThat(cd(a)).isEqualTo("LINE");
        long b = node("P1", null, "AREA", "ſtore");
        assertThat(cd(b)).isEqualTo("STORE");
        create("P1", null, "AREA", "line").andExpect(jsonPath("$.errors[0].code").value("DUPLICATE"));
        long c = node("P1", null, "AREA", "ßx");
        assertThat(cd(c)).isEqualTo("SSX");
    }

    @Test
    void 경계_이름_100자_101자_전각공백만_제로폭공백_순서범위() throws Exception {
        createNm("P1", null, "AREA", "N1", "가".repeat(100)).andExpect(status().isOk());
        createNm("P1", null, "AREA", "N2", "가".repeat(101)).andExpect(jsonPath("$.errors[0].code").value("TOO_LONG"));
        createNm("P1", null, "AREA", "N3", "　　").andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        // 관찰: 제로폭 공백(보이지 않는 글자)만 있는 이름은 저장된다 — strip·btrim 둘 다 공백으로 보지 않음
        createNm("P1", null, "AREA", "N4", "​").andExpect(status().isOk());
        mvc.perform(json(post(HIER), "{\"plantCd\":\"P1\",\"nodeType\":\"AREA\",\"nodeCd\":\"S1\",\"nodeNm\":\"x\",\"sortNo\":100000}")
                .with(admin)).andExpect(jsonPath("$.errors[0].field").value("sortNo"));
        mvc.perform(json(post(HIER), "{\"plantCd\":\"P1\",\"nodeType\":\"AREA\",\"nodeCd\":\"S2\",\"nodeNm\":\"x\",\"sortNo\":-1}")
                .with(admin)).andExpect(jsonPath("$.errors[0].field").value("sortNo"));
        mvc.perform(json(post(HIER), "{\"plantCd\":\"P1\",\"nodeType\":\"AREA\",\"nodeCd\":\"S3\",\"nodeNm\":\"x\",\"sortNo\":3000000000}")
                .with(admin)).andExpect(status().isBadRequest());
        // 유형 소문자는 코드 오류(코드처럼 대문자로 정리하지 않음)
        create("P1", null, "area", "S4").andExpect(jsonPath("$.errors[0].field").value("nodeType"));
    }

    @Test
    void 경계_깊이_정확히_10단은_허용_11단째_이동도_DEPTH() throws Exception {
        Long parent = node("P1", null, "WH", "D0");
        long d8 = 0;
        for (int i = 1; i < 10; i++) {
            parent = node("P1", parent, "LOC", "D" + i);
            if (i == 8) {
                d8 = parent;
            }
        }
        long d9 = parent;                                                       // 10단
        assertThat(maxLevelInTree("P1")).isEqualTo(10);
        // 잎(하위 없음)을 10단째 아래로 이동 → 11단 → DEPTH
        long w2 = node("P1", null, "WH", "W2");
        long leaf = node("P1", w2, "LOC", "LEAF");
        update(leaf, d9, "Y").andExpect(jsonPath("$.errors[0].code").value("DEPTH"));
        update(leaf, d8, "Y").andExpect(status().isOk());                       // 10단째로는 가능
    }

    // =====================================================================
    // 2. 계층 이동
    // =====================================================================

    @Test
    void 결함1_하위가_있는_노드를_깊은_곳으로_옮기면_10단을_넘는다() throws Exception {
        Long parent = node("P1", null, "WH", "D0");
        long d7 = 0;
        for (int i = 1; i < 10; i++) {
            parent = node("P1", parent, "LOC", "D" + i);
            if (i == 7) {
                d7 = parent;
            }
        }
        // 다른 창고에 3단 로케이션 묶음
        long w2 = node("P1", null, "WH", "W2");
        long x1 = node("P1", w2, "LOC", "X1");
        long x2 = node("P1", x1, "LOC", "X2");
        node("P1", x2, "LOC", "X3");
        // X1 을 8단째(D7) 아래로 → X1 9단·X2 10단·X3 11단 — 거부돼야 한다
        update(x1, d7, "Y").andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("DEPTH"));
        assertThat(maxLevelInTree("P1")).isLessThanOrEqualTo(10);
    }

    @Test
    void 이동_하위와_설비경로_함께_사용중지_부모로_이동_거부_자기자신을_부모로() throws Exception {
        long a1 = node("P1", null, "AREA", "A1");
        long a2 = node("P1", null, "AREA", "A2");
        long l1 = node("P1", a1, "LINE", "L1");
        node("P1", l1, "CELL", "C1");
        update(l1, a2, "Y").andExpect(status().isOk());
        mvc.perform(get(HIER).param("plantCd", "P1").with(admin))
                .andExpect(jsonPath("$.data[?(@.nodeCd=='C1')].path").value("P1/A2/L1/C1"));
        // 사용 중지된 구역 아래로 이동
        long a3 = node("P1", null, "AREA", "A3");
        update(a3, null, "N").andExpect(status().isOk());
        update(l1, a3, "Y").andExpect(jsonPath("$.errors[0].code").value("INACTIVE"));
        // 로케이션의 부모를 자기 자신으로(유형 규칙상 LOC → LOC 는 허용) → 순환
        long wh = node("P1", null, "WH", "W1");
        long loc = node("P1", wh, "LOC", "R1");
        update(loc, loc, "Y").andExpect(jsonPath("$.errors[0].code").value("CYCLE"));
        // 공장 바로 아래로(부모 null) — 로케이션은 불가, 창고 아래 구역 → 불가
        update(loc, null, "Y").andExpect(jsonPath("$.errors[0].code").value("PARENT_TYPE"));
        // 옛 rowVer
        mvc.perform(json(put(HIER + "/" + loc), "{\"parentId\":" + wh + ",\"nodeCd\":\"R1\",\"nodeNm\":\"x\",\"rowVer\":" + (ver(loc) + 5)
                + "}").with(admin)).andExpect(status().isConflict());
        // 수정 본문의 plantCd 는 무시(공장 이동 불가) — P2 로 보내도 P1 그대로
        mvc.perform(json(put(HIER + "/" + loc), "{\"plantCd\":\"P2\",\"parentId\":" + wh + ",\"nodeCd\":\"R1\",\"nodeNm\":\"x\",\"rowVer\":"
                + ver(loc) + "}").with(admin)).andExpect(status().isOk());
        assertThat(accounts.query(comp, "select plant_cd from mst_equip_hier where node_id = ?", String.class, loc)).isEqualTo("P1");
    }

    @Test
    void 결함2_직접SQL_유형변경은_하위가_있으면_거부() throws Exception {
        long area = node("P1", null, "AREA", "A1");
        long line = node("P1", area, "LINE", "L1");
        node("P1", line, "CELL", "C1");
        // 라인 → 창고: 구역 아래 창고는 허용 유형이라 부모 규칙만으로는 통과 — 하위 셀이 창고 아래가 된다
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "update mst_equip_hier set node_type = 'WH' where node_id = ?", line)))).isPresent();
        // 공장 바로 아래 구역 → 창고(하위 라인)
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "update mst_equip_hier set node_type = 'WH' where node_id = ?", area)))).contains("ck_mst_equip_hier_type_fixed");
        // 하위 없는 노드도 유형 불변(API 의 FIXED 와 같은 규칙)
        long w = node("P1", null, "AREA", "LONE");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "update mst_equip_hier set node_type = 'WH' where node_id = ?", w)))).contains("ck_mst_equip_hier_type_fixed");
        mvc.perform(json(put(HIER + "/" + area), "{\"parentId\":null,\"nodeType\":\"WH\",\"nodeCd\":\"A1\",\"nodeNm\":\"x\",\"rowVer\":"
                + ver(area) + "}").with(admin)).andExpect(jsonPath("$.errors[0].code").value("FIXED"));
    }

    @Test
    void 결함3_직접SQL_사용중지_부모_아래_하위_다시사용_거부() throws Exception {
        long area = node("P1", null, "AREA", "A1");
        long line = node("P1", area, "LINE", "L1");
        update(line, area, "N").andExpect(status().isOk());
        update(area, null, "N").andExpect(status().isOk());
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "update mst_equip_hier set use_yn = 'Y' where node_id = ?", line)))).contains("ck_mst_equip_hier_parent_use");
    }

    @Test
    void 결함4_사용중지_공장_아래_노드_추가_거부() throws Exception {
        accounts.exec(comp, "update sys_plant set use_yn = 'N' where comp_cd = ? and plant_cd = 'P2'", comp);
        create("P2", null, "AREA", "NEW").andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("plantCd"))
                .andExpect(jsonPath("$.errors[0].code").value("INACTIVE"))
                .andExpect(jsonPath("$.errors[0].message").value("사용 중지된 항목입니다."));   // 품목 전용 문구였음
        // 사용 중지 공장의 (사용 중지) 노드를 다시 사용 — 직접 SQL 도
        accounts.exec(comp, "update sys_plant set use_yn = 'Y' where comp_cd = ? and plant_cd = 'P2'", comp);
        long a = node("P2", null, "AREA", "A2");
        update(a, null, "N").andExpect(status().isOk());
        accounts.exec(comp, "update sys_plant set use_yn = 'N' where comp_cd = ? and plant_cd = 'P2'", comp);
        update(a, null, "Y").andExpect(jsonPath("$.errors[0].field").value("plantCd"));
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "update mst_equip_hier set use_yn = 'Y' where node_id = ?", a)))).contains("ck_mst_equip_hier_plant_use");
    }

    // =====================================================================
    // 3. 동시성
    // =====================================================================

    @Test
    void 동시_교차_이동_RA를_RB아래로_커밋전에_RB를_RA아래로_뒤쪽은_기다린뒤_CYCLE() throws Exception {
        long wh = node("P1", null, "WH", "W1");
        long a = node("P1", wh, "LOC", "RA");
        long b = node("P1", wh, "LOC", "RB");
        int vb = ver(b);
        CountDownLatch locked = new CountDownLatch(1);
        // 사용자 1: RA 를 RB 아래로(트리거 통과, 커밋 전) — 부모 FK 확인이 RB 행을 KEY SHARE 로 잡는다
        CompletableFuture<Void> first = holdTx(() -> jdbc.update("update mst_equip_hier set parent_id = ? where node_id = ?", b, a),
                locked, 1500);
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
        // 사용자 2: RB 를 RA 아래로 — RB 행 잠금에서 기다렸다가 다시 읽은 행으로 트리거가 순환을 본다
        CompletableFuture<MvcResult> second = async(json(put(HIER + "/" + b),
                "{\"parentId\":" + a + ",\"nodeCd\":\"RB\",\"nodeNm\":\"RB\",\"rowVer\":" + vb + "}").with(admin));
        first.get(10, TimeUnit.SECONDS);
        MvcResult r = second.get(10, TimeUnit.SECONDS);
        assertThat(r.getResponse().getStatus()).isEqualTo(400);
        assertThat(r.getResponse().getContentAsString()).contains("CYCLE");
        assertThat(accounts.query(comp, """
                select count(*) from mst_equip_hier x join mst_equip_hier y on y.node_id = x.parent_id
                 where y.parent_id = x.node_id""", Integer.class)).isZero();
    }

    /** X(하위 X1)·Z(하위 Z1): 사용자 1 은 X 를 Z1 아래로, 사용자 2 는 Z 를 X1 아래로 — 잠그는 행이 서로 겹치지 않는다 */
    private MvcResult crossMoveWithChildren(long x, long z1, long z, long x1) throws Exception {
        int vz = ver(z);
        CountDownLatch locked = new CountDownLatch(1);
        // 앱 매퍼와 같은 SET 목록(node_cd 포함)으로 커밋 전 붙잡기
        CompletableFuture<Void> first = holdTx(() -> jdbc.update(
                "update mst_equip_hier set parent_id = ?, node_cd = node_cd, row_ver = row_ver + 1 where node_id = ?", z1, x),
                locked, 1500);
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<MvcResult> second = async(json(put(HIER + "/" + z),
                "{\"parentId\":" + x1 + ",\"nodeCd\":\"Z\",\"nodeNm\":\"Z\",\"rowVer\":" + vz + "}").with(admin));
        MvcResult r = second.get(10, TimeUnit.SECONDS);
        first.get(10, TimeUnit.SECONDS);
        return r;
    }

    @Test
    void 결함5_동시_하위경유_교차이동은_한쪽이_실패해야_한다() throws Exception {
        long wh = node("P1", null, "WH", "W1");
        long x = node("P1", wh, "LOC", "X");
        long x1 = node("P1", x, "LOC", "X1");
        long z = node("P1", wh, "LOC", "Z");
        long z1 = node("P1", z, "LOC", "Z1");
        MvcResult r = crossMoveWithChildren(x, z1, z, x1);
        assertThat(cycleNodes()).as("second status %s", r.getResponse().getStatus()).isZero();
        // 고친 뒤: 뒤쪽은 공장 단위 잠금에서 기다렸다가 앞쪽 이동을 보고 순환으로 거부
        assertThat(r.getResponse().getStatus()).isEqualTo(400);
        assertThat(r.getResponse().getContentAsString()).contains("CYCLE");
    }

    private int cycleNodes() {
        return accounts.query(comp, """
                with recursive up(start_id, node_id, d) as (
                    select node_id, parent_id, 1 from mst_equip_hier where parent_id is not null
                    union all
                    select u.start_id, h.parent_id, u.d + 1 from up u join mst_equip_hier h on h.node_id = u.node_id
                     where u.d < 30 and h.parent_id is not null and u.node_id <> u.start_id
                )
                select count(distinct start_id) from up where node_id = start_id""", Integer.class);
    }

    @Test
    void 동시_같은_코드_두번_추가_뒤쪽은_DUPLICATE() throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CompletableFuture<Void> first = holdTx(() -> jdbc.update("""
                insert into mst_equip_hier (comp_cd, plant_cd, node_type, node_cd, node_nm, created_by, updated_by)
                values (?, 'P1', 'AREA', 'SAME', 'x', 't', 't')""", comp), locked, 1000);
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<MvcResult> second = async(json(post(HIER),
                "{\"plantCd\":\"P1\",\"nodeType\":\"AREA\",\"nodeCd\":\"same\",\"nodeNm\":\"y\"}").with(admin));
        first.get(10, TimeUnit.SECONDS);
        MvcResult r = second.get(10, TimeUnit.SECONDS);
        assertThat(r.getResponse().getStatus()).isEqualTo(400);
        assertThat(r.getResponse().getContentAsString()).contains("DUPLICATE");
    }

    // =====================================================================
    // 4. A사로 B사 · 권한 · 엑셀 · 공장
    // =====================================================================

    @Test
    void B사_같은_공장코드_P1_트리_내보내기_수정_모두_자기것만() throws Exception {
        long aArea = node("P1", null, "AREA", "A-ONLY");
        String other = accounts.newComp("EQ");
        long bUser = accounts.newUser(other, "admin1", PW);
        accounts.grant(other, bUser, "MASTER", "mst-plant", "SEARCH", "SAVE");
        accounts.grant(other, bUser, "MASTER", "mst-equip-hier", "SEARCH", "SAVE", "EXCEL");
        accounts.addPlant(other, "P1", "B사 1공장");
        RequestPostProcessor b = accounts.as(other, bUser, "admin1");
        mvc.perform(get(HIER).param("plantCd", "P1").with(b)).andExpect(jsonPath("$.data.length()").value(0));
        mvc.perform(get(HIER + "/plants").with(b)).andExpect(jsonPath("$.data.length()").value(1));
        mvc.perform(get("/api/master/plants").with(b)).andExpect(jsonPath("$.data[0].plantNm").value("B사 1공장"));
        // A사 공장 P2 는 B사에 없음
        mvc.perform(get(HIER).param("plantCd", "P2").with(b)).andExpect(status().isBadRequest());
        mvc.perform(json(put("/api/master/plants/P2"), "{\"plantNm\":\"x\",\"rowVer\":0}").with(b)).andExpect(status().isNotFound());
        MvcResult x = mvc.perform(json(post(HIER + "/export"), "{\"plantCd\":\"P1\"}").with(b)).andExpect(status().isOk()).andReturn();
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(x.getResponse().getContentAsByteArray()))) {
            assertThat(wb.getSheetAt(0).getLastRowNum()).isZero();                    // 머리글만
        }
        // B사 노드 이동에 A사 노드를 부모로 / A사 노드를 B사 이름으로 수정
        long bArea = id(mvc.perform(json(post(HIER), "{\"plantCd\":\"P1\",\"nodeType\":\"AREA\",\"nodeCd\":\"B1\",\"nodeNm\":\"b\"}").with(b)));
        long bLine = id(mvc.perform(json(post(HIER), "{\"plantCd\":\"P1\",\"parentId\":" + bArea
                + ",\"nodeType\":\"LINE\",\"nodeCd\":\"BL\",\"nodeNm\":\"b\"}").with(b)));
        mvc.perform(json(put(HIER + "/" + bLine), "{\"parentId\":" + aArea + ",\"nodeCd\":\"BL\",\"nodeNm\":\"b\",\"rowVer\":0}").with(b))
                .andExpect(jsonPath("$.errors[0].code").value("NOT_FOUND"));
        mvc.perform(json(put(HIER + "/" + aArea), "{\"parentId\":null,\"nodeCd\":\"A-ONLY\",\"nodeNm\":\"hacked\",\"rowVer\":0}").with(b))
                .andExpect(status().isNotFound());
        // 변경 이력: A사 노드 키로 B사가 조회 → 0건
        mvc.perform(get("/api/platform/audit").param("table", "mst_equip_hier").param("key", String.valueOf(aArea)).with(b))
                .andExpect(jsonPath("$.total").value(0));
        assertThat(accounts.query(comp, "select node_nm from mst_equip_hier where node_id = ?", String.class, aArea))
                .isEqualTo("A-ONLY 이름");
    }

    @Test
    void 엑셀_내보내기_수식주입_방지_열선택_권한() throws Exception {
        long a = id(createNm("P1", null, "AREA", "F1", "=HYPERLINK(\\\"http://x\\\",\\\"y\\\")"));
        node("P1", a, "LINE", "L1");
        MvcResult x = mvc.perform(json(post(HIER + "/export"), "{\"plantCd\":\"P1\",\"columns\":[\"nodeCd\",\"nodeNm\",\"path\"]}")
                .with(admin)).andExpect(status().isOk()).andReturn();
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(x.getResponse().getContentAsByteArray()))) {
            Sheet s = wb.getSheetAt(0);
            assertThat(s.getLastRowNum()).isEqualTo(2);
            assertThat(s.getRow(0).getLastCellNum()).isEqualTo((short) 3);
            assertThat(s.getRow(1).getCell(1).getCellType()).isEqualTo(CellType.STRING);
            assertThat(s.getRow(1).getCell(1).getStringCellValue()).doesNotStartWith("=");
            assertThat(s.getRow(2).getCell(2).getStringCellValue()).isEqualTo("P1/F1/L1");
        }
        // 없는 열 이름 / 공장 없음
        mvc.perform(json(post(HIER + "/export"), "{\"plantCd\":\"P1\",\"columns\":[\"node_cd; drop\"]}").with(admin))
                .andExpect(status().isBadRequest());
        mvc.perform(json(post(HIER + "/export"), "{}").with(admin)).andExpect(status().isBadRequest());
        // 조회만 권한 → 내보내기·저장 403
        long reader = accounts.newUser(comp, "reader1", PW);
        accounts.grant(comp, reader, "READER", "mst-equip-hier", "SEARCH");
        RequestPostProcessor r = accounts.as(comp, reader, "reader1");
        mvc.perform(json(post(HIER + "/export"), "{\"plantCd\":\"P1\"}").with(r)).andExpect(status().isForbidden());
        mvc.perform(json(post(HIER), "{\"plantCd\":\"P1\",\"nodeType\":\"AREA\",\"nodeCd\":\"R\",\"nodeNm\":\"r\"}").with(r))
                .andExpect(status().isForbidden());
        mvc.perform(get(HIER).param("plantCd", "P1").with(r)).andExpect(status().isOk());
        mvc.perform(get("/api/master/plants").with(r)).andExpect(status().isForbidden());
    }

    @Test
    void 권한_조합_저장만_있고_조회없음_공장_SAVE만() throws Exception {
        long u = accounts.newUser(comp, "saver1", PW);
        accounts.grant(comp, u, "SAVER", "mst-equip-hier", "SAVE");
        accounts.grant(comp, u, "SAVER", "mst-plant", "SAVE");
        RequestPostProcessor s = accounts.as(comp, u, "saver1");
        // 관찰: 조회 권한 없이 저장 권한만으로 API 저장은 된다(화면은 SEARCH 없이 못 연다) — 권한 모델 그대로
        mvc.perform(json(post(HIER), "{\"plantCd\":\"P1\",\"nodeType\":\"AREA\",\"nodeCd\":\"S1\",\"nodeNm\":\"s\"}").with(s))
                .andExpect(status().isOk());
        mvc.perform(get(HIER).param("plantCd", "P1").with(s)).andExpect(status().isForbidden());
        mvc.perform(get("/page/mst-equip-hier").with(s)).andExpect(status().isForbidden());
        mvc.perform(json(post("/api/master/plants"), "{\"plantCd\":\"P9\",\"plantNm\":\"9\"}").with(s)).andExpect(status().isOk());
    }

    @Test
    void 공장_코드_20자_21자_점_시간대_오프셋_약어_옛버전_rowVer없음() throws Exception {
        mvc.perform(json(post("/api/master/plants"), "{\"plantCd\":\"" + "P".repeat(20) + "\",\"plantNm\":\"x\"}").with(admin))
                .andExpect(status().isOk());
        mvc.perform(json(post("/api/master/plants"), "{\"plantCd\":\"" + "P".repeat(21) + "\",\"plantNm\":\"x\"}").with(admin))
                .andExpect(jsonPath("$.errors[0].code").value("FORMAT"));
        mvc.perform(json(post("/api/master/plants"), "{\"plantCd\":\"P.1\",\"plantNm\":\"x\"}").with(admin))
                .andExpect(jsonPath("$.errors[0].code").value("FORMAT"));
        // 시간대: 지역 ID·오프셋은 저장, 약어(EST·KST)는 CODE
        for (String tz : new String[] {"America/Detroit", "+09:00", "UTC+9", "Z"}) {
            String cd = "T" + Math.abs(tz.hashCode() % 1000);
            mvc.perform(json(post("/api/master/plants"), "{\"plantCd\":\"" + cd + "\",\"plantNm\":\"x\",\"timeZone\":\"" + tz + "\"}")
                    .with(admin)).andExpect(status().isOk());
        }
        for (String tz : new String[] {"EST", "KST", "Asia/Seoul;drop", "../../etc"}) {
            mvc.perform(json(post("/api/master/plants"), "{\"plantCd\":\"TX\",\"plantNm\":\"x\",\"timeZone\":\"" + tz + "\"}")
                    .with(admin)).andExpect(jsonPath("$.errors[0].code").value("CODE"));
        }
        mvc.perform(json(put("/api/master/plants/P1"), "{\"plantNm\":\"x\"}").with(admin)).andExpect(status().isBadRequest());
        mvc.perform(json(put("/api/master/plants/P1"), "{\"plantNm\":\"x\",\"rowVer\":99}").with(admin)).andExpect(status().isConflict());
        // 경로의 공장 코드는 대문자 정리 안 함 → 소문자는 404
        mvc.perform(json(put("/api/master/plants/p1"), "{\"plantNm\":\"x\",\"rowVer\":0}").with(admin)).andExpect(status().isNotFound());
    }
}
