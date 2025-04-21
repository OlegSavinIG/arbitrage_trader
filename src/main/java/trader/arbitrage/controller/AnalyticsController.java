package trader.arbitrage.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import trader.arbitrage.model.clickhouse.TokenPriceRecord;
import trader.arbitrage.service.clickhouse.ClickHouseService;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/analytics")
@RequiredArgsConstructor
public class AnalyticsController {
    private final ClickHouseService service;

    /**
     * Получить историю цен по символу и диапазону.
     */
    @GetMapping("/prices")
    public List<TokenPriceRecord> getPrices(
            @RequestParam String symbol,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to
    ) {
        return service.getPrices(symbol, from, to);
    }

    /**
     * Получить последнюю цену по символу.
     */
    @GetMapping("/prices/latest")
    public ResponseEntity<TokenPriceRecord> getLatestPrice(@RequestParam String symbol) {
        return service.getLatestPrice(symbol)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Получить среднюю цену по символу и диапазону.
     */
    @GetMapping("/prices/average")
    public ResponseEntity<BigDecimal> getAveragePrice(
            @RequestParam String symbol,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to
    ) {
        return service.getAveragePrice(symbol, from, to)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.noContent().build());
    }
}

