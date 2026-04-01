# IKE VCS Bridge: Implementation Plan (Revised)

## Purpose

Enable seamless multi-machine development with Syncthing-based file synchronization
and git state coordination through the IKE Maven plugin. Developers work normally —
commit from IntelliJ, Claude Code, or the terminal. The system detects when git state
is stale due to a commit on another machine, blocks unsafe operations with a clear
message, and provides simple commands to reconcile.

## Background and Design Decisions

**Problem.** Syncthing syncs working trees between machines with `.git` excluded
(Syncthing corrupts `.git` internals). When machine A commits and pushes, machine B's
working tree is updated by Syncthing, but B's `.git` doesn't know about the commit.
B sees phantom dirty files. A commit on B duplicates A's changes and creates
divergent history.

**Solution.** A state file (`.ike/vcs-state`) is written by a `post-commit` git hook
on every commit. Syncthing delivers this state file alongside the working tree.
A `pre-commit` hook on every machine compares the state file SHA to local HEAD. If
they differ, the commit is blocked with instructions to run `mvnw ike:sync`.

## Resolved Design Decisions

These decisions were made in the design conversation and must not be revisited
without discussion with Keith.

| Decision | Rationale |
|----------|-----------|
| No jj dependency | Git only. No new VCS tool. |
| No background daemon | Consistency resolved at command invocation, not continuously. |
| Auto-push in post-commit | Ensures remote has commit objects for `ike:sync`. Safe because feature branches use squash-merge. Failure is silent — `ike:verify` diagnoses on the other machine. |
| Squash-merge default for feature-finish | Feature branch history is disposable. Main stays clean. Auto-push becomes safe. |
| Configurable merge strategy | Three goals: `feature-finish-squash` (default), `feature-finish-merge`, `feature-finish-rebase`. Squash deletes branch (divergent history otherwise). Merge/rebase keep branch by default. `-DkeepBranch` overrides either way. |
| Blocking hook, never mutating hook | `git reset` inside a pre-commit hook destroys the staging area. The hook blocks; `ike:sync` fixes. |
| `.ike/` committed, `vcs-state` gitignored | `.ike/` is the opt-in marker. State file is machine-local state. CI clones get the marker but not the state file. |
| Hook location: `~/.git-hooks/` | Alongside existing hooks (prepare-commit-msg, commit-msg, post-checkout). No filename collisions. VCS bridge hooks are `pre-commit`, `post-commit`, `pre-push`. No change to `core.hooksPath`. `ike:setup` writes three new files into the existing directory. |
| State file format: plain properties | Not JSON, not YAML. Simplest to parse in bash (`grep '^key=' \| cut -d= -f2`) and Java (`Properties.load()`). |
| Terminology: "state file" / "vcs-state" | Not "manifest" — avoids overloading with `META-INF/MANIFEST.MF` in Maven/JAR context. Classes: `VcsState`, methods: `readVcsState()`, `writeVcsState()`. |
| Fold VCS checks into existing `ike:verify` | The existing `verify` goal checks workspace manifest consistency. VCS state checks are added to it. No new goal name. |
| Always branch ws on feature-start | The workspace aggregator repo is branched alongside component repos. Provides isolation (workspace.yaml on main stays clean), collaboration (another developer can `ws init -Dbranch=feature/x`), and reproducibility. |
| SHA length: `--short=8` everywhere | Collision probability negligible for "same commit or not" comparison. 8 characters consistent across hooks, state file, and plugin. |
| Multiple remotes: `origin` only | Sufficient for current setup. State file could record `remote` field in the future. |
| pre-commit hook uses sed/grep only | No Python, no jq. Works in Git Bash on Windows with zero additional dependencies. |
| GitHub PRs for review, plugin for merge | PR merge button can't update POM versions. Plugin owns the merge because it owns the version lifecycle. |
| No branch-metadata.json | Unnecessary. Target branch defaults to `main` with `-DtargetBranch` override. Source version recovered by `VersionSupport.extractNumericBase()` stripping the branch qualifier. vcs-state branch field handles branch tracking. |
| Syncthing health check: default port 8384 | Default to `localhost:8384`. Configurable via `.ike/config` properties file if it exists, key `syncthing.port`. No config file required. |
| Remove existing FeatureFinishMojo | Replaced by three explicit goals: feature-finish-squash, feature-finish-merge, feature-finish-rebase. No command-line strategy selection. Each goal surfaces independently in IntelliJ and Maven tooling. |

