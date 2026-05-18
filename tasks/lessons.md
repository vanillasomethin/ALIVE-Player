# Lessons Learned

## Layout ID Mismatches
**Pattern:** Agent writes Kotlin referencing view IDs that don't exist in the layout XML.
**Rule:** Always read the layout XML *and* the Activity/Fragment together. Grep for every `R.id.*` reference and confirm each exists in the layout before committing.

## Invalid Theme Attributes
**Pattern:** Used `android:windowKeepScreenOn` in a `<style>` — not a valid theme attribute, causes resource linking failure.
**Rule:** Window flags belong in code (`window.addFlags(...)`), not in `themes.xml`. Check the Android attribute namespace before adding any `<item>` to a style.

## Working Tree vs Committed Code
**Pattern:** Background agent modified a file that was already correctly committed, introducing broken view ID references in the working tree.
**Rule:** After any agent run, `git diff` the working tree against HEAD. If working tree changes break things (e.g. reference non-existent IDs), restore with `git restore` rather than committing the broken version.

## Auto-Claim vs Manual Register
**Pattern:** Auto-claiming on screen open gave the user no chance to name the device or verify the Studio-assigned ID.
**Rule:** For registration flows, always pause for user input (name) and confirmation (show server-assigned ID) before transitioning. Silent auto-flows are appropriate only for background operations.

## One-Shot Heartbeat Chain
**Pattern:** `HeartbeatWorker` re-scheduled itself as a one-shot — broke if the process was killed.
**Rule:** Use `PeriodicWorkRequest` for any recurring background task. Self-rescheduling one-shots are fragile and do not survive process death.
