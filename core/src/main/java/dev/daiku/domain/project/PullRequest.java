package dev.daiku.domain.project;

import java.time.OffsetDateTime;

public record PullRequest(
    long number,
    String title,
    String state,
    boolean draft,
    boolean merged,
    String headBranch,
    String headSha,
    String mergeSha,
    OffsetDateTime updatedAt,
    OffsetDateTime syncedAt,
    String syncError) {
  public String getLabel() {
    if (merged) return "Merged";
    if ("closed".equals(state)) return "Closed";
    if ("open".equals(state)) return draft ? "Draft" : "Open";
    return "Not refreshed";
  }
}
