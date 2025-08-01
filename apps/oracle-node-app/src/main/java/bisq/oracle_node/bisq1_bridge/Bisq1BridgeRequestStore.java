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

package bisq.oracle_node.bisq1_bridge;

import bisq.common.proto.ProtoResolver;
import bisq.common.proto.UnresolvableProtobufMessageException;
import bisq.persistence.PersistableStore;
import bisq.user.reputation.requests.AuthorizeAccountAgeRequest;
import bisq.user.reputation.requests.AuthorizeSignedWitnessRequest;
import com.google.protobuf.InvalidProtocolBufferException;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.stream.Collectors;

/**
 * We persist the requests so that in case of a scam or trade rule violation we could block the user at Bisq 1 as well.
 * This is a bit of a trade-off between security and privacy. One option to improve that would be that all data is
 * persisted as encrypted entries and the decryption key is help by another bonded role. So it would require the
 * cooperation of the oracle node operator with the key holder.
 */
@NoArgsConstructor(access = AccessLevel.PACKAGE)
@Slf4j
final class Bisq1BridgeRequestStore implements PersistableStore<Bisq1BridgeRequestStore> {
    @Getter(AccessLevel.PACKAGE)
    private final Set<AuthorizeAccountAgeRequest> accountAgeRequests = new CopyOnWriteArraySet<>();
    @Getter(AccessLevel.PACKAGE)
    private final Set<AuthorizeSignedWitnessRequest> signedWitnessRequests = new CopyOnWriteArraySet<>();

    private Bisq1BridgeRequestStore(Set<AuthorizeAccountAgeRequest> accountAgeRequests, Set<AuthorizeSignedWitnessRequest> signedWitnessRequests) {
        this.accountAgeRequests.addAll(accountAgeRequests);
        this.signedWitnessRequests.addAll(signedWitnessRequests);
    }

    @Override
    public bisq.oracle_node.protobuf.Bisq1BridgeRequestStore.Builder getBuilder(boolean serializeForHash) {
        return bisq.oracle_node.protobuf.Bisq1BridgeRequestStore.newBuilder()
                .addAllAccountAgeRequests(accountAgeRequests.stream()
                        .map(e -> e.toValueProto(serializeForHash))
                        .collect(Collectors.toList()))
                .addAllSignedWitnessRequests(signedWitnessRequests.stream()
                        .map(e -> e.toValueProto(serializeForHash))
                        .collect(Collectors.toList()));
    }

    @Override
    public bisq.oracle_node.protobuf.Bisq1BridgeRequestStore toProto(boolean serializeForHash) {
        return resolveProto(serializeForHash);
    }

    public static Bisq1BridgeRequestStore fromProto(bisq.oracle_node.protobuf.Bisq1BridgeRequestStore proto) {
        return new Bisq1BridgeRequestStore(
                proto.getAccountAgeRequestsList().stream()
                        .map(AuthorizeAccountAgeRequest::fromProto)
                        .collect(Collectors.toSet()),
                proto.getSignedWitnessRequestsList().stream()
                        .map(AuthorizeSignedWitnessRequest::fromProto)
                        .collect(Collectors.toSet()));
    }

    @Override
    public ProtoResolver<PersistableStore<?>> getResolver() {
        return any -> {
            try {
                return fromProto(any.unpack(bisq.oracle_node.protobuf.Bisq1BridgeRequestStore.class));
            } catch (InvalidProtocolBufferException e) {
                throw new UnresolvableProtobufMessageException(e);
            }
        };
    }

    @Override
    public Bisq1BridgeRequestStore getClone() {
        return new Bisq1BridgeRequestStore(new HashSet<>(accountAgeRequests), new HashSet<>(signedWitnessRequests));
    }

    @Override
    public void applyPersisted(Bisq1BridgeRequestStore persisted) {
        accountAgeRequests.clear();
        accountAgeRequests.addAll(persisted.getAccountAgeRequests());
        signedWitnessRequests.clear();
        signedWitnessRequests.addAll(persisted.getSignedWitnessRequests());
    }
}