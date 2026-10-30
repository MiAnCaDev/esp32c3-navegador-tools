package dev.gpsnav.bridge.moto

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/** Guardado, recuperación tras un cierre inesperado, estadísticas y exportación GPX/CSV de un viaje. */
class TripStoreTest {
    private lateinit var dir: File
    private lateinit var store: TripStore
    private val id = "20261027_101500"
    private val start = 1_790_000_000_000L

    @Before fun setUp() { dir = Files.createTempDirectory("trips").toFile(); store = TripStore(dir) }
    @After fun tearDown() { dir.deleteRecursively() }

    /** Circunferencia de 500 m de radio a 15 m/s durante 200 s (longitud esperada = 3000 m), con una curva y vibración vertical de ±1 m. */
    private fun writeTrip(crash: Boolean = true) {
        val w = store.open(id, start)
        val r = 500.0; val v = 15.0; val lat0 = 40.0; val lon0 = -3.0
        for (i in 0..200) {
            val ang = v * i / r
            val lat = lat0 + r * sin(ang) / 111_320.0
            val lon = lon0 + r * (1 - cos(ang)) / (111_320.0 * cos(Math.toRadians(lat0)))
            val lean = Math.toDegrees(atan(v * v / (r * 9.80665)))
            val p = TrackPoint(start + i * 1000L, lat, lon, 600.0 + 30 * sin(i / 20.0) + (if (i % 2 == 0) 1.0 else -1.0), v,
                Math.toDegrees(ang) % 360, 5.0, lean, lean + 1.5, tan(Math.toRadians(lean + 1.5)),
                if (i == 10) 0.35 else if (i == 50) -0.60 else 0.0)
            w.write(TripCodec.point(p)); w.newLine()
        }
        w.write(TripCodec.curve(Curve(start + 30_000, 5000, 41.3, 15.0, 40.001, -3.001))); w.newLine()
        if (crash) { w.write("P,1790000201000,40.0"); w.newLine() }     // línea cortada por un cierre inesperado
        w.close()
    }

    @Test fun tripWithoutMeta_isRebuiltWithCorrectStatistics() {
        writeTrip()                                       // sin .meta: como si la app hubiera muerto durante el viaje
        assertEquals(1, store.list(activeId = "otro").size)
        val s = store.read(id)!!
        assertEquals("distancia (m)", 3000.0, s.distanceM, 30.0)
        assertEquals(201, s.points); assertEquals(1, s.curves)
        assertEquals("velocidad máx (km/h)", 54.0, s.maxSpeedMs * 3.6, 0.1)
        assertTrue("tiempo en movimiento ${s.movingMs}", s.movingMs in 199_000..200_000)
        assertEquals(41.3, s.maxLeanLeft, 0.01); assertEquals(0.0, s.maxLeanRight, 0.0)      // el pico de la curva pasa a ser el máximo
        assertEquals(0.35, s.maxAccelG, 0.001); assertEquals(0.60, s.maxBrakeG, 0.001)
        assertTrue(s.hasLean)
        // desnivel real ≈ 90 m (sube 30, baja 60, sube 60); la vibración de ±1 m no debe inflarlo
        assertTrue("desnivel ${s.elevGainM}", s.elevGainM > 85 && s.elevGainM < 95)
    }

    @Test fun activeTripIsNotTouched() {
        writeTrip(); assertEquals(0, store.list(activeId = id).size)
    }

    @Test fun metaPersists_renameSanitizesTheName() {
        writeTrip(); store.read(id)
        assertTrue(File(dir, "$id.meta").exists())
        store.rename(id, "Puerto de la Morcuera"); assertEquals("Puerto de la Morcuera", store.read(id)!!.name)
        val again = TripSummary.fromMeta(store.read(id)!!.toMeta())!!
        assertEquals(201, again.points); assertEquals("Puerto de la Morcuera", again.name)
        store.rename(id, "Nombre\ncon salto & <raro>"); assertFalse(store.read(id)!!.name.contains('\n'))
    }

    @Test fun gpx_isWellFormedXml_withCurvesAsWaypointsBeforeTheTrack() {
        writeTrip(); val s = store.read(id)!!; store.rename(id, "Nombre & <raro>")
        val bo = ByteArrayOutputStream(); TripExport.writeGpx(store, store.read(id)!!, bo)
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(ByteArrayInputStream(bo.toByteArray()))
        assertEquals(201, doc.getElementsByTagName("trkpt").length)
        assertEquals(1, doc.getElementsByTagName("wpt").length)
        val text = bo.toString("UTF-8")
        assertTrue("los puntos de interés deben ir antes de la ruta", text.indexOf("<wpt") in 1 until text.indexOf("<trk>"))
        assertTrue(text.contains("Curva izq 41°"))
        assertTrue("el nombre debe ir escapado", text.contains("&lt;raro&gt;") && text.contains("&amp;"))
        assertNotNull(s)
    }

    @Test fun csv_hasAllRowsAndColumns() {
        writeTrip(); val co = ByteArrayOutputStream(); TripExport.writeCsv(store, store.read(id)!!, co)
        val rows = co.toString("UTF-8").trim().lines()
        assertEquals(202, rows.size)                                    // cabecera + 201 puntos
        assertTrue(rows.all { it.count { c -> c == ',' } == 10 })
    }

    @Test fun emptyTripIsDiscarded_deleteWorks() {
        store.open("vacio", start).close()
        assertNull(store.read("vacio")); assertFalse(File(dir, "vacio.trip").exists())
        writeTrip(); store.read(id); store.delete(id); assertEquals(0, store.list().size)
    }

    @Test fun distanceIgnoresGpsJitterWhileStopped() {
        val w = store.open("parado", start)
        for (i in 0..100) {      // parado 100 s con el GPS "bailando" ±5 m
            val p = TrackPoint(start + i * 1000L, 40.0 + (if (i % 2 == 0) 4e-5 else -4e-5), -3.0, 600.0, 0.2, 0.0, 5.0, 0.0, 0.0, 0.0, 0.0)
            w.write(TripCodec.point(p)); w.newLine()
        }
        w.close()
        assertTrue("distancia parado ${store.read("parado")!!.distanceM}", abs(store.read("parado")!!.distanceM) < 1.0)
    }
}
