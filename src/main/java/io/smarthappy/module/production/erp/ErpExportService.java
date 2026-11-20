package io.smarthappy.module.production.erp;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;

import org.apache.ibatis.cursor.Cursor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.production.erp.ErpDtos.ErpRow;
import io.smarthappy.module.production.erp.ErpDtos.ExportForm;
import io.smarthappy.module.production.erp.ErpDtos.Preview;
import io.smarthappy.module.sales.OrderService;
import io.smarthappy.platform.common.query.DateRanges;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.excel.ExcelExportService;
import io.smarthappy.platform.excel.ExcelExportService.ExportJob;
import io.smarthappy.platform.grid.ColumnSpec;
import io.smarthappy.platform.grid.ColumnSpec.ColumnType;
import io.smarthappy.platform.policy.PolicyService;
import io.smarthappy.platform.security.CurrentUser;
import io.smarthappy.platform.security.LoginUser;
import io.smarthappy.platform.time.BizClock;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/**
 * ERP 업로드용 엑셀 내보내기 (prd-erp-export, P2 7c, docs/25 4.11) — 자동 송신이 없는 고객의 실제 경로(07 D4).
 *
 * 종류 RESULT(완료된 지시 — 완료 시각 기준) / RECEIPT(입고·정정 행 — 발생 시각 기준) × 기간(회사 시간대 날짜, 끝 날 포함).
 * 내보낼 때마다 기록(prd_erp_export). 같은 기간을 또 내보내는 것은 막지 않고 화면이 이전 기록을 보여 준다(D20).
 */
@Service
@RequiredArgsConstructor
public class ErpExportService {

    public static final String PROG = "prd-erp-export";
    static final int SCREEN_MAX_DAYS = 92;
    static final int PREVIEW_LIMIT = 500;
    static final int EXPORT_LOG_LIMIT = 20;
    static final Set<String> KINDS = Set.of("RESULT", "RECEIPT");

    private static final int QTY_SCALE = 6;

    /** ERP 업로드 양식 열 — 일자·ERP 품목·품목·수량·단위·창고·LOT·MES 번호 */
    static final List<ColumnSpec> RESULT_COLUMNS = List.of(
            ColumnSpec.of("day", "prdErp.col.day", ColumnType.DATE, 100),
            ColumnSpec.text("erpItemCd", "prdErp.col.erpItemCd", 140),
            ColumnSpec.text("itemCd", "prdErp.col.itemCd", 140),
            ColumnSpec.text("itemNm", "prdErp.col.itemNm", 200),
            ColumnSpec.dec("qty", "prdErp.col.goodQty", QTY_SCALE, 100).withMinScale(0),
            ColumnSpec.dec("scrapQty", "prdErp.col.scrapQty", QTY_SCALE, 100).withMinScale(0),
            ColumnSpec.text("unitCd", "prdErp.col.unitCd", 70),
            ColumnSpec.text("woNo", "prdErp.col.woNo", 140),
            ColumnSpec.text("bizKey", "prdErp.col.bizKey", 160));

    static final List<ColumnSpec> RECEIPT_COLUMNS = List.of(
            ColumnSpec.of("day", "prdErp.col.day", ColumnType.DATE, 100),
            ColumnSpec.text("erpItemCd", "prdErp.col.erpItemCd", 140),
            ColumnSpec.text("itemCd", "prdErp.col.itemCd", 140),
            ColumnSpec.text("itemNm", "prdErp.col.itemNm", 200),
            ColumnSpec.dec("qty", "prdErp.col.qty", QTY_SCALE, 100).withMinScale(0),
            ColumnSpec.text("unitCd", "prdErp.col.unitCd", 70),
            ColumnSpec.text("whCd", "prdErp.col.whCd", 110),
            ColumnSpec.text("lotNo", "prdErp.col.lotNo", 160),
            ColumnSpec.text("woNo", "prdErp.col.woNo", 140),
            ColumnSpec.code("entryMethod", "prdErp.col.entryMethod", "ENTRY_METHOD", 90),
            ColumnSpec.text("bizKey", "prdErp.col.bizKey", 160));

    private final ErpMapper mapper;
    private final ExcelExportService excel;
    private final DateRanges ranges;
    private final PolicyService policies;
    private final BizClock clock;

    @Transactional(readOnly = true)
    public Preview preview(String kind, LocalDate from, LocalDate to) {
        String k = kind(kind);
        ranges.check(from, to, SCREEN_MAX_DAYS);
        ZoneId zone = clock.zone();
        Instant a = start(from, zone);
        Instant b = start(to.plusDays(1), zone);
        List<ErpRow> rows = mapper.rows(k, a, b, zone.getId(), PREVIEW_LIMIT + 1);
        boolean truncated = rows.size() > PREVIEW_LIMIT;
        return new Preview(truncated ? rows.subList(0, PREVIEW_LIMIT) : rows, mapper.count(k, a, b), truncated,
                mapper.unmapped(k, a, b), mapper.exports(k, from, to, EXPORT_LOG_LIMIT), policies.value(ErpPublisher.POLICY));
    }

    /** 기록을 먼저 넣고(같은 트랜잭션) 파일을 쓴다 — 건수 상한 초과·오류면 기록도 없다 */
    @Transactional
    public long export(ExportForm in, HttpServletResponse response) throws IOException {
        LoginUser me = CurrentUser.get().orElseThrow(() -> new BusinessException(HbErrorCode.UNAUTHENTICATED));
        if (in == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        String k = kind(in.kind());
        ranges.check(in.from(), in.to(), SCREEN_MAX_DAYS);
        ZoneId zone = clock.zone();
        Instant a = start(in.from(), zone);
        Instant b = start(in.to().plusDays(1), zone);
        long count = mapper.count(k, a, b);
        List<ColumnSpec> cols = "RESULT".equals(k) ? RESULT_COLUMNS : RECEIPT_COLUMNS;
        return excel.export(response, new ExportJob(PROG, "prog.prdErpExport", cols, count, List.of("kind", "from", "to")), w -> {
            // 상한 검사는 export 안에서 먼저 — 넘으면 여기까지 오지 않는다
            mapper.insertExport(k, in.from(), in.to(), count, clock.instant(), me.loginId());
            try (Cursor<ErpRow> c = mapper.cursor(k, a, b, zone.getId(), null)) {
                c.forEach(w);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    private static String kind(String kind) {
        String k = kind == null ? null : kind.strip();
        if (k == null || k.isEmpty()) {
            throw OrderService.fieldError("kind", "REQUIRED");
        }
        if (!KINDS.contains(k)) {
            throw OrderService.fieldError("kind", "CODE");
        }
        return k;
    }

    private static Instant start(LocalDate day, ZoneId zone) {
        return day.atStartOfDay(zone).toInstant();
    }
}
