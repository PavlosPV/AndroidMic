using SharpAdbClient;
using System;
using System.IO;
using System.Windows;
using System.Threading;
using System.Diagnostics;
using System.Net.Sockets;

// Reference: https://stackoverflow.com/questions/21748790/how-to-send-a-message-from-android-to-windows-using-usb

namespace AndroidMic
{
    public enum AdbStatus
    {
        CONNECTING,
        CONNECTED
    }

    // helper class to communicate with android device by USB port
    // use abd.exe
    public class ADBHelper
    {
        private readonly AdbServer mServer = new AdbServer();
        private readonly AdbClient mAdbClient = new AdbClient();
        private DeviceData mDevice = null;
        private TcpClient mTcpClient = null;

        // try to set ports that may not be taken
        private readonly int PORTLOCAL = 38233, PORTREMOTE = 38233;
        private readonly int MAX_WAIT_TIME = 2000;
        private readonly int BUFFER_SIZE = 2048;
        private readonly string LOCALADDR = "localhost";

        private bool isTryConnectAllowed = false;
        private bool isProcessAllowed = false;
        private bool isForwardCreated = false;

        private Thread mThreadTryConnect = null;
        private Thread mThreadProcess = null;

        private readonly MainWindow mMainWindow;
        private readonly AudioData mGlobalData;

        public AdbStatus Status { get; private set; } = AdbStatus.CONNECTING;


        public ADBHelper(MainWindow window, AudioData globalData)
        {
            mMainWindow = window;
            mGlobalData = globalData;
            mServer.StartServer("./adb.exe", false);
            isTryConnectAllowed = true;
            mThreadTryConnect = new Thread(new ThreadStart(TryConnect));
            mThreadTryConnect.Start();
        }

        public void Clean()
        {
            // stop try connect thread
            isTryConnectAllowed = false;
            if(mThreadTryConnect != null && mThreadTryConnect.IsAlive)
            {
                if (!mThreadTryConnect.Join(MAX_WAIT_TIME)) mThreadTryConnect.Abort();
            }
            // stop process thread
            isProcessAllowed = false;
            if (mThreadProcess != null && mThreadProcess.IsAlive)
            {
                if (!mThreadProcess.Join(MAX_WAIT_TIME)) mThreadProcess.Abort();
            }
            // disconnect
            Disconnect();
            // stop forwarding
            mAdbClient.RemoveAllForwards(mDevice);
        }

        private void TryConnect()
        {
            Status = AdbStatus.CONNECTING;
            AddLog("Waiting for device...");
            while (isTryConnectAllowed)
            {
                if(RefreshDevice() && !mMainWindow.IsConnected())
                {
                    if(isForwardCreated)
                    {
                        mAdbClient.RemoveAllForwards(mDevice);
                        isForwardCreated = false;
                    }
                    // if device is detected, try to start forward tcp
                    if(mAdbClient.CreateForward(mDevice, PORTLOCAL, PORTREMOTE) > 0)
                    {
                        isForwardCreated = true;
                        // check if process thread is alive
                        if (mThreadProcess != null && mThreadProcess.IsAlive)
                        {
                            isProcessAllowed = false;
                            if (!mThreadProcess.Join(MAX_WAIT_TIME)) mThreadProcess.Abort();
                            Disconnect();
                        }
                        // if forward tcp created, try to connect with TCP
                        if (Connect())
                        {
                            AddLog("Device connected\nclient [Model]: " + mDevice.Model);
                            isProcessAllowed = true;
                            mThreadProcess = new Thread(new ThreadStart(Process));
                            mThreadProcess.Start();
                            // exit current thread after processing starts
                            isTryConnectAllowed = false;
                            break;
                        }
                        else Disconnect(); // else remove TCP client and loop again
                    }
                    else
                    {
                        Debug.WriteLine("[ADBHelper] failed to CreateForward on port " + PORTLOCAL);
                        AddLog("TPC port (" + PORTLOCAL + ") has been taken\nUnable to start USB connection");
                        isTryConnectAllowed = false;
                        isProcessAllowed = false;
                        break;
                    }

                }
                Thread.Sleep(500); // refresh every 0.5s
            }
        }

