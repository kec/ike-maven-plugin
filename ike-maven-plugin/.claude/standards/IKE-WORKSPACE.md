# IKE Workspace Conventions

## What is an IKE Workspace?

An IKE Workspace is a multi-repository development environment managed
through `workspace.yaml` — a YAML manifest that declares all components,
their inter-repository dependencies, groups, and component types.

Workspace operations are implemented as Maven plugin goals in
`ike-workspace-maven-plugin`, invokable via the `ws:` prefix (requires
`network.ike` in `~/.m2/settings.xml` `<pluginGroups>`).

Single-repo goals (release, setup, asciidoc, etc.) remain in
`ike-maven-plugin` with the `ike:` prefix.

## Prerequisites

### Maven Settings

Add `network.ike` to `<pluginGroups>` in `~/.m2/settings.xml`:

```xml
<settings>
  <pluginGroups>
    <pluginGroup>network.ike</pluginGroup>
  </pluginGroups>
</settings>
```

### Workspace POM

The workspace root must contain a `pom.xml` that declares both plugins:

```xml
<project xmlns="http://maven.apache.org/POM/4.1.0" ...>
    <modelVersion>4.1.0</modelVersion>

    <!-- Inherit ike-parent to get managed plugin versions
         (including ike-tooling.version). Use the latest
         released ike-parent version from Nexus. -->
    <parent>
        <groupId>network.ike</groupId>
        <artifactId>ike-parent</artifactId>
        <version>LATEST-RELEASE</version>
        <relativePath/>
    </parent>

    <groupId>network.ike</groupId>
    <artifactId>ike-workspace</artifactId>
    <version>1.0.0</version>
    <packaging>pom</packaging>

    <build>
        <plugins>
            <!-- Workspace goals (ws:*) — version managed by ike-parent -->
            <plugin>
                <groupId>network.ike</groupId>
                <artifactId>ike-workspace-maven-plugin</artifactId>
                <version>${ike-tooling.version}</version>
            </plugin>
            <!-- Single-repo goals (ike:*) — version managed by ike-parent -->
            <plugin>
                <groupId>network.ike</groupId>
                <artifactId>ike-maven-plugin</artifactId>
                <version>${ike-tooling.version}</version>
            </plugin>
        </plugins>
    </build>

    <!-- File-activated profiles for partial checkout -->
    <profiles>
        <profile>
            <id>component-name</id>
            <activation>
                <file><exists>${project.basedir}/component-name/pom.xml</exists></file>
            </activation>
            <subprojects>
                <subproject>component-name</subproject>
            </subprojects>
        </profile>
        <!-- Repeat for each component -->
    </profiles>
</project>
```

File-activated profiles enable incremental IntelliJ builds: only checked-out
components participate in the reactor. Missing components are silently skipped.

## workspace.yaml Manifest

The manifest lives at the workspace root alongside `pom.xml`:

```yaml
schema-version: "1.0"
generated: 2026-02-25

defaults:
  branch: main

component-types:
  infrastructure:
    description: "Build tooling, parent POMs"
    build-command: "mvn clean install"
    checkpoint-mechanism: git-tag
  software:
    description: "Java libraries and applications"
    build-command: "mvn clean install"
    checkpoint-mechanism: git-tag
  document:
    description: "AsciiDoc topic libraries and assemblies"
    build-command: "mvn clean verify"
    checkpoint-mechanism: git-tag

components:
  - name: ike-pipeline
    type: infrastructure
    repo: git@github.com:IKE-Community/ike-pipeline.git
    version: "24-SNAPSHOT"
    depends-on: []

  - name: tinkar-core
    type: software
    repo: git@github.com:ikmdev/tinkar-core.git
    version: "1.80.0-SNAPSHOT"
    depends-on:
      - component: ike-pipeline
        relationship: build

groups:
  core: [ike-pipeline, tinkar-core]
  all: [ike-pipeline, tinkar-core, ...]
```

### Manifest Fields

