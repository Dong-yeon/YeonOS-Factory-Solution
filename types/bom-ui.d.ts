/** BOM 네 화면 공용(views/master/bom-ui.js, docs/60 X2·X3·X5) — 공통(hb.*)이 아니라 BOM 화면 전용 */
declare var bomUi: {
    /** 다른 BOM 화면을 그 조건(q.이름)으로 — 셸 탭, 셸 밖이면 이동 */
    open(progCd: 'mst-bom' | 'mst-bom-tree' | 'mst-bom-where' | 'mst-bom-replace', params: Record<string, string>): void;
    /** 주소 링크(?q.필드)로 새로 열렸고 그 칸이 채워졌으면 조회 */
    linked(sp: HB.SearchPanel, field: string): Promise<unknown> | null;
    /** "없는 품목" 칸 오류를 품목 코드가 들어간 문구로 */
    errorText(err: HB.ApiError, field: string, typed: string | null | undefined): string;
};
