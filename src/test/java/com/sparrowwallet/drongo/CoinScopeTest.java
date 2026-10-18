package com.sparrowwallet.drongo;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Guards the fail-closed coin scoping the unified Sparrow-Scrypt build depends on.
 *
 * The flavor builds baked one coin per binary, so an unscoped {@link Coin#get()} could only ever
 * resolve to the right chain. The unified build holds wallets of several coins at once and resolves
 * the active coin from whichever tab has focus, so a background thread that derives an address
 * without declaring its wallet's coin silently derives against the focused wallet's chain instead.
 * That yields a well-formed address on the wrong chain - and between Dogecoin and Dingocoin, whose
 * address parameters are byte-for-byte identical, one that cannot be told apart from a correct one.
 *
 * Resolving to the focused coin is right for the UI thread and wrong for everything else, so the
 * rule is per-thread rather than global: the foreground thread follows focus, background threads
 * must declare a scope or fail.
 */
public class CoinScopeTest {
    @AfterEach
    public void resetScoping() {
        Coin.setStrictScoping(false);
        Coin.setForegroundThread(null);
        Coin.set(Coin.LITECOIN);
    }

    private record Outcome(Coin value, Throwable thrown) {
    }

    private static Outcome onBackgroundThread(Supplier<Coin> work) throws InterruptedException {
        AtomicReference<Coin> value = new AtomicReference<>();
        AtomicReference<Throwable> thrown = new AtomicReference<>();

        Thread thread = new Thread(() -> {
            try {
                value.set(work.get());
            } catch(Throwable t) {
                thrown.set(t);
            }
        }, "coin-scope-test");

        thread.start();
        thread.join();

        return new Outcome(value.get(), thrown.get());
    }

    @Test
    public void unscopedBackgroundAccessThrowsUnderStrictScoping() throws InterruptedException {
        Coin.set(Coin.LITECOIN);
        Coin.setForegroundThread(Thread.currentThread());
        Coin.setStrictScoping(true);

        Outcome outcome = onBackgroundThread(Coin::get);

        Assertions.assertNotNull(outcome.thrown(),
                "an unscoped background thread resolved " + outcome.value()
                        + " from the focused wallet instead of refusing to guess");
        Assertions.assertInstanceOf(IllegalStateException.class, outcome.thrown());
    }

    @Test
    public void scopedBackgroundAccessReturnsTheScopedCoin() throws InterruptedException {
        Coin.set(Coin.LITECOIN);
        Coin.setForegroundThread(Thread.currentThread());
        Coin.setStrictScoping(true);

        Outcome outcome = onBackgroundThread(() -> {
            Coin previous = Coin.beginScope(Coin.DOGECOIN);
            try {
                return Coin.get();
            } finally {
                Coin.endScope(previous);
            }
        });

        Assertions.assertNull(outcome.thrown(), "a correctly scoped background thread must not throw");
        Assertions.assertEquals(Coin.DOGECOIN, outcome.value(),
                "the scoped coin must win over the focused coin");
    }

    /**
     * The scope must not outlive its bracket. A thread pool reuses threads, so a scope left behind by
     * one task would silently become the next task's coin - the same wrong-chain derivation this is
     * meant to prevent, only harder to trace.
     */
    @Test
    public void endScopeRestoresTheFailClosedState() throws InterruptedException {
        Coin.set(Coin.LITECOIN);
        Coin.setForegroundThread(Thread.currentThread());
        Coin.setStrictScoping(true);

        Outcome outcome = onBackgroundThread(() -> {
            Coin previous = Coin.beginScope(Coin.DOGECOIN);
            Coin.endScope(previous);
            return Coin.get();
        });

        Assertions.assertNotNull(outcome.thrown(),
                "the thread still resolved " + outcome.value() + " after its scope ended");
        Assertions.assertInstanceOf(IllegalStateException.class, outcome.thrown());
    }

    @Test
    public void nestedScopesRestoreTheOuterCoin() throws InterruptedException {
        Coin.set(Coin.LITECOIN);
        Coin.setForegroundThread(Thread.currentThread());
        Coin.setStrictScoping(true);

        Outcome outcome = onBackgroundThread(() -> {
            Coin outer = Coin.beginScope(Coin.DOGECOIN);
            try {
                Coin inner = Coin.beginScope(Coin.BELLSCOIN);
                Coin.endScope(inner);
                return Coin.get();
            } finally {
                Coin.endScope(outer);
            }
        });

        Assertions.assertNull(outcome.thrown());
        Assertions.assertEquals(Coin.DOGECOIN, outcome.value(),
                "closing the inner scope must restore the outer one, not clear scoping entirely");
    }

    @Test
    public void foregroundAccessStillFollowsTheFocusedCoin() {
        Coin.setForegroundThread(Thread.currentThread());
        Coin.setStrictScoping(true);
        Coin.set(Coin.DOGECOIN);

        Assertions.assertEquals(Coin.DOGECOIN, Coin.get(),
                "focus-follow is the intended model on the UI thread - only background work must declare a scope");
    }

    /**
     * A single-coin flavor binary has nothing to confuse, so strict scoping stays off there and the
     * historical unscoped fallback must keep working unchanged.
     */
    @Test
    public void flavorBuildsKeepTheUnscopedFallback() throws InterruptedException {
        Coin.set(Coin.DOGECOIN);

        Outcome outcome = onBackgroundThread(Coin::get);

        Assertions.assertNull(outcome.thrown(), "strict scoping is off, so nothing may throw");
        Assertions.assertEquals(Coin.DOGECOIN, outcome.value());
    }
}
