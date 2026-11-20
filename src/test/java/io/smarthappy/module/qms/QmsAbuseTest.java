package io.smarthappy.module.qms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * P3 QMS Q1 이상 사용 (dev-playbook abuse-testing A~K) — A사로 B사, 직접 SQL(불변·회사·상태·스냅샷), 행 한 벌 저장의 이상 입력,
 * 사용 중지 참조(새 참조만 차단), 동시 편집, 멱등, 권한.
 */
class QmsAbuseTest extends QmsTestBase {

    private long releasedPlan() throws Exception {
        long p = plan("PROCESS", "FG1", null, "DRAW");
        lines(p, line("a", null, 10, fgChar1, "S5", "CALIPER"), line("b", null, 20, drawChar, "SAQL", "OTHER")).andExpect(status().isOk());
        action(p, "release").andExpect(status().isOk());
        return p;
    }

    // ---------- A사로 B사 ----------

    @Test
    void B사_사용자는_A사_검사계획_샘플링_AQL표_특성을_보지도_쓰지도_못한다() throws Exception {
        long p = releasedPlan();
        long draft = plan("FINAL", "FG1", null, null);
        String other = accounts.newComp("QM");
        long bUser = accounts.newUser(other, "admin1", PW);
        accounts.grant(other, bUser, "QA", "qms-sampling", "SEARCH", "SAVE", "EXCEL");
        accounts.grant(other, bUser, "QA", "qms-insp-plan", "SEARCH", "SAVE", "APPROVE", "DELETE", "EXCEL");
        RequestPostProcessor b = accounts.as(other, bUser, "admin1");
        long aS5 = accounts.query(comp, "select sampling_plan_id from qms_sampling_plan where plan_cd = 'S5'", Long.class);
        mvc.perform(get(IP + "/" + p).with(b)).andExpect(status().isNotFound());
        mvc.perform(get(IP + "/" + p + "/chars").with(b)).andExpect(status().isNotFound());
        mvc.perform(get(IP).with(b)).andExpect(jsonPath("$.total").value(0));
        mvc.perform(get(SP).with(b)).andExpect(jsonPath("$.total").value(0));
        mvc.perform(get(SP + "/" + aS5).with(b)).andExpect(status().isNotFound());
        mvc.perform(get("/api/qms/aql/stds").with(b)).andExpect(jsonPath("$.data.length()").value(0));
        mvc.perform(get("/api/qms/aql").param("stdCd", "FAKE").with(b)).andExpect(jsonPath("$.data.letters.length()").value(0));
        mvc.perform(json(post(SP + "/preview"), "{\"samplingPlanId\":" + aS5 + ",\"lotQty\":10}").with(b)).andExpect(status().isNotFound());
        mvc.perform(json(put(SP + "/" + aS5), "{\"planNm\":\"x\",\"method\":\"ALL\",\"rowVer\":0}").with(b)).andExpect(status().isNotFound());
        mvc.perform(json(put(IP + "/" + draft + "/lines?rowVer=0"), "{\"lines\":[]}").with(b)).andExpect(status().isNotFound());
        mvc.perform(json(post(IP + "/" + p + "/obsolete"), "{\"rowVer\":1}").with(b)).andExpect(status().isNotFound());
        mvc.perform(json(post(IP + "/" + p + "/new-revision"), "{\"rowVer\":1}").with(b)).andExpect(status().isNotFound());
        mvc.perform(get(IP + "/effective").param("itemCd", "FG1").param("inspType", "PROCESS").with(b)).andExpect(status().isNotFound());
        // B사 자기 계획에 A사 특성 id·A사 샘플링 코드(B사엔 없음) → 칸 오류
        accounts.exec(other, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, created_by, updated_by)
                values (?, 'FG1', 'B사 품목', 'FG', 'MAKE', 'EA', 't', 't')""", other);
        long bp = id(mvc.perform(json(post(IP), "{\"inspType\":\"FINAL\",\"itemCd\":\"FG1\"}").with(b)));
        mvc.perform(json(put(IP + "/" + bp + "/lines?rowVer=0"), "{\"lines\":[" + line("a", null, 10, fgChar1, "S5", "VISUAL") + "]}")
                .with(b)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].code").value(hasItems("NOT_FOUND")))
                .andExpect(jsonPath("$.errors.length()").value(2));
        // 직접 SQL: B사 범위에서 A사 품목·클래스·공정으로 머리, A사 머리에 행, A사 특성·샘플링으로 B사 행
        long aItem = accounts.query(comp, "select item_id from mst_item where item_cd = 'FG1'", Long.class);
        long aClass = accounts.query(comp, "select class_id from mst_item_class where class_cd = 'COIL'", Long.class);
        long aProc = accounts.query(comp, "select proc_id from mst_proc where proc_cd = 'DRAW'", Long.class);
        constraint(other, "fk_qms_insp_plan_item_comp", () -> accounts.exec(other, """
                insert into qms_insp_plan (comp_cd, insp_type, item_id, rev, valid_from, created_by, updated_by)
                values (?, 'RECEIVE', ?, 'Z1', (now() at time zone 'Asia/Seoul')::date, 't', 't')""", other, aItem));
        constraint(other, "fk_qms_insp_plan_class_comp", () -> accounts.exec(other, """
                insert into qms_insp_plan (comp_cd, insp_type, item_class_id, rev, valid_from, created_by, updated_by)
                values (?, 'RECEIVE', ?, 'Z1', (now() at time zone 'Asia/Seoul')::date, 't', 't')""", other, aClass));
        long bItem = accounts.query(other, "select item_id from mst_item where item_cd = 'FG1'", Long.class);
        constraint(other, "fk_qms_insp_plan_proc_comp", () -> accounts.exec(other, """
                insert into qms_insp_plan (comp_cd, insp_type, item_id, proc_id, rev, valid_from, created_by, updated_by)
                values (?, 'PROCESS', ?, ?, 'Z1', (now() at time zone 'Asia/Seoul')::date, 't', 't')""", other, bItem, aProc));
        constraint(other, "fk_qms_insp_plan_line_plan_comp", () -> accounts.exec(other, """
                insert into qms_insp_plan_line (comp_cd, insp_plan_id, line_no, char_id, gauge_class, sampling_plan_id, created_by, updated_by)
                values (?, ?, 99, ?, 'VISUAL', ?, 't', 't')""", other, draft, fgChar2, aS5));
        long bS5 = accounts.query(other, """
                insert into qms_sampling_plan (comp_cd, plan_cd, plan_nm, method, ac, re, created_by, updated_by)
                values (?, 'S5', 'B사 전수', 'ALL', 0, 1, 't', 't') returning sampling_plan_id""", Long.class, other);
        long bChar = accounts.query(other, """
                insert into qms_char (comp_cd, char_type, item_id, char_no, char_nm, data_type, scale, created_by, updated_by)
                values (?, 'PRODUCT', ?, '2', 'B사 외관', 'ATTRIBUTE', 0, 't', 't') returning char_id""", Long.class, other, bItem);
        constraint(other, "fk_qms_insp_plan_line_char_comp", () -> accounts.exec(other, """
                insert into qms_insp_plan_line (comp_cd, insp_plan_id, line_no, char_id, gauge_class, sampling_plan_id, created_by, updated_by)
                values (?, ?, 10, ?, 'VISUAL', ?, 't', 't')""", other, bp, fgChar2, bS5));
        constraint(other, "fk_qms_insp_plan_line_sampling_comp", () -> accounts.exec(other, """
                insert into qms_insp_plan_line (comp_cd, insp_plan_id, line_no, char_id, gauge_class, sampling_plan_id, created_by, updated_by)
                values (?, ?, 10, ?, 'VISUAL', ?, 't', 't')""", other, bp, bChar, aS5));
        // B사 범위에서 A사 회사 코드로 쓰기 → RLS(WITH CHECK)
        sqlState("42501", () -> accounts.exec(other, """
                insert into qms_sampling_plan (comp_cd, plan_cd, plan_nm, method, ac, re, created_by, updated_by)
                values (?, 'X', 'x', 'ALL', 0, 1, 't', 't')""", comp));
        // B사 범위의 수정·삭제는 A사 행에 0 행
        assertThat(accounts.exec(other, "update qms_sampling_plan set plan_nm = 'x' where sampling_plan_id = ?", aS5)).isZero();
        assertThat(accounts.exec(other, "update qms_insp_plan set remark = 'x' where insp_plan_id = ?", draft)).isZero();
        assertThat(accounts.exec(other, "delete from qms_insp_plan_line where insp_plan_id = ?", p)).isZero();
    }

    // ---------- 직접 SQL: 불변·상태·스냅샷 ----------

    @Test
    void 직접SQL_확정본_불변_대상키_불변_삭제_금지_상태_이동_규칙() throws Exception {
        long p = releasedPlan();
        long l1 = lineId(p, fgChar1);
        constraint(comp, "ck_qms_insp_plan_line_draft", () -> accounts.exec(comp, "update qms_insp_plan_line set lsl = 0 where line_id = ?", l1));
        constraint(comp, "ck_qms_insp_plan_line_draft", () -> accounts.exec(comp, "delete from qms_insp_plan_line where line_id = ?", l1));
        constraint(comp, "ck_qms_insp_plan_line_draft", () -> accounts.exec(comp, """
                insert into qms_insp_plan_line (comp_cd, insp_plan_id, line_no, char_id, gauge_class, sampling_plan_id, created_by, updated_by)
                select comp_cd, insp_plan_id, 99, ?, 'VISUAL', sampling_plan_id, 't', 't' from qms_insp_plan_line where line_id = ?""",
                fgChar2, l1));
        constraint(comp, "ck_qms_insp_plan_immutable", () -> accounts.exec(comp, "update qms_insp_plan set remark = 'x' where insp_plan_id = ?", p));
        // 확정본 종료일은 앞당기기만(마감) — 마감 뒤 늘리거나 지우기는 거부
        accounts.exec(comp, "update qms_insp_plan set valid_to = valid_from + 30 where insp_plan_id = ?", p);
        constraint(comp, "ck_qms_insp_plan_immutable", () -> accounts.exec(comp, "update qms_insp_plan set valid_to = null where insp_plan_id = ?", p));
        constraint(comp, "ck_qms_insp_plan_immutable", () -> accounts.exec(comp,
                "update qms_insp_plan set valid_to = valid_from + 31 where insp_plan_id = ?", p));
        constraint(comp, "ck_qms_insp_plan_transition", () -> accounts.exec(comp, "update qms_insp_plan set status = 'DRAFT' where insp_plan_id = ?", p));
        sqlState("42501", () -> accounts.exec(comp, "delete from qms_insp_plan where insp_plan_id = ?", p));
        // 작성 중이어도 대상 키(공정·품목)는 못 바꾼다 — 행 트리거의 "특성이 대상과 맞는지" 를 우회하게 됨
        long d = plan("PROCESS", "FG1", null, null);
        constraint(comp, "ck_qms_insp_plan_key_immutable", () -> accounts.exec(comp,
                "update qms_insp_plan set proc_id = (select proc_id from mst_proc where proc_cd = 'IQC') where insp_plan_id = ?", d));
        constraint(comp, "ck_qms_insp_plan_key_immutable", () -> accounts.exec(comp, "update qms_insp_plan set insp_type = 'FINAL' where insp_plan_id = ?", d));
        // 새 머리는 DRAFT 로만, 대상은 하나만
        constraint(comp, "ck_qms_insp_plan_draft", () -> accounts.exec(comp, """
                insert into qms_insp_plan (comp_cd, insp_type, item_id, rev, status, valid_from, released_at, created_by, updated_by)
                select ?, 'PATROL', item_id, 'A0', 'RELEASED', (now() at time zone 'Asia/Seoul')::date, now(), 't', 't' from mst_item where item_cd = 'FG1'""", comp));
        constraint(comp, "ck_qms_insp_plan_target", () -> accounts.exec(comp, """
                insert into qms_insp_plan (comp_cd, insp_type, item_id, item_class_id, rev, valid_from, created_by, updated_by)
                select ?, 'PATROL', i.item_id, c.class_id, 'A0', (now() at time zone 'Asia/Seoul')::date, 't', 't' from mst_item i, mst_item_class c
                 where i.item_cd = 'FG1' and c.class_cd = 'COIL'""", comp));
        // 행 없는 계획을 직접 확정 → 거부
        constraint(comp, "ck_qms_insp_plan_no_lines", () -> accounts.exec(comp,
                "update qms_insp_plan set status = 'RELEASED', released_at = now() where insp_plan_id = ?", d));
        // 작성 중 두 번째, 같은 키 확정본 기간 겹침(공정 NULL 끼리도) — 직접 SQL
        constraint(comp, "uq_qms_insp_plan_draft", () -> accounts.exec(comp, """
                insert into qms_insp_plan (comp_cd, insp_type, item_id, rev, valid_from, created_by, updated_by)
                select ?, 'PROCESS', item_id, 'Q9', (now() at time zone 'Asia/Seoul')::date, 't', 't' from mst_item where item_cd = 'FG1'""", comp));
        accounts.exec(comp, "insert into qms_insp_plan_line (comp_cd, insp_plan_id, line_no, char_id, gauge_class, sampling_plan_id, "
                + "created_by, updated_by) select ?, ?, 10, ?, 'VISUAL', sampling_plan_id, 't', 't' from qms_sampling_plan where plan_cd = 'S5'",
                comp, d, fgChar2);
        accounts.exec(comp, "update qms_insp_plan set status = 'RELEASED', released_at = now() where insp_plan_id = ?", d);
        long d2 = id(action(d, "new-revision"));
        accounts.exec(comp, "update qms_insp_plan set valid_from = (now() at time zone 'Asia/Seoul')::date - 1 where insp_plan_id = ?", d2);
        constraint(comp, "ex_qms_insp_plan_period", () -> accounts.exec(comp,
                "update qms_insp_plan set status = 'RELEASED', released_at = now() where insp_plan_id = ?", d2));
    }

    @Test
    void 직접SQL_작성중_스냅샷_값을_조작해도_확정이_특성_값으로_덮는다() throws Exception {
        long p = plan("FINAL", "FG1", null, null);
        lines(p, line("a", null, 10, fgChar1, "S5", "CALIPER")).andExpect(status().isOk());
        accounts.exec(comp, "update qms_insp_plan_line set lsl = -999, usl = 999, data_type = 'ATTRIBUTE', special_cls = 'NONE' "
                + "where insp_plan_id = ?", p);
        accounts.exec(comp, "update qms_insp_plan set status = 'RELEASED', released_at = now(), released_by = 'sql' where insp_plan_id = ?", p);
        assertThat(accounts.query(comp, "select concat_ws('/', lsl, usl, data_type, special_cls) from qms_insp_plan_line "
                + "where insp_plan_id = ?", String.class, p)).isEqualTo("9.950000/10.050000/VARIABLE/SC");
    }

    @Test
    void 직접SQL_행_특성은_대상에_맞아야_새_참조는_사용중_코드는_쓸수있는_것만() throws Exception {
        long p = plan("PROCESS", "FG1", null, "DRAW");
        long s5 = accounts.query(comp, "select sampling_plan_id from qms_sampling_plan where plan_cd = 'S5'", Long.class);
        String ins = """
                insert into qms_insp_plan_line (comp_cd, insp_plan_id, line_no, char_id, gauge_class, sampling_plan_id, freq_type, freq_n,
                                                created_by, updated_by)
                values (?, ?, ?, ?, ?, ?, ?, ?, 't', 't')""";
        constraint(comp, "ck_qms_insp_plan_line_char_target", () -> accounts.exec(comp, ins, comp, p, 10, iqcChar, "VISUAL", s5, "EVERY_LOT", null));
        constraint(comp, "fk_qms_insp_plan_line_gauge_class", () -> accounts.exec(comp, ins, comp, p, 10, fgChar1, "LASER", s5, "EVERY_LOT", null));
        constraint(comp, "ck_qms_insp_plan_line_freq", () -> accounts.exec(comp, ins, comp, p, 10, fgChar1, "VISUAL", s5, "EVERY_N", null));
        constraint(comp, "ck_qms_insp_plan_line_freq", () -> accounts.exec(comp, ins, comp, p, 10, fgChar1, "VISUAL", s5, "EVERY_HOURS", 169));
        constraint(comp, "ck_qms_insp_plan_line_freq", () -> accounts.exec(comp, ins, comp, p, 10, fgChar1, "VISUAL", s5, "START", 3));
        accounts.exec(comp, "update qms_char set use_yn = 'N' where char_id = ?", fgChar1);
        constraint(comp, "ck_qms_insp_plan_line_char_active", () -> accounts.exec(comp, ins, comp, p, 10, fgChar1, "VISUAL", s5, "EVERY_LOT", null));
        accounts.exec(comp, "update qms_char set use_yn = 'Y' where char_id = ?", fgChar1);
        accounts.exec(comp, "update qms_sampling_plan set use_yn = 'N' where sampling_plan_id = ?", s5);
        constraint(comp, "ck_qms_insp_plan_line_sampling_active", () -> accounts.exec(comp, ins, comp, p, 10, fgChar1, "VISUAL", s5, "EVERY_LOT", null));
        // 샘플링·AQL 표: 삭제 금지, 1회 샘플링 규칙, 로트 구간 겹침
        sqlState("42501", () -> accounts.exec(comp, "delete from qms_sampling_plan where sampling_plan_id = ?", s5));   // 앱 DELETE 회수(트리거는 소유자용 2중)
        sqlState("42501", () -> accounts.exec(comp, "delete from qms_aql_table"));
        constraint(comp, "ck_qms_sampling_plan_acre", () -> accounts.exec(comp,
                "update qms_sampling_plan set re = 3 where sampling_plan_id = ?", s5));
        // NULL 은 CHECK 를 통과한다(unknown) — Re 없음·주기 N 없음은 IS NOT NULL 로 막았다(이 테스트로 발견한 결함)
        constraint(comp, "ck_qms_sampling_plan_acre", () -> accounts.exec(comp,
                "update qms_sampling_plan set re = null where sampling_plan_id = ?", s5));
        constraint(comp, "ck_qms_sampling_plan_method", () -> accounts.exec(comp,
                "update qms_sampling_plan set aql = 1.0 where sampling_plan_id = ?", s5));
        constraint(comp, "ck_qms_aql_plan_row_acre", () -> accounts.exec(comp, """
                insert into qms_aql_plan_row (comp_cd, std_cd, severity, code_letter, aql, sample_size, ac, re, created_by, updated_by)
                values (?, 'FAKE', 'NORMAL', 'E', 1.0, 10, 1, 3, 't', 't')""", comp));
        constraint(comp, "ck_qms_aql_plan_row_acre", () -> accounts.exec(comp, """
                insert into qms_aql_plan_row (comp_cd, std_cd, severity, code_letter, aql, sample_size, ac, re, created_by, updated_by)
                values (?, 'FAKE', 'REDUCED', 'E', 1.0, 2, 1, 3, 't', 't')""", comp));
        constraint(comp, "ex_qms_aql_table_lot", () -> accounts.exec(comp, """
                insert into qms_aql_table (comp_cd, std_cd, insp_level, lot_from, lot_to, code_letter, created_by, updated_by)
                values (?, 'FAKE', 'II', 400, 600, 'Z', 't', 't')""", comp));
        // 사용 중지 행은 겹쳐도 된다(되살릴 때 다시 검사)
        accounts.exec(comp, """
                insert into qms_aql_table (comp_cd, std_cd, insp_level, lot_from, lot_to, code_letter, use_yn, created_by, updated_by)
                values (?, 'FAKE', 'II', 400, 600, 'Z', 'N', 't', 't')""", comp);
        constraint(comp, "ex_qms_aql_table_lot", () -> accounts.exec(comp, "update qms_aql_table set use_yn = 'Y' where code_letter = 'Z'"));
    }

    // ---------- 행 한 벌 저장 이상 입력 ----------

    @Test
    void 행_저장_이상_입력_중복_순번_특성_교환_id_섞기_개수_경계() throws Exception {
        long p = plan("PROCESS", "FG1", null, "DRAW");
        lines(p, line("a", null, 10, fgChar1, "S5", "CALIPER"), line("b", null, 10, fgChar1, "S5", "CALIPER"),
                line("c", null, 30, fgChar2, "NOPE", "LASER", "EVERY_N", null),
                line("d", null, 0, drawChar, "S5", "OTHER", "EVERY_HOURS", 169),
                line("e", null, 50, 999999999L, null, null, "WEEKLY", null)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.rowKey=='a')].code").value(hasItems("DUPLICATE")))
                .andExpect(jsonPath("$.errors[?(@.rowKey=='b')].field").value(hasItems("lineNo", "charId")))
                .andExpect(jsonPath("$.errors[?(@.rowKey=='c')].code").value(hasItems("NOT_FOUND", "CODE", "REQUIRED")))
                .andExpect(jsonPath("$.errors[?(@.rowKey=='d')].code").value(hasItems("RANGE")))
                .andExpect(jsonPath("$.errors[?(@.rowKey=='d')].field").value(hasItems("lineNo", "freqN")))
                .andExpect(jsonPath("$.errors[?(@.rowKey=='e')].code").value(hasItems("NOT_FOUND", "REQUIRED", "CODE")));
        assertThat(accounts.query(comp, "select count(*) from qms_insp_plan_line where insp_plan_id = ?", Long.class, p)).isZero();
        lines(p, line("a", null, 10, fgChar1, "S5", "CALIPER"), line("b", null, 20, fgChar2, "SALL", "VISUAL")).andExpect(status().isOk());
        long la = lineId(p, fgChar1);
        long lb = lineId(p, fgChar2);
        // 두 행의 특성·순번 맞바꾸기(커밋 시점 유일 검사) → 2행만
        lines(p, line("L" + la, la, 20, fgChar2, "S5", "CALIPER"), line("L" + lb, lb, 10, fgChar1, "SALL", "VISUAL"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.lines").value(2));
        // 같은 내용 다시 → 0, 행 빼면 삭제 1
        lines(p, line("L" + la, la, 20, fgChar2, "S5", "CALIPER"), line("L" + lb, lb, 10, fgChar1, "SALL", "VISUAL"))
                .andExpect(jsonPath("$.data.lines").value(0));
        lines(p, line("L" + la, la, 20, fgChar2, "S5", "CALIPER")).andExpect(jsonPath("$.data.lines").value(1));
        // 다른 계획의 행 id·같은 id 두 번·행 키 중복·null 행 → 400, 반영 0
        long other = plan("FINAL", "FG1", null, null);
        lines(other, line("x", la, 10, fgChar2, "S5", "VISUAL")).andExpect(status().isBadRequest());
        lines(p, line("x", la, 10, fgChar2, "S5", "VISUAL"), line("y", la, 20, fgChar2, "S5", "VISUAL")).andExpect(status().isBadRequest());
        lines(p, line("x", null, 10, fgChar2, "S5", "VISUAL"), line("x", null, 20, fgChar1, "S5", "VISUAL")).andExpect(status().isBadRequest());
        lines(p, "null").andExpect(status().isBadRequest());
        lines(p, line("a b", null, 10, fgChar2, "S5", "VISUAL")).andExpect(status().isBadRequest());
        // 200 행 경계 — 201 은 400 (특성이 모자라 200 은 순번·특성 중복 400 이 아니라 칸 오류로 끝까지 검사됨을 본다)
        List<String> many = new ArrayList<>();
        for (int i = 1; i <= 201; i++) {
            many.add(line("k" + i, null, i, fgChar1, "S5", "VISUAL"));
        }
        lines(p, many.toArray(String[]::new)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors").doesNotExist());
        lines(p, many.subList(0, 200).toArray(String[]::new)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.length()").value(200));
        assertThat(accounts.query(comp, "select count(*) from qms_insp_plan_line where insp_plan_id = ?", Long.class, p)).isEqualTo(1);
        // 낡은 머리 버전 → 409
        putJson(IP + "/" + p + "/lines?rowVer=0", "{\"lines\":[]}").andExpect(status().isConflict());
    }

    @Test
    void 사용중지_특성_샘플링은_새_참조만_막고_든_행은_저장되며_확정이_알린다_새리비전은_복사() throws Exception {
        long p = plan("FINAL", "FG1", null, null);
        lines(p, line("a", null, 10, fgChar1, "S5", "CALIPER")).andExpect(status().isOk());
        long la = lineId(p, fgChar1);
        accounts.exec(comp, "update qms_char set use_yn = 'N' where char_id = ?", fgChar1);
        accounts.exec(comp, "update qms_sampling_plan set use_yn = 'N' where plan_cd = 'S5'");
        accounts.exec(comp, "insert into sys_comp_code (comp_cd, grp_cd, code, code_nm, sort_no, created_by, updated_by) "
                + "values (?, 'GAUGE_CLASS', 'LASER', '레이저 변위계', 99, 't', 't')", comp);
        // 이미 든 행은 그대로 저장(방법만 고침) / 새로 고르면 INACTIVE
        lines(p, "{\"rowKey\":\"L" + la + "\",\"lineId\":" + la + ",\"lineNo\":10,\"charId\":" + fgChar1
                + ",\"samplingPlanCd\":\"S5\",\"gaugeClass\":\"laser\",\"methodNm\":\"새 방법\"}").andExpect(status().isOk());
        lines(p, line("L" + la, la, 10, fgChar1, "S5", "LASER"), line("n", null, 20, fgChar2, "S5", "VISUAL"))
                .andExpect(jsonPath("$.errors[?(@.rowKey=='n')].code").value(hasItem("INACTIVE")));
        action(p, "release").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.rowKey=='L" + la + "')].field").value(hasItems("charId", "samplingPlanCd")));
        accounts.exec(comp, "update qms_char set use_yn = 'Y' where char_id = ?", fgChar1);
        accounts.exec(comp, "update qms_sampling_plan set use_yn = 'Y' where plan_cd = 'S5'");
        action(p, "release").andExpect(status().isOk());
        // 회사 계측기 코드를 사용 중지해도 새 리비전 복사는 된다(같은 대상의 다른 리비전이 쓰던 참조), 확정 검사는 알린다
        accounts.exec(comp, "update sys_comp_code set use_yn = 'N' where code = 'LASER'");
        accounts.exec(comp, "update qms_char set use_yn = 'N' where char_id = ?", fgChar1);
        long p2 = id(action(p, "new-revision"));
        mvc.perform(get(IP + "/" + p2).with(admin)).andExpect(jsonPath("$.data.lines[0].gaugeClass").value("LASER"))
                .andExpect(jsonPath("$.data.lines[0].charUseYn").value("N"));
        action(p2, "release").andExpect(jsonPath("$.errors[*].field").value(hasItems("charId", "gaugeClass")));
    }

    // ---------- 멱등·권한 ----------

    @Test
    void 같은_멱등키로_두번_만들면_409_권한별_403() throws Exception {
        String body = "{\"planCd\":\"IDEM\",\"planNm\":\"x\",\"method\":\"ALL\"}";
        mvc.perform(json(post(SP), body).header("X-Idempotency-Key", "qms-sp-0001").with(admin)).andExpect(status().isOk());
        mvc.perform(json(post(SP), body.replace("IDEM", "IDEM2")).header("X-Idempotency-Key", "qms-sp-0001").with(admin))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DUPLICATE_REQUEST"));
        long p = releasedPlan();
        long reader = accounts.newUser(comp, "reader1", PW);
        accounts.grant(comp, reader, "READER", "qms-insp-plan", "SEARCH");
        accounts.grant(comp, reader, "READER", "qms-sampling", "SEARCH");
        RequestPostProcessor rd = accounts.as(comp, reader, "reader1");
        mvc.perform(get(IP + "/" + p).with(rd)).andExpect(status().isOk());
        mvc.perform(json(post(IP), "{\"inspType\":\"FINAL\",\"itemCd\":\"FG1\"}").with(rd)).andExpect(status().isForbidden());
        mvc.perform(json(post(IP + "/" + p + "/new-revision"), "{\"rowVer\":1}").with(rd)).andExpect(status().isForbidden());
        mvc.perform(json(post(IP + "/" + p + "/obsolete"), "{\"rowVer\":1}").with(rd)).andExpect(status().isForbidden());
        mvc.perform(json(post(IP + "/" + p + "/release"), "{\"rowVer\":1}").with(rd)).andExpect(status().isForbidden());
        mvc.perform(json(post(IP + "/export"), "{}").with(rd)).andExpect(status().isForbidden());
        mvc.perform(json(post(SP), body.replace("IDEM", "R1")).with(rd)).andExpect(status().isForbidden());
        mvc.perform(json(post(SP + "/preview"), "{\"form\":{\"method\":\"ALL\"},\"lotQty\":3}").with(rd)).andExpect(status().isOk());
        mvc.perform(json(post(SP + "/export"), "{}").with(rd)).andExpect(status().isForbidden());
        // 검사계획 권한만 있어도 행 샘플링 선택지는 보인다, 샘플링 화면 API 는 403
        long planner = accounts.newUser(comp, "planner1", PW);
        accounts.grant(comp, planner, "PLANNER", "qms-insp-plan", "SEARCH", "SAVE");
        RequestPostProcessor pl = accounts.as(comp, planner, "planner1");
        mvc.perform(get(IP + "/sampling-plans").with(pl)).andExpect(status().isOk());
        mvc.perform(get(SP).with(pl)).andExpect(status().isForbidden());
        mvc.perform(post("/api/platform/import/templates/QMS_AQL_TABLE/file").with(org.springframework.security.test.web.servlet
                .request.SecurityMockMvcRequestPostProcessors.csrf()).with(pl)).andExpect(status().isForbidden());
    }

    @Test
    void 입력_경계_목록_조건_조작_리비전_형식() throws Exception {
        mvc.perform(get(IP).param("inspType", "HACK").with(admin)).andExpect(status().isBadRequest());
        mvc.perform(get(IP).param("status", "x").with(admin)).andExpect(status().isBadRequest());
        mvc.perform(get(IP).param("sort", "rev;drop table x,asc").with(admin)).andExpect(status().isBadRequest());
        mvc.perform(get(SP).param("method", "' or 1=1").with(admin)).andExpect(status().isBadRequest());
        mvc.perform(get(SP).param("keyword", "%_\\").with(admin)).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
        mvc.perform(get("/api/qms/aql").param("stdCd", "../x").with(admin)).andExpect(status().isBadRequest());
        postJson(IP, "{\"inspType\":\"FINAL\",\"itemCd\":\"FG1\",\"rev\":\"toolong\",\"validFrom\":\"3000-01-01\"}")
                .andExpect(jsonPath("$.errors[*].field").value(hasItems("rev", "validFrom")));
        postJson(IP, "{\"inspType\":\"FINAL\",\"itemCd\":\"FG1\",\"procCd\":\"NOPE\",\"altNo\":100}")
                .andExpect(jsonPath("$.errors[*].field").value(hasItems("procCd", "altNo")));
        accounts.exec(comp, "update mst_item set use_yn = 'N' where item_cd = 'RM2'");
        postJson(IP, "{\"inspType\":\"FINAL\",\"itemCd\":\"RM2\"}").andExpect(jsonPath("$.errors[0].code").value("INACTIVE"));
        long p = id(postJson(IP, "{\"inspType\":\"FINAL\",\"itemCd\":\"FG1\",\"rev\":\"x1\"}"));
        mvc.perform(get(IP + "/" + p).with(admin)).andExpect(jsonPath("$.data.plan.rev").value("X1"));
        mvc.perform(get(IP + "/effective").param("itemCd", "FG1").param("inspType", "NOPE").with(admin)).andExpect(status().isBadRequest());
    }
}
