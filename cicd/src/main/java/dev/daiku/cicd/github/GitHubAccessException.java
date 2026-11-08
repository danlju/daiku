package dev.daiku.cicd.github;

/** A GitHub failure with a locally generated message safe to display to the user. */
public class GitHubAccessException extends IllegalStateException {
  GitHubAccessException(String message) {
    super(message);
  }
}
