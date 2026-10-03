package com.example.drivestream

import androidx.media3.common.C
import androidx.media3.datasource.HttpDataSource.HttpDataSourceException
import androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo
import java.net.UnknownHostException

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class InfiniteRetryLoadErrorHandlingPolicy : DefaultLoadErrorHandlingPolicy() {

    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorInfo): Long {
        val exception = loadErrorInfo.exception
        
        // Fast fail on definitive HTTP errors (e.g. 404, 403)
        if (exception is InvalidResponseCodeException) {
            val responseCode = exception.responseCode
            if (responseCode == 404 || responseCode == 403 || responseCode == 401) {
                return C.TIME_UNSET // Crash immediately instead of infinite hang
            }
        }

        // Catch typical offline/stall exceptions
        if (exception is HttpDataSourceException || exception is UnknownHostException || exception is java.net.SocketTimeoutException) {
            // Exponential backoff
            return if (loadErrorInfo.errorCount < 3) 2000L else 5000L
        }
        
        return super.getRetryDelayMsFor(loadErrorInfo)
    }

    override fun getMinimumLoadableRetryCount(dataType: Int): Int {
        // Return Int.MAX_VALUE to prevent ExoPlayer from giving up entirely
        return Int.MAX_VALUE
    }
}
