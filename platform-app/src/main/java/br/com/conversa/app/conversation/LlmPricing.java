package br.com.conversa.app.conversation;

import br.com.conversa.core.llm.LlmUsage;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

/**
 * Price table of the models in USD per million tokens, versioned in configuration (ADR-005).
 * A model missing from the table costs zero and is logged by the caller.
 */
@ConfigurationProperties("conversa.llm")
public record LlmPricing(Map<String, Price> prices) {

    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);

    public record Price(BigDecimal input, BigDecimal output, BigDecimal cacheRead, BigDecimal cacheWrite) {
    }

    public LlmPricing {
        prices = prices == null ? Map.of() : Map.copyOf(prices);
    }

    public boolean knows(String model) {
        return prices.containsKey(model);
    }

    public BigDecimal cost(String model, LlmUsage usage) {
        Price price = prices.get(model);
        if (price == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal micro = price.input().multiply(BigDecimal.valueOf(usage.inputTokens()))
                .add(price.output().multiply(BigDecimal.valueOf(usage.outputTokens())))
                .add(price.cacheRead().multiply(BigDecimal.valueOf(usage.cacheReadTokens())))
                .add(price.cacheWrite().multiply(BigDecimal.valueOf(usage.cacheWriteTokens())));
        return micro.divide(MILLION, 6, RoundingMode.HALF_UP);
    }
}
