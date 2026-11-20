package io.smarthappy.guard;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import io.smarthappy.support.PostgresTestBase;

/**
 * 가드(QA 작성, 2026-10-24 상위 지시): offset 페이지 문장의 정렬 키가 NOT NULL 칸이면 {@code nulls last} 를 붙이지 않는다
 * (docs/qa/2026-10-24_generic-plan.md N-1, 표 docs/qa/2026-10-24_sort-nulls-last.md).
 * <p>{@code desc nulls last} 는 기본 색인(오름차순·NULLS LAST)의 역순 스캔(= DESC NULLS FIRST)과 맞지 않아, 칸이 NOT NULL 이어도
 * 플래너가 색인 순서를 쓰지 못하고 <b>1쪽도 전 행을 정렬</b>한다(LOT 지연 조인 100만 행: 1쪽 0.4초 → 빼면 0.4ms).
 *
 * <p><b>판정</b> — 매퍼 XML 의 &lt;select&gt; 중 offset 이 있는 문장에서 {@code nulls last} 가 든 &lt;foreach&gt; 의
 * &lt;when&gt;·&lt;otherwise&gt; 정렬 식마다:
 * <ul>
 * <li>{@code 별칭.칸} 이고 별칭이 표 하나로 풀리며(파생 표·lateral 은 select 목록을 따라 한 단계씩 푼다) 그 칸이 DB 카탈로그에서
 * NOT NULL 이면 위반. left join 으로 붙은 표(또는 left join 한 파생 표)의 칸은 결과에서 NULL 이 될 수 있어 통과</li>
 * <li>계산식(coalesce·부질의·jsonb·집계 별칭)·별칭이 여러 표로 풀리는 것·union 파생 표(결과를 늘 정렬)는 판정하지 않는다(보고만)</li>
 * </ul>
 * <p><b>허용 목록</b>({@link #KNOWN}) — 지금 걸리는 문장과 그 정렬 식. 새 문장·새 식은 실패, 고쳐서 빠진 식도 "목록 갱신" 으로 실패.
 * {@code nulls first} 도 같은 판정(nulls first 포함).
 * 고치는 법: 그 키에만 nulls last 를 빼거나(빈 값 있는 키에만 남김), {@code desc} 와 {@code nulls last} 를 키별로 고른다(WorkCalMapper 꼴).
 */
class SortNullsLastGuardTest extends PostgresTestBase {

    static final Path MAPPERS = Path.of("src/main/resources/mapper");

    /** 문장 → 지금 걸리는 정렬 식(쉼표). 배정은 상위가 docs/qa 표를 보고 */
    static final Map<String, String> KNOWN = new LinkedHashMap<>();

    static {
    }

    @Autowired
    JdbcTemplate jdbc;

    enum Verdict { NOT_NULL, NULLABLE, UNKNOWN }

    record Key(String expr, Verdict verdict, String why) {
    }

    static final Pattern STMT = OffsetLedgerGuardTest.STMT;
    static final Pattern FRAG = OffsetLedgerGuardTest.FRAG;
    static final Pattern INCLUDE = OffsetLedgerGuardTest.INCLUDE;
    static final Pattern FOREACH = Pattern.compile("<foreach\\b[^>]*>(.*?)</foreach>", Pattern.DOTALL);
    static final Pattern BRANCH = Pattern.compile("<(when|otherwise)\\b[^>]*>(.*?)</\\1>", Pattern.DOTALL);
    /**
     * nulls first 포함 — asc nulls first 는 정방향 색인(NULLS LAST)과, desc nulls last 는 역방향과 어긋난다.
     * NOT NULL 키에는 어느 쪽이든 결과가 같아 붙일 이유가 없다
     */
    static final Pattern NULLS_LAST = Pattern.compile("nulls\\s+(?:first|last)", Pattern.CASE_INSENSITIVE);
    static final Pattern TABLE = Pattern.compile(
            "\\b(left\\s+(?:outer\\s+)?join|right\\s+(?:outer\\s+)?join|full\\s+(?:outer\\s+)?join|join|from)\\s+([a-z_][a-z0-9_]*)(?:\\s+(?:as\\s+)?([a-z_][a-z0-9_]*))?");
    static final Pattern COL = Pattern.compile("([a-z_][a-z0-9_]*)\\.([a-z_][a-z0-9_]*)");
    static final Set<String> WORDS = Set.of("on", "where", "join", "left", "right", "full", "inner", "outer", "cross", "lateral", "order",
            "group", "limit", "offset", "using", "and", "or", "union", "having", "window", "select", "from", "as", "natural");

