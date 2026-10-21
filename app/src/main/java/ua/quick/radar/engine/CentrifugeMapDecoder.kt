package ua.quick.radar.engine

import java.util.Base64

object CentrifugeMapDecoder {

    /**
     * Декодує бінарно-замаскований фрейм Centrifugo (~3:...) з офіційного джерела «Повітряна тривога» (Мінцифра).
     *
     * Архітектура демаскування та десеріалізації:
     * 1. Base64 URL-safe декодування
     * 2. Xorshift32 PRNG демаскування бінарного потоку
     * 3. FNV-1a валідація цілісності контрольної суми
     * 4. LEB128 varint & IEEE-754 AST десеріалізація
     * 5. Фішер-Єйтс дерандомізація метаданих конверта
     * 6. Реконструювання геометрії клітинок та деобфускація ідентифікаторів треків (UUID)
     * 7. Витяг активних цілей та векторів переміщення
     */
    fun decode(rawStr: String): UaTargetsResponse? {
        val trimmed = rawStr.trim()
        val payload = if (trimmed.startsWith("\"") && trimmed.endsWith("\"") && trimmed.length >= 2) {
            trimmed.substring(1, trimmed.length - 1)
        } else {
            trimmed
        }

        if (!payload.startsWith("~3:")) {
            return null
        }

        val aStr = payload.substring(3)
        val pad = (4 - aStr.length % 4) % 4
        val b64 = aStr.replace('-', '+').replace('_', '/') + "=".repeat(pad)
        val rawBytes = Base64.getDecoder().decode(b64)
        if (rawBytes.size < 9) {
            throw IllegalArgumentException("Invalid map frame size: ${rawBytes.size}")
        }

        val seed = readUInt32LE(rawBytes, 0)
        val expectedHash = readUInt32LE(rawBytes, 4)

        // 1. Xorshift32 PRNG демаскування та перевірка FNV-1a
        var dVal = seed
        fun prng(): Long {
            var r = if (dVal != 0L) dVal else 1831565813L
            r = (r xor ((r shl 13) and 0xFFFFFFFFL)) and 0xFFFFFFFFL
            r = (r xor (r ushr 17)) and 0xFFFFFFFFL
            r = (r xor ((r shl 5) and 0xFFFFFFFFL)) and 0xFFFFFFFFL
            dVal = r
            return dVal
        }

        var mask = 0L
        var fnv = 2166136261L
        for (t in 8 until rawBytes.size) {
            if ((t - 8) % 4 == 0) {
                mask = prng()
            }
            val shift = 8 * ((t - 8) and 3)
            val maskByte = ((mask ushr shift) and 0xFFL).toInt()
            val b = (rawBytes[t].toInt() and 0xFF) xor maskByte
            rawBytes[t] = b.toByte()
            fnv = (((fnv xor (b.toLong() and 0xFFL)) * 16777619L) and 0xFFFFFFFFL)
        }

        if (fnv != expectedHash) {
            throw IllegalArgumentException("Damaged map frame: hash mismatch ($fnv != $expectedHash)")
        }

        // 2. LEB128 Varint & AST десеріалізація
        var g = 8
        var hCount = 0
        val strTable = mutableListOf<String>()
        val numTable = mutableListOf<Double>()

        fun check(len: Int) {
            if (len < 0 || g + len > rawBytes.size) {
                throw IllegalArgumentException("Truncated map frame")
            }
        }

        fun readVarint(): Long {
            var r = 0L
            var n = 1L
            for (i in 0 until 8) {
                check(1)
                val b = rawBytes[g++].toInt() and 0xFF
                r += (b and 127).toLong() * n
                if ((b and 128) == 0) {
                    return r
                }
                n *= 128L
            }
            throw IllegalArgumentException("Invalid map frame integer")
        }

        fun parseVal(depth: Int): Any? {
            if (depth > 64) {
                throw IllegalArgumentException("Map frame exceeds codec limits (depth > 64)")
            }
            hCount++
            if (hCount > 1_000_000) {
                throw IllegalArgumentException("Map frame exceeds codec limits (hCount > 1000000)")
            }
            check(1)
            val tag = rawBytes[g++].toInt() and 0xFF

            return when (tag) {
                0 -> null
                1 -> false
                2 -> true
                3 -> {
                    val v = readVarint()
                    if ((v % 2L) != 0L) -((v + 1L) / 2L) else (v / 2L)
                }
                4 -> {
                    check(8)
                    val bits = (rawBytes[g].toLong() and 0xFFL) or
                            ((rawBytes[g + 1].toLong() and 0xFFL) shl 8) or
                            ((rawBytes[g + 2].toLong() and 0xFFL) shl 16) or
                            ((rawBytes[g + 3].toLong() and 0xFFL) shl 24) or
                            ((rawBytes[g + 4].toLong() and 0xFFL) shl 32) or
                            ((rawBytes[g + 5].toLong() and 0xFFL) shl 40) or
                            ((rawBytes[g + 6].toLong() and 0xFFL) shl 48) or
                            ((rawBytes[g + 7].toLong() and 0xFFL) shl 56)
                    g += 8
                    val v = java.lang.Double.longBitsToDouble(bits)
                    numTable.add(v)
                    v
                }
                5 -> {
                    val length = readVarint().toInt()
                    check(length)
                    val s = String(rawBytes, g, length, Charsets.UTF_8)
                    g += length
                    strTable.add(s)
                    s
                }
                6 -> {
                    val length = readVarint().toInt()
                    val arr = ArrayList<Any?>(length)
                    for (i in 0 until length) {
                        arr.add(parseVal(depth + 1))
                    }
                    arr
                }
                7 -> {
                    val idx = readVarint().toInt()
                    strTable[idx]
                }
                8 -> {
                    val idx = readVarint().toInt()
                    numTable[idx]
                }
                9 -> {
                    check(16)
                    val b64chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
                    val sb = StringBuilder("~")
                    for (r in 0 until 16 step 3) {
                        val nRem = 16 - r
                        val b0 = rawBytes[g + r].toInt() and 0xFF
                        val b1 = if (nRem > 1) rawBytes[g + r + 1].toInt() and 0xFF else 0
                        val b2 = if (nRem > 2) rawBytes[g + r + 2].toInt() and 0xFF else 0
                        val iVal = (b0 shl 16) or (b1 shl 8) or b2
                        sb.append(b64chars[(iVal ushr 18) and 63])
                        sb.append(b64chars[(iVal ushr 12) and 63])
                        if (nRem > 1) {
                            sb.append(b64chars[(iVal ushr 6) and 63])
                        }
                        if (nRem > 2) {
                            sb.append(b64chars[iVal and 63])
                        }
                    }
                    g += 16
                    val tStr = sb.toString()
                    strTable.add(tStr)
                    tStr
                }
                else -> throw IllegalArgumentException("Invalid map frame value tag: $tag")
            }
        }

        val w = parseVal(0) as? List<*> ?: throw IllegalArgumentException("Root frame is not a list")

        // 3. Фішер-Єйтс дерандомізація конверта
        val aList = intArrayOf(0, 1, 2, 3, 4)
        var permVal = (2654435769L xor seed) and 0xFFFFFFFFL
        fun prngPerm(): Long {
            var r = if (permVal != 0L) permVal else 1831565813L
            r = (r xor ((r shl 13) and 0xFFFFFFFFL)) and 0xFFFFFFFFL
            r = (r xor (r ushr 17)) and 0xFFFFFFFFL
            r = (r xor ((r shl 5) and 0xFFFFFFFFL)) and 0xFFFFFFFFL
            permVal = r
            return permVal
        }

        for (t in 4 downTo 1) {
            val e = (prngPerm() % (t + 1)).toInt()
            val tmp = aList[t]
            aList[t] = aList[e]
            aList[e] = tmp
        }

        val nArr = arrayOfNulls<Any>(5)
        for (t in 0..4) {
            if (t < w.size && aList[t] < 5) {
                nArr[aList[t]] = w[t]
            }
        }

        val publicTargetsEnabled = nArr[2] != false && nArr[2] != 0L && nArr[2] != 0 && nArr[2] != null
        val d = nArr[4] as? List<*> ?: throw IllegalArgumentException("Envelope payload 'd' is missing or invalid")
        if (d.size < 12) {
            throw IllegalArgumentException("Envelope payload 'd' size < 12 (${d.size})")
        }

        val serverTime = (d[0] as? Number)?.toLong() ?: 0L
        val cells = (d[5] as? List<*>) ?: emptyList<Any>()
        val dStyles = (d[6] as? List<*>) ?: emptyList<Any>()
        val trackIds = (d[7] as? List<*>) ?: emptyList<Any>()
        val fTargets = (d[8] as? List<*>) ?: emptyList<Any>()
        val pMovement = d[11] as? List<*>

        // 4. Деобфускація ідентифікаторів треків (порядок байтів UUID)
        val order = intArrayOf(3, 2, 1, 0, 5, 4, 7, 6, 8, 9, 10, 11, 12, 13, 14, 15)
        val vTracks = mutableListOf<String>()
        for (tidObj in trackIds) {
            val tid = tidObj?.toString() ?: ""
            if (tid.startsWith("~~")) {
                vTracks.add(tid.substring(1))
            } else if (tid.startsWith("~")) {
                try {
                    val aTid = tid.substring(1).replace('-', '+').replace('_', '/')
                    val p = (4 - aTid.length % 4) % 4
                    val b64Tid = aTid + "=".repeat(p)
                    val buf = Base64.getDecoder().decode(b64Tid)
                    val sb = StringBuilder()
                    for (idx in order) {
                        if (idx < buf.size) {
                            sb.append(String.format("%02x", buf[idx].toInt() and 0xFF))
                        }
                    }
                    vTracks.add(sb.toString())
                } catch (e: Exception) {
                    vTracks.add(tid)
                }
            } else {
                vTracks.add(tid)
            }
        }

        // Допоміжна функція декодування елемента цілі
        fun decodeTarget(itemRaw: Any?): UaMovementItem? {
            val item = itemRaw as? List<*> ?: return null
            if (item.size < 7) return null

            val tIdx = (item[0] as? Number)?.toInt() ?: 0
            val cellIdx = (item[1] as? Number)?.toInt() ?: 0
            val styleIdx = (item[2] as? Number)?.toInt() ?: 0
            val heading = (item[3] as? Number)?.toDouble()
            val count = (item[4] as? Number)?.toInt() ?: 1
            val lDur = (item[6] as? Number)?.toLong() ?: 20000L
            val sOx = if (item.size > 7) (item[7] as? Number)?.toDouble() ?: 0.0 else 0.0
            val cOy = if (item.size > 8) (item[8] as? Number)?.toDouble() ?: 0.0 else 0.0

            val trackId = if (tIdx in vTracks.indices) vTracks[tIdx] else "t_$tIdx"

            val cell = if (cellIdx in cells.indices) cells[cellIdx] as? List<*> else null
            val x = (cell?.getOrNull(0) as? Number)?.toDouble() ?: 0.0
            val y = (cell?.getOrNull(1) as? Number)?.toDouble() ?: 0.0

            val style = if (styleIdx in dStyles.indices) dStyles[styleIdx] as? List<*> else null
            val formular = style?.getOrNull(1)?.toString()
            val detectedType = style?.getOrNull(2)?.toString()
            val iconName = style?.getOrNull(3)?.toString()
            val iconScale = (style?.getOrNull(4) as? Number)?.toDouble() ?: 1.0

            return UaMovementItem(
                trackId = trackId,
                x = x,
                y = y,
                markerOffsetX = sOx,
                markerOffsetY = cOy,
                heading = heading,
                formular = formular,
                detectedType = detectedType,
                iconName = iconName,
                iconScale = iconScale,
                currentCount = count,
                movementDurationMs = lDur
            )
        }

        val decodedTargets = mutableListOf<UaMovementItem>()
        for (fIt in fTargets) {
            val dt = decodeTarget(fIt)
            if (dt != null) {
                decodedTargets.add(dt)
            }
        }

        var decodedMovement: UaMovementWrapper? = null
        if (pMovement != null && pMovement.isNotEmpty()) {
            val fromListRaw = pMovement.getOrNull(0) as? List<*>
            val fromUpdatedAt = (pMovement.getOrNull(1) as? Number)?.toLong()
            val startedAt = (pMovement.getOrNull(2) as? Number)?.toLong()
            if (fromListRaw != null) {
                val fromItems = mutableListOf<UaMovementItem>()
                for (mIt in fromListRaw) {
                    val dt = decodeTarget(mIt)
                    if (dt != null) {
                        fromItems.add(dt)
                    }
                }
                decodedMovement = UaMovementWrapper(
                    from = fromItems,
                    fromUpdatedAt = if (fromUpdatedAt != null) serverTime + fromUpdatedAt else null,
                    startedAt = if (startedAt != null) serverTime + startedAt else null
                )
            }
        }

        return UaTargetsResponse(
            targets = decodedTargets,
            movement = decodedMovement,
            publicTargetsEnabled = publicTargetsEnabled,
            serverTime = serverTime
        )
    }

    private fun readUInt32LE(bytes: ByteArray, offset: Int): Long {
        return (bytes[offset].toLong() and 0xFFL) or
                ((bytes[offset + 1].toLong() and 0xFFL) shl 8) or
                ((bytes[offset + 2].toLong() and 0xFFL) shl 16) or
                ((bytes[offset + 3].toLong() and 0xFFL) shl 24)
    }
}
