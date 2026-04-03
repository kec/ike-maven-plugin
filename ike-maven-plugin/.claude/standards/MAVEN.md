# Maven 4 Build Standards

## Core Principles

- **Declarative over imperative.** Use proper Maven plugins for their intended purpose. Never use `exec-maven-plugin` for tasks that have a dedicated Maven plugin (file copying, resource filtering, dependency management). When imperative logic is unavoidable, use `exec-maven-plugin` with an external bash script from `ike-build-tools` — never `maven-antrun-plugin`.
- **Every artifact must have proper coordinates.** GroupId, artifactId, version, classifier, and type must be explicit. No uncoordinated files floating outside the Maven reactor.
- **Consumer POM awareness.** Build-time configuration (plugin settings, profiles, properties used only during build) must not leak into the consumer POM. Use `<pluginManagement>` for inherited defaults, `<plugins>` for concrete bindings.

## Lifecycle Phase Binding

Bind plugin executions to semantically correct phases:

| Phase | Purpose | Examples |
|---|---|---|
| `validate` | Environment setup, prerequisite checks | Enforcer rules, standards unpack |
| `generate-sources` | Code/resource generation from external sources | Unpack cross-module AsciiDoc ZIPs |
| `generate-resources` | Resource preparation | Font download, config staging |
| `prepare-package` | Pre-packaging transforms | AsciiDoc to HTML/DocBook, SVG fixing |
| `package` | Artifact creation | PDF rendering, XSL-FO transforms |
| `verify` | Post-package validation and secondary artifacts | HTML generation, Prawn PDF, ZIP assembly |
| `install` | Local repo installation | Classified artifacts to ~/.m2 |

Never abuse profiles to simulate lifecycle phases. Profiles are configuration toggles, not workflow stages.

## Property-Driven Build Pattern

Use skip-flag properties (defaulting to `true`) to gate plugin executions. Profiles are thin toggles that flip flags to `false`. This makes profiles:
- Discoverable in IDE Maven panels
- Composable (activate multiple simultaneously)
- Overridable from CLI (`-Dike.skip.renderer=false`)

## Plugin Ordering

Within the same lifecycle phase, Maven runs plugins in POM declaration order. When one plugin's output is another's input within the same phase, declaration order is the contract. Document ordering dependencies with comments.

## Assembly Descriptors

- Place assembly descriptors in `src/assembly/`.
- Use `maven-assembly-plugin` for classified ZIP/TAR archives.
- Each assembly produces a properly classified artifact attached to the reactor.
- Schema: `http://maven.apache.org/ASSEMBLY/2.2.0`
- Use `<includeBaseDirectory>false</includeBaseDirectory>` unless the archive needs a wrapper directory.

## Prohibited Patterns

- `maven-antrun-plugin` — use `exec-maven-plugin` with an external bash script instead
- Inline shell commands in POM `<configuration>` blocks — extract to a named script
- `exec-maven-plugin` for file operations that have dedicated plugins (copying, moving, filtering)
- Manual file copying instead of resource filtering
- `<properties>` blocks in profiles that share names across co-activated profiles (last-wins collision)
- `git add -A` or `git add .` (stage specific files to avoid committing secrets or binaries)

## Shell Scripts in the Build

When the build requires imperative logic (patching files, conditional transforms,
multi-step operations that no Maven plugin handles), use `exec-maven-plugin` with
an external bash script. Never embed the logic inline in the POM.

- **Location**: All shared build scripts live in `ike-build-tools` at
  `src/main/resources/scripts/`. Consumer modules unpack the `-tools` ZIP
  to `target/build-tools/` and reference scripts via
  `${build.tools.directory}/scripts/{script-name}.sh`.
- **Module-specific scripts**: If a script is only used by one module and
  has no reuse potential, it may live in that module's
  `src/main/scripts/` directory. Prefer centralizing in `ike-build-tools`
  when practical.
- **Conventions**: Scripts must use `#!/usr/bin/env bash`, `set -euo pipefail`,
  and accept arguments for paths rather than hard-coding them. Include a
  usage comment block explaining what the script does and why.
- **In-place file editing**: Use `perl -pi -e` instead of `sed -i`. The
  `sed -i` flag is incompatible between macOS (`sed -i ''`) and GNU/Linux
  (`sed -i`). Perl's `-pi -e` syntax is identical on all platforms. Use
  `sed` only for non-in-place operations (piping, filtering output).
- **Phase binding**: Bind the `exec:exec` execution to the correct lifecycle
  phase per the phase binding table above.

## Required Patterns

- `maven-assembly-plugin` for classified archives
- `maven-resources-plugin` with filtering for environment-specific config
- `maven-enforcer-plugin` for prerequisite validation (Java version, Maven version)
- `maven-dependency-plugin` for artifact unpacking with proper GAV coordinates
- `exec-maven-plugin` with external bash scripts for imperative build logic
- Explicit `<version>` on every plugin in `<pluginManagement>`

## Dependency Management

- **Use BOMs (Bill of Materials) when available.** Import BOMs via `<dependencyManagement>` with `<scope>import</scope>` and `<type>pom</type>` to align transitive dependency versions. This is preferred over manually specifying versions for each dependency in a family (e.g., JUnit, Jackson, Spring).
  ```xml
  <dependencyManagement>
      <dependencies>
          <dependency>
              <groupId>org.junit</groupId>
              <artifactId>junit-bom</artifactId>
              <version>${junit.version}</version>
              <type>pom</type>
              <scope>import</scope>
          </dependency>
      </dependencies>
  </dependencyManagement>
  ```
- Child modules declare dependencies without `<version>` to inherit from the BOM or parent `<dependencyManagement>`.
- Never mix BOM-managed and manually-versioned dependencies from the same family.

## Maven 4 Specifics

- Model version `4.1.0` for new POMs.
- **POM namespace must match modelVersion.** `modelVersion 4.1.0` requires `xmlns="http://maven.apache.org/POM/4.1.0"` and `xsi:schemaLocation` pointing to `maven-4.1.0.xsd`. Never mix 4.0.0 namespace with 4.1.0 modelVersion.
  ```xml
  <!-- Correct for modelVersion 4.1.0 -->
  <project xmlns="http://maven.apache.org/POM/4.1.0"
           xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
           xsi:schemaLocation="http://maven.apache.org/POM/4.1.0
           http://maven.apache.org/xsd/maven-4.1.0.xsd">
      <modelVersion>4.1.0</modelVersion>
  ```
- `<subprojects>` replaces `<modules>` in reactor aggregator POMs.
- `<parent>` element: use either `<relativePath>` alone (when parent is at the default `../pom.xml`) or GAV alone with `<relativePath/>` (empty element — disables filesystem lookup, uses reactor/repo resolution). Never combine a non-empty `<relativePath>` with GAV.
- **Aggregator as parent.** When child modules inherit from an external parent (outside the reactor), the aggregator POM should declare that external parent and child modules should declare the aggregator as their parent. This forms the correct chain (child → aggregator → external parent) and ensures the default `../pom.xml` resolution matches the declared parent.
- Consumer POM is automatic — build-only config is stripped from the published POM.
