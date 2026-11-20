package io.smarthappy.platform.push;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

import org.springframework.stereotype.Component;

/** JDK HttpClient — 연결 5초·요청 10초, 리다이렉트 안 따름, 응답 본문은 버린다 */
@Component
class HttpPushTransport implements PushTransport {

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    @Override
    public int post(String endpoint, Map<String, String> headers, byte[] body) throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(endpoint)).timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        headers.forEach(b::header);
        return client.send(b.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }
}
