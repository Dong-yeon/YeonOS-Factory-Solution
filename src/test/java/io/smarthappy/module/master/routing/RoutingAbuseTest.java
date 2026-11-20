package io.smarthappy.module.master.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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
 * QA P1 5 — 공정·라우팅 이상 사용 (docs/qa/bom-routing-3b-5.md). TESTING.md "P1 5" 표에 없는 것:
 * 순번 순환 교환·조건 코드 교환, 단계 200·조건 50 경계와 대량 저장, 조건 id 다른 단계로, 동시 저장·저장 중 확정,
 * 미래 리비전 앞 끼워 넣기, 공정·작업장 사용 중지 뒤 새 리비전, 외주 전환, 확정 작업장 노드 이동, BOM 연결 점검 기준일,
 * 입력 경계, A/B사 남은 API, 직접 SQL, 권한·엑셀.
 */
@AutoConfigureMockMvc
class RoutingAbuseTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";
    private static final String R = "/api/master/routings";

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
    long line2;
    long cell;
    LocalDate today;

    @BeforeEach
    void setUp() throws Exception {
        comp = accounts.newComp("QR");
        long userId = accounts.newUser(comp, "admin1", PW);
        accounts.grant(comp, userId, "MASTER", "mst-proc", "SEARCH", "SAVE");
        accounts.grant(comp, userId, "MASTER", "mst-routing", "SEARCH", "SAVE", "APPROVE", "DELETE", "EXCEL");
        accounts.grant(comp, userId, "MASTER", "mst-equip-hier", "SEARCH", "SAVE");
        admin = accounts.as(comp, userId, "admin1");
        accounts.addPlant(comp, "P1", "1공장");
        accounts.addPlant(comp, "P2", "2공장");
        area = node("P1", null, "AREA", "BODY");
        line = node("P1", area, "LINE", "WL1");
        line2 = node("P1", area, "LINE", "WL2");
        cell = node("P1", line, "CELL", "WL1-C1");
        item("FG1");
        item("RM1");
        proc("PRESS", "PRESS", "N");
        proc("WELD", "WELD", "N");
        proc("PLATING", "OTHER", "Y");
        today = LocalDate.now(ZoneId.of("Asia/Seoul"));
    }

    // ---------- 도우미 (RoutingTest 와 같은 모양) ----------

    private long node(String plant, Long parent, String type, String cd) {
        return accounts.query(comp, """
                insert into mst_equip_hier (comp_cd, plant_cd, parent_id, node_type, node_cd, node_nm, created_by, updated_by)
                values (?, ?, ?, ?, ?, ?, 't', 't') returning node_id""", Long.class, comp, plant, parent, type, cd, cd);
    }

    private void item(String cd) {
        accounts.exec(comp, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, created_by, updated_by)
                values (?, ?, ?, 'FG', 'MAKE', 'EA', 't', 't')""", comp, cd, cd + " 품목");
    }

    private long proc(String cd, String cat, String outsrc) throws Exception {
        return id(postJson("/api/master/procs", "{\"procCd\":\"" + cd + "\",\"procNm\":\"" + cd + " 공정\",\"procCat\":\"" + cat
                + "\",\"outsrcYn\":\"" + outsrc + "\"}"));
    }

    private long procId(String cd) {
        return accounts.query(comp, "select proc_id from mst_proc where proc_cd = ?", Long.class, cd);
    }

    private ResultActions procUpdate(String cd, String body) throws Exception {
        int v = accounts.query(comp, "select row_ver from mst_proc where proc_cd = ?", Integer.class, cd);
        return putJson("/api/master/procs/" + procId(cd), body.replace("}", ",\"rowVer\":" + v + "}"));
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

    private long routing(String itemCd, String plant) throws Exception {
        return id(postJson(R, "{\"itemCd\":\"" + itemCd + "\",\"plantCd\":\"" + plant + "\"}"));
    }

    private int rowVer(long routingId) {
        return accounts.query(comp, "select row_ver from mst_routing where routing_id = ?", Integer.class, routingId);
    }

    private ResultActions save(long routingId, String steps) throws Exception {
        return putJson(R + "/" + routingId + "/steps?rowVer=" + rowVer(routingId), "{\"steps\":[" + steps + "]}");
    }

    private static String step(String key, Long stepId, int seq, String proc, Long node, String ct, String params) {
        return "{\"rowKey\":\"" + key + "\",\"stepId\":" + stepId + ",\"opSeq\":" + seq + ",\"procCd\":\"" + proc + "\",\"nodeId\":"
                + node + ",\"stdCtSec\":" + (ct == null ? "null" : "\"" + ct + "\"") + ",\"params\":[" + params + "]}";
    }

    private static String param(String key, Long paramId, String cd, String lsl, String target, String usl) {
        return "{\"rowKey\":\"" + key + "\",\"paramId\":" + paramId + ",\"paramCd\":\"" + cd + "\",\"paramNm\":\"" + cd
                + "\",\"unitNm\":\"kA\",\"lsl\":" + q(lsl) + ",\"targetVal\":" + q(target) + ",\"usl\":" + q(usl) + "}";
    }

    private static String q(String v) {
        return v == null ? "null" : "\"" + v + "\"";
    }

    private long stepId(long routingId, int opSeq) {
        return accounts.query(comp, "select step_id from mst_routing_step where routing_id = ? and op_seq = ?", Long.class,
                routingId, opSeq);
    }

    private long paramId(long stepId, String cd) {
        return accounts.query(comp, "select param_id from mst_routing_param where step_id = ? and param_cd = ?", Long.class,
                stepId, cd);
    }

    private ResultActions action(long routingId, String act) throws Exception {
        return postJson(R + "/" + routingId + "/" + act, "{\"rowVer\":" + rowVer(routingId) + "}");
    }

    private long released(String item, String steps) throws Exception {
        long r = routing(item, "P1");
        save(r, steps).andExpect(status().isOk());
        action(r, "release").andExpect(status().isOk());
        return r;
    }

    private void constraint(String name, Runnable sql) {
        Throwable t = catchThrowable(sql::run);
        assertThat(t).as("DB 가 막아야 한다: " + name).isNotNull();
        assertThat(PgErrors.constraint(t)).as(String.valueOf(t)).contains(name);
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

    // =====================================================================
    // 1. 한 벌 저장 — 순번·조건 코드 교환, 조건 id 옮기기
    // =====================================================================

    @Test
    void 순번_세_개_돌려_바꾸기와_같은_단계_조건코드_맞바꾸기() throws Exception {
        long r = routing("FG1", "P1");
        save(r, step("a", null, 10, "PRESS", line, "10", param("p1", null, "TEMP", null, "200", null)
                + "," + param("p2", null, "PRESS", null, "5", null))
                + "," + step("b", null, 20, "WELD", cell, "20", "") + "," + step("c", null, 30, "WELD", line, "30", ""))
                .andExpect(status().isOk());
        long s10 = stepId(r, 10);
        long s20 = stepId(r, 20);
        long s30 = stepId(r, 30);
        long temp = paramId(s10, "TEMP");
        long press = paramId(s10, "PRESS");
        // 10→20, 20→30, 30→10 + TEMP↔PRESS 코드 교환 (둘 다 커밋 시점 유일 검사)
        save(r, step("S" + s10, s10, 20, "PRESS", line, "10", param("P" + temp, temp, "PRESS", null, "200", null)
                + "," + param("P" + press, press, "TEMP", null, "5", null))
                + "," + step("S" + s20, s20, 30, "WELD", cell, "20", "") + "," + step("S" + s30, s30, 10, "WELD", line, "30", ""))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.steps").value(3)).andExpect(jsonPath("$.data.params").value(2));
        assertThat(accounts.query(comp, "select param_cd from mst_routing_param where param_id = ?", String.class, temp))
                .isEqualTo("PRESS");
        mvc.perform(get(R + "/" + r).with(admin)).andExpect(jsonPath("$.data.steps[*].opSeq")
                .value(org.hamcrest.Matchers.contains(10, 20, 30)));
    }

    @Test
    void 조건_id_를_다른_단계로_옮기거나_두번_쓰거나_다른_라우팅_것이면_400_반영_0() throws Exception {
        long r = routing("FG1", "P1");
        save(r, step("a", null, 10, "PRESS", line, "10", param("p1", null, "TEMP", null, "200", null))
                + "," + step("b", null, 20, "WELD", cell, "20", "")).andExpect(status().isOk());
        long s10 = stepId(r, 10);
        long s20 = stepId(r, 20);
        long temp = paramId(s10, "TEMP");
        // 10 단계의 조건을 20 단계 아래로 (같은 id)
        save(r, step("S" + s10, s10, 10, "PRESS", line, "10", "")
                + "," + step("S" + s20, s20, 20, "WELD", cell, "20", param("P" + temp, temp, "TEMP", null, "200", null)))
                .andExpect(status().isBadRequest());
        // 새 단계 아래로
        save(r, step("S" + s10, s10, 10, "PRESS", line, "10", "") + "," + step("S" + s20, s20, 20, "WELD", cell, "20", "")
                + "," + step("n", null, 30, "WELD", line, "5", param("P" + temp, temp, "TEMP", null, "200", null)))
                .andExpect(status().isBadRequest());
        // 같은 단계에 같은 id 두 번
        save(r, step("S" + s10, s10, 10, "PRESS", line, "10", param("P" + temp, temp, "TEMP", null, "200", null)
                + "," + param("Q" + temp, temp, "TEMP2", null, "1", null))).andExpect(status().isBadRequest());
        // 다른 라우팅의 조건 id
        long r2 = routing("RM1", "P1");
        save(r2, step("a", null, 10, "PRESS", line, "10", param("P" + temp, temp, "TEMP", null, "200", null)))
                .andExpect(status().isBadRequest());
        assertThat(accounts.query(comp, "select step_id from mst_routing_param where param_id = ?", Long.class, temp))
                .isEqualTo(s10);
        assertThat(accounts.query(comp, "select count(*) from mst_routing_step where routing_id = ?", Integer.class, r2)).isZero();
    }

    @Test
    void 행키_형식_중복_null_단계_null_조건은_400() throws Exception {
        long r = routing("FG1", "P1");
        save(r, step("a b", null, 10, "PRESS", line, "10", "")).andExpect(status().isBadRequest());
        save(r, step("a", null, 10, "PRESS", line, "10", param("a", null, "T", null, "1", null))).andExpect(status().isBadRequest());
        save(r, "null").andExpect(status().isBadRequest());
        save(r, "{\"rowKey\":\"a\",\"opSeq\":10,\"procCd\":\"PRESS\",\"params\":[null]}").andExpect(status().isBadRequest());
        save(r, step("x".repeat(41), null, 10, "PRESS", line, "10", "")).andExpect(status().isBadRequest());
        assertThat(accounts.query(comp, "select count(*) from mst_routing_step", Integer.class)).isZero();
    }

    // =====================================================================
    // 2. 경계 — 단계 200·조건 50, 대량 저장
    // =====================================================================

    private static String steps(int n, int paramsEach) {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= n; i++) {
            StringBuilder ps = new StringBuilder();
            for (int j = 1; j <= paramsEach; j++) {
                ps.append(j > 1 ? "," : "").append(param("p" + i + "_" + j, null, "C" + j, null, "" + j, null));
            }
            sb.append(i > 1 ? "," : "").append(step("s" + i, null, i, "PRESS", null, "1.5", ps.toString()));
        }
        return sb.toString();
    }

    @Test
    void 단계_200_통과_201_거부_조건_50_통과_51_거부() throws Exception {
        long r = routing("FG1", "P1");
        save(r, steps(201, 0)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_VALUE"));
        save(r, steps(1, 51)).andExpect(status().isBadRequest());
        save(r, steps(1, 50)).andExpect(status().isOk()).andExpect(jsonPath("$.data.params").value(50));
        save(r, steps(200, 0)).andExpect(status().isOk());
        assertThat(accounts.query(comp, "select count(*) from mst_routing_step where routing_id = ?", Integer.class, r))
                .isEqualTo(200);
        assertThat(accounts.query(comp, "select count(*) from mst_routing_param", Integer.class)).isZero();   // 1단계 조건은 같이 지워짐
        // 순번 상한 9999 / 10000
        save(r, step("a", null, 9999, "PRESS", line, "1", "")).andExpect(status().isOk());
        save(r, step("a", null, 10000, "PRESS", line, "1", "")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("CODE"));
    }

    @Test
    void 대량_200단계_x_조건50_만건_한벌_저장_조회_다시저장_시간() throws Exception {
        long r = routing("FG1", "P1");
        String big = steps(200, 50);
        long t0 = System.nanoTime();
        save(r, big).andExpect(status().isOk()).andExpect(jsonPath("$.data.params").value(10000));
        long saveMs = (System.nanoTime() - t0) / 1_000_000;
        long t1 = System.nanoTime();
        MvcResult d = mvc.perform(get(R + "/" + r).with(admin)).andExpect(status().isOk()).andReturn();
        long getMs = (System.nanoTime() - t1) / 1_000_000;
        int bytes = d.getResponse().getContentAsByteArray().length;
        // 같은 내용을 id 와 함께 다시 보내면 바뀐 행 0 (화면의 "저장" 을 다시 누른 경우)
        String body = d.getResponse().getContentAsString();
        StringBuilder sb = new StringBuilder();
        java.util.List<java.util.Map<String, Object>> ss = JsonPath.read(body, "$.data.steps");
        for (var s : ss) {
            StringBuilder ps = new StringBuilder();
            for (var p : (java.util.List<java.util.Map<String, Object>>) s.get("params")) {
                ps.append(ps.length() == 0 ? "" : ",").append(param("P" + p.get("paramId"), ((Number) p.get("paramId")).longValue(),
                        (String) p.get("paramCd"), null, p.get("targetVal").toString(), null));
            }
            sb.append(sb.length() == 0 ? "" : ",").append(step("S" + s.get("stepId"), ((Number) s.get("stepId")).longValue(),
                    (Integer) s.get("opSeq"), "PRESS", null, "1.5", ps.toString()));
        }
        long t2 = System.nanoTime();
        save(r, sb.toString()).andExpect(status().isOk()).andExpect(jsonPath("$.data.steps").value(0))
                .andExpect(jsonPath("$.data.params").value(0));
        long resaveMs = (System.nanoTime() - t2) / 1_000_000;
        System.out.println("[qa-perf] 라우팅 200단계×50조건: 첫 저장 " + saveMs + "ms, 상세 " + getMs + "ms(" + bytes / 1024
                + "KB), 같은 내용 다시 저장 " + resaveMs + "ms");
        assertThat(saveMs).isLessThan(60_000);
    }

    @Test
    void 입력_경계_사이클타임_캐비티_준비_조건코드_단위_비고() throws Exception {
        long r = routing("FG1", "P1");
        save(r, "{\"rowKey\":\"a\",\"opSeq\":10,\"procCd\":\"PRESS\",\"nodeId\":" + line
                + ",\"stdCtSec\":\"1234567890\",\"unitsPerCycle\":1000,\"setupMin\":\"1000000\",\"remark\":\"" + "가".repeat(501)
                + "\",\"params\":[{\"rowKey\":\"p\",\"paramCd\":\"" + "A".repeat(31) + "\",\"paramNm\":\"x\",\"unitNm\":\""
                + "u".repeat(21) + "\",\"targetVal\":\"1e3\",\"sortNo\":-1}]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.rowKey=='a')].field").value(org.hamcrest.Matchers.hasItems("stdCtSec",
                        "unitsPerCycle", "setupMin", "remark")))
                .andExpect(jsonPath("$.errors[?(@.rowKey=='p')].field").value(org.hamcrest.Matchers.hasItems("paramCd", "unitNm",
                        "targetVal", "sortNo")));
        // 최대값은 저장
        save(r, "{\"rowKey\":\"a\",\"opSeq\":10,\"procCd\":\"PRESS\",\"nodeId\":" + line
                + ",\"stdCtSec\":\"999999999.999\",\"unitsPerCycle\":999,\"setupMin\":\"999999.99\",\"remark\":\"" + "가".repeat(500)
                + "\",\"params\":[{\"rowKey\":\"p\",\"paramCd\":\"" + "A".repeat(30) + "\",\"paramNm\":\"x\",\"unitNm\":\"°C\""
                + ",\"lsl\":\"-999999999999.999999\",\"usl\":\"999999999999.999999\"}]}")
                .andExpect(status().isOk());
        // 순번 문자 → 400 (500 아님)
        save(r, "{\"rowKey\":\"a\",\"opSeq\":\"십\",\"procCd\":\"PRESS\",\"params\":[]}").andExpect(status().isBadRequest());
    }

    /** 관찰: 정수 칸(순번·캐비티·조건 순서)에 JSON 소수를 보내면 400 이 아니라 소수점 아래를 버리고 저장한다 (Jackson 기본 설정) */
    @Test
    void 관찰_정수칸에_JSON_소수는_버림으로_저장된다() throws Exception {
        long r = routing("FG1", "P1");
        save(r, "{\"rowKey\":\"a\",\"opSeq\":10.9,\"procCd\":\"PRESS\",\"nodeId\":" + line
                + ",\"stdCtSec\":\"1\",\"unitsPerCycle\":2.7,\"params\":[]}").andExpect(status().isOk());
        assertThat(accounts.query(comp, "select op_seq || '/' || units_per_cycle from mst_routing_step where routing_id = ?",
                String.class, r)).isEqualTo("10/2");
    }

    // =====================================================================
    // 3. 동시성 — 같은 머리 버전 두 저장, 저장 중 확정
    // =====================================================================

    @Test
    void 동시_한벌_저장_두사람_뒤쪽은_머리잠금_기다린뒤_409_단계_섞이지_않음() throws Exception {
        long r = routing("FG1", "P1");
        int v = rowVer(r);
        long press = procId("PRESS");
        CountDownLatch locked = new CountDownLatch(1);
        CompletableFuture<Void> first = holdTx(() -> {
            jdbc.update("update mst_routing set row_ver = row_ver + 1 where routing_id = ?", r);
            jdbc.update("""
                    insert into mst_routing_step (comp_cd, routing_id, op_seq, proc_id, created_by, updated_by)
                    values (?, ?, 10, ?, 't', 't')""", comp, r, press);
        }, locked, 1500);
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<MvcResult> second = async(json(put(R + "/" + r + "/steps?rowVer=" + v),
                "{\"steps\":[" + step("b", null, 20, "WELD", cell, "5", "") + "]}").with(admin));
        first.get(10, TimeUnit.SECONDS);
        MvcResult res = second.get(10, TimeUnit.SECONDS);
        assertThat(res.getResponse().getStatus()).as(res.getResponse().getContentAsString()).isEqualTo(409);
        assertThat(accounts.query(comp, "select count(*) from mst_routing_step where routing_id = ?", Integer.class, r))
                .isEqualTo(1);
    }

    @Test
    void 동시_저장_중에_확정하면_확정이_409_검사_안된_단계로_확정되지_않는다() throws Exception {
        long r = routing("FG1", "P1");
        save(r, step("a", null, 10, "PRESS", line, "10", "")).andExpect(status().isOk());
        int v = rowVer(r);
        long weld = procId("WELD");
        CountDownLatch locked = new CountDownLatch(1);
        // 다른 사람이 작업장·CT 없는 자체 공정 단계를 추가 중(커밋 전)
        CompletableFuture<Void> first = holdTx(() -> {
            jdbc.update("update mst_routing set row_ver = row_ver + 1 where routing_id = ?", r);
            jdbc.update("""
                    insert into mst_routing_step (comp_cd, routing_id, op_seq, proc_id, created_by, updated_by)
                    values (?, ?, 20, ?, 't', 't')""", comp, r, weld);
        }, locked, 1500);
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<MvcResult> rel = async(json(post(R + "/" + r + "/release"), "{\"rowVer\":" + v + "}").with(admin));
        first.get(10, TimeUnit.SECONDS);
        MvcResult res = rel.get(10, TimeUnit.SECONDS);
        assertThat(res.getResponse().getStatus()).as(res.getResponse().getContentAsString()).isEqualTo(409);
        assertThat(accounts.query(comp, "select status from mst_routing where routing_id = ?", String.class, r)).isEqualTo("DRAFT");
    }

    @Test
    void 동시_새리비전_두번은_하나만() throws Exception {
        long r = released("FG1", step("a", null, 10, "PRESS", line, "10", ""));
        String b = "{\"rowVer\":" + rowVer(r) + "}";
        CompletableFuture<MvcResult> x = async(json(post(R + "/" + r + "/new-revision"), b).with(admin));
        CompletableFuture<MvcResult> y = async(json(post(R + "/" + r + "/new-revision"), b).with(admin));
        int sx = x.get(20, TimeUnit.SECONDS).getResponse().getStatus();
        int sy = y.get(20, TimeUnit.SECONDS).getResponse().getStatus();
        assertThat(new int[] {sx, sy}).containsExactlyInAnyOrder(200, 409);
        assertThat(accounts.query(comp, "select count(*) from mst_routing where status = 'DRAFT'", Integer.class)).isEqualTo(1);
    }

    // =====================================================================
    // 4. 리비전·기간
    // =====================================================================

    @Test
    void 미래_확정본_앞에_끼워넣는_리비전은_겹침_2999년_새리비전은_형식오류() throws Exception {
        long r = routing("FG1", "P1");
        putJson(R + "/" + r, "{\"validFrom\":\"" + today.plusDays(3) + "\",\"rowVer\":" + rowVer(r) + "}").andExpect(status().isOk());
        save(r, step("a", null, 10, "PRESS", line, "10", "")).andExpect(status().isOk());
        action(r, "release").andExpect(status().isOk());
        long r2 = id(postJson(R + "/" + r + "/new-revision", "{\"rowVer\":" + rowVer(r) + ",\"validFrom\":\"" + today.plusDays(1)
                + "\"}"));
        action(r2, "release").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ROUTING_PERIOD_OVERLAP"));
        // 미래 확정본 기본 시작일 = 그 다음 날 (+4)
        action(r2, "obsolete").andExpect(status().isOk());
        long r3 = id(action(r, "new-revision"));
        assertThat(accounts.query(comp, "select valid_from::text from mst_routing where routing_id = ?", String.class, r3))
                .isEqualTo(today.plusDays(4).toString());
        action(r3, "obsolete").andExpect(status().isOk());
        // 2999-12-31 확정본에서 시작일 비운 새 리비전 → 3000 년이 아니라 FORMAT
        long far = routing("RM1", "P1");
        putJson(R + "/" + far, "{\"validFrom\":\"2999-12-31\",\"rowVer\":" + rowVer(far) + "}").andExpect(status().isOk());
        save(far, step("a", null, 10, "PRESS", line, "10", "")).andExpect(status().isOk());
        action(far, "release").andExpect(status().isOk());
        action(far, "new-revision").andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("validFrom"));
    }

    @Test
    void 머리_수정_리비전_소문자_중복_시작일_범위_밖() throws Exception {
        long r = released("FG1", step("a", null, 10, "PRESS", line, "10", ""));
        long d = id(action(r, "new-revision"));
        putJson(R + "/" + d, "{\"rev\":\"a0\",\"rowVer\":" + rowVer(d) + "}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("DUPLICATE"));
        putJson(R + "/" + d, "{\"validFrom\":\"3000-01-01\",\"rowVer\":" + rowVer(d) + "}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("validFrom"));
        putJson(R + "/" + d, "{\"rev\":\"b-1\",\"rowVer\":" + rowVer(d) + "}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("FORMAT"));
        // 확정본 머리 수정 → 409
        putJson(R + "/" + r, "{\"remark\":\"x\",\"rowVer\":" + rowVer(r) + "}").andExpect(status().isConflict());
    }

    // =====================================================================
    // 5. 공정·작업장 사용 중지 뒤 새 리비전 · 외주 전환 · 노드 이동
    // =====================================================================

    /**
     * 결함 #5(수정됨): 확정 라우팅이 쓰는 공정을 사용 중지(허용)하면, 그 라우팅의 "새 리비전"(단계 복사)이 트리거
     * fk_mst_routing_step_proc_comp 에 걸려 400 procCd INACTIVE 로 아예 만들어지지 않는다. 사용자는 단종된 공정을 다른 공정으로
     * 바꾼 개정판을 만들 길이 없다(신규 라우팅에 단계·조건을 처음부터 다시 입력해야 함). 오류에 단계 행 키도 없어 어느 단계인지 모른다.
     * BOM 은 같은 상황(사용 중지 자품목)에서 새 리비전을 만들고 확정 검사가 행 단위로 알려 준다.
     */
    @Test
    void 결함5_공정_사용중지_뒤에도_새리비전은_만들어지고_확정검사가_그_단계를_알려야_한다() throws Exception {
        long r = released("FG1", step("a", null, 10, "PRESS", line, "10", param("p1", null, "T", null, "1", null))
                + "," + step("b", null, 20, "WELD", cell, "20", ""));
        procUpdate("PRESS", "{\"procNm\":\"프레스\",\"procCat\":\"PRESS\",\"useYn\":\"N\"}").andExpect(status().isOk());
        long d = id(action(r, "new-revision"));
        long s10 = stepId(d, 10);
        // 확정 검사가 그 단계에 알린다
        action(d, "release").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.rowKey=='S" + s10 + "')].field").value(hasItem("procCd")));
        // 단계 10 의 공정을 사용 중인 공정(WELD)으로 바꾸면(조건은 그대로) 확정 가능
        long s20 = stepId(d, 20);
        long p1 = paramId(s10, "T");
        save(d, step("S" + s10, s10, 10, "WELD", line, "10", param("P" + p1, p1, "T", null, "1", null))
                + "," + step("S" + s20, s20, 20, "WELD", cell, "20", "")).andExpect(status().isOk());
        action(d, "release").andExpect(status().isOk());
        assertThat(accounts.query(comp, "select count(*) from mst_routing_param where step_id = ?", Integer.class, s10)).isEqualTo(1);
    }

    @Test
    void 폐기_라우팅의_작업장을_사용중지해도_새리비전은_만들어지고_확정검사가_알린다() throws Exception {
        long r = released("FG1", step("a", null, 10, "PRESS", cell, "10", ""));
        action(r, "obsolete").andExpect(status().isOk());
        accounts.exec(comp, "update mst_equip_hier set use_yn = 'N' where node_id = ?", cell);
        long d = id(action(r, "new-revision"));
        long s10 = stepId(d, 10);
        action(d, "release").andExpect(jsonPath("$.errors[?(@.rowKey=='S" + s10 + "')].code").value(hasItem("WORK_CENTER")));
        // 작업장을 사용 중인 라인으로 바꾸면 확정
        save(d, step("S" + s10, s10, 10, "PRESS", line, "10", "")).andExpect(status().isOk());
        action(d, "release").andExpect(status().isOk());
    }

    @Test
    void 처음_들이는_사용중지_공정_작업장은_여전히_거부() throws Exception {
        released("FG1", step("a", null, 10, "PRESS", cell, "10", ""));
        procUpdate("PRESS", "{\"procNm\":\"프레스\",\"procCat\":\"PRESS\",\"useYn\":\"N\"}").andExpect(status().isOk());
        // 다른 품목의 라우팅(FG1 리비전 아님) — API 는 칸 오류, 직접 SQL 은 트리거
        long other = routing("RM1", "P1");
        save(other, step("a", null, 10, "PRESS", line, "10", "")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("INACTIVE"));
        long press = procId("PRESS");
        constraint("fk_mst_routing_step_proc_comp", () -> accounts.exec(comp, """
                insert into mst_routing_step (comp_cd, routing_id, op_seq, proc_id, created_by, updated_by)
                values (?, ?, 10, ?, 't', 't')""", comp, other, press));
        // 같은 품목이라도 다른 공장(P2)이면 다른 라우팅 줄 — 거부
        long p2a = node("P2", null, "AREA", "A2");
        node("P2", p2a, "LINE", "L9");
        long p2 = routing("FG1", "P2");
        constraint("fk_mst_routing_step_proc_comp", () -> accounts.exec(comp, """
                insert into mst_routing_step (comp_cd, routing_id, op_seq, proc_id, created_by, updated_by)
                values (?, ?, 10, ?, 't', 't')""", comp, p2, press));
        // 사용 중지 작업장: FG1 라우팅(폐기)이 쓰던 셀을 다른 품목(RM1) 라우팅에 직접 SQL — 거부
        accounts.exec(comp, "update mst_proc set use_yn = 'Y' where proc_id = ?", press);
        long fg1 = accounts.query(comp, "select routing_id from mst_routing r join mst_item i using (item_id) where i.item_cd = 'FG1'"
                + " and r.plant_cd = 'P1'", Long.class);
        action(fg1, "obsolete").andExpect(status().isOk());
        accounts.exec(comp, "update mst_equip_hier set use_yn = 'N' where node_id = ?", cell);
        constraint("ck_mst_routing_step_node_type", () -> accounts.exec(comp, """
                insert into mst_routing_step (comp_cd, routing_id, op_seq, proc_id, node_id, created_by, updated_by)
                values (?, ?, 10, ?, ?, 't', 't')""", comp, other, press, cell));
    }

    @Test
    void 외주_전환_작성중만_쓰면_자유_확정본이_쓰면_거부_폐기본만_쓰면_허용() throws Exception {
        long r = routing("FG1", "P1");
        save(r, step("a", null, 10, "WELD", null, null, "")).andExpect(status().isOk());
        action(r, "release").andExpect(status().isBadRequest());                // 자체 공정: 작업장·CT 필수
        procUpdate("WELD", "{\"procNm\":\"용접\",\"procCat\":\"WELD\",\"outsrcYn\":\"Y\"}").andExpect(status().isOk());
        action(r, "release").andExpect(status().isOk());                        // 외주가 되면 면제
        procUpdate("WELD", "{\"procNm\":\"용접\",\"procCat\":\"WELD\",\"outsrcYn\":\"N\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("OUTSRC_IN_USE"));
        constraint("ck_mst_proc_outsrc_in_use", () -> accounts.exec(comp, "update mst_proc set outsrc_yn = 'N' where proc_cd = 'WELD'"));
        action(r, "obsolete").andExpect(status().isOk());
        procUpdate("WELD", "{\"procNm\":\"용접\",\"procCat\":\"WELD\",\"outsrcYn\":\"N\"}").andExpect(status().isOk());
    }

    @Test
    void 관찰_확정_라우팅의_작업장_노드를_다른_라인으로_옮기면_허용되고_확정본_경로가_바뀐다() throws Exception {
        long r = released("FG1", step("a", null, 10, "PRESS", cell, "10", ""));
        mvc.perform(get(R + "/" + r).with(admin)).andExpect(jsonPath("$.data.steps[0].nodePath").value("P1/BODY/WL1/WL1-C1"));
        int v = accounts.query(comp, "select row_ver from mst_equip_hier where node_id = ?", Integer.class, cell);
        putJson("/api/master/equip-hier/" + cell, "{\"parentId\":" + line2 + ",\"nodeCd\":\"WL1-C1\",\"nodeNm\":\"셀\",\"useYn\":\"Y\","
                + "\"rowVer\":" + v + "}").andExpect(status().isOk());
        mvc.perform(get(R + "/" + r).with(admin)).andExpect(jsonPath("$.data.steps[0].nodePath").value("P1/BODY/WL2/WL1-C1"));
        // 다른 공장으로는 못 간다
        long p2area = node("P2", null, "AREA", "A2");
        long p2line = node("P2", p2area, "LINE", "L9");
        putJson("/api/master/equip-hier/" + cell, "{\"parentId\":" + p2line + ",\"nodeCd\":\"WL1-C1\",\"nodeNm\":\"셀\",\"useYn\":\"Y\","
                + "\"rowVer\":" + (v + 1) + "}").andExpect(status().isBadRequest());
    }

    // =====================================================================
    // 6. BOM 연결 점검 기준일
    // =====================================================================

    private void bomOps(String rev, LocalDate from, LocalDate to, int... ops) {
        long b = accounts.query(comp, """
                insert into mst_bom (comp_cd, item_id, rev, valid_from, valid_to, created_by, updated_by)
                select ?, item_id, ?, ?, ?, 't', 't' from mst_item where item_cd = 'FG1' returning bom_id""",
                Long.class, comp, rev, from, to);
        for (int i = 0; i < ops.length; i++) {
            accounts.exec(comp, """
                    insert into mst_bom_line (comp_cd, bom_id, line_no, item_id, qty, unit_cd, issue_method, op_seq, created_by, updated_by)
                    select ?, ?, ?, item_id, 1, 'EA', 'BACKFLUSH', ?, 't', 't' from mst_item where item_cd = 'RM1'""",
                    comp, b, (i + 1) * 10, ops[i]);
        }
        accounts.exec(comp, "update mst_bom set status = 'RELEASED', released_at = now(), released_by = 't' where bom_id = ?", b);
    }

    @Test
    void BOM_연결점검_기준일은_라우팅_시작일_미래면_그날_BOM_종료당일은_다음_리비전() throws Exception {
        bomOps("A0", today.minusDays(10), today.plusDays(5), 10, 20);
        bomOps("A1", today.plusDays(5), null, 10, 30);
        long r = routing("FG1", "P1");
        save(r, step("a", null, 10, "PRESS", line, "10", "") + "," + step("b", null, 20, "WELD", cell, "20", "")).andExpect(status().isOk());
        // 오늘 시작 → A0 기준(빠진 것 없음). +5 부터 A1 의 30 이 빠지지만 여기선 안 보인다 — 관찰 O8
        mvc.perform(get(R + "/" + r).with(admin)).andExpect(jsonPath("$.data.unmatchedBomOps.length()").value(0));
        putJson(R + "/" + r, "{\"validFrom\":\"" + today.plusDays(4) + "\",\"rowVer\":" + rowVer(r) + "}").andExpect(status().isOk());
        mvc.perform(get(R + "/" + r).with(admin)).andExpect(jsonPath("$.data.unmatchedBomOps.length()").value(0));
        putJson(R + "/" + r, "{\"validFrom\":\"" + today.plusDays(5) + "\",\"rowVer\":" + rowVer(r) + "}").andExpect(status().isOk());
        mvc.perform(get(R + "/" + r).with(admin)).andExpect(jsonPath("$.data.unmatchedBomOps.length()").value(1))
                .andExpect(jsonPath("$.data.unmatchedBomOps[0].opSeq").value(30))
                .andExpect(jsonPath("$.data.unmatchedBomOps[0].bomRev").value("A1"));
    }

    // =====================================================================
    // 7. A/B사 — RoutingTest 에 없는 API
    // =====================================================================

    @Test
    void B사_사용자_A사_라우팅_확정_폐기_새리비전_머리수정_품목찾기_작업장목록_조건id_엑셀() throws Exception {
        long r = routing("FG1", "P1");
        save(r, step("a", null, 10, "PRESS", line, "10", param("p1", null, "T", null, "1", null))).andExpect(status().isOk());
        long aParam = paramId(stepId(r, 10), "T");
        long rel = released("RM1", step("a", null, 10, "PRESS", line, "10", ""));
        String other = accounts.newComp("QR");
        long bUser = accounts.newUser(other, "admin1", PW);
        accounts.grant(other, bUser, "MASTER", "mst-routing", "SEARCH", "SAVE", "APPROVE", "DELETE", "EXCEL");
        accounts.grant(other, bUser, "MASTER", "mst-proc", "SEARCH", "SAVE");
        accounts.addPlant(other, "P1", "B사 공장");
        RequestPostProcessor b = accounts.as(other, bUser, "admin1");
        int v = rowVer(r);
        mvc.perform(json(post(R + "/" + r + "/release"), "{\"rowVer\":" + v + "}").with(b)).andExpect(status().isNotFound());
        mvc.perform(json(post(R + "/" + r + "/obsolete"), "{\"rowVer\":" + v + "}").with(b)).andExpect(status().isNotFound());
        mvc.perform(json(post(R + "/" + rel + "/new-revision"), "{\"rowVer\":" + rowVer(rel) + "}").with(b))
                .andExpect(status().isNotFound());
        mvc.perform(json(put(R + "/" + r), "{\"remark\":\"x\",\"rowVer\":" + v + "}").with(b)).andExpect(status().isNotFound());
        mvc.perform(get(R + "/item").param("itemCd", "FG1").with(b)).andExpect(status().isNotFound());
        mvc.perform(get(R + "/places").param("plantCd", "P1").with(b)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
        mvc.perform(get(R + "/plants").with(b)).andExpect(jsonPath("$.data.length()").value(1));
        // A사 공정 id 로 B사 공정 수정
        mvc.perform(json(put("/api/master/procs/" + procId("PRESS")), "{\"procNm\":\"x\",\"procCat\":\"PRESS\",\"rowVer\":0}").with(b))
                .andExpect(status().isNotFound());
        // B사 라우팅 저장에 A사 조건 id
        accounts.exec(other, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, created_by, updated_by)
                values (?, 'BFG', 'B사', 'FG', 'MAKE', 'EA', 't', 't')""", other);
        mvc.perform(json(post("/api/master/procs"), "{\"procCd\":\"BP\",\"procNm\":\"B 공정\",\"procCat\":\"PRESS\"}").with(b))
                .andExpect(status().isOk());
        long br = id(mvc.perform(json(post(R), "{\"itemCd\":\"BFG\",\"plantCd\":\"P1\"}").with(b)));
        mvc.perform(json(put(R + "/" + br + "/steps?rowVer=0"), "{\"steps\":[" + step("a", null, 10, "BP", null, "1",
                param("P" + aParam, aParam, "T", null, "1", null)) + "]}").with(b)).andExpect(status().isBadRequest());
        // 엑셀: B사 것만 (머리글 1행 + B사 1행)
        byte[] x = mvc.perform(json(post(R + "/export"), "{}").with(b)).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsByteArray();
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(x))) {
            var s = wb.getSheetAt(0);
            int found = 0;
            for (int i = 0; i <= s.getLastRowNum(); i++) {
                var row = s.getRow(i);
                if (row != null && row.getCell(0) != null && "BFG".equals(row.getCell(0).toString())) {
                    found++;
                }
                if (row != null && row.getCell(0) != null) {
                    assertThat(row.getCell(0).toString()).isNotIn("FG1", "RM1");
                }
            }
            assertThat(found).isEqualTo(1);
        }
    }

    // =====================================================================
    // 8. 직접 SQL (앱 계정)
    // =====================================================================

    @Test
    void 직접SQL_조건범위_확정본조건_조건이동_단계이동_확정으로_INSERT_창고작업장_다른공장작업장_공정삭제() throws Exception {
        long d = routing("FG1", "P1");
        save(d, step("a", null, 10, "PRESS", line, "10", param("p1", null, "T", null, "1", null))
                + "," + step("b", null, 20, "WELD", cell, "10", "")).andExpect(status().isOk());
        long s10 = stepId(d, 10);
        long s20 = stepId(d, 20);
        long p = paramId(s10, "T");
        constraint("ck_mst_routing_param_range", () -> accounts.exec(comp, "update mst_routing_param set lsl = 5, usl = 1 where param_id = ?", p));
        constraint("ck_mst_routing_param_any", () -> accounts.exec(comp,
                "update mst_routing_param set lsl = null, usl = null, target_val = null where param_id = ?", p));
        constraint("ck_mst_routing_param_move", () -> accounts.exec(comp, "update mst_routing_param set step_id = ? where param_id = ?", s20, p));
        long d2 = routing("RM1", "P1");
        constraint("ck_mst_routing_step_move", () -> accounts.exec(comp, "update mst_routing_step set routing_id = ? where step_id = ?", d2, s20));
        long wh = node("P1", null, "WH", "WH1");
        constraint("ck_mst_routing_step_node_type", () -> accounts.exec(comp, "update mst_routing_step set node_id = ? where step_id = ?", wh, s20));
        long p2a = node("P2", null, "AREA", "A2");
        long p2l = node("P2", p2a, "LINE", "L9");
        constraint("fk_mst_routing_step_node_plant", () -> accounts.exec(comp, "update mst_routing_step set node_id = ? where step_id = ?", p2l, s20));
        constraint("ck_mst_routing_draft", () -> accounts.exec(comp, """
                insert into mst_routing (comp_cd, item_id, plant_cd, rev, status, valid_from, released_at, released_by, created_by, updated_by)
                select ?, item_id, 'P2', 'A0', 'RELEASED', ?, now(), 't', 't', 't' from mst_item where item_cd = 'FG1'""", comp, today));
        Throwable del = catchThrowable(() -> accounts.exec(comp, "delete from mst_proc where proc_cd = 'WELD'"));
        assertThat(del).isNotNull();
        assertThat(PgErrors.sqlState(del).orElse("") + PgErrors.constraint(del).orElse("")).containsAnyOf("42501", "ck_mst_proc_no_delete");
        // 확정 뒤: 조건 추가·삭제
        action(d, "release").andExpect(status().isOk());
        constraint("ck_mst_routing_param_draft", () -> accounts.exec(comp, """
                insert into mst_routing_param (comp_cd, step_id, param_cd, param_nm, target_val, created_by, updated_by)
                values (?, ?, 'X', 'x', 1, 't', 't')""", comp, s20));
        constraint("ck_mst_routing_param_draft", () -> accounts.exec(comp, "delete from mst_routing_param where param_id = ?", p));
    }

    /** 관찰: 확정 조건(단계 1+, 시작일 ≥ 오늘)은 서비스에만 있어 앱 계정 직접 SQL 로 빈·과거 라우팅을 확정할 수 있다(BOM 과 같은 설계) */
    @Test
    void 관찰_직접SQL_로_단계없는_과거_라우팅을_확정할_수_있다() throws Exception {
        long d = routing("FG1", "P1");
        accounts.exec(comp, "update mst_routing set valid_from = ? where routing_id = ?", today.minusDays(30), d);
        accounts.exec(comp, "update mst_routing set status = 'RELEASED', released_at = now(), released_by = 't' where routing_id = ?", d);
        assertThat(accounts.query(comp, "select status from mst_routing where routing_id = ?", String.class, d)).isEqualTo("RELEASED");
    }

    // =====================================================================
    // 9. 권한 조합 · 엑셀 입력
    // =====================================================================

    @Test
    void 권한_조회만은_생성_머리수정_구조저장_새리비전_403_공정_조회만은_등록_403() throws Exception {
        long r = released("FG1", step("a", null, 10, "PRESS", line, "10", ""));
        long d = routing("RM1", "P1");
        long u = accounts.newUser(comp, "viewer", PW);
        accounts.grant(comp, u, "VIEW", "mst-routing", "SEARCH");
        accounts.grant(comp, u, "VIEW", "mst-proc", "SEARCH");
        RequestPostProcessor v = accounts.as(comp, u, "viewer");
        mvc.perform(get(R + "/" + r).with(v)).andExpect(status().isOk());
        mvc.perform(json(post(R), "{\"itemCd\":\"FG1\",\"plantCd\":\"P2\"}").with(v)).andExpect(status().isForbidden());
        mvc.perform(json(put(R + "/" + d), "{\"remark\":\"x\",\"rowVer\":" + rowVer(d) + "}").with(v)).andExpect(status().isForbidden());
        mvc.perform(json(put(R + "/" + d + "/steps?rowVer=" + rowVer(d)), "{\"steps\":[]}").with(v)).andExpect(status().isForbidden());
        mvc.perform(json(post(R + "/" + r + "/new-revision"), "{\"rowVer\":" + rowVer(r) + "}").with(v))
                .andExpect(status().isForbidden());
        mvc.perform(json(post(R + "/export"), "{}").with(v)).andExpect(status().isForbidden());
        mvc.perform(get("/api/master/procs").with(v)).andExpect(status().isOk());
        mvc.perform(json(post("/api/master/procs"), "{\"procCd\":\"NEW\",\"procNm\":\"n\",\"procCat\":\"PRESS\"}").with(v))
                .andExpect(status().isForbidden());
        // 저장만(조회 없음): 상세는 403, 구조 저장은 된다(액션 단위 권한)
        long s = accounts.newUser(comp, "saver", PW);
        accounts.grant(comp, s, "SAVER", "mst-routing", "SAVE");
        RequestPostProcessor sv = accounts.as(comp, s, "saver");
        mvc.perform(get(R + "/" + d).with(sv)).andExpect(status().isForbidden());
        mvc.perform(json(put(R + "/" + d + "/steps?rowVer=" + rowVer(d)), "{\"steps\":[" + step("a", null, 10, "PRESS", line, "1", "")
                + "]}").with(sv)).andExpect(status().isOk());
    }

    @Test
    void 엑셀_목록_입력_정렬_조작_상태값_열_화이트리스트() throws Exception {
        released("FG1", step("a", null, 10, "PRESS", line, "10", ""));
        mvc.perform(json(post(R + "/export"), "{\"sort\":[\"itemCd;drop table x,asc\"]}").with(admin)).andExpect(status().isBadRequest());
        mvc.perform(json(post(R + "/export"), "{\"status\":\"DELETED\"}").with(admin)).andExpect(status().isBadRequest());
        mvc.perform(get(R).param("status", "RELEASED' or '1'='1").with(admin)).andExpect(status().isBadRequest());
        mvc.perform(get(R).param("itemCd", "%").with(admin)).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
        // 모르는 열 이름은 400 (조용히 무시하지도 않는다)
        mvc.perform(json(post(R + "/export"), "{\"columns\":[\"itemCd\",\"<script>\",\"row_ver\"]}").with(admin))
                .andExpect(status().isBadRequest());
        byte[] x = mvc.perform(json(post(R + "/export"), "{\"columns\":[\"itemCd\",\"rev\"]}").with(admin))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(x))) {
            var s = wb.getSheetAt(0);
            StringBuilder all = new StringBuilder();
            for (int i = 0; i <= s.getLastRowNum(); i++) {
                if (s.getRow(i) != null) {
                    s.getRow(i).forEach(c -> all.append(c.toString()).append('|'));
                }
            }
            assertThat(all.toString()).doesNotContain("<script>").doesNotContain("row_ver").contains("FG1");
        }
    }
}
