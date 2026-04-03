# IKE Topic Registry Standards

## Purpose

The `topic-registry.yaml` file is the authoritative catalog of all topics in a topic library
module. It serves three functions:

1. **Build validation**: CI checks that every `.adoc` file under `topics/` has a registry
   entry and every registry entry resolves to a file.
2. **Assembly planning**: Authors and tooling use the registry to understand what content
   exists, its status, and its dependencies when constructing assembly documents.
3. **Claude navigation**: The registry provides Claude (chat or Claude Code) with a searchable
   index of the corpus so that content can be located by keyword, topic-id, or domain without
   uploading the full topic library.

## File Location

```
{topic-library-module}/src/docs/asciidoc/topic-registry.yaml
```

The registry travels with the topic content it catalogs. When the topic library is packaged
and unpacked into a dependent module's `target/` directory, the registry is available alongside
the topics.

## Schema

```yaml
# topic-registry.yaml
registry-version: "1.1"            # schema version for forward compatibility
generated: 2025-06-15              # ISO date of last registry update
topic-count: 247                   # total number of topic entries (validation target)

domains:
  - id: arch                       # domain identifier, used as topic-id prefix
    title: "System Architecture"   # human-readable domain name
    description: >                 # optional: domain scope statement
      Topics covering system architecture, design patterns,
      and infrastructure decisions.
    topics:                        # ordered list of topics in this domain
      - id: arch-overview
        file: topics/architecture/overview.adoc
        title: "Architecture Overview"
        type: concept
        keywords: [architecture, overview, IKE, layers]
        status: published
        char-count: 2340
        dependencies: []
        related: []
        summary: >
          High-level overview of the IKE layered architecture including
          knowledge graph, reasoning, and API tiers. Covers the separation
          between storage, inference, and presentation layers.

      - id: arch-dl-classifier
        file: topics/architecture/dl-classifier.adoc
        title: "Classifier Architecture"
        type: concept
        keywords: [classifier, reasoning, EL++, inference]
        status: published
        char-count: 2890
        dependencies: [arch-overview]
        related: [term-dl-axioms]  # covers similar ground from architecture angle
        summary: >
          Describes the classifier subsystem architecture including integration
          points, performance characteristics, and the EL++ profile constraints
          that enable polynomial-time reasoning.

assemblies:                        # catalog of assembly documents
  - id: compendium
    file: compendium.adoc
    title: "IKE Compendium"
    description: "Master assembly containing all topics."
    sections:                      # hierarchical structure mirroring the assembly
      - heading: "Architecture"
        sections:
          - heading: "Core Patterns"
            topic-refs: [arch-overview, arch-coord-versioning, arch-module-coordinates]
          - heading: "Reasoning"
            topic-refs: [arch-dl-classifier, arch-inference-pipeline]
      - heading: "Terminology Management"
        sections:
          - heading: "SNOMED CT"
            topic-refs: [term-snomed-concept-model, term-dl-axioms]
          - heading: "LOINC"
            topic-refs: [term-loinc-part-mapping]

  - id: versioning-guide
    file: guides/versioning-guide.adoc
    title: "Versioning Guide"
    description: "Targeted guide for version management."
    sections:
      - heading: "Core Concepts"
        topic-refs: [arch-coord-versioning, arch-module-coordinates]
      - heading: "Procedures"
        topic-refs: [ops-version-migration, ops-version-conflict-resolution]
      - heading: "Reference"
        topic-refs: [ref-coordinate-fields]
```

## Field Definitions: Topic Entry

### Required Fields

| Field          | Type       | Description                                                  |
|----------------|------------|--------------------------------------------------------------|
| `id`           | string     | Unique topic identifier. Format: `{domain-prefix}-{slug}`, lowercase kebab-case. Immutable once assigned. |
| `file`         | string     | Relative path from the `src/docs/asciidoc/` root to the `.adoc` file. |
| `title`        | string     | Human-readable title. Should match the level-1 heading in the `.adoc` file. |
| `type`         | enum       | One of: `concept`, `task`, `reference`, `dialog`.            |
| `keywords`     | string[]   | 3–8 searchable terms. Include synonyms and abbreviations that a searcher might use. Do not repeat words from the title. |
| `status`       | enum       | One of: `draft`, `review`, `published`, `deprecated`.        |
| `summary`      | string     | 1–2 sentences describing the topic's content. Written in indicative mood ("Describes the..." not "This topic describes..."). Must be useful for search — include key terms not covered by `keywords`. |

