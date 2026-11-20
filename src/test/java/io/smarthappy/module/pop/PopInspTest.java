package io.smarthappy.module.pop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

import io.smarthappy.module.qms.link.FloorInspPort;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.FieldErrors;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * POP 검사 입력 (docs/25 4.57) — MES 몫: 공정 화면의 검사 플래그, 단말 작업장 공정 범위, 작업자·단말·요청 id 를 계약에 그대로 넘김,
 * 다른 공정·품목의 검사 id 거부, QMS 오류(상태·칸 오류) 그대로. 계약 {@link FloorInspPort} 는 가짜 빈(@Primary) —
 * 검사 만들기·측정 원장·자동 판정·멱등은 QMS 세션의 계약 시험이 본다(docs/29_P3 53.3).
 */
@AutoConfigureMockMvc
@Import(PopInspTest.FakeQms.class)
class PopInspTest extends PostgresTestBase {

    static final List<Object> CALLS = new CopyOnWriteArrayList<>();
    /** detail 이 돌려줄 검사의 공정 코드·품목 — 다른 공정의 검사 흉내에 쓴다 */
    static final AtomicReference<String[]> OF = new AtomicReference<>(new String[] {"SPOT", "FG"});
    static final AtomicReference<RuntimeException> FAIL = new AtomicReference<>();

    @TestConfiguration
    static class FakeQms {

        @Bean
        @Primary
        FloorInspPort fakeFloorInsp() {
            return new FloorInspPort() {
                private FloorInsp insp(long id, String status, String next) {
                    return new FloorInsp(id, "IN2611-00001", "FIRST_OFF", "LOT-1", OF.get()[1], "FG 품목", OF.get()[0], status,
                            "JUDGED".equals(status) ? "OK" : null, "OPEN", next,
                            List.of(new Line(11, 1, "FG-1", "구멍 지름", "VARIABLE", new BigDecimal("10.00"), new BigDecimal("9.95"),
                                    new BigDecimal("10.05"), 2, "mm", "VISUAL", 5, 0, 1, "OPEN", List.of())));
                }

                @Override
                public List<DueInsp> due(DueQuery q) {
                    CALLS.add(q);
                    return List.of(new DueInsp("FIRST_OFF", "LOT-1", null, null, null, null), new DueInsp("PATROL", null, null, null, "OK", null));
                }

                @Override
                public InspRef open(OpenForm f) {
                    CALLS.add(f);
                    fail();
                    return new InspRef(77, "IN2611-00001", false, false);
                }

                @Override
                public FloorInsp detail(long inspId, long terminalId) {
                    if (inspId != 77) {
                        throw new BusinessException(HbErrorCode.NOT_FOUND);
                    }
                    return insp(inspId, "OPEN", "MEASURE");
                }

                @Override
                public List<Gauge> gauges(String gaugeClass) {
                    CALLS.add("gauges:" + gaugeClass);
                    return List.of(new Gauge(5, "MIC-01", "마이크로미터", gaugeClass, false), new Gauge(6, "MIC-02", "마이크로미터 2", gaugeClass, true));
                }

                @Override
                public MeasureResult measure(MeasureForm f) {
                    CALLS.add(f);
                    fail();
                    return new MeasureResult(false, true, insp(f.inspId(), "JUDGED", "DONE"));
                }

                @Override
                public FloorInsp confirmNg(ConfirmForm f) {
                    CALLS.add(f);
                    fail();
                    return insp(f.inspId(), "JUDGED", "DONE");
                }

                private void fail() {
                    if (FAIL.get() != null) {
                        throw FAIL.get();
                    }
                }
            };
        }
    }

    private static final String MEAS = "{\"inspLineId\":11,\"entryMethod\":\"SCAN\",\"clientReqId\":\"req-meas-0001\","
            + "\"entries\":[{\"sampleNo\":1,\"value\":10.01},{\"sampleNo\":2,\"value\":-0.5,\"gaugeAssetId\":5}]}";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    PopFloor f;
    long spot;
    long terminalId;
    String base;

    @BeforeEach
    void setUp() throws Exception {
        CALLS.clear();
        OF.set(new String[] {"SPOT", "FG"});
        FAIL.set(null);
        f = new PopFloor(mvc, accounts).build("PI");
        spot = f.proc(f.releasedWo("100"), 10);
        terminalId = accounts.query(f.comp, "select terminal_id from sys_terminal where terminal_cd = 'TAB-01'", Long.class);
        base = PopFloor.P + "/procs/" + spot + "/insp";
    }

    @SuppressWarnings("unchecked")
    private static <T> T last(Class<T> type) {
        return (T) CALLS.stream().filter(type::isInstance).reduce((a, b) -> b).orElseThrow();
    }

