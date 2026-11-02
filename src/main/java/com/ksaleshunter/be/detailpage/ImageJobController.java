package com.ksaleshunter.be.detailpage;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.ksaleshunter.be.detailpage.DetailPageDtos.ImageJobView;

/** 이미지 생성 잡 폴링·취소 (FE DetailImagePage). 취소는 생성 중이어도 바로 끊긴다. */
@RestController
@RequestMapping("/image-jobs/{jobId}")
public class ImageJobController {

	private final DetailPageImageService detailImages;

	public ImageJobController(DetailPageImageService detailImages) {
		this.detailImages = detailImages;
	}

	@GetMapping
	public ImageJobView get(@PathVariable String jobId, @AuthenticationPrincipal Long userId) {
		return detailImages.job(userId, jobId);
	}

	@PostMapping("/cancel")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void cancel(@PathVariable String jobId, @AuthenticationPrincipal Long userId) {
		detailImages.cancel(userId, jobId);
	}
}
