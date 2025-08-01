package bisq.network.tor.controller;

import bisq.network.tor.controller.events.events.EventType;
import bisq.network.tor.controller.events.events.HsDescEvent;
import bisq.network.tor.controller.events.events.TorBootstrapEvent;
import bisq.network.tor.controller.events.listener.BootstrapEventListener;
import bisq.network.tor.controller.events.listener.HsDescEventListener;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;

@Slf4j
public class TorControlReader implements AutoCloseable {
    private final BlockingQueue<String> replies = new LinkedBlockingQueue<>();
    @Getter
    private final List<BootstrapEventListener> bootstrapEventListeners = new CopyOnWriteArrayList<>();
    @Getter
    private final List<HsDescEventListener> hsDescEventListeners = new CopyOnWriteArrayList<>();

    private Optional<Thread> workerThread = Optional.empty();
    private volatile boolean isStopped;

    public void start(InputStream inputStream) {
        Thread thread = new Thread(() -> {
            Thread.currentThread().setName("TorControlReader.start");
            try {
                var bufferedReader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.US_ASCII));

                String line;
                while (!isStopped && (line = bufferedReader.readLine()) != null && !Thread.currentThread().isInterrupted()) {

                    log.info("Received reply from Tor control server: {}", line);
                    if (isEventWithCode_650(line)) {
                        String[] parts = line.split(" ");

                        boolean parsedEvent = false;
                        if (parts.length > 2) {
                            String eventType = parts[1];

                            if (isStatusClientEvent(eventType)) {
                                Optional<TorBootstrapEvent> bootstrapEventOptional = BootstrapEventParser.tryParse(parts);
                                if (bootstrapEventOptional.isPresent()) {
                                    parsedEvent = true;
                                    TorBootstrapEvent torBootstrapEvent = bootstrapEventOptional.get();
                                    bootstrapEventListeners.forEach(listener -> listener.onBootstrapStatusEvent(torBootstrapEvent));
                                }

                            } else if (isHsDescEvent(eventType)) {
                                Optional<HsDescEvent> hsDescEventOptional = HsDescEventParser.tryParse(parts);
                                if (hsDescEventOptional.isPresent()) {
                                    parsedEvent = true;
                                    HsDescEvent hsDescEvent = hsDescEventOptional.get();
                                    hsDescEventListeners.forEach(listener -> listener.onHsDescEvent(hsDescEvent));
                                }
                            }
                        }

                        if (!parsedEvent) {
                            log.info("Unknown Tor event: {}", line);
                        }

                    } else {
                        replies.add(line);
                    }

                    if (Thread.interrupted()) {
                        break;
                    }
                }
            } catch (IOException e) {
                if (!isStopped) {
                    log.error("Tor control port reader couldn't read reply.", e);
                }
            }
        });
        workerThread = Optional.of(thread);
        thread.start();
    }

    @Override
    public void close() {
        isStopped = true;
        workerThread.ifPresent(Thread::interrupt);
    }

    public String readLine() {
        try {
            return replies.take();
        } catch (InterruptedException e) {
            log.warn("Thread got interrupted at readLine method", e);
            Thread.currentThread().interrupt(); // Restore interrupted state
            throw new RuntimeException(e);
        }
    }

    public void addBootstrapEventListener(BootstrapEventListener listener) {
        bootstrapEventListeners.add(listener);
    }

    public void removeBootstrapEventListener(BootstrapEventListener listener) {
        bootstrapEventListeners.remove(listener);
    }

    public void addHsDescEventListener(HsDescEventListener listener) {
        hsDescEventListeners.add(listener);
    }

    public void removeHsDescEventListener(HsDescEventListener listener) {
        hsDescEventListeners.remove(listener);
    }

    private boolean isEventWithCode_650(String line) {
        // 650 STATUS_CLIENT NOTICE CIRCUIT_ESTABLISHED
        return line.startsWith("650");
    }

    private static boolean isStatusClientEvent(String eventType) {
        // 650 STATUS_CLIENT NOTICE CIRCUIT_ESTABLISHED
        return eventType.equals(EventType.STATUS_CLIENT.name());
    }

    private static boolean isHsDescEvent(String eventType) {
        // 650 HS_DESC CREATED <onion_address> UNKNOWN UNKNOWN <descriptor_id>
        return eventType.equals(EventType.HS_DESC.name());
    }
}
