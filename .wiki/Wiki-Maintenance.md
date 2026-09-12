# Wiki Maintenance

The `.wiki/` directory in the main Jupiter repository is the source of truth. GitHub still renders a separate `judepereira/jupiter.wiki.git` repository, so the workflow simply mirrors one into the other.

## One-time GitHub setup

At the moment GitHub requires the wiki repository to exist before it can be cloned. So, once per repository:

1. Open repository **Settings**.
2. Enable **Wikis** under repository features.
3. Open the Wiki tab.
4. Create the initial `Home` page.

That first page initializes the separate wiki Git repository.

## Publishing

`.github/workflows/publish-wiki.yml` runs on pushes to `main` and can also be started manually from `main` only. It runs on macOS so browser and font rendering match the intended screenshot environment.

Before publishing, it generates screenshots in the ignored `target/wiki-generated-screenshots` directory. The Java synchronizer compares PNG dimensions and decoded pixels, so PNG metadata or encoding differences do not create commits. Genuine additions, removals, or pixel changes are copied into `.wiki/images` and committed to `main` by `github-actions[bot]`; no-op refreshes do not create a commit. The workflow checks that `origin/main` has not advanced and stages only `.wiki/images/*.png`, including deletions, before using a normal non-force push.

After that possible commit, it clones the wiki repository, mirrors `.wiki/` with deletion semantics, commits only when something changed, and pushes the mirrored result with `--force-with-lease` so a concurrent wiki update fails rather than being erased. It resolves the actual current source commit for the wiki commit message rather than assuming the triggering SHA.

The workflow uses the repository-scoped `GITHUB_TOKEN` with `contents: write` for the source commit/push and wiki publication. A push made with this token does not start another workflow run, and the main-only guard is defense in depth. Repository branch protection must permit the workflow token to push `main`; otherwise the screenshot commit fails and the wiki is not published from a stale tree.

## Don’t edit the rendered wiki directly

Once automation is on, make changes in `.wiki/*.md` instead.

A direct edit in the GitHub wiki may look fine for a while, but the next `main` publish can overwrite it. Two sources of truth are rarely fun.

## Page names

Links use GitHub-Wiki-friendly filenames, for example `Getting-Started.md` with `[Getting Started](Getting-Started)`.
