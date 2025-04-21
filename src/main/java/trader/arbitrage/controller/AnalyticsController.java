//package trader.arbitrage.controller;
//
//import lombok.RequiredArgsConstructor;
//import org.springframework.web.bind.annotation.GetMapping;
//import org.springframework.web.bind.annotation.PathVariable;
//import org.springframework.web.bind.annotation.RequestMapping;
//import org.springframework.web.bind.annotation.RequestParam;
//import org.springframework.web.bind.annotation.RestController;
//import trader.arbitrage.model.clickhouse.ArbitrageEventRecord;
//import trader.arbitrage.model.clickhouse.TokenPriceRecord;
//import trader.arbitrage.service.clickhouse.ClickHouseService;
//
//import java.time.LocalDateTime;
//import java.util.List;
//
//@RestController
//@RequestMapping("/analytics")
//@RequiredArgsConstructor
//public class AnalyticsController {
//    private final ClickHouseService ch;
//
//    @GetMapping("/prices/{symbol}")
//    public List<TokenPriceRecord> getPrices(
//            @PathVariable String symbol,
//            @RequestParam LocalDateTime from,
//            @RequestParam LocalDateTime to) {
//        return ch.fetchPrices(symbol, from, to);
//    }
//
//    @GetMapping("/events")
//    public List<ArbitrageEventRecord> getEvents(
//            @RequestParam LocalDateTime from,
//            @RequestParam LocalDateTime to) {
//        return ch.fetchEvents(from, to);
//    }
//}
//
