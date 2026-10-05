package com.ksaleshunter.be.market;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import jakarta.validation.Valid;

/** 마켓/설정 › 마켓 정보 (MKT-01). 저장한 브랜드 설정은 다음 분석·코파일럿 요청부터 AI 에 실린다. */
@RestController
@RequestMapping("/me/market-profile")
public class MarketProfileController {

	private final MarketProfileRepository repo;

	public MarketProfileController(MarketProfileRepository repo) {
		this.repo = repo;
	}

	/** 마켓 정보 조회. 비어 있는 항목은 빈 문자열로 내려 FE 폼이 그대로 바인딩한다. */
	@GetMapping
	public MarketProfileDto get(@AuthenticationPrincipal Long userId) {
		return MarketProfileDto.from(find(userId));
	}

	/**
	 * 마켓 정보 저장. market_profiles 행은 회원가입 때 market_name 과 함께 생기므로 여기서는 수정만 한다.
	 * 저장 즉시 반영되며, 이미 끝난 분석에는 소급하지 않는다(다음 분석·코파일럿 요청부터 적용).
	 */
	@PutMapping
	@Transactional
	public MarketProfileDto update(@AuthenticationPrincipal Long userId,
			@RequestBody @Valid MarketProfileDto req) {
		MarketProfile profile = find(userId);
		profile.update(req.brandDirection(), req.sellerType(), req.mainTarget(), req.brandTone());
		return MarketProfileDto.from(profile); // updated_at 은 엔티티가 갱신
	}

	private MarketProfile find(Long userId) {
		return repo.findById(userId)
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "마켓 정보가 없습니다"));
	}
}
