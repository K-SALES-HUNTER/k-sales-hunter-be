package com.ksaleshunter.be.detailpage;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * AI 초안(PdpDraft) + 확정 판매 정보 → 프론트 PdpContent.
 *
 * <p>AI 는 글(name·usps·description·specs·breadcrumb·options)만 만든다. 가격·배송·재고 문구는 판매 정보에서
 * 나오는 값이라 여기서 붙인다. 판매 정보를 고치면 다음 조회부터 바로 반영된다.
 * 문구는 AI 레포 dev_bff 의 PDP_TEXT 와 같다 (FE 목과 같은 표기).
 */
final class PdpView {

	static final Map<String, String> LOCALE = Map.of("VN", "vi", "SG", "en", "TH", "th");
	static final Map<String, String> CURRENCY = Map.of("VN", "VND", "SG", "SGD", "TH", "THB");
	private static final Map<String, String> COUNTRY_NAME = Map.of("VN", "베트남", "SG", "싱가포르", "TH", "태국");
	private static final Map<String, String> SYMBOL = Map.of("VN", "₫", "SG", "S$", "TH", "฿");
	private static final Map<String, String> METHOD_NAME = Map.of("DIRECT", "직접 배송", "SLS", "Shopee SLS");

	private record Text(String region, String fee, String eta, String stock) {
	}

	private static final Map<String, Text> TEXT = Map.of(
		"ko", new Text("{country} 전 지역 배송", "무료배송 · {method}", "{min} ~ {max}일 이내", "재고 {count}개 남음"),
		"vi", new Text("Giao hàng toàn quốc Việt Nam", "Miễn phí vận chuyển · {method}", "Trong {min} ~ {max} ngày",
				"Còn {count} sản phẩm"),
		"en", new Text("Island-wide delivery in Singapore", "Free shipping · {method}", "{min} ~ {max} days",
				"{count} left in stock"),
		"th", new Text("จัดส่งทั่วประเทศไทย", "ส่งฟรี · {method}", "ภายใน {min} ~ {max} วัน", "เหลือ {count} ชิ้น"));

	private PdpView() {
	}

	static String localeOf(String countryCode) {
		return LOCALE.getOrDefault(countryCode, "en");
	}

	/**
	 * @param draft      AI PdpDraft (content_ko 또는 content_local)
	 * @param lang       ko / vi / en / th
	 * @param sales      확정 판매 정보
	 * @param analysis   국가 보고서 analysis (AI CountryResult). 배송 소요일을 읽는다. 없으면 "-"
	 */
	static ObjectNode render(ObjectMapper json, JsonNode draft, String lang, String countryCode,
			DetailPageSources.SalesInfo sales, JsonNode analysis) {
		Text text = TEXT.getOrDefault(lang, TEXT.get("en"));
		String method = sales.shippingMethod() == null ? "SLS" : sales.shippingMethod().toUpperCase(Locale.ROOT);
		JsonNode option = shippingOption(analysis, method);
		String methodName = METHOD_NAME.getOrDefault(method, method);

		ObjectNode shipping = json.createObjectNode()
			.put("region", text.region().replace("{country}", COUNTRY_NAME.getOrDefault(countryCode, countryCode)))
			.put("fee", text.fee().replace("{method}", methodName))
			.put("eta", text.eta()
				.replace("{min}", option == null ? "-" : option.path("etaMinDays").asText("-"))
				.replace("{max}", option == null ? "-" : option.path("etaMaxDays").asText("-")));

		ObjectNode pdp = json.createObjectNode();
		pdp.set("breadcrumb", draft.path("breadcrumb"));
		pdp.put("name", draft.path("name").asText(""));
		pdp.put("price", price(countryCode, sales.finalPriceLocal(), lang));
		pdp.set("usps", draft.path("usps"));
		pdp.set("shipping", shipping);
		pdp.set("options", draft.has("options") ? draft.get("options") : json.createArrayNode());
		pdp.put("stockNote", text.stock().replace("{count}", String.valueOf(sales.stockTotal())));
		pdp.set("specs", draft.path("specs"));
		pdp.put("description", draft.path("description").asText(""));
		return pdp;
	}

	/** 현지 통화 표기. VND 는 소수점 없이, 베트남어 화면은 천 단위 마침표. */
	static String price(String countryCode, BigDecimal amount, String lang) {
		BigDecimal value = amount == null ? BigDecimal.ZERO : amount;
		String symbol = SYMBOL.getOrDefault(countryCode, "");
		DecimalFormatSymbols symbols = DecimalFormatSymbols.getInstance(Locale.US);
		if ("VN".equals(countryCode)) {
			String text = new DecimalFormat("#,##0", symbols).format(value.setScale(0, RoundingMode.HALF_UP));
			return symbol + ("vi".equals(lang) ? text.replace(',', '.') : text);
		}
		return symbol + new DecimalFormat("#,##0.00", symbols).format(value.setScale(2, RoundingMode.HALF_UP));
	}

	private static JsonNode shippingOption(JsonNode analysis, String method) {
		if (analysis == null) {
			return null;
		}
		for (JsonNode option : analysis.path("logistics").path("options")) {
			if (method.equalsIgnoreCase(option.path("method").asText())) {
				return option;
			}
		}
		return null;
	}
}
