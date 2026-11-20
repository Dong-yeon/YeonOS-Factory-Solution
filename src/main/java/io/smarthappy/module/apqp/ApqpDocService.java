package io.smarthappy.module.apqp;

import static io.smarthappy.module.apqp.jooq.Tables.APQP_QMS_DOC;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

import org.jooq.DSLContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.apqp.ApqpDtos.FmeaRow;
import io.smarthappy.module.apqp.CpDtos.CpRow;
import io.smarthappy.module.qms.link.DocLookup;
import io.smarthappy.module.qms.link.DocPort;
import io.smarthappy.module.qms.link.DocPort.DocDraft;
import io.smarthappy.module.qms.link.DocPort.DocFile;
import io.smarthappy.module.qms.link.DocPort.DocRevRef;
import io.smarthappy.platform.auth.Action;
import io.smarthappy.platform.auth.PermissionService;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.security.CurrentUser;
import io.smarthappy.platform.security.LoginUser;
import io.smarthappy.platform.tenant.TenantContext;
import lombok.RequiredArgsConstructor;

/**
 * 확정 PFMEA·관리계획서 → QMS 관리문서 (A3-3, docs/41 11.8 B1~B7). 단추로 올린다(B1): A4 양식 엑셀(바이트)을 QMS 계약
 * {@link DocPort#publish} 에 넘기면 QMS 가 작성 중 리비전을 만들고 파일을 붙인다(B2 — 결재·배포는 QMS 쪽 사람).
 * 같은 번호의 이전 리비전이 올린 문서가 있으면 그 문서의 새 리비전(B3), 결과는 apqp_qms_doc 에 한 줄(B4), sourceKey 로 멱등(B5).
 * 권한 = 화면 PRINT(컨트롤러) + QMS 문서 화면 SAVE(여기 — 부르는 쪽 확인이 계약 규칙, B6), 현행 확정본만(B7).
 * DFMEA(A5-2, docs/41 13.9)도 같은 길 — 종류 FMEA(같은 표 apqp_fmea), QMS 문서 유형만 DFMEA(QMS V213), 화면 권한은 apqp-dfmea
 */
@Service
@RequiredArgsConstructor
public class ApqpDocService {

    static final String FMEA = "FMEA";
    static final String CP = "CP";
    /** QMS 관리문서 화면 — 작성 중 리비전을 만드는 것이므로 SAVE */
    static final String DOC_PROG = "qms-doc";

    private final ApqpRepository fmeas;
    private final CpRepository cps;
    private final ApqpFormService forms;
    private final PermissionService permission;
    private final DSLContext dsl;
    private final ObjectProvider<DocPort> docPort;
    private final ObjectProvider<DocLookup> docLookup;

    /** 화면 표시 — linked = 올린 QMS 리비전(없으면 null), canPublish = 지금 올릴 수 있나(권한·상태·아직 안 올림) */
    public record DocState(DocView linked, boolean canPublish) {
    }

    /** status = QMS 리비전 상태(DRAFT·IN_APPROVAL·RELEASED·OBSOLETE, QMS 에서 못 읽으면 null) */
    public record DocView(String docNo, String rev, String status, long docRevId) {
    }

    /** docType = QMS 문서 유형(PFMEA·DFMEA·CP), prog = 원본 화면(PRINT 확인) */
    private record Src(String kind, long id, String no, int rev, String status, String title, long itemId, String docType, String prog) {
    }

    @Transactional(readOnly = true)
    public DocState state(String kind, long id) {
        Src s = src(kind, id);
        DocView v = linked(kind, id);
        boolean can = v == null && "RELEASED".equals(s.status()) && permission.actions(s.prog()).contains(Action.PRINT)
                && permission.actions(DOC_PROG).contains(Action.SAVE);
        return new DocState(v, can);
    }

