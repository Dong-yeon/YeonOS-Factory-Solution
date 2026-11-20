package io.smarthappy.platform.notify;

import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 메일 알림 설정 (docs/36 18.3) — happyboot.mail.*. SMTP 서버·계정은 스프링 표준 spring.mail.*(환경 변수 SPRING_MAIL_HOST 등).
 * 자격증명은 설정·환경 변수로만 — 저장소·문서·DB 에 두지 않는다.
 *
 * @param enabled 켜면 구독 화면에 메일 칸이 보이고 알림이 메일로도 나간다. 켰는데 보내는 주소나 SMTP 서버가 없으면 기동 중단
 * @param from    보내는 주소
 * @param baseUrl 메일 본문의 "열기" 주소 앞부분(예 https://mes.example.com). 비우면 주소 줄 없이
 */
@Component
public class MailSettings {

    /** 받는·보내는 주소 — 메일 머리 주입이 안 되게 좁게(공백·꺾쇠·쉼표·줄바꿈 없음) */
    static final Pattern ADDRESS = Pattern.compile("^[A-Za-z0-9._%+-]{1,64}@[A-Za-z0-9.-]{1,190}\\.[A-Za-z]{2,24}$");
    private static final Pattern BASE_URL = Pattern.compile("^https?://[A-Za-z0-9.:\\[\\]-]{1,200}(/[A-Za-z0-9._~/-]{0,100})?$");

    private final boolean enabled;
    private final String from;
    private final String baseUrl;

    public MailSettings(@Value("${happyboot.mail.enabled:false}") boolean enabled, @Value("${happyboot.mail.from:}") String from,
            @Value("${happyboot.mail.base-url:}") String baseUrl, @Value("${spring.mail.host:}") String host) {
        this.enabled = enabled;
        this.from = from == null ? "" : from.trim();
        String b = baseUrl == null ? "" : baseUrl.trim();
        this.baseUrl = b.endsWith("/") ? b.substring(0, b.length() - 1) : b;
        if (enabled && !ADDRESS.matcher(this.from).matches()) {
            throw new IllegalStateException("happyboot.mail.enabled=true 이면 happyboot.mail.from 에 보내는 주소가 필요합니다");
        }
        if (enabled && (host == null || host.isBlank())) {
            throw new IllegalStateException("happyboot.mail.enabled=true 이면 spring.mail.host(SMTP 서버)가 필요합니다");
        }
        if (!this.baseUrl.isEmpty() && !BASE_URL.matcher(this.baseUrl).matches()) {
            throw new IllegalStateException("happyboot.mail.base-url 형식: http(s)://호스트[:포트][/경로]");
        }
    }

    public boolean enabled() {
        return enabled;
    }

    String from() {
        return from;
    }

    String baseUrl() {
        return baseUrl;
    }

    static boolean validAddress(String a) {
        return a != null && a.length() <= 200 && ADDRESS.matcher(a).matches();
    }
}
