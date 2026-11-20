package io.smarthappy.module.srm.link;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.ObjectProvider;

/**
 * 사전 납품 통지(ASN) 조회 계약 (docs/31 4.11, SRM 2부 R3) — WMS 입고 화면이 ASN 을 골라 칸을 채울 때. SRM 표를 직접 읽지 않는다.
 * 입고 자체는 지금처럼 {@link PoReceiptGate}(ASN 행을 고르면 asnLineId 오버로드)로 — ASN 행 1개 = 입고 요청 1개.
 *
 * - 같은 회사만(RLS), 부르는 쪽 트랜잭션 안 읽기 전용
 * - 대상 상태 = 확정(SENT)·입고 중(PARTIAL_RECEIVED)·다 받음(RECEIVED — 더 받는 것은 막지 않음, R3-4). 작성 중·마감·취소는 빈 값
 * - 수량은 발주 단위(발주 행 스냅샷), 받은 양 = 그 ASN 행으로 들어온 입고 이력 합
 * - SRM 이 없는 설치본은 {@link #NONE}(빈 값)
 */
public interface AsnQuery {

    /** 한 번에 돌려주는 머리 상한 — 넘으면 앞 500개(도착 예정일·번호 순) */
    int MAX_HEADS = 500;

    /** 스캔 키 접두어 — 납품서 인쇄 바코드도 이 모양(WMS 합의 2026-10-22) */
    String SCAN_PREFIX = "ASN:";

    record AsnHead(long asnId, String asnNo, long custId, String custCd, String custNm, String plantCd, String supplierRef,
            LocalDate shipDate, LocalDate etaDate, String status, int lineCnt, int openLineCnt) {
    }

    /**
     * @param remainQty ASN 수량 − 받은 양(0 아래면 0) — 입고 화면 기본 수량
     */
    record AsnLine(long asnLineId, int lineNo, long poLineId, long poId, String poNo, int poLineNo, long itemId, String itemCd,
            String itemNm, String unitCd, BigDecimal qty, BigDecimal recvQty, BigDecimal remainQty, String supplierLotNo,
            LocalDate mfgDate, LocalDate expiryDate) {
    }

    record Asn(AsnHead head, List<AsnLine> lines) {
    }

    /** 조회 없음 — SRM 모듈이 없는 설치본(빈 없음) */
    AsnQuery NONE = new AsnQuery() {
        @Override
        public List<AsnHead> expected(String plantCd, LocalDate fromEta, LocalDate toEta) {
            return List.of();
        }

        @Override
        public Optional<Asn> find(String key) {
            return Optional.empty();
        }
    };

    /**
     * 공장의 도착 예정·입고 중 ASN(남은 행 수 &gt; 0 인 것만). fromEta·toEta 포함, null = 끝 없음. 순서 = 도착 예정일·번호
     */
    List<AsnHead> expected(String plantCd, LocalDate fromEta, LocalDate toEta);

    /**
     * ASN 번호(앞의 {@value #SCAN_PREFIX} 는 떼고 봄) 또는 협력사 납품서 번호로 하나. 번호가 먼저, 납품서 번호가 여러 협력사에 겹치면 빈 값
     * (협력사를 모르는 검색 칸에서 엉뚱한 ASN 을 고르지 않게). 없거나 다른 회사·대상 아닌 상태면 빈 값
     */
    Optional<Asn> find(String key);

    /** 빈이 있으면 그것, 없으면 {@link #NONE} */
    static AsnQuery of(ObjectProvider<AsnQuery> provider) {
        return provider.getIfAvailable(() -> NONE);
    }
}
