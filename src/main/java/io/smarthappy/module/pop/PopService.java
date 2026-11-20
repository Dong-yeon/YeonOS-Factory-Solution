package io.smarthappy.module.pop;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.cms.link.BreakdownReport;
import io.smarthappy.module.master.workforce.QualGate;
import io.smarthappy.module.master.workforce.WorkDayResolver;
import io.smarthappy.module.master.workforce.WorkDayResolver.WorkDay;
import io.smarthappy.module.master.workforce.WorkforceDtos.GateResult;
import io.smarthappy.module.pop.PopDtos.HomeView;
import io.smarthappy.module.pop.PopDtos.PopSession;
import io.smarthappy.module.pop.PopDtos.PopWorker;
import io.smarthappy.module.pop.PopDtos.TaskRow;
import io.smarthappy.platform.policy.PolicyService;
import io.smarthappy.platform.time.BizClock;
import lombok.RequiredArgsConstructor;

/** 작업장 홈 (docs/25 4.6) — 단말 작업장의 발행·진행 지시 공정 + 지금 작업자 기준 자격 게이트 미리보기 */
@Service
@RequiredArgsConstructor
public class PopService {

    static final int TASK_LIMIT = 50;

    private final PopMapper mapper;
    private final WorkDayResolver workDays;
    private final QualGate gate;
    private final PolicyService policies;
    private final PopAuthService auth;
    private final BizClock clock;
    private final ObjectProvider<BreakdownReport> breakdowns;
    private final PopDispatch dispatch;
    private final ObjectProvider<io.smarthappy.module.cms.link.PmCheckPort> pmPorts;
    private final io.smarthappy.platform.module.CompModuleService modules;

    @Transactional(readOnly = true)
    public PopSession session(TerminalPrincipal t, PopWorker w) {
        WorkDay wd = workDays.resolve(t.plantCd(), clock.instant());
        boolean cms = modules.isOn("CMS");   // 회사가 설비 모듈을 끄면 고장 신고·설비 점검 단추를 숨긴다(docs/80) — API 는 ModuleGateInterceptor 가 막는다
        return new PopSession(t.compCd(), t.terminalCd(), t.terminalNm(), t.plantCd(), t.nodeCd(), t.nodeNm(),
                wd.zone().getId(), wd.workDay(), wd.shiftCd(), wd.shiftNm(), w == null ? null : w.workerCd(), w == null ? null : w.workerNm(),
                auth.pinRequired(t.plantCd()), policies.intValue(PopAuthService.IDLE_POLICY),
                LocaleContextHolder.getLocale().getLanguage(), t.terminalType(),
                breakdownOn(t.terminalType(), breakdowns) && cms, PopPmService.pmOn(t.terminalType(), pmPorts) && cms);
    }

    /** 작업자가 없으면 할 일은 비운다(배지 화면) */
    @Transactional(readOnly = true)
    public HomeView home(TerminalPrincipal t, PopWorker w) {
        PopSession s = session(t, w);
        if (w == null) {
            return new HomeView(s, List.of());
        }
        // 현행 계획(APS 일일 배정)이 있으면 그 순서로 — 없으면 지금 순서(우선순위·계획일) 그대로(docs/25 4.56)
        List<TaskRow> tasks = dispatch.arrange(t.plantCd(), mapper.findTasks(t.nodeId(), t.plantCd(), TASK_LIMIT));
        Map<Long, GateResult> byProc = new HashMap<>();
        for (TaskRow r : tasks) {
            r.setGate(byProc.computeIfAbsent(r.getProcId(), p -> gate.check(t.plantCd(), p, w.workerId(), s.workDay())));
        }
        return new HomeView(s, tasks);
    }

    /** 고장 신고 버튼 — 생산 단말(POP)만, CMS 구현이 없으면(NONE) 숨김(docs/25 4.35, docs/33 11.16 보강 6) */
    static boolean breakdownOn(String terminalType, ObjectProvider<BreakdownReport> provider) {
        return "POP".equals(terminalType) && BreakdownReport.of(provider) != BreakdownReport.NONE;
    }
}
