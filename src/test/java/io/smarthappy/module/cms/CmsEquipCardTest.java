package io.smarthappy.module.cms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * C17 설비 이력 카드 (docs/33 13.5) — 요약 손 검산(작업지시 유형·고장·진행 중·정지 분, 점검 OK/NG/건너뜀/예정/지연, 예비품, 리셋, 카운터),
 * 이력 한 표(네 종류·시각순·종류 조건·정렬 허용 목록·페이지), 기간 경계(공장 현지 날짜), 다른 설비 제외, 엑셀, 권한, A사로 B사.
 */
@AutoConfigureMockMvc
class CmsEquipCardTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";
    private static final String C = "/api/cms/equip-card";
    private static final String M = "/api/cms/mwos";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    String comp;
    RequestPostProcessor admin;
    LocalDate today;
    LocalDate day;
    long prs1;
    long prs2;

    @BeforeEach
    void setUp() throws Exception {
        comp = accounts.newComp("CK");
        long u = accounts.newUser(comp, "admin1", PW);
        grant(comp, u);
        admin = accounts.as(comp, u, "admin1");
        accounts.addPlant(comp, "P1", "1공장");
        long cls = accounts.query(comp, "insert into mst_equip_class (comp_cd, class_cd, class_nm, created_by, updated_by) values (?, 'PRESS', 'p', 't', 't') returning class_id",
                Long.class, comp);
        prs1 = equip(comp, "PRS-01", cls);
        prs2 = equip(comp, "PRS-02", cls);
        accounts.exec(comp, "insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, created_by, updated_by) values (?, 'BRG-6204', '베어링', 'CON', 'BUY', 'EA', 't', 't')", comp);
        today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        day = today.minusDays(1);   // 오전 시각을 써도 미래 시각 거부에 걸리지 않게
    }

    private void grant(String c, long u) {
        accounts.grant(c, u, "CMS", "cms-mwo", "SEARCH", "SAVE", "APPROVE");
        accounts.grant(c, u, "CMS", "cms-breakdown", "SEARCH", "SAVE");
        accounts.grant(c, u, "CMS", "cms-chk-tmpl", "SEARCH", "SAVE");
        accounts.grant(c, u, "CMS", "cms-pm-plan", "SEARCH", "SAVE");
        accounts.grant(c, u, "CMS", "cms-counter", "SEARCH", "SAVE");
        accounts.grant(c, u, "CMS", "cms-equip-card", "SEARCH", "EXCEL");
    }

    private long equip(String c, String cd, long cls) {
        return accounts.query(c, "insert into mst_equip (comp_cd, plant_cd, equip_cd, equip_nm, class_id, created_by, updated_by) values (?, 'P1', ?, ?, ?, 't', 't') returning equip_id",
                Long.class, c, cd, cd, cls);
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String body) {
        return b.with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private long create(String url, String body) throws Exception {
        String s = mvc.perform(json(MockMvcRequestBuilders.post(url), body).with(admin)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        Object v = JsonPath.read(s, "$.data");
        if (v instanceof Number n) {
            return n.longValue();
        }
        java.util.Map<?, ?> m = (java.util.Map<?, ?>) v;   // 작업지시 = {mwoId, mwoNo}, 카운터 읽기 = {readingId, …}
        return ((Number) (m.containsKey("mwoId") ? m.get("mwoId") : m.get("readingId"))).longValue();
    }

    private int ver(long mwo) {
        return accounts.query(comp, "select row_ver from cms_mwo where mwo_id = ?", Integer.class, mwo);
    }

    /** 기록 한 벌 — PRS-01: 개선(어제, 정지 45분, 예비품 2) 완료 · 고장 신고(진행 중) · 점검 4(OK·NG·건너뜀·지연) · 카운터 리셋 1. PRS-02: 고장 1 */
    private void seed() throws Exception {
        long cm = create(M, "{\"mwoType\":\"CM\",\"targetType\":\"EQUIP\",\"targetCd\":\"PRS-01\",\"title\":\"베어링 교체\",\"priority\":2,\"reportedAt\":\"" + day + "T07:50\","
                + "\"downYn\":\"Y\",\"downStartAt\":\"" + day + "T08:00\",\"downEndAt\":\"" + day + "T08:45\"}");
        mvc.perform(json(MockMvcRequestBuilders.post(M + "/" + cm + "/start"), "{\"rowVer\":" + ver(cm) + ",\"startedAt\":\"" + day + "T08:05\"}").with(admin))
                .andExpect(status().isOk());
        accounts.exec(comp, """
                insert into cms_mwo_part (comp_cd, mwo_id, item_id, qty, unit_cd, created_by, updated_by)
                select ?, ?, item_id, 2, 'EA', 'admin1', 'admin1' from mst_item where item_cd = 'BRG-6204'""", comp, cm);
        mvc.perform(json(MockMvcRequestBuilders.post(M + "/" + cm + "/finish"), "{\"title\":\"베어링 교체\",\"workText\":\"6204 교체\",\"priority\":2,\"downYn\":\"Y\",\"downStartAt\":\""
                + day + "T08:00\",\"downEndAt\":\"" + day + "T08:45\",\"finishedAt\":\"" + day + "T08:40\",\"rowVer\":" + ver(cm) + "}").with(admin))
                .andExpect(status().isOk());
        create("/api/cms/breakdowns", "{\"targetCd\":\"PRS-01\",\"symptomText\":\"유압 누유\"}");
        create("/api/cms/breakdowns", "{\"targetCd\":\"PRS-02\",\"symptomText\":\"다른 설비\"}");
        // 점검: 계획 하나에 실행 4(예정일 다르게) — 넣을 때는 OPEN 만, 그 뒤 상태를 바꾼다
        long tmpl = create("/api/cms/chk-tmpls", "{\"tmplCd\":\"PRS-D\",\"tmplNm\":\"프레스 일상\"}");
        mvc.perform(json(MockMvcRequestBuilders.put("/api/cms/chk-tmpls/" + tmpl + "/items?rowVer=0"),
                "[{\"seq\":1,\"itemNm\":\"클러치 소음\",\"judgeType\":\"OKNG\"}]").with(admin)).andExpect(status().isOk());
        long plan = create("/api/cms/pm-plans", "{\"planCd\":\"PM-D\",\"planNm\":\"일상\",\"targetCd\":\"PRS-01\",\"tmplCd\":\"PRS-D\",\"execMode\":\"CHECK\","
                + "\"calUnit\":\"DAY\",\"calInterval\":1,\"scheduleBasis\":\"FIXED\",\"anchorDate\":\"" + today.plusDays(30) + "\"}");
        run(plan, tmpl, day, "update cms_chk_run set status = 'DONE', result = 'OK', performed_at = ?::timestamptz where run_id = ?", day + "T09:00:00+09:00");
        run(plan, tmpl, day.minusDays(1), "update cms_chk_run set status = 'DONE', result = 'NG', performed_at = ?::timestamptz where run_id = ?",
                day.minusDays(1) + "T09:00:00+09:00");
        run(plan, tmpl, day.minusDays(2), "update cms_chk_run set status = 'SKIPPED', skip_reason = ? where run_id = ?", "설비 정지");
        run(plan, tmpl, day.minusDays(3), null, null);                                                                     // 열림 + 예정일 지남 = 지연
        // 카운터 리셋: 100 → 5(리셋)
        long counter = create("/api/cms/counters", "{\"counterCd\":\"CNT-1\",\"counterNm\":\"타수\",\"targetType\":\"EQUIP\",\"targetCd\":\"PRS-01\",\"counterKind\":\"SHOTS\",\"scale\":0}");
        long r1 = create("/api/cms/counters/" + counter + "/readings", "{\"readAt\":\"" + day + "T06:00\",\"gaugeValue\":\"100\",\"lastReadingId\":0}");
        create("/api/cms/counters/" + counter + "/readings", "{\"readAt\":\"" + day + "T10:00\",\"gaugeValue\":\"5\",\"resetYn\":\"Y\",\"lastReadingId\":" + r1 + "}");
    }

    private void run(long plan, long tmpl, LocalDate due, String update, String arg) {
        long id = accounts.query(comp, """
                insert into cms_chk_run (comp_cd, plan_id, target_type, equip_id, tmpl_id, due_date, created_by, updated_by)
                values (?, ?, 'EQUIP', ?, ?, ?, 't', 't') returning run_id""", Long.class, comp, plan, prs1, tmpl, due);
        if (update != null) {
            accounts.exec(comp, update, arg, id);
        }
    }

    private ResultActions summary(RequestPostProcessor who, String equip, LocalDate from, LocalDate to) throws Exception {
        return mvc.perform(get(C + "/summary").param("equipCd", equip).param("from", from.toString()).param("to", to.toString()).with(who));
    }

    private ResultActions grid(RequestPostProcessor who, String... params) throws Exception {
        MockHttpServletRequestBuilder b = get(C + "/history/grid");
        for (int i = 0; i < params.length; i += 2) {
            b = b.param(params[i], params[i + 1]);
        }
        return mvc.perform(b.with(who));
    }

    private String[] range(String equip, LocalDate from, LocalDate to, String... more) {
        List<String> p = new ArrayList<>(List.of("equipCd", equip, "from", from.toString(), "to", to.toString()));
        p.addAll(List.of(more));
        return p.toArray(String[]::new);
    }

    @Test
    void 요약_손검산_작업지시_점검_예비품_리셋_카운터_다른설비_제외() throws Exception {
        seed();
        summary(admin, "PRS-01", today.minusDays(30), today).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.profile.equipCd").value("PRS-01"))
                .andExpect(jsonPath("$.data.timeZone").value("Asia/Seoul"))
                .andExpect(jsonPath("$.data.mwo.total").value(2))         // 개선 1 + 고장 1(PRS-02 고장은 빠짐)
                .andExpect(jsonPath("$.data.mwo.bm").value(1))
                .andExpect(jsonPath("$.data.mwo.cm").value(1))
                .andExpect(jsonPath("$.data.mwo.open").value(1))          // 신고 상태 고장
                .andExpect(jsonPath("$.data.mwo.downMin").value(45))
                .andExpect(jsonPath("$.data.chk.total").value(4))
                .andExpect(jsonPath("$.data.chk.ok").value(1))
                .andExpect(jsonPath("$.data.chk.ng").value(1))
                .andExpect(jsonPath("$.data.chk.skipped").value(1))
                .andExpect(jsonPath("$.data.chk.open").value(1))
                .andExpect(jsonPath("$.data.chk.overdue").value(1))
                .andExpect(jsonPath("$.data.part.lines").value(1))
                .andExpect(jsonPath("$.data.part.items").value(1))
                .andExpect(jsonPath("$.data.resets").value(1))
                .andExpect(jsonPath("$.data.counters.length()").value(1))
                .andExpect(jsonPath("$.data.counters[0].counterCd").value("CNT-1"));
        // PRS-02 = 고장 1 만
        summary(admin, "PRS-02", today.minusDays(30), today).andExpect(jsonPath("$.data.mwo.total").value(1))
                .andExpect(jsonPath("$.data.chk.total").value(0)).andExpect(jsonPath("$.data.resets").value(0));
    }

    @Test
    void 이력_한표_네종류_최신순_종류조건_정렬_페이지_기간경계() throws Exception {
        seed();
        // 작업지시 2 + 점검 4 + 예비품 1 + 리셋 1 = 8
        grid(admin, range("PRS-01", today.minusDays(30), today)).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(8));
        List<String> kinds = JsonPath.read(grid(admin, range("PRS-01", today.minusDays(30), today, "size", "50")).andReturn().getResponse().getContentAsString(),
                "$.list[*].kind");
        assertThat(kinds).containsExactlyInAnyOrder("MWO", "MWO", "CHK", "CHK", "CHK", "CHK", "PART", "RESET");
        List<String> at = JsonPath.read(grid(admin, range("PRS-01", today.minusDays(30), today, "size", "50")).andReturn().getResponse().getContentAsString(),
                "$.list[*].atText");
        assertThat(at).isSortedAccordingTo(java.util.Comparator.reverseOrder());                                   // 기본 = 최신순
        grid(admin, range("PRS-01", today.minusDays(30), today, "kind", "CHK")).andExpect(jsonPath("$.total").value(4));
        grid(admin, range("PRS-01", today.minusDays(30), today, "kind", "MWO", "sort", "refNo,asc"))
                .andExpect(jsonPath("$.list[0].mwoId").isNumber())
                .andExpect(jsonPath("$.list[?(@.title == '베어링 교체')].detail").value(org.hamcrest.Matchers.contains("개선 · 완료 · 정지 45분")));
        grid(admin, range("PRS-01", today.minusDays(30), today, "kind", "PART"))
                .andExpect(jsonPath("$.list[0].title").value("BRG-6204 · 베어링")).andExpect(jsonPath("$.list[0].detail").value("2 EA"));
        grid(admin, range("PRS-01", today.minusDays(30), today, "kind", "RESET")).andExpect(jsonPath("$.list[0].detail").value("리셋 → 5"));
        grid(admin, range("PRS-01", today.minusDays(30), today, "size", "3", "page", "3")).andExpect(jsonPath("$.list.length()").value(2));
        // 기간 = 공장 현지 날짜: 오늘만 → 오늘 신고한 고장. 예비품은 오늘 기록했어도 사용일 = 작업지시 완료(어제 08:40) 라 어제 쪽(docs/33 16.1 F1)
        assertThat(JsonPath.<List<String>>read(grid(admin, range("PRS-01", today, today)).andReturn().getResponse().getContentAsString(), "$.list[*].kind"))
                .containsExactlyInAnyOrder("MWO");
        // 어제만 → 개선(신고 07:50)·리셋(10:00)·OK 점검(예정일 어제)·예비품(완료 08:40)
        assertThat(JsonPath.<List<String>>read(grid(admin, range("PRS-01", day, day)).andReturn().getResponse().getContentAsString(), "$.list[*].kind"))
                .containsExactlyInAnyOrder("MWO", "RESET", "CHK", "PART");
        mvc.perform(get(C + "/history/columns").with(admin)).andExpect(jsonPath("$.list[*].field").value(org.hamcrest.Matchers.hasItems("atText", "kind", "detail")));
    }

    @Test
    void 이상사용_설비없음_기간_종류_정렬_없는설비는_400_404() throws Exception {
        grid(admin, "from", today.toString(), "to", today.toString()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("equipCd"));
        grid(admin, range("PRS-01", today, today.minusDays(1))).andExpect(status().isBadRequest());               // 시작 > 종료
        grid(admin, range("PRS-01", today.minusDays(400), today)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RANGE_TOO_LONG"));
        grid(admin, "equipCd", "PRS-01", "to", today.toString()).andExpect(status().isBadRequest());
        grid(admin, range("PRS-01", today, today, "kind", "NOPE")).andExpect(status().isBadRequest());
        grid(admin, range("PRS-01", today, today, "sort", "detail,asc")).andExpect(status().isBadRequest());
        grid(admin, range("PRS-01", today, today, "from", "2026-13-01")).andExpect(status().isBadRequest());
        grid(admin, range("NOPE-99", today, today)).andExpect(status().isNotFound());
        summary(admin, "NOPE-99", today, today).andExpect(status().isNotFound());
        // 1년(366일)은 된다
        grid(admin, range("PRS-01", today.minusDays(365), today)).andExpect(status().isOk());
    }

    @Test
    void 엑셀은_같은조건_정렬_보이는열_사용중지_설비도_고른다() throws Exception {
        seed();
        String body = "{\"equipCd\":\"PRS-01\",\"from\":\"" + day + "\",\"to\":\"" + day + "\",\"kind\":\"MWO\",\"columns\":[\"refNo\",\"kind\",\"detail\"]}";
        byte[] x = mvc.perform(json(MockMvcRequestBuilders.post(C + "/history/export"), body).with(admin)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(x))) {
            Sheet s = wb.getSheetAt(0);
            assertThat(s.getLastRowNum()).isEqualTo(1);
            Row h = s.getRow(0);
            assertThat(List.of(h.getCell(0).getStringCellValue(), h.getCell(1).getStringCellValue(), h.getCell(2).getStringCellValue()))
                    .containsExactly("번호", "종류", "상태·수량");
            assertThat(s.getRow(1).getCell(1).getStringCellValue()).isEqualTo("작업지시");                     // 코드 이름
            assertThat(s.getRow(1).getCell(2).getStringCellValue()).isEqualTo("개선 · 완료 · 정지 45분");
        }
        mvc.perform(json(MockMvcRequestBuilders.post(C + "/history/export"), "{\"equipCd\":\"PRS-01\",\"from\":\"" + day + "\",\"to\":\"" + day
                + "\",\"columns\":[\"mwoId\"]}").with(admin)).andExpect(status().isBadRequest());
        // 사용 중지 설비도 목록에 나온다(사용 중 먼저)
        accounts.exec(comp, "update mst_equip set use_yn = 'N' where equip_id = ?", prs2);
        mvc.perform(get(C + "/equips").param("keyword", "prs").with(admin))
                .andExpect(jsonPath("$.data[*].equipCd").value(org.hamcrest.Matchers.contains("PRS-01", "PRS-02")))
                .andExpect(jsonPath("$.data[1].useYn").value("N"));
        summary(admin, "PRS-02", today.minusDays(30), today).andExpect(status().isOk()).andExpect(jsonPath("$.data.mwo.total").value(1));
    }

    @Test
    void 권한없으면_403_B사로_A사_설비는_404() throws Exception {
        seed();
        long viewer = accounts.newUser(comp, "viewer1", PW);
        accounts.grant(comp, viewer, "VIEW", "cms-equip-card", "SEARCH");
        RequestPostProcessor vw = accounts.as(comp, viewer, "viewer1");
        summary(vw, "PRS-01", today, today).andExpect(status().isOk());                    // D38: 카드 SEARCH 만으로 읽기
        // 링크한 화면은 원래 권한 그대로 — 카드 권한으로 작업지시 상세·목록을 못 읽는다(우회 없음)
        long mwo = accounts.query(comp, "select min(mwo_id) from cms_mwo where equip_id = ?", Long.class, prs1);
        grid(vw, range("PRS-01", today.minusDays(30), today, "kind", "MWO")).andExpect(status().isOk());
        mvc.perform(get(M + "/" + mwo).with(vw)).andExpect(status().isForbidden());
        mvc.perform(get(M).param("from", day.toString()).param("to", today.toString()).with(vw)).andExpect(status().isForbidden());
        mvc.perform(get("/page/cms-mwo").param("mwo", String.valueOf(mwo)).with(vw)).andExpect(status().is4xxClientError());
        mvc.perform(json(MockMvcRequestBuilders.post(C + "/history/export"), "{\"equipCd\":\"PRS-01\",\"from\":\"" + today + "\",\"to\":\"" + today + "\"}").with(vw))
                .andExpect(status().isForbidden());
        long none = accounts.newUser(comp, "none1", PW);
        accounts.grant(comp, none, "OTHER", "cms-mwo", "SEARCH");
        RequestPostProcessor n = accounts.as(comp, none, "none1");
        summary(n, "PRS-01", today, today).andExpect(status().isForbidden());
        grid(n, range("PRS-01", today, today)).andExpect(status().isForbidden());
        mvc.perform(get(C + "/equips").with(n)).andExpect(status().isForbidden());
        // B사: A사 설비 코드는 B사에 없다(같은 코드를 B사가 만들면 B사 것만 — RLS)
        String other = accounts.newComp("CL");
        long bu = accounts.newUser(other, "admin1", PW);
        grant(other, bu);
        accounts.addPlant(other, "P1", "1공장");
        RequestPostProcessor b = accounts.as(other, bu, "admin1");
        summary(b, "PRS-01", today.minusDays(30), today).andExpect(status().isNotFound());
        grid(b, range("PRS-01", today.minusDays(30), today)).andExpect(status().isNotFound());
        mvc.perform(get(C + "/equips").with(b)).andExpect(jsonPath("$.data.length()").value(0));
    }
}
