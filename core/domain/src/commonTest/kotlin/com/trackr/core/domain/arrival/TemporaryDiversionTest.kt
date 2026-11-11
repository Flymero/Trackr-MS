package com.trackr.core.domain.arrival

import com.trackr.core.model.BusLineDirection
import com.trackr.core.model.TemporaryDiversion
import com.trackr.core.model.diversionNote
import com.trackr.core.model.displayLineNameWithoutDiversion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 掌上公交改道只体现在线路名上（莆田「153路（临时改道）」，2026-11 实测） */
class TemporaryDiversionTest {

    private fun line(name: String) = BusLineDirection(lineId = "mg:0:莆田市:$name:1", name = name, startStop = "A", endStop = "B")

    @Test
    fun detectsFullAndHalfWidthMarkers() {
        assertEquals("临时改道", TemporaryDiversion.noteOf("153路（临时改道）"))
        assertEquals("临时改道", TemporaryDiversion.noteOf("153路(临时改道)"))
        assertEquals("临时改道至汽车站", TemporaryDiversion.noteOf("153路（临时改道至汽车站）"))
    }

    @Test
    fun ordinaryNamesHaveNoNote() {
        assertNull(TemporaryDiversion.noteOf("153路"))
        assertNull(TemporaryDiversion.noteOf("擢英中学（学园校区）"))
        assertNull(TemporaryDiversion.noteOf("D153路"))
    }

    @Test
    fun stripRemovesOnlyTheMarker() {
        assertEquals("153路", TemporaryDiversion.strip("153路（临时改道）"))
        assertEquals("153路", TemporaryDiversion.strip("153路 (临时改道) "))
        assertEquals("夜班车（区间）", TemporaryDiversion.strip("夜班车（区间）"))
    }

    @Test
    fun lineExtensions() {
        assertEquals("临时改道", line("153路（临时改道）").diversionNote)
        assertEquals("153路", line("153路（临时改道）").displayLineNameWithoutDiversion)
        assertNull(line("153路").diversionNote)
        assertEquals("153路", line("153路").displayLineNameWithoutDiversion)
    }
}
