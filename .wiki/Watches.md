# Watches

Watches are project-owned checks configured in **Settings > Watches**. Each definition has a prompt, interval (60-86400
seconds), evaluator and action agents, and a PROMPT action from the workspace catalog. The interval is limited to 60
seconds through 86400 seconds (24 hours).

A watch is not active until it is enabled for a specific visible primary session. Sessions can be selected
independently, including inactive sessions. A watch only evaluates while the session has qualifying activity from the
same agent and within the 72-hour activity window; automatic evaluation does not extend that window. The WATCHES
bottom-bar button shows the latest 100 persisted runs for the current session, including status, timestamps, errors, and
actionable results. Only PROMPT actions are accepted; slash commands and automatic actions are not supported.
