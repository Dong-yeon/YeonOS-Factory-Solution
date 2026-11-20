package io.smarthappy.platform.notify;

import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailParseException;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import io.smarthappy.platform.file.FileStorage;
import io.smarthappy.platform.messaging.OutboxHandler;
import io.smarthappy.platform.messaging.OutboxMessage;
import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

/**
 * 토픽 MAIL — 알림 메일 SMTP 송신 (docs/36 18.3). 본문은 보낼 때 이미 받는 사람 언어로 만들어 실었다(여기서는 DB 를 안 쓴다, 01 5.8).
 *
 * - 글자 메일만(HTML 없음). 첨부(18.6)가 있으면 글자 본문 + 첨부 다중 파트 — 저장소에서 읽고(DB 없음) 크기·SHA-256 을 맞춰 본다.
 *   없거나 다르면 ATTACHMENT_MISSING 영구 실패(첨부 없이 보내지 않는다). 첨부 이름은 RFC 2231 UTF-8(Jakarta Mail 기본)
 * - 제목·본문은 보내는 쪽에서 제어 문자를 걸렀고, 받는 주소는 여기서 다시 확인한다
 * - 결과: 보냄 / 주소·인증·형식 오류·서버가 영구 거절(5xx) = 실패(다시 보내도 안 됨) / 연결·일시 오류 = 재시도
 * - 읽기 타임아웃은 재시도하지 않는다 — 서버가 이미 받았을 수 있어 같은 메일 두 번이 빠짐보다 나쁘다(01 5.8)
 * - 메일이 꺼져 있으면(설정을 끈 뒤 남은 행) 보내지 않고 실패로 끝낸다
 * - 오류 기록은 {@link MailFailReason} 코드 + 고정 설명만 (18.5 R4) — 예외 메시지·주소·SMTP 응답 글·계정을 싣지 않는다.
 *   예상 밖 실행 오류도 여기서 잡는다(릴레이의 일반 처리는 예외 메시지를 싣는다)
 */
@lombok.extern.slf4j.Slf4j
@Component
public class MailNotifyHandler implements OutboxHandler {

    public static final String TOPIC = "MAIL";

    private final MailSettings settings;
    private final ObjectProvider<JavaMailSender> sender;
    private final FileStorage storage;

    public MailNotifyHandler(MailSettings settings, ObjectProvider<JavaMailSender> sender, FileStorage storage) {
        this.settings = settings;
        this.sender = sender;
        this.storage = storage;
    }

    /** 저장소에서 읽은 첨부 하나 */
    private record Part(String name, String contentType, byte[] bytes) {
    }

    /** 첨부 없음을 알리는 내부 신호 — 사유 글은 고정 */
    private static final class MissingAttachment extends Exception {
        MissingAttachment() {
            super(null, null, false, false);
        }
    }

    @Override
    public String topic() {
        return TOPIC;
    }

