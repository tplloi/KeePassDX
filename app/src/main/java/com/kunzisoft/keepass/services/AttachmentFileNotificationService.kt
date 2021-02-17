/*
 * Copyright 2019 Jeremy Jamet / Kunzisoft.
 *
 * This file is part of KeePassDX.
 *
 *  KeePassDX is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  KeePassDX is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with KeePassDX.  If not, see <http://www.gnu.org/licenses/>.
 *
 */
package com.kunzisoft.keepass.services

import android.app.PendingIntent
import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.kunzisoft.keepass.R
import com.kunzisoft.keepass.database.element.Attachment
import com.kunzisoft.keepass.database.element.Database
import com.kunzisoft.keepass.database.element.database.BinaryAttachment
import com.kunzisoft.keepass.model.AttachmentState
import com.kunzisoft.keepass.model.EntryAttachmentState
import com.kunzisoft.keepass.model.StreamDirection
import com.kunzisoft.keepass.stream.readAllBytes
import com.kunzisoft.keepass.utils.UriUtil
import kotlinx.coroutines.*
import java.util.*
import java.util.concurrent.CopyOnWriteArrayList


class AttachmentFileNotificationService: LockNotificationService() {

    override val notificationId: Int = 10000
    private val attachmentNotificationList = CopyOnWriteArrayList<AttachmentNotification>()

    private var mActionTaskBinder = ActionTaskBinder()
    private var mActionTaskListeners = LinkedList<ActionTaskListener>()

    private val mainScope = CoroutineScope(Dispatchers.Main)

    override fun retrieveChannelId(): String {
        return CHANNEL_ATTACHMENT_ID
    }

    override fun retrieveChannelName(): String {
        return getString(R.string.entry_attachments)
    }

    inner class ActionTaskBinder: Binder() {

        fun getService(): AttachmentFileNotificationService = this@AttachmentFileNotificationService

        fun addActionTaskListener(actionTaskListener: ActionTaskListener) {
            mActionTaskListeners.add(actionTaskListener)
        }

        fun removeActionTaskListener(actionTaskListener: ActionTaskListener) {
            mActionTaskListeners.remove(actionTaskListener)
        }
    }

    private val attachmentFileActionListener = object: AttachmentFileAction.AttachmentFileActionListener {
        override fun onUpdate(attachmentNotification: AttachmentNotification) {
            newNotification(attachmentNotification)
            mActionTaskListeners.forEach { actionListener ->
                actionListener.onAttachmentAction(attachmentNotification.uri,
                        attachmentNotification.entryAttachmentState)
            }
        }
    }

    interface ActionTaskListener {
        fun onAttachmentAction(fileUri: Uri, entryAttachmentState: EntryAttachmentState)
    }

    override fun onBind(intent: Intent): IBinder? {
        return mActionTaskBinder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        val downloadFileUri: Uri? = if (intent?.hasExtra(FILE_URI_KEY) == true) {
            intent.getParcelableExtra(FILE_URI_KEY)
        } else null

        when(intent?.action) {
            ACTION_ATTACHMENT_FILE_START_UPLOAD -> {
                actionStartUploadOrDownload(downloadFileUri,
                        intent,
                        StreamDirection.UPLOAD)
            }
            ACTION_ATTACHMENT_FILE_STOP_UPLOAD -> {
                actionStopUpload()
            }
            ACTION_ATTACHMENT_FILE_START_DOWNLOAD -> {
                actionStartUploadOrDownload(downloadFileUri,
                        intent,
                        StreamDirection.DOWNLOAD)
            }
            ACTION_ATTACHMENT_REMOVE -> {
                intent.getParcelableExtra<Attachment>(ATTACHMENT_KEY)?.let { entryAttachment ->
                    attachmentNotificationList.firstOrNull { it.entryAttachmentState.attachment == entryAttachment }?.let { elementToRemove ->
                        attachmentNotificationList.remove(elementToRemove)
                    }
                }
            }
            else -> {
                if (downloadFileUri != null) {
                    attachmentNotificationList.firstOrNull { it.uri == downloadFileUri }?.let { elementToRemove ->
                        notificationManager?.cancel(elementToRemove.notificationId)
                        attachmentNotificationList.remove(elementToRemove)
                    }
                }
                if (attachmentNotificationList.isEmpty()) {
                    stopSelf()
                }
            }
        }

        return START_REDELIVER_INTENT
    }

