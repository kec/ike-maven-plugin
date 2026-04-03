# IKE Diagram Authoring Conventions

## Purpose

This document defines when and how to use generated diagrams (PlantUML,
GraphViz) in IKE topic files. The build pipeline renders all diagrams
server-side via Kroki — no local CLI tools are needed. This standard
covers editorial judgment (when a diagram earns its place), tool
selection (which engine for which communication task), and authoring
mechanics (syntax, format, renderer compatibility).

## The Diagram Test

A diagram earns its place in a topic when it satisfies **at least one**
of these criteria:

1. **Spatial relationships matter.** The content involves topology,
   containment, adjacency, or layering that prose cannot convey without
   the reader mentally reconstructing a picture. Examples: architectural
   layers, data flow through pipeline stages, component boundaries.

2. **Multiple paths or branches exist.** Decision trees, state machines,
   workflow alternatives, or conditional logic where the reader needs to
   see branching structure at a glance.

3. **Temporal or sequential ordering spans more than four steps.** Short
   sequences (3–4 steps) read fine as prose or numbered lists. Longer
   sequences — especially with parallelism or feedback loops — benefit
   from visual flow.

4. **Relationships among entities are the point.** ER schemas, class
   hierarchies, dependency graphs, or subsumption lattices where the
   *connections* are the content, not just the entities.

5. **The topic is a cross-cutting overview that synthesizes other
   topics.** Architecture overviews, end-to-end walkthroughs, and
   integration boundary diagrams orient the reader across a document
   before they dive into individual topics.

## When Not to Diagram

Do **not** add a diagram when:

- **A table communicates the same structure more compactly.** Comparison
  matrices, attribute inventories, and feature catalogs are almost always
  better as tables. A table with 5 rows and 4 columns is scannable; the
  equivalent as a diagram is a cluttered box-and-arrow mess.

- **The diagram merely restates what the prose already says.** If removing
  the diagram would leave the reader no less informed, the diagram is
  decoration. Every diagram should encode information that is *difficult
  to extract from the surrounding prose alone*.

- **The concept is inherently linear and short.** A three-step ETL
  pipeline ("extract → transform → load") does not need a flowchart.
  Write it inline: "The pipeline extracts FHIR resources, transforms
  them into ANF statements, and loads them into Delta Lake."

- **The diagram would exceed ~20 nodes.** Large diagrams are unreadable
  at rendered size and force the reader to zoom. Decompose into multiple
  focused diagrams or use a summary diagram with detail diagrams for
  subcomponents.

- **The topic is a reference type consisting primarily of structured
  tables.** Reference topics (attribute inventories, field definitions,
  editorial rules) are already visually structured. Adding a diagram
  alongside a comprehensive table creates redundancy.

## Tool Selection

Choose the diagram engine based on what you are communicating.

### PlantUML — Preferred for Most Diagrams

PlantUML is the default choice for IKE documentation. It covers the
widest range of diagram types with reliable SVG output and good layout
control. PlantUML emits clean SVG using standard path and text
elements, which renders correctly across all backends — HTML, Prince,
FOP, Prawn, and WeasyPrint — without format workarounds.

| PlantUML Diagram Type | Best For |
|----------------------|----------|
| Activity diagram | Data pipelines, process flows, conditional logic |
| Sequence diagram | Request/response interactions, API call sequences, multi-actor protocols |
| Class diagram | Data models, type hierarchies (scales well beyond 12 classes) |
| Component diagram | Architectural overviews, interfaces and ports |
| Deployment diagram | Runtime topology, infrastructure layouts |
| State diagram | Lifecycle states, status transitions |
| Object diagram | Instance-level relationships, worked examples |
| ER diagram (`!pragma layout smetana`) | Relational schemas, entity relationships |

PlantUML also supports swim lanes (partitions), explicit layout control
(`together` blocks, hidden links, directional hints), and stereotypes —
features that provide fine-grained control over diagram presentation.

### GraphViz — Graphs Where Layout Precision Matters

Use GraphViz (`dot`, `neato`, `fdp`) when PlantUML's automatic layout
does not meet the need:

- **Dependency graphs** with many edges (GraphViz's edge routing is
  superior for dense graphs)
- **Subsumption hierarchies** where you need consistent rank alignment
- **Any diagram where you need precise control** over node positioning,
  edge routing, or cluster boundaries
- **Large graphs (>20 nodes)** where automatic layout quality is
  critical

GraphViz produces the cleanest SVG of the two engines and gives the
most predictable cross-renderer results.

### Decision Summary

```
Is it a sequence of interactions between actors?
  → PlantUML sequence diagram

Is it a data flow or process pipeline?
  → PlantUML activity diagram

Is it an entity-relationship or relational schema?
  → PlantUML ER diagram

Is it a state machine or lifecycle?
  → PlantUML state diagram

Is it an architectural overview with components and boundaries?
  → PlantUML component diagram

Does it have >15 nodes with many crossing edges?
  → GraphViz dot

Is it a subsumption lattice or type hierarchy?
  → GraphViz dot (for large) or PlantUML class diagram (for small)
```

## Authoring Mechanics

### Block Syntax

