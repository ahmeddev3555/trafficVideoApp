package com.trafficwatch.server.videoanalysis

/** Thrown by [VideoAnalysisClient] on any HTTP/network/parsing failure calling the Python service. */
class VideoAnalysisException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
