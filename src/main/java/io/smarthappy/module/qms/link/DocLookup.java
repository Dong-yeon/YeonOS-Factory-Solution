package io.smarthappy.module.qms.link;

import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.ObjectProvider;

/**
 * QMS 관리문서 조회 계약 (docs/29_P3 14장) — 다른 모듈(PMS 산출물 문서 연결)이 QMS 문서·리비전을 읽을 때. QMS 서비스·표를 직접 부르지 않는다.
 * 읽기만·잠금 없음·**예외 없음**(없거나 다른 회사면 빈 값 — 부르는 쪽 트랜잭션을 rollback-only 로 만들지 않는다).
 * QMS 가 없는 설치본은 {@link #NONE}.
 */
public interface DocLookup {

    /** 리비전 요약 — status = DRAFT·IN_APPROVAL·RELEASED·OBSOLETE */
    record DocRevView(long docRevId, long docId, String docNo, String docNm, String rev, String status) {
    }

    /** 조회 없음 — QMS 모듈이 없는 설치본(빈 없음) */
    DocLookup NONE = new DocLookup() {
        @Override
        public Optional<DocRevView> rev(long docRevId) {
            return Optional.empty();
        }

        @Override
        public List<DocRevView> revsByDocNo(String docNo) {
            return List.of();
        }
    };

    /** 리비전 한 건 — 없는·다른 회사 id 는 빈 값 */
    Optional<DocRevView> rev(long docRevId);

    /** 문서 번호(대소문자 무시, 사용 중 문서만)의 리비전 전부, 최근 것부터. 없으면 빈 목록 */
    List<DocRevView> revsByDocNo(String docNo);

    /** 빈이 있으면 그것, 없으면 {@link #NONE} */
    static DocLookup of(ObjectProvider<DocLookup> provider) {
        return provider.getIfAvailable(() -> NONE);
    }
}
