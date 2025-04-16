package trader.arbitrage.config.metrics;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Пример класса, демонстрирующего ручное управление трассировкой.
 * Это особенно полезно в сложных сценариях или когда аннотации @Observed недостаточно.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ManualTracingExample {

    private final ObservationRegistry observationRegistry;

    /**
     * Пример метода, который создает вручную управляемый спан трассировки.
     */
    public void doSomethingComplex(String operation, Map<String, String> metadata) {
        // Создаем новое наблюдение (span)
        Observation observation = Observation.createNotStarted("manual.operation", observationRegistry)
                .lowCardinalityKeyValue("operation", operation)
                .highCardinalityKeyValue("userId", metadata.getOrDefault("userId", "unknown"));

        // Начинаем наблюдение (span)
        observation.observe(() -> {
            try {
                log.info("Starting complex operation: {}", operation);

                // Добавляем дополнительное событие в спан
                observation.event(Observation.Event.of("operation.start"));

                // Здесь выполняется сложная операция...
                processOperation(operation, metadata);

                // Добавляем еще одно событие
                observation.event(Observation.Event.of("operation.complete"));

                log.info("Complex operation completed: {}", operation);
            } catch (Exception e) {
                // Добавляем информацию об ошибке в спан
                observation.error(e);
                log.error("Error during complex operation: {}", e.getMessage(), e);
                throw e;
            }
        });
    }

    private void processOperation(String operation, Map<String, String> metadata) {
        // Имитация сложной операции
        try {
            Thread.sleep(100);  // Имитация работы

            // Создаем вложенный спан (child span)
            Observation childObservation = Observation.createNotStarted("manual.operation.step", observationRegistry)
                    .lowCardinalityKeyValue("step", "processing")
                    .lowCardinalityKeyValue("operation", operation);

            childObservation.observe(() -> {
                log.info("Processing step for operation: {}", operation);
                // Дополнительная обработка...
                try {
                    Thread.sleep(50);  // Еще одна имитация работы
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
