package io.smarthappy.platform.dev;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import io.smarthappy.platform.auth.PageController.PageProg;
import io.smarthappy.platform.auth.ProgAuthExempt;
import io.smarthappy.platform.common.web.ApiResponse;
import io.smarthappy.platform.common.web.HbErrorCode;

/**
 * 편집 그리드·위젯 PoC (docs/10 8장·11.3) — **local 프로필 전용**. 운영·테스트 프로필에서는 빈이 없어 경로 자체가 404.
 *
 * - 화면 /dev/poc/grid : sys_prog 에 넣지 않는다(제품 화면이 아님). 현행 CSP(SecurityConfig) 를 그대로 받는다.
 * - API /api/dev/poc/grid/** : DB 를 쓰지 않는 메모리 저장소(한 JVM 공용). 일괄 저장은 docs/09 5.1 규약 모양 그대로
 *   (deleted → updated → created, 검증 오류를 모아 400 + details{rowKey,field,code}, row_ver 불일치 409 + details{rowKey,id}).
 *   전부 아니면 전무 — 오류가 하나라도 있으면 아무것도 반영하지 않는다.
 * 8단계 거래처관리의 실제 batch API 가 생기면 이 클래스는 지운다.
 */
@Controller
@Profile("local")
public class GridPocController {

    private static final int MAX_ROWS = 5000;
    private static final int MAX_BATCH = 1000;
    private static final Set<String> TRADE_TYPES = Set.of("SALE", "PURC", "BOTH");
    private static final Set<String> YN = Set.of("Y", "N");
    private static final String[] NAMES = {"한빛정밀", "대성오토텍", "세진금속", "우리부품", "동해산업", "미래테크", "서울하이텍", "부산기공"};
    private static final String[] PERSONS = {"김민수", "이서연", "박지훈", "최유진", "정하늘", "강도윤"};

    private final MessageSource messages;
    private final Map<Long, Map<String, Object>> store = new ConcurrentHashMap<>();
    private final AtomicLong seq = new AtomicLong();
    /** X-Idempotency-Key 중복 확인 (PoC 라 메모리, 실제는 InboxService.claimRequestKey) */
    private final Set<String> seenKeys = ConcurrentHashMap.newKeySet();

    public GridPocController(MessageSource messages) {
        this.messages = messages;
        reset(30);
    }

    @GetMapping("/dev/poc/grid")
    public String page(Model model) {
        model.addAttribute("prog", new PageProg("dev-grid-poc", "편집 그리드·위젯 PoC"));
        return "dev/grid-poc";
    }

    @ResponseBody
    @GetMapping("/api/dev/poc/grid/rows")
    @ProgAuthExempt(reason = "local 프로필 전용 PoC — 운영 빌드에는 빈이 없다")
    public ApiResponse<Void> rows() {
        synchronized (store) {
            List<Map<String, Object>> list = store.values().stream()
                    .sorted((a, b) -> Long.compare((Long) a.get("id"), (Long) b.get("id")))
                    .map(m -> (Map<String, Object>) new LinkedHashMap<>(m)).toList();
            return ApiResponse.list(list, list.size());
        }
    }

    /** 데이터를 count 행으로 다시 만든다 (성능 측정용 1천 행 등) */
    @ResponseBody
    @PostMapping("/api/dev/poc/grid/reset")
    @ProgAuthExempt(reason = "local 프로필 전용 PoC — 운영 빌드에는 빈이 없다")
    public ApiResponse<Map<String, Object>> resetRows(@RequestParam(defaultValue = "30") int count) {
        reset(Math.max(0, Math.min(count, MAX_ROWS)));
        return ApiResponse.ok(Map.of("count", store.size()));
    }

