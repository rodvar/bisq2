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
public final class BizumAccountPayload extends CountryBasedAccountPayload implements SingleCurrencyAccountPayload {
    private final String mobileNr;

    public BizumAccountPayload(String id, String countryCode, String mobileNr) {
        super(id, countryCode);
        this.mobileNr = mobileNr;
    }

    @Override
    protected bisq.account.protobuf.CountryBasedAccountPayload.Builder getCountryBasedAccountPayloadBuilder(boolean serializeForHash) {
        return super.getCountryBasedAccountPayloadBuilder(serializeForHash).setBizumAccountPayload(
                toBizumAccountPayloadProto(serializeForHash));
    }

    private bisq.account.protobuf.BizumAccountPayload toBizumAccountPayloadProto(boolean serializeForHash) {
        return resolveBuilder(getBizumAccountPayloadBuilder(serializeForHash), serializeForHash).build();
    }

    private bisq.account.protobuf.BizumAccountPayload.Builder getBizumAccountPayloadBuilder(boolean serializeForHash) {
        return bisq.account.protobuf.BizumAccountPayload.newBuilder().setMobileNr(mobileNr);
    }

    public static BizumAccountPayload fromProto(AccountPayload proto) {
        var countryBasedAccountPayload = proto.getCountryBasedAccountPayload();
        return new BizumAccountPayload(
                proto.getId(),
                countryBasedAccountPayload.getCountryCode(),
                countryBasedAccountPayload.getBizumAccountPayload().getMobileNr());
    }

    @Override
    public FiatPaymentMethod getPaymentMethod() {
        return FiatPaymentMethod.fromPaymentRail(FiatPaymentRail.BIZUM);
    }

    @Override
    public String getAccountDataDisplayString() {
        return new AccountDataDisplayStringBuilder(
                Res.get("user.paymentAccounts.mobileNr"), mobileNr
        ).toString();
    }
}
