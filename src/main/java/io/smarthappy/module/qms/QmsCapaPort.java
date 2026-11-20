package io.smarthappy.module.qms;

import java.util.Optional;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.qms.CapaDtos.CapaForm;
import io.smarthappy.module.qms.link.CapaPort;
import io.smarthappy.platform.time.BizClock;

/**
 * 계약 {@link CapaPort} 구현 — 만들기는 {@link CapaService#create} 그대로(채번·기한·CSR 규칙), 조회는 매퍼만(예외 없음).
 */
@Component
public class QmsCapaPort implements CapaPort {

    private final CapaService capas;
    private final CapaMapper mapper;
    private final BizClock clock;

    public QmsCapaPort(CapaService capas, CapaMapper mapper, BizClock clock) {
        this.capas = capas;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public long createInternal(InternalRequest req) {
        String problem = req.problem() == null ? null : req.problem().length() > 4000 ? req.problem().substring(0, 4000) : req.problem();
        return capas.create(new CapaForm("INTERNAL", null, req.itemCd(), req.title(), null, req.ownerUserId(), null, problem,
                null, null, null, null, null, null, null, null, null));
    }

    @Override
    public Optional<CapaView> capa(long capaId) {
        return Optional.ofNullable(mapper.findById(capaId, clock.today()))
                .map(c -> new CapaView(c.getCapaId(), c.getCapaNo(), c.getTitle(), c.getStatus()));
    }
}
