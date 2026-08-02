package net.slimelabs.slslite.log;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DiagnosticMessagesTest {

  @Test
  void rootCauseIsBoundedSingleLineAndMandatoryRedacted() {
    String secret = "sls_live_do_not_print";
    Throwable failure =
        new IllegalStateException(
            "wrapper", new java.io.IOException("token=" + secret + " C:\\private\\file\nnext"));

    String message = DiagnosticMessages.rootCause(failure);

    assertFalse(message.contains(secret));
    assertFalse(message.contains("C:\\private"));
    assertFalse(message.contains("\n"));
    assertTrue(message.length() <= 527);
  }
}