## State File Specification

Path: `.ike/vcs-state`

```properties
timestamp=2026-03-31T14:22:00Z
machine=mac-studio
branch=main
sha=a1b2c3d4
action=commit
```

**Fields:**
- `timestamp` — UTC ISO 8601, when the action occurred
- `machine` — short hostname of the machine that performed the action
- `branch` — branch name at time of action
- `sha` — `git rev-parse --short=8 HEAD`
- `action` — one of: `commit`, `push`, `feature-start`, `feature-finish`, `release`, `checkpoint`

Written by the post-commit hook (action=commit), the pre-push hook (action=push),
and plugin goals (action=feature-start, feature-finish, release, checkpoint).

Gitignored. Syncthing delivers it. Not in git history.

## Hook Specifications

All hooks live in `~/.git-hooks/`. Installed by `ike:setup`.

### pre-commit

```bash
#!/usr/bin/env bash
# IKE VCS Bridge: pre-commit hook
# Blocks commits when git state is behind Syncthing-delivered state.
# Never mutates state. Developer runs 'mvnw ike:sync' to reconcile.

STATE_FILE=".ike/vcs-state"

# Not an IKE-managed repo — pass through
[ ! -d ".ike" ] && exit 0

# No state file yet — first commit or no Syncthing delivery, allow it
[ ! -f "$STATE_FILE" ] && exit 0

# Plugin-initiated commits bypass the check
[ "$IKE_VCS_CONTEXT" = "ike-maven-plugin" ] && exit 0

# Explicit developer override
[ "$IKE_VCS_OVERRIDE" = "1" ] && exit 0

# Compare state file SHA to local HEAD
state_sha=$(grep '^sha=' "$STATE_FILE" | cut -d= -f2)
local_sha=$(git rev-parse --short=8 HEAD 2>/dev/null)

if [ -n "$state_sha" ] && [ "$state_sha" != "$local_sha" ]; then
    state_machine=$(grep '^machine=' "$STATE_FILE" | cut -d= -f2)
    state_time=$(grep '^timestamp=' "$STATE_FILE" | cut -d= -f2)
    state_action=$(grep '^action=' "$STATE_FILE" | cut -d= -f2)
    state_branch=$(grep '^branch=' "$STATE_FILE" | cut -d= -f2)
    current_branch=$(git branch --show-current)
    echo ""
    echo "  ✗ Commit blocked: git state is behind Syncthing."
    echo ""
    echo "  Action '$state_action' was performed on '$state_machine' at $state_time"
    echo ""
    if [ "$state_branch" != "$current_branch" ]; then
        echo "  Branch mismatch: you are on '$current_branch', state file says '$state_branch'"
        echo ""
    fi
    echo "  Local HEAD:      $local_sha"
    echo "  State file SHA:  $state_sha"
    echo ""
    echo "  Run:  mvnw ike:verify    (see full details)"
    echo "        mvnw ike:sync      (reconcile, then retry commit)"
    echo ""
    exit 1
fi
```

### post-commit

```bash
#!/usr/bin/env bash
# IKE VCS Bridge: post-commit hook
# Writes state file after every commit, then auto-pushes.

# Not an IKE-managed repo — pass through
[ ! -d ".ike" ] && exit 0

BRANCH=$(git branch --show-current)
SHA=$(git rev-parse --short=8 HEAD)
MACHINE=${HOSTNAME%%.*}
TIMESTAMP=$(date -u +%Y-%m-%dT%H:%M:%SZ)

cat > .ike/vcs-state << EOF
timestamp=$TIMESTAMP
machine=$MACHINE
branch=$BRANCH
sha=$SHA
action=commit
EOF

# Auto-push: ensures remote has commit objects for ike:sync on other machine.
# Failure is silent — ike:verify on the other machine diagnoses the gap.
git push origin "$BRANCH" --quiet 2>/dev/null || true
```

