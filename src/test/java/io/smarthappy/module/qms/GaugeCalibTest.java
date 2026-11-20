package io.smarthappy.module.qms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.module.admin.IntegrityDtos;

/**
 * P3 QMS Q2a 계측기 교정 — 정상 흐름(추가·무효·최근 교정일 파생·주기 스냅샷·판정) + 이상 사용(A~K: A사로 B사, 직접 SQL 원장·회사·
 * 파생값, 입력 경계, 권한, 멱등, 열려 있던 화면의 동시 저장). 목록 SQL 판정과 GaugeStatus 가 같은지도 맞춰 본다.
 */
class GaugeCalibTest extends QmsTestBase {

    @org.springframework.beans.factory.annotation.Autowired
    io.smarthappy.module.admin.IntegrityCheckService integrity;

    @org.springframework.beans.factory.annotation.Autowired
    io.smarthappy.platform.tenant.TenantRunner runner;

    static final String G = "/api/qms/gauges";

    long gauge;

    @BeforeEach
    void gaugeSetUp() {
        accounts.grant(comp, userId, "QA", "qms-gauge-calib", "SEARCH", "SAVE", "DELETE", "EXCEL");
        accounts.grant(comp, userId, "QA", "mst-asset", "SEARCH", "SAVE", "DELETE");
        gauge = asset(comp, "GG1", "GAUGE", 365);
        accounts.exec(comp, """
                insert into mst_cust (comp_cd, cust_cd, cust_nm, cust_type, created_by, updated_by)
                values (?, 'LAB1', '교정 기관', 'PURCHASE', 't', 't')""", comp);
    }

    long asset(String c, String cd, String type, Integer cycle) {
        accounts.exec(c, """
                insert into mst_phys_asset (comp_cd, asset_cd, asset_type, asset_nm, serial_no, calib_cycle_days, created_by, updated_by)
                values (?, ?, ?, ?, 'SN-' || ?, ?, 't', 't')""", c, cd, type, cd + " 계측기", cd, cycle);
        return accounts.query(c, "select asset_id from mst_phys_asset where asset_cd = ?", Long.class, cd);
    }

    ResultActions addCalib(long assetId, LocalDate date, String result, String extra) throws Exception {
        return postJson(G + "/" + assetId + "/calibs", "{\"calibDate\":" + q(date) + ",\"result\":" + q(result)
                + ",\"agency\":\"INTERNAL\"" + (extra == null ? "" : "," + extra) + "}");
    }

    long calib(long assetId, LocalDate date, String result, String extra) throws Exception {
        return id(addCalib(assetId, date, result, extra));
    }

