package io.smarthappy.platform.auth;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.context.MessageSource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.smarthappy.platform.common.web.ApiResponse;
import lombok.RequiredArgsConstructor;

/**
 * 화면 안 [관련 화면] 단추 (docs/79 3.0 방식 A) — 메뉴에서 뺀(합친) 화면을 남는 화면이 연다.
 * 화면 JS 는 hb.page.init 이 템플릿의 [data-prog-links] 칸을 보고 부른다. 로그인한 사용자 누구나, 판정은 내 권한으로만.
 */
@RestController
@RequiredArgsConstructor
public class ProgLinkApiController {

    /** 한 번에 물을 수 있는 화면 수 — 화면 안 단추는 많아야 서너 개 */
    static final int MAX_PROG_LINKS = 10;
    private static final Pattern PROG_CD = Pattern.compile("^[a-z0-9][a-z0-9-]{1,49}$");

    private final AuthMapperFacade progs;
    private final PermissionService permissions;
    private final MessageSource messages;

    /** 단추 하나 — 화면 코드와 그 화면 이름(지금 언어) */
    public record ProgLink(String progCd, String name) {
    }

    /**
     * 물은 화면 중 내가 조회 권한을 가진 것만, 물은 순서대로(화면 주소 /page/{progCd} 와 같은 판정).
     * 없는 화면·형식이 틀린 코드는 조용히 뺀다(있는지 없는지를 알려 주지 않음). 서로 다른 앞 {@value #MAX_PROG_LINKS}개까지만 본다
     */
    @GetMapping("/api/platform/prog-links")
    @ProgAuthExempt(reason = "자기 권한으로 열 수 있는 화면만 돌려준다 — 화면 안 [관련 화면] 단추(판정은 PermissionService, /page 와 같은 규칙)")
    public ApiResponse<List<ProgLink>> progLinks(@RequestParam("progs") String codes, Locale locale) {
        Set<String> asked = new LinkedHashSet<>();
        for (String c : codes.split(",")) {
            String cd = c.trim();
            if (PROG_CD.matcher(cd).matches() && asked.size() < MAX_PROG_LINKS) {
                asked.add(cd);
            }
        }
        List<ProgLink> out = new ArrayList<>();
        for (String cd : asked) {
            ProgInfo p = progs.findProg(cd);
            if (p != null && permissions.actions(cd).contains(Action.SEARCH)) {
                out.add(new ProgLink(cd, messages.getMessage(p.getProgNmKey(), null, cd, locale)));
            }
        }
        return ApiResponse.ok(out);
    }
}
