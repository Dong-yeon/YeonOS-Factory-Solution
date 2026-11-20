package io.smarthappy.module.home;

import java.util.List;
import java.util.Locale;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.smarthappy.module.home.HomeService.CardReload;
import io.smarthappy.module.home.HomeService.HomeView;
import io.smarthappy.module.home.HomeService.SettingForm;
import io.smarthappy.module.home.HomeService.SettingView;
import io.smarthappy.platform.auth.Action;
import io.smarthappy.platform.auth.ProgAuth;
import io.smarthappy.platform.common.web.ApiResponse;
import lombok.RequiredArgsConstructor;

/** 홈 카드 API (docs/37 4.3) — 홈 화면 조회 권한 + 카드마다 그 링크 화면 조회 권한(HomeService) */
@RestController
@RequiredArgsConstructor
public class HomeApiController {

    private final HomeService service;
    private final io.smarthappy.platform.realtime.SseHub realtime;

    /**
     * 실시간 신호(SSE 시범, docs/70 3장) — 홈 화면을 볼 권한이 있는 사람만. 회사·사용자는 로그인 principal 에서만.
     * 이벤트는 "바뀌었다" 신호뿐 — 화면은 받으면 위 카드 API 를 다시 부른다(권한·회사 범위는 그 API 가 판단).
     */
    @GetMapping(value = "/api/home/stream", produces = org.springframework.http.MediaType.TEXT_EVENT_STREAM_VALUE)
    @ProgAuth(prog = HomeService.PROG, action = Action.SEARCH)
    public org.springframework.web.servlet.mvc.method.annotation.SseEmitter stream(jakarta.servlet.http.HttpServletResponse res) {
        io.smarthappy.platform.security.LoginUser me = io.smarthappy.platform.security.CurrentUser.get()
                .orElseThrow(() -> new io.smarthappy.platform.common.web.BusinessException(
                        io.smarthappy.platform.common.web.HbErrorCode.UNAUTHENTICATED));
        res.setHeader("Cache-Control", "no-store");
        res.setHeader("X-Accel-Buffering", "no");   // nginx 가 신호를 모아 두지 않게(Caddy 는 이벤트 스트림을 바로 흘린다)
        return realtime.open(me.compCd(), me.userId());
    }

    @GetMapping("/api/home/cards")
    @ProgAuth(prog = HomeService.PROG, action = Action.SEARCH)
    public ApiResponse<HomeView> cards(@RequestParam(required = false) String plant, Locale locale) {
        return ApiResponse.ok(service.cards(locale, plant));
    }

    /** 공장 선택지(5단계, docs/37 17.2) — 사용 중 공장 */
    @GetMapping("/api/home/plants")
    @ProgAuth(prog = HomeService.PROG, action = Action.SEARCH)
    public ApiResponse<Void> plants() {
        List<HomeMapper.PlantRow> list = service.plants();
        return ApiResponse.list(list, list.size());
    }

    /** 카드 하나 다시 읽기(4단계) — 볼 수 없는·없는 카드는 404 */
    @GetMapping("/api/home/cards/{code}")
    @ProgAuth(prog = HomeService.PROG, action = Action.SEARCH)
    public ApiResponse<CardReload> card(@PathVariable String code, @RequestParam(required = false) String plant, Locale locale) {
        return ApiResponse.ok(service.card(code, locale, plant));
    }

    /** 카드 설정(숨김·순서, 2단계) — 본인 설정이라 홈 조회 권한이면 된다(홈 화면의 액션은 SEARCH 하나) */
    @GetMapping("/api/home/settings")
    @ProgAuth(prog = HomeService.PROG, action = Action.SEARCH)
    public ApiResponse<List<SettingView>> settings(Locale locale) {
        return ApiResponse.ok(service.settings(locale));
    }

    @PutMapping("/api/home/settings")
    @ProgAuth(prog = HomeService.PROG, action = Action.SEARCH)
    public ApiResponse<String> saveSettings(@RequestBody(required = false) List<SettingForm> in, Locale locale) {
        service.saveSettings(in, locale);
        return ApiResponse.ok("OK");
    }

    @PostMapping("/api/home/settings/reset")
    @ProgAuth(prog = HomeService.PROG, action = Action.SEARCH)
    public ApiResponse<String> resetSettings() {
        service.resetSettings();
        return ApiResponse.ok("OK");
    }
}
