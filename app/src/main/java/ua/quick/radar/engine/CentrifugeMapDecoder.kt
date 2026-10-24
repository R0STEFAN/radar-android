package ua.quick.radar.engine

import com.google.gson.JsonElement
import com.google.gson.JsonParser

object CentrifugeMapDecoder {

    /**
     * Декодує бінарно-замаскований фрейм Centrifugo (~5:... або ~3:...) з офіційного джерела «Повітряна тривога» (Мінцифра).
     * Також автоматично розпаковує JSON-конверти виду {"n": "~5:..."}, {"p": "..."} або {"data": ...}.
     */
    fun decode(rawStr: String): UaTargetsResponse? {
        var current = rawStr.trim()

        // Розпакування JSON-конвертів ({"n": "~5:..."}, {"p": "..."}, {"data": ...}) до 4 рівнів вкладеності
        for (i in 0 until 4) {
            if (current.startsWith("~5:") || current.startsWith("~3:")) {
                break
            }
            if (current.startsWith("\"") && current.endsWith("\"") && current.length >= 2) {
                try {
                    val elem = JsonParser.parseString(current)
                    if (elem.isJsonPrimitive && elem.asJsonPrimitive.isString) {
                        current = elem.asString.trim()
                        continue
                    }
                } catch (_: Exception) {
                    current = current.substring(1, current.length - 1).trim()
                    continue
                }
            }
            if (current.startsWith("{") && current.endsWith("}")) {
                try {
                    val obj = JsonParser.parseString(current).asJsonObject
                    var extracted: JsonElement? = null
                    for (key in arrayOf("n", "p", "data")) {
                        if (obj.has(key) && !obj.get(key).isJsonNull) {
                            extracted = obj.get(key)
                            break
                        }
                    }
                    if (extracted != null) {
                        current = if (extracted.isJsonPrimitive && extracted.asJsonPrimitive.isString) {
                            extracted.asString.trim()
                        } else {
                            extracted.toString().trim()
                        }
                        continue
                    }
                } catch (_: Exception) {
                    break
                }
            }
            break
        }

        return when {
            current.startsWith("~5:") -> decodeV5(current)
            current.startsWith("~3:") -> decodeV3(current)
            else -> null
        }
    }

