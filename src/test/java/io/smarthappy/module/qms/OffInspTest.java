package io.smarthappy.module.qms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import io.smarthappy.module.production.link.CloseNotice.CloseContext;
import io.smarthappy.module.production.link.CloseNotice.Notice;
import io.smarthappy.module.production.link.LotLookup;
import io.smarthappy.module.qms.InspDtos.OffSide;
import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.support.TestAccounts;

/**
 * 초품(FIRST_OFF)·종품(LAST_OFF) 판정 — 같은 지시·공정 나란히 (docs/29_P3 27장, IATF 8.5.1.3).
 * 정상 짝 · 다른 지시·다른 공정·무효 빠짐 · 판정 전 PENDING · 가장 최근 판정 · 지시 없는 LOT · 종품 NG · 공정 완료 경고용 조회 · 권한 · A사로 B사
 */
class OffInspTest extends QmsTestBase {

    static final String I = "/api/qms/insps";

    @Autowired
    TenantRunner runner;

    @Autowired
    PlatformTransactionManager txm;

    @Autowired
    OffInspService offs;

    @Autowired
    QmsLastOffNotice notice;

    long item;
    long draw;
    long iqc;
    long wo1;
    long wo2;
    int lotSeq;

    @BeforeEach
    void offSetUp() throws Exception {
        accounts.grant(comp, userId, "QA", "qms-insp", "SEARCH", "SAVE", "DELETE");
        item = accounts.query(comp, "select item_id from mst_item where item_cd = 'FG1'", Long.class);
        draw = accounts.query(comp, "select proc_id from mst_proc where proc_cd = 'DRAW'", Long.class);
        iqc = accounts.query(comp, "select proc_id from mst_proc where proc_cd = 'IQC'", Long.class);
        wo1 = wo("WO-OFF-1");
        wo2 = wo("WO-OFF-2");
        for (String type : new String[] {"FIRST_OFF", "LAST_OFF"}) {
            for (String proc : new String[] {"DRAW", "IQC"}) {
                long p = plan(type, "FG1", null, proc);
                lines(p, line("b", null, 10, fgChar2, "S5", "VISUAL")).andExpect(status().isOk());
                action(p, "release").andExpect(status().isOk());
            }
        }
    }

    long wo(String no) throws Exception {
        return QmsStartGateTest.owner("insert into prd_wo (comp_cd, wo_no, plant_cd, item_id, plan_qty, unit_cd, plan_start, plan_end, routing_id, "
                + "status, released_at, created_by, updated_by) values (?, ?, 'P1', ?, 100, 'EA', " + TestAccounts.COMP_TODAY + ", "
                + TestAccounts.COMP_TODAY + ", 0, 'RELEASED', now(), 't', 't') returning wo_id", comp, no, item);
    }

    /** 생산 LOT(지시) 또는 입고 LOT(지시 없음) → LOT 번호 */
    String lot(Long wo) throws Exception {
        String no = "L-OFF-" + (++lotSeq);
        QmsStartGateTest.owner("insert into lot_master (comp_cd, lot_no, item_id, origin, qty_created, unit_cd, wo_id, occurred_at, created_by, "
                + "updated_by) values (?, ?, ?, ?, 10, 'EA', ?, now(), 't', 't') returning lot_id", comp, no, item,
                wo == null ? "RECEIVED" : "PRODUCED", wo);
        return no;
    }

    long lotId(String no) {
        return accounts.query(comp, "select lot_id from lot_master where lot_no = ?", Long.class, no);
    }

    /** 초품·종품 검사 하나 — ok = null 이면 판정 전(측정도 없음), true/false = 계수 5개 Y/N 뒤 판정 확정 */
    long insp(String lotNo, String type, String procCd, Boolean ok) throws Exception {
        long id = id(postJson(I, "{\"lotNo\":" + q(lotNo) + ",\"inspType\":" + q(type) + ",\"procCd\":" + q(procCd) + "}"));
        if (ok == null) {
            return id;
        }
        long line = accounts.query(comp, "select insp_line_id from qms_insp_line where insp_id = ?", Long.class, id);
        StringBuilder e = new StringBuilder();
        for (int i = 1; i <= 5; i++) {
            e.append(i == 1 ? "" : ",").append("{\"sampleNo\":").append(i).append(",\"okYn\":").append(q(ok ? "Y" : "N")).append("}");
        }
        postJson(I + "/" + id + "/meas", "{\"inspLineId\":" + line + ",\"entries\":[" + e + "]}").andExpect(status().isOk());
        postJson(I + "/" + id + "/judge", "{\"rowVer\":" + accounts.query(comp, "select row_ver from qms_insp where insp_id = ?", Integer.class, id)
                + "}").andExpect(status().isOk());
        return id;
    }

