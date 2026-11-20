package io.smarthappy.platform.excel;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.HbErrorCode;

/**
 * grid 메뉴 엑셀 요청 본문 (docs/73 3장) — hb.grid 가 보내는 {@code {조회 조건…, sort: ["필드,방향"], columns: ["필드"]}}.
 *
 * 조회 조건은 목록 API 의 쿼리 파라미터와 같은 이름·같은 글자로 꺼낸다({@link #str}) — 검사·해석은 목록 서비스가 그대로 한다.
 * 글자로 바꾸는 규칙은 hb.grid 가 목록 주소를 만드는 규칙과 같다: null·빈 글자 = 없는 조건(null), 숫자·참거짓 = 글자,
 * 배열 = 쉼표로 이은 글자(`String(값)`). 그래서 같은 화면 조건이면 목록 핸들러와 내보내기 핸들러가 서비스에 같은 값을 넘긴다.
 * page·size·total 은 본문에서 받지 않는다(내보내기가 정한다): size = 정책 상한, 첫 쪽만 건수 포함.
 * 형이 안 맞는 값(글자 자리에 객체·배열 등)은 400.
 */
public final class GridExportQuery {

    private static final Set<String> RESERVED = Set.of("sort", "columns", "page", "size", "total");
    private static final int MAX_LIST = 200;
    private static final int MAX_TEXT = 2000;

    private final Map<String, Object> body;
    private final int page;
    private final int size;

    private GridExportQuery(Map<String, Object> body, int page, int size) {
        this.body = body;
        this.page = page;
        this.size = size;
    }

    static GridExportQuery of(Map<String, Object> body, int size) {
        return new GridExportQuery(body == null ? Map.of() : body, 1, size);
    }

    GridExportQuery atPage(int p) {
        return new GridExportQuery(body, p, size);
    }

    /** 목록 서비스에 넘길 쪽 번호(1부터) */
    public Integer page() {
        return page;
    }

    /** 목록 서비스에 넘길 쪽 크기 = 정책 GRID_PAGE_SIZE_MAX */
    public Integer size() {
        return size;
    }

    /** 건수 포함 여부 — 첫 쪽만(건수 상한을 파일을 만들기 전에 본다) */
    public Boolean total() {
        return page == 1;
    }

    /** 글자 조건 — 없거나 빈 글자면 null. 숫자·참거짓은 글자로, 배열은 쉼표로 이어서(hb.grid 의 목록 쿼리와 같게) */
    public String str(String name) {
        Object v = body.get(name);
        if (v instanceof List<?> list) {
            v = String.join(",", strs(name));
        }
        String s = scalar(v);
        return s == null || s.isEmpty() ? null : s;
    }

    public Integer intValue(String name) {
        Long v = longValue(name);
        if (v != null && (v < Integer.MIN_VALUE || v > Integer.MAX_VALUE)) {
            throw bad();
        }
        return v == null ? null : v.intValue();
    }

    public Long longValue(String name) {
        String s = str(name);
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(s.strip());
        } catch (NumberFormatException e) {
            throw bad();
        }
    }

    public Boolean bool(String name) {
        String s = str(name);
        if (s == null || s.isBlank()) {
            return null;
        }
        if (!s.equals("true") && !s.equals("false")) {
            throw bad();
        }
        return Boolean.valueOf(s);
    }

    public LocalDate date(String name) {
        String s = str(name);
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(s.strip());
        } catch (DateTimeParseException e) {
            throw bad();
        }
    }

    /** 여러 값 조건(배열) — 없으면 null. 글자 하나로 오면 한 칸짜리 목록 */
    public List<String> strs(String name) {
        Object v = body.get(name);
        if (v == null) {
            return null;
        }
        if (!(v instanceof List<?> list)) {
            return List.of(scalar(v));
        }
        if (list.isEmpty()) {
            return List.of();
        }
        if (list.size() > MAX_LIST) {
            throw bad();
        }
        List<String> out = new ArrayList<>();
        for (Object o : list) {
            String s = scalar(o);
            if (s == null) {
                throw bad();
            }
            out.add(s);
        }
        return out;
    }

    /** 정렬(`필드,방향`) — 없거나 비면 null(목록 서비스의 기본 정렬). 허용 목록 검사는 목록 서비스(PageQuery) */
    public List<String> sort() {
        List<String> s = strs("sort");
        return s == null || s.isEmpty() ? null : s;
    }

    /** 보이는 열(요청 순서) — 없으면 null(내보낼 수 있는 전체) */
    List<String> columns() {
        return strs("columns");
    }

    /** 값이 있는 조건의 이름(값 제외) — 로그·출력 정보 시트 */
    List<String> conditionKeys() {
        List<String> keys = new ArrayList<>();
        body.forEach((k, v) -> {
            if (!RESERVED.contains(k) && v != null && !(v instanceof CharSequence cs && cs.toString().isBlank())
                    && !(v instanceof List<?> l && l.isEmpty()) && k.length() <= 60) {
                keys.add(k);
            }
        });
        keys.sort(null);
        return keys;
    }

    private static String scalar(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof String s) {
            if (s.length() > MAX_TEXT) {
                throw bad();
            }
            return s;
        }
        if (v instanceof Number || v instanceof Boolean) {
            return String.valueOf(v);
        }
        throw bad();
    }

    private static BusinessException bad() {
        return new BusinessException(HbErrorCode.INVALID_VALUE);
    }
}
