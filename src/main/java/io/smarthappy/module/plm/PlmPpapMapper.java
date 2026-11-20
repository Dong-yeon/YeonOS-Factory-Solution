package io.smarthappy.module.plm;

import java.time.LocalDate;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import io.smarthappy.module.plm.PlmDtos.PcnRow;
import io.smarthappy.module.plm.PlmDtos.PpapElemRow;
import io.smarthappy.module.plm.PlmDtos.PpapRow;

/** PPAP 패키지·요소, 고객 통지 PCN (PLM 4단계, docs/39 14.4·14.5). 회사 조건은 RLS */
@Mapper
public interface PlmPpapMapper {

    // ---------- PPAP ----------

    PpapRow findPpapOfEco(@Param("ecoId") long ecoId);

    PpapRow findPpap(@Param("ppapId") long ppapId);

    PpapRow lockPpap(@Param("ppapId") long ppapId);

    List<PpapElemRow> findElems(@Param("ppapId") long ppapId);

    long insertPpap(@Param("ecoId") long ecoId, @Param("custId") long custId, @Param("level") int level,
            @Param("remark") String remark, @Param("by") String by);

    void insertElem(@Param("ppapId") long ppapId, @Param("elemNo") int elemNo, @Param("reqCd") String reqCd,
            @Param("by") String by);

    int updatePpap(@Param("ppapId") long ppapId, @Param("rowVer") int rowVer, @Param("level") int level,
            @Param("remark") String remark, @Param("by") String by);

    int updateElem(@Param("ppapId") long ppapId, @Param("elemNo") int elemNo, @Param("reqCd") String reqCd,
            @Param("status") String status, @Param("fileId") Long fileId, @Param("note") String note, @Param("by") String by);

    /** 요소 6·7 의 APQP 근거(docs/39 38장) — 셋 다 null = 끊기. 같은 값이면 0 */
    int updateElemApqp(@Param("ppapId") long ppapId, @Param("elemNo") int elemNo, @Param("kind") String kind, @Param("revId") Long revId,
            @Param("label") String label, @Param("by") String by);

    /** 지시 대상 품목(종류 무관, 중복 제거) — 품번 순. APQP 후보의 범위(38장 Q1) */
    List<io.smarthappy.module.plm.PlmDtos.PpapApqpItem> ecoTargetItems(@Param("ecoId") long ecoId);

    int submitPpap(@Param("ppapId") long ppapId, @Param("rowVer") int rowVer, @Param("on") LocalDate on, @Param("by") String by);

    int decidePpap(@Param("ppapId") long ppapId, @Param("rowVer") int rowVer, @Param("status") String status,
            @Param("on") LocalDate on, @Param("ref") String ref, @Param("note") String note, @Param("by") String by);

    int reopenPpap(@Param("ppapId") long ppapId, @Param("rowVer") int rowVer, @Param("by") String by);

    /** PPAP 승인 → ECO 고객 승인 (ECO 가 고객 승인 필요·같은 고객·적용 전일 때만) */
    int syncEcoCustApproval(@Param("ecoId") long ecoId, @Param("custId") long custId, @Param("ref") String ref,
            @Param("on") LocalDate on, @Param("by") String by);

    // ---------- PCN ----------

    List<PcnRow> findPcns(@Param("ecoId") long ecoId, @Param("today") LocalDate today);

    PcnRow lockPcn(@Param("pcnId") long pcnId);

    long insertPcn(@Param("no") String no, @Param("ecoId") long ecoId, @Param("custId") long custId,
            @Param("notifiedOn") LocalDate notifiedOn, @Param("due") LocalDate due, @Param("note") String note,
            @Param("by") String by);

    int respondPcn(@Param("pcnId") long pcnId, @Param("rowVer") int rowVer, @Param("status") String status,
            @Param("on") LocalDate on, @Param("note") String note, @Param("by") String by);

    // ---------- PCN 메일 (docs/39 23장) ----------

    /** 통지 고객의 사용 중 담당자 중 메일이 있는 사람 — 대표 먼저, 이름 순 */
    List<io.smarthappy.module.plm.PlmDtos.PcnContact> findPcnContacts(@Param("pcnId") long pcnId);

    /** 메일 본문 재료 — 통지·지시·회사(이름·기본 언어) */
    io.smarthappy.module.plm.PlmDtos.PcnMailSource pcnMailSource(@Param("pcnId") long pcnId);

    long insertPcnMail(@Param("pcnId") long pcnId, @Param("contactId") long contactId, @Param("addr") String addr,
            @Param("nm") String nm, @Param("subject") String subject, @Param("outboxId") java.util.UUID outboxId,
            @Param("noticeFileId") Long noticeFileId, @Param("by") String by);

    /** 지시의 모든 통지 메일 이력 — 오래된 것부터 */
    List<io.smarthappy.module.plm.PlmDtos.PcnMailRow> findPcnMails(@Param("ecoId") long ecoId);

    // ---------- PPAP 제출 메일 (docs/39 37장) ----------

    /** 그 패키지 고객의 사용 중 담당자 중 메일이 있는 사람 — 대표 먼저, 이름 순 */
    List<io.smarthappy.module.plm.PlmDtos.PcnContact> findPpapContacts(@Param("ppapId") long ppapId);

    io.smarthappy.module.plm.PlmDtos.PpapMailSource ppapMailSource(@Param("ppapId") long ppapId);

    long insertPpapMail(@Param("ppapId") long ppapId, @Param("contactId") long contactId, @Param("addr") String addr,
            @Param("nm") String nm, @Param("subject") String subject, @Param("outboxId") java.util.UUID outboxId,
            @Param("summaryFileId") long summaryFileId, @Param("by") String by);

    List<io.smarthappy.module.plm.PlmDtos.PpapMailRow> findPpapMails(@Param("ppapId") long ppapId);
}
