package io.smarthappy;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class YeonApplication {

    public static void main(String[] args) {
        var context = SpringApplication.run(YeonApplication.class, args);
        // 마이그레이션 전용 실행(migrate 프로필): Flyway 적용 뒤 바로 끝낸다 (01 7.1)
        if (context.getEnvironment().matchesProfiles("migrate")) {
            System.exit(SpringApplication.exit(context));
        }
    }
}
