package io.smarthappy.module.qms;

import java.time.LocalDate;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import io.smarthappy.module.qms.DocDtos.AprLineRow;
import io.smarthappy.module.qms.DocDtos.DocRow;
import io.smarthappy.module.qms.DocDtos.DocSearch;
import io.smarthappy.module.qms.DocDtos.Finding;
import io.smarthappy.module.qms.DocDtos.Ref;
import io.smarthappy.module.qms.DocDtos.RevRow;
import io.smarthappy.module.qms.DocDtos.TargetRow;
import io.smarthappy.module.qms.DocDtos.TargetValue;

@Mapper
public interface DocMapper {

    // ---------- 문서 ----------

    List<DocRow> findPage(@Param("q") DocSearch q);

    long count(@Param("q") DocSearch q);

    DocRow findDoc(@Param("id") long id);

    Long lockDoc(@Param("id") long id);

    List<TargetRow> findTargets(@Param("docId") long docId);

    Ref findItem(@Param("cd") String cd);

    Ref findClass(@Param("cd") String cd);

    Ref findProc(@Param("cd") String cd);

    long insertDoc(@Param("docNo") String docNo, @Param("docType") String docType, @Param("docNm") String docNm,
            @Param("useYn") String useYn, @Param("remark") String remark, @Param("by") String by);

    int updateDoc(@Param("id") long id, @Param("docNm") String docNm, @Param("useYn") String useYn, @Param("remark") String remark,
            @Param("rowVer") int rowVer, @Param("by") String by);

    int deleteTargets(@Param("docId") long docId);

    void insertTargets(@Param("docId") long docId, @Param("targets") List<TargetValue> targets, @Param("by") String by);

    // ---------- 리비전 ----------

    List<RevRow> findRevs(@Param("docId") long docId);

    RevRow findRev(@Param("id") long id);

    String lockRev(@Param("id") long id);

    String revStatus(@Param("id") long id);

    List<String> findRevCodes(@Param("docId") long docId);

    long insertRev(@Param("docId") long docId, @Param("rev") String rev, @Param("sourceRevId") Long sourceRevId,
            @Param("summary") String summary, @Param("by") String by);

    int updateRev(@Param("id") long id, @Param("rev") String rev, @Param("summary") String summary, @Param("rowVer") int rowVer,
            @Param("by") String by);

    int submitRev(@Param("id") long id, @Param("aprId") long aprId, @Param("rowVer") int rowVer, @Param("by") String by);

    int backToDraft(@Param("id") long id, @Param("by") String by);

    int obsoleteReleased(@Param("docId") long docId, @Param("by") String by);

    int releaseRev(@Param("id") long id, @Param("by") String by);

    int obsoleteRev(@Param("id") long id, @Param("rowVer") int rowVer, @Param("by") String by);

    int lockFiles(@Param("id") long id, @Param("by") String by);

    // ---------- 결재 ----------

    List<AprLineRow> findAprLines(@Param("aprId") long aprId);


    // ---------- 정합 점검 ----------

    List<Finding> findSpecialNotInPlan(@Param("today") LocalDate today, @Param("limit") int limit);

    List<Finding> findPlanNoInspStd(@Param("today") LocalDate today, @Param("limit") int limit);

    List<Finding> findPlanDocObsolete(@Param("today") LocalDate today, @Param("limit") int limit);

    // ---------- 쓰기 계약 DocPort (docs/29_P3 20.2) ----------

    /** 같은 부르는 쪽 키 호출을 줄 세움(트랜잭션 끝까지) */
    int lockSourceKey(@Param("key") String key);

    RevRow findRevBySource(@Param("key") String key);

    /** 문서 번호(대소문자 무시) — 사용 여부와 상관없이 */
    DocRow findDocByNo(@Param("no") String no);

    Ref findItemById(@Param("id") long id);

    long insertRevSourced(@Param("docId") long docId, @Param("rev") String rev, @Param("summary") String summary,
            @Param("sourceKey") String sourceKey, @Param("by") String by);
}
