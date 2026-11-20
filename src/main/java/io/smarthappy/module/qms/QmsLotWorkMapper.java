package io.smarthappy.module.qms;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 출하 추적 화면의 QMS 상태(docs/29_P3 37장) — QMS 표만 읽는다. 회사 조건은 RLS, 잠금 없음 */
@Mapper
public interface QmsLotWorkMapper {

    /** LOT 하나의 QMS 일 한 건 — kind NCR(열린 부적합 OPEN·PENDING) / CONTAIN(해제 안 된 봉쇄 LOT, 봉쇄 OPEN) */
    record LotWork(long lotId, String kind, String no) {
    }

    /** 열린 부적합 + 걸린 봉쇄 — ids 는 500개까지(부르는 쪽이 나눔). 순서 = lot_id → 종류 → 번호 */
    List<LotWork> findOpenWork(@Param("ids") List<Long> ids);
}