    ResultActions pair(RequestPostProcessor who, long insp) throws Exception {
        return mvc.perform(get(I + "/" + insp + "/off-pair").with(who));
    }

    OffSide lastOff(String c, long proc, List<Long> lots) {
        TransactionTemplate tx = new TransactionTemplate(txm);
        return runner.call(c, () -> tx.execute(s -> offs.lastOff(proc, lots)));
    }

    @Test
    void 같은_지시_공정의_초품_종품을_나란히_다른_지시_다른_공정_무효는_빠진다() throws Exception {
        String a = lot(wo1);
        String b = lot(wo1);
        String other = lot(wo2);
        long first = insp(a, "FIRST_OFF", "DRAW", true);
        long last = insp(b, "LAST_OFF", "DRAW", true);
        insp(other, "LAST_OFF", "DRAW", false);           // 다른 지시
        insp(b, "LAST_OFF", "IQC", false);                // 같은 지시 다른 공정
        long voided = insp(a, "LAST_OFF", "DRAW", false); // 무효 처리 → 빠짐
        postJson(I + "/" + voided + "/void", "{\"rowVer\":" + accounts.query(comp, "select row_ver from qms_insp where insp_id = ?", Integer.class,
                voided) + ",\"reason\":\"잘못 잼\"}").andExpect(status().isOk());
        pair(admin, first).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.woId").value(wo1))
                .andExpect(jsonPath("$.data.firstOff.state").value("OK"))
                .andExpect(jsonPath("$.data.firstOff.rows[*].inspId").value(Matchers.contains((int) first)))
                .andExpect(jsonPath("$.data.lastOff.state").value("OK"))
                .andExpect(jsonPath("$.data.lastOff.rows[*].inspId").value(Matchers.contains((int) last)));
        // 종품 쪽에서 열어도 같은 짝
        pair(admin, last).andExpect(jsonPath("$.data.firstOff.rows[*].inspId").value(Matchers.contains((int) first)));
    }

    @Autowired
    InspMapper inspMapper;

    @Autowired
    LotLookup lotLookup;

    /** 검사 하나를 n 개 베껴 다른 LOT 에 — 트리거 끄고(owner·replica) 대량, 시각은 원본 뒤로 1초씩 */
    void copies(long tmplInspId, long lotId, int n) throws Exception {
        String cols = accounts.query(comp, "select string_agg(column_name, ',' order by ordinal_position) from information_schema.columns "
                + "where table_name = 'qms_insp' and column_name not in ('insp_id', 'insp_no', 'lot_id', 'occurred_at')", String.class);
        QmsStartGateTest.owner("with x as (insert into qms_insp (" + cols + ", insp_no, lot_id, occurred_at) select " + cols
                + ", 'X-' || g, ?, now() + g * interval '1 second' from qms_insp, generate_series(1, ?) g where insp_id = ? returning 1) "
                + "select count(*) from x", lotId, n, tmplInspId);
    }

