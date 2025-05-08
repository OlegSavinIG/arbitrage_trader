package trader.arbitrage.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import trader.arbitrage.service.clickhouse.ClickHouseService;

import java.time.LocalDateTime;

@Configuration
@RequiredArgsConstructor
public class AnalyticsController {
    private final ClickHouseService service;

    @Bean
    public RouterFunction<ServerResponse> analyticsRoutes() {
        return RouterFunctions.route()
                .path("/analytics", this::buildAnalyticsRoutes)
                .build();
    }

    private RouterFunction<ServerResponse> buildAnalyticsRoutes() {
        return RouterFunctions.route()
                .GET("/prices", this::handleGetPrices)
                .GET("/prices/latest", this::handleLatestPrice)
                .GET("/prices/average", this::handleAveragePrice)
                .build();
    }

    private Mono<ServerResponse> handleGetPrices(ServerRequest request) {
        String symbol = request.queryParam("symbol").orElseThrow();
        LocalDateTime from = parseDateTime(request, "from");
        LocalDateTime to = parseDateTime(request, "to");

        return service.getPricesReactive(symbol, from, to)
                .collectList()
                .flatMap(list -> ServerResponse.ok().bodyValue(list));
    }

    private Mono<ServerResponse> handleLatestPrice(ServerRequest request) {
        String symbol = request.queryParam("symbol").orElseThrow();
        return service.getLatestPriceReactive(symbol)
                .flatMap(price -> ServerResponse.ok().bodyValue(price))
                .switchIfEmpty(ServerResponse.notFound().build());
    }

    private Mono<ServerResponse> handleAveragePrice(ServerRequest request) {
        String symbol = request.queryParam("symbol").orElseThrow();
        LocalDateTime from = parseDateTime(request, "from");
        LocalDateTime to = parseDateTime(request, "to");

        return service.getAveragePriceReactive(symbol, from, to)
                .flatMap(avg -> ServerResponse.ok().bodyValue(avg))
                .switchIfEmpty(ServerResponse.noContent().build());
    }

    private LocalDateTime parseDateTime(ServerRequest request, String paramName) {
        return request.queryParam(paramName)
                .map(str -> LocalDateTime.parse(str))
                .orElseThrow(() -> new IllegalArgumentException("Invalid datetime format for " + paramName));
    }
}

