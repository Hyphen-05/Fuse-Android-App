package com.example.core.modecapture

import com.example.db.CustomMode
import java.io.StringWriter
import java.io.Writer
import org.json.JSONObject

data class SpatialSample(val positionIndex: Int, val r: Int, val g: Int, val b: Int)

data class CaptureFrame(val elapsedMs: Long, val samples: List<SpatialSample>)

data class ModeCaptureRecord(
    val byteValue: Int,
    val name: String,
    val category: String,
    val frames: List<CaptureFrame>,
    val complete: Boolean
)

data class ReferenceCapture(
    val knownR: Int,
    val knownG: Int,
    val knownB: Int,
    val measuredSamples: List<SpatialSample>
)

data class ModeCaptureExport(
    val exportedAtEpochMs: Long,
    val samplePositionCount: Int,
    val reference: ReferenceCapture?,
    val modes: List<ModeCaptureRecord>
)

// Auto Play is always byteValue 0 (RgbControllerViewModel's custom-mode seed list) — filtering on
// the primary key is stable across renames, unlike matching on name/category which are user-editable.
fun modesToCapture(all: List<CustomMode>): List<CustomMode> = all.filter { it.byteValue != 0 }

object ModeCaptureExporter {

    private const val SCHEMA_NOTES =
        "positionIndex 0 = first strip-endpoint tap, increasing toward the second tap. " +
            "Frame samples are raw camera readings under locked exposure/AWB/focus — no color " +
            "correction has been applied on-device. Use the top-level `reference` block (known vs. " +
            "measured RGB) to derive and apply a correction during offline analysis."

    /** Small-data convenience wrapper (tests, in-memory callers) — buffers the whole result in a
     * String via [writeJson]. Real device exports must call [writeJson] directly against a file
     * Writer instead: a full capture run's frame data can reach tens of megabytes of JSON text, and
     * this path builds that entire string in memory before the caller can even start writing it. */
    fun toJson(export: ModeCaptureExport): String {
        val stringWriter = StringWriter()
        writeJson(export, stringWriter)
        return stringWriter.toString()
    }

    /** Streams [export] straight to [writer], hand-rolled rather than going through a JSONObject/
     * JSONArray tree — building that tree and then serializing it into one giant String (the previous
     * implementation) is what OOM'd on a real full-length capture run: `ModeCaptureExporter.toJson`,
     * `java.lang.OutOfMemoryError: Failed to allocate a 75497480 byte allocation`. [JSONObject.quote]
     * is reused for string escaping since it's the same escaping org.json itself uses; there's no
     * `org.json.JSONWriter` available in this project's compileSdk stub to delegate the rest to. */
    fun writeJson(export: ModeCaptureExport, writer: Writer) {
        writer.write("{\"exportedAtEpochMs\":")
        writer.write(export.exportedAtEpochMs.toString())
        writer.write(",\"samplePositionCount\":")
        writer.write(export.samplePositionCount.toString())
        writer.write(",\"notes\":")
        writer.write(JSONObject.quote(SCHEMA_NOTES))
        writer.write(",\"reference\":")
        val reference = export.reference
        if (reference != null) writeReference(writer, reference) else writer.write("null")
        writer.write(",\"modes\":[")
        export.modes.forEachIndexed { index, mode ->
            if (index > 0) writer.write(",")
            writeMode(writer, mode)
        }
        writer.write("]}")
    }

    private fun writeReference(writer: Writer, reference: ReferenceCapture) {
        writer.write("{\"knownR\":")
        writer.write(reference.knownR.toString())
        writer.write(",\"knownG\":")
        writer.write(reference.knownG.toString())
        writer.write(",\"knownB\":")
        writer.write(reference.knownB.toString())
        writer.write(",\"measuredSamples\":")
        writeSamples(writer, reference.measuredSamples)
        writer.write("}")
    }

    private fun writeMode(writer: Writer, mode: ModeCaptureRecord) {
        writer.write("{\"byteValue\":")
        writer.write(mode.byteValue.toString())
        writer.write(",\"name\":")
        writer.write(JSONObject.quote(mode.name))
        writer.write(",\"category\":")
        writer.write(JSONObject.quote(mode.category))
        writer.write(",\"complete\":")
        writer.write(mode.complete.toString())
        writer.write(",\"frames\":[")
        mode.frames.forEachIndexed { index, frame ->
            if (index > 0) writer.write(",")
            writer.write("{\"elapsedMs\":")
            writer.write(frame.elapsedMs.toString())
            writer.write(",\"samples\":")
            writeSamples(writer, frame.samples)
            writer.write("}")
        }
        writer.write("]}")
    }

    private fun writeSamples(writer: Writer, samples: List<SpatialSample>) {
        writer.write("[")
        samples.forEachIndexed { index, sample ->
            if (index > 0) writer.write(",")
            writer.write("{\"positionIndex\":")
            writer.write(sample.positionIndex.toString())
            writer.write(",\"r\":")
            writer.write(sample.r.toString())
            writer.write(",\"g\":")
            writer.write(sample.g.toString())
            writer.write(",\"b\":")
            writer.write(sample.b.toString())
            writer.write("}")
        }
        writer.write("]")
    }
}
