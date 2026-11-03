package com.coach.camera.logger

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import com.coach.camera.sensor.OrientationEngine
import com.coach.camera.sensor.ShotSessionSummary
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * MediaShotLogger:
 * - Lắng nghe sự kiện sinh file mới trong DCIM/Camera qua MediaStore ContentObserver
 * - Lọc nghiêm ngặt chỉ nhận file trong DCIM/Camera/ (loại bỏ Screenshots, Thumbnails)
 * - Chỉ nhận file sinh ra sau thời điểm bật HUD (tránh query trúng file cũ khi nhận collection URI)
 * - Tính khoảng thời gian quay = [lúc nhận sự kiện - DURATION, lúc nhận sự kiện] để trích xuất telemetry
 * - Ghi tên file thật (VID_...mp4 / IMG_...jpg), nhãn cỡ cảnh (ShotTag), góc Pitch/Roll và độ mượt vào shot_log.json
 */
class MediaShotLogger(
    private val context: Context,
    private val engine: OrientationEngine,
    private val onShotLogged: ((JSONObject) -> Unit)? = null
) {

    companion object {
        private const val TAG = "MediaShotLogger"
        private const val RELATIVE_DOC_DIR = "Documents/CameraCoach"
        private const val LOG_FILE_NAME = "shot_log.json"
        private val loggedFileNames = LinkedHashSet<String>()

        fun getPublicShotLogFile(): File {
            val docsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
            return File(File(docsDir, "CameraCoach"), LOG_FILE_NAME)
        }

        fun getAppFallbackShotLogFile(context: Context): File {
            val baseDir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: context.filesDir
            return File(File(baseDir, "CameraCoach"), LOG_FILE_NAME)
        }

        fun readAllLoggedShots(context: Context): JSONArray {
            val publicFile = getPublicShotLogFile()
            val targetFile = if (publicFile.exists() && publicFile.canRead()) {
                publicFile
            } else {
                getAppFallbackShotLogFile(context)
            }
            if (!targetFile.exists()) return JSONArray()
            return try {
                val content = targetFile.readText().trim()
                if (content.startsWith("[")) JSONArray(content) else JSONArray()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse shot_log.json", e)
                JSONArray()
            }
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val contentResolver: ContentResolver = context.contentResolver
    private var isRegistered = false
    private var hudStartTimestampSec: Long = System.currentTimeMillis() / 1000L - 5L

    private val mediaObserver = object : ContentObserver(mainHandler) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            super.onChange(selfChange, uri)
            if (uri == null) return
            // Mốc thời gian nhận sự kiện ≈ lúc Camera vừa dừng quay & giải phóng IS_PENDING
            val notifiedAtMs = System.currentTimeMillis()
            // Trì hoãn nhẹ 400ms để Camera Xiaomi hoàn tất ghi metadata và giải phóng IS_PENDING
            mainHandler.postDelayed({ inspectMediaUri(uri, notifiedAtMs) }, 400L)
        }
    }

    fun startObserving() {
        if (isRegistered) return
        hudStartTimestampSec = System.currentTimeMillis() / 1000L - 5L
        try {
            contentResolver.registerContentObserver(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                true,
                mediaObserver
            )
            contentResolver.registerContentObserver(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                true,
                mediaObserver
            )
            isRegistered = true
            Log.i(TAG, "MediaShotLogger started observing MediaStore (since sec=$hudStartTimestampSec)")
        } catch (e: Exception) {
            Log.e(TAG, "Unable to register MediaStore ContentObserver", e)
        }
    }

    fun stopObserving() {
        if (!isRegistered) return
        try {
            contentResolver.unregisterContentObserver(mediaObserver)
        } catch (e: Exception) {
            Log.w(TAG, "Error unregistering ContentObserver", e)
        }
        isRegistered = false
        Log.i(TAG, "MediaShotLogger stopped observing")
    }

    private fun inspectMediaUri(uri: Uri, notifiedAtMs: Long) {
        val hasSpecificId = try {
            ContentUris.parseId(uri) > 0L
        } catch (_: Exception) {
            false
        }

        val projection = mutableListOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.DATE_MODIFIED
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(MediaStore.MediaColumns.RELATIVE_PATH)
                add(MediaStore.MediaColumns.IS_PENDING)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(MediaStore.Video.VideoColumns.DURATION)
            } else {
                @Suppress("DEPRECATION")
                add(MediaStore.MediaColumns.DATA)
            }
        }.toTypedArray()

        try {
            // Android 11+ MediaProvider từ chối "LIMIT" nhét trong sortOrder (IllegalArgumentException),
            // nên dùng query Bundle với QUERY_ARG_* (có từ API 26 = minSdk)
            val queryArgs = if (hasSpecificId) {
                null
            } else {
                Bundle().apply {
                    putString(
                        ContentResolver.QUERY_ARG_SQL_SELECTION,
                        "${MediaStore.MediaColumns.DATE_ADDED} >= ?"
                    )
                    putStringArray(
                        ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                        arrayOf(hudStartTimestampSec.toString())
                    )
                    putStringArray(
                        ContentResolver.QUERY_ARG_SORT_COLUMNS,
                        arrayOf(MediaStore.MediaColumns.DATE_ADDED)
                    )
                    putInt(
                        ContentResolver.QUERY_ARG_SORT_DIRECTION,
                        ContentResolver.QUERY_SORT_DIRECTION_DESCENDING
                    )
                    putInt(ContentResolver.QUERY_ARG_LIMIT, 1)
                }
            }

            contentResolver.query(uri, projection, queryArgs, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return

                val nameIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                if (nameIdx < 0) return
                val fileName = cursor.getString(nameIdx) ?: return

                // 1. Kiểm tra trạng thái IS_PENDING (Android 10+)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val pendingIdx = cursor.getColumnIndex(MediaStore.MediaColumns.IS_PENDING)
                    if (pendingIdx >= 0 && cursor.getInt(pendingIdx) != 0) {
                        return
                    }
                }

                // 2. Lọc nghiêm ngặt thư mục DCIM/Camera/ (loại bỏ Screenshots, Thumbnails, v.v.)
                var isInCameraFolder = false
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val pathIdx = cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                    val relPath = if (pathIdx >= 0) cursor.getString(pathIdx).orEmpty() else ""
                    if (relPath.contains("DCIM/Camera", ignoreCase = true)) {
                        isInCameraFolder = true
                    }
                } else {
                    @Suppress("DEPRECATION")
                    val dataIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                    val dataPath = if (dataIdx >= 0) cursor.getString(dataIdx).orEmpty() else ""
                    if (dataPath.contains("DCIM/Camera", ignoreCase = true)) {
                        isInCameraFolder = true
                    }
                }

                if (!isInCameraFolder) {
                    return
                }

                // 3. Kiểm tra thời gian sinh file phải sau khi bật HUD
                val dateAddedIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_ADDED)
                val fileDateAddedSec = if (dateAddedIdx >= 0) cursor.getLong(dateAddedIdx) else 0L
                if (fileDateAddedSec > 0L && fileDateAddedSec < hudStartTimestampSec) {
                    return
                }

                // 4. Chống ghi trùng lặp cho cùng một tên file
                synchronized(loggedFileNames) {
                    if (loggedFileNames.contains(fileName)) return
                    loggedFileNames.add(fileName)
                    if (loggedFileNames.size > 200) {
                        val first = loggedFileNames.iterator().next()
                        loggedFileNames.remove(first)
                    }
                }

                // 5. Tính khoảng thời gian quay clip: neo mốc KẾT THÚC rồi lùi lại DURATION.
                // Không dùng DATE_TAKEN làm mốc kết thúc vì nhiều app Camera ghi DATE_TAKEN = lúc BẮT ĐẦU quay.
                val durationIdx = cursor.getColumnIndex(MediaStore.Video.VideoColumns.DURATION)
                val durationMs = if (durationIdx >= 0) cursor.getLong(durationIdx) else 0L

                val dateModifiedIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
                val dateModifiedMs = if (dateModifiedIdx >= 0) cursor.getLong(dateModifiedIdx) * 1000L else 0L

                // Mặc định mốc kết thúc = lúc nhận sự kiện (chính xác tới ms). Nếu sự kiện tới trễ
                // (DATE_MODIFIED sớm hơn > 3s) thì tin DATE_MODIFIED (độ phân giải 1s) hơn.
                val clipEndMs = if (dateModifiedMs > 0L && notifiedAtMs - dateModifiedMs > 3_000L) {
                    dateModifiedMs
                } else {
                    notifiedAtMs
                }
                val clipStartMs = if (durationMs > 0L) (clipEndMs - durationMs) else (clipEndMs - 3000L)

                val timestampSec = if (fileDateAddedSec > 0L) fileDateAddedSec else (clipEndMs / 1000L)

                val summary = engine.getSessionSummaryForWindow(clipStartMs, clipEndMs)
                appendShotLogRecord(fileName, timestampSec, summary)
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Missing READ_MEDIA permission to inspect $uri: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Error inspecting MediaStore URI: $uri", e)
        }
    }

    /**
     * Ghi nhận 1 bản ghi vào shot_log.json kèm thông số telemetry chính xác của cú máy.
     */
    fun appendShotLogRecord(
        fileName: String,
        timestampSec: Long,
        summary: ShotSessionSummary
    ): JSONObject {
        val entry = JSONObject().apply {
            put("file_name", fileName)
            put("timestamp", timestampSec)
            put("mode", engine.currentMode.id)
            put("shot_tag", engine.currentShotTag.tag)
            put("avg_pitch_deg", roundTo(summary.avgPitchDeg, 1))
            put("avg_roll_deg", roundTo(summary.avgRollDeg, 1))
            put("max_gyro_rad_s", roundTo(summary.maxGyroRadS, 2))
            put("is_smooth", summary.isSmooth)
        }

        val currentArray = readAllLoggedShots(context)
        currentArray.put(entry)
        val prettyJson = currentArray.toString(2)

        // 1. Lưu bản dự phòng luôn thành công trong App External Documents
        try {
            val appFile = getAppFallbackShotLogFile(context)
            appFile.parentFile?.mkdirs()
            appFile.writeText(prettyJson)
        } catch (e: Exception) {
            Log.e(TAG, "Failed writing fallback shot_log.json", e)
        }

        // 2. Lưu vào /Documents/CameraCoach/shot_log.json để dễ chép sang PC/Laptop ThinkBook
        try {
            val publicFile = getPublicShotLogFile()
            publicFile.parentFile?.mkdirs()
            publicFile.writeText(prettyJson)
            Log.i(TAG, "Logged new shot to ${publicFile.absolutePath}: $entry")
        } catch (e: Exception) {
            writePublicDocumentViaMediaStore(prettyJson)
        }

        onShotLogged?.invoke(entry)
        return entry
    }

    private fun writePublicDocumentViaMediaStore(jsonContent: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        try {
            val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
            val selectionArgs = arrayOf(LOG_FILE_NAME, "$RELATIVE_DOC_DIR%")

            var existingUri: Uri? = null
            contentResolver.query(
                collection,
                arrayOf(MediaStore.MediaColumns._ID),
                selection,
                selectionArgs,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val id = cursor.getLong(0)
                    existingUri = Uri.withAppendedPath(collection, id.toString())
                }
            }

            val targetUri = existingUri ?: run {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, LOG_FILE_NAME)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "$RELATIVE_DOC_DIR/")
                }
                contentResolver.insert(collection, values)
            }

            targetUri?.let { uri ->
                contentResolver.openOutputStream(uri, "wt")?.use { out ->
                    out.write(jsonContent.toByteArray(Charsets.UTF_8))
                }
                Log.i(TAG, "Updated /Documents/CameraCoach/shot_log.json via MediaStore")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not write public MediaStore shot_log.json: ${e.message}")
        }
    }

    private fun roundTo(value: Float, decimals: Int): Double {
        if (value.isNaN() || value.isInfinite()) return 0.0
        return BigDecimal(value.toDouble()).setScale(decimals, RoundingMode.HALF_UP).toDouble()
    }
}
