package io.smarthappy.module.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.messaging.OutboxRelay;
import io.smarthappy.platform.notify.MailService;
import io.smarthappy.platform.notify.MailService.Recipient;
import io.smarthappy.platform.tenant.TenantRunner;

/**
 * 외부 메일 발행 공개 API (docs/36 18.3 계약, PLM PCN 요청) — 같은 트랜잭션 아웃박스, 형식 검사, 외부 발송 표시, 회사 범위.
 * 메일 꺼짐(409)은 MailServiceOffTest. SMTP 는 JavaMailSender 가짜
 */
@TestPropertySource(properties = {"happyboot.mail.enabled=true", "happyboot.mail.from=noreply@yeonos.test",
        "happyboot.mail.base-url=https://mes.example.test/", "spring.mail.host=smtp.invalid"})
class MailServiceTest extends SysAdminTestSupport {

    @MockitoBean
    JavaMailSender sender;

    @Autowired
    MailService mail;

    @Autowired
    TenantRunner runner;

    @Autowired
    OutboxRelay relay;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        setUpCompany("MS");
        reset(sender);
    }

    private List<Map<String, Object>> rows(String compCd) {
        return runner.call(compCd, () -> jdbc.queryForList(
                "select message_id::text as id, biz_key, payload::text as payload from sys_outbox where topic = 'MAIL' order by outbox_id"));
    }

    private UUID publish(String bizKey, Recipient to, String subject, String body) {
        return runner.call(comp, () -> mail.publish(bizKey, to, subject, body));
    }

    private static List<String> fields(Throwable t) {
        assertThat(t).isInstanceOf(BusinessException.class);
        return ((BusinessException) t).fieldErrors().stream().map(f -> f.field() + ":" + f.code()).toList();
    }

    @Test
    void 발행하면_같은_트랜잭션_아웃박스에_외부_표시와_함께_남고_릴레이가_이름과_함께_보낸다() {
        UUID id = publish("PCN:12:CUST:5", new Recipient("buyer@customer.test", "김 구매"), "[PCN] 공정 변경 안내",
                "안녕하세요.\r\n변경 내용입니다.\n- 1공정\t설비 교체");
        List<Map<String, Object>> r = rows(comp);
        assertThat(r).hasSize(1);
        assertThat(r.get(0).get("id")).isEqualTo(id.toString());                 // 반환 = 아웃박스 메시지 id
        assertThat((String) r.get(0).get("biz_key")).isEqualTo("EXT:PCN:12:CUST:5");
        assertThat((String) r.get(0).get("payload")).contains("\"kind\": \"EXTERNAL\"", "\"toName\": \"김 구매\"");
        assertThat(relay.relay(comp).sent()).isEqualTo(1);
        ArgumentCaptor<SimpleMailMessage> sent = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(sender).send(sent.capture());
        assertThat(sent.getValue().getTo()).containsExactly("\"김 구매\" <buyer@customer.test>");
        assertThat(sent.getValue().getFrom()).isEqualTo("noreply@yeonos.test");
        assertThat(sent.getValue().getText()).isEqualTo("안녕하세요.\n변경 내용입니다.\n- 1공정\t설비 교체");   // \r\n → \n, 탭 유지
        // 이름 없이도
        publish("PCN:12:CUST:6", Recipient.of("qa@customer.test"), "제목", "본문");
        relay.relay(comp);
        verify(sender, org.mockito.Mockito.times(2)).send(sent.capture());
        assertThat(sent.getAllValues().get(sent.getAllValues().size() - 1).getTo()).containsExactly("qa@customer.test");
    }

    @Test
    void 업무가_롤백되면_메일도_없다() {
        Throwable t = catchThrowable(() -> runner.run(comp, () -> {
            mail.publish("PCN:1", Recipient.of("a@b.test"), "s", "b");
            throw new IllegalStateException("업무 실패");
        }));
        assertThat(t).hasMessage("업무 실패");
        assertThat(rows(comp)).isEmpty();
    }

    @Test
    void 형식_제어문자_길이_검사() {
        assertThat(fields(catchThrowable(() -> publish("PCN:1", Recipient.of("a@b.test\r\nBcc: spy@evil.test"), "s", "b"))))
                .containsExactly("to:FORMAT");
        assertThat(fields(catchThrowable(() -> publish("PCN:1", new Recipient("a@b.test", "x\" <spy@evil.test>"), "s", "b"))))
                .containsExactly("toName:FORMAT");
        assertThat(fields(catchThrowable(() -> publish("PCN:1", new Recipient("a@b.test", "a\nb"), "s", "b"))))
                .containsExactly("toName:FORMAT");
        assertThat(fields(catchThrowable(() -> publish("PCN:1", Recipient.of("a@b.test"), "제목\r\nBcc: x@y.test", "b"))))
                .containsExactly("subject:FORMAT");
        assertThat(fields(catchThrowable(() -> publish("PCN:1", Recipient.of("a@b.test"), "‮제목", "b")))).containsExactly("subject:FORMAT");
        assertThat(fields(catchThrowable(() -> publish("PCN:1", Recipient.of("a@b.test"), "s".repeat(201), "b"))))
                .containsExactly("subject:TOO_LONG");
        assertThat(fields(catchThrowable(() -> publish("PCN:1", Recipient.of("a@b.test"), "s", "a\u0000b")))).containsExactly("body:FORMAT");
        assertThat(fields(catchThrowable(() -> publish("PCN:1", Recipient.of("a@b.test"), "s", "x".repeat(20_001)))))
                .containsExactly("body:TOO_LONG");
        assertThat(fields(catchThrowable(() -> publish("a b", null, " ", null))))
                .containsExactlyInAnyOrder("bizKey:FORMAT", "to:FORMAT", "subject:REQUIRED", "body:REQUIRED");
        assertThat(rows(comp)).isEmpty();
    }

    @Test
    void 트랜잭션_밖에서는_부를_수_없고_A사_발행은_B사에_보이지_않는다() {
        assertThat(catchThrowable(() -> mail.publish("PCN:1", Recipient.of("a@b.test"), "s", "b"))).isNotNull();
        publish("PCN:9", Recipient.of("a@b.test"), "s", "b");
        String other = accounts.newComp("MSB");
        assertThat(rows(other)).isEmpty();
        assertThat(rows(comp)).hasSize(1);
        assertThat(relay.relay(other).total()).isZero();
        verify(sender, org.mockito.Mockito.never()).send(any(SimpleMailMessage.class));
    }
}
