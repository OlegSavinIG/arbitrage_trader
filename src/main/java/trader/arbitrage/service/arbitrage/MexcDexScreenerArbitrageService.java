package trader.arbitrage.service.arbitrage;

import io.micrometer.core.instrument.Counter;
import io.micrometer.observation.annotation.Observed;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import trader.arbitrage.model.ArbitrageOpportunity;
import trader.arbitrage.model.TokenPrice;
import trader.arbitrage.service.priceservice.DexScreenerService;
import trader.arbitrage.service.priceservice.MexcPriceService;
import trader.arbitrage.telegram.TelegramNotificationService;

import java.util.Map;

@Slf4j
@Service
public class MexcDexScreenerArbitrageService extends BaseArbitrageService {

    private static final String PRIMARY_EXCHANGE = "MEXC";
    private static final String SECONDARY_EXCHANGE = "DexScreener";

    private final MexcPriceService mexcPriceService;
    private final DexScreenerService dexScreenerService;

    public MexcDexScreenerArbitrageService(
            MexcPriceService mexcPriceService,
            DexScreenerService dexScreenerService,
            TelegramNotificationService telegramService,
            Counter arbitrageOpportunityCounter,
            Counter telegramNotificationsCounter) {
        super(telegramService, arbitrageOpportunityCounter, telegramNotificationsCounter);
        this.mexcPriceService = mexcPriceService;
        this.dexScreenerService = dexScreenerService;
    }

    /**
     * Scheduled method to check for arbitrage opportunities between MEXC and DexScreener
     */
    @Override
    @Scheduled(fixedRateString = "${arbitrage.check-interval}")
    @Observed(name = "MexcDexCheckForArbitrageOpportunities",
            contextualName = "check-arbitrage-opportunities-mexcdex")
    public void checkForArbitrageOpportunities() {
        // Get all latest prices from both exchanges
        Map<String, TokenPrice> mexcPrices = mexcPriceService.getAllLatestPrices();
        Map<String, TokenPrice> dexScreenerPrices = dexScreenerService.getAllLatestPrices();

        // Use template method from base class
        super.checkForArbitrageOpportunities(
                mexcPrices,
                dexScreenerPrices,
                PRIMARY_EXCHANGE,
                SECONDARY_EXCHANGE);
    }

    /**
     * Override to apply special formatting for DexScreener opportunities
     */
    @Override
    protected void processArbitrageOpportunity(ArbitrageOpportunity opportunity) {
        telegramService.sendArbitrageNotification(formatMexcDexScreenerOpportunity(opportunity))
                .subscribe(
                        sent -> {
                            if (sent) {
                                if (telegramNotificationsCounter != null) {
                                    telegramNotificationsCounter.increment();
                                }
                                log.info("Telegram notification sent for arbitrage opportunity: {}", opportunity.getSymbol());
                            }
                        },
                        error -> log.error("Error sending Telegram notification: {}", error.getMessage())
                );
    }

    /**
     * Format the opportunity for Telegram message
     * This converts the ArbitrageOpportunity to use the correct exchange names
     */
    private ArbitrageOpportunity formatMexcDexScreenerOpportunity(ArbitrageOpportunity opportunity) {
        return ArbitrageOpportunity.builder()
                .symbol(opportunity.getSymbol())
                .mexcPrice(opportunity.getMexcPrice())
                .secondExchangePrice(opportunity.getSecondExchangePrice())
                .priceDifferencePercent(opportunity.getPriceDifferencePercent())
                .secondExchangeName(SECONDARY_EXCHANGE)
                .timestamp(opportunity.getTimestamp())
                .build();
    }
}