    /** include 만 펼친 본문(동적 태그는 그대로) */
    static String expand(String body, Map<String, String> frags) {
        String b = body;
        for (int i = 0; i < 5; i++) {
            b = INCLUDE.matcher(b).replaceAll(m -> Matcher.quoteReplacement(frags.getOrDefault(m.group(1), "")));
        }
        return b;
    }

    /** 문장 하나 — nulls last foreach 의 정렬 식마다 판정. offset 이 없거나 해당 foreach 가 없으면 빈 목록 */
    static List<Key> judge(String body, Map<String, String> frags, Map<String, Boolean> notNull) {
        String raw = expand(body, frags);
        String flat = OffsetLedgerGuardTest.flatten(body, frags);
        if (!OffsetLedgerGuardTest.WORD_OFFSET.matcher(flat).find()) {
            return List.of();
        }
        Scope sc = new Scope(flat);
        List<Key> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Matcher fe = FOREACH.matcher(raw);
        while (fe.find()) {
            if (!NULLS_LAST.matcher(fe.group(1)).find()) {
                continue;
            }
            // nulls last 가 분기 밖(choose 뒤 — 모든 키에 붙음)이면 모든 분기, 분기 안에만 있으면 그 분기만 판정(키별로 고른 꼴)
            boolean outside = NULLS_LAST.matcher(BRANCH.matcher(fe.group(1)).replaceAll(" ")).find();
            Matcher br = BRANCH.matcher(fe.group(1));
            while (br.find()) {
                if (!outside && !NULLS_LAST.matcher(br.group(2)).find()) {
                    continue;
                }
                String expr = br.group(2).replaceAll("(?s)<if\\b[^>]*>.*?</if>", " ").replace("&gt;", ">").replace("&lt;", "<")
                        .replaceAll("\\s+", " ").strip().toLowerCase(Locale.ROOT)
                        .replaceAll("(\\s+(asc|desc))?\\s+nulls\\s+(first|last)$", "").strip();
                if (!expr.isEmpty() && seen.add(expr)) {
                    out.add(sc.resolve(expr, notNull));
                }
            }
        }
        return out;
    }

    /** 한 문장의 별칭 — 표 별칭(left join 여부)과 파생 표(안쪽 글자, left join 여부) */
    static final class Scope {
        final Map<String, Set<String>> tables = new HashMap<>();
        final Set<String> leftAliases = new HashSet<>();
        final Map<String, String> derived = new HashMap<>();
        final Set<String> leftDerived = new HashSet<>();

        Scope(String f) {
            Matcher t = TABLE.matcher(f);
            while (t.find()) {
                String table = t.group(2);
                if (WORDS.contains(table)) {
                    continue;
                }
                String alias = t.group(3) == null || WORDS.contains(t.group(3)) ? table : t.group(3);
                tables.computeIfAbsent(alias, k -> new TreeSet<>()).add(table);
                if (t.group(1).startsWith("left") || t.group(1).startsWith("full")) {
                    leftAliases.add(alias);
                }
            }
            for (int i = 0; i < f.length(); i++) {
                // 파생 표 = from/join/lateral 바로 뒤 괄호만(스칼라 부질의 뒤 글자를 별칭으로 읽지 않게 — 정렬 식이 이어 붙는 flatten 결과)
                if (f.charAt(i) != '(' || !f.startsWith("select", skip(f, i + 1))
                        || !f.substring(Math.max(0, i - 12), i).matches(".*\\b(from|join|lateral)\\s*$")) {
                    continue;
                }
                int c = OffsetLedgerGuardTest.close(f, i);
                Matcher a = Pattern.compile("\\s*(?:as\\s+)?([a-z_][a-z0-9_]*)").matcher(f);
                if (c + 1 < f.length() && a.region(c + 1, f.length()).lookingAt() && !WORDS.contains(a.group(1))) {
                    derived.put(a.group(1), f.substring(i + 1, c));
                    String before = f.substring(Math.max(0, i - 30), i);
                    if (before.matches(".*\\b(left|full)\\s+(outer\\s+)?join\\s+(lateral\\s+)?$")) {
                        leftDerived.add(a.group(1));
                    }
                }
            }
        }

        static int skip(String s, int i) {
            while (i < s.length() && s.charAt(i) == ' ') {
                i++;
            }
            return i;
        }

