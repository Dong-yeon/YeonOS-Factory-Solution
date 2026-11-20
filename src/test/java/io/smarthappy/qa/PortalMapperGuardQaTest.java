package io.smarthappy.qa;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * QA — 포털 매퍼 가드(guard/PortalMapperGuardTest, docs/42 4장 "모든 문장 #{custId}")가 놓치는 모양. 가드의 검사기(violations)를
 * 그대로 불러 임시 매퍼에 돌린다(가드는 상위 소유라 QA 는 고치지 않는다). 지금 포털 매퍼(PortalSrmMapper.xml)에는 이 모양이 없다 —
 * 앞으로 들어올 문장을 가드가 막는지의 문제다.
 */
class PortalMapperGuardQaTest {

    @SuppressWarnings("unchecked")
    static List<String> violations(Path root) throws Exception {
        Method m = Class.forName("io.smarthappy.guard.PortalMapperGuardTest").getDeclaredMethod("violations", Path.class);
        m.setAccessible(true);
        return (List<String>) m.invoke(null, root);
    }

    static void mapper(Path dir, String body) throws Exception {
        Files.writeString(dir.resolve("PortalQaMapper.xml"), "<mapper namespace=\"x\">\n" + body + "\n</mapper>", StandardCharsets.UTF_8);
    }

    @Test
    void 가드는_큰따옴표_id_의_빠진_문장을_잡는다(@TempDir Path dir) throws Exception {
        mapper(dir, "<select id=\"bad\" resultType=\"map\">select * from srm_po where po_id = #{poId}</select>");
        assertThat(violations(dir)).containsExactly("PortalQaMapper.xml#bad");
    }

    /** XML 은 작은따옴표 속성도 된다(MyBatis 가 그대로 읽는다) — 가드의 문장 정규식이 id="…" 만 보아 문장째 건너뛴다 */
    @Test
    void 결함PG1_작은따옴표_id_문장도_잡는다(@TempDir Path dir) throws Exception {
        mapper(dir, "<select id='bad' resultType='map'>select * from srm_po where po_id = #{poId}</select>");
        assertThat(violations(dir)).containsExactly("PortalQaMapper.xml#bad");
    }

    /** 조건이 동적 분기 안에만 있으면 그 분기를 타지 않을 때 협력사 조건 없이 돈다(custId 가 null 이거나 test 가 거짓) */
    @Test
    void 결함PG2_if_안에만_있는_custId_는_거른_것이_아니다(@TempDir Path dir) throws Exception {
        mapper(dir, "<select id=\"optional\" resultType=\"map\">select * from srm_po where po_id = #{poId}"
                + "<if test=\"custId != null\"> and cust_id = #{custId}</if></select>");
        assertThat(violations(dir)).containsExactly("PortalQaMapper.xml#optional");
    }

    /** 가드 주석은 "자바 패키지 module.srm.portal" 도 대상이라 하지만 검사기는 .xml 만 읽는다 — 주석 매퍼(@Select)는 보지 않는다 */
    @Test
    void 결함PG3_주석_매퍼도_잡는다(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("PortalQaMapper.java"), """
                package io.smarthappy.module.srm.portal;
                interface PortalQaMapper {
                    @org.apache.ibatis.annotations.Select("select * from srm_po where po_id = #{poId}")
                    java.util.Map<String, Object> bad(long poId);
                }
                """, StandardCharsets.UTF_8);
        assertThat(violations(dir)).containsExactly("PortalQaMapper.java#bad");
    }
}
