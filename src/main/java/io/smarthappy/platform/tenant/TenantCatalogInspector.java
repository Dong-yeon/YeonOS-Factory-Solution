package io.smarthappy.platform.tenant;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.springframework.jdbc.core.JdbcOperations;

/**
 * RLS 우회 경로 검사 (01 설계 1.4). public 스키마의 DB 카탈로그를 읽어 규칙 위반을 돌려준다.
 * 기동 시({@link TenantCatalogGuard})와 테스트에서 같은 규칙을 쓴다.
 */
public class TenantCatalogInspector {

    /** 꼭 필요해서 허용한 SECURITY DEFINER 함수 (현재 없음) */
    static final Set<String> ALLOWED_SECURITY_DEFINER = Set.of();

    /** tenant_isolation 외에 허용한 정책 (테이블.정책) */
    // - sys_comp.system_directory_read: 회사 목록 조회(TenantRunner.activeCompCodes, V002)
    // - sys_comp.ops_directory_read: 운영자 콘솔의 회사 목록(코드·이름·상태) — 업무 데이터 아님 (V011)
    // - sys_usage_daily.ops_usage_read: 운영자는 회사별 활용 "집계 숫자만" 본다 (docs/11 D3 사용자 결정, V011)
    static final Set<String> ALLOWED_EXTRA_POLICIES = Set.of("sys_comp.system_directory_read",
            "sys_comp.ops_directory_read", "sys_usage_daily.ops_usage_read");

    /**
     * comp_cd 가 없는 전역 테이블 허용 목록. 제품이 정의하는 데이터라 Flyway 로만 바뀌어야 하므로
     * 앱 계정에 쓰기 권한이 있으면 위반이다.
     */
    static final Set<String> GLOBAL_TABLES = Set.of("flyway_schema_history", "sys_prog", "sys_code_grp", "sys_code",
            "sys_policy_def", "sys_retention_class", "sys_retention_table", "sys_operator", "sys_ops_log",
            // 용어 사전 전역 용어(P1 6단계, docs/21) — 지표 정의는 제품이 정한다. 회사 동의어는 mst_term_alias(RLS)
            "sys_term");

    /**
     * 전역 테이블 중 앱이 행을 **추가만** 할 수 있는 것 (수정·삭제·TRUNCATE 는 여전히 위반).
     * sys_ops_log: 운영자 동작 기록 — 앱이 남기되 고칠 수 없어야 한다. (sys_operator 는 열 단위 UPDATE 만이라 표 권한 없음)
     */
    static final Set<String> GLOBAL_APPEND_ONLY = Set.of("sys_ops_log");

    private static final String COMP_TABLES = """
            select c.oid, c.relname, c.relrowsecurity, c.relforcerowsecurity
              from pg_class c
              join pg_namespace n on n.oid = c.relnamespace
             where n.nspname = 'public'
               and c.relkind in ('r', 'p')
               and exists (select 1 from pg_attribute a
                            where a.attrelid = c.oid and a.attname = 'comp_cd' and not a.attisdropped)
            """;

    private final JdbcOperations jdbc;
    /** 쓰기 권한을 검사할 앱 계정 (null 이면 현재 접속 계정 — 기동 시에는 앱 계정으로 접속해 있음) */
    private final String appRole;

    public TenantCatalogInspector(JdbcOperations jdbc) {
        this(jdbc, null);
    }

    public TenantCatalogInspector(JdbcOperations jdbc, String appRole) {
        this.jdbc = jdbc;
        this.appRole = appRole;
    }

