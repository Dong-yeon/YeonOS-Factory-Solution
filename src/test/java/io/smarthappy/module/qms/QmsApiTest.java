package io.smarthappy.module.qms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;

/**
 * P3 QMS Q1 정상 흐름 (docs/29 2장 Q1 완료 기준) — 샘플링 계획 방식별 저장·미리보기, 검사계획 생성·행 한 벌 저장·확정·스냅샷,
 * 확정 뒤 특성 규격 변경 → 확정본 그대로 + "새 리비전 필요", 새 리비전 복사·자동 마감·겹침, 클래스 계획·유효 계획 찾기, 샘플링 잠금.
 */
class QmsApiTest extends QmsTestBase {

    // ---------- 샘플링 계획 ----------

    @Test
    void 샘플링_방식별_저장_안쓰는_칸은_버림_목록_엑셀() throws Exception {
        long fixed = id(postJson(SP, "{\"planCd\":\"f-10\",\"planNm\":\"고정 10\",\"method\":\"FIXED\",\"fixedN\":10,\"ac\":1,"
                + "\"aql\":\"2.5\",\"stdCd\":\"FAKE\"}"));
        assertThat(accounts.query(comp, "select concat_ws('/', plan_cd, fixed_n, ac, re, aql, std_cd) from qms_sampling_plan "
                + "where sampling_plan_id = ?", String.class, fixed)).isEqualTo("F-10/10/1/2");
        id(postJson(SP, "{\"planCd\":\"P3\",\"planNm\":\"3%\",\"method\":\"PERCENT\",\"pct\":\"3.5\"}"));
        long aql = id(postJson(SP, "{\"planCd\":\"A1\",\"planNm\":\"AQL\",\"method\":\"AQL\",\"stdCd\":\"fake\",\"inspLevel\":\"II\","
                + "\"severity\":\"NORMAL\",\"aql\":\"1.000\",\"ac\":3,\"fixedN\":9}"));
        assertThat(accounts.query(comp, "select concat_ws('/', std_cd, insp_level, severity, aql::text, coalesce(ac, -1), "
                + "coalesce(fixed_n, -1)) from qms_sampling_plan where sampling_plan_id = ?", String.class, aql))
                .isEqualTo("FAKE/II/NORMAL/1.000/-1/-1");
        mvc.perform(get(SP).param("method", "AQL").with(admin)).andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.list[*].planCd").value(hasItems("A1", "SAQL")));
        mvc.perform(get(SP + "/columns").with(admin)).andExpect(jsonPath("$.list[0].field").value("planCd"));
        mvc.perform(json(post(SP + "/export"), "{\"method\":\"FIXED\"}").with(admin)).andExpect(status().isOk());
    }

