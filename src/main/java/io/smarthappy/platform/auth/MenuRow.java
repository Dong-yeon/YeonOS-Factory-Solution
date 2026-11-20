package io.smarthappy.platform.auth;

import lombok.Getter;
import lombok.Setter;

/** sys_menu + 프로그램 이름 키 */
@Getter
@Setter
public class MenuRow {
    private long menuId;
    private Long parentId;
    private String menuNm;
    private String menuNmKey;
    private String progCd;
    private String progNmKey;
    private int sortNo;
}
