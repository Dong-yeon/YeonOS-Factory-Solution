package io.smarthappy.module.qms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import io.smarthappy.module.qms.link.ChangeLookup;
import io.smarthappy.module.qms.link.ChangeLookup.ChangeDetail;
import io.smarthappy.platform.tenant.TenantRunner;

/**
 * QMS 공개 계약 4M 변경 세부 조회 (docs/29_P3 20장 B안) — PMS APQP 가 PLM 계약으로 고른 ECO id 의 4M 세부만 읽는다.
 * 세부 있는 ECO 만·ECO id 순·유형 저장 순서·영향 특성/검사계획 행 번호 순 / 잘못된 인자(null·음수·중복) 빈 값·예외 없음 /
 * 상한 / A사로 B사 / NONE.
 */
class ChangeLookupTest extends QmsTestBase {

    static final String C = "/api/qms/changes";

    @Autowired
    ChangeLookup lookup;

    @Autowired
    TenantRunner runner;

    @Autowired
    PlatformTransactionManager txm;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void lookupSetUp() {
        accounts.grant(comp, userId, "QA", "qms-change", "SEARCH", "SAVE", "DELETE");
        accounts.grant(comp, userId, "QA", "plm-eco", "SEARCH", "SAVE", "APPROVE");
    }

    <T> T tx(String c, Supplier<T> s) {
        TransactionTemplate t = new TransactionTemplate(txm);
        return runner.call(c, () -> t.execute(st -> s.get()));
    }

    /** ECO 작성 중(FG1 품목 대상) */
    long eco(String c, long owner, String no) {
        long eco = accounts.query(c, """
                insert into plm_eco (comp_cd, eco_no, title, reason_cd, effective_date, owner_id, change_kind, mark_until, created_by, updated_by)
                values (?, ?, '드로잉 금형 교체', 'QUALITY', (now() at time zone 'Asia/Seoul')::date, ?, 'PROCESS_4M',
                        (now() at time zone 'Asia/Seoul')::date + 7, 't', 't') returning eco_id""", Long.class, c, no, owner);
        accounts.exec(c, """
                insert into plm_eco_target (comp_cd, eco_id, seq, target_type, item_id, created_by, updated_by)
                values (?, ?, 1, 'ITEM', (select item_id from mst_item where item_cd = 'FG1'), 't', 't')""", c, eco);
        return eco;
    }

    ResultActions save(long eco, String body) throws Exception {
        return putJson(C + "/" + eco, body);
    }

    static String row(String type, Object id) {
        return "{\"refType\":" + q(type) + ",\"refId\":" + id + ",\"impactDesc\":\"치수 재확인\"}";
    }

    static String text(ChangeDetail d) {
        return d.ecoNo() + " " + d.m4Types() + " " + d.beforeDesc() + ">" + d.afterDesc() + " r=" + d.reasonDesc() + " i=" + d.impactDesc()
                + " v=" + d.revertCond() + " c=" + d.charIds() + " p=" + d.inspPlanIds();
    }

