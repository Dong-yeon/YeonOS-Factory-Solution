package io.smarthappy.module.apqp.link;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.ObjectProvider;

/**
 * APQP 조회 계약 (docs/41 11장 A3-1) — 다른 모듈(PMS 산출물 연결)이 PFMEA·관리계획서 리비전을 읽을 때. APQP 서비스·표를 직접 부르지 않는다.
 * 읽기만·잠금 없음·**예외 없음**(없거나 다른 회사·모르는 종류면 빈 값 — 부르는 쪽 트랜잭션을 rollback-only 로 만들지 않는다).
 * APQP 가 없는 설치본은 {@link #NONE}.
 */
public interface ApqpLookup {

    /** 종류 — PFMEA·관리계획서 */
    String FMEA = "FMEA";
    String CP = "CP";

    /**
     * 리비전 요약. status = DRAFT·IN_APPROVAL·RELEASED·OBSOLETE, releasedAt = 확정 시각(확정 전 null).
     * everReleased = 한 번이라도 확정됨(RELEASED·OBSOLETE — 산출물 승인 기준, docs/41 L2)
     */
    record RevView(String kind, long id, String no, int rev, String title, String status, OffsetDateTime releasedAt) {

        public boolean everReleased() {
            return "RELEASED".equals(status) || "OBSOLETE".equals(status);
        }
    }

    /**
     * PFMEA 조치 한 건(docs/41 11.7 A3-5 — PMS 이슈 만들기). status = OPEN·DONE·CANCELLED, fmeaStatus = 그 리비전 상태,
     * projId = FMEA 의 프로젝트(없으면 null), fm·fc = 위험행의 고장 형태·원인 글, ap = 위험행 AP(H·M·L, 없으면 null)
     */
    record ActionView(long actionId, long fmeaId, String fmeaNo, int rev, String fmeaStatus, Long projId, long itemId, String status,
            String actionKind, String actionText, Long ownerUserId, LocalDate dueDate, String fm, String fc, String ap) {
    }

    /**
     * 품목의 현행 확정본 한 건(docs/41 11.11 A3-6 — PLM PPAP 요소 6 공정 FMEA·7 관리계획서의 근거 후보). rev.status 는 늘 RELEASED.
     * phase = 관리계획서 단계(PROTO·PRE_LAUNCH·PRODUCTION, PFMEA 는 null), docRevId = 이 리비전을 올린 QMS 관리문서 리비전(A3-3, 안 올렸으면 null —
     * 상태는 QMS DocLookup 으로)
     */
    record CurrentView(RevView rev, String phase, Long docRevId) {
    }

    /** 조회 없음 — APQP 모듈이 없는 설치본(빈 없음) */
    ApqpLookup NONE = new ApqpLookup() {
        @Override
        public Optional<RevView> rev(String kind, long id) {
            return Optional.empty();
        }

        @Override
        public List<RevView> revsByNo(String kind, String no) {
            return List.of();
        }
    };

    /** 리비전 한 건 — 없는·다른 회사 id·모르는 종류는 빈 값 */
    Optional<RevView> rev(String kind, long id);

    /** 번호(대소문자 무시)의 리비전 전부, 최근 것부터. 없으면 빈 목록 */
    List<RevView> revsByNo(String kind, String no);

    /** 조치 한 건 — 없는·다른 회사 id 는 빈 값. 기본 구현(APQP 없음)은 빈 값 */
    default Optional<ActionView> action(long actionId) {
        return Optional.empty();
    }

    /**
     * 그 품목의 <b>현행 확정(RELEASED)</b> PFMEA·관리계획서 — 번호마다 하나(같은 번호의 확정본은 늘 하나). 순서 = PFMEA(번호) → 관리계획서(단계
     * PROTO·PRE_LAUNCH·PRODUCTION → 번호). 작성 중·결재 중·폐기는 넣지 않는다. 없는·다른 회사 품목 = 빈 목록. 기본 구현(APQP 없음)은 빈 목록
     */
    default List<CurrentView> current(long itemId) {
        return List.of();
    }

    /** 빈이 있으면 그것, 없으면 {@link #NONE} */
    static ApqpLookup of(ObjectProvider<ApqpLookup> provider) {
        return provider.getIfAvailable(() -> NONE);
    }
}
