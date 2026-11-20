package io.smarthappy.module.master.bom;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import io.smarthappy.module.master.bom.BomDtos.ActionRequest;
import io.smarthappy.module.master.bom.BomDtos.BomRow;
import io.smarthappy.module.master.bom.BomDtos.ReleaseBatch;
import io.smarthappy.module.master.bom.BomDtos.ReleaseItem;
import io.smarthappy.module.master.bom.BomDtos.ReleaseOutcome;
import io.smarthappy.platform.common.db.PgErrors;
import io.smarthappy.platform.common.web.ApiError;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.ErrorMessages;
import io.smarthappy.platform.common.web.HbErrorCode;

/**
 * 고른 작성 중 BOM 여러 건을 한 번에 확정 (사용자 결정 G5 2026-11-02, docs/60 R1 — 일괄 대체 결과 목록에서 쓴다).
 *
 * - 건마다 {@link BomService#release} 를 그대로 부른다(권한·rowVer·확정 검사·이전 확정본 자동 마감·ECO 정책 모두 같다)
 * - 건마다 세이브포인트(NESTED) — 한 건이 실패해도 그 건만 되돌리고 나머지는 진행, 실패는 사유와 함께 돌려준다
 * - 권한은 BOM 확정(APPROVE) 그대로 — 컨트롤러가 본다. 다른 회사 BOM id 는 RLS 로 "없음"(NOT_FOUND)으로 남는다
 */
@Service
public class BomReleaseBatchService {

    /** 한 번에 확정할 수 있는 최대 건수(일괄 대체 실행 상한과 같다) */
    static final int MAX_ITEMS = 1000;

    private final BomService boms;
    private final BomMapper mapper;
    private final ErrorMessages errors;
    private final TransactionTemplate nested;

    BomReleaseBatchService(BomService boms, BomMapper mapper, ErrorMessages errors, PlatformTransactionManager txm) {
        this.boms = boms;
        this.mapper = mapper;
        this.errors = errors;
        this.nested = new TransactionTemplate(txm);
        this.nested.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
    }

    @Transactional
    public List<ReleaseOutcome> release(ReleaseBatch req) {
        List<ReleaseItem> items = req == null ? null : req.items();
        if (items == null || items.isEmpty() || items.size() > MAX_ITEMS
                || items.stream().anyMatch(i -> i == null || i.bomId() == null || i.rowVer() == null)) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        Set<Long> seen = new HashSet<>();
        if (!items.stream().allMatch(i -> seen.add(i.bomId()))) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);   // 같은 BOM 두 번 — 화면 실수
        }
        List<ReleaseOutcome> out = new ArrayList<>(items.size());
        for (ReleaseItem it : items) {
            BomRow head = mapper.findById(it.bomId());   // 결과 표시용(없으면 release 가 NOT_FOUND)
            String itemCd = head == null ? null : head.getItemCd();
            String rev = head == null ? null : head.getRev();
            try {
                nested.executeWithoutResult(s -> boms.release(it.bomId(), new ActionRequest(it.rowVer(), null, null)));
                out.add(new ReleaseOutcome(it.bomId(), itemCd, rev, true, null, null));
            } catch (BusinessException e) {
                out.add(new ReleaseOutcome(it.bomId(), itemCd, rev, false, e.code().name(), text(e)));
            } catch (DataAccessException e) {
                if (PgErrors.isRlsViolation(e)) {
                    throw e;   // 회사 경계 위반은 삼키지 않는다(전역 처리기가 403·접속 로그)
                }
                // 경쟁(다른 사람이 같은 품목을 먼저 확정 — 기간 배제 제약 등): 이 건만 실패로
                out.add(new ReleaseOutcome(it.bomId(), itemCd, rev, false, HbErrorCode.CONFLICT.name(),
                        text(new BusinessException(HbErrorCode.CONFLICT))));
            }
        }
        return out;
    }

    /** 응답 본문과 같은 문구 — 기본 문구 + 칸 오류(확정 검사는 행마다 사유) 앞 3개 */
    private String text(BusinessException e) {
        ApiError body = Objects.requireNonNull(errors.response(e).getBody());
        List<String> parts = new ArrayList<>();
        parts.add(body.message());
        if (body.details() != null) {
            parts.addAll(body.details());
        }
        if (body.errors() != null) {
            parts.add(body.errors().stream().map(ApiError.FieldErrorView::message).distinct().limit(3)
                    .collect(Collectors.joining(", ")));
        }
        return String.join(" ", parts);
    }
}