| Field | Required | Description |
|-------|----------|-------------|
| `schema-version` | Yes | Schema version for forward compatibility |
| `defaults.branch` | Yes | Default branch for all components |
| `component-types` | Yes | Named types with build commands and checkpoint mechanisms |
| `components` | Yes | List of repositories in the workspace |
| `groups` | No | Named subsets for partial operations |

### Component Fields

| Field | Required | Description |
|-------|----------|-------------|
| `name` | Yes | Unique identifier (matches directory name) |
| `type` | Yes | References a `component-types` key |
| `repo` | Yes | Git clone URL |
| `branch` | No | Override default branch |
| `version` | No | Current version string (null/`~` for unversioned) |
| `group-id` | No | Maven groupId for version updates |
| `depends-on` | No | List of dependency declarations |

### Dependency Relationships

```yaml
depends-on:
  - component: ike-pipeline
    relationship: build      # needs compiled artifacts
  - component: tinkar-core
    relationship: content    # references architecture/concepts
  - component: ike-pipeline
    relationship: tooling    # uses CLI tools or plugins
```

Relationship types matter for cascade analysis: `build` dependencies require
rebuild; `content` dependencies may require only review.

## Goal Reference

### Workspace Goals

| Goal | Description |
|------|-------------|
| `ws:verify` | Validate manifest consistency (deps exist, no cycles, groups resolve, types defined) |
| `ws:status` | Git status across all repos (branch, dirty/clean, branch mismatch detection) |
| `ws:cascade` | Show downstream impact of a change (`-Dcomponent=<name>` required) |
| `ws:graph` | Print dependency graph (text or `-Dformat=dot` for Graphviz DOT) |
| `ws:init` | Clone/initialize repos from manifest (Syncthing-aware) |
| `ws:pull` | `git pull --rebase` across repos |
| `ws:stignore` | Generate `.stignore` files for Syncthing |
| `ws:dashboard` | Composite: verify + status + cascade-from-dirty |

### Gitflow Goals

| Goal | Description |
|------|-------------|
| `ws:feature-start` | Create `feature/<name>` branch with branch-qualified version |
| `ws:feature-finish` | Merge feature branch to main with `--no-ff`, strip qualifier, tag |
| `ws:checkpoint` | Record multi-repo checkpoint YAML (SHAs, versions, dirty flags) |

### Release Goals

| Goal | Description |
|------|-------------|
| `ws:release` | Workspace-level release orchestration — scan, filter, topo-sort, release in dependency order |
| `ike:generate-bom` | Auto-generate a standalone BOM POM from `ike-parent`'s `dependencyManagement` |

### Common Options

| Option | Applicable Goals | Description |
|--------|------------------|-------------|
| `-Dworkspace.manifest=<path>` | All workspace goals | Path to workspace.yaml (auto-detected by searching upward) |
| `-Dgroup=<name>` | status, init, pull, feature-start, feature-finish | Restrict to named group |
| `-Dcomponent=<name>` | cascade | Component to analyze (required) |
| `-Dformat=dot` | graph | Graphviz DOT output |
| `-Dfeature=<name>` | feature-start, feature-finish | Feature name (branch: `feature/<name>`) |
| `-DskipVersion=true` | feature-start | Skip POM version qualification |
| `-DtargetBranch=<name>` | feature-finish | Merge target (default: `main`) |
| `-Dpush=true` | feature-finish, checkpoint, release | Push to origin |
| `-Dtag=true` | checkpoint | Tag each component |
| `-DdryRun=true` | feature-start, feature-finish, release | Show plan without executing |
| `-Dname=<name>` | checkpoint | Checkpoint name (required) |
| `-DskipCheckpoint=true` | release | Skip pre-release checkpoint creation |
| `-Dbom.source=<artifactId>` | generate-bom | Source POM for dependency extraction (default: `ike-parent`) |

## Version Convention

Feature branches use branch-qualified versions:

```
<base-version>-<safe-branch-name>-SNAPSHOT
```

The main branch uses the unqualified version:

```
<base-version>-SNAPSHOT
```

`ws:feature-start` sets this automatically by updating all POM files in the
reactor. When creating files or modifying POMs in a workspace, respect the
branch-qualified version already set.

Safe branch name: replace `/` with `-` in the Git branch name.

