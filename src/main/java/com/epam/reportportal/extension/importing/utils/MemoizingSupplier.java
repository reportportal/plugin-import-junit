package com.epam.reportportal.extension.importing.utils;

import static com.google.common.base.Preconditions.checkNotNull;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * @author Andrei Piankouski
 */
public class MemoizingSupplier<T> implements Supplier<T> {

    private final Supplier<T> delegate;

    private AtomicBoolean initialized = new AtomicBoolean(false);

    private T value;

    public MemoizingSupplier(Supplier<T> delegate) {
        this.delegate = checkNotNull(delegate);
    }

    @Override
    public T get() {
        if (!initialized.get()) {
            synchronized (this) {
                if (!initialized.get()) {
                    T t = delegate.get();
                    value = t;
                    initialized.set(true);
                    return t;
                }
            }
        }
        return value;
    }

}
