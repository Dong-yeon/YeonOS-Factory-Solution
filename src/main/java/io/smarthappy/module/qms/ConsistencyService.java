package io.smarthappy.module.qms;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.qms.DocDtos.Finding;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.excel.ExcelExportService;
import io.smarthappy.platform.excel.ExcelExportService.ExportJob;
import io.smarthappy.platform.grid.ColumnSpec;
import io.smarthappy.platform.time.BizClock;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/**
 * 품질 정합 점검 (qms-consistency, 읽기 전용) — P3 QMS Q3, docs/29 (26 9번 문서 간 정합).
 * 기준 = 오늘(회사 날짜) 유효한 확정 검사계획. 점검 3종:
 * SPECIAL_NOT_IN_PLAN 특별특성이 어떤 확정 검사계획에도 없음 / PLAN_NO_INSP_STD 확정 검사계획의 대상에 검사기준서 배포본 없음 /
 * PLAN_DOC_OBSOLETE 검사계획이 가리키는 검사기준서 리비전이 폐기됨. 점검마다 정렬 전 상한 — 넘으면 truncated
 */
@Service
@RequiredArgsConstructor
public class ConsistencyService {

    static final String PROG = "qms-consistency";
    static final Set<String> CHECKS = Set.of("SPECIAL_NOT_IN_PLAN", "PLAN_NO_INSP_STD", "PLAN_DOC_OBSOLETE");
    static final int LIMIT = 2000;

    static final List<ColumnSpec> COLUMNS = List.of(
            ColumnSpec.code("checkCd", "qms.cons.col.checkCd", "QMS_CHECK", 200),
            ColumnSpec.text("targetCd", "qms.cons.col.targetCd", 140),
            ColumnSpec.text("targetNm", "qms.cons.col.targetNm", 200),
            ColumnSpec.text("detail", "qms.cons.col.detail", 240));

    private final DocMapper mapper;
    private final BizClock clock;
    private final ExcelExportService excel;

    public record Result(List<Finding> list, boolean truncated) {
    }

    @Transactional(readOnly = true)
    public Result run(String checkCd) {
        String c = checkCd == null || checkCd.isBlank() ? null : checkCd.strip();
        if (c != null && !CHECKS.contains(c)) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        List<Finding> out = new ArrayList<>();
        boolean truncated = false;
        for (String k : List.of("SPECIAL_NOT_IN_PLAN", "PLAN_NO_INSP_STD", "PLAN_DOC_OBSOLETE")) {
            if (c != null && !c.equals(k)) {
                continue;
            }
            List<Finding> part = switch (k) {
                case "SPECIAL_NOT_IN_PLAN" -> mapper.findSpecialNotInPlan(clock.today(), LIMIT + 1);
                case "PLAN_NO_INSP_STD" -> mapper.findPlanNoInspStd(clock.today(), LIMIT + 1);
                default -> mapper.findPlanDocObsolete(clock.today(), LIMIT + 1);
            };
            if (part.size() > LIMIT) {
                truncated = true;
                part = part.subList(0, LIMIT);
            }
            out.addAll(part);
        }
        return new Result(out, truncated);
    }

    @Transactional(readOnly = true)
    public long export(String checkCd, HttpServletResponse response) throws IOException {
        Result r = run(checkCd);
        return excel.export(response, new ExportJob(PROG, "prog.qmsConsistency", COLUMNS, r.list().size(),
                checkCd == null ? List.of() : List.of("checkCd")), w -> r.list().forEach(w));
    }
}