    @Test
    void 짝은_지시_LOT_으로_먼저_다른_지시_검사가_200건_넘어도_찾고_LOT_못받으면_최근_검사로_대체_잘림_표시() throws Exception {
        String a = lot(wo1);
        long first = insp(a, "FIRST_OFF", "DRAW", true);
        String other = lot(wo2);
        long tmpl = insp(other, "FIRST_OFF", "DRAW", null);
        copies(tmpl, lotId(other), 201);   // 같은 품목·공정에 다른 지시 검사 202건 — 모두 첫 검사보다 나중
        // ① 지시 LOT 으로: 최근 200건 상한과 무관하게 옛 짝을 찾는다
        pair(admin, first).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.basis").value("WO_LOTS"))
                .andExpect(jsonPath("$.data.truncated").value(false))
                .andExpect(jsonPath("$.data.firstOff.state").value("OK"))
                .andExpect(jsonPath("$.data.firstOff.rows[*].inspId").value(Matchers.contains((int) first)));
        // ② LOT 계약 구현이 없는 설치본(lotsOfWo 기본 = 빈 목록) — 최근 200건을 지시로 거름: 옛 짝이 빠지고 잘림 표시
        LotLookup legacy = new LotLookup() {
            @Override
            public List<LotLookup.LotView> lots(java.util.Collection<Long> ids) {
                return lotLookup.lots(ids);
            }

            @Override
            public java.util.Optional<LotLookup.LotView> byNo(String no) {
                return lotLookup.byNo(no);
            }
        };
        org.springframework.beans.factory.support.DefaultListableBeanFactory bf = new org.springframework.beans.factory.support.DefaultListableBeanFactory();
        bf.registerSingleton("legacyLot", legacy);
        OffInspService old = new OffInspService(inspMapper, bf.getBeanProvider(LotLookup.class));
        TransactionTemplate tx = new TransactionTemplate(txm);
        InspDtos.OffPair p = runner.call(comp, () -> tx.execute(st -> old.pair(first)));
        assertThat(p.basis()).isEqualTo("RECENT");
        assertThat(p.truncated()).isTrue();
        assertThat(p.firstOff().state()).isEqualTo("NONE");
        // 상한에 안 걸리면 대체 방식도 짝을 찾고 잘림 표시 없음
        String c = lot(wo1);
        long last = insp(c, "LAST_OFF", "IQC", true);
        InspDtos.OffPair q = runner.call(comp, () -> tx.execute(st -> old.pair(last)));
        assertThat(q.basis()).isEqualTo("RECENT");
        assertThat(q.truncated()).isFalse();
        assertThat(q.lastOff().rows()).extracting(InspDtos.OffRow::getInspId).containsExactly(last);
    }

    @Test
    void 판정_전은_PENDING_가장_최근_판정이_상태_종품_NG() throws Exception {
        String a = lot(wo1);
        String b = lot(wo1);
        long first = insp(a, "FIRST_OFF", "DRAW", null);
        pair(admin, first).andExpect(jsonPath("$.data.firstOff.state").value("PENDING"))
                .andExpect(jsonPath("$.data.lastOff.state").value("NONE"));
        insp(b, "LAST_OFF", "DRAW", true);
        insp(b, "LAST_OFF", "DRAW", false);               // 더 늦게 판정한 NG
        pair(admin, first).andExpect(jsonPath("$.data.lastOff.state").value("NG"))
                .andExpect(jsonPath("$.data.lastOff.rows.length()").value(2));
    }

    @Test
    void 지시_없는_LOT_은_짝을_찾지_않는다() throws Exception {
        long received = insp(lot(null), "FIRST_OFF", "DRAW", true);
        pair(admin, received).andExpect(status().isOk()).andExpect(jsonPath("$.data.woId").doesNotExist())
                .andExpect(jsonPath("$.data.firstOff.state").value("NONE")).andExpect(jsonPath("$.data.lastOff.state").value("NONE"));
    }

    /** 공정 완료 경고(27장)가 쓸 조회 — 그 공정·산출 LOT 의 종품만. 없음·판정 전·NG·다른 공정 */
    @Test
    void 공정_완료_경고용_종품_조회는_그_공정_산출_LOT_만() throws Exception {
        String a = lot(wo1);
        String b = lot(wo1);
        assertThat(lastOff(comp, draw, List.of(lotId(a), lotId(b))).state()).isEqualTo("NONE");
        assertThat(lastOff(comp, draw, List.of()).state()).isEqualTo("NONE");
        insp(a, "LAST_OFF", "IQC", true);
        assertThat(lastOff(comp, draw, List.of(lotId(a), lotId(b))).state()).as("다른 공정 종품은 안 셈").isEqualTo("NONE");
        insp(b, "LAST_OFF", "DRAW", null);
        assertThat(lastOff(comp, draw, List.of(lotId(a), lotId(b))).state()).isEqualTo("PENDING");
        insp(b, "LAST_OFF", "DRAW", false);
        assertThat(lastOff(comp, draw, List.of(lotId(a), lotId(b))).state()).isEqualTo("NG");
    }

