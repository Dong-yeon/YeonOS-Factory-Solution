package io.smarthappy.module.pop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

import io.smarthappy.module.aps.link.ApsDispatchQuery;
import io.smarthappy.module.aps.link.ApsDispatchQuery.Slot;
import io.smarthappy.module.pop.PopDtos.TaskRow;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * POP 일일 배정 순서 (docs/25 4.56, docs/63 G5·AP-10) — APS 읽기 계약의 현행 계획 자리로 "내 할 일"을 다시 놓고, 순서 어김은 표시만.
 * 계약은 가짜 빈(시험마다 자리 표를 바꾼다) — 계약 자체(현행 계획·공장·RLS)는 APS 의 ApsFenceDispatchTest 가 본다.
 */
@AutoConfigureMockMvc
@Import(PopDispatchTest.FakePlan.class)
class PopDispatchTest extends PostgresTestBase {

    /** 공정 id → 자리. 비우면 "현행 계획 없음" */
    static final AtomicReference<Map<Long, Slot>> PLAN = new AtomicReference<>(Map.of());
    static final List<Collection<Long>> ASKED = new CopyOnWriteArrayList<>();

    @TestConfiguration
    static class FakePlan {

        @Bean
        @Primary
        ApsDispatchQuery fakeDispatch() {
            return new ApsDispatchQuery() {
                @Override
                public List<Slot> sequence(String plantCd, LocalDate day) {
                    return List.of();
                }

                @Override
                public Map<Long, Slot> slots(String plantCd, Collection<Long> woProcIds) {
                    ASKED.add(List.copyOf(woProcIds));
                    Map<Long, Slot> out = new HashMap<>();
                    woProcIds.forEach(id -> {
                        if (PLAN.get().containsKey(id)) {
                            out.put(id, PLAN.get().get(id));
                        }
                    });
                    return out;
                }
            };
        }
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    PopFloor f;
    long a10;
    long a20;
    long b10;
    long b20;

    @BeforeEach
    void setUp() throws Exception {
        PLAN.set(Map.of());
        ASKED.clear();
        f = new PopFloor(mvc, accounts).build("PD");
        long woA = f.releasedWo("100");
        long woB = f.releasedWo("50");
        a10 = f.proc(woA, 10);
        a20 = f.proc(woA, 20);
        b10 = f.proc(woB, 10);
        b20 = f.proc(woB, 20);
    }

    private static Slot slot(long woProcId, int seq, long nodeId, String nodeCd, int hour) {
        OffsetDateTime at = OffsetDateTime.of(2026, 11, 10, hour, 0, 0, 0, ZoneOffset.ofHours(9));
        return new Slot(7L, "PLAN-0007", seq, "NODE", null, null, nodeId, nodeCd, 0L, "WO", woProcId, 10, "SPOT", at, at.plusHours(1));
    }

    private static TaskRow task(long id, String status) {
        TaskRow r = new TaskRow();
        r.setWoProcId(id);
        r.setProcStatus(status);
        return r;
    }

    private void twoPlanned() {
        PLAN.set(Map.of(b10, slot(b10, 1, f.cell1, "C1", 8), a10, slot(a10, 2, f.cell1, "C1", 9)));
    }

    @Test
    void 현행_계획이_없으면_지금_순서_그대로이고_계획_자리도_없다() throws Exception {
        f.popGet(PopFloor.P + "/home").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tasks[*].woProcId", Matchers.contains((int) a10, (int) a20, (int) b10, (int) b20)))
                .andExpect(jsonPath("$.data.tasks[0].plan").value(Matchers.nullValue()));
        f.popGet(PopFloor.P + "/procs/" + a10).andExpect(status().isOk()).andExpect(jsonPath("$.data.plan").value(Matchers.nullValue()));
    }

    @Test
    void 계획에_있는_공정이_계획_순서로_먼저_오고_계획_밖_공정은_원래_순서로_뒤에_온다() throws Exception {
        twoPlanned();
        f.popGet(PopFloor.P + "/home").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tasks[*].woProcId", Matchers.contains((int) b10, (int) a10, (int) a20, (int) b20)))
                .andExpect(jsonPath("$.data.tasks[0].plan.seq").value(1))
                .andExpect(jsonPath("$.data.tasks[0].plan.planNo").value("PLAN-0007"))
                .andExpect(jsonPath("$.data.tasks[0].plan.resCd").value("C1"))
                .andExpect(jsonPath("$.data.tasks[0].plan.ahead").value(0))
                .andExpect(jsonPath("$.data.tasks[1].plan.seq").value(2))
                .andExpect(jsonPath("$.data.tasks[1].plan.ahead").value(1))
                .andExpect(jsonPath("$.data.tasks[1].plan.early").value(false))
                .andExpect(jsonPath("$.data.tasks[2].plan").value(Matchers.nullValue()));
        // 계약에는 이 단말 목록의 공정 id 만 묻는다
        assertThat(ASKED.get(ASKED.size() - 1)).containsExactlyInAnyOrder(a10, a20, b10, b20);
    }