    public List<String> inspect() {
        List<String> violations = new ArrayList<>();

        jdbc.queryForList("""
                select c.relname from pg_class c join pg_namespace n on n.oid = c.relnamespace
                 where n.nspname = 'public' and c.relkind = 'v'
                   and not exists (select 1 from unnest(coalesce(c.reloptions, '{}'::text[])) o
                                    where lower(o) in ('security_invoker=true', 'security_invoker=on',
                                                       'security_invoker=1', 'security_invoker=yes'))
                """, String.class)
                .forEach(v -> violations.add("뷰 [" + v + "] 에 security_invoker=true 가 없습니다 (정의자 권한으로 RLS 우회)"));

        jdbc.queryForList("""
                select c.relname from pg_class c join pg_namespace n on n.oid = c.relnamespace
                 where n.nspname = 'public' and c.relkind = 'm'
                """, String.class)
                .forEach(v -> violations.add("머티리얼라이즈드 뷰 [" + v + "] 는 RLS 가 적용되지 않으므로 금지입니다"));

        jdbc.queryForList("""
                select p.proname from pg_proc p join pg_namespace n on n.oid = p.pronamespace
                 where n.nspname = 'public' and p.prosecdef
                   and not exists (select 1 from pg_depend d where d.objid = p.oid and d.deptype = 'e')
                """, String.class).stream()
                .filter(f -> !ALLOWED_SECURITY_DEFINER.contains(f))
                .forEach(f -> violations.add("SECURITY DEFINER 함수 [" + f + "] 는 허용 목록에 없습니다"));

        jdbc.queryForList(COMP_TABLES).forEach(t -> {
            String table = (String) t.get("relname");
            if (!Boolean.TRUE.equals(t.get("relrowsecurity")) || !Boolean.TRUE.equals(t.get("relforcerowsecurity"))) {
                violations.add("회사별 테이블 [" + table + "] 에 ENABLE + FORCE ROW LEVEL SECURITY 가 없습니다");
            }
        });

        jdbc.queryForList("""
                select t.relname from (%s) t
                 where not exists (
                       select 1 from pg_policies p
                        where p.schemaname = 'public' and p.tablename = t.relname
                          and p.policyname = 'tenant_isolation' and p.cmd = 'ALL' and p.permissive = 'PERMISSIVE'
                          and p.qual like '%%hb_current_comp()%%' and p.with_check like '%%hb_current_comp()%%')
                """.formatted(COMP_TABLES), String.class)
                .forEach(t -> violations.add("회사별 테이블 [" + t + "] 에 표준 tenant_isolation 정책이 없습니다"));

        jdbc.queryForList("""
                select p.tablename || '.' || p.policyname from pg_policies p
                 where p.schemaname = 'public' and p.policyname <> 'tenant_isolation'
                """, String.class).stream()
                .filter(p -> !ALLOWED_EXTRA_POLICIES.contains(p))
                .forEach(p -> violations.add("허용 목록에 없는 RLS 정책 [" + p + "] (정책은 OR 로 합쳐져 격리를 넓힐 수 있음)"));

        jdbc.queryForList("""
                select c.relname,
                       has_table_privilege(coalesce(?, current_user), c.oid, 'UPDATE, DELETE, TRUNCATE') as mutable,
                       has_table_privilege(coalesce(?, current_user), c.oid, 'INSERT') as insertable
                  from pg_class c join pg_namespace n on n.oid = c.relnamespace
                 where n.nspname = 'public' and c.relkind in ('r', 'p')
                   and not exists (select 1 from pg_attribute a
                                    where a.attrelid = c.oid and a.attname = 'comp_cd' and not a.attisdropped)
                """, appRole, appRole).forEach(t -> {
            String table = (String) t.get("relname");
            boolean writable = Boolean.TRUE.equals(t.get("mutable"))
                    || (Boolean.TRUE.equals(t.get("insertable")) && !GLOBAL_APPEND_ONLY.contains(table));
            if (!GLOBAL_TABLES.contains(table)) {
                violations.add("회사 구분(comp_cd) 없는 테이블 [" + table + "] — 전역 테이블이면 허용 목록에 근거와 함께 등록");
            } else if (writable) {
                violations.add("전역 테이블 [" + table + "] 에 앱 계정 쓰기 권한이 있습니다 (Flyway 로만 변경해야 함)");
            }
        });

        // 원장 규약(01 3.1): reversal_of_id 가 있는 테이블 = 원장 → 수정·삭제 차단 트리거 + 앱 계정 UPDATE/DELETE 권한 없음
        // + 두 시각(occurred_at·recorded_at)·입력 방식(entry_method, 채택 KPI 원천 — docs/11 D1)
        for (Ledger t : ledgers()) {
            violations.addAll(t.problems());
        }

        return violations;
    }

    /**
     * 원장 표 하나의 상태 (운영 중 "기록 무결성 점검" 도 같은 규칙을 쓴다 — docs/52 Y1)
     *
     * @param guarded 수정·삭제 차단 트리거(hb_forbid_update_delete)가 있다
     * @param mutable 앱 계정에 UPDATE/DELETE 권한이 있다
     * @param stdCols occurred_at·recorded_at·entry_method 중 있는 수(3이어야 한다)
     */
    public record Ledger(String table, boolean guarded, boolean mutable, int stdCols) {
        public List<String> problems() {
            List<String> p = new ArrayList<>();
            if (!guarded) {
                p.add("원장 테이블 [" + table + "] 에 hb_forbid_update_delete 트리거가 없습니다 (정정은 역분개 행만)");
            }
            if (mutable) {
                p.add("원장 테이블 [" + table + "] 에 앱 계정 UPDATE/DELETE 권한이 있습니다 (REVOKE 필요)");
            }
            if (stdCols < 3) {
                p.add("원장 테이블 [" + table + "] 에 occurred_at·recorded_at·entry_method 가 모두 있어야 합니다");
            }
            return p;
        }

        public boolean ok() {
            return guarded && !mutable && stdCols >= 3;
        }
    }

    /** 원장 표(= reversal_of_id 칸이 있는 표) 전부의 상태, 이름순 */
    public List<Ledger> ledgers() {
        return jdbc.query("""
                select c.relname,
                       exists (select 1 from pg_trigger tg join pg_proc p on p.oid = tg.tgfoid
                                where tg.tgrelid = c.oid and not tg.tgisinternal and tg.tgenabled <> 'D'
                                  and p.proname = 'hb_forbid_update_delete') as guarded,
                       (select count(*) from pg_attribute a where a.attrelid = c.oid and not a.attisdropped
                         and a.attname in ('occurred_at', 'recorded_at', 'entry_method')) as std_cols,
                       has_table_privilege(coalesce(?, current_user), c.oid, 'UPDATE, DELETE') as mutable
                  from pg_class c join pg_namespace n on n.oid = c.relnamespace
                 where n.nspname = 'public' and c.relkind in ('r', 'p')
                   and exists (select 1 from pg_attribute a
                                where a.attrelid = c.oid and a.attname = 'reversal_of_id' and not a.attisdropped)
                 order by c.relname
                """, (rs, i) -> new Ledger(rs.getString(1), rs.getBoolean(2), rs.getBoolean(4), rs.getInt(3)), appRole);
    }
}
