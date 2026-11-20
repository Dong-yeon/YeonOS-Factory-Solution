package io.smarthappy.module.qms.link;

import java.util.List;

import org.springframework.beans.factory.ObjectProvider;

/**
 * QMS 관리문서 쓰기 계약 (docs/29_P3 20.2, 사용자 결정 Q-t) — 다른 모듈(PMS APQP 산출물)이 산출물을 QMS 관리문서의 **작성 중(DRAFT) 리비전**으로 올릴 때.
 * - 결재를 건너뛰지 않는다: 만드는 것은 DRAFT 까지. 결재(정책 QMS_DOC_APPROVAL)·배포는 QMS 화면의 사람이 한다. 상태는 {@link DocLookup#rev} 로 읽는다
 * - 부르는 쪽 트랜잭션 안(MANDATORY). **쓰기 계약이라 거부는 예외**(BusinessException — 칸 오류 또는 상태 오류): 부르는 쪽 저장도 함께 실패해야 한다
 * - 멱등: 같은 sourceKey 로 다시 부르면 처음 만든 리비전을 그대로 돌려준다(created = false, 파일도 다시 붙이지 않음)
 *   → **sourceKey 는 원본의 리비전마다 달라야 한다**(예 {@code APQP:12:r3}). 같은 키로 내용(파일·요약)을 바꿔 불러도 새 리비전이 생기지 않고
 *   처음 리비전을 돌려준다 — 원본이 바뀌면 키에 원본 리비전을 넣어 새 키로 부른다
 * - 파일은 내용을 받아 QMS 가 그 리비전(qms-doc × docRevId)에 붙인다 — 확장자·크기(회사 한도)·시그니처 검사는 플랫폼 첨부 규칙 그대로
 * - 권한(qms-doc SAVE 등)은 부르는 쪽이 확인한다. 행위자 = 로그인 사용자
 * QMS 가 없는 설치본은 {@link #NONE} — 올리기는 IllegalStateException(조용히 성공한 척하지 않는다).
 */
public interface DocPort {

    /** 한 번에 받는 파일 상한 */
    int MAX_FILES = 20;

    /** 파일 하나 — 이름(확장자 포함)과 내용 */
    record DocFile(String fileName, byte[] content) {
    }

    /**
     * @param sourceKey     부르는 쪽 키(필수, 영문·숫자로 시작, 영문·숫자·{@code :._-} 100자 안) — 같은 키 = 같은 리비전(내용이 달라도), 원본 리비전마다 다른 키
     * @param docNo         기존 문서 번호(대소문자 무시) — 없으면 새 문서(번호는 QMS 채번)
     * @param docType       새 문서일 때 문서 유형(WI·INSP_STD·CP·PFMEA·DFMEA·DRAWING·OTHER) — 기존 문서면 무시하지 않고 같아야 한다
     * @param docNm         새 문서일 때 이름(200자 안)
     * @param itemId        문서 대상 품목(선택) — 새 문서는 대상으로 걸고, 기존 문서는 그 품목이 대상에 없으면 더한다
     * @param changeSummary 리비전 변경 요약(1,000자 안, 선택)
     * @param files         붙일 파일(0~{@link #MAX_FILES})
     */
    record DocDraft(String sourceKey, String docNo, String docType, String docNm, Long itemId, String changeSummary, List<DocFile> files) {
    }

    /** created = 이번 호출이 만들었나(false = 같은 sourceKey 로 이미 있던 것) */
    record DocRevRef(long docId, String docNo, long docRevId, String rev, String status, boolean created) {
    }

    DocPort NONE = d -> {
        throw new IllegalStateException("QMS 없음 — 관리문서를 올릴 수 없음");
    };

    DocRevRef publish(DocDraft draft);

    static DocPort of(ObjectProvider<DocPort> provider) {
        return provider.getIfAvailable(() -> NONE);
    }
}
