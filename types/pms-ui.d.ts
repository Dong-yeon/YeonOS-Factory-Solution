/*
 * PMS 화면 공용 표시(static/js/views/pms/pms-ui.js) 타입 — PMS 모듈 전용, 공통 계약(hb.d.ts)이 아니다.
 */
interface PmsUi {
    /** 저장 안 한 입력이 있으면 묻는다 — 없으면 바로 true. dirty 생략 = hb.dirty */
    leaveOk(dirty?: boolean): Promise<boolean>;
    ask(title: string, message: string | null, ok: string, danger?: boolean): Promise<boolean>;
    /** 사유 입력(500자) — 취소 = null */
    reason(title: string, label: string, ok: string, required?: boolean): Promise<string | null>;
    /** 상태 배지 — kind = project·task·deliv·review·decision·issue·severity·day */
    badge(kind: string, code: string | null | undefined, text?: string, title?: string | null): HTMLSpanElement;
    /** 공수 글자 "12h · 1.5MD" — 저장은 시간, MD = 시간 ÷ 회사 하루 시간(docs/48 R-2). 빈 값 = "" */
    hours(h: number | string | null | undefined, hoursPerDay: number): string;
    /** 빈 서식 단추 "서식 FM-001" — 누르면 QMS 관리문서 화면을 그 번호로 연다(docs/32 4.17). PMS 는 파일을 갖지 않는다 */
    formLink(docNo: string): HTMLButtonElement;
    /** 선후행 종류 꼬리표 — FS = "", SS·FF = " · SS(시작→시작)" (docs/32 4.27) */
    depTypeTag(type: string | null | undefined): string;
    TONES: Record<string, Record<string, HB.StatusTone>>;
}

interface Window {
    pmsUi: PmsUi;
}

declare var pmsUi: PmsUi;
