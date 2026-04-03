# IKE Index Term Standards

## Purpose

This document defines the conventions for inserting AsciiDoc index terms into topic fragments.
Index terms serve two functions:

1. **Rendered index**: The AsciiDoc build produces a back-of-book style index in the
   compendium and other assemblies, enabling readers to locate discussions of specific
   concepts across the document.
2. **Term-topic reverse index**: A build-time collector extracts all index terms and produces
   `term-index.yaml`, a generated artifact that maps terms to topics. Claude uses this index
   to detect content overlap, identify redundancy, and locate relevant topics during
   integration and revision work.

Index terms are inserted at authoring time — during initial decomposition, during on-demand
indexing of existing topics, or during revision. The build collects what authors (and Claude)
have marked; it does not perform automated term extraction.

For the rendered index to appear in an assembly's output, the assembly must include an
`[index]` block macro. See `IKE-ASSEMBLY.md` for index generation conventions, including
which assemblies require an index and backend support details.

## When Index Terms Are Generated

- **During decomposition**: Claude inserts index terms as part of fragment authoring. Index
  terms are a required deliverable alongside the `.adoc` file and registry YAML fragment.
- **On demand**: When asked to index an existing topic, Claude reads the topic and inserts
  terms per these standards, returning the updated file and a summary of terms added.
- **During revision**: When revising a topic, Claude reviews existing index terms for accuracy
  and adds terms for any new content.

## Term Selection Criteria

### Index-Worthy Terms

A term should be indexed if it meets **any** of the following criteria:

- **Named standards and specifications**: SNOMED CT, HL7, FHIR, TINKAR, ANF, ISO 13606,
  RF2, OWL, etc.
- **Named patterns and architectural concepts**: coordinate-based versioning, STAMP
  coordinate, knowledge graph layering, etc.
- **Formal methods and formalisms**: description logic, EL++ profile, necessary normal form,
  subsumption testing, role group, etc.
- **System and component names**: IKE, classifier, reasoner, terminology server, etc.
- **Defined domain vocabulary**: concept model, semantic tag, fully specified name, language
  refset, stated form, inferred form, etc.
- **Acronyms at first use**: Index the expanded form. The index entry should use the expanded
  form as primary, with the acronym as a secondary or see-also.
- **Named organizations and governance bodies**: IHTSDO, Regenstrief Institute, NLM, etc.,
  when substantively discussed (not just credited).

### Terms That Should Not Be Indexed

- **Generic technical terms** (database, server, API, REST, JSON, XML) unless the topic is
  *defining or explaining* them in the IKE context.
- **Ubiquitous domain terms** that appear in nearly every topic within a domain. In the
  terminology domain, "SNOMED CT" appears everywhere — do not index every occurrence.
  Index it only where SNOMED CT itself is being described, compared, or analyzed, not where
  it is simply the context for another discussion.
- **Passing mentions**: If a term appears in a single sentence without substantive discussion,
  do not index it. The term should be explained, defined, analyzed, or procedurally
  significant in the topic.
- **Terms already fully captured by the topic title and keywords**: If `arch-coord-versioning`
  has keywords `[versioning, coordinates, STAMP, temporal]` and the title is
  "Coordinate-Based Versioning," there is no need to add an inline index term for
  "coordinate-based versioning" in the opening paragraph. The title and keywords already
  make this topic findable. Index the *sub-concepts* discussed within the topic.

## AsciiDoc Syntax

### Inline Visible Terms

Use double parentheses for terms that should appear in the rendered text and generate an
index entry:

```asciidoc
The ((necessary normal form)) transformation ensures that all concept definitions
are reduced to a canonical structure before classification.
```

This renders as: "The necessary normal form transformation ensures..." and adds an index
entry for "necessary normal form" pointing to this location.

### Silent Index Entries

Use the `indexterm` macro for entries that should not appear in the rendered text. This is
appropriate when the natural prose doesn't use the exact index term, or when adding
hierarchical entries:

```asciidoc
indexterm:[description logic, EL++ profile]
The EL++ profile restricts expressivity to ensure polynomial-time classification.
```

This adds an index entry "description logic > EL++ profile" without altering the rendered
text.

### Syntax Forms

| Form | Syntax | Result |
|------|--------|--------|
| Inline visible, single level | `\((term))` | Renders "term", indexes "term" |
| Silent, single level | `indexterm:[term]` | No rendered text, indexes "term" |
| Silent, two levels | `indexterm:[primary, secondary]` | Indexes "primary > secondary" |
| Silent, three levels | `indexterm:[primary, secondary, tertiary]` | Indexes "primary > secondary > tertiary" |

### Placement

- Place `indexterm` macros immediately before the paragraph that substantively discusses the
  term. Do not cluster them at the top or bottom of the topic.
- **Never place an `indexterm` macro on the line immediately before a list item** (`*`, `.`,
  `-`) with no blank line between. The AsciiDoc processor treats the list marker as inline
  formatting (e.g., `*` becomes bold) within a paragraph that began with the indexterm. Move
  the indexterm above the paragraph that introduces the list instead.
