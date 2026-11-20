package io.smarthappy.module.cms.link;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.ObjectProvider;

/**
 * 예비품 재고 출고 계약 (docs/33 11.15, docs/30 12.8) — CMS 보전 작업지시 완료·반납이 부르고 WMS 가 구현한다.
 * CMS 는 사용 기록(cms_mwo_part)만 가지고, 수량·차감은 WMS 원장(1부 D9). WMS 는 CMS 표를 읽지 않으므로 필요한 값을 인자로 넘긴다.
 *
 * - 부르는 쪽 트랜잭션 안(MANDATORY). 잠금 = cms_mwo(완료 FOR UPDATE, 반납 FOR SHARE) 뒤에 WMS 가 LOT → 재고 칸 → 기간 — WMS 는 그 뒤 CMS 표를 잡지 않는다
 * - 공장 정책 WMS_MRO_LINK = N 이면 아무것도 안 함(기록만). 원장에 한 번도 들어온 적 없는 품목은 건너뜀(D33)
 * - 부족 409 INSUFFICIENT_STOCK·마감 달 409 PERIOD_CLOSED·반납 초과 409 WMS_MRO_RETURN_OVER 는 칸 오류 없는 예외 — 부르는 쪽이 그대로 던져
 *   그 트랜잭션 전체를 롤백(D31)
 * - 값은 CMS 그대로: occurredAt = cms_mwo.finished_at, qty = cms_mwo_part.qty(품목 기본 단위, 자릿수 6) — 환산 없음
 * - 화면 권한은 호출자 것(CMS cms-mwo SAVE)으로 본다 — 계약 안에서 WMS 화면 권한을 따로 검사하지 않는다(MES 훅과 같은 관례)
 * - WMS 가 없는 설치본은 {@link #NONE}
 */
public interface SpareIssueLink {

    /** 사용 예비품 줄 하나 — qty = 품목 기본 단위 */
    record PartIssue(long partId, long itemId, BigDecimal qty) {
    }

    /** 아무것도 안 함 — WMS 없는 설치본 */
    SpareIssueLink NONE = new SpareIssueLink() {
        @Override
        public void onMwoDone(long mwoId, String plantCd, Instant occurredAt, List<PartIssue> parts, String by) {
        }

        @Override
        public boolean onPartReturn(long partId, BigDecimal qty, String reason, String clientReqId, String by) {
            return false;
        }
    };

    /**
     * 출고 — 완료(DONE) 전이 직후, 같은 트랜잭션에서 한 번(D30). parts 가 비어 있으면 아무것도 안 함(CMS 는 분기 없이 넘긴다). 원장 ISSUE_MRO −, 출처 CMS_PART = partId, 멱등 키 CMS_PART:{partId}.
     *
     * @param plantCd    작업지시 공장(cms_mwo.plant_cd)
     * @param occurredAt 완료 시각(finished_at) — 원장 발생 시각
     * @param by         처리자 로그인 아이디
     */
    void onMwoDone(long mwoId, String plantCd, Instant occurredAt, List<PartIssue> parts, String by);

    /**
     * 반납 — 완료 뒤 덜 쓴 것(부분 가능, D32). 원장 MRO_RTN +(출고한 칸으로), 누적 반납 ≤ 출고는 WMS 가 검사(넘으면 409 WMS_MRO_RETURN_OVER).
     * 수량·사유·요청 id 형식이 틀리면 400 칸 오류(qty·reason·clientReqId).
     *
     * @param reason      2~500자
     * @param clientReqId 요청 id(8~64자) — 같은 id 로 다시 오면 처음 결과(두 번 누름 방지)
     * @return true = 반납 원장을 넣음(또는 같은 요청의 처음 결과), false = 그 줄의 출고 원장이 없음(스위치 꺼짐·원장 밖 품목 — 오류 아님, 할 일 없음)
     */
    boolean onPartReturn(long partId, BigDecimal qty, String reason, String clientReqId, String by);

    /**
     * 반납 누계 읽기 (C18, docs/33 11.15.2) — 줄마다 누적 반납 합(품목 기본 단위, MRO_RTN 출처 CMS_PART = partId).
     * 출고 원장이 있는 줄만 키를 넣는다(반납 없으면 0). <b>키 없음 = 모름</b>(WMS 없음·스위치 꺼짐·원장 밖 품목·완료 전) — CMS 화면은 0 으로 보이지 않는다.
     * 같은 회사만, 빈 입력 = 빈 맵, 예외 대신 빈 맵(읽기 실패가 작업지시 상세를 막지 않게). 잠금 없음.
     * 기본 구현 = 빈 맵(모름) — 구현이 덮어쓴다.
     */
    default Map<Long, BigDecimal> returnedQty(Collection<Long> partIds) {
        return Map.of();
    }

    /** 빈이 있으면 그것, 없으면 {@link #NONE} */
    static SpareIssueLink of(ObjectProvider<SpareIssueLink> provider) {
        return provider.getIfAvailable(() -> NONE);
    }
}
