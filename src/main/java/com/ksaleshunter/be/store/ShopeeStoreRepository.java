package com.ksaleshunter.be.store;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ShopeeStoreRepository extends JpaRepository<ShopeeStore, Long> {

	List<ShopeeStore> findByUserIdOrderByConnectedAt(Long userId);

	Optional<ShopeeStore> findByUserIdAndCountryCode(Long userId, String countryCode);

	void deleteByUserIdAndCountryCode(Long userId, String countryCode);
}