    @Override
    public SendResult send(OutboxMessage m) {
        JavaMailSender mail = sender.getIfAvailable();
        if (!settings.enabled() || mail == null) {
            return SendResult.fail(MailFailReason.DISABLED.record("메일 꺼짐"));
        }
        Map<String, Object> p = m.payload();
        String to = p == null ? null : (String) p.get("to");
        String subject = p == null ? null : (String) p.get("subject");
        String body = p == null ? null : (String) p.get("body");
        String toName = p == null ? null : (String) p.get("toName");
        if (!MailSettings.validAddress(to) || subject == null || body == null || (toName != null && !MailService.validName(toName))) {
            return SendResult.fail(MailFailReason.FORMAT.record("메일 본문 형식"));
        }
        Object rawParts = p.get("attachments");
        try {
            if (rawParts == null) {
                SimpleMailMessage msg = new SimpleMailMessage();
                msg.setFrom(settings.from());
                // 표시 이름은 따옴표로 감싼다(발행 때 따옴표·꺾쇠·제어 문자를 막았다) — 비ASCII 는 메일 발송기가 UTF-8 로 인코딩
                msg.setTo(toName == null ? to : "\"" + toName + "\" <" + to + ">");
                msg.setSubject(subject);
                msg.setText(body);
                mail.send(msg);
            } else {
                List<Part> parts;
                try {
                    parts = readParts(rawParts);
                } catch (MissingAttachment e) {
                    return SendResult.fail(MailFailReason.ATTACHMENT_MISSING.record("첨부가 저장소에 없거나 바뀜"));
                }
                MimeMessage mime = mail.createMimeMessage();
                try {
                    MimeMessageHelper h = new MimeMessageHelper(mime, true, StandardCharsets.UTF_8.name());
                    h.setFrom(settings.from());
                    h.setTo(toName == null ? new InternetAddress(to) : new InternetAddress(to, toName, StandardCharsets.UTF_8.name()));
                    h.setSubject(subject);
                    h.setText(body, false);
                    for (Part part : parts) {
                        h.addAttachment(part.name(), new ByteArrayResource(part.bytes()), part.contentType());
                    }
                } catch (MessagingException | UnsupportedEncodingException e) {
                    return SendResult.fail(MailFailReason.FORMAT.record(e.getClass().getSimpleName()));
                }
                mail.send(mime);
            }
            if (MailService.KIND_EXTERNAL.equals(p.get("kind"))) {
                // 외부 발송은 누가 무엇을 받았는지 업무 키로 추적한다 — 주소·본문은 로그에 남기지 않는다
                log.info("외부 메일 보냄 compCd={} outboxId={} bizKey={}", m.compCd(), m.outboxId(), m.bizKey());
            }
            return SendResult.sent();
        } catch (MailAuthenticationException e) {
            return SendResult.fail(MailFailReason.AUTH.record(e.getClass().getSimpleName()));
        } catch (MailParseException | MailPreparationException e) {
            return SendResult.fail(MailFailReason.FORMAT.record(e.getClass().getSimpleName()));
        } catch (MailSendException e) {
            if (permanent(e)) {
                return SendResult.fail(MailFailReason.REJECTED.record("SMTP 거절"));
            }
            // 연결 뒤 시간 초과는 서버가 이미 받았을 수 있다 — 재시도하지 않는다(릴레이는 감싼 예외 안의 타임아웃을 못 본다)
            return readTimeout(e) ? SendResult.fail(MailFailReason.TIMEOUT_NO_RETRY.record("SMTP 시간 초과(재시도 안 함 — 중복 위험)"))
                    : SendResult.retry(MailFailReason.TEMPORARY.record("SMTP 일시 오류"));
        } catch (RuntimeException e) {
            log.warn("메일 송신 예상 밖 오류 compCd={} outboxId={} type={}", m.compCd(), m.outboxId(), e.getClass().getName());
            return SendResult.retry(MailFailReason.TEMPORARY.record(e.getClass().getSimpleName()));
        }
    }

    /** 아웃박스 본문의 첨부 목록 → 저장소에서 읽은 바이트. 형식이 틀리거나 없거나 크기·SHA-256 이 다르면 MissingAttachment */
    private List<Part> readParts(Object raw) throws MissingAttachment {
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            throw new MissingAttachment();
        }
        List<Part> out = new ArrayList<>();
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> a) || !(a.get("key") instanceof String key) || !(a.get("name") instanceof String name)
                    || !(a.get("size") instanceof Number size) || !(a.get("sha256") instanceof String sha)) {
                throw new MissingAttachment();
            }
            String type = a.get("contentType") instanceof String t ? t : "application/octet-stream";
            byte[] bytes;
            try (InputStream in = storage.open(key)) {
                bytes = in.readNBytes((int) Math.min(size.longValue() + 1, Integer.MAX_VALUE));
            } catch (IOException e) {
                throw new MissingAttachment();
            }
            if (bytes.length != size.longValue() || !sha.equals(sha256(bytes))) {
                throw new MissingAttachment();
            }
            out.add(new Part(name, type, bytes));
        }
        return out;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 원인 사슬에 연결 시간 초과가 아닌 시간 초과가 있는가 */
    static boolean readTimeout(Throwable e) {
        java.util.List<Throwable> all = new java.util.ArrayList<>();
        all.add(e);
        if (e instanceof MailSendException mse) {
            all.addAll(mse.getFailedMessages().values());
        }
        for (Throwable root : all) {
            for (Throwable t = root; t != null; t = t.getCause()) {
                if (t instanceof java.net.SocketTimeoutException
                        && (t.getMessage() == null || !t.getMessage().toLowerCase(java.util.Locale.ROOT).contains("connect"))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 서버가 받는 주소를 영구 거절(5xx) — 다시 보내도 같다 */
    static boolean permanent(MailSendException e) {
        for (Exception f : e.getFailedMessages().values()) {
            for (Throwable t = f; t != null; t = t.getCause()) {
                if (t instanceof SendFailedException sfe && sfe.getInvalidAddresses() != null && sfe.getInvalidAddresses().length > 0) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public boolean retryOnReadTimeout() {
        return false;
    }
}
