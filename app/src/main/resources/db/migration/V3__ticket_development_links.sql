ALTER TABLE tickets ADD COLUMN branch VARCHAR(255) NOT NULL DEFAULT '';
ALTER TABLE tickets ADD COLUMN pull_request_number BIGINT CHECK (pull_request_number > 0);
CREATE INDEX tickets_pull_requests ON tickets(project_id, pull_request_number);
ALTER TABLE build_runs ADD COLUMN head_repository_id BIGINT;
CREATE INDEX builds_branch ON build_runs(project_id, branch, head_repository_id);
CREATE INDEX builds_commit ON build_runs(project_id, commit_sha);
CREATE TABLE pull_requests (
    project_id BIGINT NOT NULL REFERENCES projects(id),
    number BIGINT NOT NULL,
    title VARCHAR(1000) NOT NULL DEFAULT '',
    state VARCHAR(20) NOT NULL DEFAULT 'unknown',
    draft BOOLEAN NOT NULL DEFAULT FALSE,
    merged BOOLEAN NOT NULL DEFAULT FALSE,
    head_branch VARCHAR(255) NOT NULL DEFAULT '',
    head_sha VARCHAR(64) NOT NULL DEFAULT '',
    merge_sha VARCHAR(64) NOT NULL DEFAULT '',
    updated_at TIMESTAMP WITH TIME ZONE,
    synced_at TIMESTAMP WITH TIME ZONE,
    sync_error VARCHAR(1000),
    PRIMARY KEY (project_id, number)
);
