package io.smarthappy.module.home;

/**
 * 홈 "내 할 일" 카드 제공자 (docs/37 4장). 각 모듈이 **자기 패키지에** 빈으로 둔다 — 홈은 모아 그리기만 한다.
 * <ul>
 * <li>{@link #progCd()} 화면의 조회(SEARCH) 권한이 있을 때만 카드가 보인다(R1). null = 개인 카드 — 로그인 사용자 본인 것만 센다(R2)</li>
 * <li>{@link #load} 는 카드마다 따로 연 읽기 전용 트랜잭션 + statement_timeout 안에서 불린다(R3). 실패하면 그 카드만 "불러오지 못함"(R4)</li>
 * <li>숫자는 "지금 손댈 것"(끝난·취소된 것 제외, R8), 사람별 집계 금지(R9). 날짜는 {@link HomeCardContext#today()}(회사 시간대),
 *     공장 카드는 {@link HomeCardContext#plantToday()}</li>
 * <li>카드 숫자 링크(HomeCard.link)는 화면이 조건을 모두 받을 때만 — 숫자 = 그 조건 목록 건수(docs/37 16.2)</li>
 * </ul>
 */
public interface HomeCardProvider {

    /** 카드 코드 — {@code ^[a-z][a-z0-9-]{1,39}$}, 전체에서 유일(기동 시 검사). 모듈 접두(prd-, sal-, qms- …) */
    String code();

    /** 제목 문구 키(ko/en) */
    String titleKey();

    /** 링크·권한 화면 코드. null = 개인 카드(링크 없음) */
    String progCd();

    /** 홈 기본 순서 — 모듈 sort_no 대역을 그대로 쓴다 */
    int sortNo();

    HomeCard load(HomeCardContext ctx);

    /**
     * 공장 카드인가(5단계, docs/37 17.2) — true 면 홈에서 고른 공장({@link HomeCardContext#plantCd()})으로 거르고 공장 날짜를 쓴다.
     * 화면은 이 카드에만 "공장 기준" 을 표시한다. 기본 false = 공장 선택과 상관없이 회사 기준
     */
    default boolean plantScoped() {
        return false;
    }
}
