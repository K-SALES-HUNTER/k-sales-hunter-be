package com.ksaleshunter.be.detailpage;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 상세페이지 이미지 (detail_page_images).
 *
 * <p>PRODUCT = Shopee 상품 이미지(대표 1장 포함), DETAIL = 상세 설명 이미지.
 * 생성 직후에는 상품 등록 때 올린 사진(product_images)을 PRODUCT 로 복사해 둔다.
 * 재생성 결과를 확정할 때 replaceImageId 를 주면 행을 새로 만들지 않고 url 만 바꾼다(FE 가 id 를 유지한다).
 */
@Entity
@Table(name = "detail_page_images")
public class DetailPageImage {

	public enum Kind { PRODUCT, DETAIL }

	public enum Source { AI, UPLOAD }

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "detail_page_id", nullable = false)
	private Long detailPageId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private Kind kind;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private Source source;

	@Column(nullable = false)
	private String url;

	@Column(name = "is_main", nullable = false)
	private boolean main;

	@Column(name = "sort_order", nullable = false)
	private int sortOrder;

	@Column(name = "model_type")
	private String modelType;

	private String prompt;

	protected DetailPageImage() {
	}

	public static DetailPageImage of(Long detailPageId, Kind kind, Source source, String url, int sortOrder,
			String prompt) {
		DetailPageImage image = new DetailPageImage();
		image.detailPageId = detailPageId;
		image.kind = kind;
		image.source = source;
		image.url = url;
		image.sortOrder = sortOrder;
		image.prompt = prompt;
		return image;
	}

	/** 재생성 결과로 교체. 위치와 id 는 그대로다. */
	public void replace(String url, String prompt, String modelType) {
		this.url = url;
		this.prompt = prompt;
		this.modelType = modelType;
		this.source = Source.AI;
	}

	public void setMain(boolean main) {
		this.main = main;
	}

	public void setModelType(String modelType) {
		this.modelType = modelType;
	}

	public Long getId() {
		return id;
	}

	public Long getDetailPageId() {
		return detailPageId;
	}

	public Kind getKind() {
		return kind;
	}

	public String getUrl() {
		return url;
	}

	public boolean isMain() {
		return main;
	}

	public int getSortOrder() {
		return sortOrder;
	}

	public String getPrompt() {
		return prompt;
	}
}
