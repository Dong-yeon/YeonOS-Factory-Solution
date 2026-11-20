package io.smarthappy.platform.common.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;

import io.smarthappy.support.PostgresTestBase;

/**
 * 전역 UUID 처리기 경계 (UuidTypeHandler) — null 칸은 null, 글자(::text)도 UUID 로, UUID 가 아닌 글자는 조용히 null 이 아니라 오류,
 * null 파라미터는 그대로 null. 기본 경로(uuid 칸 → UUID 필드·쓰기)는 qa/UuidMappingQaTest 가 고정한다
 */
class UuidTypeHandlerTest extends PostgresTestBase {

    static final UUID U = UUID.fromString("6f1c2b0e-3a4d-4e5f-8a9b-0c1d2e3f4a5b");
    static final String NS = "platform.uuidhandler";

    @Autowired
    SqlSessionFactory factory;

    @Autowired
    SqlSessionTemplate sql;

    public static class Row {
        private UUID id;

        public UUID getId() {
            return id;
        }

        public void setId(UUID id) {
            this.id = id;
        }
    }

    @BeforeEach
    void addStatements() {
        Configuration c = factory.getConfiguration();
        if (c.hasStatement(NS + ".nullCol")) {
            return;
        }
        String xml = """
                <?xml version="1.0" encoding="UTF-8" ?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "https://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="%s">
                  <select id="nullCol" resultType="io.smarthappy.platform.common.db.UuidTypeHandlerTest$Row">select null::uuid as id</select>
                  <select id="textCol" resultType="io.smarthappy.platform.common.db.UuidTypeHandlerTest$Row">select '%s'::text as id</select>
                  <select id="badText" resultType="io.smarthappy.platform.common.db.UuidTypeHandlerTest$Row">select 'not-a-uuid'::text as id</select>
                  <select id="nullParam" resultType="boolean">select #{u}::uuid is null</select>
                </mapper>""".formatted(NS, U);
        new XMLMapperBuilder(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), c, "platform-uuid-handler.xml",
                c.getSqlFragments()).parse();
    }

    @Test
    void null_칸은_null() {
        Row r = sql.selectOne(NS + ".nullCol");
        assertThat(r == null ? null : r.getId()).isNull();
    }

    @Test
    void 글자_칸도_UUID_로() {
        Row r = sql.selectOne(NS + ".textCol");
        assertThat(r.getId()).isEqualTo(U);
    }

    @Test
    void UUID_가_아닌_글자는_조용히_null_이_아니라_오류() {
        assertThat(catchThrowable(() -> sql.selectOne(NS + ".badText"))).isNotNull();
    }

    @Test
    void null_파라미터는_null_로() {
        java.util.HashMap<String, Object> p = new java.util.HashMap<>();
        p.put("u", null);
        Boolean isNull = sql.selectOne(NS + ".nullParam", p);
        assertThat(isNull).isTrue();
        assertThat((Boolean) sql.selectOne(NS + ".nullParam", Map.of("u", U))).isFalse();
    }
}
