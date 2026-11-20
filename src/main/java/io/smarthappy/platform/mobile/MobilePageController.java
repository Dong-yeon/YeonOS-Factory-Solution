package io.smarthappy.platform.mobile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import io.smarthappy.platform.auth.Action;
import io.smarthappy.platform.auth.PermissionService;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.log.AccessEvent;
import io.smarthappy.platform.log.AccessLogService;
import io.smarthappy.platform.log.AccessLogService.Result;
import io.smarthappy.platform.security.CurrentUser;
import io.smarthappy.platform.security.LoginUser;
import io.smarthappy.platform.time.BizClock;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 모바일 웹앱 화면 (docs/74). 일반 로그인(회사코드 + 아이디) 사용자가 휴대폰 브라우저로 쓰는 고정 화면 6개 —
 * 셸(탭·iframe)이 없고, 사무 화면과 같은 보안 체인(세션·CSRF·회사 범위·MFA·협력사 경계)을 그대로 탄다.
 *
 * <ul>
 * <li>새 화면 코드(sys_prog)를 만들지 않는다 — 권한은 <b>사무 화면의 progCd 그대로</b>(M1·M2). 여기 권한 확인(403)과 탭 숨김은 편의이고,
 *     판정은 화면이 부르는 기존 API 의 {@code @ProgAuth}·서비스가 한다</li>
 * <li>플랫폼은 업무 모듈을 모른다(ModuleBoundaryGuardTest) — 화면 코드는 글자 상수로만 든다</li>
 * <li>접속 로그: 연 화면 = PAGE_VIEW(그 사무 화면 코드, 설명 {@code m}), 권한 없음 = PERM_DENIED</li>
 * </ul>
 */
@Slf4j
@Controller
@RequiredArgsConstructor
public class MobilePageController {

    public static final String HOME = "/m";
    public static final String APPROVAL = "/m/approval";
    public static final String FIND = "/m/find";
    public static final String NCR = "/m/ncr";
    public static final String STATUS = "/m/status";
    public static final String ME = "/m/me";

    // 사무 화면 코드 — 모바일 화면이 기대는 권한(docs/74 2장)
    static final String P_HOME = "home-main";
    static final String P_APPROVAL = "sys-approval";
    static final String P_INBOX = "sys-inbox";
    static final String P_LOT = "prd-lot";
    static final String P_EQUIP_STATE = "cms-state";
    static final String P_NCR = "qms-ncr";
    static final String P_WO = "prd-wo";
    static final String P_MY_INFO = "sys-my-info";

    private static final String FORBIDDEN_VIEW = "error/page-forbidden";
    private static final String NOTE = "m";

    private final PermissionService permissions;
    private final AccessLogService accessLog;
    private final BizClock clock;

    /** 아래 탭 한 칸 */
    public record Tab(String code, String path, String labelKey) {
    }

    @GetMapping(HOME)
    public String home(Model model) {
        Map<String, Boolean> can = shell(model, "home");
        accessLog.recordCurrent(AccessEvent.PAGE_VIEW, P_HOME, null, Result.OK, NOTE);
        log.debug("모바일 홈 can={}", can);
        return "m/home";
    }

    @GetMapping(APPROVAL)
    public String approval(Model model, HttpServletResponse response) {
        Map<String, Boolean> can = shell(model, "approval");
        if (!can.get("approval")) {
            return forbidden(response, P_APPROVAL, Action.SEARCH);
        }
        accessLog.recordCurrent(AccessEvent.PAGE_VIEW, P_APPROVAL, null, Result.OK, NOTE);
        return "m/approval";
    }

    /** LOT·설비 찾기 — 둘 중 가진 쪽만 보인다. 둘 다 없으면 403 */
    @GetMapping(FIND)
    public String find(Model model, HttpServletResponse response) {
        Map<String, Boolean> can = shell(model, "find");
        if (!can.get("lot") && !can.get("equip")) {
            return forbidden(response, P_LOT, Action.SEARCH);
        }
        accessLog.recordCurrent(AccessEvent.PAGE_VIEW, can.get("lot") ? P_LOT : P_EQUIP_STATE, null, Result.OK, NOTE);
        return "m/find";
    }

