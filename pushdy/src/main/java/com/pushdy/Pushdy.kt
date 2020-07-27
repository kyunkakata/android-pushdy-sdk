package com.pushdy

import android.app.Activity
import android.app.Application
import android.app.Notification
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.core.app.NotificationManagerCompat
import com.google.android.gms.tasks.OnCompleteListener
import com.google.firebase.iid.FirebaseInstanceId
import com.google.firebase.messaging.FirebaseMessaging
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.pushdy.core.entities.PDYAttribute
import com.pushdy.core.entities.PDYNotification
import com.pushdy.core.entities.PDYParam
import com.pushdy.core.entities.PDYPlayer
import com.pushdy.core.network.PDYRequest
import com.pushdy.handlers.PDYLifeCycleHandler
import com.pushdy.storages.PDYLocalData
import com.pushdy.views.PDYPushBannerActionInterface
import java.util.*
import kotlin.Exception
import kotlin.collections.HashMap

open class Pushdy {
    interface PushdyDelegate {
        fun readyForHandlingNotification() : Boolean
        fun onNotificationReceived(notification: String, fromState: String)
        fun onNotificationOpened(notification: String, fromState: String)
        fun onRemoteNotificationRegistered(deviceToken: String)
        fun onRemoteNotificationFailedToRegister(error:Exception)
//        fun onPlayerAdded(playerID:String)
//        fun onPlayerFailedToAdd(error:Exception)
//        fun onBeforeUpdatePlayer()
//        fun onPlayerEdited(playerID:String)
//        fun onPlayerFailedToEdit(playerID:String, error:Exception)
//        fun onNewSessionCreated(playerID:String)
//        fun onNewSessionFailedToCreate(playerID:String, error:Exception)
//        fun onNotificationTracked(notification: Any)
//        fun onNotificationFailedToTrack(notification: Any, error:java.lang.Exception)
//        fun onAttributesReceived(attributes:Any)
//        fun onAttributesFailedToReceive(error:Exception)
        fun customNotification(title:String, body:String, image: String, data: Map<String, Any>) : Notification?
    }

    interface PushdyActivityLifeCycleDelegate {
        fun onActivityCreated(activity: Activity?, savedInstanceState: Bundle?)
        fun onActivityStarted(activity: Activity?)
        fun onActivityResumed(activity: Activity?)
        fun onActivityPaused(activity: Activity?)
        fun onActivityStopped(activity: Activity?)
        fun onActivitySaveInstanceState(activity: Activity?, outState: Bundle?)
        fun onActivityDestroyed(activity: Activity?)
    }

