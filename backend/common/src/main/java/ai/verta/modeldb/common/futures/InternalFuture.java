package ai.verta.modeldb.common.futures;

import ai.verta.modeldb.common.exceptions.ModelDBException;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.function.*;
import java.util.stream.Collectors;
import lombok.extern.log4j.Log4j2;

@Log4j2
public class InternalFuture<T> {
  private static final boolean DEEP_TRACING_ENABLED;
  public static final AttributeKey<String> STACK_ATTRIBUTE_KEY = AttributeKey.stringKey("stack");

  static {
    String internalFutureTracingEnabled = System.getenv("IFUTURE_TRACING_ENABLED");
    DEEP_TRACING_ENABLED = Boolean.parseBoolean(internalFutureTracingEnabled);
  }

  private static Tracer futureTracer;
  private final String formattedStack;
  private CompletionStage<T> stage;

  private InternalFuture() {
    if (!DEEP_TRACING_ENABLED) {
      formattedStack = null;
    } else {
      StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
      if (stackTrace.length > 10) {
        stackTrace = Arrays.copyOf(stackTrace, 10);
      }
      this.formattedStack =
          Arrays.stream(stackTrace)
              .map(StackTraceElement::toString)
              .collect(Collectors.joining("\nat "));
    }
  }

  // Convert a list of futures to a future of a list
  @SuppressWarnings("unchecked")
  public static <T> InternalFuture<List<T>> sequence(
      final List<InternalFuture<T>> futures, FutureExecutor ex) {
    final var executor = ex.captureContext();
    if (futures.isEmpty()) {
      return InternalFuture.completedInternalFuture(new LinkedList<>());
    }

    final var promise = new CompletableFuture<List<T>>();
    final var values = new ArrayList<T>(futures.size());
    final CompletableFuture<T>[] futuresArray =
        futures.stream()
            .map(x -> x.toCompletionStage().toCompletableFuture())
            .collect(Collectors.toList())
            .toArray(new CompletableFuture[futures.size()]);

    CompletableFuture.allOf(futuresArray)
        .whenCompleteAsync(
            (ignored, throwable) -> {
              if (throwable != null) {
                promise.completeExceptionally(throwable);
                return;
              }

              try {
                for (final var future : futuresArray) {
                  values.add(future.get());
                }
                promise.complete(values);
              } catch (Throwable t) {
                if (t instanceof InterruptedException) {
                  // Restore interrupted state...
                  Thread.currentThread().interrupt();
                }
                promise.completeExceptionally(t);
              }
            },
            executor);

    return InternalFuture.from(promise);
  }

  public static <R> InternalFuture<R> from(CompletionStage<R> other) {
    var ret = new InternalFuture<R>();
    ret.stage = other;
    return ret;
  }

  public static <R> InternalFuture<R> completedInternalFuture(R value) {
    var ret = new InternalFuture<R>();
    ret.stage = CompletableFuture.completedFuture(value);
    return ret;
  }

  public InternalFuture<T> onSuccess(Consumer<T> fn, FutureExecutor ex) {
    final var executor = ex.captureContext();
    return from(
        stage.whenCompleteAsync(
            (t, throwable) -> {
              if (throwable == null) {
                fn.accept(t);
              }
            },
            executor));
  }

  public InternalFuture<T> onFailure(Consumer<Throwable> fn, FutureExecutor ex) {
    final var executor = ex.captureContext();
    return from(
        stage.whenCompleteAsync(
            (t, throwable) -> {
              if (throwable != null) {
                fn.accept(throwable);
              }
            },
            executor));
  }

  public <U> InternalFuture<U> thenCompose(
      Function<? super T, InternalFuture<U>> fn, FutureExecutor ex) {
    final var executor = ex.captureContext();
    return from(
        stage.thenComposeAsync(
            traceFunction(
                traceFunction(
                    fn.andThen(internalFuture -> internalFuture.stage), "futureThenCompose"),
                "futureThenApply"),
            executor));
  }

