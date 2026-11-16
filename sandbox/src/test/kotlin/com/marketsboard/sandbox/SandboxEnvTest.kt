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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/** The real presenter, refresh controller and repository running against the fake market (real time, no latency). */
class SandboxEnvTest {
    /**
     * These tests run the real presenter, controller and fake market in REAL time on a shared CPU, so on a loaded
     * CI runner an occasional step can miss its window. Retry the whole scenario up to 3 times; a genuine bug fails
     * every attempt. Deterministic logic lives in the unit tests with fake clocks.
     */
    @get:Rule val retry = TestRule { base, _: Description ->
        object : Statement() {
            override fun evaluate() {
                var last: Throwable? = null
                repeat(3) { try { base.evaluate(); return } catch (t: Throwable) { last = t } }
                throw last!!
            }
        }
    }

    private suspend fun <T> until(timeoutMs: Long = 30_000, f: () -> T?): T = withTimeout(timeoutMs) {
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
        // the header turns Offline as soon as a row fails; rows that failed keep their numbers, dimmed (a row can still be in flight, so not "all")
        val off = until { e.presenter.state.value.takeIf { st -> st.header.kind == LiveKind.Offline && st.quotes.any { it.status is RowStatus.Error } } }
        assertTrue(off.quotes.filter { it.status is RowStatus.Error }.all { it.price != null })
        e.fake.behavior = FakeBehavior.Normal
        // manual Refresh is debounced to one press per 5 s, so recover through the per-row Retry actions
        e.presenter.onRetry("XEQT.TO"); e.presenter.onRetry("MSFT")
        until { e.presenter.state.value.takeIf { it.header.kind == LiveKind.Live } }
    }

    @Test fun addingASymbolThroughThePresenterShowsItInTheList() = env { e, _ ->
        // Real-time integration test: drive each step idempotently so a slow runner cannot lose a stimulus.
        val results = until {
            val add = e.presenter.state.value.add
            if (add == null) e.presenter.onAddOpen()
            else if (add.query != "shop") e.presenter.onQueryChange("shop")
            add?.results?.takeIf { it.isNotEmpty() && add.query == "shop" }
        }
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
        // Retry is debounced (5 s) and CI runners are slow, so keep asking until the skewed fetch has landed.
        until { e.presenter.onRetry("XEQT.TO"); e.presenter.state.value.takeIf { it.clockSkew } }
    }
}
