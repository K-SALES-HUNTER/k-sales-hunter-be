package com.ksaleshunter.be.detailpage;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface DetailPageImageRepository extends JpaRepository<DetailPageImage, Long> {

	List<DetailPageImage> findByDetailPageIdOrderBySortOrderAscIdAsc(Long detailPageId);

	Optional<DetailPageImage> findByIdAndDetailPageId(Long id, Long detailPageId);

	@Modifying
	@Query("delete from DetailPageImage i where i.detailPageId = :detailPageId")
	void deleteByDetailPageId(Long detailPageId);
}
