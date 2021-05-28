package com.example.microphone

import android.util.Log
import java.io.IOException
import java.lang.Exception
import java.lang.IllegalArgumentException
import java.net.ServerSocket
import java.net.Socket

class AdbHelper(private val mActivity: MainActivity, private val mGlobalData : GlobalData)
{
    private val mLogTag : String = "AndroidMicAdb"

    private val TCP_SERVER_PORT = 38233
    private val MAX_WAIT_TIME = 1500 // timeout

    private var mServer : ServerSocket
    private var mSocket : Socket? = null

    init
    {
        // create server socket
        try
        {
            mServer = ServerSocket(TCP_SERVER_PORT)
            mServer.soTimeout = MAX_WAIT_TIME
        } catch (e : Exception) {
            Log.d(mLogTag, "init failed: ${e.message}")
            throw IllegalArgumentException("USB tcp is not initialized successfully")
        }
    }

    // try to accept connection
    fun connect() : Boolean
    {
        mSocket = try {
            mServer.accept()
        } catch (e : Exception) {
            Log.d(mLogTag, "accept failed: ${e.message}")
            null
        } ?: return false
        mSocket?.keepAlive = true
        return true
    }

    // try to disconnect
    fun disconnect() : Boolean
    {
        if(mSocket == null) return false
        try {
            mSocket?.close()
        } catch(e : IOException) {
            Log.d(mLogTag, "disconnect [close]: ${e.message}")
            mSocket = null
            return false
        }
        return true
    }

    fun clean()
    {
        ignore { mServer.close() }
    }

    // send data through socket
    fun sendData() : Boolean
    {
        if(mSocket?.isConnected != true) return false
        val nextData = mGlobalData.getData()
        try {
            val streamOut = mSocket?.getOutputStream()
            if(nextData != null)
            {
                streamOut?.write(nextData)
                streamOut?.flush()
            }
            // Log.d(mLogTag, "[sendData] data sent (${nextData.size} bytes)")
        } catch (e : IOException)
        {
            Log.d(mLogTag, "${e.message}")
            Thread.sleep(4)
            return false
        }
        return true
    }

    // get connected device information
    fun getConnectedDeviceInfo() : String
    {
        if(mSocket == null || mSocket?.isConnected != true) return ""
        return "[Device Address] ${mSocket?.remoteSocketAddress}"
    }

    // check if socket is valid
    fun isSocketValid() : Boolean
    {
        return mSocket != null && (mSocket?.isConnected == true)
    }
}