package io.moquette.broker;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

public class MemoryQueueRepository implements IQueueRepository {

    private Map<String, SessionMessageQueue<SessionRegistry.EnqueuedMessage>> queues = new HashMap<>();

    @Override
    public Set<String> listQueueNames() {
        return Collections.unmodifiableSet(queues.keySet());
    }

    @Override
    public boolean containsQueue(String queueName) {
        return queues.containsKey(queueName);
    }

    @Override
    public SessionMessageQueue<SessionRegistry.EnqueuedMessage> getOrCreateQueue(String clientId) {
        if (containsQueue(clientId)) {
            return queues.get(clientId);
        }

        SessionMessageQueue<SessionRegistry.EnqueuedMessage> queue = new InMemoryQueue(this, clientId);
        queues.put(clientId, queue);
        return queue;
    }

    void dropQueue(String queueName) {
        queues.remove(queueName);
    }
}