    @Test
    void 공정_화면에_검사_플래그가_서고_열_수_있는_유형과_이름을_돌려준다() throws Exception {
        f.popGet(PopFloor.P + "/procs/" + spot).andExpect(status().isOk()).andExpect(jsonPath("$.data.insp").value(true));
        f.popGet(base).andExpect(status().isOk()).andExpect(jsonPath("$.data.due.length()").value(2))
                .andExpect(jsonPath("$.data.due[0].inspType").value("FIRST_OFF")).andExpect(jsonPath("$.data.due[0].suggestedLotNo").value("LOT-1"))
                .andExpect(jsonPath("$.data.due[1].suggestedLotNo").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.data.typeNames.FIRST_OFF").isNotEmpty()).andExpect(jsonPath("$.data.typeNames.PATROL").isNotEmpty());
        FloorInspPort.DueQuery q = last(FloorInspPort.DueQuery.class);
        assertThat(q.woProcId()).isEqualTo(spot);
        assertThat(q.terminalId()).isEqualTo(terminalId);
    }

    @Test
    void 열기_측정_불합격_확정은_작업자와_단말과_요청_id_를_계약에_그대로_넘긴다() throws Exception {
        f.pop(post(base + "/open"), "{\"inspType\":\"FIRST_OFF\",\"lotNo\":\"LOT-1\",\"clientReqId\":\"req-open-0001\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.ref.inspId").value(77)).andExpect(jsonPath("$.data.ref.existing").value(false))
                .andExpect(jsonPath("$.data.insp.lines[0].sampleSize").value(5)).andExpect(jsonPath("$.data.insp.nextAction").value("MEASURE"));
        FloorInspPort.OpenForm o = last(FloorInspPort.OpenForm.class);
        assertThat(o).isEqualTo(new FloorInspPort.OpenForm("FIRST_OFF", "LOT-1", spot, f.workerId, terminalId, "req-open-0001"));

        f.popGet(base + "/77").andExpect(status().isOk()).andExpect(jsonPath("$.data.inspNo").value("IN2611-00001"));
        f.popGet(base + "/gauges?gaugeClass=MICRO").andExpect(status().isOk()).andExpect(jsonPath("$.data[1].blocked").value(true));

        f.pop(post(base + "/77/meas"), MEAS).andExpect(status().isOk()).andExpect(jsonPath("$.data.judged").value(true))
                .andExpect(jsonPath("$.data.insp.status").value("JUDGED"));
        FloorInspPort.MeasureForm m = last(FloorInspPort.MeasureForm.class);
        assertThat(m.inspId()).isEqualTo(77);
        assertThat(m.inspLineId()).isEqualTo(11);
        assertThat(m.entryMethod()).isEqualTo("SCAN");
        assertThat(m.workerId()).isEqualTo(f.workerId);
        assertThat(m.terminalId()).isEqualTo(terminalId);
        assertThat(m.clientReqId()).isEqualTo("req-meas-0001");
        assertThat(m.entries()).hasSize(2);
        assertThat(m.entries().get(0).value()).isEqualByComparingTo("10.01");
        assertThat(m.entries().get(1).value()).isEqualByComparingTo("-0.5");
        assertThat(m.entries().get(1).gaugeAssetId()).isEqualTo(5L);

        f.pop(post(base + "/77/confirm-ng"), "{\"clientReqId\":\"req-ng-00001\"}").andExpect(status().isOk());
        assertThat(last(FloorInspPort.ConfirmForm.class)).isEqualTo(new FloorInspPort.ConfirmForm(77, f.workerId, terminalId, "req-ng-00001"));
    }

