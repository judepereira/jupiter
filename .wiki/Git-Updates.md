# Git Updates

Jupiter can pull workspaces manually or keep them updated automatically. In both cases it sticks to fast-forward-only
pulls rather than making merge decisions behind your back.

## Manual pull

Use the pull control on the active workspace. Jupiter coordinates the operation so overlapping pulls don’t race each
other.

## Automatic updates

When enabled, Jupiter checks workspaces after startup and then periodically for new commits. Update passes do not
overlap.

## What exactly gets run?

Once Jupiter has resolved the branch and upstream, it uses:

```bash
git pull --ff-only
```

If there is no upstream, Jupiter prefers `origin`. If there is no `origin` but exactly one remote exists, it uses that
instead - but only when the same branch exists remotely.

No remote, several ambiguous remotes, or no matching branch? Jupiter skips the workspace rather than guessing.

## Notifications and failures

When new commits arrive, Jupiter writes an informational message into the most recently opened visible primary session
for that workspace. Pull failures are surfaced rather than silently changing strategy.
