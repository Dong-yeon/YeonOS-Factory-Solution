package io.smarthappy.module.cms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.IllegalTransactionStateException;

import io.smarthappy.module.cms.link.BreakdownReport;
import io.smarthappy.module.cms.link.BreakdownReport.Photo;
import io.smarthappy.module.cms.link.BreakdownReport.PhotoResult;
import io.smarthappy.module.cms.link.BreakdownReport.Report;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * C25 POP 고장 신고 사진 (docs/33 11.17) — 신고한 단말만·신고/접수 때만·이미지·개수 한도(정책)·같은 사진 재전송 = 처음 파일,
 * 행위자 t{단말}, 다른 신고 요청 id·사무 신고·다른 단말 400, 착수 뒤 409, 형식·내용 거부, 트랜잭션 밖 거부·롤백이면 파일 없음,
 * 사무 화면 "신고 사진"(cms-breakdown 목록)에 보임, NONE 409, A사 단말로 B사 번호.
 * C28 사진 한도(photoLimits) = attachPhoto 와 같은 계산(한도 바이트 통과·+1 거부, 형식 교집합), 회사별, NONE = 0·0·없음.
 */
@AutoConfigureMockMvc
class CmsPopPhotoTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n', 0, 0, 0, 13, 'I', 'H', 'D', 'R'};

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    @Autowired
    TenantRunner runner;

    @Autowired
    ObjectProvider<BreakdownReport> link;

    String comp;
    long prs1;
    long w1;
    long t1;
    long t2;
    RequestPostProcessor admin;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("PP");
        long u = accounts.newUser(comp, "admin1", PW);
        accounts.grant(comp, u, "CMS", "cms-mwo", "SEARCH", "SAVE", "APPROVE");
        accounts.grant(comp, u, "CMS", "cms-breakdown", "SEARCH", "SAVE");
        admin = accounts.as(comp, u, "admin1");
        accounts.addPlant(comp, "P1", "1공장");
        long cls = accounts.query(comp, "insert into mst_equip_class (comp_cd, class_cd, class_nm, created_by, updated_by) values (?, 'PRESS', 'p', 't', 't') returning class_id",
                Long.class, comp);
        prs1 = accounts.query(comp, "insert into mst_equip (comp_cd, plant_cd, equip_cd, equip_nm, class_id, created_by, updated_by) values (?, 'P1', 'PRS-01', 'p', ?, 't', 't') returning equip_id",
                Long.class, comp, cls);
        w1 = accounts.query(comp, "insert into mst_worker (comp_cd, worker_cd, worker_nm, plant_cd, created_by, updated_by) values (?, 'W-001', '박현장', 'P1', 't', 't') returning worker_id",
                Long.class, comp);
        t1 = terminal(comp, "TAB-01");
        t2 = terminal(comp, "TAB-02");
    }

    private long terminal(String c, String cd) {
        return accounts.query(c, "insert into sys_terminal (comp_cd, terminal_cd, terminal_nm, plant_cd, created_by, updated_by) values (?, ?, ?, 'P1', 't', 't') returning terminal_id",
                Long.class, c, cd, cd);
    }

    private BreakdownReport impl() {
        return BreakdownReport.of(link);
    }

    private String report(String reqId) {
        return runner.callAs(comp, "t" + t1 + ":TAB-01", () -> impl().report(new Report(prs1, null, "누유", w1, t1, reqId))).reqNo();
    }

    private static byte[] png(int seed) {
        byte[] b = Arrays.copyOf(PNG, PNG.length + 4);
        b[PNG.length] = (byte) seed;
        return b;
    }

    private PhotoResult photo(long terminal, String reqNo, String reqId, String name, byte[] body) {
        return runner.callAs(comp, "t" + terminal + ":TAB", () -> impl().attachPhoto(
                new Photo(reqNo, w1, terminal, reqId, name, body.length, new ByteArrayInputStream(body))));
    }

    private BusinessException fail(long terminal, String reqNo, String reqId, String name, byte[] body) {
        return catchThrowableOfType(BusinessException.class, () -> photo(terminal, reqNo, reqId, name, body));
    }

    private static String field(BusinessException e) {
        return e.fieldErrors().get(0).field() + ":" + e.fieldErrors().get(0).code();
    }

    @Test
    void 사진은_신고한단말만_한도까지_같은사진은_처음파일_행위자는_단말() throws Exception {
        String no = report("req-photo-0001");
        PhotoResult p1 = photo(t1, no, "req-photo-0001", "현장.png", png(1));
        assertThat(p1.duplicate()).isFalse();
        assertThat(p1.count()).isEqualTo(1);
        PhotoResult again = photo(t1, no, "req-photo-0001", "현장(재전송).png", png(1));   // 응답을 못 받고 다시
        assertThat(again.duplicate()).isTrue();
        assertThat(again.fileId()).isEqualTo(p1.fileId());
        assertThat(again.count()).isEqualTo(1);
        photo(t1, no, "req-photo-0001", "b.jpg", new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 1, 2});
        assertThat(photo(t1, no, "req-photo-0001", "c.png", png(3)).count()).isEqualTo(3);
        // 4번째 = 409 PHOTO_LIMIT(기본 3), 행·파일 남지 않음. 같은 사진 재전송은 한도여도 처음 파일
        BusinessException over = fail(t1, no, "req-photo-0001", "d.png", png(4));
        assertThat(over.code()).isEqualTo(HbErrorCode.CONFLICT);
        assertThat(field(over)).isEqualTo("file:PHOTO_LIMIT");
        assertThat(photo(t1, no, "req-photo-0001", "x.png", png(1)).duplicate()).isTrue();
        long mwo = accounts.query(comp, "select mwo_id from cms_mwo where mwo_no = ?", Long.class, no);
        assertThat(accounts.query(comp, "select count(*) from sys_file where target_type = 'cms-breakdown' and target_id = ? and use_yn = 'Y'",
                Integer.class, String.valueOf(mwo))).isEqualTo(3);
        assertThat(accounts.query(comp, "select min(created_by) from sys_file where target_type = 'cms-breakdown' and target_id = ?",
                String.class, String.valueOf(mwo))).isEqualTo("t" + t1 + ":TAB-01");
        // 사무 화면(작업지시 상세 "신고 사진")에 보인다
        mvc.perform(get("/api/platform/files").param("targetType", "cms-breakdown").param("targetId", String.valueOf(mwo)).with(admin))
                .andExpect(jsonPath("$.total").value(3));
        // 정책으로 한도 올리면 더 붙는다(회사별)
        accounts.exec(comp, "insert into sys_policy (comp_cd, policy_key, policy_value, created_by, updated_by) values (?, 'CMS_POP_PHOTO_MAX', '4', 't', 't')", comp);
        assertThat(photo(t1, no, "req-photo-0001", "d.png", png(4)).count()).isEqualTo(4);
    }

    @Test
    void 다른단말_사무신고_다른요청id_없는번호_400_착수뒤_409_형식거부() throws Exception {
        String no = report("req-photo-0002");
        assertThat(field(fail(t2, no, "req-photo-0002", "a.png", png(1)))).isEqualTo("reqNo:NO_SUCH_CODE");          // 다른 단말
        assertThat(field(fail(t1, no, "req-photo-9999", "a.png", png(1)))).isEqualTo("clientReqId:MISMATCH");        // 다른 신고의 요청 id
        assertThat(field(fail(t1, "MWO-NOPE", "req-photo-0002", "a.png", png(1)))).isEqualTo("reqNo:NO_SUCH_CODE");
        assertThat(field(fail(t1, " ", "x", "a.png", png(1)))).isIn("reqNo:REQUIRED", "clientReqId:FORMAT");
        assertThat(field(fail(999_999L, no, "req-photo-0002", "a.png", png(1)))).isEqualTo("terminalId:NO_SUCH_CODE");
        // 사무 신고 작업지시(단말 없음)
        String office = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/cms/breakdowns")
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content("{\"targetCd\":\"PRS-01\",\"symptomText\":\"사무\"}").with(admin))
                .andReturn().getResponse().getContentAsString();
        String officeNo = com.jayway.jsonpath.JsonPath.read(office, "$.data.mwoNo");
        assertThat(field(fail(t1, officeNo, "req-photo-0002", "a.png", png(1)))).isEqualTo("reqNo:NO_SUCH_CODE");
        // 형식: 이미지 아님(pdf)·png 이름에 다른 내용 → 공용 거부
        assertThat(fail(t1, no, "req-photo-0002", "a.pdf", "%PDF-1.7\n".getBytes(StandardCharsets.US_ASCII)).code())
                .isEqualTo(HbErrorCode.FILE_REJECTED);
        assertThat(fail(t1, no, "req-photo-0002", "a.png", "%PDF-1.7\n".getBytes(StandardCharsets.US_ASCII)).code())
                .isEqualTo(HbErrorCode.FILE_REJECTED);
        // 접수(OPEN)까지는 되고, 착수(WIP) 뒤 409
        accounts.exec(comp, "update cms_mwo set status = 'OPEN' where mwo_no = ?", no);
        assertThat(photo(t1, no, "req-photo-0002", "a.png", png(1)).duplicate()).isFalse();
        accounts.exec(comp, "update cms_mwo set status = 'WIP', started_at = now() where mwo_no = ?", no);
        assertThat(fail(t1, no, "req-photo-0002", "b.png", png(2)).code()).isEqualTo(HbErrorCode.CMS_MWO_STATE);
    }

    private BreakdownReport.PhotoLimits limits(String c) {
        return runner.call(c, () -> impl().photoLimits());
    }

    private void policy(String key, String value) {
        accounts.exec(comp, "insert into sys_policy (comp_cd, policy_key, policy_value, created_by, updated_by) values (?, ?, ?, 't', 't')",
                comp, key, value);
    }

    @Test
    void 사진한도는_attachPhoto와_같은계산_한도바이트는_통과_1바이트넘으면_거부_회사별_NONE은_0() {
        BreakdownReport.PhotoLimits d = limits(comp);
        assertThat(d.maxCount()).isEqualTo(3);                                      // CMS_POP_PHOTO_MAX 기본
        assertThat(d.exts()).containsExactly("jpeg", "jpg", "png");                 // 소문자·점 없이·정렬
        assertThat(d.maxBytes()).isEqualTo(5L * 1024 * 1024);                     // min(FILE_MAX_MB 20, CMS_POP_PHOTO_MB 5) × 1MiB
        // 정책을 바꾸면 그대로 — 크기 = min(FILE_MAX_MB, CMS_POP_PHOTO_MB) × 1MiB, 형식 = {jpg,jpeg,png} ∩ 회사 허용
        policy("CMS_POP_PHOTO_MAX", "5");
        policy("CMS_POP_PHOTO_MB", "1");
        policy("FILE_ALLOWED_EXT", "PNG, pdf,jpg");
        BreakdownReport.PhotoLimits l = limits(comp);
        assertThat(l).isEqualTo(new BreakdownReport.PhotoLimits(5, 1024L * 1024L, java.util.List.of("jpg", "png")));
        // 같은 계산: 한도 바이트 = 통과, +1 = 거부
        String no = report("req-photo-0004");
        byte[] exact = Arrays.copyOf(PNG, (int) l.maxBytes());
        assertThat(photo(t1, no, "req-photo-0004", "max.png", exact).duplicate()).isFalse();
        byte[] over = Arrays.copyOf(PNG, (int) l.maxBytes() + 1);
        over[over.length - 1] = 1;
        assertThat(fail(t1, no, "req-photo-0004", "over.png", over).code()).isEqualTo(HbErrorCode.FILE_REJECTED);
        // 받는 형식 밖(jpeg 은 회사 허용에서 빠짐) = 거부 — exts 와 같은 판정
        assertThat(fail(t1, no, "req-photo-0004", "a.jpeg", new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 1, 2}).code())
                .isEqualTo(HbErrorCode.FILE_REJECTED);
        // 회사 정책이 FILE_MAX_MB 가 더 작으면 그쪽
        policy("FILE_MAX_MB", "1");
        accounts.exec(comp, "update sys_policy set policy_value = '50' where policy_key = 'CMS_POP_PHOTO_MB'");
        assertThat(limits(comp).maxBytes()).isEqualTo(1024L * 1024L);
        // B사는 B사 정책(기본), 구현 없음 = 0·0·없음(예외 없음)
        String other = accounts.newComp("PR");
        assertThat(limits(other).maxCount()).isEqualTo(3);
        assertThat(limits(other).exts()).containsExactly("jpeg", "jpg", "png");
        assertThat(BreakdownReport.NONE.photoLimits()).isEqualTo(new BreakdownReport.PhotoLimits(0, 0, java.util.List.of()));
    }

    @Test
    void 트랜잭션밖은_거부_호출쪽_롤백이면_파일행없음_NONE은_409_다른회사_단말은_없음() {
        String no = report("req-photo-0003");
        assertThat(catchThrowable(() -> impl().attachPhoto(new Photo(no, w1, t1, "req-photo-0003", "a.png", 12, new ByteArrayInputStream(png(1))))))
                .isInstanceOfAny(IllegalTransactionStateException.class, IllegalStateException.class);
        assertThat(catchThrowable(() -> runner.callAs(comp, "t" + t1 + ":TAB", () -> {
            impl().attachPhoto(new Photo(no, w1, t1, "req-photo-0003", "a.png", png(1).length, new ByteArrayInputStream(png(1))));
            throw new IllegalStateException("POP 쪽 뒤 단계 실패");
        }))).hasMessageContaining("뒤 단계");
        long mwo = accounts.query(comp, "select mwo_id from cms_mwo where mwo_no = ?", Long.class, no);
        assertThat(accounts.query(comp, "select count(*) from sys_file where target_type = 'cms-breakdown' and target_id = ?", Integer.class,
                String.valueOf(mwo))).isZero();
        assertThat(catchThrowableOfType(BusinessException.class, () -> BreakdownReport.NONE.attachPhoto(
                new Photo(no, w1, t1, "req-photo-0003", "a.png", 1, new ByteArrayInputStream(png(1))))).code()).isEqualTo(HbErrorCode.CONFLICT);
        // B사 단말·작업자로 A사 신고 번호 → B사에선 단말 기준으로 A사 행이 안 보인다(번호 없음)
        String other = accounts.newComp("PQ");
        accounts.addPlant(other, "P1", "1공장");
        long bw = accounts.query(other, "insert into mst_worker (comp_cd, worker_cd, worker_nm, plant_cd, created_by, updated_by) values (?, 'W-B', 'b', 'P1', 't', 't') returning worker_id",
                Long.class, other);
        long bt = terminal(other, "TAB-B");
        BusinessException e = catchThrowableOfType(BusinessException.class, () -> runner.callAs(other, "t" + bt + ":TAB-B",
                () -> impl().attachPhoto(new Photo(no, bw, bt, "req-photo-0003", "a.png", png(1).length, new ByteArrayInputStream(png(1))))));
        assertThat(field(e)).isEqualTo("reqNo:NO_SUCH_CODE");
    }
}