    @Test
    void QMS_의_상태_오류와_칸_오류는_그대로_돌려준다() throws Exception {
        FAIL.set(new BusinessException(HbErrorCode.QMS_INSP_STATE));
        f.pop(post(base + "/77/meas"), MEAS).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("QMS_INSP_STATE"));
        f.pop(post(base + "/77/confirm-ng"), "{\"clientReqId\":\"req-ng-00002\"}").andExpect(status().isConflict());
        FieldErrors e = FieldErrors.create();
        e.row("1").add("gaugeAssetId", "GAUGE_BLOCKED");
        FAIL.set(new BusinessException(HbErrorCode.INVALID_VALUE).withFieldErrors(e.list()));
        f.pop(post(base + "/77/meas"), MEAS).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].rowKey").value("1"))
                .andExpect(jsonPath("$.errors[0].field").value("gaugeAssetId")).andExpect(jsonPath("$.errors[0].code").value("GAUGE_BLOCKED"));
        FAIL.set(new BusinessException(HbErrorCode.QMS_INSP_NO_PLAN));
        f.pop(post(base + "/open"), "{\"inspType\":\"FIRST_OFF\",\"lotNo\":\"LOT-1\",\"clientReqId\":\"req-open-0002\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("QMS_INSP_NO_PLAN"));
        // 본문 없음·줄 없음 → 400(계약을 부르지 않는다)
        int before = CALLS.size();
        f.pop(post(base + "/77/meas"), "{}").andExpect(status().isBadRequest());
        f.pop(post(base + "/open"), "").andExpect(status().isBadRequest());
        assertThat(CALLS).hasSize(before);
    }

    @Test
    void 작업자_없음_쿠키_없음_사무_로그인_PDA_는_계약까지_가지_못한다() throws Exception {
        f.getAs(base, f.cookie, new MockHttpSession()).andExpect(status().is(428)).andExpect(jsonPath("$.code").value("POP_NO_WORKER"));
        f.postAs(base + "/77/meas", MEAS, f.cookie, new MockHttpSession()).andExpect(status().is(428));
        f.postAs(base + "/open", "{\"inspType\":\"FIRST_OFF\",\"lotNo\":\"LOT-1\",\"clientReqId\":\"req-open-0003\"}", f.cookie, new MockHttpSession())
                .andExpect(status().is(428));
        f.getAs(base, null, new MockHttpSession()).andExpect(status().isUnauthorized());
        f.postAs(base + "/77/confirm-ng", "{\"clientReqId\":\"req-ng-00003\"}", null, new MockHttpSession()).andExpect(status().isUnauthorized());
        mvc.perform(get(base).with(f.admin)).andExpect(status().is(Matchers.either(Matchers.is(401)).or(Matchers.is(403))));
        String pda = f.terminal("PDA-01", null, "PDA");
        MockHttpSession ps = new MockHttpSession();
        f.badge(pda, ps, "BADGE-0001").andExpect(status().isOk());
        f.getAs(base, pda, ps).andExpect(status().isForbidden());
        f.postAs(base + "/77/meas", MEAS, pda, ps).andExpect(status().isForbidden());
        assertThat(CALLS).isEmpty();
    }

    @Test
    void 작업장_밖_공정과_다른_공정의_검사와_B사_단말은_404() throws Exception {
        // 같은 회사 L2 단말 — L1 공정의 검사는 없음(계약을 부르지 않는다)
        String l2 = f.terminal("TAB-L2", f.line2);
        MockHttpSession s2 = new MockHttpSession();
        f.badge(l2, s2, "BADGE-0001").andExpect(status().isOk());
        f.getAs(base, l2, s2).andExpect(status().isNotFound());
        f.postAs(base + "/open", "{\"inspType\":\"FIRST_OFF\",\"lotNo\":\"LOT-1\",\"clientReqId\":\"req-open-0004\"}", l2, s2)
                .andExpect(status().isNotFound());
        f.postAs(base + "/77/meas", MEAS, l2, s2).andExpect(status().isNotFound());
        f.postAs(base + "/77/confirm-ng", "{\"clientReqId\":\"req-ng-00004\"}", l2, s2).andExpect(status().isNotFound());
        // B사 단말 + A사 공정 id — 404
        PopFloor other = new PopFloor(mvc, accounts).build("PJ");
        other.popGet(base).andExpect(status().isNotFound());
        other.pop(post(base + "/77/meas"), MEAS).andExpect(status().isNotFound());
        other.pop(post(base + "/open"), "{\"inspType\":\"FIRST_OFF\",\"lotNo\":\"LOT-1\",\"clientReqId\":\"req-open-0005\"}")
                .andExpect(status().isNotFound());
        assertThat(CALLS).isEmpty();
        // 내 공정 경로에 다른 공정(또는 다른 품목)의 검사 id — 보기·측정·확정 404, 측정·확정은 계약에 닿지 않는다
        OF.set(new String[] {"INSP", "FG"});
        f.popGet(base + "/77").andExpect(status().isNotFound());
        f.pop(post(base + "/77/meas"), MEAS).andExpect(status().isNotFound());
        f.pop(post(base + "/77/confirm-ng"), "{\"clientReqId\":\"req-ng-00005\"}").andExpect(status().isNotFound());
        OF.set(new String[] {"SPOT", "RM"});
        f.pop(post(base + "/77/meas"), MEAS).andExpect(status().isNotFound());
        // 없는 검사 id
        OF.set(new String[] {"SPOT", "FG"});
        f.popGet(base + "/999").andExpect(status().isNotFound());
        assertThat(CALLS.stream().filter(c -> c instanceof FloorInspPort.MeasureForm || c instanceof FloorInspPort.ConfirmForm)).isEmpty();
    }
}