    @Test
    void 권한_A사로_B사() throws Exception {
        String a = lot(wo1);
        long first = insp(a, "FIRST_OFF", "DRAW", true);
        insp(lot(wo1), "LAST_OFF", "DRAW", true);
        // 권한 없음 → 403
        long u2 = accounts.newUser(comp, "off2", PW);
        pair(accounts.as(comp, u2, "off2"), first).andExpect(status().isForbidden());
        // B사 사용자가 A사 검사 id → 404, B사 범위의 종품 조회는 A사 LOT 을 못 본다
        String other = accounts.newComp("QF");
        long bu = accounts.newUser(other, "qf1", PW);
        accounts.grant(other, bu, "QA", "qms-insp", "SEARCH", "SAVE");
        pair(accounts.as(other, bu, "qf1"), first).andExpect(status().isNotFound());
        assertThat(lastOff(other, draw, List.of(lotId(a))).state()).isEqualTo("NONE");
        assertThat(lastOff(comp, draw, List.of(lotId(a))).state()).as("A사 자신은 봄 — 종품은 다른 LOT").isEqualTo("NONE");
    }

    List<String> notices(String c, long proc, List<Long> lots, boolean preview) {
        TransactionTemplate tx = new TransactionTemplate(txm);
        List<Notice> got = runner.call(c, () -> tx.execute(s -> notice.notices(new CloseContext("P1", wo1, 1, proc, item, lots, preview))));
        return got.stream().map(n -> n.code() + ":" + n.messageKey() + ":" + n.args() + ":" + n.refType()).toList();
    }

    /** 공정 완료 경고(27.3, MES CloseNotice 구현) — 종품 계획 있는 공정만: 없음·판정 전·NG 는 경고, OK 는 없음. 미리보기·판정 같은 결과 */
    @Test
    void 공정_완료_경고는_종품_계획이_있는_공정만_없음_판정전_NG() throws Exception {
        String a = lot(wo1);
        String b = lot(wo1);
        List<Long> lots = List.of(lotId(a), lotId(b));
        assertThat(notices(comp, draw, lots, true)).containsExactly("QMS_LAST_PENDING:qms.close.lastNone:[]:null");
        long pending = insp(b, "LAST_OFF", "DRAW", null);
        String pendingNo = accounts.query(comp, "select insp_no from qms_insp where insp_id = ?", String.class, pending);
        assertThat(notices(comp, draw, lots, false)).containsExactly("QMS_LAST_PENDING:qms.close.lastPending:[" + pendingNo + "]:INSP");
        long ng = insp(a, "LAST_OFF", "DRAW", false);
        String ngNo = accounts.query(comp, "select insp_no from qms_insp where insp_id = ?", String.class, ng);
        assertThat(notices(comp, draw, lots, true)).as("판정 전 검사가 있어도 판정된 NG 가 상태").containsExactly("QMS_LAST_NG:qms.close.lastNg:[" + ngNo + ", " + a + "]:INSP");
        insp(a, "LAST_OFF", "DRAW", true);
        assertThat(notices(comp, draw, lots, false)).as("다시 검사해 OK").isEmpty();
        // 종품 계획 없는 공정은 경고하지 않음
        accounts.exec(comp, "insert into mst_proc (comp_cd, proc_cd, proc_nm, proc_cat, created_by, updated_by) values (?, 'WELD', '용접', 'PRESS', 't', 't')", comp);
        long weld = accounts.query(comp, "select proc_id from mst_proc where proc_cd = 'WELD'", Long.class);
        assertThat(notices(comp, weld, lots, true)).isEmpty();
        // B사 범위: A사 계획·검사를 못 봄 → 계획 없음 → 경고 없음(남의 데이터로 경고하지 않음)
        String other = accounts.newComp("QG");
        assertThat(notices(other, draw, lots, true)).isEmpty();
    }

}
