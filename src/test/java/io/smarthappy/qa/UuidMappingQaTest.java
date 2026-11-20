package io.smarthappy.qa;

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
 * QA — MyBatis 의 uuid 칸 처리(상위 요청, PLM 발견: "UUID 형 처리기가 없어 자바 UUID 필드로 받으면 오류 없이 null"). 이 앱 설정
 * (Configuration)에 시험 문장을 더해 읽기·쓰기 경로를 직접 본다. 판정·표는 docs/qa/2026-10-24_uuid-mapping.md.
 * <p>2026-10-24 플랫폼이 UUID TypeHandler 를 전역 등록했다(`UuidTypeHandler`, 상위 결정) — "조용히 null" 기대를 뒤집어
 * 회귀 시험으로 둔다: UUID 필드에 값이 들고, 쓰기·map 은 예전 그대로.
 */
class UuidMappingQaTest extends PostgresTestBase {

    static final UUID U = UUID.fromString("0f8fad5b-d9cb-469f-a165-70867728950e");
    static final String NS = "qa.uuidmapping";

    @Autowired
    SqlSessionFactory factory;

    @Autowired
    SqlSessionTemplate sql;

    /** UUID 필드 DTO(읽기) — 매퍼 resultType 꼴 */
    public static class Row {
        private UUID id;
        private String txt;

        public UUID getId() {
            return id;
        }

        public void setId(UUID id) {
            this.id = id;
        }

        public String getTxt() {
            return txt;
        }

        public void setTxt(String txt) {
            this.txt = txt;
        }
    }

    /** UUID getter 를 가진 파라미터(쓰기 — InvMapper insertTrx 의 #{t.moveGrpId} 꼴) */
    public record Param(UUID u) {
    }

    @BeforeEach
    void addStatements() {
        Configuration c = factory.getConfiguration();
        if (c.hasStatement(NS + ".readBean")) {
            return;
        }
        String xml = """
                <?xml version="1.0" encoding="UTF-8" ?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "https://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="%s">
                  <select id="readBean" resultType="io.smarthappy.qa.UuidMappingQaTest$Row">
                    select '%s'::uuid as id, '%s'::uuid::text as txt
                  </select>
                  <select id="readMap" resultType="map">select '%s'::uuid as id</select>
                  <select id="writeMap" resultType="string">select pg_typeof(#{u})::text || ' ' || (#{u})::text</select>
                  <select id="writeBean" resultType="string">select pg_typeof(#{p.u})::text || ' ' || (#{p.u})::text</select>
                  <select id="writeUuidCol" resultType="boolean">select #{u} = '%s'::uuid</select>
                </mapper>""".formatted(NS, U, U, U, U);
        new XMLMapperBuilder(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), c, "qa-uuid-mapping.xml",
                c.getSqlFragments()).parse();
    }

    @Test
    void 설정에_UUID_TypeHandler_가_있다() {
        assertThat(factory.getConfiguration().getTypeHandlerRegistry().hasTypeHandler(UUID.class)).isTrue();
    }

    /** 회귀: uuid 칸을 UUID 필드로 받으면 값이 든다(예전 함정 — 처리기가 없어 오류 없이 null). ::text → String 도 그대로 */
    @Test
    void 읽기_UUID_필드에_값이_든다_text_도_된다() {
        Row r = sql.selectOne(NS + ".readBean");
        assertThat(r.getId()).as("UUID 필드 — 전역 처리기").isEqualTo(U);
        assertThat(r.getTxt()).isEqualTo(U.toString());
    }

    /** resultType=map 은 드라이버가 준 java.util.UUID 를 그대로 담는다 */
    @Test
    void 읽기_map_은_UUID_객체() {
        Map<String, Object> m = sql.selectOne(NS + ".readMap");
        assertThat(m.get("id")).isEqualTo(U);
    }

    /** 쓰기: @Param(맵) 으로 넘긴 UUID — ObjectTypeHandler → setObject 로 uuid 형 그대로 간다(PlmPpap insertPcnMail·Label insertJob·Inv findMoveGroup 꼴) */
    @Test
    void 쓰기_맵_파라미터_UUID_는_uuid_로_간다() {
        String s = sql.selectOne(NS + ".writeMap", Map.of("u", U));
        assertThat(s).isEqualTo("uuid " + U);
        Boolean eq = sql.selectOne(NS + ".writeUuidCol", Map.of("u", U));
        assertThat(eq).isTrue();
    }

    /** 쓰기: 객체 getter 가 UUID(InvMapper insertTrx 의 #{t.moveGrpId}) — 이것도 된다 */
    @Test
    void 쓰기_객체_getter_UUID_도_uuid_로_간다() {
        Throwable t = catchThrowable(() -> sql.selectOne(NS + ".writeBean", Map.of("p", new Param(U))));
        assertThat(t).isNull();
        String s = sql.selectOne(NS + ".writeBean", Map.of("p", new Param(U)));
        assertThat(s).isEqualTo("uuid " + U);
    }
}
