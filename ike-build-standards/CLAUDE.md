# IKE Build Standards — Claude Standards

## Initial Setup — ALWAYS DO THIS FIRST

This module IS the standards source. The standards files live in
`src/main/standards/`. Read them directly — no unpacking needed.

Read these files in `src/main/standards/`:

- MAVEN.md — Maven 4 build standards (always read)
- IKE-MAVEN.md — IKE-specific Maven conventions (always read)

## Module Overview

This module produces two classified ZIP artifacts:
- `classifier=claude` — Claude instruction files (Markdown)
- `classifier=docs` — human-readable convention documents (AsciiDoc)

Consumer modules unpack the `claude` artifact at `validate` phase into
`.claude/standards/` via `maven-dependency-plugin`.

- **Artifact**: `network.ike:ike-build-standards:1.1.0-SNAPSHOT:zip:claude`
- **Packaging**: POM (no compiled code)
- **Versioning**: Unified pipeline version (matches all reactor modules)

## Key Conventions

- Uses the unified pipeline version (e.g., `1.1.0-SNAPSHOT`)
- Assembly descriptors: `src/assembly/claude-standards.xml`, `src/assembly/docs.xml`
- Version is managed in `ike-parent`, which provides inline dependency management

## Build

```bash
mvn install
```
