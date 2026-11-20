package io.smarthappy.platform.notify;

import java.util.List;
import java.util.Map;

/**
 * 보낼 알림 한 건. 문구는 키 + 인자로 — 읽는 사람 언어로 그때 번역한다(제목 키는 {@code notify.} 로 시작).
 *
 * @param dedupKey   같은 (사람, 이벤트) 안에서 한 번만 — 재시도·이중 호출 멱등 (예: 결재 id·차례, 날짜)
 * @param linkProg   누르면 열 화면(없으면 null). 받는 사람이 그 화면 SEARCH 가 없으면 보내지 않는다(Q15)
 * @param linkParams 그 화면 조회 조건(셸 q.* — docs/37 14.2)
 * @param lines      세부 줄(하루 요약 등)
 */
public record NotifyMessage(String eventCd, long userId, String dedupKey, String titleKey, List<String> titleArgs,
        String linkProg, Map<String, String> linkParams, List<Line> lines) {

    /** 세부 한 줄: 문구 키(notify.)·숫자·누르면 열 화면 */
    public record Line(String labelKey, long count, String progCd) {
    }

    public static NotifyMessage of(String eventCd, long userId, String dedupKey, String titleKey, String... args) {
        return new NotifyMessage(eventCd, userId, dedupKey, titleKey, List.of(args), null, Map.of(), List.of());
    }

    public NotifyMessage withLink(String progCd, Map<String, String> params) {
        return new NotifyMessage(eventCd, userId, dedupKey, titleKey, titleArgs, progCd, params, lines);
    }

    public NotifyMessage withLines(List<Line> l) {
        return new NotifyMessage(eventCd, userId, dedupKey, titleKey, titleArgs, linkProg, linkParams, l);
    }
}