Notes:
- `${HOSTNAME%%.*}` works in bash on macOS, Linux, and Git Bash on Windows.
  Avoids `hostname -s` which is unavailable in some Windows Git Bash versions.

### pre-push

```bash
#!/usr/bin/env bash
# IKE VCS Bridge: pre-push hook
# Blocks creation of new branches and tags unless through ike plugin.
# Pushes to existing branches are always allowed.

# Not an IKE-managed repo — pass through
[ ! -d ".ike" ] && exit 0

# Plugin-initiated pushes bypass the check
[ "$IKE_VCS_CONTEXT" = "ike-maven-plugin" ] && exit 0

# Explicit developer override
[ "$IKE_VCS_OVERRIDE" = "1" ] && exit 0

# Check each ref being pushed
while read local_ref local_sha remote_ref remote_sha; do
    # 40 zeros = new ref being created on remote
    if [ "$remote_sha" = "0000000000000000000000000000000000000000" ]; then
        echo ""
        echo "  ✗ Push blocked: creating new remote ref '$remote_ref'"
        echo ""
        echo "  New branches: mvnw ike:feature-start -Dfeature=<name>"
        echo "  New tags:     mvnw ike:release or mvnw ike:ws-checkpoint"
        echo ""
        echo "  Override:     IKE_VCS_OVERRIDE=1 git push ..."
        echo ""
        exit 1
    fi
done

# Existing branch/tag push — update state file action to "push"
if [ -d ".ike" ] && [ -f ".ike/vcs-state" ]; then
    BRANCH=$(git branch --show-current)
    SHA=$(git rev-parse --short=8 HEAD)
    MACHINE=${HOSTNAME%%.*}
    TIMESTAMP=$(date -u +%Y-%m-%dT%H:%M:%SZ)
    cat > .ike/vcs-state << EOF
timestamp=$TIMESTAMP
machine=$MACHINE
branch=$BRANCH
sha=$SHA
action=push
EOF
fi
```

## Plugin Goals

### Existing Plugin Context

- **Plugin coordinates:** `network.ike:ike-maven-plugin:45-SNAPSHOT`
- **Package:** `network.ike.plugin`
- **Git operations:** ProcessBuilder exclusively via `ReleaseSupport.exec()`. No JGit.
- **Path:** `pipeline-ws/ike-tooling/ike-maven-plugin/`

### New Goals

**`ike:setup`** — One-time machine setup.

```
Mojo:        SetupMojo.java
Purpose:     Install VCS bridge hooks into ~/.git-hooks/
Side effects:
  - Writes pre-commit, post-commit, pre-push to ~/.git-hooks/
  - Sets chmod +x on scripts
  - Verifies core.hooksPath points to ~/.git-hooks/
  - Prints confirmation with installed hook list
Notes:
  - Does not change core.hooksPath (already set by existing setup-hooks.sh)
  - Warns if core.hooksPath is not set or points elsewhere
  - Hook scripts are shipped as plugin resources in src/main/resources/hooks/
  - Overwrites existing VCS bridge hooks (idempotent upgrade)
  - Does NOT overwrite prepare-commit-msg, commit-msg, post-checkout (not ours)
```

**`ike:sync`** — Reconcile git state after Syncthing delivers changes.

```
Mojo:        SyncMojo.java
Purpose:     Fetch from remote and soft-reset to match state file
Side effects:
  - git fetch --all --quiet
  - git checkout <branch> (if state file branch differs from current)
  - git reset origin/<branch> --quiet (no --hard — working tree untouched)
  - Runs mvn validate if .claude/standards/ is absent
Flow:
  1. Read .ike/vcs-state for target branch and SHA
  2. Fetch all remotes
  3. If branch mismatch, checkout state file branch
  4. git reset origin/<branch> (soft)
  5. Verify: git rev-parse --short=8 HEAD should match state file SHA
  6. If mismatch: report clearly (push from other machine may have failed)
  7. Print summary
```

