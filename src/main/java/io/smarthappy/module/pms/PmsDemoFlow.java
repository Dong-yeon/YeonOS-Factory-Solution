package io.smarthappy.module.pms;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import io.smarthappy.module.pms.GateDtos.CheckIn;
import io.smarthappy.module.pms.GateDtos.CondIn;
import io.smarthappy.module.pms.GateDtos.DecideForm;
import io.smarthappy.module.pms.GateDtos.DelivRow;
import io.smarthappy.module.pms.GateDtos.ReviewForm;
import io.smarthappy.module.pms.PmsDtos.BaselineForm;
import io.smarthappy.module.pms.PmsDtos.ProjectForm;
import io.smarthappy.module.pms.PmsDtos.ProjectRow;
import io.smarthappy.module.pms.PmsDtos.StatusForm;
import io.smarthappy.module.pms.PmsDtos.TaskForm;
import io.smarthappy.module.pms.PmsDtos.TaskRow;
import io.smarthappy.module.pms.WorkDtos.DayAction;
import io.smarthappy.module.pms.WorkDtos.DayForm;
import io.smarthappy.module.pms.WorkDtos.IssueForm;
import io.smarthappy.module.pms.WorkDtos.LogIn;
import io.smarthappy.platform.security.LoginUser;
import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.platform.time.BizClock;
import lombok.RequiredArgsConstructor;

/**
 * PMS 데모 관통 흐름 (docs/32 PM5) — 화면과 같은 서비스 길로: 기본 APQP 템플릿 → 신규 부품 프로젝트(SOP = 오늘 + 150) → 기준선 →
 * 1·2단계 작업 완료·산출물 제출·승인 → **게이트 2개 통과**(G1 통과, G2 조건부 → 이슈) → 3단계 진행 중 **지연 1건** + 기준선 뒤 계획 연장(사유) →
 * 이슈 → 지난 평일 **공수** 확정 + 4M 변경 프로젝트 1개.
 * 회사에 프로젝트가 하나라도 있으면 아무것도 하지 않는다(사용자 데이터를 덮지 않음). 게이트 결정은 결재 대신 바로 결정(데모 계정 하나로 —
 * 결재 흐름은 화면에서 직접 해 보도록 남긴다). 실행자 = 회사 관리자(권한 판정이 화면과 같게).
 */
@Service
@RequiredArgsConstructor
public class PmsDemoFlow {

    private static final byte[] PDF = "%PDF-1.7\n1 0 obj\n<<>>\nendobj\n% demo\n".getBytes(StandardCharsets.US_ASCII);

    private final TenantRunner runner;
    private final JdbcTemplate jdbc;
    private final BizClock clock;
    private final TemplateService templates;
    private final ProjectService projects;
    private final TaskService tasks;
    private final ScheduleService schedule;
    private final DeliverableService delivs;
    private final GateService gates;
    private final GateDecider decider;
    private final IssueService issues;
    private final WorkLogService logs;
    private final PmsMapper mapper;
    private final GateMapper gm;

    /** 반환 = 만든 프로젝트 수(이미 있으면 0). user = 실행자(화면 권한으로 판정된다) */
    public int seed(String comp, LoginUser user) {
        return as(user, () -> runner.call(comp, () -> {
            Integer n = jdbc.queryForObject("select count(*) from pms_project", Integer.class);
            if (n != null && n > 0) {
                return 0;
            }
            apqp(user);
            change(user);
            return 2;
        }));
    }

    // ------------------------------------------------------------------ 신규 부품 APQP

