package com.pushdy.services

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.pushdy.Pushdy
import com.pushdy.core.entities.PDYParam
import com.pushdy.handlers.PDYNotificationHandler
import com.pushdy.storages.PDYLocalData

open class PDYFirebaseMessagingService : FirebaseMessagingService() {
    private val TAG = "FCMService"

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)

        Log.d(TAG, "onMessageReceived HAS CALLED")
        // Process received message
        val data = message.data
        if (data != null) {
            val title = message.notification?.title ?: ""
            val body = message.notification?.body ?: ""

            Log.d(TAG, "onMessageReceived title: $title, body: $body")
            Log.d(TAG, "data: $data")
            // Check ready state
            var ready = true
            val delegate = Pushdy.getDelegate()
            if (delegate != null && delegate!!.readyForHandlingNotification()) {
                ready = delegate!!.readyForHandlingNotification()
            }

            if (ready) { // Process immediately
                PDYNotificationHandler.process(title, body, data)
            }
            else { // Push notification to pending stack
                val pendingNotification:MutableMap<String, Any> = mutableMapOf()
                pendingNotification.put("title", title)
                pendingNotification.put("body", body)
                pendingNotification.put("data", data)
                Pushdy.pushPendingNotification(pendingNotification)
            }
        }
    }

    override fun onMessageSent(msgId: String) {
        super.onMessageSent(msgId)
    }

    override fun onDeletedMessages() {
        super.onDeletedMessages()
    }

    override fun onNewToken(token: String) {
        Log.d(TAG, "onNewToken: $token")
        // Check is new token or not
        val oldToken = PDYLocalData.getDeviceToken()
        var notEqual = false
        if (oldToken != null) {
            notEqual = oldToken != token
        }
        else {
            notEqual = true
        }


        PDYLocalData.setDeviceToken(token)


        if (notEqual) {
            // Push to change stack or edit player immediately
            PDYLocalData.pushToChangedStack(PDYParam.DeviceToken, token)
            Pushdy.editPlayer()
        }
    }
}