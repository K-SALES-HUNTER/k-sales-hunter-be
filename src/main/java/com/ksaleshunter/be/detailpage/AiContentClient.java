package com.ksaleshunter.be.detailpage;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 상세페이지·이미지가 부르는 AI 내부 API.
 *
 * <pre>
 *   POST /internal/ai/content-jobs            상세페이지 생성 잡
 *   GET  /internal/ai/content-jobs/{jobId}    폴링 (JobState)
 *   POST /internal/ai/content/retranslate     수정본 재번역 (동기)
 *   POST /internal/ai/image-jobs              이미지 생성 잡
 *   GET  /internal/ai/image-jobs/{jobId}      폴링 (JobState)
 *   POST /internal/ai/image-jobs/{jobId}/cancel
 * </pre>
 * 요청·응답 형식은 AI 레포 contracts/v1/*.schema.json 이 기준이다. 결과는 필요한 필드만 읽도록 JsonNode 로 받는다.
 */
@Component
public class AiContentClient {

	private final RestClient ai; // config/AiClientConfig 단일 빈 (내부 토큰 포함)

	public AiContentClient(RestClient aiRestClient) {
		this.ai = aiRestClient;
	}

	/** AI JobState 중 화면이 쓰는 부분. status: QUEUED·RUNNING·CANCEL_REQUESTED·COMPLETED·FAILED·CANCELLED */
	public record JobState(String status, String step, double progress, JsonNode result, String errorCode) {

		public boolean completed() {
			return "COMPLETED".equals(status);
		}

		public boolean failed() {
			return "FAILED".equals(status) || "CANCELLED".equals(status);
		}

		static JobState from(JsonNode node) {
			JsonNode error = node.path("error");
			return new JobState(node.path("status").asText(), node.path("step").asText(null),
				node.path("progress").asDouble(0), node.get("result"),
				error.isMissingNode() || error.isNull() ? null : error.path("code").asText(null));
		}
	}

	public record Translated(String name, String description) {
	}

	public void submitContentJob(Map<String, Object> command) {
		ai.post().uri("/internal/ai/content-jobs").body(command).retrieve().toBodilessEntity();
	}

	public JobState contentJob(String jobId) {
		return job("/internal/ai/content-jobs/{jobId}", jobId);
	}

	public Translated retranslate(String country, String locale, String name, String description,
			String previousName, String previousDescription) {
		return ai.post().uri("/internal/ai/content/retranslate")
			.body(Map.of("country", country, "locale", locale, "name", name, "description", description,
					"previousName", previousName, "previousDescription", previousDescription))
			.retrieve().body(Translated.class);
	}

	public void submitImageJob(String jobId, String target, String prompt, String mode, String modelId,
			List<String> referenceUrls) {
		var command = new HashMap<String, Object>();
		command.put("jobId", jobId);
		command.put("target", target);
		command.put("prompt", prompt);
		command.put("mode", mode);
		command.put("modelId", modelId);
		command.put("referenceUrls", referenceUrls);
		ai.post().uri("/internal/ai/image-jobs").body(command).retrieve().toBodilessEntity();
	}

	public JobState imageJob(String jobId) {
		return job("/internal/ai/image-jobs/{jobId}", jobId);
	}

	public void cancelImageJob(String jobId) {
		try {
			ai.post().uri("/internal/ai/image-jobs/{jobId}/cancel", jobId).retrieve().toBodilessEntity();
		}
		catch (HttpClientErrorException.NotFound e) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "이미지 생성 작업이 없습니다");
		}
	}

	private JobState job(String uri, String jobId) {
		try {
			return JobState.from(ai.get().uri(uri, jobId).retrieve().body(JsonNode.class));
		}
		catch (HttpClientErrorException.NotFound e) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "생성 작업이 없습니다");
		}
	}
}
