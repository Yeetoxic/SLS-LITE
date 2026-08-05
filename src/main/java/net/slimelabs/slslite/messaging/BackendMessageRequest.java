package net.slimelabs.slslite.messaging;

import java.util.UUID;

sealed interface BackendMessageRequest
    permits BackendMessageRequest.Matchmake, BackendMessageRequest.Command {

  UUID requestId();

  record Matchmake(UUID requestId, String registry, String target)
      implements BackendMessageRequest {}

  record Command(UUID requestId, String command) implements BackendMessageRequest {}
}
