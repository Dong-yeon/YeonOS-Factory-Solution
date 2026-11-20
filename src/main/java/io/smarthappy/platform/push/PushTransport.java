package io.smarthappy.platform.push;

import java.io.IOException;
import java.util.Map;

/**
 * 푸시 서비스로 POST 하나 — 시험은 이 빈을 바꿔 끼워 실제 네트워크 없이 요청을 본다.
 * 구현은 리다이렉트를 따르지 않는다(허용 호스트 검사를 우회하지 못하게, docs/45 9장 N6)
 */
public interface PushTransport {

    /** 응답 상태 코드. 연결 실패·타임아웃은 IOException */
    int post(String endpoint, Map<String, String> headers, byte[] body) throws IOException, InterruptedException;
}
