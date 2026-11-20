package io.smarthappy.platform.messaging;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/** 수신 원문 처리 정기 작업 (docs/65 16.2) — 스케줄이 꺼져 있으면(테스트) {@link InboundProcessor#drain} 을 직접 부른다 */
@Component
@RequiredArgsConstructor
public class InboundJobs {

    private final InboundProcessor processor;

    @Scheduled(fixedDelayString = "${happyboot.jobs.inbound-drain-ms:2000}", initialDelayString = "${happyboot.jobs.inbound-drain-ms:2000}")
    public void drain() {
        processor.drainAll();
    }
}
