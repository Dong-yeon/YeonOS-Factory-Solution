package io.smarthappy.module.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.file.FileService;
import io.smarthappy.platform.file.FileService.FileInfo;
import io.smarthappy.platform.file.FileStorage;
import io.smarthappy.platform.messaging.OutboxRelay;
import io.smarthappy.platform.notify.MailFailReason;
import io.smarthappy.platform.notify.MailService;
import io.smarthappy.platform.notify.MailService.MailAttach;
import io.smarthappy.platform.notify.MailService.Recipient;
import io.smarthappy.platform.notify.MailStatus;
import io.smarthappy.platform.policy.Policies;
import io.smarthappy.platform.policy.PolicyService;
import io.smarthappy.platform.tenant.TenantRunner;
import jakarta.mail.BodyPart;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;

/**
 * 외부 메일 첨부 (docs/36 18.6) — 대상에 붙은 사용 중 파일만, FileService 규칙 그대로(지금 허용 목록), 5개·합계 정책,
 * 아웃박스엔 키·이름·크기·SHA-256 만, 송신기는 저장소에서 읽어 맞춰 보고 없거나 다르면 ATTACHMENT_MISSING.
 * 필수(A8): A사로 B사 file_id → 400, 남의 targetId 파일 → 400. SMTP 는 JavaMailSender 가짜
 */
@TestPropertySource(properties = {"happyboot.mail.enabled=true", "happyboot.mail.from=noreply@yeonos.test",
        "happyboot.mail.base-url=https://mes.example.test/", "spring.mail.host=smtp.invalid"})
class MailAttachTest extends SysAdminTestSupport {

