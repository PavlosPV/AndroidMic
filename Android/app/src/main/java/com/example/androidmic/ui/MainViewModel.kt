package com.example.androidmic.ui

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.*
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.example.androidmic.AndroidMicApp
import com.example.androidmic.R
import com.example.androidmic.domain.service.ForegroundService
import com.example.androidmic.ui.utils.Preferences
import com.example.androidmic.utils.Command
import com.example.androidmic.utils.Command.Companion.COMMAND_DISC_STREAM
import com.example.androidmic.utils.Command.Companion.COMMAND_GET_STATUS
import com.example.androidmic.utils.CommandService
import com.example.androidmic.utils.Modes.Companion.MODE_WIFI
import com.example.androidmic.utils.States
import kotlinx.coroutines.launch


class MainViewModel(application: Application,
                    private val savedStateHandle: SavedStateHandle
                    ): AndroidViewModel(application) {

    private val TAG = "MainViewModel"
    private val WAIT_PERIOD = 500L

    val uiStates = savedStateHandle.getStateFlow("uiStates", States.UiStates())

    private  val preferences = Preferences(application as AndroidMicApp)

    private var mService: Messenger? = null
    private var mBound = false

    private lateinit var handlerThread: HandlerThread
    private lateinit var mMessenger: Messenger
    private lateinit var mMessengerLooper: Looper
    private lateinit var mMessengerHandler: ReplyHandler

    private inner class ReplyHandler(looper: Looper) : Handler(looper) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                COMMAND_DISC_STREAM     -> handleDisconnect(msg)
                COMMAND_GET_STATUS      -> handleGetStatus(msg)

                else                    -> handleResult(msg)
            }
        }
    }

    private fun handlerServiceResponse() {
        handlerThread = HandlerThread("activity", Process.THREAD_PRIORITY_BACKGROUND)
        handlerThread.start()
        mMessengerLooper = handlerThread.looper
        mMessengerHandler = ReplyHandler(mMessengerLooper)
        mMessenger = Messenger(mMessengerHandler)
    }

    private val mConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.d(TAG, "onServiceConnected")
            mService = Messenger(service)
            mBound = true
            handlerServiceResponse()
            askForStatus()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            mService = null
            mBound = false
        }
    }

    init {
        Log.d(TAG, "init")
        val intent = Intent(application, ForegroundService::class.java)
        application.bindService(intent, mConnection, Context.BIND_AUTO_CREATE)

        val ipPort = preferences.getIpPort(true)
        val mode = preferences.getMode()
        savedStateHandle["uiStates"] = uiStates.value.copy(
            IP = ipPort.first,
            PORT = ipPort.second.toString(),
            mode = mode,
            textMode = preferences.getModeText(mode)
        )
    }

    fun onEvent(event: Event) {
        if(!mBound) {
            Log.d(TAG, "Service not available")
            return
        }
        var reply: Message? = null
        when(event) {
            is Event.ConnectButton -> {
                // lock button to avoid duplicate events
                savedStateHandle["uiStates"] = uiStates.value.copy(
                    buttonConnectIsClickable = false
                )
                if(uiStates.value.isStreamStarted) {
                    Log.d(TAG, "onConnectButton: stop stream")
                    reply = Message.obtain(null, Command.COMMAND_STOP_STREAM)
                }
                else {
                    val data = Bundle()
                    if(uiStates.value.mode == MODE_WIFI) {
                        try {
                            val (ip, port) = preferences.getIpPort(false)
                            data.putString("IP", ip)
                            data.putInt("PORT", port)
                        } catch (e: Exception) {
                            Toast.makeText(getApplication(), "Ip/Port invalid", Toast.LENGTH_SHORT).show()
                            return
                        }
                    }
                    data.putInt("MODE", uiStates.value.mode)
                    reply = Message.obtain(null, Command.COMMAND_START_STREAM)
                    reply.data = data
                    Log.d(TAG, "onConnectButton: start stream")
                }
            }

            is Event.AudioSwitch -> {
                // lock switch to avoid duplicate events
                savedStateHandle["uiStates"] = uiStates.value.copy(
                    switchAudioIsClickable = false
                )
                reply = if(uiStates.value.isAudioStarted) {
                    Log.d(TAG, "onAudioSwitch: stop audio")
                    Message.obtain(null, Command.COMMAND_STOP_AUDIO)
                } else {
                    Log.d(TAG, "onAudioSwitch: start audio")
                    Message.obtain(null, Command.COMMAND_START_AUDIO)
                }
            }

            is Event.SetIpPort -> {
                try {
                    preferences.setIpPort(Pair(event.ip, event.port))
                } catch (e: Exception) {
                    Toast.makeText(getApplication(), "Ip/Port invalid", Toast.LENGTH_SHORT).show()
                    return
                }
                savedStateHandle["uiStates"] = uiStates.value.copy(
                    IP = event.ip,
                    PORT = event.port,
                    dialogIpPortIsVisible = false
                )
            }
            is Event.SetMode -> {
                preferences.setMode(event.mode)
                savedStateHandle["uiStates"] = uiStates.value.copy(
                    mode = event.mode,
                    dialogModesIsVisible = false,
                    textMode = preferences.getModeText(event.mode)
                )
            }

            is Event.ShowDialog -> {
                when (event.id) {
                    R.string.drawerIpPort -> savedStateHandle["uiStates"] = uiStates.value.copy(dialogIpPortIsVisible = true)
                    R.string.drawerMode -> savedStateHandle["uiStates"] = uiStates.value.copy(dialogModesIsVisible = true)
                }
            }
            is Event.DismissDialog -> {
                when (event.id) {
                    R.string.drawerIpPort -> savedStateHandle["uiStates"] = uiStates.value.copy(dialogIpPortIsVisible = false)
                    R.string.drawerMode -> savedStateHandle["uiStates"] = uiStates.value.copy(dialogModesIsVisible = false)
                }
            }
        }
        if (reply != null) {
            reply.replyTo = mMessenger
            mService?.send(reply)
        }
    }

    // ask foreground service for current status
    private fun askForStatus() {
        if (!mBound) return
        val reply = Message.obtain(null, COMMAND_GET_STATUS)
        reply.replyTo = mMessenger
        mService?.send(reply)
    }


    // apply status to UI
    private fun handleGetStatus(msg: Message) {
        savedStateHandle["uiStates"] = uiStates.value.copy(
            isStreamStarted = msg.data.getBoolean("isStreamStarted"),
            isAudioStarted = msg.data.getBoolean("isAudioStarted"),
            switchAudioIsClickable = true,
            buttonConnectIsClickable = true
        )
    }

    private fun handleResult(msg: Message) {
        val reply = msg.data.getString("reply")
        if (reply != null) addLogMessage(reply)

        val result = msg.data.getBoolean("result")
        savedStateHandle["uiStates"] = uiStates.value.copy(
            switchAudioIsClickable = true,
            buttonConnectIsClickable = true
        )
        val log = if(result) "handleSuccess" else "handleFailure"
        // for log
        val commandService = CommandService()
        Log.d(TAG, "$log: ${commandService.dic[msg.what]}")
        when (msg.what) {
            Command.COMMAND_START_STREAM -> savedStateHandle["uiStates"] = uiStates.value.copy(
                isStreamStarted = result)
            Command.COMMAND_STOP_STREAM -> savedStateHandle["uiStates"] = uiStates.value.copy(
                isStreamStarted = !result)

            Command.COMMAND_START_AUDIO -> savedStateHandle["uiStates"] = uiStates.value.copy(
                isAudioStarted = result)
            Command.COMMAND_STOP_AUDIO -> savedStateHandle["uiStates"] = uiStates.value.copy(
                isAudioStarted = !result)
        }
    }

    private fun handleDisconnect(msg: Message) {
        Log.d(TAG, "handleDisconnect")
        val reply = msg.data.getString("reply")
        if (reply != null) addLogMessage(reply)
        savedStateHandle["uiStates"] = uiStates.value.copy(
            isStreamStarted = false,
            buttonConnectIsClickable = true)
    }

    // helper function to append log message to textview
    private fun addLogMessage(message: String) {
        savedStateHandle["uiStates"] = uiStates.value.copy(
            textLog = uiStates.value.textLog + message + "\n",
            scrollState = uiStates.value.scrollState.apply {
                viewModelScope.launch{ scrollTo(maxValue) }
            }
        )
    }
}