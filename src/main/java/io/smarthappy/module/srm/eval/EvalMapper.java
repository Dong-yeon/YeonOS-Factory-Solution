package io.smarthappy.module.srm.eval;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import io.smarthappy.module.srm.eval.EvalDtos.AckRate;
import io.smarthappy.module.srm.eval.EvalDtos.CertState;
import io.smarthappy.module.srm.eval.EvalDtos.EvalCrit;
import io.smarthappy.module.srm.eval.EvalDtos.EvalRow;
import io.smarthappy.module.srm.eval.EvalDtos.BandRow;
import io.smarthappy.module.srm.eval.EvalDtos.BandValue;
import io.smarthappy.module.srm.eval.EvalDtos.GradeRow;
import io.smarthappy.module.srm.eval.EvalDtos.GradeSpec;
import io.smarthappy.module.srm.eval.EvalDtos.GradeValue;
import io.smarthappy.module.srm.eval.EvalDtos.ItemRow;
import io.smarthappy.module.srm.eval.EvalDtos.ItemSpec;
import io.smarthappy.module.srm.eval.EvalDtos.ItemValue;
import io.smarthappy.module.srm.eval.EvalDtos.OtdRate;
import io.smarthappy.module.srm.eval.EvalDtos.ScoreRow;
import io.smarthappy.module.srm.eval.EvalDtos.ScoreSpec;
import io.smarthappy.module.srm.eval.EvalDtos.SupplierOption;
import io.smarthappy.module.srm.eval.EvalDtos.TmplCrit;
import io.smarthappy.module.srm.eval.EvalDtos.TmplRow;
import io.smarthappy.platform.common.query.PageQuery;

@Mapper
public interface EvalMapper {

    // ---------- 템플릿 ----------

    List<TmplRow> findTmpls(@Param("c") TmplCrit c);

    /** grid(UI 단계 3 ①) — 같은 조건 + 페이지·정렬(허용 목록) */
    List<TmplRow> findTmplPage(@Param("c") TmplCrit c, @Param("page") PageQuery page);

    long countTmpls(@Param("c") TmplCrit c);

    TmplRow findTmpl(@Param("id") long id);

    TmplRow lockTmpl(@Param("id") long id);

    List<ItemRow> findItems(@Param("tmplId") long tmplId);

    List<GradeRow> findGrades(@Param("tmplId") long tmplId);

    /** 점수 구간(R4b) — 항목·상한 오름차순, 끝 없는 구간 마지막 */
    List<BandRow> findBands(@Param("tmplId") long tmplId);

    int deleteBands(@Param("tmplId") long tmplId);

    /** 구간 — 항목은 같은 템플릿의 항목 번호로 찾는다(항목을 막 다시 넣었으므로 id 를 모름) */
    void insertBand(@Param("tmplId") long tmplId, @Param("itemNo") int itemNo, @Param("f") BandValue f, @Param("by") String by);

    void copyBands(@Param("from") long from, @Param("to") long to, @Param("by") String by);

    boolean tmplCdExists(@Param("tmplCd") String tmplCd);

    int maxRev(@Param("tmplCd") String tmplCd);

    long insertTmpl(@Param("tmplCd") String tmplCd, @Param("rev") int rev, @Param("tmplNm") String tmplNm,
            @Param("validFrom") LocalDate validFrom, @Param("validTo") LocalDate validTo, @Param("remark") String remark,
            @Param("by") String by);

    int updateTmpl(@Param("id") long id, @Param("tmplNm") String tmplNm, @Param("validFrom") LocalDate validFrom,
            @Param("validTo") LocalDate validTo, @Param("remark") String remark, @Param("rowVer") int rowVer, @Param("by") String by);

    int deleteItems(@Param("tmplId") long tmplId);

    int deleteGrades(@Param("tmplId") long tmplId);

    void insertItem(@Param("tmplId") long tmplId, @Param("f") ItemValue f, @Param("by") String by);

    void insertGrade(@Param("tmplId") long tmplId, @Param("f") GradeValue f, @Param("by") String by);

    void copyItems(@Param("from") long from, @Param("to") long to, @Param("by") String by);

    void copyGrades(@Param("from") long from, @Param("to") long to, @Param("by") String by);