## Maven 4 Project-Local Repository

Each workspace isolates installed artifacts via `.mvn/maven.properties`:

```
maven.repo.local.path.installed=${session.rootDirectory}/.mvn/local-repo
```

Do not modify this configuration. Do not reference artifacts from
other workspaces' local repositories.

## Syncthing

Working trees are synced between machines via Syncthing.
Use `ws:stignore` to generate deterministic `.stignore` files that exclude:

- `**/target`
- `**/.git`
- `**/.idea`
- `**/.DS_Store`
- `**/.claude/worktrees`
- `**/.mvn/local-repo`

Each machine has independent Git state, build output, and IDE config.
`ws:init` is Syncthing-aware: when a directory already exists (synced by
Syncthing but not yet a git repo), it runs `git init` + `git reset` instead
of `git clone`.

## Partial Checkout

File-activated profiles in the workspace POM enable partial checkout:
only cloned components participate in the reactor. This supports:

- **Incremental IntelliJ builds**: Open the workspace POM; only checked-out
  modules appear in the project tree.
- **Selective `mvn -pl -am`**: Build a specific component and its
  dependencies within the workspace.
- **New developer onboarding**: Clone workspace, run `ws:init -Dgroup=core`,
  build immediately with a minimal set.

## Checkpoint Files

`ws:checkpoint` records per-component state to
`checkpoints/checkpoint-<name>.yaml`:

```yaml
checkpoint:
  name: "release-1.0"
  created: "2026-03-20T17:00:00Z"
  components:
    ike-pipeline:
      sha: "a1b2c3d..."
      short-sha: "a1b2c3d"
      branch: "main"
      type: infrastructure
      version: "24-SNAPSHOT"
      dirty: false
```

Checkpoint files are committed to the workspace repository.
Optional tagging (`-Dtag=true`) creates `checkpoint/<name>/<component>`
tags in each component's repo.

## Workspace Release Orchestration (`ws:release`)

`ws:release` automates multi-component release across a workspace.
It replaces manual per-component release sequences with a single
orchestrated workflow that respects inter-repository dependency order.

### The Self-Limiting Cascade

The release is *self-limiting*: only checked-out repositories with
commits since their last release tag are candidates. Components that
are not checked out or have no changes are silently skipped. This
means the release scope is determined by the intersection of two sets:

1. Components physically present in the workspace (checked out)
2. Components with commits since their last release tag (dirty)

A workspace with three of ten components checked out will release
at most three components — and only those with actual changes.

### Workflow

The goal executes five phases:

1. **Scan** — Walk the workspace manifest, identify checked-out repos.
2. **Filter dirty** — For each checked-out repo, compare HEAD against
   the last release tag. Only repos with new commits are candidates.
3. **Topological sort** — Order candidates by dependency graph so that
   upstream components release before their dependents.
4. **Release in order** — For each candidate (in topo order):
   - Strip `-SNAPSHOT` from the version
   - Build and verify
   - Tag the release commit
   - Optionally push (`-Dpush=true`)
   - Bump to the next SNAPSHOT version
5. **Update cross-references** — After each release, update parent
   version references in downstream POMs that depend on the just-released
   component. This keeps the cascade self-consistent: when `ike-pipeline`
   releases version 24, downstream components that reference
   `ike-pipeline` as a parent are updated to `<version>24</version>`
   before they build.

### Pre-Release Checkpoint

By default, `ws:release` creates a checkpoint before the first
release to enable recovery. Use `-DskipCheckpoint=true` to bypass this.

### Options

| Option | Default | Description |
|--------|---------|-------------|
| `-Dcomponent=<name>` | (all dirty) | Release only the named component (and its dirty dependents) |
| `-Dgroup=<name>` | (all) | Restrict to components in the named group |
| `-DdryRun=true` | `false` | Show the release plan without executing |
| `-Dpush=true` | `false` | Push tags and commits to origin after each release |
| `-DskipCheckpoint=true` | `false` | Skip the pre-release checkpoint |

### Examples