    @Test
    void 세부있는_ECO_만_id_순_유형_영향행_순서() throws Exception {
        long p = plan("FINAL", "FG1", null, null);
        long e1 = eco(comp, userId, "ECO-CL-1");
        long e2 = eco(comp, userId, "ECO-CL-2");     // 4M 세부 없음
        long e3 = eco(comp, userId, "ECO-CL-3");
        // 영향 행: 검사계획 → 특성2 → 특성1 순으로 넣는다(행 번호 순 그대로 돌아와야 함)
        save(e1, "{\"m4Types\":[\"METHOD\",\"MACHINE\"],\"beforeDesc\":\"구 금형 #3\",\"afterDesc\":\"신 금형 #4\",\"reasonDesc\":\"마모\","
                + "\"impactDesc\":\"홀 지름 영향\",\"revertCond\":\"Cpk < 1.33\",\"chars\":[" + row("PLAN", p) + "," + row("CHAR", fgChar2) + ","
                + row("CHAR", fgChar1) + "],\"rowVer\":null}").andExpect(status().isOk());
        save(e3, "{\"m4Types\":[\"MAN\"],\"beforeDesc\":\"a\",\"afterDesc\":\"b\",\"rowVer\":null}").andExpect(status().isOk());

        List<ChangeDetail> r = tx(comp, () -> lookup.details(Arrays.asList(e3, e1, e2, null, -1L, 0L, e1)));
        assertThat(r).extracting(ChangeDetail::ecoId).containsExactly(e1, e3);
        assertThat(text(r.get(0))).isEqualTo("ECO-CL-1 [MACHINE, METHOD] 구 금형 #3>신 금형 #4 r=마모 i=홀 지름 영향 v=Cpk < 1.33 c=["
                + fgChar2 + ", " + fgChar1 + "] p=[" + p + "]");
        assertThat(text(r.get(1))).isEqualTo("ECO-CL-3 [MAN] a>b r=null i=null v=null c=[] p=[]");
        // 승인·적용 여부는 보지 않는다(B안 — PLM 계약 몫): 상신 전 작성 중 ECO 도 세부가 있으면 나온다
        assertThat(accounts.query(comp, "select status from plm_eco where eco_id = ?", String.class, e1)).isEqualTo("DRAFT");
    }

    @Test
    void 잘못된_인자는_빈값_예외없음_상한_같은트랜잭션_계속() throws Exception {
        long e1 = eco(comp, userId, "ECO-CL-9");
        save(e1, "{\"m4Types\":[\"MATERIAL\"],\"beforeDesc\":\"SPCC\",\"afterDesc\":\"SPHC\",\"rowVer\":null}").andExpect(status().isOk());
        assertThat(tx(comp, () -> lookup.details(null))).isEmpty();
        assertThat(tx(comp, () -> lookup.details(List.of()))).isEmpty();
        assertThat(tx(comp, () -> lookup.details(Arrays.asList(null, -5L)))).isEmpty();
        assertThat(tx(comp, () -> lookup.details(List.of(999_999_999L)))).isEmpty();
        // 상한: 작은 id 부터 500 개만 본다 — e1 이 가장 작으니 뒤에 가짜 id 700 개가 붙어도 남는다
        List<Long> many = new ArrayList<>(List.of(e1));
        for (long i = 1; i <= 700; i++) {
            many.add(e1 + 1_000_000 + i);
        }
        assertThat(tx(comp, () -> lookup.details(many))).hasSize(1);
        // 예외 없이 끝나 같은 트랜잭션에서 계속 쓸 수 있다
        Integer n = tx(comp, () -> {
            lookup.details(Arrays.asList(null, 1L, -1L));
            return jdbc.queryForObject("select count(*) from qms_change", Integer.class);
        });
        assertThat(n).isEqualTo(1);
    }

    @Test
    void A사로_B사_NONE() throws Exception {
        long e1 = eco(comp, userId, "ECO-CL-A");
        save(e1, "{\"m4Types\":[\"SITE\"],\"beforeDesc\":\"1공장\",\"afterDesc\":\"2공장\",\"rowVer\":null}").andExpect(status().isOk());
        String other = accounts.newComp("QM");
        fixtures(other);
        long bUser = accounts.newUser(other, "admin1", PW);
        long b1 = eco(other, bUser, "ECO-CL-B");
        accounts.exec(other, "insert into qms_change (eco_id, comp_cd, eco_no, m4_types, before_desc, after_desc, created_by, updated_by) "
                + "values (?, ?, '-', array['ENV']::varchar(10)[], 'x', 'y', 't', 't')", b1, other);
        // B사 범위로 A사 id → 빠짐, 자기 것만
        assertThat(tx(other, () -> lookup.details(List.of(e1, b1)))).extracting(ChangeDetail::ecoNo).containsExactly("ECO-CL-B");
        assertThat(tx(comp, () -> lookup.details(List.of(e1, b1)))).extracting(ChangeDetail::ecoNo).containsExactly("ECO-CL-A");
        assertThat(ChangeLookup.NONE.details(List.of(e1))).isEmpty();
    }
}
