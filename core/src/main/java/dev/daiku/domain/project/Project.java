package dev.daiku.domain.project;

import java.time.OffsetDateTime;

public record Project(
    long id,
    Long repositoryId,
    Long installationId,
    String name,
    String repository,
    String defaultBranch,
    OffsetDateTime syncedAt,
    String syncError,
    boolean archived) {
  public boolean connected() {
    return repositoryId != null;
  }
}