        Key resolve(String expr, Map<String, Boolean> notNull) {
            return resolve(expr, expr, notNull, false, 0);
        }

        private Key resolve(String shown, String expr, Map<String, Boolean> notNull, boolean nullable, int depth) {
            Matcher m = COL.matcher(expr);
            if (!m.matches()) {
                return new Key(shown, Verdict.UNKNOWN, expr.contains(" ") || expr.contains("(") ? "계산식" : "계산 열 별칭");
            }
            String alias = m.group(1);
            String col = m.group(2);
            if (derived.containsKey(alias) && depth < 4) {
                if (Pattern.compile("\\bunion\\b").matcher(derived.get(alias)).find()) {
                    return new Key(shown, Verdict.UNKNOWN, "파생 " + alias + " 이 union — 결과를 늘 정렬해 색인 순서와 무관");
                }
                String src = outputOf(derived.get(alias), col);
                if (src == null) {
                    return new Key(shown, Verdict.UNKNOWN, "파생 " + alias + " 의 " + col + " 을 못 찾음");
                }
                return resolve(shown, src, notNull, nullable || leftDerived.contains(alias), depth + 1);
            }
            Set<String> t = tables.get(alias);
            if (t == null) {
                return new Key(shown, Verdict.UNKNOWN, "별칭 " + alias + " 모름");
            }
            if (t.size() > 1) {
                return new Key(shown, Verdict.UNKNOWN, "별칭 " + alias + " 이 여러 표 " + t);
            }
            String table = t.iterator().next();
            Boolean nn = notNull.get(table + "." + col);
            if (nn == null) {
                return new Key(shown, Verdict.UNKNOWN, table + "." + col + " 카탈로그에 없음");
            }
            if (nullable || leftAliases.contains(alias)) {
                return new Key(shown, Verdict.NULLABLE, table + "." + col + " (left join)");
            }
            return new Key(shown, nn ? Verdict.NOT_NULL : Verdict.NULLABLE, table + "." + col);
        }

        /** 파생 표 안쪽 select 목록에서 col 을 내는 식 — "별칭.칸" 이나 "식 as col", 없으면 별칭.* 로 넘김 */
        static String outputOf(String inner, String col) {
            int[] d = OffsetLedgerGuardTest.depths(inner);
            Matcher sel = OffsetLedgerGuardTest.WORD_SELECT.matcher(inner);
            if (!sel.find()) {
                return null;
            }
            int from = OffsetLedgerGuardTest.fromOf(inner, d, sel.start(), d[sel.start()]);
            String list = inner.substring(sel.end(), from < 0 ? inner.length() : from);
            int base = d[sel.start()];
            List<String> items = new ArrayList<>();
            int start = 0;
            for (int i = 0; i < list.length(); i++) {
                if (list.charAt(i) == ',' && d[sel.end() + i] == base) {
                    items.add(list.substring(start, i).strip());
                    start = i + 1;
                }
            }
            items.add(list.substring(start).strip());
            String star = null;
            for (String it : items) {
                Matcher as = Pattern.compile("(?s)(.*?)\\s+as\\s+([a-z_][a-z0-9_]*)").matcher(it);
                if (as.matches()) {
                    if (as.group(2).equals(col)) {
                        return as.group(1).strip();
                    }
                    continue;
                }
                Matcher c = COL.matcher(it);
                if (c.matches() && c.group(2).equals(col)) {
                    return it;
                }
                Matcher s = Pattern.compile("([a-z_][a-z0-9_]*)\\.\\*").matcher(it);
                if (s.matches()) {
                    star = s.group(1);
                }
            }
            return star == null ? null : star + "." + col;
        }
    }