    @Transactional
    public DocView publish(String kind, long id) {
        if (!permission.actions(DOC_PROG).contains(Action.SAVE)) {
            throw new BusinessException(HbErrorCode.NO_PERMISSION);
        }
        // 잠금 순서: APQP 행 → QMS(문서 머리) — QMS 는 APQP 행을 잠그지 않는다(DP-6)
        String status = FMEA.equals(kind) ? fmeas.lockFmea(id) : cps.lockCp(id);
        if (status == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        DocView done = linked(kind, id);
        if (done != null) {
            return done;
        }
        Src s = src(kind, id);
        if (!"RELEASED".equals(s.status())) {
            throw new BusinessException(FMEA.equals(kind) ? HbErrorCode.APQP_FMEA_STATE : HbErrorCode.APQP_CP_STATE);
        }
        ApqpFormService.Form form;
        try {
            form = FMEA.equals(kind) ? forms.fmea(id) : forms.cp(id);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        String docNo = dsl.select(APQP_QMS_DOC.DOC_NO).from(APQP_QMS_DOC)
                .where(APQP_QMS_DOC.SRC_KIND.eq(kind)).and(APQP_QMS_DOC.SRC_NO.eq(s.no()))
                .orderBy(APQP_QMS_DOC.SRC_REV.desc()).limit(1).fetchOne(APQP_QMS_DOC.DOC_NO);
        String title = s.title() == null || s.title().isBlank() ? s.no() : s.title();
        DocRevRef ref;
        try {
            ref = DocPort.of(docPort).publish(new DocDraft("APQP:" + kind + ":" + id, docNo, s.docType(),
                    title.length() > 200 ? title.substring(0, 200) : title, s.itemId(),
                    s.docType() + " " + s.no() + " rev " + s.rev(), List.of(new DocFile(form.fileName(), form.content()))));
        } catch (BusinessException ex) {
            // 연결된 QMS 문서가 사용 중지됐으면 막기는 그대로(QMS 정책), 칸 오류 대신 알아볼 수 있는 문구로(QA #495 관찰 b)
            if (docNo != null && ex.fieldErrors().stream().anyMatch(e -> "docNo".equals(e.field()) && "INACTIVE".equals(e.code()))) {
                throw new BusinessException(HbErrorCode.APQP_QMS_DOC_INACTIVE, docNo);
            }
            throw ex;
        }
        String by = CurrentUser.get().map(LoginUser::loginId).orElseThrow();
        dsl.insertInto(APQP_QMS_DOC)
                .set(APQP_QMS_DOC.COMP_CD, TenantContext.requireCompCd())
                .set(APQP_QMS_DOC.SRC_KIND, kind).set(APQP_QMS_DOC.SRC_ID, id).set(APQP_QMS_DOC.SRC_NO, s.no()).set(APQP_QMS_DOC.SRC_REV, s.rev())
                .set(APQP_QMS_DOC.DOC_ID, ref.docId()).set(APQP_QMS_DOC.DOC_NO, ref.docNo()).set(APQP_QMS_DOC.DOC_REV_ID, ref.docRevId())
                .set(APQP_QMS_DOC.CREATED_BY, by).set(APQP_QMS_DOC.UPDATED_BY, by)
                .execute();
        return new DocView(ref.docNo(), ref.rev(), ref.status(), ref.docRevId());
    }

    private DocView linked(String kind, long id) {
        var r = dsl.select(APQP_QMS_DOC.DOC_NO, APQP_QMS_DOC.DOC_REV_ID).from(APQP_QMS_DOC)
                .where(APQP_QMS_DOC.SRC_KIND.eq(kind)).and(APQP_QMS_DOC.SRC_ID.eq(id)).fetchOne();
        if (r == null) {
            return null;
        }
        long revId = r.value2();
        return DocLookup.of(docLookup).rev(revId).map(v -> new DocView(v.docNo(), v.rev(), v.status(), revId))
                .orElse(new DocView(r.value1(), null, null, revId));
    }

    private Src src(String kind, long id) {
        if (FMEA.equals(kind)) {
            FmeaRow f = fmeas.findFmea(id);
            if (f == null) {
                throw new BusinessException(HbErrorCode.NOT_FOUND);
            }
            return new Src(kind, id, f.getFmeaNo(), f.getRev(), f.getStatus(), f.getTitle(), f.getItemId(), f.getFmeaType(),
                    FmeaService.prog(f.getFmeaType()));
        }
        CpRow c = cps.findCp(id);
        if (c == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        return new Src(kind, id, c.getCpNo(), c.getRev(), c.getStatus(), c.getTitle(), c.getItemId(), "CP", CpService.PROG);
    }
}
