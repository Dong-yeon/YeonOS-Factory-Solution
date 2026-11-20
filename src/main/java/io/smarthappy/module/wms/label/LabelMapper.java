package io.smarthappy.module.wms.label;

import java.util.List;
import java.util.UUID;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import io.smarthappy.module.wms.label.LabelDtos.JobRow;
import io.smarthappy.module.wms.label.LabelDtos.JobSearch;
import io.smarthappy.module.wms.label.LabelDtos.PrinterForm;
import io.smarthappy.module.wms.label.LabelDtos.PrinterRow;
import io.smarthappy.module.wms.label.LabelDtos.RuleForm;
import io.smarthappy.module.wms.label.LabelDtos.RuleRow;
import io.smarthappy.module.wms.label.LabelDtos.TplForm;
import io.smarthappy.module.wms.label.LabelDtos.TplRow;

@Mapper
public interface LabelMapper {

    // ---- 프린터
    List<PrinterRow> findPrinters(@Param("plantCd") String plantCd, @Param("useYn") String useYn);

    List<PrinterRow> findPrinterPage(@Param("plantCd") String plantCd, @Param("useYn") String useYn,
            @Param("page") io.smarthappy.platform.common.query.PageQuery page);

    long countPrinters(@Param("plantCd") String plantCd, @Param("useYn") String useYn);

    PrinterRow findPrinter(@Param("id") long id);

    long insertPrinter(@Param("f") PrinterForm f, @Param("by") String by);

    int updatePrinter(@Param("id") long id, @Param("f") PrinterForm f, @Param("by") String by);

    boolean plantActive(@Param("plantCd") String plantCd);

    // ---- 템플릿
    List<TplRow> findTpls(@Param("labelType") String labelType, @Param("useYn") String useYn);

    List<TplRow> findTplPage(@Param("labelType") String labelType, @Param("useYn") String useYn,
            @Param("page") io.smarthappy.platform.common.query.PageQuery page);

    long countTpls(@Param("labelType") String labelType, @Param("useYn") String useYn);

    TplRow findTpl(@Param("id") long id);

    TplRow findDefaultTpl(@Param("labelType") String labelType);

    long insertTpl(@Param("f") TplForm f, @Param("by") String by);

    int updateTpl(@Param("id") long id, @Param("f") TplForm f, @Param("by") String by);

    /** 같은 유형의 다른 기본 템플릿을 내린다 */
    int clearDefault(@Param("labelType") String labelType, @Param("exceptId") Long exceptId, @Param("by") String by);

    // ---- 인쇄 작업
    long insertJob(@Param("plantCd") String plantCd, @Param("printerId") long printerId, @Param("tplId") long tplId,
            @Param("labelType") String labelType, @Param("bizKey") String bizKey, @Param("valuesJson") String valuesJson,
            @Param("zpl") String zpl, @Param("sha") String sha, @Param("copies") int copies, @Param("testYn") String testYn,
            @Param("messageId") UUID messageId, @Param("reprintOf") Long reprintOf, @Param("reason") String reason,
            @Param("by") String by, @Param("connMode") String connMode);

    /** 인쇄 에이전트 후보(사용 중 COLLECTOR 장치) */
    List<java.util.Map<String, Object>> agentOptions(@Param("offlineSec") int offlineSec);

    boolean agentActive(@Param("id") long id);

    List<JobRow> findJobs(@Param("q") JobSearch q);

    long countJobs(@Param("q") JobSearch q);

    JobRow findJob(@Param("id") long id);

    // ---- 템플릿 선택 규칙 (docs/65 18.4)
    List<RuleRow> findRules(@Param("labelType") String labelType, @Param("useYn") String useYn);

    RuleRow findRule(@Param("id") long id);

    long insertRule(@Param("f") RuleForm f, @Param("by") String by);

    int updateRule(@Param("id") long id, @Param("f") RuleForm f, @Param("by") String by);

    boolean custActive(@Param("id") long id);

    boolean itemActive(@Param("id") long id);

    Long ruleTplId(@Param("labelType") String labelType, @Param("custId") Long custId, @Param("itemId") Long itemId);
}
