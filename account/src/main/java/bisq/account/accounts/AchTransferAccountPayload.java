package bisq.account.accounts;

import bisq.account.accounts.util.AccountDataDisplayStringBuilder;
import bisq.account.payment_method.FiatPaymentMethod;
import bisq.account.payment_method.FiatPaymentRail;
import bisq.common.validation.NetworkDataValidation;
import bisq.i18n.Res;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;

@Slf4j
@ToString
@Getter
@EqualsAndHashCode(callSuper = true)
public final class AchTransferAccountPayload extends BankAccountPayload {
    public static final int HOLDER_ADDRESS_MIN_LENGTH = 5;
    public static final int HOLDER_ADDRESS_MAX_LENGTH = 150;

    private final String holderAddress;

    public AchTransferAccountPayload(String id,
                                     String holderName,
                                     String holderAddress,
                                     String bankName,
                                     String routingNr,
                                     String accountNr,
                                     BankAccountType bankAccountType) {
        super(id,
                "US",
                "USD",
                Optional.of(holderName),
                Optional.empty(),
                Optional.of(bankName),
                Optional.of(routingNr),
                Optional.empty(),
                accountNr,
                Optional.of(bankAccountType),
                Optional.empty());
        this.holderAddress = holderAddress;

        verify();
    }

    @Override
    public void verify() {
        super.verify();

        NetworkDataValidation.validateRequiredText(holderAddress, HOLDER_ADDRESS_MIN_LENGTH, HOLDER_ADDRESS_MAX_LENGTH);
    }

    @Override
    protected bisq.account.protobuf.BankAccountPayload.Builder getBankAccountPayloadBuilder(boolean serializeForHash) {
        return super.getBankAccountPayloadBuilder(serializeForHash).setAchTransferAccountPayload(
                toAchTransferAccountPayloadProto(serializeForHash));
    }

    private bisq.account.protobuf.AchTransferAccountPayload toAchTransferAccountPayloadProto(boolean serializeForHash) {
        return resolveBuilder(getAchTransferAccountPayloadBuilder(serializeForHash), serializeForHash).build();
    }

    private bisq.account.protobuf.AchTransferAccountPayload.Builder getAchTransferAccountPayloadBuilder(boolean serializeForHash) {
        return bisq.account.protobuf.AchTransferAccountPayload.newBuilder()
                .setHolderAddress(holderAddress);
    }

    public static AchTransferAccountPayload fromProto(bisq.account.protobuf.AccountPayload proto) {
        var countryBasedPaymentAccountPayload = proto.getCountryBasedAccountPayload();
        var bankAccountPayload = countryBasedPaymentAccountPayload.getBankAccountPayload();
        var achAccountPayload = bankAccountPayload.getAchTransferAccountPayload();
        checkArgument(bankAccountPayload.hasBankName(), "Bank name for ACH must be present");
        checkArgument(bankAccountPayload.hasBankId(), "BankId (Routing number) for ACH must be present");
        checkArgument(bankAccountPayload.hasBankAccountType(), "AccountType for ACH must be present");
        return new AchTransferAccountPayload(proto.getId(),
                bankAccountPayload.getHolderName(),
                achAccountPayload.getHolderAddress(),
                bankAccountPayload.getBankName(),
                bankAccountPayload.getBankId(),
                bankAccountPayload.getAccountNr(),
                BankAccountType.fromProto(bankAccountPayload.getBankAccountType()));
    }

    @Override
    public FiatPaymentMethod getPaymentMethod() {
        return FiatPaymentMethod.fromPaymentRail(FiatPaymentRail.ACH_TRANSFER);
    }

    @Override
    public String getAccountDataDisplayString() {
        AccountDataDisplayStringBuilder builder = new AccountDataDisplayStringBuilder();
        holderName.ifPresent(value -> builder.add(Res.get("user.paymentAccounts.holderName"), value));
        holderId.ifPresent(value -> builder.add(BankAccountUtils.getHolderIdDescription(countryCode), value));
        builder.add(Res.get("user.paymentAccounts.holderAddress"), holderAddress);
        bankName.ifPresent(value -> builder.add(Res.get("user.paymentAccounts.bank.bankName"), value));
        bankId.ifPresent(value -> builder.add(BankAccountUtils.getBankIdDescription(countryCode), value));
        branchId.ifPresent(value -> builder.add(BankAccountUtils.getBranchIdDescription(countryCode), value));
        builder.add(BankAccountUtils.getAccountNrDescription(countryCode), accountNr);
        bankAccountType.ifPresent(value -> builder.add(Res.get("user.paymentAccounts.bank.bankAccountType"),
                Res.get("user.paymentAccounts.bank.bankAccountType." + value.name())));
        nationalAccountId.ifPresent(value -> builder.add(BankAccountUtils.getNationalAccountIdDescription(countryCode), value));
        return builder.toString();
    }
}