package io.smarthappy.module.qms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import io.smarthappy.module.qms.link.DocPort;
import io.smarthappy.module.qms.link.DocPort.DocDraft;
import io.smarthappy.module.qms.link.DocPort.DocFile;
import io.smarthappy.module.qms.link.DocPort.DocRevRef;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.security.LoginUser;
import io.smarthappy.platform.tenant.TenantRunner;

/**
 * QMS 관리문서 쓰기 계약 DocPort (docs/29_P3 20.2, Q-t + 상위 변경 DP-5·itemId) — PMS 산출물을 작성 중 리비전으로.
 * 새 문서(채번·대상 품목·A0)·기존 문서 새 리비전(대상 품목 더함)·파일 붙임(qms-doc × 리비전) / 같은 sourceKey 멱등 /
 * 작성 중 리비전 있으면 거부·트랜잭션 함께 실패 / 입력 오류 / 결재 건너뛰지 않음(DRAFT) / A사로 B사 / 직접 SQL(키 유일·불변·형식) / NONE.
 */
class DocPortTest extends QmsTestBase {

    static final String D = "/api/qms/docs";
    static final byte[] PDF = "%PDF-1.7\n1 0 obj\n<<>>\nendobj\n".getBytes(StandardCharsets.US_ASCII);

    @Autowired
    DocPort port;

    @Autowired
    TenantRunner runner;

    long fg1;

