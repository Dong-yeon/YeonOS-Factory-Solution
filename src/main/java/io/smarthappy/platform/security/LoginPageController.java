package io.smarthappy.platform.security;

import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import io.smarthappy.platform.config.HappyBootProperties;
import lombok.RequiredArgsConstructor;

/** 로그인 화면과 로그인 후 첫 화면(임시 — 메뉴·탭 셸은 P0 4단계) */
@Controller
@RequiredArgsConstructor
public class LoginPageController {

    private static final Set<String> ERROR_CODES = Set.of("bad", "locked", "inactive", "throttled");

    private final HappyBootProperties props;

    /** local 전용: 개발 회사·아이디를 미리 채운다(비밀번호는 채우지 않음). application-local.yml 에만 켜져 있고 기본은 꺼짐 */
    @Value("${happyboot.dev.login-prefill:false}")
    boolean prefill;
    @Value("${happyboot.dev.comp-cd:}")
    String devCompCd;
    @Value("${happyboot.dev.admin-login-id:}")
    String devLoginId;
    /**
     * demo 전용: 공개 데모 안내(체험 계정 회사·아이디·비밀번호, 매일 초기화). 손님이 바로 들어올 수 있게 비밀번호도 화면에 쓴다
     * (2026-10-26 사용자 결정 — 전에는 README 에만). 공개하는 체험 계정 값이고 권한은 DEMO_VIEWER(조회 위주)뿐이다
     */
    @Value("${happyboot.demo.notice:false}")
    boolean demoNotice;
    @Value("${happyboot.demo.login-id:user1}")
    String demoLoginId;
    @Value("${happyboot.demo.password:}")
    String demoPassword;

    @GetMapping("/login")
    public String login(@RequestParam(required = false) String error, Model model) {
        model.addAttribute("onprem", props.deploy().onprem());
        // 알 수 없는 오류 코드는 무시 (URL 조작으로 임의 문구가 뜨지 않게 — 문구는 메시지 키로만)
        model.addAttribute("errorCode", error != null && ERROR_CODES.contains(error) ? error : null);
        model.addAttribute("demoNotice", demoNotice);
        if (demoNotice) {
            String comp = devCompCd == null || devCompCd.isBlank() ? "DEMO" : devCompCd;
            model.addAttribute("demoCompCd", comp);
            model.addAttribute("demoLoginId", demoLoginId);
            model.addAttribute("demoPassword", demoPassword);
            // 회사·아이디는 미리 채운다(비밀번호는 안내 문구를 보고 입력)
            model.addAttribute("prefillCompCd", comp);
            model.addAttribute("prefillLoginId", demoLoginId);
        }
        if (prefill) {
            model.addAttribute("prefillCompCd", devCompCd);
            model.addAttribute("prefillLoginId", devLoginId);
        }
        return "login";
    }

    @GetMapping("/")
    public String root() {
        return "redirect:/main";
    }

    @GetMapping("/main")
    public String main(@AuthenticationPrincipal LoginUser user, Model model) {
        model.addAttribute("user", user);
        return "main";
    }
}
