package io.smarthappy.module.qms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.List;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.IllegalTransactionStateException;

import io.smarthappy.module.qms.link.CapaPort;
import io.smarthappy.module.qms.link.CapaPort.InternalRequest;
import io.smarthappy.module.qms.link.DocLookup;
import io.smarthappy.module.qms.link.DocLookup.DocRevView;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.security.LoginUser;
import io.smarthappy.platform.tenant.TenantRunner;

/**
 * QMS 공개 계약 module.qms.link (docs/29_P3 14장) — DocLookup(리비전·문서 번호, 예외 없음·회사 격리)·CapaPort(INTERNAL 8D 만들기 MANDATORY·조회).
 * PMS PM6 이 QMS 서비스를 직접 부르던 것을 이 계약으로 바꾸기 위한 것.
 */
class QmsLinkContractTest extends QmsTestBase {

    static final String D = "/api/qms/docs";

    @Autowired
    TenantRunner runner;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    DocLookup docs;

    @Autowired
    CapaPort capas;

    @BeforeEach
    void linkSetUp() {
        accounts.grant(comp, userId, "QA", "qms-doc", "SEARCH", "SAVE", "APPROVE", "DELETE");
    }

    <T> T as(String c, long uid, String loginId, Supplier<T> work) {
        LoginUser user = new LoginUser(uid, c, loginId, loginId, "ko", false);
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                user, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        try {
            return runner.call(c, work);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void 문서_조회는_대소문자_무시_사용중만_예외없이_빈값_회사격리() throws Exception {
        long docId = id(postJson(D, "{\"docNo\":\"wi-777\",\"docType\":\"WI\",\"docNm\":\"작업 표준\"}"));
        List<DocRevView> revs = runner.call(comp, () -> docs.revsByDocNo("WI-777"));
        assertThat(revs).isNotEmpty();
        assertThat(revs).allSatisfy(r -> assertThat(r.docId()).isEqualTo(docId));
        assertThat(revs.get(0).docNo()).isEqualToIgnoringCase("WI-777");
        long revId = revs.get(0).docRevId();
        assertThat(runner.call(comp, () -> docs.rev(revId))).get().extracting(DocRevView::docNm, DocRevView::status)
                .containsExactly("작업 표준", revs.get(0).status());
        // 없는 id·번호 = 빈 값이고 같은 트랜잭션의 쓰기를 망치지 않는다(rollback-only 아님)
        Integer after = runner.call(comp, () -> {
            assertThat(docs.rev(999999999L)).isEmpty();
            assertThat(docs.revsByDocNo("NO-SUCH")).isEmpty();
            assertThat(docs.revsByDocNo(" ")).isEmpty();
            return jdbc.update("update mst_doc set updated_by = 'link' where doc_id = ?", docId);
        });
        assertThat(after).isEqualTo(1);
        assertThat(accounts.query(comp, "select updated_by from mst_doc where doc_id = ?", String.class, docId)).isEqualTo("link");
        // 사용 중지 문서는 번호로 안 나온다(리비전 id 로는 그대로)
        accounts.exec(comp, "update mst_doc set use_yn = 'N', updated_by = 't' where doc_id = ?", docId);
        assertThat(runner.call(comp, () -> docs.revsByDocNo("wi-777"))).isEmpty();
        assertThat(runner.call(comp, () -> docs.rev(revId))).isPresent();
        // A사로 B사
        String other = accounts.newComp("QM");
        assertThat(runner.call(other, () -> docs.rev(revId))).isEmpty();
        accounts.exec(comp, "update mst_doc set use_yn = 'Y', updated_by = 't' where doc_id = ?", docId);
        assertThat(runner.call(other, () -> docs.revsByDocNo("WI-777"))).isEmpty();
    }

    @Test
    void 시정조치는_부르는쪽_트랜잭션에서만_만들고_조회는_예외없이_회사격리() {
        // 트랜잭션 밖 = MANDATORY 위반
        assertThat(catchThrowable(() -> capas.createInternal(new InternalRequest("x", null, userId, null))))
                .isInstanceOf(IllegalTransactionStateException.class);
        long id = as(comp, userId, "admin1", () -> capas.createInternal(new InternalRequest("PMS 이슈 승격", "FG1", userId, "PMS ISS-1 / 문제")));
        assertThat(accounts.query(comp, "select source || ':' || status || ':' || d2_problem from qms_capa where capa_id = ?", String.class, id))
                .isEqualTo("INTERNAL:OPEN:PMS ISS-1 / 문제");
        assertThat(runner.call(comp, () -> capas.capa(id))).get().extracting(CapaPort.CapaView::title, CapaPort.CapaView::status)
                .containsExactly("PMS 이슈 승격", "OPEN");
        // 4,000자 넘는 문제 서술은 잘라서
        long id2 = as(comp, userId, "admin1", () -> capas.createInternal(new InternalRequest("긴 문제", null, userId, "가".repeat(4100))));
        assertThat(accounts.query(comp, "select length(d2_problem) from qms_capa where capa_id = ?", Integer.class, id2)).isEqualTo(4000);
        // 입력 오류 = 칸 오류(부르는 쪽도 실패), 없는 품목
        Throwable t = catchThrowable(() -> as(comp, userId, "admin1", () -> capas.createInternal(new InternalRequest("x", "NO-ITEM", userId, null))));
        assertThat(t).isInstanceOf(BusinessException.class);
        assertThat(runner.call(comp, () -> capas.capa(999999999L))).isEmpty();
        String other = accounts.newComp("QM");
        assertThat(runner.call(other, () -> capas.capa(id))).isEmpty();
        // 빈 없을 때(QMS 없는 설치본)
        assertThat(DocLookup.NONE.rev(1)).isEmpty();
        assertThat(CapaPort.NONE.capa(1)).isEmpty();
        assertThat(catchThrowable(() -> CapaPort.NONE.createInternal(new InternalRequest("x", null, 1, null)))).isInstanceOf(IllegalStateException.class);
    }
}
