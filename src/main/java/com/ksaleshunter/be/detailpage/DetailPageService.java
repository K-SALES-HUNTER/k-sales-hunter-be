package com.ksaleshunter.be.detailpage;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.IntStream;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ksaleshunter.be.detailpage.DetailPageDtos.ImageItem;

/**
 * 상세페이지 생성·조회·텍스트 수정 (R-003 콘텐츠 아키텍트, 화면 DTL-01).
 *
 * <p>흐름: POST 로 AI 생성 잡을 걸고 행을 GENERATING 으로 둔다 → FE 가 GET 을 2초마다 부른다 →
 * 잡이 끝난 걸 처음 본 GET 이 결과를 detail_pages 에 옮기고 상품 사진을 상품 이미지로 복사한다.
 * 콜백 없이 폴링만 쓰는 이유는 AI 레포와 같다(구성 요소가 적고, 어느 쪽이 재시작돼도 결과가 남는다).
 */
@Service
public class DetailPageService {

	private final DetailPageRepository pages;
	private final DetailPageImageRepository images;
	private final DetailPageSources sources;
	private final AiContentClient ai;
	private final ObjectMapper json;

	public DetailPageService(DetailPageRepository pages, DetailPageImageRepository images,
			DetailPageSources sources, AiContentClient ai, ObjectMapper json) {
		this.pages = pages;
		this.images = images;
		this.sources = sources;
		this.ai = ai;
		this.json = json;
	}

