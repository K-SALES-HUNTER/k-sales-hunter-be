-- 상세페이지 검수·수정 상태 (강근우 detail-page)
--
-- V1 의 detail_pages 는 생성 결과만 담는다. 화면이 쓰는 세 가지가 빠져 있어 덧붙인다.
--   edited          셀러가 텍스트를 고쳤는지. 고친 뒤 재생성하면 수정본을 버린다는 안내에 쓴다
--   quality_issues  품질 검수 지적 [{kind, detail, hardBlock}]. hardBlock 이 있으면 NEEDS_REVIEW
--   error_code      생성 실패 사유 (AI ErrorCode). FE 가 메시지를 고른다

ALTER TABLE detail_pages
    ADD COLUMN edited         BOOLEAN     NOT NULL DEFAULT FALSE,
    ADD COLUMN quality_issues JSONB       NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN error_code     VARCHAR(64);
