package io.github.marvinds1.reconciliation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.marvinds1.reconciliation.persistence.ReconciliationRepository;
import io.github.marvinds1.reconciliation.persistence.ReconciliationRun;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

class ReconciliationServiceBulkheadTest {

    private static final byte[] CSV = "reference,value_date,amount,currency\nA,2026-11-02,1,IDR\n"
            .getBytes(StandardCharsets.UTF_8);

    @Test
    void rejectsRunsBeyondTheConcurrencyLimitAndRecoversAfterwards() throws Exception {
        ReconciliationRepository repository = mock(ReconciliationRepository.class);
        CountDownLatch insideRun = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(repository.findRunByHash(anyString())).thenReturn(Optional.empty());
        when(repository.insertRunIfAbsent(any(), anyString(), anyString(), anyString(), anyInt(), any()))
                .thenAnswer(invocation -> {
                    insideRun.countDown();
                    release.await(5, TimeUnit.SECONDS);
                    return true;
                });
        when(repository.findRun(any())).thenReturn(Optional.of(mock(ReconciliationRun.class)));

        var service = new ReconciliationService(repository, mock(PlatformTransactionManager.class), 1000, 1, 1);
        var first = CompletableFuture.supplyAsync(() -> service.reconcile(
                new UploadedFile("a.csv", CSV), new UploadedFile("b.csv", CSV), 1));
        assertThat(insideRun.await(5, TimeUnit.SECONDS)).isTrue();

        assertThatThrownBy(() -> service.reconcile(new UploadedFile("a.csv", CSV), new UploadedFile("b.csv", CSV), 2))
                .isInstanceOf(TooManyRunsException.class);

        release.countDown();
        assertThat(first.get(5, TimeUnit.SECONDS).replayed()).isFalse();
        assertThat(service.reconcile(new UploadedFile("a.csv", CSV), new UploadedFile("b.csv", CSV), 3).replayed())
                .isFalse();
    }
}
