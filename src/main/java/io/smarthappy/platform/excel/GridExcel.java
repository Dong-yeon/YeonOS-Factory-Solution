package io.smarthappy.platform.excel;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.platform.excel.ExcelExportService.ExportJob;
import io.smarthappy.platform.grid.ColumnSpec;
import io.smarthappy.platform.policy.Policies;
import io.smarthappy.platform.policy.PolicyService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/**
 * grid 메뉴 엑셀 공통 도우미 (docs/73 3장) — 화면마다 내보내기 서비스·매퍼를 따로 쓰지 않고 **목록 API 의 서비스 메서드를 그대로** 불러 파일을 만든다.
 *
 * <pre>
 * &#64;PostMapping(API + "/export")
 * &#64;ProgAuth(prog = PROG, action = Action.EXCEL)
 * public void export(&#64;RequestBody(required = false) Map&lt;String, Object&gt; body, HttpServletResponse response) throws IOException {
 *     gridExcel.export(response, body, PROG, service.gridColumns(), q -&gt; {
 *         var p = service.grid(q.str("keyword"), q.str("status"), q.page(), q.size(), q.sort(), q.total());
 *         return GridExcel.Slice.of(p.list(), p.total());
 *     });
 * }
 * </pre>
 *
 * 원칙
 * - 열 정의는 서버 한 곳: 화면 `/columns` 와 같은 {@link ColumnSpec} 목록 — 허용 목록 밖 열·`noExport()` 열은 400.
 * - 조회 조건은 목록과 같은 조건: 같은 서비스 메서드에 같은 이름의 값을 넘기므로 검사·해석·SQL 이 하나다(화면과 파일의 행이 어긋날 수 없다).
 * - 파일 생성은 {@link ExcelExportService} 만: 동시 자리·시간 제한·건수 상한·수식 주입 방지·출력 정보 시트.
 * - 회사 격리는 RLS 그대로: 이 메서드가 요청 스레드에서 회사 트랜잭션을 열고 그 안에서 목록 서비스가 조회한다.
 * - 쪽을 나눠 읽는 동안 행이 늘거나 줄어도 겹침·빠짐이 없게 **REPEATABLE READ**(한 스냅샷)로 읽는다.
 *
 * 수만 행이 예상되는 목록(재고·원장 등)은 지금처럼 매퍼 Cursor 로 쓰는 전용 내보내기가 더 낫다 — 쪽 읽기는 OFFSET 이라 뒤로 갈수록 느려진다.
 */
@Service
@RequiredArgsConstructor
public class GridExcel {

    private final ExcelExportService excel;
    private final PolicyService policies;
    private final JdbcTemplate jdbc;

    /** 목록 한 쪽 — total 은 조건에 맞는 전체 건수(모르면 null) */
    public record Slice(List<?> rows, Long total) {

        public static Slice of(List<?> rows, Number total) {
            return new Slice(rows == null ? List.of() : rows, total == null ? null : total.longValue());
        }
    }

    /** 목록 서비스 호출 — q.page()·q.size()·q.sort()·q.total() 과 조건 값을 넘긴다 */
    @FunctionalInterface
    public interface PageFetcher {
        Slice fetch(GridExportQuery q);
    }

    /** 쪽 단위 목록(page·size 를 받는 grid API) 내보내기 */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public long export(HttpServletResponse response, Map<String, Object> body, String progCd, List<ColumnSpec> columns,
            PageFetcher fetcher) throws IOException {
        GridExportQuery q = GridExportQuery.of(body, policies.intValue(Policies.GRID_PAGE_SIZE_MAX));
        return run(response, q, progCd, columns, fetcher, true);
    }

    /** 쪽이 없는 목록(조건에 맞는 전부를 한 번에 주는 API) 내보내기 — 한 번만 부른다 */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public long exportAll(HttpServletResponse response, Map<String, Object> body, String progCd, List<ColumnSpec> columns,
            Function<GridExportQuery, List<?>> fetcher) throws IOException {
        GridExportQuery q = GridExportQuery.of(body, Integer.MAX_VALUE);
        return run(response, q, progCd, columns, x -> Slice.of(fetcher.apply(x), null), false);
    }

    private long run(HttpServletResponse response, GridExportQuery q, String progCd, List<ColumnSpec> columns,
            PageFetcher fetcher, boolean paged) throws IOException {
        List<ColumnSpec> cols = ColumnSpec.selectExportable(columns, q.columns());
        // 첫 쪽을 먼저 읽는다 — 조건·정렬 오류(400)와 건수 상한이 응답을 쓰기 전에 걸린다
        Slice first = checked(fetcher.fetch(q), q, paged);
        long count = first.total() != null ? first.total() : first.rows().size();
        ExportJob job = new ExportJob(progCd, progNameKey(progCd), cols, count, q.conditionKeys());
        return excel.export(response, job, w -> {
            Slice s = first;
            int page = 1;
            while (true) {
                s.rows().forEach(w);
                if (!paged || s.rows().size() < q.size()) {
                    break;
                }
                s = checked(fetcher.fetch(q.atPage(++page)), q, true);
            }
        });
    }

    /** 쪽 크기를 무시하는 목록을 쪽 방식으로 붙이면 같은 행이 되풀이된다 — 조용히 틀리지 않게 여기서 멈춘다(exportAll 을 써야 한다) */
    private static Slice checked(Slice s, GridExportQuery q, boolean paged) {
        if (s == null || s.rows() == null) {
            throw new IllegalStateException("grid 엑셀: 목록이 null");
        }
        if (paged && s.rows().size() > q.size()) {
            throw new IllegalStateException("grid 엑셀: 목록이 쪽 크기를 지키지 않는다 — exportAll 로 붙일 것");
        }
        return s;
    }

    /** 파일 이름·출력 정보에 쓸 화면 이름 키 — sys_prog 한 곳(전역 표) */
    private String progNameKey(String progCd) {
        List<String> keys = jdbc.queryForList("select prog_nm_key from sys_prog where prog_cd = ?", String.class, progCd);
        if (keys.isEmpty()) {
            throw new IllegalStateException("grid 엑셀: 없는 화면 " + progCd);
        }
        return keys.get(0);
    }
}
