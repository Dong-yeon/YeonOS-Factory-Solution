package io.smarthappy.platform.tenant;

import javax.sql.DataSource;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.transaction.autoconfigure.TransactionManagerCustomizers;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/** Boot 기본 JdbcTransactionManager 대신 회사 범위를 설정하는 트랜잭션 매니저를 등록한다 */
@Configuration(proxyBeanMethods = false)
public class TenantTransactionConfig {

    @Bean
    public PlatformTransactionManager transactionManager(DataSource dataSource,
            ObjectProvider<TransactionManagerCustomizers> customizers) {
        TenantAwareTransactionManager tm = new TenantAwareTransactionManager(dataSource);
        customizers.ifAvailable(c -> c.customize(tm));
        return tm;
    }
}
