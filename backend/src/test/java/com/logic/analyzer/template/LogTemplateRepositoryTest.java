package com.logic.analyzer.template;

import com.logic.analyzer.crypto.EncryptionKeyProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Exercises the repository against a real EntityManager/transaction, unlike the
 * Mockito-based service tests elsewhere - a Mockito mock can't catch a missing
 * @Transactional on a custom derived query, but a real Hibernate session can. Regression
 * coverage for "No EntityManager with actual transaction available for current thread -
 * cannot reliably process 'remove' call": a derived deleteBy query executes as a
 * find-then-remove-each-entity loop, which needs its own write transaction that (unlike
 * deleteById, already @Transactional on SimpleJpaRepository itself) isn't supplied just by
 * being a repository method - see the @Transactional on deleteByLastSeenAtBefore.
 *
 * @DataJpaTest wraps every test method in its own transaction (rolled back afterward) by
 * default - that would mask exactly the bug this class exists to catch, since the delete
 * would just join the test's ambient transaction regardless of whether the fix is present.
 * NOT_SUPPORTED suspends that, so each test genuinely calls the repository with no
 * surrounding transaction, matching how TemplateRetentionJob's @Scheduled method calls it.
 */
@DataJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(EncryptionKeyProvider.class) // satisfies LogSource.password's converter bean lookup during Hibernate metadata boot
class LogTemplateRepositoryTest {

    @Autowired
    private LogTemplateRepository repository;

    @BeforeEach
    void setUp() {
        // No ambient transaction to auto-rollback (see class javadoc), so tests clean up explicitly.
        repository.deleteAll();
    }

    @Test
    void deleteByLastSeenAtBeforeRunsWithoutARequiredTransactionError() {
        Instant now = Instant.now();
        repository.save(new LogTemplate("src", "app.log", "old *", 2, now.minus(40, ChronoUnit.DAYS), "old line", 1));
        repository.save(new LogTemplate("src", "app.log", "new *", 2, now, "new line", 1));

        assertThatCode(() -> repository.deleteByLastSeenAtBefore(now.minus(30, ChronoUnit.DAYS)))
                .doesNotThrowAnyException();

        assertThat(repository.findAll()).extracting(LogTemplate::getTemplateText).containsExactly("new *");
    }

    @Test
    void deleteByLastSeenAtBeforeReturnsTheNumberOfRowsDeleted() {
        Instant now = Instant.now();
        repository.save(new LogTemplate("src", "app.log", "old *", 2, now.minus(40, ChronoUnit.DAYS), "old line", 1));

        long deleted = repository.deleteByLastSeenAtBefore(now.minus(30, ChronoUnit.DAYS));

        assertThat(deleted).isEqualTo(1);
    }
}
