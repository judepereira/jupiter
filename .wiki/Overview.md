# Overview

The easiest way to understand Jupiter is to think in three layers: project, workspace, session.

**Project** — an existing source directory you’ve registered with Jupiter.

**Workspace** — a Git worktree tied to a branch inside that project.

**Session** — a persisted coding conversation operating inside one workspace.

Agents sit on top of that. They’re Markdown definitions with a default model, reasoning level, prompt, and tool permissions. A primary agent can also delegate work to persisted subagent sessions.

## Browser-first, not browser-owned

The browser is only the client. Jupiter keeps application state on the server, so you can reload, disconnect, or move between devices without making browser storage the source of truth.

Chat and UI events use Server-Sent Events; terminals use WebSockets.

## Models

v1 discovers OpenAI GPT-5-family models from the configured model catalogue. The harness has provider abstractions, but the current user-facing implementation is OpenAI-only.

## One important security point

Jupiter runs against real source trees and real shell processes. It has careful credential handling and command-environment controls, but it is **not** an operating-system sandbox.

If you’re exposing Jupiter remotely, read [Security Model](Security-Model) first.
