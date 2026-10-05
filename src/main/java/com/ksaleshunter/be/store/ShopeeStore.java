package com.ksaleshunter.be.store;

import java.time.OffsetDateTime;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** shopee_stores — 국가별 Shopee 스토어 연동. UNIQUE(user_id, country_code). */
@Entity
@Table(name = "shopee_stores")
public class ShopeeStore {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "user_id", nullable = false)
	private Long userId;

	// DB 컬럼이 CHAR(2) 라 Hibernate 스키마 검증(validate)이 VARCHAR 로 오인하지 않게 타입을 고정한다
	@JdbcTypeCode(SqlTypes.CHAR)
	@Column(name = "country_code", nullable = false, length = 2)
	private String countryCode;

	@Column(name = "store_name", length = 100)
	private String storeName;

	@Column(name = "access_token", columnDefinition = "text")
	private String accessToken; // 암호화 저장. 평문 금지

	@Column(name = "refresh_token", columnDefinition = "text")
	private String refreshToken;

	@Column(nullable = false, length = 20)
	private String status;

	@Column(name = "connected_at", nullable = false)
	private OffsetDateTime connectedAt;

	protected ShopeeStore() {
	}

	/** 새 연동. 상태는 active, 연동일시는 지금. */
	public static ShopeeStore connect(Long userId, String countryCode, String storeName) {
		ShopeeStore store = new ShopeeStore();
		store.userId = userId;
		store.countryCode = countryCode;
		store.storeName = storeName;
		store.status = "active";
		store.connectedAt = OffsetDateTime.now();
		return store;
	}

	public String getCountryCode() { return countryCode; }
	public String getStoreName() { return storeName; }
	public OffsetDateTime getConnectedAt() { return connectedAt; }
}