    /**
     * Декодує фрейм версії ~5:... (ChaCha20 потокове демаскування + FNV-1a + 3 Xorshift32 ГПВЧ + 6 площин).
     */
    private fun decodeV5(rawStr: String): UaTargetsResponse {
        val aStr = rawStr.substring(3)
        val rawBytes = RadarApiClient.decodeBase64(aStr)
        if (rawBytes.size < 17) {
            throw IllegalArgumentException("Invalid ~5 map frame size: ${rawBytes.size}")
        }

        val fSeed = readUInt32LE(rawBytes, 0)
        val hSeed = readUInt32LE(rawBytes, 4)
        val gSeed = readUInt32LE(rawBytes, 8)
        val expectedFnv = readUInt32LE(rawBytes, 12)

        fun rotl32(val32: Long, shift: Int): Long {
            val v = val32 and 0xFFFFFFFFL
            return ((v shl shift) or (v ushr (32 - shift))) and 0xFFFFFFFFL
        }

        fun quarterRound(arr: LongArray, a: Int, b: Int, c: Int, dIdx: Int) {
            arr[a] = (arr[a] + arr[b]) and 0xFFFFFFFFL
            arr[dIdx] = rotl32(arr[dIdx] xor arr[a], 16)
            arr[c] = (arr[c] + arr[dIdx]) and 0xFFFFFFFFL
            arr[b] = rotl32(arr[b] xor arr[c], 12)
            arr[a] = (arr[a] + arr[b]) and 0xFFFFFFFFL
            arr[dIdx] = rotl32(arr[dIdx] xor arr[a], 8)
            arr[c] = (arr[c] + arr[dIdx]) and 0xFFFFFFFFL
            arr[b] = rotl32(arr[b] xor arr[c], 7)
        }

        // 1. ChaCha20 потокове демаскування корисного навантаження (байти 16..N)
        val stateInit = longArrayOf(
            1634760805L, 857760878L, 2036477234L, 1797285236L,
            2838917605L, 566540499L, 2028117766L, 3025996967L,
            1063430289L, 3353677899L, 1519178294L, 2422993229L,
            1L, fSeed, hSeed, gSeed
        )

        var rOff = 16
        while (rOff < rawBytes.size) {
            val b = stateInit.clone()
            for (round in 0 until 10) {
                quarterRound(b, 0, 4, 8, 12)
                quarterRound(b, 1, 5, 9, 13)
                quarterRound(b, 2, 6, 10, 14)
                quarterRound(b, 3, 7, 11, 15)
                quarterRound(b, 0, 5, 10, 15)
                quarterRound(b, 1, 6, 11, 12)
                quarterRound(b, 2, 7, 8, 13)
                quarterRound(b, 3, 4, 9, 14)
            }
            val chunkLen = minOf(64, rawBytes.size - rOff)
            for (n in 0 until chunkLen) {
                val wordIdx = n shr 2
                val word = (b[wordIdx] + stateInit[wordIdx]) and 0xFFFFFFFFL
                val maskByte = ((word ushr (8 * (n and 3))) and 0xFFL).toInt()
                val curByte = rawBytes[rOff + n].toInt() and 0xFF
                rawBytes[rOff + n] = (curByte xor maskByte).toByte()
            }
            stateInit[12] = (stateInit[12] + 1L) and 0xFFFFFFFFL
            rOff += 64
        }

        // 2. Перевірка контрольної суми FNV-1a
        var fnv = 2166136261L
        for (idx in 16 until rawBytes.size) {
            val byteVal = rawBytes[idx].toLong() and 0xFFL
            fnv = ((fnv xor byteVal) * 16777619L) and 0xFFFFFFFFL
        }
        if (fnv != expectedFnv) {
            throw IllegalArgumentException("Damaged ~5 map frame: hash mismatch ($fnv != $expectedFnv)")
        }

        // 3. Ініціалізація трьох ГПВЧ Xorshift32 (A, N, M)
        fun xorshiftNext(stateHolder: LongArray, idx: Int): Long {
            var r = if (stateHolder[idx] != 0L) stateHolder[idx] else 1831565813L
            r = (r xor ((r shl 13) and 0xFFFFFFFFL)) and 0xFFFFFFFFL
            r = (r xor (r ushr 17)) and 0xFFFFFFFFL
            r = (r xor ((r shl 5) and 0xFFFFFFFFL)) and 0xFFFFFFFFL
            stateHolder[idx] = r
            return r
        }

        val prngStates = longArrayOf(
            (fSeed xor gSeed xor 2246822507L) and 0xFFFFFFFFL, // 0: prng_A
            (3266489909L xor hSeed) and 0xFFFFFFFFL,           // 1: prng_N
            (fSeed xor hSeed xor gSeed xor 668265263L) and 0xFFFFFFFFL // 2: prng_M
        )
        fun prngA() = xorshiftNext(prngStates, 0)
        fun prngN() = xorshiftNext(prngStates, 1)
        fun prngM() = xorshiftNext(prngStates, 2)

        // 4. Таблиця перестановки тегів типів (tag_map)
        val xPerm = intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9)
        for (eIdx in 9 downTo 1) {
            val tIdx = (prngM() % (eIdx + 1)).toInt()
            val tmp = xPerm[eIdx]
            xPerm[eIdx] = xPerm[tIdx]
            xPerm[tIdx] = tmp
        }
        val tagMap = IntArray(10)
        for (idx in xPerm.indices) {
            tagMap[xPerm[idx]] = idx
        }

        // 5. Десеріалізація дерева значень (AST)
        var pos = 16
        var opCount = 0
        val strTable = mutableListOf<String>()
        val numTable = mutableListOf<Double>()

        fun checkBounds(length: Int) {
            if (length < 0 || pos + length > rawBytes.size) {
                throw IllegalArgumentException("Truncated ~5 map frame")
            }
        }

        fun readVarint(): Long {
            var r = 0L
            var n = 1L
            for (i in 0 until 8) {
                checkBounds(1)
                val bVal = rawBytes[pos++].toInt() and 0xFF
                r += (bVal and 127).toLong() * n
                if ((bVal and 128) == 0) {
                    return r
                }
                n *= 128L
            }
            throw IllegalArgumentException("Invalid ~5 map frame integer")
        }