**`ike:commit`** — Commit with catch-up preamble.

```
Mojo:        CommitMojo.java
Purpose:     Sync first, then commit
Properties:
  -Dmessage="commit message"     (optional — omit to open editor / trigger
                                  prepare-commit-msg hook for Claude generation)
  -DaddAll=true                  (optional, default false — git add -A before commit)
  -Dpush=true                    (optional, default false — push after commit)
Flow:
  1. Run catch-up logic (shared with ike:sync)
  2. If addAll: git add -A
  3. git commit with IKE_VCS_CONTEXT=ike-maven-plugin
  4. If push: run push logic
```

**`ike:push`** — Push with catch-up preamble.

```
Mojo:        PushMojo.java
Purpose:     Sync first, then push
Properties:
  -Dremote=origin                (optional, default origin)
Flow:
  1. Run catch-up logic
  2. git push <remote> <branch> with IKE_VCS_CONTEXT=ike-maven-plugin
```

### Modified Goals

**`ike:verify`** — Extended with VCS state diagnostics.

```
Mojo:        VerifyWorkspaceMojo.java (existing — add VCS checks)
New checks:
  1. Read .ike/vcs-state — extract all fields
  2. Compare SHA to git rev-parse --short=8 HEAD
  3. Compare branch to current branch
  4. Check if SHA exists on remote: git ls-remote origin <branch>
  5. Report with action-aware diagnostic messages (see table below)
  6. In workspace mode: two-level report (ws repo + each component)
  7. Syncthing health check: GET http://localhost:<port>/rest/noauth/health (2s timeout)
     Port defaults to 8384. Reads .ike/config property syncthing.port if file exists.
  8. Standards and CLAUDE.md presence
```

Diagnostic messages by state:

| Condition | Output |
|-----------|--------|
| SHA match, branch match | `Status: in sync  ✓` |
| SHA mismatch, action=commit, SHA on remote | `Status: commit on <machine> at <time>. Run 'mvnw ike:sync'.` |
| SHA mismatch, action=commit, SHA NOT on remote | `Status: commit on <machine> at <time>, but push did not complete. Push from <machine> first, then 'mvnw ike:sync' here. Or: IKE_VCS_OVERRIDE=1 to proceed independently.` |
| Branch mismatch, action=feature-start | `Status: feature branch '<branch>' started on <machine> at <time>. Run 'mvnw ike:sync' to switch to the feature branch.` |
| Branch mismatch, action=feature-finish | `Status: feature finished on <machine> at <time>, merged to '<branch>'. Run 'mvnw ike:sync' to return to <branch>.` |
| SHA mismatch, action=push | `Status: push from <machine> at <time>. Local HEAD behind. Run 'mvnw ike:sync'.` |
| SHA mismatch, action=release | `Status: release performed on <machine> at <time>. Run 'mvnw ike:sync'.` |
| SHA mismatch, action=checkpoint | `Status: checkpoint created on <machine> at <time>. Run 'mvnw ike:sync'.` |
| No state file, .ike/ exists | `Status: no VCS state file. First commit, or Syncthing has not delivered state yet.` |
| No .ike/ directory | `Status: not an IKE-managed repo. VCS bridge inactive.` |

Workspace-mode output example:

```
IKE Environment Verification
─────────────────────────────
Machine:       mac-studio
Syncthing:     connected, folder idle  ✓
Standards:     .claude/standards/ present  ✓
CLAUDE.md:     present  ✓

Workspace:     feature/x  ✓  in sync
  ike-tooling: feature/x  ✓  in sync
  ike-docs:    feature/x  ✓  in sync
  ike-infra:   main       ─  not in feature scope

Component behind example:
  ike-tooling: feature/x  ⚠  commit on macbook-pro at 2026-03-31T14:22:00Z
               Local HEAD: a1b2c3d4  State file: e5f6g7h8
               Run 'mvnw ike:sync' to reconcile
```

