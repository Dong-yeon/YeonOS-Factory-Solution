package io.smarthappy.module.apqp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItems;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * P6 APQP A2 관리계획서 (docs/41 5장 · C1~C8) — PFMEA 에서 끌어오기(추가만), 특별특성 빠짐·반응계획·주기로 확정 거부, 확정 스냅샷·불변,
 * 새 리비전(개정 사유)·다음 단계(새 번호), 근거 FMEA 새 리비전 표시, 이상 입력(라우팅 밖 단계·남의 공정 특성·남의 위험행·중복·범위),
 * 권한, A사로 B사.
 */
@AutoConfigureMockMvc
class ApqpCpTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";
    private static final String F = "/api/apqp/fmeas";
    private static final String CP = "/api/apqp/cps";
    private static final String LINE = "/api/apqp/cp-lines/";
    private static final String RULES = """
            [{"sevFrom":9,"sevTo":10,"occFrom":1,"occTo":10,"detFrom":1,"detTo":10,"ap":"H"},
             {"sevFrom":1,"sevTo":8,"occFrom":6,"occTo":10,"detFrom":1,"detTo":10,"ap":"M"},
             {"sevFrom":1,"sevTo":8,"occFrom":1,"occTo":5,"detFrom":1,"detTo":10,"ap":"L"}]""";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    String comp;
    RequestPostProcessor qe;
    Fixture fx;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("CQ");
        long u = accounts.newUser(comp, "qe1", PW);
        accounts.grant(comp, u, "APQP", "apqp-fmea", "SEARCH", "SAVE", "APPROVE");
        accounts.grant(comp, u, "APQP", "apqp-ap", "SEARCH", "SAVE");
        accounts.grant(comp, u, "APQP", "apqp-cp", "SEARCH", "SAVE", "APPROVE");
        qe = accounts.as(comp, u, "qe1");
        fx = fixture(comp);
    }

    private record Fixture(long routing, long otherRouting, long step10, long step20, long otherStep, long prodChar, long weldChar,
            long inspChar) {
    }

    /** 품목 ZT-BRKT(라우팅 10 용접 · 20 검사) + ZT-OTHER(라우팅 10 용접), 제품 특성 CC 1 · 공정 특성 2 */
    private Fixture fixture(String c) {
        accounts.addPlant(c, "P1", "1공장");
        accounts.exec(c, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, created_by, updated_by)
                values (?, 'ZT-BRKT', 'ZT 브래킷', 'FG', 'MAKE', 'EA', 't', 't'), (?, 'ZT-OTHER', 'ZT 다른 품목', 'FG', 'MAKE', 'EA', 't', 't')""",
                c, c);
        accounts.exec(c, """
                insert into mst_proc (comp_cd, proc_cd, proc_nm, proc_cat, created_by, updated_by)
                values (?, 'ZWELD', 'ZT 용접', 'WELD', 't', 't'), (?, 'ZINSP', 'ZT 검사', 'INSPECT', 't', 't')""", c, c);
        long rid = accounts.query(c, """
                insert into mst_routing (comp_cd, item_id, plant_cd, rev, valid_from, created_by, updated_by)
                values (?, (select item_id from mst_item where item_cd = 'ZT-BRKT'), 'P1', 'A0', (now() at time zone 'Asia/Seoul')::date, 't', 't')
                returning routing_id""", Long.class, c);
        long s10 = accounts.query(c, """
                insert into mst_routing_step (comp_cd, routing_id, op_seq, proc_id, created_by, updated_by)
                values (?, ?, 10, (select proc_id from mst_proc where proc_cd = 'ZWELD'), 't', 't') returning step_id""", Long.class, c, rid);
        long s20 = accounts.query(c, """
                insert into mst_routing_step (comp_cd, routing_id, op_seq, proc_id, created_by, updated_by)
                values (?, ?, 20, (select proc_id from mst_proc where proc_cd = 'ZINSP'), 't', 't') returning step_id""", Long.class, c, rid);
        long orid = accounts.query(c, """
                insert into mst_routing (comp_cd, item_id, plant_cd, rev, valid_from, created_by, updated_by)
                values (?, (select item_id from mst_item where item_cd = 'ZT-OTHER'), 'P1', 'A0', (now() at time zone 'Asia/Seoul')::date, 't', 't')
                returning routing_id""", Long.class, c);
        long os = accounts.query(c, """
                insert into mst_routing_step (comp_cd, routing_id, op_seq, proc_id, created_by, updated_by)
                values (?, ?, 10, (select proc_id from mst_proc where proc_cd = 'ZWELD'), 't', 't') returning step_id""", Long.class, c, orid);
        long pc = accounts.query(c, """
                insert into qms_char (comp_cd, char_type, item_id, char_no, char_nm, data_type, special_cls, created_by, updated_by)
                values (?, 'PRODUCT', (select item_id from mst_item where item_cd = 'ZT-BRKT'), 'ZC1', 'ZT 용접 강도', 'ATTRIBUTE', 'CC', 't', 't')
                returning char_id""", Long.class, c);
        long wc = accounts.query(c, """
                insert into qms_char (comp_cd, char_type, proc_id, char_no, char_nm, data_type, lsl, usl, unit_nm, created_by, updated_by)
                values (?, 'PROCESS', (select proc_id from mst_proc where proc_cd = 'ZWELD'), 'ZP1', 'ZT 용접 전류', 'VARIABLE', 180, 220, 'A', 't', 't')
                returning char_id""", Long.class, c);
        long ic = accounts.query(c, """
                insert into qms_char (comp_cd, char_type, proc_id, char_no, char_nm, data_type, created_by, updated_by)
                values (?, 'PROCESS', (select proc_id from mst_proc where proc_cd = 'ZINSP'), 'ZP2', 'ZT 검사 조도', 'ATTRIBUTE', 't', 't')
                returning char_id""", Long.class, c);
        return new Fixture(rid, orid, s10, s20, os, pc, wc, ic);
    }

    private ResultActions send(MockHttpServletRequestBuilder b, String body, RequestPostProcessor who) throws Exception {
        return mvc.perform(b.with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body).with(who));
    }

    private long id(ResultActions r) throws Exception {
        String s = r.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(s, "$.data")).longValue();
    }

    private <T> T read(String url, String path) throws Exception {
        return JsonPath.read(mvc.perform(get(url).with(qe)).andReturn().getResponse().getContentAsString(), path);
    }

    private int ver(String url, String path) throws Exception {
        return ((Number) read(url, path)).intValue();
    }

    private record Fmea(long fmea, long risk, long stepNode, long item) {
    }

    /** 확정 PFMEA 한 벌: 용접 단계 FM(특성 = 용접 전류 공정 특성) · FC(작업요소) · FE(S 9), 위험행 검출 관리 + 특별특성 SC */
    private Fmea releasedFmea() throws Exception {
        long set = id(send(post("/api/apqp/ap-sets"), "{\"setCd\":\"ZT-EX\",\"setNm\":\"ZT 예시(핸드북 아님)\",\"useYn\":\"Y\",\"rules\":" + RULES + "}", qe));
        long fmea = id(send(post(F), "{\"itemCd\":\"ZT-BRKT\",\"routingId\":" + fx.routing() + ",\"title\":\"ZT 브래킷 PFMEA\",\"apSetId\":" + set
                + ",\"fromRouting\":true}", qe));
        List<Map<String, Object>> nodes = read(F + "/" + fmea, "$.data.nodes");
        long item = nodes.stream().filter(n -> "ITEM".equals(n.get("nodeKind"))).map(n -> ((Number) n.get("nodeId")).longValue()).findFirst()
                .orElseThrow();
        long step = nodes.stream().filter(n -> "STEP".equals(n.get("nodeKind")) && ((Number) n.get("stepId")).longValue() == fx.step10())
                .map(n -> ((Number) n.get("nodeId")).longValue()).findFirst().orElseThrow();
        long el = id(send(post(F + "/" + fmea + "/nodes"), "{\"parentId\":" + step + ",\"nodeKind\":\"ELEMENT\",\"nodeNm\":\"ZT 용접기\",\"m4\":\"MACHINE\"}", qe));
        long fi = id(send(post(F + "/" + fmea + "/funcs"), "{\"nodeId\":" + item + ",\"funcText\":\"차체 고정\"}", qe));
        long fs = id(send(post(F + "/" + fmea + "/funcs"), "{\"nodeId\":" + step + ",\"funcText\":\"규정 전류로 용접\",\"charId\":" + fx.weldChar() + "}", qe));
        long fe = id(send(post(F + "/" + fmea + "/funcs"), "{\"nodeId\":" + el + ",\"funcText\":\"설정 전류 유지\"}", qe));
        long e = id(send(post(F + "/" + fmea + "/fails"), "{\"funcId\":" + fi + ",\"failText\":\"주행 중 이탈\",\"severity\":9}", qe));
        long m = id(send(post(F + "/" + fmea + "/fails"), "{\"funcId\":" + fs + ",\"failText\":\"용접 강도 부족\"}", qe));
        long c = id(send(post(F + "/" + fmea + "/fails"), "{\"funcId\":" + fe + ",\"failText\":\"전류 저하\"}", qe));
        id(send(post(F + "/" + fmea + "/chains"), "{\"fmId\":" + m + ",\"feId\":" + e + "}", qe));
        long risk = id(send(post(F + "/" + fmea + "/risks"), "{\"fmId\":" + m + ",\"fcId\":" + c
                + ",\"prevCtrl\":\"전류 감시\",\"detCtrl\":\"파괴 시험\",\"occ\":3,\"det\":4,\"specialCls\":\"SC\"}", qe));
        send(post(F + "/" + fmea + "/release"), "{\"rowVer\":" + ver(F + "/" + fmea, "$.data.fmea.rowVer") + "}", qe).andExpect(status().isOk());
        return new Fmea(fmea, risk, step, item);
    }

    private long cp(long fmea, String phase) throws Exception {
        return id(send(post(CP), "{\"itemCd\":\"ZT-BRKT\",\"fmeaId\":" + fmea + ",\"phase\":\"" + phase + "\",\"title\":\"ZT 브래킷 관리계획서\","
                + "\"pullFromFmea\":true}", qe));
    }

    private int head(long cp) throws Exception {
        return ver(CP + "/" + cp, "$.data.cp.rowVer");
    }

    private long lineId(long cp, int index) throws Exception {
        return ((Number) read(CP + "/" + cp, "$.data.lines[" + index + "].lineId")).longValue();
    }

    /** 끌어온 행을 채우고(반응계획·주기) 제품 특성 CC 행을 더해 확정 */
    private void completeAndRelease(long cp) throws Exception {
        long l1 = lineId(cp, 0);
        send(put(LINE + l1), "{\"stepId\":" + fx.step10() + ",\"charId\":" + fx.weldChar() + ",\"ctrlMethod\":\"INSPECT\",\"evalMethod\":\"파괴 시험\","
                + "\"gaugeClass\":\"VISUAL\",\"inspType\":\"PROCESS\",\"sampleN\":5,\"sampleText\":\"5개\",\"freqType\":\"EVERY_N\",\"freqN\":50,"
                + "\"reactionPlan\":\"라인 정지 · 격리\",\"riskId\":" + read(CP + "/" + cp, "$.data.lines[0].riskId") + ",\"specialCls\":\"SC\","
                + "\"rowVer\":" + ver(CP + "/" + cp, "$.data.lines[0].rowVer") + "}", qe).andExpect(status().isOk());
        id(send(post(CP + "/" + cp + "/lines"), "{\"stepId\":" + fx.step20() + ",\"charId\":" + fx.prodChar() + ",\"ctrlMethod\":\"INSPECT\","
                + "\"inspType\":\"FINAL\",\"freqType\":\"EVERY_LOT\",\"sampleText\":\"LOT 당 1\",\"reactionPlan\":\"LOT 보류\"}", qe));
        send(post(CP + "/" + cp + "/release"), "{\"rowVer\":" + head(cp) + "}", qe).andExpect(status().isOk());
    }

    // ------------------------------------------------------------------ 정상 흐름

    @Test
    void 끌어오기_확정_검사_스냅샷_불변() throws Exception {
        Fmea f = releasedFmea();
        long cp = cp(f.fmea(), "PROTO");
        String url = CP + "/" + cp;
        // 근거 FMEA → 라우팅도 그 FMEA 의 것, 위험행 1 → 행 1(단계 10·특성 = FM 기능의 용접 전류·검출 관리 → INSPECT·특별특성 SC 제안)
        assertThat(((Number) read(url, "$.data.cp.routingId")).longValue()).isEqualTo(fx.routing());
        assertThat((List<?>) read(url, "$.data.lines")).hasSize(1);
        assertThat(((Number) read(url, "$.data.lines[0].stepId")).longValue()).isEqualTo(fx.step10());
        assertThat(((Number) read(url, "$.data.lines[0].charId")).longValue()).isEqualTo(fx.weldChar());
        assertThat((String) read(url, "$.data.lines[0].ctrlMethod")).isEqualTo("INSPECT");
        assertThat((String) read(url, "$.data.lines[0].evalMethod")).isEqualTo("파괴 시험");
        assertThat((String) read(url, "$.data.lines[0].specialCls")).isEqualTo("SC");
        assertThat((String) read(url, "$.data.lines[0].riskAp")).isEqualTo("H");
        // 다시 끌어와도 추가만(같은 근거 위험행 → 건너뜀) — 사람이 고친 행은 그대로
        long l1 = lineId(cp, 0);
        send(put(LINE + l1), "{\"stepId\":" + fx.step10() + ",\"charId\":" + fx.weldChar() + ",\"ctrlMethod\":\"SPC\",\"riskId\":" + f.risk()
                + ",\"rowVer\":" + ver(url, "$.data.lines[0].rowVer") + "}", qe).andExpect(status().isOk());
        send(post(url + "/pull"), "", qe).andExpect(jsonPath("$.data.added").value(0)).andExpect(jsonPath("$.data.skipped").value(1));
        assertThat((String) read(url, "$.data.lines[0].ctrlMethod")).isEqualTo("SPC");
        // 확정 점검: 품목 제품 특성 CC 가 행에 없음 → 상세에 빠짐 표시, 확정 거부(행 반응계획·주기 + CHAR 행 키)
        assertThat((List<?>) read(url, "$.data.gaps[?(@.kind == 'CHAR')].id")).hasSize(1);
        send(post(url + "/release"), "{\"rowVer\":" + head(cp) + "}", qe).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("APQP_RELEASE_CHECK"))
                .andExpect(jsonPath("$.errors[*].rowKey").value(hasItems("LINE:" + l1, "CHAR:" + fx.prodChar())))
                .andExpect(jsonPath("$.errors[*].field").value(hasItems("reactionPlan", "freqType", "specialCls")));
        completeAndRelease(cp);
        // 확정 → 스냅샷(규격·특별특성), 이전 것 없음, 편집 불가
        assertThat((String) read(url, "$.data.cp.status")).isEqualTo("RELEASED");
        assertThat((Boolean) read(url, "$.data.editable")).isFalse();
        assertThat((String) read(url, "$.data.lines[0].dataType")).isEqualTo("VARIABLE");
        assertThat(((Number) read(url, "$.data.lines[0].usl")).intValue()).isEqualTo(220);
        assertThat((String) read(url, "$.data.lines[1].specialCls")).isEqualTo("CC");
        // 확정 뒤 특성 마스터를 바꿔도 스냅샷은 그대로
        accounts.exec(comp, "update qms_char set usl = 250 where char_id = ?", fx.weldChar());
        assertThat(((Number) read(url, "$.data.lines[0].usl")).intValue()).isEqualTo(220);
        // 확정본은 고칠 수 없다(API 409 · 직접 SQL 거부)
        send(post(url + "/lines"), "{\"stepText\":\"x\",\"charText\":\"x\",\"ctrlMethod\":\"OTHER\"}", qe)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("APQP_CP_STATE"));
        send(post(url + "/pull"), "", qe).andExpect(jsonPath("$.code").value("APQP_CP_STATE"));
        assertThat(catchThrowable(() -> accounts.exec(comp, "update apqp_cp_line set reaction_plan = 'x' where line_id = ?", l1)))
                .hasMessageContaining("is RELEASED");
        assertThat(catchThrowable(() -> accounts.exec(comp, "update apqp_cp set title = 'x' where cp_id = ?", cp)))
                .hasMessageContaining("is RELEASED");
        assertThat(catchThrowable(() -> accounts.exec(comp, "delete from apqp_cp where cp_id = ?", cp))).hasMessageContaining("cannot be deleted");
        // 목록 grid
        mvc.perform(get(CP + "/grid").param("keyword", "ZT 브래킷").with(qe)).andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.list[0].lineCnt").value(2)).andExpect(jsonPath("$.list[0].phase").value("PROTO"));
        mvc.perform(get(CP + "/columns").with(qe)).andExpect(jsonPath("$.list[*].field").value(hasItems("cpNo", "phase", "status")));
    }

    @Test
    void 새_리비전_개정사유_다음_단계_근거FMEA_새리비전_표시() throws Exception {
        Fmea f = releasedFmea();
        long cp = cp(f.fmea(), "PROTO");
        completeAndRelease(cp);
        String cpNo = read(CP + "/" + cp, "$.data.cp.cpNo");
        // 새 리비전 = 복사(스냅샷 비움), 같은 번호에 작성 중 하나만
        long r2 = id(send(post(CP + "/" + cp + "/revise"), "{}", qe));
        assertThat((Integer) read(CP + "/" + r2, "$.data.cp.rev")).isEqualTo(2);
        assertThat((List<?>) read(CP + "/" + r2, "$.data.lines")).hasSize(2);
        assertThat((Object) read(CP + "/" + r2, "$.data.lines[0].dataType")).isNull();
        send(post(CP + "/" + cp + "/revise"), "{}", qe).andExpect(status().isConflict());
        // rev 2 확정은 개정 사유 필수(API · 직접 SQL CHECK)
        send(post(CP + "/" + r2 + "/release"), "{\"rowVer\":" + head(r2) + "}", qe)
                .andExpect(jsonPath("$.errors[*].field").value(hasItems("changeReason")));
        // 직접 SQL: 스냅샷을 채워도(작성 중이라 됨) 사유 없이 확정은 CHECK 가 막는다
        accounts.exec(comp, "update apqp_cp_line set data_type = 'ATTRIBUTE' where cp_id = ? and char_id is not null", r2);
        assertThat(catchThrowable(() -> accounts.exec(comp, "update apqp_cp set status = 'RELEASED', released_at = now() where cp_id = ?", r2)))
                .hasMessageContaining("ck_apqp_cp_reason");
        send(put(CP + "/" + r2), "{\"fmeaId\":" + f.fmea() + ",\"routingId\":" + fx.routing() + ",\"title\":\"ZT 브래킷 관리계획서\","
                + "\"changeReason\":\"반응계획 보강\",\"rowVer\":" + head(r2) + "}", qe).andExpect(status().isOk());
        send(post(CP + "/" + r2 + "/release"), "{\"rowVer\":" + head(r2) + "}", qe).andExpect(status().isOk());
        assertThat((String) read(CP + "/" + cp, "$.data.cp.status")).isEqualTo("OBSOLETE");
        // 다음 단계 = 새 번호(같은 단계·앞 단계는 거부)
        send(post(CP + "/" + r2 + "/next-phase"), "{\"phase\":\"PROTO\"}", qe).andExpect(jsonPath("$.errors[0].code").value("APQP_CP_PHASE_ORDER"));
        long pre = id(send(post(CP + "/" + r2 + "/next-phase"), "{\"phase\":\"PRE_LAUNCH\"}", qe));
        assertThat((String) read(CP + "/" + pre, "$.data.cp.phase")).isEqualTo("PRE_LAUNCH");
        assertThat((String) read(CP + "/" + pre, "$.data.cp.cpNo")).isNotEqualTo(cpNo);
        assertThat(((Number) read(CP + "/" + pre, "$.data.cp.prevCpId")).longValue()).isEqualTo(r2);
        assertThat((List<?>) read(CP + "/" + pre, "$.data.lines")).hasSize(2);
        // 근거 FMEA 의 새 리비전이 확정되면: 표시만(자동으로 바꾸지 않음), 작성 중 CP 는 확정 거부, 새 근거로 복사하면 근거 위험행은 비운다
        long f2 = id(send(post(F + "/" + f.fmea() + "/revise"), "", qe));
        send(post(F + "/" + f2 + "/release"), "{\"rowVer\":" + ver(F + "/" + f2, "$.data.fmea.rowVer") + "}", qe).andExpect(status().isOk());
        assertThat((Boolean) read(CP + "/" + pre, "$.data.cp.fmeaStale")).isTrue();
        assertThat(((Number) read(CP + "/" + pre, "$.data.newerFmeaId")).longValue()).isEqualTo(f2);
        send(post(CP + "/" + pre + "/release"), "{\"rowVer\":" + head(pre) + "}", qe)
                .andExpect(jsonPath("$.errors[*].code").value(hasItems("APQP_CP_FMEA_STALE")));
        send(post(CP + "/" + r2 + "/revise"), "{}", qe).andExpect(jsonPath("$.errors[0].code").value("APQP_CP_FMEA_STALE"));
        long r3 = id(send(post(CP + "/" + r2 + "/revise"), "{\"fmeaId\":" + f2 + "}", qe));
        assertThat(((Number) read(CP + "/" + r3, "$.data.cp.fmeaId")).longValue()).isEqualTo(f2);
        assertThat((List<?>) read(CP + "/" + r3, "$.data.lines[?(@.riskId != null)]")).isEmpty();
        // 새 근거에서 끌어오기: 같은 단계·특성·관리 방법(INSPECT) 행이 이미 있어 건너뜀
        send(post(CP + "/" + r3 + "/pull"), "", qe).andExpect(jsonPath("$.data.added").value(0)).andExpect(jsonPath("$.data.skipped").value(1));
        // 작성 중 삭제
        send(delete(CP + "/" + pre).param("rowVer", String.valueOf(head(pre))), "", qe).andExpect(status().isOk());
        mvc.perform(get(CP + "/" + pre).with(qe)).andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------ 검사계획 대조 (C5 · C6)

    /** QMS 검사계획(공정검사 · 용접 공정): 용접 전류 = 캘리퍼 · 50개마다, 용접 강도(제품 CC) = 육안 · LOT 마다 → 확정 */
    private void inspPlan() throws Exception {
        long u = accounts.newUser(comp, "qa1", PW);
        accounts.grant(comp, u, "QA", "qms-insp-plan", "SEARCH", "SAVE", "APPROVE");
        RequestPostProcessor qa = accounts.as(comp, u, "qa1");
        accounts.exec(comp, """
                insert into qms_sampling_plan (comp_cd, plan_cd, plan_nm, method, fixed_n, ac, re, created_by, updated_by)
                values (?, 'ZT-S5', 'ZT 고정 5', 'FIXED', 5, 0, 1, 't', 't')""", comp);
        long plan = id(send(post("/api/qms/insp-plans"), "{\"inspType\":\"PROCESS\",\"itemCd\":\"ZT-BRKT\",\"procCd\":\"ZWELD\"}", qa));
        String ip = "/api/qms/insp-plans/" + plan;
        int v = ((Number) JsonPath.read(mvc.perform(get(ip).with(qa)).andReturn().getResponse().getContentAsString(), "$.data.plan.rowVer")).intValue();
        send(put(ip + "/lines?rowVer=" + v), "{\"lines\":[{\"rowKey\":\"a\",\"lineNo\":10,\"charId\":" + fx.weldChar()
                + ",\"samplingPlanCd\":\"ZT-S5\",\"gaugeClass\":\"CALIPER\",\"freqType\":\"EVERY_N\",\"freqN\":50,\"methodNm\":\"측정\","
                + "\"reactionPlan\":\"격리\"},{\"rowKey\":\"b\",\"lineNo\":20,\"charId\":" + fx.prodChar()
                + ",\"samplingPlanCd\":\"ZT-S5\",\"gaugeClass\":\"VISUAL\",\"freqType\":\"EVERY_LOT\",\"methodNm\":\"육안\",\"reactionPlan\":\"격리\"}]}", qa)
                .andExpect(status().isOk());
        v = ((Number) JsonPath.read(mvc.perform(get(ip).with(qa)).andReturn().getResponse().getContentAsString(), "$.data.plan.rowVer")).intValue();
        send(post(ip + "/release"), "{\"rowVer\":" + v + "}", qa).andExpect(status().isOk());
    }

    @Test
    void 검사계획_대조_3종_양산단계는_검사계획에_없는_검사행이면_확정_거부() throws Exception {
        inspPlan();
        long cp = id(send(post(CP), "{\"itemCd\":\"ZT-BRKT\",\"routingId\":" + fx.routing() + ",\"phase\":\"PRODUCTION\",\"title\":\"ZT 양산 관리계획서\"}", qe));
        String url = CP + "/" + cp;
        // A: 용접 전류 · 공정검사 · 육안(검사계획은 캘리퍼) → DIFF / B: 용접 강도 · 출하검사(계획 없음) → MISSING_IN_PLAN
        long a = id(send(post(url + "/lines"), "{\"stepId\":" + fx.step10() + ",\"charId\":" + fx.weldChar() + ",\"ctrlMethod\":\"INSPECT\","
                + "\"inspType\":\"PROCESS\",\"gaugeClass\":\"VISUAL\",\"freqType\":\"EVERY_N\",\"freqN\":50,\"reactionPlan\":\"정지\"}", qe));
        long b = id(send(post(url + "/lines"), "{\"stepId\":" + fx.step10() + ",\"charId\":" + fx.prodChar() + ",\"ctrlMethod\":\"INSPECT\","
                + "\"inspType\":\"FINAL\",\"freqType\":\"EVERY_LOT\",\"reactionPlan\":\"보류\"}", qe));
        id(send(post(url + "/lines"), "{\"stepText\":\"ZT 포장\",\"charText\":\"ZT 라벨\",\"ctrlMethod\":\"ERROR_PROOF\",\"reactionPlan\":\"재부착\"}", qe));
        assertThat((Boolean) read(url, "$.data.compare.ready")).isTrue();
        assertThat((Integer) read(url, "$.data.compare.skipped")).isEqualTo(1);
        assertThat(this.<List<Object>>read(url, "$.data.compare.rows[?(@.kind == 'DIFF' && @.lineId == " + a + ")].planGauge")).containsExactly("CALIPER");
        assertThat(this.<List<Object>>read(url, "$.data.compare.rows[?(@.kind == 'MISSING_IN_PLAN')].lineId")).containsExactly((int) b);
        // 검사계획의 용접 강도(공정검사 · 용접)는 CP 의 같은 공정·검사 유형 행에 없음
        assertThat(this.<List<Object>>read(url, "$.data.compare.rows[?(@.kind == 'MISSING_IN_CP')].charNo")).containsExactly("ZC1");
        // 양산 단계 확정: MISSING_IN_PLAN 행 거부(DIFF 는 알림만)
        send(post(url + "/release"), "{\"rowVer\":" + head(cp) + "}", qe).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].rowKey").value(containsInAnyOrder("LINE:" + b)))
                .andExpect(jsonPath("$.errors[0].code").value("APQP_CP_NOT_IN_PLAN"));
        // B 를 공정검사로 → 검사계획과 맞음, 남은 것은 DIFF 하나 → 확정
        send(put(LINE + b), "{\"stepId\":" + fx.step10() + ",\"charId\":" + fx.prodChar() + ",\"ctrlMethod\":\"INSPECT\",\"inspType\":\"PROCESS\","
                + "\"gaugeClass\":\"VISUAL\",\"freqType\":\"EVERY_LOT\",\"reactionPlan\":\"보류\",\"rowVer\":" + ver(url, "$.data.lines[1].rowVer") + "}", qe)
                .andExpect(status().isOk());
        assertThat((Integer) read(url, "$.data.compare.matched")).isEqualTo(1);
        assertThat(this.<List<Object>>read(url, "$.data.compare.rows[*].kind")).containsExactly("DIFF");
        send(post(url + "/release"), "{\"rowVer\":" + head(cp) + "}", qe).andExpect(status().isOk());
        // B사 범위에서는 A사 검사계획을 읽지 않는다(B사 품목 CP 에 같은 특성 이름·같은 공정)
        String other = accounts.newComp("CS");
        long bu = accounts.newUser(other, "qe1", PW);
        accounts.grant(other, bu, "APQP", "apqp-cp", "SEARCH", "SAVE", "APPROVE");
        RequestPostProcessor bq = accounts.as(other, bu, "qe1");
        Fixture fb = fixture(other);
        long bCp = id(send(post(CP), "{\"itemCd\":\"ZT-BRKT\",\"routingId\":" + fb.routing() + ",\"phase\":\"PRODUCTION\",\"title\":\"B 사\"}", bq));
        id(send(post(CP + "/" + bCp + "/lines"), "{\"stepId\":" + fb.step10() + ",\"charId\":" + fb.weldChar() + ",\"ctrlMethod\":\"INSPECT\","
                + "\"inspType\":\"PROCESS\",\"freqType\":\"EVERY_N\",\"freqN\":50,\"reactionPlan\":\"정지\"}", bq));
        String bJson = mvc.perform(get(CP + "/" + bCp).with(bq)).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<Object>>read(bJson, "$.data.compare.rows[*].kind")).containsExactly("MISSING_IN_PLAN");
    }

    // ------------------------------------------------------------------ 이상 사용

    @Test
    void 이상_입력_라우팅밖_단계_남의_공정특성_남의_위험행_중복_범위_낡은_rowVer() throws Exception {
        Fmea f = releasedFmea();
        long cp = id(send(post(CP), "{\"itemCd\":\"ZT-BRKT\",\"fmeaId\":" + f.fmea() + ",\"phase\":\"PROTO\",\"title\":\"ZT 관리계획서\"}", qe));
        String lines = CP + "/" + cp + "/lines";
        // 머리: 단계 없음·코드 밖, 다른 품목의 FMEA, 작성 중 FMEA 를 근거로
        send(post(CP), "{\"itemCd\":\"ZT-BRKT\",\"title\":\"x\"}", qe).andExpect(jsonPath("$.errors[0].field").value("phase"));
        send(post(CP), "{\"itemCd\":\"ZT-BRKT\",\"phase\":\"ZZZ\",\"title\":\"x\"}", qe).andExpect(jsonPath("$.errors[0].code").value("CODE"));
        send(post(CP), "{\"itemCd\":\"ZT-OTHER\",\"fmeaId\":" + f.fmea() + ",\"phase\":\"PROTO\",\"title\":\"x\"}", qe)
                .andExpect(jsonPath("$.errors[0].field").value("fmeaId"));
        long draftFmea = id(send(post(F + "/" + f.fmea() + "/revise"), "", qe));
        send(post(CP), "{\"itemCd\":\"ZT-BRKT\",\"fmeaId\":" + draftFmea + ",\"phase\":\"PROTO\",\"title\":\"x\"}", qe)
                .andExpect(jsonPath("$.errors[0].code").value("APQP_CP_FMEA_STALE"));
        assertThat(catchThrowable(() -> accounts.exec(comp, """
                insert into apqp_cp (comp_cd, cp_no, phase, item_id, routing_id, fmea_id, title, created_by, updated_by)
                values (?, 'ZT-X', 'PROTO', (select item_id from mst_item where item_cd = 'ZT-BRKT'), ?, ?, 'x', 't', 't')""",
                comp, fx.routing(), draftFmea))).hasMessageContaining("is DRAFT");
        // 행: 다른 품목 라우팅의 단계(API · 직접 SQL), 검사 공정 특성을 용접 단계에, 코드·범위 밖, 남의 위험행
        send(post(lines), "{\"stepId\":" + fx.otherStep() + ",\"charText\":\"x\",\"ctrlMethod\":\"OTHER\"}", qe)
                .andExpect(jsonPath("$.errors[0].code").value("APQP_STEP_ROUTING"));
        assertThat(catchThrowable(() -> accounts.exec(comp, """
                insert into apqp_cp_line (comp_cd, cp_id, line_no, step_id, char_text, ctrl_method, created_by, updated_by)
                values (?, ?, 1, ?, 'x', 'OTHER', 't', 't')""", comp, cp, fx.otherStep()))).hasMessageContaining("is not of the control plan routing");
        send(post(lines), "{\"stepId\":" + fx.step10() + ",\"charId\":" + fx.inspChar() + ",\"ctrlMethod\":\"INSPECT\"}", qe)
                .andExpect(jsonPath("$.errors[0].code").value("APQP_CHAR_TARGET"));
        assertThat(catchThrowable(() -> accounts.exec(comp, """
                insert into apqp_cp_line (comp_cd, cp_id, line_no, step_id, char_id, ctrl_method, created_by, updated_by)
                values (?, ?, 1, ?, ?, 'INSPECT', 't', 't')""", comp, cp, fx.step10(), fx.inspChar()))).hasMessageContaining("does not belong");
        send(post(lines), "{\"stepText\":\"x\",\"charText\":\"x\",\"ctrlMethod\":\"ZZZ\",\"gaugeClass\":\"ZZZ\",\"inspType\":\"ZZZ\","
                + "\"freqType\":\"EVERY_HOURS\",\"freqN\":200,\"sampleN\":0}", qe)
                .andExpect(jsonPath("$.errors[*].field").value(containsInAnyOrder("ctrlMethod", "gaugeClass", "inspType", "freqN", "sampleN")));
        send(post(lines), "{\"stepText\":\"x\",\"ctrlMethod\":\"OTHER\"}", qe).andExpect(jsonPath("$.errors[0].field").value("charText"));
        long draftRisk = ((Number) ((List<?>) read(F + "/" + draftFmea, "$.data.risks[*].riskId")).get(0)).longValue();
        send(post(lines), "{\"stepText\":\"x\",\"charText\":\"x\",\"ctrlMethod\":\"OTHER\",\"riskId\":" + draftRisk + "}", qe)
                .andExpect(jsonPath("$.errors[0].field").value("riskId"));
        assertThat(catchThrowable(() -> accounts.exec(comp, """
                insert into apqp_cp_line (comp_cd, cp_id, line_no, step_text, char_text, ctrl_method, risk_id, created_by, updated_by)
                values (?, ?, 1, 'x', 'x', 'OTHER', ?, 't', 't')""", comp, cp, draftRisk))).hasMessageContaining("is not of the control plan fmea");
        // 같은 단계 × 특성 × 관리 방법 두 번
        long a = id(send(post(lines), "{\"stepId\":" + fx.step10() + ",\"charId\":" + fx.weldChar() + ",\"ctrlMethod\":\"INSPECT\"}", qe));
        send(post(lines), "{\"stepId\":" + fx.step10() + ",\"charId\":" + fx.weldChar() + ",\"ctrlMethod\":\"INSPECT\"}", qe)
                .andExpect(jsonPath("$.errors[0].code").value("DUPLICATE"));
        long b = id(send(post(lines), "{\"stepText\":\"ZT 포장\",\"charText\":\"ZT 라벨\",\"ctrlMethod\":\"ERROR_PROOF\",\"reactionPlan\":\"재부착\"}", qe));
        // 순서 바꾸기 → b 가 위로
        send(post(LINE + b + "/move"), "{\"op\":\"UP\",\"rowVer\":" + ver(CP + "/" + cp, "$.data.lines[1].rowVer") + "}", qe).andExpect(status().isOk());
        assertThat(lineId(cp, 0)).isEqualTo(b);
        // 같은 rowVer 두 번 저장 → 두 번째 409, 낡은 rowVer 삭제 409
        int v = ver(CP + "/" + cp, "$.data.lines[1].rowVer");
        String body = "{\"stepId\":" + fx.step10() + ",\"charId\":" + fx.weldChar() + ",\"ctrlMethod\":\"SPC\",\"rowVer\":" + v + "}";
        send(put(LINE + a), body, qe).andExpect(status().isOk());
        send(put(LINE + a), body, qe).andExpect(status().isConflict());
        send(delete(LINE + a).param("rowVer", String.valueOf(v)), "", qe).andExpect(status().isConflict());
        // 근거 위험행을 가리키는 행이 있으면 근거 FMEA 를 못 바꾼다
        send(post(CP + "/" + cp + "/pull"), "", qe).andExpect(jsonPath("$.data.added").value(1));
        send(put(CP + "/" + cp), "{\"routingId\":" + fx.routing() + ",\"title\":\"x\",\"rowVer\":" + head(cp) + "}", qe).andExpect(jsonPath("$.errors[0].code").value("APQP_CP_FMEA_USED"));
    }

    @Test
    void 권한_SAVE_APPROVE_없으면_403() throws Exception {
        Fmea f = releasedFmea();
        long cp = cp(f.fmea(), "PROTO");
        long viewer = accounts.newUser(comp, "view1", PW);
        accounts.grant(comp, viewer, "VIEW", "apqp-cp", "SEARCH");
        RequestPostProcessor v = accounts.as(comp, viewer, "view1");
        mvc.perform(get(CP + "/" + cp).with(v)).andExpect(status().isOk()).andExpect(jsonPath("$.data.editable").value(false));
        mvc.perform(get(CP + "/items").param("keyword", "ZT").with(v)).andExpect(status().isOk());
        send(post(CP), "{\"itemCd\":\"ZT-BRKT\",\"phase\":\"PROTO\",\"title\":\"x\"}", v).andExpect(status().isForbidden());
        send(post(CP + "/" + cp + "/pull"), "", v).andExpect(status().isForbidden());
        send(post(CP + "/" + cp + "/release"), "{\"rowVer\":" + head(cp) + "}", v).andExpect(status().isForbidden());
        long saver = accounts.newUser(comp, "save1", PW);
        accounts.grant(comp, saver, "SAVE", "apqp-cp", "SEARCH", "SAVE");
        send(post(CP + "/" + cp + "/release"), "{\"rowVer\":" + head(cp) + "}", accounts.as(comp, saver, "save1")).andExpect(status().isForbidden());
        // FMEA 화면 권한만으로는 관리계획서를 못 본다
        long fm = accounts.newUser(comp, "fm1", PW);
        accounts.grant(comp, fm, "FM", "apqp-fmea", "SEARCH");
        mvc.perform(get(CP + "/" + cp).with(accounts.as(comp, fm, "fm1"))).andExpect(status().isForbidden());
    }

    @Test
    void A사로_B사_관리계획서_FMEA_특성_단계를_못쓴다() throws Exception {
        Fmea f = releasedFmea();
        long cp = cp(f.fmea(), "PROTO");
        long l1 = lineId(cp, 0);
        String other = accounts.newComp("CR");
        long bu = accounts.newUser(other, "qe1", PW);
        accounts.grant(other, bu, "APQP", "apqp-cp", "SEARCH", "SAVE", "APPROVE");
        RequestPostProcessor b = accounts.as(other, bu, "qe1");
        Fixture fb = fixture(other);
        // 목록·상세 — B사에서 A사 것은 안 보이고, A사 행·머리를 고치거나 지울 수 없다(404)
        mvc.perform(get(CP + "/grid").with(b)).andExpect(jsonPath("$.total").value(0));
        mvc.perform(get(CP + "/" + cp).with(b)).andExpect(status().isNotFound());
        send(post(CP + "/" + cp + "/lines"), "{\"stepText\":\"x\",\"charText\":\"x\",\"ctrlMethod\":\"OTHER\"}", b).andExpect(status().isNotFound());
        send(put(LINE + l1), "{\"stepText\":\"x\",\"charText\":\"x\",\"ctrlMethod\":\"OTHER\",\"rowVer\":0}", b).andExpect(status().isNotFound());
        send(post(CP + "/" + cp + "/pull"), "", b).andExpect(status().isNotFound());
        mvc.perform(get(CP + "/fmeas").param("itemCd", "ZT-BRKT").with(b)).andExpect(jsonPath("$.data.length()").value(0));
        // B사 관리계획서에 A사 FMEA·A사 단계·A사 특성
        send(post(CP), "{\"itemCd\":\"ZT-BRKT\",\"fmeaId\":" + f.fmea() + ",\"phase\":\"PROTO\",\"title\":\"x\"}", b)
                .andExpect(jsonPath("$.errors[0].field").value("fmeaId"));
        long bCp = id(send(post(CP), "{\"itemCd\":\"ZT-BRKT\",\"routingId\":" + fb.routing() + ",\"phase\":\"PROTO\",\"title\":\"B 사 관리계획서\"}", b));
        send(post(CP + "/" + bCp + "/lines"), "{\"stepId\":" + fx.step10() + ",\"charText\":\"x\",\"ctrlMethod\":\"OTHER\"}", b)
                .andExpect(jsonPath("$.errors[0].field").value("stepId"));
        send(post(CP + "/" + bCp + "/lines"), "{\"stepId\":" + fb.step10() + ",\"charId\":" + fx.prodChar() + ",\"ctrlMethod\":\"INSPECT\"}", b)
                .andExpect(jsonPath("$.errors[0].field").value("charId"));
        // 직접 SQL: B사 범위에서 A사 관리계획서 행·A사 특성·A사 FMEA → 트리거·RLS
        assertThat(catchThrowable(() -> accounts.exec(other, """
                insert into apqp_cp_line (comp_cd, cp_id, line_no, step_text, char_text, ctrl_method, created_by, updated_by)
                values (?, ?, 1, 'x', 'x', 'OTHER', 't', 't')""", other, cp))).hasMessageContaining("is not of company");
        assertThat(catchThrowable(() -> accounts.exec(other, """
                insert into apqp_cp_line (comp_cd, cp_id, line_no, step_text, char_id, ctrl_method, created_by, updated_by)
                values (?, ?, 1, 'x', ?, 'OTHER', 't', 't')""", other, bCp, fx.prodChar()))).hasMessageContaining("is not of company");
        assertThat(catchThrowable(() -> accounts.exec(other, "update apqp_cp set fmea_id = ? where cp_id = ?", f.fmea(), bCp)))
                .hasMessageContaining("is not of item");
        assertThat(accounts.exec(other, "update apqp_cp set title = 'x' where cp_id = ?", cp)).isZero();
        assertThat(accounts.exec(other, "delete from apqp_cp_line where line_id = ?", l1)).isZero();
    }
}
