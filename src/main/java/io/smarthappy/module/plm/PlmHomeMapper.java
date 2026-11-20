package io.smarthappy.module.plm;

import java.time.LocalDate;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 홈 카드 집계 (docs/39 20.6) — 적용 대기 ECO, 고객 응답 대기(PPAP·PCN). 회사 조건은 RLS */
@Mapper
public interface PlmHomeMapper {

    /** 승인됐는데 적용 안 한 ECO — 목록 status=APPROVED 와 같은 조건 */
    ApprovedSummary approvedSummary(@Param("today") LocalDate today);

    List<ApprovedRow> approvedTop(@Param("limit") int limit);

    record ApprovedSummary(long approvedCnt, long pastCnt, long custWaitCnt, long stockOutCnt) {
    }

    record ApprovedRow(String ecoNo, String title, LocalDate effectiveDate) {
    }

    /** PPAP 판정 대기(SUBMITTED) + PCN 응답 기한 지남(SENT, 기한 < 오늘) — 취소된 ECO 것은 빼고 */
    CustWaitSummary custWaitSummary(@Param("today") LocalDate today);

    List<CustWaitRow> custWaitTop(@Param("today") LocalDate today, @Param("limit") int limit);

    record CustWaitSummary(long ppapCnt, long pcnOverdueCnt) {
    }

    /** kind = PCN·PPAP, no = PCN 번호 또는 ECO 번호(PPAP 는 번호가 없다), day = PCN 응답 기한 또는 PPAP 제출일 */
    record CustWaitRow(String kind, String no, String ecoNo, String custCd, LocalDate day) {
    }

    // ---------- 알림 (docs/39 26장) — 홈 카드와 같은 조건, 받는 사람 = 지시 담당

    /** 승인됐는데 유효일이 지난 ECO(적용 안 함) — 카드 1 의 "유효일 지남" 과 같은 조건 */
    List<OverdueEco> overdueEcos(@Param("today") LocalDate today, @Param("limit") int limit);

    /** 응답 기한이 지난 열린 PCN(취소된 ECO 제외) — 카드 2 의 "PCN 기한 지남" 과 같은 조건 */
    List<OverduePcn> overduePcns(@Param("today") LocalDate today, @Param("limit") int limit);

    /** 응답 기한이 오늘 ~ until 인 열린 PCN(취소된 ECO 제외) — 기한 임박 알림(35장), 지남(overduePcns)의 바로 앞 */
    List<OverduePcn> dueSoonPcns(@Param("today") LocalDate today, @Param("until") LocalDate until, @Param("limit") int limit);

    record OverdueEco(long ecoId, String ecoNo, String title, LocalDate effectiveDate, long ownerId) {
    }

    record OverduePcn(long pcnId, String pcnNo, String ecoNo, String custCd, LocalDate responseDue, long ownerId) {
    }
}
