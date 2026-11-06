package com.ksaleshunter.be.detailpage;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.JsonNode;
import com.ksaleshunter.be.detailpage.DetailPageDtos.GenerateImageRequest;
import com.ksaleshunter.be.detailpage.DetailPageDtos.GeneratedImageRequest;
import com.ksaleshunter.be.detailpage.DetailPageDtos.ImageItem;
import com.ksaleshunter.be.detailpage.DetailPageDtos.ImageJobView;
import com.ksaleshunter.be.detailpage.DetailPageDtos.ImageResult;
import com.ksaleshunter.be.upload.UploadStorage;

/**
 * 상세페이지 이미지 — 직접 업로드, AI 생성(잡), 생성 결과 확정, 삭제, 대표 지정 (화면 DTL-01-02).
 *
 * <p>AI 생성은 두 단계다. generate 로 잡을 걸고 FE 가 /image-jobs/{jobId} 를 폴링해 결과를 보여 준다.
 * 셀러가 마음에 들어 "저장"을 누를 때만 generated 로 상세페이지에 붙인다. 버린 결과는 DB 에 남지 않는다.
 */
@Service
public class DetailPageImageService {

	private final DetailPageService detailPages;
	private final DetailPageImageRepository images;
	private final UploadStorage storage;
	private final AiContentClient ai;

	public DetailPageImageService(DetailPageService detailPages, DetailPageImageRepository images,
			UploadStorage storage, AiContentClient ai) {
		this.detailPages = detailPages;
		this.images = images;
		this.storage = storage;
		this.ai = ai;
	}

	@Transactional
	public ImageItem upload(Long userId, Long productId, String countryCode, String target, MultipartFile file) {
		DetailPage page = page(userId, productId, countryCode);
		UploadStorage.Stored stored = storage.save(file);
		return add(page, kind(target), DetailPageImage.Source.UPLOAD, stored.url(), null);
	}

