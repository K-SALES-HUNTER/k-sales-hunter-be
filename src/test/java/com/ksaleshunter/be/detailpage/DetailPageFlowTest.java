package com.ksaleshunter.be.detailpage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ksaleshunter.be.TestcontainersConfiguration;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

/**
 * 상세페이지 전 구간: 생성 잡 → 폴링 → 결과 저장 → 텍스트 수정·재번역 → 이미지 업로드·AI 생성·확정·대표·삭제.
 * DB 는 Testcontainers PostgreSQL(Flyway 실제 적용), AI 는 MockWebServer 가 계약 형식 그대로 응답한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class DetailPageFlowTest {

	static final MockWebServer ai = new MockWebServer();
	static final Path uploads;

	static {
		try {
			ai.start();
			uploads = Files.createTempDirectory("ksh-uploads");
		}
		catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	@DynamicPropertySource
	static void props(DynamicPropertyRegistry registry) {
		registry.add("ai.base-url", () -> ai.url("/").toString().replaceAll("/$", ""));
		registry.add("storage.upload-dir", uploads::toString);
	}

	@AfterAll
	static void stop() throws IOException {
		ai.shutdown();
	}

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	ObjectMapper json;

	long productId;

	@BeforeEach
	void seed() throws InterruptedException {
		while (ai.takeRequest(10, TimeUnit.MILLISECONDS) != null) {
			// 이전 테스트가 남긴 요청 기록을 비운다
		}
		Long userId = jdbc.queryForObject("""
				INSERT INTO users (email, password_hash, business_reg_no)
				VALUES ('seller' || nextval('users_id_seq') || '@test.kr', 'x', '1234567890') RETURNING id
				""", Long.class);
		jdbc.update("INSERT INTO market_profiles (user_id, market_name, brand_tone) VALUES (?, '포근상점', '다정한')",
				userId);
		productId = jdbc.queryForObject("""
				INSERT INTO products (user_id, name, category, supply_cost_krw, weight_g, description)
				VALUES (?, '포근 캐릭터 인형', '캐릭터·굿즈', 12800, 300, '부드러운 인형') RETURNING id
				""", Long.class, userId);
		jdbc.update("INSERT INTO product_images (product_id, url, sort_order) VALUES (?, '/api/v1/uploads/a.png', 0), (?, '/api/v1/uploads/b.png', 1)",
				productId, productId);
		Long salesInfoId = jdbc.queryForObject("""
				INSERT INTO sales_infos (product_id, country_code, final_price_krw, final_price_local, currency,
				    shopee_category_id, category_attributes, options, shipping_method)
				VALUES (?, 'VN', 28900, 175000, 'VND', '100635', '{"brand": "Kora"}'::jsonb,
				    '{"useOptions": true, "option1": {"name": "color", "values": ["핑크", "블루"]}}'::jsonb, 'SLS')
				RETURNING id
				""", Long.class, productId);
		jdbc.update("INSERT INTO variants (sales_info_id, option1_value, stock_qty) VALUES (?, '핑크', 30), (?, '블루', 20)",
				salesInfoId, salesInfoId);
		jdbc.update("""
				INSERT INTO country_reports (product_id, country_code, positioning, analysis)
				VALUES (?, 'VN', 'GIFT', '{
				  "insight": {"positioning": "GIFT", "positioningLabel": "선물형"},
				  "productFeatures": {"keywords": ["gấu bông"], "usps": ["부드러운 촉감"]},
				  "risk": {"warnings": [{"text": "어린이 제품 인증 확인"}]},
				  "logistics": {"options": [{"method": "SLS", "etaMinDays": 7, "etaMaxDays": 12}]}
				}'::jsonb)
				""", productId);
	}

	@Test
	void 판매정보가_없으면_만들_수_없다() throws Exception {
		mvc.perform(post(path("TH"))).andExpect(status().isConflict());
	}

	@Test
	void 생성부터_수정_이미지까지() throws Exception {
		// 1) 생성 잡
		ai.enqueue(accepted());
		String jobId = body(mvc.perform(post(path("vn"))).andExpect(status().isAccepted())).path("jobId").asText();
		assertThat(jobId).startsWith("c_" + productId + "_VN_");

		JsonNode command = json.readTree(ai.takeRequest().getBody().readUtf8());
		assertThat(command.path("locale").asText()).isEqualTo("vi");
		assertThat(command.path("salesInfo").path("stockTotal").asInt()).isEqualTo(50);
		assertThat(command.path("salesInfo").path("option1").path("values").size()).isEqualTo(2);
		assertThat(command.path("analysis").path("positioningLabel").asText()).isEqualTo("선물형");
		assertThat(command.path("analysis").path("riskFlags").get(0).asText()).isEqualTo("어린이 제품 인증 확인");
		assertThat(command.path("marketProfile").path("brandTone").asText()).isEqualTo("다정한");

		// 2) 아직 생성 중이면 202
		ai.enqueue(jobState("RUNNING", "\"step\": \"REVIEW\", \"progress\": 0.6"));
		mvc.perform(get(path("VN"))).andExpect(status().isAccepted())
			.andExpect(jsonPath("$.status").value("GENERATING"))
			.andExpect(jsonPath("$.step").value("REVIEW"));

		// 3) 끝나면 결과를 저장하고 판매 정보로 가격·배송·재고 문구를 붙인다
		ai.enqueue(jobState("COMPLETED", "\"result\": " + contentResult()));
		JsonNode page = body(mvc.perform(get(path("VN"))).andExpect(status().isOk()));
		assertThat(page.path("status").asText()).isEqualTo("NEEDS_REVIEW");
		assertThat(page.path("qualityIssues").get(0).path("hardBlock").asBoolean()).isTrue();
		assertThat(page.at("/content/ko/price").asText()).isEqualTo("₫175,000");
		assertThat(page.at("/content/local/price").asText()).isEqualTo("₫175.000");
		assertThat(page.at("/content/local/shipping/eta").asText()).isEqualTo("Trong 7 ~ 12 ngày");
		assertThat(page.at("/content/ko/stockNote").asText()).isEqualTo("재고 50개 남음");
		assertThat(page.at("/seller/name").asText()).isEqualTo("포근상점");
		assertThat(page.path("productImages").size()).isEqualTo(2);
		String firstImage = page.at("/productImages/0/id").asText();
		String secondImage = page.at("/productImages/1/id").asText();
		assertThat(page.path("mainImageId").asText()).isEqualTo(firstImage);
		assertThat(page.at("/productImages/1/label").asText()).isEqualTo("기본 2");

		// 다시 조회해도 AI 를 부르지 않는다 (DB 에 옮겨 둠)
		mvc.perform(get(path("VN"))).andExpect(status().isOk());
		assertThat(ai.getRequestCount()).isEqualTo(3);

		// 4) 텍스트 수정 + 재번역. 한국어본은 입력 그대로
		ai.takeRequest();
		ai.takeRequest();
		ai.enqueue(jsonResponse("{\"name\": \"Gấu bông mới\", \"description\": \"Mô tả mới\"}"));
		JsonNode edited = body(mvc.perform(patch(path("VN") + "/content").contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\": \"새 인형\", \"description\": \"새 설명\"}")).andExpect(status().isOk()));
		assertThat(edited.path("edited").asBoolean()).isTrue();
		assertThat(edited.at("/content/ko/name").asText()).isEqualTo("새 인형");
		assertThat(edited.at("/content/local/name").asText()).isEqualTo("Gấu bông mới");
		JsonNode retranslate = json.readTree(ai.takeRequest().getBody().readUtf8());
		assertThat(retranslate.path("previousName").asText()).isEqualTo("Gấu bông Kora");

		// 5) 상세 이미지 직접 업로드 → 서빙
		JsonNode uploaded = body(mvc.perform(multipart(path("VN") + "/images")
			.file(new MockMultipartFile("file", "cut.png", "image/png", new byte[] {1, 2, 3}))
			.param("target", "detail")).andExpect(status().isOk()));
		assertThat(uploaded.path("label").asText()).isEqualTo("상세 이미지 1");
		String src = uploaded.path("src").asText();
		assertThat(src).startsWith("/api/v1/uploads/");
		mvc.perform(get("/uploads/" + src.substring("/api/v1/uploads/".length()))).andExpect(status().isOk());

		// 6) AI 생성: 참고 사진 id → URL 로 바꿔 잡을 건다
		ai.enqueue(accepted());
		String imageJob = body(mvc.perform(post(path("VN") + "/images/generate").contentType(MediaType.APPLICATION_JSON)
			.content("{\"target\": \"product\", \"prompt\": \"밝은 배경\", \"mode\": \"basic\", \"referenceIds\": [\""
					+ firstImage + "\"]}"))
			.andExpect(status().isAccepted())).path("jobId").asText();
		JsonNode imageCommand = json.readTree(ai.takeRequest().getBody().readUtf8());
		assertThat(imageCommand.path("target").asText()).isEqualTo("PRODUCT");
		assertThat(imageCommand.path("mode").asText()).isEqualTo("BASIC");
		assertThat(imageCommand.path("referenceUrls").get(0).asText()).isEqualTo("/api/v1/uploads/a.png");

		ai.enqueue(jobState("QUEUED", "\"progress\": 0"));
		mvc.perform(get("/image-jobs/" + imageJob)).andExpect(jsonPath("$.status").value("RUNNING"));
		ai.enqueue(jobState("COMPLETED", "\"result\": {\"imageUrl\": \"/api/v1/uploads/gen.png\", \"prompt\": \"밝은 배경\"}"));
		mvc.perform(get("/image-jobs/" + imageJob)).andExpect(jsonPath("$.status").value("DONE"))
			.andExpect(jsonPath("$.result.src").value("/api/v1/uploads/gen.png"));

		// 7) 재생성 결과로 1번 사진 교체 — id 유지
		ai.enqueue(jobState("COMPLETED", "\"result\": {\"imageUrl\": \"/api/v1/uploads/gen.png\", \"prompt\": \"밝은 배경\"}"));
		mvc.perform(post(path("VN") + "/images/generated").contentType(MediaType.APPLICATION_JSON)
			.content("{\"generatedImageId\": \"" + imageJob + "\", \"target\": \"product\", \"replaceImageId\": \""
					+ firstImage + "\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(firstImage))
			.andExpect(jsonPath("$.src").value("/api/v1/uploads/gen.png"));

		// 8) 대표 지정 → 대표 삭제 시 남은 첫 장이 대표
		mvc.perform(put(path("VN") + "/images/main").contentType(MediaType.APPLICATION_JSON)
			.content("{\"imageId\": \"" + secondImage + "\"}"))
			.andExpect(jsonPath("$.mainImageId").value(secondImage));
		mvc.perform(delete(path("VN") + "/images/" + secondImage)).andExpect(status().isNoContent());
		mvc.perform(get(path("VN"))).andExpect(jsonPath("$.mainImageId").value(firstImage))
			.andExpect(jsonPath("$.productImages.length()").value(1));

		// 9) 참고 사진이 하나도 맞지 않으면 422
		mvc.perform(post(path("VN") + "/images/generate").contentType(MediaType.APPLICATION_JSON)
			.content("{\"target\": \"detail\", \"prompt\": \"x\", \"referenceIds\": [\"없는사진\"]}"))
			.andExpect(status().isUnprocessableEntity());
	}

	@Test
	void 생성이_실패하면_사유를_알려준다() throws Exception {
		ai.enqueue(accepted());
		mvc.perform(post(path("VN"))).andExpect(status().isAccepted());
		ai.enqueue(jobState("FAILED", "\"error\": {\"code\": \"LLM_TIMEOUT\", \"detail\": \"\"}"));
		mvc.perform(get(path("VN"))).andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("FAILED"))
			.andExpect(jsonPath("$.errorCode").value("LLM_TIMEOUT"));

		// 실패한 상세페이지에는 이미지를 붙일 수 없다
		mvc.perform(put(path("VN") + "/images/main").contentType(MediaType.APPLICATION_JSON)
			.content("{\"imageId\": \"1\"}")).andExpect(status().isConflict());
	}

	@Test
	void 다른_상품의_이미지_잡은_볼_수_없다() throws Exception {
		mvc.perform(get("/image-jobs/i_999999_1")).andExpect(status().isNotFound());
		mvc.perform(get("/image-jobs/c_" + productId + "_VN_1")).andExpect(status().isNotFound());
	}

	private String path(String country) {
		return "/products/" + productId + "/countries/" + country + "/detail-page";
	}

	private JsonNode body(org.springframework.test.web.servlet.ResultActions actions) throws Exception {
		return json.readTree(actions.andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
	}

	private static MockResponse accepted() {
		return jsonResponse("{\"jobId\": \"x\", \"jobType\": \"CONTENT\", \"status\": \"QUEUED\"}").setResponseCode(202);
	}

	private static MockResponse jobState(String status, String extra) {
		return jsonResponse("{\"jobId\": \"x\", \"jobType\": \"CONTENT\", \"status\": \"" + status + "\", " + extra + "}");
	}

	private static MockResponse jsonResponse(String body) {
		return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
	}

	private static String contentResult() {
		return """
				{"jobId": "x", "status": "NEEDS_REVIEW", "locale": "vi", "qualityScore": 64,
				 "qualityIssues": [{"kind": "TRADEMARK", "detail": "타사 상표", "hardBlock": true}],
				 "ko": {"name": "포근 캐릭터 인형", "usps": ["부드러운 촉감"], "description": "본문",
				        "specs": [["브랜드", "Kora"]], "breadcrumb": ["Shopee", "완구"],
				        "options": [{"label": "색상", "values": ["핑크", "블루"]}]},
				 "local": {"name": "Gấu bông Kora", "usps": ["Mềm mại"], "description": "Nội dung",
				        "specs": [["Thương hiệu", "Kora"]], "breadcrumb": ["Shopee", "Đồ chơi"],
				        "options": [{"label": "Màu", "values": ["Hồng", "Xanh"]}]}}
				""";
	}
}
