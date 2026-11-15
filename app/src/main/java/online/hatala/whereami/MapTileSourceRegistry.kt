package online.hatala.whereami

import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.MapTileIndex

enum class MapBaseLayer(val label: String) {
    STANDARD("Standard OSM"),
    FREEMAP_OUTDOOR("Freemap Outdoor (Central Europe)"),
    TOPO("Topographic (OpenTopo)"),
    SATELLITE("Satellite (Esri)")
}

object MapTileSourceRegistry {
    val FreemapOutdoorSource: OnlineTileSourceBase = object : OnlineTileSourceBase(
        "FreemapOutdoor2x",
        0, 19, 512, ".jpeg",
        arrayOf("https://outdoor.tiles.freemap.sk/"),
        "© Freemap Slovakia, OpenStreetMap contributors"
    ) {
        override fun getTileURLString(pMapTileIndex: Long): String {
            val zoom = MapTileIndex.getZoom(pMapTileIndex)
            val x = MapTileIndex.getX(pMapTileIndex)
            val y = MapTileIndex.getY(pMapTileIndex)
            return "${getBaseUrl()}$zoom/$x/$y@2x"
        }
    }

    val OpenTopoMapSource: OnlineTileSourceBase = XYTileSource(
        "OpenTopoMap",
        1, 17, 256, ".png",
        arrayOf(
            "https://a.tile.opentopomap.org/",
            "https://b.tile.opentopomap.org/",
            "https://c.tile.opentopomap.org/"
        ),
        "© OpenTopoMap (CC-BY-SA)"
    )

    val EsriSatelliteSource: OnlineTileSourceBase = object : OnlineTileSourceBase(
        "EsriSatellite",
        0, 19, 256, ".jpg",
        arrayOf("https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/"),
        "© Esri, Maxar, Earthstar Geographics"
    ) {
        override fun getTileURLString(pMapTileIndex: Long): String {
            val zoom = MapTileIndex.getZoom(pMapTileIndex)
            val x = MapTileIndex.getX(pMapTileIndex)
            val y = MapTileIndex.getY(pMapTileIndex)
            return getBaseUrl() + "$zoom/$y/$x"
        }
    }

    val WaymarkedTrailsHikingSource: OnlineTileSourceBase = XYTileSource(
        "WaymarkedTrailsHiking",
        1, 18, 256, ".png",
        arrayOf(
            "https://tile.waymarkedtrails.org/hiking/"
        ),
        "© Waymarked Trails (CC-BY-SA)"
    )

    fun tileSourceFor(layer: MapBaseLayer): OnlineTileSourceBase = when (layer) {
        MapBaseLayer.STANDARD -> TileSourceFactory.MAPNIK
        MapBaseLayer.FREEMAP_OUTDOOR -> FreemapOutdoorSource
        MapBaseLayer.TOPO -> OpenTopoMapSource
        MapBaseLayer.SATELLITE -> EsriSatelliteSource
    }

    fun providerNameFor(layer: MapBaseLayer): String = tileSourceFor(layer).name()

    fun maxZoomFor(layer: MapBaseLayer, defaultMaxZoom: Double): Double =
        if (layer == MapBaseLayer.TOPO) 17.85 else defaultMaxZoom

    fun minZoomIntFor(layer: MapBaseLayer): Int = tileSourceFor(layer).minimumZoomLevel

    fun maxZoomIntFor(layer: MapBaseLayer): Int = tileSourceFor(layer).maximumZoomLevel

    fun tileUrlFor(layer: MapBaseLayer, zoom: Int, x: Int, y: Int): String =
        tileSourceFor(layer).getTileURLString(MapTileIndex.getTileIndex(zoom, x, y))
}
