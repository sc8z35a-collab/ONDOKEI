package jp.rstlab.batteryrelay.share;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Verifies absolute request/response deadlines in addition to Socket SO_TIMEOUT. */
public final class TransportDeadlineRegressionTest {
    @Test
    public void hostRejectsByteTrickleThatNeverTimesOutPerRead() throws Exception {
        assertDeadline(() -> ShareHost.readLineLimited(
                new SlowInputStream("{\"v\":1}\n", 30L), 60L), "request_deadline");
    }

    @Test
    public void clientRejectsByteTrickleThatNeverTimesOutPerRead() throws Exception {
        assertDeadline(() -> RemoteClient.readLineLimited(
                new SlowInputStream("{\"ok\":true}\n", 30L), 60L), "response_deadline");
    }

    @Test
    public void fastBoundedLinesStillPass() throws Exception {
        assertEquals("{\"v\":1}", ShareHost.readLineLimited(
                new SlowInputStream("{\"v\":1}\n", 0L), 1_000L));
        assertEquals("{\"ok\":true}", RemoteClient.readLineLimited(
                new SlowInputStream("{\"ok\":true}\n", 0L), 1_000L));
    }

    private static void assertDeadline(ThrowingSupplier supplier, String expected) throws Exception {
        try {
            supplier.get();
            fail("slow trickle was accepted");
        } catch (IOException error) {
            assertTrue(String.valueOf(error.getMessage()).contains(expected));
        }
    }

    private interface ThrowingSupplier {
        String get() throws Exception;
    }

    private static final class SlowInputStream extends InputStream {
        private final byte[] bytes;
        private final long delayMillis;
        private int index;

        SlowInputStream(String text, long delayMillis) {
            this.bytes = text.getBytes(StandardCharsets.UTF_8);
            this.delayMillis = delayMillis;
        }

        @Override public int read() throws IOException {
            if (index >= bytes.length) return -1;
            if (delayMillis > 0L) {
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted", interrupted);
                }
            }
            return bytes[index++] & 0xff;
        }
    }
}