    @BeforeEach
    void portSetUp() {
        accounts.grant(comp, userId, "QA", "qms-doc", "SEARCH", "SAVE", "APPROVE", "DELETE");
        fg1 = accounts.query(comp, "select item_id from mst_item where item_cd = 'FG1'", Long.class);
        // 결재 없이 배포(QMS 화면 경로) — 결재 정책 Y 여도 이 계약은 DRAFT 까지라 같다
        accounts.exec(comp, "insert into sys_policy (comp_cd, policy_key, policy_value, created_by, updated_by) values (?, 'QMS_DOC_APPROVAL', 'N', 't', 't')",
                comp);
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

    DocRevRef publish(DocDraft d) {
        return as(comp, userId, "admin1", () -> port.publish(d));
    }

    static List<DocFile> files(String... names) {
        return java.util.Arrays.stream(names).map(n -> new DocFile(n, PDF)).toList();
    }

    long fileCnt(long revId) {
        return accounts.query(comp, "select count(*) from sys_file where target_type = 'qms-doc' and target_id = ? and use_yn = 'Y'", Long.class,
                String.valueOf(revId));
    }

    BusinessException fail(DocDraft d) {
        Throwable t = catchThrowable(() -> publish(d));
        assertThat(t).isInstanceOf(BusinessException.class);
        return (BusinessException) t;
    }

    @Test
    void 새_문서는_채번_대상품목_A0_DRAFT_파일_붙임_같은키는_그대로() throws Exception {
        DocRevRef r = publish(new DocDraft("PMS:DELIV:1:1", null, "CP", "관리계획서 FG1", fg1, "APQP 3단계 산출물", files("cp.pdf", "flow.pdf")));
        assertThat(r.created()).isTrue();
        assertThat(r.docNo()).startsWith("DOC");
        assertThat(r.rev() + "/" + r.status()).isEqualTo("A0/DRAFT");
        assertThat(accounts.query(comp, "select doc_type || ':' || doc_nm || ':' || use_yn from mst_doc where doc_id = ?", String.class, r.docId()))
                .isEqualTo("CP:관리계획서 FG1:Y");
        assertThat(accounts.query(comp, "select count(*) from mst_doc_target where doc_id = ? and target_type = 'ITEM' and item_id = ?", Long.class,
                r.docId(), fg1)).isEqualTo(1);
        assertThat(accounts.query(comp, "select change_summary || ':' || source_key || ':' || created_by from mst_doc_rev where doc_rev_id = ?",
                String.class, r.docRevId())).isEqualTo("APQP 3단계 산출물:PMS:DELIV:1:1:admin1");
        assertThat(fileCnt(r.docRevId())).isEqualTo(2);
        // 같은 키로 다시(재시도·두 번 클릭) → 같은 리비전, 파일도 다시 붙이지 않음, 문서도 하나
        DocRevRef again = publish(new DocDraft("PMS:DELIV:1:1", null, "CP", "관리계획서 FG1", fg1, "다른 요약", files("cp.pdf")));
        assertThat(again.created()).isFalse();
        assertThat(again.docRevId()).isEqualTo(r.docRevId());
        assertThat(fileCnt(r.docRevId())).isEqualTo(2);
        assertThat(accounts.query(comp, "select count(*) from mst_doc where doc_type = 'CP'", Long.class)).isEqualTo(1);
        // 결재를 건너뛰지 않는다 — QMS 화면에서 배포하면 RELEASED, 그 뒤 같은 키는 지금 상태를 돌려준다
        int ver = accounts.query(comp, "select row_ver from mst_doc_rev where doc_rev_id = ?", Integer.class, r.docRevId());
        postJson(D + "/revisions/" + r.docRevId() + "/release", "{\"rowVer\":" + ver + "}").andExpect(status().isOk());
        assertThat(publish(new DocDraft("PMS:DELIV:1:1", null, null, null, null, null, null)).status()).isEqualTo("RELEASED");
    }

    @Test
    void 기존_문서는_새_리비전_대상품목_더함_작성중_있으면_거부() throws Exception {
        long docId = id(postJson(D, "{\"docNo\":\"wi-310\",\"docType\":\"WI\",\"docNm\":\"작업 표준\"}"));
        long a0 = accounts.query(comp, "select doc_rev_id from mst_doc_rev where doc_id = ?", Long.class, docId);
        // 작성 중 A0 이 있으면 거부(QMS 규칙) — 부르는 쪽 트랜잭션도 함께 실패해야 한다
        assertThat(fail(new DocDraft("PMS:DELIV:2:1", "WI-310", null, null, null, null, List.of())).code())
                .isEqualTo(HbErrorCode.QMS_DOC_OPEN_EXISTS);
        assertThat(accounts.query(comp, "select count(*) from mst_doc_rev where source_key = 'PMS:DELIV:2:1'", Long.class)).isZero();
        int ver = accounts.query(comp, "select row_ver from mst_doc_rev where doc_rev_id = ?", Integer.class, a0);
        postJson(D + "/revisions/" + a0 + "/release", "{\"rowVer\":" + ver + "}").andExpect(status().isOk());
        DocRevRef r = publish(new DocDraft("PMS:DELIV:2:1", "wi-310", "WI", null, fg1, "산출물 개정", files("wi.pdf")));
        assertThat(r.docId()).isEqualTo(docId);
        assertThat(r.docNo()).isEqualTo("WI-310");
        assertThat(r.rev() + "/" + r.status()).isEqualTo("A1/DRAFT");
        assertThat(fileCnt(r.docRevId())).isEqualTo(1);     // 배포본 첨부를 복사하지 않는다 — 받은 파일만
        assertThat(accounts.query(comp, "select count(*) from mst_doc_target where doc_id = ? and item_id = ?", Long.class, docId, fg1)).isEqualTo(1);
        // 이미 대상인 품목은 다시 더하지 않는다(다음 리비전에서)
        ver = accounts.query(comp, "select row_ver from mst_doc_rev where doc_rev_id = ?", Integer.class, r.docRevId());
        postJson(D + "/revisions/" + r.docRevId() + "/release", "{\"rowVer\":" + ver + "}").andExpect(status().isOk());
        publish(new DocDraft("PMS:DELIV:2:2", "WI-310", null, null, fg1, null, null));
        assertThat(accounts.query(comp, "select count(*) from mst_doc_target where doc_id = ? and item_id = ?", Long.class, docId, fg1)).isEqualTo(1);
    }

    @Test
    void 입력_오류는_칸_오류_파일_검사는_플랫폼_규칙() throws Exception {
        long docId = id(postJson(D, "{\"docNo\":\"OLD-1\",\"docType\":\"WI\",\"docNm\":\"옛 문서\",\"useYn\":\"N\"}"));
        assertThat(fail(new DocDraft(null, null, "CP", "x", null, null, null)).code()).isEqualTo(HbErrorCode.INVALID_VALUE);
        assertThat(fail(new DocDraft("bad key!", null, "CP", "x", null, null, null)).fieldErrors().get(0).field()).isEqualTo("sourceKey");
        assertThat(fail(new DocDraft("K1", null, null, "x", null, null, null)).fieldErrors().get(0).field()).isEqualTo("docType");
        assertThat(fail(new DocDraft("K1", null, "XX", "x", null, null, null)).fieldErrors().get(0).field()).isEqualTo("docType");
        assertThat(fail(new DocDraft("K1", null, "CP", " ", null, null, null)).fieldErrors().get(0).field()).isEqualTo("docNm");
        assertThat(fail(new DocDraft("K1", "NOPE-9", null, null, null, null, null)).fieldErrors().get(0).code()).isEqualTo("NOT_FOUND");
        assertThat(fail(new DocDraft("K1", "old-1", null, null, null, null, null)).fieldErrors().get(0).code()).isEqualTo("INACTIVE");
        id(postJson(D, "{\"docNo\":\"ACT-1\",\"docType\":\"WI\",\"docNm\":\"쓰는 문서\"}"));
        assertThat(fail(new DocDraft("K1", "ACT-1", "CP", null, null, null, null)).fieldErrors().get(0).code()).isEqualTo("FIXED");
        assertThat(fail(new DocDraft("K1", null, "CP", "x", 999_999_999L, null, null)).fieldErrors().get(0).field()).isEqualTo("itemId");
        assertThat(fail(new DocDraft("K1", null, "CP", "x", null, null, java.util.Collections.nCopies(21, new DocFile("a.pdf", PDF))))
                .fieldErrors().get(0).field()).isEqualTo("files");
        assertThat(fail(new DocDraft("K1", null, "CP", "x", null, null, List.of(new DocFile("a.pdf", null)))).fieldErrors().get(0).field())
                .isEqualTo("file");
        // 파일 검사는 attachAs 그대로 — 막힌 확장자면 전체 실패(문서·리비전도 남지 않음)
        Throwable t = catchThrowable(() -> publish(new DocDraft("K2", null, "CP", "x", null, null, List.of(new DocFile("run.exe", PDF)))));
        assertThat(t).isInstanceOf(BusinessException.class);
        assertThat(accounts.query(comp, "select count(*) from mst_doc_rev where source_key = 'K2'", Long.class)).isZero();
        assertThat(accounts.query(comp, "select count(*) from mst_doc where doc_no not in ('OLD-1', 'ACT-1')", Long.class)).isZero();
    }

    @Test
    void DFMEA_확정본을_문서로_배포_유형_고정_화면_목록_A사로_B사() throws Exception {
        // APQP DFMEA 확정본 → QMS 문서(docs/29_P3 30장): 새 문서 유형 DFMEA, A0 작성 중
        DocRevRef r = publish(new DocDraft("APQP:DFMEA:7:1", null, "DFMEA", "DFMEA FG1", fg1, "설계 FMEA 확정", files("dfmea.pdf")));
        assertThat(r.created()).isTrue();
        assertThat(r.rev() + "/" + r.status()).isEqualTo("A0/DRAFT");
        assertThat(accounts.query(comp, "select doc_type from mst_doc where doc_id = ?", String.class, r.docId())).isEqualTo("DFMEA");
        // 다음 리비전에 다른 유형을 주면 칸 오류(유형 고정), 화면 목록 유형 거르기·코드 이름
        int ver = accounts.query(comp, "select row_ver from mst_doc_rev where doc_rev_id = ?", Integer.class, r.docRevId());
        postJson(D + "/revisions/" + r.docRevId() + "/release", "{\"rowVer\":" + ver + "}").andExpect(status().isOk());
        assertThat(fail(new DocDraft("APQP:DFMEA:7:2", r.docNo(), "PFMEA", null, null, null, null)).fieldErrors().get(0).code()).isEqualTo("FIXED");
        mvc.perform(get(D).param("docType", "DFMEA").with(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.list.length()").value(1)).andExpect(jsonPath("$.list[0].docNo").value(r.docNo()));
        mvc.perform(get("/api/platform/codes").param("grp", "DOC_TYPE").with(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.code == 'DFMEA')].name").value("DFMEA"));
        // A사로 B사: B사 범위에선 A사 DFMEA 가 안 보이고, 같은 키로 부르면 B사 문서를 새로 만든다
        String other = accounts.newComp("QM");
        fixtures(other);
        long bUser = accounts.newUser(other, "admin1", PW);
        DocRevRef b = as(other, bUser, "admin1", () -> port.publish(new DocDraft("APQP:DFMEA:7:1", null, "DFMEA", "B사 DFMEA", null, null, null)));
        assertThat(b.docId()).isNotEqualTo(r.docId());
        assertThat(accounts.query(other, "select count(*) from mst_doc where doc_type = 'DFMEA'", Long.class)).isEqualTo(1L);
        // 직접 SQL: 목록 밖 유형은 DB 가 거부
        constraint(comp, "fk_mst_doc_doc_type_code", () -> accounts.exec(comp,   // 목록 CHECK → 공통코드 트리거(docs/68)
                "insert into mst_doc (comp_cd, doc_no, doc_type, doc_nm, created_by, updated_by) values (?, 'X-1', 'XFMEA', 'x', 't', 't')", comp));
    }

    @Test
    void A사로_B사_직접SQL_NONE() throws Exception {
        DocRevRef r = publish(new DocDraft("PMS:DELIV:9:1", null, "OTHER", "A사 산출물", fg1, null, null));
        // B사: 같은 키로 불러도 A사 리비전을 돌려주지 않고 B사 것을 새로 만든다(키는 회사 안에서만 유일), A사 품목 id → 칸 오류
        String other = accounts.newComp("QM");
        fixtures(other);
        long bUser = accounts.newUser(other, "admin1", PW);
        DocRevRef b = as(other, bUser, "admin1", () -> port.publish(new DocDraft("PMS:DELIV:9:1", null, "OTHER", "B사 산출물", null, null, null)));
        assertThat(b.created()).isTrue();
        assertThat(b.docRevId()).isNotEqualTo(r.docRevId());
        Throwable t = catchThrowable(() -> as(other, bUser, "admin1", () -> port.publish(new DocDraft("PMS:DELIV:9:2", null, "OTHER", "x", fg1, null,
                null))));
        assertThat(((BusinessException) t).fieldErrors()).as(String.valueOf(t) + " " + (t instanceof BusinessException be ? be.code() : ""))
                .extracting(f -> f.field()).containsExactly("itemId");
        // A사에만 있는 문서 번호 → B사 범위에선 없음(채번 번호는 회사마다 같을 수 있어 직접 정한 번호로)
        long aOnly = id(postJson(D, "{\"docNo\":\"A-ONLY-1\",\"docType\":\"WI\",\"docNm\":\"A사 문서\"}"));
        t = catchThrowable(() -> as(other, bUser, "admin1", () -> port.publish(new DocDraft("PMS:DELIV:9:3", "A-ONLY-1", null, null, null, null,
                null))));
        assertThat(((BusinessException) t).fieldErrors().get(0).code()).isEqualTo("NOT_FOUND");
        // 직접 SQL: 키 형식·유일·불변(유일은 작성 중 리비전이 없는 문서에서 — 문서당 작성 중 하나 규칙이 먼저 걸리지 않게)
        long aRev = accounts.query(comp, "select doc_rev_id from mst_doc_rev where doc_id = ?", Long.class, aOnly);
        int aVer = accounts.query(comp, "select row_ver from mst_doc_rev where doc_rev_id = ?", Integer.class, aRev);
        postJson(D + "/revisions/" + aRev + "/release", "{\"rowVer\":" + aVer + "}").andExpect(status().isOk());
        constraint(comp, "uq_mst_doc_rev_source_key", () -> accounts.exec(comp,
                "insert into mst_doc_rev (comp_cd, doc_id, rev, source_key, created_by, updated_by) values (?, ?, 'Z9', 'PMS:DELIV:9:1', 't', 't')",
                comp, aOnly));
        constraint(comp, "ck_mst_doc_rev_source_key", () -> accounts.exec(comp,
                "insert into mst_doc_rev (comp_cd, doc_id, rev, source_key, created_by, updated_by) values (?, ?, 'Z8', 'a b', 't', 't')",
                comp, aOnly));
        constraint(comp, "ck_mst_doc_rev_source_fixed", () -> accounts.exec(comp,
                "update mst_doc_rev set source_key = 'OTHER:1' where doc_rev_id = ?", r.docRevId()));
        constraint(comp, "ck_mst_doc_rev_source_fixed", () -> accounts.exec(comp,
                "update mst_doc_rev set source_key = null where doc_rev_id = ?", r.docRevId()));
        // 화면 경로(키 없는 리비전)는 그대로 — 변경 요약 수정 OK
        assertThat(accounts.exec(comp, "update mst_doc_rev set change_summary = '수정' where doc_rev_id = ?", r.docRevId())).isEqualTo(1);
        assertThat(catchThrowable(() -> DocPort.NONE.publish(new DocDraft("K", null, "CP", "x", null, null, null))))
                .isInstanceOf(IllegalStateException.class);
    }
}
