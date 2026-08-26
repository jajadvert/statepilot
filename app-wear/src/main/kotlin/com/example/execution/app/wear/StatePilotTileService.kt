package com.example.execution.app.wear

import androidx.wear.tiles.ActionBuilders
import androidx.wear.tiles.DimensionBuilders
import androidx.wear.tiles.EventBuilders
import androidx.wear.tiles.LayoutElementBuilders
import androidx.wear.tiles.ModifiersBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import androidx.wear.tiles.TimelineBuilders
import com.example.execution.wear.cache.WatchDisplayState
import com.example.execution.wear.tile.TileButton
import com.example.execution.wear.tile.TileLayoutModel
import com.example.execution.wear.tile.TileStateMapper
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * Fase 13: Wear Tile — thin adapter. All layout logic lives in the pure
 * TileStateMapper (JVM-tested); this service only renders it and wires
 * action ids to the same command path as notifications.
 */
class StatePilotTileService : TileService() {

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> {
        val model = TileStateMapper.map(StatePilotTileStateHolder.currentDisplayState())
        val tile = TileBuilders.Tile.Builder()
            .setResourcesVersion("1")
            .setTimeline(
                TimelineBuilders.Timeline.Builder()
                    .addTimelineEntry(
                        TimelineBuilders.TimelineEntry.Builder()
                            .setLayout(
                                LayoutElementBuilders.Layout.Builder()
                                    .setRoot(column(model))
                                    .build()
                            )
                            .build()
                    )
                    .build()
            )
            .build()
        return Futures.immediateFuture(tile)
    }

    override fun onTileAddEvent(requestParams: EventBuilders.TileAddEvent) {}

    override fun onTileRemoveEvent(requestParams: EventBuilders.TileRemoveEvent) {}

    private fun column(model: TileLayoutModel): LayoutElementBuilders.LayoutElement {
        val builder = LayoutElementBuilders.Column.Builder()
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setPadding(
                        ModifiersBuilders.Padding.Builder()
                            .setStart(DimensionBuilders.dp(8f))
                            .setEnd(DimensionBuilders.dp(8f))
                            .build()
                    )
                    .build()
            )
        builder.addContent(text(model.title, size = 15f, bold = true))
        model.subtitle?.let { builder.addContent(text(it, size = 12f)) }
        model.buttons.forEach { b -> builder.addContent(button(b)) }
        return builder.build()
    }

    private fun text(value: String, size: Float, bold: Boolean = false): LayoutElementBuilders.LayoutElement =
        LayoutElementBuilders.Text.Builder()
            .setText(value)
            .setFontStyle(
                LayoutElementBuilders.FontStyle.Builder()
                    .setSize(DimensionBuilders.sp(size))
                    .setWeight(if (bold) 700 else 400)
                    .build()
            )
            .build()

    private fun button(btn: TileButton): LayoutElementBuilders.LayoutElement =
        LayoutElementBuilders.Text.Builder()
            .setText("  ${btn.label}  ")
            .setFontStyle(
                LayoutElementBuilders.FontStyle.Builder()
                    .setSize(DimensionBuilders.sp(12f))
                    .setWeight(600)
                    .build()
            )
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setPadding(
                        ModifiersBuilders.Padding.Builder()
                            .setTop(DimensionBuilders.dp(6f))
                            .setBottom(DimensionBuilders.dp(6f))
                            .build()
                    )
                    .setClickable(
                        ModifiersBuilders.Clickable.Builder()
                            .setId(btn.actionId)
                            .setOnClick(
                                ActionBuilders.LaunchAction.Builder()
                                    .setAndroidActivity(
                                        ActionBuilders.AndroidActivity.Builder()
                                            .setPackageName("com.example.execution.wear")
                                            .setClassName("com.example.execution.app.wear.WearMainActivity")
                                            .build()
                                    )
                                    .build()
                            )
                            .build()
                    )
                    .build()
            )
            .build()

    companion object {
        const val TILE_ID = "statepilot_tile"
    }
}

/** In-memory bridge: the app writes the latest display state; the tile reads it. */
object StatePilotTileStateHolder {
    @Volatile
    private var state: WatchDisplayState? = null

    fun update(s: WatchDisplayState) { state = s }
    fun currentDisplayState(): WatchDisplayState =
        state ?: WatchDisplayState(null, fromCache = false, staleSeconds = 0)
}