    static final String PROG = "sys-my-info";
    static final String TARGET = "ECO-1";
    static final byte[] PDF = "%PDF-1.7\n1 0 obj\n<<>>\nendobj\n".getBytes(StandardCharsets.US_ASCII);
    static final byte[] PNG = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3};

    @MockitoBean
    JavaMailSender sender;

    @Autowired
    MailService mail;

    @Autowired
    FileService files;

    @Autowired
    FileStorage storage;

    @Autowired
    PolicyService policies;

    @Autowired
    TenantRunner runner;

    @Autowired
    OutboxRelay relay;

    @BeforeEach
    void setUp() {
        setUpCompany("MA");
        reset(sender);
        when(sender.createMimeMessage()).thenAnswer(i -> new MimeMessage(Session.getInstance(new Properties())));
    }

    private FileInfo attach(String compCd, String targetId, String name, byte[] content) {
        return runner.call(compCd, () -> files.attachAs(PROG, targetId, name, content.length,
                new ByteArrayInputStream(content), "t"));
    }

    private UUID publish(MailAttach a) {
        return runner.call(comp, () -> mail.publish("PCN:1:CUST:1", new Recipient("buyer@customer.test", "김 구매"),
                "[PCN] 변경 안내", "도면을 붙입니다.", a));
    }

    private List<String> fields(Throwable t) {
        assertThat(t).isInstanceOf(BusinessException.class);
        return ((BusinessException) t).fieldErrors().stream().map(f -> f.field() + ":" + f.code()).toList();
    }

    private int outboxRows() {
        return accounts.query(comp, "select count(*) from sys_outbox where topic = 'MAIL'", Integer.class);
    }

    private MailStatus status(UUID id) {
        return runner.call(comp, () -> mail.status(id)).orElseThrow();
    }

    @Test
    void 대상의_파일을_붙여_보내면_글자_본문과_첨부_다중_파트_한글_이름() throws Exception {
        FileInfo a = attach(comp, TARGET, "도면 A-1.pdf", PDF);
        FileInfo b = attach(comp, TARGET, "photo.png", PNG);
        UUID id = publish(MailAttach.of(PROG, TARGET, List.of(a.fileId(), b.fileId(), a.fileId())));   // 중복은 한 번
        String payload = accounts.query(comp, "select payload::text from sys_outbox where message_id = ?", String.class, id);
        assertThat(payload).contains("\"attachments\"", a.storageKey(), a.sha256()).doesNotContain("JVBER");   // 내용(base64) 없음
        assertThat(relay.relay(comp).sent()).isEqualTo(1);
        ArgumentCaptor<MimeMessage> sent = ArgumentCaptor.forClass(MimeMessage.class);
        verify(sender).send(sent.capture());
        verify(sender, never()).send(any(SimpleMailMessage.class));
        MimeMessage m = sent.getValue();
        m.saveChanges();
        assertThat(m.getSubject()).isEqualTo("[PCN] 변경 안내");
        assertThat(m.getAllRecipients()[0].toString()).contains("buyer@customer.test");
        List<String> names = new ArrayList<>();
        List<byte[]> bodies = new ArrayList<>();
        String[] text = {null};
        walk(m, names, bodies, text);
        assertThat(text[0]).isEqualTo("도면을 붙입니다.");
        assertThat(names).containsExactly("도면 A-1.pdf", "photo.png");
        assertThat(bodies.get(0)).isEqualTo(PDF);
        assertThat(bodies.get(1)).isEqualTo(PNG);
        assertThat(status(id).state()).isEqualTo(MailStatus.State.SENT);
    }

    private static void walk(Part p, List<String> names, List<byte[]> bodies, String[] text) throws Exception {
        if (p.isMimeType("multipart/*")) {
            Multipart mp = (Multipart) p.getContent();
            for (int i = 0; i < mp.getCount(); i++) {
                walk(mp.getBodyPart(i), names, bodies, text);
            }
        } else if (Part.ATTACHMENT.equalsIgnoreCase(p.getDisposition())) {
            names.add(jakarta.mail.internet.MimeUtility.decodeText(((BodyPart) p).getFileName()));
            bodies.add(p.getInputStream().readAllBytes());
        } else if (p.isMimeType("text/plain")) {
            text[0] = (String) p.getContent();
        }
    }

    @Test
    void A사로_B사_file_id는_400_아웃박스에_없다() {
        String other = accounts.newComp("MAB");
        FileInfo theirs = attach(other, TARGET, "b.pdf", PDF);   // 같은 대상 이름·같은 화면 코드
        assertThat(fields(catchThrowable(() -> publish(MailAttach.of(PROG, TARGET, List.of(theirs.fileId()))))))
                .containsExactly("attach:NOT_FOUND");
        assertThat(outboxRows()).isZero();
    }

    @Test
    void 남의_targetId_파일과_지운_파일은_400() {
        FileInfo other = attach(comp, "ECO-2", "other.pdf", PDF);
        FileInfo mine = attach(comp, TARGET, "mine.pdf", PDF);
        assertThat(fields(catchThrowable(() -> publish(MailAttach.of(PROG, TARGET, List.of(mine.fileId(), other.fileId()))))))
                .containsExactly("attach:NOT_FOUND");
        assertThat(fields(catchThrowable(() -> publish(MailAttach.of("plm-eco", TARGET, List.of(mine.fileId()))))))
                .as("같은 id 라도 다른 화면 코드 대상이면").containsExactly("attach:NOT_FOUND");
        accounts.exec(comp, "update sys_file set use_yn = 'N' where file_id = ?", mine.fileId());
        assertThat(fields(catchThrowable(() -> publish(MailAttach.of(PROG, TARGET, List.of(mine.fileId()))))))
                .containsExactly("attach:NOT_FOUND");
        assertThat(fields(catchThrowable(() -> publish(MailAttach.of(PROG, TARGET, List.of(-1L))))))
                .containsExactly("attach:NOT_FOUND");
        assertThat(outboxRows()).isZero();
    }

    @Test
    void 허용_목록에서_빠진_확장자는_400() {
        FileInfo png = attach(comp, TARGET, "p.png", PNG);
        runner.run(comp, () -> policies.set(Policies.FILE_ALLOWED_EXT, null, "pdf", "t"));
        assertThat(fields(catchThrowable(() -> publish(MailAttach.of(PROG, TARGET, List.of(png.fileId()))))))
                .containsExactly("attach:ATTACH_EXT");
        assertThat(outboxRows()).isZero();
    }

    @Test
    void 여섯_개는_400_합계가_정책을_넘으면_400() {
        assertThat(fields(catchThrowable(() -> publish(MailAttach.of(PROG, TARGET, List.of(1L, 2L, 3L, 4L, 5L, 6L))))))
                .containsExactly("attach:ATTACH_COUNT");
        byte[] big = Arrays.copyOf(PDF, 600 * 1024);
        FileInfo a = attach(comp, TARGET, "a.pdf", big);
        FileInfo b = attach(comp, TARGET, "b.pdf", big);
        runner.run(comp, () -> policies.set(Policies.MAIL_ATTACH_MAX_MB, null, "1", "t"));
        assertThat(fields(catchThrowable(() -> publish(MailAttach.of(PROG, TARGET, List.of(a.fileId(), b.fileId()))))))
                .containsExactly("attach:ATTACH_SIZE");
        assertThat(outboxRows()).isZero();
        UUID ok = publish(MailAttach.of(PROG, TARGET, List.of(a.fileId())));   // 한 장(600KB)은 1MB 안
        assertThat(ok).isNotNull();
        // 정책 범위: 25 를 넘게는 못 둔다(SMTP 한도)
        assertThat(catchThrowable(() -> runner.run(comp, () -> policies.set(Policies.MAIL_ATTACH_MAX_MB, null, "26", "t"))))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void 대상_형식이_틀리면_400_빈_첨부는_글자_메일() {
        assertThat(catchThrowable(() -> publish(MailAttach.of("../etc", TARGET, List.of(1L))))).isInstanceOf(BusinessException.class);
        UUID id = publish(MailAttach.of(PROG, TARGET, List.of()));
        assertThat(accounts.query(comp, "select payload::text from sys_outbox where message_id = ?", String.class, id))
                .doesNotContain("attachments");
        relay.relay(comp);
        verify(sender).send(any(SimpleMailMessage.class));
    }

    @Test
    void 발행_뒤_저장소_파일이_없어지면_ATTACHMENT_MISSING_보내지_않는다() {
        FileInfo a = attach(comp, TARGET, "a.pdf", PDF);
        UUID id = publish(MailAttach.of(PROG, TARGET, List.of(a.fileId())));
        storage.deleteQuietly(a.storageKey());
        relay.relay(comp);
        assertThat(status(id).state()).isEqualTo(MailStatus.State.FAILED);
        assertThat(status(id).reason()).isEqualTo(MailFailReason.ATTACHMENT_MISSING);
        verify(sender, never()).send(any(MimeMessage.class));
    }

    @Test
    void 아웃박스_본문의_SHA256_이_다르면_ATTACHMENT_MISSING() {
        FileInfo a = attach(comp, TARGET, "a.pdf", PDF);
        UUID id = publish(MailAttach.of(PROG, TARGET, List.of(a.fileId())));
        accounts.exec(comp, """
                update sys_outbox set payload = jsonb_set(payload, '{attachments,0,sha256}', to_jsonb(repeat('0', 64)))
                 where message_id = ?""", id);
        relay.relay(comp);
        assertThat(status(id).reason()).isEqualTo(MailFailReason.ATTACHMENT_MISSING);
        verify(sender, never()).send(any(MimeMessage.class));
    }

    @Test
    void 발행_뒤_첨부를_지워도_이미_발행된_메일에는_실린다_A6_한계() {
        FileInfo a = attach(comp, TARGET, "a.pdf", PDF);
        UUID id = publish(MailAttach.of(PROG, TARGET, List.of(a.fileId())));
        accounts.exec(comp, "update sys_file set use_yn = 'N' where file_id = ?", a.fileId());
        relay.relay(comp);
        assertThat(status(id).state()).isEqualTo(MailStatus.State.SENT);   // 발행 = 보낸 것 (docs/36 18.6 A6)
    }
}
