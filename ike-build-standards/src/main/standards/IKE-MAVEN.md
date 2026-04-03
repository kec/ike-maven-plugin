# IKE Maven Conventions

## Group ID

All IKE artifacts use `network.ike` as the group ID.

## Artifact Naming Convention

### Core rule

**Submodule directory name = submodule artifactId. Always.**
No abbreviations, no different naming. The directory `ike-workspace-model/`
contains the POM with `<artifactId>ike-workspace-model</artifactId>`.

### Naming patterns

| Concept | Pattern | Example |
|---------|---------|---------|
| Aggregator workspace | `{name}-workspace` | `ike-workspace` |
| Multi-module reactor | Descriptive, no `-reactor` suffix | `ike-pipeline`, `ike-tooling` |
| Maven plugin | `{prefix}-maven-plugin` (required) | `ike-maven-plugin` |
| Library | `{project}-{purpose}` | `ike-workspace-model` |
| Parent POM | `{project}-parent` | `ike-parent` |
| BOM | `{project}-bom` | `ike-bom` |
| Build standards | `{project}-build-standards` | `ike-build-standards` |

### Rules

1. **Submodule directory = artifactId** — no exceptions.
2. **Reactor artifactId must not collide with any submodule artifactId.**
   If the reactor contains a plugin module named `ike-maven-plugin`, the
   reactor POM cannot also be `ike-maven-plugin`.
3. **Maven plugins must follow `{prefix}-maven-plugin`** — required for
   Maven prefix resolution (`mvn ike:release` resolves to `ike-maven-plugin`).
4. **No `-reactor` suffix** — use a meaningful name that describes the
   project's purpose (`ike-tooling`, `ike-pipeline`).
5. **Git repo name should match the most recognizable artifact** — for a
   single-module project, repo = artifactId. For multi-module, the repo
   name may match the primary artifact rather than the reactor artifactId.
   Example: repo `ike-maven-plugin` with reactor artifactId `ike-tooling`.

### Aggregator workspaces vs. multi-module reactors

An **aggregator workspace** (`ike-workspace`) is a developer-local project
that ties multiple independent Git repositories together via `workspace.yaml`
and file-activated `<subprojects>` profiles. It is not deployed to Nexus.

A **multi-module reactor** (`ike-pipeline`, `ike-tooling`) is a single Git
repository with tightly coupled modules that share a version and release
together. It IS deployed to Nexus.

The two serve different purposes: workspaces coordinate across repos,
reactors coordinate within a repo. A workspace may contain multiple
reactors as checked-out components.

## Parent POM Inheritance

All IKE projects inherit from a single parent:

```
ike-parent                    (root POM — aggregator + parent)
  └── [your-project]          (inherits documentation + Java config)
```

`ike-parent` provides everything:
- AsciiDoc toolchain, PDF renderer profiles, font management, assembly descriptors, skip-flag property pattern
- Java 25 compiler config, JUnit 5 + AssertJ + Mockito dependency management, Surefire/Failsafe configuration
- JPMS support, source/javadoc attachment

The doc pipeline is activated by a file-based profile (`doc-pipeline`).
Projects with `src/docs/asciidoc/` get the full pipeline automatically.
Projects without that directory (infrastructure modules, tool modules)
get only the universal build config (compiler, surefire, enforcer).

## Infrastructure Modules

Infrastructure modules inherit from `ike-parent` like all other modules,
but the doc pipeline does not activate (they lack `src/docs/asciidoc/`):

| Module | Purpose | Packaging |
|---|---|---|
| `ike-build-standards` | Claude instruction files | POM + classified ZIP |
| `ike-build-tools` | Shared build scripts, release automation | POM + classified ZIP |
| `ike-doc-resources` | Shared doc build resources (themes, assembly descriptors, configs) | JAR |
| `minimal-fonts` | Noto font subset for PDF | JAR |
| `docbook-xsl` | DocBook XSL stylesheets + IKE FO layer | JAR |
| `koncept-asciidoc-extension` | AsciidoctorJ inline macro + glossary | JAR |
| `ike-bom` | Auto-generated BOM (via ike:generate-bom) | POM |

