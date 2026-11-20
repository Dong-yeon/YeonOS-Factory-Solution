package io.smarthappy.module.admin;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import io.smarthappy.module.admin.SettingsDtos.CodeForm;
import io.smarthappy.module.admin.SettingsDtos.CodeGroupRow;
import io.smarthappy.module.admin.SettingsDtos.CompCodeRow;
import io.smarthappy.module.admin.SettingsDtos.CompForm;
import io.smarthappy.module.admin.SettingsDtos.CompRow;
import io.smarthappy.module.admin.SettingsDtos.DeptForm;
import io.smarthappy.module.admin.SettingsDtos.DeptRow;
import io.smarthappy.module.admin.SettingsDtos.PolicyRow;
import io.smarthappy.module.admin.SettingsDtos.PolicyValueRow;
import io.smarthappy.module.admin.SettingsDtos.RuleForm;
import io.smarthappy.module.admin.SettingsDtos.RuleRow;

/** 시스템관리 2단계(docs/36 9장). 회사별 표는 RLS 범위 트랜잭션 안에서 */
@Mapper
public interface SettingsMapper {

    // ---------- 부서 ----------

    List<DeptRow> findDepts();

    DeptRow findDept(@Param("deptId") long deptId);

    boolean existsDeptCd(@Param("deptCd") String deptCd);

    long insertDept(@Param("f") DeptForm f, @Param("by") String by);

    int updateDept(@Param("deptId") long deptId, @Param("f") DeptForm f, @Param("by") String by);

    // ---------- 정책 ----------

    List<PolicyRow> findPolicyDefs();

    List<PolicyValueRow> findPolicyValues();

    PolicyValueRow findPolicyValue(@Param("policyKey") String policyKey, @Param("plantCd") String plantCd);

    int deletePolicyValue(@Param("policyKey") String policyKey, @Param("plantCd") String plantCd,
            @Param("rowVer") int rowVer);

    List<String> findPlantCds();

    // ---------- 회사 공통코드 ----------

    List<CodeGroupRow> findExtGroups();

    List<CompCodeRow> findGroupCodes(@Param("grpCd") String grpCd);

    /** grid(UI 단계 3 ①) — 목록과 같은 그룹(시스템 코드 + 회사 코드) + 페이지·정렬(허용 목록) */
    List<CompCodeRow> findGroupCodePage(@Param("grpCd") String grpCd, @Param("page") io.smarthappy.platform.common.query.PageQuery page);

    long countGroupCodes(@Param("grpCd") String grpCd);

    CompCodeRow findCompCode(@Param("grpCd") String grpCd, @Param("code") String code);

    int insertCompCode(@Param("grpCd") String grpCd, @Param("f") CodeForm f, @Param("attrs") String attrs,
            @Param("by") String by);

    int updateCompCode(@Param("grpCd") String grpCd, @Param("f") CodeForm f, @Param("attrs") String attrs,
            @Param("by") String by);

    /** 시스템 코드 한 개 + 이 회사 숨김 여부(docs/68 Q4) */
    CompCodeRow findSystemCode(@Param("grpCd") String grpCd, @Param("code") String code);

    int hideSystemCode(@Param("grpCd") String grpCd, @Param("code") String code, @Param("by") String by);

    int unhideSystemCode(@Param("grpCd") String grpCd, @Param("code") String code);

    // ---------- 채번 ----------

    List<RuleRow> findRules();

    /** grid(UI 단계 3 ①) — 검색어(코드·이름) + 페이지·정렬(허용 목록) */
    List<RuleRow> findRulePage(@Param("keyword") String keyword, @Param("page") io.smarthappy.platform.common.query.PageQuery page);

    long countRules(@Param("keyword") String keyword);

    RuleRow findRule(@Param("ruleCd") String ruleCd);

    Long findLastNo(@Param("ruleCd") String ruleCd, @Param("periodKey") String periodKey);

    int insertRule(@Param("f") RuleForm f, @Param("by") String by);

    int updateRule(@Param("f") RuleForm f, @Param("by") String by);

    // ---------- 회사 정보 ----------

    CompRow findComp();

    int updateComp(@Param("f") CompForm f, @Param("by") String by);
}
