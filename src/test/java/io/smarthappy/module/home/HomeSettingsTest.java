package io.smarthappy.module.home;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.LinkedHashMap;
import java.util.Map;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * 홈 2단계 (docs/37 14장) — 카드 설정(숨김·순서) 한 벌 저장·기본값, 새 카드는 뒤에, 권한이 설정보다 우선, 검증(행 키),
 * 같은 회사 다른 사용자·A사로 B사(직접 SQL 트리거), 없어진 카드 행 무시, 링크 조건 형식(safeLink).
 */
@AutoConfigureMockMvc
class HomeSettingsTest extends PostgresTestBase {

    private static final String PW = "Juniper-ledger-31";
    private static final String S = "/api/home/settings";
    private static final String CARDS = "/api/home/cards";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    String comp;
    long userId;
    RequestPostProcessor me;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("HS");
        userId = accounts.newUser(comp, "user1", PW);
        grant(comp, userId, "OFFICE");
        me = accounts.as(comp, userId, "user1");
    }

    private void grant(String c, long u, String grp) {
        accounts.grant(c, u, grp, HomeService.PROG, "SEARCH");
        accounts.grant(c, u, grp, "prd-lot", "SEARCH");
        accounts.grant(c, u, grp, "sal-order", "SEARCH");
    }

    private ResultActions save(RequestPostProcessor who, String body) throws Exception {
        return mvc.perform(put(S).with(csrf()).with(who).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    void 처음은_권한_있는_카드가_기본_순서로_모두_보인다() throws Exception {
        mvc.perform(get(S).with(me))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].code").value(Matchers.contains("apr-todo", "sal-due-soon", "prd-lot-hold")))
                .andExpect(jsonPath("$.data[*].hidden").value(Matchers.everyItem(Matchers.is(false))))
                .andExpect(jsonPath("$.data[0].title").value("결재 대기"));
        mvc.perform(get(CARDS).with(me)).andExpect(jsonPath("$.data.hiddenCount").value(0));
    }

    @Test
    void 저장한_순서와_숨김이_홈에_그대로_기본값으로_되돌린다() throws Exception {
        save(me, "[{\"code\":\"prd-lot-hold\",\"hidden\":false},{\"code\":\"apr-todo\",\"hidden\":true},"
                + "{\"code\":\"sal-due-soon\",\"hidden\":false}]").andExpect(status().isOk());
        mvc.perform(get(CARDS).with(me))
                .andExpect(jsonPath("$.data.cards[*].code").value(Matchers.contains("prd-lot-hold", "sal-due-soon")))
                .andExpect(jsonPath("$.data.hiddenCount").value(1));
        mvc.perform(get(S).with(me))
                .andExpect(jsonPath("$.data[*].code").value(Matchers.contains("prd-lot-hold", "apr-todo", "sal-due-soon")))
                .andExpect(jsonPath("$.data[1].hidden").value(true));
        assertThat(accounts.query(comp, "select string_agg(card_cd || ':' || sort_no || hidden_yn, ',' order by sort_no) from sys_home_user_card",
                String.class)).isEqualTo("prd-lot-hold:1N,apr-todo:2Y,sal-due-soon:3N");
        // 감사 트리거가 남긴다
        assertThat(accounts.query(comp, "select count(*) from sys_audit_log where table_nm = 'sys_home_user_card'", Integer.class)).isEqualTo(3);
        mvc.perform(post(S + "/reset").with(csrf()).with(me)).andExpect(status().isOk());
        mvc.perform(get(CARDS).with(me))
                .andExpect(jsonPath("$.data.cards[*].code").value(Matchers.contains("apr-todo", "sal-due-soon", "prd-lot-hold")));
    }

    @Test
    void 설정에_없는_카드는_뒤에_기본_순서로_보인다() throws Exception {
        save(me, "[{\"code\":\"prd-lot-hold\"}]").andExpect(status().isOk());   // hidden 빠지면 보이기
        mvc.perform(get(CARDS).with(me))
                .andExpect(jsonPath("$.data.cards[*].code").value(Matchers.contains("prd-lot-hold", "apr-todo", "sal-due-soon")));
        // 권한을 새로 받은 카드(작업지시)도 숨겨진 채로 오지 않고 끝에
        accounts.grant(comp, userId, "OFFICE", "prd-wo", "SEARCH");
        mvc.perform(get(CARDS).with(me))
                .andExpect(jsonPath("$.data.cards[*].code").value(Matchers.contains("prd-lot-hold", "apr-todo", "sal-due-soon", "prd-wo-today")));
    }

    // ---------- 이상 사용 ----------

    @Test
    void 검증_빈_목록_없는_카드_권한_없는_카드_중복_빈_코드는_저장하지_않는다() throws Exception {
        save(me, "[{\"code\":\"apr-todo\",\"hidden\":true}]").andExpect(status().isOk());
        save(me, "[]").andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        mvc.perform(put(S).with(csrf()).with(me).contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
        save(me, "[{\"code\":\"prd-wo-today\"},{\"code\":\"no-such\"},{\"code\":\"APR-TODO\"},{\"code\":\"apr-todo\"},{\"code\":\"apr-todo\"},"
                + "{\"code\":null},{\"code\":\"  \"},null]")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].code").value(Matchers.contains(
                        "NO_SUCH_CODE", "NO_SUCH_CODE", "NO_SUCH_CODE", "DUPLICATE", "REQUIRED", "REQUIRED", "REQUIRED")))
                .andExpect(jsonPath("$.errors[0].rowKey").value("prd-wo-today"))
                .andExpect(jsonPath("$.errors[4].rowKey").value("6"));
        // 실패한 저장은 아무것도 바꾸지 않는다
        assertThat(accounts.query(comp, "select string_agg(card_cd || hidden_yn, ',') from sys_home_user_card", String.class))
                .isEqualTo("apr-todoY");
        // 형식이 틀린 본문
        save(me, "{\"code\":\"apr-todo\"}").andExpect(status().isBadRequest());
    }

    @Test
    void 권한이_설정보다_우선_잃은_카드는_설정에도_홈에도_없다() throws Exception {
        save(me, "[{\"code\":\"prd-lot-hold\",\"hidden\":true},{\"code\":\"apr-todo\"}]").andExpect(status().isOk());
        accounts.exec(comp, "delete from sys_auth_prog where prog_cd = 'prd-lot'");
        mvc.perform(get(S).with(me)).andExpect(jsonPath("$.data[*].code").value(Matchers.contains("apr-todo", "sal-due-soon")));
        mvc.perform(get(CARDS).with(me)).andExpect(jsonPath("$.data.hiddenCount").value(0));
        // 권한 없는 카드를 저장으로 되살릴 수 없다
        save(me, "[{\"code\":\"prd-lot-hold\"}]").andExpect(jsonPath("$.errors[0].code").value("NO_SUCH_CODE"));
        // 홈 권한 자체가 없으면 설정 API 도 403
        long none = accounts.newUser(comp, "none1", PW);
        mvc.perform(get(S).with(accounts.as(comp, none, "none1"))).andExpect(status().isForbidden());
        save(accounts.as(comp, none, "none1"), "[{\"code\":\"apr-todo\"}]").andExpect(status().isForbidden());
        mvc.perform(put(S).with(me).contentType(MediaType.APPLICATION_JSON).content("[{\"code\":\"apr-todo\"}]"))
                .andExpect(status().isForbidden());   // CSRF 없음
    }

    @Test
    void 없어진_카드의_옛_행은_무시하고_다음_저장에_사라진다() throws Exception {
        accounts.exec(comp, """
                insert into sys_home_user_card (comp_cd, user_id, card_cd, sort_no, hidden_yn, created_by, updated_by)
                values (?, ?, 'zz-gone', 1, 'N', 't', 't'), (?, ?, 'sal-due-soon', 2, 'N', 't', 't')""", comp, userId, comp, userId);
        mvc.perform(get(CARDS).with(me))
                .andExpect(jsonPath("$.data.cards[*].code").value(Matchers.contains("sal-due-soon", "apr-todo", "prd-lot-hold")));
        save(me, "[{\"code\":\"apr-todo\"}]").andExpect(status().isOk());
        assertThat(accounts.query(comp, "select count(*) from sys_home_user_card where card_cd = 'zz-gone'", Integer.class)).isZero();
    }

    @Test
    void 같은_회사_다른_사용자와_B사의_설정은_섞이지_않는다() throws Exception {
        long other = accounts.newUser(comp, "user2", PW);
        grant(comp, other, "OFFICE");
        save(me, "[{\"code\":\"apr-todo\",\"hidden\":true}]").andExpect(status().isOk());
        mvc.perform(get(CARDS).with(accounts.as(comp, other, "user2"))).andExpect(jsonPath("$.data.hiddenCount").value(0));
        String b = accounts.newComp("HS");
        long ub = accounts.newUser(b, "user1", PW);
        grant(b, ub, "OFFICE");
        mvc.perform(get(CARDS).with(accounts.as(b, ub, "user1")))
                .andExpect(jsonPath("$.data.hiddenCount").value(0))
                .andExpect(jsonPath("$.data.cards[*].code").value(Matchers.hasItem("apr-todo")));
        assertThat(accounts.query(b, "select count(*) from sys_home_user_card", Integer.class)).isZero();
        // 직접 SQL: A사 범위로 B사 사용자 행 → 같은 회사 사용자 트리거 / B사 회사 코드로 넣기 → RLS
        assertThatThrownBy(() -> accounts.exec(comp, """
                insert into sys_home_user_card (comp_cd, user_id, card_cd, sort_no, created_by, updated_by)
                values (?, ?, 'apr-todo', 1, 't', 't')""", comp, ub)).rootCause().hasMessageContaining("does not belong");
        assertThatThrownBy(() -> accounts.exec(comp, """
                insert into sys_home_user_card (comp_cd, user_id, card_cd, sort_no, created_by, updated_by)
                values (?, ?, 'apr-todo', 1, 't', 't')""", b, ub)).rootCause()   // BEFORE 트리거가 RLS WITH CHECK 보다 먼저 막는다
                .message().containsAnyOf("does not belong", "row-level security");
        // 형식 제약
        assertThatThrownBy(() -> accounts.exec(comp, """
                insert into sys_home_user_card (comp_cd, user_id, card_cd, sort_no, hidden_yn, created_by, updated_by)
                values (?, ?, 'Bad Code', 1, 'X', 't', 't')""", comp, userId)).rootCause().hasMessageContaining("ck_sys_home_user_card");
    }

    // ---------- 링크 조건 ----------

    @Test
    void 링크_조건_형식이_틀리면_통째로_버린다() {
        assertThat(HomeService.safeLink(Map.of("keyword", "L-1", "from", "2026-10-01"), "prd-lot")).hasSize(2);
        assertThat(HomeService.safeLink(Map.of("keyword", "L-1"), null)).as("링크 화면 없는 카드").isNull();
        assertThat(HomeService.safeLink(Map.of(), "prd-lot")).isNull();
        assertThat(HomeService.safeLink(Map.of("bad name", "x"), "prd-lot")).isNull();
        assertThat(HomeService.safeLink(Map.of("q.keyword", "x"), "prd-lot")).isNull();
        assertThat(HomeService.safeLink(Map.of("keyword", "x".repeat(201)), "prd-lot")).isNull();
        Map<String, String> many = new LinkedHashMap<>();
        for (int i = 0; i < 7; i++) {
            many.put("k" + i, "v");
        }
        assertThat(HomeService.safeLink(many, "prd-lot")).isNull();
    }
}
