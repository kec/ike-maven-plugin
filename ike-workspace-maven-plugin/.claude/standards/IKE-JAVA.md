# IKE Java Patterns

## Domain Context

IKE (Integrated Knowledge Environment) projects work with knowledge representation, terminology systems, and description logic. Core concepts include:

- **Concepts** — named entities in a knowledge graph (e.g., clinical findings, anatomical structures)
- **Axioms** — description logic statements relating concepts (e.g., `Pneumonia ⊑ DiseaseOfLung`)
- **Koncepts** — IKE's notation for referencing concepts in documentation via `k:ConceptName[]` inline macros

## RocksDB Lifecycle Management

RocksDB is the default embedded storage for IKE knowledge bases.

- **Always close RocksDB instances** in a try-with-resources or explicit shutdown hook. Unclosed instances corrupt WAL files.
- **Column families** are the unit of isolation. Create separate column families for different data types (concepts, axioms, descriptions).
- **Use `WriteBatch`** for multi-key atomic writes. Never write related keys individually — partial writes corrupt the knowledge graph.
- **Iterator cleanup** — always close `RocksIterator` instances. They hold native memory that the GC cannot reclaim.

```java
try (var db = RocksDB.open(options, path);
     var batch = new WriteBatch()) {
    batch.put(cfHandle, key, value);
    db.write(writeOptions, batch);
}
```

## Knowledge Graph Module Conventions

- One Maven module per bounded context (e.g., `ike-terminology`, `ike-reasoner`, `ike-coordinate`).
- JPMS module names mirror the Maven artifactId: `network.ike.terminology`.
- Public API is in the root package. Implementation classes are in `.internal` subpackages.
- Service interfaces use `ServiceLoader` (JPMS `provides`/`uses` in `module-info.java`).

## gRPC Service Patterns

- Proto files in `src/main/proto/` with package `network.ike.<module>`.
- Use `protobuf-maven-plugin` for code generation (bound to `generate-sources`).
- Service implementations extend the generated `*ImplBase` class.
- Use virtual threads for gRPC server handlers (I/O-bound by nature).
- Deadline propagation: always set deadlines on client stubs, always check `Context.current().isCancelled()` in long-running handlers.

## Koncept Extension Conventions

The `koncept-asciidoc-extension` provides:
- **InlineMacro** (`k:ConceptName[]`) — registered via SPI, works with all backends.
- **Postprocessor** (glossary generation) — registered per-execution in asciidoctor-maven-plugin config. Cannot be registered via SPI because it crashes the asciidoctorj-pdf (Prawn) backend.

When adding new AsciiDoc extensions:
- InlineMacros and BlockMacros → SPI registration (`META-INF/services/`)
- Postprocessors and TreeProcessors → per-execution registration in POM
- Test with both HTML and PDF backends — Prawn's JRuby bridge has quirks

## Logging

- SLF4J API for all production code (`org.slf4j:slf4j-api`, scope `provided`).
- `slf4j-simple` for test scope only.
- Log at appropriate levels: ERROR for unrecoverable failures, WARN for degraded behavior, INFO for lifecycle events, DEBUG for diagnostic detail.
- Use parameterized messages: `log.info("Loaded {} concepts from {}", count, path)` — never string concatenation.
