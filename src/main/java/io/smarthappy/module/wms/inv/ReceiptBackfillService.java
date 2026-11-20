package io.smarthappy.module.wms.inv;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import io.smarthappy.module.wms.inv.ReceiptPostingMapper.BackfillRun;
import io.smarthappy.platform.common.db.PgErrors;
import io.smarthappy.platform.common.web.BusinessException;
import tools.jackson.databind.json.JsonMapper;

/**
 * 생산 입고 이관 (docs/30 11.4) — 그 회사의 기존 prd_receipt 를 원래 발생 시각 그대로 재고 원장에 옮긴다.
 * 입고마다 따로 트랜잭션(실패한 행이 다른 행을 막지 않게), 실패는 이유와 함께 결과 행에 남기고 건너뛴다.
 * 몇 번을 돌려도 같다(멱등 키) — 다시 돌리면 실패했던 행만 다시 시도된다. 마감된 달은 실패로 남긴다(우회 없음).
 */
@Service
public class ReceiptBackfillService {

    static final int MAX_DETAIL = 200;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    public record Preview(int total, int posted, int pending, BackfillRun lastRun) {
    }

    private final ReceiptPostingMapper mapper;
    private final ProductionReceiptPosting posting;
    private final TransactionTemplate perRow;
    private final TransactionTemplate tx;

    public ReceiptBackfillService(ReceiptPostingMapper mapper, ProductionReceiptPosting posting, PlatformTransactionManager tm) {
        this.mapper = mapper;
        this.posting = posting;
        this.perRow = new TransactionTemplate(tm);
        this.perRow.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.tx = new TransactionTemplate(tm);
    }

    @Transactional(readOnly = true)
    public Preview preview() {
        int total = mapper.countReceipts();
        int posted = mapper.countPosted();
        return new Preview(total, posted, total - posted, mapper.lastRun());
    }

    /** 요청 스레드에서(트랜잭션 밖) — 입고마다 새 트랜잭션 */
    public BackfillRun run() {
        List<Long> ids = tx.execute(s -> mapper.allReceiptIds());
        int posted = 0;
        int existed = 0;
        List<Map<String, Object>> fails = new ArrayList<>();
        int failed = 0;
        for (Long id : ids == null ? List.<Long>of() : ids) {
            try {
                Boolean fresh = perRow.execute(s -> {
                    boolean had = mapper.trxOf(ProductionReceiptPosting.key(id)) != null;
                    posting.post(id, true);
                    return !had;
                });
                if (Boolean.TRUE.equals(fresh)) {
                    posted++;
                } else {
                    existed++;
                }
            } catch (RuntimeException e) {
                failed++;
                if (fails.size() < MAX_DETAIL) {
                    Map<String, Object> f = new LinkedHashMap<>();
                    f.put("receiptId", id);
                    f.put("code", reason(e));
                    fails.add(f);
                }
            }
        }
        int p = posted;
        int x = existed;
        int fl = failed;
        String detail = JSON.writeValueAsString(fails);
        return tx.execute(s -> {
            mapper.insertRun(p + x + fl, p, x, fl, detail, InventoryService.me().loginId());
            return mapper.lastRun();
        });
    }

    /** 실패 이유 = 오류 코드(칸 오류면 첫 칸의 코드) 또는 DB 제약 이름 — 사용자 입력·SQL 은 넣지 않는다 */
    static String reason(RuntimeException e) {
        if (e instanceof BusinessException b) {
            if (!b.fieldErrors().isEmpty()) {
                return b.fieldErrors().get(0).code();
            }
            return b.code().name();
        }
        return PgErrors.constraint(e).orElse(PgErrors.sqlState(e).orElse("ERROR"));
    }
}
