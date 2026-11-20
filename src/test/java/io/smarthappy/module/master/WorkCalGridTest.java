package io.smarthappy.module.master;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * 공장 휴일 달력 "이 달 예외" 목록 그리드 API (UI 단계 3 ①, 결정 docs/29 #45·#49) — `/api/master/work-cal/grid` + `/columns`.
 * 한 달 API 는 그대로(달력 칸), 새 API 는 같은 조건(범위·달)·기존 순서·페이지 경계·정렬 허용 열(빈 범위·근무조는 오름차순에서 먼저)·건수,
 * 조회 권한, A사로 B사.
 */
@AutoConfigureMockMvc
class WorkCalGridTest extends PostgresTestBase {

    private static final String PW = "Harbor-violet-27";
    private static final String CAL = "/api/master/work-cal";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    String comp;
    RequestPostProcessor admin;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("WC");
        admin = user(comp, "admin1", true);
        seed(comp, "");
    }

    private RequestPostProcessor user(String c, String id, boolean search) {
        long u = accounts.newUser(c, id, PW);
        if (search) {
            accounts.grant(c, u, "MST", "mst-work-cal", "SEARCH");
        }
        return accounts.as(c, u, id);
    }

    /**
     * 공장 P1(근무조 A)·P2. 2026-12: 회사 공통 25일 OFF "성탄절" → P1 25일 A조 WORK "특근" → P1 24일 OFF "공장 휴무"(넣은 순서 = cal_id 순),
     * P2 24일 OFF "P2 휴무", 사용 중지 P1 26일, 다른 달(2027-01-01) 회사 공통. 사유 뒤에 tag 를 붙여 회사를 구분한다.
     */
    private void seed(String c, String tag) {
        accounts.addPlant(c, "P1", "1공장");
        accounts.addPlant(c, "P2", "2공장");
        accounts.exec(c, """
                insert into mst_shift (comp_cd, plant_cd, shift_cd, shift_nm, start_time, end_time, sort_no, use_yn, created_by, updated_by)
                values (?, 'P1', 'A', '주간', '08:00', '17:00', 1, 'Y', 't', 't')""", c);
        cal(c, null, "2026-12-25", null, "OFF", "성탄절" + tag, "Y");
        cal(c, "P1", "2026-12-25", "A", "WORK", "특근" + tag, "Y");
        cal(c, "P1", "2026-12-24", null, "OFF", "공장 휴무" + tag, "Y");
        cal(c, "P2", "2026-12-24", null, "OFF", "P2 휴무" + tag, "Y");
        cal(c, "P1", "2026-12-26", null, "OFF", "중지됨" + tag, "N");
        cal(c, null, "2027-01-01", null, "OFF", "신정" + tag, "Y");
    }

    private void cal(String c, String plant, String day, String shift, String type, String reason, String use) {
        accounts.exec(c, """
                insert into mst_work_cal (comp_cd, plant_cd, cal_date, shift_cd, day_type, reason, use_yn, created_by, updated_by)
                values (?, ?, cast(? as date), ?, ?, ?, ?, 't', 't')""", c, plant, day, shift, type, reason, use);
    }

    /** 조회 — 쿼리 문자열은 param 으로(정렬 값 "필드,방향" 의 쉼표가 목록 구분으로 나뉘지 않게, hb.grid 가 보내는 모양과 같음) */
    private MockHttpServletRequestBuilder req(String url) {
        int q = url.indexOf('?');
        MockHttpServletRequestBuilder b = get(q < 0 ? url : url.substring(0, q));
        if (q >= 0) {
            for (String kv : url.substring(q + 1).split("&")) {
                int e = kv.indexOf('=');
                b = b.param(kv.substring(0, e), kv.substring(e + 1));
            }
        }
        return b;
    }

    private MvcResult ok(RequestPostProcessor who, String url) throws Exception {
        MvcResult r = mvc.perform(req(url).with(who)).andReturn();
        assertThat(r.getResponse().getStatus()).as(url + " → " + r.getResponse().getContentAsString()).isEqualTo(200);
        return r;
    }

    private static List<String> field(MvcResult r, String path) throws Exception {
        return JsonPath.read(r.getResponse().getContentAsString(), path);
    }

    private static int total(MvcResult r) throws Exception {
        return ((Number) JsonPath.read(r.getResponse().getContentAsString(), "$.total")).intValue();
    }

    private List<String> reasons(String query) throws Exception {
        return field(ok(admin, CAL + "/grid?" + query), "$.list[*].reason");
    }

    // =====================================================================
    // 정상
    // =====================================================================

    @Test
    void 공장_범위는_회사_공통과_그_공장_예외를_한_달_API_와_같은_순서로_페이지_경계에서_빠지거나_겹치지_않는다() throws Exception {
        MvcResult r = ok(admin, CAL + "/grid?plantCd=P1&month=2026-12");
        assertThat(field(r, "$.list[*].reason")).containsExactly("공장 휴무", "성탄절", "특근");   // 날짜 → 범위(공통 먼저) → 근무조
        assertThat(field(r, "$.list[*].calDate")).containsExactly("2026-12-24", "2026-12-25", "2026-12-25");
        assertThat(total(r)).isEqualTo(3);
        assertThat(field(r, "$.list[*].reason"))
                .containsExactlyElementsOf(field(ok(admin, CAL + "?plantCd=P1&month=2026-12"), "$.data.rows[*].reason"));
        assertThat(field(r, "$.list[?(@.shiftCd == 'A')].shiftNm")).containsExactly("주간");            // 근무조 이름(화면이 "A · 주간")
        assertThat(reasons("plantCd=P1&month=2026-12&page=1&size=2")).containsExactly("공장 휴무", "성탄절");
        assertThat(reasons("plantCd=P1&month=2026-12&page=2&size=2")).containsExactly("특근");
        assertThat(reasons("plantCd=P1&month=2026-12&page=3&size=2")).isEmpty();
        // 회사 공통 범위 = 공통 행만, 공장 코드는 대문자로, 다른 달·사용 중지 행은 없음
        assertThat(reasons("month=2026-12")).containsExactly("성탄절");
        assertThat(reasons("plantCd=p2&month=2026-12")).containsExactly("P2 휴무", "성탄절");
        assertThat(reasons("month=2027-01")).containsExactly("신정");
        assertThat(reasons("plantCd=P1&month=2026-11")).isEmpty();
        mvc.perform(req(CAL + "/grid?month=2026-12&total=false").with(admin)).andExpect(jsonPath("$.total").doesNotExist());
    }

    @Test
    void 정렬은_허용_열만_빈_범위와_근무조는_오름차순에서_먼저_내림차순에서_나중() throws Exception {
        String q = "plantCd=P1&month=2026-12&sort=";
        assertThat(reasons(q + "plantCd,asc")).containsExactly("성탄절", "특근", "공장 휴무");          // 공통 먼저, 같으면 넣은 순서
        assertThat(reasons(q + "plantCd,desc")).containsExactly("특근", "공장 휴무", "성탄절");
        assertThat(reasons(q + "shiftCd,asc&size=1")).containsExactly("성탄절");                           // 하루 전체 먼저
        assertThat(reasons(q + "shiftCd,desc&size=1")).containsExactly("특근");
        assertThat(reasons(q + "dayType,desc&size=1")).containsExactly("특근");                            // WORK > OFF
        assertThat(reasons(q + "calDate,desc&sort=plantCd,desc")).containsExactly("특근", "성탄절", "공장 휴무");
    }

    @Test
    void 열_정의는_정렬_표시가_정렬_허용_열과_같고_문구는_로캘을_따른다() throws Exception {
        MvcResult c = ok(admin, CAL + "/columns");
        assertThat(field(c, "$.list[*].field")).containsExactly("calDate", "plantCd", "shiftCd", "dayType", "reason");
        assertThat(field(c, "$.list[?(@.sortable == true)].field")).containsExactlyInAnyOrder("calDate", "plantCd", "shiftCd", "dayType");
        assertThat(field(c, "$.list[?(@.field == 'calDate')].title")).containsExactly("날짜");
        assertThat(field(c, "$.list[?(@.field == 'calDate')].type")).containsExactly("DATE");
    }

    // =====================================================================
    // 거부 · A사로 B사 · 권한
    // =====================================================================

    @Test
    void B_달_없음_형식_범위_밖_정렬_허용_밖_잘못된_방향_페이지_0_크기_초과_없는_공장은_400() throws Exception {
        for (String q : List.of("plantCd=P1", "month=2026-13", "month=2026/12", "month=1999-12", "month=3000-01",
                "month=2026-12&sort=reason,asc", "month=2026-12&sort=cal_date,asc", "month=2026-12&sort=calDate,up",
                "month=2026-12&page=0", "month=2026-12&size=0", "month=2026-12&size=100000", "month=2026-12&plantCd=NOPE",
                "month=2026-12&plantCd=" + "P".repeat(21))) {
            mvc.perform(req(CAL + "/grid?" + q).with(admin)).andExpect(status().isBadRequest());
        }
    }

    @Test
    void A사로_B사_그리드는_자기_회사_것만_보이고_B사_공장_코드는_없는_공장_조회_권한이_없으면_403() throws Exception {
        String b = accounts.newComp("WD");
        RequestPostProcessor bAdmin = user(b, "admin1", true);
        seed(b, "-B");
        accounts.addPlant(b, "PB", "B사 공장");
        assertThat(field(ok(bAdmin, CAL + "/grid?plantCd=P1&month=2026-12"), "$.list[*].reason"))
                .containsExactly("공장 휴무-B", "성탄절-B", "특근-B");
        assertThat(reasons("plantCd=P1&month=2026-12")).containsExactly("공장 휴무", "성탄절", "특근");   // 같은 공장 코드여도 자기 회사 것만
        assertThat(total(ok(admin, CAL + "/grid?plantCd=P1&month=2026-12"))).isEqualTo(3);
        mvc.perform(req(CAL + "/grid?plantCd=PB&month=2026-12").with(admin)).andExpect(status().isBadRequest());   // B사 공장 = 없는 공장

        RequestPostProcessor viewer = user(comp, "nobody", false);
        mvc.perform(req(CAL + "/grid?month=2026-12").with(viewer)).andExpect(status().isForbidden());
        mvc.perform(req(CAL + "/columns").with(viewer)).andExpect(status().isForbidden());
    }
}
