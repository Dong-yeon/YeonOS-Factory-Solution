package io.smarthappy.platform.notify;

import java.sql.Array;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.FieldErrors;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.file.FileService;
import io.smarthappy.platform.file.FileService.FileInfo;
import io.smarthappy.platform.messaging.OutboxService;
import io.smarthappy.platform.policy.Policies;
import io.smarthappy.platform.policy.PolicyService;
import io.smarthappy.platform.tenant.TenantContext;

/**
 * 외부 메일 발행 (docs/36 18.3 계약) — 업무 모듈이 사람(고객 담당자 등)에게 직접 보내는 메일. 사내 알림 메일(알림함 → 메일, M1)과
 * 경로가 다르다: 구독·알림함 없이 호출자가 정한 받는 사람에게, 아웃박스 MAIL 로(같은 트랜잭션 — 업무가 롤백되면 메일도 없다).
 *
 * - 메일 설정이 꺼져 있으면 409 MAIL_DISABLED — 호출자가 "보내지 않았음" 을 안다(조용히 버리지 않는다)
 * - 받는 주소·이름·제목·본문·업무 키를 검사한다(형식·제어 문자·길이). 틀리면 400 INVALID_VALUE + 칸 오류
 * - **받는 주소가 그 업무에 맞는 사람인지(예: 그 고객의 담당자인지)는 호출자 책임** — 이 서비스는 형식만 본다
 * - 반환 = 아웃박스 메시지 id. 아웃박스 업무 키는 {@code EXT:{bizKey}}, 본문 kind = EXTERNAL(송신 로그에 외부 발송 표시)
 * - 발송 결과 읽기 {@link #status}·{@link #statuses} (18.5) — 지금 회사의 외부 메일만, 사유는 코드로(오류 원문 비노출)
 */
@Service
public class MailService {

    /** 받는 사람 — 이름은 선택(메일 머리의 표시 이름) */
    public record Recipient(String address, String name) {
        public static Recipient of(String address) {
            return new Recipient(address, null);
        }
    }

    /**
     * 첨부 (18.6 A1) — 호출자가 소유·권한을 확인한 업무 대상과 그 대상에 붙은 파일 id. 대상 밖 파일은 400
     */
    public record MailAttach(String targetType, String targetId, java.util.List<Long> fileIds) {
        public static MailAttach of(String targetType, String targetId, java.util.List<Long> fileIds) {
            return new MailAttach(targetType, targetId, fileIds);
        }
    }

