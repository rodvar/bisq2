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

package bisq.support.mediation;

import bisq.chat.mu_sig.open_trades.MuSigOpenTradeMessage;
import bisq.common.proto.ProtoResolver;
import bisq.common.proto.UnresolvableProtobufMessageException;
import bisq.common.validation.NetworkDataValidation;
import bisq.network.p2p.message.ExternalNetworkMessage;
import bisq.network.p2p.services.data.storage.MetaData;
import bisq.network.p2p.services.data.storage.mailbox.MailboxMessage;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static bisq.network.p2p.services.data.storage.MetaData.HIGH_PRIORITY;
import static bisq.network.p2p.services.data.storage.MetaData.TTL_10_DAYS;
import static com.google.common.base.Preconditions.checkArgument;

@Slf4j
@Getter
@ToString
@EqualsAndHashCode
public final class MuSigDisputeCaseDataMessage implements MailboxMessage, ExternalNetworkMessage {
    private transient final MetaData metaData = new MetaData(TTL_10_DAYS, HIGH_PRIORITY, getClass().getSimpleName());
    private final String tradeId;
    private final String senderUserProfileId;
    private final byte[] contractHash;
    private final List<MuSigOpenTradeMessage> chatMessages;

    public MuSigDisputeCaseDataMessage(String tradeId,
                                       String senderUserProfileId,
                                       byte[] contractHash,
                                       List<MuSigOpenTradeMessage> chatMessages) {
        this.tradeId = tradeId;
        this.senderUserProfileId = senderUserProfileId;
        this.contractHash = contractHash.clone();
        this.chatMessages = maybePrune(chatMessages);

        Collections.sort(this.chatMessages);
        verify();
    }

    @Override
    public void verify() {
        NetworkDataValidation.validateTradeId(tradeId);
        NetworkDataValidation.validateProfileId(senderUserProfileId);
        NetworkDataValidation.validateHash(contractHash);
        checkArgument(chatMessages.size() < 1000);
    }

    @Override
    public bisq.support.protobuf.MuSigDisputeCaseDataMessage.Builder getValueBuilder(boolean serializeForHash) {
        return bisq.support.protobuf.MuSigDisputeCaseDataMessage.newBuilder()
                .setTradeId(tradeId)
                .setSenderUserProfileId(senderUserProfileId)
                .setContractHash(ByteString.copyFrom(contractHash))
                .addAllChatMessages(chatMessages.stream()
                        .map(message -> message.toValueProto(serializeForHash))
                        .collect(Collectors.toList()));
    }

    public static MuSigDisputeCaseDataMessage fromProto(bisq.support.protobuf.MuSigDisputeCaseDataMessage proto) {
        return new MuSigDisputeCaseDataMessage(
                proto.getTradeId(),
                proto.getSenderUserProfileId(),
                proto.getContractHash().toByteArray(),
                proto.getChatMessagesList().stream()
                        .map(MuSigOpenTradeMessage::fromProto)
                        .collect(Collectors.toList())
        );
    }

    public static ProtoResolver<ExternalNetworkMessage> getNetworkMessageResolver() {
        return any -> {
            try {
                bisq.support.protobuf.MuSigDisputeCaseDataMessage proto = any.unpack(bisq.support.protobuf.MuSigDisputeCaseDataMessage.class);
                return MuSigDisputeCaseDataMessage.fromProto(proto);
            } catch (InvalidProtocolBufferException e) {
                throw new UnresolvableProtobufMessageException(e);
            }
        };
    }

    @Override
    public double getCostFactor() {
        return getCostFactor(0.25, 0.5);
    }

    public byte[] getContractHash() {
        return contractHash.clone();
    }

    private List<MuSigOpenTradeMessage> maybePrune(List<MuSigOpenTradeMessage> chatMessages) {
        StringBuilder sb = new StringBuilder();
        List<MuSigOpenTradeMessage> result = chatMessages.stream()
                .filter(message -> {
                    sb.append(message.getTextOrNA());
                    return sb.length() < 10_000;
                })
                .collect(Collectors.toList());
        if (result.size() != chatMessages.size()) {
            log.warn("chatMessages pruned for trade {}: kept={}, dropped={}, maxTotalChars=10000",
                    tradeId,
                    result.size(),
                    chatMessages.size() - result.size());
        }
        return result;
    }
}
