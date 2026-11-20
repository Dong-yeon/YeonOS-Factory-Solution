package io.smarthappy.module.qms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.support.TestAccounts;

/**
 * P3 QMS Q2b GRR 연구 — 정상 흐름(작성·미리 계산·확정 스냅샷·공차 스냅샷·계수형·취소) + 이상 사용(A~K: A사로 B사, 직접 SQL 불변·회사·
 * 모양·종류, 원자료 이상 입력, 권한, 멱등, 동시 편집, 정책 판정 구간). 계산 값은 MsaCalcTest 예제 1(손 계산)과 같다.
 */
class MsaApiTest extends QmsTestBase {

    static final String M = "/api/qms/msa";

    @BeforeEach
    void msaSetUp() {
        accounts.grant(comp, userId, "QA", "qms-msa", "SEARCH", "SAVE", "APPROVE", "EXCEL");
        gauge(comp, "GG1", "GAUGE");
    }

    void gauge(String c, String cd, String type) {
        accounts.exec(c, """
                insert into mst_phys_asset (comp_cd, asset_cd, asset_type, asset_nm, calib_cycle_days, created_by, updated_by)
                values (?, ?, ?, ?, 365, 't', 't')""", c, cd, type, cd + " 계측기");
    }

    static String cells(double[][][] d) {
        List<String> out = new ArrayList<>();
        for (int a = 0; a < d.length; a++) {
            for (int p = 0; p < d[a].length; p++) {
                for (int t = 0; t < d[a][p].length; t++) {
                    out.add(cell(a + 1, p + 1, t + 1, BigDecimal.valueOf(d[a][p][t]).toPlainString()));
                }
            }
        }
        return String.join(",", out);
    }

    static String cell(int a, int p, int t, String v) {
        return "{\"a\":" + a + ",\"p\":" + p + ",\"t\":" + t + ",\"v\":" + q(v) + "}";
    }

    static String form(String method, int k, int n, int r, String basis, Long charId, String extra, String cells) {
        return "{\"assetCd\":\"GG1\",\"charId\":" + charId + ",\"method\":" + q(method) + ",\"appraiserCnt\":" + k + ",\"partCnt\":" + n
                + ",\"trialCnt\":" + r + ",\"basis\":" + q(basis) + (extra == null ? "" : "," + extra) + ",\"cells\":[" + cells + "]}";
    }

    long create(String body) throws Exception {
        return id(postJson(M, body));
    }

    int ver(long id) {
        return accounts.query(comp, "select row_ver from qms_msa_study where study_id = ?", Integer.class, id);
    }

    ResultActions act(long id, String a) throws Exception {
        return postJson(M + "/" + id + "/" + a, "{\"rowVer\":" + ver(id) + "}");
    }

    // ---------- 정상 흐름 ----------