        fun parseVal(depth: Int): Any? {
            if (depth > 64) {
                throw IllegalArgumentException("Map frame exceeds codec depth limits")
            }
            opCount++
            if (opCount > 1_000_000) {
                throw IllegalArgumentException("Map frame exceeds codec op limits")
            }
            checkBounds(1)
            val rawTag = rawBytes[pos++].toInt() and 0xFF
            if (rawTag >= tagMap.size) {
                throw IllegalArgumentException("Invalid ~5 map frame tag byte: $rawTag")
            }
            val tag = tagMap[rawTag]

            return when (tag) {
                0 -> null
                1 -> false
                2 -> true
                3 -> {
                    val eVal = readVarint()
                    val tMask = prngN() and 0xFFFFL
                    val low = ((eVal and 0xFFFFFFFFL) xor tMask) and 0xFFFFFFFFL
                    val nVal = (eVal ushr 32) * 4294967296L + low
                    if ((nVal % 2L) != 0L) -((nVal + 1L) / 2L) else (nVal / 2L)
                }
                4 -> {
                    checkBounds(8)
                    val w0 = (readUInt32LE(rawBytes, pos) xor prngN()) and 0xFFFFFFFFL
                    val w1 = (readUInt32LE(rawBytes, pos + 4) xor prngN()) and 0xFFFFFFFFL
                    pos += 8
                    val bits = w0 or (w1 shl 32)
                    val valDouble = java.lang.Double.longBitsToDouble(bits)
                    numTable.add(valDouble)
                    valDouble
                }
                5 -> {
                    val length = readVarint().toInt()
                    checkBounds(length)
                    val tMask = prngN()
                    val buf = ByteArray(length)
                    for (rIdx in 0 until length) {
                        val b = rawBytes[pos + rIdx].toInt() and 0xFF
                        val m = ((tMask ushr (8 * (rIdx and 3))) and 0xFFL).toInt()
                        buf[rIdx] = (b xor m).toByte()
                    }
                    pos += length
                    val s = String(buf, Charsets.UTF_8)
                    strTable.add(s)
                    s
                }
                6 -> {
                    val length = readVarint().toInt()
                    if (length == 0) {
                        emptyList<Any?>()
                    } else {
                        val arr = MutableList<Any?>(length) { null }
                        val oStart = (prngA() % length).toInt()
                        val revDir = (prngA() and 1L) != 0L
                        for (rIdx in 0 until length) {
                            val step = if (revDir) (length - rIdx) else rIdx
                            val destIdx = (oStart + step) % length
                            arr[destIdx] = parseVal(depth + 1)
                        }
                        arr
                    }
                }
                7 -> {
                    val idx = readVarint().toInt()
                    strTable[idx]
                }
                8 -> {
                    val idx = readVarint().toInt()
                    numTable[idx]
                }
                else -> throw IllegalArgumentException("Unsupported ~5 tag: $tag")
            }
        }

        // 6. Реконструкція 6 площин даних L
        val lRoot = parseVal(0) as? List<*> ?: throw IllegalArgumentException("Root ~5 frame is not a list")
        if (lRoot.size < 6) {
            throw IllegalArgumentException("Invalid ~5 root structure (len < 6)")
        }

        val plane2 = lRoot[2] as? List<*> ?: emptyList<Any?>()
        val plane3 = lRoot[3] as? List<*> ?: emptyList<Any?>()
        val plane4 = lRoot[4] as? List<*> ?: emptyList<Any?>()
        val plane5 = lRoot[5] as? List<*> ?: emptyList<Any?>()

        val pubEnabledRaw = plane2.getOrNull(1)
        val publicTargetsEnabled = pubEnabledRaw != false && pubEnabledRaw != 0L && pubEnabledRaw != 0 && pubEnabledRaw != null

        val serverTime = (plane3.getOrNull(0) as? Number)?.toLong() ?: 0L
        val iUpd = (plane3.getOrNull(1) as? Number)?.toLong()

        val trackIds = (plane4.getOrNull(0) as? List<*>) ?: emptyList<Any?>()
        val uCells = (plane4.getOrNull(1) as? List<*>) ?: emptyList<Any?>()
        val dStyles = (plane4.getOrNull(2) as? List<*>) ?: emptyList<Any?>()

        val fTargets = (plane5.getOrNull(2) as? List<*>) ?: emptyList<Any?>()
        val pMov = plane5.getOrNull(3) as? List<*>