### Optional Fields

| Field          | Type       | Description                                                  |
|----------------|------------|--------------------------------------------------------------|
| `char-count`   | integer    | Character count of the `.adoc` content (excluding attribute block). Updated on each decomposition pass. Used for granularity validation. |
| `dependencies` | string[]   | List of `topic-id` values that this topic cross-references via `xref:`. Represents "this topic links to" relationships. |
| `related`      | string[]   | List of `topic-id` values that cover similar subject matter from a different angle. Represents "this topic overlaps with" relationships. Used for redundancy management — when revising one topic, check its `related` topics for consistency. Distinct from `dependencies`, which are structural cross-references. |
| `supersedes`   | string     | `topic-id` of a deprecated topic that this topic replaces.   |
| `notes`        | string     | Free-text notes for authors and Claude. Use for documenting exceptions (e.g., "Exceeds 5000 chars — indivisible reference table"). |

## Field Definitions: Assembly Entry

| Field          | Type       | Description                                                  |
|----------------|------------|--------------------------------------------------------------|
| `id`           | string     | Unique assembly identifier. Lowercase kebab-case.            |
| `file`         | string     | Relative path to the assembly `.adoc` file.                  |
| `title`        | string     | Human-readable title of the assembled document.              |
| `description`  | string     | Brief description of the assembly's purpose and audience.    |
| `sections`     | section[]  | Hierarchical structure of the assembly (see below).          |

### Assembly Section Structure

Assembly entries use nested `sections` to capture the heading hierarchy of the assembled
document. This gives Claude and authors structural context — not just which topics are
included, but where they sit in the document hierarchy.

| Field          | Type       | Description                                                  |
|----------------|------------|--------------------------------------------------------------|
| `heading`      | string     | The section heading text as it appears in the assembly.      |
| `topic-refs`   | string[]   | Ordered list of `topic-id` values included under this heading. |
| `sections`     | section[]  | Optional nested subsections.                                 |

Sections may nest to match the assembly's heading depth. The `topic-refs` at each level
list the topics included directly under that heading, in document order. A section may have
both `topic-refs` and child `sections` if it contains both directly included topics and
subsections.

## Topic ID Construction Rules

1. Format: `{domain-prefix}-{descriptive-slug}`
2. Domain prefix: 2–5 lowercase characters matching a `domains[].id` in the registry.
3. Slug: lowercase kebab-case, 2–5 words, descriptive of content.
4. Total length: aim for under 40 characters.
5. **Immutability**: Once a `topic-id` is assigned and committed, it must not be changed. Other
   topics, assemblies, and external documents may reference it. If a topic's scope changes
   substantially, create a new topic and set `status: deprecated` on the old one with a
   `notes` field pointing to the replacement.

Examples:
- `arch-coord-versioning` — architecture domain, describes coordinate-based versioning
- `term-snomed-concept-model` — terminology domain, SNOMED CT concept model
- `safe-usc-hazard-analysis` — safety domain, unsafe control action hazard analysis
- `ops-maven-release-process` — operations domain, Maven release procedure

## Status Lifecycle

```
draft → review → published
                     ↓
                deprecated
```

- **draft**: Content is being authored or decomposed. May contain TODOs and placeholders.
- **review**: Content is complete and awaiting technical review.
- **published**: Content is reviewed and approved for inclusion in assemblies.
- **deprecated**: Content is superseded or no longer applicable. Retained in the registry for
  reference stability but excluded from new assemblies. Set `supersedes` on the replacement
  topic if one exists.

## Keyword Guidelines

Keywords are the primary mechanism for Claude to locate topics by subject matter. Follow these
rules:

1. **3–8 keywords per topic.** Fewer is too sparse for search; more dilutes relevance.
2. **Include synonyms and abbreviations**: If the topic discusses "description logic," also
   include `DL` and `classifier`. If it covers SNOMED CT, include `SCT`.
3. **Do not repeat title words**: The title is already searchable. Keywords should expand
   coverage.
4. **Prefer specific terms over generic**: `stamp-coordinate` over `coordinate`;
   `el-profile` over `profile`.
5. **Include the names of key standards, systems, or specifications** referenced in the topic.

## Summary Guidelines

Summaries serve triple duty: human-readable abstracts, Claude search targets, and redundancy
detection signals. They are the primary mechanism by which Claude identifies content overlap
across sessions. Invest effort in making them specific and term-rich.

