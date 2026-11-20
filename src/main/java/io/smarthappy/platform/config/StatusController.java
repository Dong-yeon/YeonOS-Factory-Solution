package io.smarthappy.platform.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.json.JsonMapper;

/**
 * 장애·점검 공지 /status.json (01 9장 9단계). 로그인 없이 열린다 — 로그인 화면·셸이 읽어 띠로 보여 줄 수 있다.
 * 운영자가 서버의 파일(happyboot.status-file)을 고치면 반영된다. 파일 내용을 그대로 내보내지 않고
 * 허용 항목만 다시 만든다(status 는 정해진 값, 문구는 길이 제한).
 */
@Slf4j
@RestController
public class StatusController {

    static final Set<String> STATUSES = Set.of("ok", "degraded", "maintenance", "outage");
    static final int MAX_FILE = 4096;
    static final int MAX_TEXT = 500;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final Path file;

    public StatusController(@Value("${happyboot.status-file:./data/status.json}") String file) {
        this.file = Path.of(file);
    }

    @GetMapping(value = "/status.json", produces = "application/json")
    public ResponseEntity<Map<String, Object>> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "ok");
        try {
            if (Files.isRegularFile(file) && Files.size(file) <= MAX_FILE) {
                Map<?, ?> raw = JSON.readValue(Files.readString(file), Map.class);
                if (raw.get("status") instanceof String s && STATUSES.contains(s)) {
                    out.put("status", s);
                }
                for (String k : new String[] {"messageKo", "messageEn", "updatedAt"}) {
                    if (raw.get(k) instanceof String v && !v.isBlank()) {
                        out.put(k, v.length() > MAX_TEXT ? v.substring(0, MAX_TEXT) : v);
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            log.warn("status.json 읽기 실패 — 정상으로 응답 ({})", e.getClass().getSimpleName());
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(out);
    }
}