        // receive audio data
        private void Process()
        {
            Debug.WriteLine("[ADBHelper] Process connected");
            Status = AdbStatus.CONNECTED;
            var stream = mTcpClient.GetStream();
            if (stream.CanTimeout) stream.ReadTimeout = MAX_WAIT_TIME;
            while (isProcessAllowed && IsSocketValid())
            {
                try
                {
                    byte[] buffer = new byte[BUFFER_SIZE];
                    int bufferSize = stream.Read(buffer, 0, BUFFER_SIZE);
                    if (bufferSize < 0) break;
                    else if (bufferSize == 0)
                    {
                        Thread.Sleep(5);
                        continue;
                    }
                    mGlobalData.AddData(buffer, bufferSize);
                    //Debug.WriteLine("[ADBHelper] Process buffer received (" + bufferSize + " bytes)");
                }
                catch (IOException e)
                {
                    Debug.WriteLine("[ADBHelper] Process error: " + e.Message);
                    break;
                }
                Thread.Sleep(1);
            }
            stream.Close();
            stream.Dispose();
            Status = AdbStatus.CONNECTING;
            Disconnect();
            Debug.WriteLine("[ADBHelper] Process disconnected");
            // try to connect again
            if(isProcessAllowed)
            {
                if (mThreadTryConnect != null && mThreadTryConnect.IsAlive)
                {
                    isTryConnectAllowed = false;
                    if (!mThreadTryConnect.Join(MAX_WAIT_TIME)) mThreadTryConnect.Abort();
                }
                isTryConnectAllowed = true;
                mThreadTryConnect = new Thread(new ThreadStart(TryConnect));
                mThreadTryConnect.Start();
            }   
        }

        // connect to tcp server
        private bool Connect()
        {
            Debug.WriteLine("[ADBHelper] Trying to connect");
            mTcpClient = new TcpClient();
            var connection = mTcpClient.BeginConnect(LOCALADDR, PORTLOCAL, null, null);
            // wait for connection for 1 second
            if (!connection.AsyncWaitHandle.WaitOne(TimeSpan.FromSeconds(1)))
            {
                // failed to connect to server, meaning no server available
                return false;
            }
            mTcpClient.EndConnect(connection);
            Debug.WriteLine("[ADBHelper] mTcpClient.Connected = " + mTcpClient.Connected);
            return mTcpClient.Connected;
        }

        // disconnect from tcp server
        private void Disconnect()
        {
            if(mTcpClient != null)
            {
                mTcpClient.Close();
                mTcpClient.Dispose();
                mTcpClient = null;
            }
            Debug.WriteLine("[ADBHelper] disconnected");
        }

        // select the first device if connected
        private bool RefreshDevice()
        {
            var devices = mAdbClient.GetDevices();
            if (devices.Count > 0)
            {
                mDevice = devices[0];
                // clear previous forwards
                mAdbClient.RemoveAllForwards(mDevice);
                mAdbClient.RemoveAllReverseForwards(mDevice);
                return true;
            }
            else
            {
                mDevice = null; // else set to null
                return false;
            }
        }

        // check if socket is valid
        private bool IsSocketValid()
        {
            if (mTcpClient == null || !mTcpClient.Connected) return false;
            if (mTcpClient.Client == null || !mTcpClient.Client.Connected) return false;
            return true;
        }

        // add log message to UI
        private void AddLog(string message)
        {
            if (Application.Current == null) return;
            Application.Current.Dispatcher.Invoke(new Action(() =>
            {
                mMainWindow.AddLogMessage("[ADB usb]\n" + message + "\n");
            }));
        }
    }
}
