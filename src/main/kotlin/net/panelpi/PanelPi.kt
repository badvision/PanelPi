package net.panelpi

import mu.KLogging
import net.panelpi.duetwifi.DuetWifi
import net.panelpi.views.MainView
import tornadofx.*

class PanelPiApp : App(MainView::class) {
    companion object : KLogging()

    init {
        Thread.setDefaultUncaughtExceptionHandler { _, e ->
            logger.error(e) { "Uncaught exception." }
            DuetWifi.instance.logDuetData()
        }
    }
}
