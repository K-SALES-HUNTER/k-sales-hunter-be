-- K-Sales Hunter public 스키마 초기 구축
--
-- 네 명이 각자 라우트를 만들려면 테이블이 먼저 있어야 해서 한 파일에 몰아 넣는다.
-- 각자 V2, V3 을 따로 만들면 번호가 충돌한다. 이후 변경만 V2 부터 이어 붙인다.
--
-- [규칙 1] ai 스키마를 FK 로 참조하지 않는다.
--          AI 잡 식별자는 Spring 이 발번한 문자열이고 여기서는 그냥 컬럼이다.
--          배포 순서 의존을 없애기 위함이다 (AI 레포 scripts/init_db.sql 참조).
-- [규칙 2] ENUM 타입 대신 VARCHAR + CHECK 를 쓴다.
--          값이 늘 때 ALTER TYPE 없이 제약만 바꾸면 되고 JPA @Enumerated(STRING) 과 그대로 맞는다.
-- [규칙 3] 금액은 원화 정수(_krw)와 현지통화 NUMERIC 을 구분한다.
--          표시용 문자열(₩28,900)은 저장하지 않는다. 서비스 계층에서 만든다.
--
-- 수수료·관세·환율 테이블은 여기 없다. AI 레포가 YAML 로 소유한다
-- (data/fee_schedules, data/shipping_rates). 마진 계산은 AI 의 pricing/quote 를 호출한다.

-- ─────────────────────────────────────────────────────────────
-- 사용자 · 마켓  (차은호 auth / 이동건 settings)
-- ─────────────────────────────────────────────────────────────

