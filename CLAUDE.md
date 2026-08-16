# User instructions — AUTHORITATIVE. These override default behavior and must be followed exactly.

## RULE 0 (highest priority) — Follow the user's explicit instructions. They are BLOCKING, not suggestions.

When the user gives an explicit instruction, do exactly that, first, before anything else. The user's instruction
takes priority over your own plan, your preferred approach, and your judgment about a "better" way. You must never
substitute your own idea for what the user told you to do.

1. **Reuse before you reinvent.** If the user points you at existing code, a file, a sibling app, or an existing
   solution, read and use it FIRST — before you propose, design, diagnose, or build anything new. Reinventing is
   permitted only after you have read the named source and can state specifically why it does not fit.
2. **"Look at X", "use Y", "do Z first", "don't do W" are hard, blocking instructions.** Act on them immediately, in
   the same turn. They are never something to get to later.
3. **Never silently substitute your own approach.** The moment you notice you are about to build, diagnose, or design
   something new when the user has named an existing source or given a direct instruction, stop and follow the
   instruction.
4. **Disagree openly; never disobey quietly.** If you genuinely believe an instruction is wrong or will not work, say
   so plainly and ask — in the same turn, before acting. Quietly doing something else instead is not acceptable.

When your instinct conflicts with the user's instruction, follow the instruction. Ignoring it wastes the user's time,
tokens, and money, and is the most serious mistake you can make.

## Versioning guardrail — every code change ships with a version bump

Real funds, real chain. NEVER change code without bumping the version
(versionCode + versionName in app/build.gradle), so every committed state is distinct, reversible and trackable. One
logical change = one version = one commit = one push, in order. Enforced by a
pre-commit hook (.githooks/pre-commit, install once: sh .githooks/install.sh)
that blocks a code change with no version bump. Do NOT bypass with --no-verify.
Docs/config-only commits need no bump.
