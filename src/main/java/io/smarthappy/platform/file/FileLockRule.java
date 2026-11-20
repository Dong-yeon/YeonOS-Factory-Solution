package io.smarthappy.platform.file;

/**
 * 첨부 잠금 규칙 — 업무 모듈이 빈으로 등록한다(FileReadGrant 와 같은 모양). 하나라도 locked 면 FileService 가 그 대상의
 * **올리기·삭제**를 409 {@code FILE_LOCKED} 로 거부한다. 예: 결재 중·배포된 관리문서 리비전(docs/29 Q3), 상신한 설계 변경(PLM D7).
 * 회사 범위 읽기 트랜잭션 안에서 불린다. 모르는 대상이면 false.
 *
 * 앱 규칙은 상태 전이(결재 중 → 반려면 다시 풀림)까지 다룬다. 근거로 굳힌 파일의 DB 최종 방어는 sys_file.locked_yn(한 방향) —
 * 모듈이 배포 같은 전이 때 찍고, 공통 트리거가 사용 중지·변경·해제를 막는다
 */
public interface FileLockRule {

    boolean locked(String targetType, String targetId);
}
