--changeset oleg:001-token-prices
CREATE TABLE IF NOT EXISTS token_prices (
    symbol String,
    exchange String,
    price Decimal64(6),
    timestamp DateTime
) ENGINE = MergeTree()
ORDER BY (symbol, exchange, timestamp);

--changeset oleg:002-arbitrage-events
CREATE TABLE IF NOT EXISTS arbitrage_events (
    symbol String,
    primary_exchange String,
    secondary_exchange String,
    primary_price Decimal64(6),
    secondary_price Decimal64(6),
    diff_percent Decimal64(2),
    timestamp DateTime
) ENGINE = MergeTree()
ORDER BY (symbol, timestamp);
