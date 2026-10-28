package br.com.conversa.app.tenant;

import br.com.conversa.core.tenant.TenantId;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.function.Supplier;

/**
 * Tenant context of business work (ADR-003, ADR-010): runs the work in a transaction bound to one
 * tenant, as the {@code conversa_app} role, so row-level security hides every other tenant. The
 * binding lasts until that transaction ends; joining a transaction already bound to another tenant
 * is refused.
 */
@Component
public class TenantTransactions {

    static final String APP_ROLE = "conversa_app";

    private final TransactionTemplate transactions;
    private final JdbcClient jdbc;

    public TenantTransactions(TransactionTemplate transactions, JdbcClient jdbc) {
        this.transactions = transactions;
        this.jdbc = jdbc;
    }

    public <T> T inTenant(TenantId tenant, Supplier<T> work) {
        return transactions.execute(status -> {
            bind(tenant);
            return work.get();
        });
    }

    public void runInTenant(TenantId tenant, Runnable work) {
        inTenant(tenant, () -> {
            work.run();
            return null;
        });
    }

    private void bind(TenantId tenant) {
        List<String> current = jdbc.sql("SELECT current_setting('app.tenant_id', true)").query(String.class).list();
        String bound = current.isEmpty() ? null : current.getFirst();
        if (bound != null && !bound.isEmpty() && !bound.equals(tenant.toString())) {
            throw new IllegalStateException("Transaction is already bound to another tenant");
        }
        jdbc.sql("SELECT set_config('app.tenant_id', :tenant, true)")
                .param("tenant", tenant.toString())
                .query(String.class).list();
        jdbc.sql("SET LOCAL ROLE " + APP_ROLE).update();
    }
}
