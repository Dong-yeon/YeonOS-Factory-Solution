package io.smarthappy.platform.tenant;

/** 회사 범위가 필요한 곳에서 범위가 없을 때. 웹 요청에서는 401 로 응답한다 */
public class TenantNotSetException extends RuntimeException {

    public TenantNotSetException() {
        super("회사 정보가 없습니다. 다시 로그인해 주세요.");
    }
}