    /** 매퍼 전체 — 문장 → 정렬 식 판정(nulls last foreach 가 있는 offset 문장만) */
    static Map<String, List<Key>> scan(Path root, Map<String, Boolean> notNull) throws IOException {
        Map<String, List<Key>> out = new TreeMap<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".xml")).sorted().toList()) {
                String xml = Files.readString(f, StandardCharsets.UTF_8).replaceAll("(?s)<!--.*?-->", "");
                Map<String, String> frags = new HashMap<>();
                Matcher fm = FRAG.matcher(xml);
                while (fm.find()) {
                    frags.put(fm.group(1), fm.group(2));
                }
                Matcher m = STMT.matcher(xml);
                while (m.find()) {
                    List<Key> keys = judge(m.group(2), frags, notNull);
                    if (!keys.isEmpty()) {
                        out.put(root.relativize(f).toString().replace('\\', '/') + "#" + m.group(1), keys);
                    }
                }
            }
        }
        return out;
    }

    Map<String, Boolean> catalog() {
        Map<String, Boolean> nn = new HashMap<>();
        jdbc.query("""
                select c.relname, a.attname, a.attnotnull from pg_attribute a join pg_class c on c.oid = a.attrelid
                  join pg_namespace n on n.oid = c.relnamespace
                 where n.nspname = 'public' and c.relkind in ('r', 'p', 'v') and a.attnum > 0 and not a.attisdropped""",
                rs -> {
                    nn.put(rs.getString(1) + "." + rs.getString(2), rs.getBoolean(3));
                });
        return nn;
    }

    // ------------------------------------------------------------------ 시험

    @Test
    void offset_문장의_NOT_NULL_정렬_키에는_nulls_last_를_붙이지_않는다() throws IOException {
        Map<String, List<Key>> all = scan(MAPPERS, catalog());
        Map<String, String> now = new TreeMap<>();
        StringBuilder report = new StringBuilder("\n[nulls last] 문장 | 정렬 식 | 판정 | 근거\n");
        all.forEach((stmt, keys) -> {
            List<String> bad = new ArrayList<>();
            for (Key k : keys) {
                report.append(stmt).append(" | ").append(k.expr()).append(" | ").append(k.verdict()).append(" | ").append(k.why()).append('\n');
                if (k.verdict() == Verdict.NOT_NULL) {
                    bad.add(k.expr());
                }
            }
            if (!bad.isEmpty()) {
                now.put(stmt, String.join(", ", bad));
            }
        });
        System.out.println(report);
        List<String> fresh = new ArrayList<>();
        now.forEach((stmt, exprs) -> {
            if (!exprs.equals(KNOWN.get(stmt))) {
                fresh.add(stmt + " → " + exprs + (KNOWN.containsKey(stmt) ? "  (목록: " + KNOWN.get(stmt) + ")" : ""));
            }
        });
        assertThat(fresh).as("NOT NULL 정렬 키에 nulls last — 그 키에서 빼거나(빈 값 있는 키에만), 목록과 다르면 KNOWN 을 지금 상태로").isEmpty();
        List<String> stale = KNOWN.keySet().stream().filter(k -> !now.containsKey(k)).toList();
        assertThat(stale).as("고쳐져서 더 이상 걸리지 않는 문장 — KNOWN 에서 빼세요").isEmpty();
    }

    @Test
    void 검사기_자체_시험(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir.resolve("m"));
        Files.writeString(dir.resolve("m/T.xml"), """
                <mapper namespace="x">
                  <sql id="sort"><foreach collection="page.sorts" item="s"><choose>
                      <when test="s.key == 'A'">t.a_nn</when><when test="s.key == 'B'">u.b_nn</when>
                      <when test="s.key == 'C'">coalesce(t.c, t.a_nn)</when><when test="s.key == 'E'">(select max(z.v) from t3 z where z.id = t.id)</when>
                    <otherwise>t.d_null</otherwise>
                    </choose><if test="s.desc"> desc</if> nulls last,</foreach></sql>
                  <select id="plain">select t.id from t1 t left join t2 u on u.id = t.id order by <include refid="sort"/> t.id
                    limit #{size} offset #{offset}</select>
                  <select id="derived">select x.* from (select t.a_nn, u.b_nn as b_nn, t.d_null from t1 t join t2 u on u.id = t.id) x
                    order by <foreach collection="page.sorts" item="s"><choose><when test="s.key == 'A'">x.a_nn</when>
                    <when test="s.key == 'B'">x.b_nn</when><otherwise>x.d_null</otherwise></choose><if test="s.desc"> desc</if> nulls last,</foreach>
                    x.id limit #{size} offset #{offset}</select>
                  <select id="perKey">select t.id from t1 t order by <foreach collection="page.sorts" item="o"><choose>
                    <when test="o.key == 'A'">t.a_nn<if test="o.desc"> desc nulls last</if></when></choose>,</foreach> t.id
                    limit 10 offset 0</select>
                  <select id="mixed">select t.id from t1 t order by <foreach collection="page.sorts" item="o"><choose>
                    <when test="o.key == 'A'">t.a_nn<if test="o.desc"> desc</if></when>
                    <otherwise>t.d_null<if test="o.desc"> desc</if> nulls last</otherwise></choose>,</foreach> t.id
                    limit 10 offset 0</select>
                  <select id="outsideIf">select t.id from t1 t order by <foreach collection="page.sorts" item="o"><choose><when test="o.key == 'A'">t.a_nn</when><otherwise>t.d_null</otherwise></choose><if test="o.desc"> desc nulls last</if>,</foreach> t.id limit 10 offset 0</select>
                  <select id="inlineFixed">select t.id from t1 t order by <foreach collection="page.sorts" item="o"><choose><when test="o.key == 'A'">t.a_nn desc nulls last</when><when test="o.key == 'B'">t.c</when></choose>,</foreach> t.id limit 10 offset 0</select>
                  <select id="ascFirst">select t.id from t1 t order by <foreach collection="page.sorts" item="o"><choose><when test="o.key == 'A'">t.a_nn<if test="!o.desc"> nulls first</if></when><otherwise>t.d_null<if test="o.desc"> desc nulls last</if><if test="!o.desc"> nulls first</if></otherwise></choose>,</foreach> t.id limit 10 offset 0</select>
                  <select id="noOffset">select t.id from t1 t order by <include refid="sort"/> t.id</select>
                </mapper>""", StandardCharsets.UTF_8);
        Map<String, Boolean> nn = Map.of("t1.a_nn", true, "t1.c", false, "t1.d_null", false, "t2.b_nn", true);
        Map<String, List<Key>> r = scan(dir, nn);
        assertThat(r).containsOnlyKeys("m/T.xml#plain", "m/T.xml#derived", "m/T.xml#perKey", "m/T.xml#mixed",
                "m/T.xml#outsideIf", "m/T.xml#inlineFixed", "m/T.xml#ascFirst");
        assertThat(r.get("m/T.xml#plain")).extracting(Key::expr, Key::verdict).containsExactly(
                org.assertj.core.groups.Tuple.tuple("t.a_nn", Verdict.NOT_NULL),
                org.assertj.core.groups.Tuple.tuple("u.b_nn", Verdict.NULLABLE),          // left join
                org.assertj.core.groups.Tuple.tuple("coalesce(t.c, t.a_nn)", Verdict.UNKNOWN),
                org.assertj.core.groups.Tuple.tuple("(select max(z.v) from t3 z where z.id = t.id)", Verdict.UNKNOWN),
                org.assertj.core.groups.Tuple.tuple("t.d_null", Verdict.NULLABLE));   // 스칼라 부질의 뒤 t 를 파생 별칭으로 읽지 않음
        assertThat(r.get("m/T.xml#derived")).extracting(Key::verdict).containsExactly(Verdict.NOT_NULL, Verdict.NOT_NULL, Verdict.NULLABLE);
        assertThat(r.get("m/T.xml#perKey")).extracting(Key::verdict).containsExactly(Verdict.NOT_NULL);
        // 키별로 고른 꼴: nulls last 가 빈 값 있는 분기에만 — NOT NULL 분기는 판정하지 않는다
        assertThat(r.get("m/T.xml#mixed")).extracting(Key::expr, Key::verdict).containsExactly(
                org.assertj.core.groups.Tuple.tuple("t.d_null", Verdict.NULLABLE));
        // nulls last 가 분기 밖 <if> 안 — 모든 키에 붙으므로 모든 분기
        assertThat(r.get("m/T.xml#outsideIf")).extracting(Key::expr, Key::verdict).containsExactly(
                org.assertj.core.groups.Tuple.tuple("t.a_nn", Verdict.NOT_NULL),
                org.assertj.core.groups.Tuple.tuple("t.d_null", Verdict.NULLABLE));
        // 분기 안에 desc nulls last 를 그대로 적은 꼴 — 그 분기만, 식 끝의 방향·nulls 는 떼고 판정
        assertThat(r.get("m/T.xml#inlineFixed")).extracting(Key::expr, Key::verdict).containsExactly(
                org.assertj.core.groups.Tuple.tuple("t.a_nn", Verdict.NOT_NULL));
        // asc nulls first — NOT NULL 키면 위반
        assertThat(r.get("m/T.xml#ascFirst")).extracting(Key::expr, Key::verdict).containsExactly(
                org.assertj.core.groups.Tuple.tuple("t.a_nn", Verdict.NOT_NULL),
                org.assertj.core.groups.Tuple.tuple("t.d_null", Verdict.NULLABLE));
    }
}
