package bisq.account.accounts;

import bisq.account.accounts.util.AccountDataDisplayStringBuilder;
import bisq.account.payment_method.FiatPaymentMethod;
import bisq.account.payment_method.FiatPaymentRail;
import bisq.account.protobuf.AccountPayload;
import bisq.i18n.Res;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;

@Getter
@Slf4j
@ToString
@EqualsAndHashCode(callSuper = true)
public final class StrikeAccountPayload extends CountryBasedAccountPayload implements SingleCurrencyAccountPayload {
    private final String holderName;

    public StrikeAccountPayload(String id, String countryCode, String holderName) {
        super(id, countryCode);
        this.holderName = holderName;
    }

    @Override
    protected bisq.account.protobuf.CountryBasedAccountPayload.Builder getCountryBasedAccountPayloadBuilder(boolean serializeForHash) {
        return super.getCountryBasedAccountPayloadBuilder(serializeForHash).setStrikeAccountPayload(
                toStrikeAccountPayloadProto(serializeForHash));
    }

    private bisq.account.protobuf.StrikeAccountPayload toStrikeAccountPayloadProto(boolean serializeForHash) {
        return resolveBuilder(getStrikeAccountPayloadBuilder(serializeForHash), serializeForHash).build();
    }

    private bisq.account.protobuf.StrikeAccountPayload.Builder getStrikeAccountPayloadBuilder(boolean serializeForHash) {
        return bisq.account.protobuf.StrikeAccountPayload.newBuilder().setHolderName(holderName);
    }

    public static StrikeAccountPayload fromProto(AccountPayload proto) {
        return new StrikeAccountPayload(
                proto.getId(),
                proto.getCountryBasedAccountPayload().getCountryCode(),
                proto.getCountryBasedAccountPayload().getStrikeAccountPayload().getHolderName());
    }

    @Override
    public FiatPaymentMethod getPaymentMethod() {
        return FiatPaymentMethod.fromPaymentRail(FiatPaymentRail.STRIKE);
    }

    @Override
    public String getAccountDataDisplayString() {
        return new AccountDataDisplayStringBuilder(
                Res.get("user.paymentAccounts.holderName"), holderName
        ).toString();
    }
}
