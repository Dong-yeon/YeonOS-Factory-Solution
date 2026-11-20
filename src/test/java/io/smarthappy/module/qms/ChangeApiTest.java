package io.smarthappy.module.qms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import io.smarthappy.module.plm.link.EcoQualityView;
import io.smarthappy.module.plm.link.EcoQualityView.Summary;
import io.smarthappy.platform.tenant.TenantRunner;

/**
 * P3 QMS 2부 Q5 4M 변경 — 정상 흐름(작성 → ECO 상신 규칙 통과 → 상신 뒤 고정 → 반려되면 다시) + ECO 상신 규칙 ④(정책·변경 종류·표시 대상) +
 * 요약 계약 ③ + 이상 사용(A~K: A사로 B사, 직접 SQL, 입력, 권한) + FOR SHARE 직렬화(상신 중인 ECO 에 4M 저장은 기다렸다 409).
 */
class ChangeApiTest extends QmsTestBase {

    static final String C = "/api/qms/changes";
    static final String ECO = "/api/plm/ecos";

    @Autowired
    TenantRunner runner;

    @Autowired
    EcoQualityView qualityView;

    @Autowired
    JdbcTemplate jdbc;

    long approverId;
    RequestPostProcessor approver;

    @BeforeEach
    void changeSetUp() {
        accounts.grant(comp, userId, "QA", "qms-change", "SEARCH", "SAVE", "DELETE");
        accounts.grant(comp, userId, "QA", "plm-eco", "SEARCH", "SAVE", "APPROVE");
        approverId = accounts.newUser(comp, "mgr1", PW);
        accounts.grant(comp, approverId, "MGR", "plm-eco", "SEARCH", "SAVE", "APPROVE");
        approver = accounts.as(comp, approverId, "mgr1");
    }

    /** ECO 작성 중 — kind = DESIGN·PROCESS_4M·TEMP, proc = 공정 대상(FG1 × proc) 여부 */
    long eco(String c, long owner, String no, String kind, boolean proc) {
        long eco = accounts.query(c, """
                insert into plm_eco (comp_cd, eco_no, title, reason_cd, effective_date, owner_id, change_kind, mark_until, created_by, updated_by)
                values (?, ?, '드로잉 금형 교체', 'QUALITY', (now() at time zone 'Asia/Seoul')::date, ?, ?, case when ? = 'DESIGN' then null else (now() at time zone 'Asia/Seoul')::date + 7 end, 't', 't')
                returning eco_id""", Long.class, c, no, owner, kind, kind);
        accounts.exec(c, """
                insert into plm_eco_target (comp_cd, eco_id, seq, target_type, item_id, created_by, updated_by)
                values (?, ?, 1, 'ITEM', (select item_id from mst_item where item_cd = 'FG1'), 't', 't')""", c, eco);
        if (proc) {
            accounts.exec(c, """
                    insert into plm_eco_target (comp_cd, eco_id, seq, target_type, item_id, proc_id, created_by, updated_by)
                    values (?, ?, 2, 'PROC', (select item_id from mst_item where item_cd = 'FG1'), (select proc_id from mst_proc where proc_cd = 'DRAW'), 't', 't')""",
                    c, eco);
        }
        return eco;
    }

    int ecoVer(long eco) {
        return accounts.query(comp, "select row_ver from plm_eco where eco_id = ?", Integer.class, eco);
    }

    String ecoStatus(long eco) {
        return accounts.query(comp, "select status from plm_eco where eco_id = ?", String.class, eco);
    }

    ResultActions submit(long eco) throws Exception {
        return postJson(ECO + "/" + eco + "/submit", "{\"rowVer\":" + ecoVer(eco) + ",\"lines\":[{\"userId\":" + approverId + "}]}");
    }

    Integer chVer(long eco) {
        return accounts.query(comp, "select max(row_ver) from qms_change where eco_id = ?", Integer.class, eco);
    }

    ResultActions save(long eco, String m4, String extra) throws Exception {
        return putJson(C + "/" + eco, "{\"m4Types\":[" + m4 + "],\"beforeDesc\":\"구 금형 #3\",\"afterDesc\":\"신 금형 #4\""
                + (extra.isEmpty() ? "" : "," + extra) + ",\"rowVer\":" + chVer(eco) + "}");
    }

