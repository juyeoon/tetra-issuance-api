-- 테넌트 스키마 t_{tenant_id} (Confluence "MVP 물리 ERD", 2026-10-15 사본)
-- 로컬 전용: 시드 테넌트 poctenant001 의 스키마. 운영에서는 테넌트 가입(WF5)이 같은 DDL로 만든다.
-- 발급 API가 쓰는 테이블만 만든다 (event · event_auth_key · coupon · issuance_history · event_result_summary).
--   Slack 설정·알림·리포트·비용·상태 이력은 Portal·Console 소관이라 넣지 않는다.
CREATE DATABASE IF NOT EXISTS t_poctenant001
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_0900_ai_ci;
USE t_poctenant001;

CREATE TABLE event (
  event_id           INT UNSIGNED  NOT NULL,                     -- tetra_platform.event_registry 가 발급 (AUTO_INCREMENT 없음)
  tenant_id          CHAR(12) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  name               VARCHAR(100)  NOT NULL,
  event_type         VARCHAR(20)   NOT NULL,
  template_id        INT UNSIGNED  NOT NULL,
  layout_config      JSON          NOT NULL,
  banner_image_path  VARCHAR(1024) NULL,
  start_at           DATETIME      NOT NULL,
  end_at             DATETIME      NOT NULL,
  hosting_start_at   DATETIME      NOT NULL,
  hosting_end_at     DATETIME      NOT NULL,
  endpoint_url       VARCHAR(2048) NOT NULL,
  traffic_plan_code  VARCHAR(10) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  stock_count        INT UNSIGNED  NOT NULL,                     -- 일괄 발급 재고(이벤트 단위 카운터 1개)
  slug               CHAR(8) CHARACTER SET ascii COLLATE ascii_bin NULL DEFAULT NULL,
  applied_at         DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  processed_by       INT UNSIGNED  NULL DEFAULT NULL,
  processed_at       DATETIME      NULL DEFAULT NULL,
  reject_reason      VARCHAR(500)  NULL DEFAULT NULL,
  application_status VARCHAR(20)   NOT NULL DEFAULT 'APPLYING',
  hosting_status     VARCHAR(20)   NULL DEFAULT NULL,
  event_status       VARCHAR(20)   NULL DEFAULT NULL,
  PRIMARY KEY (event_id),
  UNIQUE KEY uk_event_slug (slug),
  KEY idx_event_applied (applied_at),
  KEY idx_event_start (start_at),
  KEY idx_event_template (template_id),
  KEY idx_event_plan (traffic_plan_code),
  KEY idx_event_operator (processed_by),
  CONSTRAINT chk_event_tenant_id_format CHECK (tenant_id REGEXP '^[a-z0-9]{12}$'),
  CONSTRAINT chk_event_type CHECK (event_type IN ('COUPON','TIMESALE','LIVE_COMMERCE')),
  CONSTRAINT chk_event_period CHECK (end_at > start_at),
  CONSTRAINT chk_event_hosting_period CHECK (hosting_end_at > hosting_start_at),
  CONSTRAINT chk_event_stock CHECK (stock_count > 0),
  CONSTRAINT chk_event_slug CHECK (slug REGEXP '^[a-z0-9]{8}$'),
  CONSTRAINT chk_event_app_status CHECK (application_status IN ('APPLYING','APPLIED','APPLY_FAILED','APPROVED','REJECTED')),
  CONSTRAINT chk_event_hosting_status CHECK (hosting_status IN ('PENDING','DEPLOYED','DEPLOY_FAILED','HOSTING','HOSTING_FAILED','ENDED','END_FAILED')),
  CONSTRAINT chk_event_event_status CHECK (event_status IN ('SCHEDULED','OPEN','OPEN_FAILED','EXPIRED')),
  CONSTRAINT chk_event_approved CHECK ((application_status = 'APPROVED') = (hosting_status IS NOT NULL AND event_status IS NOT NULL)),
  CONSTRAINT chk_event_reject CHECK ((application_status = 'REJECTED') = (reject_reason IS NOT NULL)),
  CONSTRAINT chk_event_hosting_cover CHECK (hosting_start_at <= start_at AND hosting_end_at >= end_at),
  CONSTRAINT chk_event_banner CHECK (application_status IN ('APPLYING','APPLY_FAILED') OR banner_image_path IS NOT NULL),
  CONSTRAINT chk_event_layout_config CHECK (JSON_SCHEMA_VALID('{
    "type": "object",
    "required": ["title", "banner", "couponList", "issueButton", "theme"],
    "additionalProperties": false,
    "definitions": {
      "pos": {
        "type": "object", "required": ["h", "v"], "additionalProperties": false,
        "properties": {
          "h": {"enum": ["LEFT", "CENTER", "RIGHT"]},
          "v": {"enum": ["TOP", "MIDDLE", "BOTTOM"]}
        }
      }
    },
    "properties": {
      "title": {"$ref": "#/definitions/pos"},
      "banner": {"$ref": "#/definitions/pos"},
      "couponList": {"$ref": "#/definitions/pos"},
      "issueButton": {"$ref": "#/definitions/pos"},
      "theme": {"enum": ["WHITE", "WARM_ORANGE", "DARK", "BLUE"]}
    }
  }', layout_config)),
  CONSTRAINT fk_event_registry FOREIGN KEY (event_id) REFERENCES tetra_platform.event_registry (event_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT fk_event_template FOREIGN KEY (template_id) REFERENCES tetra_platform.template (template_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT fk_event_plan FOREIGN KEY (traffic_plan_code) REFERENCES tetra_platform.traffic_plan (plan_code) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT fk_event_operator FOREIGN KEY (processed_by) REFERENCES tetra_platform.operator (operator_id) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB;

CREATE TABLE event_auth_key (
  key_id      INT UNSIGNED  NOT NULL AUTO_INCREMENT,
  event_id    INT UNSIGNED  NOT NULL,
  tenant_id   CHAR(12) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  public_key  VARCHAR(2048) NOT NULL,                            -- RS256 공개키(PEM)
  key_version INT UNSIGNED  NOT NULL DEFAULT 1,                  -- JWT 헤더 kid
  status      VARCHAR(20)   NOT NULL DEFAULT 'ACTIVE',
  issued_at   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  expires_at  DATETIME      NOT NULL,                            -- 발급 시 hosting_end_at
  revoked_at  DATETIME      NULL DEFAULT NULL,
  PRIMARY KEY (key_id),
  UNIQUE KEY uk_auth_key_event_version (event_id, key_version),
  CONSTRAINT chk_auth_key_tenant_id_format CHECK (tenant_id REGEXP '^[a-z0-9]{12}$'),
  CONSTRAINT chk_auth_key_status CHECK (status IN ('ACTIVE','EXPIRED','REVOKED')),
  CONSTRAINT chk_auth_key_expires CHECK (expires_at > issued_at),
  CONSTRAINT chk_auth_key_revoked CHECK ((status = 'ACTIVE' AND revoked_at IS NULL) OR (status IN ('EXPIRED','REVOKED') AND revoked_at IS NOT NULL)),
  CONSTRAINT fk_auth_key_event FOREIGN KEY (event_id) REFERENCES event (event_id) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB;

CREATE TABLE coupon (
  coupon_id   INT UNSIGNED NOT NULL AUTO_INCREMENT,              -- 노출 순서
  event_id    INT UNSIGNED NOT NULL,
  tenant_id   CHAR(12) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  name        VARCHAR(100) NOT NULL,
  description VARCHAR(500) NOT NULL,
  PRIMARY KEY (coupon_id),
  KEY idx_coupon_event (event_id),
  CONSTRAINT chk_coupon_tenant_id_format CHECK (tenant_id REGEXP '^[a-z0-9]{12}$'),
  CONSTRAINT fk_coupon_event FOREIGN KEY (event_id) REFERENCES event (event_id) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB;

CREATE TABLE issuance_history (
  issuance_id       BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  event_id          INT UNSIGNED    NOT NULL,
  tenant_id         CHAR(12) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,   -- 핫패스라 CHECK 없음
  user_id           VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  ticket_number     INT UNSIGNED    NOT NULL,
  session_issued_at DATETIME(3)     NOT NULL,
  queue_entered_at  DATETIME(3)     NOT NULL,
  issued_at         DATETIME(3)     NOT NULL,
  result            VARCHAR(20)     NOT NULL,
  PRIMARY KEY (issuance_id),
  UNIQUE KEY uk_issuance_event_user (event_id, user_id),
  CONSTRAINT chk_issuance_result CHECK (result IN ('SUCCESS','FAILED_SOLDOUT')),
  CONSTRAINT fk_issuance_event FOREIGN KEY (event_id) REFERENCES event (event_id) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB;

CREATE TABLE event_result_summary (
  event_id             INT UNSIGNED  NOT NULL,
  tenant_id            CHAR(12) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  session_count        INT UNSIGNED  NOT NULL,
  ticket_count         INT UNSIGNED  NOT NULL,
  peak_concurrent      INT UNSIGNED  NOT NULL,
  peak_at              DATETIME      NULL DEFAULT NULL,
  metrics_finalized_at DATETIME      NOT NULL,
  total_stock          INT UNSIGNED  NULL DEFAULT NULL,
  success_count        INT UNSIGNED  NULL DEFAULT NULL,
  soldout_count        INT UNSIGNED  NULL DEFAULT NULL,
  sellout_rate         DECIMAL(5,4)  NULL DEFAULT NULL,
  history_file_path    VARCHAR(1024) NULL DEFAULT NULL,
  history_generated_at DATETIME      NULL DEFAULT NULL,
  PRIMARY KEY (event_id),
  CONSTRAINT chk_summary_tenant_id_format CHECK (tenant_id REGEXP '^[a-z0-9]{12}$'),
  CONSTRAINT chk_summary_sellout_rate CHECK (sellout_rate BETWEEN 0 AND 1),
  CONSTRAINT fk_summary_event FOREIGN KEY (event_id) REFERENCES event (event_id) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB;

-- 로컬 앱 계정 tetra(docker-compose MYSQL_USER)는 MYSQL_DATABASE(이 스키마)에 대한 권한만 자동으로 받는다.
-- 운영 앱 계정 권한(제안): t_{tenant}.event·event_auth_key·coupon SELECT, issuance_history INSERT, event_result_summary INSERT·UPDATE
