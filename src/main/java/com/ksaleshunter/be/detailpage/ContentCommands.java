package com.ksaleshunter.be.detailpage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 상세페이지 생성 잡 입력(AI ContentCommand)을 만든다. 형식: AI 레포 contracts/v1/content_command.schema.json.
 *
 * <p>AI 는 업무 DB 를 읽지 않으므로 상품·확정 판매 정보·분석 요약을 모두 실어 보낸다.
 * AI 쪽 문자열 필드는 null 을 받지 않아서 비어 있으면 "" 로 보낸다.
 */
final class ContentCommands {

	private ContentCommands() {
	}

	static Map<String, Object> build(String jobId, String countryCode, DetailPageSources.Product product,
			DetailPageSources.SalesInfo sales, DetailPageSources.CountryReport report,
			DetailPageSources.MarketProfile profile) {
		Map<String, Object> command = new HashMap<>();
		command.put("jobId", jobId);
		command.put("traceId", UUID.randomUUID().toString().replace("-", ""));
		command.put("country", countryCode);
		command.put("locale", PdpView.localeOf(countryCode));
		command.put("product", product(product));
		command.put("salesInfo", salesInfo(countryCode, sales));
		command.put("analysis", analysis(report));
		command.put("marketProfile", profile == null ? null : Map.of(
			"brandDirection", text(profile.brandDirection()),
			"sellerType", text(profile.sellerType()),
			"mainTarget", text(profile.mainTarget()),
			"brandTone", text(profile.brandTone())));
		return command;
	}

	private static Map<String, Object> product(DetailPageSources.Product p) {
		Map<String, Object> product = new HashMap<>();
		product.put("productId", p.id());
		product.put("name", p.name());
		product.put("category", p.category());
		product.put("supplyCostKrw", p.supplyCostKrw());
		product.put("weightG", p.weightG());
		product.put("description", text(p.description()));
		product.put("sellingPoint", text(p.sellingPoint()));
		product.put("mainTarget", text(p.mainTarget()));
		boolean packed = p.pkgWeightG() != null && p.pkgWidthMm() != null && p.pkgDepthMm() != null
				&& p.pkgHeightMm() != null;
		product.put("packaging", packed ? Map.of("weightG", p.pkgWeightG(), "widthMm", p.pkgWidthMm(),
				"depthMm", p.pkgDepthMm(), "heightMm", p.pkgHeightMm()) : null);
		product.put("imageUrls", p.imageUrls());
		return product;
	}

	private static Map<String, Object> salesInfo(String countryCode, DetailPageSources.SalesInfo s) {
		JsonNode options = s.options();
		boolean useOptions = options == null || options.path("useOptions").asBoolean(true);
		Map<String, Object> sales = new HashMap<>();
		sales.put("categoryId", text(s.shopeeCategoryId()));
		sales.put("categoryLabel", ""); // AI 가 카테고리 스냅샷에서 id 로 찾는다
		sales.put("attributes", s.categoryAttributes() == null || s.categoryAttributes().isNull()
				? Map.of() : s.categoryAttributes());
		sales.put("option1", useOptions ? nullable(options, "option1") : null);
		sales.put("option2", useOptions ? nullable(options, "option2") : null);
		sales.put("finalPriceKrw", s.finalPriceKrw() == null ? 0 : s.finalPriceKrw());
		sales.put("finalPriceLocal", s.finalPriceLocal() == null ? 0 : s.finalPriceLocal());
		sales.put("currency", s.currency() == null ? PdpView.CURRENCY.getOrDefault(countryCode, "") : s.currency());
		sales.put("shippingMethod", s.shippingMethod());
		sales.put("stockTotal", s.stockTotal());
		return sales;
	}

	private static Map<String, Object> analysis(DetailPageSources.CountryReport report) {
		JsonNode a = report.analysis() == null ? null : report.analysis();
		JsonNode insight = a == null ? null : a.path("insight");
		JsonNode features = a == null ? null : a.path("productFeatures");
		List<String> risks = new ArrayList<>();
		if (a != null) {
			a.path("risk").path("warnings").forEach(w -> risks.add(w.path("text").asText()));
		}
		String positioning = report.positioning() != null ? report.positioning()
				: insight == null ? "" : insight.path("positioning").asText("");
		return Map.of(
			"positioning", positioning,
			"positioningLabel", insight == null ? "" : insight.path("positioningLabel").asText(""),
			"keywords", strings(features, "keywords"),
			"usps", strings(features, "usps"),
			"riskFlags", risks);
	}

	private static List<String> strings(JsonNode node, String field) {
		List<String> values = new ArrayList<>();
		if (node != null) {
			node.path(field).forEach(v -> values.add(v.asText()));
		}
		return values;
	}

	private static JsonNode nullable(JsonNode node, String field) {
		if (node == null) {
			return null;
		}
		JsonNode value = node.get(field);
		return value == null || value.isNull() ? null : value;
	}

	private static String text(String value) {
		return value == null ? "" : value;
	}
}
