package dev.mcp.gateway.rest;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import dev.mcp.gateway.config.GatewayProperties;
import dev.mcp.gateway.support.CommittingPaymentMock;
import org.junit.jupiter.api.Test;
import reactor.netty.resources.ConnectionProvider;

import static org.assertj.core.api.Assertions.*;

class CommittedCancellationTest {
    @Test void localCancellationAfterCommitStopsAttemptsWithoutRollback() throws Exception {
        for (var mode : List.of(CommittingPaymentMock.Mode.STALL_AFTER_COMMIT, CommittingPaymentMock.Mode.DROP_ALWAYS)) {
            try (var backend = new CommittingPaymentMock(mode)) {
                var connections = ConnectionProvider.create("commit-cancel", 2);
                try {
                    var properties = new GatewayProperties("classpath:catalog/payment-catalog.json",
                            Map.of("worker-coordinator", new GatewayProperties.Backend(backend.origin(), "")),
                            new GatewayProperties.Upstream(Duration.ofMillis(200), Duration.ofSeconds(2), 65536), List.of());
                    var executor = new RestBindingExecutor(properties, connections);
                    var binding = new RestBinding("REST", "worker-coordinator", RestBinding.Method.POST, "/api/v1/payments",
                            "{\"amount\":12.34}", RestBinding.pointer("/id"), "clientIdempotencyKey");
                    var subscription = executor.execute(binding).subscribe();
                    backend.take(); // commit is recorded before this observation
                    subscription.dispose();
                    int attempts = backend.count();
                    assertThat(backend.poll(450)).isNull();
                    assertThat(backend.count()).isEqualTo(attempts); assertThat(backend.commits()).isEqualTo(1);
                }
                finally { connections.dispose(); }
            }
        }
    }
}