    private void apqp(LoginUser u) {
        LocalDate today = clock.today();
        templates.createDefaultApqp();
        String cust = exists("select count(*) from mst_cust where cust_cd = 'S005' and use_yn = 'Y'") ? "S005" : null;
        List<String> items = exists("select count(*) from mst_item where item_cd = '88010-DM300' and use_yn = 'Y'")
                ? List.of("88010-DM300") : List.of();
        long proj = projects.create(new ProjectForm("시트 프레임 브래킷 신규 개발(데모)", "APQP", cust, null, null,
                today.plusDays(150).toString(), "데모: 게이트 2개 통과 · 3단계 진행 중 · 지연 1건", items, TemplateService.DEFAULT_APQP, null,
                null));
        activate(proj);
        schedule.setBaseline(proj, new BaselineForm("착수 기준 계획(고객 SOP 기준)"));

        // 1단계 완료 → 산출물 승인·면제 → G1 통과
        for (String t : List.of("고객 요구사항·CSR 검토", "타당성 검토(설비·공수·원가)")) {
            done(proj, t, u);
        }
        approve(proj, "고객 요구사항 검토서");
        waive(proj, "타당성 검토서", "기존 라인 활용 — 별도 검토서 생략(PM 승인)");
        decide(proj, "G1 개발 착수 심사", "GO", List.of());

        // 2단계 완료 → 산출물 승인 → G2 조건부 통과(조건 → 이슈)
        for (String t : List.of("설계 FMEA", "도면·특별특성 확정", "시작품 제작·시험")) {
            done(proj, t, u);
        }
        for (String d : List.of("DFMEA", "확정 도면", "특별특성 목록")) {
            approve(proj, d);
        }
        decide(proj, "G2 설계 확정 심사", "CONDITIONAL",
                List.of(new CondIn("시작품 치수 NG 2건 원인 확인·재측정", u.loginId(), today.plusDays(10).toString())));

        // 3단계: 공정 흐름도 진행 중(계획 종료 지남 = 지연), 금형 발주 진행 중, Safe Launch 기준선 뒤 7일 연장(사유)
        progress(proj, "공정 흐름도", 60);
        progress(proj, "금형·설비 발주·제작", 40);
        TaskRow sl = task(proj, "Safe Launch(강화 검사)");
        tasks.update(sl.getTaskId(), form(sl, sl.getPlanStart(), sl.getPlanEnd().plusDays(7), null, null, null, u.loginId(),
                "고객 요청으로 강화 검사 기간 연장"));

        TaskRow mold = task(proj, "금형·설비 발주·제작");
        issues.create(new IssueForm(proj, mold.getTaskId(), "금형 T1 시사출 일정 1주 지연 위험", "금형 업체 설비 고장 — 대체 설비 확인 중",
                "HIGH", u.loginId(), today.plusDays(7).toString(), items.isEmpty() ? null : "ITEM", items.isEmpty() ? null : items.get(0),
                null, "RISK"));

        // 지난 평일 3일 공수 확정 + 오늘은 미리 채움(작성 중)
        TaskRow flow = task(proj, "공정 흐름도");
        int done = 0;
        for (LocalDate d = today.minusDays(1); done < 3; d = d.minusDays(1)) {
            if (d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY) {
                continue;
            }
            logs.saveDay(new DayForm(d.toString(), List.of(
                    new LogIn(null, proj, flow.getTaskId(), "DESIGN", "5", null, null),
                    new LogIn(null, proj, mold.getTaskId(), "MEETING", "2.5", "금형 업체 회의", null))));
            logs.confirm(new DayAction(d.toString(), null));
            done++;
        }
        logs.prefill(new DayAction(today.toString(), null));
    }

    // ------------------------------------------------------------------ 4M 변경

    private void change(LoginUser u) {
        LocalDate today = clock.today();
        List<String> items = exists("select count(*) from mst_item where item_cd = '64310-DM100' and use_yn = 'Y'")
                ? List.of("64310-DM100") : List.of();
        long proj = projects.create(new ProjectForm("용접 지그 개조 4M 변경(데모)", "CHANGE", null, null, null, null,
                "데모: 템플릿 없이 만든 변경 프로젝트", items, null, null, null));
        LocalDate s = today.minusDays(20);
        long a = tasks.add(proj, task("변경 영향 평가", "TASK", s, s.plusDays(4)));
        long b = tasks.add(proj, task("지그 개조·검증", "TASK", s.plusDays(5), today.plusDays(10)));
        tasks.add(proj, task("변경 승인 심사", "GATE", today.plusDays(12), today.plusDays(12)));
        tasks.add(proj, task("변경 적용(초품 확인)", "MILESTONE", today.plusDays(15), today.plusDays(15)));
        activate(proj);
        done(proj, "변경 영향 평가", u);
        progress(proj, "지그 개조·검증", 50);
        delivs.create(proj, new GateDtos.DelivForm(b, "지그 검증 성적서", "Y", null, u.loginId(), null, null));
        delivs.create(proj, new GateDtos.DelivForm(a, "4M 변경 신청서(고객)", "Y", null, u.loginId(), null, null));
        approve(proj, "4M 변경 신청서(고객)");
        issues.create(new IssueForm(proj, b, "개조 지그 클램프 간섭", null, "MID", u.loginId(), today.plusDays(5).toString(),
                items.isEmpty() ? null : "ITEM", items.isEmpty() ? null : items.get(0), null, "DEFECT"));
    }

    // ------------------------------------------------------------------ 도우미

    private void activate(long proj) {
        ProjectRow p = projects.get(proj);
        projects.changeStatus(proj, new StatusForm("ACTIVE", null, p.getRowVer()));
    }

    private static TaskForm task(String nm, String type, LocalDate start, LocalDate end) {
        return new TaskForm(null, null, type, nm, null, start.toString(), end.toString(), null, null, null, null, null, null, null, null, null);
    }

