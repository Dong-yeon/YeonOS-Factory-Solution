package io.smarthappy.module.cms.link;

import java.io.InputStream;
import java.util.List;

import org.springframework.beans.factory.ObjectProvider;

import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.HbErrorCode;

/**
 * POP 고장 신고 계약 (docs/33 11.16, C20) — MES POP 화면(작업자 배지)이 부르고 CMS 가 구현한다. 화면은 MES, 기록(작업지시 REQ)은 CMS.
 * MES 는 CMS 구현을 직접 의존하지 않고 이 계약만 본다({@link AlarmSink} 와 같은 모양).
 *
 * - POP 서비스 트랜잭션 안에서 부른다(MANDATORY) — CMS 채번·멱등(sys_inbox)·작업지시 행이 호출 쪽과 같은 트랜잭션. 예외면 호출 쪽이 그대로 던져 전체 롤백
 * - 설비는 id 로 받는다. MES 가 단말 공장 범위를 확인하고, CMS 도 다시 확인한다: 같은 회사·사용 중 설비, **단말 공장 = 설비 공장**(다르면 400 equipId)
 * - 신고자 = 작업자 id + 단말 id 따로(사무 계정 없음). 감사 행위자 = {@code t{단말 id}:{단말 코드}}(POP 관례)
 * - 입력 오류는 칸 오류 400(equipId·symptomCd·symptomText·workerId·terminalId·clientReqId) — MES 가 그대로 화면에 보인다
 * - 같은 clientReqId 재요청 = 처음 작업지시 번호({@link ReportResult#duplicate()} = true), 새로 만들지 않는다
 * - 공정·설비 상태 자동 연결 없음(1차) — 정지 기록은 보전 담당이 작업지시에서
 * - 구현이 없는 설치본은 {@link #NONE} — MES 는 {@link #of(ObjectProvider)} 가 NONE 이면 POP "고장 신고" 버튼을 숨긴다
 * - 사진(C25, docs/33 11.17): {@link #attachPhoto} — 신고한 단말만·신고(REQ)·접수(OPEN) 때만·이미지·크기·개수 한도(회사 정책)·같은 사진 재전송 = 처음 파일
 * - 사진 한도 미리 알기(C28, docs/25 4.44): {@link #photoLimits} — attachPhoto 판정과 같은 계산
 */
public interface BreakdownReport {

    /** 현상 선택지 하나 — 고장 코드(현상 유형) */
    record Symptom(String symptomCd, String symptomNm) {
    }

    /**
     * 신고 한 건.
     *
     * @param equipId     설비(단말 공장의 사용 중 설비)
     * @param symptomCd   현상 코드(선택 — 없으면 symptomText 필수)
     * @param symptomText 현상 글(선택, 200자 이하)
     * @param workerId    배지로 확인한 작업자(같은 회사·사용 중)
     * @param terminalId  POP 단말
     * @param clientReqId 단말이 누른 순간 만든 요청 id(8~64자, 영문·숫자·_.:-) — 재전송 멱등 키
     */
    record Report(long equipId, String symptomCd, String symptomText, long workerId, long terminalId, String clientReqId) {
    }

    /** reqNo = 작업지시 번호, duplicate = 같은 요청 id 로 이미 만든 것 */
    record ReportResult(String reqNo, boolean duplicate) {
    }

    /**
     * 신고 사진 한 장(C25).
     *
     * @param reqNo       신고로 받은 작업지시 번호
     * @param workerId    배지로 확인한 작업자(같은 회사·사용 중 — 신고자와 달라도 됨)
     * @param terminalId  POP 단말 — 그 신고를 한 단말이어야 한다
     * @param clientReqId 그 신고의 요청 id(다른 신고에 사진을 붙이지 않게)
     * @param fileName    원래 파일 이름(확장자로 형식 판단 — jpg·jpeg·png)
     * @param size        선언 크기(실제 바이트도 다시 잰다)
     * @param content     내용(부르는 쪽이 닫는다)
     */
    record Photo(String reqNo, long workerId, long terminalId, String clientReqId, String fileName, long size, InputStream content) {
    }

    /** fileId = 붙은(또는 이미 있던) 첨부, duplicate = 같은 내용이 이미 있어 그것을 돌려줬다, count = 이 단말이 붙인 사진 수 */
    record PhotoResult(long fileId, boolean duplicate, int count) {
    }

    /**
     * 사진 한도(C28) — POP 이 고르기 전에 알린다. attachPhoto 의 판정과 같은 계산(공용 첨부 {@code FileService.limitsOf}).
     *
     * @param maxCount 한 단말이 한 신고에 붙일 수 있는 사진 수(정책 CMS_POP_PHOTO_MAX)
     * @param maxBytes 한 장 최대 바이트 = min(FILE_MAX_MB, CMS_POP_PHOTO_MB) × 1MiB — 이 바이트까지 통과, 1 바이트 넘으면 거부
     * @param exts     받는 확장자 = {jpg, jpeg, png} ∩ 회사 FILE_ALLOWED_EXT — 소문자·점 없이·정렬
     */
    record PhotoLimits(int maxCount, long maxBytes, List<String> exts) {
    }

    /** 구현 없음 — 선택지 없음, 신고하면 409 CONFLICT(버튼이 숨겨져 있어야 한다), 사진 한도 = 0·0·없음 */
    BreakdownReport NONE = new BreakdownReport() {
        @Override
        public List<Symptom> symptoms(long equipId) {
            return List.of();
        }

        @Override
        public ReportResult report(Report report) {
            throw new BusinessException(HbErrorCode.CONFLICT);   // 설치본에 CMS 없음 — 버튼이 보이면 안 되는 상태
        }

        @Override
        public PhotoResult attachPhoto(Photo photo) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }

        @Override
        public PhotoLimits photoLimits() {
            return new PhotoLimits(0, 0, List.of());
        }
    };

    /** 그 설비에 고를 수 있는 현상 — 현상 유형·사용 중, 설비 클래스 한정 코드는 그 클래스 설비만. 정렬 순서 → 코드. 없는 설비 = 빈 목록 */
    List<Symptom> symptoms(long equipId);

    /** 신고 — 고장(BM) 작업지시를 신고(REQ) 상태로 만들고 번호를 돌려준다 */
    ReportResult report(Report report);

    /**
     * 신고 사진(C25) — 호출자(POP) 트랜잭션 안(MANDATORY). 공용 첨부 대상 {@code cms-breakdown} × 작업지시 id(사무 화면 "신고 사진"에 그대로).
     * 칸 오류 400(reqNo·clientReqId·workerId·terminalId) + 공용 거부(형식·크기·내용), 개수 넘침·착수 뒤 409, 잠김 409 FILE_LOCKED
     */
    PhotoResult attachPhoto(Photo photo);

    /** 사진 한도(C28) — 호출자 트랜잭션 안(SUPPORTS·읽기 전용, 회사 범위 정책). 예외 없음 */
    PhotoLimits photoLimits();

    /** 빈이 있으면 그것, 없으면 {@link #NONE} */
    static BreakdownReport of(ObjectProvider<BreakdownReport> provider) {
        return provider.getIfAvailable(() -> NONE);
    }
}
