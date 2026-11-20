package io.smarthappy.guard;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 가드: 협력사 포털 매퍼의 모든 문장은 #{custId} 로 협력사를 거른다 (docs/42 4장).
 * 회사 격리는 RLS, 같은 회사의 다른 협력사 격리는 이 조건뿐이다 — 빠뜨리면 A 협력사가 B 협력사 발주를 본다.
 * 범위: 매퍼 XML {@code mapper/**}{@code /portal/**}, 모듈 자바 {@code module/**}{@code /portal} 의 주석 매퍼(@Select·@Insert·@Update·@Delete).
 * 규칙: #{custId} 가 **동적 분기 밖**에 있어야 한다 — {@code <if>}·{@code <choose>}·{@code <when>}·{@code <otherwise>} 안에만 있으면
 * 그 분기를 타지 않을 때 협력사 조건 없이 돈다(QA #449 PG-2). 같은 파일의 {@code <sql>} 조각은 {@code <include>} 자리에 펼쳐 본다.
 * 속성 따옴표는 둘 다(id="…"·id='…', PG-1). custId 값은 서비스가 PortalUser.current() 로만 얻는다(리뷰·이상 사용 시험 몫).
 * 한계: 조건이 where 에 실제로 걸렸는지(select 목록에만 썼는지)는 보지 않는다 — 이상 사용 시험 "A 협력사로 B 협력사"가 본다.
 */
class PortalMapperGuardTest {

    private static final Path MAPPERS = Path.of("src/main/resources/mapper");
    private static final Path MODULES = Path.of("src/main/java/io/smarthappy/module");
    private static final Pattern STATEMENT = Pattern.compile(
            "<(select|insert|update|delete)\\b[^>]*?\\bid\\s*=\\s*([\"'])([^\"']+)\\2[^>]*>(.*?)</\\1\\s*>", Pattern.DOTALL);
    private static final Pattern SQL_FRAGMENT = Pattern.compile(
            "<sql\\b[^>]*?\\bid\\s*=\\s*([\"'])([^\"']+)\\1[^>]*>(.*?)</sql\\s*>", Pattern.DOTALL);
    private static final Pattern INCLUDE = Pattern.compile(
            "<include\\b[^>]*?\\brefid\\s*=\\s*([\"'])([^\"']+)\\1[^>]*?(?:/>|>.*?</include\\s*>)", Pattern.DOTALL);
    /** 안쪽부터 지운다(중첩 분기) — 닫는 태그까지 안에 같은 이름의 여는 태그가 없는 가장 안쪽 블록 */
    private static final Pattern DYNAMIC = Pattern.compile(
            "<(if|choose|when|otherwise)\\b[^>]*>(?:(?!<\\1\\b).)*?</\\1\\s*>", Pattern.DOTALL);
    private static final Pattern ANNOTATED = Pattern.compile(
            "@(?:[\\w.]+\\.)?(Select|Insert|Update|Delete)\\s*\\(\\s*(\"\"\"[\\s\\S]*?\"\"\"|\"(?:[^\"\\\\]|\\\\.)*\"(?:\\s*\\+\\s*\"(?:[^\"\\\\]|\\\\.)*\")*)"
                    + "\\s*\\)[^;{]*?\\b(\\w+)\\s*\\(");
    private static final Pattern CUST_PARAM = Pattern.compile("#\\{\\s*(?:\\w+\\.)?custId\\b");

    @Test
    void 포털_매퍼의_모든_문장이_분기_밖에서_custId_로_거른다() throws IOException {
        List<String> hits = new ArrayList<>();
        for (Path root : portalDirs(MAPPERS)) {
            violations(root).forEach(v -> hits.add(MAPPERS.relativize(root) + "/" + v));
        }
        for (Path root : portalDirs(MODULES)) {
            violations(root).forEach(v -> hits.add(MODULES.relativize(root) + "/" + v));
        }
        assertThat(hits).as("#{custId} 가 분기 밖에 없는 포털 매퍼 문장").isEmpty();
    }

    @Test
    void 포털_매퍼가_실제로_검사된다() throws IOException {
        assertThat(portalDirs(MAPPERS)).as("포털 매퍼 폴더를 못 찾으면 가드가 빈 집합을 본다").isNotEmpty();
    }

    @Test
    void 가드_자체_시험_빠뜨린_문장을_잡는다(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("PortalPoMapper.xml"), """
                <mapper namespace="x">
                    <sql id='own'>and p.cust_id = #{custId}</sql>
                    <select id="good" resultType="map">select * from srm_po where cust_id = #{custId} and po_id = #{poId}</select>
                    <select id="goodNested" resultType="map">select * from srm_po where cust_id = #{q.custId}</select>
                    <select id='goodInclude' resultType='map'>select * from srm_po p where p.po_id = #{poId} <include refid="own"/></select>
                    <select id="bad" resultType="map">select * from srm_po where po_id = #{poId}</select>
                    <select id='badSingle' resultType='map'>select * from srm_po where po_id = #{poId}</select>
                    <select id="badIf" resultType="map">select * from srm_po where po_id = #{poId}
                        <if test="custId != null">and cust_id = #{custId}</if></select>
                    <select id="badChoose" resultType="map">select * from srm_po <where><choose>
                        <when test="a"><if test="b">x = 1</if> and cust_id = #{custId}</when><otherwise>1 = 1</otherwise></choose></where></select>
                    <update id="badUpdate">update srm_po set remark = #{remark} where po_id = #{poId} -- custIdX
                    </update>
                </mapper>""", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("PortalPoMapper.java"), """
                interface PortalPoMapper {
                    @Select("select * from srm_po where cust_id = #{custId}")
                    Object good(long custId);
                    @Select(\"\"\"
                            select * from srm_po where po_id = #{poId}
                            \"\"\")
                    java.util.Map<String, Object> badBlock(long poId);
                    @Update("update srm_po set x = 1 " + "where po_id = #{poId}")
                    int badConcat(long poId);
                }
                """, StandardCharsets.UTF_8);
        assertThat(violations(dir)).containsExactlyInAnyOrder("PortalPoMapper.xml#bad", "PortalPoMapper.xml#badSingle",
                "PortalPoMapper.xml#badIf", "PortalPoMapper.xml#badChoose", "PortalPoMapper.xml#badUpdate",
                "PortalPoMapper.java#badBlock", "PortalPoMapper.java#badConcat");
    }

    /** base 아래 이름이 portal 인 폴더들 (mapper/**}/portal, module/**}/portal) */
    static List<Path> portalDirs(Path base) throws IOException {
        if (!Files.isDirectory(base)) {
            return List.of();
        }
        try (Stream<Path> s = Files.walk(base)) {
            return s.filter(Files::isDirectory).filter(p -> p.getFileName().toString().equals("portal")).sorted().toList();
        }
    }

    /** root 아래 .xml 매퍼 문장·.java 주석 매퍼 중 #{custId} 가 분기 밖에 없는 것 — "상대경로#문장id" */
    static List<String> violations(Path root) throws IOException {
        List<String> out = new ArrayList<>();
        if (!Files.isDirectory(root)) {
            return out;
        }
        try (Stream<Path> files = Files.walk(root)) {
            for (Path f : files.filter(Files::isRegularFile).sorted().toList()) {
                String name = root.relativize(f).toString().replace('\\', '/');
                String text = Files.readString(f, StandardCharsets.UTF_8);
                if (name.endsWith(".xml")) {
                    xml(text.replaceAll("(?s)<!--.*?-->", ""), name, out);
                } else if (name.endsWith(".java")) {
                    java(text, name, out);
                }
            }
        }
        return out;
    }

    private static void xml(String xml, String name, List<String> out) {
        Map<String, String> fragments = new HashMap<>();
        Matcher sql = SQL_FRAGMENT.matcher(xml);
        while (sql.find()) {
            fragments.put(sql.group(2), sql.group(3));
        }
        Matcher m = STATEMENT.matcher(xml);
        while (m.find()) {
            if (!filters(expand(m.group(4), fragments, 0))) {
                out.add(name + "#" + m.group(3));
            }
        }
    }

    private static void java(String src, String name, List<String> out) {
        Matcher m = ANNOTATED.matcher(src);
        while (m.find()) {
            if (!filters(m.group(2))) {
                out.add(name + "#" + m.group(3));
            }
        }
    }

    /** 같은 파일의 sql 조각을 include 자리에 펼친다(조각 안의 include 도, 깊이 제한) */
    private static String expand(String body, Map<String, String> fragments, int depth) {
        if (depth > 5) {
            return body;
        }
        Matcher inc = INCLUDE.matcher(body);
        StringBuilder sb = new StringBuilder();
        while (inc.find()) {
            String frag = fragments.getOrDefault(inc.group(2), "");
            inc.appendReplacement(sb, Matcher.quoteReplacement(expand(frag, fragments, depth + 1)));
        }
        inc.appendTail(sb);
        return sb.toString();
    }

    /** 동적 분기를 안쪽부터 지운 뒤에도 #{custId} 가 남는가 */
    static boolean filters(String body) {
        String s = body;
        String prev;
        do {
            prev = s;
            s = DYNAMIC.matcher(s).replaceAll(" ");
        } while (!s.equals(prev));
        return CUST_PARAM.matcher(s).find();
    }
}
