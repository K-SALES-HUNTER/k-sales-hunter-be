package com.ksaleshunter.be.copilot;

/**
 * 코파일럿 명령 3종을 실제 잡으로 바꾸는 포트.
 *
 * 분석 잡(analysis 프록시)과 상세페이지 잡(detail-page)이 각자 구현을 등록한다.
 * 코파일럿은 어떤 잡을 만들지만 고르고, 버전·이력 관리는 각 라우트가 소유한다.
 *
 * @return 생성된 AI 잡 ID. 프론트는 이 ID 로 진행 상태를 폴링한다.
 */
public interface CopilotJobLauncher {

	/** REANALYZE — 변경 필드 기준 부분 재분석 */
	String reanalyze(Long productId);

	/** ADD_COUNTRY — 다른 국가 분석 추가 */
	String addCountry(Long productId, String countryCode);

	/** REGENERATE_DETAIL — 해당 국가 상세페이지 재생성 */
	String regenerateDetail(Long productId, String countryCode);
}
