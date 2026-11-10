package dev.daiku.core.port;

import java.util.List;

/** Read access to the pull requests linked by tickets, without a CI/CD dependency on the board. */
public interface TicketLinks {
  List<Long> pullRequestsForProject(long projectId);
}