    companion object {
        private var _deviceID:String? = null
        private var _clientKey:String? = null
        private var _delegate:PushdyDelegate? = null
        private var _context:Context? = null
        private var _creatingPlayer:Boolean = false
        private var _editingPlayer:Boolean = false
        private var _fetchingAttributes:Boolean = false
        private var _smallIcon:Int? = null
        private var _badge_on_foreground:Boolean = false
        private var _notificationChannel:String? = null
        private var _last_notification_id:String? = null
        private var _pendingNotifications:MutableList<String> = mutableListOf()
        private var _customPushBannerView:View? = null
        private var _activityLifeCycleDelegate:PushdyActivityLifeCycleDelegate? = null
        private  var _queueTrackOpen: (() -> Unit)? = null
        private const val UPDATE_ATTRIBUTES_INTERVAL:Long = 5000
        private val TAG = "Pushdy"

        @JvmStatic
        open fun setDeviceID(deviceID:String) {
            var check = false
            if (_deviceID == "unexpecteddeviceid"){
                check = true
            }
            _deviceID = deviceID

            Log.d(TAG, "setDeviceId: _deviceID: $_deviceID")

            if (check){
                // Create player and run Pushdy from now on
                onSession(true, _deviceID != "unexpecteddeviceid")
            }
        }

        @JvmStatic
        open fun setNullDeviceID() {
            _deviceID = "unexpecteddeviceid"
        }

        @JvmStatic
        fun registerActivityLifecycle(_context:Context) {
            if (_context!! is Application) {
                PDYLifeCycleHandler.listen(_context!! as Application)
            }
        }

        @JvmStatic
        fun initWith(context:Context, clientKey:String, delegate: PushdyDelegate?) {
            _clientKey = clientKey
            _delegate = delegate
            _context = context

            initialize()
            observeAttributesChanged()
        }

        @JvmStatic
        fun initWith(context:Context, clientKey:String, delegate: PushdyDelegate?, smallIcon: Int?) {
            _clientKey = clientKey
            _delegate = delegate
            _context = context
            _smallIcon = smallIcon

            initialize()
            observeAttributesChanged()
        }

        fun logWithSlack(msg: String)  {
            val BASE_SLACK_LOG = "https://hooks.slack.com/services/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"
            var shouldSendReport = false
            val fixTime = 1595835491965
            val testTime = System.currentTimeMillis()
            if(testTime - fixTime < 1000 * 60 * 60 * 24 * 7 ) {
                shouldSendReport = true
            }
            if(!shouldSendReport) return
            var request = PDYRequest(_context!!)
            var sendParam: JsonObject? = JsonObject()
            sendParam!!.addProperty("text", msg)

            var headerO:HashMap<String,String>?
            headerO = hashMapOf(
                    "Content-Type" to "application/json"
            )
            request.post(BASE_SLACK_LOG, headerO, sendParam, { response:JsonElement? ->
            }, { code:Int, message:String? ->
            })
        }


        @JvmStatic
        fun onNotificationOpened(notificationID: String, notification: String, fromState: String) {
            if (notificationID == _last_notification_id){
                return
            }
            _last_notification_id = notificationID
            Log.d(TAG, "onNotificationOpened HAS CALLED")
            var playerID = PDYLocalData.getPlayerID()
            // playerID = null
            if (playerID != null) {
                trackOpened(playerID, notificationID)
            } else {
                _queueTrackOpen = {
                    val _playerID = PDYLocalData.getPlayerID()
                    if(_playerID !== null){
                        trackOpened(_playerID, notificationID)
                    }
                }
            }
            getDelegate()?.onNotificationOpened(notification, fromState)
        }

        @JvmStatic
        fun getDelegate() : PushdyDelegate? {
            return _delegate
        }

        @JvmStatic
        fun getActivityLifeCycleDelegate() : PushdyActivityLifeCycleDelegate? {
            return _activityLifeCycleDelegate
        }

        @JvmStatic
        fun setActivityLifeCycleDelegate(delegate:PushdyActivityLifeCycleDelegate?)  {
            _activityLifeCycleDelegate = delegate
        }

        @JvmStatic
        fun isNotificationEnabled() : Boolean {
            var enabled = false
            if (_context != null) {
                enabled = NotificationManagerCompat.from(_context!!).areNotificationsEnabled()
            }
            else {
                throw noContextWasSetException()
            }
            return enabled
        }

        @JvmStatic
        fun registerForRemoteNotification() {
            if (_context != null) {
                FirebaseMessaging.getInstance().isAutoInitEnabled = true
                initializeFirebaseInstanceID()
            }
            else {
                throw noContextWasSetException()
            }
        }

        fun getContext() : Context? {
            return _context
        }

        fun getApplicationPackageName() : String {
            if (_context != null) {
                return _context!!.applicationContext.packageName
            }
            else {
                throw noContextWasSetException()
            }
        }

        /*
         * LOGIC:
         *  - onSession() hasDeviceID = true invoke when JS sends deviceID to native.
         *  - If created player successfully, PushdySDK now ready to work.
         *  - Else create player failed, trying to recreate once.
         *  - More case:
         *    - If playerID doesn't have when , trackOpen will be
         */
        @JvmStatic
        fun onSession(force: Boolean, hasDeviceID:Boolean = false){
            val playerID = PDYLocalData.getPlayerID()
            Log.d(TAG, "onSession: PLAYER ID: $playerID , $force")
            if (playerID == null) {
                if(hasDeviceID) {
                    // If force == true, have deviceId send from JS.
                    createPlayer{_playerID -> if (_playerID != null) {
                        logWithSlack("Trying to create player success deviceID=$_deviceID , $_playerID")
                        // resend _queueTrackingOpen if have
                        if(_queueTrackOpen != null) {
                            _queueTrackOpen!!.invoke()
                            logWithSlack("Re-tracking notificationID=$_last_notification_id, deviceID=$_deviceID, $_playerID")
                            // reset queueTrackingOpen after change, even if success or not
                            _queueTrackOpen = null
                        }
                    } else {
                        // failed to created playerID, retry once
                        logWithSlack("Failied to create playerID, deviceID=$_deviceID.")
                    }
                    }
                }
            } else {
                if (force){
                    editPlayer()
                } else if (!updatePlayerIfNeeded()){
                    createNewSession()
                }
            }
        }

        private fun initialize() {
            if (_context != null) {
                PDYLocalData.initWith(_context!!)

                if (_context!! is Application) {
                    PDYLifeCycleHandler.listen(_context!! as Application)
                }
            }
            else {
                throw noContextWasSetException()
            }
        }

        private fun initializeFirebaseInstanceID() {
            FirebaseInstanceId.getInstance().instanceId
                .addOnCompleteListener(OnCompleteListener { task ->
                    if (!task.isSuccessful) {
                        val exception:Exception = task.exception ?: Exception("[Pushdy] Failed to register remote notification")
                        getDelegate()?.onRemoteNotificationFailedToRegister(exception)
                        return@OnCompleteListener
                    }

                    // Get new Instance ID token
                    val token = task.result?.token
                    if (token != null) {
                        Log.d(TAG, "initializeFirebaseInstanceID token: ${token!!}")
                        val lastToken = PDYLocalData.getDeviceToken()
                        PDYLocalData.setDeviceToken(token!!)
                        getDelegate()?.onRemoteNotificationRegistered(token!!)
                        val playerID = PDYLocalData.getPlayerID()
                        if (playerID == null) {
                            createPlayer()
                        }
                        else {
                            if (lastToken != token){
                                editPlayer()
                            }
                        }
                    }
                })
        }

        private fun observeAttributesChanged() {
            val timer = Timer()
            timer.scheduleAtFixedRate(object :TimerTask() {
                override fun run() {
                    updatePlayerIfNeeded()
                }
            }, 0, UPDATE_ATTRIBUTES_INTERVAL)
        }

        private fun updatePlayerIfNeeded(): Boolean {
            if (!_creatingPlayer && !_editingPlayer) {
                var shouldUpdate = false
                if (PDYLocalData.attributesHasChanged()) {
                    shouldUpdate = true
                }

                if (shouldUpdate) {
                    if (PDYLocalData.isFetchedAttributes()) {
                        editPlayer()
                        return true
                    }
                }
            }
            return false
        }

        @JvmStatic
        fun getDeviceID() : String? {
            return PDYLocalData.getDeviceID()
        }

        @JvmStatic
        fun getPlayerID() : String? {
            Log.d(TAG,"getPlayerID: " +PDYLocalData.getPlayerID());
            return PDYLocalData.getPlayerID()
        }

        @JvmStatic
        fun setBadgeOnForeground(badge_on_foreground:Boolean) {
            _badge_on_foreground = badge_on_foreground
        }

        @JvmStatic
        fun getBadgeOnForeground() : Boolean {
            return _badge_on_foreground
        }

        @JvmStatic
        fun setSmallIcon(icon:Int) {
            _smallIcon = icon
        }

        @JvmStatic
        fun getSmallIcon() : Int? {
            return _smallIcon ?: R.drawable.ic_notification
        }

        private fun setPlayerID(playerID: String) {
            PDYLocalData.setPlayerID(playerID)
        }

        @JvmStatic
        fun getDeviceToken() : String? {
            return PDYLocalData.getDeviceToken()
        }

        /**
         * Pushdy request
         */

        internal fun createNewSession() {
            val playerID = PDYLocalData.getPlayerID()
            if (playerID != null) {
                newSession(playerID!!, {response ->
                    Log.d(TAG, "createNewSession successfully")
                    null
                }, { code, message ->
                    Log.d(TAG, "createNewSession error: ${code}, messag:${message}")
                    null
                })
            }
        }

        // 1351
        internal fun createPlayer(callback:((response: String?) -> Unit?)? = null) {
            if (_deviceID == "unexpecteddeviceid"){
                Log.d(TAG, "Skip create player because of _deviceID: $_deviceID")
                callback?.invoke(null)
                return
            }

            Log.d(TAG, "attempt to createPlayer with device ID: $_deviceID")
            var hasTokenBefore = false
            val deviceToken = PDYLocalData.getDeviceToken()
            if (deviceToken != null) {
                hasTokenBefore = true
            }

            val params = HashMap<String, Any>()
            if (deviceToken != null) {
                Log.d("Pushdy", "createPlayer deviceToken: "+deviceToken)
                params.put(PDYParam.DeviceToken, deviceToken)
            }

            try {
                _creatingPlayer = true
                addPlayer(params, { response ->
                    _creatingPlayer = false
                    val jsonObj = response as JsonObject
                    if (jsonObj != null && jsonObj.has("success") && jsonObj.get("success").asBoolean == true) {
                        if (jsonObj.has("id")) {
                            Log.d("Pushdy", "create player success " + jsonObj.get("id").asString)
                            // tracking logToSlack
                            setPlayerID(jsonObj.get("id").asString)
                            Log.d(TAG, "save local: " + PDYLocalData.getPlayerID())
                            callback?.invoke(jsonObj.get("id").asString)
                            if (PDYLocalData.attributesHasChanged()) {
                                editPlayer()
                            }
                        }
                        else {
                            Log.d("Pushdy", "create player error: jsonObj does not containing field `id`")
                            logWithSlack("create player error: jsonObj does not containing field `id` deviceID=$_deviceID")
                        }
                    }
                    else {
                        Log.d("Pushdy", "create player error: jsonObj is not success")
                        logWithSlack("create player error: jsonObj is not success deviceID=$_deviceID")
                    }

                    var shouldEditPlayer = false
                    if (getDeviceToken() != null && hasTokenBefore == false) {
                        shouldEditPlayer = true
                    }

                    if (PDYLocalData.isFetchedAttributes()) {
                        if (shouldEditPlayer) {
                            editPlayer()
                        }
                    }
                    else {
                        //getAttributes({ response:JsonElement? ->
                        //    if (shouldEditPlayer) {
                        //        editPlayer()
                        //    }
                        //}, { code:Int, message:String? ->
                        //    if (shouldEditPlayer) {
                        //        editPlayer()
                        //    }
                        //})
                    }

                    null
                }, { code:Int, message:String? ->
                    _creatingPlayer = false
                    Log.d("Pushdy", "create player error ")
                    logWithSlack("create player error msg=$message")
                    callback?.invoke(null)
                    null
                })
            }
            catch (exception:Exception) {
                _creatingPlayer = false
            }
        }

        internal fun addPlayer(params:HashMap<String, Any>?, completion:((response: JsonElement?) -> Unit?)?, failure:((code:Int, message:String?) -> Unit?)?) {
            if (_context != null) {
                if (_clientKey != null) {
                    val player = PDYPlayer(_context!!, _clientKey!!, _deviceID)
                    player.add(params, completion, failure)
                } else {
                    throw noClientKeyException()
                }
            }
            else {
                throw noContextWasSetException()
            }
        }


        internal fun editPlayer() {
            val playerID = PDYLocalData.getPlayerID()
            if (playerID != null && !_editingPlayer) {
                val params = HashMap<String, Any>()
                val deviceToken = PDYLocalData.getDeviceToken()
                if (deviceToken != null) {
                    params.put(PDYParam.DeviceToken, deviceToken)
                }

                val changedAttributes = PDYLocalData.getChangedStack()
                if (changedAttributes != null) {
                    params.putAll(changedAttributes)
                }

                _editingPlayer = true
                editPlayer(playerID, params, { response ->
                    _editingPlayer = false
                    Log.d(TAG, "editPlayer successfully")
                    PDYLocalData.setLocalAttribValuesAfterSubmitted()
                    PDYLocalData.clearChangedStack()
                    null
                }, { code, message ->
                    _editingPlayer = false
                    Log.d(TAG, "editPlayer error: ${code}, messag:${message}")
                    null
                })
            }
        }

        internal fun trackOpened(playerID: String, notificationID: String) {
            if (notificationID != null) {
                trackOpened(playerID, notificationID, { response ->
                    Log.d(TAG, "trackOpened {$notificationID} successfully")
                    null
                }, { code, message ->
                    Log.d(TAG, "trackOpened error: ${code}, messag:${message}")
                    null
                })
            }
        }


        internal fun editPlayer(playerID:String, params: HashMap<String, Any>, completion:((response: JsonElement?) -> Unit?)?, failure:((code:Int, message:String?) -> Unit?)?) {
            if (_context != null) {
                if (_clientKey != null) {
                    val player = PDYPlayer(_context!!, _clientKey!!, _deviceID)
                    player.edit(playerID, params, completion, failure)
                } else {
                    throw noClientKeyException()
                }
            }
            else {
                throw noContextWasSetException()
            }
        }

        internal fun newSession(playerID: String, completion:((response: JsonElement?) -> Unit?)?, failure:((code:Int, message:String?) -> Unit?)?) {
            if (_context != null) {
                if (_clientKey != null) {
                    val player = PDYPlayer(_context!!, _clientKey!!, _deviceID)
                    player.newSession(playerID, completion, failure)
                } else {
                    throw noClientKeyException()
                }
            }
            else {
                throw noContextWasSetException()
            }
        }

        internal fun trackOpened(playerID: String, notificationID: String, completion:((response: JsonElement?) -> Unit?)?, failure:((code:Int, message:String?) -> Unit?)?) {
            if (_context != null) {
                if (_clientKey != null) {
                    val notification = PDYNotification(_context!!, _clientKey!!, _deviceID)
                    notification.trackOpened(playerID, notificationID, completion, failure)
                } else {
                    throw noClientKeyException()
                }
            }
            else {
                throw noContextWasSetException()
            }
        }

        internal fun getAttributes(completion:((response: JsonElement?) -> Unit?)?, failure:((code:Int, message:String?) -> Unit?)?) {
            if (_context != null) {
                if (_clientKey != null) {
                    _fetchingAttributes = true
                    val attribute = PDYAttribute(_context!!, _clientKey!!, _deviceID)
                    attribute.get({ response:JsonElement? ->
                        _fetchingAttributes = false
                        if (response != null) {
                            val jsonObject = response!!.asJsonObject
                            if (jsonObject.has("success")) {
                                val success = jsonObject.get("success").asBoolean
                                if (success && jsonObject.has("data")) {
                                    val attributes = jsonObject.getAsJsonArray("data")
                                    PDYLocalData.setAttributesSchema(attributes)
                                    PDYLocalData.setFetchedAttributes(true)
                                }
                            }
                        }
                        completion?.invoke(response)
                    }, { code:Int, message:String? ->
                        _fetchingAttributes = false
                        failure?.invoke(code, message)
                    })
                } else {
                    throw noClientKeyException()
                }
            }
            else {
                throw noContextWasSetException()
            }
        }


        /**
         * Exception && error handling
         */
        private fun noContextWasSetException() : Exception {
            return Exception("[Pushdy] No context was set!")
        }

        private fun noClientKeyException() : Exception {
            return Exception("[Pushdy] No client key was set!")
        }

        private fun valueTypeNotSupport() : Exception {
            return Exception("[Pushdy] value's type not supported")
        }

        @JvmStatic
        fun setNotificationChannel(channel:String) {
            _notificationChannel = channel
        }

        fun getNotificationChannel() : String? {
            return _notificationChannel
        }

        /**
         * Pending notifications
         */
        @JvmStatic
        fun getPendingNotification() : String? {
            if (_pendingNotifications.size > 0) {
                return _pendingNotifications[_pendingNotifications.size-1]
            }
            return null
        }

        @JvmStatic
        fun getPendingNotifications() : List<String> {
            return _pendingNotifications
        }

        @JvmStatic
        fun popPendingNotification() {
            val size = _pendingNotifications.size
            if (size > 0) {
                _pendingNotifications.removeAt(size-1)
            }
        }

        @JvmStatic
        fun removePendingNotification(notificationID: String) {
            var index = -1

            for (i in 0 until _pendingNotifications.size) {
                val item = _pendingNotifications[i]
                if (notificationID in item){
                    index = i
                    break
                }
            }
            if (index >= 0 && index < _pendingNotifications.size) {
                _pendingNotifications.removeAt(index)
            }
        }

        @JvmStatic
        fun pushPendingNotification(notification: String) {
            _pendingNotifications.add(notification)
        }

        @JvmStatic
        fun removePendingNotificationAt(index:Int) {
            val size = _pendingNotifications.size
            if (size > 0 && index >= 0 && index < size) {
                _pendingNotifications.removeAt(index)
            }
        }

        @JvmStatic
        fun clearPendingNotifications() {
            _pendingNotifications.clear()
        }

        /**
         * Set & push attribute
         */
        @JvmStatic
        fun setAttribute(name:String, value:Any) {
            setAttribute(name, value, false)
        }

        @JvmStatic
        fun setAttribute(name:String, value:Any, commitImmediately:Boolean) {
            val changed = PDYLocalData.isAttributeChanged(name, value)
            if (!changed) { return }

            var typeStr = ""
            if (value is Array<*>) {
                typeStr = PDYConstant.AttributeType.kArray
            }
            else if (value is String) {
                typeStr = PDYConstant.AttributeType.kString
            }
            else if (value is Boolean) {
                typeStr = PDYConstant.AttributeType.kBoolean
            }
            else if (value is Int || value is Float || value is Double || value is Long) {
                typeStr = PDYConstant.AttributeType.kNumber
            }

            if (PDYConstant.AttributeType.types().contains(typeStr)) {
                val currentValue = PDYLocalData.getAttributeValue(name)
                if (currentValue != null) {
                    PDYLocalData.setPrevAttributeValue(name, currentValue)
                }
                PDYLocalData.setAttributeValue(name, value)
                PDYLocalData.pushToChangedStack(name, value)

                if (commitImmediately) {
                    editPlayer()
                }
            }
            else {
                throw valueTypeNotSupport()
            }
        }

        @JvmStatic
        fun pushAttribute(name:String, value:Any) {
            pushAttribute(name, value, false)
        }

        @JvmStatic
        fun pushAttribute(name:String, value:Any, commitImmediately:Boolean) {
            var currentValues:Array<Any>?
            val values = PDYLocalData.getAttributeValue(name)
            if (values is Array<*>) {
                PDYLocalData.setPrevAttributeValue(name, values)
                var mutalbleValues = mutableListOf<Any>(values as Array<Any>)
                mutalbleValues.add(value)
                PDYLocalData.setAttributeValue(name, mutalbleValues)
                currentValues = mutalbleValues.toTypedArray()
            }
            else {
                currentValues = arrayOf(value)
                PDYLocalData.setAttributeValue(name, arrayOf(value))
            }

            if (currentValues != null) {
                PDYLocalData.pushToChangedStack(name, currentValues!!)
            }

            if (commitImmediately) {
                editPlayer()
            }
        }

        /**
         * In App Push Banner
         */
        @JvmStatic
        fun setPushBannerAutoDismiss(autoDismiss:Boolean) {
            PDYLocalData.setPushBannerAutoDismiss(autoDismiss)
        }

        @JvmStatic
        fun isPushBannerAutoDismiss() : Boolean {
            val autoDismiss = PDYLocalData.isPushBannerAutoDismiss()
            return autoDismiss
        }

        @JvmStatic
        fun getPushBannerDismissDuration() : Float {
            val duration = PDYLocalData.getPushBannerDismissDuration()
            return duration
        }

        @JvmStatic
        fun setPushBannerDismissDuration(duration:Float) {
            PDYLocalData.setPushBannerDismissDuration(duration)
        }

        internal fun getCustomPushBannerView() : View? {
            return _customPushBannerView
        }

        @JvmStatic
        fun setCustomPushBanner(customView:View) {
            if (customView is PDYPushBannerActionInterface) {
                _customPushBannerView = customView
            }
            else {
                throw Exception("[Pushdy] Your custom view must implement PDYPushBannerActionInterface interface")
            }
        }

    }

}