```bash
# Dry run — see what would be released and in what order
mvn ws:release -DdryRun=true

# Release all dirty components, push results
mvn ws:release -Dpush=true

# Release only ike-pipeline and its dirty dependents
mvn ws:release -Dcomponent=ike-pipeline -Dpush=true

# Release the "core" group without creating a checkpoint
mvn ws:release -Dgroup=core -DskipCheckpoint=true -Dpush=true
```

### Dry Run Output

A dry run prints the release plan without executing:

```
[INFO] === Workspace Release Plan (DRY RUN) ===
[INFO] Dirty components (topo order):
[INFO]   1. ike-pipeline       24-SNAPSHOT → 24 → 25-SNAPSHOT
[INFO]   2. tinkar-core         1.80.0-SNAPSHOT → 1.80.0 → 1.81.0-SNAPSHOT
[INFO] Cross-reference updates:
[INFO]   tinkar-core: ike-pipeline parent 24-SNAPSHOT → 24
[INFO] Pre-release checkpoint: checkpoint/pre-release-20260320
[INFO] === No changes made (dry run) ===
```

## Auto-Generated BOM (`ike:generate-bom`)

`ike:generate-bom` produces a standalone Bill of Materials (BOM) POM
from `ike-parent`'s `<dependencyManagement>` section. The generated
BOM resolves all property references (`${project.version}`,
`${tinkar.version}`, etc.) to literal values, producing a self-contained
POM that external consumers can import without inheriting `ike-parent`.

### How It Works

The goal is bound to the `generate-resources` phase in the `ike-bom`
stub module. During a normal reactor build:

1. `ike-parent` builds first (it is earlier in the reactor order).
2. `ike-bom` reaches `generate-resources`.
3. `ike:generate-bom` reads `ike-parent`'s resolved model from the reactor.
4. All managed dependencies are extracted with property references
   resolved to their literal values.
5. A standalone `pom.xml` is written that replaces the stub for
   `install`/`deploy`.

### Zero Maintenance

The BOM is entirely derived from `ike-parent`. Adding a new managed
dependency to `ike-parent`'s `<dependencyManagement>` automatically
includes it in the next BOM build. There is no separate file to
maintain, no manual synchronization, and no risk of drift.

### Consumer Usage

External projects that do not inherit from `ike-parent` can import
the BOM for version alignment:

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>network.ike</groupId>
            <artifactId>ike-bom</artifactId>
            <version>24</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```

### Options

| Option | Default | Description |
|--------|---------|-------------|
| `-Dbom.source=<artifactId>` | `ike-parent` | Artifact ID of the POM whose `dependencyManagement` is extracted |

### Example

```bash
# Normal reactor build generates the BOM automatically
mvn clean install

# Build only the BOM (requires ike-parent in reactor)
mvn clean install -pl ike-bom -am
```

## Gitflow Workflow — End to End

### Single-Component Feature

A feature that touches only one component (e.g., `tinkar-core`):

```bash
# Start the feature — creates feature/add-nid-index branch, sets
# version to 1.80.0-add-nid-index-SNAPSHOT
mvn ws:feature-start -Dfeature=add-nid-index -Dcomponent=tinkar-core

# Work in tinkar-core/, commit normally
cd tinkar-core
# ... edit, build, test ...
git add -A && git commit -m "feat: add NID index for faster lookups"

# Preview the merge
mvn ws:feature-finish -Dfeature=add-nid-index -DdryRun=true
# Output:
#   tinkar-core: merge feature/add-nid-index → main
#   Version: 1.80.0-add-nid-index-SNAPSHOT → 1.80.0-SNAPSHOT
#   Tag: tinkar-core-1.80.0-add-nid-index-merge

# Merge and push
mvn ws:feature-finish -Dfeature=add-nid-index -Dpush=true
```

### Multi-Component Feature

A feature spanning `ike-pipeline` and `tinkar-core`:

```bash
# Start across the core group
mvn ws:feature-start -Dfeature=new-renderer -Dgroup=core
# Creates feature/new-renderer in both repos
# ike-pipeline: 24-new-renderer-SNAPSHOT
# tinkar-core:  1.80.0-new-renderer-SNAPSHOT

