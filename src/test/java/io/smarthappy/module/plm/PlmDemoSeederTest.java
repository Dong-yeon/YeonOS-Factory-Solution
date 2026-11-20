package io.smarthappy.module.plm;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import io.smarthappy.module.demo.DemoSeedSupport;
import io.smarthappy.support.DemoCompanies;
import io.smarthappy.support.DemoCompanies.Demo;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * PLM 데모 시더(local·demo 빈 — 흐름 빈을 직접 부른다): ECO 4건(적용 2 · 승인됨 1 · 작성 중 1)·ECR 2·도면 리비전·4M 세부,
 * 결재는 실제 결재 기록(요청자 ≠ 결재자), 데모 결재자는 로그인 불가, 멱등, A사 실행이 B사를 건드리지 않음.
 */
class PlmDemoSeederTest extends PostgresTestBase {

    @Autowired
    TestAccounts accounts;

    @Autowired
    DemoCompanies demos;

    @Autowired
    PlmDemoFlow flow;

    private int n(String comp, String sql, Object... args) {
        return accounts.query(comp, sql, Integer.class, args);
    }

    @Test
    void ECO_도면_리비전_4M_을_넣고_다시_돌려도_늘지_않는다() {
        Demo d = demos.seeded("PDM");
        String c = d.comp();
        assertThat(flow.seed(c, d.admin())).isEqualTo(4);

        assertThat(n(c, "select count(*) from plm_eco where status = 'RELEASED'")).isEqualTo(2);
        assertThat(n(c, "select count(*) from plm_eco where status = 'APPROVED'")).as("적용 대기").isEqualTo(1);
        assertThat(n(c, "select count(*) from plm_eco where status = 'DRAFT' and change_kind = 'TEMP'")).isEqualTo(1);
        assertThat(n(c, "select count(*) from plm_ecr where status = 'ACCEPTED' and eco_id is not null")).isEqualTo(1);
        assertThat(n(c, "select count(*) from plm_ecr where status = 'OPEN'")).isEqualTo(1);
        assertThat(n(c, "select count(*) from plm_eco where cust_approval_status = 'APPROVED'")).isEqualTo(1);

        // 도면 A: ECO 적용이 A1 배포·A0 폐기 / 도면 B: A1 은 승인된 ECO 대상(작성 중 그대로)
        assertThat(accounts.query(c, """
                select string_agg(r.rev || ':' || r.status, ',' order by r.rev) from mst_doc_rev r join mst_doc d on d.doc_id = r.doc_id
                 where d.doc_no = ?""", String.class, PlmDemoFlow.DWG_A)).isEqualTo("A0:OBSOLETE,A1:RELEASED");
        assertThat(accounts.query(c, """
                select string_agg(r.rev || ':' || r.status, ',' order by r.rev) from mst_doc_rev r join mst_doc d on d.doc_id = r.doc_id
                 where d.doc_no = ?""", String.class, PlmDemoFlow.DWG_B)).isEqualTo("A0:RELEASED,A1:DRAFT");

        // 4M 연결: 4M·임시 ECO 마다 QMS 4M 세부(1:1)
        assertThat(n(c, "select count(*) from qms_change q join plm_eco e on e.eco_id = q.eco_id where e.change_kind in ('PROCESS_4M', 'TEMP')"))
                .isEqualTo(2);

        // 결재: ECO 3 + 도면 A0 2 — 모두 승인, 요청자(관리자) ≠ 결재자(데모 결재자)
        assertThat(n(c, "select count(*) from apr_request where status = 'APPROVED' and requester_id = ?", d.admin().userId())).isEqualTo(5);
        assertThat(n(c, """
                select count(*) from apr_line l join sys_user u on u.user_id = l.approver_id
                 where l.status = 'APPROVED' and u.login_id = ?""", DemoSeedSupport.APPROVER_LOGIN)).isEqualTo(5);
        // 데모 결재자는 권한그룹이 없다 — 결재선에만 서고 화면·API 권한은 없음(비밀번호는 아무도 모르는 무작위 값)
        assertThat(n(c, """
                select count(*) from sys_user_auth a join sys_user u on u.user_id = a.user_id where u.login_id = ?""",
                DemoSeedSupport.APPROVER_LOGIN)).isZero();

        // 날짜: 지난 60일 안 — 유효일은 그날 기준으로 통과(지난 날 적용 2건)
        assertThat(n(c, "select count(*) from plm_eco where effective_date < " + TestAccounts.COMP_TODAY)).isEqualTo(2);

        assertThat(flow.seed(c, d.admin())).isZero();
        assertThat(n(c, "select count(*) from plm_eco")).isEqualTo(4);
        assertThat(n(c, "select count(*) from plm_ecr")).isEqualTo(2);
        assertThat(n(c, "select count(*) from sys_user where login_id = ?", DemoSeedSupport.APPROVER_LOGIN)).isEqualTo(1);
    }

    @Test
    void 기준정보_데모가_없으면_아무것도_넣지_않는다() {
        Demo d = demos.empty("PDE");
        assertThat(flow.seed(d.comp(), d.admin())).isZero();
        assertThat(n(d.comp(), "select count(*) from plm_eco")).isZero();
        assertThat(n(d.comp(), "select count(*) from mst_doc")).isZero();
        assertThat(n(d.comp(), "select count(*) from sys_user where login_id = ?", DemoSeedSupport.APPROVER_LOGIN)).isZero();
    }

    @Test
    void A사에_넣어도_B사는_그대로다() {
        Demo a = demos.seeded("PDA");
        Demo b = demos.seeded("PDB");
        flow.seed(a.comp(), a.admin());
        assertThat(n(b.comp(), "select count(*) from plm_eco")).isZero();
        assertThat(n(b.comp(), "select count(*) from plm_ecr")).isZero();
        assertThat(n(b.comp(), "select count(*) from mst_doc where doc_type = 'DRAWING'")).isZero();
        assertThat(n(b.comp(), "select count(*) from sys_user where login_id = ?", DemoSeedSupport.APPROVER_LOGIN)).isZero();
        // B사도 같은 도면 번호로 따로 들어간다(번호 유일은 회사 안)
        assertThat(flow.seed(b.comp(), b.admin())).isEqualTo(4);
        assertThat(n(a.comp(), "select count(*) from plm_eco")).isEqualTo(4);
    }
}
