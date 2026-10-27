package com.jarvis.assistant.ai

import com.jarvis.assistant.speech.ModelArchiveException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.UnknownHostException

class ModelDownloadPolicyTest {

    private val hosts = setOf("alphacephei.com", "www.alphacephei.com")

    @Test
    fun allowsOnlyHttpsOnAllowListedHosts() {
        assertTrue(ModelDownloadPolicy.isAllowed("https://alphacephei.com/vosk/models/x.zip", hosts))
        assertTrue(ModelDownloadPolicy.isAllowed("https://ALPHACEPHEI.com:443/x.zip", hosts))
        assertFalse(ModelDownloadPolicy.isAllowed("http://alphacephei.com/x.zip", hosts))
        assertFalse(ModelDownloadPolicy.isAllowed("https://evil.com/x.zip", hosts))
        assertFalse(ModelDownloadPolicy.isAllowed("https://alphacephei.com.evil.com/x.zip", hosts))
        assertFalse(ModelDownloadPolicy.isAllowed("https://evilalphacephei.com/x.zip", hosts))
        assertFalse(ModelDownloadPolicy.isAllowed("https://alphacephei.com@evil.com/x.zip", hosts))
        assertFalse(ModelDownloadPolicy.isAllowed("https://user@alphacephei.com/x.zip", hosts))
        assertFalse(ModelDownloadPolicy.isAllowed("https://alphacephei.com:8443/x.zip", hosts))
        assertFalse(ModelDownloadPolicy.isAllowed("ftp://alphacephei.com/x.zip", hosts))
        assertFalse(ModelDownloadPolicy.isAllowed("file:///sdcard/x.zip", hosts))
        assertFalse(ModelDownloadPolicy.isAllowed("/vosk/x.zip", hosts))
        assertFalse(ModelDownloadPolicy.isAllowed("not a url", hosts))
    }

    @Test
    fun resolvesRelativeAndAbsoluteRedirects() {
        val base = "https://alphacephei.com/vosk/models/x.zip"
        assertEquals("https://alphacephei.com/other/y.zip", ModelDownloadPolicy.resolveRedirect(base, "/other/y.zip"))
        assertEquals("http://evil.com/a", ModelDownloadPolicy.resolveRedirect(base, "http://evil.com/a"))
        assertNull(ModelDownloadPolicy.resolveRedirect(base, null))
        assertNull(ModelDownloadPolicy.resolveRedirect(base, "  "))
        // A downgrade or off-list redirect resolves, but is then rejected by isAllowed.
        assertFalse(ModelDownloadPolicy.isAllowed(ModelDownloadPolicy.resolveRedirect(base, "http://alphacephei.com/x")!!, hosts))
    }

    @Test
    fun errorMessagesAreActionable() {
        assertTrue(ModelInstallErrors.userMessage(UnknownHostException()).contains("internet"))
        assertTrue(ModelInstallErrors.userMessage(InsufficientStorageException(200L shl 20, 10L shl 20)).contains("Free up space"))
        assertTrue(ModelInstallErrors.userMessage(ModelArchiveException("Checksum mismatch")).startsWith("Checksum mismatch"))
        assertEquals("Download failed (HTTP 404). Try again.", ModelInstallErrors.userMessage(IOException("Download failed (HTTP 404)")))
        assertEquals("Could not save the model. Try again.", ModelInstallErrors.userMessage(IOException("/data/secret/path")))
    }
}
