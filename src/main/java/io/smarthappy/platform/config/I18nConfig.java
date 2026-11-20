package io.smarthappy.platform.config;

import java.util.List;
import java.util.Locale;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.LocaleResolver;

import io.smarthappy.platform.security.CurrentUser;
import io.smarthappy.platform.security.LoginUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

/**
 * 화면 언어. 로그인 사용자는 sys_user.locale_cd, POP 단말은 세션의 1탭 전환 값, 그 밖은 브라우저 Accept-Language,
 * 지원하지 않으면 한국어.
 * 언어를 늘릴 때: SUPPORTED 에 추가 + messages_{언어}.properties (키 집합은 I18nKeyGuardTest 가 확인).
 */
@Configuration(proxyBeanMethods = false)
public class I18nConfig {

    public static final Locale DEFAULT = Locale.KOREAN;
    public static final List<Locale> SUPPORTED = List.of(Locale.KOREAN, Locale.ENGLISH);
    /** 세션 속성: 로그인 사용자가 없는 화면(POP 단말)의 언어 1탭 전환 값 (ko/en) */
    public static final String SESSION_LOCALE = I18nConfig.class.getName() + ".locale";

    public static boolean isSupported(String localeCd) {
        return localeCd != null && SUPPORTED.stream().anyMatch(l -> l.getLanguage().equals(localeCd));
    }

    /** 지원 언어로 맞춘다 (ko_KR → ko, 지원 안 함 → ko) */
    public static Locale supported(Locale requested) {
        if (requested == null) {
            return DEFAULT;
        }
        return SUPPORTED.stream().filter(l -> l.getLanguage().equals(requested.getLanguage())).findFirst()
                .orElse(DEFAULT);
    }

    @Bean
    public LocaleResolver localeResolver() {
        return new LocaleResolver() {
            @Override
            public Locale resolveLocale(HttpServletRequest request) {
                LoginUser user = CurrentUser.get().orElse(null);
                if (user != null && isSupported(user.localeCd())) {
                    return Locale.forLanguageTag(user.localeCd());
                }
                HttpSession session = request.getSession(false);
                if (user == null && session != null && session.getAttribute(SESSION_LOCALE) instanceof String s
                        && isSupported(s)) {
                    return Locale.forLanguageTag(s);
                }
                return supported(request.getLocale());
            }

            @Override
            public void setLocale(HttpServletRequest request, HttpServletResponse response, Locale locale) {
                throw new UnsupportedOperationException("언어는 사용자 정보(sys_user.locale_cd)로만 바꾼다");
            }
        };
    }
}