**`ike:feature-start`** — Extended with VCS bridge integration.

```
Mojo:        FeatureStartMojo.java (existing — modify)
New behavior:
  1. Add catch-up preamble (shared sync logic)
  2. Branch the workspace aggregator repo to feature/<name> (always)
  3. Create feature branches in component repos (existing behavior)
  4. Update workspace.yaml on the feature branch (existing, now on ws feature branch)
  5. Set IKE_VCS_CONTEXT=ike-maven-plugin for commits
  6. Auto-push all branches (ws + components)
  7. Write state file with action=feature-start in ws and each component
```

**`ike:feature-finish-squash`** — Squash-merge feature branch. Default strategy.

```
Mojo:        FeatureFinishSquashMojo.java (new, extracts from existing FeatureFinishMojo)
Purpose:     Squash-merge feature branch to target, delete branch.
Properties:
  -Dfeature=<name>               (required or prompted)
  -Dgroup=<scope>                (optional)
  -DtargetBranch=main            (optional, default main)
  -DkeepBranch=false             (optional, default false — override to keep)
  -Dmessage="squash message"     (required or prompted)
Flow:
  1. Catch-up preamble
  2. Verify working tree is clean (all components + ws)
  3. For each component (reverse topological order):
     a. Strip branch-qualified version, commit
     b. Checkout target branch
     c. git merge --squash <feature-branch>
     d. git commit -m "<message>" with IKE_VCS_CONTEXT
     e. Push to origin
     f. Unless keepBranch: git branch -d <feature-branch>
     g. Unless keepBranch: git push origin --delete <feature-branch>
  4. Checkout ws to target branch
  5. Merge ws (update workspace.yaml branch fields to target)
  6. Push ws
  7. Unless keepBranch: delete ws feature branch (local + remote)
  8. Write state file with action=feature-finish in ws and each component
When to use:
  Default. Feature branch history is disposable. Target branch gets one
  clean commit per feature. Branch is deleted because squash creates
  divergent history — continuing on the branch would cause conflicts.
  Use -DkeepBranch=true only if you understand the consequences.
```

**`ike:feature-finish-merge`** — No-fast-forward merge. Preserves history.

```
Mojo:        FeatureFinishMergeMojo.java (new, based on existing FeatureFinishMojo)
Purpose:     Merge feature branch with merge commit, keep branch alive.
Properties:
  -Dfeature=<name>               (required or prompted)
  -Dgroup=<scope>                (optional)
  -DtargetBranch=main            (optional, default main)
  -DkeepBranch=true              (optional, default true — override to delete)
  -Dmessage="merge message"      (optional, auto-generated if omitted)
Flow:
  1. Catch-up preamble
  2. Verify working tree is clean
  3. For each component (reverse topological order):
     a. Checkout target branch
     b. git merge --no-ff <feature-branch> -m "<message>"
     c. Push to origin
     d. If !keepBranch: delete feature branch (local + remote)
  4. Merge ws similarly
  5. Write state file with action=feature-finish
When to use:
  Long-lived feature branches that periodically merge intermediate work
  to the target branch. Histories stay connected so the branch can
  continue after merge. Target branch gets the full feature history.
  Use when you need traceability of individual commits on the feature.
```

**`ike:feature-finish-rebase`** — Rebase feature commits onto target.