    String detail(long assetId) throws Exception {
        return mvc.perform(get(G + "/" + assetId).with(admin)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    LocalDate lastCalib(long assetId) {
        return accounts.query(comp, "select last_calib_date from mst_phys_asset where asset_id = ?", LocalDate.class, assetId);
    }

    int assetVer(long assetId) {
        return accounts.query(comp, "select row_ver from mst_phys_asset where asset_id = ?", Integer.class, assetId);
    }

    // ---------- 정상 흐름 ----------

    @Test
    void 교정_추가_무효_흐름_최근교정일_파생_다음교정일_주기_스냅샷() throws Exception {
        mvc.perform(get(G + "/" + gauge).with(admin)).andExpect(jsonPath("$.data.gauge.status").value("NO_RECORD"))
                .andExpect(jsonPath("$.data.history.length()").value(0));
        int ver0 = assetVer(gauge);
        LocalDate d1 = today.minusDays(10);
        long c1 = calib(gauge, d1, "OK", "\"certNo\":\"C-001\"");
        // 주기를 비우면 계측기 주기(365), 다음 교정일 = 교정일 + 주기, 최근 교정일·row_ver 는 트리거가
        String body = detail(gauge);
        assertThat((String) JsonPath.read(body, "$.data.gauge.status")).isEqualTo("OK");
        assertThat((String) JsonPath.read(body, "$.data.history[0].nextDueDate")).isEqualTo(d1.plusDays(365).toString());
        assertThat((Integer) JsonPath.read(body, "$.data.history[0].cycleDays")).isEqualTo(365);
        assertThat((Integer) JsonPath.read(body, "$.data.gauge.daysLeft")).isEqualTo(355);
        assertThat(lastCalib(gauge)).isEqualTo(d1);
        assertThat(assetVer(gauge)).isEqualTo(ver0 + 1);
        String createdBy = accounts.query(comp, "select updated_by from mst_phys_asset where asset_id = ?", String.class, gauge);
        assertThat(createdBy).isEqualTo("admin1");

        // 계측기 주기를 30 으로 바꿔도 이미 넣은 기록의 다음 교정일은 그대로, 새 기록은 30
        int rv = assetVer(gauge);
        putJson(G + "/" + gauge, "{\"gaugeClass\":\"CALIPER\",\"calibCycleDays\":30,\"rowVer\":" + rv + "}").andExpect(status().isOk());
        assertThat(accounts.query(comp, "select next_due_date from qms_gauge_calib where calib_id = ?", LocalDate.class, c1))
                .isEqualTo(d1.plusDays(365));
        // 더 이른 날짜를 나중에 넣어도(소급) 최근 교정일·판정은 가장 늦은 교정일 기준
        long c2 = calib(gauge, today.minusDays(20), "OK", null);
        assertThat(lastCalib(gauge)).isEqualTo(d1);
        assertThat(accounts.query(comp, "select cycle_days from qms_gauge_calib where calib_id = ?", Integer.class, c2)).isEqualTo(30);
        mvc.perform(get(G + "/" + gauge).with(admin)).andExpect(jsonPath("$.data.gauge.status").value("OK"))
                .andExpect(jsonPath("$.data.gauge.gaugeClass").value("CALIPER"))
                .andExpect(jsonPath("$.data.history[0].calibId").value((int) c1));

        // 최근 기록 무효 → 최근 교정일·판정이 남은 기록(20일 전 + 30일 = 10일 뒤 기한 → 임박)으로
        postJson(G + "/calibs/" + c1 + "/void", "{\"reason\":\"성적서 오기입\"}").andExpect(status().isOk());
        assertThat(lastCalib(gauge)).isEqualTo(today.minusDays(20));
        mvc.perform(get(G + "/" + gauge).with(admin)).andExpect(jsonPath("$.data.gauge.status").value("DUE_SOON"))
                .andExpect(jsonPath("$.data.gauge.daysLeft").value(10))
                .andExpect(jsonPath("$.data.history[0].voidYn").value("Y"))
                .andExpect(jsonPath("$.data.history[0].voidReason").value("성적서 오기입"));
        mvc.perform(get(G).param("status", "DUE_SOON").with(admin)).andExpect(jsonPath("$.list[*].assetCd").value(hasItem("GG1")));
        // 마지막 유효 기록도 무효 → 기록 없음, 최근 교정일은 비움(유효 기록 0건이면 예전처럼 입력 가능)
        postJson(G + "/calibs/" + c2 + "/void", "{\"reason\":\"중복\"}").andExpect(status().isOk());
        assertThat(lastCalib(gauge)).isNull();
        mvc.perform(get(G + "/" + gauge).with(admin)).andExpect(jsonPath("$.data.gauge.status").value("NO_RECORD"));
        accounts.exec(comp, "update mst_phys_asset set last_calib_date = (now() at time zone 'Asia/Seoul')::date - 400 where asset_id = ?", gauge);
    }

    @Test
    void 제한사용_외부기관_NG_판정과_목록_조건() throws Exception {
        long g2 = asset(comp, "GG2", "GAUGE", 365);
        long g3 = asset(comp, "GG3", "GAUGE", 365);
        calib(gauge, today.minusDays(1), "LIMITED", "\"limitNote\":\"0~100 mm 만\",\"agency\":\"EXTERNAL\",\"custCd\":\"lab1\"");
        calib(g2, today.minusDays(1), "NG", null);
        calib(g3, today.minusDays(400), "OK", null);
        String body = detail(gauge);
        assertThat((String) JsonPath.read(body, "$.data.gauge.status")).isEqualTo("OK");
        assertThat((String) JsonPath.read(body, "$.data.gauge.lastLimitNote")).isEqualTo("0~100 mm 만");
        assertThat((String) JsonPath.read(body, "$.data.history[0].custCd")).isEqualTo("LAB1");
        assertThat((String) JsonPath.read(body, "$.data.history[0].agency")).isEqualTo("EXTERNAL");
        mvc.perform(get(G).param("status", "NG").with(admin)).andExpect(jsonPath("$.list[*].assetCd").value(contains("GG2")));
        mvc.perform(get(G).param("status", "OVERDUE").with(admin)).andExpect(jsonPath("$.list[*].assetCd").value(contains("GG3")))
                .andExpect(jsonPath("$.list[0].daysLeft").value(-35));
        mvc.perform(get(G).param("keyword", "sn-gg2").with(admin)).andExpect(jsonPath("$.total").value(1));
        // 사용 중 게이지만(금형은 목록에 없다), 사용 중지는 조건으로만
        asset(comp, "MD1", "MOLD", null);
        accounts.exec(comp, "update mst_phys_asset set use_yn = 'N', updated_by = 't' where asset_id = ?", g3);
        mvc.perform(get(G).with(admin)).andExpect(jsonPath("$.total").value(2));
        mvc.perform(get(G).param("withUnused", "true").with(admin)).andExpect(jsonPath("$.total").value(3));
        mvc.perform(get(G).param("status", "INACTIVE").with(admin)).andExpect(jsonPath("$.list[*].assetCd").value(contains("GG3")));
        mvc.perform(json(post(G + "/export"), "{\"status\":\"NG\"}").with(admin)).andExpect(status().isOk());
    }

    /** 목록 SQL 의 판정 조각과 GaugeStatus(상세)가 같은 답을 내는지 — 경계 날짜 묶음 */
    @Test
    void 목록_SQL_판정과_GaugeStatus가_같다() throws Exception {
        accounts.exec(comp, """
                insert into sys_policy (comp_cd, policy_key, policy_value, created_by, updated_by)
                values (?, 'QMS_CALIB_DUE_SOON_DAYS', '7', 't', 't')""", comp);
        int[] offsets = {-366, -365, -364, -358, -357, -356, -300};   // 주기 365 → 기한 = 오늘 -1, 0, +1, +7, +8, +9, +65
        for (int i = 0; i < offsets.length; i++) {
            long g = asset(comp, "GB" + i, "GAUGE", 365);
            calib(g, today.plusDays(offsets[i]), "OK", null);
        }
        asset(comp, "GBN", "GAUGE", 365);
        MvcResult m = mvc.perform(get(G).param("keyword", "GB").param("size", "50").with(admin)).andExpect(status().isOk()).andReturn();
        List<Map<String, Object>> rows = JsonPath.read(m.getResponse().getContentAsString(), "$.list");
        assertThat(rows).hasSize(offsets.length + 1);
        for (Map<String, Object> r : rows) {
            long id = ((Number) r.get("assetId")).longValue();
            String fromDetail = JsonPath.read(detail(id), "$.data.gauge.status");
            assertThat(r.get("status")).as(String.valueOf(r.get("assetCd"))).isEqualTo(fromDetail);
        }
        assertThat(rows.stream().map(r -> r.get("assetCd") + "=" + r.get("status")).toList())
                .contains("GB0=OVERDUE", "GB1=DUE_SOON", "GB2=DUE_SOON", "GB3=DUE_SOON", "GB4=OK", "GBN=NO_RECORD");
    }

    // ---------- 물리 자산 화면(P1)과의 관계 (D4) ----------

    @Test
    void 교정기록이_있으면_물리자산_화면에서_최근교정일_유형을_못_바꾼다_없으면_예전처럼() throws Exception {
        String put = "{\"assetCd\":\"GG1\",\"assetType\":\"%s\",\"assetNm\":\"GG1 계측기\",\"lastCalibDate\":%s,\"calibCycleDays\":365,"
                + "\"useYn\":\"Y\",\"rowVer\":%d}";
        // 기록 없음: 입력 허용(이전 데이터)
        putJson("/api/master/assets/" + gauge, put.formatted("GAUGE", q(today.minusDays(5)), assetVer(gauge))).andExpect(status().isOk());
        assertThat(lastCalib(gauge)).isEqualTo(today.minusDays(5));
        calib(gauge, today.minusDays(2), "OK", null);
        assertThat(lastCalib(gauge)).isEqualTo(today.minusDays(2));
        putJson("/api/master/assets/" + gauge, put.formatted("GAUGE", q(today.minusDays(1)), assetVer(gauge)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("lastCalibDate"))
                .andExpect(jsonPath("$.errors[0].code").value("CALIB_DERIVED"));
        putJson("/api/master/assets/" + gauge, put.formatted("JIG", q(today.minusDays(2)), assetVer(gauge)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("assetType"))
                .andExpect(jsonPath("$.errors[0].code").value("GAUGE_CALIB"));
        // 같은 값 그대로 보내는 일반 저장(이름 등)은 된다
        putJson("/api/master/assets/" + gauge, put.formatted("GAUGE", q(today.minusDays(2)), assetVer(gauge)).replace("GG1 계측기", "새 이름"))
                .andExpect(status().isOk());
        // 열려 있던 자산 화면(교정 추가 전 rowVer)으로 저장하면 409 — 교정 트리거가 row_ver 를 올렸다
        int stale = assetVer(gauge);
        calib(gauge, today.minusDays(1), "OK", null);
        putJson("/api/master/assets/" + gauge, put.formatted("GAUGE", q(today.minusDays(2)), stale)).andExpect(status().isConflict());
    }

    // ---------- 이상 사용: 입력 ----------

    @Test
    void 입력_경계_필수_형식_미래_범위_조건조작() throws Exception {
        addCalib(gauge, today.plusDays(1), "OK", null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("FUTURE"));
        addCalib(gauge, LocalDate.of(1999, 12, 31), "OK", null).andExpect(jsonPath("$.errors[0].code").value("FORMAT"));   // 2000년 전
        postJson(G + "/" + gauge + "/calibs", "{\"calibDate\":\"2026-13-01\",\"result\":\"OK\",\"agency\":\"INTERNAL\"}")
                .andExpect(jsonPath("$.errors[*].code").value(hasItem("FORMAT")));
        postJson(G + "/" + gauge + "/calibs", "{}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.length()").value(3));                      // 교정일·결과·기관
        addCalib(gauge, today, "PASS", null).andExpect(jsonPath("$.errors[0].field").value("result"));
        addCalib(gauge, today, "LIMITED", null).andExpect(jsonPath("$.errors[0].field").value("limitNote"))
                .andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        addCalib(gauge, today, "OK", "\"agency\":\"EXTERNAL\"").andExpect(jsonPath("$.errors[0].field").value("custCd"));
        addCalib(gauge, today, "OK", "\"agency\":\"EXTERNAL\",\"custCd\":\"NOPE\"").andExpect(jsonPath("$.errors[0].code").value("NO_SUCH_CODE"));
        addCalib(gauge, today, "OK", "\"cycleDays\":0").andExpect(jsonPath("$.errors[0].code").value("RANGE"));
        addCalib(gauge, today, "OK", "\"cycleDays\":3651").andExpect(jsonPath("$.errors[0].code").value("RANGE"));
        addCalib(gauge, today, "OK", "\"certNo\":\"" + "x".repeat(51) + "\"").andExpect(jsonPath("$.errors[0].code").value("TOO_LONG"));
        // 주기 없는 계측기는 주기 입력 필수
        long noCycle = asset(comp, "GG9", "GAUGE", null);
        addCalib(noCycle, today, "OK", null).andExpect(jsonPath("$.errors[0].field").value("cycleDays"));
        id(addCalib(noCycle, today, "OK", "\"cycleDays\":180"));
        // 사내 기관이면 거래처는 무시(저장 안 함), 제한 사용이 아니면 제한 내용 무시
        long c = calib(gauge, today, "OK", "\"custCd\":\"LAB1\",\"limitNote\":\"무시\"");
        assertThat(accounts.query(comp, "select count(*) from qms_gauge_calib where calib_id = ? and cust_id is null and limit_note is null",
                Integer.class, c)).isEqualTo(1);
        // 금형·없는 id → 404, 사용 중지 게이지 → 칸 오류
        long mold = asset(comp, "MD1", "MOLD", null);
        addCalib(mold, today, "OK", null).andExpect(status().isNotFound());
        addCalib(999_999_999L, today, "OK", null).andExpect(status().isNotFound());
        accounts.exec(comp, "update mst_phys_asset set use_yn = 'N', updated_by = 't' where asset_id = ?", gauge);
        addCalib(gauge, today, "OK", null).andExpect(jsonPath("$.errors[0].code").value("GAUGE_INACTIVE"));
        // 목록 조건 조작
        mvc.perform(get(G).param("status", "BAD").with(admin)).andExpect(status().isBadRequest());
        mvc.perform(get(G).param("keyword", "k".repeat(101)).with(admin)).andExpect(status().isBadRequest());
        mvc.perform(get(G).param("sort", "asset_id;drop table x,asc").with(admin)).andExpect(status().isBadRequest());
        mvc.perform(get(G).param("keyword", "%").with(admin)).andExpect(jsonPath("$.total").value(0));
        // 계측기 정보: 육안·없는 클래스·주기 범위
        int rv = assetVer(noCycle);
        putJson(G + "/" + noCycle, "{\"gaugeClass\":\"VISUAL\",\"rowVer\":" + rv + "}").andExpect(jsonPath("$.errors[0].field").value("gaugeClass"));
        putJson(G + "/" + noCycle, "{\"gaugeClass\":\"NOPE\",\"rowVer\":" + rv + "}").andExpect(jsonPath("$.errors[0].code").value("CODE"));
        putJson(G + "/" + noCycle, "{\"calibCycleDays\":0,\"rowVer\":" + rv + "}").andExpect(jsonPath("$.errors[0].code").value("RANGE"));
        putJson(G + "/" + noCycle, "{\"gaugeClass\":\"CMM\"}").andExpect(status().isBadRequest());
        putJson(G + "/" + mold, "{\"rowVer\":0}").andExpect(status().isNotFound());
    }

    @Test
    void 무효는_사유_필수_한번만_권한별_403_멱등키_409() throws Exception {
        long c = calib(gauge, today, "OK", null);
        postJson(G + "/calibs/" + c + "/void", "{\"reason\":\"  \"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("reason"));
        postJson(G + "/calibs/" + c + "/void", "{\"reason\":\"" + "r".repeat(501) + "\"}").andExpect(jsonPath("$.errors[0].code").value("TOO_LONG"));
        postJson(G + "/calibs/" + c + "/void", "{\"reason\":\"오기입\"}").andExpect(status().isOk());
        postJson(G + "/calibs/" + c + "/void", "{\"reason\":\"다시\"}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("QMS_CALIB_VOIDED"));
        postJson(G + "/calibs/999999999/void", "{\"reason\":\"x\"}").andExpect(status().isNotFound());

        String body = "{\"calibDate\":" + q(today) + ",\"result\":\"OK\",\"agency\":\"INTERNAL\"}";
        mvc.perform(json(post(G + "/" + gauge + "/calibs"), body).header("X-Idempotency-Key", "qms-calib-0001").with(admin))
                .andExpect(status().isOk());
        mvc.perform(json(post(G + "/" + gauge + "/calibs"), body).header("X-Idempotency-Key", "qms-calib-0001").with(admin))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DUPLICATE_REQUEST"));

        long reader = accounts.newUser(comp, "reader1", PW);
        accounts.grant(comp, reader, "READER", "qms-gauge-calib", "SEARCH");
        RequestPostProcessor rd = accounts.as(comp, reader, "reader1");
        long saver = accounts.newUser(comp, "saver1", PW);
        accounts.grant(comp, saver, "SAVER", "qms-gauge-calib", "SEARCH", "SAVE");
        RequestPostProcessor sv = accounts.as(comp, saver, "saver1");
        long live = calib(gauge, today.minusDays(1), "OK", null);
        mvc.perform(get(G + "/" + gauge).with(rd)).andExpect(status().isOk());
        mvc.perform(json(post(G + "/" + gauge + "/calibs"), body).with(rd)).andExpect(status().isForbidden());
        mvc.perform(json(put(G + "/" + gauge), "{\"rowVer\":0}").with(rd)).andExpect(status().isForbidden());
        mvc.perform(json(post(G + "/export"), "{}").with(rd)).andExpect(status().isForbidden());
        mvc.perform(json(post(G + "/calibs/" + live + "/void"), "{\"reason\":\"x\"}").with(sv)).andExpect(status().isForbidden());
        mvc.perform(json(post(G + "/" + gauge + "/calibs"), body).with(sv)).andExpect(status().isOk());
        // 교정 화면 권한이 없으면 성적서 첨부도 못 본다(첨부 권한은 대상 화면 기준)
        long other = accounts.newUser(comp, "other1", PW);
        accounts.grant(comp, other, "OTHER", "qms-sampling", "SEARCH");
        mvc.perform(get("/api/platform/files").param("targetType", "qms-gauge-calib").param("targetId", String.valueOf(live))
                .with(accounts.as(comp, other, "other1"))).andExpect(status().isForbidden());
    }

    @Test
    void 열려_있던_계측기_정보_저장은_교정_추가_뒤_409() throws Exception {
        int stale = assetVer(gauge);
        calib(gauge, today, "OK", null);
        putJson(G + "/" + gauge, "{\"gaugeClass\":\"CALIPER\",\"calibCycleDays\":30,\"rowVer\":" + stale + "}")
                .andExpect(status().isConflict());
        putJson(G + "/" + gauge, "{\"gaugeClass\":\"CALIPER\",\"calibCycleDays\":30,\"rowVer\":" + assetVer(gauge) + "}")
                .andExpect(status().isOk());
        // 회사가 추가한 계측기 클래스는 쓸 수 있고, 사용 중지하면 새로 고를 수 없지만 지금 값은 남는다(다른 칸 저장 가능)
        accounts.exec(comp, """
                insert into sys_comp_code (comp_cd, grp_cd, code, code_nm, sort_no, created_by, updated_by)
                values (?, 'GAUGE_CLASS', 'PIN', '핀 게이지', 20, 't', 't')""", comp);
        putJson(G + "/" + gauge, "{\"gaugeClass\":\"PIN\",\"calibCycleDays\":30,\"rowVer\":" + assetVer(gauge) + "}").andExpect(status().isOk());
        accounts.exec(comp, "update sys_comp_code set use_yn = 'N' where code = 'PIN'");
        putJson(G + "/" + gauge, "{\"gaugeClass\":\"PIN\",\"calibCycleDays\":60,\"rowVer\":" + assetVer(gauge) + "}").andExpect(status().isOk());
        long g2 = asset(comp, "GG2", "GAUGE", 365);
        putJson(G + "/" + g2, "{\"gaugeClass\":\"PIN\",\"rowVer\":" + assetVer(g2) + "}").andExpect(jsonPath("$.errors[0].code").value("CODE"));
    }

    // ---------- 이상 사용: A사로 B사 ----------

    @Test
    void B사_사용자는_A사_계측기_교정기록을_보지도_쓰지도_못한다() throws Exception {
        long c = calib(gauge, today, "OK", null);
        String other = accounts.newComp("QM");
        long bUser = accounts.newUser(other, "admin1", PW);
        accounts.grant(other, bUser, "QA", "qms-gauge-calib", "SEARCH", "SAVE", "DELETE", "EXCEL");
        RequestPostProcessor b = accounts.as(other, bUser, "admin1");
        mvc.perform(get(G).with(b)).andExpect(jsonPath("$.total").value(0));
        mvc.perform(get(G + "/" + gauge).with(b)).andExpect(status().isNotFound());
        mvc.perform(json(post(G + "/" + gauge + "/calibs"), "{\"calibDate\":" + q(today) + ",\"result\":\"OK\",\"agency\":\"INTERNAL\"}")
                .with(b)).andExpect(status().isNotFound());
        mvc.perform(json(post(G + "/calibs/" + c + "/void"), "{\"reason\":\"x\"}").with(b)).andExpect(status().isNotFound());
        mvc.perform(json(put(G + "/" + gauge), "{\"gaugeClass\":\"CMM\",\"rowVer\":" + assetVer(gauge) + "}").with(b))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/platform/files").param("targetType", "qms-gauge-calib").param("targetId", String.valueOf(c)).with(b))
                .andExpect(jsonPath("$.list.length()").value(0));
        // B사 자기 계측기에 A사 거래처 코드(B사엔 없음) → 칸 오류
        long bGauge = asset(other, "GG1", "GAUGE", 365);
        mvc.perform(json(post(G + "/" + bGauge + "/calibs"), "{\"calibDate\":" + q(today)
                + ",\"result\":\"OK\",\"agency\":\"EXTERNAL\",\"custCd\":\"LAB1\"}").with(b))
                .andExpect(jsonPath("$.errors[0].code").value("NO_SUCH_CODE"));
        // 직접 SQL: B사 범위에서 A사 게이지로 기록, A사 거래처로 B사 기록, A사 기록 무효 시도(보이지 않아 0건)
        long aCust = accounts.query(comp, "select cust_id from mst_cust where cust_cd = 'LAB1'", Long.class);
        constraint(other, "fk_qms_gauge_calib_asset_comp", () -> accounts.exec(other, """
                insert into qms_gauge_calib (comp_cd, asset_id, calib_date, result, agency, cycle_days, created_by)
                values (?, ?, (now() at time zone 'Asia/Seoul')::date, 'OK', 'INTERNAL', 365, 't')""", other, gauge));
        constraint(other, "fk_qms_gauge_calib_cust_comp", () -> accounts.exec(other, """
                insert into qms_gauge_calib (comp_cd, asset_id, calib_date, result, agency, cust_id, cycle_days, created_by)
                values (?, ?, (now() at time zone 'Asia/Seoul')::date, 'OK', 'EXTERNAL', ?, 365, 't')""", other, bGauge, aCust));
        // A사 comp_cd 를 적어 넣어도 같은 회사 확인 트리거가 RLS(42501)보다 먼저 막는다(SKILL: 트리거가 먼저 23503)
        constraint(other, "fk_qms_gauge_calib_asset_comp", () -> accounts.exec(other, """
                insert into qms_gauge_calib (comp_cd, asset_id, calib_date, result, agency, cycle_days, created_by)
                values (?, ?, (now() at time zone 'Asia/Seoul')::date, 'OK', 'INTERNAL', 365, 't')""", comp, bGauge));
        assertThat(accounts.exec(other, "update qms_gauge_calib set void_yn = 'Y', void_reason = 'x', voided_by = 't', voided_at = now() "
                + "where calib_id = ?", c)).isZero();
        assertThat(accounts.query(comp, "select void_yn from qms_gauge_calib where calib_id = ?", String.class, c)).isEqualTo("N");
    }

    // ---------- 이상 사용: 직접 SQL (원장 변형·파생값·계측기 클래스) ----------

    @Test
    void 직접SQL_교정기록은_추가와_무효_한번만_삭제_금지_파생값_보호() throws Exception {
        long c = calib(gauge, today.minusDays(3), "OK", null);
        constraint(comp, "ck_qms_gauge_calib_ledger", () -> accounts.exec(comp,
                "update qms_gauge_calib set result = 'NG' where calib_id = ?", c));
        constraint(comp, "ck_qms_gauge_calib_ledger", () -> accounts.exec(comp,
                "update qms_gauge_calib set calib_date = calib_date - 1 where calib_id = ?", c));
        constraint(comp, "ck_qms_gauge_calib_ledger", () -> accounts.exec(comp,
                "update qms_gauge_calib set cycle_days = 30 where calib_id = ?", c));
        constraint(comp, "ck_qms_gauge_calib_void", () -> accounts.exec(comp,
                "update qms_gauge_calib set void_yn = 'Y', voided_by = 't', voided_at = now() where calib_id = ?", c));
        sqlState("428C9", () -> accounts.exec(comp, "update qms_gauge_calib set next_due_date = (now() at time zone 'Asia/Seoul')::date where calib_id = ?", c));
        sqlState("42501", () -> accounts.exec(comp, "delete from qms_gauge_calib where calib_id = ?", c));
        accounts.exec(comp, "update qms_gauge_calib set void_yn = 'Y', void_reason = 'x', voided_by = 't', voided_at = now() where calib_id = ?", c);
        constraint(comp, "ck_qms_gauge_calib_ledger", () -> accounts.exec(comp,
                "update qms_gauge_calib set void_yn = 'N', void_reason = null, voided_by = null, voided_at = null where calib_id = ?", c));
        constraint(comp, "ck_qms_gauge_calib_ledger", () -> accounts.exec(comp,
                "update qms_gauge_calib set void_reason = '바꿈' where calib_id = ?", c));
        constraint(comp, "ck_qms_gauge_calib_ledger", () -> accounts.exec(comp, """
                insert into qms_gauge_calib (comp_cd, asset_id, calib_date, result, agency, cycle_days, void_yn, void_reason, voided_by,
                                             voided_at, created_by)
                values (?, ?, (now() at time zone 'Asia/Seoul')::date, 'OK', 'INTERNAL', 365, 'Y', 'x', 't', now(), 't')""", comp, gauge));
        constraint(comp, "ck_qms_gauge_calib_limit", () -> accounts.exec(comp, """
                insert into qms_gauge_calib (comp_cd, asset_id, calib_date, result, agency, cycle_days, created_by)
                values (?, ?, (now() at time zone 'Asia/Seoul')::date, 'LIMITED', 'INTERNAL', 365, 't')""", comp, gauge));
        constraint(comp, "ck_qms_gauge_calib_agency", () -> accounts.exec(comp, """
                insert into qms_gauge_calib (comp_cd, asset_id, calib_date, result, agency, cust_id, cycle_days, created_by)
                values (?, ?, (now() at time zone 'Asia/Seoul')::date, 'OK', 'INTERNAL', (select cust_id from mst_cust where cust_cd = 'LAB1'), 365, 't')""", comp, gauge));
        long mold = asset(comp, "MD1", "MOLD", null);
        constraint(comp, "fk_qms_gauge_calib_asset_comp", () -> accounts.exec(comp, """
                insert into qms_gauge_calib (comp_cd, asset_id, calib_date, result, agency, cycle_days, created_by)
                values (?, ?, (now() at time zone 'Asia/Seoul')::date, 'OK', 'INTERNAL', 365, 't')""", comp, mold));

        // 파생값: 유효 기록이 있으면 직접 바꿀 수 없다, 유형 변경도
        calib(gauge, today.minusDays(1), "OK", null);
        constraint(comp, "ck_mst_phys_asset_calib_derived", () -> accounts.exec(comp,
                "update mst_phys_asset set last_calib_date = (now() at time zone 'Asia/Seoul')::date - 100 where asset_id = ?", gauge));
        constraint(comp, "ck_mst_phys_asset_calib_derived", () -> accounts.exec(comp,
                "update mst_phys_asset set last_calib_date = null where asset_id = ?", gauge));
        constraint(comp, "ck_mst_phys_asset_gauge_calib", () -> accounts.exec(comp,
                "update mst_phys_asset set asset_type = 'JIG' where asset_id = ?", gauge));
        // 계측기 클래스: 게이지만·육안 아님·쓸 수 있는 코드만
        constraint(comp, "ck_mst_phys_asset_gauge_class", () -> accounts.exec(comp,
                "update mst_phys_asset set gauge_class = 'CALIPER' where asset_id = ?", mold));
        constraint(comp, "ck_mst_phys_asset_gauge_class", () -> accounts.exec(comp,
                "update mst_phys_asset set gauge_class = 'VISUAL' where asset_id = ?", gauge));
        constraint(comp, "fk_mst_phys_asset_gauge_class", () -> accounts.exec(comp,
                "update mst_phys_asset set gauge_class = 'NOPE' where asset_id = ?", gauge));
        assertThat(accounts.exec(comp, "update mst_phys_asset set gauge_class = 'CMM' where asset_id = ?", gauge)).isEqualTo(1);
        mvc.perform(get(G).param("gaugeClass", "CMM").with(admin)).andExpect(jsonPath("$.total").value(1));
        mvc.perform(get(G).param("gaugeClass", "CALIPER").with(admin)).andExpect(jsonPath("$.list[*].assetCd").value(not(hasItem("GG1"))));
    }

    @Test
    void 전자서명_정책_Y_교정_등록은_승인_서명_그_교정_행에만_묶인다() throws Exception {
        policy("QMS_ESIGN_REQUIRED", "Y");
        MockHttpSession s = new MockHttpSession();
        addCalib(gauge, today.minusDays(10), "OK", null).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("QMS_ESIGN_REQUIRED"));
        long gauge2 = asset(comp, "GG2", "GAUGE", 365);
        // 다른 계측기에 묶인 토큰은 403, 그 요청은 롤백(교정 행 없음)
        postJson(s, G + "/" + gauge + "/calibs", "{\"calibDate\":" + q(today.minusDays(10)) + ",\"result\":\"OK\",\"agency\":\"INTERNAL\","
                + "\"esignToken\":" + q(esignToken(s, "APPROVE", "qms_gauge", gauge2)) + "}").andExpect(status().isForbidden());
        assertThat(accounts.query(comp, "select count(*) from qms_gauge_calib where asset_id = ?", Long.class, gauge)).isZero();
        long c1 = id(postJson(s, G + "/" + gauge + "/calibs", "{\"calibDate\":" + q(today.minusDays(10))
                + ",\"result\":\"OK\",\"agency\":\"INTERNAL\",\"certNo\":\"C|1\",\"remark\":\"줄\\n바꿈\","
                + "\"esignToken\":" + q(esignToken(s, "APPROVE", "qms_gauge", gauge)) + "}"));
        long c2 = id(postJson(s, G + "/" + gauge + "/calibs", "{\"calibDate\":" + q(today.minusDays(1)) + ",\"result\":\"NG\",\"agency\":\"INTERNAL\","
                + "\"esignToken\":" + q(esignToken(s, "APPROVE", "qms_gauge", gauge)) + "}"));
        assertThat(esignCount("qms_gauge", gauge)).isEqualTo(2);
        String d = detail(gauge);
        assertThat((Boolean) JsonPath.read(d, "$.data.esignRequired")).isTrue();
        List<Integer> ids = JsonPath.read(d, "$.data.signs[*].calibId");
        assertThat(ids).containsExactlyInAnyOrder((int) c1, (int) c2);
        assertThat((List<String>) JsonPath.read(d, "$.data.signs[*].sign.meaningCd")).containsOnly("APPROVE");
        // 무효 처리해도 서명은 그 교정 행에 그대로(내용 정규 글자에 무효 여부는 없음)
        postJson(G + "/calibs/" + c2 + "/void", "{\"reason\":\"성적서 오기입\"}").andExpect(status().isOk());
        assertThat((List<Integer>) JsonPath.read(detail(gauge), "$.data.signs[*].calibId")).containsExactlyInAnyOrder((int) c1, (int) c2);
        // 기록 무결성 점검(docs/52 Y1): 서명 2건 = 지금 교정 내용 → 일치. 교정 칸을 바꾸면(정상 경로에는 없는 변경) "서명 뒤 바뀜"
        IntegrityDtos.Check sig = runner.call(comp, () -> integrity.run()).checks().stream()
                .filter(c -> c.key().equals("ESIGN")).findFirst().orElseThrow();
        assertThat(sig.items()).filteredOn(i -> i.subject().equals("qms_gauge")).extracting(IntegrityDtos.Item::args)
                .containsExactly(List.of("2", "0", "0", "0"));
        tamperCalib(c1);   // 교정 행은 원장(무효 한 번만) — 정상 경로로는 못 고친다. DB 소유자가 차단을 끄고 고친 경우를 흉내
        IntegrityDtos.Check after = runner.call(comp, () -> integrity.run()).checks().stream()
                .filter(c -> c.key().equals("ESIGN")).findFirst().orElseThrow();
        assertThat(after.items()).filteredOn(i -> i.subject().equals("qms_gauge")).extracting(IntegrityDtos.Item::args)
                .containsExactly(List.of("1", "1", "0", "0"));
        assertThat(after.items()).extracting(IntegrityDtos.Item::subject, IntegrityDtos.Item::code)
                .contains(org.assertj.core.groups.Tuple.tuple("qms_gauge:" + gauge, "ESIGN_CHANGED"));
        // 정책 N: 토큰 없이 등록, 서명 없음
        accounts.exec(comp, "update sys_policy set policy_value = 'N' where policy_key = 'QMS_ESIGN_REQUIRED'");
        long c3 = calib(gauge2, today.minusDays(3), "OK", null);
        assertThat(c3).isPositive();
        assertThat(esignCount("qms_gauge", gauge2)).isZero();
    }


    /** 소유자 연결로 교정 행 차단 트리거를 잠시 끄고 비고를 고친다(앱 경로 밖 변경 흉내 — 기록 무결성 점검이 잡아야 한다) */
    private void tamperCalib(long calibId) throws Exception {
        try (java.sql.Connection c = java.sql.DriverManager.getConnection(jdbcUrl(), OWNER_USER, testPassword());
                java.sql.Statement st = c.createStatement()) {
            c.setAutoCommit(false);
            st.execute("alter table qms_gauge_calib disable trigger user");
            st.execute("select set_config('app.comp_cd', '" + comp + "', true)");
            st.executeUpdate("update qms_gauge_calib set remark = '서명 뒤 고침' where calib_id = " + calibId);
            st.execute("alter table qms_gauge_calib enable trigger user");
            c.commit();
        }
    }
}
