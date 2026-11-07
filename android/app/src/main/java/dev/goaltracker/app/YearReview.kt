package dev.goaltracker.app

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import dev.goaltracker.core.YearReview
import java.io.File
import java.io.IOException
import java.time.LocalDate

/** The year's numbers as plain lines, used on the card, in the image and for screen readers. */
private fun YearReview.lines(): List<String> = buildList {
    add("${countOf(activeDays.toInt(), "active day")}, longest run ${countOf(longestRun.toInt(), "day")}")
    add("${countOf(logs.toInt(), "check-in")} and ${countOf(targetsMet.toInt(), "target")} met")
    if (tasksDone > 0u) add(countOf(tasksDone.toInt(), "task") + " done")
    add("$xp XP earned" + if (titanDays > 0u) ", ${countOf(titanDays.toInt(), "Titan day")}" else "")
    if (treatsWon + treatsBought > 0u) {
        add(countOf(treatsWon.toInt(), "treat") + " won" + if (treatsBought > 0u) ", $treatsBought bought" else "")
    }
    topTitle?.let { add("Most logged: $it (${countOf(topLogs.toInt(), "time")})") }
}

/**
 * Year in review: this year so far, or last year. Share makes an image of the card.
 * Day notes and moods are never in it, because the image can leave the phone.
 */
@Composable
fun YearReviewCard(controller: AppController) {
    val thisYear = remember(controller.today) { LocalDate.parse(controller.today).year }
    var year by remember(thisYear) { mutableIntStateOf(thisYear) }
    val review = remember(controller.version, controller.today, year) {
        controller.read(null) { yearReview(year, controller.today) }
    }
    val context = LocalContext.current
    SoftCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle("Year in review")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(year == thisYear, { year = thisYear }, label = { Text("$thisYear so far") })
                FilterChip(year == thisYear - 1, { year = thisYear - 1 }, label = { Text("${thisYear - 1}") })
            }
            if (review == null || review.activeDays == 0u) {
                Text("Nothing logged in $year.", style = MaterialTheme.typography.bodyMedium)
            } else {
                review.lines().forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                OutlinedButton(onClick = {
                    val error = shareYearImage(context, review)
                    if (error != null) controller.message = error
                }) { Text("Share as image") }
            }
        }
    }
}

/** Draws the review on a 1080×1350 image and opens the share sheet. Returns an error or null. */
private fun shareYearImage(context: Context, review: YearReview): String? {
    val width = 1080
    val height = 1350
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val background = Paint().apply {
        shader = LinearGradient(0f, 0f, width.toFloat(), height.toFloat(), 0xFF3949AB.toInt(), 0xFF00897B.toInt(), Shader.TileMode.CLAMP)
    }
    canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), background)
    val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textSize = 96f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    val body = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xF2FFFFFF.toInt()
        textSize = 46f
    }
    val small = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xB3FFFFFF.toInt()
        textSize = 36f
    }
    canvas.drawText("My ${review.year}", 80f, 220f, title)
    canvas.drawText("in Jarvest", 80f, 300f, small)
    var y = 440f
    for (line in review.lines()) {
        // Long lines (a long goal name) are cut to fit the width.
        var text = line
        while (body.measureText(text) > width - 160 && text.length > 4) text = text.dropLast(2) + "…"
        canvas.drawText(text, 80f, y, body)
        y += 96f
    }
    canvas.drawText("Made with Jarvest", 80f, height - 80f, small)

    val dir = File(context.cacheDir, "share")
    return try {
        dir.deleteRecursively()
        dir.mkdirs()
        val file = File(dir, "jarvest-${review.year}.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.share", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "Share your year").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        null
    } catch (_: IOException) {
        "The image couldn't be made."
    } finally {
        bitmap.recycle()
    }
}
