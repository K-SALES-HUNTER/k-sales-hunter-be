package com.ksaleshunter.be.store;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** 마켓/설정 › 연동. 국가별 Shopee 스토어 등록·조회·해제. 응답은 FE ConnectedStore 타입과 1:1. */
@RestController
@RequestMapping("/stores")
public class StoreController {

	private final ShopeeStoreRepository repo;
	private final JdbcClient jdbc;

	public StoreController(ShopeeStoreRepository repo, JdbcClient jdbc) {
		this.repo = repo;
		this.jdbc = jdbc;
	}

	/** FE ConnectedStore 타입. connectedAt 은 YYYY-MM-DD 문자열이다. */
	public record ConnectedStore(String countryCode, String countryName, String storeName,
			String connectedAt) {
	}

	/** 연동된 스토어 목록. 연동한 순서대로. */
	@GetMapping
	public List<ConnectedStore> list(@AuthenticationPrincipal Long userId) {
		return repo.findByUserIdOrderByConnectedAt(userId).stream().map(this::toDto).toList();
	}

	/**
	 * 국가별 스토어 연동. 같은 국가를 다시 누르면 기존 연동을 그대로 돌려준다(멱등).
	 * 스토어명 기본값은 셀러의 마켓 이름이다. Shopee OAuth 토큰 발급·암호화 저장(access/refresh_token)은
	 * shopee_stores 테이블에 자리가 있고, 실연동 시 이 메서드에서 인증 코드 교환 후 채운다.
	 */
	@PostMapping("/{cc}/connect")
	@Transactional
	public ConnectedStore connect(@AuthenticationPrincipal Long userId, @PathVariable String cc) {
		String code = cc.toUpperCase();
		countryName(code); // 지원 국가가 아니면 404
		ShopeeStore store = repo.findByUserIdAndCountryCode(userId, code) // 이미 연동돼 있으면 그대로
			.orElseGet(() -> repo.save(ShopeeStore.connect(userId, code, marketName(userId))));
		return toDto(store);
	}

	/** 연동 해제. 행을 지운다. 해당 국가의 판매 이력(listings·orders)은 건드리지 않는다. */
	@DeleteMapping("/{cc}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@Transactional
	public void disconnect(@AuthenticationPrincipal Long userId, @PathVariable String cc) {
		repo.deleteByUserIdAndCountryCode(userId, cc.toUpperCase());
	}

	private ConnectedStore toDto(ShopeeStore s) {
		return new ConnectedStore(s.getCountryCode(), countryName(s.getCountryCode()),
				s.getStoreName(), s.getConnectedAt().toLocalDate().toString()); // YYYY-MM-DD
	}

	private String countryName(String code) {
		return jdbc.sql("SELECT name FROM countries WHERE code = ?").param(code)
			.query(String.class).optional()
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "지원하지 않는 국가입니다"));
	}

	private String marketName(Long userId) {
		return jdbc.sql("SELECT market_name FROM market_profiles WHERE user_id = ?").param(userId)
			.query(String.class).optional().orElse("");
	}
}
