# IKE AsciiDoc Fragment Authoring Conventions

## Purpose

This document defines the authoring conventions for individual AsciiDoc topic files (fragments)
in the IKE topic library. Fragments are designed for reuse — they are included into assembly
documents via `include::` directives with `leveloffset` adjustments. These conventions ensure
that fragments compose correctly regardless of where they appear in an assembly's heading
hierarchy.

## File Structure

Every topic `.adoc` file must follow this structure:

```asciidoc
// {topic-id}
// Topic: {title}
// Type: {concept|task|reference|dialog}
// Status: {draft|review|published|deprecated}
:topic-id: arch-coord-versioning
:topic-type: concept
:topic-status: draft
:topic-keywords: versioning, coordinates, STAMP, temporal
:topic-scope-note: Covers versioning from the architecture perspective. \
  For version management procedures, see ops-version-migration.

[[arch-coord-versioning]]
= {Title}

{Content body with index terms per IKE-INDEX.md}
```

### Breakdown

1. **Comment header** (lines 1–4): Human-readable metadata for quick identification when
   viewing raw files. These are AsciiDoc comments and do not render.

2. **Attribute block** (lines 5–9): Machine-readable metadata. These attributes are available
   to the build pipeline and can be extracted for registry validation.

3. **Anchor** (line 11): A literal inline anchor matching the `topic-id` value. This is the
   cross-reference target. It must immediately precede the heading. **Always use a literal
   string** — never `[[{topic-id}]]`. Asciidoctor expands attributes for HTML output but does
   not register attribute-expanded anchors in its xref catalog, so cross-references to such
   anchors produce "possible invalid reference" warnings and lose their display text.

4. **Level-1 heading** (line 12): Every fragment starts with a level-1 heading (`=`). The
   assembly controls the actual rendered level via `leveloffset` in the `include::` directive.

5. **Content body** (line 14+): The topic content, with index terms embedded per
   `IKE-INDEX.md`.

## Heading Rules

### Always Start at Level 1

Fragments must use `= Title` as their top-level heading, regardless of where they will appear
in an assembly. The assembly's `include::` directive applies `leveloffset` to shift headings
to the correct depth.

```asciidoc
// In the fragment:
= Coordinate-Based Versioning        <-- level 1 in the fragment

== Temporal Coordinates               <-- level 2 in the fragment
```

```asciidoc
// In an assembly with :doctype: book — chapter heading is ==
== Architecture
include::topics/architecture/coord-versioning.adoc[leveloffset=+2]
// Renders "Coordinate-Based Versioning" as === (section within chapter)
// Renders "Temporal Coordinates" as ==== (subsection)
```

**Leveloffset must match the assembly's heading depth.** The offset places the
topic's `= Title` one level below the containing heading. Since all IKE assemblies
use `:doctype: book` where `==` headings are chapters:

| Assembly heading | Leveloffset | Topic `= Title` becomes | Topic `== Sub` becomes |
|------------------|-------------|-------------------------|------------------------|
| `=` (part)       | `+1`        | `==` (chapter)          | `===` (section)        |
| `==` (chapter)   | `+2`        | `===` (section)         | `====` (subsection)    |

Using `leveloffset=+1` under a `==` chapter heading is a common mistake — it makes
the topic a peer chapter instead of a child section, leaving the named chapter empty.

### Maximum Depth

Fragments should contain at most 2 levels of internal headings (the title plus one sublevel).
If deeper nesting is needed, the topic is likely too broad and should be decomposed further
per `IKE-TOPIC-DECOMPOSITION.md`.

### No Orphan Headings

Every heading must have content below it before the next heading. Do not use headings as
labels for empty sections or as organizational placeholders.

## Document Attributes: What to Include and What to Omit

### Include in Fragments

