CREATE TABLE IF NOT EXISTS knowledge_document (
    id BIGINT PRIMARY KEY,
    file_name VARCHAR(255) NOT NULL,
    file_url VARCHAR(1024) NOT NULL,
    status VARCHAR(32) NOT NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS knowledge_segment (
    id BIGINT PRIMARY KEY,
    document_id BIGINT NOT NULL,
    content LONGTEXT NOT NULL,
    vector_id VARCHAR(128) NOT NULL,
    CONSTRAINT uk_knowledge_segment_vector UNIQUE (vector_id),
    INDEX idx_knowledge_segment_document_id (document_id)
);

CREATE TABLE IF NOT EXISTS slow_query_log (
    id BIGINT PRIMARY KEY,
    sql_text LONGTEXT NOT NULL,
    execution_time_ms BIGINT NOT NULL,
    user VARCHAR(128) NOT NULL,
    happen_time DATETIME NOT NULL,
    INDEX idx_slow_query_log_happen_time (happen_time),
    INDEX idx_slow_query_log_user (user)
);
