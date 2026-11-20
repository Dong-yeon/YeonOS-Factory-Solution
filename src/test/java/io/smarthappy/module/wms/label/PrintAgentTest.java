package io.smarthappy.module.wms.label;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.module.wms.label.LabelDtos.PrintItem;
import io.smarthappy.platform.common.db.PgErrors;
import io.smarthappy.platform.security.LoginUser;
import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * pull 인쇄 에이전트 E2 (docs/65 19장) — SaaS 경로: 서버는 밀지 않고, 가짜 에이전트(MockMvc /ext Bearer)가 claim → 결과 → 하트비트.
 * 정상 + 이상 사용(C 동시 claim·D 늦은 결과·F 흐름·G lease 만료·H 재전송) + A사로 B사 + 같은 회사 다른 장치 + 폐기 키 + 직접 SQL.
 */
@AutoConfigureMockMvc
class PrintAgentTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    @Autowired
    LabelPrintService printService;

    @Autowired
    LabelJobs jobs;

    @Autowired
    TenantRunner runner;

    static final class Site {
        String comp;
        long userId;
        RequestPostProcessor admin;
        long agentId;
        String agentKey;
        long printerId;
        long tplId;
    }

    Site a;

    @BeforeEach
    void setUp() throws Exception {
        a = site("PA");
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    Site site(String prefix) throws Exception {
        Site s = new Site();
        s.comp = accounts.newComp(prefix);
        s.userId = accounts.newUser(s.comp, "admin1", PW);
        accounts.grant(s.comp, s.userId, "WMS", "wms-label", "SEARCH", "SAVE", "PRINT");
        accounts.grant(s.comp, s.userId, "WMS", "wms-print-job", "SEARCH", "PRINT", "APPROVE");
        accounts.grant(s.comp, s.userId, "ADM", "sys-terminal", "SEARCH", "SAVE");
        s.admin = accounts.as(s.comp, s.userId, "admin1");
        accounts.addPlant(s.comp, "P1", "1공장");
        String[] dev = device(s, "AGT-01");
        s.agentId = Long.parseLong(dev[0]);
        s.agentKey = dev[1];
        s.printerId = agentPrinter(s, "ZA1", s.agentId);
        s.tplId = id(s, post("/api/wms/labels"), "{\"labelType\":\"ITEM\",\"tplCd\":\"IT1\",\"tplNm\":\"품목\",\"widthMm\":50,\"heightMm\":30,"
                + "\"body\":\"^XA^FO10,10^A0N,30,30^FD{{itemCd}}^FS^XZ\",\"defaultYn\":\"Y\"}");
        return s;
    }

    // =====================================================================
    // 정상 — SaaS 경로 끝까지
    // =====================================================================

    @Test
    void 에이전트가_당겨가_보내고_하트비트_odometer_로_인쇄_확인() throws Exception {
        long job = request(a, "A1");
        // 아웃박스 없음 · 큐 QUEUED · 상태 QUEUED
        assertThat(str(a, "select conn_mode || ':' || coalesce(outbox_message_id::text, '-') from wms_print_job where job_id = ?", job))
                .isEqualTo("AGENT:-");
        assertThat(str(a, "select stage from wms_print_dispatch where job_id = ?", job)).isEqualTo("QUEUED");
        assertThat(state(a, job)).isEqualTo("QUEUED");
        // 하트비트: 내 프린터 목록 + 기다리는 작업 수, 기준 odometer
        ext(a.agentKey, "/heartbeat", "{\"bootId\":\"b1\",\"printers\":[{\"printerId\":" + a.printerId + ",\"online\":true,\"odometer\":500}]}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.queued").value(1))
                .andExpect(jsonPath("$.data.printers[0].printerCd").value("ZA1"));
        // claim — ZPL·부수·장 수·lease
        MvcResult r = ext(a.agentKey, "/claim", "{\"max\":5}").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1)).andExpect(jsonPath("$.data[0].jobId").value(job))
                .andExpect(jsonPath("$.data[0].labels").value(1)).andReturn();
        assertThat((String) JsonPath.read(r.getResponse().getContentAsString(), "$.data[0].zpl")).contains("^FH_^FDA1^FS");
        assertThat(state(a, job)).isEqualTo("CLAIMED");
        ext(a.agentKey, "/claim", "{}").andExpect(jsonPath("$.data.length()").value(0));          // 두 번 주지 않는다
        // 보냄 → 하트비트 odometer +1 → PRINTED(설치형과 같은 판정식)
        result(a.agentKey, job, "SENT", null).andExpect(jsonPath("$.data.outcome").value("MOVED")).andExpect(jsonPath("$.data.stateCd").value("SENT"));
        result(a.agentKey, job, "SENT", null).andExpect(jsonPath("$.data.outcome").value("DUP"));   // 재전송 = 사건 없음
        ext(a.agentKey, "/heartbeat", "{\"printers\":[{\"printerId\":" + a.printerId + ",\"online\":true,\"odometer\":501}]}")
                .andExpect(jsonPath("$.data.queued").value(0));
        assertThat(state(a, job)).isEqualTo("PRINTED");
        assertThat(str(a, "select stage from wms_print_dispatch where job_id = ?", job)).isEqualTo("CLOSED");
        assertThat(str(a, "select state_cd || ':' || src || ':' || odometer from wms_printer_status where printer_id = ?", a.printerId))
                .isEqualTo("OK:AGENT:501");
        // 늦게 온 PRINTED = DUP, 끝난 뒤 FAILED = STALE(상태 그대로)
        result(a.agentKey, job, "PRINTED", null).andExpect(jsonPath("$.data.outcome").value("DUP"));
        result(a.agentKey, job, "FAILED", "HEAD_OPEN").andExpect(status().isOk()).andExpect(jsonPath("$.data.outcome").value("STALE"))
                .andExpect(jsonPath("$.data.stateCd").value("PRINTED"));
        assertThat(str(a, "select string_agg(event_cd || ':' || src, ',' order by event_id) from wms_print_job_event where job_id = ?", job))
                .isEqualTo("CLAIMED:AGENT,SENT:AGENT,PRINTED:AGENT,STALE:AGENT");
        // 행위자 = 장치
        assertThat(str(a, "select recorded_by from wms_print_job_event where job_id = ? and event_cd = 'CLAIMED'", job))
                .isEqualTo("d" + a.agentId + ":AGT-01");
    }

    @Test
    void 결과_보고로_바로_인쇄_완료_오류_뒤_고쳐서_완료() throws Exception {
        long j1 = request(a, "A1");
        long j2 = request(a, "A2");
        ext(a.agentKey, "/claim", "{\"max\":10}").andExpect(jsonPath("$.data.length()").value(2));
        result(a.agentKey, j1, "PRINTED", null).andExpect(jsonPath("$.data.stateCd").value("PRINTED"));
        result(a.agentKey, j2, "PRINTER_ERROR", "MEDIA_OUT").andExpect(jsonPath("$.data.stateCd").value("PRINTER_ERROR"));
        assertThat(str(a, "select stage from wms_print_dispatch where job_id = ?", j2)).isEqualTo("SENT");
        // 오류 동안 사람이 닫을 수 있다(PRINTER_ERROR) — 여기서는 에이전트가 고쳐서 찍음
        result(a.agentKey, j2, "PRINTED", null).andExpect(jsonPath("$.data.stateCd").value("PRINTED"));
        // 검증 스캔은 설치형과 같은 API
        mvc.perform(post("/api/wms/print-jobs/" + j2 + "/verify").with(csrf()).with(a.admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"scanned\":\"A2\"}")).andExpect(jsonPath("$.data.stateCd").value("VERIFIED"));
    }

    // =====================================================================
    // 이상 사용
    // =====================================================================

    @Test
    void G_lease_만료는_결과_불명_자동_재인쇄_없음_늦은_결과와_사람_확정() throws Exception {
        long j1 = request(a, "A1");
        long j2 = request(a, "A2");
        ext(a.agentKey, "/claim", "{\"max\":10}").andExpect(jsonPath("$.data.length()").value(2));
        accounts.exec(a.comp, "update wms_print_dispatch set lease_until = now() - interval '1 second'");
        assertThat(jobs.expireLeases()).isEqualTo(2);
        assertThat(state(a, j1)).isEqualTo("UNKNOWN");
        assertThat(str(a, "select reason_cd from wms_print_job_event where job_id = ? and event_cd = 'UNKNOWN'", j1)).isEqualTo("LEASE_EXPIRED");
        ext(a.agentKey, "/claim", "{}").andExpect(jsonPath("$.data.length()").value(0));         // 다시 나눠 주지 않는다
        assertThat(jobs.expireLeases()).isZero();
        // 늦게 온 결과: UNKNOWN → PRINTED 는 받는다 · SENT 는 STALE
        result(a.agentKey, j1, "SENT", null).andExpect(jsonPath("$.data.outcome").value("STALE"));
        result(a.agentKey, j1, "PRINTED", null).andExpect(jsonPath("$.data.stateCd").value("PRINTED"));
        // 사람: 가져간 중(CLAIMED)은 넘기기·닫기 409, 결과 불명은 닫고 다른 프린터로
        long other = agentPrinter(a, "ZA2", a.agentId);
        mvc.perform(post("/api/wms/print-jobs/" + j2 + "/move").with(csrf()).with(a.admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"printerId\":" + other + ",\"reason\":\" \"}")).andExpect(jsonPath("$.errors[0].field").value("reason"));
        long moved = id(a, post("/api/wms/print-jobs/" + j2 + "/move"), "{\"printerId\":" + other + ",\"reason\":\"프린터 1 용지 걸림\"}");
        assertThat(state(a, j2)).isEqualTo("CANCELLED");
        assertThat(str(a, "select printer_id || ':' || reprint_of_id || ':' || conn_mode from wms_print_job where job_id = ?", moved))
                .isEqualTo(other + ":" + j2 + ":AGENT");
        assertThat(str(a, "select stage from wms_print_dispatch where job_id = ?", moved)).isEqualTo("QUEUED");
        // 가져간 중
        ext(a.agentKey, "/claim", "{}").andExpect(jsonPath("$.data[0].jobId").value(moved));
        mvc.perform(post("/api/wms/print-jobs/" + moved + "/move").with(csrf()).with(a.admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"printerId\":" + a.printerId + ",\"reason\":\"다시\"}")).andExpect(status().isConflict());
        mvc.perform(post("/api/wms/print-jobs/" + moved + "/resolve").with(csrf()).with(a.admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"stateCd\":\"CANCELLED\",\"reason\":\"취소\"}")).andExpect(status().isConflict());
    }

    @Test
    void F_아직_안_가져간_작업은_닫거나_넘길_수_있다() throws Exception {
        long j = request(a, "A1");
        mvc.perform(post("/api/wms/print-jobs/" + j + "/resolve").with(csrf()).with(a.admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"stateCd\":\"PRINTED\",\"reason\":\"억지\"}")).andExpect(status().isConflict());    // 안 가져간 것은 찍혔을 수 없다
        mvc.perform(post("/api/wms/print-jobs/" + j + "/resolve").with(csrf()).with(a.admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"stateCd\":\"CANCELLED\",\"reason\":\"잘못 요청\"}")).andExpect(jsonPath("$.data").value("CANCELLED"));
        assertThat(str(a, "select stage from wms_print_dispatch where job_id = ?", j)).isEqualTo("CLOSED");
        ext(a.agentKey, "/claim", "{}").andExpect(jsonPath("$.data.length()").value(0));
        // 같은 프린터로 넘기기 · 넘길 프린터 없음
        long j2 = request(a, "A2");
        mvc.perform(post("/api/wms/print-jobs/" + j2 + "/move").with(csrf()).with(a.admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"printerId\":" + a.printerId + ",\"reason\":\"같은 곳\"}")).andExpect(jsonPath("$.errors[0].code").value("SAME_PRINTER"));
    }

    @Test
    void C_동시_claim_은_한_작업을_한_번만() throws Exception {
        List<Long> all = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            all.add(request(a, "C" + i));
        }
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Callable<List<Integer>>> calls = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                calls.add(() -> {
                    MvcResult r = ext(a.agentKey, "/claim", "{\"max\":5}").andExpect(status().isOk()).andReturn();
                    return JsonPath.read(r.getResponse().getContentAsString(), "$.data[*].jobId");
                });
            }
            List<Long> got = Collections.synchronizedList(new ArrayList<>());
            for (Future<List<Integer>> f : pool.invokeAll(calls)) {
                f.get().forEach(x -> got.add(x.longValue()));
            }
            assertThat(got).hasSize(12).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(all);
        } finally {
            pool.shutdownNow();
        }
        assertThat(str(a, "select string_agg(distinct claim_cnt::text, ',') from wms_print_dispatch")).isEqualTo("1");
    }

    @Test
    void 롱폴링은_기다렸다_빈_목록_정책이_상한() throws Exception {
        long t0 = System.nanoTime();
        ext(a.agentKey, "/claim", "{\"waitSec\":2}").andExpect(jsonPath("$.data.length()").value(0));
        assertThat((System.nanoTime() - t0) / 1_000_000L).isGreaterThanOrEqualTo(1_500L);
        accounts.exec(a.comp, """
                insert into sys_policy (comp_cd, policy_key, policy_value, created_by, updated_by)
                values (?, 'WMS_PRINT_AGENT_WAIT_SEC', '0', 't', 't')""", a.comp);
        long t1 = System.nanoTime();
        ext(a.agentKey, "/claim", "{\"waitSec\":25}").andExpect(jsonPath("$.data.length()").value(0));
        assertThat((System.nanoTime() - t1) / 1_000_000L).isLessThan(1_500L);
    }

    @Test
    void B_결과_보고_입력_검사() throws Exception {
        long j = request(a, "A1");
        result(a.agentKey, j, "PRINTED", null).andExpect(status().isConflict());                 // 가져가지 않은 작업
        ext(a.agentKey, "/claim", "{}").andExpect(jsonPath("$.data.length()").value(1));
        result(a.agentKey, j, "VERIFIED", null).andExpect(jsonPath("$.errors[0].field").value("state"));
        result(a.agentKey, j, "QUEUED", null).andExpect(jsonPath("$.errors[0].field").value("state"));
        result(a.agentKey, j, "FAILED", "head open").andExpect(jsonPath("$.errors[0].field").value("reasonCd"));
        ext(a.agentKey, "/jobs/" + j + "/result", "not json").andExpect(status().isBadRequest());
        // 미래 시각은 서버 시각으로
        ext(a.agentKey, "/jobs/" + j + "/result", "{\"state\":\"SENT\",\"occurredAt\":\"2099-01-01T00:00:00Z\"}").andExpect(status().isOk());
        assertThat(str(a, "select (occurred_at < now() + interval '1 minute')::text from wms_print_job_event where job_id = ? and event_cd = 'SENT'",
                j)).isEqualTo("true");
        StringBuilder many = new StringBuilder("{\"printers\":[");
        for (int i = 0; i < 51; i++) {
            many.append(i == 0 ? "" : ",").append("{\"printerId\":").append(i + 1).append('}');
        }
        ext(a.agentKey, "/heartbeat", many + "]}").andExpect(status().isBadRequest());
    }

    // =====================================================================
    // 장치 범위 · A사로 B사 · 폐기 키
    // =====================================================================

    @Test
    void 같은_회사_다른_장치_A사로_B사_폐기된_키() throws Exception {
        long j = request(a, "A1");
        String[] other = device(a, "AGT-02");
        // 같은 회사 다른 장치: 남의 프린터 작업 못 가져감 · 결과 404 · 하트비트 unknown
        ext(other[1], "/claim", "{}").andExpect(jsonPath("$.data.length()").value(0));
        ext(a.agentKey, "/claim", "{}").andExpect(jsonPath("$.data.length()").value(1));
        result(other[1], j, "PRINTED", null).andExpect(status().isNotFound());
        ext(other[1], "/heartbeat", "{\"printers\":[{\"printerId\":" + a.printerId + ",\"odometer\":9}]}")
                .andExpect(jsonPath("$.data.unknown[0]").value(a.printerId)).andExpect(jsonPath("$.data.printers.length()").value(0));
        // B사 장치
        Site b = site("PB");
        long bJob = request(b, "B1");
        ext(b.agentKey, "/claim", "{}").andExpect(jsonPath("$.data[*].jobId").value(Matchers.contains((int) bJob)));
        result(b.agentKey, j, "PRINTED", null).andExpect(status().isNotFound());
        result(a.agentKey, bJob, "PRINTED", null).andExpect(status().isNotFound());
        ext(b.agentKey, "/heartbeat", "{\"printers\":[{\"printerId\":" + a.printerId + ",\"odometer\":9}]}")
                .andExpect(jsonPath("$.data.unknown[0]").value(a.printerId));
        assertThat(str(a, "select coalesce(max(odometer)::text, '-') from wms_printer_status")).isEqualTo("-");
        // B사 프린터에 A사 장치를 배정 · 화면 API 로 A사 작업 넘기기
        mvc.perform(post("/api/wms/printers").with(csrf()).with(b.admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"plantCd\":\"P1\",\"printerCd\":\"X\",\"printerNm\":\"x\",\"host\":\"10.1.1.9\",\"port\":9100,\"connMode\":\"AGENT\","
                        + "\"agentTerminalId\":" + a.agentId + "}")).andExpect(jsonPath("$.errors[0].code").value("NOT_FOUND"));
        mvc.perform(post("/api/wms/print-jobs/" + j + "/move").with(csrf()).with(b.admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"printerId\":" + b.printerId + ",\"reason\":\"남의 것\"}")).andExpect(status().isNotFound());
        // 폐기된 키 → 다음 요청부터 401
        mvc.perform(post("/api/production/terminals/" + a.agentId + "/revoke").with(csrf()).with(a.admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"PC 교체\",\"rowVer\":" + str(a, "select row_ver from sys_terminal where terminal_id = ?", a.agentId) + "}"))
                .andExpect(status().isOk());
        ext(a.agentKey, "/claim", "{}").andExpect(status().isUnauthorized());
        // 사람 로그인(세션)으로는 /ext 에 못 들어온다
        mvc.perform(post("/ext/v1/print-agent/claim").with(a.admin).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 프린터_에이전트_검사와_직접_SQL() throws Exception {
        String base = "{\"plantCd\":\"P1\",\"printerCd\":\"Y\",\"printerNm\":\"y\",\"host\":\"10.1.1.9\",\"port\":9100,\"connMode\":\"AGENT\"";
        mvc.perform(post("/api/wms/printers").with(csrf()).with(a.admin).contentType(MediaType.APPLICATION_JSON).content(base + "}"))
                .andExpect(jsonPath("$.errors[0].field").value("agentTerminalId"));
        long pop = accounts.query(a.comp, """
                insert into sys_terminal (comp_cd, terminal_cd, terminal_nm, plant_cd, terminal_type, created_by, updated_by)
                values (?, 'POP-1', 'POP', 'P1', 'POP', 't', 't') returning terminal_id""", Long.class, a.comp);
        mvc.perform(post("/api/wms/printers").with(csrf()).with(a.admin).contentType(MediaType.APPLICATION_JSON)
                .content(base + ",\"agentTerminalId\":" + pop + "}")).andExpect(jsonPath("$.errors[0].code").value("NOT_FOUND"));
        mvc.perform(get("/api/wms/printers/agents").with(a.admin)).andExpect(jsonPath("$.data[*].terminalCd").value(Matchers.contains("AGT-01")));
        // 직접 SQL: AGENT 인데 장치 없음 · DIRECT 인데 장치 있음 · POP 단말 배정
        assertThat(sqlError(a.comp, "update wms_printer set agent_terminal_id = null where printer_id = ?", a.printerId))
                .isEqualTo("23514 ck_wms_printer_agent");
        assertThat(sqlError(a.comp, "update wms_printer set conn_mode = 'DIRECT' where printer_id = ?", a.printerId))
                .isEqualTo("23514 ck_wms_printer_agent");
        assertThat(sqlError(a.comp, "update wms_printer set agent_terminal_id = ? where printer_id = ?", pop, a.printerId))
                .isEqualTo("23503 fk_wms_printer_agent_comp");
        // 큐: 단계 되돌리기 · 대상 바꾸기 · 지우기 · 설치형 작업에 큐 행
        long j = request(a, "A1");
        accounts.exec(a.comp, "update wms_print_dispatch set stage = 'CLOSED', closed_at = now() where job_id = ?", j);
        assertThat(sqlError(a.comp, "update wms_print_dispatch set stage = 'QUEUED', closed_at = null where job_id = ?", j))
                .isEqualTo("55000 ck_wms_print_dispatch_transition");
        assertThat(sqlError(a.comp, "update wms_print_dispatch set terminal_id = ? where job_id = ?", pop, j))
                .isEqualTo("55000 ck_wms_print_dispatch_fixed");
        assertThat(sqlError(a.comp, "delete from wms_print_dispatch where job_id = ?", j)).startsWith("42501");
        assertThat(sqlError(a.comp, """
                insert into wms_print_job (comp_cd, plant_cd, printer_id, tpl_id, label_type, biz_key, zpl, zpl_sha256, requested_by, conn_mode)
                values (?, 'P1', ?, ?, 'ITEM', 'X', '^XA^XZ', repeat('a', 64), 't', 'DIRECT')""", a.comp, a.printerId, a.tplId))
                .isEqualTo("23514 ck_wms_print_job_conn");
    }

    // =====================================================================
    // 도우미
    // =====================================================================

    long request(Site s, String itemCd) {
        return runner.call(s.comp, () -> {
            SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                    new LoginUser(s.userId, s.comp, "admin1", "admin1", "ko", false), null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
            try {
                return printService.request("ITEM", null, s.printerId, 1, List.of(new PrintItem(itemCd, Map.of("itemCd", itemCd)))).get(0);
            } finally {
                SecurityContextHolder.clearContext();
            }
        });
    }

    String state(Site s, long job) {
        return runner.call(s.comp, () -> printService.job(job).getPrintState());
    }

    String[] device(Site s, String cd) throws Exception {
        MvcResult m = mvc.perform(post("/api/production/terminals").with(csrf()).with(s.admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"terminalCd\":\"" + cd + "\",\"terminalNm\":\"" + cd + "\",\"plantCd\":\"P1\",\"terminalType\":\"COLLECTOR\"}"))
                .andExpect(status().isOk()).andReturn();
        String body = m.getResponse().getContentAsString();
        return new String[] {String.valueOf(((Number) JsonPath.read(body, "$.data.terminalId")).longValue()), JsonPath.read(body, "$.data.code")};
    }

    long agentPrinter(Site s, String cd, long agent) throws Exception {
        return id(s, post("/api/wms/printers"), "{\"plantCd\":\"P1\",\"printerCd\":\"" + cd + "\",\"printerNm\":\"공장 PC 프린터\","
                + "\"host\":\"192.168.0.50\",\"port\":9100,\"connMode\":\"AGENT\",\"agentTerminalId\":" + agent + "}");
    }

    long id(Site s, org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder b, String body) throws Exception {
        MvcResult m = mvc.perform(b.with(csrf()).with(s.admin).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        return ((Number) JsonPath.read(m.getResponse().getContentAsString(), "$.data")).longValue();
    }

    ResultActions ext(String key, String path, String body) throws Exception {
        return mvc.perform(post(PrintAgentController.BASE + path).header("Authorization", "Bearer " + key)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    ResultActions result(String key, long job, String state, String reason) throws Exception {
        return ext(key, "/jobs/" + job + "/result", "{\"state\":\"" + state + "\"" + (reason == null ? "" : ",\"reasonCd\":\"" + reason + "\"") + "}");
    }

    String str(Site s, String sql, Object... args) {
        return accounts.query(s.comp, sql, String.class, args);
    }

    String sqlError(String c, String sql, Object... args) {
        Throwable t = catchThrowable(() -> accounts.exec(c, sql, args));
        return t == null ? "OK" : PgErrors.sqlState(t).orElse("?") + " " + PgErrors.constraint(t).orElse("-");
    }

    @SuppressWarnings("unused")
    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder putJson(String url) {
        return put(url);
    }
}
