The normal development loop stays on one screen: projects, workspaces, sessions, reviews, terminals.

![Jupiter desktop interface](images/interface-desktop.png)

## Project Bar

Projects sit across the top. Agents continue to run in the background in other projects.

## Workspace and Session Rail

The left rail contains Git workspaces and their sessions. It also shows unread, running, and failed activity.

On smaller screens this turns into the mobile navigation rail rather than squeezing the desktop layout into oblivion.

![Jupiter mobile interface](images/interface-mobile.png)

## Chat

The centre panel streams assistant text and tool activity. The composer is also where you choose the agent, model, and
thinking level for the next turn.

## Review

The review panel can show either files attributed to the current session or the workspace’s current Git changes.

## Terminal

The bottom panel contains one or more terminals for the active workspace.

## Settings and Notifications

Project environment, MCP servers, lifecycle hooks, Git updates, usage, and provider connection settings live in
**Settings**.

Warnings and errors appear as system balloons, so failures stay visible in the interface.

## Keyboard Shortcuts

|        Shortcut        |          Action           |
|------------------------|---------------------------|
| `Ctrl` + `` ` ``       | Toggle the terminal panel |
| `Meta` + `.`           | Cycle the selected agent  |
| `Meta` + `Shift` + `D` | Cycle the thinking level  |

On macOS, `Meta` is the Command key.
