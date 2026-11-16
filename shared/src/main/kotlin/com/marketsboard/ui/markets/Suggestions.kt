package com.marketsboard.ui.markets

/** Quick-add suggestions from the owner's brief. Never on the watchlist by default; shown in the add sheet. */
object Suggestions {
    val ALL: List<SearchRowUi> = listOf(
        row("^GSPTSE", "S&P/TSX Composite", "TSX", "Index"),
        row("^GSPC", "S&P 500", "S&P", "Index"),
        row("^IXIC", "Nasdaq Composite", "NASDAQ", "Index"),
        row("USDCAD=X", "US dollar to Canadian dollar", "FX", "Currency"),
        row("CL=F", "Crude oil (WTI)", "NYMEX", "Future"),
        row("GC=F", "Gold", "COMEX", "Future"),
        row("BTC-CAD", "Bitcoin in Canadian dollars", "CRYPTO", "Crypto"),
        row("ETH-CAD", "Ethereum in Canadian dollars", "CRYPTO", "Crypto"),
        row("VFV.TO", "Vanguard S&P 500 Index ETF", "TSX", "ETF"),
        row("XIU.TO", "iShares S&P/TSX 60 Index ETF", "TSX", "ETF"),
        row("SHOP.TO", "Shopify", "TSX", "Stock"),
        row("RY.TO", "Royal Bank of Canada", "TSX", "Stock"),
        row("TD.TO", "Toronto-Dominion Bank", "TSX", "Stock"),
        row("ENB.TO", "Enbridge", "TSX", "Stock"),
        row("AAPL", "Apple", "NASDAQ", "Stock"),
        row("NVDA", "NVIDIA", "NASDAQ", "Stock"),
    )

    private fun row(symbol: String, name: String, exchange: String, type: String) = SearchRowUi(symbol, name, exchange, type, added = false)
}