```
Mojo:        FeatureFinishRebaseMojo.java (new)
Purpose:     Rebase feature branch onto target, fast-forward merge.
Properties:
  -Dfeature=<name>               (required or prompted)
  -Dgroup=<scope>                (optional)
  -DtargetBranch=main            (optional, default main)
  -DkeepBranch=true              (optional, default true — override to delete)
Flow:
  1. Catch-up preamble
  2. Verify working tree is clean
  3. For each component (reverse topological order):
     a. Checkout feature branch
     b. git rebase <target-branch>
     c. Checkout target branch
     d. git merge --ff-only <feature-branch>
     e. Push to origin
     f. If !keepBranch: delete feature branch
  4. Rebase/merge ws similarly
  5. Write state file with action=feature-finish
When to use:
  When you want feature commits replayed individually on the target
  branch without a merge commit. Produces linear history. Good for
  small features where each commit is meaningful. Caution: rewrites
  feature branch history — other machines must ike:sync after rebase.
```

**`ike:release`** — Extended with VCS bridge integration.

```
Mojo:        ReleaseMojo.java (existing — modify)
New behavior:
  1. Add catch-up preamble
  2. Set IKE_VCS_CONTEXT=ike-maven-plugin for commits and pushes
  3. Write state file with action=release after completion
```

**`ike:ws-checkpoint`** — Extended with VCS bridge integration.

```
Mojo:        WsCheckpointMojo.java (existing — modify)
New behavior:
  1. Add catch-up preamble
  2. Set IKE_VCS_CONTEXT=ike-maven-plugin for commits and tag pushes
  3. Write state file with action=checkpoint after completion
```

### Shared VCS Utility Classes

```
Package: network.ike.plugin.vcs

VcsState.java
  - Record/POJO for state file fields (timestamp, machine, branch, sha, action)
  - readFrom(Path) — parse properties file
  - writeTo(Path, VcsState) — write properties file

VcsOperations.java
  - Constructor takes projectDir and Maven Log
  - currentBranch() — git branch --show-current
  - headSha() — git rev-parse --short=8 HEAD
  - remoteSha(remote, branch) — git ls-remote origin <branch>, extract short SHA
  - isClean() — git status --porcelain, return empty or not
  - fetch(remote) — git fetch --all --quiet
  - resetSoft(ref) — git reset <ref> --quiet (NO --hard)
  - checkout(branch) — git checkout <branch>
  - checkoutNew(branch) — git checkout -b <branch>
  - commit(message) — git commit -m with IKE_VCS_CONTEXT
  - push(remote, branch) — git push with IKE_VCS_CONTEXT
  - deleteBranch(branch) — git branch -d
  - deleteRemoteBranch(remote, branch) — git push origin --delete
  - mergeSquash(branch) — git merge --squash
  - mergeNoFf(branch, message) — git merge --no-ff -m
  - rebase(onto) — git rebase <onto>
  - mergeFfOnly(branch) — git merge --ff-only

  - readVcsState() — Optional<VcsState>
  - writeVcsState(VcsState)

  - needsSync() — compare state file to HEAD
  - sync() — fetch + branch switch if needed + soft reset
  - catchUp() — shared preamble used by all goals

  All methods use ProcessBuilder. IKE_VCS_CONTEXT set in environment.
```

### File Structure

```
ike-maven-plugin/
  src/main/java/network/ike/plugin/
    vcs/
      VcsState.java
      VcsOperations.java
    SetupMojo.java
    SyncMojo.java
    CommitMojo.java
    PushMojo.java
    FeatureFinishSquashMojo.java
    FeatureFinishMergeMojo.java
    FeatureFinishRebaseMojo.java
    # Modified existing:
    VerifyWorkspaceMojo.java        (add VCS checks)
    FeatureStartMojo.java           (add VCS bridge, ws branching, auto-push)
    ReleaseMojo.java                (add catch-up, state file)
    WsCheckpointMojo.java           (add catch-up, state file)
    # Removed:
    FeatureFinishMojo.java          (replaced by three strategy-specific goals)
    FeatureFinishDryRunMojo.java    (replaced by three strategy-specific goals)
  src/main/resources/
    hooks/
      pre-commit
      post-commit
      pre-push
  src/test/java/network/ike/plugin/
    vcs/
      VcsStateTest.java
      VcsOperationsTest.java
```

## Repository Changes

