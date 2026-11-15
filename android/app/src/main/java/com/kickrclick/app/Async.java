package com.kickrclick.app;

/** Small callback types shared by the Bluetooth code and the plain-Java control logic. */
final class Async {
    private Async() {}

    /** Called when a Bluetooth request finishes. {@code status} is {@link Protocol#OK} or an error code. */
    interface Done {
        void done(int status);
    }

    /** Cancels something that was scheduled. */
    interface Cancel {
        void cancel();
    }

    /** Timers, so the control logic can run on the phone or in a test with a fake clock. */
    interface Timer {
        Cancel schedule(Runnable task, long delayMs);

        long now();
    }
}
