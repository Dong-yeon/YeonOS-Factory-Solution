package io.smarthappy.guard;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * 가드: jOOQ 사용 규칙 (docs/43, 결정 #52) — MyBatis XML 의 {@code ${}} 금지에 해당하는 jOOQ 규칙.
 * - jOOQ(org.jooq·생성물 패키지)는 시범 모듈 module.apqp 안에서만 import 한다
 * - plain SQL(문자열을 SQL 로 그대로 넣는 API: DSL.sql·field("…")·table("…")·condition("…")·where("…")·fetch("…")·raw …) 금지.
 *   값은 바인드 변수(DSL.val·생성된 표·칸)로만. 예외는 {@link #ALLOWED} 에 파일·근거와 함께
 * 한계: 문자열 리터럴만 본다 — 변수에 담은 SQL 문자열은 리뷰가 막는다(docs/43 "가드" 절)
 */
class JooqUsageGuardTest {

    private static final Path MAIN = Path.of("src/main/java");
    /** jOOQ 를 써도 되는 곳 */
    private static final String ALLOWED_DIR = "io/smarthappy/module/apqp/".replace('/', java.io.File.separatorChar);
    private static final Pattern JOOQ_IMPORT = Pattern.compile("^import\\s+(static\\s+)?(org\\.jooq|io\\.smarthappy\\.module\\.apqp\\.jooq)\\b");
    /**
     * plain SQL — 첫 인자가 문자열 리터럴인 호출. 정적 import(sql("…"))와 DSL.·dsl. 호출 모두.
     * jOOQ 의 plain SQL API 는 @PlainSQL 이 붙은 String 첫 인자 오버로드다(org.jooq.PlainSQL 문서)
     */
    static final Pattern PLAIN_SQL = Pattern.compile(
            "(?:^|[^\\w])(?:sql|field|table|condition|query|resultQuery|raw|keyword|unquotedName|"
                    + "fetch|fetchOne|fetchSingle|fetchOptional|fetchValue|fetchLazy|fetchMany|fetchStream|fetchExists|fetchCount|"
                    + "execute|batch|where|and|or|andNot|orNot|having|on|orderBy|groupBy|join|from|select|insertInto|update|deleteFrom|"
                    + "using|with|values|set)\\s*\\(\\s*\"");
    /** 파일(MAIN 기준 상대 경로) → 근거. 비어 있는 것이 정상 */
    private static final Map<String, String> ALLOWED = Map.of();

    private static List<Path> sources() throws IOException {
        try (Stream<Path> files = Files.walk(MAIN)) {
            return files.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }

    private static boolean code(String line) {
        String t = line.trim();
        return !(t.startsWith("//") || t.startsWith("*") || t.startsWith("/*"));
    }

    @Test
    void jOOQ_는_module_apqp_안에서만_import() throws IOException {
        List<String> hits = new ArrayList<>();
        for (Path f : sources()) {
            if (MAIN.relativize(f).toString().startsWith(ALLOWED_DIR)) {
                continue;
            }
            List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                if (JOOQ_IMPORT.matcher(lines.get(i).trim()).find()) {
                    hits.add(f + ":" + (i + 1) + "  " + lines.get(i).trim());
                }
            }
        }
        assertThat(hits).as("jOOQ 시범은 module.apqp 만 — 다른 모듈은 MyBatis(docs/43)").isEmpty();
    }

    @Test
    void jOOQ_를_쓰는_파일에_plain_SQL_이_없다() throws IOException {
        List<String> hits = new ArrayList<>();
        for (Path f : sources()) {
            List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);
            if (lines.stream().noneMatch(l -> JOOQ_IMPORT.matcher(l.trim()).find())
                    || ALLOWED.containsKey(MAIN.relativize(f).toString().replace(java.io.File.separatorChar, '/'))) {
                continue;
            }
            for (int i = 0; i < lines.size(); i++) {
                if (code(lines.get(i)) && PLAIN_SQL.matcher(lines.get(i)).find()) {
                    hits.add(f + ":" + (i + 1) + "  " + lines.get(i).trim());
                }
            }
        }
        assertThat(hits).as("jOOQ plain SQL 금지 — 생성된 표·칸과 DSL.val 로(docs/43)").isEmpty();
    }

    @Test
    void 검사기가_실제로_잡는다() {
        for (String bad : List.of("dsl.fetch(\"select * from mst_item\")", "DSL.field(\"item_cd\")", "DSL.condition(\"1 = 1\")",
                ".where(\"comp_cd = '\" + c + \"'\")", "dsl.execute(\"delete from x\")", "sql(\"now()\")", "DSL.table(\"mst_item\")",
                "dsl.resultQuery(\"select 1\")", "DSL.raw(\"x\")", ".orderBy(\"item_cd\")", "dsl.select(DSL.field(\"x\"))",
                ".join(\"mst_cust c\")", "dsl.fetchValue(\"select 1\")")) {
            assertThat(PLAIN_SQL.matcher(bad).find()).as(bad).isTrue();
        }
        for (String ok : List.of("dsl.select(MST_ITEM.ITEM_CD).from(MST_ITEM)", ".where(MST_ITEM.ITEM_CD.eq(code))",
                "DSL.val(\"ZT\")", "MST_ITEM.ITEM_CD.like(\"ZT%\")", "dsl.fetchCount(MST_ITEM)", "log.info(\"select 를 부른다\")",
                "throw new BusinessException(\"x\")")) {
            assertThat(PLAIN_SQL.matcher(ok).find()).as(ok).isFalse();
        }
        assertThat(JOOQ_IMPORT.matcher("import org.jooq.DSLContext;").find()).isTrue();
        assertThat(JOOQ_IMPORT.matcher("import static io.smarthappy.module.apqp.jooq.Tables.MST_ITEM;").find()).isTrue();
        assertThat(JOOQ_IMPORT.matcher("import org.springframework.jdbc.core.JdbcTemplate;").find()).isFalse();
    }
}