    /** 다른 사용자가 먼저 수정한 상황 흉내: row_ver 만 올린다 → 다음 저장이 409 */
    @ResponseBody
    @PostMapping("/api/dev/poc/grid/{id}/bump")
    @ProgAuthExempt(reason = "local 프로필 전용 PoC — 운영 빌드에는 빈이 없다")
    public ResponseEntity<Object> bump(@PathVariable long id, Locale locale) {
        Map<String, Object> row = store.get(id);
        if (row == null) {
            return error(HbErrorCode.NOT_FOUND, List.of(), locale);
        }
        synchronized (store) {
            row.put("rowVer", ((Integer) row.get("rowVer")) + 1);
        }
        return ResponseEntity.ok(ApiResponse.ok(Map.of("id", id, "rowVer", row.get("rowVer"))));
    }

    @ResponseBody
    @PostMapping("/api/dev/poc/grid/batch")
    @ProgAuthExempt(reason = "local 프로필 전용 PoC — 운영 빌드에는 빈이 없다")
    public ResponseEntity<Object> batch(@RequestBody Map<String, List<Map<String, Object>>> body,
            @RequestHeader(name = "X-Idempotency-Key", required = false) String idemKey, Locale locale) {
        List<Map<String, Object>> created = nonNull(body.get("created"));
        List<Map<String, Object>> updated = nonNull(body.get("updated"));
        List<Map<String, Object>> deleted = nonNull(body.get("deleted"));
        if (created.size() + updated.size() + deleted.size() > MAX_BATCH) {
            return error(HbErrorCode.BAD_REQUEST, List.of(), locale);
        }
        if (idemKey != null && !seenKeys.add(idemKey)) {
            return error(HbErrorCode.DUPLICATE_REQUEST, List.of(), locale);
        }
        synchronized (store) {
            // 1) 검증을 전부 돌려 모은다
            List<Map<String, Object>> invalid = new ArrayList<>();
            for (Map<String, Object> r : created) {
                validate(r, invalid);
            }
            for (Map<String, Object> r : updated) {
                validate(r, invalid);
            }
            if (!invalid.isEmpty()) {
                return error(HbErrorCode.INVALID_VALUE, invalid, locale);
            }
            // 2) row_ver 확인 (updated·deleted) — 하나라도 어긋나면 전체 409
            List<Map<String, Object>> conflicts = new ArrayList<>();
            for (Map<String, Object> r : concat(deleted, updated)) {
                Map<String, Object> cur = store.get(toLong(r.get("id")));
                if (cur == null || !cur.get("rowVer").equals(toInt(r.get("rowVer")))) {
                    conflicts.add(Map.of("rowKey", String.valueOf(r.get("rowKey")), "id", String.valueOf(r.get("id"))));
                }
            }
            if (!conflicts.isEmpty()) {
                return error(HbErrorCode.CONFLICT, conflicts, locale);
            }
            // 3) 반영: deleted → updated → created
            for (Map<String, Object> r : deleted) {
                store.remove(toLong(r.get("id")));
            }
            for (Map<String, Object> r : updated) {
                Map<String, Object> cur = store.get(toLong(r.get("id")));
                copyFields(r, cur);
                cur.put("rowVer", (Integer) cur.get("rowVer") + 1);
            }
            List<Map<String, Object>> createdIds = new ArrayList<>();
            for (Map<String, Object> r : created) {
                long id = seq.incrementAndGet();
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", id);
                row.put("rowVer", 1);
                copyFields(r, row);
                store.put(id, row);
                createdIds.add(Map.of("rowKey", String.valueOf(r.get("rowKey")), "id", id));
            }
            return ResponseEntity.ok(ApiResponse.ok(Map.of("created", createdIds,
                    "count", Map.of("c", created.size(), "u", updated.size(), "d", deleted.size()))));
        }
    }

