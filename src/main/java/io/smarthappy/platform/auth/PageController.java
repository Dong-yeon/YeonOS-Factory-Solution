package io.smarthappy.platform.auth;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.context.MessageSource;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import io.smarthappy.platform.log.AccessEvent;
import io.smarthappy.platform.log.AccessLogService;
import io.smarthappy.platform.log.AccessLogService.Result;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 화면 진입점: /page/{progCd} → sys_prog.view_path 템플릿 (화면별 페이지 컨트롤러를 만들지 않는다).
 * - 형식이 틀리거나 없는·사용중지 프로그램 → 404 화면 (템플릿 경로를 사용자 입력으로 만들지 않는다)
 * - SEARCH 권한 없음 → 403 화면
 * - 모델: prog(코드·이름), actions(권한 있는 액션 이름 집합) → 버튼 노출 th:if="${actions.contains('SAVE')}"
 *   버튼 숨김은 편의, 판정은 API 의 @ProgAuth.
 */
@Slf4j
@Controller
@RequiredArgsConstructor
public class PageController {

    private static final Pattern PROG_CD = Pattern.compile("^[a-z0-9][a-z0-9-]{1,49}$");
    private static final Pattern VIEW_PATH = Pattern.compile("^[a-z0-9-]+(/[a-z0-9-]+)*$");

    private final AuthMapperFacade progs;
    private final PermissionService permissions;
    private final MessageSource messages;
    private final AccessLogService accessLog;
    private final io.smarthappy.platform.module.CompModuleService modules;

    public record PageProg(String progCd, String name) {
    }

    @GetMapping("/page/{progCd}")
    public String page(@PathVariable String progCd, Model model, Locale locale, HttpServletResponse response) {
        if (!PROG_CD.matcher(progCd).matches()) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return "error/page-not-found";
        }
        ProgInfo prog = progs.findProg(progCd);
        if (prog == null || !VIEW_PATH.matcher(prog.getViewPath()).matches()) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return "error/page-not-found";
        }
        Set<Action> actions = permissions.actions(progCd);
        if (!actions.contains(Action.SEARCH)) {
            log.info("화면 권한 없음 prog={}", progCd);
            // 이 회사가 쓰지 않는 모듈의 화면이면 그렇게 알린다(docs/80) — 관리자도. 판정은 권한 SQL 이 이미 했다
            boolean moduleOff = modules.disabledModuleOfProg(progCd) != null;
            accessLog.recordCurrent(AccessEvent.PERM_DENIED, progCd, Action.SEARCH.name(), Result.DENIED,
                    moduleOff ? "page module=" + prog.getModuleCd() : "page");
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return moduleOff ? "error/module-disabled" : "error/page-forbidden";
        }
        accessLog.recordCurrent(AccessEvent.PAGE_VIEW, progCd, null, Result.OK, null);
        model.addAttribute("prog", new PageProg(progCd, messages.getMessage(prog.getProgNmKey(), null, progCd, locale)));
        model.addAttribute("actions", actions.stream().map(Enum::name).collect(Collectors.toUnmodifiableSet()));
        return prog.getViewPath();
    }
}
