package io.smarthappy.module.wms.labeler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

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

import io.smarthappy.module.wms.ship.Sscc;
import io.smarthappy.platform.common.db.PgErrors;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * 오토라벨러 E3 (docs/65 21장) — 가짜 라벨러 = MockMvc /ext Bearer. 설치형(DIRECT 바로 묻기)·SaaS(EDGE 미리 받기) 두 길.
 * 정상 + 이상 사용(B 입력·C 동시·D 늦은 결과·F 흐름·G 기한 초과·장비 리젝트·H 같은 요청 다시) + A사로 B사 + 직접 SQL + API 직접.
 */
@AutoConfigureMockMvc
class LabelerTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";
    private static final String TPL = "^XA^CI28^FO10,10^A0N,30,30^FD{{palletNo}}^FS^FO10,60^BXN,6,200,,,,@^FD{{gs1}}^FS"
            + "^FO10,200^A0N,20,20^FD{{sscc}}^FS^XZ";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    @Autowired
    LabelerJobs jobs;

    static final class Site {
        String comp;
        long userId;
        RequestPostProcessor admin;
        long deviceId;
        String key;
        long labelerId;
        long tplId;
    }

    Site a;

    @BeforeEach
    void setUp() throws Exception {
        a = site("LA");
    }

    Site site(String prefix) throws Exception {
        Site s = new Site();
        s.comp = accounts.newComp(prefix);
        s.userId = accounts.newUser(s.comp, "admin1", PW);
        accounts.grant(s.comp, s.userId, "WMS", "wms-labeler", "SEARCH", "SAVE");
        accounts.grant(s.comp, s.userId, "WMS", "wms-label", "SEARCH", "SAVE", "PRINT");
        accounts.grant(s.comp, s.userId, "ADM", "sys-terminal", "SEARCH", "SAVE");
        s.admin = accounts.as(s.comp, s.userId, "admin1");
        accounts.addPlant(s.comp, "P1", "1공장");
        accounts.exec(s.comp, """
                insert into sys_policy (comp_cd, policy_key, policy_value, created_by, updated_by)
                values (?, 'WMS_GS1_COMPANY_PREFIX', '8801234', 't', 't')""", s.comp);
        accounts.exec(s.comp, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, created_by, updated_by)
                values (?, 'ZT-BOX', 'ZT 브래킷 박스', 'FG', 'MAKE', 'EA', 't', 't')""", s.comp);
        s.tplId = id(s, post("/api/wms/labels"), "{\"labelType\":\"PALLET\",\"tplCd\":\"BOX100\",\"tplNm\":\"박스 SSCC\",\"widthMm\":100,"
                + "\"heightMm\":70,\"body\":" + q(TPL) + "}");
        String[] dev = device(s, "LBL-DEV1");
        s.deviceId = Long.parseLong(dev[0]);
        s.key = dev[1];
        s.labelerId = id(s, post("/api/wms/labelers"), "{\"plantCd\":\"P1\",\"labelerCd\":\"LBL1\",\"labelerNm\":\"1라인 라벨러\",\"terminalId\":"
                + s.deviceId + ",\"tplId\":" + s.tplId + ",\"deadlineMs\":2000}");
        return s;
    }

    // =====================================================================
    // 정상 — 설치형(DIRECT)
    // =====================================================================

    @Test
    void 요청_인쇄_부착_검증까지_같은_요청은_처음_답() throws Exception {
        long box = box(a, "ZT-BOX-0001", "N");
        MvcResult r = req(a.key, "r1", "ZT-BOX-0001", 30).andExpect(status().isOk()).andExpect(jsonPath("$.data.action").value("PRINT"))
                .andExpect(jsonPath("$.data.tplCd").value("BOX100")).andExpect(jsonPath("$.data.sizeCd").value("100x70")).andReturn();
        String sscc = JsonPath.read(r.getResponse().getContentAsString(), "$.data.sscc");
        assertThat(Sscc.valid(sscc)).isTrue();
        assertThat(sscc).startsWith("08801234");
        String zpl = JsonPath.read(r.getResponse().getContentAsString(), "$.data.zpl");
        assertThat(zpl).contains("^FD@100" + sscc + "^FS").contains("^FH_^FDZT-BOX-0001^FS");
        // 같은 requestId 다시 = 처음 답(replay), 새 번호 없음
        req(a.key, "r1", "ZT-BOX-0001", 5).andExpect(jsonPath("$.data.sscc").value(sscc)).andExpect(jsonPath("$.data.replay").value(true));
        res(a.key, "r1", "PRINTED", null, null).andExpect(jsonPath("$.data.outcome").value("MOVED"));
        res(a.key, "r1", "APPLIED", null, null).andExpect(jsonPath("$.data.outcome").value("MOVED"));
        res(a.key, "r1", "VERIFY", "]C100" + sscc, null).andExpect(jsonPath("$.data.boxStatus").value("VERIFIED"))
                .andExpect(jsonPath("$.data.eventCd").value("VERIFY_OK"));
        res(a.key, "r1", "VERIFY", "]C100" + sscc, null).andExpect(jsonPath("$.data.outcome").value("DUP"));
        assertThat(str(a, "select status || ':' || sscc || ':' || attempt_cnt from wms_labeler_box where box_id = ?", box))
                .isEqualTo("VERIFIED:" + sscc + ":1");
        // 다시 온 박스 = DUPLICATE(두 번째 라벨을 주지 않는다)
        req(a.key, "r2", "ZT-BOX-0001", 30).andExpect(jsonPath("$.data.action").value("REJECT"))
                .andExpect(jsonPath("$.data.reasonCd").value("DUPLICATE"));
        mvc.perform(get("/api/wms/labeler-boxes/" + box + "/events").with(a.admin))
                .andExpect(jsonPath("$.data[*].eventCd").value(org.hamcrest.Matchers.contains("REQUEST", "PRINTED", "APPLIED", "VERIFY_OK",
                        "REQUEST")));
        // 행위자 = 장치, 요청 사건에 처리 시간
        assertThat(str(a, "select recorded_by || ':' || (elapsed_ms >= 0)::text from wms_labeler_event where request_id = 'r1' "
                + "and event_cd = 'REQUEST'")).isEqualTo("d" + a.deviceId + ":LBL-DEV1:true");
    }

    @Test
    void 검증_불일치_빈_라벨은_리젝트_재라벨은_같은_SSCC_늦은_결과는_STALE() throws Exception {
        box(a, "ZT-BOX-0002", "N");
        String s1 = sscc(req(a.key, "q1", "ZT-BOX-0002", 30));
        // 다른 박스 라벨이 붙음(조용히 틀리는 것 — 핵심)
        res(a.key, "q1", "VERIFY", "]C100088012340000099999".substring(0, 23), null)
                .andExpect(jsonPath("$.data.boxStatus").value("REJECTED")).andExpect(jsonPath("$.data.reasonCd").value("VERIFY_MISMATCH"));
        String s2 = sscc(req(a.key, "q2", "ZT-BOX-0002", 30));
        assertThat(s2).isEqualTo(s1);                                                    // 같은 물류 단위 = 같은 SSCC
        res(a.key, "q2", "VERIFY", "", null).andExpect(jsonPath("$.data.reasonCd").value("VERIFY_NO_READ"));
        String s3 = sscc(req(a.key, "q3", "ZT-BOX-0002", 30));
        assertThat(s3).isEqualTo(s1);
        // 앞 요청(q2)의 늦은 결과 = STALE(지금 시도에 쓰지 않는다)
        res(a.key, "q2", "APPLIED", null, null).andExpect(jsonPath("$.data.outcome").value("STALE"));
        res(a.key, "q3", "VERIFY", s1, null).andExpect(jsonPath("$.data.boxStatus").value("VERIFIED"));      // 18자리만 읽어도 된다
        assertThat(str(a, "select attempt_cnt::text from wms_labeler_box where box_cd = 'ZT-BOX-0002'")).isEqualTo("3");
        assertThat(str(a, "select count(distinct sscc)::text from wms_labeler_event where box_id = "
                + "(select box_id from wms_labeler_box where box_cd = 'ZT-BOX-0002') and sscc is not null")).isEqualTo("1");
    }

    @Test
    void G_앞단_못읽음_미등록_라벨생략_기한초과() throws Exception {
        box(a, "ZT-BOX-SKIP", "Y");
        box(a, "ZT-BOX-LATE", "N");
        req(a.key, "n1", "", 30).andExpect(jsonPath("$.data.action").value("REJECT")).andExpect(jsonPath("$.data.reasonCd").value("NO_READ"));
        req(a.key, "n2", "ZT-NOBODY", 30).andExpect(jsonPath("$.data.reasonCd").value("UNREGISTERED"));
        req(a.key, "n3", "ZT-BOX-SKIP", 30).andExpect(jsonPath("$.data.action").value("SKIP"));
        assertThat(str(a, "select status from wms_labeler_box where box_cd = 'ZT-BOX-SKIP'")).isEqualTo("SKIPPED");
        // 장비가 읽은 지 2.5초 — 기한 2초를 넘긴 답은 그 박스에 쓰지 않는다(늦게 온 정답 = 오답)
        MvcResult late = req(a.key, "n4", "ZT-BOX-LATE", 2500).andExpect(jsonPath("$.data.action").value("REJECT"))
                .andExpect(jsonPath("$.data.reasonCd").value("LATE")).andReturn();
        String kept = JsonPath.read(late.getResponse().getContentAsString(), "$.data.sscc");
        assertThat(str(a, "select status || ':' || sscc || ':' || last_reason_cd from wms_labeler_box where box_cd = 'ZT-BOX-LATE'"))
                .isEqualTo("REJECTED:" + kept + ":LATE");
        // 다음 박스 통과 때(기한 안) 같은 번호로 다시
        assertThat(sscc(req(a.key, "n5", "ZT-BOX-LATE", 30))).isEqualTo(kept);
        // 박스 없는 답에 대한 장비 리젝트 보고는 원장에만
        res(a.key, "n1", "REJECTED", null, "NO_READ").andExpect(jsonPath("$.data.outcome").value("MOVED"));
    }

    @Test
    void 장비_리젝트_사유는_공통코드_모르는_코드는_OTHER_회사_코드는_그대로() throws Exception {
        box(a, "ZT-BOX-R1", "N");
        box(a, "ZT-BOX-R2", "N");
        box(a, "ZT-BOX-R3", "N");
        req(a.key, "x1", "ZT-BOX-R1", 30);
        res(a.key, "x1", "REJECTED", null, "PRINT_FAIL").andExpect(jsonPath("$.data.reasonCd").value("PRINT_FAIL"));
        req(a.key, "x2", "ZT-BOX-R2", 30);
        res(a.key, "x2", "REJECTED", null, "JAM_42").andExpect(jsonPath("$.data.reasonCd").value("OTHER"));
        assertThat(str(a, "select detail from wms_labeler_event where request_id = 'x2' and event_cd = 'REJECTED'")).startsWith("device=JAM_42");
        accounts.exec(a.comp, "insert into sys_comp_code (comp_cd, grp_cd, code, code_nm, sort_no, created_by, updated_by) "
                + "values (?, 'WMS_LABELER_REASON', 'TAMP_FAIL', '탬프 실패', 50, 't', 't')", a.comp);
        req(a.key, "x3", "ZT-BOX-R3", 30);
        res(a.key, "x3", "REJECTED", null, "TAMP_FAIL").andExpect(jsonPath("$.data.reasonCd").value("TAMP_FAIL"));
        // 입력 검사
        res(a.key, "x3", "DONE", null, null).andExpect(jsonPath("$.errors[0].field").value("phase"));
        res(a.key, "nope", "PRINTED", null, null).andExpect(status().isNotFound());
        req(a.key, "bad id!", "ZT-BOX-R3", 30).andExpect(jsonPath("$.errors[0].field").value("requestId"));
    }

    @Test
    void 장비_사건_리젝트_만재는_정책_지시() throws Exception {
        ext(a.key, "/event", "{\"type\":\"REJECT_FULL\",\"code\":\"REJECT_LINE_FULL\"}").andExpect(jsonPath("$.data.reply").value("STOP"));
        accounts.exec(a.comp, "insert into sys_policy (comp_cd, plant_cd, policy_key, policy_value, created_by, updated_by) "
                + "values (?, 'P1', 'WMS_LABELER_REJECT_FULL', 'ALARM', 't', 't')", a.comp);
        ext(a.key, "/event", "{\"type\":\"REJECT_FULL\"}").andExpect(jsonPath("$.data.reply").value("ALARM"));
        ext(a.key, "/event", "{\"type\":\"ERROR\",\"code\":\"RIBBON_OUT\",\"detail\":\"리본 없음\"}").andExpect(jsonPath("$.data.reply").isEmpty());
        ext(a.key, "/event", "{\"type\":\"BOOM\"}").andExpect(jsonPath("$.errors[0].field").value("type"));
        ext(a.key, "/event", "{\"type\":\"ERROR\",\"code\":\"ribbon out\"}").andExpect(jsonPath("$.errors[0].field").value("code"));
        mvc.perform(get("/api/wms/labelers/" + a.labelerId + "/device-log").with(a.admin))
                .andExpect(jsonPath("$.data[*].eventCd").value(org.hamcrest.Matchers.contains("ERROR", "REJECT_FULL", "REJECT_FULL")));
    }

    // =====================================================================
    // SaaS(EDGE) — 미리 받기·현장 답 올리기·결번
    // =====================================================================

    @Test
    void 엣지는_미리_받고_현장_답을_올리며_안_쓴_번호는_결번() throws Exception {
        mvc.perform(json(put("/api/wms/labelers/" + a.labelerId), "{\"labelerNm\":\"엣지 라벨러\",\"terminalId\":" + a.deviceId
                + ",\"connMode\":\"EDGE\",\"tplId\":" + a.tplId + ",\"deadlineMs\":2000,\"rowVer\":0}").with(a.admin)).andExpect(status().isOk());
        box(a, "ZT-E1", "N");
        box(a, "ZT-E2", "N");
        box(a, "ZT-E3", "Y");                                                         // 라벨 생략은 미리 받지 않는다
        MvcResult p = ext(a.key, "/prefetch", "{\"max\":10}").andExpect(jsonPath("$.data.length()").value(2)).andReturn();
        String e1 = JsonPath.read(p.getResponse().getContentAsString(), "$.data[0].sscc");
        String e2 = JsonPath.read(p.getResponse().getContentAsString(), "$.data[1].sscc");
        assertThat(str(a, "select string_agg(status, ',' order by box_cd) from wms_labeler_box where box_cd in ('ZT-E1','ZT-E2')"))
                .isEqualTo("PREFETCHED,PREFETCHED");
        ext(a.key, "/prefetch", "{}").andExpect(jsonPath("$.data.length()").value(0));                // 두 번 주지 않는다
        // 현장에서 답한 것 올리기 — 다른 번호는 거부, 맞는 번호는 받음(기한 검사 없음)
        ext(a.key, "/request", "{\"requestId\":\"e-1\",\"scanned\":\"ZT-E1\",\"answeredLocally\":true,\"action\":\"PRINT\",\"sscc\":\"" + e2 + "\"}")
                .andExpect(jsonPath("$.errors[0].code").value("SSCC_MISMATCH"));
        ext(a.key, "/request", "{\"requestId\":\"e-1\",\"scanned\":\"ZT-E1\",\"answeredLocally\":true,\"action\":\"PRINT\",\"sscc\":\"" + e1
                + "\",\"ageMs\":99999}").andExpect(jsonPath("$.data.action").value("PRINT"));
        res(a.key, "e-1", "VERIFY", "(00)" + e1, null).andExpect(jsonPath("$.data.boxStatus").value("VERIFIED"));
        ext(a.key, "/request", "{\"requestId\":\"e-2\",\"scanned\":\"\",\"answeredLocally\":true,\"action\":\"REJECT\",\"reasonCd\":\"NO_READ\"}")
                .andExpect(jsonPath("$.data.reasonCd").value("NO_READ"));
        // 같은 박스가 LAN 직접 요청으로 와도 미리 받은 번호를 두 군데 쓰지 않는다
        req(a.key, "e-3", "ZT-E2", 10).andExpect(jsonPath("$.data.reasonCd").value("DUPLICATE"));
        // 안 쓴 미리 받기 놓기 = 결번, 다음 미리 받기는 새 번호
        ext(a.key, "/release", "{\"boxCds\":[\"ZT-E2\",\"ZT-E1\",\"NOPE\"]}").andExpect(jsonPath("$.data.voided").value(1))
                .andExpect(jsonPath("$.data.unknown.length()").value(2));
        assertThat(str(a, "select status || ':' || coalesce(sscc, '-') from wms_labeler_box where box_cd = 'ZT-E2'")).isEqualTo("REGISTERED:-");
        assertThat(str(a, "select sscc || ':' || reason_cd from wms_labeler_event where event_cd = 'SSCC_VOID'")).isEqualTo(e2 + ":PREFETCH_UNUSED");
        MvcResult p2 = ext(a.key, "/prefetch", "{}").andExpect(jsonPath("$.data.length()").value(1)).andReturn();
        String again = JsonPath.read(p2.getResponse().getContentAsString(), "$.data[0].sscc");
        assertThat(again).isNotEqualTo(e2);                                             // 결번은 다시 쓰지 않는다
        // 정기 작업: 정책 시간 넘은 미리 받기 결번
        accounts.exec(a.comp, "update wms_labeler_box set prefetched_at = now() - interval '25 hours' where status = 'PREFETCHED'");
        assertThat(jobs.voidStalePrefetch()).isEqualTo(1);
        assertThat(str(a, "select count(*)::text from wms_labeler_event where event_cd = 'SSCC_VOID'")).isEqualTo("2");
    }

    @Test
    void 설치형은_미리_받기_없음_현장_답_올리기_없음() throws Exception {
        ext(a.key, "/prefetch", "{}").andExpect(status().isConflict());
        ext(a.key, "/request", "{\"requestId\":\"z1\",\"scanned\":\"X\",\"answeredLocally\":true,\"action\":\"PRINT\"}")
                .andExpect(status().isConflict());
    }

    @Test
    void C_같은_박스_동시_요청에도_SSCC_하나_다른_박스는_서로_다른_번호() throws Exception {
        box(a, "ZT-C0", "N");
        for (int i = 1; i <= 6; i++) {
            box(a, "ZT-C" + i, "N");
        }
        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            List<Callable<String>> calls = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                String rid = "c-same-" + i;
                calls.add(() -> sscc(req(a.key, rid, "ZT-C0", 10)));
            }
            for (int i = 1; i <= 6; i++) {
                String rid = "c-diff-" + i;
                String cd = "ZT-C" + i;
                calls.add(() -> sscc(req(a.key, rid, cd, 10)));
            }
            List<String> got = new ArrayList<>();
            for (Future<String> f : pool.invokeAll(calls)) {
                got.add(f.get());
            }
            assertThat(got.subList(0, 4)).containsOnly(got.get(0));                    // 같은 박스 = 한 번호
            assertThat(got.subList(4, 10)).doesNotHaveDuplicates().doesNotContain(got.get(0));
        } finally {
            pool.shutdownNow();
        }
    }

    // =====================================================================
    // 화면 · A사로 B사 · 직접 SQL · 권한
    // =====================================================================

    @Test
    void B_박스_등록_검사와_목록() throws Exception {
        String base = "{\"plantCd\":\"P1\",\"boxCd\":";
        postJson(a, "/api/wms/labeler-boxes", base + "\"has space\",\"itemCd\":\"NOPE\",\"qty\":0}")
                .andExpect(jsonPath("$.errors[*].field").value(org.hamcrest.Matchers.containsInAnyOrder("boxCd", "itemCd", "qty")));
        long b = box(a, "ZT-B1", "N");
        postJson(a, "/api/wms/labeler-boxes", base + "\"ZT-B1\",\"itemCd\":\"zt-box\",\"qty\":3}").andExpect(jsonPath("$.errors[0].code").value("DUPLICATE"));
        mvc.perform(json(put("/api/wms/labeler-boxes/" + b), "{\"qty\":5,\"lotNo\":\"L-9\",\"rowVer\":0}").with(a.admin)).andExpect(status().isOk());
        req(a.key, "b1", "ZT-B1", 30);
        mvc.perform(json(put("/api/wms/labeler-boxes/" + b), "{\"qty\":6,\"rowVer\":1}").with(a.admin)).andExpect(status().isConflict());   // 진행 중
        String today = accounts.query(a.comp, "select " + TestAccounts.COMP_TODAY + "::text", String.class);
        mvc.perform(get("/api/wms/labeler-boxes/grid").param("from", today).param("to", today).param("status", "REQUESTED").with(a.admin))
                .andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.list[0].boxCd").value("ZT-B1"));
        mvc.perform(get("/api/wms/labeler-boxes/grid").param("from", today).param("to", today).param("status", "DONE").with(a.admin))
                .andExpect(jsonPath("$.errors[0].field").value("status"));
        mvc.perform(get("/api/wms/labeler-boxes/columns").with(a.admin)).andExpect(status().isOk());
        mvc.perform(get("/api/wms/labelers/options").with(a.admin)).andExpect(jsonPath("$.data.templates[0].tplCd").value("BOX100"));
        // 라벨러: 같은 장치 두 번 · ITEM 템플릿 · 기한 밖
        long itemTpl = id(a, post("/api/wms/labels"), "{\"labelType\":\"ITEM\",\"tplCd\":\"IT\",\"tplNm\":\"i\",\"widthMm\":50,\"heightMm\":30,"
                + "\"body\":\"^XA^FD{{itemCd}}^FS^XZ\"}");
        postJson(a, "/api/wms/labelers", "{\"plantCd\":\"P1\",\"labelerCd\":\"LBL2\",\"labelerNm\":\"x\",\"terminalId\":" + a.deviceId
                + ",\"tplId\":" + itemTpl + ",\"deadlineMs\":50}")
                .andExpect(jsonPath("$.errors[*].field").value(org.hamcrest.Matchers.containsInAnyOrder("tplId", "deadlineMs")));
        postJson(a, "/api/wms/labelers", "{\"plantCd\":\"P1\",\"labelerCd\":\"LBL2\",\"labelerNm\":\"x\",\"terminalId\":" + a.deviceId
                + ",\"tplId\":" + a.tplId + "}").andExpect(jsonPath("$.errors[0].code").value("DUPLICATE"));
    }

    @Test
    void A사로_B사_장치_박스_라벨러() throws Exception {
        long box = box(a, "ZT-AB-1", "N");
        req(a.key, "ab1", "ZT-AB-1", 30).andExpect(jsonPath("$.data.action").value("PRINT"));
        Site b = site("LB");
        // B사 장치가 A사 박스 코드 → 미등록(RLS), A사 요청 결과 → 404
        req(b.key, "bb1", "ZT-AB-1", 30).andExpect(jsonPath("$.data.reasonCd").value("UNREGISTERED"));
        res(b.key, "ab1", "VERIFY", "x", null).andExpect(status().isNotFound());
        mvc.perform(get("/api/wms/labeler-boxes/" + box).with(b.admin)).andExpect(status().isNotFound());
        mvc.perform(get("/api/wms/labeler-boxes/" + box + "/events").with(b.admin)).andExpect(status().isNotFound());
        mvc.perform(json(put("/api/wms/labelers/" + a.labelerId), "{\"labelerNm\":\"x\",\"terminalId\":" + b.deviceId + ",\"tplId\":" + b.tplId
                + ",\"rowVer\":0}").with(b.admin)).andExpect(status().isNotFound());
        postJson(b, "/api/wms/labelers", "{\"plantCd\":\"P1\",\"labelerCd\":\"X\",\"labelerNm\":\"x\",\"terminalId\":" + a.deviceId + ",\"tplId\":"
                + a.tplId + "}").andExpect(jsonPath("$.errors[*].code").value(org.hamcrest.Matchers.hasItem("NOT_FOUND")));
        // 라벨러가 아닌 장치 · 사람 로그인으로 /ext
        String[] plain = device(a, "COL-9");
        req(plain[1], "p1", "ZT-AB-1", 30).andExpect(status().isNotFound());
        mvc.perform(post("/ext/v1/labeler/request").with(a.admin).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        // 직접 SQL: B사 범위에서 A사 박스 사건 · A사 품목 박스 · A사 장치 라벨러
        long aItem = accounts.query(a.comp, "select item_id from mst_item where item_cd = 'ZT-BOX'", Long.class);
        assertThat(sqlError(b.comp, "insert into wms_labeler_event (comp_cd, box_id, event_cd, src, occurred_at, recorded_by) "
                + "values (?, ?, 'PRINTED', 'SYSTEM', now(), 't')", b.comp, box)).isEqualTo("23503 fk_wms_labeler_event_comp");
        assertThat(sqlError(b.comp, "insert into wms_labeler_box (comp_cd, plant_cd, box_cd, item_id, qty, created_by, updated_by) "
                + "values (?, 'P1', 'X', ?, 1, 't', 't')", b.comp, aItem)).isEqualTo("23503 fk_wms_labeler_box_item_comp");
        assertThat(sqlError(b.comp, "insert into wms_labeler (comp_cd, plant_cd, labeler_cd, labeler_nm, terminal_id, tpl_id, created_by, updated_by) "
                + "values (?, 'P1', 'Y', 'y', ?, ?, 't', 't')", b.comp, a.deviceId, b.tplId)).isEqualTo("23503 fk_wms_labeler_terminal_comp");
    }

    @Test
    void 직접_SQL_원장_추가만_SSCC_고정_전이_사유_코드() throws Exception {
        long box = box(a, "ZT-S1", "N");
        String s = sscc(req(a.key, "s1", "ZT-S1", 30));
        assertThat(sqlError(a.comp, "update wms_labeler_event set reason_cd = 'OTHER' where box_id = ?", box)).startsWith("42501");
        assertThat(sqlError(a.comp, "delete from wms_labeler_event where box_id = ?", box)).startsWith("42501");
        assertThat(sqlError(a.comp, "update wms_labeler_box set sscc = '088012340000099990' where box_id = ?", box))
                .isEqualTo("55000 ck_wms_labeler_box_sscc_fixed");
        assertThat(sqlError(a.comp, "update wms_labeler_box set status = 'REGISTERED' where box_id = ?", box))
                .isEqualTo("55000 ck_wms_labeler_box_transition");
        assertThat(sqlError(a.comp, "update wms_labeler_box set box_cd = 'OTHER' where box_id = ?", box)).isEqualTo("55000 ck_wms_labeler_box_fixed");
        assertThat(sqlError(a.comp, "delete from wms_labeler_box where box_id = ?", box)).startsWith("42501");
        assertThat(sqlError(a.comp, "update wms_labeler_box set last_reason_cd = 'NOT_A_CODE' where box_id = ?", box))
                .isEqualTo("23503 fk_wms_labeler_box_last_reason_cd_code");
        assertThat(sqlError(a.comp, "insert into wms_labeler_event (comp_cd, box_id, event_cd, reason_cd, src, occurred_at, recorded_by) "
                + "values (?, ?, 'REJECTED', 'NOT_A_CODE', 'SYSTEM', now(), 't')", a.comp, box)).isEqualTo("23503 fk_wms_labeler_event_reason_cd_code");
        // 다른 박스가 같은 SSCC · 처음부터 SSCC 를 들고 등록
        long other = box(a, "ZT-S2", "N");
        req(a.key, "s2", "ZT-S2", 30);
        assertThat(sqlError(a.comp, "update wms_labeler_box set sscc = ? where box_id = ?", s, other)).isEqualTo("55000 ck_wms_labeler_box_sscc_fixed");
        assertThat(sqlError(a.comp, "insert into wms_labeler_box (comp_cd, plant_cd, box_cd, item_id, qty, sscc, created_by, updated_by) "
                + "select ?, 'P1', 'ZT-S3', item_id, 1, '088012340000000017', 't', 't' from mst_item where item_cd = 'ZT-BOX'", a.comp))
                .isEqualTo("23514 ck_wms_labeler_box_insert");
    }

    @Test
    void 권한_API_직접() throws Exception {
        long viewer = accounts.newUser(a.comp, "viewer", PW);
        accounts.grant(a.comp, viewer, "VIEW", "wms-labeler", "SEARCH");
        RequestPostProcessor v = accounts.as(a.comp, viewer, "viewer");
        mvc.perform(get("/api/wms/labelers").with(v)).andExpect(status().isOk());
        mvc.perform(json(post("/api/wms/labeler-boxes"), "{}").with(v)).andExpect(status().isForbidden());
        mvc.perform(json(post("/api/wms/labelers"), "{}").with(v)).andExpect(status().isForbidden());
        mvc.perform(post("/api/wms/labeler-boxes").contentType(MediaType.APPLICATION_JSON).content("{}").with(a.admin))
                .andExpect(status().isForbidden());                                       // CSRF 없이
        ext("not.a.key", "/request", "{\"requestId\":\"k\",\"scanned\":\"x\"}").andExpect(status().isUnauthorized());
    }

    // =====================================================================
    // 도우미
    // =====================================================================

    long box(Site s, String cd, String skip) throws Exception {
        return id(s, post("/api/wms/labeler-boxes"), "{\"plantCd\":\"P1\",\"boxCd\":\"" + cd + "\",\"itemCd\":\"ZT-BOX\",\"qty\":12,"
                + "\"refNo\":\"SHIP-1\",\"labelSkipYn\":\"" + skip + "\"}");
    }

    ResultActions req(String key, String rid, String scanned, int ageMs) throws Exception {
        return ext(key, "/request", "{\"requestId\":\"" + rid + "\",\"scanned\":\"" + scanned + "\",\"ageMs\":" + ageMs + "}");
    }

    ResultActions res(String key, String rid, String phase, String scanned, String reason) throws Exception {
        return ext(key, "/result", "{\"requestId\":\"" + rid + "\",\"phase\":\"" + phase + "\""
                + (scanned == null ? "" : ",\"scanned\":" + q(scanned)) + (reason == null ? "" : ",\"reasonCd\":\"" + reason + "\"") + "}");
    }

    static String sscc(ResultActions r) throws Exception {
        MvcResult m = r.andExpect(status().isOk()).andReturn();
        return JsonPath.read(m.getResponse().getContentAsString(), "$.data.sscc");
    }

    ResultActions ext(String key, String path, String body) throws Exception {
        return mvc.perform(post(LabelerExtController.BASE + path).header("Authorization", "Bearer " + key)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    String[] device(Site s, String cd) throws Exception {
        MvcResult m = mvc.perform(json(post("/api/production/terminals"), "{\"terminalCd\":\"" + cd + "\",\"terminalNm\":\"" + cd
                + "\",\"plantCd\":\"P1\",\"terminalType\":\"COLLECTOR\"}").with(s.admin)).andExpect(status().isOk()).andReturn();
        String body = m.getResponse().getContentAsString();
        return new String[] {String.valueOf(((Number) JsonPath.read(body, "$.data.terminalId")).longValue()), JsonPath.read(body, "$.data.code")};
    }

    long id(Site s, MockHttpServletRequestBuilder b, String body) throws Exception {
        MvcResult m = mvc.perform(json(b, body).with(s.admin)).andExpect(status().isOk()).andReturn();
        return ((Number) JsonPath.read(m.getResponse().getContentAsString(), "$.data")).longValue();
    }

    ResultActions postJson(Site s, String url, String body) throws Exception {
        return mvc.perform(json(post(url), body).with(s.admin));
    }

    static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String body) {
        return b.with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    static String q(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    String str(Site s, String sql, Object... args) {
        return accounts.query(s.comp, sql, String.class, args);
    }

    String sqlError(String c, String sql, Object... args) {
        Throwable t = catchThrowable(() -> accounts.exec(c, sql, args));
        return t == null ? "OK" : PgErrors.sqlState(t).orElse("?") + " " + PgErrors.constraint(t).orElse("-");
    }
}
