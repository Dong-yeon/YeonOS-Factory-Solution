package io.smarthappy.module.qms;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * P3 QMS 2부 Q9 교정 NG 영향 LOT (읽기만) — 구간 = 직전 유효 교정일 0시 ~ NG 교정일 끝, 살아 있는 측정값만, 이 게이지만
 * + 이상 사용(NG 아님·무효·다른 게이지 교정·A사로 B사).
 */
class GaugeImpactTest extends QmsTestBase {

    static final String G = "/api/qms/gauges";
    static final String I = "/api/qms/insps";

    long gauge;
    long other;
    long okCalib;

    @BeforeEach
    void impactSetUp() throws Exception {
        accounts.grant(comp, userId, "QA", "qms-insp", "SEARCH", "SAVE");
        accounts.grant(comp, userId, "QA", "qms-gauge-calib", "SEARCH", "SAVE", "DELETE");
        gauge = gauge("GA", "CALIPER");
        other = gauge("GB", "CALIPER");
        okCalib = calib(gauge, today.minusDays(30), "OK");
        calib(other, today.minusDays(30), "OK");
        long p = plan("FINAL", "FG1", null, null);
        lines(p, line("a", null, 10, fgChar1, "S5", "CALIPER")).andExpect(status().isOk());
        action(p, "release").andExpect(status().isOk());
        for (String no : new String[] {"L-A", "L-B", "L-OLD"}) {
            accounts.exec(comp, """
                    insert into lot_master (comp_cd, lot_no, item_id, origin, qty_created, unit_cd, occurred_at, created_by, updated_by)
                    select ?, ?, item_id, 'RECEIVED', 10, 'EA', now(), 't', 't' from mst_item where item_cd = 'FG1'""", comp, no);
        }
    }

    long gauge(String cd, String cls) {
        accounts.exec(comp, """
                insert into mst_phys_asset (comp_cd, asset_cd, asset_type, asset_nm, gauge_class, calib_cycle_days, created_by, updated_by)
                values (?, ?, 'GAUGE', ?, ?, 365, 't', 't')""", comp, cd, cd, cls);
        return accounts.query(comp, "select asset_id from mst_phys_asset where asset_cd = ?", Long.class, cd);
    }

    long calib(long assetId, LocalDate date, String result) throws Exception {
        return id(postJson(G + "/" + assetId + "/calibs", "{\"calibDate\":" + q(date) + ",\"result\":" + q(result) + ",\"agency\":\"INTERNAL\"}"));
    }

    /** 검사 하나 + 샘플 n 개(이 게이지) → 검사 행 id */
    long measured(String lotNo, long g, int n) throws Exception {
        long id = id(postJson(I, "{\"lotNo\":" + q(lotNo) + ",\"inspType\":\"FINAL\"}"));
        long line = accounts.query(comp, "select insp_line_id from qms_insp_line where insp_id = ?", Long.class, id);
        StringBuilder b = new StringBuilder();
        for (int i = 1; i <= n; i++) {
            b.append(i == 1 ? "" : ",").append("{\"sampleNo\":").append(i).append(",\"value\":10.0,\"gaugeAssetId\":").append(g).append("}");
        }
        postJson(I + "/" + id + "/meas", "{\"inspLineId\":" + line + ",\"entries\":[" + b + "]}").andExpect(status().isOk());
        return line;
    }

