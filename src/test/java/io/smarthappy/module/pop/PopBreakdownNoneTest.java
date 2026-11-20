package io.smarthappy.module.pop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import io.smarthappy.module.cms.link.BreakdownReport;
import io.smarthappy.module.pop.PopDtos.PopWorker;
import io.smarthappy.module.pop.PopWorkDtos.BreakdownEquip;
import io.smarthappy.module.pop.PopWorkDtos.BreakdownForm;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.HbErrorCode;

/**
 * CMS 구현이 없을 때(NONE, docs/25 4.35) — 버튼 숨김 플래그 거짓, 현상 빈 목록, 신고 409. 전체 문맥에는 CMS 빈이 늘 있어 단위로 본다.
 */
class PopBreakdownNoneTest {

    private static final TerminalPrincipal T = new TerminalPrincipal("XA", 7L, "TAB-01", "TAB-01", "P1", null, null, null, "POP");
    private static final PopWorker W = new PopWorker(7L, 3L, "E1", "홍작업", 0L, 0L);

    private static ObjectProvider<BreakdownReport> empty() {
        return new StaticListableBeanFactory().getBeanProvider(BreakdownReport.class);
    }

    private static ObjectProvider<BreakdownReport> with(BreakdownReport r) {
        StaticListableBeanFactory f = new StaticListableBeanFactory();
        f.addBean("cmsBreakdown", r);
        return f.getBeanProvider(BreakdownReport.class);
    }

    @Test
    void 버튼은_POP_단말이고_구현이_있을_때만() {
        BreakdownReport real = mock(BreakdownReport.class);
        assertThat(PopService.breakdownOn("POP", empty())).isFalse();
        assertThat(PopService.breakdownOn("POP", with(real))).isTrue();
        assertThat(PopService.breakdownOn("PDA", with(real))).isFalse();
        assertThat(PopService.breakdownOn(null, with(real))).isFalse();
    }

    @Test
    void NONE_이면_현상_빈_목록_신고는_409() {
        PopWorkMapper mapper = mock(PopWorkMapper.class);
        BreakdownEquip e = new BreakdownEquip();
        e.setEquipId(11L);
        e.setPlantCd("P1");
        e.setUseYn("Y");
        when(mapper.findBreakdownEquip(11L)).thenReturn(e);
        PopBreakdownService svc = new PopBreakdownService(mapper, empty());
        assertThat(svc.symptoms(T, W, 11L)).isEmpty();
        Throwable t = catchThrowable(() -> svc.report(T, W, new BreakdownForm(11L, "LEAK", null, "req-00000001")));
        assertThat(t).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) t).code()).isEqualTo(HbErrorCode.CONFLICT);
    }
}
