package io.smarthappy.module.home;

import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.Locale;

import org.springframework.context.MessageSource;

/**
 * 카드 계산 문맥 — 모든 카드가 같은 "지금"을 본다(자정 무렵 카드마다 날짜가 갈리지 않게).
 * 문구는 로그인 사용자 언어로 서버가 푼다.
 * <ul>
 * <li>{@link #now()}·{@link #today()} = 회사 시간대</li>
 * <li>{@link #plantCd()} = 홈에서 고른 공장(null = 전체), {@link #plantNow()}·{@link #plantToday()} = 그 공장 시간대(공장 시간대가 없거나
 *     전체면 회사와 같다, 5단계 docs/37 17.2). 공장 카드({@link HomeCardProvider#plantScoped()})만 쓴다 — 나머지 카드는 링크 대상 목록이
 *     회사 날짜로 계산하므로 {@link #today()} 를 써야 숫자가 같다(H21)</li>
 * </ul>
 */
public record HomeCardContext(ZonedDateTime now, long userId, Locale locale, MessageSource messages, String plantCd,
        ZonedDateTime plantNow) {

    /** 공장 선택 없음 — 공장 시각 = 회사 시각 */
    public HomeCardContext(ZonedDateTime now, long userId, Locale locale, MessageSource messages) {
        this(now, userId, locale, messages, null, now);
    }

    public HomeCardContext {
        plantNow = plantNow == null ? now : plantNow;
    }

    /** 회사 시간대 오늘 */
    public LocalDate today() {
        return now.toLocalDate();
    }

    /** 고른 공장 시간대 오늘(전체 = 회사 오늘) */
    public LocalDate plantToday() {
        return plantNow.toLocalDate();
    }

    public String msg(String key, Object... args) {
        return messages.getMessage(key, args, key, locale);
    }
}