	/** 생성 결과 확정. replaceImageId 가 있으면 그 자리(같은 id)를 바꾼다 — 재생성 시 FE 가 원본 id 를 유지한다. */
	@Transactional
	public ImageItem saveGenerated(Long userId, Long productId, String countryCode, GeneratedImageRequest req) {
		DetailPage page = page(userId, productId, countryCode);
		requireOwnJob(req.generatedImageId(), productId);
		AiContentClient.JobState state = ai.imageJob(req.generatedImageId());
		if (!state.completed() || state.result() == null) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "이미지 생성이 끝나지 않았습니다");
		}
		String url = state.result().path("imageUrl").asText();
		String prompt = state.result().path("prompt").asText("");

		if (req.replaceImageId() != null && !req.replaceImageId().isBlank()) {
			DetailPageImage image = image(page, req.replaceImageId());
			image.replace(url, prompt, null);
			return item(page, image);
		}
		return add(page, kind(req.target()), DetailPageImage.Source.AI, url, prompt);
	}

	/** 대표 이미지를 지우면 남은 상품 이미지 첫 장이 대표가 된다. */
	@Transactional
	public void delete(Long userId, Long productId, String countryCode, String imageId) {
		DetailPage page = page(userId, productId, countryCode);
		DetailPageImage image = image(page, imageId);
		images.delete(image);
		images.flush();
		if (image.isMain()) {
			images.findByDetailPageIdOrderBySortOrderAscIdAsc(page.getId()).stream()
				.filter(i -> i.getKind() == DetailPageImage.Kind.PRODUCT)
				.findFirst()
				.ifPresent(first -> first.setMain(true));
		}
	}

	@Transactional
	public String setMain(Long userId, Long productId, String countryCode, String imageId) {
		DetailPage page = page(userId, productId, countryCode);
		DetailPageImage target = image(page, imageId);
		if (target.getKind() != DetailPageImage.Kind.PRODUCT) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "상품 이미지가 아닙니다");
		}
		images.findByDetailPageIdOrderBySortOrderAscIdAsc(page.getId())
			.forEach(i -> i.setMain(i.getId().equals(target.getId())));
		return String.valueOf(target.getId());
	}

	/**
	 * 이미지 생성 잡. referenceIds 는 상세페이지 이미지 id 또는 업로드 id(= 파일 이름)다.
	 * AI 가 참고 사진을 같은 업로드 디렉터리에서 읽으므로 URL 만 넘긴다.
	 */
	public String generate(Long userId, Long productId, String countryCode, GenerateImageRequest req) {
		DetailPage page = page(userId, productId, countryCode);
		List<DetailPageImage> owned = images.findByDetailPageIdOrderBySortOrderAscIdAsc(page.getId());
		List<String> references = new ArrayList<>();
		for (String id : Optional.ofNullable(req.referenceIds()).orElse(List.of())) {
			owned.stream().filter(i -> String.valueOf(i.getId()).equals(id)).findFirst()
				.map(DetailPageImage::getUrl)
				.or(() -> storage.urlOf(id))
				.ifPresent(references::add);
		}
		if (references.isEmpty()) {
			throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "참고 사진을 1장 이상 고르세요");
		}

		String jobId = "i_%d_%d".formatted(productId, System.currentTimeMillis());
		ai.submitImageJob(jobId, kind(req.target()).name(), req.prompt(),
				req.mode() == null ? "BASIC" : req.mode().toUpperCase(Locale.ROOT), req.modelId(), references);
		return jobId;
	}

	/** FE 는 RUNNING 이 아닐 때까지 폴링한다. 대기(QUEUED)·취소 요청 중도 RUNNING 으로 보인다. */
	public ImageJobView job(Long userId, String jobId) {
		requireAccess(userId, jobId);
		AiContentClient.JobState state = ai.imageJob(jobId);
		String status = switch (state.status()) {
			case "COMPLETED" -> "DONE";
			case "FAILED" -> "FAILED";
			case "CANCELLED" -> "CANCELLED";
			default -> "RUNNING";
		};
		ImageResult result = null;
		if (state.completed() && state.result() != null) {
			JsonNode r = state.result();
			result = new ImageResult(jobId, r.path("imageUrl").asText(), r.path("prompt").asText(""));
		}
		return new ImageJobView(status, state.progress(), result, state.errorCode());
	}

	public void cancel(Long userId, String jobId) {
		requireAccess(userId, jobId);
		ai.cancelImageJob(jobId);
	}

	private DetailPage page(Long userId, Long productId, String countryCode) {
		String code = DetailPageService.country(countryCode);
		detailPages.product(userId, productId);
		return detailPages.readyPage(productId, code);
	}

	private ImageItem add(DetailPage page, DetailPageImage.Kind kind, DetailPageImage.Source source, String url,
			String prompt) {
		List<DetailPageImage> all = images.findByDetailPageIdOrderBySortOrderAscIdAsc(page.getId());
		int order = (int) all.stream().filter(i -> i.getKind() == kind).count();
		DetailPageImage image = DetailPageImage.of(page.getId(), kind, source, url, order, prompt);
		image.setMain(kind == DetailPageImage.Kind.PRODUCT && all.stream().noneMatch(DetailPageImage::isMain));
		images.save(image);
		return item(page, image);
	}

	private ImageItem item(DetailPage page, DetailPageImage image) {
		return DetailPageService.toItems(images.findByDetailPageIdOrderBySortOrderAscIdAsc(page.getId()),
				image.getKind()).stream()
			.filter(i -> i.id().equals(String.valueOf(image.getId())))
			.findFirst().orElseThrow();
	}

	private DetailPageImage image(DetailPage page, String imageId) {
		Long id;
		try {
			id = Long.valueOf(imageId);
		}
		catch (NumberFormatException e) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "이미지가 없습니다");
		}
		return images.findByIdAndDetailPageId(id, page.getId())
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "이미지가 없습니다"));
	}

	private static DetailPageImage.Kind kind(String target) {
		return "product".equalsIgnoreCase(target) ? DetailPageImage.Kind.PRODUCT : DetailPageImage.Kind.DETAIL;
	}

	/** 이미지 잡 id 는 i_{productId}_{시각}. 다른 상품의 잡 결과를 가져다 붙이지 못하게 한다. */
	private static void requireOwnJob(String jobId, Long productId) {
		if (!jobId.startsWith("i_" + productId + "_")) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "이미지 생성 작업이 없습니다");
		}
	}

	private void requireAccess(Long userId, String jobId) {
		String[] parts = jobId.split("_");
		if (parts.length < 3 || !"i".equals(parts[0])) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "이미지 생성 작업이 없습니다");
		}
		try {
			detailPages.product(userId, Long.valueOf(parts[1]));
		}
		catch (NumberFormatException e) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "이미지 생성 작업이 없습니다");
		}
	}
}
