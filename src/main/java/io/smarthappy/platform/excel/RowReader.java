package io.smarthappy.platform.excel;

import java.lang.reflect.RecordComponent;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.BeanWrapperImpl;

/**
 * 엑셀 행에서 열 값을 읽는다 — 목록 API 가 화면에 주는 행 그대로 쓸 수 있게 getter 객체·레코드·Map 을 모두 받는다 (docs/73 3장).
 * 행에 그 이름의 값이 없으면 null(빈 칸) — 화면 그리드도 없는 필드는 빈 칸으로 그린다.
 */
interface RowReader {

    Object get(String field);

    /** 레코드 종류별 접근자 표(한 번만 만든다) */
    Map<Class<?>, Map<String, RecordComponent>> RECORDS = new ConcurrentHashMap<>();

    static RowReader of(Object row) {
        if (row instanceof Map<?, ?> m) {
            return m::get;
        }
        if (row.getClass().isRecord()) {
            Map<String, RecordComponent> parts = RECORDS.computeIfAbsent(row.getClass(), c -> {
                Map<String, RecordComponent> out = new HashMap<>();
                for (RecordComponent rc : c.getRecordComponents()) {
                    rc.getAccessor().setAccessible(true);
                    out.put(rc.getName(), rc);
                }
                return out;
            });
            return field -> {
                RecordComponent rc = parts.get(field);
                if (rc == null) {
                    return null;
                }
                try {
                    return rc.getAccessor().invoke(row);
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException("엑셀 행 값 읽기 실패: " + field, e);
                }
            };
        }
        BeanWrapperImpl w = new BeanWrapperImpl(row);
        return field -> w.isReadableProperty(field) ? w.getPropertyValue(field) : null;
    }
}