| Attribute            | Purpose                          | Example                                |
|----------------------|----------------------------------|----------------------------------------|
| `:topic-id:`         | Unique identifier                | `arch-coord-versioning`                |
| `:topic-type:`       | concept, task, reference, or dialog | `concept`                           |
| `:topic-status:`     | Lifecycle status                 | `published`                            |
| `:topic-keywords:`   | Comma-separated keyword list     | `versioning, coordinates, STAMP`       |
| `:topic-scope-note:` | Optional. Clarifies this topic's angle when it intentionally overlaps with a related topic. References the related topic-id. | `Covers classifiers from the authoring perspective. For classifier architecture, see arch-dl-classifier.` |

### External Source Attributes (ext/ domain only)

These attributes are required on all topics in the `ext/` domain and must not appear on
authored topics. See `IKE-INGEST.md` § "External Source Ingestion" for the full workflow,
including the mandatory confirmation step and content handling matrix.

| Attribute              | Purpose                                    | Example                                               |
|------------------------|--------------------------------------------|-------------------------------------------------------|
| `:topic-provenance:`   | Always `external` for ingested sources     | `external`                                            |
| `:topic-citation:`     | Full bibliographic citation                | `Hatchett PL. Open Questions PLH Answers. 2026-03-11. Unpublished memorandum.` |
| `:topic-license:`      | Rights/permissions note                    | `Internal use only — not for publication.`            |

### Never Include in Fragments

These are document-level concerns controlled by the assembly:

- `:doctype:` — set by the assembly
- `:toc:` or `:toc-placement:` — set by the assembly
- `:sectnums:` — set by the assembly
- `:imagesdir:` — set by the assembly or build plugin configuration
- `:source-highlighter:` — set by the build plugin
- `:icons:` — set by the assembly
- Any attribute that affects global document rendering

## Cross-References

### Referencing Other Topics

Use the standard AsciiDoc `xref:` macro with the target topic's `topic-id` as the anchor:

```asciidoc
For details on the module coordinate, see xref:arch-module-coordinates[Module Coordinates].
```

The anchor `arch-module-coordinates` corresponds to the `[[arch-module-coordinates]]` anchor
in the target topic's fragment. This works within a single assembly because all fragments
are included into the same document tree.

### Cross-References Across Assemblies

If a topic needs to reference content that may not be present in every assembly, use a
conditional include or a note:

```asciidoc
ifdef::compendium[]
See xref:term-snomed-concept-model[SNOMED CT Concept Model] for terminology details.
endif::[]
ifndef::compendium[]
NOTE: For terminology details, refer to the SNOMED CT Concept Model topic in the IKE
Compendium.
endif::[]
```

Use this sparingly. Most cross-references should work within any assembly that includes both
the source and target topics.

### Bibliography Citations

Reference bibliography entries defined in a `[bibliography]` section using `<<anchorId>>`:

```asciidoc
The Dragon Book <<aho1977>> formalized compiler architecture.
```

**Multiple citations must be separate refs.** Never comma-separate citation IDs
inside a single `<<>>` — AsciiDoc interprets `<<id1,id2>>` as "link to `id1` with
display text `id2`", which silently drops the first citation:

```asciidoc
// WRONG — renders as "[date2003]" linking to codd1970, loses codd1970 citation
<<codd1970,date2003>>

// CORRECT — renders as "[codd1970] [date2003]" with both citations
<<codd1970>> <<date2003>>
```

### Placeholder Cross-References

During decomposition, when the target topic does not yet exist:

```asciidoc
// TODO: xref to description logic classifier topic (not yet decomposed)
```

Mark the topic as `status: draft` in the registry with a `notes` field documenting the
unresolved reference.

## Content Conventions

### Opening Paragraph

Every topic must begin with a context-setting paragraph immediately after the title heading.
This paragraph should:

- Establish what the topic covers in 1–2 sentences.
- Be comprehensible without having read any other topic.
- Not begin with relational phrases like "As mentioned in..." or "Building on the previous
  section..."

