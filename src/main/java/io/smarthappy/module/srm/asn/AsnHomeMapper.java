package io.smarthappy.module.srm.asn;

import java.time.LocalDate;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 홈 카드 집계 (docs/37 23장) — 늦은 ASN. 회사 조건은 RLS. "늦음" = ASN 목록의 "늦은 ASN 만"과 같은 정의:
 * 보냄·입고 중 + 덜 받은 행 > 0 + 도착 예정일 &lt; 회사 오늘
 */
@Mapper
public interface AsnHomeMapper {

    AsnLateSummary asnLateSummary(@Param("from") LocalDate from, @Param("today") LocalDate today);

    List<AsnLateRow> asnLateTop(@Param("today") LocalDate today, @Param("limit") int limit);

    /** lateCnt = 창(from ~ 어제) 안, olderCnt = 창보다 오래 늦음, todayCnt = 오늘 도착 예정(아직 안 늦음) */
    record AsnLateSummary(long lateCnt, long olderCnt, long todayCnt) {
    }

    record AsnLateRow(String asnNo, String custNm, String plantCd, LocalDate etaDate, int openLineCnt) {
    }
}
