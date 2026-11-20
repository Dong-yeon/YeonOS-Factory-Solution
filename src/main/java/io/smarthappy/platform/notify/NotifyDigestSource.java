package io.smarthappy.platform.notify;

import java.time.LocalDate;
import java.util.List;

/**
 * 하루 요약(DUE_DIGEST)의 원천 — 모듈이 빈으로 등록하면 정기 작업이 회사마다 부른다(회사 범위 트랜잭션 안).
 * 사람마다 "오늘 확인할 일" 줄을 돌려준다(0건은 빼고). 교정·PM·자격 만료 등은 각 모듈 몫.
 */
public interface NotifyDigestSource {

    /** today = 회사 날짜 */
    List<Due> due(LocalDate today);

    /** 한 사람의 한 줄 — 문구 키는 notify. 로 시작, 인자는 건수 하나({0}) */
    record Due(long userId, String labelKey, long count, String progCd) {
    }
}