Good:
```asciidoc
= Coordinate-Based Versioning

Coordinate-based versioning identifies each component version using a tuple of module, path,
and temporal coordinates. This pattern decouples version identity from sequential numbering
and supports concurrent development across organizational boundaries.
```

Bad:
```asciidoc
= Coordinate-Based Versioning

As discussed in the architecture overview, we need a versioning approach. This section
explains coordinates.
```

### Admonitions

Use standard AsciiDoc admonitions. Keep them concise:

```asciidoc
NOTE: Coordinate values are immutable once published. Corrections require a new coordinate.

WARNING: Changing a published topic-id breaks cross-references across all assemblies.

IMPORTANT: The module coordinate must match a registered namespace in the governance registry.
```

### Code Listings

Use source blocks with language annotation:

```asciidoc
[source,java]
----
public interface StampCoordinate {
    ModuleCoordinate getModule();
    PathCoordinate getPath();
    TemporalCoordinate getTime();
}
----
```

For listings over 30 lines, extract to a separate file:

```asciidoc
[source,java]
----
\include::examples/architecture/stamp-coordinate.java[]
----
```

### Tables

Use AsciiDoc table syntax. Always include a title:

```asciidoc
.Coordinate Fields
[cols="1,2,3", options="header"]
|===
| Field    | Type               | Description
| Module   | ModuleCoordinate   | Identifies the authoring organization and namespace
| Path     | PathCoordinate     | Identifies the development branch or release context
| Time     | TemporalCoordinate | Identifies the point-in-time or range
|===
```

### Images

Reference images using the `imagesdir` attribute (set by the assembly or build):

```asciidoc
.Architecture layer diagram
image::{imagesdir}/architecture/layer-diagram.png[Architecture layers, width=600]
```

- Always include alt text (the text in square brackets).
- Always include a block title (the line starting with `.`).
- Place image files in `src/docs/asciidoc/images/{domain}/`.

### Generated Diagrams

For inline diagrams rendered via Kroki (PlantUML, GraphViz, or Mermaid), see
`IKE-DIAGRAMS.md` for editorial guidance on when to include a diagram, which
engine to use, and renderer compatibility constraints. Key rules:

- **Prefer PlantUML or GraphViz over Mermaid.** Mermaid's SVG output uses
  `<foreignObject>` elements that break in most PDF renderers. PlantUML and
  GraphViz produce clean SVG that works across all backends.
- Every diagram block must have a block title (`.Title text`).
- Use `[plantuml]`, `[graphviz]`, or `[mermaid]` as the block attribute.
- Apply the Diagram Test from `IKE-DIAGRAMS.md` before adding any diagram —
  diagrams that merely restate prose or duplicate table content should be omitted.

## Task-Type Topic Conventions

Task topics follow a structured pattern:

```asciidoc
= Configuring a New Module Namespace

Brief context paragraph explaining when and why you would perform this task.

.Prerequisites
- Access to the governance registry
- A registered organization identifier

.Procedure
. Log into the governance console.
. Navigate to **Namespaces > Register New**.
. Enter the module identifier using the format `{org}.{project}.{module}`.
. Submit the registration request.

.Verification
After registration, verify the namespace appears:

[source,bash]
----
ike namespace list --org my-org
----

.Result
The new namespace is available for use in coordinate-based version identifiers.
```

Use titled blocks (`.Prerequisites`, `.Procedure`, `.Verification`, `.Result`) rather than
headings for the internal structure of task topics. This keeps the heading depth shallow
and provides visual consistency across task topics.

## Reference-Type Topic Conventions

Reference topics are primarily structured data — tables, schemas, or specification extracts:

```asciidoc
= Coordinate Field Definitions

This reference defines all fields in the STAMP coordinate system.

.STAMP Coordinate Fields
[cols="1,1,2,1", options="header"]
|===
| Field    | Type               | Description                              | Required
| Module   | ModuleCoordinate   | Authoring namespace                      | Yes
| Path     | PathCoordinate     | Development context                      | Yes
| Time     | TemporalCoordinate | Point-in-time or range                   | Yes
| Author   | AuthorCoordinate   | Individual contributor                   | No
|===
```

