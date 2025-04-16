package trader.arbitrage.telegram;

import io.github.cdimascio.dotenv.Dotenv;
import io.micrometer.core.instrument.Counter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;
import trader.arbitrage.model.ArbitrageOpportunity;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
@RequiredArgsConstructor
public class TelegramNotificationService {

    private final WebClient telegramWebClient;
    private final Dotenv dotenv;
    private final Counter telegramNotificationsCounter;


    @Value("${telegram.enabled:false}")
    private boolean telegramEnabled;

    @Value("${telegram.retry.max-attempts:3}")
    private int maxRetryAttempts;

    @Value("${telegram.retry.initial-backoff:1000}")
    private long initialBackoffMillis;

    @Value("${telegram.retry.max-backoff:10000}")
    private long maxBackoffMillis;

    @Value("${telegram.rate-limit.messages-per-minute:20}")
    private int maxMessagesPerMinute;

    // Rate limiting tracking
    private final AtomicInteger messagesSentInCurrentMinute = new AtomicInteger(0);
    private long currentMinuteStartTime = System.currentTimeMillis();

    /**
     * Sends an arbitrage opportunity notification to Telegram
     *
     * @param opportunity The arbitrage opportunity to notify about
     * @return Mono<Boolean> indicating success or failure
     */
    public Mono<Boolean> sendArbitrageNotification(ArbitrageOpportunity opportunity) {
        String chatId = dotenv.get("TELEGRAM_CHAT_ID");
        if (!telegramEnabled) {
            log.debug("Telegram notifications are disabled");
            return Mono.just(false);
        }

        if (!checkAndUpdateRateLimit()) {
            log.warn("Telegram rate limit reached. Skipping notification for {}", opportunity.getSymbol());
            return Mono.just(false);
        }

        String message = formatArbitrageMessage(opportunity);

        return telegramWebClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/sendMessage")
                        .queryParam("chat_id", chatId)
                        .queryParam("text", message)
                        .queryParam("parse_mode", "HTML")
                        .build())
                .retrieve()
                .bodyToMono(String.class)
                .map(response -> {
                    log.info("Telegram notification sent successfully for {}", opportunity.getSymbol());
                    telegramNotificationsCounter.increment();
                    return true;
                })
                .onErrorResume(e -> {
                    log.error("Failed to send Telegram notification: {}", e.getMessage(), e);
                    return Mono.just(false);
                })
                .retryWhen(createRetrySpec());
    }

    /**
     * Format the arbitrage opportunity as a Telegram message
     */
    private String formatArbitrageMessage(ArbitrageOpportunity opportunity) {
        String direction = opportunity.getPriceDifferencePercent().compareTo(java.math.BigDecimal.ZERO) > 0
                ? "MEXC > " + opportunity.getSecondExchangeName()
                : opportunity.getSecondExchangeName() + " > MEXC";

        return String.format(
                "🚨 <b>ARBITRAGE OPPORTUNITY</b> 🚨\n\n" +
                        "💰 <b>Token</b>: %s\n" +
                        "📊 <b>MEXC Price</b>: %s\n" +
                        "📊 <b>%s Price</b>: %s\n" +
                        "📈 <b>Price Difference</b>: %s%%\n" +
                        "↔️ <b>Direction</b>: %s\n" +
                        "⏰ <b>Timestamp</b>: %s",
                opportunity.getSymbol(),
                opportunity.getMexcPrice(),
                opportunity.getSecondExchangePrice(),
                opportunity.getSecondExchangePrice(),
                opportunity.getPriceDifferencePercent().abs(),
                direction,
                opportunity.getTimestamp()
        );
    }

    /**
     * Check and update rate limiting for Telegram messages
     *
     * @return true if message can be sent, false if rate limit is reached
     */
    private boolean checkAndUpdateRateLimit() {
        long currentTime = System.currentTimeMillis();
        long timeElapsed = currentTime - currentMinuteStartTime;

        // Reset counter if a minute has passed
        if (timeElapsed >= 60000) { // 60 seconds in milliseconds
            log.debug("Resetting Telegram rate limit counter. Previous count: {}", messagesSentInCurrentMinute.get());
            messagesSentInCurrentMinute.set(0);
            currentMinuteStartTime = currentTime;
        }

        // Check if we're over the limit
        int currentCount = messagesSentInCurrentMinute.incrementAndGet();
        if (currentCount > maxMessagesPerMinute) {
            return false;
        }

        return true;
    }

    /**
     * Creates a retry specification for handling temporary errors
     */
    private Retry createRetrySpec() {
        return Retry.backoff(maxRetryAttempts, Duration.ofMillis(initialBackoffMillis))
                .maxBackoff(Duration.ofMillis(maxBackoffMillis))
                .filter(this::shouldRetry)
                .doBeforeRetry(retrySignal ->
                        log.info("Retrying Telegram notification after error. Attempt {}/{}",
                                retrySignal.totalRetries() + 1, maxRetryAttempts)
                );
    }

    /**
     * Determines if a failed request should be retried
     */
    private boolean shouldRetry(Throwable throwable) {
        // Retry on rate limiting (429) and server errors (5xx)
        if (throwable instanceof org.springframework.web.reactive.function.client.WebClientResponseException) {
            org.springframework.web.reactive.function.client.WebClientResponseException ex =
                    (org.springframework.web.reactive.function.client.WebClientResponseException) throwable;

            HttpStatus status = HttpStatus.valueOf(ex.getStatusCode().value());
            return status.equals(HttpStatus.TOO_MANY_REQUESTS) ||
                    (status.is5xxServerError());
        }

        // Also retry on connection issues
        return throwable instanceof java.net.ConnectException ||
                throwable instanceof java.io.IOException;
    }
}
