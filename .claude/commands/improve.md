---
name: improve
description: Brainstorm improvements, implement the best ones, test, and commit — fully autonomous
argument-hint: [focus area] e.g. "TCP commands", "agent tooling", "UI", or leave blank for general
allowed-tools:
  - Read
  - Write
  - Edit
  - Glob
  - Grep
  - Bash
  - Agent
---

You are improving the ImageJAI project autonomously. The user may be away from their computer.

## Your process

### 1. Brainstorm
- Read CLAUDE.md, agent/CLAUDE.md, and agent/learnings.md for full project context
- If a focus area was given ("$ARGUMENTS"), brainstorm improvements in that area
- Otherwise brainstorm broadly: TCP commands, agent Python tools, UI, safety, workflows
- Filter ideas by: actually implementable in Java 8, useful for real microscopy, not already covered
- Pick the top 3-5 highest-impact, most feasible ideas

### 2. Implement
For each improvement:
- Write the code (Java for TCP/plugin, Python for agent tools)
- Follow all constraints: Java 8 only, no external deps, EDT safety
- Build after Java changes: `cd "PROJECT_ROOT" && mvn clean package -q`
- Deploy JAR: `cp target/imagej-ai-0.1.0-SNAPSHOT.jar "C:\Users\jamie\OneDrive - Imperial College London\ImageJ\Fiji.app\plugins\"`

### 3. Test
- For Python tools: run them with a quick self-test
- For TCP commands: they need a Fiji restart to test live, but verify they compile
- For agent context: verify the docs are accurate and complete

### 4. Commit & push after EACH change
- `git add` only the relevant files
- Write a clear commit message describing what and why
- `git push` so the user can roll back individual changes if needed
- Do NOT batch multiple unrelated changes into one commit

### 5. Update context
- Update agent/CLAUDE.md with any new commands or tools
- Update agent/learnings.md with anything discovered
- Update agent/ij.py if new TCP commands were added

## Rules
- Be completely autonomous — don't ask for confirmation
- Commit and push between each logical change for rollback safety
- No Co-Authored-By lines on commits
- Java 8 only (no var, no records, no lambdas)
- Deploy to OneDrive Fiji, NOT Dropbox Fiji
- If something doesn't compile, fix it before committing
