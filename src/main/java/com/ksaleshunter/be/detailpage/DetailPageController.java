package com.ksaleshunter.be.detailpage;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.fasterxml.jackson.databind.JsonNode;
import com.ksaleshunter.be.detailpage.DetailPageDtos.ContentEditRequest;
import com.ksaleshunter.be.detailpage.DetailPageDtos.GenerateImageRequest;
import com.ksaleshunter.be.detailpage.DetailPageDtos.GeneratedImageRequest;
import com.ksaleshunter.be.detailpage.DetailPageDtos.ImageItem;
import com.ksaleshunter.be.detailpage.DetailPageDtos.JobAccepted;
import com.ksaleshunter.be.detailpage.DetailPageDtos.MainImageRequest;
import com.ksaleshunter.be.detailpage.DetailPageDtos.MainImageResponse;

import jakarta.validation.Valid;

/**
 * 국가별 상세페이지 (FE CountryReportPage "상세 페이지 생성", DetailPage, DetailImagePage).
 * 계약: AI 레포 docs/API_SPEC_FE기준.md §2-4. userId 는 JWT 필터가 principal 에 넣는다(도입 전에는 null).
 */
@RestController
@RequestMapping("/products/{productId}/countries/{countryCode}/detail-page")
public class DetailPageController {

	private final DetailPageService detailPages;
	private final DetailPageImageService detailImages;

	public DetailPageController(DetailPageService detailPages, DetailPageImageService detailImages) {
		this.detailPages = detailPages;
		this.detailImages = detailImages;
	}

	/** 생성 잡을 건다. 판매 정보가 없으면 409. 다시 부르면 새로 만들고 셀러 수정본은 버린다. */
	@PostMapping
	@ResponseStatus(HttpStatus.ACCEPTED)
	public JobAccepted create(@PathVariable Long productId, @PathVariable String countryCode,
			@AuthenticationPrincipal Long userId) {
		return new JobAccepted(detailPages.create(userId, productId, countryCode));
	}

	/** 생성 중 202, 실패 200 {status: FAILED}, 완료 200 상세페이지. FE 는 200 이 올 때까지 폴링한다. */
	@GetMapping
	public ResponseEntity<JsonNode> get(@PathVariable Long productId, @PathVariable String countryCode,
			@AuthenticationPrincipal Long userId) {
		return detailPages.view(userId, productId, countryCode);
	}

	@PatchMapping("/content")
	public JsonNode editContent(@PathVariable Long productId, @PathVariable String countryCode,
			@AuthenticationPrincipal Long userId, @RequestBody @Valid ContentEditRequest req) {
		return detailPages.editContent(userId, productId, countryCode, req);
	}

	@PostMapping(path = "/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public ImageItem upload(@PathVariable Long productId, @PathVariable String countryCode,
			@AuthenticationPrincipal Long userId, @RequestPart("file") MultipartFile file,
			@RequestParam(defaultValue = "detail") String target) {
		return detailImages.upload(userId, productId, countryCode, target, file);
	}

	@PostMapping("/images/generated")
	public ImageItem saveGenerated(@PathVariable Long productId, @PathVariable String countryCode,
			@AuthenticationPrincipal Long userId, @RequestBody @Valid GeneratedImageRequest req) {
		return detailImages.saveGenerated(userId, productId, countryCode, req);
	}

	@DeleteMapping("/images/{imageId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable Long productId, @PathVariable String countryCode,
			@PathVariable String imageId, @AuthenticationPrincipal Long userId) {
		detailImages.delete(userId, productId, countryCode, imageId);
	}

	@PutMapping("/images/main")
	public MainImageResponse setMain(@PathVariable Long productId, @PathVariable String countryCode,
			@AuthenticationPrincipal Long userId, @RequestBody @Valid MainImageRequest req) {
		return new MainImageResponse(detailImages.setMain(userId, productId, countryCode, req.imageId()));
	}

	/** 이미지 AI 생성 잡. 결과는 GET /image-jobs/{jobId} 로 폴링한다. */
	@PostMapping("/images/generate")
	@ResponseStatus(HttpStatus.ACCEPTED)
	public JobAccepted generate(@PathVariable Long productId, @PathVariable String countryCode,
			@AuthenticationPrincipal Long userId, @RequestBody @Valid GenerateImageRequest req) {
		return new JobAccepted(detailImages.generate(userId, productId, countryCode, req));
	}
}