    public static final String KIND_EXTERNAL = "EXTERNAL";
    /** 메일 한 통 첨부 개수 상한 (18.6 A3) */
    static final int MAX_ATTACH = 5;
    static final int MAX_SUBJECT = 200;
    static final int MAX_BODY = 20_000;
    static final int MAX_NAME = 100;
    /** 결과 읽기 한 번에 볼 수 있는 id 수 (18.5 R5) */
    static final int MAX_STATUS_IDS = 500;
    static final Pattern BIZ_KEY = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9:_./-]{0,79}$");
    /** 제목·이름: 제어 문자·양방향 문자 금지(머리 주입·표시 위장) */
    private static final Pattern HEADER_BAD = Pattern.compile("[\\x00-\\x1F\\x7F\\u202A-\\u202E\\u2066-\\u2069]");
    /** 본문: 줄바꿈·탭 말고 제어 문자 금지 */
    private static final Pattern BODY_BAD = Pattern.compile("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F\\u202A-\\u202E\\u2066-\\u2069]");
    /** 표시 이름에 쓰지 않는 글자 — 따옴표·꺾쇠·쉼표·세미콜론·역슬래시(주소 목록·인용을 깨뜨림) */
    private static final Pattern NAME_BAD = Pattern.compile("[\"<>,;\\\\]");

    private final OutboxService outbox;
    private final MailSettings settings;
    private final JdbcTemplate jdbc;
    private final FileService files;
    private final PolicyService policies;

    public MailService(OutboxService outbox, MailSettings settings, JdbcTemplate jdbc, FileService files,
            PolicyService policies) {
        this.outbox = outbox;
        this.settings = settings;
        this.jdbc = jdbc;
        this.files = files;
        this.policies = policies;
    }

    /** 메일 설정이 켜졌는가 — 화면이 "메일로 보내기" 를 보일지 정할 때 */
    public boolean enabled() {
        return settings.enabled();
    }

    /**
     * 업무 트랜잭션 안에서 외부 메일 한 통을 발행한다.
     * @param bizKey  업무 키(영문·숫자·{@code :_./-}, 80자) — 예 {@code PCN:12:CUST:5}. 아웃박스에 {@code EXT:} 를 붙여 남는다
     * @return 아웃박스 메시지 id
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID publish(String bizKey, Recipient to, String subject, String body) {
        return publish(bizKey, to, subject, body, null);
    }

    /**
     * 첨부와 함께 발행한다 (18.6). 첨부 = attach 대상에 붙은 사용 중 파일만, FileService 규칙(확장자 다시 확인) 그대로,
     * 5개·합계 정책 {@code MAIL_ATTACH_MAX_MB} 까지. 아웃박스 본문에는 저장 키·이름·크기·SHA-256 만 싣는다(A4).
     * attach 가 null 이거나 파일 id 가 없으면 글자만 보내는 publish 와 같다
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID publish(String bizKey, Recipient to, String subject, String body, MailAttach attach) {
        if (!settings.enabled()) {
            throw new BusinessException(HbErrorCode.MAIL_DISABLED);
        }
        String text = body == null ? null : body.replace("\r\n", "\n");
        FieldErrors e = FieldErrors.create();
        if (bizKey == null || !BIZ_KEY.matcher(bizKey).matches()) {
            e.add("bizKey", "FORMAT");
        }
        if (to == null || !MailSettings.validAddress(to.address())) {
            e.add("to", "FORMAT");
        } else if (to.name() != null && to.name().length() > MAX_NAME) {
            e.add("toName", "TOO_LONG", MAX_NAME);
        } else if (to.name() != null && (HEADER_BAD.matcher(to.name()).find() || NAME_BAD.matcher(to.name()).find())) {
            e.add("toName", "FORMAT");
        }
        if (subject == null || subject.isBlank()) {
            e.add("subject", "REQUIRED");
        } else if (subject.length() > MAX_SUBJECT) {
            e.add("subject", "TOO_LONG", MAX_SUBJECT);
        } else if (HEADER_BAD.matcher(subject).find()) {
            e.add("subject", "FORMAT");
        }
        if (text == null || text.isBlank()) {
            e.add("body", "REQUIRED");
        } else if (text.length() > MAX_BODY) {
            e.add("body", "TOO_LONG", MAX_BODY);
        } else if (BODY_BAD.matcher(text).find()) {
            e.add("body", "FORMAT");
        }
        java.util.List<Long> ids = attach == null || attach.fileIds() == null ? java.util.List.of()
                : attach.fileIds().stream().filter(Objects::nonNull).distinct().toList();
        if (ids.size() > MAX_ATTACH) {
            e.add("attach", "ATTACH_COUNT", MAX_ATTACH);
        }
        e.throwIfAny();
        java.util.List<Map<String, Object>> attachments = new java.util.ArrayList<>();
        if (!ids.isEmpty()) {
            FileService.Sendable ok = files.sendable(attach.targetType(), attach.targetId(), ids);
            int maxMb = policies.intValue(Policies.MAIL_ATTACH_MAX_MB);
            long total = ok.files().stream().mapToLong(FileInfo::sizeBytes).sum();
            if (!ok.notFound().isEmpty()) {
                e.add("attach", "NOT_FOUND");
            } else if (!ok.blocked().isEmpty()) {
                e.add("attach", "ATTACH_EXT");
            } else if (total > maxMb * 1024L * 1024L) {
                e.add("attach", "ATTACH_SIZE", maxMb);
            }
            e.throwIfAny();
            for (FileInfo f : ok.files()) {
                Map<String, Object> a = new HashMap<>();
                a.put("key", f.storageKey());
                a.put("name", f.origName());
                a.put("size", f.sizeBytes());
                a.put("sha256", f.sha256());
                a.put("contentType", f.contentType());
                attachments.add(a);
            }
        }
        Map<String, Object> payload = new HashMap<>();
        payload.put("kind", KIND_EXTERNAL);
        payload.put("to", to.address().trim());
        if (to.name() != null && !to.name().isBlank()) {
            payload.put("toName", to.name().strip());
        }
        payload.put("subject", subject.strip());
        payload.put("body", text);
        if (!attachments.isEmpty()) {
            payload.put("attachments", attachments);
        }
        return outbox.publish(MailNotifyHandler.TOPIC, "EXT:" + bizKey, payload);
    }

    /** 외부 메일 한 통의 발송 결과 — 다른 회사·사내 알림 메일·없는 id 는 빈 값 (18.5 R1) */
    @Transactional(readOnly = true)
    public Optional<MailStatus> status(UUID messageId) {
        return messageId == null ? Optional.empty()
                : Optional.ofNullable(statuses(java.util.List.of(messageId)).get(messageId));
    }

    /**
     * 외부 메일 여러 통의 발송 결과 — 목록 화면용. 찾은 것만 담는다(null·빈 목록 = 빈 결과).
     * 500개를 넘으면 400 {@code ids TOO_LONG}
     */
    @Transactional(readOnly = true)
    public Map<UUID, MailStatus> statuses(Collection<UUID> messageIds) {
        if (messageIds == null || messageIds.isEmpty()) {
            return Map.of();
        }
        UUID[] ids = messageIds.stream().filter(Objects::nonNull).distinct().toArray(UUID[]::new);
        if (ids.length > MAX_STATUS_IDS) {
            FieldErrors.create().add("ids", "TOO_LONG", MAX_STATUS_IDS).throwIfAny();
        }
        if (ids.length == 0) {
            return Map.of();
        }
        String compCd = TenantContext.requireCompCd();
        Map<UUID, MailStatus> out = new LinkedHashMap<>();
        jdbc.query(con -> {
            var ps = con.prepareStatement("""
                    select message_id, status, attempts, sent_at, last_error
                      from sys_outbox
                     where comp_cd = ? and message_id = any(?)
                       and topic = ? and payload ->> 'kind' = ?""");
            Array arr = con.createArrayOf("uuid", ids);
            ps.setString(1, compCd);
            ps.setArray(2, arr);
            ps.setString(3, MailNotifyHandler.TOPIC);
            ps.setString(4, KIND_EXTERNAL);
            return ps;
        }, rs -> {
            UUID id = rs.getObject("message_id", UUID.class);
            out.put(id, toStatus(id, rs.getString("status"), rs.getInt("attempts"),
                    rs.getObject("sent_at", OffsetDateTime.class), rs.getString("last_error")));
        });
        return out;
    }

    /**
     * 보내기 전 외부 메일 발행 취소 (18.7) — 잘못 발행(첨부 등)한 것을 아웃박스가 집기 전에 거둔다. **업무 트랜잭션 안에서만**
     * (MANDATORY — 호출자가 같은 트랜잭션에 업무 이력을 남긴다). 지금 회사의 MAIL·EXTERNAL 행이 **대기(NEW, 재시도 대기 포함)**
     * 일 때만 조건부 UPDATE 한 문장으로 CANCELED — 릴레이가 이미 집었으면(행 잠금 뒤 SENDING) 0건이라 한쪽만 이긴다.
     * 없음(다른 회사·사내 알림 메일 포함) 404 NOT_FOUND, 대기가 아님 409 MAIL_NOT_CANCELABLE
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void cancel(UUID messageId) {
        if (messageId == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        String compCd = TenantContext.requireCompCd();
        int n = jdbc.update("""
                update sys_outbox set status = 'CANCELED', locked_at = null, last_error = null
                 where comp_cd = ? and message_id = ? and topic = ? and payload ->> 'kind' = ? and status = 'NEW'""",
                compCd, messageId, MailNotifyHandler.TOPIC, KIND_EXTERNAL);
        if (n == 1) {
            return;
        }
        Integer exists = jdbc.queryForObject("""
                select count(*) from sys_outbox
                 where comp_cd = ? and message_id = ? and topic = ? and payload ->> 'kind' = ?""", Integer.class,
                compCd, messageId, MailNotifyHandler.TOPIC, KIND_EXTERNAL);
        throw new BusinessException(exists != null && exists > 0 ? HbErrorCode.MAIL_NOT_CANCELABLE : HbErrorCode.NOT_FOUND);
    }

    /** 아웃박스 상태·오류 기록 → 결과 (18.5 R2~R4) */
    static MailStatus toStatus(UUID id, String status, int attempts, OffsetDateTime sentAt, String lastError) {
        return switch (status) {
            case "SENT" -> new MailStatus(id, MailStatus.State.SENT, attempts, sentAt, null);
            // 수동 종결(연계 현황, docs/36 19장 O8)도 같은 규칙 — 대기 중으로 보이지 않게
            case "CANCELED", "CLOSED_MANUAL" -> new MailStatus(id, attempts > 0 ? MailStatus.State.STOPPED : MailStatus.State.CANCELED,
                    attempts, null, null);   // 한 번이라도 보내 봤으면 배달됐을 수 있다(QA M-1)
            case "FAIL" -> {
                MailFailReason r = MailFailReason.parse(lastError);
                yield new MailStatus(id, MailStatus.State.FAILED, attempts, null,
                        r == MailFailReason.TEMPORARY ? MailFailReason.MAX_ATTEMPTS : r);   // 일시 오류로 상한까지 = 상한
            }
            default -> new MailStatus(id, MailStatus.State.PENDING, attempts, null,
                    lastError == null ? null : MailFailReason.parse(lastError));
        };
    }

    /** 송신기가 다시 쓰는 표시 이름 검사(발행 때와 같은 규칙) */
    static boolean validName(String name) {
        return name.length() <= MAX_NAME && !HEADER_BAD.matcher(name).find() && !NAME_BAD.matcher(name).find();
    }
}
