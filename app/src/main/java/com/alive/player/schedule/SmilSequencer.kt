package com.alive.player.schedule

/**
 * Depth-first traversal of a nested (Master → Internal) playlist tree, per the SMIL
 * <seq>-in-<seq> semantics documented in docs/smil-reference-notes.md: a nested playlist
 * plays ALL its items on each visit, then the parent sequence continues; the Master loops
 * indefinitely (matching the flat plan's round-robin behaviour).
 *
 * The tree is walked with live per-container cursors (a frame stack) rather than being
 * pre-flattened, so per-container state survives across calls — the groundwork for
 * per-playlist repeat/shuffle/interleave semantics later without changing callers.
 *
 * Written independently against the prose spec above; no third-party player code was
 * consulted for this implementation. Pure Kotlin, no Android dependencies — unit-testable
 * on the JVM.
 *
 * Not thread-safe; PlaybackEngine only calls it from the main thread.
 */
class SmilSequencer(private val roots: List<PlanNode>) {

    private class Frame(val nodes: List<PlanNode>, var index: Int = 0)

    private val stack = ArrayDeque<Frame>()

    // A tree with no reachable media must yield null instead of spinning forever.
    private val hasMedia: Boolean = containsMedia(roots)

    // Defensive cap — the server already limits nesting depth; a deeper tree is
    // truncated rather than trusted.
    private companion object {
        const val MAX_DEPTH = 8
    }

    private fun containsMedia(nodes: List<PlanNode>, depth: Int = 1): Boolean {
        if (depth > MAX_DEPTH) return false
        return nodes.any { node ->
            when (node) {
                is PlanNode.Media -> true
                is PlanNode.Nested -> containsMedia(node.children, depth + 1)
            }
        }
    }

    /**
     * Returns the next item in play order, wrapping to the start of the Master when the
     * traversal completes. Null only when the tree contains no media at all.
     */
    fun next(): PlanItem? {
        if (!hasMedia) return null
        while (true) {
            if (stack.isEmpty()) stack.addLast(Frame(roots))
            val frame = stack.last()
            if (frame.index >= frame.nodes.size) {
                stack.removeLast()
                continue // parent frame's cursor already points past this nested node
            }
            val node = frame.nodes[frame.index]
            frame.index++
            when (node) {
                is PlanNode.Media -> return node.item
                is PlanNode.Nested ->
                    // Empty or over-deep children: skip the node entirely. hasMedia
                    // guarantees some Media node is reachable, so this loop terminates.
                    if (node.children.isNotEmpty() && stack.size < MAX_DEPTH) {
                        stack.addLast(Frame(node.children))
                    }
            }
        }
    }
}
