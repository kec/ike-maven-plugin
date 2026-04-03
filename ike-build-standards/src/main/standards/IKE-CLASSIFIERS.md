# IKE Classifier Conventions

## Overview

IKE uses Maven classified artifacts as a content distribution mechanism.
Each classifier produces a ZIP artifact attached to the reactor via
`maven-assembly-plugin`. Assembly descriptors use `<id>` matching the
classifier name and `<includeBaseDirectory>false</includeBaseDirectory>`.

## Classifier Taxonomy

### Infrastructure Classifiers

| Classifier | Producer Module | Assembly Descriptor | Unpack Phase | Destination |
|---|---|---|---|---|
| `claude` | `ike-build-standards` | `src/assembly/claude-standards.xml` | `validate` | `.claude/standards/` |
| `tools` | `ike-build-tools` | `src/assembly/build-tools.xml` | `initialize` | `target/build-tools/` |
| `docs` | `ike-build-standards` | `src/assembly/docs.xml` | (consumer choice) | (consumer choice) |

### Documentation Source Classifier

| Classifier | Producer Module | Assembly Descriptor | Unpack Phase | Destination |
|---|---|---|---|---|
| `asciidoc` | (any doc project) | `${ike.assembly.directory}/asciidoc.xml` | `generate-sources` | `target/generated-sources/asciidoc/` |

### Renderer Classifiers

| Classifier | Format | Assembly Descriptor | Phase | Skip Flag |
|---|---|---|---|---|
| `html` | HTML (multi-file) | `${ike.assembly.directory}/html.xml` | `verify` | `ike.skip.html` |
| `html-single` | HTML (single file) | `${ike.assembly.directory}/html-single.xml` | `verify` | `ike.skip.html-single` |
| `prawn` | PDF (Prawn) | `${ike.assembly.directory}/prawn.xml` | `verify` | `ike.skip.prawn` |
| `fop` | PDF (FOP) | `${ike.assembly.directory}/fop.xml` | `package` | `ike.skip.fop` |
| `prince` | PDF (Prince) | `${ike.assembly.directory}/prince.xml` | `package` | `ike.skip.prince` |
| `ah` | PDF (Antenna House) | `${ike.assembly.directory}/ah.xml` | `package` | `ike.skip.ah` |
| `weasyprint` | PDF (WeasyPrint) | `${ike.assembly.directory}/weasyprint.xml` | `package` | `ike.skip.weasyprint` |
| `xep` | PDF (XEP) | `${ike.assembly.directory}/xep.xml` | `package` | `ike.skip.xep` |
| `pdf` | PDF (default copy) | `${ike.assembly.directory}/pdf.xml` | `verify` | `ike.skip.pdf-default` |

Renderer assembly descriptors live in `ike-doc-resources` at
`src/main/resources/assembly/` and are unpacked to
`${ike.assembly.directory}` (`target/ike-doc-resources/assembly/`).

## Rules

- Assembly descriptor `<id>` must match the classifier name.
- All assembly descriptors use `<includeBaseDirectory>false</includeBaseDirectory>`.
- All classified artifacts use ZIP format.
- Infrastructure classifiers (`claude`, `tools`, `docs`) use
  `scope=provided` in consumer dependencies — they are build-time-only.
- Renderer classifiers are gated by `ike.skip.*` properties defaulting
  to `true`. Profiles flip the flag to `false`.
- Version management for infrastructure classifiers is declared inline in `ike-parent`.

## Adding a New Classifier

1. Create assembly descriptor in the producer module's `src/assembly/`
   (infrastructure) or in `ike-doc-resources/src/main/resources/assembly/`
   (renderer). Set `<id>` to the classifier name.
2. Add `maven-assembly-plugin` execution in the producer POM, phase
   `package`, referencing the descriptor.
3. Add managed dependency entry in `ike-parent`'s `<dependencyManagement>`
   with `<classifier>`, `<type>zip</type>`.
4. Consumer modules declare the dependency with `scope=provided`
   and add an unpack execution in the appropriate lifecycle phase.
