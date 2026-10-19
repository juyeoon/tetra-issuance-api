-- 공용 스키마 tetra_platform (Confluence "MVP 물리 ERD", 2026-10-15 사본)
-- 로컬 전용: 발급 API가 테넌트 스키마에서 FK로 참조하는 테이블만 만든다
--   (tenant · operator · traffic_plan · template · event_registry).
--   알림·리포트·테넌트 상태 로그는 Portal·Console 소관이라 넣지 않는다.
CREATE DATABASE IF NOT EXISTS tetra_platform
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_0900_ai_ci;
USE tetra_platform;

CREATE TABLE tenant (
  tenant_id                  CHAR(12) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  name                       VARCHAR(100) NOT NULL,
  root_domain                VARCHAR(253) NOT NULL,
  created_at                 DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  email                      VARCHAR(255) NOT NULL,
  password                   VARCHAR(255) NULL DEFAULT NULL,
  contact_name               VARCHAR(100) NOT NULL,
  slack_team_id              VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL DEFAULT NULL,
  account_status             VARCHAR(20)  NOT NULL DEFAULT 'INACTIVE',
  provisioning_status        VARCHAR(20)  NOT NULL DEFAULT 'PROVISIONING',
  email_notification_enabled TINYINT(1)   NOT NULL DEFAULT 0,
  PRIMARY KEY (tenant_id),
  UNIQUE KEY uk_tenant_email (email),
  UNIQUE KEY uk_tenant_slack_team (slack_team_id),
  KEY idx_tenant_created (created_at),
  CONSTRAINT chk_tenant_id_format CHECK (tenant_id REGEXP '^[a-z0-9]{12}$'),
  CONSTRAINT chk_tenant_account_status CHECK (account_status IN ('ACTIVE','INACTIVE')),
  CONSTRAINT chk_tenant_provisioning_status CHECK (provisioning_status IN ('PROVISIONING','READY','FAILED'))
) ENGINE=InnoDB;

CREATE TABLE operator (
  operator_id  INT UNSIGNED NOT NULL AUTO_INCREMENT,
  email        VARCHAR(255) NOT NULL,
  contact_name VARCHAR(100) NOT NULL,
  password     VARCHAR(255) NOT NULL,
  PRIMARY KEY (operator_id),
  UNIQUE KEY uk_operator_email (email)
) ENGINE=InnoDB;

CREATE TABLE traffic_plan (
  plan_code            VARCHAR(10) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  max_capacity         INT UNSIGNED  NOT NULL,
  hourly_price         DECIMAL(15,2) NOT NULL,
  hosting_hourly_price DECIMAL(15,2) NOT NULL,
  is_visible           TINYINT(1)    NOT NULL DEFAULT 1,
  PRIMARY KEY (plan_code)
) ENGINE=InnoDB;

CREATE TABLE template (
  template_id INT UNSIGNED  NOT NULL AUTO_INCREMENT,
  event_type  VARCHAR(20)   NOT NULL,
  description VARCHAR(500)  NULL DEFAULT NULL,
  file_path   VARCHAR(1024) NOT NULL,
  PRIMARY KEY (template_id),
  UNIQUE KEY uk_template_event_type (event_type),
  CONSTRAINT chk_template_event_type CHECK (event_type IN ('COUPON','TIMESALE','LIVE_COMMERCE'))
) ENGINE=InnoDB;

CREATE TABLE event_registry (
  event_id           INT UNSIGNED NOT NULL AUTO_INCREMENT,
  tenant_id          CHAR(12) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  name               VARCHAR(100) NOT NULL,
  event_type         VARCHAR(20)  NOT NULL,
  traffic_plan_code  VARCHAR(10) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  hosting_start_at   DATETIME     NOT NULL,
  start_at           DATETIME     NOT NULL,
  applied_at         DATETIME     NOT NULL,
  processed_at       DATETIME     NULL DEFAULT NULL,
  application_status VARCHAR(20)  NOT NULL DEFAULT 'APPLYING',
  event_status       VARCHAR(20)  NULL DEFAULT NULL,
  hosting_status     VARCHAR(20)  NULL DEFAULT NULL,
  PRIMARY KEY (event_id),
  KEY idx_registry_tenant_applied (tenant_id, applied_at),
  KEY idx_registry_app_status (application_status, applied_at),
  KEY idx_registry_app_start (application_status, start_at),
  KEY idx_registry_hosting_status (hosting_status),
  KEY idx_registry_event_status (event_status),
  KEY idx_registry_plan (traffic_plan_code),
  CONSTRAINT chk_registry_event_type CHECK (event_type IN ('COUPON','TIMESALE','LIVE_COMMERCE')),
  CONSTRAINT chk_registry_app_status CHECK (application_status IN ('APPLYING','APPLIED','APPLY_FAILED','APPROVED','REJECTED')),
  CONSTRAINT chk_registry_hosting_status CHECK (hosting_status IN ('PENDING','DEPLOYED','DEPLOY_FAILED','HOSTING','HOSTING_FAILED','ENDED','END_FAILED')),
  CONSTRAINT chk_registry_event_status CHECK (event_status IN ('SCHEDULED','OPEN','OPEN_FAILED','EXPIRED')),
  CONSTRAINT fk_registry_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (tenant_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT fk_registry_plan FOREIGN KEY (traffic_plan_code) REFERENCES traffic_plan (plan_code) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB;
