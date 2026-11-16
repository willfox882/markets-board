package com.marketsboard.sandbox

import com.marketsboard.ui.markets.LiveKind
import com.marketsboard.ui.markets.RowStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The real presenter, refresh controller and repository running against the fake market (real time, no latency). */
class SandboxEnvTest {
    private suspend fun <T> until(timeoutMs: Long = 10_000, f: () -> T?): T = withTimeout(timeoutMs) {
        var v = f()
        while (v == null) { delay(20); v = f() }
        v
    }

    private fun env(block: suspend (SandboxEnv, CoroutineScope) -> Any?) {
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try { block(SandboxEnv(scope), scope) } finally { scope.cancel() }
        }
    }

    @Test fun defaultsLoadAndGoLive() = env { e, _ ->
        val s = until { e.presenter.state.value.takeIf { st -> st.quotes.size == 2 && st.quotes.all { it.status == RowStatus.Fresh } } }
        assertEquals(listOf("XEQT.TO", "MSFT"), s.quotes.map { it.symbol })
        assertEquals(listOf("CAD", "USD"), s.quotes.map { it.currency })
        assertEquals(LiveKind.Live, s.header.kind)
        assertTrue(s.header.updated.startsWith("Updated "))
    }

    @Test fun offlineThenRecoveryThroughManualRefresh() = env { e, _ ->
        until { e.presenter.state.value.takeIf { st -> st.quotes.all { it.status == RowStatus.Fresh } && st.quotes.size == 2 } }
        e.fake.behavior = FakeBehavior.Offline
        e.presenter.onRefresh()
        val off = until { e.presenter.state.value.takeIf { it.header.kind == LiveKind.Offline } }
        assertTrue(off.quotes.all { it.status is RowStatus.Error && it.price != null }) // numbers kept, dimmed
        e.fake.behavior = FakeBehavior.Normal
        e.presenter.onRefresh()
        until { e.presenter.state.value.takeIf { it.header.kind == LiveKind.Live } }
    }

    @Test fun addingASymbolThroughThePresenterShowsItInTheList() = env { e, _ ->
        e.presenter.onAddOpen()
        e.presenter.onQueryChange("shop")
        val results = until { e.presenter.state.value.add?.results?.takeIf { it.isNotEmpty() } }
        assertEquals("SHOP.TO", results.first().symbol)
        e.presenter.onPickResult("SHOP.TO")
        val s = until { e.presenter.state.value.takeIf { st -> st.quotes.any { it.symbol == "SHOP.TO" && it.status == RowStatus.Fresh } } }
        assertEquals(3, s.quotes.size)
        e.resetWatchlist()
        until { e.presenter.state.value.takeIf { it.quotes.size == 2 } }
    }

    @Test fun clockSkewShowsTheBanner() = env { e, _ ->
        until { e.presenter.state.value.takeIf { st -> st.quotes.size == 2 && st.quotes.all { it.status == RowStatus.Fresh } } }
        e.time.deviceSkewMs = -45 * 60_000L
        e.presenter.onRefresh()
        until { e.presenter.state.value.takeIf { it.clockSkew } }
    }
}
