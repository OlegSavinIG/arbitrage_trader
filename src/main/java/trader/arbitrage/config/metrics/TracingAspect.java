package trader.arbitrage.config.metrics;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

/**
 * Аспект для автоматического добавления трассировки к методам клиентов API.
 */
@Aspect
@Component
@Slf4j
@RequiredArgsConstructor
public class TracingAspect {

    private final ObservationRegistry observationRegistry;

    /**
     * Pointcut для перехвата всех публичных методов в клиентских классах API.
     */
    @Pointcut("execution(public * trader.arbitrage.client.*Client.*(..))")
    public void apiClientMethods() {}

    /**
     * Обертывает вызовы API в наблюдение (span) для трассировки.
     */
    @Around("apiClientMethods()")
    public Object traceApiCalls(ProceedingJoinPoint joinPoint) throws Throwable {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        String methodName = method.getName();
        String className = method.getDeclaringClass().getSimpleName();

        String observationName = "api.client." + className + "." + methodName;

        return Observation.createNotStarted(observationName, observationRegistry)
                .lowCardinalityKeyValue("className", className)
                .lowCardinalityKeyValue("methodName", methodName)
                .observe(() -> {
                    try {
                        return joinPoint.proceed();
                    } catch (Throwable t) {
                        // Для Observable/Reactive возвращаем объект с ошибкой вместо выбрасывания исключения
                        if (t instanceof RuntimeException) {
                            throw (RuntimeException) t;
                        } else {
                            throw new RuntimeException(t);
                        }
                    }
                });
    }
}
