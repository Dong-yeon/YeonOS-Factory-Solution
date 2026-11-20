package io.smarthappy.module.cms;

import java.util.List;

import org.springframework.stereotype.Component;

import io.smarthappy.module.cms.link.BreakdownReport;
import lombok.RequiredArgsConstructor;

/** POP 고장 신고 계약 구현 (docs/33 11.16, C20) — 기록은 보전 작업지시(MwoService) 규칙 그대로 */
@Component
@RequiredArgsConstructor
class CmsBreakdownReport implements BreakdownReport {

    private final MwoService mwos;

    @Override
    public List<Symptom> symptoms(long equipId) {
        return mwos.popSymptoms(equipId);
    }

    @Override
    public ReportResult report(Report report) {
        return mwos.reportFromPop(report);
    }

    @Override
    public PhotoResult attachPhoto(Photo photo) {
        return mwos.attachPopPhoto(photo);
    }

    @Override
    public PhotoLimits photoLimits() {
        return mwos.popPhotoLimits();
    }
}