    /** 부적합 사진 등록 — 등록 화면이라 조회 권한만으로는 열지 않는다(SAVE) */
    @GetMapping(NCR)
    public String ncr(Model model, HttpServletResponse response) {
        Map<String, Boolean> can = shell(model, "ncr");
        if (!can.get("ncr")) {
            return forbidden(response, P_NCR, Action.SAVE);
        }
        accessLog.recordCurrent(AccessEvent.PAGE_VIEW, P_NCR, null, Result.OK, NOTE);
        return "m/ncr";
    }

    /** 오늘 작업지시·설비 상태(읽기 전용) — 둘 중 가진 쪽만. 둘 다 없으면 403 */
    @GetMapping(STATUS)
    public String status(Model model, HttpServletResponse response) {
        Map<String, Boolean> can = shell(model, "status");
        if (!can.get("wo") && !can.get("equip")) {
            return forbidden(response, P_WO, Action.SEARCH);
        }
        accessLog.recordCurrent(AccessEvent.PAGE_VIEW, can.get("wo") ? P_WO : P_EQUIP_STATE, null, Result.OK, NOTE);
        return "m/status";
    }

    @GetMapping(ME)
    public String me(Model model) {
        shell(model, "me");
        accessLog.recordCurrent(AccessEvent.PAGE_VIEW, P_MY_INFO, null, Result.OK, NOTE);
        return "m/me";
    }

    /**
     * 레이아웃 조각(m/layout)이 쓰는 값: mUser(로그인 사용자)·can(조각·단추 노출)·tabs(아래 탭)·activeTab·today(회사 시간대 오늘).
     * 권한은 요청마다 다시 읽는다(PermissionService — 세션에 두지 않는다)
     */
    private Map<String, Boolean> shell(Model model, String active) {
        LoginUser user = CurrentUser.get().orElseThrow(() -> new BusinessException(HbErrorCode.UNAUTHENTICATED));
        Map<String, Boolean> can = new LinkedHashMap<>();
        can.put("cards", has(P_HOME, Action.SEARCH));
        can.put("approval", has(P_APPROVAL, Action.SEARCH));
        can.put("inbox", has(P_INBOX, Action.SEARCH));
        can.put("lot", has(P_LOT, Action.SEARCH));
        can.put("equip", has(P_EQUIP_STATE, Action.SEARCH));
        can.put("ncr", has(P_NCR, Action.SAVE));
        can.put("wo", has(P_WO, Action.SEARCH));
        can.put("locale", has(P_MY_INFO, Action.SAVE));

        List<Tab> tabs = new ArrayList<>();
        tabs.add(new Tab("home", HOME, "m.tab.home"));
        if (can.get("approval")) {
            tabs.add(new Tab("approval", APPROVAL, "m.tab.approval"));
        }
        if (can.get("lot") || can.get("equip")) {
            tabs.add(new Tab("find", FIND, "m.tab.find"));
        }
        if (can.get("wo") || can.get("equip")) {
            tabs.add(new Tab("status", STATUS, "m.tab.status"));
        }
        tabs.add(new Tab("me", ME, "m.tab.me"));

        model.addAttribute("mUser", user);
        model.addAttribute("can", can);
        model.addAttribute("tabs", tabs);
        model.addAttribute("activeTab", active);
        model.addAttribute("today", clock.today().toString());
        return can;
    }

    private boolean has(String progCd, Action action) {
        Set<Action> actions = permissions.actions(progCd);
        return actions.contains(action);
    }

    private String forbidden(HttpServletResponse response, String progCd, Action action) {
        log.info("모바일 화면 권한 없음 prog={}", progCd);
        accessLog.recordCurrent(AccessEvent.PERM_DENIED, progCd, action.name(), Result.DENIED, NOTE);
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        return FORBIDDEN_VIEW;
    }
}