    @Synchronized
    fun checkCurrentAttachmentProgress() {
        attachmentNotificationList.forEach { attachmentNotification ->
            mActionTaskListeners.forEach { actionListener ->
                actionListener.onAttachmentAction(
                        attachmentNotification.uri,
                        attachmentNotification.entryAttachmentState
                )
            }
        }
    }

    @Synchronized
    fun removeAttachmentAction(entryAttachment: EntryAttachmentState) {
        attachmentNotificationList.firstOrNull {
            it.entryAttachmentState == entryAttachment
        }?.let {
            attachmentNotificationList.remove(it)
        }
    }

    private fun newNotification(attachmentNotification: AttachmentNotification) {

        val pendingContentIntent = PendingIntent.getActivity(this,
                0,
                Intent().apply {
                    action = Intent.ACTION_VIEW
                    setDataAndType(attachmentNotification.uri,
                            contentResolver.getType(attachmentNotification.uri))
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }, PendingIntent.FLAG_CANCEL_CURRENT)

        val pendingDeleteIntent =  PendingIntent.getService(this,
                0,
                Intent(this, AttachmentFileNotificationService::class.java).apply {
                    // No action to delete the service
                    putExtra(FILE_URI_KEY, attachmentNotification.uri)
                }, PendingIntent.FLAG_CANCEL_CURRENT)

        val fileName = DocumentFile.fromSingleUri(this, attachmentNotification.uri)?.name ?: ""

        val builder = buildNewNotification().apply {
            when (attachmentNotification.entryAttachmentState.streamDirection) {
                StreamDirection.UPLOAD -> {
                    setSmallIcon(R.drawable.ic_file_upload_white_24dp)
                    setContentTitle(getString(R.string.upload_attachment, fileName))
                }
                StreamDirection.DOWNLOAD -> {
                    setSmallIcon(R.drawable.ic_file_download_white_24dp)
                    setContentTitle(getString(R.string.download_attachment, fileName))
                }
            }
            setAutoCancel(false)
            when (attachmentNotification.entryAttachmentState.downloadState) {
                AttachmentState.NULL, AttachmentState.START -> {
                    setContentText(getString(R.string.download_initialization))
                    setOngoing(true)
                }
                AttachmentState.IN_PROGRESS -> {
                    if (attachmentNotification.entryAttachmentState.downloadProgression > 100) {
                        setContentText(getString(R.string.download_finalization))
                    } else {
                        setProgress(100,
                                attachmentNotification.entryAttachmentState.downloadProgression,
                                false)
                        setContentText(getString(R.string.download_progression,
                                attachmentNotification.entryAttachmentState.downloadProgression))
                    }
                    setOngoing(true)
                }
                AttachmentState.COMPLETE -> {
                    setContentText(getString(R.string.download_complete))
                    when (attachmentNotification.entryAttachmentState.streamDirection) {
                        StreamDirection.UPLOAD -> {

                        }
                        StreamDirection.DOWNLOAD -> {
                            setContentIntent(pendingContentIntent)
                        }
                    }
                    setDeleteIntent(pendingDeleteIntent)
                    setOngoing(false)
                }
                AttachmentState.CANCELED -> {
                    setContentText(getString(R.string.download_canceled))
                    setDeleteIntent(pendingDeleteIntent)
                    setOngoing(false)
                }
                AttachmentState.ERROR -> {
                    setContentText(getString(R.string.error_file_not_create))
                    setDeleteIntent(pendingDeleteIntent)
                    setOngoing(false)
                }
            }
        }
        when (attachmentNotification.entryAttachmentState.downloadState) {
            AttachmentState.COMPLETE,
            AttachmentState.CANCELED,
            AttachmentState.ERROR -> {
                stopForeground(false)
                notificationManager?.notify(attachmentNotification.notificationId, builder.build())
            } else -> {
                startForeground(attachmentNotification.notificationId, builder.build())
            }
        }
    }

