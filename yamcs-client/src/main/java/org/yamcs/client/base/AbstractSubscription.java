package org.yamcs.client.base;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.yamcs.api.Observer;
import org.yamcs.client.MessageListener;
import org.yamcs.client.Subscription;

import com.google.protobuf.Message;

/**
 * Default base class for a {@link Subscription}.
 * <p>
 * This is designed such that most subclasses need only to provide type information. More advanced subscription
 * subclasses may want to add custom functionality such as call-specific message processing.
 */
public abstract class AbstractSubscription<C extends Message, S extends Message> implements Subscription<C, S> {

    // A future that resolves when the call is completed
    private CompletableFuture<Void> wrappedFuture = new CompletableFuture<>();

    protected Observer<C> clientObserver;

    private Set<MessageListener<S>> messageListeners = new CopyOnWriteArraySet<>();

    protected AbstractSubscription(WebSocketClient client, String topic, Class<S> responseClass) {
        clientObserver = client.call(topic, new DataObserver<S>() {

            @Override
            public void next(S message) {
                messageListeners.forEach(l -> l.onMessage(message));
            }

            @Override
            public void completeExceptionally(Throwable t) {
                messageListeners.forEach(l -> l.onError(t));
                wrappedFuture.completeExceptionally(t);
            }

            @Override
            public void complete() {
                wrappedFuture.complete(null);
            }

            @Override
            public Class<S> getMessageClass() {
                return responseClass;
            }
        });
    }

    /**
     * Send a message (typically a subscription request) to Yamcs
     */
    @Override
    public void sendMessage(C message) {
        clientObserver.next(message);
    }

    /**
     * Get updated on received server messages.
     */
    @Override
    public void addMessageListener(MessageListener<S> listener) {
        messageListeners.add(listener);
    }

    /**
     * Cancel this subscription. After this method is called, you will no longer receive any messages from it.
     */
    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
        messageListeners.clear(); // Immediately ensure nobody will receive anything anymore
        clientObserver.complete(); // Now notify Yamcs async.
        return true;
    }

    @Override
    public boolean isCancelled() {
        return wrappedFuture.isCancelled();
    }

    @Override
    public boolean isDone() {
        return wrappedFuture.isDone();
    }

    @Override
    public Void get() throws InterruptedException, ExecutionException {
        return wrappedFuture.get();
    }

    @Override
    public Void get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
        return wrappedFuture.get(timeout, unit);
    }
}
