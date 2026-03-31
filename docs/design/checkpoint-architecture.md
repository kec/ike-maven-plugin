# Checkpoint Architecture

## Goal Hierarchy

```
ike:ws-checkpoint            — workspace orchestrator (user-facing)
ike:ws-checkpoint-dry-run    — dry-run wrapper for ws-checkpoint (user-facing)
ike:checkpoint               — per-component engine (primarily internal)
ike:checkpoint-dry-run       — dry-run wrapper for checkpoint (user-facing)
```

## How It Works

`ike:ws-checkpoint` iterates workspace components in **topological order**
(dependencies before dependents) and invokes `ike:checkpoint` in each
component's directory. Each component is independently built, tagged, and
deployed to Nexus. After all components succeed, a YAML file recording the
checkpoint coordinates is written to `checkpoints/` in the workspace root.

### Component version derivation

`ike:checkpoint` derives the checkpoint version automatically from the
current SNAPSHOT version and the current date — no user input required:

```
1.127.2-SNAPSHOT  →  1.127.2-checkpoint.2026-03-30.1
```

If a tag for that date already exists, the sequence auto-increments:
`.1`, `.2`, etc.

### Tags created

Each component gets a git tag of the form:

```
checkpoint/1.127.2-checkpoint.2026-03-30.1
```

This tag is pushed to origin and points to the version-bump commit
(before the SNAPSHOT-restore commit).

### Checkpoint YAML

The workspace-level YAML records the immutable checkpoint coordinates
for all components:

```yaml
checkpoint:
  name: "sprint-42"
  created: "2026-03-30T23:00:00Z"
  components:
    tinkar-core:
      version: "1.127.2-checkpoint.2026-03-30.1"
      tag: "checkpoint/1.127.2-checkpoint.2026-03-30.1"
      sha: "abc1234..."
```

## Design Notes / Future Work

- **`ike:checkpoint` visibility**: Currently user-facing but primarily
  intended as the per-component engine. Consider whether to hide it from
  `ike:help` output in a future release.

- **Selective component checkpointing**: Add `-Dcomponents=tinkar-core,komet`
  to checkpoint a subset of components (useful for hotfixes).

- **Push control**: `ike:checkpoint` currently pushes if origin exists.
  Add `-DskipPush=true` for offline/CI environments where push is handled
  separately.

- **Knowledge-source checkpoints**: Components of type `knowledge-source`
  need a composite checkpoint: git-tag + view-coordinate from the Tinkar
  runtime. The YAML has a placeholder `TODO: add view-coordinate` for these.
