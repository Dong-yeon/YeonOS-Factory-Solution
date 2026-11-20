package io.smarthappy.module.qms.link;

import java.util.Optional;

import org.springframework.beans.factory.ObjectProvider;

/**
 * QMS 시정조치(8D) 계약 (docs/29_P3 14장) — 다른 모듈(PMS 이슈 "시정조치로 올리기")이 8D 를 만들고 번호·상태를 읽을 때.
 * - {@link #createInternal} 은 부르는 쪽 트랜잭션 안(MANDATORY). 입력이 틀리면 칸 오류(BusinessException) — 부르는 쪽 저장도 함께 실패해야 한다
 * - {@link #capa} 는 읽기만·예외 없음(없거나 다른 회사면 빈 값)
 * - 권한(qms-capa SAVE)은 부르는 쪽이 확인한다(이 계약은 화면 권한을 보지 않는다)
 * QMS 가 없는 설치본은 {@link #NONE} — 만들기는 IllegalStateException(조용히 0 을 돌려주면 "만들었다" 로 착각한다).
 */
public interface CapaPort {

    /** 8D 요약 — status = OPEN·CONTAINED·ANALYZED·ACTIONED·VERIFIED·CLOSING·CLOSED·CANCELLED */
    record CapaView(long capaId, String capaNo, String title, String status) {
    }

    /**
     * 사내(INTERNAL) 출처 8D 만들기 요청 — itemCd 는 선택(없거나 다른 회사 품목이면 칸 오류), problem = D2 문제 서술(4,000자 안), owner = 담당자 사용자 id
     */
    record InternalRequest(String title, String itemCd, long ownerUserId, String problem) {
    }

    CapaPort NONE = new CapaPort() {
        @Override
        public long createInternal(InternalRequest req) {
            throw new IllegalStateException("QMS 없음 — 시정조치를 만들 수 없음");
        }

        @Override
        public Optional<CapaView> capa(long capaId) {
            return Optional.empty();
        }
    };

    /** 만들고 capa_id 를 돌려준다(번호 채번·기한 계산은 QMS 규칙 그대로) */
    long createInternal(InternalRequest req);

    Optional<CapaView> capa(long capaId);

    static CapaPort of(ObjectProvider<CapaPort> provider) {
        return provider.getIfAvailable(() -> NONE);
    }
}
