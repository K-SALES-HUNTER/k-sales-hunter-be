package com.ksaleshunter.be.detailpage;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 상세페이지가 읽기만 하는 다른 라우트 소유 테이블.
 *
 * <p>products·product_images(차은호), sales_infos·variants(권수현), country_reports(차은호 보고서 매핑),
 * market_profiles(이동건). 엔티티는 각 소유자가 만들기 때문에 여기서는 JPA 엔티티를 두지 않고
 * 필요한 컬럼만 SQL 로 읽는다. 소유자 쪽 서비스가 생기면 이 클래스만 그 호출로 바꾸면 된다.
 *
 * <p>JSONB 컬럼 형식 가정 (매핑 담당과 맞출 것)
 * <ul>
 *   <li>sales_infos.options: {@code {"useOptions": true, "option1": {name, values}, "option2": …}}
 *       — FE PUT sales-info 본문 그대로</li>
 *   <li>country_reports.analysis: AI CountryResult JSON (insight·logistics·risk·productFeatures 를 그대로 둔다)</li>
 * </ul>
 */
@Component
public class DetailPageSources {

	private final JdbcTemplate jdbc;
	private final ObjectMapper json;

	public DetailPageSources(JdbcTemplate jdbc, ObjectMapper json) {
		this.jdbc = jdbc;
		this.json = json;
	}

	public record Product(Long id, Long userId, String name, String category, int supplyCostKrw, int weightG,
			String description, String sellingPoint, String mainTarget, Integer pkgWeightG, Integer pkgWidthMm,
			Integer pkgDepthMm, Integer pkgHeightMm, List<String> imageUrls) {
	}

	public record SalesInfo(Long id, Integer finalPriceKrw, BigDecimal finalPriceLocal, String currency,
			String shopeeCategoryId, JsonNode categoryAttributes, JsonNode options, String shippingMethod,
			int stockTotal) {
	}

	public record CountryReport(String status, String positioning, JsonNode analysis) {
	}

	public record MarketProfile(String marketName, String brandDirection, String sellerType, String mainTarget,
			String brandTone) {
	}

	public Optional<Product> product(Long productId) {
		List<String> images = jdbc.queryForList(
			"SELECT url FROM product_images WHERE product_id = ? ORDER BY sort_order, id", String.class, productId);
		return jdbc.query("""
				SELECT id, user_id, name, category, supply_cost_krw, weight_g, description, selling_point,
				       main_target, pkg_weight_g, pkg_width_mm, pkg_depth_mm, pkg_height_mm
				FROM products WHERE id = ?
				""", (rs, i) -> new Product(rs.getLong("id"), rs.getLong("user_id"), rs.getString("name"),
				rs.getString("category"), rs.getInt("supply_cost_krw"), rs.getInt("weight_g"),
				rs.getString("description"), rs.getString("selling_point"), rs.getString("main_target"),
				integer(rs, "pkg_weight_g"), integer(rs, "pkg_width_mm"), integer(rs, "pkg_depth_mm"),
				integer(rs, "pkg_height_mm"), images), productId)
			.stream().findFirst();
	}

	public Optional<SalesInfo> salesInfo(Long productId, String countryCode) {
		return jdbc.query("""
				SELECT s.id, s.final_price_krw, s.final_price_local, s.currency, s.shopee_category_id,
				       s.category_attributes::text AS attrs, s.options::text AS opts, s.shipping_method,
				       COALESCE((SELECT SUM(v.stock_qty) FROM variants v WHERE v.sales_info_id = s.id), 0) AS stock
				FROM sales_infos s WHERE s.product_id = ? AND s.country_code = ?
				""", (rs, i) -> new SalesInfo(rs.getLong("id"), integer(rs, "final_price_krw"),
				rs.getBigDecimal("final_price_local"), rs.getString("currency"), rs.getString("shopee_category_id"),
				parse(rs.getString("attrs")), parse(rs.getString("opts")), rs.getString("shipping_method"),
				rs.getInt("stock")), productId, countryCode)
			.stream().findFirst();
	}

	/** 최신 버전 국가 보고서. */
	public Optional<CountryReport> latestCountryReport(Long productId, String countryCode) {
		return jdbc.query("""
				SELECT status, positioning, analysis::text AS analysis
				FROM country_reports
				WHERE product_id = ? AND country_code = ? AND is_latest
				ORDER BY version DESC LIMIT 1
				""", (rs, i) -> new CountryReport(rs.getString("status"), rs.getString("positioning"),
				parse(rs.getString("analysis"))), productId, countryCode)
			.stream().findFirst();
	}

	public Optional<MarketProfile> marketProfile(Long userId) {
		return jdbc.query("""
				SELECT market_name, brand_direction, seller_type, main_target, brand_tone
				FROM market_profiles WHERE user_id = ?
				""", (rs, i) -> new MarketProfile(rs.getString("market_name"), rs.getString("brand_direction"),
				rs.getString("seller_type"), rs.getString("main_target"), rs.getString("brand_tone")), userId)
			.stream().findFirst();
	}

	private static Integer integer(ResultSet rs, String column) throws SQLException {
		int value = rs.getInt(column);
		return rs.wasNull() ? null : value;
	}

	private JsonNode parse(String text) {
		if (text == null) {
			return json.nullNode();
		}
		try {
			return json.readTree(text);
		}
		catch (JsonProcessingException e) {
			throw new IllegalStateException("JSONB 파싱 실패", e);
		}
	}
}
