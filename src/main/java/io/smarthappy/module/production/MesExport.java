package io.smarthappy.module.production;

import java.util.List;
import java.util.Map;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;

import io.smarthappy.platform.grid.ColumnSpec;

/**
 * MES grid 엑셀 도우미 (docs/25 4.40) — 화면이 코드 값을 이름으로 바꿔 보이는 글자 칸(단말·LOT·수집 카운트 상태 등)을
 * 엑셀에서도 이름으로: 그 열을 이름 칸(같은 제목·폭)으로 바꾸고, 서비스가 행마다 이름 칸을 채운다. 코드 그룹 칸(CODE)은 엑셀 서비스가 코드명으로 바꾼다
 */
public final class MesExport {

    private MesExport() {
    }

    /**
     * 보이는 열 중 renamed 의 키를 값(다른 속성)으로 — 열 순서·제목·폭·형식은 그대로. 쓰임: 코드 칸 → 이름 칸,
     * 글자로 담긴 수량 칸(planQty 등, 화면 응답 규칙) → 숫자 속성(…Num) — 엑셀 DEC 칸은 숫자 값이어야 한다
     */
    public static List<ColumnSpec> named(List<ColumnSpec> cols, Map<String, String> renamed) {
        return cols.stream().map(c -> renamed.containsKey(c.field())
                ? new ColumnSpec(renamed.get(c.field()), c.titleKey(), c.type(), c.scale(), c.codeGrp(), c.width(), c.sortable(),
                        c.exportable(), c.priority(), c.minWidth(), c.flex(), c.minScale())
                : c).toList();
    }

    /** 글자 수량 → 숫자(엑셀 숫자 셀). 비었으면 null */
    public static java.math.BigDecimal num(String s) {
        return s == null || s.isBlank() ? null : new java.math.BigDecimal(s);
    }

    /** 코드 → 문구 키 표로 이름(요청 언어). 표에 없는 코드는 코드 그대로 */
    public static String name(MessageSource messages, Map<String, String> keys, String code) {
        String key = code == null ? null : keys.get(code);
        return key == null ? code : messages.getMessage(key, null, code, LocaleContextHolder.getLocale());
    }
}
