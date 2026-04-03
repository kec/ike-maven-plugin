# IKE Assembly Document Conventions

## Purpose

An assembly document composes topic fragments into a coherent deliverable. Assemblies contain
structural scaffolding — headings, `include::` directives, and document-level configuration —
but no substantive content of their own. All substantive content lives in topic fragments
authored per `IKE-ASCIIDOC-FRAGMENT.md`.

This separation ensures that content is authored once in a topic and reused across multiple
assemblies without duplication.

## Assembly Types

### Compendium

The master assembly that includes every published topic. It is the validation target: if a
topic is not in the compendium, it is effectively orphaned. The compendium is organized by
domain and serves as a comprehensive reference, not a linear read.

### Targeted Guide

A focused assembly that selects a subset of topics for a specific audience or purpose (e.g.,
"Versioning Guide," "Safety Analysis Report," "API Reference"). Targeted guides may include
brief transitional prose between included topics to provide narrative flow.

### Dialog Collection

A collection assembly that gathers all dialog topics into a single document. Dialogs are
Socratic or dramatic dialogues that explore domain questions through conversational structure.
Each dialog is included as a single topic (dialogs are never decomposed). The collection
provides minimal scaffolding — a preface explaining the dialog form, then one `include::`
per dialog. New dialogs are added to this assembly as they are ingested.

### Module Documentation

A per-module assembly that documents a single Maven module's functionality. Typically lives
within the module itself and includes topics from both the local source tree and the unpacked
topic library.

## Assembly File Structure

### Compendium

```asciidoc
= IKE Community Compendium
:doctype: book
:toc: left
:toclevels: 3
:sectnums:
:sectnumlevels: 3
:imagesdir: {topicsdir}/images
:source-highlighter: rouge
:icons: font

// Document attributes for conditional includes
:compendium:

[preface]
== About This Document

This compendium contains the complete set of published IKE Community documentation,
organized by domain. It serves as the master reference and is not intended for
sequential reading.

// ============================================================
// Architecture
// ============================================================

== Architecture

include::{topicsdir}/topics/architecture/overview.adoc[leveloffset=+1]

include::{topicsdir}/topics/architecture/coord-versioning.adoc[leveloffset=+1]

include::{topicsdir}/topics/architecture/module-coordinates.adoc[leveloffset=+1]

// ============================================================
// Terminology Management
// ============================================================

== Terminology Management

include::{topicsdir}/topics/terminology/snomed-ct/concept-model.adoc[leveloffset=+1]

include::{topicsdir}/topics/terminology/snomed-ct/description-logic-axioms.adoc[leveloffset=+1]

// ... remaining domains

// ============================================================
// Index (generated from indexterm macros in topic fragments)
// ============================================================

[index]
== Index
```

### Targeted Guide

```asciidoc
= Versioning Guide
:doctype: article
:toc: left
:toclevels: 2
:sectnums:
:imagesdir: {topicsdir}/images
:source-highlighter: rouge
:icons: font

== Introduction

This guide covers the coordinate-based versioning system used across IKE Community
projects. It is intended for developers and architects who need to understand how
component versions are identified, managed, and resolved.

== Core Concepts

include::{topicsdir}/topics/architecture/coord-versioning.adoc[leveloffset=+1]

include::{topicsdir}/topics/architecture/module-coordinates.adoc[leveloffset=+1]

== Procedures

// Transitional prose is permitted in assemblies to connect topics:
The following procedures assume you have completed the namespace registration
described in the Core Concepts section above.

include::{topicsdir}/topics/operations/version-migration.adoc[leveloffset=+1]

include::{topicsdir}/topics/operations/version-conflict-resolution.adoc[leveloffset=+1]

== Reference

include::{topicsdir}/topics/architecture/coordinate-field-definitions.adoc[leveloffset=+1]

// Index is optional for targeted guides. Include for guides with 30+ topics.
// [index]
// == Index
```

## Include Directive Conventions

### Path Resolution

All `include::` paths use the `{topicsdir}` attribute, which is set by the build
configuration to point to the directory where the topic library is unpacked:

```asciidoc
include::{topicsdir}/topics/architecture/coord-versioning.adoc[leveloffset=+1]
```

The `{topicsdir}` attribute is defined in the `asciidoctor-maven-plugin` configuration:

```xml
<configuration>
  <attributes>
    <topicsdir>${project.build.directory}/topics</topicsdir>
  </attributes>
</configuration>
```

This indirection insulates assemblies from changes in the unpack location.

### Level Offset

The `leveloffset` attribute shifts all headings in the included fragment. Since fragments
always start at level 1 (`=`), the offset determines the rendered depth:

