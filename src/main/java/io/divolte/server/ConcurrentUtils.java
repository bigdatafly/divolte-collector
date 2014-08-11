package io.divolte.server;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

@ParametersAreNonnullByDefault
final class ConcurrentUtils {
    private static final Logger logger = LoggerFactory.getLogger(ConcurrentUtils.class);
    private ConcurrentUtils() {
        throw new UnsupportedOperationException("Singleton; do not instantiate.");
    }

    @Nullable
    public static <E> E pollQuietly(final BlockingQueue<E> queue, final long timeout, final TimeUnit unit) {
        try {
            return queue.poll(timeout, unit);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    public static <T> boolean offerQuietly(final BlockingQueue<T> queue,
                                           final T item,
                                           final long timeout,
                                           final TimeUnit unit) {
        try {
            return queue.offer(item, timeout, unit);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public static ThreadFactory createThreadFactory(final ThreadGroup group, final String nameFormat) {
        return new ThreadFactoryBuilder()
        .setNameFormat(nameFormat)
        .setThreadFactory((runnable) -> new Thread(group, runnable))
        .build();
    }

    public static <T> Runnable microBatchingQueueDrainerWithHeartBeat(final BlockingQueue<T> queue,
                                                                      final Consumer<T> consumer,
                                                                      @Nullable final Runnable heartBeatAction) {
        return () -> {
            final int maxBatchSize = 100;
            final List<T> batch = new ArrayList<>(maxBatchSize);

            while(!queue.isEmpty() || !Thread.currentThread().isInterrupted()) {
                queue.drainTo(batch, maxBatchSize - 1);
                final int batchSize = batch.size();

                batch.forEach(consumer);
                batch.clear();

                // if the batch was empty, block on the queue for some time until something is available
                final T polled;
                if (batchSize == 0) {
                    if ((polled = pollQuietly(queue, 1, TimeUnit.SECONDS)) != null) {
                        batch.add(polled);
                    } else {
                        heartBeatAction.run();
                    }
                }
            }
        };
    }

    public static <T> Runnable microBatchingQueueDrainer(final BlockingQueue<T> queue, final Consumer<T> consumer) {
        return microBatchingQueueDrainerWithHeartBeat(queue, consumer, () -> {});
    }

    public static void scheduleQueueReaderWithCleanup(final ExecutorService es, final Runnable reader, final Runnable cleanup) {
        CompletableFuture
        .runAsync(reader, es)
        .whenComplete((voidValue, error) -> {
            cleanup.run();

            // In case the reader for some reason escapes its loop with an exception,
            // log any uncaught exceptions and reschedule
            if (error != null) {
                logger.warn("Uncaught exception in incoming queue reader thread.", error);
                scheduleQueueReaderWithCleanup(es, reader, cleanup);
            }
        });
    }

    public static void scheduleQueueReader(final ExecutorService es, final Runnable reader) {
        scheduleQueueReaderWithCleanup(es, reader, () ->
            logger.debug("Unhandled cleanup for thread: {}", Thread.currentThread().getName()));
    }

    @FunctionalInterface
    public interface IOExceptionThrower {
        public abstract void run() throws IOException;
    }

    public static boolean throwsIoException(final IOExceptionThrower r) {
        try {
            r.run();
            return false;
        } catch (final IOException ioe) {
            return true;
        }
    }
}
