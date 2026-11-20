package io.smarthappy.module.qms;

import java.time.LocalDate;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.cursor.Cursor;

import io.smarthappy.module.qms.CapaDtos.CapaRow;
import io.smarthappy.module.qms.CapaDtos.CapaSearch;
import io.smarthappy.module.qms.CapaDtos.CapaValue;
import io.smarthappy.module.qms.CapaDtos.CsrDue;
import io.smarthappy.module.qms.CapaDtos.CsrRow;
import io.smarthappy.module.qms.CapaDtos.NcrRef;
import io.smarthappy.module.qms.CapaDtos.CsrTargetRow;
import io.smarthappy.module.qms.CapaDtos.CsrTargetValue;
import io.smarthappy.module.qms.CapaDtos.CsrValue;
import io.smarthappy.module.qms.CapaDtos.Ref;
import io.smarthappy.module.qms.CapaDtos.SpreadRow;
import io.smarthappy.module.qms.CapaDtos.SpreadValue;
import io.smarthappy.platform.common.query.PageQuery;

@Mapper
public interface CapaMapper {

    // ---------- 시정조치 ----------

    List<CapaRow> findPage(@Param("q") CapaSearch q);

    long count(@Param("q") CapaSearch q);

    Cursor<CapaRow> cursor(@Param("q") CapaSearch q);

    CapaRow findById(@Param("id") long id, @Param("today") LocalDate today);

    String lockCapa(@Param("id") long id);

    String capaStatus(@Param("id") long id);

    List<SpreadRow> findSpreads(@Param("id") long id);

    Ref findCust(@Param("cd") String cd);

    /** 부적합 번호(대소문자 무시) — 없으면 null */
    NcrRef findNcr(@Param("no") String no);

    /** 8D 의 부적합 연결 — 종결 결재 전까지 */
    int updateCapaNcr(@Param("id") long id, @Param("ncrId") Long ncrId);

    Ref findItem(@Param("cd") String cd);

    Ref findProc(@Param("cd") String cd);


    CsrDue findCsrDue(@Param("custId") long custId, @Param("itemId") Long itemId, @Param("on") LocalDate on);

    long insertCapa(@Param("f") CapaValue f, @Param("capaNo") String capaNo, @Param("containDue") LocalDate containDue,
            @Param("closeDue") LocalDate closeDue, @Param("csrId") Long csrId, @Param("by") String by);

    int updateCapa(@Param("id") long id, @Param("f") CapaValue f, @Param("by") String by);

    int deleteSpreads(@Param("id") long id);

    void insertSpreads(@Param("id") long id, @Param("rows") List<SpreadValue> rows, @Param("by") String by);

    int setStatus(@Param("id") long id, @Param("from") String from, @Param("to") String to, @Param("rowVer") Integer rowVer,
            @Param("by") String by);

    int submitClose(@Param("id") long id, @Param("aprId") long aprId, @Param("rowVer") int rowVer, @Param("by") String by);

    int close(@Param("id") long id, @Param("from") String from, @Param("rowVer") Integer rowVer, @Param("by") String by);

    int cancel(@Param("id") long id, @Param("reason") String reason, @Param("rowVer") int rowVer, @Param("by") String by);

    int lockFiles(@Param("id") long id, @Param("by") String by);

    // ---------- CSR ----------

    List<CsrRow> findCsrs(@Param("custLike") String custLike, @Param("withUnused") boolean withUnused, @Param("limit") int limit);

    List<CsrRow> findCsrGrid(@Param("custLike") String custLike, @Param("withUnused") boolean withUnused, @Param("page") PageQuery page);

    long countCsrs(@Param("custLike") String custLike, @Param("withUnused") boolean withUnused);

    CsrRow findCsr(@Param("id") long id);

    List<CsrTargetRow> findCsrTargets(@Param("id") long id);

    Ref findClass(@Param("cd") String cd);

    long insertCsr(@Param("f") CsrValue f, @Param("by") String by);

    int updateCsr(@Param("id") long id, @Param("f") CsrValue f, @Param("by") String by);

    int deleteCsrTargets(@Param("id") long id);

    void insertCsrTargets(@Param("id") long id, @Param("rows") List<CsrTargetValue> rows, @Param("by") String by);
}
