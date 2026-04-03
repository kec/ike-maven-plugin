# Java 25 Standards

## Language Level

- Target Java 25. Use `<maven.compiler.release>25</maven.compiler.release>`.
- Enable preview features only when they provide clear value. Gate preview usage behind a property (`maven.compiler.enablePreview`) so it can be toggled.
- Surefire and Failsafe need `--enable-preview` in `<argLine>` when preview features are active.

## Modern Java Idioms

### Records
- Use records for immutable data carriers (DTOs, value objects, configuration snapshots).
- Records are final, have canonical constructors, and auto-generate `equals()`, `hashCode()`, `toString()`.
- Add compact constructors for validation:
  ```java
  public record Coordinate(double lat, double lon) {
      public Coordinate {
          if (lat < -90 || lat > 90) throw new IllegalArgumentException("Invalid latitude");
      }
  }
  ```

### Sealed Interfaces and Classes
- Use sealed hierarchies to model closed type sets (AST nodes, command types, protocol messages).
- Exhaustive pattern matching on sealed types — the compiler enforces completeness.
- Prefer `sealed interface` over `sealed class` unless shared state is needed.

### Pattern Matching
- Use pattern matching for `instanceof` — never cast after an `instanceof` check.
- Use switch expressions with pattern matching for sealed type dispatch.
- Prefer `case Type t when condition` over nested if-else chains.
  ```java
  return switch (node) {
      case Leaf l -> l.value();
      case Branch b when b.left() == null -> b.right().value();
      case Branch b -> b.left().value() + b.right().value();
  };
  ```

### Virtual Threads
- Use virtual threads for I/O-bound work (network calls, file I/O, database queries).
- Do not use virtual threads for CPU-bound computation.
- Avoid `synchronized` blocks in virtual thread contexts — use `ReentrantLock` instead (synchronized pins the carrier thread).
- Create via `Thread.ofVirtual().start()` or `Executors.newVirtualThreadPerTaskExecutor()`.

### Text Blocks
- Use text blocks for multi-line strings (SQL, JSON, XML templates, log messages).
- Align the closing `"""` to control indentation stripping.

### Structured Concurrency (Preview)
- Use `StructuredTaskScope` for concurrent subtask management when preview is enabled.
- Scoped values (`ScopedValue`) replace thread-locals in virtual thread contexts.

## Module System (JPMS)

- Provide `module-info.java` for library modules that will be consumed as dependencies.
- Application modules may use the classpath (automatic modules) when JPMS adoption is partial.
- Use `<maven.compiler.release>` (not `<source>`/`<target>`) to ensure cross-compilation correctness.

## Error Handling

- Throw specific exceptions, not generic `RuntimeException` or `Exception`.
- Never swallow exceptions silently. Log at appropriate level or rethrow.
- Use try-with-resources for all `AutoCloseable` resources — no manual `finally` blocks.
- Prefer returning `Optional` over returning `null` for methods that may have no result.

## Testing

- JUnit 5 (Jupiter) for all tests.
- AssertJ for fluent assertions (`assertThat(...).isEqualTo(...)`).
- Test method names describe behavior: `shouldRejectNegativeLatitude()`, not `test1()`.
- Use `@Nested` classes to group related test cases.
- Integration tests use Maven Failsafe (`*IT.java` naming convention).

## Code Organization

- One public class per file. Package-private helpers in the same package are fine.
- Favor composition over inheritance.
- Keep methods short — if a method needs a comment explaining what a block does, extract it.
- No wildcard imports. IDE should manage imports automatically.
