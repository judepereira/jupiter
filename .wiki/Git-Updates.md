Jupiter can pull workspaces manually or keep them updated automatically. In both cases, it sticks to fast-forward-only
pulls rather than making merge decisions behind your back.

## Manual Pull

Use the pull control on the active workspace, found in the top navigation bar, on the right side.

## Automatic Updates

When enabled, Jupiter checks workspaces after startup and then periodically for new commits.

## What Exactly Gets Run?

Once Jupiter has resolved the branch and upstream, it uses:

```bash
git pull --ff-only
```

If there is no upstream, Jupiter prefers `origin`. If there is no `origin`, but exactly one remote exists, it uses that
instead - but only when the same branch exists remotely.

No remote, several ambiguous remotes, or no matching branch? Jupiter skips the workspace rather than guessing.

## Notifications and Failures

When new commits arrive, Jupiter writes an informational message into the most recently opened visible primary session
for that workspace. Pull failures are surfaced rather than silently changing strategy.
