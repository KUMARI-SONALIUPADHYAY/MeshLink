package com.meshlink.app.core.voice

import android.content.Context
import java.io.File
import java.util.UUID

/** Reassembles bounded voice chunks into the original local AAC/M4A asset. */
class VoiceReassembler(private val context: Context) {
    private data class Bucket(
        val total: Int,
        val durationMs: Long,
        val parts: MutableMap<Int, ByteArray>
    )

    private val buckets = HashMap<UUID, Bucket>()

    @Synchronized
    fun accept(chunk: VoiceChunk): File? {
        if (chunk.total !in 1..1200) return null
        if (chunk.index !in 0 until chunk.total) return null

        val bucket = buckets.getOrPut(chunk.messageId) {
            Bucket(chunk.total, chunk.durationMs, HashMap())
        }

        // Reject inconsistent metadata instead of corrupting an existing transfer.
        if (bucket.total != chunk.total || bucket.durationMs != chunk.durationMs) return null

        bucket.parts[chunk.index] = chunk.data
        if (bucket.parts.size != bucket.total) return null

        val assembled = ByteArray(bucket.parts.values.sumOf { it.size })
        var offset = 0
        for (index in 0 until bucket.total) {
            val part = bucket.parts[index] ?: run {
                buckets.remove(chunk.messageId)
                return null
            }
            part.copyInto(assembled, destinationOffset = offset)
            offset += part.size
        }

        val output = File(context.filesDir, "voice_${chunk.messageId}.m4a")
        output.outputStream().use { it.write(assembled) }
        buckets.remove(chunk.messageId)
        return output
    }
}
