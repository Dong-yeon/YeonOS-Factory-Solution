package io.smarthappy.platform.grid;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.context.MessageSource;

import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.HbErrorCode;

/**
 * 그리드·엑셀 공용 열 정의 (docs/09 G2: 서버가 열 정의를 갖고 화면은 표시 속성만 덧붙인다).
 * 엑셀 내보내기 열 화이트리스트·헤더 문구·셀 서식이 모두 여기서 나온다.
 *
 * @param field      응답 필드 이름(camelCase, DTO getter 이름)
 * @param titleKey   헤더 문구 i18n 키
 * @param type       표시·정렬·엑셀 서식
 * @param scale      DEC 소수 자릿수
 * @param codeGrp    CODE 의 공통코드 그룹
 * @param width      기본 폭(px)
 * @param sortable   서버 정렬 가능 (SortField 에 있는 필드만 true)
 * @param exportable 엑셀 포함 가능 (개인정보 열은 false)
 * @param priority   좁을 때 "열 맞춤"에서 숨기는 순서 (1 항상 보임 · 2 · 3 먼저 숨김, docs/15 R6)
 * @param minWidth   열 폭 드래그 하한(px)
 * @param flex       넓을 때 남는 폭을 받는 열 (이름·규격처럼 길어지는 글자)
 * @param minScale   DEC 최소 표시 자릿수 — 기본 = scale(끝 0 채움). 작으면 scale 까지만 보이고 끝 0 은 안 채운다(순질량 6자리)
 * @param labelKey   LABEL 의 문구 키 접두어(값 → {@code labelKey + 값}) — docs/36 18.9 X2
 * @param labelValues LABEL 이 가질 수 있는 값(화면에 번역 표를 내려줄 목록)
 */
