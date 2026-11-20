package io.smarthappy.platform.common.query;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.HbErrorCode;
import lombok.Getter;

/**
 * 서버 페이징·정렬 요청 (docs/09 4.5). `?page=1&size=50&sort=custNm,asc&sort=custCd,desc&total=true`
 *
 * - 정렬 필드는 리소스별 화이트리스트 enum({@link SortField})만 — 모르는 필드는 400. MyBatis 는 enum 이름으로
 *   `<choose>` 해서 열 이름을 고른다(`${}` 금지). 정렬 키 최대 3개, 마지막에 PK 를 매퍼가 덧붙인다(페이지 경계 중복·누락 방지)
 * - size 는 정책 상한(GRID_PAGE_SIZE_MAX) 이하, page 는 1부터
 * MyBatis OGNL 이 읽을 수 있게 레코드 대신 getter 클래스로 둔다.
 */
@Getter
public final class PageQuery {

    public static final int MAX_SORTS = 3;
    static final int MAX_PAGE = 100_000;

    @Getter
    public static final class Sort {
        private final String key;
        private final boolean desc;

        Sort(String key, boolean desc) {
            this.key = key;
            this.desc = desc;
        }
    }

    private final int page;
    private final int size;
    private final List<Sort> sorts;
    private final boolean withTotal;

    private PageQuery(int page, int size, List<Sort> sorts, boolean withTotal) {
        this.page = page;
        this.size = size;
        this.sorts = List.copyOf(sorts);
        this.withTotal = withTotal;
    }

    public long getOffset() {
        return (long) (page - 1) * size;
    }

    /**
     * @param sortParams `field,asc|desc` 목록 (null 이면 기본 정렬)
     * @param allowed    리소스 정렬 enum 의 값들
     * @param defaults   기본 정렬 (sortParams 가 비었을 때)
     */
    public static <E extends Enum<E> & SortField> PageQuery parse(Integer page, Integer size, List<String> sortParams,
            Boolean total, E[] allowed, int maxSize, List<String> defaults) {
        int p = page == null ? 1 : page;
        int s = size == null ? Math.min(50, maxSize) : size;
        if (p < 1 || p > MAX_PAGE || s < 1 || s > maxSize) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        return new PageQuery(p, s, sorts(sortParams == null || sortParams.isEmpty() ? defaults : sortParams, allowed),
                total == null || total);
    }

    /** 정렬만 (엑셀 내보내기 — 페이지 없음) */
    public static <E extends Enum<E> & SortField> PageQuery sortOnly(List<String> sortParams, E[] allowed,
            List<String> defaults) {
        return new PageQuery(1, Integer.MAX_VALUE, sorts(sortParams == null || sortParams.isEmpty() ? defaults
                : sortParams, allowed), false);
    }

    private static <E extends Enum<E> & SortField> List<Sort> sorts(List<String> params, E[] allowed) {
        if (params.size() > MAX_SORTS) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        List<Sort> out = new ArrayList<>();
        for (String raw : params) {
            if (raw == null || raw.length() > 80) {
                throw new BusinessException(HbErrorCode.INVALID_VALUE);
            }
            String[] parts = raw.split(",", -1);
            if (parts.length > 2) {
                throw new BusinessException(HbErrorCode.INVALID_VALUE);
            }
            String dir = parts.length == 2 ? parts[1].trim().toLowerCase(Locale.ROOT) : "asc";
            if (!dir.equals("asc") && !dir.equals("desc")) {
                throw new BusinessException(HbErrorCode.INVALID_VALUE);
            }
            E match = null;
            for (E e : allowed) {
                if (e.field().equals(parts[0].trim())) {
                    match = e;
                }
            }
            String name = match == null ? null : match.name();
            if (match == null || out.stream().anyMatch(o -> o.key.equals(name))) {
                throw new BusinessException(HbErrorCode.INVALID_VALUE);
            }
            out.add(new Sort(match.name(), dir.equals("desc")));
        }
        return out;
    }

}