Reference topics should minimize narrative and maximize scannable structure.

## Dialog-Type Topic Conventions

Dialog topics preserve the original conversational structure of Socratic or dramatic dialogues.
They are never decomposed — the entire dialog is one topic file. See
`IKE-TOPIC-DECOMPOSITION.md` § "Dialog Topics."

```asciidoc
= On the Naming of Clinical Things

A dialogue on complex concepts, compound codes, and the nature of
clinical naming — in the spirit of Cratylus.

== Persons of the Dialogue

**Kleos** — An informaticist. Builder of systems...

**Phaedra** — A physician. Documenter of patient care...

== Part I: The Hardest Problem

**Kleos:** We have a problem to solve...
```

Key conventions for dialog fragments:

- Retain the original heading structure (`== Part I`, `== Epilogue`, etc.) — these are
  internal to the topic and shift via `leveloffset` like any other fragment.
- Speakers are rendered as bold (`**Name:**`) at the start of each speech.
- Stage directions and narrative framing use AsciiDoc italics (`_text_`).
- Epigraphs and block quotes use AsciiDoc `quote` blocks.
- Strip document-level attributes (`:doctype:`, `:toc:`, `:icons:`, etc.) — these belong
  to the assembly.
- The opening paragraph after the title should describe the dialog's subject matter, not
  begin with dialog text. This establishes context for readers encountering the topic in
  an assembly or search result.

## Index Terms

Every topic must contain index terms per `IKE-INDEX.md`. Index terms are embedded during
authoring — they are not added in a separate pass. Target 3–10 index terms per topic.

### Syntax Summary

Use inline double parentheses for terms that should appear in rendered text:

```asciidoc
The ((necessary normal form)) transformation ensures that all concept definitions
are reduced to a canonical structure before classification.
```

Use the `indexterm` macro for silent entries (hierarchical or alternate terms):

```asciidoc
indexterm:[classification, necessary normal form]
indexterm:[coordinates, temporal coordinate]
```

### Placement

- Place index terms at the point of first substantive use in the topic.
- Place `indexterm` macros immediately before the paragraph that discusses the term.
- Do not cluster index terms at the top or bottom of the file.
- Do not mark subsequent occurrences of a term already indexed.

### Controlled Vocabulary

Use the controlled vocabulary defined in `IKE-INDEX.md`. Check the vocabulary before
creating new index entries. If the term you need is not in the vocabulary, propose an
addition as part of your session deliverables.

See `IKE-INDEX.md` for full term selection criteria, density guidelines, and the current
controlled vocabulary.

## Things to Avoid

- **Document-level attributes**: `:doctype:`, `:toc:`, `:sectnums:`, etc.
- **Preamble text before the title heading**: The anchor and title must come first (after the
  attribute block).
- **Hard-coded heading levels**: Never use `===` as the top heading in a fragment to "pre-adjust"
  for assembly context. Always use `=` and let `leveloffset` do the work.
- **Inline HTML**: Stick to AsciiDoc markup.
- **Relative paths outside the topic tree**: All paths should resolve relative to the
  `src/docs/asciidoc/` root or via build-configured attributes like `{imagesdir}`.
- **Conditional logic for content within a single topic**: If content varies by audience or
  context, create separate topics rather than using `ifdef` blocks within the body. Reserve
  `ifdef` for cross-reference handling as described above.
- **Attribute references in anchors**: Never write `[[{topic-id}]]`. Asciidoctor's xref catalog
  does not resolve attribute-expanded anchors, so `xref:` links targeting them produce warnings
  and lose display text. Always use the literal topic ID: `[[arch-coord-versioning]]`.
