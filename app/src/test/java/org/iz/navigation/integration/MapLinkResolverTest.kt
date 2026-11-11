package org.iz.navigation.integration

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import okhttp3.Dns
import org.iz.navigation.car.SharedMapInput
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.withTimeout

class MapLinkResolverTest {
    private fun dns(lookup: (String) -> List<InetAddress>) = object : Dns {
        override fun lookup(hostname: String) = lookup(hostname)
    }
    private val publicDns = dns { listOf(InetAddress.getByAddress(byteArrayOf(8,8,8,8))) }
    @Test fun disallowedRedirectMakesZeroFollowupRequests() = runBlocking {
        var requests = 0
        val resolver = MapLinkResolver(publicDns, MapLinkExchange { _, _, _ -> requests++; MapLinkResponse(302, "https://evil.example/maps", "", 0) })
        assertTrue(resolver.resolve("https://maps.app.goo.gl/abc") is SharedMapInput.Unsupported)
        assertEquals(1, requests)
    }
    @Test fun privateDnsAtRedirectMakesZeroFollowupRequests() = runBlocking {
        var requests = 0
        val dns = dns { host -> if (host == "maps.apple.com") listOf(InetAddress.getByAddress(byteArrayOf(127,0,0,1))) else publicDns.lookup(host) }
        val resolver = MapLinkResolver(dns, MapLinkExchange { _, _, _ -> requests++; MapLinkResponse(302, "https://maps.apple.com/p/abc", "", 0) })
        assertTrue(resolver.resolve("https://maps.app.goo.gl/abc") is SharedMapInput.Unsupported)
        assertEquals(1, requests)
    }
    @Test fun validatedDestinationIsParsedLocallyWithoutFetchingGeometry() = runBlocking {
        var requests = 0
        val resolver = MapLinkResolver(publicDns, MapLinkExchange { _, addresses, _ ->
            assertEquals("8.8.8.8", addresses.single().hostAddress)
            requests++; MapLinkResponse(302, "https://maps.apple.com/place?coordinate=41,29&name=Park", "", 0)
        })
        val result = resolver.resolve("https://maps.app.goo.gl/abc") as SharedMapInput.Single
        assertEquals(41.0, result.target.coordinate!!.latitude, 0.0)
        assertEquals(1, requests)
    }
    @Test fun redirectLoopIsBoundedAndResponseBytesAccumulate() = runBlocking {
        var requests = 0
        val loop = MapLinkResolver(publicDns, MapLinkExchange { _, _, _ -> requests++; MapLinkResponse(302, "https://maps.app.goo.gl/abc", "", 0) })
        assertTrue(loop.resolve("https://maps.app.goo.gl/abc") is SharedMapInput.Unsupported)
        assertEquals(1, requests)
        val big = MapLinkResolver(publicDns, MapLinkExchange { _, _, _ -> MapLinkResponse(200, null, "", 524289) })
        assertTrue(big.resolve("https://maps.app.goo.gl/abc") is SharedMapInput.Unsupported)
    }
    @Test fun privateAndSpecialAddressRangesAreRejected() {
        listOf("0.0.0.0", "127.0.0.1", "10.0.0.1", "172.16.0.1", "192.168.0.1", "169.254.169.254", "100.64.0.1", "224.0.0.1",
            "::1", "fc00::1", "fe80::1", "2001:db8::1", "2001:20::1", "2002:7f00:1::1", "3fff::1").forEach {
            assertFalse(it, isPublicMapAddress(InetAddress.getByName(it)))
        }
        assertTrue(isPublicMapAddress(InetAddress.getByName("8.8.8.8")))
        assertTrue(isPublicMapAddress(InetAddress.getByName("2606:4700:4700::1111")))
    }
    @Test fun localInputsAndDisallowedLinksNeverUseNetwork() = runBlocking {
        val resolver = MapLinkResolver(dns { error("No DNS expected") }, MapLinkExchange { _, _, _ -> error("No network expected") })
        assertTrue(resolver.resolve("https://maps.apple.com/place?coordinate=1,2") is SharedMapInput.Single)
        assertTrue(resolver.resolve("https://google.com.evil/maps") is SharedMapInput.Unsupported)
    }
    @Test fun shortResolutionRunsOffTheCallingThread() = runBlocking {
        val caller=Thread.currentThread()
        var worker: Thread?=null
        val resolver=MapLinkResolver(publicDns,MapLinkExchange { _,_,_ ->
            worker=Thread.currentThread()
            MapLinkResponse(302,"https://maps.apple.com/place?coordinate=1,2","",0)
        })
        assertTrue(resolver.resolve("https://maps.app.goo.gl/start") is SharedMapInput.Single)
        assertNotSame(caller,worker)
    }
    @Test fun htmlCanonicalIsExplicitAndMalformedBodyDoesNotCrawlLinks() = runBlocking {
        val html="<a href='https://evil.example/'>ignored</a><meta content='https://maps.apple.com/place?coordinate=1,2&amp;name=Pin' property='og:url'>"
        val resolver=MapLinkResolver(publicDns,MapLinkExchange { _,_,_ -> MapLinkResponse(200,null,html,html.length) })
        assertEquals("Pin",(resolver.resolve("https://maps.app.goo.gl/start") as SharedMapInput.Single).target.name)
        val malformed="<meta".repeat(90_000)
        var requests=0
        val broken=MapLinkResolver(publicDns,MapLinkExchange { _,_,_ -> requests++; MapLinkResponse(200,null,malformed,malformed.length) })
        assertTrue(broken.resolve("https://maps.app.goo.gl/start") is SharedMapInput.Unsupported)
        assertEquals(1,requests)
    }
    @Test fun scriptStyleAndCommentTextCannotMasqueradeAsCanonicalMetadata() = runBlocking {
        val fake="<meta property='og:url' content='https://maps.apple.com/place?coordinate=5,6&name=Wrong'>"
        val html="<script>const x=\"$fake\";</script><style>$fake</style><!-- > $fake -->"+
            "<link rel='canonical' href='https://maps.apple.com/place?coordinate=1,2&name=Right'>"
        val resolver=MapLinkResolver(publicDns,MapLinkExchange { _,_,_ -> MapLinkResponse(200,null,html,html.length) })
        assertEquals("Right",(resolver.resolve("https://maps.app.goo.gl/start") as SharedMapInput.Single).target.name)
    }
    @Test fun fiveRedirectsCanResolveButSixCannot() = runBlocking {
        suspend fun resolve(hops: Int): Pair<SharedMapInput?,Int> {
            var calls=0
            val resolver=MapLinkResolver(publicDns,MapLinkExchange { _,_,_ ->
                calls++
                MapLinkResponse(302, if(calls==hops) "https://maps.apple.com/place?coordinate=1,2" else "https://maps.app.goo.gl/h$calls", "",0)
            })
            return resolver.resolve("https://maps.app.goo.gl/start") to calls
        }
        assertTrue(resolve(5).first is SharedMapInput.Single)
        assertTrue(resolve(6).first is SharedMapInput.Unsupported)
    }
    @Test fun cancellationCannotPublishALateResolution() = runBlocking {
        val entered=CompletableDeferred<Unit>()
        val resolver=MapLinkResolver(publicDns,MapLinkExchange { _,_,_ -> entered.complete(Unit); awaitCancellation() })
        val task=async { resolver.resolve("https://maps.app.goo.gl/start") }
        entered.await(); task.cancel(); task.join()
        assertTrue(task.isCancelled)
    }
    @Test fun cumulativeResponseLimitIsEnforcedAcrossHops() = runBlocking {
        var calls=0
        val resolver=MapLinkResolver(publicDns,MapLinkExchange { _,_,remaining ->
            calls++
            if(calls==1) MapLinkResponse(302,"https://maps.apple.com/p/second","",400000)
            else { assertEquals(124288,remaining); MapLinkResponse(200,null,"",124289) }
        })
        assertTrue(resolver.resolve("https://maps.app.goo.gl/start") is SharedMapInput.Unsupported)
        assertEquals(2,calls)
    }
    @Test fun nonInterruptibleDnsCannotHoldResolutionPastTotalDeadline() = runBlocking {
        val entered=CompletableDeferred<Unit>()
        val release=CountDownLatch(1)
        val blocking=dns {
            entered.complete(Unit)
            while(release.count > 0) { try { release.await() } catch (_: InterruptedException) { /* emulate platform DNS */ } }
            publicDns.lookup(it)
        }
        val resolver=MapLinkResolver(blocking,MapLinkExchange { _,_,_ -> error("Late DNS must not request") },50)
        val task=async { resolver.resolve("https://maps.app.goo.gl/start") }
        try {
            withTimeout(1000) { entered.await() }
            assertTrue(withTimeout(1000) { task.await() } is SharedMapInput.Unsupported)
        } finally { release.countDown(); task.cancel(); task.join() }
    }
    @Test fun cancelledNonInterruptibleLookupsCannotCreateUnboundedWorkers() = runBlocking {
        val entered=CompletableDeferred<Unit>()
        val release=CountDownLatch(1)
        val count=AtomicInteger()
        val blocking=dns {
            if(count.incrementAndGet()==2) entered.complete(Unit)
            while(release.count > 0) { try { release.await() } catch (_: InterruptedException) { } }
            publicDns.lookup(it)
        }
        val resolver=MapLinkResolver(blocking,MapLinkExchange { _,_,_ -> error("Cancelled DNS must not request") })
        val tasks=List(2) { async { resolver.resolve("https://maps.app.goo.gl/start") } }
        try {
            withTimeout(1000) { entered.await() }
            tasks.forEach { it.cancel() }; withTimeout(1000) { tasks.forEach { it.join() } }
            repeat(4) { assertTrue(withTimeout(1000) { resolver.resolve("https://maps.app.goo.gl/overflow") } is SharedMapInput.Unsupported) }
            assertEquals(2,count.get())
        } finally { release.countDown(); tasks.forEach { it.cancel() }; tasks.forEach { it.join() } }
    }
}