    @Test
    void 순서를_어기고_착수해도_막지_않고_표시만_한다() throws Exception {
        twoPlanned();
        // 앞 순번(b10)이 대기인데 뒤 순번(a10) 착수 — 200
        f.pop(post(PopFloor.P + "/procs/" + a10 + "/events/start"), "{\"clientReqId\":\"" + PopFloor.req() + "\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.procStatus").value("RUN"));
        f.popGet(PopFloor.P + "/home").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tasks[1].woProcId").value((int) a10))
                .andExpect(jsonPath("$.data.tasks[1].plan.early").value(true))
                .andExpect(jsonPath("$.data.tasks[1].plan.ahead").value(1));
        f.popGet(PopFloor.P + "/procs/" + a10).andExpect(status().isOk()).andExpect(jsonPath("$.data.plan.early").value(true));
        // 앞 순번도 시작하면 어김 표시가 풀린다
        f.pop(post(PopFloor.P + "/procs/" + b10 + "/events/start"), "{\"clientReqId\":\"" + PopFloor.req() + "\"}").andExpect(status().isOk());
        f.popGet(PopFloor.P + "/home").andExpect(jsonPath("$.data.tasks[1].plan.early").value(false))
                .andExpect(jsonPath("$.data.tasks[1].plan.ahead").value(0));
    }

    @Test
    void 다른_작업장_단말과_다른_회사_단말은_계획이_있어도_남의_공정을_보지_못한다() throws Exception {
        twoPlanned();
        // 같은 회사 L2 단말 — 목록 0건, 공정 직접 열기 404
        String l2 = f.terminal("TAB-L2", f.line2);
        MockHttpSession s2 = new MockHttpSession();
        f.badge(l2, s2, "BADGE-0001").andExpect(status().isOk());
        f.getAs(PopFloor.P + "/home", l2, s2).andExpect(status().isOk()).andExpect(jsonPath("$.data.tasks.length()").value(0));
        f.getAs(PopFloor.P + "/procs/" + a10, l2, s2).andExpect(status().isNotFound());
        // B사 단말(A사와 같은 구조) — A사 공정 id 로 직접 열어도 404, 목록에 A사 공정 없음
        PopFloor other = new PopFloor(mvc, accounts).build("PE");
        other.popGet(PopFloor.P + "/procs/" + a10).andExpect(status().isNotFound());
        other.popGet(PopFloor.P + "/home").andExpect(status().isOk()).andExpect(jsonPath("$.data.tasks.length()").value(0));
        // 배지 없는 요청: 목록은 비우고(배지 화면), 공정 보기는 428
        f.getAs(PopFloor.P + "/home", f.cookie, new MockHttpSession()).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tasks.length()").value(0));
        f.getAs(PopFloor.P + "/procs/" + a10, f.cookie, new MockHttpSession()).andExpect(status().is(428));
        // 단말 쿠키 없는 요청 — 401
        f.getAs(PopFloor.P + "/home", null, new MockHttpSession()).andExpect(status().isUnauthorized());
    }

    @Test
    void 순수_정렬_자원별로_앞선_미착수를_세고_완료는_시작한_것으로_본다() {
        // 자원 R1(node 1): 1 DONE, 2 WAIT, 3 RUN(어김), 자원 R2(node 2): 4 READY, 5 PAUSE(어김) — 계획 밖 6 은 맨 뒤
        List<TaskRow> in = List.of(task(6, "WAIT"), task(5, "PAUSE"), task(3, "RUN"), task(4, "READY"), task(2, "WAIT"), task(1, "DONE"));
        Map<Long, Slot> plan = Map.of(1L, slot(1, 1, 1, "R1", 8), 2L, slot(2, 2, 1, "R1", 9), 3L, slot(3, 3, 1, "R1", 10),
                4L, slot(4, 1, 2, "R2", 8), 5L, slot(5, 2, 2, "R2", 11));
        List<TaskRow> out = PopDispatch.arrange(in, plan);
        assertThat(out).extracting(TaskRow::getWoProcId).containsExactly(1L, 4L, 2L, 3L, 5L, 6L);
        assertThat(out.get(0).getPlan().ahead()).isZero();
        assertThat(out.get(2).getPlan().ahead()).isZero();                 // 앞의 1 은 DONE — 시작한 것
        assertThat(out.get(3).getPlan().ahead()).isEqualTo(1);              // 2 가 아직 대기
        assertThat(out.get(3).getPlan().early()).isTrue();
        assertThat(out.get(4).getPlan().early()).isTrue();                  // 다른 자원은 따로 센다(4 READY)
        assertThat(out.get(4).getPlan().ahead()).isEqualTo(1);
        assertThat(out.get(5).getPlan()).isNull();
        // 자리 표가 비면 받은 그대로
        assertThat(PopDispatch.arrange(in, Map.of())).isSameAs(in);
    }
}
