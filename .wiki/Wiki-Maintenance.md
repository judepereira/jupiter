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

`.github/workflows/publish-wiki.yml` runs on pushes to `main` and can also be started manually.

It checks out the main repository, clones the wiki repository, mirrors `.wiki/` with deletion semantics, commits only when something changed, and force-pushes the mirrored result.

The workflow uses the repository-scoped `GITHUB_TOKEN` with `contents: write`.

## Don’t edit the rendered wiki directly

Once automation is on, make changes in `.wiki/*.md` instead.

A direct edit in the GitHub wiki may look fine for a while, but the next `main` publish can overwrite it. Two sources of truth are rarely fun.

## Page names

Links use GitHub-Wiki-friendly filenames, for example `Getting-Started.md` with `[Getting Started](Getting-Started)`.