The `ike-maven-plugin` and `ike-workspace-model` are in a separate
reactor (`ike-tooling`) at `github.com/kec/ike-tooling`.
ike-pipeline consumes the plugin as an external build dependency at
`${ike-tooling.version}`.

These are built and installed first in the reactor. The Maven reactor handles build ordering automatically.

## pluginManagement Pattern

All plugin versions are declared in `<pluginManagement>` in the parent POM. Child modules activate plugins by declaring them in `<plugins>` with no version or configuration (unless overriding):

```xml
<!-- Parent: pluginManagement declares version + defaults -->
<pluginManagement>
    <plugins>
        <plugin>
            <artifactId>maven-compiler-plugin</artifactId>
            <version>3.13.0</version>
            <configuration>...</configuration>
        </plugin>
    </plugins>
</pluginManagement>

<!-- Child: activates the managed plugin, inherits config -->
<plugins>
    <plugin>
        <artifactId>maven-compiler-plugin</artifactId>
    </plugin>
</plugins>
```

## Dependency Coordination

- Use `<dependencyManagement>` in parent POMs for version alignment.
- Child modules declare dependencies without `<version>` to inherit managed versions.
- Infrastructure module versions are pinned explicitly (they don't inherit from the parent chain).

## Property Naming Convention

IKE-specific properties use the `ike.` prefix:

| Pattern | Purpose | Examples |
|---|---|---|
| `ike.skip.*` | Renderer/feature skip flags | `ike.skip.html`, `ike.skip.prawn` |
| `ike.pdf.*` | PDF renderer activation | `ike.pdf.prawn`, `ike.pdf.fop` |
| `ike.html.*` | HTML variant activation | `ike.html.single` |
| `ike.document.name` | Output document filename | Defaults to `${project.artifactId}` |
| `ike.assembly.directory` | Assembly descriptor location | Path to `src/assembly/` |

## Version Strategy

### Unified Versioning

All modules in the IKE pipeline reactor share a single version.
The version is declared once in the root `pom.xml` `<version>` element.
All subproject modules are versionless (parent is the aggregator).

**Consumer POM workaround (Maven 4.0.0-rc-5):** Maven 4's consumer POM
resolves `${project.version}` to literals in `<dependencies>`, but does
NOT resolve it in `<build><plugins>`, `<pluginManagement>`, or
`<dependencyManagement>`. This means external projects inheriting
`ike-parent` would get unresolved `${project.version}` references for
the `ike-maven-plugin` declaration and managed dependency versions.

The `ike:prepare-release` goal works around this by replacing all
`${project.version}` occurrences with the literal release version in
every POM before deploying, then restoring from backups after deploy.
This ensures deployed consumer POMs contain only concrete versions.

**When to remove:** When Maven resolves `${project.version}` in all
consumer POM sections (not just `<dependencies>`), the backup/replace/
restore logic in `PrepareReleaseMojo` and `ReleaseSupport` can be
removed. Test by deploying without the workaround and checking the
consumer POM (`~/.m2/repository/.../ike-parent-X.pom`) for any
remaining `${project.version}` references. No version indirection
property is needed regardless — use `${project.version}` in source POMs.

- Pipeline versions are sequential: 1.1.0, 1.2.0, 1.3.0, etc.
- No semantic versioning contract is implied. Each release supersedes
  the previous one.
- All modules in the reactor share the unified version.
### Branch-Qualified Versions

Feature branches use a branch-qualified SNAPSHOT version:

    1.2.0-shield-terminology-SNAPSHOT

The qualifier is the branch name with `/` replaced by `-`.
The `main` branch retains the unqualified version:

    1.2.0-SNAPSHOT

The `ws:init` goal sets this automatically at workspace creation.
See the IKE Workspace Conventions document for the full rationale.

### Standards Artifact Versioning

The `ike-build-standards` artifact now uses the unified pipeline version
(`1.1.0-SNAPSHOT`, etc.) like all other reactor modules. The previous
monotonic integer scheme (1, 2, 3...) is deprecated.

### Release Process

Releases are performed by `ike-maven-plugin` goals,
not by `maven-release-plugin` or `maven-gitflow-plugin`. A valid release:

1. Sets version to release version (strip `-SNAPSHOT`)
2. Builds and verifies
3. Tags the commit in Git (e.g., `release/1.2.0`)
4. Deploys the release artifact
5. Bumps to the next `-SNAPSHOT` for continued development

**Prohibited**: Manually removing `-SNAPSHOT` from `<version>` elements
and running `mvn deploy`. This produces untagged, unreproducible artifacts.

**During development**, all versions remain `-SNAPSHOT`. Only release
scripts may transition a version to a non-SNAPSHOT release.

### Standards Version Coordination

The `ike-build-standards` version is managed inline in `ike-parent`'s
`<dependencyManagement>` section, along with all other infrastructure
module versions.

- `ike-parent` declares `ike-build-standards` in `<dependencyManagement>`
  with the current version, classifier=claude, type=zip.
- The parent POM's `<pluginManagement>` defines the `unpack-dependencies`
  execution filtered by artifactId and classifier.
- Projects that inherit `ike-parent` get managed versions automatically.
- Standalone modules (not inheriting from the parent chain) declare an
  explicit version in their own dependency.

### Dependency Version Management

All dependency versions are declared inline in `ike-parent`'s
`<dependencyManagement>` section. Projects that inherit `ike-parent`
get managed versions automatically — no separate BOM import is needed.
Child modules declare dependencies without `<version>` to inherit
managed versions from the parent.

## Project-Local Repository (Maven 4)

The IKE pipeline uses Maven 4's split local repository to isolate
installed artifacts per workspace.

In `.mvn/maven.properties`:

    maven.repo.local.path.installed=${session.rootDirectory}/.mvn/local-repo

This ensures that `mvn install` writes artifacts to a directory within
the project tree, not to the shared `~/.m2/repository`. Each IKE Workspace
has its own project-local repository, preventing SNAPSHOT cross-contamination
between branches.

The shared `~/.m2/repository` remains a read-only cache for artifacts
downloaded from remote repositories.

The `.mvn/local-repo/` directory is excluded from:
- Git (via `.gitignore`)
- Syncthing (via `.stignore`)

## JVM Configuration (`.mvn/jvm.config`)

Every IKE project should include `.mvn/jvm.config` with standard JVM
flags. This file is read by Maven before the POM is parsed — it cannot
be inherited from a parent POM or unpacked at build time.

`ws:init` generates this file automatically when initializing workspace
components. Standard contents:

```
-Dpolyglotimpl.AttachLibraryFailureAction=ignore
```

| Flag | Purpose |
|------|---------|
| `polyglotimpl.AttachLibraryFailureAction=ignore` | Suppresses GraalVM TruffleAttach warning on standard JDKs |

Add additional JVM flags as needed (e.g., `--add-opens` for
reflection access, memory settings for large builds).

## POM Element Names

Always use full element names in POM files. Maven 4.1.0 supports
abbreviated element names (e.g., `<n>` for `<name>`, `<v>` for
`<version>`), but these abbreviations harm readability and are not
universally supported by tools. **Never use abbreviated element names.**

| Prohibited | Required |
|---|---|
| `<n>` | `<name>` |
| `<v>` | `<version>` |
| `<g>` | `<groupId>` |
| `<a>` | `<artifactId>` |

## Reactor Build

The root POM (`ike-parent`) is both the reactor aggregator and the single parent POM. It lists all 11 subproject modules via `<subprojects>`. Module ordering aids readability but Maven sorts automatically by dependency graph.

## Testing Philosophy

### No mocks by default

Do not use mock frameworks (Mockito, PowerMock, EasyMock) unless the
dependency being isolated meets **all three** criteria:

1. External I/O you cannot control (network, hardware, FDA endpoints)
2. Non-deterministic or slow (>1s per invocation)
3. Unavailable in CI (licensed software, physical devices)

If the dependency can be made fast and deterministic, use it directly.
Mocks test that code calls the mock the way you programmed it — that is
circular validation, not functional verification.

### Why mock frameworks are a liability

Mock frameworks depend on runtime bytecode manipulation (cglib,
ByteBuddy, Java agents). This creates ongoing maintenance costs:

- **JVM major version boundaries** break bytecode assumptions. Each
  upgrade requires mock framework version negotiation.
- **JPMS module enforcement** restricts reflective access. The
  `--add-opens` flags in surefire config are scar tissue from this
  fight. Strong encapsulation is the direction of travel — Oracle is
  not reversing it.
- **Preview features** alter class file semantics between releases. A
  mock framework tested against a stable release may silently
  misbehave with preview-enabled builds.
- **GraalVM native image** makes runtime bytecode generation
  impossible. Mock-dependent tests cannot run in native mode.

The maintenance cost of keeping mock infrastructure compatible with JVM
evolution is ongoing and escalating.

### What to do instead

**Extract pure functions from orchestration code.**  Mojos, controllers,
and service classes that orchestrate I/O should delegate logic to
static utility methods or records that take plain inputs and return
plain outputs.  Test the extracted functions directly.

Pattern:
```java
// Orchestration (not unit-tested — calls git, filesystem, network)
public void execute() {
    List<ComponentSnapshot> snapshots = gatherFromGit();
    String yaml = buildCheckpointYaml("name", timestamp, snapshots);
    Files.writeString(path, yaml);
}

// Pure function (unit-tested with real inputs)
public static String buildCheckpointYaml(String name,
        String timestamp, List<ComponentSnapshot> snapshots) { ... }
```

**Use LLM-generated test data** for domain-rich scenarios (concept
graphs, axiom sets, coordinate sequences). LLMs produce semantically
coherent test corpora that exercise the real pipeline — integration
coverage with scenario richness previously only achievable with mocks.

**Build fast, resettable test fixtures.** An in-memory or ephemeral
store (e.g., RocksDB with temp directory) provides real dependency
behavior at test speed.

### Where mocks are genuinely necessary

- Network calls to external services (FDA submission endpoints,
  Nexus, GitHub API) that cannot be stubbed with a local server
- JavaFX platform thread constraints (though TestFX handles most
  cases without mocking)
- Clock/timing control for deterministic testing of time-dependent
  behavior (prefer `Clock` injection over mocking `Instant.now()`)

### Plugin testing in ike-maven-plugin

The plugin follows the extract-and-test pattern:

| Utility | What it tests |
|---------|---------------|
| `GenerateBomMojo.buildBomXml()` | BOM XML generation from `BomEntry` records |
| `GenerateBomMojo.escapeXml()` | XML entity escaping |
| `WsCheckpointMojo.buildCheckpointYaml()` | Checkpoint YAML from `ComponentSnapshot` records |
| `GraphWorkspaceMojo.buildDotGraph()` | Graphviz DOT format generation |
| `GraphWorkspaceMojo.componentColor()` | Component type → DOT color mapping |
| `ReleaseSupport.validateRemotePath()` | Remote path safety validation |
| `ReleaseSupport.siteDiskPath()` | Site URL path generation |
| `ReleaseSupport.branchToSitePath()` | Branch name to URL-safe path |

Mojos that are pure I/O orchestration (status, pull, init, cascade)
delegate to `git` via `ReleaseSupport.exec()` or to `WorkspaceGraph`
(tested in ike-workspace-model with 51 tests). These are not
unit-tested because testing them would require mocking
`ProcessBuilder` — exactly the circular validation this standard
prohibits.
