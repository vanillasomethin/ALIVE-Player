# SMIL Playback Architecture — Reference Notes (garlic-player study)

Original written summary of garlic-player's SMIL playback design, produced by reading its
source in a scratch directory outside this repo. **No code was copied, adapted, or vendored**
— garlic-player is AGPL-3.0; these notes capture architectural ideas only, and ALIVE's
`SmilSequencer` is implemented independently against this prose spec.

## 1. How SMIL elements become a playback structure

garlic-player parses the SMIL document in two phases:

- **Preload phase.** A body parser walks the XML tree once. For every element an element
  factory creates a typed object (seq / par / excl containers; image / video / audio / web
  media leaves) and hands it to its parent container, which stores its children as an ordered
  DOM-element list plus a hash from element id → object. Media elements additionally register
  with a media manager (which owns download/caching) during this same walk. Timing attributes
  (`dur`, `repeatCount`, `begin`, `end`) are parsed per element into timer objects at this point.
- **Playback phase.** Nothing is "compiled" into a flat queue. Playback is a *live traversal*
  of the object tree, driven by signals: a container starts, activates children, children
  finish, the parent's `next()` decides what happens.

Key insight: the queue is implicit. Each container keeps an `activated_childs` working list
(copied from its ordered child list each time the container (re)starts, optionally shuffled),
and consumes it front-first. When the working list empties, the container's own duration/repeat
logic decides whether to refill it (repeat) or report completion to *its* parent.

## 2. Container semantics

- **`<seq>` (sequential — our playlist equivalent).** On start, it copies all children into the
  working list and starts only the first. When a child ends, `next()` removes it and starts the
  new front element. When the working list is empty, the seq checks its own `dur`/`repeatCount`:
  repeat → refill and start over; otherwise → signal completion to the parent. **A nested seq is
  simply a child element**: the parent starts it, it plays its whole child sequence (subject to
  its own timing attributes), then reports finished, and the parent moves on. So nesting is
  depth-first, play-fully-per-visit — a nested playlist does NOT interleave one item per parent
  loop.
- **`<par>` (parallel).** Same structure, but `start()` activates *all* children at once, and
  `next()` only completes when no active children remain (with an `endsync` attribute able to
  cut the whole par short when the first/a named child ends). This is how multi-zone screens
  play: one par with one child chain per region.
- **`<excl>`/`priorityClass`** add interruption/priority (one child at a time, higher priority
  preempts) — not needed for ALIVE's flat-priority model, noted only for completeness.

## 3. Regions / zones

Layout is parsed from the SMIL head into a region list — normalized rectangles (top/left/
width/height as fractions of the screen, z-index, background). There is always a default
full-screen region ("screen", 0/0/1×1). Media elements reference a region by name and the
render layer places each media widget into its region's rectangle. Playback logic and layout
are fully decoupled: containers don't know about regions at all.

## 4. Durations, transitions, scheduling

- Every element owns optional begin/end/dur timers. An image's display time is its `dur`;
  a video's "intrinsic duration" is its natural media length unless `dur` overrides it.
- Completion cascades: media end (timer or decoder EOF) → `finishedSimpleDuration` → element's
  repeat check (`repeatCount` decrements an internal counter; `indefinite` never exhausts) →
  if exhausted, active duration ends and the parent's `next()` runs.
- Wall-clock scheduling (`begin="wallclock(...)"`) arms real-time timers that trigger
  containers — the schedule lives inside the same document rather than a separate layer.
  (ALIVE keeps its separate Schedule → timeline model; we don't adopt this.)

## 5. Resilience patterns worth noting

- **Missing/undownloaded media is skipped, not fatal**: an element whose file isn't available
  completes via a deferred ~100 ms one-shot timer instead of synchronously. The delay is
  deliberate — a broken lonely element inside `repeatCount="indefinite"` would otherwise
  recurse start→fail→next→start on the same call stack and eventually crash. **Takeaway for
  ALIVE: advancing past a failed item must go through the event loop (post/delay), never
  direct recursion.** (ALIVE's `PlaybackEngine` already advances via `Handler.postDelayed`,
  including a 2 s delay on error — same principle.)
- Downloads happen out-of-band (media manager); playback always renders the last complete
  local copy and picks up new files on the next loop — never blocks on the network.
- A container restart interrupt (new SMIL arriving) force-stops all active children and
  rebuilds working lists — content updates are a controlled teardown, not in-place mutation.

## 6. What ALIVE adopts vs. rejects

| garlic concept | ALIVE decision |
|---|---|
| Implicit queue via per-container working lists, depth-first traversal | **Adopt** — `SmilSequencer` walks a node tree with a per-container cursor, no flattening at play time |
| Nested seq plays fully per parent visit | **Adopt** — matches SMIL; nested "Internal" playlist plays all its items, then the Master continues |
| Skip-broken-media via deferred advance | **Adopt** — already present in `PlaybackEngine`; sequencer treats an unplayable item as "advance" |
| repeatCount per container | **Defer** — Master loops indefinitely (existing behaviour); per-playlist repeat counts are a follow-up |
| par/regions multi-zone | **Defer** — ALIVE's zone model (`Composition`) is admin-side only today |
| In-document wall-clock scheduling | **Reject** — ALIVE keeps its Schedule/timeline + 72 h plan window model |
