package com.liskovsoft.smartyoutubetv2.mobile.ui.signin;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DeviceCodeSignInTest {
    /** Fails when upstream renames the private method; the app would then silently lose the fast poll. */
    @Test
    public void upstreamPersistMethodExists() {
        assertNotNull(DeviceCodeSignIn.persistMethod());
    }

    @Test
    public void pollNowEndsTheWait() throws Exception {
        DeviceCodeSignIn signIn = new DeviceCodeSignIn();
        Thread waker = new Thread(() -> {
            try { Thread.sleep(100); } catch (InterruptedException ignored) { }
            signIn.pollNow();
        });
        long start = System.currentTimeMillis();
        waker.start();
        signIn.awaitNextPoll(10_000);
        assertTrue(System.currentTimeMillis() - start < 5_000);
    }

    @Test
    public void pollNowBeforeTheWaitIsNotLost() throws Exception {
        DeviceCodeSignIn signIn = new DeviceCodeSignIn();
        signIn.pollNow();
        long start = System.currentTimeMillis();
        signIn.awaitNextPoll(10_000);
        assertTrue(System.currentTimeMillis() - start < 1_000);
    }
}