| Assembly context         | `leveloffset` | Fragment `=` renders as |
|--------------------------|---------------|------------------------|
| Under a level-1 heading  | `+1`          | `==` (level 2)         |
| Under a level-2 heading  | `+2`          | `===` (level 3)        |
| At document root         | `+0`          | `=` (level 1)          |

**Standard practice**: Most compendium includes use `leveloffset=+1` because topics appear
under domain-level `==` headings. Targeted guides may vary.

### Blank Lines

Always place a blank line before and after each `include::` directive. AsciiDoc requires
this for correct block boundary detection:

```asciidoc
== Architecture

include::{topicsdir}/topics/architecture/overview.adoc[leveloffset=+1]

include::{topicsdir}/topics/architecture/coord-versioning.adoc[leveloffset=+1]

== Terminology
```

### Section Comments

Use AsciiDoc comment blocks to visually separate domain sections in the compendium. This aids
human navigation of the assembly file:

```asciidoc
// ============================================================
// Architecture
// ============================================================
```

## Document-Level Attributes

Assemblies own all document-level rendering configuration. The following attributes should be
set in assemblies and never in fragments:

| Attribute               | Compendium       | Targeted Guide   | Notes                    |
|-------------------------|------------------|------------------|--------------------------|
| `:doctype:`             | `book`           | `article`        | Books get parts/chapters |
| `:toc:`                 | `left`           | `left`           | Or `macro` for custom    |
| `:toclevels:`           | `3`              | `2`              | Deeper for compendium    |
| `:sectnums:`            | set              | set              | Enable section numbering |
| `:sectnumlevels:`       | `3`              | `2`              | Match toclevels          |
| `:imagesdir:`           | `{topicsdir}/images` | `{topicsdir}/images` | Via build attribute  |
| `:source-highlighter:`  | `rouge`          | `rouge`          | Or `coderay`/`highlight.js` |
| `:icons:`               | `font`           | `font`           | Font-based admonition icons |

## Conditional Content

Assemblies can define attributes that fragments use for conditional cross-references:

```asciidoc
// In the compendium assembly:
:compendium:

// In a fragment:
ifdef::compendium[]
See xref:term-snomed-concept-model[SNOMED CT Concept Model] for details.
endif::[]
```

This allows fragments to include richer cross-references in the compendium while gracefully
degrading in targeted guides that may not include the referenced topic.

**Rule**: Conditional attributes should be named after the assembly (`compendium`,
`versioning-guide`, etc.) and should only control cross-reference behavior, never substantive
content. If content varies by audience, create separate topics.

## Index Generation

Topic fragments contain `indexterm` macros and inline `((...))` index terms authored per
`IKE-INDEX.md`. Assemblies control whether those terms are collected into a rendered index.

### Compendium: Index Required

The compendium must include a generated index. Place the index block as the final section
of the document:

```asciidoc
// after all domain sections

[index]
== Index
```

The `[index]` block macro instructs the AsciiDoc processor to collect all `indexterm` and
`((...))` entries from the included fragments and render them as a sorted, linked index at
this location.

### Targeted Guides: Index Optional

Targeted guides should include an index when they contain 30 or more topics. For shorter
guides, the TOC is typically sufficient navigation. When including an index in a targeted
guide, use the same syntax as the compendium.

For guides that omit the index, add a commented placeholder so future maintainers know it
was an intentional choice:

```asciidoc
// Index omitted — guide has fewer than 30 topics.
// Uncomment if the guide grows:
// [index]
// == Index
```

### Backend Requirements

Index generation support varies by backend:

| Backend              | Index Support | Notes                                    |
|----------------------|---------------|------------------------------------------|
| DocBook              | Full          | Native index generation                  |
| `asciidoctor-pdf`    | Full          | Generates back-of-book index             |
| HTML5 (default)      | Partial       | Renders index term anchors but does not generate a collected index page without an extension |
| HTML5 + Asciidoctor.js | Partial    | Same limitation as default HTML5         |

For HTML5 output, consider the `asciidoctor-index` extension or generate the index via the
DocBook toolchain and convert. If HTML5 is the primary output format, the `term-index.yaml`
generated build artifact (see `IKE-INDEX.md` and `IKE-TOPIC-REGISTRY.md`) serves as the
machine-readable index regardless of rendering backend.

### Build Configuration

No additional `asciidoctor-maven-plugin` attributes are needed for index generation. The
`[index]` block macro and the `indexterm`/`((...))` markup in fragments are sufficient. The
processor handles collection and rendering.

For PDF output, ensure `asciidoctor-pdf` is configured in the build:

```xml
<plugin>
  <groupId>org.asciidoctor</groupId>
  <artifactId>asciidoctor-maven-plugin</artifactId>
  <dependencies>
    <dependency>
      <groupId>org.asciidoctor</groupId>
      <artifactId>asciidoctorj-pdf</artifactId>
      <version>${asciidoctorj-pdf.version}</version>
    </dependency>
  </dependencies>
  <executions>
    <execution>
      <id>generate-pdf</id>
      <phase>generate-resources</phase>
      <goals><goal>process-asciidoc</goal></goals>
      <configuration>
        <backend>pdf</backend>
      </configuration>
    </execution>
  </executions>
</plugin>
```

## Transitional Prose in Assemblies

Targeted guides may include brief transitional prose between `include::` directives to provide
narrative continuity:

```asciidoc
include::{topicsdir}/topics/architecture/coord-versioning.adoc[leveloffset=+1]

With the coordinate model established, the next step is understanding how version
conflicts are detected and resolved during concurrent development.

include::{topicsdir}/topics/operations/version-conflict-resolution.adoc[leveloffset=+1]
```

Constraints on transitional prose:

- Keep it to 1–3 sentences.
- It must not introduce new concepts or information — only connect the preceding topic to the
  following one.
- It must not duplicate content from the topics it connects.
- The compendium should contain minimal or no transitional prose, since it is not read
  sequentially.

## Assembly Registration

Every assembly must have an entry in `topic-registry.yaml` under the `assemblies` section.
The entry's `sections` structure must be kept in sync with the `include::` directives and
heading hierarchy in the assembly file. See `IKE-TOPIC-REGISTRY.md` for the assembly entry
schema, including the nested `sections` format.

CI validation should check that:

1. Every `include::` path in the assembly resolves to a topic with a valid registry entry.
2. The `sections` hierarchy in the registry matches the actual heading structure and includes
   in the assembly file (same topics, same nesting, same order).
3. Every published, non-deprecated topic appears in at least one assembly's `sections`.

## Maven Build Integration

### Topic Library Unpacking

Dependent modules unpack the topic library during `generate-resources`:

```xml
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-dependency-plugin</artifactId>
  <executions>
    <execution>
      <id>unpack-topics</id>
      <phase>generate-resources</phase>
      <goals><goal>unpack-dependencies</goal></goals>
      <configuration>
        <includeGroupIds>${project.groupId}</includeGroupIds>
        <includeArtifactIds>topics</includeArtifactIds>
        <includeClassifiers>topics</includeClassifiers>
        <outputDirectory>${project.build.directory}/topics</outputDirectory>
      </configuration>
    </execution>
  </executions>
</plugin>
```

### AsciiDoctor Plugin Configuration

```xml
<plugin>
  <groupId>org.asciidoctor</groupId>
  <artifactId>asciidoctor-maven-plugin</artifactId>
  <configuration>
    <sourceDirectory>src/docs/asciidoc</sourceDirectory>
    <attributes>
      <topicsdir>${project.build.directory}/topics</topicsdir>
      <imagesdir>${project.build.directory}/topics/images</imagesdir>
      <project-version>${project.version}</project-version>
    </attributes>
    <backend>html5</backend>
  </configuration>
  <executions>
    <execution>
      <id>generate-html</id>
      <phase>generate-resources</phase>
      <goals><goal>process-asciidoc</goal></goals>
    </execution>
  </executions>
</plugin>
```

### Build Order

The topic library module must build before any assembly module. Enforce this via the reactor
ordering in the parent POM's `<modules>` section, or via explicit `<dependency>` declarations
in each assembly module's POM.

```xml
<!-- In the assembly module's pom.xml -->
<dependencies>
  <dependency>
    <groupId>${project.groupId}</groupId>
    <artifactId>topics</artifactId>
    <version>${project.version}</version>
    <classifier>topics</classifier>
    <type>zip</type>
  </dependency>
</dependencies>
```

## Creating a New Assembly

1. Create the assembly `.adoc` file in `src/docs/asciidoc/` of the assembly module.
2. Set document-level attributes per the table above.
3. Add `include::` directives for each topic, using `{topicsdir}` paths and appropriate
   `leveloffset`.
4. Add the assembly entry to `topic-registry.yaml` with nested `sections` mirroring the
   heading hierarchy of the assembly.
5. Build and verify:
   - All includes resolve.
   - All cross-references resolve.
   - Heading levels render correctly.
   - TOC structure is sensible.
   - Registry `sections` match the actual assembly structure.

## Instructing Claude for Assembly Work

When requesting assembly creation or modification:

> Create a targeted guide assembly for [subject]. Include topics: [list of topic-ids or
> descriptions]. Follow IKE-ASSEMBLY conventions. Use `{topicsdir}` for include paths.

Claude should produce:

- The assembly `.adoc` file.
- The registry entry for the new assembly.
- A note if any referenced topic does not exist in the registry (indicating a gap that
  requires new topic creation).