CREATE TABLE users (
    id              BIGSERIAL    PRIMARY KEY,
    email           VARCHAR(255) NOT NULL UNIQUE,
    password_hash   VARCHAR(255) NOT NULL,
    business_reg_no CHAR(10)     NOT NULL,          -- 하이픈 제외 10자리. 표기는 화면에서
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE market_profiles (
    user_id         BIGINT      PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    market_name     VARCHAR(100) NOT NULL,          -- 가입 시 입력값이 초기값
    brand_direction VARCHAR(50),                    -- K트렌드 / 가성비 / 프리미엄 ...
    seller_type     VARCHAR(50),                    -- 예비셀러 ~ 에이전시 7종
    main_target     TEXT,
    brand_tone      TEXT,                           -- 시장 해설·콘텐츠 톤에 반영된다
    updated_at      TIMESTAMPTZ
);

-- 참조 데이터. 프론트 판매국가 연동 화면이 12개국을 보여준다.
-- 분석 대상 3개국(VN/SG/TH)은 AI 쪽이 정한다.
CREATE TABLE countries (
    code          CHAR(2)     PRIMARY KEY,
    name          VARCHAR(50) NOT NULL,
    currency_code CHAR(3)     NOT NULL
);

INSERT INTO countries (code, name, currency_code) VALUES
    ('VN', '베트남',     'VND'),
    ('SG', '싱가포르',   'SGD'),
    ('TH', '태국',       'THB'),
    ('MY', '말레이시아', 'MYR'),
    ('ID', '인도네시아', 'IDR'),
    ('PH', '필리핀',     'PHP'),
    ('TW', '대만',       'TWD'),
    ('KH', '캄보디아',   'KHR'),
    ('LA', '라오스',     'LAK'),
    ('BR', '브라질',     'BRL'),
    ('MX', '멕시코',     'MXN'),
    ('AR', '아르헨티나', 'ARS');

CREATE TABLE shopee_stores (
    id           BIGSERIAL    PRIMARY KEY,
    user_id      BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    country_code CHAR(2)     NOT NULL REFERENCES countries (code),
    store_name   VARCHAR(100),
    access_token TEXT,                              -- 암호화해서 넣는다. 평문 금지
    refresh_token TEXT,
    status       VARCHAR(20) NOT NULL DEFAULT 'active'
                 CHECK (status IN ('active', 'expired')),
    connected_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_store_user_country UNIQUE (user_id, country_code)
);

-- ─────────────────────────────────────────────────────────────
-- 상품  (차은호 products·uploads)
-- ─────────────────────────────────────────────────────────────

CREATE TABLE products (
    id              BIGSERIAL    PRIMARY KEY,
    user_id         BIGINT       NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    name            VARCHAR(200) NOT NULL,
    category        VARCHAR(50)  NOT NULL,          -- 프론트 드롭다운 11종
    supply_cost_krw INTEGER      NOT NULL,
    weight_g        INTEGER      NOT NULL,          -- 포장 전 상품 무게
    description     TEXT,
    selling_point   TEXT,
    main_target     TEXT,
    -- 포장 완료 기준 치수. 배송 요율 구간을 결정한다 (R-004-10). 미입력이면 NULL
    pkg_weight_g    INTEGER,
    pkg_width_mm    INTEGER,
    pkg_depth_mm    INTEGER,
    pkg_height_mm   INTEGER,
    -- AI 가 채운 필드명 배열. 사용자가 고치면 해당 항목을 뺀다
    ai_filled_fields JSONB      NOT NULL DEFAULT '[]'::jsonb,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ
);

CREATE INDEX ix_products_user ON products (user_id, created_at DESC);

CREATE TABLE product_images (
    id         BIGSERIAL    PRIMARY KEY,
    product_id BIGINT      NOT NULL REFERENCES products (id) ON DELETE CASCADE,
    url        TEXT        NOT NULL,
    sort_order INTEGER     NOT NULL DEFAULT 0,      -- 0 번이 대표. 최대 12장
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX ix_product_images_product ON product_images (product_id, sort_order);

-- ─────────────────────────────────────────────────────────────
-- 분석  (차은호 analysis 프록시·보고서 매핑)
-- ─────────────────────────────────────────────────────────────

-- AI 분석 잡의 Spring 쪽 기록. 프론트 로딩 오버레이가 이 상태를 폴링한다.
CREATE TABLE analysis_runs (
    id           BIGSERIAL    PRIMARY KEY,
    product_id   BIGINT      NOT NULL REFERENCES products (id) ON DELETE CASCADE,
    -- AI 레포 ai.jobs.job_id 와 같은 값. FK 는 걸지 않는다 (규칙 1)
    ai_job_id    VARCHAR(64) NOT NULL,
    run_type     VARCHAR(10) NOT NULL DEFAULT 'FULL'
                 CHECK (run_type IN ('FULL', 'PARTIAL')),
    status       VARCHAR(20) NOT NULL DEFAULT 'RUNNING'
                 CHECK (status IN ('QUEUED','RUNNING','COMPLETED','FAILED','CANCELLED')),
    -- 프론트 오버레이 5단계
    current_step VARCHAR(20)
                 CHECK (current_step IN ('GATE','MARKET','SHIPPING','MARGIN','REPORT')),
    progress     NUMERIC(4,3) NOT NULL DEFAULT 0,
    error_code   VARCHAR(64),
    started_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at  TIMESTAMPTZ
);

CREATE INDEX ix_analysis_runs_product ON analysis_runs (product_id, started_at DESC);
CREATE UNIQUE INDEX ux_analysis_runs_job ON analysis_runs (ai_job_id);

-- 글로벌 분석 보고서. 재생성할 때마다 version 이 오른다
CREATE TABLE reports (
    id                BIGSERIAL    PRIMARY KEY,
    product_id        BIGINT      NOT NULL REFERENCES products (id) ON DELETE CASCADE,
    version           INTEGER     NOT NULL DEFAULT 1,
    is_latest         BOOLEAN     NOT NULL DEFAULT TRUE,
    conclusion_title  TEXT,
    conclusion_body   TEXT,
    best_country_code CHAR(2)     REFERENCES countries (code),
    investigation     JSONB,                        -- 조사 국가·기준·플랫폼
    next_actions      TEXT,
    generated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_reports_version UNIQUE (product_id, version)
);

-- 최신본은 상품당 하나만 존재한다
CREATE UNIQUE INDEX ux_reports_latest ON reports (product_id) WHERE is_latest;

-- 국가별 보고서. 부분 재실행 시 해당 국가만 version 이 오른다
CREATE TABLE country_reports (
    id                   BIGSERIAL    PRIMARY KEY,
    product_id           BIGINT      NOT NULL REFERENCES products (id) ON DELETE CASCADE,
    country_code         CHAR(2)     NOT NULL REFERENCES countries (code),
    version              INTEGER     NOT NULL DEFAULT 1,
    is_latest            BOOLEAN     NOT NULL DEFAULT TRUE,
    status               VARCHAR(20) NOT NULL DEFAULT 'DONE'
                         CHECK (status IN ('DONE','FILTERED_OUT','NEEDS_REVIEW','FAILED')),
    rank                 INTEGER,
    -- 4축 점수. 등급은 점수에서 파생되지만 화면이 바로 쓰도록 같이 저장한다
    entry_score          INTEGER,
    entry_grade          VARCHAR(10) CHECK (entry_grade IN ('FIT','NORMAL','CAUTION')),
    fit_grade            VARCHAR(20)
                         CHECK (fit_grade IN ('VERY_FIT','PARTIAL_FIT','NORMAL','CAUTION')),
    score_demand         INTEGER,
    grade_demand         VARCHAR(10) CHECK (grade_demand IN ('HIGH','MID','LOW')),
    score_competition    INTEGER,
    grade_competition    VARCHAR(10) CHECK (grade_competition IN ('HIGH','MID','LOW')),
    score_ktrend         INTEGER,
    grade_ktrend         VARCHAR(10) CHECK (grade_ktrend IN ('HIGH','MID','LOW')),
    score_profitability  INTEGER,
    grade_profitability  VARCHAR(10) CHECK (grade_profitability IN ('HIGH','MID','LOW')),
    positioning          VARCHAR(10)
                         CHECK (positioning IN ('ENTRY','PREMIUM','FANDOM','GIFT')),
    recommended_price_krw   INTEGER,
    recommended_price_local NUMERIC(18,2),
    currency             CHAR(3),
    -- 해설·경쟁 환경·배송·통관 경고. 화면 표시 전용이라 통째로 담는다
    analysis             JSONB,
    generated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_country_reports_version UNIQUE (product_id, country_code, version)
);

CREATE UNIQUE INDEX ux_country_reports_latest
    ON country_reports (product_id, country_code) WHERE is_latest;

-- 가격 3안. 보고서 버전마다 3행이라 가격안 이력이 자동으로 남는다
CREATE TABLE price_options (
    id                BIGSERIAL    PRIMARY KEY,
    country_report_id BIGINT       NOT NULL REFERENCES country_reports (id) ON DELETE CASCADE,
    tier              VARCHAR(4)   NOT NULL CHECK (tier IN ('LOW','MID','HIGH')),
    price_krw         INTEGER      NOT NULL,
    price_local       NUMERIC(18,2),
    net_profit_krw    INTEGER,
    margin_rate       NUMERIC(6,4),
    break_even_units  INTEGER,
    badge             VARCHAR(50),
    summary           TEXT,
    cost_rows         JSONB,                        -- 비용 차감 구조 7행
    is_recommended    BOOLEAN      NOT NULL DEFAULT FALSE,
    CONSTRAINT uq_price_options_tier UNIQUE (country_report_id, tier)
);

-- ─────────────────────────────────────────────────────────────
-- 판매 정보  (권수현 sales-info)
-- ─────────────────────────────────────────────────────────────

CREATE TABLE sales_infos (
    id                  BIGSERIAL    PRIMARY KEY,
    product_id          BIGINT      NOT NULL REFERENCES products (id) ON DELETE CASCADE,
    country_code        CHAR(2)     NOT NULL REFERENCES countries (code),
    selected_price_tier VARCHAR(4)  CHECK (selected_price_tier IN ('LOW','MID','HIGH')),
    final_price_krw     INTEGER,                    -- 사용자 확정가. AI 추천값과 구분해 둔다
    final_price_local   NUMERIC(18,2),
    currency            CHAR(3),
    shopee_category_id  VARCHAR(50),
    category_attributes JSONB,                      -- 카테고리 종속이라 스키마가 가변이다
    options             JSONB,                      -- 1단·2단 옵션 정의
    shipping_method     VARCHAR(10) CHECK (shipping_method IN ('DIRECT','SLS')),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_sales_infos_product_country UNIQUE (product_id, country_code)
);

-- 옵션 조합별 재고. 조합은 최대 50개 (Shopee 제약)
CREATE TABLE variants (
    id            BIGSERIAL    PRIMARY KEY,
    sales_info_id BIGINT       NOT NULL REFERENCES sales_infos (id) ON DELETE CASCADE,
    option1_value VARCHAR(100) NOT NULL,
    option2_value VARCHAR(100),                     -- 2단 미사용이면 NULL
    stock_qty     INTEGER      NOT NULL DEFAULT 0,
    extra_price   NUMERIC(18,2) NOT NULL DEFAULT 0, -- 옵션별 추가금
    CONSTRAINT uq_variants_combo UNIQUE (sales_info_id, option1_value, option2_value)
);

-- 재고는 덮어쓰지 않고 증분으로 더한다. 저장 사이 주문이 들어와도 어긋나지 않는다
CREATE TABLE stock_additions (
    id         BIGSERIAL    PRIMARY KEY,
    variant_id BIGINT      NOT NULL REFERENCES variants (id) ON DELETE CASCADE,
    added_qty  INTEGER     NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ─────────────────────────────────────────────────────────────
-- 상세페이지  (강근우 detail-page·images)
-- ─────────────────────────────────────────────────────────────

CREATE TABLE detail_pages (
    id            BIGSERIAL    PRIMARY KEY,
    product_id    BIGINT      NOT NULL REFERENCES products (id) ON DELETE CASCADE,
    country_code  CHAR(2)     NOT NULL REFERENCES countries (code),
    ai_job_id     VARCHAR(64),                      -- 생성 잡. 규칙 1 에 따라 FK 없음
    locale        VARCHAR(10),                      -- vi / en / th
    content_ko    JSONB,                            -- 검수용 한국어본
    content_local JSONB,                            -- 업로드용 현지어본. 같은 구조
    quality_score INTEGER,                          -- 0~100. 기준 미달이면 재생성
    status        VARCHAR(20) NOT NULL DEFAULT 'GENERATING'
                  CHECK (status IN ('GENERATING','DONE','NEEDS_REVIEW','FAILED')),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_detail_pages_product_country UNIQUE (product_id, country_code)
);

CREATE TABLE detail_page_images (
    id             BIGSERIAL    PRIMARY KEY,
    detail_page_id BIGINT      NOT NULL REFERENCES detail_pages (id) ON DELETE CASCADE,
    kind           VARCHAR(10) NOT NULL CHECK (kind IN ('PRODUCT','DETAIL')),
    source         VARCHAR(10) NOT NULL CHECK (source IN ('AI','UPLOAD')),
    url            TEXT        NOT NULL,
    is_main        BOOLEAN     NOT NULL DEFAULT FALSE,  -- kind=PRODUCT 에서 항상 1장
    sort_order     INTEGER     NOT NULL DEFAULT 0,
    model_type     VARCHAR(30),                      -- 모델 컷 4종 중 어느 것
    prompt         TEXT                              -- AI 생성 시 요청 문구
);

CREATE INDEX ix_detail_images_page ON detail_page_images (detail_page_id, kind, sort_order);

-- ─────────────────────────────────────────────────────────────
-- 판매 관리  (권수현 sales-ops)
-- ─────────────────────────────────────────────────────────────

-- Shopee 업로드는 과업 범위 밖이다 (기술결정서 결정-2).
-- 이 표는 셀러가 직접 올린 상품의 링크와 상태를 기록하는 용도다.
CREATE TABLE listings (
    id             BIGSERIAL    PRIMARY KEY,
    sales_info_id  BIGINT      NOT NULL UNIQUE REFERENCES sales_infos (id) ON DELETE CASCADE,
    shopee_item_id VARCHAR(50),
    shopee_url     TEXT,
    status         VARCHAR(20) NOT NULL DEFAULT 'SELLING'
                   CHECK (status IN ('SELLING','STOPPED')),
    stop_reason    VARCHAR(20) CHECK (stop_reason IN ('USER','CUSTOMS','SOLDOUT')),
    uploaded_at    TIMESTAMPTZ,
    last_synced_at TIMESTAMPTZ
);

CREATE TABLE orders (
    id              BIGSERIAL    PRIMARY KEY,
    listing_id      BIGINT       NOT NULL REFERENCES listings (id) ON DELETE CASCADE,
    variant_id      BIGINT       REFERENCES variants (id),
    shopee_order_no VARCHAR(50)  NOT NULL,
    ordered_at      TIMESTAMPTZ,
    qty             INTEGER      NOT NULL DEFAULT 1,
    total_amount    NUMERIC(18,2),
    currency        CHAR(3),
    -- 주문 시점의 비용 구조로 계산한 순이익. 나중에 요율이 바뀌어도 집계가 흔들리지 않는다
    net_profit_krw  INTEGER,
    shipping_status VARCHAR(20),
    claim_type      VARCHAR(20),
    claim_status    VARCHAR(20),
    synced_at       TIMESTAMPTZ,
    CONSTRAINT uq_orders_listing_no UNIQUE (listing_id, shopee_order_no)
);

CREATE INDEX ix_orders_listing ON orders (listing_id, ordered_at DESC);

CREATE TABLE price_change_logs (
    id            BIGSERIAL    PRIMARY KEY,
    listing_id    BIGINT      NOT NULL REFERENCES listings (id) ON DELETE CASCADE,
    old_price_krw INTEGER,
    new_price_krw INTEGER,
    changed_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ─────────────────────────────────────────────────────────────
-- 코파일럿  (이동건)
-- ─────────────────────────────────────────────────────────────

-- 대화는 화면을 옮겨도 이어진다. product_id 가 NULL 이면 전역 대화
CREATE TABLE chat_messages (
    id         BIGSERIAL    PRIMARY KEY,
    user_id    BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    product_id BIGINT      REFERENCES products (id) ON DELETE CASCADE,
    role       VARCHAR(10) NOT NULL CHECK (role IN ('USER','ASSISTANT')),
    content    TEXT        NOT NULL,
    intent     VARCHAR(20),                         -- QUERY / COMMAND / CLARIFY
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX ix_chat_messages_thread ON chat_messages (user_id, product_id, created_at);
