package com.albustech.orbit.complication

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.MonochromaticImageComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.data.SmallImage
import androidx.wear.watchface.complications.data.SmallImageComplicationData
import androidx.wear.watchface.complications.data.SmallImageType
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import com.albustech.orbit.MainActivity
import com.albustech.orbit.R

/** A watch-face complication that opens Orbit. Static data; never needs updating. */
class LaunchComplicationService : SuspendingComplicationDataSourceService() {

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? =
        data(request.complicationType)

    override fun getPreviewData(type: ComplicationType): ComplicationData? = data(type)

    private fun data(type: ComplicationType): ComplicationData? {
        val tap = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val label = getString(R.string.complication_label)
        val description = PlainComplicationText.Builder(label).build()
        val icon = Icon.createWithResource(this, R.drawable.ic_complication)
        return when (type) {
            ComplicationType.SHORT_TEXT ->
                ShortTextComplicationData.Builder(PlainComplicationText.Builder(label).build(), description)
                    .setMonochromaticImage(MonochromaticImage.Builder(icon).build())
                    .setTapAction(tap)
                    .build()
            ComplicationType.MONOCHROMATIC_IMAGE ->
                MonochromaticImageComplicationData.Builder(MonochromaticImage.Builder(icon).build(), description)
                    .setTapAction(tap)
                    .build()
            ComplicationType.SMALL_IMAGE ->
                SmallImageComplicationData.Builder(SmallImage.Builder(icon, SmallImageType.ICON).build(), description)
                    .setTapAction(tap)
                    .build()
            else -> null
        }
    }
}
