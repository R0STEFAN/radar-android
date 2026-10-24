package ua.quick.radar.engine

import org.junit.Assert.*
import org.junit.Test
import java.nio.charset.StandardCharsets

class RadarEngineTest {

    @Test
    fun testBase64RoundTrip() {
        val sample = "Fluger Radar Test України ~5:1234567890!@#$%^&*()"
        val raw = sample.toByteArray(StandardCharsets.UTF_8)

        val encoded = RadarApiClient.encodeBase64(raw)
        val decoded = RadarApiClient.decodeBase64(encoded)
        assertArrayEquals(raw, decoded)

        val urlEncoded = RadarApiClient.encodeBase64UrlNoPad(raw)
        assertFalse(urlEncoded.contains("="))
        assertFalse(urlEncoded.contains("+"))
        assertFalse(urlEncoded.contains("/"))
        val urlDecoded = RadarApiClient.decodeBase64(urlEncoded)
        assertArrayEquals(raw, urlDecoded)
    }

    @Test
    fun testFcmTokenFormatAndPerNodeRotation() {
        val client = RadarApiClient()

        val tokenKyiv1 = client.buildFcmToken(50.4501, 30.5234, forceRotate = false)
        val tokenKyiv2 = client.buildFcmToken(50.4501, 30.5234, forceRotate = false)
        val tokenLviv = client.buildFcmToken(49.8397, 24.0297, forceRotate = false)

        // Format check: 22 chars + ":APA91b" (7 chars) + 114 chars = 143 chars
        assertEquals(143, tokenKyiv1.length)
        assertTrue(tokenKyiv1.contains(":APA91b"))
        assertEquals(22, tokenKyiv1.indexOf(":APA91b"))

        // Same node within same TTL window returns identical token
        assertEquals(tokenKyiv1, tokenKyiv2)

        // Different grid node has isolated token
        assertNotEquals(tokenKyiv1, tokenLviv)

        // Forced rotation on HTTP 403 generates a brand new token for the node
        val tokenKyivRotated = client.buildFcmToken(50.4501, 30.5234, forceRotate = true)
        assertEquals(143, tokenKyivRotated.length)
        assertTrue(tokenKyivRotated.contains(":APA91b"))
        assertNotEquals(tokenKyiv1, tokenKyivRotated)
    }

    @Test
    fun testLiveRadarQuickUaFetch() {
        val client = RadarApiClient()
        val data = client.fetchRawRadarData(50.4501, 30.5234)
        assertNotNull("Live radar.quick.ua response must not be null", data)
        assertTrue("Response must contain 'radar' object", data!!.has("radar"))
    }

    @Test
    fun testLiveUkraineAlarmCentrifugeDecoderAndClient() {
        val uaClient = UkraineAlarmClient()
        // Should never throw any exception and should decode ~5: ChaCha20 frames wrapped in {"n": "~5:..."}
        val targets = uaClient.fetchUaRadarTargets()
        assertNotNull(targets)
        for (t in targets) {
            assertTrue("Target latitude must be valid", t.lat in 40.0..56.0)
            assertTrue("Target longitude must be valid", t.lng in 18.0..45.0)
            assertTrue("Target trackId must start with ua_", t.trackId.startsWith("ua_"))
        }
    }

    @Test
    fun testCentrifugeEnvelopeUnwrapping() {
        // Sample real ~5 frame captured from map.ukrainealarm.com
        val uaClient = UkraineAlarmClient()
        val req = okhttp3.Request.Builder()
            .url("https://map.ukrainealarm.com/api/centrifuge/map/fallback")
            .header("User-Agent", "Mozilla/5.0")
            .header("Accept", "application/json")
            .get()
            .build()
        val http = okhttp3.OkHttpClient()
        val body = http.newCall(req).execute().use { it.body?.string() ?: "" }
        assertTrue("Fallback response should not be empty", body.isNotEmpty())

        val decoded = CentrifugeMapDecoder.decode(body)
        assertNotNull("CentrifugeMapDecoder must decode live fallback frame", decoded)
        assertNotNull("Decoded serverTime must be present", decoded!!.serverTime)
        assertTrue("Decoded serverTime must be > 1700000000000", (decoded.serverTime ?: 0L) > 1700000000000L)
    }

    @Test
    fun testFourTierThreatAssessment() {
        val settings = AppSettings(
            pinnedLat = 50.4501,
            pinnedLng = 30.5234,
            criticalRadiusKm = 30.0,
            sectorRadiusKm = 45.0
        )

        // 1. Clear sky -> Green
        val greenAssess = ThreatAnalyzer.assessThreats(emptyList(), settings)
        assertFalse(greenAssess.isCritical)
        assertFalse(greenAssess.isAlertZone)
        assertFalse(greenAssess.isSector)
        assertTrue(greenAssess.summaryText.startsWith("🟢"))

        // 2. Target at ~38 km (inside 45 km yellow sector, outside 30 km red alert zone) -> Yellow
        val pt38km = ThreatAnalyzer.getDestinationPoint(50.4501, 30.5234, 38_000.0, 0.0)
        val yellowTarget = RadarTarget(
            trackId = "t_yellow",
            lat = pt38km.first,
            lng = pt38km.second,
            courseDeg = 90.0, // flying East (not towards user)
            avgSpeedKmh = 185.0
        )
        val yellowAssess = ThreatAnalyzer.assessThreats(listOf(yellowTarget), settings)
        assertFalse(yellowAssess.isCritical)
        assertFalse(yellowAssess.isAlertZone)
        assertTrue(yellowAssess.isSector)
        assertEquals(1, yellowAssess.sectorTargets.size)
        assertTrue(yellowAssess.summaryText.startsWith("🟡"))

        // 3. Target at ~20 km (inside 30 km red alert zone, flying tangential course 90°) -> Red (Alert Zone)
        val pt20km = ThreatAnalyzer.getDestinationPoint(50.4501, 30.5234, 20_000.0, 0.0)
        val redTarget = RadarTarget(
            trackId = "t_red",
            lat = pt20km.first,
            lng = pt20km.second,
            courseDeg = 90.0, // flying East, not towards user at South (180°)
            avgSpeedKmh = 185.0
        )
        val redAssess = ThreatAnalyzer.assessThreats(listOf(redTarget), settings)
        assertFalse(redAssess.isCritical)
        assertTrue(redAssess.isAlertZone)
        assertTrue(redAssess.isSector)
        assertEquals(1, redAssess.alertZoneTargets.size)
        assertTrue(redAssess.summaryText.startsWith("🔴"))

        // 4. Target at ~20 km North flying South (180° directly at user) -> Critical
        val critTarget = RadarTarget(
            trackId = "t_crit",
            lat = pt20km.first,
            lng = pt20km.second,
            courseDeg = 180.0, // flying directly South towards user
            avgSpeedKmh = 185.0
        )
        val critAssess = ThreatAnalyzer.assessThreats(listOf(critTarget), settings)
        assertTrue(critAssess.isCritical)
        assertTrue(critAssess.isAlertZone)
        assertEquals(1, critAssess.criticalTargets.size)
        assertTrue(critAssess.summaryText.startsWith("🚨"))
    }
}
