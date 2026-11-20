package io.smarthappy.module.plm;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.smarthappy.platform.esign.ESignContentSource;

/** PLM 서명 대상(ECO·PPAP)의 지금 내용 해시 — 기록 무결성 점검(docs/52 Y1)이 서명 원장과 대조한다 */
@Configuration(proxyBeanMethods = false)
class PlmSignSources {

    @Bean
    ESignContentSource plmEcoSignSource(EcoService ecos) {
        return ESignContentSource.of(PlmSignContent.ECO, ecos::signHashes);
    }

    @Bean
    ESignContentSource plmPpapSignSource(PpapService ppaps) {
        return ESignContentSource.of(PlmSignContent.PPAP, ppaps::signHashes);
    }
}
