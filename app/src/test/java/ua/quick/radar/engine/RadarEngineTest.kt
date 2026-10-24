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
        // Real ~5: ChaCha20 frame captured from map.ukrainealarm.com wrapped in {"n":"~5:..."}
        val realEnvelope = """{"n":"~5:-YwI0wrSjb4yHxm26Hm6C9H6sCfEf6dYbMcNNq-Dc2VkMfjf05jgkVXxvT-l6EXDh7fsKTLdI5yDCmMd6AriljzflvPHohFPTuy8xbdNd7mmEefpTWY6-hDeLmz0tNRrLw9zcvejetxdyvBXIwEXDRS5Xe4GETjUEwEJJg6fHl2CThRfr6ADigYW4kW3T7dvBg0IYMHvphn9Z2Nz_C28lqhNdL1qUCNJP0MazShpRFcTzWFwh49Jsf3WGBGXaZdfELdF74Cso_gAPyVK4bnBDiQfDnZgshipgG-dXw7OD9d0i7mUUIRFvbyuWwKGzOhpShVU77Zgzcs550_7VtJzagF2USgAUH6RpPmM5mpNwEYbDMwj99nsZLsNw-1YbIzuJWyg4aeH7OrpuL9YlUPs5aWo5mfC4HoxQRUUutI4x2C-9jF0k8DI3Vmmq68iAjP6bd3__68A-uUTmTVWeM5DtRBGnl1sl3RqLGVGA5gu4xJoLCTLQH42G4F6LdUAPBpyDtWHIsIJlfHSHJAV0WvfHBDXtLo0mgx_imjqQntvsIuGc9-696JBSeIQG-zv7tzBHtaepal-FzQOmzQMvxPikTMd_MRFbs3cKcvmpIWLgP2Zc3jhfbqmqvldS8pLRNy59w9mXm16X2bgU5BDhAM64sklloR9bjNOWzl8zi-9n0mi0IO19uBQnPztYpdw_ZBmAfRpoRcVzxYF8ea8qDSrxH0c4ZCwNb8mWtmTJTPNWgcO9kj2ai8SjGurlacXam1_4TITYE9YAoBO2gHhT0ZLkp9CLM98iINxgo2Q8zP33rqW7UBRlvTWRf_q8cFM9HoYfT_r6oFKokR_m7lH1oTpXFFWQOHnmKYiyAMN9bICAr8tMEESRlhS5N4Kcx401lYLF4XsIm3T-mSe_QXLbA9fNsj8SmlYMYUrH_6RysSGQznwqoJbmliZSjy4OpLY46Yqchxedfyca4QZixuDblW2oBTb_nwLguIUg4Jd5jApFFhWW-WhQIcciMoXqVjweJVUaW4wOI_demjSyli_XDSHYxF9vw_sHZxag8WYbidQQXFTxNRsYaffcsHrMHcAzzEKKQQAjKW7zU4bXlWES8Q7F05utYEAGNw7Qf-2Dsu8FRR36pj5AbOUEvMUHYIX-JQuEtYgpSInFDQ0bf55wn9ttFrtadAttetC_V_RG7EHtKrRTCLED1xHvoTimU250AfjvtIU6U_CV5zqBFAfFScUgacufU4ChohkOXIpaQ5tRC96RneCC0A4tHHSqq4sCDaBccEKkgnYek71OAknxTfncrvJne7TfZk6b0zxxap8Zzd2zhs-ubMoILewFEypBLUhsLiHYpoaveeDfD-dgBv2uAsXOMfXrC9e8Wc_00TGtqCNK_GwOdFAo9nRKZo_H0oHRDeOctQsUCNkCnI7VgfjOgXLVMfoi7zmgN11EPtwyQ84I5p4MCHWsX8f1BnMotrj1rMdeZGg1Aui9SLWp8R0NGCVsH25Mds9djgLLUOeXXX2t6l9T0HRhWkjYOdpuB_Mvo8zxnQmIXGXkFFtOXlDSLNc_xrU8FkcHriWQa47aNR-qZ5_tvZodJIQowjw-biEcKWUjcR603kZPgo76cpp28bjTlRpcxFOQLooAMPujTWPD1TM--GZiPDfbBYTG1GKD4hN7iaTRaR_Sl1Ozu4k7hMvTTP0qTnnw2HY0T3Yc7pazZQ7kvy5eW1uFZSMH8ZBeK1yY4kIpw4V4V9liU-GleQkzJPwspFwDSyHRglzHmjSNFyu5KPeWSGVRNWUo0Bm02oyROrGF_o6smySfEmc-Y_2iGrf2HhQh5FhZdx6_juoAAVbKrbaTZOZ7eGSn4cD7TbrVRjupqRK0BfL3_pgyMtY72hVCXpKPnUSLAoMU3hwwXiQAqs-5_eybDKoVBTrk-tHaRRAaT1as4QDGqLbUeWQSP-sb0igRisXDVfyOZ1UKHMSJep9udMFz82y1fPP5Ls9S2Inv8BQ34L5RjZ5KyQ0XlYUC5ohAxRPEdpzMo9kK3shB8rNLIpwdMMaZje1bHEmYoJ3WRtSK653HQCUNicUVKJYDfY4OHT3RD6Xygj1dstZqXIJcp3W-EwKZjWQltbPnQL1IFqkkNFVExyIIoJdVr_ro7HfDD15qwdt-zBz84N8gPVKHhOHb3QZ7Q9nE2mMFjenQOGNjwA7-7y3v3bxOxYDiAGV3aHHqig7TgH9YjEbSX2R8aCy-lQlPHlu-kAmbFpPqtv4Ezdaijz9RbpmdOHyiTDQQevvyP8vZJ8hKVBRoTT4pV1o4P-ue_YFSwFy2n8isfFYAyxQ6bSz_D6rq1n4Udq9C8lX0jeA"}"""

        val decoded = CentrifugeMapDecoder.decode(realEnvelope)
        assertNotNull("CentrifugeMapDecoder must decode real ~5: ChaCha20 frame", decoded)
        assertTrue("Decoded serverTime must be > 1700000000000", (decoded!!.serverTime ?: 0L) > 1700000000000L)
        assertFalse("Decoded targets must not be empty", decoded.targets.isNullOrEmpty())
        val firstTarget = decoded.targets!!.first()
        assertNotNull(firstTarget.trackId)
        assertTrue(firstTarget.x in 0.0..1.0)
        assertTrue(firstTarget.y in 0.0..1.0)
        val (lat, lng) = UkraineAlarmClient.normalizedToLngLat(firstTarget.x, firstTarget.y)
        assertTrue("Projected latitude must be inside Ukraine region", lat in 44.0..53.0)
        assertTrue("Projected longitude must be inside Ukraine region", lng in 22.0..41.0)
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
