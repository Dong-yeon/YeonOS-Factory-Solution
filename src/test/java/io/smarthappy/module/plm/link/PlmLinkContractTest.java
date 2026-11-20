package io.smarthappy.module.plm.link;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import io.smarthappy.module.plm.link.EcoTrace.EcoRef;
import io.smarthappy.module.plm.link.EcoTrace.Via;

/**
 * PLM 3a 계약 (docs/39 12.7) — MES 가 ObjectProvider 로 받을 때: PLM 빈이 없으면 NONE(표시·조회 없음, 예외 없음),
 * PLM 이 있으면 그 빈(3b 부터 실제 구현).
 */
class PlmLinkContractTest {

    private static final OffsetDateTime AT = OffsetDateTime.parse("2026-10-20T09:00:00+09:00");

    @Test
    void PLM_없는_설치본_빈이_없으면_NONE_으로_표시도_조회도_없다() {
        DefaultListableBeanFactory empty = new DefaultListableBeanFactory();
        ChangeMarker marker = ChangeMarker.of(empty.getBeanProvider(ChangeMarker.class));
        EcoTrace trace = EcoTrace.of(empty.getBeanProvider(EcoTrace.class));
        assertThat(marker).isSameAs(ChangeMarker.NONE);
        assertThat(trace).isSameAs(EcoTrace.NONE);
        assertThat(marker.mark(1L, 10L, null, AT)).isEmpty();
        // LOT 단위 재작업(지시 없음)
        assertThat(marker.mark(2L, null, null, AT)).isEmpty();
        assertThat(trace.ecosOfLot(1L)).isEmpty();
        // QMS 2부 계약(docs/39 16장)도 PLM 없으면 빈 값
        assertThat(EcoLookup.of(empty.getBeanProvider(EcoLookup.class))).isSameAs(EcoLookup.NONE);
        assertThat(EcoLookup.NONE.eco(1L)).isEmpty();
        assertThat(EcoLookup.NONE.byNo("ECO-1")).isEmpty();
        assertThat(EcoLookup.NONE.ecos(List.of(1L))).isEmpty();
        assertThat(EcoQualityView.of(empty.getBeanProvider(EcoQualityView.class))).isSameAs(EcoQualityView.NONE);
        assertThat(EcoQualityView.NONE.summary(1L)).isEmpty();
        assertThat(trace.markedLots(1L)).isEmpty();
        assertThat(trace.firstLotsOfItem(1L, 10)).isEmpty();
        assertThat(trace.shipChecks(List.of(1L))).isEmpty();
        assertThat(EcoLookup.NONE.ppap(1L)).isEmpty();
        // 품목별 목록(docs/39 24장)도 빈 페이지
        assertThat(EcoLookup.NONE.releasedFor(1L, java.time.LocalDate.now())).isEqualTo(EcoLookup.EcoPage.EMPTY);
        assertThat(EcoLookup.NONE.approvedFor(1L, java.time.LocalDate.now())).isEqualTo(EcoLookup.EcoPage.EMPTY);
    }

    @Test
    void PLM_이_있으면_그_빈을_쓴다() {
        // 3b: PLM 의 실제 빈(EcoChangeMarker·EcoLotTrace)으로 바뀌어도 MES 쪽 코드는 그대로 그 빈을 받는다 — DB 동작은 PlmEcoLotTest
        DefaultListableBeanFactory plm = new DefaultListableBeanFactory();
        EcoRef ref = new EcoRef(7L, "ECO2610-0001", "브래킷 두께", null, "NA", Via.REVISION);
        plm.registerSingleton("changeMarker", (ChangeMarker) (lot, wo, eq, at) -> Optional.of(7L));
        plm.registerSingleton("ecoTrace", (EcoTrace) lot -> List.of(ref));
        assertThat(ChangeMarker.of(plm.getBeanProvider(ChangeMarker.class)).mark(1L, null, null, AT)).contains(7L);
        assertThat(EcoTrace.of(plm.getBeanProvider(EcoTrace.class)).ecosOfLot(1L)).containsExactly(ref);
    }
}