  public <U> InternalFuture<U> thenApply(Function<? super T, ? extends U> fn, FutureExecutor ex) {
    final var executor = ex.captureContext();
    return from(stage.thenApplyAsync(traceFunction(fn, "futureThenApply"), executor));
  }

  private <U> Function<? super T, ? extends U> traceFunction(
      Function<? super T, ? extends U> fn, String spanName) {
    if (!DEEP_TRACING_ENABLED) {
      return fn;
    }
    return t -> {
      Span span = startSpan(spanName);
      try (Scope ignored = span.makeCurrent()) {
        return fn.apply(t);
      } finally {
        span.end();
      }
    };
  }

  private Span startSpan(String futureThenApply) {
    if (futureTracer == null) {
      return Span.getInvalid();
    }
    return futureTracer
        .spanBuilder(futureThenApply)
        .setAttribute(STACK_ATTRIBUTE_KEY, formattedStack)
        .startSpan();
  }

  public <U> InternalFuture<U> handle(
      BiFunction<? super T, Throwable, ? extends U> fn, FutureExecutor ex) {
    final var executor = ex.captureContext();
    return from(stage.handleAsync(traceBiFunctionThrowable(fn), executor));
  }

  private <U> BiFunction<? super T, Throwable, ? extends U> traceBiFunctionThrowable(
      BiFunction<? super T, Throwable, ? extends U> fn) {
    if (!DEEP_TRACING_ENABLED) {
      return fn;
    }
    return (t, throwable) -> {
      Span span = startSpan("futureHandle");
      try (Scope ignored = span.makeCurrent()) {
        return fn.apply(t, throwable);
      } finally {
        span.end();
      }
    };
  }

  public <U, V> InternalFuture<V> thenCombine(
      InternalFuture<? extends U> other,
      BiFunction<? super T, ? super U, ? extends V> fn,
      FutureExecutor ex) {
    final var executor = ex.captureContext();
    BiFunction<? super T, ? super U, ? extends V> tracedBiFunction = traceBiFunction(fn);
    return from(stage.thenCombineAsync(other.stage, tracedBiFunction, executor));
  }

  private <U, V> BiFunction<? super T, ? super U, ? extends V> traceBiFunction(
      BiFunction<? super T, ? super U, ? extends V> fn) {
    if (!DEEP_TRACING_ENABLED) {
      return fn;
    }
    return (t, u) -> {
      Span span = startSpan("futureThenCombine");
      try (Scope ignored = span.makeCurrent()) {
        return fn.apply(t, u);
      } finally {
        span.end();
      }
    };
  }

  public InternalFuture<Void> thenAccept(Consumer<? super T> action, FutureExecutor ex) {
    final var executor = ex.captureContext();
    return from(stage.thenAcceptAsync(traceConsumer(action), executor));
  }

  private Consumer<? super T> traceConsumer(Consumer<? super T> action) {
    if (!DEEP_TRACING_ENABLED) {
      return action;
    }
    return t -> {
      Span span = startSpan("futureThenAccept");
      try (Scope ignored = span.makeCurrent()) {
        action.accept(t);
      } finally {
        span.end();
      }
    };
  }

  public <U> InternalFuture<Void> thenRun(Runnable runnable, FutureExecutor ex) {
    final var executor = ex.captureContext();
    return from(stage.thenRunAsync(traceRunnable(runnable), executor));
  }

  private Runnable traceRunnable(Runnable r) {
    if (!DEEP_TRACING_ENABLED) {
      return r;
    }
    return () -> {
      Span span = startSpan("futureThenRun");
      try (Scope ignored = span.makeCurrent()) {
        r.run();
      } finally {
        span.end();
      }
    };
  }

  public InternalFuture<T> whenComplete(
      BiConsumer<? super T, ? super Throwable> action, FutureExecutor ex) {
    final var executor = ex.captureContext();
    return from(stage.whenCompleteAsync(traceBiConsumer(action), executor));
  }

  public InternalFuture<T> recover(Function<Throwable, ? extends T> fn, FutureExecutor ex) {
    final var executor = ex.captureContext();
    return handle(
        (v, t) -> {
          if (t != null) {
            return fn.apply(t);
          }
          return v;
        },
        executor);
  }

