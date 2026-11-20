package io.smarthappy.platform.portal;

import java.util.List;

/**
 * 포털 메뉴를 내놓는 모듈 (빈으로 등록). 플랫폼은 홈·내 정보, SRM 은 발주 회신·ASN·납품서(docs/42 7장 3).
 * 메뉴는 모든 협력사 사용자에게 같다 — 협력사별 권한이 없다(1차). 화면·API 의 협력사 거름은 각 서비스가 PortalUser.current() 로.
 */
public interface PortalMenuSource {

    List<PortalMenuItem> items();
}
