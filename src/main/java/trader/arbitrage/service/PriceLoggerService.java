package trader.arbitrage.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@EnableScheduling
@RequiredArgsConstructor
public class PriceLoggerService {

    private final PriceService priceService;

    /**
     * Log all last prices every minute
     */
    @Scheduled(fixedRate = 60000)
    public void logAllPrices() {
        log.info("=== Scheduled price logging ===");
        priceService.logAllLastPrices();
    }
}
