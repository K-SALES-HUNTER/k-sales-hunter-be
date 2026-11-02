package com.ksaleshunter.be.detailpage;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface DetailPageRepository extends JpaRepository<DetailPage, Long> {

	Optional<DetailPage> findByProductIdAndCountryCode(Long productId, String countryCode);
}
