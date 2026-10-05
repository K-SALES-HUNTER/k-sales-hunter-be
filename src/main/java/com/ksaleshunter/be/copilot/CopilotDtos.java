package com.ksaleshunter.be.copilot;

import java.util.List;
import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 코파일럿 요청·응답. FE(API_SPEC §2-5) 와 AI 계약(contracts/v1/sync.py) 양쪽 모양. */
public final class CopilotDtos {

	private CopilotDtos() {
	}

	// ── FE ↔ Spring ─────────────────────────────────────────────

	/** 현재 화면. type = TOTAL_REPORT | COUNTRY_REPORT | SALES_INFO | DETAIL_PAGE | SALES_OPS */
	public record Page(@NotBlank String type, String countryCode) {
	}

	public record ChatTurn(String role, String text) {
	}

	public record CopilotMessageRequest(
			@NotBlank @Size(max = 1000) String message,
			@NotNull @Valid Page page,
			@Size(max = 10) List<ChatTurn> history) { // 최근 10턴
	}

	public record ActionResult(String type, String jobId, String description) {
	}

	public record CopilotMessageResponse(String answer, ActionResult action) {
	}

	// ── Spring ↔ AI (/internal/ai/copilot/messages) ─────────────

	public record AiCopilotRequest(String message, String page, String countryCode,
			Map<String, Object> context, List<ChatTurn> history,
			Map<String, Object> product, Map<String, Object> marketProfile) {
	}

	public record AiAction(String type, Map<String, String> params, String description) {
	}

	public record AiCopilotResponse(String answer, String intent, AiAction action,
			List<Map<String, Object>> sources) {
	}
}
