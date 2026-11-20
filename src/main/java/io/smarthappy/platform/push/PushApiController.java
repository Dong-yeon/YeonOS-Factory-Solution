package io.smarthappy.platform.push;

import java.util.Map;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import io.smarthappy.platform.auth.ProgAuthExempt;
import io.smarthappy.platform.common.web.ApiResponse;
import io.smarthappy.platform.push.PushService.Status;
import lombok.RequiredArgsConstructor;

/**
 * 내 기기 Web Push 구독 (docs/45 9장) — 로그인한 사람 누구나 자기 기기만. 2단계 인증이 필요한 사람은 2단계를 마쳐야
 * 이 API 에 닿는다(기존 필터 — N10, 새 규칙 없음). 협력사 포털 사용자는 /api/portal 밖이라 닿지 않는다(PortalBoundaryFilter)
 */
@RestController
@RequiredArgsConstructor
public class PushApiController {

    private final PushService push;

    /** 켜졌는지·서버 공개 키·내 기기 목록 */
    @GetMapping("/api/platform/push")
    @ProgAuthExempt(reason = "내 기기 푸시 구독 상태 — 본인 것만")
    public ApiResponse<Status> status() {
        return ApiResponse.ok(push.status());
    }

    public record SubscribeForm(String endpoint, Keys keys, String label) {
        public record Keys(String p256dh, String auth) {
        }
    }

    /** 브라우저 PushSubscription.toJSON() 모양 그대로 + 기기 이름 */
    @PostMapping("/api/platform/push/subscriptions")
    @ProgAuthExempt(reason = "이 기기를 내 푸시 받는 곳으로 등록 — 본인 것만")
    public ApiResponse<Map<String, Long>> subscribe(@RequestBody(required = false) SubscribeForm in) {
        SubscribeForm f = in == null ? new SubscribeForm(null, null, null) : in;
        SubscribeForm.Keys k = f.keys() == null ? new SubscribeForm.Keys(null, null) : f.keys();
        return ApiResponse.ok(Map.of("pushSubId", push.subscribe(f.endpoint(), k.p256dh(), k.auth(), f.label())));
    }

    public record UnsubscribeForm(String endpoint, Long pushSubId) {
    }

    @DeleteMapping("/api/platform/push/subscriptions")
    @ProgAuthExempt(reason = "내 기기 푸시 해지 — 본인 것만")
    public ApiResponse<String> unsubscribe(@RequestBody(required = false) UnsubscribeForm in) {
        push.unsubscribe(in == null ? null : in.endpoint(), in == null ? null : in.pushSubId());
        return ApiResponse.ok("OK");
    }
}
