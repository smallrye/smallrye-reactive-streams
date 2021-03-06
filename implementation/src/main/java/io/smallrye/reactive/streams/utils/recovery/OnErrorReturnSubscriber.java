package io.smallrye.reactive.streams.utils.recovery;

import java.util.Objects;
import java.util.function.Function;

import org.reactivestreams.Subscriber;

import io.reactivex.internal.subscribers.SinglePostCompleteSubscriber;

public class OnErrorReturnSubscriber<T> extends SinglePostCompleteSubscriber<T, T> {

    private static final long serialVersionUID = 1111111L;
    private final transient Function<? super Throwable, ? extends T> valueSupplier;

    OnErrorReturnSubscriber(Subscriber<? super T> actual,
            Function<? super Throwable, ? extends T> valueSupplier) {
        super(actual);
        this.valueSupplier = valueSupplier;
    }

    @Override
    public void onNext(T t) {
        produced++;
        downstream.onNext(t);
    }

    @Override
    public void onError(Throwable t) {
        T v;
        try {
            v = Objects.requireNonNull(valueSupplier.apply(t), "The valueSupplier returned a null value");
        } catch (Exception ex) {

            downstream.onError(ex);
            return;
        }
        complete(v);
    }

    @Override
    public void onComplete() {
        downstream.onComplete();
    }
}
