package com.ko2ic.imagedownloader

import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.ko2ic.imagedownloader.ImageDownloaderPlugin.TemporaryDatabase.Companion.COLUMNS
import com.ko2ic.imagedownloader.ImageDownloaderPlugin.TemporaryDatabase.Companion.TABLE_NAME
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.PluginRegistry.Registrar
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.net.URLConnection
import java.text.SimpleDateFormat
import java.util.*

class ImageDownloaderPlugin : FlutterPlugin, ActivityAware, MethodCallHandler {
    companion object {
        @JvmStatic
        fun registerWith(registrar: Registrar) {
            val activity = registrar.activity() ?: return
            val context = registrar.context()
            val applicationContext = context.applicationContext
            val pluginInstance = ImageDownloaderPlugin()
            pluginInstance.setup(
                registrar.messenger(), applicationContext, activity, registrar, null
            )
        }

        private const val CHANNEL = "plugins.ko2ic.com/image_downloader"
        private const val LOGGER_TAG = "image_downloader"
    }

    private lateinit var channel: MethodChannel
    private lateinit var permissionListener: ImageDownloaderPermissionListener
    private lateinit var pluginBinding: FlutterPlugin.FlutterPluginBinding

    private var activityBinding: ActivityPluginBinding? = null
    private var applicationContext: Context? = null

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        pluginBinding = binding
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        tearDown()
    }

    override fun onAttachedToActivity(activityPluginBinding: ActivityPluginBinding) {
        setup(
            pluginBinding.binaryMessenger,
            pluginBinding.applicationContext,
            activityPluginBinding.activity,
            null,
            activityPluginBinding
        )
    }

    override fun onDetachedFromActivity() {
        tearDown()
    }

    override fun onDetachedFromActivityForConfigChanges() {
        onDetachedFromActivity()
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        onAttachedToActivity(binding)
    }

    private fun setup(
        messenger: BinaryMessenger,
        applicationContext: Context,
        activity: Activity,
        registrar: Registrar?,
        activityBinding: ActivityPluginBinding?
    ) {
        this.applicationContext = applicationContext
        channel = MethodChannel(messenger, CHANNEL)
        channel.setMethodCallHandler(this)
        permissionListener = ImageDownloaderPermissionListener(activity)

        if (registrar != null) {
            // V1 embedding setup for activity listeners.
            registrar.addRequestPermissionsResultListener(permissionListener)
        } else {
            // V2 embedding setup for activity listeners.
            this.activityBinding = activityBinding
            this.activityBinding?.addRequestPermissionsResultListener(permissionListener)
        }
    }

    private fun tearDown() {
        activityBinding?.removeRequestPermissionsResultListener(permissionListener)
        channel.setMethodCallHandler(null)
        applicationContext = null
    }

    private var inPublicDir: Boolean = true

    private var callback: CallbackImpl? = null

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "downloadImage" -> {
                inPublicDir = call.argument<Boolean>("inPublicDir") ?: true

                val permissionCallback =
                    applicationContext?.let { CallbackImpl(call, result, channel, it) }
                this.callback = permissionCallback
                if (inPublicDir) {
                    this.permissionListener.callback = permissionCallback
                    if (permissionListener.alreadyGranted()) {
                        permissionCallback?.granted()
                    }
                } else {
                    permissionCallback?.granted()
                }
            }
            "cancel" -> {
                callback?.downloader?.cancel()
            }
            "open" -> {
                open(call, result)
            }

            "findPath" -> {
                val imageId = call.argument<String>("imageId")
                    ?: throw IllegalArgumentException("imageId is required.")
                val filePath = applicationContext?.let { findPath(imageId, it) }
                result.success(filePath)
            }
            "findName" -> {
                val imageId = call.argument<String>("imageId")
                    ?: throw IllegalArgumentException("imageId is required.")
                val fileName = applicationContext?.let { findName(imageId, it) }
                result.success(fileName)
            }
            "findByteSize" -> {
                val imageId = call.argument<String>("imageId")
                    ?: throw IllegalArgumentException("imageId is required.")
                val fileSize = applicationContext?.let { findByteSize(imageId, it) }
                result.success(fileSize)
            }
            "findMimeType" -> {
                val imageId = call.argument<String>("imageId")
                    ?: throw IllegalArgumentException("imageId is required.")
                val mimeType = applicationContext?.let { findMimeType(imageId, it) }
                result.success(mimeType)
            }
            else -> result.notImplemented()
        }
    }

    private fun open(call: MethodCall, result: MethodChannel.Result) {

        val path =
            call.argument<String>("path") ?: throw IllegalArgumentException("path is required.")

        val file = File(path)
        val intent = Intent(Intent.ACTION_VIEW)

        val fileExtension = MimeTypeMap.getFileExtensionFromUrl(file.path)
        val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(fileExtension)

        if (Build.VERSION.SDK_INT >= 24) {
            val uri = applicationContext?.let {
                FileProvider.getUriForFile(
                    it, "${applicationContext?.packageName}.image_downloader.provider", file
                )
            }
            intent.setDataAndType(uri, mimeType)
        } else {
            intent.setDataAndType(Uri.fromFile(file), mimeType)
        }

        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

        val manager = applicationContext?.packageManager
        if (manager != null) {
            if (manager.queryIntentActivities(intent, 0).size == 0) {
                result.error("preview_error", "This file is not supported for previewing", null)
            } else {
                applicationContext?.startActivity(intent)
            }
        }

    }

    private fun findPath(imageId: String, context: Context): String {
        val data = findFileData(imageId, context)
        return data.path
    }

    private fun findName(imageId: String, context: Context): String {
        val data = findFileData(imageId, context)
        return data.name
    }

    private fun findByteSize(imageId: String, context: Context): Int {
        val data = findFileData(imageId, context)
        return data.byteSize
    }

    private fun findMimeType(imageId: String, context: Context): String {
        val data = findFileData(imageId, context)
        return data.mimeType
    }

    @SuppressLint("Range")
    private fun findFileData(imageId: String, context: Context): FileData {

        if (inPublicDir) {
            val contentResolver = context.contentResolver
            return contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                null,
                "${MediaStore.Images.Media._ID}=?",
                arrayOf(imageId),
                null
            ).use {
                checkNotNull(it) { "$imageId is an imageId that does not exist." }
                it.moveToFirst()
                val path = it.getString(it.getColumnIndex(MediaStore.Images.Media.DATA))
                val name = it.getString(it.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME))
                val size = it.getInt(it.getColumnIndex(MediaStore.Images.Media.SIZE))
                val mimeType = it.getString(it.getColumnIndex(MediaStore.Images.Media.MIME_TYPE))
                FileData(path = path, name = name, byteSize = size, mimeType = mimeType)
            }
        } else {
            val db = TemporaryDatabase(context).readableDatabase
            return db.query(
                TABLE_NAME,
                COLUMNS,
                "${MediaStore.Images.Media._ID}=?",
                arrayOf(imageId),
                null,
                null,
                null,
                null
            ).use {
                it.moveToFirst()
                val path = it.getString(it.getColumnIndex(MediaStore.Images.Media.DATA))
                val name = it.getString(it.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME))
                val size = it.getInt(it.getColumnIndex(MediaStore.Images.Media.SIZE))
                val mimeType = it.getString(it.getColumnIndex(MediaStore.Images.Media.MIME_TYPE))
                FileData(path = path, name = name, byteSize = size, mimeType = mimeType)
            }
        }
    }

    class CallbackImpl(
        private val call: MethodCall,
        private val result: MethodChannel.Result,
        private val channel: MethodChannel,
        private val context: Context
    ) : ImageDownloaderPermissionListener.Callback {

        var downloader: Downloader? = null

        override fun granted() {
            val url =
                call.argument<String>("url") ?: throw IllegalArgumentException("url is required.")

            val headers: Map<String, String>? = call.argument<Map<String, String>>("headers")

            val outputMimeType = call.argument<String>("mimeType")
            val inPublicDir = call.argument<Boolean>("inPublicDir") ?: true
            val directoryType = call.argument<String>("directory") ?: "DIRECTORY_DOWNLOADS"
            val subDirectory = call.argument<String>("subDirectory")
            val tempSubDirectory = subDirectory ?: SimpleDateFormat(
                "yyyy-MM-dd.HH.mm.sss", Locale.getDefault()
            ).format(Date())

            val directory = convertToDirectory(directoryType)

            val uri = Uri.parse(url)
            val request = DownloadManager.Request(uri)

            //request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            request.allowScanningByMediaScanner()

            if (headers != null) {
                for ((key, value) in headers) {
                    request.addRequestHeader(key, value)
                }
            }

            if (inPublicDir) {
                request.setDestinationInExternalPublicDir(directory, tempSubDirectory)
            } else {
                TemporaryDatabase(context).writableDatabase.delete(TABLE_NAME, null, null)
                request.setDestinationInExternalFilesDir(context, directory, tempSubDirectory)
            }

            val downloader = Downloader(context, request)
            this.downloader = downloader

            downloader.execute(onNext = {
                Log.d(LOGGER_TAG, it.result.toString())
                when (it) {
                    is Downloader.DownloadStatus.Failed -> Log.d(LOGGER_TAG, it.reason)
                    is Downloader.DownloadStatus.Paused -> Log.d(LOGGER_TAG, it.reason)
                    // fix error
                    // 'when' expression must be exhaustive, add necessary 'is Pending', 'is Successful' branches or 'else' branch instead
                    is Downloader.DownloadStatus.Successful -> Log.d(LOGGER_TAG, "Successful")
                    is Downloader.DownloadStatus.Pending -> Log.d(LOGGER_TAG, "Pending")
                    is Downloader.DownloadStatus.Running -> {
                        Log.d(LOGGER_TAG, it.progress.toString())
                        val args = HashMap<String, Any>()
                        args["image_id"] = it.result.id.toString()
                        args["progress"] = it.progress

                        val uiThreadHandler = Handler(Looper.getMainLooper())

                        uiThreadHandler.post {
                            channel.invokeMethod("onProgressUpdate", args)
                        }
                    }
                    else -> throw AssertionError()
                }

            }, onError = {
                result.error(it.code, it.message, null)
            }, onComplete = {

                val file = if (inPublicDir) {
                    File("${Environment.getExternalStoragePublicDirectory(directory)}/$tempSubDirectory")
                } else {
                    File("${context.getExternalFilesDir(directory)}/$tempSubDirectory")
                }

                if (!file.exists()) {
                    result.error(
                        "save_error",
                        "Couldn't save ${file.absolutePath ?: tempSubDirectory} ",
                        null
                    )
                } else {
                    val stream = BufferedInputStream(FileInputStream(file))
                    val mimeType =
                        outputMimeType ?: URLConnection.guessContentTypeFromStream(stream)

                    val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)

                    val fileName = when {
                        subDirectory != null -> subDirectory
                        extension != null -> "$tempSubDirectory.$extension"
                        else -> uri.lastPathSegment?.split("/")?.last() ?: "file"
                    }

                    val newFile = if (inPublicDir) {
                        File("${Environment.getExternalStoragePublicDirectory(directory)}/$fileName")
                    } else {
                        File("${context.getExternalFilesDir(directory)}/$fileName")
                    }

                    file.renameTo(newFile)
                    val newMimeType = mimeType ?: MimeTypeMap.getSingleton()
                        .getMimeTypeFromExtension(newFile.extension) ?: ""
                    val imageId = saveToDatabase(newFile, mimeType ?: newMimeType, inPublicDir)

                    result.success(imageId)
                }
            })
        }

        override fun denied() {
            result.success(null)
        }

        private fun convertToDirectory(directoryType: String): String {
            return when (directoryType) {
                "DIRECTORY_DOWNLOADS" -> Environment.DIRECTORY_DOWNLOADS
                "DIRECTORY_PICTURES" -> Environment.DIRECTORY_PICTURES
                "DIRECTORY_DCIM" -> Environment.DIRECTORY_DCIM
                "DIRECTORY_MOVIES" -> Environment.DIRECTORY_MOVIES
                else -> directoryType
            }
        }

        @SuppressLint("Range")
        private fun saveToDatabase(file: File, mimeType: String, inPublicDir: Boolean): String {
            val path = file.absolutePath
            val name = file.name
            val size = file.length()

            val contentValues = ContentValues()
            contentValues.put(MediaStore.Images.Media.MIME_TYPE, mimeType)
            contentValues.put(MediaStore.Images.Media.DATA, path)
            contentValues.put(MediaStore.Images.ImageColumns.DISPLAY_NAME, name)
            contentValues.put(MediaStore.Images.ImageColumns.SIZE, size)
            if (inPublicDir) {

                context.contentResolver.insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues
                )
                return context.contentResolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATA),
                    "${MediaStore.Images.Media.DATA}=?",
                    arrayOf(file.absolutePath),
                    null
                ).use {
                    checkNotNull(it) { "${file.absolutePath} is not found." }
                    it.moveToFirst()
                    it.getString(it.getColumnIndex(MediaStore.Images.Media._ID))
                }
            } else {
                val db = TemporaryDatabase(context)
                val allowedChars = "ABCDEFGHIJKLMNOPQRSTUVWXTZabcdefghiklmnopqrstuvwxyz0123456789"
                val id = (1..20).map { allowedChars.random() }.joinToString("")
                contentValues.put(MediaStore.Images.Media._ID, id)
                db.writableDatabase.insert(TABLE_NAME, null, contentValues)
                return id
            }
        }
    }

    private data class FileData(
        val path: String, val name: String, val byteSize: Int, val mimeType: String
    )

    class TemporaryDatabase(context: Context) :
        SQLiteOpenHelper(context, TABLE_NAME, null, DATABASE_VERSION) {


        companion object {

            val COLUMNS = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.MIME_TYPE,
                MediaStore.Images.Media.DATA,
                MediaStore.Images.ImageColumns.DISPLAY_NAME,
                MediaStore.Images.ImageColumns.SIZE
            )

            private const val DATABASE_VERSION = 1
            const val TABLE_NAME = "image_downloader_temporary"
            private const val DICTIONARY_TABLE_CREATE =
                "CREATE TABLE " + TABLE_NAME + " (" + MediaStore.Images.Media._ID + " TEXT, " + MediaStore.Images.Media.MIME_TYPE + " TEXT, " + MediaStore.Images.Media.DATA + " TEXT, " + MediaStore.Images.ImageColumns.DISPLAY_NAME + " TEXT, " + MediaStore.Images.ImageColumns.SIZE + " INTEGER" + ");"
        }

        override fun onUpgrade(db: SQLiteDatabase?, oldVersion: Int, newVersion: Int) {
        }

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(DICTIONARY_TABLE_CREATE)
        }
    }
}
