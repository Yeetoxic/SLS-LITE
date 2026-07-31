package net.slimelabs.slslite.process;

public final class ProcessStartException extends Exception {

  public ProcessStartException(String message) {
    super(message);
  }

  public ProcessStartException(String message, Throwable cause) {
    super(message, cause);
  }
}
