package io.smarthappy.module.qms;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.smarthappy.platform.esign.ESignContentSource;

/** QMS 서명 대상의 지금 내용 해시 — 기록 무결성 점검(docs/52 Y1)이 서명 원장과 대조한다 */
@Configuration(proxyBeanMethods = false)
class QmsSignSources {

    @Bean
    ESignContentSource qmsNcrSignSource(NcrService ncrs) {
        return ESignContentSource.of(QmsESign.NCR, ncrs::signHashes);
    }

    @Bean
    ESignContentSource qmsGaugeSignSource(GaugeCalibService gauges) {
        return ESignContentSource.of(QmsESign.GAUGE, gauges::signHashes);
    }

    @Bean
    ESignContentSource qmsChangeSignSource(ChangeRecordService records) {
        return ESignContentSource.of(QmsESign.CHANGE, records::signHashes);
    }
}
