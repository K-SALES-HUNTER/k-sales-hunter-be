package com.ksaleshunter.be.copilot;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 코파일럿이 AI 에 붙여 보낼 화면 컨텍스트를 만든다.
 *
 * 원본 전체가 아니라 현재 화면에 필요한 요약만 보낸다. 토큰을 아끼고 환각을 줄이기 위함이다.
 * Python 은 업무 DB 를 읽지 않으므로 상품 스냅샷도 여기서 만들어 보낸다.
 */
@Component
public class CopilotContextAssembler {

	private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
	};

	private final JdbcClient jdbc;
	private final ObjectMapper json;

	public CopilotContextAssembler(JdbcClient jdbc, ObjectMapper json) {
		this.jdbc = jdbc;
		this.json = json;
	}

	/**
	 * 화면 종류에 맞는 요약 컨텍스트.
	 * <ul>
	 *   <li>국가 코드 없음(글로벌 보고서): 종합 결론 + 국가 카드(순위·진입점수·추천가)</li>
	 *   <li>국가 화면: 4축 점수 + 가격 3안 + 경쟁·리스크 해설(country_reports.analysis JSONB)</li>
	 * </ul>
	 * AI 의 get_report_section 도구가 이 맵에서 필요한 섹션만 꺼내 쓴다.
	 */
	public Map<String, Object> build(Long productId, CopilotDtos.Page page) {
		if (productId == null) {
			return Map.of();
		}
		if (page.countryCode() == null) { // 글로벌 보고서: 종합 결론 + 국가 카드
			Map<String, Object> ctx = new LinkedHashMap<>();
			ctx.put("conclusion", jdbc.sql("""
					SELECT conclusion_title, conclusion_body, best_country_code
					FROM reports WHERE product_id = ? AND is_latest""")
				.param(productId).query().listOfRows());
			ctx.put("countries", jdbc.sql("""
					SELECT country_code, rank, entry_score, fit_grade, recommended_price_krw
					FROM country_reports WHERE product_id = ? AND is_latest ORDER BY rank""")
				.param(productId).query().listOfRows());
			return ctx;
		}
		// 국가 화면: 4축 점수 + 가격 3안 + 해설(경쟁·통관·배송)
		List<Map<String, Object>> rows = jdbc.sql("""
				SELECT id, entry_score, score_demand, score_competition, score_ktrend,
				       score_profitability, positioning, recommended_price_krw, analysis::text AS analysis
				FROM country_reports WHERE product_id = ? AND country_code = ? AND is_latest""")
			.param(productId).param(page.countryCode().toUpperCase()).query().listOfRows();
		if (rows.isEmpty()) {
			return Map.of();
		}
		Map<String, Object> report = new HashMap<>(rows.get(0));
		Map<String, Object> analysis = parse((String) report.remove("analysis"));
		Object reportId = report.remove("id");
		Map<String, Object> ctx = new LinkedHashMap<>();
		ctx.put("scores", report);
		ctx.put("pricing", jdbc.sql("""
				SELECT tier, price_krw, net_profit_krw, margin_rate, break_even_units, is_recommended
				FROM price_options WHERE country_report_id = ? ORDER BY price_krw""")
			.param(reportId).query().listOfRows());
		ctx.put("competition", analysis.get("competition"));
		ctx.put("risk", analysis.get("risk"));
		return ctx;
	}

	/** AI 계약 ProductSnapshot 모양 (camelCase). pricing_quote 도구가 원가·무게를 여기서 읽는다. */
	public Map<String, Object> productSnapshot(Long productId) {
		if (productId == null) {
			return null;
		}
		return jdbc.sql("""
				SELECT id AS "productId", name, category, supply_cost_krw AS "supplyCostKrw",
				       weight_g AS "weightG", COALESCE(description, '') AS description,
				       COALESCE(selling_point, '') AS "sellingPoint",
				       COALESCE(main_target, '') AS "mainTarget"
				FROM products WHERE id = ?""")
			.param(productId).query().listOfRows().stream().findFirst().orElse(null);
	}

	/** 셀러 브랜드 설정. 코파일럿 시스템 프롬프트의 톤에 반영된다 (R-005-07). */
	public Map<String, Object> marketProfile(Long userId) {
		return jdbc.sql("""
				SELECT COALESCE(brand_direction, '') AS "brandDirection",
				       COALESCE(seller_type, '') AS "sellerType",
				       COALESCE(main_target, '') AS "mainTarget",
				       COALESCE(brand_tone, '') AS "brandTone"
				FROM market_profiles WHERE user_id = ?""")
			.param(userId).query().listOfRows().stream().findFirst().orElse(null);
	}

	private Map<String, Object> parse(String raw) {
		try {
			return raw == null ? Map.of() : json.readValue(raw, MAP);
		} catch (Exception e) {
			return Map.of();
		}
	}
}
