package janush.tech.whereami

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object GpxExporter {

    fun exportTripToGpx(context: Context, trip: TripRecord): File {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

        val gpxBuilder = StringBuilder()
        gpxBuilder.append("""<?xml version="1.0" encoding="UTF-8"?>""").append("\n")
        gpxBuilder.append("""<gpx version="1.1" creator="Where Am I App" xmlns="http://www.topografix.com/GPX/1/1">""").append("\n")
        gpxBuilder.append("""  <metadata>""").append("\n")
        val defaultTitle = "Trip on " + SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(trip.startTime))
        val titleSafe = (if (trip.title.isNotBlank()) trip.title else defaultTitle)
            .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        gpxBuilder.append("    <name>").append(titleSafe).append("</name>\n")
        gpxBuilder.append("    <time>").append(sdf.format(Date(trip.startTime))).append("</time>\n")
        gpxBuilder.append("""  </metadata>""").append("\n")

        // Add visited places as Waypoints (wpt)
        for (vp in trip.placesVisited) {
            val nameSafe = vp.placeName.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            val descSafe = vp.hierarchySubtitle.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            gpxBuilder.append("  <wpt lat=\"").append(vp.latitude).append("\" lon=\"").append(vp.longitude).append("\">\n")
            gpxBuilder.append("    <name>").append(nameSafe).append("</name>\n")
            if (descSafe.isNotBlank()) {
                gpxBuilder.append("    <desc>").append(descSafe).append("</desc>\n")
            }
            gpxBuilder.append("    <time>").append(sdf.format(Date(vp.timestamp))).append("</time>\n")
            gpxBuilder.append("  </wpt>\n")
        }

        // Add track (trk) and segment (trkseg)
        gpxBuilder.append("""  <trk>""").append("\n")
        gpxBuilder.append("    <name>").append(titleSafe).append("</name>\n")
        gpxBuilder.append("""    <trkseg>""").append("\n")
        for (pt in trip.points) {
            gpxBuilder.append("      <trkpt lat=\"").append(pt.latitude).append("\" lon=\"").append(pt.longitude).append("\"/>\n")
        }
        gpxBuilder.append("""    </trkseg>""").append("\n")
        gpxBuilder.append("""  </trk>""").append("\n")
        gpxBuilder.append("""</gpx>""").append("\n")

        val gpxDir = File(context.cacheDir, "gpx_exports").apply { mkdirs() }
        val safeFileName = "trip_" + trip.startTime + ".gpx"
        val file = File(gpxDir, safeFileName)
        file.writeText(gpxBuilder.toString())
        return file
    }

    fun shareGpx(context: Context, trip: TripRecord) {
        val file = exportTripToGpx(context, trip)
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/gpx+xml"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, if (trip.title.isNotBlank()) trip.title else "Where Am I Recorded Trip")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(intent, "Share Trip GPX")
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }
}