	/** 생성 잡을 건다. 이미 있으면 새로 만들고 셀러 수정본·이미지는 버린다. */
	@Transactional
	public String create(Long userId, Long productId, String countryCode) {
		String code = country(countryCode);
		DetailPageSources.Product product = product(userId, productId);
		DetailPageSources.SalesInfo sales = sources.salesInfo(productId, code)
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "판매 정보를 먼저 저장하세요"));
		DetailPageSources.CountryReport report = sources.latestCountryReport(productId, code)
			.filter(r -> !"FILTERED_OUT".equals(r.status()))
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "해당 국가 분석 결과가 없습니다"));
		DetailPageSources.MarketProfile profile = sources.marketProfile(product.userId()).orElse(null);

		String jobId = "c_%d_%s_%d".formatted(productId, code, System.currentTimeMillis());
		ai.submitContentJob(ContentCommands.build(jobId, code, product, sales, report, profile));

		String locale = PdpView.localeOf(code);
		DetailPage page = pages.findByProductIdAndCountryCode(productId, code).orElse(null);
		if (page == null) {
			pages.save(DetailPage.generating(productId, code, jobId, locale, json.createArrayNode()));
		}
		else {
			images.deleteByDetailPageId(page.getId());
			page.restart(jobId, locale, json.createArrayNode());
		}
		return jobId;
	}

	/**
	 * 생성 중이면 202 {status: GENERATING, jobId, step, progress}, 실패면 200 {status: FAILED, jobId, errorCode},
	 * 끝났으면 200 상세페이지 전체.
	 */
	@Transactional
	public ResponseEntity<JsonNode> view(Long userId, Long productId, String countryCode) {
		String code = country(countryCode);
		DetailPageSources.Product product = product(userId, productId);
		DetailPage page = pages.findByProductIdAndCountryCode(productId, code)
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "상세 페이지가 없습니다"));

		if (page.getStatus() == DetailPage.Status.GENERATING) {
			AiContentClient.JobState state = ai.contentJob(page.getAiJobId());
			if (state.completed() && state.result() != null) {
				materialize(page, state.result(), product);
			}
			else if (state.failed()) {
				page.fail(state.errorCode());
			}
			else {
				ObjectNode body = json.createObjectNode()
					.put("status", "GENERATING")
					.put("jobId", page.getAiJobId())
					.put("progress", state.progress());
				body.put("step", state.step());
				return ResponseEntity.status(HttpStatus.ACCEPTED).body(body);
			}
		}
		if (page.getStatus() == DetailPage.Status.FAILED) {
			ObjectNode body = json.createObjectNode().put("status", "FAILED").put("jobId", page.getAiJobId());
			body.put("errorCode", page.getErrorCode());
			return ResponseEntity.ok(body);
		}
		return ResponseEntity.ok(fullView(page, product));
	}

	/** 셀러가 고친 한국어 상품명·설명. 사람이 고친 값은 그대로 저장하고 현지어본만 다시 옮긴다. */
	@Transactional
	public JsonNode editContent(Long userId, Long productId, String countryCode, DetailPageDtos.ContentEditRequest req) {
		String code = country(countryCode);
		DetailPageSources.Product product = product(userId, productId);
		DetailPage page = readyPage(productId, code);

		ObjectNode ko = page.getContentKo().deepCopy();
		ko.put("name", req.name()).put("description", req.description());
		ObjectNode local = null;
		JsonNode previous = page.getContentLocal();
		if (req.shouldRetranslate() && previous != null && !previous.isNull()) {
			AiContentClient.Translated translated = ai.retranslate(code, page.getLocale(), req.name(),
					req.description(), previous.path("name").asText(""), previous.path("description").asText(""));
			local = previous.deepCopy();
			local.put("name", translated.name()).put("description", translated.description());
		}
		page.edit(ko, local);
		return fullView(page, product);
	}

	/** 화면에 그릴 상세페이지 전체. GET 과 PATCH 가 같은 모양을 돌려준다. */
	JsonNode fullView(DetailPage page, DetailPageSources.Product product) {
		String code = page.getCountryCode();
		DetailPageSources.SalesInfo sales = sources.salesInfo(product.id(), code)
			.orElse(new DetailPageSources.SalesInfo(null, null, null, null, null, null, null, null, 0));
		JsonNode analysis = sources.latestCountryReport(product.id(), code)
			.map(DetailPageSources.CountryReport::analysis).orElse(null);

		ObjectNode content = json.createObjectNode();
		content.set("ko", PdpView.render(json, page.getContentKo(), "ko", code, sales, analysis));
		JsonNode localDraft = page.getContentLocal();
		content.set("local", localDraft == null || localDraft.isNull() ? json.nullNode()
				: PdpView.render(json, localDraft, page.getLocale(), code, sales, analysis));

		String marketName = sources.marketProfile(product.userId())
			.map(DetailPageSources.MarketProfile::marketName).orElse("내 마켓");
		ObjectNode seller = json.createObjectNode()
			.put("name", marketName)
			.put("meta", "온라인 · 5분 이내 응답")
			.put("rating", "—")
			.put("response", "—")
			.put("followers", "—");

		List<DetailPageImage> all = images.findByDetailPageIdOrderBySortOrderAscIdAsc(page.getId());
		ObjectNode view = json.createObjectNode()
			.put("status", page.getStatus().name())
			.put("qualityScore", page.getQualityScore() == null ? 0 : page.getQualityScore())
			.put("edited", page.isEdited());
		view.set("qualityIssues", page.getQualityIssues());
		view.set("content", content);
		view.set("seller", seller);
		view.set("productImages", items(all, DetailPageImage.Kind.PRODUCT));
		view.put("mainImageId", all.stream().filter(DetailPageImage::isMain).findFirst()
			.map(i -> String.valueOf(i.getId())).orElse(null));
		view.set("detailImages", items(all, DetailPageImage.Kind.DETAIL));
		view.put("locale", page.getLocale());
		return view;
	}

	/** 화면 라벨은 저장하지 않고 순서로 만든다: 기본 N / 상세 이미지 N */
	static List<ImageItem> toItems(List<DetailPageImage> all, DetailPageImage.Kind kind) {
		List<DetailPageImage> ofKind = all.stream().filter(i -> i.getKind() == kind).toList();
		return IntStream.range(0, ofKind.size())
			.mapToObj(index -> {
				DetailPageImage image = ofKind.get(index);
				String label = (kind == DetailPageImage.Kind.PRODUCT ? "기본 " : "상세 이미지 ") + (index + 1);
				return new ImageItem(String.valueOf(image.getId()), label, image.getUrl(), image.getPrompt());
			})
			.toList();
	}

	DetailPage readyPage(Long productId, String code) {
		DetailPage page = pages.findByProductIdAndCountryCode(productId, code)
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "상세 페이지를 먼저 생성하세요"));
		if (!page.isReady()) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "상세 페이지 생성이 끝나지 않았습니다");
		}
		return page;
	}

	/** 상품 소유 확인. 남의 상품은 없는 것처럼 404 로 숨긴다. userId 가 없으면(JWT 필터 도입 전) 검사하지 않는다. */
	DetailPageSources.Product product(Long userId, Long productId) {
		return sources.product(productId)
			.filter(p -> userId == null || userId.equals(p.userId()))
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "상품이 없습니다"));
	}

	static String country(String countryCode) {
		String code = countryCode.toUpperCase(Locale.ROOT);
		if (!PdpView.LOCALE.containsKey(code)) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "지원하지 않는 국가입니다");
		}
		return code;
	}

	private void materialize(DetailPage page, JsonNode result, DetailPageSources.Product product) {
		DetailPage.Status status = "NEEDS_REVIEW".equals(result.path("status").asText())
				? DetailPage.Status.NEEDS_REVIEW : DetailPage.Status.DONE;
		JsonNode issues = result.has("qualityIssues") ? result.get("qualityIssues") : json.createArrayNode();
		page.complete(status, result.path("locale").asText(null), result.get("ko"), result.get("local"),
				result.path("qualityScore").asInt(0), issues);

		List<String> urls = product.imageUrls();
		for (int i = 0; i < urls.size(); i++) {
			DetailPageImage image = DetailPageImage.of(page.getId(), DetailPageImage.Kind.PRODUCT,
					DetailPageImage.Source.UPLOAD, urls.get(i), i, null);
			image.setMain(i == 0);
			images.save(image);
		}
	}

	private ArrayNode items(List<DetailPageImage> all, DetailPageImage.Kind kind) {
		ArrayNode array = json.createArrayNode();
		toItems(all, kind).forEach(item -> array.add(json.valueToTree(Map.of(
			"id", item.id(), "label", item.label(), "src", item.src(),
			"prompt", item.prompt() == null ? "" : item.prompt()))));
		return array;
	}
}