    @Test
    void 구간은_직전_유효교정일부터_NG일_끝까지_살아있는_측정값_이_게이지만() throws Exception {
        long lineA = measured("L-A", gauge, 3);
        measured("L-B", other, 2);
        // 구간 밖: 직전 OK 교정(30일 전) 보다 이른 측정, NG 일 다음 날 측정(직접 SQL — 원장 시각만 다르게)
        long lineOld = measured("L-OLD", gauge, 1);
        accounts.exec(comp, "insert into qms_insp_meas (comp_cd, insp_line_id, sample_no, value, gauge_asset_id, occurred_at, recorded_by) "
                + "values (?, ?, 2, 10, ?, now() - interval '40 days', 't'), (?, ?, 3, 10, ?, now() + interval '3 days', 't')",
                comp, lineOld, gauge, comp, lineOld, gauge);
        // L-A 샘플 1 무효 → 측정 수 2
        long m1 = accounts.query(comp, "select meas_id from qms_insp_meas where insp_line_id = ? and sample_no = 1", Long.class, lineA);
        long inspA = accounts.query(comp, "select insp_id from qms_insp_line where insp_line_id = ?", Long.class, lineA);
        postJson(I + "/" + inspA + "/meas/void", "{\"measId\":" + m1 + "}").andExpect(status().isOk());
        // 봉쇄 후보로 넘길 LOT 발생일 = 회사 시간대 날짜(UTC 로는 전날인 회사 시간대 오늘 00:30 발생 LOT 도 오늘)
        accounts.exec(comp, "insert into lot_master (comp_cd, lot_no, item_id, origin, qty_created, unit_cd, occurred_at, created_by, updated_by) "
                + "select ?, 'L-T', item_id, 'RECEIVED', 10, 'EA', ((" + io.smarthappy.support.TestAccounts.COMP_TODAY
                + ")::timestamp + interval '30 minutes') at time zone 'Asia/Seoul', 't', 't' from mst_item where item_cd = 'FG1'", comp);
        measured("L-T", gauge, 1);
        long ng = calib(gauge, today, "NG");
        mvc.perform(get(G + "/" + gauge + "/calibs/" + ng + "/impact").with(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rows[?(@.lotNo == 'L-T')].lotDate").value(org.hamcrest.Matchers.hasItem(today.toString())))
                .andExpect(jsonPath("$.data.fromDate").value(today.minusDays(30).toString()))
                .andExpect(jsonPath("$.data.ngDate").value(today.toString()))
                .andExpect(jsonPath("$.data.lotCnt").value(3))
                .andExpect(jsonPath("$.data.rows[?(@.lotNo == 'L-A')].measCnt").value(org.hamcrest.Matchers.hasItem(2)))
                .andExpect(jsonPath("$.data.rows[?(@.lotNo == 'L-OLD')].measCnt").value(org.hamcrest.Matchers.hasItem(1)))
                .andExpect(jsonPath("$.data.rows[?(@.lotNo == 'L-B')]").isEmpty())
                .andExpect(jsonPath("$.data.truncated").value(false));
        // 직전 유효 교정이 없으면 처음부터(구간 밖이던 40일 전 측정도 포함)
        long only = gauge("GC", "CALIPER");
        calib(only, today.minusDays(60), "OK");
        long lineC = measured("L-B", only, 1);
        accounts.exec(comp, "update qms_gauge_calib set void_yn = 'Y', void_reason = 'x', voided_by = 't', voided_at = now() where asset_id = ?", only);
        accounts.exec(comp, "insert into qms_insp_meas (comp_cd, insp_line_id, sample_no, value, gauge_asset_id, occurred_at, recorded_by) "
                + "values (?, ?, 2, 10, ?, now() - interval '400 days', 't')", comp, lineC, only);
        long ngC = calib(only, today, "NG");
        mvc.perform(get(G + "/" + only + "/calibs/" + ngC + "/impact").with(admin)).andExpect(jsonPath("$.data.fromDate").doesNotExist())
                .andExpect(jsonPath("$.data.rows[0].measCnt").value(2));
    }

    @Test
    void 이상_NG_아님_무효_다른게이지_B사() throws Exception {
        long ng = calib(gauge, today, "NG");
        mvc.perform(get(G + "/" + gauge + "/calibs/" + okCalib + "/impact").with(admin)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("CALIB_NOT_NG"));
        mvc.perform(get(G + "/" + other + "/calibs/" + ng + "/impact").with(admin)).andExpect(status().isNotFound());
        mvc.perform(get(G + "/" + gauge + "/calibs/999999999/impact").with(admin)).andExpect(status().isNotFound());
        mvc.perform(get(G + "/" + gauge + "/calibs/" + ng + "/impact").with(admin)).andExpect(jsonPath("$.data.rows.length()").value(0));
        String b = accounts.newComp("QM");
        long bUser = accounts.newUser(b, "admin1", PW);
        accounts.grant(b, bUser, "QA", "qms-gauge-calib", "SEARCH");
        RequestPostProcessor bp = accounts.as(b, bUser, "admin1");
        mvc.perform(get(G + "/" + gauge + "/calibs/" + ng + "/impact").with(bp)).andExpect(status().isNotFound());
        long reader = accounts.newUser(comp, "nobody", PW);
        RequestPostProcessor rd = accounts.as(comp, reader, "nobody");
        mvc.perform(get(G + "/" + gauge + "/calibs/" + ng + "/impact").with(rd)).andExpect(status().isForbidden());
        postJson(G + "/calibs/" + ng + "/void", "{\"reason\":\"잘못 입력\"}").andExpect(status().isOk());
        mvc.perform(get(G + "/" + gauge + "/calibs/" + ng + "/impact").with(admin)).andExpect(jsonPath("$.errors[0].code").value("CALIB_NOT_NG"));
    }
}