    private TaskRow task(long proj, String nm) {
        return mapper.findTasks(proj).stream().filter(t -> t.getTaskNm().equals(nm)).findFirst()
                .orElseThrow(() -> new IllegalStateException("데모 작업 없음: " + nm));
    }

    private static TaskForm form(TaskRow t, LocalDate ps, LocalDate pe, LocalDate as, LocalDate ae, Integer pct, String owner,
            String reason) {
        return new TaskForm(null, null, t.getTaskType(), t.getTaskNm(), owner, ps.toString(), pe.toString(),
                as == null ? null : as.toString(), ae == null ? null : ae.toString(), pct, null, t.getRemark(), t.getRowVer(), reason,
                t.getPlanHours(), null);
    }

    /** 계획대로 끝남(실적 = 계획, 단 오늘을 넘지 않게) */
    private void done(long proj, String nm, LoginUser u) {
        TaskRow t = task(proj, nm);
        LocalDate today = clock.today();
        LocalDate end = t.getPlanEnd().isAfter(today) ? today : t.getPlanEnd();
        LocalDate start = t.getPlanStart().isAfter(end) ? end : t.getPlanStart();
        tasks.update(t.getTaskId(), form(t, t.getPlanStart(), t.getPlanEnd(), start, end, 100, u.loginId(), null));
    }

    private void progress(long proj, String nm, int pct) {
        TaskRow t = task(proj, nm);
        LocalDate today = clock.today();
        LocalDate start = t.getPlanStart().isAfter(today) ? today : t.getPlanStart();
        tasks.update(t.getTaskId(), form(t, t.getPlanStart(), t.getPlanEnd(), start, null, pct,
                PmsInputs.me().loginId(), null));
    }

    private DelivRow deliv(long proj, String nm) {
        return gm.findDeliverables(proj).stream().filter(d -> d.getDelivNm().equals(nm)).findFirst()
                .orElseThrow(() -> new IllegalStateException("데모 산출물 없음: " + nm));
    }

    private void approve(long proj, String nm) {
        DelivRow d = deliv(proj, nm);
        delivs.submit(d.getDelivId(), new PdfFile(nm + ".pdf"), d.getRowVer(), "데모 제출");
        DelivRow s = deliv(proj, nm);
        delivs.decide(d.getDelivId(), new DecideForm("APPROVE", "데모 승인", s.getRowVer()));
    }

    private void waive(long proj, String nm, String reason) {
        DelivRow d = deliv(proj, nm);
        delivs.decide(d.getDelivId(), new DecideForm("WAIVE", reason, d.getRowVer()));
    }

    /** 심사 만들기 → 심사일 = 게이트 계획일, 점검 모두 OK(조건부는 하나 NG) → 결정 */
    private void decide(long proj, String gateNm, String decision, List<CondIn> conds) {
        TaskRow g = task(proj, gateNm);
        long rv = gates.create(g.getTaskId());
        List<CheckIn> checks = new ArrayList<>();
        int i = 0;
        for (GateDtos.CheckRow c : gm.findChecks(rv)) {
            boolean ng = "CONDITIONAL".equals(decision) && i++ == 0;
            checks.add(new CheckIn(c.getCheckNm(), ng ? "NG" : "OK", ng ? "조건으로 남김" : null));
        }
        LocalDate day = g.getPlanStart().isAfter(clock.today()) ? clock.today() : g.getPlanStart();
        gates.update(rv, new ReviewForm(day.toString(), decision, "데모 심사 — " + gateNm, checks, conds,
                gm.findReview(rv).getRowVer()));
        decider.lock(rv);
        decider.decide(rv, PmsInputs.me().loginId());
    }

    private boolean exists(String sql) {
        Integer n = jdbc.queryForObject(sql, Integer.class);
        return n != null && n > 0;
    }

    private static <T> T as(LoginUser user, Supplier<T> work) {
        SecurityContext before = SecurityContextHolder.getContext();
        SecurityContext ctx = SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(new UsernamePasswordAuthenticationToken(user, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        SecurityContextHolder.setContext(ctx);
        try {
            return work.get();
        } finally {
            SecurityContextHolder.setContext(before);
        }
    }

    /** 데모 제출용 작은 PDF (FileService 의 확장자·시그니처 검사를 그대로 받는다) */
    private record PdfFile(String name) implements MultipartFile {

        @Override
        public String getName() {
            return "file";
        }

        @Override
        public String getOriginalFilename() {
            return name;
        }

        @Override
        public String getContentType() {
            return "application/pdf";
        }

        @Override
        public boolean isEmpty() {
            return false;
        }

        @Override
        public long getSize() {
            return PDF.length;
        }

        @Override
        public byte[] getBytes() {
            return PDF.clone();
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(PDF);
        }

        @Override
        public void transferTo(File dest) throws IOException {
            Files.write(dest.toPath(), PDF);
        }
    }
}
