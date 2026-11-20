package io.smarthappy.module.plm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.module.plm.link.ChangeMarker;
import io.smarthappy.module.plm.link.EcoTrace;
import io.smarthappy.platform.common.db.PgErrors;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * PLM 1단계 설계 변경 (docs/39) — ECR 접수 → ECO 대상·상신·결재(플랫폼 결재)·적용(BOM·라우팅 기존 확정 흐름),
 * 결재 뒤 대상 변경 거부, 고객 승인 게이트, 한 리비전 = 열린 지시 하나, 롤백, 상태·권한, A사로 B사, 직접 SQL.
 */
@AutoConfigureMockMvc
class PlmEcoTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";
    private static final String ECR = "/api/plm/ecrs";
    private static final String ECO = "/api/plm/ecos";
    private static final String APR = "/api/platform/approvals";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    @Autowired
    ObjectProvider<ChangeMarker> markers;

    @Autowired
    ObjectProvider<EcoTrace> traces;

    String comp;
    long ownerId;
    long approverId;
    RequestPostProcessor owner;
    RequestPostProcessor approver;
    long bomA0;
    long bomA1;
    long routingA1;
    String today;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("PLM");
        ownerId = accounts.newUser(comp, "eng1", PW);
        approverId = accounts.newUser(comp, "mgr1", PW);
        for (long u : new long[] {ownerId, approverId}) {
            accounts.grant(comp, u, "ENG", "plm-ecr", "SEARCH", "SAVE", "APPROVE");
            accounts.grant(comp, u, "ENG", "plm-eco", "SEARCH", "SAVE", "APPROVE");
        }
        owner = accounts.as(comp, ownerId, "eng1");
        approver = accounts.as(comp, approverId, "mgr1");
        accounts.addPlant(comp, "P1", "1공장");
        today = accounts.query(comp, "select " + TestAccounts.COMP_TODAY + "::text", String.class);
        seedMaster(comp);
        bomA0 = id(comp, "select bom_id from mst_bom where rev = 'A0'");
        bomA1 = id(comp, "select bom_id from mst_bom where rev = 'A1'");
        routingA1 = id(comp, "select routing_id from mst_routing where rev = 'A1'");
    }

    /** FG ← RM. BOM A0 확정(30일 전부터) + A1 작성 중(행 1), 라우팅 A0 확정 + A1 작성 중(외주 공정 1단계) */
    private void seedMaster(String c) {
        for (String i : new String[] {"FG", "RM", "RM2"}) {
            accounts.exec(c, """
                    insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, created_by, updated_by)
                    values (?, ?, ?, 'FG', 'MAKE', 'EA', 't', 't')""", c, i, i + " 품목");
        }
        accounts.exec(c, """
                insert into mst_proc (comp_cd, proc_cd, proc_nm, proc_cat, outsrc_yn, created_by, updated_by)
                values (?, 'PLATE', '외주 도금', 'WELD', 'Y', 't', 't')""", c);
        for (String rev : new String[] {"A0", "A1"}) {
            long b = accounts.query(c, """
                    insert into mst_bom (comp_cd, item_id, rev, valid_from, created_by, updated_by)
                    values (?, (select item_id from mst_item where item_cd = 'FG'), ?,
                            case when ? = 'A0' then (now() at time zone 'Asia/Seoul')::date - 30 else (now() at time zone 'Asia/Seoul')::date + 1 end, 't', 't') returning bom_id""",
                    Long.class, c, rev, rev);
            accounts.exec(c, """
                    insert into mst_bom_line (comp_cd, bom_id, line_no, item_id, qty, unit_cd, line_type, issue_method,
                                              created_by, updated_by)
                    values (?, ?, 10, (select item_id from mst_item where item_cd = ?), 2, 'EA', 'NORMAL', 'BACKFLUSH', 't', 't')""",
                    c, b, "A0".equals(rev) ? "RM" : "RM2");
            long r = accounts.query(c, """
                    insert into mst_routing (comp_cd, item_id, plant_cd, rev, valid_from, created_by, updated_by)
                    values (?, (select item_id from mst_item where item_cd = 'FG'), 'P1', ?,
                            case when ? = 'A0' then (now() at time zone 'Asia/Seoul')::date - 30 else (now() at time zone 'Asia/Seoul')::date + 1 end, 't', 't') returning routing_id""",
                    Long.class, c, rev, rev);
            accounts.exec(c, """
                    insert into mst_routing_step (comp_cd, routing_id, op_seq, proc_id, created_by, updated_by)
                    values (?, ?, 10, (select proc_id from mst_proc where proc_cd = 'PLATE'), 't', 't')""", c, r);
            if ("A0".equals(rev)) {
                accounts.exec(c, "update mst_bom set status = 'RELEASED', released_at = now(), released_by = 't' where bom_id = ?", b);
                accounts.exec(c, "update mst_routing set status = 'RELEASED', released_at = now(), released_by = 't' where routing_id = ?", r);
            }
        }
    }

    // ---------- 도우미 ----------

    private long id(String c, String sql, Object... args) {
        return accounts.query(c, sql, Long.class, args);
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String body) {
        return b.with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private ResultActions postAs(RequestPostProcessor who, String url, String body) throws Exception {
        return mvc.perform(json(post(url), body).with(who));
    }

    private ResultActions putAs(RequestPostProcessor who, String url, String body) throws Exception {
        return mvc.perform(json(put(url), body).with(who));
    }

    private static long data(ResultActions r) throws Exception {
        MvcResult m = r.andExpect(status().isOk()).andReturn();
        return ((Number) JsonPath.read(m.getResponse().getContentAsString(), "$.data")).longValue();
    }

    private int ecoVer(long ecoId) {
        return accounts.query(comp, "select row_ver from plm_eco where eco_id = ?", Integer.class, ecoId);
    }

    private String ecoStatus(long ecoId) {
        return accounts.query(comp, "select status from plm_eco where eco_id = ?", String.class, ecoId);
    }

    private long newEco(String extra) throws Exception {
        return data(postAs(owner, ECO, "{\"title\":\"브래킷 두께 변경\",\"reasonCd\":\"QUALITY\",\"effectiveDate\":\"" + today + "\""
                + (extra == null ? "" : "," + extra) + "}"));
    }

    private ResultActions saveTargets(long ecoId, String targetsJson) throws Exception {
        return putAs(owner, ECO + "/" + ecoId + "/targets", "{\"rowVer\":" + ecoVer(ecoId) + ",\"targets\":" + targetsJson + "}");
    }

    private String bomTarget(long bomId) {
        return "{\"rowKey\":\"b" + bomId + "\",\"targetType\":\"BOM\",\"refId\":" + bomId + ",\"changeDesc\":\"RM → RM2\"}";
    }

    private String routingTarget(long routingId) {
        return "{\"rowKey\":\"r" + routingId + "\",\"targetType\":\"ROUTING\",\"refId\":" + routingId + "}";
    }

    private ResultActions submit(long ecoId) throws Exception {
        return postAs(owner, ECO + "/" + ecoId + "/submit", "{\"rowVer\":" + ecoVer(ecoId)
                + ",\"lines\":[{\"userId\":" + approverId + ",\"lineType\":\"APPROVE\"}]}");
    }

    private long aprId(long ecoId) {
        return id(comp, "select apr_id from plm_eco where eco_id = ?", ecoId);
    }

    private ResultActions approve(long ecoId) throws Exception {
        return postAs(approver, APR + "/" + aprId(ecoId) + "/approve", "{\"comment\":\"확인\"}");
    }

    private ResultActions release(long ecoId) throws Exception {
        return postAs(approver, ECO + "/" + ecoId + "/release", "{\"rowVer\":" + ecoVer(ecoId) + "}");
    }

    /** 대상 BOM A1 + 라우팅 A1, 상신·승인까지 */
    private long approvedEco() throws Exception {
        return approvedEco("[" + bomTarget(bomA1) + "," + routingTarget(routingA1) + "]");
    }

    private long approvedEco(String targetsJson) throws Exception {
        long eco = newEco(null);
        saveTargets(eco, targetsJson).andExpect(status().isOk());
        submit(eco).andExpect(status().isOk());
        approve(eco).andExpect(status().isOk());
        assertThat(ecoStatus(eco)).isEqualTo("APPROVED");
        return eco;
    }

    // ---------- 정상 ----------

    @Test
    void ECR_접수_ECO_상신_승인_적용하면_BOM_라우팅이_유효일부터_확정되고_이전본은_마감() throws Exception {
        long ecr = data(postAs(owner, ECR, "{\"title\":\"브래킷 균열\",\"reasonCd\":\"QUALITY\",\"itemCd\":\"fg\",\"description\":\"고객 클레임\"}"));
        mvc.perform(get(ECR + "/" + ecr).with(owner))
                .andExpect(jsonPath("$.data.ecrNo").value(Matchers.matchesPattern("^ECR\\d{4}-0001$")))
                .andExpect(jsonPath("$.data.itemCd").value("FG"))
                .andExpect(jsonPath("$.data.status").value("OPEN"))
                .andExpect(jsonPath("$.data.mine").value(true));
        long eco = data(postAs(approver, ECR + "/" + ecr + "/accept", "{\"rowVer\":0,\"comment\":\"접수\"}"));
        mvc.perform(get(ECR + "/" + ecr).with(owner))
                .andExpect(jsonPath("$.data.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.data.ecoId").value((int) eco))
                .andExpect(jsonPath("$.data.mine").value(true));
        // 접수 = 새 작성 중 지시(요청 복사, 주 품목 = ITEM 대상, 담당 = 접수자)
        mvc.perform(get(ECO + "/" + eco).with(approver))
                .andExpect(jsonPath("$.data.eco.ecoNo").value(Matchers.matchesPattern("^ECO\\d{4}-0001$")))
                .andExpect(jsonPath("$.data.eco.title").value("브래킷 균열"))
                .andExpect(jsonPath("$.data.eco.ownerId").value((int) approverId))
                .andExpect(jsonPath("$.data.targets[0].targetType").value("ITEM"))
                .andExpect(jsonPath("$.data.ecrs[0].ecrId").value((int) ecr));

        // 대상: ITEM 유지 + BOM A1 + 라우팅 A1 (한 벌 저장)
        long itemTarget = id(comp, "select target_id from plm_eco_target where eco_id = ?", eco);
        putAs(approver, ECO + "/" + eco + "/targets", "{\"rowVer\":" + ecoVer(eco) + ",\"targets\":[{\"targetId\":" + itemTarget
                + ",\"changeDesc\":\"도면 B\"}," + bomTarget(bomA1) + "," + routingTarget(routingA1) + "]}").andExpect(status().isOk());
        postAs(approver, ECO + "/" + eco + "/submit", "{\"rowVer\":" + ecoVer(eco) + ",\"lines\":[{\"userId\":" + ownerId + "}]}")
                .andExpect(status().isOk());
        assertThat(ecoStatus(eco)).isEqualTo("PENDING");
        assertThat(accounts.query(comp, "select count(*) from plm_eco_target where eco_id = ? and base_row_ver is not null",
                Integer.class, eco)).isEqualTo(2);
        mvc.perform(get(ECO + "/" + eco).with(owner))
                .andExpect(jsonPath("$.data.approval.myTurn").value(true))
                .andExpect(jsonPath("$.data.approval.canWithdraw").value(false));
        postAs(owner, APR + "/" + aprId(eco) + "/approve", "{}").andExpect(status().isOk());
        assertThat(ecoStatus(eco)).isEqualTo("APPROVED");

        postAs(approver, ECO + "/" + eco + "/release", "{\"rowVer\":" + ecoVer(eco) + "}").andExpect(status().isOk());
        String ecoNo = accounts.query(comp, "select eco_no from plm_eco where eco_id = ?", String.class, eco);
        assertThat(ecoStatus(eco)).isEqualTo("RELEASED");
        // A1 = 오늘(유효일)부터 확정, 변경 번호 = ECO 번호 / A0 = 오늘로 마감
        assertThat(accounts.query(comp, "select status || ' ' || valid_from || ' ' || change_no from mst_bom where bom_id = ?",
                String.class, bomA1)).isEqualTo("RELEASED " + today + " " + ecoNo);
        assertThat(accounts.query(comp, "select valid_to::text from mst_bom where bom_id = ?", String.class, bomA0)).isEqualTo(today);
        assertThat(accounts.query(comp, "select status || ' ' || valid_from || ' ' || change_no from mst_routing where routing_id = ?",
                String.class, routingA1)).isEqualTo("RELEASED " + today + " " + ecoNo);
        // 적용 뒤에는 "결재 뒤 바뀜" 이 아니다(확정이 row_ver 를 올린 것) — 브라우저 확인에서 발견
        mvc.perform(get(ECO + "/" + eco).with(owner))
                .andExpect(jsonPath("$.data.targets[*].changed").value(Matchers.everyItem(Matchers.is(false))));
        // 감사 행위자 = 적용한 사람
        assertThat(accounts.query(comp, "select released_by from plm_eco where eco_id = ?", String.class, eco)).isEqualTo("mgr1");
    }

    @Test
    void 반려하면_작성_중으로_돌아와_다시_고치고_상신할_수_있다() throws Exception {
        long eco = newEco(null);
        saveTargets(eco, "[" + bomTarget(bomA1) + "]").andExpect(status().isOk());
        submit(eco).andExpect(status().isOk());
        // 결재 중에는 내용·대상 수정 불가
        putAs(owner, ECO + "/" + eco, "{\"title\":\"x\",\"reasonCd\":\"QUALITY\",\"effectiveDate\":\"" + today + "\",\"rowVer\":"
                + ecoVer(eco) + "}").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PLM_ECO_STATE"));
        saveTargets(eco, "[]").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PLM_ECO_STATE"));
        postAs(approver, APR + "/" + aprId(eco) + "/reject", "{\"comment\":\"자재 확인 필요\"}").andExpect(status().isOk());
        assertThat(ecoStatus(eco)).isEqualTo("DRAFT");
        assertThat(accounts.query(comp, "select count(*) from plm_eco_target where eco_id = ? and base_row_ver is not null",
                Integer.class, eco)).isZero();
        putAs(owner, ECO + "/" + eco, "{\"title\":\"브래킷 두께 변경 2\",\"reasonCd\":\"QUALITY\",\"effectiveDate\":\"" + today
                + "\",\"rowVer\":" + ecoVer(eco) + "}").andExpect(status().isOk());
        submit(eco).andExpect(status().isOk());
        // 회수(요청자, 아무도 처리 전) → 작성 중
        mvc.perform(get(ECO + "/" + eco).with(owner)).andExpect(jsonPath("$.data.approval.canWithdraw").value(true));
        postAs(owner, APR + "/" + aprId(eco) + "/withdraw", "{}").andExpect(status().isOk());
        assertThat(ecoStatus(eco)).isEqualTo("DRAFT");
    }

    // ---------- F 결재 뒤 변경 · G 롤백 ----------

    @Test
    void 상신_뒤_BOM_행을_고치면_적용이_거부되고_아무것도_확정되지_않는다() throws Exception {
        long eco = approvedEco();
        // BOM 화면에서 작성 중 A1 행 수정(머리 row_ver 증가) — 결재받은 내용과 달라짐
        long line = id(comp, "select line_id from mst_bom_line where bom_id = ?", bomA1);
        int bomVer = accounts.query(comp, "select row_ver from mst_bom where bom_id = ?", Integer.class, bomA1);
        int lineVer = accounts.query(comp, "select row_ver from mst_bom_line where line_id = ?", Integer.class, line);
        accounts.grant(comp, ownerId, "BOM", "mst-bom", "SEARCH", "SAVE");
        postAs(owner, "/api/master/boms/" + bomA1 + "/lines/batch?rowVer=" + bomVer, "{\"created\":[],\"deleted\":[],\"updated\":[{\"rowKey\":\"x\","
                + "\"lineId\":" + line + ",\"rowVer\":" + lineVer + ",\"lineNo\":10,\"itemCd\":\"RM2\",\"qty\":\"3\",\"unitCd\":\"EA\","
                + "\"lineType\":\"NORMAL\",\"scrapPct\":\"0\",\"fixedYn\":\"N\",\"issueMethod\":\"BACKFLUSH\"}]}")
                .andExpect(status().isOk());
        long target = id(comp, "select target_id from plm_eco_target where eco_id = ? and bom_id = ?", eco, bomA1);
        release(eco).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PLM_TARGET_CHANGED"))
                .andExpect(jsonPath("$.errors[0].rowKey").value("t" + target))
                .andExpect(jsonPath("$.errors[0].code").value("TARGET_CHANGED"));
        mvc.perform(get(ECO + "/" + eco).with(owner))
                .andExpect(jsonPath("$.data.targets[?(@.bomId == " + bomA1 + ")].changed").value(Matchers.contains(true)));
        assertThat(accounts.query(comp, "select status from mst_routing where routing_id = ?", String.class, routingA1)).isEqualTo("DRAFT");
        assertThat(ecoStatus(eco)).isEqualTo("APPROVED");
    }

    @Test
    void 상신_뒤_BOM_화면에서_리비전을_폐기하면_적용이_거부된다() throws Exception {
        long eco = approvedEco();
        accounts.exec(comp, "update mst_bom set status = 'OBSOLETE' where bom_id = ?", bomA1);
        release(eco).andExpect(status().isConflict()).andExpect(jsonPath("$.errors[0].code").value("NOT_DRAFT"));
    }

    @Test
    void 대상_하나가_확정_검사에_걸리면_다른_대상도_확정되지_않는다() throws Exception {
        // 순서: 라우팅(정상) → BOM(자품목 RM2 사용 중지 → 확정 검사 행 오류). 라우팅 확정도 함께 롤백돼야 한다
        long eco = approvedEco("[" + routingTarget(routingA1) + "," + bomTarget(bomA1) + "]");
        accounts.exec(comp, "update mst_item set use_yn = 'N' where item_cd = 'RM2'");
        release(eco).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PLM_RELEASE_CHECK"))
                .andExpect(jsonPath("$.errors[0].rowKey").value(Matchers.startsWith("t")))
                .andExpect(jsonPath("$.errors[0].code").value("RELEASE_CHECK"))
                .andExpect(jsonPath("$.errors[1].rowKey").value(Matchers.containsString(":")));
        assertThat(accounts.query(comp, "select status from mst_bom where bom_id = ?", String.class, bomA1)).isEqualTo("DRAFT");
        assertThat(accounts.query(comp, "select status from mst_routing where routing_id = ?", String.class, routingA1)).isEqualTo("DRAFT");
        assertThat(accounts.query(comp, "select valid_to is null from mst_bom where bom_id = ?", Boolean.class, bomA0)).isTrue();
        assertThat(ecoStatus(eco)).isEqualTo("APPROVED");
    }

    // ---------- 고객 승인 게이트 ----------

    @Test
    void 고객_승인이_필요하면_기록_전에는_적용할_수_없다() throws Exception {
        accounts.exec(comp, """
                insert into mst_cust (comp_cd, cust_cd, cust_nm, cust_type, created_by, updated_by)
                values (?, 'C1', '고객1', 'SALES', 't', 't'), (?, 'S1', '협력사', 'PURCHASE', 't', 't')""", comp, comp);
        postAs(owner, ECO, "{\"title\":\"t\",\"reasonCd\":\"CUSTOMER\",\"effectiveDate\":\"" + today
                + "\",\"custApprovalReqYn\":\"Y\",\"custCd\":\"S1\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("custCd"));
        long eco = newEco("\"custApprovalReqYn\":\"Y\",\"custCd\":\"C1\"");
        saveTargets(eco, "[" + bomTarget(bomA1) + "]").andExpect(status().isOk());
        submit(eco).andExpect(status().isOk());
        approve(eco).andExpect(status().isOk());
        release(eco).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PLM_CUST_APPROVAL_REQUIRED"));
        // 직접 SQL 로 건너뛰기도 트리거가 막는다
        Throwable t = catchThrowable(() -> accounts.exec(comp,
                "update plm_eco set status = 'RELEASED', released_at = now() where eco_id = ?", eco));
        assertThat(PgErrors.constraint(t)).contains("ck_plm_eco_cust_gate");
        String base = ECO + "/" + eco + "/cust-approval";
        postAs(owner, base, "{\"rowVer\":" + ecoVer(eco) + ",\"status\":\"APPROVED\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field").value(Matchers.containsInAnyOrder("custApprovalRef", "custApprovedOn")));
        postAs(owner, base, "{\"rowVer\":" + ecoVer(eco) + ",\"status\":\"APPROVED\",\"ref\":\"PPAP-2026-17\",\"approvedOn\":\""
                + today + "\"}").andExpect(status().isOk());
        release(eco).andExpect(status().isOk());
        assertThat(ecoStatus(eco)).isEqualTo("RELEASED");
        // 적용 뒤 고객 승인 기록 변경 불가
        postAs(owner, base, "{\"rowVer\":" + ecoVer(eco) + ",\"status\":\"REJECTED\"}").andExpect(status().isConflict());
    }

    // ---------- C 상태 · D 동시성 · E 값 ----------

    @Test
    void 상태_규칙_승인_전_적용_불가_연타_한_번_직접_SQL_건너뛰기_불가() throws Exception {
        long eco = newEco(null);
        release(eco).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PLM_ECO_STATE"));
        submit(eco).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("targets"));
        Throwable t = catchThrowable(() -> accounts.exec(comp,
                "update plm_eco set status = 'APPROVED', approved_at = now() where eco_id = ?", eco));
        assertThat(PgErrors.constraint(t)).contains("ck_plm_eco_transition");

        long eco2 = approvedEco();
        // 승인 뒤 내용 직접 수정도 불가
        Throwable t2 = catchThrowable(() -> accounts.exec(comp, "update plm_eco set effective_date = effective_date + 1 where eco_id = ?", eco2));
        assertThat(PgErrors.constraint(t2)).contains("ck_plm_eco_fixed");
        Throwable t3 = catchThrowable(() -> accounts.exec(comp, "delete from plm_eco_target where eco_id = ?", eco2));
        assertThat(t3).isNotNull();
        int ver = ecoVer(eco2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Integer> call = () -> postAs(approver, ECO + "/" + eco2 + "/release", "{\"rowVer\":" + ver + "}")
                    .andReturn().getResponse().getStatus();
            List<Future<Integer>> fs = pool.invokeAll(List.of(call, call));
            assertThat(List.of(fs.get(0).get(), fs.get(1).get())).containsExactlyInAnyOrder(200, 409);
        } finally {
            pool.shutdown();
        }
        assertThat(ecoStatus(eco2)).isEqualTo("RELEASED");
    }

    @Test
    void 같은_리비전은_열린_지시_하나에만_취소하면_다시_쓸_수_있다() throws Exception {
        long eco1 = newEco(null);
        long eco2 = newEco(null);
        saveTargets(eco1, "[" + bomTarget(bomA1) + "]").andExpect(status().isOk());
        String no1 = accounts.query(comp, "select eco_no from plm_eco where eco_id = ?", String.class, eco1);
        saveTargets(eco2, "[" + bomTarget(bomA1) + "]").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].rowKey").value("b" + bomA1))
                .andExpect(jsonPath("$.errors[0].code").value("OPEN_ECO"))
                .andExpect(jsonPath("$.errors[0].message").value(Matchers.containsString(no1)));
        // 직접 SQL 도 트리거가 막는다
        Throwable t = catchThrowable(() -> accounts.exec(comp, """
                insert into plm_eco_target (comp_cd, eco_id, seq, target_type, item_id, bom_id, created_by, updated_by)
                values (?, ?, 1, 'BOM', (select item_id from mst_item where item_cd = 'FG'), ?, 't', 't')""", comp, eco2, bomA1));
        assertThat(PgErrors.constraint(t)).contains("uq_plm_eco_target_open");
        // 확정본은 대상이 될 수 없다
        saveTargets(eco2, "[" + bomTarget(bomA0) + "]").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("NOT_DRAFT"));
        postAs(owner, ECO + "/" + eco1 + "/cancel", "{\"rowVer\":" + ecoVer(eco1) + ",\"reason\":\"중복\"}").andExpect(status().isOk());
        saveTargets(eco2, "[" + bomTarget(bomA1) + "]").andExpect(status().isOk());
    }

    @Test
    void 값_검사_지난_유효일_빈_제목_없는_사유_오래된_rowVer() throws Exception {
        postAs(owner, ECO, "{\"title\":\" \",\"reasonCd\":\"NOPE\",\"effectiveDate\":\"2001-01-01\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field").value(Matchers.containsInAnyOrder("title", "reasonCd", "effectiveDate")))
                .andExpect(jsonPath("$.errors[?(@.field == 'effectiveDate')].code").value(Matchers.contains("PAST")));
        long eco = newEco(null);
        int ver = ecoVer(eco);
        putAs(owner, ECO + "/" + eco, "{\"title\":\"a\",\"reasonCd\":\"COST\",\"effectiveDate\":\"" + today + "\",\"rowVer\":" + ver + "}")
                .andExpect(status().isOk());
        putAs(owner, ECO + "/" + eco, "{\"title\":\"b\",\"reasonCd\":\"COST\",\"effectiveDate\":\"" + today + "\",\"rowVer\":" + ver + "}")
                .andExpect(status().isConflict());
        saveTargets(eco, "[{\"rowKey\":\"x\",\"targetType\":\"BOM\"},{\"rowKey\":\"y\",\"targetType\":\"NOPE\"},"
                + "{\"rowKey\":\"z\",\"targetType\":\"ITEM\",\"itemCd\":\"NONE\"}]").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].code").value(Matchers.containsInAnyOrder("REQUIRED", "CODE", "NOT_FOUND")));
        // 회사가 추가한 사유 코드는 쓸 수 있다
        accounts.exec(comp, """
                insert into sys_comp_code (comp_cd, grp_cd, code, code_nm, sort_no, created_by, updated_by)
                values (?, 'PLM_CHANGE_REASON', 'VAVE', 'VA/VE', 50, 't', 't')""", comp);
        postAs(owner, ECO, "{\"title\":\"VA\",\"reasonCd\":\"vave\",\"effectiveDate\":\"" + today + "\"}").andExpect(status().isOk());
    }

    // ---------- A 권한 ----------

    @Test
    void 권한_조회만_저장_불가_SAVE_만_적용_접수_불가_남의_요청_수정_불가() throws Exception {
        long viewer = accounts.newUser(comp, "view1", PW);
        accounts.grant(comp, viewer, "VIEW", "plm-eco", "SEARCH");
        accounts.grant(comp, viewer, "VIEW", "plm-ecr", "SEARCH", "SAVE");
        RequestPostProcessor v = accounts.as(comp, viewer, "view1");
        long eco = approvedEco();
        mvc.perform(get(ECO + "/" + eco).with(v)).andExpect(status().isOk());
        postAs(v, ECO, "{\"title\":\"t\",\"reasonCd\":\"COST\",\"effectiveDate\":\"" + today + "\"}").andExpect(status().isForbidden());
        postAs(v, ECO + "/" + eco + "/release", "{\"rowVer\":" + ecoVer(eco) + "}").andExpect(status().isForbidden());
        long ecr = data(postAs(owner, ECR, "{\"title\":\"요청\",\"reasonCd\":\"COST\"}"));
        postAs(v, ECR + "/" + ecr + "/accept", "{\"rowVer\":0}").andExpect(status().isForbidden());
        putAs(v, ECR + "/" + ecr, "{\"title\":\"남의 요청\",\"reasonCd\":\"COST\",\"rowVer\":0}").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PLM_NOT_REQUESTER"));
        postAs(v, ECR + "/" + ecr + "/cancel", "{\"rowVer\":0}").andExpect(status().isForbidden());
        // 반려 사유 필수, 닫힌 요청은 다시 못 닫는다
        postAs(approver, ECR + "/" + ecr + "/reject", "{\"rowVer\":0,\"comment\":\" \"}").andExpect(status().isBadRequest());
        postAs(approver, ECR + "/" + ecr + "/reject", "{\"rowVer\":0,\"comment\":\"효과 없음\"}").andExpect(status().isOk());
        postAs(approver, ECR + "/" + ecr + "/accept", "{\"rowVer\":1}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PLM_ECR_CLOSED"));
    }

    // ---------- B A사로 B사 ----------

    @Test
    void A사로_B사_지시_요청_대상_리비전에_손댈_수_없다() throws Exception {
        String compB = accounts.newComp("PLMB");
        long userB = accounts.newUser(compB, "engB", PW);
        accounts.grant(compB, userB, "ENG", "plm-eco", "SEARCH", "SAVE", "APPROVE");
        accounts.grant(compB, userB, "ENG", "plm-ecr", "SEARCH", "SAVE", "APPROVE");
        accounts.addPlant(compB, "P1", "1공장");
        seedMaster(compB);
        RequestPostProcessor b = accounts.as(compB, userB, "engB");
        long bomB = id(compB, "select bom_id from mst_bom where rev = 'A1'");
        long ecoB = data(mvc.perform(json(post(ECO), "{\"title\":\"B사\",\"reasonCd\":\"COST\",\"effectiveDate\":\"" + today + "\"}").with(b)));
        long ecrB = data(mvc.perform(json(post(ECR), "{\"title\":\"B사 요청\",\"reasonCd\":\"COST\"}").with(b)));

        // A사 사용자로 B사 지시·요청
        mvc.perform(get(ECO + "/" + ecoB).with(owner)).andExpect(status().isNotFound());
        putAs(owner, ECO + "/" + ecoB, "{\"title\":\"x\",\"reasonCd\":\"COST\",\"effectiveDate\":\"" + today + "\",\"rowVer\":0}")
                .andExpect(status().isNotFound());
        putAs(owner, ECO + "/" + ecoB + "/targets", "{\"rowVer\":0,\"targets\":[]}").andExpect(status().isNotFound());
        postAs(owner, ECO + "/" + ecoB + "/submit", "{\"rowVer\":0,\"lines\":[]}").andExpect(status().isNotFound());
        postAs(owner, ECO + "/" + ecoB + "/release", "{\"rowVer\":0}").andExpect(status().isNotFound());
        postAs(owner, ECO + "/" + ecoB + "/cancel", "{\"rowVer\":0,\"reason\":\"x\"}").andExpect(status().isNotFound());
        mvc.perform(get(ECR + "/" + ecrB).with(owner)).andExpect(status().isNotFound());
        postAs(approver, ECR + "/" + ecrB + "/accept", "{\"rowVer\":0}").andExpect(status().isNotFound());
        mvc.perform(get(ECO).with(owner)).andExpect(jsonPath("$.data.rows[?(@.ecoId == " + ecoB + ")]").isEmpty());
        mvc.perform(get(ECO + "/candidates").with(owner))
                .andExpect(jsonPath("$.data[?(@.refId == " + bomB + ")]").isEmpty());

        // A사 지시에 B사 BOM 리비전·B사 지시에 묶기
        long eco = newEco(null);
        saveTargets(eco, "[" + bomTarget(bomB) + "]").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("NOT_FOUND"));
        long ecrA = data(postAs(owner, ECR, "{\"title\":\"A 요청\",\"reasonCd\":\"COST\"}"));
        postAs(approver, ECR + "/" + ecrA + "/accept", "{\"rowVer\":0,\"ecoId\":" + ecoB + "}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("ecoId"));
        // 직접 SQL(앱 계정, A사 범위): B사 리비전·B사 지시를 가리키는 행
        Throwable t1 = catchThrowable(() -> accounts.exec(comp, """
                insert into plm_eco_target (comp_cd, eco_id, seq, target_type, item_id, bom_id, created_by, updated_by)
                values (?, ?, 1, 'BOM', (select item_id from mst_item where item_cd = 'FG'), ?, 't', 't')""", comp, eco, bomB));
        assertThat(PgErrors.constraint(t1)).contains("fk_plm_eco_target_comp");
        Throwable t2 = catchThrowable(() -> accounts.exec(comp, """
                insert into plm_eco_target (comp_cd, eco_id, seq, target_type, item_id, created_by, updated_by)
                values (?, ?, 1, 'ITEM', (select item_id from mst_item where item_cd = 'FG'), 't', 't')""", comp, ecoB));
        assertThat(PgErrors.constraint(t2)).contains("fk_plm_eco_target_comp");
        // B사 comp_cd 로 넣기 — 참조 검사 트리거 또는 RLS WITH CHECK 가 막는다
        Throwable t3 = catchThrowable(() -> accounts.exec(comp, """
                insert into plm_ecr (comp_cd, ecr_no, title, reason_cd, requester_id, created_by, updated_by)
                values (?, 'X', 'x', 'COST', ?, 't', 't')""", compB, userB));
        assertThat(t3).isNotNull();
        assertThat(accounts.query(compB, "select count(*) from plm_ecr where ecr_no = 'X'", Integer.class)).isZero();
        // 다른 회사 사용자를 담당으로
        putAs(owner, ECO + "/" + eco, "{\"title\":\"x\",\"reasonCd\":\"COST\",\"effectiveDate\":\"" + today + "\",\"ownerId\":" + userB
                + ",\"rowVer\":" + ecoVer(eco) + "}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("ownerId"));
        // B사 지시 첨부 목록은 A사에게 빈 목록이 아니라 거부/없음
        assertThat(mvc.perform(get("/api/platform/files?targetType=plm-eco&targetId=" + ecoB).with(owner))
                .andReturn().getResponse().getContentAsString()).doesNotContain("\"fileId\"");
    }

    // ---------- 3a 계약 → 3b 실제 구현 (docs/39 12.7·13장) ----------

    @Test
    void 앱_컨텍스트에서_계약_빈은_하나씩이고_3b_실제_구현이다() {
        assertThat(markers.getIfUnique()).isNotNull().isNotSameAs(ChangeMarker.NONE);
        assertThat(traces.getIfUnique()).isNotNull().isNotSameAs(EcoTrace.NONE);
        // 표시는 MES 트랜잭션 안에서만(MANDATORY) — 동작은 PlmEcoLotTest
        assertThat(catchThrowable(() -> ChangeMarker.of(markers).mark(1L, null, null, java.time.OffsetDateTime.now())))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
    }
}
