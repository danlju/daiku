package dev.daiku.domain.ticket;

public enum TicketStatus {
  SUGGESTED("Suggested"),
  READY("Ready"),
  IN_PROGRESS("In progress"),
  IN_REVIEW("In review"),
  DONE("Done");
  private final String label;

  TicketStatus(String label) {
    this.label = label;
  }

  public String getLabel() {
    return label;
  }
}
