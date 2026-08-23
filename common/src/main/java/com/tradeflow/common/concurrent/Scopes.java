package com.tradeflow.common.concurrent;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.StructuredTaskScope.Subtask;

/**
 * The single home for {@link StructuredTaskScope} usage (DD-10, OQ-5), so the preview API is
 * confined to one class until it finalizes (~Java 27). Runs a homogeneous set of tasks concurrently
 * on virtual threads, fail-fast: if any task throws, the rest are cancelled and the failure
 * propagates as {@link StructuredTaskScope.FailedException}. Scoped values are inherited by each fork.
 */
public final class Scopes {

    private Scopes() {
    }

    public static <T> List<T> fanOut(List<? extends Callable<T>> tasks) throws InterruptedException {
        try (var scope = StructuredTaskScope.<T>open()) {
            List<Subtask<T>> subtasks = tasks.stream()
                    .<Subtask<T>>map(scope::fork)
                    .toList();
            scope.join();
            return subtasks.stream().map(Subtask::get).toList();
        }
    }
}