1. Be 1–3 sentences, 150–400 characters. This is longer than a typical abstract — the extra
   space is needed for the technical terms that drive redundancy detection.
2. Use indicative mood: "Describes the coordinate-based versioning pattern..." not "This topic
   describes..."
3. Include 3–5 key technical terms not already in `keywords` or `title`. Prioritize terms
   that would help identify overlap with other topics — the specific standards, formalisms,
   patterns, and domain concepts discussed in the body.
4. Mention the *angle* or *perspective* of the topic when relevant: "from the terminology
   authoring perspective" or "focusing on build-time validation." This helps distinguish
   intentionally overlapping topics.
5. Be specific enough that a reader (or Claude) can determine relevance and potential overlap
   without opening the file.

Bad: "Covers versioning." (too vague, no technical terms, useless for redundancy detection)

Bad: "Describes coordinate-based versioning." (marginally better but still lacks the specific
terms that would trigger overlap detection)

Good: "Describes the coordinate-based versioning pattern where each component version is
identified by module, path, and temporal coordinates within the STAMP model. Covers the
relationship between coordinates and the version graph used for dependency resolution."

## Maintenance Rules

### When to Update

Update the registry whenever:

- A topic is created, modified, split, merged, or deprecated.
- A topic's status changes.
- An assembly's topic list changes.
- A decomposition session produces new topics.

### Who Updates

- **Claude (chat or Claude Code)**: Always produces registry YAML fragments as part of
  decomposition or topic creation. Fragments are reviewed and merged by the author.
- **Authors**: Responsible for final merge and commit. The registry is a source-controlled
  artifact.

### Validation

The CI build should enforce:

1. Every `.adoc` file under `topics/` has a corresponding registry entry with a matching `id`
   and `file` path.
2. Every registry entry's `file` path resolves to an existing `.adoc` file.
3. `topic-count` matches the actual count of topic entries.
4. All `dependencies` reference valid `topic-id` values.
5. All `related` entries reference valid `topic-id` values, and the relationship is
   bidirectional — if topic A lists topic B as `related`, topic B must list topic A.
6. All `topic-refs` in assembly sections reference valid `topic-id` values.
7. No duplicate `topic-id` values exist.
8. Every published topic appears in at least one assembly's `sections`.

A Maven Enforcer rule or a lightweight validation script invoked during `validate` phase can
perform these checks.

## Generated Artifact: term-index.yaml

The build produces `term-index.yaml` by collecting all `indexterm` and `((...))` entries from
topic `.adoc` files. This file is a generated artifact — it must not be hand-edited. See
`IKE-INDEX.md` for the full schema and authoring conventions.

### Purpose

The term index provides a reverse mapping from technical terms to topics. While the registry's
`keywords` and `summary` fields capture what a topic is *about*, the term index captures what
a topic *discusses*. This distinction matters for redundancy detection: two topics may have
different keywords but discuss the same underlying concepts.

### Location

```
{topic-library-module}/target/generated/term-index.yaml
```

The term index is generated during the build and placed in the `target/` directory. It is not
committed to source control. It is included in the packaged topic library zip so that
dependent modules and Claude have access to it after unpacking.

### Build Integration

A build-time script (Groovy, Python, or similar) walks all `.adoc` files under `topics/`,
extracts `indexterm` macros and `((...))` inline index terms, and produces the YAML file.
This script should run during the `process-resources` phase, after topic files are in place
but before packaging.

## Working with Claude

### Providing Context

At the start of a session involving topic work, upload or paste:

1. The `topic-registry.yaml` file (or the relevant domain section if the full file is too
   large).
2. The `term-index.yaml` file, if available and if the session involves integration or
   redundancy checking.

For a 600-page compendium decomposed into ~300 topics, the registry will be roughly 20–30 KB
of YAML and the term index roughly 10–15 KB — both well within context window limits.

Together, these two files give Claude a complete map of what exists (registry), where it sits
structurally (assembly sections), and what specific terms each topic discusses (term index).

### Requesting Topic Lookup

To find existing content without uploading topic files:

> Which topics cover STAMP coordinates? (Check the registry.)

Claude will search the registry's `title`, `keywords`, and `summary` fields to identify
matching topics and report their `topic-id`, `title`, and `summary`.

### Requesting Registry Updates

After any topic creation or modification:

> Provide the updated registry YAML fragment for the topics we just created.

Claude will produce a YAML block ready for merge into `topic-registry.yaml`.