    @Test
    void 샘플링_검증_칸오류_모아서() throws Exception {
        postJson(SP, "{\"planCd\":\"a b\",\"planNm\":\"\",\"method\":\"SKIP\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.length()").value(3));
        postJson(SP, "{\"planCd\":\"X1\",\"planNm\":\"x\",\"method\":\"FIXED\",\"ac\":5,\"fixedN\":5}")
                .andExpect(jsonPath("$.errors[0].code").value("AC_TOO_BIG"));
        postJson(SP, "{\"planCd\":\"X1\",\"planNm\":\"x\",\"method\":\"FIXED\"}").andExpect(jsonPath("$.errors[0].field").value("fixedN"));
        postJson(SP, "{\"planCd\":\"X1\",\"planNm\":\"x\",\"method\":\"PERCENT\",\"pct\":\"100.01\"}")
                .andExpect(jsonPath("$.errors[0].code").value("RANGE"));
        postJson(SP, "{\"planCd\":\"X1\",\"planNm\":\"x\",\"method\":\"PERCENT\",\"pct\":\"1.234\"}")
                .andExpect(jsonPath("$.errors[0].code").value("SCALE"));
        // 표에 없는 수준·AQL — 저장 때 막는다(오타 방지)
        postJson(SP, "{\"planCd\":\"X1\",\"planNm\":\"x\",\"method\":\"AQL\",\"stdCd\":\"FAKE\",\"inspLevel\":\"III\","
                + "\"severity\":\"NORMAL\",\"aql\":\"0.65\"}").andExpect(jsonPath("$.errors[*].code").value(hasItems("NO_AQL_LETTER", "NO_AQL_PLAN")));
        postJson(SP, "{\"planCd\":\"X1\",\"planNm\":\"x\",\"method\":\"AQL\"}").andExpect(jsonPath("$.errors.length()").value(4));
        postJson(SP, "{\"planCd\":\"s5\",\"planNm\":\"x\",\"method\":\"ALL\"}").andExpect(jsonPath("$.errors[0].code").value("DUPLICATE"));
        long s5 = accounts.query(comp, "select sampling_plan_id from qms_sampling_plan where plan_cd = 'S5'", Long.class);
        putJson(SP + "/" + s5, "{\"planCd\":\"S6\",\"planNm\":\"x\",\"method\":\"FIXED\",\"fixedN\":5,\"rowVer\":0}")
                .andExpect(jsonPath("$.errors[0].code").value("FIXED"));
        putJson(SP + "/" + s5, "{\"planNm\":\"x\",\"method\":\"FIXED\",\"fixedN\":5,\"rowVer\":9}").andExpect(status().isConflict());
    }

    @Test
    void 판정_미리보기_저장된_계획과_입력중_값() throws Exception {
        long saql = accounts.query(comp, "select sampling_plan_id from qms_sampling_plan where plan_cd = 'SAQL'", Long.class);
        // 손 검산(가짜 표): 로트 200 → C, n 32, Ac 1 / Re 2 → 불량 2 불합격
        postJson(SP + "/preview", "{\"samplingPlanId\":" + saql + ",\"lotQty\":200,\"defects\":2}").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.codeLetter").value("C")).andExpect(jsonPath("$.data.sampleSize").value(32))
                .andExpect(jsonPath("$.data.ac").value(1)).andExpect(jsonPath("$.data.re").value(2))
                .andExpect(jsonPath("$.data.verdict").value("REJECT"));
        postJson(SP + "/preview", "{\"samplingPlanId\":" + saql + ",\"lotQty\":2}").andExpect(jsonPath("$.data.all").value(true))
                .andExpect(jsonPath("$.data.sampleSize").value(2)).andExpect(jsonPath("$.data.verdict").doesNotExist());
        postJson(SP + "/preview", "{\"form\":{\"method\":\"PERCENT\",\"pct\":\"10\",\"ac\":0},\"lotQty\":1001,\"defects\":0}")
                .andExpect(jsonPath("$.data.sampleSize").value(101)).andExpect(jsonPath("$.data.verdict").value("ACCEPT"));
        postJson(SP + "/preview", "{\"form\":{\"method\":\"FIXED\",\"fixedN\":5},\"lotQty\":100,\"defects\":6}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("DEFECTS"));
        postJson(SP + "/preview", "{\"form\":{\"method\":\"ALL\"},\"lotQty\":0}").andExpect(jsonPath("$.errors[0].code").value("LOT_QTY"));
        postJson(SP + "/preview", "{\"form\":{\"method\":\"ALL\"}}").andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        // 둘 다·둘 다 없음 → 400
        postJson(SP + "/preview", "{\"samplingPlanId\":" + saql + ",\"form\":{\"method\":\"ALL\"},\"lotQty\":5}").andExpect(status().isBadRequest());
        postJson(SP + "/preview", "{\"lotQty\":5}").andExpect(status().isBadRequest());
        // 표 1 구간을 사용 중지하면 그 로트는 "표에 없음"
        accounts.exec(comp, "update qms_aql_table set use_yn = 'N' where code_letter = 'D'");
        postJson(SP + "/preview", "{\"samplingPlanId\":" + saql + ",\"lotQty\":501}").andExpect(jsonPath("$.errors[0].code").value("NO_AQL_LETTER"));
        mvc.perform(get("/api/qms/aql/stds").with(admin)).andExpect(jsonPath("$.data[0]").value("FAKE"));
        mvc.perform(get("/api/qms/aql").param("stdCd", "fake").with(admin)).andExpect(jsonPath("$.data.letters.length()").value(4))
                .andExpect(jsonPath("$.data.rows.length()").value(4)).andExpect(jsonPath("$.data.letters[3].lotTo").doesNotExist());
    }

    // ---------- 검사계획 ----------

    @Test
    void 검사계획_생성_행저장_특성고르기는_대상에_맞는것만() throws Exception {
        long p = plan("PROCESS", "fg1", null, "draw");
        postJson(IP, "{\"inspType\":\"PROCESS\",\"itemCd\":\"FG1\",\"procCd\":\"DRAW\"}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("QMS_PLAN_DRAFT_EXISTS"));
        plan("PROCESS", "FG1", null, null);                                // 공정이 다르면(공정 무관) 다른 대상
        plan("FINAL", "FG1", null, "DRAW");                                // 유형이 다르면 다른 대상
        // 특성 선택지 = 품목 FG1 제품 특성 + DRAW 공정 특성 (IQC 공정 특성은 아님)
        mvc.perform(get(IP + "/" + p + "/chars").with(admin)).andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.data[*].charId").value(not(hasItem((int) iqcChar))))
                .andExpect(jsonPath("$.data[0].specText").value("9.95 ~ 10.05 mm"));
        lines(p, line("a", null, 10, fgChar1, "S5", "CALIPER"), line("b", null, 20, fgChar2, "sall", "visual"),
                line("c", null, 30, drawChar, "SAQL", "OTHER", "EVERY_N", 50)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.lines").value(3));
        mvc.perform(get(IP + "/" + p).with(admin))
                .andExpect(jsonPath("$.data.plan.rev").value("A0")).andExpect(jsonPath("$.data.plan.lineCnt").value(3))
                .andExpect(jsonPath("$.data.plan.targetCd").value("FG1"))
                .andExpect(jsonPath("$.data.lines[0].specText").value("9.95 ~ 10.05 mm"))
                .andExpect(jsonPath("$.data.lines[0].specialCls").value("SC"))
                .andExpect(jsonPath("$.data.lines[1].samplingPlanCd").value("SALL"))
                .andExpect(jsonPath("$.data.lines[1].specText").value("OK/NG"))
                .andExpect(jsonPath("$.data.lines[2].freqN").value(50))
                .andExpect(jsonPath("$.data.lines[0].specChanged").value(false));
        // IQC 공정 특성은 이 계획(공정 DRAW)에 맞지 않음
        lines(p, line("d", null, 10, iqcChar, "S5", "VISUAL")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("CHAR_TARGET"));
        mvc.perform(get(IP).param("inspType", "PROCESS").with(admin)).andExpect(jsonPath("$.total").value(2));
        mvc.perform(get(IP).param("target", "fg").param("procCd", "draw").with(admin)).andExpect(jsonPath("$.total").value(2));
        mvc.perform(json(post(IP + "/export"), "{\"inspType\":\"PROCESS\"}").with(admin)).andExpect(status().isOk());
    }

    @Test
    void 확정하면_특성_규격_스냅샷_특성이_바뀌어도_확정본은_그대로_새리비전_필요_표시() throws Exception {
        long p = plan("FINAL", "FG1", null, null);
        lines(p, line("a", null, 10, fgChar1, "S5", "CALIPER"), line("b", null, 20, fgChar2, "SALL", "VISUAL")).andExpect(status().isOk());
        // 작성 중엔 스냅샷 없음(특성을 따라감)
        assertThat(accounts.query(comp, "select count(*) from qms_insp_plan_line where insp_plan_id = ? and data_type is null",
                Long.class, p)).isEqualTo(2);
        action(p, "release").andExpect(status().isOk());
        assertThat(accounts.query(comp, "select concat_ws('/', lsl, nominal, usl, unit_nm, scale, special_cls, data_type) "
                + "from qms_insp_plan_line where insp_plan_id = ? and char_id = ?", String.class, p, fgChar1))
                .isEqualTo("9.950000/10.000000/10.050000/mm/2/SC/VARIABLE");
        // 도면 개정: 특성 규격·특별특성 변경 → 확정본 행은 그대로, 화면은 "다름"
        accounts.exec(comp, "update qms_char set lsl = 9.97, usl = 10.03, special_cls = 'CC' where char_id = ?", fgChar1);
        assertThat(accounts.query(comp, "select lsl::text from qms_insp_plan_line where insp_plan_id = ? and char_id = ?", String.class,
                p, fgChar1)).isEqualTo("9.950000");
        mvc.perform(get(IP + "/" + p).with(admin))
                .andExpect(jsonPath("$.data.plan.specChangedCnt").value(1))
                .andExpect(jsonPath("$.data.lines[0].specText").value("9.95 ~ 10.05 mm"))
                .andExpect(jsonPath("$.data.lines[0].specialCls").value("SC"))
                .andExpect(jsonPath("$.data.lines[0].specChanged").value(true))
                .andExpect(jsonPath("$.data.lines[0].currentSpecText").value("9.97 ~ 10.03 mm"))
                .andExpect(jsonPath("$.data.lines[1].specChanged").value(false));
        mvc.perform(get(IP).param("specChangedOnly", "true").with(admin)).andExpect(jsonPath("$.total").value(1));
        // 새 리비전은 지금 규격을 따라가고, 확정하면 새 규격으로 스냅샷
        long p2 = id(action(p, "new-revision"));
        mvc.perform(get(IP + "/" + p2).with(admin)).andExpect(jsonPath("$.data.plan.rev").value("A1"))
                .andExpect(jsonPath("$.data.lines[0].specText").value("9.97 ~ 10.03 mm"))
                .andExpect(jsonPath("$.data.lines[0].specChanged").value(false))
                .andExpect(jsonPath("$.data.lines[0].methodNm").value("측정"));
        action(p2, "release").andExpect(status().isOk());
        assertThat(accounts.query(comp, "select concat_ws('/', lsl, special_cls) from qms_insp_plan_line where insp_plan_id = ? "
                + "and char_id = ?", String.class, p2, fgChar1)).isEqualTo("9.970000/CC");
        // 이전 확정본은 새 확정본 시작일에 자동 마감
        assertThat(accounts.query(comp, "select valid_to::text from qms_insp_plan where insp_plan_id = ?", String.class, p))
                .isEqualTo(today.plusDays(1).toString());
        mvc.perform(get(IP).param("specChangedOnly", "true").with(admin)).andExpect(jsonPath("$.total").value(1));   // 마감된 A0 은 아직 RELEASED
    }

    @Test
    void 확정_조건_행없음_시작일_과거_겹침_작성중_하나() throws Exception {
        long p = plan("PROCESS", "FG1", null, "DRAW");
        action(p, "release").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("QMS_PLAN_RELEASE_CHECK"))
                .andExpect(jsonPath("$.errors[0].code").value("NO_LINES"));
        lines(p, line("a", null, 10, drawChar, "S5", "OTHER")).andExpect(status().isOk());
        putJson(IP + "/" + p, "{\"validFrom\":\"" + today.minusDays(1) + "\",\"rowVer\":" + rowVer(p) + "}").andExpect(status().isOk());
        action(p, "release").andExpect(jsonPath("$.errors[0].code").value("PAST"));
        putJson(IP + "/" + p, "{\"validFrom\":\"" + today + "\",\"rowVer\":" + rowVer(p) + "}").andExpect(status().isOk());
        action(p, "release").andExpect(status().isOk());
        // 확정본: 수정 409, 행 저장 409
        putJson(IP + "/" + p, "{\"remark\":\"x\",\"rowVer\":" + rowVer(p) + "}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("QMS_PLAN_NOT_DRAFT"));
        putJson(IP + "/" + p + "/lines?rowVer=" + rowVer(p), "{\"lines\":[]}").andExpect(status().isConflict());
        // 같은 날 시작하는 새 리비전 → 확정 때 겹침
        long p3 = id(postJson(IP + "/" + p + "/new-revision", "{\"rowVer\":" + rowVer(p) + ",\"validFrom\":\"" + today + "\",\"rev\":\"b0\"}"));
        action(p3, "release").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("QMS_PLAN_PERIOD_OVERLAP"));
        postJson(IP + "/" + p3 + "/new-revision", "{\"rowVer\":" + rowVer(p3) + "}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("QMS_PLAN_DRAFT_EXISTS"));
        action(p3, "obsolete").andExpect(status().isOk());
        action(p3, "obsolete").andExpect(status().isConflict());
    }

    @Test
    void 클래스_계획은_공정특성만_유효계획은_품목이_클래스보다_우선() throws Exception {
        long cls = plan("RECEIVE", null, "coil", "IQC");
        mvc.perform(get(IP + "/" + cls + "/chars").with(admin)).andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].charId").value(iqcChar));
        lines(cls, line("a", null, 10, fgChar1, "S5", "VISUAL")).andExpect(jsonPath("$.errors[0].code").value("CHAR_TARGET"));
        lines(cls, line("a", null, 10, iqcChar, "SAQL", "VISUAL")).andExpect(status().isOk());
        action(cls, "release").andExpect(status().isOk());
        mvc.perform(get(IP + "/effective").param("itemCd", "RM1").param("inspType", "RECEIVE").param("procCd", "IQC").with(admin))
                .andExpect(jsonPath("$.data.inspPlanId").value(cls)).andExpect(jsonPath("$.data.targetKind").value("CLASS"));
        // 공정 없이 물으면 공정 IQC 계획은 맞지 않음
        mvc.perform(get(IP + "/effective").param("itemCd", "RM1").param("inspType", "RECEIVE").with(admin))
                .andExpect(jsonPath("$.data.inspPlanId").doesNotExist());
        long item = plan("RECEIVE", "RM1", null, "IQC");
        lines(item, line("a", null, 10, iqcChar, "S5", "VISUAL")).andExpect(status().isOk());
        action(item, "release").andExpect(status().isOk());
        mvc.perform(get(IP + "/effective").param("itemCd", "RM1").param("inspType", "RECEIVE").param("procCd", "IQC").with(admin))
                .andExpect(jsonPath("$.data.inspPlanId").value(item)).andExpect(jsonPath("$.data.targetKind").value("ITEM"));
        mvc.perform(get(IP + "/effective").param("itemCd", "RM2").param("inspType", "RECEIVE").param("procCd", "IQC")
                .param("date", today.toString()).with(admin)).andExpect(jsonPath("$.data.inspPlanId").value(cls));
        mvc.perform(get(IP + "/effective").param("itemCd", "RM2").param("inspType", "RECEIVE").param("procCd", "IQC")
                .param("date", today.minusDays(1).toString()).with(admin)).andExpect(jsonPath("$.data.inspPlanId").doesNotExist());
        postJson(IP, "{\"inspType\":\"RECEIVE\",\"itemCd\":\"RM1\",\"classCd\":\"COIL\"}").andExpect(jsonPath("$.errors[0].code").value("ONE_TARGET"));
        postJson(IP, "{\"inspType\":\"RECEIVE\"}").andExpect(jsonPath("$.errors[0].field").value("itemCd"));
    }

    @Test
    void 확정_검사계획이_쓰는_샘플링은_방식_수치_잠김_이름은_허용() throws Exception {
        long p = plan("FINAL", "FG1", null, null);
        lines(p, line("a", null, 10, fgChar1, "S5", "CALIPER")).andExpect(status().isOk());
        long s5 = accounts.query(comp, "select sampling_plan_id from qms_sampling_plan where plan_cd = 'S5'", Long.class);
        // 작성 중 계획만 쓰면 자유
        putJson(SP + "/" + s5, "{\"planNm\":\"고정 6\",\"method\":\"FIXED\",\"fixedN\":6,\"rowVer\":0}").andExpect(status().isOk());
        action(p, "release").andExpect(status().isOk());
        mvc.perform(get(SP + "/" + s5).with(admin)).andExpect(jsonPath("$.data.lockedCnt").value(1));
        putJson(SP + "/" + s5, "{\"planNm\":\"고정 7\",\"method\":\"FIXED\",\"fixedN\":7,\"rowVer\":1}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("SAMPLING_LOCKED"));
        putJson(SP + "/" + s5, "{\"planNm\":\"고정 6개(이름만)\",\"method\":\"FIXED\",\"fixedN\":6,\"useYn\":\"N\",\"rowVer\":1}")
                .andExpect(status().isOk());
        constraint(comp, "ck_qms_sampling_plan_in_use",
                () -> accounts.exec(comp, "update qms_sampling_plan set fixed_n = 8 where sampling_plan_id = ?", s5));
        // 사용 중지된 샘플링은 선택지에서 빠지지만 확정본 행은 그대로
        mvc.perform(get(IP + "/sampling-plans").with(admin)).andExpect(jsonPath("$.data[*].planCd").value(not(hasItem("S5"))));
        mvc.perform(get(IP + "/" + p).with(admin)).andExpect(jsonPath("$.data.lines[0].samplingUseYn").value("N"));
    }
}
