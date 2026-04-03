# IKE Documentation Project Standards

## Parent Selection

- **Doc-only projects**: inherit from `ike-parent`
- **Java + docs projects**: inherit from `java-parent` (which inherits `ike-parent`)

## Packaging

Doc-only projects use `jar` packaging, even though they produce no Java classes.
This avoids the `pom-skip-renderers` profile (which disables all renderers for
POM-packaging modules). The JAR artifact is minimal (only `META-INF/MANIFEST.MF`).

## Required Directory Structure

Minimum for a documentation project:

```
my-project/
├── pom.xml
└── src/
    └── docs/
        └── asciidoc/
            └── index.adoc
```

Optional additions:

```
src/docs/asciidoc/
├── index.adoc              # Master document
├── chapters/               # Modular chapter includes
│   ├── intro.adoc
│   └── architecture.adoc
└── .mermaid-config.json    # Mermaid diagram config (legacy; prefer PlantUML/GraphViz)
```

## Document Attributes

Standard attributes for the master document (`index.adoc`):

```asciidoc
= Document Title
:author: IKE Community
:revnumber: {project-version}
:revdate: {docdate}
:doctype: book
:toc: left
:toclevels: 3
:sectnums:
:icons: font
:source-highlighter: coderay
:experimental:
```

## Diagram Conventions

All diagrams are rendered server-side via Kroki. No local CLI tools needed.

- **PlantUML** (preferred): Standard `@startuml`/`@enduml` syntax. Clean SVG across all renderers.
- **GraphViz** (preferred): Standard `digraph`/`graph` syntax. Clean SVG across all renderers.
- **Mermaid** (discouraged): SVG uses `<foreignObject>`, which breaks in Prawn, WeasyPrint,
  and partially in FOP. Use only for HTML-only output or diagram types without PlantUML
  equivalents (Gantt, pie). If used, set `htmlLabels: false` in `.mermaid-config.json`.
- **Kroki server**: Default `https://kroki.komet.sh`, override with `-Dkroki.server.url=...`

For editorial guidance on **when** to include a diagram, **which engine** to
choose, style conventions, and renderer compatibility details, see `IKE-DIAGRAMS.md`.

## Koncept Macro Usage

Reference formally identified terminology with `k:Name[]`:

```asciidoc
The Koncept k:DiabetesMellitus[] is a metabolic disorder.
```

Koncept definitions are provided via YAML files in the
`koncept-asciidoc-extension` module.

## Theme Customization

The default IKE theme is provided by `ike-doc-resources` and unpacked
automatically. To override:

1. Create `src/theme/ike-default-theme.yml` in your project
2. Add to `<properties>` in your POM:

```xml
<asciidoc.theme.directory>${project.basedir}/src/theme</asciidoc.theme.directory>
```

## Build Commands

```bash
# HTML only (default):
mvn clean verify

# HTML + specific PDF renderer:
mvn clean verify -Dike.pdf.prawn
mvn clean verify -Dike.pdf.fop
mvn clean verify -Dike.pdf.prince
mvn clean verify -Dike.pdf.ah
mvn clean verify -Dike.pdf.weasyprint
mvn clean verify -Dike.pdf.xep

# Multiple renderers:
mvn clean verify -Dike.pdf.prawn -Dike.pdf.fop

# Self-contained HTML:
mvn clean verify -Dike.html.single
```

## Output Locations

| Format | Directory |
|--------|-----------|
| HTML | `target/generated-docs/html/` |
| Self-contained HTML | `target/generated-docs/html-single/` |
| Prawn PDF | `target/generated-docs/pdf-prawn/` |
| FOP PDF | `target/generated-docs/pdf-fop/` |
| Prince PDF | `target/generated-docs/pdf-prince/` |
| AH PDF | `target/generated-docs/pdf-ah/` |
| WeasyPrint PDF | `target/generated-docs/pdf-weasyprint/` |
| XEP PDF | `target/generated-docs/pdf-xep/` |
| Default PDF copy | `target/generated-docs/pdf/` |

## Renderer Capabilities

The pipeline supports 6 PDF renderers and 2 HTML outputs. Each uses a
different Asciidoctor backend and rendering pipeline, which determines
what features are available.

### Renderer Overview

| Renderer | Backend | Pipeline | License |
|----------|---------|----------|---------|
| HTML | html5 | AsciiDoc → HTML | Free |
| HTML-Single | html5 | AsciiDoc → HTML (data-URI) | Free |
| Prawn | pdf | AsciiDoc → PDF (direct) | Free |
| FOP | docbook5 | AsciiDoc → DocBook → XSL-FO → PDF | Free |
| Prince | html5 | AsciiDoc → HTML → PDF | Commercial |
| AH | html5 | AsciiDoc → HTML → PDF | Commercial |
| WeasyPrint | html5 | AsciiDoc → HTML → PDF | Free |
| XEP | docbook5 | AsciiDoc → DocBook → XSL-FO → PDF | Commercial |

