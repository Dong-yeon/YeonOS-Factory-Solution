package io.smarthappy.module.home;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * 홈 3단계 (docs/37 15장) — 역할별 기본 카드 세트(sys_home_grp_card)·관리 화면 home-card-mgt, 기본 홈 H2.
 * 겹쳐 쓰기(사용자 > 그룹 > 코드), 세트는 권한을 주지 않음, 여러 그룹, 동시 편집 409, 검증, 화면 권한, A사로 B사(API·직접 SQL),
 * 역할별 홈이 없으면 home-main(조회 권한이 있을 때만).
 */
@AutoConfigureMockMvc
class HomeGroupCardTest extends PostgresTestBase {

    private static final String PW = "Harbor-quill-58";
    private static final String G = "/api/home/grp-cards";
    private static final String CARDS = "/api/home/cards";
    private static final String MINE = "/api/home/settings";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    String comp;
    long mgrId;
    long userId;
    RequestPostProcessor mgr;
    RequestPostProcessor me;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("HG");
        mgrId = accounts.newUser(comp, "mgr1", PW);
        accounts.grant(comp, mgrId, "HOMEADM", HomeCardAdminService.PROG, "SEARCH", "SAVE");
        mgr = accounts.as(comp, mgrId, "mgr1");
        userId = accounts.newUser(comp, "user1", PW);
        office(comp, userId);
        me = accounts.as(comp, userId, "user1");
    }

    /** OFFICE = 홈 + 보류 LOT + 납기 임박(작업지시 화면 권한 없음) */
    private void office(String c, long u) {
        accounts.grant(c, u, "OFFICE", HomeService.PROG, "SEARCH");
        accounts.grant(c, u, "OFFICE", "prd-lot", "SEARCH");
        accounts.grant(c, u, "OFFICE", "sal-order", "SEARCH");
    }

    private String ver(RequestPostProcessor who, String grp) throws Exception {
        String body = mvc.perform(get(G + "/" + grp).with(who)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.data.ver");
    }

    private ResultActions saveSet(RequestPostProcessor who, String grp, String ver, String body) throws Exception {
        return mvc.perform(put(G + "/" + grp).param("ver", ver).with(csrf()).with(who)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static final String SET = "[{\"code\":\"prd-lot-hold\"},{\"code\":\"apr-todo\",\"hidden\":true},{\"code\":\"prd-wo-today\"}]";

    @Test
    void 그룹_세트가_설정_없는_사용자의_홈_순서와_숨김이_된다() throws Exception {
        // 세트 전: 코드 기본 순서
        mvc.perform(get(CARDS).with(me))
                .andExpect(jsonPath("$.data.cards[*].code").value(Matchers.contains("apr-todo", "sal-due-soon", "prd-lot-hold")));
        saveSet(mgr, "OFFICE", ver(mgr, "OFFICE"), SET).andExpect(status().isOk());
        // 세트 순서(prd-lot-hold) → 세트에 없는 카드(sal-due-soon)는 뒤에. apr-todo 숨김. prd-wo-today 는 권한이 없어 안 보인다
        mvc.perform(get(CARDS).with(me))
                .andExpect(jsonPath("$.data.cards[*].code").value(Matchers.contains("prd-lot-hold", "sal-due-soon")))
                .andExpect(jsonPath("$.data.hiddenCount").value(1));
        mvc.perform(get(MINE).with(me))
                .andExpect(jsonPath("$.data[*].code").value(Matchers.contains("prd-lot-hold", "apr-todo", "sal-due-soon")))
                .andExpect(jsonPath("$.data[1].hidden").value(true));
        assertThat(accounts.query(comp, "select string_agg(card_cd || ':' || sort_no || hidden_yn, ',' order by sort_no) from sys_home_grp_card",
                String.class)).isEqualTo("prd-lot-hold:1N,apr-todo:2Y,prd-wo-today:3N");
        assertThat(accounts.query(comp, "select count(*) from sys_audit_log where table_nm = 'sys_home_grp_card'", Integer.class)).isEqualTo(3);
        // 세트는 권한을 주지 않는다 — 화면 권한을 받으면 세트 자리(3번째 → 보이는 것 중 두 번째)에
        accounts.grant(comp, userId, "OFFICE", "prd-wo", "SEARCH");
        mvc.perform(get(CARDS).with(me))
                .andExpect(jsonPath("$.data.cards[*].code").value(Matchers.contains("prd-lot-hold", "prd-wo-today", "sal-due-soon")));
    }

    @Test
    void 개인_설정이_그룹_세트보다_먼저_기본값으로는_그룹_세트로_돌아간다() throws Exception {
        saveSet(mgr, "OFFICE", ver(mgr, "OFFICE"), SET).andExpect(status().isOk());
        mvc.perform(put(MINE).with(csrf()).with(me).contentType(MediaType.APPLICATION_JSON)
                .content("[{\"code\":\"sal-due-soon\"},{\"code\":\"apr-todo\"},{\"code\":\"prd-lot-hold\",\"hidden\":true}]"))
                .andExpect(status().isOk());
        mvc.perform(get(CARDS).with(me))
                .andExpect(jsonPath("$.data.cards[*].code").value(Matchers.contains("sal-due-soon", "apr-todo")))
                .andExpect(jsonPath("$.data.hiddenCount").value(1));
        // 같은 그룹 다른 사용자는 그룹 세트
        long other = accounts.newUser(comp, "user2", PW);
        office(comp, other);
        mvc.perform(get(CARDS).with(accounts.as(comp, other, "user2")))
                .andExpect(jsonPath("$.data.cards[*].code").value(Matchers.contains("prd-lot-hold", "sal-due-soon")));
        mvc.perform(post(MINE + "/reset").with(csrf()).with(me)).andExpect(status().isOk());
        mvc.perform(get(CARDS).with(me))
                .andExpect(jsonPath("$.data.cards[*].code").value(Matchers.contains("prd-lot-hold", "sal-due-soon")));
        // 세트를 지우면 코드 기본 순서
        mvc.perform(post(G + "/OFFICE/reset").param("ver", ver(mgr, "OFFICE")).with(csrf()).with(mgr)).andExpect(status().isOk());
        mvc.perform(get(CARDS).with(me))
                .andExpect(jsonPath("$.data.cards[*].code").value(Matchers.contains("apr-todo", "sal-due-soon", "prd-lot-hold")));
    }

    @Test
    void 여러_그룹이면_세트가_있는_사용_중_첫_그룹을_따른다() throws Exception {
        // FIELD(순서 1, 세트 없음) · OFFICE(순서 5) · QC(순서 3) — 세트 있는 첫 그룹 = QC
        accounts.grant(comp, userId, "FIELD", "prd-lot", "SEARCH");
        accounts.grant(comp, userId, "QC", "prd-lot", "SEARCH");
        accounts.exec(comp, "update sys_auth_group set sort_no = case auth_grp_cd when 'FIELD' then 1 when 'QC' then 3 else 5 end");
        saveSet(mgr, "OFFICE", ver(mgr, "OFFICE"), "[{\"code\":\"sal-due-soon\"}]").andExpect(status().isOk());
        saveSet(mgr, "QC", ver(mgr, "QC"), "[{\"code\":\"prd-lot-hold\"},{\"code\":\"sal-due-soon\",\"hidden\":true}]")
                .andExpect(status().isOk());
        mvc.perform(get(CARDS).with(me))
                .andExpect(jsonPath("$.data.cards[*].code").value(Matchers.contains("prd-lot-hold", "apr-todo")));
        // QC 사용 중지 → 다음 세트 OFFICE
        accounts.exec(comp, "update sys_auth_group set use_yn = 'N' where auth_grp_cd = 'QC'");
        mvc.perform(get(CARDS).with(me))
                .andExpect(jsonPath("$.data.cards[*].code").value(Matchers.contains("sal-due-soon", "apr-todo", "prd-lot-hold")));
        // 목록: 그룹별 세트 카드 수
        mvc.perform(get(G).with(mgr))
                .andExpect(jsonPath("$.list[?(@.authGrpCd=='QC')].cardCnt").value(Matchers.contains(2)))
                .andExpect(jsonPath("$.list[?(@.authGrpCd=='FIELD')].cardCnt").value(Matchers.contains(0)));
    }

    @Test
    void 관리_상세는_등록_카드_전체와_그룹_권한_안내만_내고_숫자는_없다() throws Exception {
        saveSet(mgr, "OFFICE", ver(mgr, "OFFICE"), "[{\"code\":\"prd-wo-today\",\"hidden\":true}]").andExpect(status().isOk());
        mvc.perform(get(G + "/OFFICE").with(mgr))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.group.authGrpNm").value("OFFICE 그룹"))
                // 세트 카드 먼저, 나머지는 코드 기본 순서 — 다른 모듈이 카드를 더해도(PMS 800~) 앞 4장은 그대로라 앞만 본다
                .andExpect(jsonPath("$.data.cards[0:4].code").value(Matchers.contains("prd-wo-today", "apr-todo", "sal-due-soon", "prd-lot-hold")))
                .andExpect(jsonPath("$.data.cards[0].inSet").value(true))
                .andExpect(jsonPath("$.data.cards[0].hidden").value(true))
                .andExpect(jsonPath("$.data.cards[0].visible").value(false))   // OFFICE 는 작업지시 화면 권한 없음
                .andExpect(jsonPath("$.data.cards[0].progTitle").isNotEmpty())
                .andExpect(jsonPath("$.data.cards[1].visible").value(true))    // 개인 카드
                .andExpect(jsonPath("$.data.cards[1].inSet").value(false))
                .andExpect(jsonPath("$.data.cards[0].count").doesNotExist())
                .andExpect(jsonPath("$.data.cards[0].items").doesNotExist());
        // 관리자 그룹은 모든 화면을 본다
        accounts.exec(comp, "update sys_auth_group set admin_yn = 'Y' where auth_grp_cd = 'OFFICE'");
        mvc.perform(get(G + "/OFFICE").with(mgr)).andExpect(jsonPath("$.data.cards[0].visible").value(true));
    }

    // ---------- 이상 사용 ----------

    @Test
    void 동시_편집은_늦은_쪽이_409_판_없이는_400() throws Exception {
        String v = ver(mgr, "OFFICE");
        saveSet(mgr, "OFFICE", v, "[{\"code\":\"apr-todo\"}]").andExpect(status().isOk());
        saveSet(mgr, "OFFICE", v, "[{\"code\":\"sal-due-soon\"}]").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
        mvc.perform(post(G + "/OFFICE/reset").param("ver", v).with(csrf()).with(mgr)).andExpect(status().isConflict());
        // 같은 내용으로 다시 저장해도 판이 바뀐다(지우고 다시 넣기) — 앞 판은 무효
        String v2 = ver(mgr, "OFFICE");
        saveSet(mgr, "OFFICE", v2, "[{\"code\":\"apr-todo\"}]").andExpect(status().isOk());
        assertThat(ver(mgr, "OFFICE")).isNotEqualTo(v2);
        mvc.perform(put(G + "/OFFICE").with(csrf()).with(mgr).contentType(MediaType.APPLICATION_JSON).content("[{\"code\":\"apr-todo\"}]"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(G + "/OFFICE/reset").with(csrf()).with(mgr)).andExpect(status().isBadRequest());
        assertThat(accounts.query(comp, "select string_agg(card_cd, ',') from sys_home_grp_card", String.class)).isEqualTo("apr-todo");
    }

    @Test
    void 검증_빈_목록_없는_카드_중복_빈_코드_과다는_저장하지_않는다() throws Exception {
        saveSet(mgr, "OFFICE", ver(mgr, "OFFICE"), "[{\"code\":\"apr-todo\",\"hidden\":true}]").andExpect(status().isOk());
        String v = ver(mgr, "OFFICE");
        saveSet(mgr, "OFFICE", v, "[]").andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        mvc.perform(put(G + "/OFFICE").param("ver", v).with(csrf()).with(mgr).contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
        // 작업지시 카드는 그룹이 못 봐도 등록 카드라 허용 — 없는 카드·대문자·중복·null·공백만 오류
        saveSet(mgr, "OFFICE", v, "[{\"code\":\"prd-wo-today\"},{\"code\":\"no-such\"},{\"code\":\"APR-TODO\"},{\"code\":\"prd-wo-today\"},"
                + "{\"code\":null},{\"code\":\"  \"},null]")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].code").value(Matchers.contains("NO_SUCH_CODE", "NO_SUCH_CODE", "DUPLICATE",
                        "REQUIRED", "REQUIRED", "REQUIRED")))
                .andExpect(jsonPath("$.errors[0].rowKey").value("no-such"))
                .andExpect(jsonPath("$.errors[3].rowKey").value("5"));
        StringBuilder many = new StringBuilder("[");
        for (int i = 0; i < HomeCardAdminService.MAX_ROWS + 1; i++) {
            many.append(i == 0 ? "" : ",").append("{\"code\":\"apr-todo\"}");
        }
        saveSet(mgr, "OFFICE", v, many.append(']').toString()).andExpect(status().isBadRequest());
        saveSet(mgr, "OFFICE", v, "{\"code\":\"apr-todo\"}").andExpect(status().isBadRequest());
        assertThat(accounts.query(comp, "select string_agg(card_cd || hidden_yn, ',') from sys_home_grp_card", String.class))
                .isEqualTo("apr-todoY");
        // 없는 그룹·너무 긴 코드 → 404
        mvc.perform(get(G + "/NOPE").with(mgr)).andExpect(status().isNotFound());
        saveSet(mgr, "NOPE", v, "[{\"code\":\"apr-todo\"}]").andExpect(status().isNotFound());
        mvc.perform(get(G + "/" + "X".repeat(31)).with(mgr)).andExpect(status().isNotFound());
    }

    @Test
    void 화면_권한_조회만이면_저장_403_권한_없으면_전부_403_CSRF_없으면_403() throws Exception {
        long viewer = accounts.newUser(comp, "viewer1", PW);
        accounts.grant(comp, viewer, "VIEW", HomeCardAdminService.PROG, "SEARCH");
        RequestPostProcessor v = accounts.as(comp, viewer, "viewer1");
        String ver = ver(v, "OFFICE");
        saveSet(v, "OFFICE", ver, "[{\"code\":\"apr-todo\"}]").andExpect(status().isForbidden());
        mvc.perform(post(G + "/OFFICE/reset").param("ver", ver).with(csrf()).with(v)).andExpect(status().isForbidden());
        // 홈 사용자(user1)는 관리 화면 권한이 없다 — 자기 그룹 세트도 못 바꾼다
        mvc.perform(get(G).with(me)).andExpect(status().isForbidden());
        saveSet(me, "OFFICE", ver, "[{\"code\":\"apr-todo\"}]").andExpect(status().isForbidden());
        mvc.perform(put(G + "/OFFICE").param("ver", ver).with(mgr).contentType(MediaType.APPLICATION_JSON).content("[{\"code\":\"apr-todo\"}]"))
                .andExpect(status().isForbidden());
        assertThat(accounts.query(comp, "select count(*) from sys_home_grp_card", Integer.class)).isZero();
    }

    @Test
    void A사_관리자는_B사_그룹_세트를_보거나_바꿀_수_없다() throws Exception {
        String b = accounts.newComp("HG");
        long ub = accounts.newUser(b, "user1", PW);
        office(b, ub);
        accounts.grant(b, ub, "BONLY", "prd-lot", "SEARCH");
        // B사에만 있는 그룹 → A사 관리자에겐 404 / 같은 코드(OFFICE)는 A사 그룹만 바뀐다
        mvc.perform(get(G + "/BONLY").with(mgr)).andExpect(status().isNotFound());
        saveSet(mgr, "BONLY", "x", "[{\"code\":\"apr-todo\"}]").andExpect(status().isNotFound());
        mvc.perform(get(G).with(mgr)).andExpect(jsonPath("$.list[*].authGrpCd").value(Matchers.not(Matchers.hasItem("BONLY"))));
        saveSet(mgr, "OFFICE", ver(mgr, "OFFICE"), "[{\"code\":\"apr-todo\",\"hidden\":true}]").andExpect(status().isOk());
        mvc.perform(get(CARDS).with(accounts.as(b, ub, "user1"))).andExpect(jsonPath("$.data.hiddenCount").value(0));
        assertThat(accounts.query(b, "select count(*) from sys_home_grp_card", Integer.class)).isZero();
        // 직접 SQL: A사 범위에서 B사 회사 코드 행 → RLS / A사 회사 코드로 B사에만 있는 그룹 → 복합 FK
        assertThatThrownBy(() -> accounts.exec(comp, """
                insert into sys_home_grp_card (comp_cd, auth_grp_cd, card_cd, sort_no, created_by, updated_by)
                values (?, 'OFFICE', 'apr-todo', 1, 't', 't')""", b)).rootCause().hasMessageContaining("row-level security");
        assertThatThrownBy(() -> accounts.exec(comp, """
                insert into sys_home_grp_card (comp_cd, auth_grp_cd, card_cd, sort_no, created_by, updated_by)
                values (?, 'BONLY', 'apr-todo', 1, 't', 't')""", comp)).rootCause().hasMessageContaining("fk_sys_home_grp_card_grp");
        // B사 행을 A사 범위에서 고치거나 지울 수 없다(0행)
        accounts.exec(b, """
                insert into sys_home_grp_card (comp_cd, auth_grp_cd, card_cd, sort_no, created_by, updated_by)
                values (?, 'OFFICE', 'sal-due-soon', 1, 't', 't')""", b);
        assertThat(accounts.exec(comp, "update sys_home_grp_card set hidden_yn = 'Y' where comp_cd = ?", b)).isZero();
        assertThat(accounts.exec(comp, "delete from sys_home_grp_card where comp_cd = ?", b)).isZero();
        assertThat(accounts.query(b, "select hidden_yn from sys_home_grp_card", String.class)).isEqualTo("N");
        // 형식 제약
        assertThatThrownBy(() -> accounts.exec(comp, """
                insert into sys_home_grp_card (comp_cd, auth_grp_cd, card_cd, sort_no, hidden_yn, created_by, updated_by)
                values (?, 'OFFICE', 'Bad Code', 0, 'X', 't', 't')""", comp)).rootCause().hasMessageContaining("ck_sys_home_grp_card");
    }

    // ---------- 기본 홈 (H2 "홈은 모두 동일하게") ----------

    @Test
    void 역할별_홈이_없으면_조회_권한이_있을_때만_home_main() throws Exception {
        String home = "/api/platform/home";
        mvc.perform(get(home).with(me)).andExpect(jsonPath("$.data").value(HomeService.PROG));
        // 그룹에 홈이 지정돼 있으면 그것이 먼저
        accounts.exec(comp, "update sys_auth_group set home_prog_cd = 'prd-lot' where auth_grp_cd = 'OFFICE'");
        mvc.perform(get(home).with(me)).andExpect(jsonPath("$.data").value("prd-lot"));
        // 지정 화면 권한을 잃으면 다시 기본 홈
        accounts.exec(comp, "delete from sys_auth_prog where auth_grp_cd = 'OFFICE' and prog_cd = 'prd-lot'");
        mvc.perform(get(home).with(me)).andExpect(jsonPath("$.data").value(HomeService.PROG));
        // home-main 조회 권한이 없으면 아무 탭도 열지 않는다(관리 화면 권한만 있는 사람 포함)
        mvc.perform(get(home).with(mgr)).andExpect(jsonPath("$.data").doesNotExist());
        long none = accounts.newUser(comp, "none1", PW);
        mvc.perform(get(home).with(accounts.as(comp, none, "none1"))).andExpect(jsonPath("$.data").doesNotExist());
        // 그룹 사용 중지 → 권한도 홈도 없다
        accounts.exec(comp, "update sys_auth_group set use_yn = 'N' where auth_grp_cd = 'OFFICE'");
        mvc.perform(get(home).with(me)).andExpect(jsonPath("$.data").doesNotExist());
        // 관리자 그룹은 모든 화면 조회 권한 → 기본 홈
        long adm = accounts.newUser(comp, "adm1", PW);
        accounts.grant(comp, adm, "ADM", "mst-cust");
        accounts.exec(comp, "update sys_auth_group set admin_yn = 'Y' where auth_grp_cd = 'ADM'");
        mvc.perform(get(home).with(accounts.as(comp, adm, "adm1"))).andExpect(jsonPath("$.data").value(HomeService.PROG));
        // B사: A사 권한과 무관 — B사 사용자는 자기 회사 권한으로만
        String b = accounts.newComp("HG");
        long ub = accounts.newUser(b, "user1", PW);
        accounts.grant(b, ub, "OFFICE", "prd-lot", "SEARCH");
        mvc.perform(get(home).with(accounts.as(b, ub, "user1"))).andExpect(jsonPath("$.data").doesNotExist());
    }
}
