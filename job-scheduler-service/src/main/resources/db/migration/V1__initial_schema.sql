-- Jobs table
CREATE TABLE jobs (
    id VARCHAR(255) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    type VARCHAR(50) NOT NULL,
    priority VARCHAR(20) NOT NULL,
    cron_expression VARCHAR(100),
    scheduled_at TIMESTAMP,
    parameters TEXT,
    max_retries INTEGER DEFAULT 3,
    retry_delay_seconds INTEGER DEFAULT 60,
    timeout_seconds INTEGER DEFAULT 300,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_by VARCHAR(255),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP
);

CREATE INDEX idx_job_name ON jobs(name);
CREATE INDEX idx_job_type ON jobs(type);
CREATE INDEX idx_created_at ON jobs(created_at);

-- Job dependencies table
CREATE TABLE job_dependencies (
    job_id VARCHAR(255) NOT NULL,
    dependency_id VARCHAR(255) NOT NULL,
    PRIMARY KEY (job_id, dependency_id),
    FOREIGN KEY (job_id) REFERENCES jobs(id) ON DELETE CASCADE
);

-- Job tags table
CREATE TABLE job_tags (
    job_id VARCHAR(255) NOT NULL,
    tag VARCHAR(100) NOT NULL,
    PRIMARY KEY (job_id, tag),
    FOREIGN KEY (job_id) REFERENCES jobs(id) ON DELETE CASCADE
);

-- Job executions table
CREATE TABLE job_executions (
    id VARCHAR(255) PRIMARY KEY,
    job_id VARCHAR(255) NOT NULL,
    status VARCHAR(50) NOT NULL,
    started_at TIMESTAMP,
    completed_at TIMESTAMP,
    parameters TEXT,
    result TEXT,
    error_message TEXT,
    stack_trace TEXT,
    current_retry INTEGER DEFAULT 0,
    worker_id VARCHAR(255),
    execution_time_ms BIGINT,
    created_at TIMESTAMP NOT NULL,
    FOREIGN KEY (job_id) REFERENCES jobs(id) ON DELETE CASCADE
);

CREATE INDEX idx_execution_job_id ON job_executions(job_id);
CREATE INDEX idx_execution_status ON job_executions(status);
CREATE INDEX idx_execution_created_at ON job_executions(created_at);