    private void reset(int count) {
        synchronized (store) {
            store.clear();
            seq.set(0);
            LocalDate base = LocalDate.of(2026, 10, 1);
            for (int i = 0; i < count; i++) {
                long id = seq.incrementAndGet();
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", id);
                row.put("rowVer", 1);
                row.put("custCd", String.format("C%05d", id));
                row.put("custNm", NAMES[i % NAMES.length] + (i / NAMES.length > 0 ? " " + (i / NAMES.length) : ""));
                row.put("personNm", PERSONS[i % PERSONS.length]);
                row.put("qty", (i * 7) % 500);
                row.put("unitPrice", new BigDecimal((i * 1310) % 99000).add(new BigDecimal("0.5")));
                row.put("tradeDt", base.plusDays(i % 60).toString());
                row.put("tradeType", i % 3 == 0 ? "SALE" : i % 3 == 1 ? "PURC" : "BOTH");
                row.put("useYn", i % 10 == 9 ? "N" : "Y");
                row.put("remark", "");
                store.put(id, row);
            }
        }
    }

    /** 서버 검증 (클라이언트 파서를 믿지 않는다). 오류는 details 에 rowKey·field·code 로 */
    private static void validate(Map<String, Object> r, List<Map<String, Object>> out) {
        String rowKey = String.valueOf(r.get("rowKey"));
        if (isBlank(r.get("custCd")) || String.valueOf(r.get("custCd")).length() > 20) {
            out.add(detail(rowKey, "custCd", isBlank(r.get("custCd")) ? "REQUIRED" : "TOO_LONG"));
        }
        if (isBlank(r.get("custNm"))) {
            out.add(detail(rowKey, "custNm", "REQUIRED"));
        } else if (String.valueOf(r.get("custNm")).length() > 100) {
            out.add(detail(rowKey, "custNm", "TOO_LONG"));
        }
        if (!isBlank(r.get("qty")) && !(r.get("qty") instanceof Number n && n.doubleValue() >= 0)) {
            out.add(detail(rowKey, "qty", "NUMBER"));
        }
        if (!isBlank(r.get("unitPrice")) && !(r.get("unitPrice") instanceof Number)) {
            out.add(detail(rowKey, "unitPrice", "NUMBER"));
        }
        if (!isBlank(r.get("tradeDt"))) {
            try {
                LocalDate.parse(String.valueOf(r.get("tradeDt")));
            } catch (DateTimeParseException e) {
                out.add(detail(rowKey, "tradeDt", "DATE"));
            }
        }
        if (!TRADE_TYPES.contains(String.valueOf(r.get("tradeType")))) {
            out.add(detail(rowKey, "tradeType", "CODE"));
        }
        if (!YN.contains(String.valueOf(r.get("useYn")))) {
            out.add(detail(rowKey, "useYn", "CODE"));
        }
    }

    /** 허용 필드만 복사 (id·rowVer·compCd 등은 클라이언트 값을 쓰지 않는다) */
    private static void copyFields(Map<String, Object> from, Map<String, Object> to) {
        for (String f : List.of("custCd", "custNm", "personNm", "qty", "unitPrice", "tradeDt", "tradeType", "useYn",
                "remark")) {
            to.put(f, from.get(f));
        }
    }

    private ResponseEntity<Object> error(HbErrorCode code, List<Map<String, Object>> details, Locale locale) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("result", "ERROR");
        body.put("code", code.name());
        body.put("message", messages.getMessage(code.messageKey(), null, code.name(), locale));
        body.put("details", details);
        return ResponseEntity.status(code.status()).body(body);
    }

    private static Map<String, Object> detail(String rowKey, String field, String code) {
        return Map.of("rowKey", rowKey, "field", field, "code", code);
    }

    private static boolean isBlank(Object v) {
        return v == null || String.valueOf(v).isBlank();
    }

    private static <T> List<T> nonNull(List<T> l) {
        return l == null ? List.of() : l;
    }

    private static List<Map<String, Object>> concat(List<Map<String, Object>> a, List<Map<String, Object>> b) {
        List<Map<String, Object>> all = new ArrayList<>(a);
        all.addAll(b);
        return all;
    }

    private static Long toLong(Object v) {
        return v instanceof Number n ? n.longValue() : -1L;
    }

    private static Integer toInt(Object v) {
        return v instanceof Number n ? n.intValue() : -1;
    }
}
