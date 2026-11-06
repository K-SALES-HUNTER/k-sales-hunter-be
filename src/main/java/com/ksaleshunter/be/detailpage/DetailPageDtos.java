package com.ksaleshunter.be.detailpage;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** 상세페이지·이미지 요청/응답. 형식은 AI 레포 docs/API_SPEC_FE기준.md §2-4 와 FE src/apis 가 기준이다. */
public final class DetailPageDtos {

	private DetailPageDtos() {
	}

	public record JobAccepted(String jobId) {
	}

	/** PATCH .../detail-page/content. retranslate 를 빼면 현지어본도 다시 옮긴다. */
	public record ContentEditRequest(@NotBlank String name, @NotNull String description, Boolean retranslate) {

		boolean shouldRetranslate() {
			return retranslate == null || retranslate;
		}
	}

	/** 화면의 이미지 한 장. id 는 detail_page_images.id 문자열이다. */
	public record ImageItem(String id, String label, String src, String prompt) {
	}

	/** POST .../images/generated — 생성 결과 확정. generatedImageId 는 이미지 잡 id. */
	public record GeneratedImageRequest(@NotBlank String generatedImageId, String target, String replaceImageId) {
	}

	public record MainImageRequest(@NotBlank String imageId) {
	}

	public record MainImageResponse(String mainImageId) {
	}

	/** POST .../images/generate — FE GenerateImageParams 그대로. target·mode 는 소문자로 온다. */
	public record GenerateImageRequest(String target, @NotBlank String prompt, String mode, String modelId,
			List<String> referenceIds) {
	}

	/** GET /image-jobs/{jobId}. status: RUNNING · DONE · FAILED · CANCELLED */
	public record ImageJobView(String status, double progress, ImageResult result, String errorCode) {
	}

	public record ImageResult(String id, String src, String prompt) {
	}
}
