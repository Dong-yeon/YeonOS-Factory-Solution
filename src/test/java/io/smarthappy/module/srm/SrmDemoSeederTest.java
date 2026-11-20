package io.smarthappy.module.srm;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import io.smarthappy.module.demo.DemoSeedSupport;
import io.smarthappy.support.DemoCompanies;
import io.smarthappy.support.DemoCompanies.Demo;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * SRM 데모 시더(local·demo 빈 — 흐름 빈을 직접 부른다): 지난 60일 관통 흐름, 다시 돌려도 그대로(멱등), 기준정보 없으면 아무것도 안 함,
 * A사 실행이 B사를 건드리지 않음, 원장 두 시각(발생 = 지난 날, 기록 = 지금).
 */
class SrmDemoSeederTest extends PostgresTestBase {

    @Autowired
    TestAccounts accounts;

    @Autowired
    DemoCompanies demos;

    @Autowired
    SrmDemoFlow flow;

    private int n(String comp, String sql, Object... args) {
        return accounts.query(comp, sql, Integer.class, args);
    }

    private LocalDate today(String comp) {
        return accounts.query(comp, "select " + TestAccounts.COMP_TODAY, LocalDate.class);
    }

    @Test
    void 지난_60일에_발주부터_평가까지_흐름을_넣고_다시_돌려도_늘지_않는다() {
        Demo d = demos.seeded("SDM");
        String c = d.comp();
        assertThat(flow.seed(c, d.admin())).isEqualTo(SrmDemoFlow.POS.size());

        // 협력사 4곳 등록·인증서·공급 품목·단가
        assertThat(n(c, "select count(*) from srm_supplier")).isEqualTo(4);
        assertThat(n(c, "select count(*) from srm_supplier_cert where expires_on <= " + TestAccounts.COMP_TODAY + " + 60"))
                .as("인증서 만료 임박 1곳").isEqualTo(1);
        // 발주 상태가 골고루 — 작성 중(결재 상신 대기)·발행(회신 대기)·회신·입고 중·입고 완료·마감
        assertThat(n(c, "select count(*) from srm_po")).isEqualTo(17);
        assertThat(n(c, "select count(*) from srm_po where status = 'DRAFT'")).isEqualTo(1);
        assertThat(n(c, "select count(*) from srm_po where status = 'ISSUED'")).isEqualTo(1);
        assertThat(n(c, "select count(*) from srm_po where status = 'ACKED'")).isEqualTo(2);
        assertThat(n(c, "select count(*) from srm_po where status = 'PARTIAL_RECEIVED'")).isEqualTo(1);
        assertThat(n(c, "select count(*) from srm_po where status = 'CLOSED'")).isEqualTo(3);
        assertThat(n(c, "select count(*) from srm_po where status = 'RECEIVED'")).isEqualTo(9);
        // 발주일은 지난 60일에 흩어져 있고(오늘 몰림 없음), 채번 연월도 그날 기준
        LocalDate today = today(c);
        assertThat(accounts.query(c, "select min(po_date) from srm_po", LocalDate.class)).isEqualTo(today.minusDays(58));
        assertThat(n(c, "select count(distinct po_date) from srm_po")).isEqualTo(17);
        assertThat(n(c, "select count(*) from srm_po where po_date > " + TestAccounts.COMP_TODAY)).isZero();
        // 회신·ASN(늦은 ASN 1건)·입고 이력
        assertThat(n(c, "select count(*) from srm_po_ack")).isEqualTo(15);
        assertThat(n(c, "select count(*) from srm_asn where status <> 'CANCELLED'")).isEqualTo(8);
        assertThat(n(c, "select count(*) from srm_asn where status = 'SENT' and eta_date < " + TestAccounts.COMP_TODAY))
                .as("도착 예정일 지난 ASN").isEqualTo(1);
        assertThat(n(c, "select count(*) from srm_po_receipt where occurred_on > " + TestAccounts.COMP_TODAY)).isZero();
        assertThat(n(c, "select count(*) from srm_po_receipt")).isEqualTo(16);

        // 원장: 구매 입고는 발생 = 지난 날(입고일), 기록 = 넣은 지금 — 수정 없이 추가만
        assertThat(n(c, "select count(*) from inv_trx where trx_type = 'RCV_PO'")).isEqualTo(16);
        assertThat(n(c, "select count(*) from inv_trx where trx_type = 'RCV_PO' and occurred_at >= recorded_at - interval '1 day'"))
                .as("발생 시각이 지난 날").isZero();

        // 클레임 2: 종결 1(회신 있음) · 보냄 1(비용 청구, 회신 대기) — 근거 NCR 은 그 협력사 입고 LOT
        assertThat(n(c, "select count(*) from srm_claim where status = 'CLOSED' and answer_cause is not null")).isEqualTo(1);
        assertThat(n(c, "select count(*) from srm_claim where status = 'SENT' and charge_amt = 350000")).isEqualTo(1);
        assertThat(n(c, "select count(*) from srm_claim_ncr")).isEqualTo(2);

        // 평가: 템플릿 확정 1 · 평가 확정 2 · 작성 중 1
        assertThat(n(c, "select count(*) from srm_eval_tmpl where status = 'RELEASED'")).isEqualTo(1);
        assertThat(n(c, "select count(*) from srm_eval where status = 'CONFIRMED' and grade_cd is not null")).isEqualTo(2);
        assertThat(n(c, "select count(*) from srm_eval where status = 'DRAFT'")).isEqualTo(1);

        // 결재 기준 금액: 회사 값이 없어서 3천만 원으로
        assertThat(accounts.query(c, "select policy_value from sys_policy where policy_key = 'SRM_PO_APPROVAL_MIN_AMT'", String.class))
                .isEqualTo(SrmDemoFlow.APPROVAL_MIN_AMT);

        // 다시 돌리면 아무것도 안 한다
        assertThat(flow.seed(c, d.admin())).isZero();
        assertThat(n(c, "select count(*) from srm_po")).isEqualTo(17);
        assertThat(n(c, "select count(*) from inv_trx where trx_type = 'RCV_PO'")).isEqualTo(16);
        assertThat(n(c, "select count(*) from srm_claim")).isEqualTo(2);
    }