### Feature Support by Backend

Not all AsciiDoc features work identically across backends. The
Asciidoctor backend determines what is available.

| Feature | html5 | pdf (Prawn) | docbook5 |
|---------|:-----:|:-----------:|:--------:|
| `[index]` catalog | No | Yes | Yes |
| `indexterm:[]` captured | Anchors only | Full index | Full index |
| Koncept badges | SVG | Text-only | DocBook phrase |
| Glossary (Postprocessor) | Yes | No (crashes) | Yes |
| SVG diagrams (Kroki) | Full | PNG fallback | Full (with fixes) |
| Table of contents | Yes | Yes | Yes |
| Cross-references | Yes | Yes | Yes |
| Source highlighting | Rouge | Rouge | N/A (XSL-FO) |

**Renderers grouped by backend:**

- **html5**: HTML, HTML-Single, Prince, AH, WeasyPrint
- **pdf**: Prawn
- **docbook5**: FOP, XEP

### Known Limitations

**Index generation** — The `[index]` macro only produces a back-of-book
index with the `pdf` (Prawn) and `docbook5` (FOP/XEP) backends. The
`html5` backend captures `indexterm:[]` macros as hidden anchors but
does not generate the index catalog. This affects all HTML-based
renderers (HTML, Prince, AH, WeasyPrint). Use conditional inclusion
in assembly files:

```asciidoc
ifdef::backend-pdf,backend-docbook5[]
[index]
== Index
endif::[]
```