# Work across both repos, commit in each
cd ike-pipeline
# ... edit pipeline code ...
git add -A && git commit -m "feat: add weasyprint2 renderer support"

cd ../tinkar-core
# ... update build config ...
git add -A && git commit -m "feat: enable weasyprint2 for tinkar docs"

# Save a checkpoint for team visibility
mvn ws:checkpoint -Dname=new-renderer-wip

# Preview the coordinated merge
mvn ws:feature-finish -Dfeature=new-renderer -Dgroup=core -DdryRun=true
# Output:
#   ike-pipeline: merge feature/new-renderer → main
#     Version: 24-new-renderer-SNAPSHOT → 24-SNAPSHOT
#   tinkar-core: merge feature/new-renderer → main
#     Version: 1.80.0-new-renderer-SNAPSHOT → 1.80.0-SNAPSHOT

# Merge and push all
mvn ws:feature-finish -Dfeature=new-renderer -Dgroup=core -Dpush=true
```

### Release After Feature

After merging a feature, release the affected components:

```bash
# See what needs releasing
mvn ws:release -DdryRun=true
# Output:
#   Dirty components (topo order):
#     1. ike-pipeline       24-SNAPSHOT → 24 → 25-SNAPSHOT
#     2. tinkar-core         1.80.0-SNAPSHOT → 1.80.0 → 1.81.0-SNAPSHOT
#   Cross-reference updates:
#     tinkar-core: ike-pipeline parent 24-SNAPSHOT → 24

# Execute the release
mvn ws:release -Dpush=true
# Releases ike-pipeline first (upstream), then tinkar-core
# Tags: ike-pipeline-24, tinkar-core-1.80.0
# Post-release versions: 25-SNAPSHOT, 1.81.0-SNAPSHOT
```

## Troubleshooting

### Recovery from Failed `ws-release`

If `ws-release` fails mid-cascade (e.g., build failure in the second
component), the pre-release checkpoint file records the state of every
component before the release started. Re-running `mvn ws:release`
skips components that were already tagged and released — it resumes
from the point of failure.

```bash
# Check the checkpoint to see what was released
cat checkpoints/checkpoint-pre-release-*.yaml

# Re-run — already-released components are skipped
mvn ws:release -Dpush=true
```

### Merge Conflicts in `feature-finish`

When `feature-finish` encounters a merge conflict, it stops in the
conflicting repository. Resolve manually:

```bash
cd <conflicting-component>
# Resolve conflicts in the affected files
git add <resolved-files>
git commit

# Re-run feature-finish — already-merged components are skipped
mvn ws:feature-finish -Dfeature=my-feature -Dpush=true
```

### Plugin Prefix Not Resolving

If `mvn ws:status` fails with "No plugin found for prefix 'ws'":

1. Verify `~/.m2/settings.xml` contains `network.ike` in `<pluginGroups>`:

```xml
<pluginGroups>
  <pluginGroup>network.ike</pluginGroup>
</pluginGroups>
```

2. Verify the workspace `pom.xml` declares `ike-workspace-maven-plugin` in `<build><plugins>`.

3. Verify `ike-workspace-maven-plugin` is installed in the local repository:

```bash
mvn install -pl ike-workspace-maven-plugin -f <path-to-ike-pipeline>/pom.xml
```

### Component Not Found in Manifest

If a goal reports "component not found" for a name you expect to exist:

- Check spelling: component names in `workspace.yaml` are case-sensitive
  and must match directory names exactly.
- Check groups: if using `-Dgroup=core`, the component must be listed
  in the `groups.core` array in `workspace.yaml`.
- Check checkout: some goals only operate on checked-out components.
  Run `mvn ws:status` to see which components are present.

## Key Rules

- Never use `${revision}` for version indirection. Versions are literal in POMs.
- All reactor modules share a unified version.
- The version in the root POM is the single source of truth.
- Branch-qualified versions are set once at feature-start and committed.
- Workspace manifest (`workspace.yaml`) is the inter-repository dependency graph.
- The aggregator POM and the manifest are complementary: POM drives `mvn`,
  YAML drives `ws:` workspace goals.
