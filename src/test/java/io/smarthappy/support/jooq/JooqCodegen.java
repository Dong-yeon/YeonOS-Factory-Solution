package io.smarthappy.support.jooq;

import java.nio.file.Path;
import java.util.Map;

import org.flywaydb.core.Flyway;
import org.jooq.codegen.GenerationTool;
import org.jooq.meta.jaxb.Configuration;
import org.jooq.meta.jaxb.Database;
import org.jooq.meta.jaxb.Generate;
import org.jooq.meta.jaxb.Generator;
import org.jooq.meta.jaxb.Jdbc;
import org.jooq.meta.jaxb.Logging;
import org.jooq.meta.jaxb.Target;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/**
 * jOOQ 코드 생성 (docs/43). 스키마의 원본은 Flyway V 파일 — 그 마이그레이션을 실제 PostgreSQL 에 올린 DB 에서 생성한다.
 * 생성물은 저장소에 커밋한다(src/main/jooq) — bootRun·compile 은 Docker 없이 된다.
 *
 * - 재생성: {@code ./gradlew jooqGenerate} → {@link #main}: 일회용 컨테이너 + Flyway(소유자 계정) + 생성
 * - 어긋남 가드: {@code JooqGeneratedGuardTest} 가 시험 DB 로 {@link #generate} 를 다시 돌려 커밋된 생성물과 비교
 * 대상: {@link #TABLES} — 시범 모듈(apqp_) 표 + 조인할 기준정보 표만, {@link #ROUTINES} — 판정용 순수 함수만(이름 허용 목록).
 * 넓히면 생성물·가드가 같이 움직인다
 */
public final class JooqCodegen {

    /** 생성물 패키지 — jOOQ 는 module.apqp 안에서만 쓴다(가드) */
    public static final String PACKAGE = "io.smarthappy.module.apqp.jooq";
    /** 생성물 위치(저장소 루트 기준) */
    public static final String TARGET_DIR = "src/main/jooq";
    /** 생성할 표(정규식, 대소문자 무시) — 시범 모듈 + 조인할 기준정보 */
    public static final String TABLES = "apqp_.* | mst_item | mst_cust | mst_routing | mst_routing_step | mst_proc | mst_bom | mst_bom_line | qms_char | qms_defect | pms_project | sys_user"
            + " | sys_numbering_rule";
    /**
     * jOOQ 로 부를 DB 함수 — 정확한 이름만(docs/43 3장). **판정용 순수 함수**(STABLE·IMMUTABLE, 쓰기 없음, SECURITY DEFINER 아님)만 넣는다.
     * 회사 범위·소속 검사(hb_current_comp·hb_check_*)·트리거 함수는 넣지 않는다 — 가드 JooqRoutinesGuardTest
     */
    public static final java.util.List<String> ROUTINES = java.util.List.of("hb_code_usable");
    /** jOOQ includes 정규식(표·함수에 함께 걸린다) */
    public static final String INCLUDES = TABLES + " | " + String.join(" | ", ROUTINES);

    private JooqCodegen() {
    }

    /** 이미 마이그레이션된 DB 에서 dir 아래로 생성(패키지 폴더를 지우고 다시 쓴다) */
    public static void generate(String jdbcUrl, String user, String password, Path dir) throws Exception {
        GenerationTool.generate(new Configuration()
                .withLogging(Logging.WARN)
                .withJdbc(new Jdbc().withDriver("org.postgresql.Driver").withUrl(jdbcUrl).withUser(user).withPassword(password))
                .withGenerator(new Generator()
                        .withDatabase(new Database()
                                .withName("org.jooq.meta.postgres.PostgresDatabase")
                                .withInputSchema("public")
                                .withOutputSchemaToDefault(true)          // 스키마 이름을 SQL 에 박지 않는다(search_path)
                                .withIncludes(INCLUDES)
                                .withExcludes("flyway_schema_history")
                                .withIncludeTables(true)
                                .withIncludeRoutines(true)                // ROUTINES 허용 목록만(INCLUDES 에 정확한 이름)
                                .withIncludeSequences(false)
                                .withIncludeUDTs(false)
                                .withIncludeDomains(false)
                                .withIncludeIndexes(false)
                                .withIncludeCheckConstraints(false)       // CHECK 식은 문자열(plain SQL)로 생성된다 — 판정은 DB 몫
                                .withIncludeSystemCheckConstraints(false)
                                .withIncludeTriggerRoutines(false))
                        .withGenerate(new Generate()
                                .withRecords(true)
                                .withPojos(false)
                                .withDaos(false)
                                .withJavaTimeTypes(true)
                                .withGeneratedAnnotation(false)           // 날짜·버전을 박지 않아 다시 생성해도 같은 글자
                                .withSources(false)
                                .withSourcesOnViews(false)
                                .withComments(true)
                                .withFluentSetters(false)
                                .withSequences(false)
                                .withIndexes(false)
                                .withRoutines(true)
                                .withGlobalObjectReferences(true))
                        .withTarget(new Target()
                                .withPackageName(PACKAGE)
                                .withDirectory(dir.toString())
                                .withEncoding("UTF-8")
                                .withClean(true))));
    }

    /** {@code ./gradlew jooqGenerate} — 일회용 PostgreSQL(로컬·시험과 같은 이미지·init) + Flyway → 생성 */
    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args.length > 0 ? args[0] : TARGET_DIR);
        String pw = "codegen-only-pw";
        try (PostgreSQLContainer pg = new PostgreSQLContainer("postgres:16-alpine")
                .withDatabaseName("yeonos").withUsername("yeonos_owner").withPassword(pw)
                .withEnv("APP_DB_USER", "yeonos_app").withEnv("APP_DB_PASSWORD", pw)
                .withCopyFileToContainer(MountableFile.forHostPath(Path.of("docker/postgres/init/01-create-app-role.sh"), 0755),
                        "/docker-entrypoint-initdb.d/01-create-app-role.sh")) {
            pg.start();
            Flyway.configure().dataSource(pg.getJdbcUrl(), "yeonos_owner", pw).locations("classpath:db/migration")
                    .placeholders(Map.of("app_role", "yeonos_app")).load().migrate();
            generate(pg.getJdbcUrl(), "yeonos_owner", pw, dir);
        }
        System.out.println("jOOQ 생성 끝: " + dir.toAbsolutePath() + " (" + PACKAGE + ")");
    }
}