**Koncept badges** — The `k:Name[]` inline macro renders differently
per backend:
- html5: clickable SVG pill badges linking to glossary
- pdf (Prawn): text-only `K Label` (Prawn's HTML parser cannot render SVG)
- docbook5: `<phrase role="koncept">` styled by ike-fo.xsl

**Glossary Postprocessor** — Cannot be registered via SPI because it
crashes the Prawn backend (JRuby `PostprocessorProxy` TypeError). It is
registered per-execution in the asciidoctor-maven-plugin config for
html5 and docbook5 backends only.

**FOP SVG rendering** — Apache FOP uses Batik for SVG, which has
several limitations. The pipeline includes automated fixes (svgo +
antrun) for: missing `<rect>` dimensions, `orient="auto-start-reverse"`
on markers, `alignment-baseline="central"` (SVG2), and `fill:rgba()`
values. FOP also requires the `-r` (relaxed validation) flag.

**WeasyPrint SVG** — Cannot render `<foreignObject>` in SVGs, so
Mermaid diagrams use PNG format instead of SVG.

**Prawn SVG** — The `prawn-svg` library drops `<foreignObject>` from
Mermaid SVGs. Diagrams use PNG format instead of SVG.

## Creating a Standalone Doc Project

For a doc project in its own repository (outside the IKE reactor):

1. Ensure these artifacts are deployed to a shared repository:
   - `network.ike:ike-parent`
   - `network.ike:ike-doc-resources`
   - `network.ike:ike-build-standards`
   - `network.ike:minimal-fonts`
   - `network.ike:koncept-asciidoc-extension`

2. Use the POM template from `doc-example/README.md`

3. The `ike-doc-resources` JAR is unpacked automatically by `ike-parent`'s
   `maven-dependency-plugin` configuration — no `../` paths needed.

## Multi-Assembly Projects

When a project produces multiple documents (e.g., a compendium, an
architecture guide, and a developer guide), use a **topic library +
assembly module** pattern. Each assembly produces an independent PDF
artifact in one reactor build.

### Topic Library (`topics/`)

Every doc multi-module project has a module named `topics/` with
artifact ID `topics`. The group ID carries project uniqueness. See
`IKE-INGEST.md` for the full standard project structure.

- **Directory**: always `topics/`
- **ArtifactId**: always `topics`
- **Packaging**: JAR (renders HTML by default for authoring preview)
- **Source**: `src/docs/asciidoc/topics/` with topic files, plus
  `index.adoc` for a browsable all-topics preview
- **Artifact**: publishes a `-asciidoc` classified ZIP of all sources
- Does NOT render PDF — that is the assembly module's job

**POM template** (topic library):

```xml
<project xmlns="http://maven.apache.org/POM/4.1.0" ...>
    <modelVersion>4.1.0</modelVersion>
    <parent>
        <groupId>network.ike</groupId>
        <artifactId>ike-parent</artifactId>
        <version>1.1.0-SNAPSHOT</version>
    </parent>
    <artifactId>topics</artifactId>
    <version>1.0.0-SNAPSHOT</version>

    <name>Topics</name>

    <properties>
        <ike.skip.asciidoc-zip>false</ike.skip.asciidoc-zip>
    </properties>

    <dependencies>
        <dependency>
            <groupId>network.ike</groupId>
            <artifactId>minimal-fonts</artifactId>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-dependency-plugin</artifactId>
            </plugin>
            <plugin>
                <groupId>org.asciidoctor</groupId>
                <artifactId>asciidoctor-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

### Assembly Module

An assembly module composes topics from one or more topic libraries
into a single document:

- **Packaging**: JAR (full renderer pipeline)
- **Source**: `src/docs/asciidoc/` with the assembly `.adoc` file
- **Dependencies**: one or more topic library `-asciidoc` ZIPs
- **Unpack**: `ike-parent` automatically unpacks `-asciidoc` ZIPs to
  `target/generated-sources/asciidoc/{artifactId}-asciidoc/`
- **Includes**: use AsciiDoc attributes to reference unpacked topics

**POM template** (assembly module):

```xml
<project xmlns="http://maven.apache.org/POM/4.1.0" ...>
    <modelVersion>4.1.0</modelVersion>
    <parent>
        <groupId>network.ike</groupId>
        <artifactId>ike-parent</artifactId>
        <version>1.1.0-SNAPSHOT</version>
    </parent>
    <artifactId>my-compendium</artifactId>
    <version>1.0.0-SNAPSHOT</version>

    <properties>
        <pdf.source.document>compendium</pdf.source.document>
    </properties>

    <dependencies>
        <dependency>
            <groupId>network.ike</groupId>
            <artifactId>topics</artifactId>
            <version>1.0.0-SNAPSHOT</version>
            <classifier>asciidoc</classifier>
            <type>zip</type>
        </dependency>
        <dependency>
            <groupId>network.ike</groupId>
            <artifactId>minimal-fonts</artifactId>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-dependency-plugin</artifactId>
            </plugin>
            <plugin>
                <groupId>org.asciidoctor</groupId>
                <artifactId>asciidoctor-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

### Include Path Resolution

In assembly `.adoc` files, use the `{generated}` attribute (provided by
`ike-parent`'s asciidoctor-maven-plugin config) to reference unpacked
topic libraries:

```asciidoc
:topics: {generated}/topics-asciidoc

== Developer Guide
include::{topics}/topics/dev/overview.adoc[leveloffset=+2]
```

The `leveloffset` value depends on the containing heading level — see
`IKE-ASCIIDOC-FRAGMENT.md` for the full table. Since all IKE assemblies
use `:doctype: book` with `==` chapter headings, topic includes under a
chapter need `leveloffset=+2` (not `+1`).

The attribute name matches the topic library's artifact ID. Since every
doc project uses `topics` as the standard artifact ID, this attribute
is always `:topics:`.

### IDE Preview with `.asciidoctorconfig`

Create `.asciidoctorconfig` in each assembly module root so IntelliJ
and VS Code resolve includes without a Maven build:

```
:generated: {asciidoctorconfigdir}/target/generated-sources/asciidoc
```

After one `mvn generate-sources`, the IDE resolves all includes, shows
previews, and provides file completion.

### Aggregator POM

The top-level POM uses `pom` packaging and lists subprojects:

```xml
<project xmlns="http://maven.apache.org/POM/4.1.0" ...>
    <modelVersion>4.1.0</modelVersion>
    <groupId>network.ike</groupId>
    <artifactId>my-documents</artifactId>
    <version>1.0.0-SNAPSHOT</version>
    <packaging>pom</packaging>

    <subprojects>
        <subproject>topics</subproject>
        <subproject>my-compendium</subproject>
        <subproject>my-guide</subproject>
    </subprojects>
</project>
```

### Build Commands

```bash
# IDE setup (unpack dependencies for preview):
mvn generate-sources

# All assemblies, HTML only:
mvn clean verify

# All assemblies with PDF:
mvn clean verify -Dike.pdf.prawn

# Single assembly with PDF:
mvn clean verify -pl my-compendium -am -Dike.pdf.prawn
```

### Cross-Project Composition

A future assembly can pull from multiple topic libraries across repos:

```asciidoc
:arch-topics: {generated}/arch-topics-asciidoc
:clinical-topics: {generated}/clinical-topics-asciidoc

include::{arch-topics}/topics/arch/design-lineage.adoc[leveloffset=+1]
include::{clinical-topics}/topics/clinical/workflow.adoc[leveloffset=+1]
```

Each `.asciidoctorconfig` defines the attributes for its own dependencies.
Attribute names are stable; values are project-local.
