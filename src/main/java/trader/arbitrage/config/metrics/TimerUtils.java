package trader.arbitrage.config.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.experimental.UtilityClass;
import reactor.core.publisher.Mono;

import java.util.function.Supplier;

@UtilityClass
public class TimerUtils {

    public <T> Mono<T> timedMono(Supplier<Mono<T>> supplier, MeterRegistry registry, String name, String... tags) {
        Timer.Sample sample = Timer.start(registry);

        return supplier.get()
                .doOnSuccess(result -> stopTimer(sample, registry, name, tags))
                .doOnError(error -> stopTimer(sample, registry, name, tags));
    }

    private void stopTimer(Timer.Sample sample, MeterRegistry registry, String name, String... tags) {
        sample.stop(
                Timer.builder(name)
                        .tags(tags)
                        .description("Timed operation: " + name)
                        .register(registry)
        );
    }
}

