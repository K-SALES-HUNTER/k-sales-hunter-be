package com.ksaleshunter.be.copilot;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

import com.ksaleshunter.be.copilot.CopilotDtos.ActionResult;
import com.ksaleshunter.be.copilot.CopilotDtos.AiCopilotRequest;
import com.ksaleshunter.be.copilot.CopilotDtos.AiCopilotResponse;
import com.ksaleshunter.be.copilot.CopilotDtos.CopilotMessageRequest;
import com.ksaleshunter.be.copilot.CopilotDtos.CopilotMessageResponse;

import jakarta.validation.Valid;

/**
 * AI 패널 프록시 (R-005 전략 고도화 코파일럿).
 *
 * <p>역할 분담
 * <ul>
 *   <li>Spring: 대화 저장(chat_messages), 화면 컨텍스트 조립, 명령 3종의 잡 생성, 버전 관리</li>
 *   <li>AI(/internal/ai/copilot/messages): gpt-4o 도구 호출로 답변 생성, 명령이면 action 반환</li>
 * </ul>
 * 숫자 계산은 여기서도 AI 쪽에서도 LLM 이 하지 않는다. "가격 낮추면 마진?" 같은 질문은
 * AI 가 pricing_quote 도구(마진 엔진)를 불러 답한다.
 *
 * <p>잡 생성을 Python 이 아니라 Spring 이 하는 이유: 보고서 버전·이력(reports, country_reports)이
 * Spring 소유라, 재분석 결과를 새 버전으로 저장하는 흐름을 한 곳에 두기 위함이다.
 */
@RestController
@RequestMapping("/products/{productId}/copilot")
public class CopilotController {

	private final RestClient aiRestClient;          // config/AiClientConfig 단일 빈 (내부 토큰 포함)
	private final ChatMessageRepository chatRepo;
	private final CopilotContextAssembler contexts; // 화면별 보고서·판매정보 요약 JSON
	private final ObjectProvider<CopilotJobLauncher> jobs; // 명령 3종 → 분석·상세페이지 잡 (구현 등록 시)

	public CopilotController(RestClient aiRestClient, ChatMessageRepository chatRepo,
			CopilotContextAssembler contexts, ObjectProvider<CopilotJobLauncher> jobs) {
		this.aiRestClient = aiRestClient;
		this.chatRepo = chatRepo;
		this.contexts = contexts;
		this.jobs = jobs;
	}

	/**
	 * 코파일럿에 메시지를 보낸다. FE 의 useAiChatStore.send 가 호출한다.
	 *
	 * @param productId 대화 중인 상품. 화면을 옮겨도 같은 상품이면 대화가 이어진다
	 * @param userId    JWT 필터가 SecurityContext 에 넣어 둔 사용자 ID
	 * @param req       메시지, 현재 화면(type·국가), 최근 10턴
	 * @return 답변과, 명령이었으면 생성된 잡 정보(프론트가 jobId 로 진행 상태를 폴링)
	 */
	@PostMapping("/messages")
	@Transactional
	public CopilotMessageResponse send(@PathVariable Long productId,
			@AuthenticationPrincipal Long userId,          // JWT 필터가 principal 에 userId 를 넣는다
			@RequestBody @Valid CopilotMessageRequest req) {
		// 1) 사용자 발화를 먼저 저장한다. AI 호출이 실패해도 질문 기록은 남는다
		chatRepo.save(ChatMessage.user(userId, productId, req.message()));

		// 2) 현재 화면에 필요한 요약만 붙여 AI 에 보낸다 (Python 은 업무 DB 를 읽지 않는다)

		var aiReq = new AiCopilotRequest(req.message(), req.page().type(), req.page().countryCode(),
				contexts.build(productId, req.page()), req.history(),
				contexts.productSnapshot(productId), contexts.marketProfile(userId));
		AiCopilotResponse ai = aiRestClient.post().uri("/internal/ai/copilot/messages")
			.body(aiReq).retrieve().body(AiCopilotResponse.class);

		// 3) 답변과 의도(QUERY/COMMAND/CLARIFY)를 저장한다
		chatRepo.save(ChatMessage.assistant(userId, productId, ai.answer(), ai.intent()));

		// 4) 명령이면 해당 잡만 새로 만든다. 구현 빈이 아직 없으면 답변만 돌려준다(jobId = null)
		ActionResult action = null;
		CopilotJobLauncher launcher = jobs.getIfAvailable();
		if (ai.action() != null) {                      // 명령 3종만. 잡 생성은 Spring 소유
			String country = ai.action().params() == null ? null : ai.action().params().get("country");
			String jobId = launcher == null ? null : switch (ai.action().type()) {
				case "REANALYZE" -> launcher.reanalyze(productId);
				case "ADD_COUNTRY" -> launcher.addCountry(productId, country);
				case "REGENERATE_DETAIL" -> launcher.regenerateDetail(productId, country);
				default -> null;
			};
			action = new ActionResult(ai.action().type(), jobId, ai.action().description());
		}
		return new CopilotMessageResponse(ai.answer(), action);
	}
}
