package net.slimelabs.slslite.command.handler;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

final class SequentialCommandBatch {

  private SequentialCommandBatch() {}

  static CompletableFuture<Result> run(
      List<String> targets, Function<String, CompletableFuture<?>> operation) {
    int[] completed = {0};
    int[] failures = {0};
    CompletableFuture<Void> sequence = CompletableFuture.completedFuture(null);
    for (String target : targets) {
      sequence =
          sequence.thenCompose(
              ignored -> {
                CompletableFuture<?> current;
                try {
                  current = operation.apply(target);
                } catch (RuntimeException exception) {
                  current = CompletableFuture.failedFuture(exception);
                }
                return current.handle(
                    (result, failure) -> {
                      if (failure == null) {
                        completed[0]++;
                      } else {
                        failures[0]++;
                      }
                      return null;
                    });
              });
    }
    return sequence.thenApply(ignored -> new Result(completed[0], failures[0]));
  }

  record Result(int completed, int failures) {}
}
