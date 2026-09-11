package org.iz.navigation.group

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GroupClientTest {
    @get:Rule val folder=TemporaryFolder()
    private fun oldSession()=JSONObject().put("access_token","old-access").put("refresh_token","old-refresh").put("expires_at",1).put("user",JSONObject().put("id","old-user"))
    private fun freshSession()=JSONObject().put("access_token","new-access").put("refresh_token","new-refresh").put("expires_at",System.currentTimeMillis()/1000+3600).put("user",JSONObject().put("id","new-user"))
    private fun http(server:MockWebServer)=OkHttpClient.Builder().addInterceptor { chain -> chain.proceed(chain.request().newBuilder().url(server.url(chain.request().url.encodedPath+ (chain.request().url.encodedQuery?.let { "?$it" } ?: ""))).build()) }.build()
    @Test fun confirmedDeletedIdentityCreatesNewDeviceButDoesNotReplayOriginalOperation()=runTest {
        MockWebServer().use { server ->
            val file=folder.newFile(); file.writeText(oldSession().toString())
            server.enqueue(MockResponse().setResponseCode(400).setBody("{\"code\":\"refresh_token_not_found\"}"))
            server.enqueue(MockResponse().setBody(freshSession().toString()))
            val client=GroupClient("https://groups.example.test","public-key",file,http(server))
            val failure=runCatching { client.command("publish",JSONObject().put("group_id","old-ride")) }.exceptionOrNull()
            assertEquals("identity_changed",(failure as GroupException).reason)
            assertEquals(2,server.requestCount)
            assertEquals("/auth/v1/token?grant_type=refresh_token",server.takeRequest().path)
            assertEquals("/auth/v1/signup",server.takeRequest().path)
            assertEquals("new-refresh",JSONObject(file.readText()).getString("refresh_token"))
            server.enqueue(MockResponse().setBody("{\"ok\":true,\"group\":null}"))
            client.command("status",JSONObject())
            assertEquals("Bearer new-access",server.takeRequest().getHeader("Authorization"))
        }
    }
    @Test fun transientAndRateLimitedRefreshNeverRotateIdentity()=runTest {
        for(status in listOf(429,500,400)) MockWebServer().use { server ->
            val file=folder.newFile(); val old=oldSession().toString();file.writeText(old)
            server.enqueue(MockResponse().setResponseCode(status).setBody("{\"code\":\"unexpected_failure\"}"))
            val client=GroupClient("https://groups.example.test","public-key",file,http(server))
            assertNotNull(runCatching { client.command("status",JSONObject()) }.exceptionOrNull())
            assertEquals(1,server.requestCount);assertEquals(old,file.readText())
        }
    }
}