        return buildTargetsResponse(
            serverTime = serverTime,
            updatedAt = if (iUpd != null) serverTime + iUpd else serverTime,
            publicTargetsEnabled = publicTargetsEnabled,
            trackIds = trackIds,
            cells = uCells,
            dStyles = dStyles,
            fTargets = fTargets,
            pMovement = pMov
        )
    }

    /**
     * Декодує фрейм попередньої версії ~3:... для зворотної сумісності.
     */
    private fun decodeV3(payload: String): UaTargetsResponse? {
        val aStr = payload.substring(3)
        val rawBytes = RadarApiClient.decodeBase64(aStr)
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
        val iUpd = (d[1] as? Number)?.toLong()
        val cells = (d[5] as? List<*>) ?: emptyList<Any>()
        val dStyles = (d[6] as? List<*>) ?: emptyList<Any>()
        val trackIds = (d[7] as? List<*>) ?: emptyList<Any>()
        val fTargets = (d[8] as? List<*>) ?: emptyList<Any>()
        val pMovement = d[11] as? List<*>

        return buildTargetsResponse(
            serverTime = serverTime,
            updatedAt = if (iUpd != null) serverTime + iUpd else serverTime,
            publicTargetsEnabled = publicTargetsEnabled,
            trackIds = trackIds,
            cells = cells,
            dStyles = dStyles,
            fTargets = fTargets,
            pMovement = pMovement
        )
    }

    /**
     * Спільна функція реконструкції ідентифікаторів треків (UUID), активних цілей, векторів руху та прогнозів.
     */
    private fun buildTargetsResponse(
        serverTime: Long,
        updatedAt: Long,
        publicTargetsEnabled: Boolean,
        trackIds: List<*>,
        cells: List<*>,
        dStyles: List<*>,
        fTargets: List<*>,
        pMovement: List<*>?
    ): UaTargetsResponse {
        // Деобфускація ідентифікаторів треків (порядок байтів UUID)
        val order = intArrayOf(3, 2, 1, 0, 5, 4, 7, 6, 8, 9, 10, 11, 12, 13, 14, 15)
        val vTracks = mutableListOf<String>()
        for (tidObj in trackIds) {
            val tid = tidObj?.toString() ?: ""
            if (tid.startsWith("~~")) {
                vTracks.add(tid.substring(1))
            } else if (tid.startsWith("~")) {
                try {
                    val buf = RadarApiClient.decodeBase64(tid.substring(1))
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

        fun bTime(deltaObj: Any?): Long? {
            val delta = (deltaObj as? Number)?.toLong() ?: return null
            return serverTime + delta
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
            val gPred = if (item.size > 12) item[12] as? List<*> else null

            val trackId = if (tIdx in vTracks.indices) vTracks[tIdx] else "t_$tIdx"

            val cell = if (cellIdx in cells.indices) cells[cellIdx] as? List<*> else null
            val x = (cell?.getOrNull(0) as? Number)?.toDouble() ?: 0.0
            val y = (cell?.getOrNull(1) as? Number)?.toDouble() ?: 0.0

            val style = if (styleIdx in dStyles.indices) dStyles[styleIdx] as? List<*> else null
            val formular = style?.getOrNull(1)?.toString()
            val detectedType = style?.getOrNull(2)?.toString()
            val iconName = style?.getOrNull(3)?.toString()
            val iconScale = (style?.getOrNull(4) as? Number)?.toDouble() ?: 1.0

            var prediction: UaPrediction? = null
            if (gPred != null && gPred.size >= 6) {
                val hasOffsets = gPred.size > 6 && gPred[6] != null
                prediction = UaPrediction(
                    fromX = (gPred.getOrNull(0) as? Number)?.toDouble() ?: 0.0,
                    fromY = (gPred.getOrNull(1) as? Number)?.toDouble() ?: 0.0,
                    toX = (gPred.getOrNull(2) as? Number)?.toDouble() ?: 0.0,
                    toY = (gPred.getOrNull(3) as? Number)?.toDouble() ?: 0.0,
                    startedAt = bTime(gPred.getOrNull(4)),
                    endsAt = bTime(gPred.getOrNull(5)),
                    fromMarkerOffsetX = if (hasOffsets) (gPred.getOrNull(6) as? Number)?.toDouble() else null,
                    fromMarkerOffsetY = if (hasOffsets) (gPred.getOrNull(7) as? Number)?.toDouble() else null,
                    toMarkerOffsetX = if (hasOffsets) (gPred.getOrNull(8) as? Number)?.toDouble() else null,
                    toMarkerOffsetY = if (hasOffsets) (gPred.getOrNull(9) as? Number)?.toDouble() else null
                )
            }

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
                movementDurationMs = lDur,
                prediction = prediction
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
            serverTime = serverTime,
            updatedAt = updatedAt
        )
    }

    private fun readUInt32LE(bytes: ByteArray, offset: Int): Long {
        return (bytes[offset].toLong() and 0xFFL) or
                ((bytes[offset + 1].toLong() and 0xFFL) shl 8) or
                ((bytes[offset + 2].toLong() and 0xFFL) shl 16) or
                ((bytes[offset + 3].toLong() and 0xFFL) shl 24)
    }
}