    override fun onDestroy() {
        attachmentNotificationList.forEach { attachmentNotification ->
            attachmentNotification.attachmentFileAction?.cancel()
            attachmentNotification.attachmentFileAction?.listener = null
            notificationManager?.cancel(attachmentNotification.notificationId)
        }
        attachmentNotificationList.clear()

        super.onDestroy()
    }

    private data class AttachmentNotification(var uri: Uri,
                                              var notificationId: Int,
                                              var entryAttachmentState: EntryAttachmentState,
                                              var attachmentFileAction: AttachmentFileAction? = null) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as AttachmentNotification

            if (notificationId != other.notificationId) return false

            return true
        }

        override fun hashCode(): Int {
            return notificationId
        }
    }

    private fun actionStartUploadOrDownload(fileUri: Uri?,
                                            intent: Intent,
                                            streamDirection: StreamDirection) {
        if (fileUri != null
                && intent.hasExtra(ATTACHMENT_KEY)) {
            try {
                intent.getParcelableExtra<Attachment>(ATTACHMENT_KEY)?.let { entryAttachment ->

                    val nextNotificationId = (attachmentNotificationList.maxByOrNull { it.notificationId }
                            ?.notificationId ?: notificationId) + 1
                    val entryAttachmentState = EntryAttachmentState(entryAttachment, streamDirection)
                    val attachmentNotification = AttachmentNotification(fileUri, nextNotificationId, entryAttachmentState)

                    // Add action to the list on start
                    attachmentNotificationList.add(attachmentNotification)

                    mainScope.launch {
                        AttachmentFileAction(attachmentNotification,
                                contentResolver).apply {
                            listener = attachmentFileActionListener
                        }.executeAction()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Unable to upload/download $fileUri", e)
            }
        }
    }

    private fun actionStopUpload() {
        try {
            // Stop each upload
            attachmentNotificationList.filter {
                it.entryAttachmentState.streamDirection == StreamDirection.UPLOAD
            }.forEach {
                it.attachmentFileAction?.cancel()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Unable to stop upload", e)
        }
    }

    private class AttachmentFileAction(
            private val attachmentNotification: AttachmentNotification,
            private val contentResolver: ContentResolver) {

        private val updateMinFrequency = 1000
        private var previousSaveTime = System.currentTimeMillis()
        var listener: AttachmentFileActionListener? = null

        interface AttachmentFileActionListener {
            fun onUpdate(attachmentNotification: AttachmentNotification)
        }

        suspend fun executeAction() {

            // on pre execute
            CoroutineScope(Dispatchers.Main).launch {
                attachmentNotification.attachmentFileAction = this@AttachmentFileAction
                attachmentNotification.entryAttachmentState.apply {
                    downloadState = AttachmentState.START
                    downloadProgression = 0
                }
                listener?.onUpdate(attachmentNotification)
            }

            withContext(Dispatchers.IO) {
                // on Progress with thread
                val asyncAction = launch {
                    attachmentNotification.entryAttachmentState.apply {
                        try {
                                downloadState = AttachmentState.IN_PROGRESS

                                when (streamDirection) {
                                    StreamDirection.UPLOAD -> {
                                        uploadToDatabase(
                                                attachmentNotification.uri,
                                                attachment.binaryAttachment,
                                                contentResolver, 1024,
                                                { // Cancellation
                                                    downloadState == AttachmentState.CANCELED
                                                }
                                        ) { percent ->
                                            publishProgress(percent)
                                        }
                                    }
                                    StreamDirection.DOWNLOAD -> {
                                        downloadFromDatabase(
                                                attachmentNotification.uri,
                                                attachment.binaryAttachment,
                                                contentResolver, 1024) { percent ->
                                            publishProgress(percent)
                                        }
                                    }
                                }
                        } catch (e: Exception) {
                            e.printStackTrace()
                            downloadState = AttachmentState.ERROR
                        }
                    }
                    attachmentNotification.entryAttachmentState.downloadState
                }

                // on post execute
                withContext(Dispatchers.Main) {
                    asyncAction.join()
                    attachmentNotification.entryAttachmentState.apply {
                        if (downloadState != AttachmentState.CANCELED
                                && downloadState != AttachmentState.ERROR) {
                            downloadState = AttachmentState.COMPLETE
                            downloadProgression = 100
                        }
                    }
                    attachmentNotification.attachmentFileAction = null
                    listener?.onUpdate(attachmentNotification)
                }

            }
        }

        fun cancel() {
            attachmentNotification.entryAttachmentState.downloadState = AttachmentState.CANCELED
        }

        fun downloadFromDatabase(attachmentToUploadUri: Uri,
                                 binaryAttachment: BinaryAttachment,
                                 contentResolver: ContentResolver,
                                 bufferSize: Int = DEFAULT_BUFFER_SIZE,
                                 update: ((percent: Int)->Unit)? = null) {
            var dataDownloaded = 0L
            val fileSize = binaryAttachment.length
            UriUtil.getUriOutputStream(contentResolver, attachmentToUploadUri)?.use { outputStream ->
                Database.getInstance().loadedCipherKey?.let { binaryCipherKey ->
                    binaryAttachment.getUnGzipInputDataStream(binaryCipherKey).use { inputStream ->
                        inputStream.readAllBytes(bufferSize) { buffer ->
                            outputStream.write(buffer)
                            dataDownloaded += buffer.size
                            try {
                                val percentDownload = (100 * dataDownloaded / fileSize).toInt()
                                update?.invoke(percentDownload)
                            } catch (e: Exception) {
                                Log.e(TAG, "", e)
                            }
                        }
                    }
                }
            }
        }

        fun uploadToDatabase(attachmentFromDownloadUri: Uri,
                             binaryAttachment: BinaryAttachment,
                             contentResolver: ContentResolver,
                             bufferSize: Int = DEFAULT_BUFFER_SIZE,
                             canceled: ()-> Boolean = { false },
                             update: ((percent: Int)->Unit)? = null) {
            var dataUploaded = 0L
            val fileSize = contentResolver.openFileDescriptor(attachmentFromDownloadUri, "r")?.statSize ?: 0
            UriUtil.getUriInputStream(contentResolver, attachmentFromDownloadUri)?.use { inputStream ->
                Database.getInstance().loadedCipherKey?.let { binaryCipherKey ->
                    binaryAttachment.getGzipOutputDataStream(binaryCipherKey).use { outputStream ->
                        inputStream.readAllBytes(bufferSize, canceled) { buffer ->
                            outputStream.write(buffer)
                            dataUploaded += buffer.size
                            try {
                                val percentDownload = (100 * dataUploaded / fileSize).toInt()
                                update?.invoke(percentDownload)
                            } catch (e: Exception) {
                                Log.e(TAG, "", e)
                            }
                        }
                    }
                }
            }
        }

        private fun publishProgress(percent: Int) {
            // Publish progress
            val currentTime = System.currentTimeMillis()
            if (previousSaveTime + updateMinFrequency < currentTime) {
                attachmentNotification.entryAttachmentState.apply {
                    if (downloadState != AttachmentState.CANCELED) {
                        downloadState = AttachmentState.IN_PROGRESS
                    }
                    downloadProgression = percent
                }
                CoroutineScope(Dispatchers.Main).launch {
                    listener?.onUpdate(attachmentNotification)
                    Log.d(TAG, "Download file ${attachmentNotification.uri} : $percent%")
                }
                previousSaveTime = currentTime
            }
        }

        companion object {
            private val TAG = AttachmentFileAction::class.java.name
        }
    }

    companion object {
        private val TAG = AttachmentFileNotificationService::javaClass.name

        private const val CHANNEL_ATTACHMENT_ID = "com.kunzisoft.keepass.notification.channel.attachment"

        const val ACTION_ATTACHMENT_FILE_START_UPLOAD = "ACTION_ATTACHMENT_FILE_START_UPLOAD"
        const val ACTION_ATTACHMENT_FILE_STOP_UPLOAD = "ACTION_ATTACHMENT_FILE_STOP_UPLOAD"
        const val ACTION_ATTACHMENT_FILE_START_DOWNLOAD = "ACTION_ATTACHMENT_FILE_START_DOWNLOAD"
        const val ACTION_ATTACHMENT_REMOVE = "ACTION_ATTACHMENT_REMOVE"

        const val FILE_URI_KEY = "FILE_URI_KEY"
        const val ATTACHMENT_KEY = "ATTACHMENT_KEY"
    }

}