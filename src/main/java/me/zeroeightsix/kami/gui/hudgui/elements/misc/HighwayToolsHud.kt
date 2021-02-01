package me.zeroeightsix.kami.gui.hudgui.elements.misc

import me.zeroeightsix.kami.event.SafeClientEvent
import me.zeroeightsix.kami.gui.hudgui.LabelHud
import me.zeroeightsix.kami.module.modules.misc.HighwayTools.gatherStatistics

object HighwayToolsHud : LabelHud(
    name = "HighwayTools",
    category = Category.MISC,
    description = "Hud for HighwayTools module"
) {
    override fun SafeClientEvent.updateText() {
        gatherStatistics(displayText)
    }
}