### ike-lab-documents (at ~/ike-dev/ike-lab-documents/)

1. Add `.ike/` directory with a README marker
2. Add `.ike/vcs-state` to `.gitignore`
3. Update getting-started documentation with VCS bridge setup
4. New AsciiDoc topic: IKE VCS Bridge architecture and workflow
   - Problem statement, solution architecture, state file spec
   - Hook descriptions, plugin goals with when-to-use guidance
   - Developer workflow scenarios (happy path, machine switch, forgot to push, etc.)
   - Feature-finish strategy comparison: squash vs merge vs rebase
   - Design decisions and alternatives considered
   - Follow existing topic conventions (semantic linebreaks, topic metadata)

### ike-pipeline (workspace aggregator)

1. Add `.ike/` directory
2. Add `.ike/vcs-state` to `.gitignore`
3. Workspace setup script includes `ike:setup` invocation

## Implementation Order

### Phase 1: State file, hooks, setup

1. Write `VcsState.java` and `VcsOperations.java`
2. Write the three hook scripts as plugin resources
3. Implement `SetupMojo`
4. Test: run `ike:setup`, commit from IntelliJ, verify state file written and auto-push
5. Test: edit state file SHA manually, try to commit, verify block with correct message

### Phase 2: Verify and sync

6. Extend `VerifyWorkspaceMojo` with VCS state checks and diagnostic messages
7. Implement `SyncMojo`
8. Test full cycle: commit on machine A, walk to machine B, verify block, sync, unblock

### Phase 3: Commit and push goals

9. Implement `CommitMojo`
10. Implement `PushMojo`
11. Test catch-up preamble in both

### Phase 4: Feature lifecycle

12. Modify `FeatureStartMojo` — ws branching, catch-up, auto-push, state file
13. Implement `FeatureFinishSquashMojo`
14. Implement `FeatureFinishMergeMojo`
15. Implement `FeatureFinishRebaseMojo`
16. Test pre-push hook blocks branch creation from terminal
17. Test plugin-initiated branch creation succeeds
18. Test squash-merge + branch deletion
19. Test no-ff merge + branch kept alive

### Phase 5: Release and checkpoint integration

20. Modify `ReleaseMojo` — catch-up, state file, IKE_VCS_CONTEXT
21. Modify `WsCheckpointMojo` — catch-up, state file, IKE_VCS_CONTEXT

### Phase 6: Documentation and onboarding

22. Add `.ike/` directory and `.gitignore` entries to ike-lab-documents
23. Add `.ike/` directory and `.gitignore` entries to ws aggregator repos
24. Update getting-started docs
25. Write VCS bridge AsciiDoc topic
26. Onboard Windows developer — test `${HOSTNAME%%.*}` in Git Bash

## Testing Strategy

Unit tests for `VcsState`: read/write round-trip, missing fields, empty file.
Unit tests for `VcsOperations`: mock ProcessBuilder, verify command construction.

Integration tests (manual, two machines with Syncthing):
1. Machine A commits from IntelliJ — state file written, auto-push, Syncthing delivers
2. Machine B tries commit — blocked with correct message
3. Machine B runs `ike:verify` — correct diagnostic for the specific action
4. Machine B runs `ike:sync` — HEAD updated, state file matches
5. Machine B commits — succeeds
6. Machine B tries `git checkout -b foo && git push` — blocked by pre-push
7. Machine B runs `mvnw ike:feature-start -Dfeature=foo` — succeeds, ws branched too
8. Singleton repo (no `.ike/`) — all hooks are no-ops
9. CI clone — `.ike/` present but no state file — hooks pass through
10. Offline push failure — `ike:verify` on other machine reports "push did not complete"
11. Feature-start on machine A — machine B verify shows "feature branch started"
12. Feature-finish-squash — branch deleted, target branch has single commit
13. Feature-finish-merge — branch kept, target branch has merge commit

## Open Items (Deferred)

1. **Multiple remotes.** Origin-only for now. Revisit if fork workflows are adopted.
