package com.sasch.cameragps.sharednew.remote.wifi

import javax.net.SocketFactory

internal class AndroidCameraHttpTransport(sockets: SocketFactory) : SonyLiveViewHttpTransport by
    CameraHttpTransport({ host, port -> AndroidCameraByteConnection.open(sockets, host, port) })
