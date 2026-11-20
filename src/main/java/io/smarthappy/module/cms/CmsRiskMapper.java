package io.smarthappy.module.cms;

import java.time.LocalDate;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import io.smarthappy.module.cms.CmsRiskDtos.ItemRow;
import io.smarthappy.module.cms.CmsRiskDtos.MemberRow;
import io.smarthappy.module.cms.CmsRiskDtos.RaRow;
import io.smarthappy.module.cms.CmsRiskDtos.WorkerOption;
import io.smarthappy.platform.common.query.PageQuery;

/** CMS 1부 C6 (docs/33 4.7) */
@Mapper
public interface CmsRiskMapper {

    List<RaRow> findRas(@Param("from") LocalDate from, @Param("to") LocalDate to, @Param("statuses") List<String> statuses,
            @Param("keyword") String keyword, @Param("today") LocalDate today, @Param("limit") int limit);

    /** grid(UI 단계 3 ①) — 같은 조건 + 페이지·정렬(허용 목록) */
    List<RaRow> findRaPage(@Param("from") LocalDate from, @Param("to") LocalDate to, @Param("statuses") List<String> statuses,
            @Param("keyword") String keyword, @Param("today") LocalDate today, @Param("page") PageQuery page);

    long countRas(@Param("from") LocalDate from, @Param("to") LocalDate to, @Param("statuses") List<String> statuses,
            @Param("keyword") String keyword);

    RaRow findRa(@Param("id") long id, @Param("today") LocalDate today);

    Long lockRa(@Param("id") long id);

    long insertRa(@Param("r") RaRow r, @Param("by") String by);

    int updateRa(@Param("r") RaRow r, @Param("by") String by);

    /** 상태 전이 (rowVer 가 있으면 확인) */
    int setStatus(@Param("id") long id, @Param("status") String status, @Param("aprId") Long aprId, @Param("released") boolean released,
            @Param("rowVer") Integer rowVer, @Param("by") String by);

    List<ItemRow> findItems(@Param("raId") long raId);

    ItemRow findItem(@Param("raId") long raId, @Param("itemId") long itemId);

    long insertItem(@Param("raId") long raId, @Param("i") ItemRow i, @Param("by") String by);

    int updateItem(@Param("raId") long raId, @Param("i") ItemRow i, @Param("by") String by);

    int progressItem(@Param("raId") long raId, @Param("i") ItemRow i, @Param("by") String by);

    /** 그 평가의 요인인지(1/0) — 요인 삭제 전 첨부를 지우기 전에 */
    int itemOfRa(@Param("raId") long raId, @Param("itemId") long itemId);

    int deleteItem(@Param("raId") long raId, @Param("itemId") long itemId);

    /** 이전 확정본 복사 — 평가 내용만(개선 진행 빼고) */
    int copyItems(@Param("fromRaId") long fromRaId, @Param("toRaId") long toRaId, @Param("by") String by);

    List<MemberRow> findMembers(@Param("raId") long raId);

    long insertMember(@Param("raId") long raId, @Param("m") MemberRow m, @Param("by") String by);

    /** 작업자(C19) — 기준정보 읽기만. 사용 중만, 코드순 */
    List<WorkerOption> findWorkers(@Param("limit") int limit);

    /** 코드로 사용 중 작업자 하나 — 없거나 사용 중지면 null */
    WorkerOption findWorker(@Param("workerCd") String workerCd);

    int notifyMember(@Param("raId") long raId, @Param("memberId") long memberId, @Param("notifiedOn") LocalDate notifiedOn,
            @Param("rowVer") Integer rowVer, @Param("by") String by);

    int deleteMember(@Param("raId") long raId, @Param("memberId") long memberId);

    /** 증빙 첨부 잠금(C12) — 요인의 평가 상태, 없으면 null */
    String itemRaStatus(@Param("itemId") long itemId);
}