    @Test
    void 평균범위법_작성_미리계산_확정_스냅샷_뒤_불변() throws Exception {
        long id = create(form("AVG_RANGE", 2, 5, 2, "PROCESS", null, "\"appraisers\":[\"김\",\"이\"]", cells(MsaCalcTest.EX1)));
        String d = mvc.perform(get(M + "/" + id).with(admin)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat((String) JsonPath.read(d, "$.data.study.studyNo")).matches("^MSA[0-9]{4}-001$");
        assertThat((String) JsonPath.read(d, "$.data.study.status")).isEqualTo("DRAFT");
        assertThat((List<String>) JsonPath.read(d, "$.data.study.appraisers")).containsExactly("김", "이");
        assertThat((Integer) JsonPath.read(d, "$.data.cells.length()")).isEqualTo(20);
        assertThat(((Number) JsonPath.read(d, "$.data.preview.pctGrr")).doubleValue()).isEqualTo(11.60);
        assertThat((Integer) JsonPath.read(d, "$.data.preview.ndc")).isEqualTo(12);
        assertThat((String) JsonPath.read(d, "$.data.preview.verdict")).isEqualTo("CONDITIONAL");
        assertThat((Object) JsonPath.read(d, "$.data.result")).isNull();
        assertThat((Integer) JsonPath.read(d, "$.data.okPct")).isEqualTo(10);

        act(id, "complete").andExpect(status().isOk());
        mvc.perform(get(M + "/" + id).with(admin)).andExpect(jsonPath("$.data.study.status").value("DONE"))
                .andExpect(jsonPath("$.data.study.grrPct").value(11.60)).andExpect(jsonPath("$.data.study.verdict").value("CONDITIONAL"))
                .andExpect(jsonPath("$.data.result.ev").value(0.08862)).andExpect(jsonPath("$.data.result.av").value(0.031853))
                .andExpect(jsonPath("$.data.result.pv").value(0.806)).andExpect(jsonPath("$.data.preview").doesNotExist());
        mvc.perform(get(M).param("status", "DONE").with(admin)).andExpect(jsonPath("$.list[0].grrPct").value(11.60))
                .andExpect(jsonPath("$.list[0].assetCd").value("GG1"));
        // 확정 뒤: 저장·확정·취소 409
        putJson(M + "/" + id, form("AVG_RANGE", 2, 5, 2, "PROCESS", null, "\"rowVer\":" + ver(id), cells(MsaCalcTest.EX1)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("QMS_MSA_NOT_DRAFT"));
        act(id, "complete").andExpect(status().isConflict());
        act(id, "cancel").andExpect(status().isConflict());
        mvc.perform(json(post(M + "/export"), "{}").with(admin)).andExpect(status().isOk());
    }

    @Test
    void ANOVA_작성_미리계산_확정_스냅샷에_분산분석표_원자료_종류_트리거() throws Exception {
        long id = create(form("ANOVA", 2, 5, 2, "PROCESS", null, null, cells(MsaCalcTest.EX1)));
        mvc.perform(get(M + "/" + id).with(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.study.method").value("ANOVA"))
                .andExpect(jsonPath("$.data.preview.pctGrr").value(10.56)).andExpect(jsonPath("$.data.preview.ndc").value(13))
                .andExpect(jsonPath("$.data.preview.pooled").value(true)).andExpect(jsonPath("$.data.preview.anova.length()").value(5))
                .andExpect(jsonPath("$.data.preview.anova[2].source").value("INTERACTION"));
        act(id, "complete").andExpect(status().isOk());
        mvc.perform(get(M + "/" + id).with(admin)).andExpect(jsonPath("$.data.study.status").value("DONE"))
                .andExpect(jsonPath("$.data.study.grrPct").value(10.56)).andExpect(jsonPath("$.data.study.ndc").value(13))
                .andExpect(jsonPath("$.data.study.verdict").value("CONDITIONAL"))
                .andExpect(jsonPath("$.data.result.ev").value(0.076532)).andExpect(jsonPath("$.data.result.iv").value(0.0))
                .andExpect(jsonPath("$.data.result.anova[3].df").value(10));
        mvc.perform(get(M).param("method", "ANOVA").with(admin)).andExpect(jsonPath("$.list.length()").value(1));
        // 원자료 종류 트리거: ANOVA 연구에 계수형 값 → 거부
        long draft = create(form("ANOVA", 2, 5, 2, "PROCESS", null, null, cell(1, 1, 1, "1")));
        constraint(comp, "ck_qms_msa_data_kind", () -> accounts.exec(comp, """
                insert into qms_msa_data (comp_cd, study_id, appraiser_no, part_no, trial_no, attr_value, created_by)
                values (?, ?, 1, 2, 1, 'OK', 't')""", comp, draft));
        // 칸이 덜 찬 채 직접 SQL 확정 → 트리거가 다시 센다
        constraint(comp, "ck_qms_msa_study_data_complete", () -> accounts.exec(comp,
                "update qms_msa_study set status = 'DONE', result_json = '{}', grr_pct = 1, ndc = 1, verdict = 'ACCEPT', done_at = now(), "
                        + "done_by = 't' where study_id = ?", draft));
    }

    @Test
    void 공차_기준은_확정_때_특성_공차를_스냅샷하고_정책_구간으로_판정한다() throws Exception {
        // FG1-1 공차 = 10.05 − 9.95 = 0.1 → %GRR = 6000 × GRR(.0941705) = 565.02 → 불합격
        long id = create(form("AVG_RANGE", 2, 5, 2, "TOLERANCE", fgChar1, "\"processSigma\":\"0.5\"", cells(MsaCalcTest.EX1)));
        assertThat(accounts.query(comp, "select process_sigma is null from qms_msa_study where study_id = ?", Boolean.class, id)).isTrue();
        act(id, "complete").andExpect(status().isOk());
        assertThat(accounts.query(comp, "select tolerance || ':' || grr_pct || ':' || verdict from qms_msa_study where study_id = ?",
                String.class, id)).isEqualTo("0.100000:565.02:REJECT");
        accounts.exec(comp, "update qms_char set usl = 10.50, updated_by = 't' where char_id = ?", fgChar1);
        mvc.perform(get(M + "/" + id).with(admin)).andExpect(jsonPath("$.data.result.tolerance").value(0.1))
                .andExpect(jsonPath("$.data.study.tolerance").value(0.1));
        // 규격 없는 특성(계수 특성) + 공차 기준 → 미리 계산 오류, 확정은 특성 칸 오류
        long noTol = create(form("AVG_RANGE", 2, 5, 2, "TOLERANCE", fgChar2, null, cells(MsaCalcTest.EX1)));
        mvc.perform(get(M + "/" + noTol).with(admin)).andExpect(jsonPath("$.data.previewError").value("MSA_NO_TOLERANCE"));
        act(noTol, "complete").andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("charId"))
                .andExpect(jsonPath("$.errors[0].code").value("MSA_NO_TOLERANCE"));
        // 회사 정책 합격 구간 15 → 예제 1(11.60)은 합격
        accounts.exec(comp, """
                insert into sys_policy (comp_cd, policy_key, policy_value, created_by, updated_by)
                values (?, 'QMS_GRR_OK_PCT', '15', 't', 't')""", comp);
        long p = create(form("AVG_RANGE", 2, 5, 2, "PROCESS", null, null, cells(MsaCalcTest.EX1)));
        act(p, "complete").andExpect(status().isOk());
        assertThat(accounts.query(comp, "select verdict from qms_msa_study where study_id = ?", String.class, p)).isEqualTo("ACCEPT");
    }

    @Test
    void 덜_찬_원자료는_확정_불가_채우면_확정_취소는_작성중만() throws Exception {
        String half = cell(1, 1, 1, "1.0") + "," + cell(1, 1, 2, "1.1");
        long id = create(form("AVG_RANGE", 2, 5, 2, "PROCESS", null, null, half));
        mvc.perform(get(M + "/" + id).with(admin)).andExpect(jsonPath("$.data.previewError").value("MSA_INCOMPLETE"))
                .andExpect(jsonPath("$.data.preview").doesNotExist());
        act(id, "complete").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("QMS_MSA_INCOMPLETE"));
        // 모양 밖 칸은 비어 있어도 거부(행 키로 표시)
        putJson(M + "/" + id, form("AVG_RANGE", 2, 5, 2, "PROCESS", null, "\"rowVer\":" + ver(id),
                cells(MsaCalcTest.EX1) + "," + cell(1, 5, 3, " ")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].rowKey").value("1-5-3"));
        putJson(M + "/" + id, form("AVG_RANGE", 2, 5, 2, "PROCESS", null, "\"rowVer\":" + ver(id), cells(MsaCalcTest.EX1)))
                .andExpect(status().isOk());
        assertThat(accounts.query(comp, "select count(*) from qms_msa_data where study_id = ?", Integer.class, id)).isEqualTo(20);
        // 열려 있던 옛 rowVer 로 저장 → 409
        putJson(M + "/" + id, form("AVG_RANGE", 2, 5, 2, "PROCESS", null, "\"rowVer\":0", "")).andExpect(status().isConflict());
        act(id, "cancel").andExpect(status().isOk());
        mvc.perform(get(M + "/" + id).with(admin)).andExpect(jsonPath("$.data.study.status").value("CANCELLED"))
                .andExpect(jsonPath("$.data.previewError").doesNotExist());
        act(id, "complete").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("QMS_MSA_NOT_DRAFT"));
    }

    @Test
    void 계수형_일치율_기준값_선택() throws Exception {
        String[][][] d = {
                {{"OK", "OK"}, {"NG", "NG"}, {"OK", "NG"}, {"OK", "OK"}},
                {{"OK", "OK"}, {"NG", "NG"}, {"OK", "OK"}, {"NG", "NG"}}};
        List<String> cs = new ArrayList<>();
        for (int a = 0; a < 2; a++) {
            for (int p = 0; p < 4; p++) {
                for (int t = 0; t < 2; t++) {
                    cs.add(cell(a + 1, p + 1, t + 1, d[a][p][t].toLowerCase()));
                }
            }
        }
        String refs = cell(0, 1, 1, "OK") + "," + cell(0, 2, 1, "NG") + "," + cell(0, 3, 1, "OK") + "," + cell(0, 4, 1, "NG");
        long id = create(form("ATTRIBUTE", 2, 4, 2, "PROCESS", null, null, String.join(",", cs) + "," + refs));
        assertThat(accounts.query(comp, "select basis is null from qms_msa_study where study_id = ?", Boolean.class, id)).isTrue();
        mvc.perform(get(M + "/" + id).with(admin)).andExpect(jsonPath("$.data.preview.withinPct[0]").value(75.0))
                .andExpect(jsonPath("$.data.preview.betweenPct").value(50.0)).andExpect(jsonPath("$.data.preview.vsRefPct[1]").value(100.0));
        act(id, "complete").andExpect(status().isOk());
        mvc.perform(get(M + "/" + id).with(admin)).andExpect(jsonPath("$.data.result.allVsRefPct").value(50.0))
                .andExpect(jsonPath("$.data.study.verdict").doesNotExist());
        // 기준값 일부만 → 덜 참
        long part = create(form("ATTRIBUTE", 2, 4, 2, null, null, null, String.join(",", cs) + "," + cell(0, 1, 1, "OK")));
        act(part, "complete").andExpect(jsonPath("$.code").value("QMS_MSA_INCOMPLETE"));
        // 계수형은 부품 50개까지
        postJson(M, form("ATTRIBUTE", 2, 50, 2, null, null, null, "")).andExpect(status().isOk());
        postJson(M, form("ATTRIBUTE", 2, 51, 2, null, null, null, "")).andExpect(jsonPath("$.errors[0].field").value("partCnt"));
    }

    // ---------- 이상 사용: 입력 ----------

    @Test
    void 머리와_원자료_이상_입력() throws Exception {
        postJson(M, "{}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[*].field").value(hasItem("assetCd")));
        postJson(M, form("AVG_RANGE", 4, 5, 2, "PROCESS", null, null, "")).andExpect(jsonPath("$.errors[0].field").value("appraiserCnt"));
        postJson(M, form("AVG_RANGE", 2, 11, 2, "PROCESS", null, null, "")).andExpect(jsonPath("$.errors[0].field").value("partCnt"));
        postJson(M, form("AVG_RANGE", 2, 5, 1, "PROCESS", null, null, "")).andExpect(jsonPath("$.errors[0].field").value("trialCnt"));
        postJson(M, form("AVG_RANGE", 2, 5, 2, "NOPE", null, null, "")).andExpect(jsonPath("$.errors[0].field").value("basis"));
        postJson(M, form("NOPE", 2, 5, 2, "PROCESS", null, null, "")).andExpect(jsonPath("$.errors[0].field").value("method"));
        postJson(M, form("ANOVA", 2, 11, 2, "PROCESS", null, null, "")).andExpect(jsonPath("$.errors[0].field").value("partCnt")); // ANOVA 도 부품 10 까지
        postJson(M, form("AVG_RANGE", 2, 5, 2, "PROCESS", null, "\"processSigma\":\"-1\"", "")).andExpect(jsonPath("$.errors[0].field").value("processSigma"));
        postJson(M, form("AVG_RANGE", 2, 5, 2, "PROCESS", null, "\"appraisers\":[\"김\"]", "")).andExpect(jsonPath("$.errors[0].code").value("MSA_NAMES"));
        postJson(M, form("AVG_RANGE", 2, 5, 2, "PROCESS", null, "\"appraisers\":[\"" + "x".repeat(51) + "\",\"y\"]", ""))
                .andExpect(jsonPath("$.errors[0].code").value("TOO_LONG"));
        postJson(M, form("AVG_RANGE", 2, 5, 2, "PROCESS", null, "\"studyDate\":" + q(today.plusDays(1)), ""))
                .andExpect(jsonPath("$.errors[0].code").value("FUTURE"));
        postJson(M, form("AVG_RANGE", 2, 5, 2, "PROCESS", 999_999_999L, null, "")).andExpect(jsonPath("$.errors[0].field").value("charId"));
        postJson(M, form("AVG_RANGE", 2, 5, 2, "PROCESS", null, null, "").replace("\"GG1\"", "\"NOPE\""))
                .andExpect(jsonPath("$.errors[0].code").value("NO_SUCH_CODE"));
        gauge(comp, "MD1", "MOLD");
        postJson(M, form("AVG_RANGE", 2, 5, 2, "PROCESS", null, null, "").replace("\"GG1\"", "\"MD1\""))
                .andExpect(jsonPath("$.errors[0].code").value("NO_SUCH_CODE"));
        accounts.exec(comp, "update qms_char set use_yn = 'N', updated_by = 't' where char_id = ?", drawChar);
        postJson(M, form("AVG_RANGE", 2, 5, 2, "PROCESS", drawChar, null, "")).andExpect(jsonPath("$.errors[0].code").value("INACTIVE"));
        // 원자료: 모양 밖·같은 칸 두 번·숫자 아님·자릿수·계량에 기준값 행·계수에 OK/NG 아님 → 칸(행 키)마다
        postJson(M, form("AVG_RANGE", 2, 5, 2, "PROCESS", null, null, cell(3, 1, 1, "1") + "," + cell(1, 6, 1, "1") + ","
                + cell(1, 1, 3, "1") + "," + cell(1, 1, 1, "1") + "," + cell(1, 1, 1, "2") + "," + cell(1, 2, 1, "abc") + ","
                + cell(1, 3, 1, "1.1234567") + "," + cell(0, 1, 1, "1")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.length()").value(7))
                .andExpect(jsonPath("$.errors[?(@.rowKey == '1-1-1')].code").value(hasItem("DUPLICATE")))
                .andExpect(jsonPath("$.errors[?(@.rowKey == '1-2-1')].code").value(hasItem("MSA_NUMBER")))
                .andExpect(jsonPath("$.errors[?(@.rowKey == '0-1-1')].code").value(hasItem("MSA_CELL")));
        postJson(M, form("ATTRIBUTE", 2, 4, 2, null, null, null, cell(1, 1, 1, "PASS") + "," + cell(0, 1, 2, "OK")))
                .andExpect(jsonPath("$.errors.length()").value(2));
        postJson(M, form("AVG_RANGE", 2, 5, 2, "PROCESS", null, null, "null")).andExpect(status().isBadRequest());
        postJson(M, form("AVG_RANGE", 2, 5, 2, "PROCESS", null, null, "{\"a\":1,\"p\":1,\"v\":\"1\"}")).andExpect(status().isBadRequest());
        // 목록 조건 조작
        mvc.perform(get(M).param("status", "X").with(admin)).andExpect(status().isBadRequest());
        mvc.perform(get(M).param("method", "X").with(admin)).andExpect(status().isBadRequest());
        mvc.perform(get(M + "/999999999").with(admin)).andExpect(status().isNotFound());
    }

    @Test
    void 권한별_403_멱등키_409() throws Exception {
        String body = form("AVG_RANGE", 2, 5, 2, "PROCESS", null, null, cells(MsaCalcTest.EX1));
        mvc.perform(json(post(M), body).header("X-Idempotency-Key", "qms-msa-0001").with(admin)).andExpect(status().isOk());
        mvc.perform(json(post(M), body).header("X-Idempotency-Key", "qms-msa-0001").with(admin)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_REQUEST"));
        long id = create(body);
        long saver = accounts.newUser(comp, "saver1", PW);
        accounts.grant(comp, saver, "SAVER", "qms-msa", "SEARCH", "SAVE");
        RequestPostProcessor sv = accounts.as(comp, saver, "saver1");
        long reader = accounts.newUser(comp, "reader1", PW);
        accounts.grant(comp, reader, "READER", "qms-msa", "SEARCH");
        RequestPostProcessor rd = accounts.as(comp, reader, "reader1");
        mvc.perform(get(M + "/" + id).with(rd)).andExpect(status().isOk());
        mvc.perform(get(M + "/gauges").with(rd)).andExpect(jsonPath("$.data[0].cd").value("GG1"));
        mvc.perform(json(post(M), body).with(rd)).andExpect(status().isForbidden());
        mvc.perform(json(put(M + "/" + id), body).with(rd)).andExpect(status().isForbidden());
        mvc.perform(json(post(M + "/" + id + "/cancel"), "{\"rowVer\":0}").with(rd)).andExpect(status().isForbidden());
        mvc.perform(json(post(M + "/export"), "{}").with(rd)).andExpect(status().isForbidden());
        mvc.perform(json(post(M + "/" + id + "/complete"), "{\"rowVer\":" + ver(id) + "}").with(sv)).andExpect(status().isForbidden());
        mvc.perform(json(post(M + "/" + id + "/cancel"), "{\"rowVer\":" + ver(id) + "}").with(sv)).andExpect(status().isOk());
    }

    // ---------- 이상 사용: A사로 B사 ----------

    @Test
    void B사_사용자는_A사_연구를_보지도_쓰지도_못한다() throws Exception {
        long id = create(form("AVG_RANGE", 2, 5, 2, "PROCESS", fgChar1, null, cells(MsaCalcTest.EX1)));
        long aAsset = accounts.query(comp, "select asset_id from mst_phys_asset where asset_cd = 'GG1'", Long.class);
        String other = accounts.newComp("QM");
        long bUser = accounts.newUser(other, "admin1", PW);
        accounts.grant(other, bUser, "QA", "qms-msa", "SEARCH", "SAVE", "APPROVE", "EXCEL");
        RequestPostProcessor b = accounts.as(other, bUser, "admin1");
        mvc.perform(get(M).with(b)).andExpect(jsonPath("$.total").value(0));
        mvc.perform(get(M + "/gauges").with(b)).andExpect(jsonPath("$.data.length()").value(0));
        mvc.perform(get(M + "/chars").with(b)).andExpect(jsonPath("$.data.length()").value(0));
        mvc.perform(get(M + "/" + id).with(b)).andExpect(status().isNotFound());
        mvc.perform(json(put(M + "/" + id), form("AVG_RANGE", 2, 5, 2, "PROCESS", null, "\"rowVer\":0", "")).with(b))
                .andExpect(status().isNotFound());
        mvc.perform(json(post(M + "/" + id + "/complete"), "{\"rowVer\":0}").with(b)).andExpect(status().isNotFound());
        mvc.perform(json(post(M + "/" + id + "/cancel"), "{\"rowVer\":0}").with(b)).andExpect(status().isNotFound());
        // B사에서 A사 계측기 코드·특성 id → 칸 오류
        mvc.perform(json(post(M), form("AVG_RANGE", 2, 5, 2, "PROCESS", fgChar1, null, "")).with(b)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field").value(org.hamcrest.Matchers.hasItems("assetCd", "charId")));
        // 직접 SQL: B사 범위에서 A사 계측기·특성으로 연구, A사 연구에 원자료, A사 연구 취소
        gauge(other, "GG1", "GAUGE");
        long bAsset = accounts.query(other, "select asset_id from mst_phys_asset where asset_cd = 'GG1'", Long.class);
        constraint(other, "fk_qms_msa_study_asset_comp", () -> accounts.exec(other, """
                insert into qms_msa_study (comp_cd, study_no, asset_id, method, appraiser_cnt, part_cnt, trial_cnt, basis, study_date,
                                           created_by, updated_by)
                values (?, 'X1', ?, 'AVG_RANGE', 2, 5, 2, 'PROCESS', (now() at time zone 'Asia/Seoul')::date, 't', 't')""", other, aAsset));
        constraint(other, "fk_qms_msa_study_char_comp", () -> accounts.exec(other, """
                insert into qms_msa_study (comp_cd, study_no, asset_id, char_id, method, appraiser_cnt, part_cnt, trial_cnt, basis, study_date,
                                           created_by, updated_by)
                values (?, 'X2', ?, ?, 'AVG_RANGE', 2, 5, 2, 'PROCESS', (now() at time zone 'Asia/Seoul')::date, 't', 't')""", other, bAsset, fgChar1));
        constraint(other, "fk_qms_msa_data_study_comp", () -> accounts.exec(other, """
                insert into qms_msa_data (comp_cd, study_id, appraiser_no, part_no, trial_no, value, created_by)
                values (?, ?, 1, 1, 1, 1.0, 't')""", other, id));
        assertThat(accounts.exec(other, "update qms_msa_study set status = 'CANCELLED' where study_id = ?", id)).isZero();
        assertThat(accounts.exec(other, "delete from qms_msa_data where study_id = ?", id)).isZero();
        assertThat(accounts.query(comp, "select count(*) from qms_msa_data where study_id = ?", Integer.class, id)).isEqualTo(20);
    }

    // ---------- 이상 사용: 직접 SQL ----------

    @Test
    void 직접SQL_확정본_불변_원자료_모양_종류_상태_규칙() throws Exception {
        long done = create(form("AVG_RANGE", 2, 5, 2, "PROCESS", null, null, cells(MsaCalcTest.EX1)));
        act(done, "complete").andExpect(status().isOk());
        constraint(comp, "ck_qms_msa_study_immutable", () -> accounts.exec(comp, "update qms_msa_study set remark = 'x' where study_id = ?", done));
        constraint(comp, "ck_qms_msa_study_immutable", () -> accounts.exec(comp,
                "update qms_msa_study set status = 'DRAFT' where study_id = ?", done));
        sqlState("42501", () -> accounts.exec(comp, "delete from qms_msa_study where study_id = ?", done));
        constraint(comp, "ck_qms_msa_data_draft", () -> accounts.exec(comp, "update qms_msa_data set value = 9 where study_id = ?", done));
        constraint(comp, "ck_qms_msa_data_draft", () -> accounts.exec(comp, "delete from qms_msa_data where study_id = ?", done));
        constraint(comp, "ck_qms_msa_data_draft", () -> accounts.exec(comp, """
                insert into qms_msa_data (comp_cd, study_id, appraiser_no, part_no, trial_no, value, created_by)
                values (?, ?, 2, 5, 3, 1.0, 't')""", comp, done));

        long draft = create(form("AVG_RANGE", 2, 5, 2, "PROCESS", null, null, cell(1, 1, 1, "1")));
        // SQL 로 확정: 원자료 덜 참 → 트리거, 다 찼는데 결과 없음 → CHECK, 모양 밖 행 → 트리거
        String setDone = "update qms_msa_study set status = 'DONE', result_json = '{}', grr_pct = 1, ndc = 5, verdict = 'ACCEPT', "
                + "done_at = now(), done_by = 't' where study_id = ?";
        constraint(comp, "ck_qms_msa_study_data_complete", () -> accounts.exec(comp, setDone, draft));
        accounts.exec(comp, "delete from qms_msa_data where study_id = ?", draft);
        for (int a = 1; a <= 2; a++) {
            for (int p = 1; p <= 5; p++) {
                for (int t = 1; t <= 2; t++) {
                    accounts.exec(comp, "insert into qms_msa_data (comp_cd, study_id, appraiser_no, part_no, trial_no, value, created_by) "
                            + "values (?, ?, ?, ?, ?, ?, 't')", comp, draft, a, p, t, a + p * 0.5 + t * 0.01);
                }
            }
        }
        constraint(comp, "ck_qms_msa_study_done", () -> accounts.exec(comp,
                "update qms_msa_study set status = 'DONE' where study_id = ?", draft));
        accounts.exec(comp, "insert into qms_msa_data (comp_cd, study_id, appraiser_no, part_no, trial_no, value, created_by) "
                + "values (?, ?, 3, 1, 1, 1.0, 't')", comp, draft);
        constraint(comp, "ck_qms_msa_study_data_complete", () -> accounts.exec(comp, setDone, draft));
        // 종류·값 규칙
        constraint(comp, "ck_qms_msa_data_kind", () -> accounts.exec(comp, """
                insert into qms_msa_data (comp_cd, study_id, appraiser_no, part_no, trial_no, attr_value, created_by)
                values (?, ?, 1, 2, 3, 'OK', 't')""", comp, draft));
        constraint(comp, "ck_qms_msa_data_value", () -> accounts.exec(comp, """
                insert into qms_msa_data (comp_cd, study_id, appraiser_no, part_no, trial_no, value, attr_value, created_by)
                values (?, ?, 1, 3, 3, 1, 'OK', 't')""", comp, draft));
        constraint(comp, "ck_qms_msa_data_ref", () -> accounts.exec(comp, """
                insert into qms_msa_data (comp_cd, study_id, appraiser_no, part_no, trial_no, value, created_by)
                values (?, ?, 0, 1, 2, 1.0, 't')""", comp, draft));
        constraint(comp, "uq_qms_msa_data_cell", () -> accounts.exec(comp, """
                insert into qms_msa_data (comp_cd, study_id, appraiser_no, part_no, trial_no, value, created_by)
                values (?, ?, 1, 1, 1, 2.0, 't')""", comp, draft));
        // 머리 규칙
        long asset = accounts.query(comp, "select asset_id from mst_phys_asset where asset_cd = 'GG1'", Long.class);
        String ins = "insert into qms_msa_study (comp_cd, study_no, asset_id, method, appraiser_cnt, part_cnt, trial_cnt, basis, "
                + "process_sigma, status, study_date, created_by, updated_by) values (?, ?, ?, ?, 2, ?, 2, ?, ?, ?, " + TestAccounts.COMP_TODAY + ", 't', 't')";
        constraint(comp, "ck_qms_msa_study_draft", () -> accounts.exec(comp, ins, comp, "Z1", asset, "AVG_RANGE", 5, "PROCESS", null, "DONE"));
        constraint(comp, "ck_qms_msa_study_method", () -> accounts.exec(comp, ins, comp, "Z2", asset, "ATTRIBUTE", 5, "PROCESS", null, "DRAFT"));
        constraint(comp, "ck_qms_msa_study_method", () -> accounts.exec(comp, ins, comp, "Z3", asset, "AVG_RANGE", 11, "PROCESS", null, "DRAFT"));
        constraint(comp, "ck_qms_msa_study_method", () -> accounts.exec(comp, ins, comp, "Z5", asset, "ANOVA", 11, "PROCESS", null, "DRAFT"));
        constraint(comp, "ck_qms_msa_study_basis_required", () -> accounts.exec(comp, ins, comp, "Z6", asset, "ANOVA", 5, null, null, "DRAFT"));
        constraint(comp, "ck_qms_msa_study_basis_required", () -> accounts.exec(comp, ins, comp, "Z7", asset, "AVG_RANGE", 5, null, null, "DRAFT"));
        // 빈틈 제약은 NOT VALID — 마이그레이션 때 기존 행을 검사하지 않는다(옛 기준 NULL 행이 있는 DB 에서도 기동)
        assertThat(accounts.query(comp, "select convalidated from pg_constraint where conname = 'ck_qms_msa_study_basis_required'", Boolean.class))
                .isFalse();
        constraint(comp, "ck_qms_msa_study_sigma", () -> accounts.exec(comp, ins, comp, "Z4", asset, "AVG_RANGE", 5, "TOLERANCE", 0.5, "DRAFT"));
        constraint(comp, "ck_qms_msa_study_names", () -> accounts.exec(comp,
                "update qms_msa_study set appraiser_nms = '{a}' where study_id = ?", draft));
    }
}
