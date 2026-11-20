package io.smarthappy.module.wms.shipnote;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.wms.shipnote.ShipNoteDtos.ClassOpt;
import io.smarthappy.module.wms.shipnote.ShipNoteDtos.MatchRow;
import io.smarthappy.module.wms.shipnote.ShipNoteDtos.Note;
import io.smarthappy.module.wms.shipnote.ShipNoteDtos.SaveForm;
import io.smarthappy.module.wms.shipnote.ShipNoteDtos.ShipNote;
import io.smarthappy.platform.common.query.PageQuery;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.FieldErrors;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.grid.ColumnSpec;
import io.smarthappy.platform.tenant.TenantContext;
import lombok.RequiredArgsConstructor;

/**
 * 출하 특이사항 (docs/56, docs/49 W-4) — 규칙(고객·품목군·품목 조건, 비면 전체) 관리와 출하 지시별 판정·확인.
 * <ul>
 * <li>판정은 SQL 한 곳(ShipNoteMapper.matchOn) — 출하 목록 열·상세·피킹 검사가 같은 조건을 쓴다</li>
 * <li>확인 = 지시 × 규칙 × 규칙 버전(row_ver). 글을 고치면 버전이 올라 다시 확인. 확인은 쌓기만(DB 가 UPDATE·DELETE 거부)</li>
 * <li>확인 필수 검사(피킹·출하 확정)는 {@code ShipService} 가 지시 머리를 잠근 뒤 {@link #unacked} 로</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class ShipNoteService {

    public static final String PROG = "wms-ship-note";
    static final int MAX_TITLE = 100;
    static final int MAX_BODY = 1000;

    static final List<ColumnSpec> COLUMNS = List.of(
            ColumnSpec.of("sortNo", "wmsShipNote.col.sortNo", ColumnSpec.ColumnType.INT, 60).withSort(),
            ColumnSpec.text("title", "wmsShipNote.col.title", 220).withSort().withFlex().withMinWidth(140),
            ColumnSpec.text("custCd", "wmsShipNote.col.custCd", 110).withSort(),
            ColumnSpec.text("custNm", "wmsShipNote.col.cust", 140).withPriority(2),
            ColumnSpec.text("classCd", "wmsShipNote.col.classCd", 110).withPriority(2),
            ColumnSpec.text("itemCd", "wmsShipNote.col.itemCd", 130).withPriority(2),
            ColumnSpec.of("ackRequiredYn", "wmsShipNote.col.ackRequired", ColumnSpec.ColumnType.YN, 80),
            ColumnSpec.of("useYn", "wmsShipNote.col.useYn", ColumnSpec.ColumnType.YN, 60),
            ColumnSpec.of("updatedAt", "wmsShipNote.col.updatedAt", ColumnSpec.ColumnType.DATETIME, 140).withSort().withPriority(3));
    static final List<String> SORT = List.of("sortNo,asc");

    private final ShipNoteMapper mapper;

    // ================================================================== 규칙 관리

    @Transactional(readOnly = true)
    public ShipNoteDtos.Page<Note> grid(String q, String useYn, String ackRequiredYn, Integer page, Integer size, List<String> sort, Boolean total,
            int maxSize) {
        PageQuery pq = PageQuery.parse(page, size, sort, total, ShipNoteDtos.NoteSort.values(), maxSize, SORT);
        FieldErrors e = FieldErrors.create();
        String use = yn(useYn, "useYn", e);
        String ack = yn(ackRequiredYn, "ackRequiredYn", e);
        e.throwIfAny();
        String kw = q == null || q.isBlank() ? null : like(q.strip());
        return new ShipNoteDtos.Page<>(mapper.findPage(kw, use, ack, pq), pq.isWithTotal() ? mapper.count(kw, use, ack) : null);
    }

    public List<ColumnSpec> columns() {
        return COLUMNS;
    }

    @Transactional(readOnly = true)
    public Note view(long id) {
        Note n = mapper.findNote(id);
        if (n == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        return n;
    }

    @Transactional(readOnly = true)
    public List<ClassOpt> classes() {
        return mapper.classes();
    }

    /** 추가(noteId 없음) 또는 수정(rowVer — 다르면 409). 고객·품목군·품목 코드는 이 회사 것만(RLS) */
    @Transactional
    public Note save(SaveForm f) {
        if (f == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        FieldErrors e = FieldErrors.create();
        Note n = new Note();
        n.setCustId(ref(f.custCd(), "custCd", e, mapper::custIdByCode));
        n.setItemClassId(ref(f.classCd(), "classCd", e, mapper::classIdByCode));
        n.setItemId(ref(f.itemCd(), "itemCd", e, mapper::itemIdByCode));
        if (n.getItemClassId() != null && n.getItemId() != null) {
            e.add("itemCd", "NOTE_CLASS_AND_ITEM");          // 품목이 품목군을 이미 정한다 — 둘 다 주면 뜻이 겹친다(docs/56 N-4)
        }
        String title = f.title() == null ? "" : f.title().strip();
        if (title.isEmpty()) {
            e.add("title", "REQUIRED");
        } else if (title.length() > MAX_TITLE) {
            e.add("title", "TOO_LONG", MAX_TITLE);
        }
        String body = f.body() == null || f.body().isBlank() ? null : f.body().strip();
        if (body != null && body.length() > MAX_BODY) {
            e.add("body", "TOO_LONG", MAX_BODY);
        }
        String ack = yn(f.ackRequiredYn(), "ackRequiredYn", e);
        String use = yn(f.useYn(), "useYn", e);
        int sortNo = f.sortNo() == null ? 0 : f.sortNo();
        if (sortNo < 0 || sortNo > 9999) {
            e.add("sortNo", "RANGE", 0, 9999);
        }
        if (f.noteId() != null && f.rowVer() == null) {
            e.add("rowVer", "REQUIRED");
        }
        e.throwIfAny();
        n.setTitle(title);
        n.setBody(body);
        n.setAckRequiredYn(ack == null ? "N" : ack);
        n.setUseYn(use == null ? "Y" : use);
        n.setSortNo(sortNo);
        if (f.noteId() == null) {
            return view(mapper.insert(n, by()));
        }
        n.setNoteId(f.noteId());
        if (mapper.update(n, f.rowVer(), by()) != 1) {
            view(f.noteId());                                   // 없으면 404
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        return view(f.noteId());
    }

    // ================================================================== 출하 지시별

    /** 지시에 걸린 사용 중 규칙(규칙 순서) — 걸린 줄 id·현재 버전 확인 */
    @Transactional(readOnly = true)
    public List<ShipNote> forShip(long shipId) {
        Map<Long, ShipNote> out = new LinkedHashMap<>();
        for (MatchRow r : mapper.matchShip(shipId)) {
            ShipNote n = out.computeIfAbsent(r.getNoteId(), id -> {
                ShipNote x = new ShipNote();
                x.setNoteId(id);
                x.setTitle(r.getTitle());
                x.setBody(r.getBody());
                x.setAckRequiredYn(r.getAckRequiredYn());
                x.setNoteVer(r.getNoteVer());
                x.setCustCd(r.getCustCd());
                x.setClassCd(r.getClassCd());
                x.setItemCd(r.getItemCd());
                x.setAcked(r.getAckedAt() != null);
                x.setAckedAt(r.getAckedAt());
                x.setAckedBy(r.getAckedBy());
                x.setAckWorkerNm(r.getAckWorkerNm());
                return x;
            });
            n.getLineIds().add(r.getShipLineId());
        }
        return new ArrayList<>(out.values());
    }

    /**
     * 확인 필수인데 현재 버전 확인이 없는 규칙 가운데 lineIds 에 걸린 첫 규칙 제목(줄 id → 제목). 비면 통과.
     * 호출자({@code ShipService.pick·confirm})가 지시 머리를 잠근 뒤 부른다
     */
    public Map<Long, String> unacked(long shipId, Set<Long> lineIds) {
        Map<Long, String> out = new LinkedHashMap<>();
        for (ShipNote n : forShip(shipId)) {
            if (!"Y".equals(n.getAckRequiredYn()) || n.isAcked()) {
                continue;
            }
            n.getLineIds().stream().filter(lineIds::contains).forEach(id -> out.putIfAbsent(id, n.getTitle()));
        }
        return out;
    }

    /**
     * 확인 — 지시에 걸린 사용 중 규칙만(아니면 400), 현재 버전으로. 같은 버전 두 번 = 처음 기록 그대로(멱등).
     * 지시 상태(DRAFT·PICKING)는 호출자가 본다
     */
    public void ack(long shipId, long noteId, Long workerId) {
        ShipNote n = forShip(shipId).stream().filter(x -> x.getNoteId() == noteId).findFirst().orElse(null);
        if (n == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE).withFieldErrors(FieldErrors.create().add("noteId", "NOTE_NOT_APPLICABLE").list());
        }
        mapper.insertAck(shipId, noteId, n.getNoteVer(), workerId, by());
    }

    // ------------------------------------------------------------------ 도움

    private static Long ref(String code, String field, FieldErrors e, java.util.function.Function<String, Long> find) {
        if (code == null || code.isBlank()) {
            return null;                                          // 비우면 전체
        }
        Long id = find.apply(code.strip().toUpperCase(Locale.ROOT));
        if (id == null) {
            e.add(field, "NOT_FOUND");
        }
        return id;
    }

    private static String yn(String v, String field, FieldErrors e) {
        if (v == null || v.isBlank()) {
            return null;
        }
        String s = v.strip().toUpperCase(Locale.ROOT);
        if (!s.equals("Y") && !s.equals("N")) {
            e.add(field, "CODE");
            return null;
        }
        return s;
    }

    private static String like(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static String by() {
        return TenantContext.currentActor();
    }
}
