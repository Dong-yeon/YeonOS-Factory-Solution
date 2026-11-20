package io.smarthappy.guard;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 가드(QA 작성, 2026-10-24 상위 지시로 guard/): 원장처럼 커지는 표의 offset 페이지 문장은 건너뛰는 행마다 계산하지 않는다
 * (docs/qa/2026-10-24_offset-deep-page.md, WMS 수불 원장 100만 행 offset 50만 시간 초과 — docs/30 5.1 지연 조인).
 *
 * <p><b>판정</b> — 매퍼 XML 의 &lt;select&gt; 중 offset 이 있는 문장에서, offset 이 걸린 쿼리 층(include 펼침·동적 태그 제거)의
 * <ul>
 * <li>select 목록에 상관 부질의 {@code (select}, 업무 함수 {@code hb_/wms_/…(}, 집계 {@code string_agg} 등, jsonb {@code ->>} 가 있거나</li>
 * <li>from ~ offset 사이에 {@code lateral} 이 있으면 "행별 계산" 이다.</li>
 * </ul>
 * 행별 계산이 있고 ① 주 표(from 첫 표, 파생 표면 그 안의 첫 표)가 {@link #LEDGER} 이거나 ② 행별 계산 글자 안에 원장 표·원장을 읽는 함수
 * ({@link #LEDGER_FUNCS})가 나오면 위반.
 * 지연 조인(안쪽 파생 표가 id 만 골라 offset, 바깥이 그 행에만 계산)은 offset 이 안쪽 층에 있으므로 통과한다(자체 시험).
 *
 * <p><b>허용 목록</b>({@link #KNOWN}) — 지금 걸리는 문장. 줄마다 배정 모듈·풀릴 PR. 고쳐서 더 이상 걸리지 않으면 이 시험이 "목록에서 빼라" 로 실패한다.
 */
class OffsetLedgerGuardTest {

    static final Path MAPPERS = Path.of("src/main/resources/mapper");

    /** 원장처럼 커지는 표 — 근거: 업무 이벤트마다 행이 늘고 지우지 않는다(원장 규약 01 3.1) 또는 기계·요청마다 늘어난다 */
    static final Map<String, String> LEDGER = new LinkedHashMap<>();

    static {
        LEDGER.put("inv_trx", "재고 원장 — 이동·입출고마다 행(WMS 실측 100만)");
        LEDGER.put("inv_stock", "현재고 — 칸 × 품목 × LOT × 상태");
        LEDGER.put("lot_master", "LOT — POP 실적·입고마다 하나");
        LEDGER.put("lot_genealogy", "LOT 계보 원장 — 투입·변환마다");
        LEDGER.put("prd_result", "생산 실적 원장");
        LEDGER.put("cms_alarm_log", "설비 알람 — 설비가 자동으로 올림(가장 빠름)");
        LEDGER.put("cms_equip_state_log", "설비 상태 원장");
        LEDGER.put("cms_equip_count", "설비 카운트 원장");
        LEDGER.put("cms_counter_reading", "계수기 판독 누적");
        LEDGER.put("cms_chk_run", "점검 회차 누적(계획마다 주기 생성)");
        LEDGER.put("cms_chk_result", "점검 결과 줄");
        LEDGER.put("qms_insp", "검사 — LOT·공정마다");
        LEDGER.put("qms_insp_line", "검사 줄 — 검사 항목마다");
        LEDGER.put("qms_insp_meas", "측정값 원장");
        LEDGER.put("qms_spc_event", "SPC 규칙 위반 원장");
        LEDGER.put("srm_po_receipt", "구매 입고 원장");
        LEDGER.put("sys_outbox", "아웃박스 — 모든 외부·비동기 처리");
        LEDGER.put("sys_access_log", "접속 로그 — 요청마다");
        LEDGER.put("sys_audit_log", "감사 로그 — 변경마다");
    }

    /** 원장을 읽는 함수 — 행마다 부르면 그 원장 합을 행마다 계산한다 */
    static final Map<String, String> LEDGER_FUNCS = Map.of(
            "hb_lot_balance", "lot_genealogy 합",
            "hb_proc_qty", "prd_result 합");

    /** 지금 걸리는 문장 — "파일#문장 id" → 배정 모듈·풀릴 PR (docs/qa/2026-10-24_offset-deep-page.md 의 번호) */
    static final Map<String, String> KNOWN = new LinkedHashMap<>();

    static {
    }

    // ------------------------------------------------------------------ 검사기

    static final Pattern STMT = Pattern.compile("<select\\b[^>]*\\bid\\s*=\\s*[\"']([^\"']+)[\"'][^>]*>(.*?)</select>", Pattern.DOTALL);
    static final Pattern FRAG = Pattern.compile("<sql\\b[^>]*\\bid\\s*=\\s*[\"']([^\"']+)[\"'][^>]*>(.*?)</sql>", Pattern.DOTALL);
    static final Pattern INCLUDE = Pattern.compile("<include\\s+refid\\s*=\\s*[\"']([^\"']+)[\"']\\s*/>");
    static final Pattern WORD_OFFSET = Pattern.compile("\\boffset\\b", Pattern.CASE_INSENSITIVE);
    static final Pattern WORD_SELECT = Pattern.compile("\\bselect\\b", Pattern.CASE_INSENSITIVE);
    static final Pattern WORD_FROM = Pattern.compile("\\bfrom\\b", Pattern.CASE_INSENSITIVE);
    static final Pattern PER_ROW = Pattern.compile("\\(\\s*select\\b|\\b(?:hb|wms|mes|qms|srm|cms|plm|aps)_\\w+\\s*\\(|\\b(?:string_agg|array_agg|jsonb_agg|json_agg)\\s*\\(|->>",
            Pattern.CASE_INSENSITIVE);
    static final Pattern LATERAL = Pattern.compile("\\blateral\\b", Pattern.CASE_INSENSITIVE);
    static final Pattern IDENT = Pattern.compile("[a-z_][a-z0-9_]*");

    /** 문장 하나의 판정 — null 이면 통과 */
    record Hit(String key, String table, String why) {
    }

    static String flatten(String body, Map<String, String> frags) {
        String b = body;
        for (int i = 0; i < 5; i++) {
            b = INCLUDE.matcher(b).replaceAll(m -> Matcher.quoteReplacement(frags.getOrDefault(m.group(1), "")));
        }
        b = b.replaceAll("<!\\[CDATA\\[(.*?)]]>", "$1");
        b = b.replaceAll("</?(?:if|where|trim|set|foreach|choose|when|otherwise|bind)\\b[^>]*>", " ");
        b = b.replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&");
        return b.replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    static int[] depths(String s) {
        int[] d = new int[s.length()];
        int cur = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') {
                cur++;
                d[i] = cur;
            } else if (c == ')') {
                d[i] = cur;
                cur--;
            } else {
                d[i] = cur;
            }
        }
        return d;
    }

    /** start 위치의 select(깊이 depth)에 맞는 from 위치, 없으면 -1 */
    static int fromOf(String s, int[] d, int start, int depth) {
        Matcher f = WORD_FROM.matcher(s);
        int i = start + 6;
        while (f.find(i)) {
            if (d[f.start()] == depth) {
                return f.start();
            }
            i = f.end();
        }
        return -1;
    }

    /** 괄호 p( 의 짝 ) 위치 */
    static int close(String s, int p) {
        int depth = 0;
        for (int i = p; i < s.length(); i++) {
            if (s.charAt(i) == '(') {
                depth++;
            } else if (s.charAt(i) == ')' && --depth == 0) {
                return i;
            }
        }
        return s.length() - 1;
    }

    static Hit judge(String key, String sql) {
        Matcher om = WORD_OFFSET.matcher(sql);
        int o = -1;
        while (om.find()) {
            o = om.start();
        }
        if (o < 0) {
            return null;
        }
        int[] d = depths(sql);
        int od = d[o];
        int owner = -1;
        Matcher sm = WORD_SELECT.matcher(sql);
        while (sm.find() && sm.start() < o) {
            if (d[sm.start()] == od) {
                owner = sm.start();
            }
        }
        if (owner < 0) {
            return null;
        }
        int from = fromOf(sql, d, owner, od);
        if (from < 0) {
            return null;
        }
        String selectList = sql.substring(owner + 6, from);
        String fromToOffset = sql.substring(from, o);
        String rest = sql.substring(from + 4).stripLeading();
        String table;
        String perRowText;
        if (rest.startsWith("(")) {
            // 파생 표 — 그 안의 첫 select 가 행마다 계산하는 곳이다(바깥 offset 전에 모든 행)
            int p = sql.indexOf('(', from + 4);
            String inner = sql.substring(p + 1, close(sql, p));
            int[] id = depths(inner);
            Matcher is = WORD_SELECT.matcher(inner);
            if (!is.find()) {
                return null;
            }
            int ifrom = fromOf(inner, id, is.start(), id[is.start()]);
            if (ifrom < 0) {
                return null;
            }
            Matcher t = IDENT.matcher(inner.substring(ifrom + 4).stripLeading());
            table = t.lookingAt() ? t.group() : "(";
            perRowText = selectList + " " + inner.substring(is.start() + 6, ifrom) + " " + inner.substring(ifrom);
        } else {
            Matcher t = IDENT.matcher(rest);
            table = t.lookingAt() ? t.group() : "?";
            perRowText = selectList + " " + fromToOffset;
        }
        List<String> marks = new ArrayList<>();
        Matcher pr = PER_ROW.matcher(rest.startsWith("(") ? perRowText : selectList);
        while (pr.find()) {
            marks.add(pr.group().replaceAll("\\s+", ""));
        }
        if (LATERAL.matcher(rest.startsWith("(") ? perRowText : fromToOffset).find()) {
            marks.add("lateral");
        }
        if (marks.isEmpty()) {
            return null;
        }
        if (LEDGER.containsKey(table)) {
            return new Hit(key, table, "주 표가 원장, 행별 계산 " + new TreeSet<>(marks));
        }
        for (String led : LEDGER.keySet()) {
            if (Pattern.compile("\\b" + led + "\\b").matcher(perRowText).find() && !led.equals(table)) {
                return new Hit(key, table, "행별 계산이 원장 " + led + " 을 읽음");
            }
        }
        for (String fn : LEDGER_FUNCS.keySet()) {
            if (perRowText.contains(fn + "(")) {
                return new Hit(key, table, "행별 계산이 " + fn + "(" + LEDGER_FUNCS.get(fn) + ")");
            }
        }
        return null;
    }

    static List<Hit> scan(Path root) throws IOException {
        List<Hit> out = new ArrayList<>();
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
                    String key = root.relativize(f).toString().replace('\\', '/') + "#" + m.group(1);
                    Hit h = judge(key, flatten(m.group(2), frags));
                    if (h != null) {
                        out.add(h);
                    }
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ 시험

    @Test
    void 원장_표_offset_문장은_행별_계산을_하지_않는다() throws IOException {
        List<Hit> hits = scan(MAPPERS);
        List<String> fresh = hits.stream().filter(h -> !KNOWN.containsKey(h.key())).map(h -> h.key() + " — " + h.table() + " — " + h.why())
                .toList();
        assertThat(fresh).as("새로 걸린 문장 — 지연 조인(안쪽 id 만 offset, 바깥 50행만 계산)으로 바꾸거나, 정말 필요하면 KNOWN 에 근거와 함께").isEmpty();
        Set<String> still = new TreeSet<>();
        hits.forEach(h -> still.add(h.key()));
        List<String> stale = KNOWN.keySet().stream().filter(k -> !still.contains(k)).map(k -> k + " (" + KNOWN.get(k) + ")").toList();
        assertThat(stale).as("고쳐져서 더 이상 걸리지 않는 문장 — KNOWN 에서 빼세요").isEmpty();
    }

    @Test
    void 검사기_자체_시험(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir.resolve("m"));
        Files.writeString(dir.resolve("m/T.xml"), """
                <mapper namespace="x">
                  <sql id="cols">t.trx_id, (select l.lot_no from lot_master l where l.lot_id = t.lot_id) as lot_no</sql>
                  <select id="bad">select <include refid="cols"/>, wms_node_path(t.node_id) from inv_trx t
                    where t.comp_cd = hb_current_comp() order by t.trx_id limit #{size} offset #{offset}</select>
                  <select id='badSingleQuote'>select t.trx_id, (select 1) from inv_trx t <if test="x">where t.qty &gt; 0</if>
                    limit 10 offset 20</select>
                  <select id="deferred">select t.trx_id, (select l.lot_no from lot_master l where l.lot_id = t.lot_id) as lot_no, wms_node_path(t.node_id)
                    from (select x.trx_id from inv_trx x where x.qty &gt; 0 order by x.trx_id limit #{size} offset #{offset}) k
                    join inv_trx t on t.trx_id = k.trx_id order by t.trx_id</select>
                  <select id="derivedBad">select v.* from (select e.event_id, (select max(a.act_id) from qms_spc_event_act a where a.event_id = e.event_id) as last
                    from qms_spc_event e) v order by v.event_id limit 50 offset 100</select>
                  <select id="smallTable">select u.user_id, (select count(*) from sys_user_auth a where a.user_id = u.user_id) from sys_user u
                    limit 50 offset 0</select>
                  <select id="smallReadsLedger">select c.counter_id, (select sum(r.delta_qty) from cms_counter_reading r where r.counter_id = c.counter_id)
                    from cms_counter c limit 50 offset 0</select>
                  <select id="lateralOnLedger">select l.alarm_id, d.nm from cms_alarm_log l left join lateral (select dd.nm from cms_alarm_def dd limit 1) d on true
                    limit 50 offset 0</select>
                  <select id="plainLedger">select t.trx_id, i.item_cd from inv_trx t join mst_item i on i.item_id = t.item_id limit 50 offset 10</select>
                  <select id="noOffset">select (select 1) from inv_trx t limit 50</select>
                </mapper>""", StandardCharsets.UTF_8);
        assertThat(scan(dir)).extracting(Hit::key).containsExactlyInAnyOrder("m/T.xml#bad", "m/T.xml#badSingleQuote", "m/T.xml#derivedBad",
                "m/T.xml#smallReadsLedger", "m/T.xml#lateralOnLedger");
    }

    @Test
    void 허용_목록_줄마다_배정과_근거가_있다() {
        KNOWN.forEach((k, v) -> assertThat(v).as(k).contains("—").hasSizeGreaterThan(8));
        assertThat(LEDGER.values()).allMatch(v -> v.length() > 4);
    }
}
