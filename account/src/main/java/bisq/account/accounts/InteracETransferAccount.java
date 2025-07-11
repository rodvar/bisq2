package bisq.account.accounts;

import bisq.account.payment_method.FiatPaymentMethod;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;

@Getter
@Slf4j
@ToString
@EqualsAndHashCode(callSuper = true)
public final class InteracETransferAccount extends Account<FiatPaymentMethod, InteracETransferAccountPayload> {
    public InteracETransferAccount(String id,
                                   long creationDate,
                                   String accountName,
                                   InteracETransferAccountPayload accountPayload) {
        super(id, creationDate, accountName, accountPayload);
    }

    @Override
    public bisq.account.protobuf.Account.Builder getBuilder(boolean serializeForHash) {
        return getAccountBuilder(serializeForHash)
                .setInteracETransferAccount(toInteracETransferAccountProto(serializeForHash));
    }

    private bisq.account.protobuf.InteracETransferAccount toInteracETransferAccountProto(boolean serializeForHash) {
        return resolveBuilder(getInteracETransferAccountBuilder(serializeForHash), serializeForHash).build();
    }

    private bisq.account.protobuf.InteracETransferAccount.Builder getInteracETransferAccountBuilder(boolean serializeForHash) {
        return bisq.account.protobuf.InteracETransferAccount.newBuilder();
    }

    public static InteracETransferAccount fromProto(bisq.account.protobuf.Account proto) {
        return new InteracETransferAccount(proto.getId(),
                proto.getCreationDate(),
                proto.getAccountName(),
                InteracETransferAccountPayload.fromProto(proto.getAccountPayload())
        );
    }
}
