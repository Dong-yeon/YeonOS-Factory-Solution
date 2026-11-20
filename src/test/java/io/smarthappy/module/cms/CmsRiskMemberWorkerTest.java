package io.smarthappy.module.cms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneId;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.platform.common.db.PgErrors;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * C19 위험성평가 참여자 작업자 (docs/33 13.6) — 작업자 고르기(이름 스냅샷), 사용자·작업자 둘 다 400, 없음·사용 중지·다른 회사 작업자 400,
 * 직접 SQL(둘 다 CHECK·다른 회사 트리거·확정 뒤 불변), 고르기 목록(사용 중만), 작업자 참여로 확정 가능, A사로 B사.
 */
@AutoConfigureMockMvc
class CmsRiskMemberWorkerTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";
    private static final String R = "/api/cms/risks";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    String comp;
    RequestPostProcessor admin;
    String today;
    long w1;
    long wOff;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("RW");
        long u = accounts.newUser(comp, "admin1", PW);
        accounts.grant(comp, u, "CMS", "cms-risk", "SEARCH", "SAVE", "APPROVE");
        admin = accounts.as(comp, u, "admin1");
        accounts.addPlant(comp, "P1", "1공장");
        accounts.exec(comp, "insert into sys_policy (comp_cd, policy_key, policy_value, created_by, updated_by) values (?, 'CMS_RA_APPROVAL', 'N', 't', 't')", comp);
        w1 = worker(comp, "W-001", "박현장", "Y");
        wOff = worker(comp, "W-OLD", "퇴사자", "N");
        today = LocalDate.now(ZoneId.of("Asia/Seoul")).toString();
    }

    private long worker(String c, String cd, String nm, String useYn) {
        return accounts.query(c, """
                insert into mst_worker (comp_cd, worker_cd, worker_nm, plant_cd, use_yn, created_by, updated_by)
                values (?, ?, ?, 'P1', ?, 't', 't') returning worker_id""", Long.class, c, cd, nm, useYn);
    }

    private ResultActions postJson(String url, String body) throws Exception {
        return mvc.perform(post(url).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body).with(admin));
    }

    private long id(ResultActions r) throws Exception {
        return ((Number) JsonPath.read(r.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.data")).longValue();
    }

    private long ra() throws Exception {
        return id(postJson(R, "{\"raType\":\"REGULAR\",\"method\":\"FREQ_SEV\",\"title\":\"프레스 정기\",\"plantCd\":\"P1\",\"assessDate\":\"" + today + "\"}"));
    }

    private int ver(long ra) throws Exception {
        return JsonPath.read(mvc.perform(get(R + "/" + ra).with(admin)).andReturn().getResponse().getContentAsString(), "$.data.rowVer");
    }

    @Test
    void 작업자_고르기_이름스냅샷_고르기목록은_사용중만_작업자_참여로_확정() throws Exception {
        mvc.perform(get(R + "/options").with(admin)).andExpect(jsonPath("$.data.workers[*].workerCd").value(org.hamcrest.Matchers.contains("W-001")));
        long ra = ra();
        postJson(R + "/" + ra + "/items", "{\"seq\":1,\"taskText\":\"금형 교체\",\"hazardCat\":\"MECH\",\"hazardText\":\"끼임\",\"likelihood\":1,\"severity\":1}")
                .andExpect(status().isOk());
        // 이름 비우면 작업자 이름, 코드는 소문자도
        postJson(R + "/" + ra + "/members", "{\"workerCd\":\"w-001\",\"roleCd\":\"WORKER\",\"participatedOn\":\"" + today + "\"}").andExpect(status().isOk());
        mvc.perform(get(R + "/" + ra).with(admin))
                .andExpect(jsonPath("$.data.members[0].workerCd").value("W-001"))
                .andExpect(jsonPath("$.data.members[0].memberNm").value("박현장"))
                .andExpect(jsonPath("$.data.members[0].loginId").doesNotExist())
                .andExpect(jsonPath("$.data.problems[*].code").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("RA_NO_WORKER"))));
        // 작업자 참여만으로 확정 — 확정 뒤 작업자 칸은 직접 SQL 로도 못 바꾼다
        postJson(R + "/" + ra + "/release", "{\"rowVer\":" + ver(ra) + "}").andExpect(status().isOk());
        long w2 = worker(comp, "W-002", "이현장", "Y");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, "update cms_risk_member set worker_id = ? where ra_id = ?", w2, ra))))
                .contains("ck_cms_risk_member_released_fixed");
    }

    @Test
    void 사용자와_작업자_둘다_없는_작업자_사용중지_다른회사는_400_직접SQL도_막힘() throws Exception {
        long ra = ra();
        postJson(R + "/" + ra + "/members", "{\"loginId\":\"admin1\",\"workerCd\":\"W-001\",\"roleCd\":\"WORKER\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("workerCd"))
                .andExpect(jsonPath("$.errors[0].code").value("USER_OR_WORKER"));
        postJson(R + "/" + ra + "/members", "{\"workerCd\":\"NOPE\",\"roleCd\":\"WORKER\"}").andExpect(jsonPath("$.errors[0].code").value("NO_SUCH_CODE"));
        postJson(R + "/" + ra + "/members", "{\"workerCd\":\"W-OLD\",\"roleCd\":\"WORKER\"}").andExpect(jsonPath("$.errors[0].code").value("NO_SUCH_CODE"));
        postJson(R + "/" + ra + "/members", "{\"workerCd\":\"" + "W".repeat(21) + "\",\"roleCd\":\"WORKER\"}").andExpect(jsonPath("$.errors[0].code").value("NO_SUCH_CODE"));
        String other = accounts.newComp("RX");
        accounts.addPlant(other, "P1", "1공장");
        long bw = worker(other, "W-B01", "B사 작업자", "Y");
        postJson(R + "/" + ra + "/members", "{\"workerCd\":\"W-B01\",\"roleCd\":\"WORKER\"}").andExpect(jsonPath("$.errors[0].code").value("NO_SUCH_CODE"));
        // 직접 SQL: 사용자·작업자 둘 다 / 다른 회사 작업자 / 사용 중지 작업자
        long uid = accounts.query(comp, "select user_id from sys_user where login_id = 'admin1'", Long.class);
        String ins = "insert into cms_risk_member (comp_cd, ra_id, user_id, worker_id, member_nm, role_cd, created_by, updated_by) values (?, ?, ?, ?, 'x', 'WORKER', 't', 't')";
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, ins, comp, ra, uid, w1)))).contains("ck_cms_risk_member_who");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, ins, comp, ra, null, bw)))).contains("fk_cms_risk_member_worker_comp");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, ins, comp, ra, null, wOff)))).contains("fk_cms_risk_member_worker_comp");
        // B사는 A사 평가에 참여자를 못 넣는다(404), B사 고르기 목록엔 자기 작업자만
        long bu = accounts.newUser(other, "admin1", PW);
        accounts.grant(other, bu, "CMS", "cms-risk", "SEARCH", "SAVE");
        RequestPostProcessor b = accounts.as(other, bu, "admin1");
        mvc.perform(post(R + "/" + ra + "/members").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"workerCd\":\"W-B01\",\"roleCd\":\"WORKER\"}").with(b)).andExpect(status().isNotFound());
        mvc.perform(get(R + "/options").with(b)).andExpect(jsonPath("$.data.workers[*].workerCd").value(org.hamcrest.Matchers.contains("W-B01")));
    }

    /** QA U-2 — 사용자 참여자는 평가 담당과 같은 규칙: 사용 중지·관리자 잠금은 400 loginId:INACTIVE, 로그인 실패 자동 잠금(해제 시각 있음)은 그대로 넣는다 */
    @Test
    void 사용중지_관리자잠금_사용자는_참여자로_400() throws Exception {
        long ra = ra();
        long off = accounts.newUser(comp, "left1", PW);
        long adminLocked = accounts.newUser(comp, "locked1", PW);
        long autoLocked = accounts.newUser(comp, "retry1", PW);
        accounts.exec(comp, "update sys_user set use_yn = 'N' where user_id = ?", off);
        accounts.exec(comp, "update sys_user set lock_yn = 'Y', locked_until = null where user_id = ?", adminLocked);
        accounts.exec(comp, "update sys_user set lock_yn = 'Y', locked_until = now() + interval '15 minutes' where user_id = ?", autoLocked);
        postJson(R + "/" + ra + "/members", "{\"loginId\":\"left1\",\"roleCd\":\"WORKER\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("loginId"))
                .andExpect(jsonPath("$.errors[0].code").value("INACTIVE"));
        postJson(R + "/" + ra + "/members", "{\"loginId\":\"LOCKED1\",\"roleCd\":\"WORKER\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("INACTIVE"));
        postJson(R + "/" + ra + "/members", "{\"loginId\":\"retry1\",\"roleCd\":\"WORKER\"}").andExpect(status().isOk());
        assertThat(accounts.query(comp, "select count(*) from cms_risk_member where ra_id = ?", Integer.class, ra)).isEqualTo(1);
    }
}
