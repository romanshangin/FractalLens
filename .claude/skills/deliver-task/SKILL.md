---
name: deliver-task
description: >
  Publish a reviewed and, when required, independently verified FractalLens task by committing
  the intended changes, pushing the task branch, and creating or updating its
  pull request. Use only for the delivery stage after required review and
  verification gates have passed. Do not implement, review, verify, merge, or
  close pull requests.
disable-model-invocation: true
---

# deliver-task (Claude Code entry point)

The source of truth for this skill is `.agents/skills/deliver-task/SKILL.md`, shared
with other agents. Read that file in full before acting and follow it exactly.
Resolve relative links in it against `.agents/skills/deliver-task/`. Do not edit this
wrapper to change the workflow; edit the shared file instead and keep this
description in sync with its frontmatter.
