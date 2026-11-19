package com.ansardeo.bnb.demo.trade

enum class HttpVerb { GET, POST, DELETE }

/**
 * The COMPLETE list of Binance Demo endpoints this app may call in the Trade layer (steps 1-2 of the Demo execution phase).
 * Anything not listed here cannot be called: [DemoTradeClient] only exposes typed methods built on these entries.
 *
 * Step 3 (approved) added exactly two account-setting changes: margin type (ISOLATED only) and leverage, plus the read of the symbol config.
 * Deliberately NOT present (each needs a separate approval): position-mode CHANGE, batch orders, cancel-all, MARKET orders,
 * listen key, take-profit or trailing algo orders.
 */
enum class Endpoint(val verb: HttpVerb, val path: String, val signed: Boolean) {
    TIME(HttpVerb.GET, "/fapi/v1/time", false),
    EXCHANGE_INFO(HttpVerb.GET, "/fapi/v1/exchangeInfo", false),
    MARK_PRICE(HttpVerb.GET, "/fapi/v1/premiumIndex", false),
    BALANCE(HttpVerb.GET, "/fapi/v2/balance", true),
    POSITION_MODE_READ(HttpVerb.GET, "/fapi/v1/positionSide/dual", true),
    POSITION_RISK(HttpVerb.GET, "/fapi/v2/positionRisk", true),
    ORDER_NEW_LIMIT(HttpVerb.POST, "/fapi/v1/order", true),
    ORDER_QUERY(HttpVerb.GET, "/fapi/v1/order", true),
    ORDER_CANCEL(HttpVerb.DELETE, "/fapi/v1/order", true),
    OPEN_ORDERS(HttpVerb.GET, "/fapi/v1/openOrders", true),
    ALGO_NEW_STOP(HttpVerb.POST, "/fapi/v1/algoOrder", true),
    ALGO_QUERY(HttpVerb.GET, "/fapi/v1/algoOrder", true),
    ALGO_CANCEL(HttpVerb.DELETE, "/fapi/v1/algoOrder", true),
    ALGO_OPEN(HttpVerb.GET, "/fapi/v1/openAlgoOrders", true),
    SYMBOL_CONFIG(HttpVerb.GET, "/fapi/v1/symbolConfig", true),
    MARGIN_TYPE_SET(HttpVerb.POST, "/fapi/v1/marginType", true),
    LEVERAGE_SET(HttpVerb.POST, "/fapi/v1/leverage", true),
    /** Round 2 (approved): READ-ONLY trade history, used only to show realized PnL in History. */
    USER_TRADES(HttpVerb.GET, "/fapi/v1/userTrades", true),
}
