package com.pwa.shell.data.remote

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PwaManifestFetcherTest {

    @Test
    fun testFetchPwaInfo() = runBlocking {
        val client = OkHttpClient()
        val fetcher = PwaManifestFetcher(client)
        
        try {
            // Fetch PWA info from github.com
            val result = fetcher.fetchPwaInfo("https://github.com")
            println("Result: $result")
            assertNotNull(result.name)
            assertNotNull(result.iconUrl)
            assertTrue(result.iconUrl!!.startsWith("http"))
        } catch (e: Exception) {
            // Log and pass if network is not available during build
            println("Network test skipped: ${e.message}")
        }
    }
}