    @Test
    void 기준정보_데모가_없으면_아무것도_넣지_않는다() {
        Demo d = demos.empty("SDE");
        assertThat(flow.seed(d.comp(), d.admin())).isZero();
        assertThat(n(d.comp(), "select count(*) from srm_supplier")).isZero();
        assertThat(n(d.comp(), "select count(*) from sys_policy where policy_key = 'SRM_PO_APPROVAL_MIN_AMT'")).isZero();
        assertThat(n(d.comp(), "select count(*) from sys_user where login_id = ?", DemoSeedSupport.APPROVER_LOGIN)).isZero();
    }

    @Test
    void A사에_넣어도_B사_데이터는_그대로이고_B사_순번은_따로_간다() {
        Demo a = demos.seeded("SDA");
        Demo b = demos.seeded("SDB");
        flow.seed(a.comp(), a.admin());
        assertThat(n(b.comp(), "select count(*) from srm_po")).isZero();
        assertThat(n(b.comp(), "select count(*) from srm_supplier")).isZero();
        assertThat(n(b.comp(), "select count(*) from inv_trx where trx_type = 'RCV_PO'")).isZero();
        assertThat(n(b.comp(), "select count(*) from srm_claim")).isZero();
        // B사도 넣으면 B사 것만 — 발주 번호 같은 모양이어도 회사마다 따로
        flow.seed(b.comp(), b.admin());
        assertThat(n(a.comp(), "select count(*) from srm_po")).isEqualTo(17);
        assertThat(n(b.comp(), "select count(*) from srm_po")).isEqualTo(17);
        assertThat(n(a.comp(), "select count(*) from srm_po where comp_cd <> ?", a.comp())).isZero();
    }
}