  private BiConsumer<? super T, ? super Throwable> traceBiConsumer(
      BiConsumer<? super T, ? super Throwable> action) {
    if (!DEEP_TRACING_ENABLED) {
      return action;
    }

    return (t, throwable) -> {
      Span span = startSpan("futureWhenComplete");
      try (Scope ignored = span.makeCurrent()) {
        action.accept(t, throwable);
      } finally {
        span.end();
      }
    };
  }

  /**
   * Syntactic sugar for {@link #thenCompose(Function, FutureExecutor)} with the function ignoring
   * the input.
   */
  public <U> InternalFuture<U> thenSupply(Supplier<InternalFuture<U>> supplier, FutureExecutor ex) {
    final var executor = ex.captureContext();
    return thenCompose(ignored -> supplier.get(), executor);
  }

  public static InternalFuture<Void> runAsync(Runnable runnable, FutureExecutor ex) {
    final var executor = ex.captureContext();
    return from(CompletableFuture.runAsync(runnable, executor));
  }

  public static <U> InternalFuture<U> supplyAsync(Supplier<U> supplier, FutureExecutor ex) {
    final var executor = ex.captureContext();
    return from(CompletableFuture.supplyAsync(supplier, executor));
  }

  public static <U> InternalFuture<U> failedStage(Throwable ex) {
    return from(CompletableFuture.failedFuture(ex));
  }

  public static <U> InternalFuture<U> retriableStage(
      Supplier<InternalFuture<U>> supplier,
      Function<Throwable, Boolean> retryChecker,
      FutureExecutor ex) {
    final var executor = ex.captureContext();
    final var promise = new CompletableFuture<U>();

    supplier
        .get()
        .whenComplete(
            (value, throwable) -> {
              boolean retryCheckerFlag;
              try {
                retryCheckerFlag = retryChecker.apply(throwable);
              } catch (Throwable e) {
                promise.completeExceptionally(
                    new ExecutionException("retryChecker threw an exception. Not retrying.", e));
                return;
              }
              if (throwable == null) {
                promise.complete(value);
              } else if (retryCheckerFlag) {
                // If we should retry, then perform the retry and map the result of the future to
                // the current promise
                // This build up a chain of promises, which can consume memory. I couldn't figure
                // out how to do better
                // with Java constraints of final vars in lambdas and not using uninitialized
                // variables.
                retriableStage(supplier, retryChecker, executor)
                    .whenComplete(
                        (v, t) -> {
                          if (t == null) {
                            promise.complete(v);
                          } else {
                            promise.completeExceptionally(t);
                          }
                        },
                        executor);
              } else {
                promise.completeExceptionally(throwable);
              }
            },
            executor);

    return InternalFuture.from(promise);
  }

  public static <U> InternalFuture<Optional<U>> flipOptional(
      Optional<InternalFuture<U>> val, FutureExecutor ex) {
    final var executor = ex.captureContext();
    return val.map(future -> future.thenApply(Optional::of, executor))
        .orElse(InternalFuture.completedInternalFuture(Optional.empty()));
  }

  @Deprecated(forRemoval = true)
  public T get() throws Exception {
    return blockAndGet();
  }

  public T blockAndGet() throws Exception {
    try {
      return stage.toCompletableFuture().get();
    } catch (ExecutionException ex) {
      Throwable cause = ex.getCause();
      if (cause instanceof Exception) {
        throw (Exception) cause;
      }
      throw new ModelDBException(ex);
    } catch (InterruptedException ex) {
      // Restore interrupted state...
      Thread.currentThread().interrupt();
      throw new ModelDBException(ex);
    }
  }

  public CompletionStage<T> toCompletionStage() {
    return stage;
  }

  public Future<T> toFuture() {
    return Future.from(stage);
  }

  public static void setOpenTelemetry(OpenTelemetry openTelemetry) {
    if (futureTracer != null) {
      log.warn(
          "OpenTelemetry has already been set. Ignoring this call.",
          new RuntimeException("call-site capturer"));
    }
    futureTracer = openTelemetry.getTracer("futureTracer");
  }
}
