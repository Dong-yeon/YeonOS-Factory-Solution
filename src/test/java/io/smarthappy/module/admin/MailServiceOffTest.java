package io.smarthappy.module.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.notify.MailService;
import io.smarthappy.platform.notify.MailService.Recipient;
import io.smarthappy.platform.tenant.TenantRunner;

/** 외부 메일 발행 — 메일 설정이 꺼져 있으면(기본) 409 MAIL_DISABLED, 아웃박스에 아무것도 남지 않는다 (docs/36 18.3) */
class MailServiceOffTest extends SysAdminTestSupport {

    @Autowired
    MailService mail;

    @Autowired
    TenantRunner runner;

    @Test
    void 메일이_꺼져_있으면_409_로_알리고_아무것도_남기지_않는다() {
        setUpCompany("MO");
        assertThat(mail.enabled()).isFalse();
        Throwable t = catchThrowable(() -> runner.run(comp, () -> mail.publish("PCN:1", Recipient.of("a@b.test"), "s", "b")));
        assertThat(t).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) t).code()).isEqualTo(HbErrorCode.MAIL_DISABLED);
        assertThat(HbErrorCode.MAIL_DISABLED.status().value()).isEqualTo(409);
        assertThat(accounts.query(comp, "select count(*) from sys_outbox where topic = 'MAIL'", Integer.class)).isZero();
    }
}
