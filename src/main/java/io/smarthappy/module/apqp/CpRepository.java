package io.smarthappy.module.apqp;

import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;

import io.smarthappy.module.apqp.CpDtos.CpFilter;
import io.smarthappy.module.apqp.CpDtos.CpLineRow;
import io.smarthappy.module.apqp.CpDtos.CpRow;
import io.smarthappy.module.apqp.CpDtos.CpValue;
import io.smarthappy.module.apqp.CpDtos.FmeaOption;
import io.smarthappy.platform.common.query.PageQuery;

/**
 * 관리계획서 데이터 접근(docs/41 5.5 — C8: 처음부터 jOOQ, 구현 {@link JooqCpRepository}). 회사 범위는 RLS.
 * 기준정보·PFMEA 조회는 {@link ApqpRepository} 를 그대로 쓴다
 */
public interface CpRepository {

    List<CpRow> findCpPage(CpFilter f, PageQuery page);

    long countCps(CpFilter f);

    /** 목록 엑셀 — grid 와 같은 조건·정렬, 페이지 없이 한 줄씩(커서) */
    void eachCp(CpFilter f, PageQuery sort, Consumer<CpRow> each);

    CpRow findCp(long id);

    /** 행 잠금 + 상태 — 행 편집·확정·새 리비전이 같은 머리에서 겹치지 않게 */
    String lockCp(long id);

    boolean draftExists(String cpNo);

    long insertCp(String cpNo, int rev, String phase, CpValue v, Long prevId, String by);

    int updateCp(long id, CpValue v, int rowVer, String by);

    int deleteCp(long id, int rowVer);

    int obsoleteReleased(String cpNo, String by);

    int releaseCp(long id, int rowVer, String by);

    /** 결재 상신 — 작성 중 → 결재 중 */
    int submitCp(long id, long aprId, int rowVer, String by);

    /** 결재 승인 = 확정 */
    int approveCp(long id, String by);

    /** 결재 반려·회수 — 결재 중 → 작성 중 */
    int draftCp(long id, String by);

    /** 행이 바뀌면 머리 row_ver 를 올린다(화면이 머리로 낡음을 안다) */
    void touchCp(long id, String by);

    Long cpOfLine(long lineId);

    List<CpLineRow> findLines(long cpId);

    /** PFD(A5-1) — 이 PFMEA 를 가리키는 관리계획서 중 폐기 아닌 가장 높은 리비전(없으면 null) */
    Long findLatestCpOfFmea(long fmeaId);

    long insertLine(long cpId, CpLineRow l, String by);

    int updateLine(long id, CpLineRow l, int rowVer, String by);

    int updateLineNo(long id, int lineNo, String by);

    /** 확정 직전 — 특성을 가리키는 행에 규격·특별특성 스냅샷(행에 정한 특별특성이 있으면 그것) */
    int snapshotLines(long cpId);

    int deleteLines(Collection<Long> ids);

    /** 품목의 확정 PFMEA(근거로 고를 것) */
    List<FmeaOption> findReleasedFmeas(long itemId);

    /** 같은 번호의 지금 확정 리비전 — 근거가 폐기됐을 때 새 근거 안내 */
    Long findReleasedFmeaIdByNo(String fmeaNo);
}