    /** 같은 코드의 끝 없는 확정본 중 시작일이 더 이른 것을 새 시작일 전날로 마감 */
    int closeOpenReleased(@Param("tmplCd") String tmplCd, @Param("exceptId") long exceptId, @Param("validFrom") LocalDate validFrom,
            @Param("by") String by);

    int release(@Param("id") long id, @Param("rowVer") int rowVer, @Param("by") String by);

    int obsolete(@Param("id") long id, @Param("from") String from, @Param("rowVer") int rowVer, @Param("by") String by);

    // ---------- 평가 ----------

    List<SupplierOption> findSuppliers();

    SupplierOption findSupplier(@Param("custCd") String custCd);

    /** 기준일(평가 기간 끝)에 유효한 확정 리비전 */
    TmplRow findEffectiveTmpl(@Param("tmplCd") String tmplCd, @Param("on") LocalDate on);

    List<TmplRow> findReleasedTmpls();

    List<EvalRow> findEvals(@Param("c") EvalCrit c);

    /** grid(UI 단계 3 ①) — 같은 조건 + 페이지·정렬(허용 목록) */
    List<EvalRow> findEvalPage(@Param("c") EvalCrit c, @Param("page") PageQuery page);

    long countEvals(@Param("c") EvalCrit c);

    EvalRow findEval(@Param("id") long id);

    EvalRow lockEval(@Param("id") long id);

    List<ScoreRow> findScores(@Param("evalId") long evalId);

    List<ItemSpec> itemSpecs(@Param("tmplId") long tmplId);

    List<GradeSpec> gradeSpecs(@Param("tmplId") long tmplId);

    List<ScoreSpec> scoreSpecs(@Param("evalId") long evalId);

    long insertEval(@Param("evalNo") String evalNo, @Param("custId") long custId, @Param("tmplId") long tmplId,
            @Param("from") LocalDate from, @Param("to") LocalDate to, @Param("remark") String remark, @Param("by") String by);

    void insertScores(@Param("evalId") long evalId, @Param("tmplId") long tmplId, @Param("by") String by);

    int touchEval(@Param("id") long id, @Param("remark") String remark, @Param("rowVer") int rowVer, @Param("by") String by);

    /** 사람이 넣는 점수(AUTO 계산 행은 서비스가 거른다) */
    int updateScore(@Param("evalId") long evalId, @Param("itemId") long itemId, @Param("score") BigDecimal score,
            @Param("comment") String comment, @Param("by") String by);

    /** 자동 계산 행은 의견만 */
    int updateAutoComment(@Param("evalId") long evalId, @Param("itemId") long itemId, @Param("comment") String comment,
            @Param("by") String by);

    /** AUTO 계산 결과 — 계산할 수 없으면 autoYn N + 근거(사람이 넣는다), 점수는 그대로 둔다 */
    int updateAuto(@Param("evalId") long evalId, @Param("itemId") long itemId, @Param("autoYn") String autoYn,
            @Param("score") BigDecimal score, @Param("rawValue") BigDecimal rawValue, @Param("basis") String basis, @Param("by") String by);

    int confirm(@Param("id") long id, @Param("rowVer") int rowVer, @Param("total") BigDecimal total, @Param("gradeCd") String gradeCd,
            @Param("failYn") String failYn, @Param("suggest") String suggest, @Param("by") String by);

    int cancel(@Param("id") long id, @Param("rowVer") int rowVer, @Param("reason") String reason, @Param("by") String by);

    // ---------- AUTO 지표 (1부: ACK_RATE · CERT_VALID, 2부 R2: OTD_RATE) ----------

    /** 기간에 발행한 개정(발행본) 수와 그중 회신 기한 안에 회신(거절 포함)이 온 수 */
    /** OTD_RATE — 요구 납기가 기간 안인 발주 행 수와 그중 납기까지 다 받은 행 수. 원천 SRM 발주만(ERP 원천은 게이트를 지나지 않음) */
    OtdRate otdRate(@Param("custId") long custId, @Param("from") LocalDate from, @Param("to") LocalDate to);

    AckRate ackRate(@Param("custId") long custId, @Param("from") LocalDate from, @Param("to") LocalDate to, @Param("zone") String zone,
            @Param("dueDays") int dueDays);

    /** 기준일의 현재 인증서(유형마다 사용 중 행 중 기준일까지 발행된 최신) 수와 가장 이른 만료일 */
    CertState certState(@Param("custId") long custId, @Param("on") LocalDate on);
}
