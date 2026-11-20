package io.smarthappy.module.cms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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
 * 수집 COUNT 조회·정정 (docs/25 4.36) — 공장 현지 기간·설비·구분 조건, 정렬 허용 목록, 정정 = 역분개 행(시각은 원 행 그대로·사유 필수),
 * 두 번 정정·역분개 행 정정 409, 동시 정정 하나만, A사로 B사(조회 0·정정 404), 권한(SEARCH 만 = 정정 403).
 */
@AutoConfigureMockMvc
class EquipCountTest extends PostgresTestBase {

    private static final String PW = "Willow-ledger-27";
    private static final String API = "/api/cms/equip-counts";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    String comp;
    RequestPostProcessor admin;
    RequestPostProcessor viewer;
    long press;
    long weld;
    long p2Equip;
    long c1;
    long c2;
    long c3;
    long cEarly;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("EC");
        long u = accounts.newUser(comp, "admin1", PW);
        accounts.grant(comp, u, "PRD", EquipCountService.PROG, "SEARCH", "SAVE", "EXCEL");
        admin = accounts.as(comp, u, "admin1");
        long v = accounts.newUser(comp, "viewer1", PW);
        accounts.grant(comp, v, "VIEW", EquipCountService.PROG, "SEARCH");
        viewer = accounts.as(comp, v, "viewer1");
        accounts.addPlant(comp, "P1", "1공장");              // 회사 시간대(Asia/Seoul)
        accounts.addPlant(comp, "P2", "2공장");
        accounts.exec(comp, "update sys_plant set time_zone = 'UTC' where plant_cd = 'P2'");
        long cls = accounts.query(comp, "insert into mst_equip_class (comp_cd, class_cd, class_nm, created_by, updated_by) values (?, 'PRESS', '프레스', 't', 't') returning class_id",
                Long.class, comp);
        press = equip(comp, "P1", "PRS-01", cls);
        weld = equip(comp, "P1", "WLD-01", cls);
        p2Equip = equip(comp, "P2", "PRS-21", cls);
        c1 = count(comp, press, "GOOD", "12", "2026-10-20 08:00+09");
        c2 = count(comp, press, "SCRAP", "2", "2026-10-20 09:00+09");
        c3 = count(comp, weld, "GOOD", "5", "2026-10-20 00:30+09");      // UTC 로는 19일 — 공장 현지 20일
        cEarly = count(comp, press, "GOOD", "7", "2026-10-19 23:30+09");
        count(comp, p2Equip, "GOOD", "9", "2026-10-20 08:00+00");
    }

    private long equip(String c, String plant, String cd, long cls) {
        return accounts.query(c, "insert into mst_equip (comp_cd, plant_cd, equip_cd, equip_nm, class_id, created_by, updated_by) values (?, ?, ?, ?, ?, 't', 't') returning equip_id",
                Long.class, c, plant, cd, cd + " 설비", cls);
    }

    private long count(String c, long equipId, String metric, String qty, String at) {
        return accounts.query(c, """
                insert into cms_equip_count (comp_cd, equip_id, metric_cd, qty, occurred_at, entry_method, recorded_by)
                values (?, ?, ?, ?::numeric, ?::timestamptz, 'AUTO', 'collector') returning count_id""", Long.class, c, equipId, metric, qty, at);
    }

    private ResultActions grid(RequestPostProcessor who, String query) throws Exception {
        return mvc.perform(get(API + "/grid?" + query).with(who));
    }

    private ResultActions correct(RequestPostProcessor who, long id, String body) throws Exception {
        return mvc.perform(post(API + "/" + id + "/correct").with(csrf()).with(who).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private int reversals(String c, long id) {
        return accounts.query(c, "select count(*) from cms_equip_count where reversal_of_id = ?", Integer.class, id);
    }

    @Test
    void 공장_현지_하루_기간_설비_구분_조건과_기본_순서() throws Exception {
        // P1(Asia/Seoul) 20일 = 3행(00:30 행 포함, 19일 23:30 행 빠짐), 최신 발생 순, P2 설비 없음
        grid(admin, "plantCd=P1&from=2026-10-20&to=2026-10-20").andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.list[*].countId").value(Matchers.contains((int) c2, (int) c1, (int) c3)))
                .andExpect(jsonPath("$.list[0].state").value("NORMAL")).andExpect(jsonPath("$.list[0].equipCd").value("PRS-01"));
        grid(admin, "plantCd=P1&from=2026-10-20&to=2026-10-20&equipId=" + press + "&metricCd=GOOD")
                .andExpect(jsonPath("$.list[*].countId").value(Matchers.contains((int) c1)));
        grid(admin, "plantCd=P1&from=2026-10-19&to=2026-10-20&sort=equipCd,desc&sort=occurredAt,asc")
                .andExpect(jsonPath("$.list[0].equipCd").value("WLD-01")).andExpect(jsonPath("$.list[1].countId").value((int) cEarly));
        // P2 는 UTC 하루
        grid(admin, "plantCd=P2&from=2026-10-20&to=2026-10-20").andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.list[0].equipCd").value("PRS-21"));
        mvc.perform(get(API + "/columns").with(admin)).andExpect(status().isOk());
    }

    @Test
    void 조건_검사() throws Exception {
        grid(admin, "from=2026-10-20&to=2026-10-20").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("plantCd"));
        grid(admin, "plantCd=P1&to=2026-10-20").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("from")).andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        grid(admin, "plantCd=P1&from=2026-10-21&to=2026-10-20").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("INVALID_RANGE"));
        grid(admin, "plantCd=P1&from=2026-01-01&to=2026-10-20").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("RANGE_TOO_LONG"));
        grid(admin, "plantCd=P1&from=2026-10-20&to=2026-10-20&metricCd=COUNT").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("metricCd"));
        grid(admin, "plantCd=P1&from=2026-10-x&to=2026-10-20").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("FORMAT"));
        grid(admin, "plantCd=P1&from=2026-10-20&to=2026-10-20&sort=qty,desc").andExpect(status().isBadRequest());
        mvc.perform(get(API + "/equips").with(admin)).andExpect(status().isBadRequest());
    }

    @Test
    void 정정은_역분개_행_하나_시각은_원_행_그대로_두_번과_역분개_행은_409() throws Exception {
        correct(admin, c1, "{\"reason\":\"수집기 중복 이벤트\"}").andExpect(status().isOk());
        String row = accounts.query(comp, """
                select concat_ws('|', equip_id, metric_cd, qty::text, entry_method, reason, recorded_by,
                                 occurred_at = (select occurred_at from cms_equip_count where count_id = ?))
                  from cms_equip_count where reversal_of_id = ?""", String.class, c1, c1);
        assertThat(row).isEqualTo(press + "|GOOD|-12.000000|CORRECT|수집기 중복 이벤트|admin1|t");
        // 원 행은 그대로(수량·방식), 상태만 정정됨 — 같은 날 순합 0(POP 제안·KPI 가 더해 읽는 값)
        assertThat(accounts.query(comp, "select qty::text || entry_method from cms_equip_count where count_id = ?", String.class, c1))
                .isEqualTo("12.000000AUTO");
        assertThat(accounts.query(comp, "select sum(qty)::int from cms_equip_count where count_id = ? or reversal_of_id = ?", Integer.class,
                c1, c1)).isZero();
        mvc.perform(get(API + "/" + c1).with(admin)).andExpect(jsonPath("$.data.state").value("CORRECTED"))
                .andExpect(jsonPath("$.data.reversedById").isNumber());
        long rev = accounts.query(comp, "select count_id from cms_equip_count where reversal_of_id = ?", Long.class, c1);
        mvc.perform(get(API + "/" + rev).with(admin)).andExpect(jsonPath("$.data.state").value("REVERSAL"))
                .andExpect(jsonPath("$.data.reversalOfId").value((int) c1));
        // 두 번 정정·역분개 행 정정 = 409 STATE
        correct(admin, c1, "{\"reason\":\"다시\"}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors[0].field").value("countId")).andExpect(jsonPath("$.errors[0].code").value("STATE"));
        correct(admin, rev, "{\"reason\":\"되돌리기의 되돌리기\"}").andExpect(status().isConflict());
        assertThat(reversals(comp, c1)).isEqualTo(1);
        assertThat(reversals(comp, rev)).isZero();
        // 목록: 정정됨·정정 행이 같이 보인다(정정 행도 발생 시각이 원 행이라 같은 날)
        grid(admin, "plantCd=P1&from=2026-10-20&to=2026-10-20&equipId=" + press + "&metricCd=GOOD").andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.list[*].state").value(Matchers.containsInAnyOrder("CORRECTED", "REVERSAL")));
    }

    @Test
    void 사유_검사와_없는_행() throws Exception {
        correct(admin, c2, "{\"reason\":\"   \"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("reason")).andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        correct(admin, c2, "{}").andExpect(status().isBadRequest());
        correct(admin, c2, "{\"reason\":\"" + "가".repeat(501) + "\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("TOO_LONG"));
        mvc.perform(post(API + "/" + c2 + "/correct").with(csrf()).with(admin)).andExpect(status().isBadRequest());
        correct(admin, 999999999L, "{\"reason\":\"없는 행\"}").andExpect(status().isNotFound());
        assertThat(reversals(comp, c2)).isZero();
    }

    @Test
    void 동시에_두_번_정정해도_역분개는_하나() throws Exception {
        CountDownLatch go = new CountDownLatch(1);
        List<CompletableFuture<Integer>> calls = List.of(1, 2).stream().map(i -> CompletableFuture.supplyAsync(() -> {
            try {
                go.await(10, TimeUnit.SECONDS);
                return correct(admin, c2, "{\"reason\":\"동시 " + i + "\"}").andReturn().getResponse().getStatus();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        })).toList();
        go.countDown();
        List<Integer> codes = calls.stream().map(f -> f.orTimeout(20, TimeUnit.SECONDS).join()).toList();
        assertThat(codes).containsExactlyInAnyOrder(200, 409);
        assertThat(reversals(comp, c2)).isEqualTo(1);
    }

    @Test
    void A사로_B사_행은_안_보이고_정정은_404_권한() throws Exception {
        String other = accounts.newComp("ED");
        accounts.addPlant(other, "P1", "1공장");
        long cls = accounts.query(other, "insert into mst_equip_class (comp_cd, class_cd, class_nm, created_by, updated_by) values (?, 'PRESS', '프레스', 't', 't') returning class_id",
                Long.class, other);
        long theirEquip = equip(other, "P1", "PRS-01", cls);
        long theirs = count(other, theirEquip, "GOOD", "3", "2026-10-20 08:00+09");
        // 같은 공장 코드·설비 코드라도 A사 조회에 B사 행 없음, 상세·정정 404, B사 원장 그대로
        grid(admin, "plantCd=P1&from=2026-10-20&to=2026-10-20").andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.list[*].countId").value(Matchers.not(Matchers.hasItem((int) theirs))));
        mvc.perform(get(API + "/" + theirs).with(admin)).andExpect(status().isNotFound());
        correct(admin, theirs, "{\"reason\":\"남의 행\"}").andExpect(status().isNotFound());
        assertThat(reversals(other, theirs)).isZero();
        // 조회만 있는 사용자는 정정 403, 권한 없는 사용자는 조회도 403
        grid(viewer, "plantCd=P1&from=2026-10-20&to=2026-10-20").andExpect(status().isOk());
        correct(viewer, c3, "{\"reason\":\"조회 권한만\"}").andExpect(status().isForbidden());
        long nobody = accounts.newUser(comp, "nobody1", PW);
        grid(accounts.as(comp, nobody, "nobody1"), "plantCd=P1&from=2026-10-20&to=2026-10-20").andExpect(status().isForbidden());
        assertThat(reversals(comp, c3)).isZero();
    }

    @Test
    void 엑셀_grid_와_같은_조건_상태와_구분은_이름_권한() throws Exception {
        // docs/25 4.40 — 공장·기간 필수, 정정됨·정정 행 상태와 양품·불량 구분은 화면 이름 그대로
        correct(admin, c1, "{\"reason\":\"중복\"}").andExpect(status().isOk());
        byte[] x = mvc.perform(post(API + "/export").with(csrf()).with(admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"plantCd\":\"P1\",\"from\":\"2026-10-20\",\"to\":\"2026-10-20\",\"columns\":[\"equipCd\",\"state\",\"metricCd\",\"qty\"]}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try (org.apache.poi.xssf.usermodel.XSSFWorkbook wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(x))) {
            org.apache.poi.ss.usermodel.Sheet sh = wb.getSheetAt(0);
            assertThat(sh.getLastRowNum()).isEqualTo(4);                               // 머리줄 + 3행 + 정정 행
            java.util.Set<String> states = new java.util.HashSet<>();
            java.util.Set<String> metrics = new java.util.HashSet<>();
            for (int i = 1; i <= sh.getLastRowNum(); i++) {
                states.add(sh.getRow(i).getCell(1).getStringCellValue());
                metrics.add(sh.getRow(i).getCell(2).getStringCellValue());
            }
            assertThat(states).containsExactlyInAnyOrder("정상", "정정됨", "정정 행");
            assertThat(metrics).containsExactlyInAnyOrder("양품", "불량");
        }
        mvc.perform(post(API + "/export").with(csrf()).with(admin).contentType(MediaType.APPLICATION_JSON).content("{\"plantCd\":\"P1\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(API + "/export").with(csrf()).with(admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"plantCd\":\"P1\",\"from\":\"2026-10-20\",\"to\":\"2026-10-20\",\"equipId\":\"x\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(API + "/export").with(csrf()).with(viewer).contentType(MediaType.APPLICATION_JSON)
                .content("{\"plantCd\":\"P1\",\"from\":\"2026-10-20\",\"to\":\"2026-10-20\"}")).andExpect(status().isForbidden());
    }
}
