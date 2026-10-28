package br.com.conversa.app.order;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/** Amounts as Brazilian customers read them: R$ 1.234,50. */
public final class Money {

    private Money() {
    }

    public static String format(BigDecimal amount) {
        DecimalFormat format = new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.of("pt", "BR")));
        return "R$ " + format.format(amount.setScale(2, RoundingMode.HALF_UP));
    }
}
