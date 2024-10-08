/*
 * This file is part of Bisq.
 *
 * Bisq is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * Bisq is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Bisq. If not, see <http://www.gnu.org/licenses/>.
 */

package bisq.wallets.bitcoind.zmq;

import bisq.wallets.regtest.BitcoindExtension;
import bisq.wallets.regtest.bitcoind.BitcoindRegtestSetup;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@ExtendWith(BitcoindExtension.class)
@Slf4j
public class BitcoindZeroMqBlockHashIntegrationIntegrationTests {

    private final BitcoindRegtestSetup regtestSetup;
    private final CountDownLatch listenerReceivedBlockHashLatch = new CountDownLatch(1);

    public BitcoindZeroMqBlockHashIntegrationIntegrationTests(BitcoindRegtestSetup regtestSetup) {
        this.regtestSetup = regtestSetup;
    }

    @Test
    void blockHashNotification() throws InterruptedException {
        ZmqListeners zmqListeners = regtestSetup.getZmqListeners();
        zmqListeners.registerNewBlockMinedListener((blockHash) -> {
            log.info("Notification: New block with hash {}", blockHash);
            listenerReceivedBlockHashLatch.countDown();
        });

        Thread thread = new Thread(() -> {
            while (true) {
                try {
                    List<String> blockHashes = regtestSetup.mineOneBlock();
                    log.info("Mined block: {}", blockHashes);

                    if (Thread.interrupted()) {
                        break;
                    }

                    log.info("Sleeping for 200ms before mining next block.");
                    //noinspection BusyWait
                    Thread.sleep(200);

                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
        });

        thread.start();

        boolean await = listenerReceivedBlockHashLatch.await(1, TimeUnit.MINUTES);
        thread.interrupt();

        if (!await) {
            throw new IllegalStateException("Didn't connect to bitcoind after 1 minute.");
        }
    }
}
