package com.albustech.orbit.tile

import android.content.Context
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.LayoutElementBuilders.LayoutElement
import androidx.wear.protolayout.ModifiersBuilders.Clickable
import androidx.wear.protolayout.material3.MaterialScope
import androidx.wear.protolayout.material3.buttonGroup
import androidx.wear.protolayout.material3.primaryLayout
import androidx.wear.protolayout.material3.text
import androidx.wear.protolayout.material3.textButton
import androidx.wear.protolayout.material3.textEdgeButton
import androidx.wear.protolayout.material3.titleCard
import androidx.wear.protolayout.modifiers.clickable
import androidx.wear.protolayout.types.layoutString
import androidx.wear.tiles.Material3TileService
import androidx.wear.tiles.RequestBuilders.TileRequest
import androidx.wear.tiles.TileBuilders.Tile
import androidx.wear.tiles.TileService
import androidx.wear.tiles.timeline
import androidx.wear.tiles.timelineEntry
import androidx.wear.tiles.tile
import com.albustech.orbit.MainActivity
import com.albustech.orbit.OrbitApp
import com.albustech.orbit.R
import com.albustech.orbit.data.Suggestions
import com.albustech.orbit.data.db.Bookmark
import com.albustech.orbit.data.db.ReadingPosition

/**
 * The Orbit tile: the last page read (with its page number in Reader) and three bookmarks,
 * each one tap from reopening, plus an "Open" edge button.
 */
class OrbitTileService : Material3TileService() {

    override suspend fun MaterialScope.tileResponse(requestParams: TileRequest): Tile {
        val repo = (application as OrbitApp).repository
        val last = repo.latestPosition()
        val bookmarks = repo.recentBookmarks(BOOKMARKS)
        val layout = primaryLayout(
            titleSlot = { text(getString(R.string.tile_label).layoutString) },
            mainSlot = { mainContent(last, bookmarks) },
            bottomSlot = {
                textEdgeButton(onClick = launch(this@OrbitTileService, null, "open")) {
                    text(getString(R.string.action_open).layoutString)
                }
            },
        )
        return tile(timeline(timelineEntry(layout)))
    }

    private fun MaterialScope.mainContent(last: ReadingPosition?, bookmarks: List<Bookmark>): LayoutElement {
        val column = LayoutElementBuilders.Column.Builder().setWidth(expand())
        val card = if (last != null) {
            val where = if (last.reader && last.pageCount > 0) {
                getString(R.string.page_of, last.page + 1, last.pageCount)
            } else {
                Suggestions.displayUrl(last.url)
            }
            titleCard(
                onClick = launch(this@OrbitTileService, last.url, "last"),
                title = { text((last.title?.takeIf { it.isNotBlank() } ?: Suggestions.displayUrl(last.url)).layoutString, maxLines = 2) },
                content = { text(where.layoutString, maxLines = 1) },
            )
        } else {
            text(getString(R.string.tile_nothing).layoutString)
        }
        column.addContent(card)
        if (bookmarks.isNotEmpty()) {
            column.addContent(LayoutElementBuilders.Spacer.Builder().setHeight(androidx.wear.protolayout.DimensionBuilders.dp(4f)).build())
            column.addContent(
                buttonGroup(height = androidx.wear.protolayout.DimensionBuilders.wrap()) {
                    bookmarks.forEachIndexed { i, b ->
                        buttonGroupItem {
                            textButton(
                                onClick = launch(this@OrbitTileService, b.url, "bm$i"),
                                labelContent = { text(shortName(b).layoutString, maxLines = 1) },
                                width = expand(),
                            )
                        }
                    }
                },
            )
        }
        return column.build()
    }

    private fun shortName(b: Bookmark): String =
        (Suggestions.siteKey(b.url)?.substringBefore('.') ?: b.title).take(SHORT_NAME)

    companion object {
        private const val BOOKMARKS = 3
        private const val SHORT_NAME = 8

        /** Opens Orbit, optionally straight to [url]. */
        fun launch(context: Context, url: String?, id: String): Clickable {
            val activity = ActionBuilders.AndroidActivity.Builder()
                .setPackageName(context.packageName)
                .setClassName(MainActivity::class.java.name)
                .apply {
                    if (url != null) {
                        addKeyToExtraMapping(
                            MainActivity.EXTRA_OPEN_URL,
                            ActionBuilders.AndroidStringExtra.Builder().setValue(url).build(),
                        )
                    }
                }
                .build()
            return clickable(ActionBuilders.LaunchAction.Builder().setAndroidActivity(activity).build(), id = id)
        }

        /** Ask the system to refresh the tile (after reading or bookmarking). */
        fun requestUpdate(context: Context) {
            TileService.getUpdater(context).requestUpdate(OrbitTileService::class.java)
        }
    }
}
