package com.alive.player.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SmilSequencerTest {

    private fun media(id: String) = PlanNode.Media(
        PlanItem(contentVersionId = id, durationMs = 1_000, type = "image", uri = "file:///$id")
    )

    private fun nested(id: String, vararg children: PlanNode) =
        PlanNode.Nested(playlistId = id, name = id, children = children.toList())

    private fun take(seq: SmilSequencer, n: Int): List<String> =
        (1..n).map { seq.next()!!.contentVersionId }

    @Test
    fun flatListLoopsInOrder() {
        val seq = SmilSequencer(listOf(media("a"), media("b"), media("c")))
        assertEquals(listOf("a", "b", "c", "a", "b", "c", "a"), take(seq, 7))
    }

    @Test
    fun nestedPlaylistPlaysFullyPerVisit() {
        // Master: [a, Internal[x, y], b] — SMIL seq-in-seq: Internal plays x,y in full,
        // then the Master continues with b.
        val seq = SmilSequencer(listOf(media("a"), nested("p1", media("x"), media("y")), media("b")))
        assertEquals(listOf("a", "x", "y", "b", "a", "x", "y", "b"), take(seq, 8))
    }

    @Test
    fun threeLevelNestingIsDepthFirst() {
        val seq = SmilSequencer(
            listOf(
                media("a"),
                nested("p1", media("x"), nested("p2", media("m"), media("n")), media("y")),
            )
        )
        assertEquals(listOf("a", "x", "m", "n", "y", "a", "x"), take(seq, 7))
    }

    @Test
    fun emptyNestedPlaylistIsSkipped() {
        val seq = SmilSequencer(listOf(media("a"), nested("p1"), media("b")))
        assertEquals(listOf("a", "b", "a", "b"), take(seq, 4))
    }

    @Test
    fun treeWithoutMediaYieldsNull() {
        val seq = SmilSequencer(listOf(nested("p1"), nested("p2", nested("p3"))))
        assertNull(seq.next())
        assertNull(seq.next()) // stays null, never spins
    }

    @Test
    fun emptyRootsYieldNull() {
        assertNull(SmilSequencer(emptyList()).next())
    }

    @Test
    fun traversalMatchesDepthFirstFlattening() {
        // The sequencer's order must equal the server's flattened `items` list — that is
        // what keeps nesting-aware and legacy players in identical play order.
        val roots = listOf(
            nested("p1", media("a"), media("b")),
            media("c"),
            nested("p2", nested("p3", media("d")), media("e")),
        )
        fun flatten(nodes: List<PlanNode>): List<String> = nodes.flatMap {
            when (it) {
                is PlanNode.Media -> listOf(it.item.contentVersionId)
                is PlanNode.Nested -> flatten(it.children)
            }
        }
        val expected = flatten(roots)
        assertEquals(expected, take(SmilSequencer(roots), expected.size))
    }

    @Test
    fun cursorsPersistAcrossCalls() {
        val seq = SmilSequencer(listOf(nested("p1", media("x"), media("y")), media("b")))
        assertEquals("x", seq.next()!!.contentVersionId)
        // Mid-nested-playlist: the next call resumes inside p1, not from the Master top.
        assertEquals("y", seq.next()!!.contentVersionId)
        assertEquals("b", seq.next()!!.contentVersionId)
    }
}
