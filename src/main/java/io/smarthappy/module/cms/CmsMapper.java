package io.smarthappy.module.cms;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import io.smarthappy.module.cms.CmsDtos.FailCodeRow;
import io.smarthappy.module.cms.CmsDtos.FailCodeValue;
import io.smarthappy.module.cms.CmsDtos.IdOption;
import io.smarthappy.module.cms.CmsDtos.InstalledAsset;
import io.smarthappy.module.cms.CmsDtos.LaborRow;
import io.smarthappy.module.cms.CmsDtos.MwoCrit;
import io.smarthappy.module.cms.CmsDtos.MwoRow;
import io.smarthappy.module.cms.CmsDtos.Option;
import io.smarthappy.module.cms.CmsDtos.ProfileRow;
import io.smarthappy.module.cms.CmsDtos.TargetRow;
import io.smarthappy.module.cms.link.BreakdownReport.Symptom;
import io.smarthappy.platform.common.query.PageQuery;

/** CMS 1부 C1 (docs/33) */
@Mapper
public interface CmsMapper {

    // ---------- 공통 ----------

    List<Option> findPlants();

    List<IdOption> findEquipClasses();

    Long findClassId(@Param("classCd") String classCd, @Param("activeOnly") boolean activeOnly);

    /** 공통코드가 그 회사에서 쓸 수 있나(시스템 사용 중 또는 회사 사용 중) — ISO 14224 칸(G3), 트리거와 같은 hb_code_usable */
    boolean codeUsable(@Param("grp") String grp, @Param("code") String code);

    boolean ruleExists(@Param("ruleCd") String ruleCd);

    // ---------- 고장 코드 ----------

    List<FailCodeRow> findFailCodes(@Param("codeType") String codeType, @Param("useYn") String useYn,
            @Param("keyword") String keyword);

    /** grid(UI 단계 3 ①) — 같은 조건 + 페이지·정렬(허용 목록) */
    List<FailCodeRow> findFailCodePage(@Param("codeType") String codeType, @Param("useYn") String useYn,
            @Param("keyword") String keyword, @Param("page") PageQuery page);

    long countFailCodes(@Param("codeType") String codeType, @Param("useYn") String useYn, @Param("keyword") String keyword);

    FailCodeRow findFailCode(@Param("id") long id);

    FailCodeRow findFailCodeByCd(@Param("codeType") String codeType, @Param("failCd") String failCd);

    long insertFailCode(@Param("f") FailCodeValue f, @Param("by") String by);

    int updateFailCode(@Param("id") long id, @Param("f") FailCodeValue f, @Param("by") String by);

    // ---------- 설비 프로필 ----------

    List<ProfileRow> findProfiles(@Param("plantCd") String plantCd, @Param("keyword") String keyword,
            @Param("criticality") String criticality, @Param("keyOnly") boolean keyOnly, @Param("limit") int limit);

    /** grid(UI 단계 3 ①) — 같은 조건 + 페이지·정렬(허용 목록) */
    List<ProfileRow> findProfilePage(@Param("plantCd") String plantCd, @Param("keyword") String keyword,
            @Param("criticality") String criticality, @Param("keyOnly") boolean keyOnly, @Param("page") PageQuery page);

    long countProfiles(@Param("plantCd") String plantCd, @Param("keyword") String keyword,
            @Param("criticality") String criticality, @Param("keyOnly") boolean keyOnly);

    ProfileRow findProfile(@Param("equipId") long equipId);

    int insertProfile(@Param("equipId") long equipId, @Param("criticality") String criticality,
            @Param("keyEquipYn") String keyEquipYn, @Param("ownerUserId") Long ownerUserId, @Param("idealCtSec") java.math.BigDecimal idealCtSec,
            @Param("remark") String remark, @Param("by") String by);

    int updateProfile(@Param("equipId") long equipId, @Param("criticality") String criticality,
            @Param("keyEquipYn") String keyEquipYn, @Param("ownerUserId") Long ownerUserId, @Param("idealCtSec") java.math.BigDecimal idealCtSec,
            @Param("remark") String remark, @Param("rowVer") int rowVer, @Param("by") String by);

    List<InstalledAsset> findInstalledAssets(@Param("equipId") long equipId);

    // ---------- 작업지시 대상 ----------

    List<TargetRow> findTargets(@Param("keyword") String keyword, @Param("plantCd") String plantCd,
            @Param("withAssets") boolean withAssets, @Param("limit") int limit);

    TargetRow findEquipTarget(@Param("code") String code);

    /** POP 고장 신고(C20) */
    TargetRow findEquipTargetById(@Param("id") long id);

    List<Symptom> findPopSymptoms(@Param("equipId") long equipId);

    boolean popWorkerActive(@Param("workerId") long workerId);

    /** code = 단말 코드, name = 단말 공장 */
    Option findPopTerminal(@Param("terminalId") long terminalId);

    String findMwoNoByReportReq(@Param("reqId") String reqId);

    /** POP 사진 대상(C25) — 작업지시 번호로 행 잠금(같은 신고의 동시 사진 줄 세우기) */
    io.smarthappy.module.cms.CmsDtos.PopPhotoTarget lockPopPhotoTarget(@Param("mwoNo") String mwoNo);

    TargetRow findAssetTarget(@Param("code") String code);

    // ---------- 작업지시 ----------

    List<MwoRow> findMwos(@Param("c") MwoCrit c, @Param("limit") int limit);

    /** grid(UI 단계 3 ①) — 같은 조건 + 페이지·정렬(허용 목록) */
    List<MwoRow> findMwoPage(@Param("c") MwoCrit c, @Param("page") PageQuery page);

    long countMwos(@Param("c") MwoCrit c);

    MwoRow findMwo(@Param("id") long id);

    Long lockMwo(@Param("id") long id);

    Long lockMwoShare(@Param("id") long id);

    /** 첨부 잠금(C13) — "유형:상태", 없으면 null */
    String mwoTypeStatus(@Param("id") long id);

    long insertMwo(@Param("m") MwoRow m, @Param("by") String by);

    int updateMwo(@Param("m") MwoRow m, @Param("closedBy") Long closedBy, @Param("by") String by);

    List<LaborRow> findLabor(@Param("mwoId") long mwoId);

    long insertLabor(@Param("mwoId") long mwoId, @Param("userId") long userId, @Param("workDate") LocalDate workDate,
            @Param("minutes") int minutes, @Param("remark") String remark, @Param("by") String by);

    int deleteLabor(@Param("mwoId") long mwoId, @Param("laborId") long laborId);
}
