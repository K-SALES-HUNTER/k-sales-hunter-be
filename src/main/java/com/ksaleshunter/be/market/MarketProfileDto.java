package com.ksaleshunter.be.market;

import jakarta.validation.constraints.Size;

/** FE MarketInfo 타입과 1:1 (src/types/settings.ts). */
public record MarketProfileDto(
		@Size(max = 50) String brandDirection,
		@Size(max = 50) String sellerType,
		String mainTarget,
		String brandTone) {

	static MarketProfileDto from(MarketProfile p) {
		return new MarketProfileDto(nz(p.getBrandDirection()), nz(p.getSellerType()),
				nz(p.getMainTarget()), nz(p.getBrandTone()));
	}

	private static String nz(String v) {
		return v == null ? "" : v;
	}
}
