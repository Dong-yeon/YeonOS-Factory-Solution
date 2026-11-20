package io.smarthappy.platform.file;

/**
 * 대상 화면 권한(SEARCH)이 없는 사람에게 첨부 **읽기**(목록·내려받기)를 허락하는 규칙 — 예: 게시 중인 공지의 첨부는
 * 공지 관리 권한이 없는 모든 사용자가 읽는다(docs/36 11.4). 올리기·삭제는 언제나 대상 화면 SAVE.
 * 회사 범위 읽기 트랜잭션 안에서 불린다. 모르는 대상이면 false.
 */
public interface FileReadGrant {

    boolean allows(String targetType, String targetId);
}
