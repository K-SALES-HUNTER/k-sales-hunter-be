package com.ksaleshunter.be.detailpage;

import java.time.OffsetDateTime;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.fasterxml.jackson.databind.JsonNode;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 국가별 상세페이지 (detail_pages). 상품 × 국가당 1행이다.
 *
 * <p>생성 잡을 다시 돌리면 같은 행을 GENERATING 으로 되돌려 재사용한다. 셀러 수정본은 그때 버린다.
 * content_ko · content_local 은 AI PdpDraft 그대로다 (name, usps, description, specs, breadcrumb, options).
 * 가격·배송·재고 문구는 저장하지 않는다. 판매 정보가 바뀌면 바로 반영되도록 조회 때 만든다.
 */
@Entity
@Table(name = "detail_pages")
public class DetailPage {

	public enum Status { GENERATING, DONE, NEEDS_REVIEW, FAILED }

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "product_id", nullable = false)
	private Long productId;

	@Column(name = "country_code", nullable = false, columnDefinition = "bpchar")
	private String countryCode;

	@Column(name = "ai_job_id")
	private String aiJobId;

	private String locale;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "content_ko", columnDefinition = "jsonb")
	private JsonNode contentKo;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "content_local", columnDefinition = "jsonb")
	private JsonNode contentLocal;

	@Column(name = "quality_score")
	private Integer qualityScore;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "quality_issues", columnDefinition = "jsonb", nullable = false)
	private JsonNode qualityIssues;

	@Column(nullable = false)
	@Enumerated(EnumType.STRING)
	private Status status;

	@Column(nullable = false)
	private boolean edited;

	@Column(name = "error_code")
	private String errorCode;

	@Column(name = "updated_at", nullable = false)
	private OffsetDateTime updatedAt;

	protected DetailPage() {
	}

	public static DetailPage generating(Long productId, String countryCode, String jobId, String locale,
			JsonNode emptyIssues) {
		DetailPage page = new DetailPage();
		page.productId = productId;
		page.countryCode = countryCode;
		page.restart(jobId, locale, emptyIssues);
		return page;
	}

	/** 다시 생성. 이전 결과와 셀러 수정본을 버린다. 이미지는 서비스가 따로 지운다. */
	public void restart(String jobId, String locale, JsonNode emptyIssues) {
		this.aiJobId = jobId;
		this.locale = locale;
		this.contentKo = null;
		this.contentLocal = null;
		this.qualityScore = null;
		this.qualityIssues = emptyIssues;
		this.status = Status.GENERATING;
		this.edited = false;
		this.errorCode = null;
		touch();
	}

	/** 잡 결과(ContentResult)를 옮겨 담는다. */
	public void complete(Status status, String locale, JsonNode ko, JsonNode local, int qualityScore,
			JsonNode qualityIssues) {
		this.status = status;
		if (locale != null && !locale.isBlank()) {
			this.locale = locale;
		}
		this.contentKo = ko;
		this.contentLocal = local;
		this.qualityScore = qualityScore;
		this.qualityIssues = qualityIssues;
		touch();
	}

	public void fail(String errorCode) {
		this.status = Status.FAILED;
		this.errorCode = errorCode;
		touch();
	}

	/** 셀러 수정. 한국어본은 입력 그대로, 현지어본은 재번역 결과가 있을 때만 바꾼다. */
	public void edit(JsonNode ko, JsonNode local) {
		this.contentKo = ko;
		if (local != null) {
			this.contentLocal = local;
		}
		this.edited = true;
		touch();
	}

	public boolean isReady() {
		return status == Status.DONE || status == Status.NEEDS_REVIEW;
	}

	private void touch() {
		this.updatedAt = OffsetDateTime.now();
	}

	public Long getId() {
		return id;
	}

	public Long getProductId() {
		return productId;
	}

	public String getCountryCode() {
		return countryCode;
	}

	public String getAiJobId() {
		return aiJobId;
	}

	public String getLocale() {
		return locale;
	}

	public JsonNode getContentKo() {
		return contentKo;
	}

	public JsonNode getContentLocal() {
		return contentLocal;
	}

	public Integer getQualityScore() {
		return qualityScore;
	}

	public JsonNode getQualityIssues() {
		return qualityIssues;
	}

	public Status getStatus() {
		return status;
	}

	public boolean isEdited() {
		return edited;
	}

	public String getErrorCode() {
		return errorCode;
	}
}