public record ColumnSpec(String field, String titleKey, ColumnType type, int scale, String codeGrp, int width,
        boolean sortable, boolean exportable, int priority, int minWidth, boolean flex, int minScale, String labelKey,
        List<String> labelValues) {

    private static final java.util.regex.Pattern LABEL_KEY = java.util.regex.Pattern.compile("^[a-zA-Z][a-zA-Z0-9_.]*\\.$");
    private static final java.util.regex.Pattern LABEL_VALUE = java.util.regex.Pattern.compile("^[A-Za-z0-9_]{1,40}$");

    public ColumnSpec {
        if (priority < 1 || priority > 3 || minWidth < 20 || minWidth > width || minScale < 0 || minScale > scale) {
            throw new IllegalArgumentException("priority 1~3, 20 <= minWidth <= width: " + field);
        }
        if (type == ColumnType.LABEL && (labelKey == null || !LABEL_KEY.matcher(labelKey).matches() || labelValues == null
                || labelValues.isEmpty() || !labelValues.stream().allMatch(v -> v != null && LABEL_VALUE.matcher(v).matches()))) {
            throw new IllegalArgumentException("LABEL 은 문구 키 접두어(점으로 끝)와 값 목록(영문·숫자·_) 필요: " + field);
        }
        labelValues = labelValues == null ? null : List.copyOf(labelValues);
    }

    /** 모듈 코드가 쓰던 12칸 생성자 — LABEL 이 아닌 열 */
    public ColumnSpec(String field, String titleKey, ColumnType type, int scale, String codeGrp, int width,
            boolean sortable, boolean exportable, int priority, int minWidth, boolean flex, int minScale) {
        this(field, titleKey, type, scale, codeGrp, width, sortable, exportable, priority, minWidth, flex, minScale, null, null);
    }

    /**
     * LABEL = 코드에 고정된 몇 개 값(열거형·계산 값)을 서버 문구로 바꿔 보이는 칸 (docs/36 18.9 X2) — 화면(toView 의 번역 표)과
     * 엑셀이 같은 문구. 쉼표로 여러 값이면 나눠 각각 바꾸고 ", " 로 잇는다. 문구가 없는 값은 그대로(조용히 비우지 않는다)
     */
    public enum ColumnType {
        TEXT, INT, DEC, DATE, DATETIME, CODE, YN, LABEL
    }

    private static int defaultMin(int width) {
        return Math.min(width, 60);
    }

    public static ColumnSpec text(String field, String titleKey, int width) {
        return new ColumnSpec(field, titleKey, ColumnType.TEXT, 0, null, width, false, true, 2, defaultMin(width), false, 0);
    }

    public static ColumnSpec code(String field, String titleKey, String codeGrp, int width) {
        return new ColumnSpec(field, titleKey, ColumnType.CODE, 0, codeGrp, width, false, true, 2, defaultMin(width), false, 0);
    }

    public static ColumnSpec of(String field, String titleKey, ColumnType type, int width) {
        return new ColumnSpec(field, titleKey, type, 0, null, width, false, true, 2, defaultMin(width), false, 0);
    }

    /**
     * 고정 값 → 서버 문구 칸 (docs/36 18.9 X2). 예 {@code labels("review", "apqp.col.review", "apqp.review.", 110, "ROUTING", "FMEA")}
     * → 값 ROUTING 은 문구 키 {@code apqp.review.ROUTING}. 정렬 없음·내보내기 가능(다른 열처럼 with… 로 바꾼다)
     */
    public static ColumnSpec labels(String field, String titleKey, String labelKey, int width, String... values) {
        return new ColumnSpec(field, titleKey, ColumnType.LABEL, 0, null, width, false, true, 2, defaultMin(width), false, 0,
                labelKey, values == null ? null : List.of(values));
    }

    /** 이 LABEL 열의 값 → 문구 표(보는 사람 언어, 정의한 순서). LABEL 이 아니면 빈 표 */
    public Map<String, String> labelMap(MessageSource messages, Locale locale) {
        Map<String, String> m = new LinkedHashMap<>();
        if (type == ColumnType.LABEL) {
            labelValues.forEach(v -> m.put(v, messages.getMessage(labelKey + v, null, v, locale)));
        }
        return m;
    }

    /** LABEL 값 → 글자 — 쉼표로 여러 값이면 나눠 바꾸고 ", " 로 잇는다. 표에 없는 값은 그대로. 화면 hb.fmt.label 과 같은 규칙 */
    public static String labelText(Map<String, String> labels, String raw) {
        if (raw == null) {
            return null;
        }
        List<String> out = new ArrayList<>();
        for (String part : raw.split(",")) {
            String v = part.strip();
            if (!v.isEmpty()) {
                out.add(labels.getOrDefault(v, v));
            }
        }
        return String.join(", ", out);
    }

    /** 소수 열 (DEC) */
    public static ColumnSpec dec(String field, String titleKey, int scale, int width) {
        return new ColumnSpec(field, titleKey, ColumnType.DEC, scale, null, width, false, true, 2, defaultMin(width), false, scale);
    }

    public ColumnSpec withSort() {
        return new ColumnSpec(field, titleKey, type, scale, codeGrp, width, true, exportable, priority, minWidth, flex, minScale, labelKey, labelValues);
    }

    public ColumnSpec noExport() {
        return new ColumnSpec(field, titleKey, type, scale, codeGrp, width, sortable, false, priority, minWidth, flex, minScale, labelKey, labelValues);
    }

    /** 숨김 순서 1(항상 보임)·2·3(먼저 숨김) */
    public ColumnSpec withPriority(int p) {
        return new ColumnSpec(field, titleKey, type, scale, codeGrp, width, sortable, exportable, p, minWidth, flex, minScale, labelKey, labelValues);
    }

    /** 남는 폭을 받는 열 */
    public ColumnSpec withFlex() {
        return new ColumnSpec(field, titleKey, type, scale, codeGrp, width, sortable, exportable, priority, minWidth, true, minScale, labelKey, labelValues);
    }

    /** DEC 끝 0 을 이 자릿수까지만 채운다(나머지는 값 있을 때만, 최대 scale) */
    public ColumnSpec withMinScale(int s) {
        return new ColumnSpec(field, titleKey, type, scale, codeGrp, width, sortable, exportable, priority, minWidth, flex, s, labelKey, labelValues);
    }

    public ColumnSpec withMinWidth(int px) {
        return new ColumnSpec(field, titleKey, type, scale, codeGrp, width, sortable, exportable, priority, px, flex, minScale, labelKey, labelValues);
    }

    /** 화면에 내려주는 형태 (헤더 문구는 사용자 언어로 번역) */
    public Map<String, Object> toView(MessageSource messages, Locale locale) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("field", field);
        m.put("title", messages.getMessage(titleKey, null, field, locale));
        m.put("type", type.name());
        if (scale > 0) {
            m.put("scale", scale);
        }
        if (minScale < scale) {
            m.put("minScale", minScale);
        }
        if (codeGrp != null) {
            m.put("codeGrp", codeGrp);
        }
        if (type == ColumnType.LABEL) {
            m.put("labels", labelMap(messages, locale));   // 화면은 이 표로 그린다(엑셀과 같은 문구)
        }
        m.put("width", width);
        m.put("sortable", sortable);
        m.put("exportable", exportable);
        m.put("priority", priority);
        m.put("minWidth", minWidth);
        m.put("flex", flex);
        return m;
    }

    /** 요청한 열 이름 → 정의(요청 순서 유지). 정의에 없거나 내보내기 불가·중복이면 400. 비면 내보내기 가능한 전체 */
    public static List<ColumnSpec> selectExportable(List<ColumnSpec> all, List<String> requested) {
        if (requested == null || requested.isEmpty()) {
            return all.stream().filter(ColumnSpec::exportable).toList();
        }
        if (requested.size() > all.size()) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        List<ColumnSpec> out = new ArrayList<>();
        for (String f : requested) {
            ColumnSpec spec = all.stream().filter(c -> c.field().equals(f)).findFirst().orElse(null);
            if (spec == null || !spec.exportable() || out.contains(spec)) {
                throw new BusinessException(HbErrorCode.INVALID_VALUE);
            }
            out.add(spec);
        }
        return out;
    }
}
