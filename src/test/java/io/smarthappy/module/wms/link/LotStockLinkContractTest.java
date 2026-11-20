package io.smarthappy.module.wms.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

/**
 * LotStockLink 계약 (docs/30 12.4) — MES 가 ObjectProvider 로 받을 때: WMS 빈이 없으면 NONE(아무것도 안 함, 예외 없음),
 * 있으면 그 빈. 실제 반영(ISSUE_WO·CONV)은 B5 구현과 그 시험에서.
 */
class LotStockLinkContractTest {

    @Test
    void WMS_없는_설치본_빈이_없으면_NONE_으로_아무것도_안_한다() {
        DefaultListableBeanFactory empty = new DefaultListableBeanFactory();
        LotStockLink link = LotStockLink.of(empty.getBeanProvider(LotStockLink.class));
        assertThat(link).isSameAs(LotStockLink.NONE);
        assertThatCode(() -> {
            link.onEdge(1L);
            link.onReverse(2L);
        }).doesNotThrowAnyException();
    }

    @Test
    void WMS_가_있으면_그_빈을_쓴다() {
        List<String> calls = new ArrayList<>();
        DefaultListableBeanFactory wms = new DefaultListableBeanFactory();
        wms.registerSingleton("lotStockLink", new LotStockLink() {
            @Override
            public void onEdge(long genId) {
                calls.add("edge:" + genId);
            }

            @Override
            public void onReverse(long reversalGenId) {
                calls.add("reverse:" + reversalGenId);
            }
        });
        LotStockLink link = LotStockLink.of(wms.getBeanProvider(LotStockLink.class));
        link.onEdge(7L);
        link.onReverse(8L);
        assertThat(calls).containsExactly("edge:7", "reverse:8");
    }
}
