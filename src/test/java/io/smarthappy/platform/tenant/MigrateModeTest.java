package io.smarthappy.platform.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.context.WebApplicationContext;

import io.smarthappy.platform.job.SchedulingConfig;
import io.smarthappy.support.PostgresTestBase;

/** 마이그레이션 전용 실행 모드(migrate 프로필): 웹 서버·기동 가드·작업 없이 Flyway 만 (01 7.1) */
@ActiveProfiles({"test", "migrate"})
class MigrateModeTest extends PostgresTestBase {

    @Autowired
    ApplicationContext context;

    @Test
    void 웹_서버도_기동_가드도_작업도_없다() {
        assertThat(context).isNotInstanceOf(WebApplicationContext.class);
        assertThat(context.getBeanNamesForType(DatabaseRoleGuard.class)).isEmpty();
        assertThat(context.getBeanNamesForType(TenantCatalogGuard.class)).isEmpty();
        assertThat(context.getBeanNamesForType(ProdStartupGuard.class)).isEmpty();
        assertThat(context.getBeanNamesForType(SchedulingConfig.class)).isEmpty();
    }
}
