package io.smarthappy.platform.common.db.mybatis;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedTypes;

/**
 * MyBatis 전역 UUID 처리기 (QA docs/qa/2026-10-24_uuid-mapping.md, 상위 결정). 처리기가 없으면 PostgreSQL uuid 칸을 자바 {@link UUID}
 * 필드로 받을 때 자동 매핑이 그 칸을 건너뛰어 **오류 없이 null** 이 됐다 — 그 함정만 없앤다.
 * 쓰기는 예전과 같다(드라이버 {@code setObject} → uuid 형). 읽기는 드라이버가 준 UUID 를 그대로, 글자로 오면(::text 등) 형식을 검사해 바꾼다.
 * 등록: {@code mybatis.type-handlers-package}(application.yml)
 */
@MappedTypes(UUID.class)
public class UuidTypeHandler extends BaseTypeHandler<UUID> {

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, UUID parameter, JdbcType jdbcType) throws SQLException {
        ps.setObject(i, parameter);
    }

    @Override
    public UUID getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return toUuid(rs.getObject(columnName));
    }

    @Override
    public UUID getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return toUuid(rs.getObject(columnIndex));
    }

    @Override
    public UUID getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return toUuid(cs.getObject(columnIndex));
    }

    private static UUID toUuid(Object v) throws SQLException {
        if (v == null || v instanceof UUID) {
            return (UUID) v;
        }
        try {
            return UUID.fromString(v.toString());
        } catch (IllegalArgumentException e) {
            throw new SQLException("UUID 가 아닌 값", e);   // 조용히 null 로 넘기지 않는다
        }
    }
}
