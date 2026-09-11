package tech.skworks.tachyon.plugin.core.resilience;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Short class explaination
 * <p>
 * Long class explaination with @link if needed
 * </p>
 *
 * <p><i>Project Tachyon</i></p>
 *
 * @author Jimmy (vSKAH) - 10/09/2026
 * @version 1.0
 * @since 2.0.0-alpha.2
 */
public final class ConcurrentRingBuffer<T> {


    private final Object[] elements;
    private final int mask;
    private final int capacity;
    private final ReentrantLock lock;

    private long writeSequence, readSequence;

    public ConcurrentRingBuffer(final int capacity) throws IllegalArgumentException {
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be positive");
        this.capacity = Integer.highestOneBit(capacity) == capacity ? capacity : Integer.highestOneBit(capacity) << 1;
        this.mask = this.capacity - 1;
        this.elements = new Object[this.capacity];
        this.lock = new ReentrantLock();

        this.writeSequence = 0;
        this.readSequence = 0;
    }

    public void offer(@NotNull final T item) {
        Objects.requireNonNull(item, "item can't be null");
        lock.lock();

        try {
            final var currentSize = writeSequence - readSequence;
            if (currentSize == capacity) {
                elements[(int) (readSequence++ & mask)] = null;
            }
            elements[(int) (writeSequence++ & mask)] = item;
        }
        finally {
            lock.unlock();
        }
    }

    public @NotNull List<T> drain(int number) {
        if (number <= 0) return List.of();

        lock.lock();
        try {
            final var maxElementsToDrain = (int) (writeSequence - readSequence);
            if (maxElementsToDrain == 0) return List.of();

            final var toDrain = Math.min(maxElementsToDrain, number);
            final var result = new ArrayList<T>(toDrain);

            for (int i = 0; i < toDrain; i++) {
                int index = (int) (readSequence & mask);
                result.add((T) this.elements[index]);
                this.elements[index] = null;
                readSequence++;
            }

            return result;
        }
        finally {
            lock.unlock();
        }
    }

    public List<T> drainAll() {
        return drain(capacity);
    }

    public int size() {
        lock.lock();
        try {
            return (int) (writeSequence - readSequence);
        } finally {
            lock.unlock();
        }
    }

    public boolean isEmpty() {
        return size() == 0;
    }

    public int capacity() {
        return capacity;
    }

    public void clear() {
        lock.lock();
        try {
            while (readSequence < writeSequence) {
                elements[(int) (readSequence & mask)] = null;
                readSequence++;
            }
        } finally {
            lock.unlock();
        }
    }

}
