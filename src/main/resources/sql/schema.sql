CREATE TABLE IF NOT EXISTS knowledge_document (
    id BIGINT PRIMARY KEY,
    file_name VARCHAR(255) NOT NULL,
    file_url VARCHAR(1024) NOT NULL,
    storage_provider VARCHAR(32) NOT NULL DEFAULT 'aliyun',
    object_key VARCHAR(512) NOT NULL,
    content_type VARCHAR(128) NULL,
    status VARCHAR(32) NOT NULL,
    failure_reason VARCHAR(512) NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_knowledge_document_object_key (object_key)
);

ALTER TABLE knowledge_document ADD COLUMN IF NOT EXISTS storage_provider VARCHAR(32) NOT NULL DEFAULT 'aliyun';
ALTER TABLE knowledge_document ADD COLUMN IF NOT EXISTS object_key VARCHAR(512) NOT NULL DEFAULT '';
ALTER TABLE knowledge_document ADD COLUMN IF NOT EXISTS content_type VARCHAR(128) NULL;
ALTER TABLE knowledge_document ADD COLUMN IF NOT EXISTS failure_reason VARCHAR(512) NULL;

CREATE TABLE IF NOT EXISTS knowledge_segment (
    id BIGINT PRIMARY KEY,
    document_id BIGINT NOT NULL,
    segment_index INT NOT NULL DEFAULT 0,
    content LONGTEXT NOT NULL,
    vector_id VARCHAR(128) NOT NULL,
    CONSTRAINT uk_knowledge_segment_vector UNIQUE (vector_id),
    INDEX idx_knowledge_segment_document_id (document_id)
);

ALTER TABLE knowledge_segment ADD COLUMN IF NOT EXISTS segment_index INT NOT NULL DEFAULT 0;

CREATE TABLE IF NOT EXISTS slow_query_log (
    id BIGINT PRIMARY KEY,
    sql_text LONGTEXT NOT NULL,
    execution_time_ms BIGINT NOT NULL,
    user VARCHAR(128) NOT NULL,
    happen_time DATETIME NOT NULL,
    INDEX idx_slow_query_log_happen_time (happen_time),
    INDEX idx_slow_query_log_user (user)
);

CREATE TABLE IF NOT EXISTS target_datasource (
    id          BIGINT       PRIMARY KEY AUTO_INCREMENT,
    instance_id VARCHAR(64)  NOT NULL UNIQUE COMMENT '实例标识，如 db-test-01',
    name        VARCHAR(128) NOT NULL COMMENT '显示名称',
    url         VARCHAR(512) NOT NULL COMMENT 'JDBC URL',
    username    VARCHAR(128) NOT NULL,
    password    VARCHAR(256) NOT NULL COMMENT '加密存储',
    status      TINYINT      NOT NULL DEFAULT 1 COMMENT '1-启用 0-禁用',
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='目标数据源配置';
