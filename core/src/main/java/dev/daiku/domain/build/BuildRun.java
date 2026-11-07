package dev.daiku.domain.build;

import java.time.OffsetDateTime;

public record BuildRun(
    long id,
    String name,
    String branch,
    String commitSha,
    String status,
    String conclusion,
    String htmlUrl,
    long runNumber,
    OffsetDateTime updatedAt) {
  public String getLabel() {
    return "completed".equals(status) && conclusion != null ? conclusion : status;
  }

  public String getTone() {
    return switch (getLabel()) {
      case "success" -> "success";
      case "failure", "timed_out", "action_required", "startup_failure" -> "failure";
      case "queued", "in_progress", "waiting", "pending", "requested" -> "pending";
      default -> "neutral";
    };
  }

  public String getShortSha() {
    return commitSha.substring(0, Math.min(7, commitSha.length()));
  }
}