- Place inline `((...))` terms at the point of first substantive use within the topic. Do
  not mark subsequent occurrences.
- For a term discussed across multiple paragraphs, place the index entry at the start of the
  first paragraph.

## Density Guideline

- **Target**: 3–10 index terms per topic.
- **Minimum**: 3. A topic with fewer than 3 index terms is either too narrow to contribute
  meaningfully to the index or is under-indexed. Review for missing terms.
- **Maximum**: 10. More than 10 suggests over-indexing (indexing generic terms or passing
  mentions) or a topic that is too broad and should be decomposed further.
- **Exception**: Reference topics with large tables may legitimately have 10–15 index terms
  if the table defines many distinct concepts. Note the exception in the registry entry.

## Controlled Vocabulary

To ensure consistency across sessions and authors, the following hierarchical term structure
defines the standard primary and secondary index categories. Extend this vocabulary as new
domains are decomposed, and record additions in this document via pull request.

```
architecture
  coordinate-based versioning
  knowledge graph
  layered architecture
  module namespace
  nesting depth
  service architecture
  structural regime

clinical naming
  complex concept
  composite complex concept
  compound code
  expansion specification
  qualified complex concept

classification
  classifier
  EL++ profile
  necessary normal form
  role group
  subsumption

coordinates
  author coordinate
  module coordinate
  path coordinate
  STAMP coordinate
  temporal coordinate

governance
  editorial rules
  namespace registration
  promotion pathway
  quality assurance

interoperability
  ANF
  FHIR
  HL7
  RF2
  TINKAR

organizations
  IHTSDO
  NLM
  Regenstrief Institute

safety
  hazard analysis
  STPA
  system safety
  unsafe control action

terminology
  concept layer
  concept model
  description logic
  fully specified name
  language refset
  LOINC
  mapping
  refset
  RxNorm
  semantic tag
  SNOMED CT
  stated form / inferred form
  value set

versioning
  branch
  dependency resolution
  merge
  version conflict
  version manifest
```

### Using the Controlled Vocabulary

When indexing a term, check the controlled vocabulary first. If a matching entry exists, use
it verbatim — do not create synonymous variants. For example:

- Use `indexterm:[classification, necessary normal form]`, not
  `indexterm:[description logic, NNF]`.
- Use `indexterm:[coordinates, temporal coordinate]`, not
  `indexterm:[STAMP, temporal]`.

If no matching entry exists and the term is index-worthy per the selection criteria above,
add it to the appropriate category. If no category fits, propose a new top-level category.
Record all vocabulary additions in the deliverables for the session so they can be merged
into this document.

## Generated Build Artifact: term-index.yaml

The build produces `term-index.yaml` by collecting all `indexterm` and `((...))` entries
across all topic files. This file is generated and must not be hand-edited.

### Schema

```yaml
# term-index.yaml (generated)
generated: 2025-06-15
term-count: 487

terms:
  classification:
    _topics: []                          # topics indexed under "classification" directly
    necessary normal form:
      _topics: [term-dl-axioms, arch-dl-classifier, term-snomed-authoring-guide]
    EL++ profile:
      _topics: [term-dl-axioms, arch-dl-classifier]
    role group:
      _topics: [term-snomed-concept-model, term-dl-axioms]

  coordinates:
    _topics: [arch-coord-versioning]
    temporal coordinate:
      _topics: [arch-coord-versioning, ref-coordinate-fields, ops-version-migration]
    module coordinate:
      _topics: [arch-coord-versioning, arch-module-coordinates, ref-coordinate-fields]
```

### Use by Claude

When integrating a new topic or checking for redundancy, Claude examines `term-index.yaml`
to identify topics that share significant terms with the new or revised content. Clusters of
3+ shared terms between two topics are flagged for human review as potential redundancy.

The term index complements but does not replace the registry's `keywords` and `summary`
fields. Keywords capture what a topic is *about*; index terms capture what a topic *discusses*.
A topic about classifier architecture might have keyword `classifier` but index terms for
`necessary normal form`, `EL++ profile`, and `subsumption` — the specific technical concepts
it covers.

## Instructing Claude for Index Work

### During Decomposition

No special instruction needed. Index term insertion is a standard part of decomposition per
`IKE-TOPIC-DECOMPOSITION.md`. Claude produces fragments with index terms already in place.

### On-Demand Indexing

> Index the following topic per IKE-INDEX standards: [upload or paste topic file]

Claude returns:

1. The updated `.adoc` file with index terms inserted.
2. A summary listing each term added, its index hierarchy, and a brief rationale.
3. Any proposed additions to the controlled vocabulary.

### Batch Indexing

> Index these topics per IKE-INDEX standards: [upload or paste multiple topic files]

Claude processes each file and returns the updated files plus a consolidated summary and
vocabulary additions.