All diagram blocks follow the standard Asciidoctor Diagram pattern:

```asciidoc
.Pipeline architecture
[plantuml]
----
@startuml
left to right direction
rectangle "FHIR Source" as A
rectangle "Evrete Engine" as B
rectangle "ANF Statements" as C
rectangle "Delta Lake" as D
A --> B
B --> C
C --> D
@enduml
----
```

Key elements:

1. **Block title** (`.Pipeline architecture`) — Required. Every diagram
   must have a descriptive title. This renders as a figure caption and
   serves as alt text for accessibility.

2. **Block attribute** (`[plantuml]` or `[graphviz]`) — Required.
   Identifies the rendering engine.

3. **Delimiter** (`----`) — Standard listing block delimiters.

### Format

The pipeline defaults to SVG for all diagrams. SVG is the correct
choice because it scales without pixelation and supports text selection.
Do not override the format unless you have a specific, documented reason.

### Diagram Sizing

Do not set explicit `width` or `height` on diagram blocks unless the
rendered output is unreasonably large. Let the renderer and theme
control sizing. If a diagram is too wide, restructure it (e.g., switch
from `left to right direction` to top-down) rather than forcing a
smaller pixel size.

### Diagram Placement

Place the diagram at the point in the topic where it provides maximum
context — typically immediately after the introductory paragraph that
motivates the visual. Do not cluster all diagrams at the end of a
topic or place them before the prose that explains them.

For topics with both a summary diagram and detail tables, place the
diagram first to establish the structural overview, then follow with
tables that provide precise field-level detail.

## Style Conventions

### Node Labels

- Use short, meaningful labels (2–4 words). Abbreviate only when the
  abbreviation has been defined in surrounding prose.
- For pipeline stages, use imperative verbs: "Resolve Terminology",
  "Assemble Circumstance", not "Terminology Resolution Step".
- For architectural components, use noun phrases: "Knowledge Layer",
  "Delta Lake Store".

### Color and Styling

- Do not rely on color alone to convey meaning. Always pair color
  with labels, shapes, or line styles.
- For PlantUML, use the `!theme plain` directive unless the topic
  requires domain-specific styling.

### Edge Labels

- Label edges only when the relationship type is ambiguous. If a
  flowchart has a single path type (data flow), unlabeled edges are
  sufficient.
- For sequence diagrams, always label messages — that is the primary
  information channel.

### Consistency Within a Topic

If a topic contains multiple diagrams, use the same engine and the same
visual conventions (node shapes, directional orientation, labeling
style) across all of them. PlantUML should be the default for
multi-diagram topics. Do not mix engines within a single topic without
a compelling reason (e.g., a PlantUML component diagram alongside a
GraphViz dependency graph where layout precision is critical).

## Renderer Compatibility

The IKE pipeline renders diagrams via Kroki and produces SVG by
default. Both PlantUML and GraphViz emit clean SVG using standard
`<text>` and `<path>` elements, so diagrams render correctly across
all PDF backends without format workarounds.

| Renderer | SVG Support | Notes |
|----------|-------------|-------|
| HTML / HTML-Single | Full | All diagram types render correctly |
| Prawn (PDF) | Full | PlantUML and GraphViz SVGs render without issues |
| FOP (PDF) | Full | Batik-based; PlantUML and GraphViz SVGs are compatible |
| Prince (PDF) | Full | Commercial; handles all SVG |
| WeasyPrint (PDF) | Full | PlantUML and GraphViz SVGs render without issues |

## Diagram Density Guidelines

| Topic Type | Typical Diagram Count | Rationale |
|------------|----------------------|-----------|
| Concept (architectural overview) | 1–2 | One overview diagram, optionally one detail |
| Concept (process or pipeline) | 1–2 | Flow diagram plus optional phase detail |
| Concept (analytical) | 0–1 | Most analysis is prose; diagram only if spatial |
| Reference (schema or field catalog) | 0–1 | ER diagram if relational; otherwise tables suffice |
| Reference (worked example) | 1–3 | Flow showing transformation steps, schema, query |
| Task (procedure) | 0–1 | Only if the procedure has conditional branching |

These are guidelines, not limits. A complex end-to-end topic may
justify 3–4 diagrams if each covers a distinct aspect. The test is
always: does this diagram encode information difficult to extract from
the prose?

## Existing Topic Retrofit

The current topic corpus contains no inline diagrams. Do not retrofit
diagrams into existing topics unless the topic is being substantively
revised for other reasons. Gratuitous diagram addition to stable topics
creates review burden without proportional benefit. When a topic is
revised, evaluate whether a diagram would improve comprehension using
the Diagram Test criteria above.

## Things to Avoid

- **Diagrams as decoration.** A box labeled "System" with an arrow to
  a box labeled "User" adds nothing.
- **Org chart layouts for non-hierarchical relationships.** Use the
  appropriate diagram type for the relationship structure.
- **Encoding prose into diagram nodes.** If a node label is a full
  sentence, it should be prose with a cross-reference to the diagram,
  not a node.
- **Duplicating table content as a diagram.** If the topic has a table
  with columns "Input → Transform → Output", do not also draw a
  three-box flowchart showing the same thing.
