package dev.riddle.magicpaper.paperui

import dev.riddle.magicpaper.paper.PageGeometry
import java.util.concurrent.atomic.AtomicReference

/** Activity-independent boundary between current window layout and turn orchestration. */
interface PageGeometryPort {
    fun snapshot(): PageGeometry?
    fun update(geometry: PageGeometry)
}

class AtomicPageGeometryPort(initial: PageGeometry? = null) : PageGeometryPort {
    private val current = AtomicReference(initial)

    override fun snapshot(): PageGeometry? = current.get()
    override fun update(geometry: PageGeometry) = current.set(geometry)
}
