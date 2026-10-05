package com.ksaleshunter.be.market;

import java.time.OffsetDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** market_profiles — 셀러 브랜드 설정. AI 시장 해설·콘텐츠 톤·코파일럿 프롬프트에 반영된다 (MKT-01). */
@Entity
@Table(name = "market_profiles")
public class MarketProfile {

	@Id
	@Column(name = "user_id")
	private Long userId;

	@Column(name = "market_name", nullable = false, length = 100)
	private String marketName;

	@Column(name = "brand_direction", length = 50)
	private String brandDirection;

	@Column(name = "seller_type", length = 50)
	private String sellerType;

	@Column(name = "main_target", columnDefinition = "text")
	private String mainTarget;

	@Column(name = "brand_tone", columnDefinition = "text")
	private String brandTone;

	@Column(name = "updated_at")
	private OffsetDateTime updatedAt;

	protected MarketProfile() {
	}

	/** 브랜드 설정 4항목을 바꾸고 수정일시를 갱신한다. market_name 은 계정 정보 화면에서 바꾼다. */
	public void update(String brandDirection, String sellerType, String mainTarget, String brandTone) {
		this.brandDirection = brandDirection;
		this.sellerType = sellerType;
		this.mainTarget = mainTarget;
		this.brandTone = brandTone;
		this.updatedAt = OffsetDateTime.now();
	}

	public String getMarketName() { return marketName; }
	public String getBrandDirection() { return brandDirection; }
	public String getSellerType() { return sellerType; }
	public String getMainTarget() { return mainTarget; }
	public String getBrandTone() { return brandTone; }
}
