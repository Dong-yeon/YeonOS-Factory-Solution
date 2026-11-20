package io.smarthappy.module.plm;

import org.springframework.stereotype.Component;

import io.smarthappy.module.plm.PlmDtos.PpapRow;
import io.smarthappy.platform.file.FileLockRule;
import lombok.RequiredArgsConstructor;

/**
 * 설계 변경 첨부 잠금 (D7, docs/39 15.2) — 올리기·삭제를 막을 때. 근거로 굳히는 DB 표시(locked_yn)는 적용·PPAP 승인 때 따로.
 *
 * - ECO 첨부(plm-eco × {eco_id}): 작성 중일 때만 열림 — 상신하면 결재받는 내용이 고정, 반려·회수로 작성 중이 되면 다시 열림
 * - PPAP 증빙(plm-eco × PPAP-{ppap_id}): 패키지가 작성 중일 때만
 * - 고객 메일 첨부 사본(plm-eco × PCN-{pcn_id} 33장 F3 · PPAPMAIL-{ppap_id} 37장 P5): 사용자에게는 늘 잠김 — 서버가 메일 보내는
 *   트랜잭션에서 그 대상만 연다({@link #SERVER_ATTACHING} 자원)
 * - 대상 도면(qms-doc × {doc_rev_id}): 결재 중·승인된 ECO 의 대상이면 잠김 — 리비전 row_ver 는 파일 변경을 모른다(QMS 규칙과 OR)
 * - 없는 대상(다른 회사 포함)은 잠김. 모르는 화면은 false
 */
@Component
@RequiredArgsConstructor
public class PlmFileLock implements FileLockRule {

    static final String DOC_PROG = "qms-doc";

    /** 이 트랜잭션에서 서버가 메일 첨부 사본을 넣는 대상(트랜잭션 자원) — 그 대상만 연다 */
    static final String SERVER_ATTACHING = PlmFileLock.class.getName() + ".serverAttaching";

    private final PlmMapper mapper;
    private final PlmPpapMapper ppaps;

    @Override
    public boolean locked(String targetType, String targetId) {
        if (DOC_PROG.equals(targetType)) {
            Long rev = id(targetId);
            return rev != null && mapper.docRevInOpenEco(rev);
        }
        if (!EcoService.PROG.equals(targetType) || targetId == null) {
            return false;
        }
        if (targetId.startsWith("PCN-") || targetId.startsWith("PPAPMAIL-")) {
            return !targetId.equals(org.springframework.transaction.support.TransactionSynchronizationManager.getResource(SERVER_ATTACHING));
        }
        if (targetId.startsWith("PPAP-")) {
            Long ppapId = id(targetId.substring(5));
            PpapRow p = ppapId == null ? null : ppaps.findPpap(ppapId);
            return p == null || !"DRAFT".equals(p.getStatus());
        }
        Long ecoId = id(targetId);
        return ecoId == null || !"DRAFT".equals(mapper.ecoStatus(ecoId));
    }

    private static Long id(String s) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