    static String row(String type, Object id) {
        return "{\"refType\":" + q(type) + ",\"refId\":" + id + ",\"impactDesc\":\"치수 재확인\"}";
    }

    long plan() throws Exception {
        return plan("FINAL", "FG1", null, null);
    }

    void policy(String c, String yn) {
        accounts.exec(c, "insert into sys_policy (comp_cd, policy_key, policy_value, created_by, updated_by) values (?, 'QMS_4M_REQUIRED', ?, 't', 't')",
                c, yn);
    }

    // ---------- 정상 흐름 ----------

    @Test
    void 작성_상신규칙_통과_상신뒤_고정_반려되면_다시_요약계약() throws Exception {
        long eco = eco(comp, userId, "ECO-4M-1", "PROCESS_4M", true);
        long p = plan();
        mvc.perform(get(C + "/" + eco).with(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.eco.ecoNo").value("ECO-4M-1"))
                .andExpect(jsonPath("$.data.eco.procCnt").value(1))
                .andExpect(jsonPath("$.data.change").doesNotExist())
                .andExpect(jsonPath("$.data.editable").value(true))
                .andExpect(jsonPath("$.data.required").value(true))
                .andExpect(jsonPath("$.data.issues[0].code").value("QMS_4M_MISSING"));
        // 후보: FG1 제품 특성 + DRAW 공정 특성 + FG1 검사계획
        mvc.perform(get(C + "/" + eco + "/choices").with(admin))
                .andExpect(jsonPath("$.data[*].refLabel").value(hasItems("FG1-1", "FG1-2", "DRAW-1")))
                .andExpect(jsonPath("$.data[?(@.refType == 'PLAN')].refId").value(hasItem((int) p)))
                .andExpect(jsonPath("$.data[?(@.refLabel == 'IQC-1')]").isEmpty());
        // 4M 없이 상신 → QMS 규칙이 막는다(ECO 칸 오류에 합쳐짐), ECO 는 작성 중 그대로
        submit(eco).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.rowKey == 'qms4m')].code").value(hasItem("QMS_4M_MISSING")));
        assertThat(ecoStatus(eco)).isEqualTo("DRAFT");
        // 저장: 유형은 정해진 순서로, 영향 행 2
        save(eco, "\"METHOD\",\"MACHINE\"", "\"impactDesc\":\"홀 지름 영향\",\"chars\":[" + row("CHAR", fgChar1) + "," + row("PLAN", p) + "]")
                .andExpect(status().isOk());
        assertThat(accounts.query(comp, "select array_to_string(m4_types, ',') || '/' || eco_no from qms_change where eco_id = ?", String.class, eco))
                .isEqualTo("MACHINE,METHOD/ECO-4M-1");
        mvc.perform(get(C + "/" + eco).with(admin)).andExpect(jsonPath("$.data.change.m4Types[0]").value("MACHINE"))
                .andExpect(jsonPath("$.data.chars.length()").value(2))
                .andExpect(jsonPath("$.data.chars[0].refLabel").value("FG1-1"))
                .andExpect(jsonPath("$.data.issues.length()").value(0));
        // 고치기: 영향 행을 안 보내면 그대로, 옛 rowVer 409
        save(eco, "\"MACHINE\"", "").andExpect(status().isOk());
        assertThat(accounts.query(comp, "select count(*) from qms_change_char where eco_id = ?", Integer.class, eco)).isEqualTo(2);
        putJson(C + "/" + eco, "{\"m4Types\":[\"MAN\"],\"beforeDesc\":\"a\",\"afterDesc\":\"b\",\"rowVer\":0}").andExpect(status().isConflict());
        mvc.perform(get(C).with(admin)).andExpect(jsonPath("$.data[0].ecoNo").value("ECO-4M-1"))
                .andExpect(jsonPath("$.data[0].ecoStatus").value("DRAFT"))
                .andExpect(jsonPath("$.data[0].ecoTitle").value("드로잉 금형 교체"))
                .andExpect(jsonPath("$.data[0].charCnt").value(2));
        mvc.perform(get(C).param("m4Type", "MAN").with(admin)).andExpect(jsonPath("$.data.length()").value(0));
        // 요약 계약 ③
        Summary q = runner.call(comp, () -> qualityView.summary(eco)).orElseThrow();
        assertThat(q.hasChange()).isTrue();
        assertThat(q.fourMTypes()).containsExactly("MACHINE");
        assertThat(q.charCount()).isEqualTo(2);
        assertThat(q.firstOk()).isNull();
        // PLM ECO 상세 응답의 quality 칸도 같은 값
        mvc.perform(get(ECO + "/" + eco).with(admin)).andExpect(jsonPath("$.data.quality.hasChange").value(true))
                .andExpect(jsonPath("$.data.quality.charCount").value(2));
        // ECO 번호로 열기
        mvc.perform(get(C + "/by-no").param("ecoNo", "ECO-4M-1").with(admin)).andExpect(jsonPath("$.data").value((int) eco));
        mvc.perform(get(C + "/by-no").param("ecoNo", "NOPE").with(admin)).andExpect(status().isNotFound());
        mvc.perform(get(C + "/by-no").with(admin)).andExpect(status().isBadRequest());
        // 상신 → 4M 고정
        long aprId = ((Number) com.jayway.jsonpath.JsonPath.read(submit(eco).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(),
                "$.data")).longValue();
        assertThat(ecoStatus(eco)).isEqualTo("PENDING");
        mvc.perform(get(C + "/" + eco).with(admin)).andExpect(jsonPath("$.data.editable").value(false));
        save(eco, "\"MAN\"", "").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("QMS_CHANGE_STATE"));
        postJson(C + "/" + eco + "/delete", "{\"rowVer\":" + chVer(eco) + "}").andExpect(jsonPath("$.code").value("QMS_CHANGE_STATE"));
        // 반려 → 작성 중 → 다시 고칠 수 있다
        mvc.perform(post("/api/platform/approvals/" + aprId + "/reject").with(approver).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"comment\":\"영향 평가 보완\"}")).andExpect(status().isOk());
        assertThat(ecoStatus(eco)).isEqualTo("DRAFT");
        save(eco, "\"MAN\",\"MACHINE\"", "\"impactDesc\":\"작업자 재교육 포함\"").andExpect(status().isOk());
        // 지우기(작성 중만) → 다시 없음
        postJson(C + "/" + eco + "/delete", "{\"rowVer\":" + chVer(eco) + "}").andExpect(status().isOk());
        assertThat(accounts.query(comp, "select count(*) from qms_change where eco_id = ?", Integer.class, eco)).isZero();
        postJson(C + "/" + eco + "/delete", "{\"rowVer\":0}").andExpect(status().isNotFound());
    }

    @Test
    void 상신규칙은_정책_변경종류_표시대상을_본다() throws Exception {
        // 품목 대상만 있는 4M → 4M 없음 + 표시 대상 없음 둘 다
        long itemOnly = eco(comp, userId, "ECO-4M-2", "TEMP", false);
        submit(itemOnly).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.rowKey == 'qms4m')].code").value(hasItems("QMS_4M_MISSING", "QMS_4M_NO_MARK_TARGET")));
        // 임시 변경은 복귀 조건 필수
        save(itemOnly, "\"MATERIAL\"", "").andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("revertCond"));
        save(itemOnly, "\"MATERIAL\"", "\"revertCond\":\"정규 자재 입고 시\"").andExpect(status().isOk());
        submit(itemOnly).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.rowKey == 'qms4m')].code").value("QMS_4M_NO_MARK_TARGET"));
        // 설계 변경은 규칙 밖
        long design = eco(comp, userId, "ECO-D-1", "DESIGN", false);
        mvc.perform(get(C + "/" + design).with(admin)).andExpect(jsonPath("$.data.required").value(false))
                .andExpect(jsonPath("$.data.issues.length()").value(0));
        // 품질 요약: 4M 이 필요한데 없으면 "없음" 으로 보이고, 필요 없고 없으면 빈 값(ECO 화면 영역을 띄우지 않음)
        mvc.perform(get(ECO + "/" + itemOnly).with(admin)).andExpect(jsonPath("$.data.quality.hasChange").value(true));
        assertThat(runner.call(comp, () -> qualityView.summary(design))).isEmpty();
        long noDetail = eco(comp, userId, "ECO-4M-10", "PROCESS_4M", true);
        assertThat(runner.call(comp, () -> qualityView.summary(noDetail)).orElseThrow().hasChange()).isFalse();
        submit(design).andExpect(status().isOk());
        // 정책 N → 4M 없어도 상신
        policy(comp, "N");
        long off = eco(comp, userId, "ECO-4M-3", "PROCESS_4M", false);
        mvc.perform(get(C + "/" + off).with(admin)).andExpect(jsonPath("$.data.required").value(false));
        submit(off).andExpect(status().isOk());
        assertThat(ecoStatus(off)).isEqualTo("PENDING");
    }

    @Test
    void 상신중인_ECO_행을_쥔_동안_4M_저장은_기다렸다가_409() throws Exception {
        long eco = eco(comp, userId, "ECO-4M-4", "PROCESS_4M", true);
        CountDownLatch locked = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            // PLM 상신 흉내: ECO 행 FOR UPDATE → 잠시 머묾 → PENDING 으로 바꾸고 커밋
            Future<?> holder = pool.submit(() -> runner.run(comp, () -> {
                jdbc.queryForList("select eco_id from plm_eco where eco_id = ? for update", eco);
                locked.countDown();
                try {
                    Thread.sleep(700);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                jdbc.update("update plm_eco set status = 'PENDING' where eco_id = ?", eco);
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            long t0 = System.nanoTime();
            save(eco, "\"MACHINE\"", "").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("QMS_CHANGE_STATE"));
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0)).as("FOR SHARE 가 상신 커밋을 기다린다").isGreaterThanOrEqualTo(400);
            holder.get(10, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertThat(accounts.query(comp, "select count(*) from qms_change where eco_id = ?", Integer.class, eco)).isZero();
    }

    // ---------- 이상 사용 ----------

    @Test
    void 입력_이상_유형_서술_영향행_조건조작() throws Exception {
        long eco = eco(comp, userId, "ECO-4M-5", "PROCESS_4M", true);
        putJson(C + "/" + eco, "{}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field").value(hasItems("m4Types", "beforeDesc", "afterDesc")));
        save(eco, "\"WEATHER\"", "").andExpect(jsonPath("$.errors[0].code").value("CODE"));
        save(eco, "\"MAN\",\"MAN\"", "").andExpect(jsonPath("$.errors[0].code").value("DUPLICATE"));
        save(eco, "\"MAN\"", "\"impactDesc\":\"" + "x".repeat(4001) + "\"").andExpect(jsonPath("$.errors[0].code").value("TOO_LONG"));
        // 영향 행: 없는 유형·id 없음·없는 특성·같은 특성 두 번·1,001자 → 행 키
        String other = accounts.newComp("QX");
        fixtures(other);
        long otherChar = charId(other, "FG1", null, "1");
        save(eco, "\"MAN\"", "\"chars\":[" + row("BOM", 1) + "," + row("CHAR", null) + "," + row("CHAR", otherChar) + "," + row("CHAR", fgChar1) + ","
                + row("CHAR", fgChar1) + ",{\"refType\":\"CHAR\",\"refId\":" + fgChar2 + ",\"impactDesc\":\"" + "y".repeat(1001) + "\"}]")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.length()").value(5))
                .andExpect(jsonPath("$.errors[?(@.rowKey == '2')].code").value(hasItem("NOT_FOUND")))
                .andExpect(jsonPath("$.errors[?(@.rowKey == '4')].code").value(hasItem("DUPLICATE")))
                .andExpect(jsonPath("$.errors[?(@.rowKey == '5')].code").value(hasItem("TOO_LONG")));
        save(eco, "\"MAN\"", "").andExpect(status().isOk());
        // 있는데 rowVer 없음, 없는 ECO, 조건 조작
        putJson(C + "/" + eco, "{\"m4Types\":[\"MAN\"],\"beforeDesc\":\"a\",\"afterDesc\":\"b\"}").andExpect(status().isBadRequest());
        putJson(C + "/999999999", "{\"m4Types\":[\"MAN\"],\"beforeDesc\":\"a\",\"afterDesc\":\"b\"}").andExpect(status().isNotFound());
        mvc.perform(get(C + "/999999999").with(admin)).andExpect(status().isNotFound());
        mvc.perform(get(C).param("m4Type", "X").with(admin)).andExpect(status().isBadRequest());
        mvc.perform(get(C).param("keyword", "x".repeat(101)).with(admin)).andExpect(status().isBadRequest());
        mvc.perform(get(C).param("keyword", "%").with(admin)).andExpect(jsonPath("$.data.length()").value(0));
        postJson(C + "/" + eco + "/delete", "{}").andExpect(status().isBadRequest());
    }

    @Test
    void 권한별_403() throws Exception {
        long eco = eco(comp, userId, "ECO-4M-6", "PROCESS_4M", true);
        long reader = accounts.newUser(comp, "reader1", PW);
        accounts.grant(comp, reader, "READER", "qms-change", "SEARCH");
        RequestPostProcessor rd = accounts.as(comp, reader, "reader1");
        long saver = accounts.newUser(comp, "saver1", PW);
        accounts.grant(comp, saver, "SAVER", "qms-change", "SEARCH", "SAVE");
        RequestPostProcessor sv = accounts.as(comp, saver, "saver1");
        String body = "{\"m4Types\":[\"MAN\"],\"beforeDesc\":\"a\",\"afterDesc\":\"b\"}";
        mvc.perform(get(C + "/" + eco).with(rd)).andExpect(status().isOk());
        mvc.perform(json(put(C + "/" + eco), body).with(rd)).andExpect(status().isForbidden());
        mvc.perform(json(put(C + "/" + eco), body).with(sv)).andExpect(status().isOk());
        mvc.perform(json(post(C + "/" + eco + "/delete"), "{\"rowVer\":0}").with(sv)).andExpect(status().isForbidden());
    }

    @Test
    void B사_사용자는_A사_ECO_의_4M_을_보지도_쓰지도_못한다() throws Exception {
        long eco = eco(comp, userId, "ECO-4M-7", "PROCESS_4M", true);
        save(eco, "\"MACHINE\"", "\"chars\":[" + row("CHAR", fgChar1) + "]").andExpect(status().isOk());
        String other = accounts.newComp("QM");
        long bUser = accounts.newUser(other, "admin1", PW);
        accounts.grant(other, bUser, "QA", "qms-change", "SEARCH", "SAVE", "DELETE");
        RequestPostProcessor b = accounts.as(other, bUser, "admin1");
        mvc.perform(get(C).with(b)).andExpect(jsonPath("$.data.length()").value(0));
        mvc.perform(get(C + "/" + eco).with(b)).andExpect(status().isNotFound());
        mvc.perform(get(C + "/" + eco + "/choices").with(b)).andExpect(status().isNotFound());
        mvc.perform(json(put(C + "/" + eco), "{\"m4Types\":[\"MAN\"],\"beforeDesc\":\"a\",\"afterDesc\":\"b\",\"rowVer\":0}").with(b))
                .andExpect(status().isNotFound());
        mvc.perform(json(post(C + "/" + eco + "/delete"), "{\"rowVer\":0}").with(b)).andExpect(status().isNotFound());
        assertThat(runner.call(other, () -> qualityView.summary(eco))).isEmpty();
        mvc.perform(get(C + "/by-no").param("ecoNo", "ECO-4M-7").with(b)).andExpect(status().isNotFound());
        // B사 ECO 의 4M 에 A사 특성 id → 칸 오류(존재를 흘리지 않음)
        fixtures(other);
        long bEco = eco(other, bUser, "ECO-B-1", "PROCESS_4M", true);
        mvc.perform(json(put(C + "/" + bEco), "{\"m4Types\":[\"MAN\"],\"beforeDesc\":\"a\",\"afterDesc\":\"b\",\"chars\":[" + row("CHAR", fgChar1) + "]}")
                .with(b)).andExpect(jsonPath("$.errors[0].code").value("NOT_FOUND"));
        // 직접 SQL: A사 ECO 에 B사 4M, B사 4M 에 A사 특성, A사 4M 행에 B사 영향 행
        constraint(other, "fk_qms_change_eco_comp", () -> accounts.exec(other,
                "insert into qms_change (eco_id, comp_cd, eco_no, m4_types, before_desc, after_desc, created_by, updated_by) "
                        + "values (?, ?, '-', array['MAN']::varchar(10)[], 'a', 'b', 't', 't')", eco, other));
        accounts.exec(other, "insert into qms_change (eco_id, comp_cd, eco_no, m4_types, before_desc, after_desc, created_by, updated_by) "
                + "values (?, ?, '-', array['MAN']::varchar(10)[], 'a', 'b', 't', 't')", bEco, other);
        assertThat(accounts.query(other, "select eco_no from qms_change where eco_id = ?", String.class, bEco)).as("번호는 트리거가 채운다").isEqualTo("ECO-B-1");
        constraint(other, "fk_qms_change_char_comp_ref", () -> accounts.exec(other,
                "insert into qms_change_char (comp_cd, eco_id, line_no, char_id, created_by) values (?, ?, 1, ?, 't')", other, bEco, fgChar1));
        constraint(other, "fk_qms_change_char_change_comp", () -> accounts.exec(other,
                "insert into qms_change_char (comp_cd, eco_id, line_no, char_id, created_by) values (?, ?, 1, ?, 't')", other, eco,
                charId(other, "FG1", null, "1")));
        assertThat(accounts.exec(other, "update qms_change set before_desc = 'x' where eco_id = ?", eco)).isZero();
    }

    @Test
    void 직접SQL_작성중_아니면_불변_유형_중복_영향행_수정금지() throws Exception {
        long eco = eco(comp, userId, "ECO-4M-8", "PROCESS_4M", true);
        save(eco, "\"MACHINE\"", "\"chars\":[" + row("CHAR", fgChar1) + "]").andExpect(status().isOk());
        constraint(comp, "ck_qms_change_m4_distinct", () -> accounts.exec(comp,
                "update qms_change set m4_types = array['MAN','MAN']::varchar(10)[] where eco_id = ?", eco));
        constraint(comp, "ck_qms_change_m4", () -> accounts.exec(comp, "update qms_change set m4_types = array['RAIN']::varchar(10)[] where eco_id = ?", eco));
        constraint(comp, "ck_qms_change_m4", () -> accounts.exec(comp, "update qms_change set m4_types = '{}'::varchar(10)[] where eco_id = ?", eco));
        constraint(comp, "ck_qms_change_immutable", () -> accounts.exec(comp, "update qms_change set eco_no = 'X' where eco_id = ?", eco));
        constraint(comp, "ck_qms_change_desc", () -> accounts.exec(comp, "update qms_change set after_desc = ' ' where eco_id = ?", eco));
        constraint(comp, "ck_qms_change_char_no_update", () -> accounts.exec(comp,
                "update qms_change_char set impact_desc = 'x' where eco_id = ?", eco));
        constraint(comp, "ck_qms_change_char_one", () -> accounts.exec(comp,
                "insert into qms_change_char (comp_cd, eco_id, line_no, created_by) values (?, ?, 9, 't')", comp, eco));
        // 상신 뒤: 4M·영향 행 변경·삭제 모두 거부
        submit(eco).andExpect(status().isOk());
        constraint(comp, "ck_qms_change_eco_draft", () -> accounts.exec(comp, "update qms_change set before_desc = '몰래' where eco_id = ?", eco));
        constraint(comp, "ck_qms_change_eco_draft", () -> accounts.exec(comp, "delete from qms_change_char where eco_id = ?", eco));
        constraint(comp, "ck_qms_change_eco_draft", () -> accounts.exec(comp,
                "insert into qms_change_char (comp_cd, eco_id, line_no, char_id, created_by) values (?, ?, 5, ?, 't')", comp, eco, fgChar2));
        long fresh = eco(comp, userId, "ECO-4M-9", "PROCESS_4M", true);
        accounts.exec(comp, "update plm_eco set status = 'PENDING' where eco_id = ?", fresh);
        constraint(comp, "ck_qms_change_eco_draft", () -> accounts.exec(comp,
                "insert into qms_change (eco_id, comp_cd, eco_no, m4_types, before_desc, after_desc, created_by, updated_by) "
                        + "values (?, ?, '-', array['MAN']::varchar(10)[], 'a', 'b', 't', 't')", fresh, comp));
    }
}
