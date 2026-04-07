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

package bisq.desktop.main.content.settings.language;

import bisq.bonded_roles.market_price.MarketPriceService;
import bisq.common.asset.FiatCurrencyRepository;
import bisq.common.locale.CountryRepository;
import bisq.common.locale.LanguageRepository;
import bisq.common.market.MarketRepository;
import bisq.common.observable.Pin;
import bisq.common.util.StringUtils;
import bisq.desktop.ServiceProvider;
import bisq.desktop.common.observable.FxBindings;
import bisq.desktop.common.view.Controller;
import bisq.desktop.components.overlay.Popup;
import bisq.i18n.Res;
import bisq.settings.SettingsService;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nullable;
import java.util.Currency;
import java.util.List;
import java.util.Locale;

@Slf4j
public class LanguageSettingsController implements Controller {
    @Getter
    private final LanguageSettingsView view;
    private final LanguageSettingsModel model;
    private final SettingsService settingsService;
    private final MarketPriceService marketPriceService;
    private Pin supportedLanguageTagsPin;

    public LanguageSettingsController(ServiceProvider serviceProvider) {
        this.settingsService = serviceProvider.getSettingsService();
        this.marketPriceService = serviceProvider.getBondedRolesService().getMarketPriceService();

        this.model = new LanguageSettingsModel(settingsService);

        model.getCountryCodes().sort((c1, c2) ->
                convertCountryCode(c1).compareToIgnoreCase(convertCountryCode(c2)));

        List<String> supportedCurrencies = MarketRepository.getAllFiatMarkets().stream()
                .map(market -> market.getQuoteCurrencyCode())
                .distinct()
                .sorted()
                .toList();
        model.getCurrencyCodes().setAll(supportedCurrencies);

        view = new LanguageSettingsView(model, this);
    }

    @Override
    public void onActivate() {
        model.getLanguageTags().setAll(getSortedLanguageTags());
        model.getSupportedLanguageTags().setAll(getSortedLanguageTags());

        supportedLanguageTagsPin = FxBindings.<String, String>bind(model.getSelectedSupportedLanguageTags())
                .to(settingsService.getSupportedLanguageTags());
        model.getSupportedLanguageTagsFilteredList().setPredicate(e -> !model.getSelectedSupportedLanguageTags().contains(e));
    }

    @Override
    public void onDeactivate() {
        supportedLanguageTagsPin.unbind();
    }

    public String getDisplayCountry(String countryCode) {
        return convertCountryCode(countryCode);
    }

    public String getDisplayLanguage(String languageTag) {
        return convertLanguageTag(languageTag);
    }

    public String getDisplayCurrency(@Nullable String currencyCode) {
        return convertCurrencyCode(currencyCode);
    }

    String convertCountryCode(String countryCode) {
        return CountryRepository.getLocalizedCountryDisplayString(countryCode);
    }

    String convertLanguageTag(String languageTag) {
        return LanguageRepository.getDisplayString(languageTag);
    }

    String convertCurrencyCode(@Nullable String currencyCode) {
        if (currencyCode == null) {
            return "";
        }
        return FiatCurrencyRepository.findDisplayName(currencyCode)
                .map(displayName -> displayName + " (" + currencyCode + ")")
                .orElse(currencyCode);
    }

    void onSelectCountryCode(@Nullable String countryCode) {
        if (countryCode == null) return;
        model.setSelectedCountryCode(countryCode);
        settingsService.setCountryCode(countryCode);
        try {
            Locale locale = new Locale.Builder().setRegion(countryCode).build();
            Currency defaultCurrency = Currency.getInstance(locale);
            if (defaultCurrency != null) {
                onSelectCurrencyCode(defaultCurrency.getCurrencyCode());
            }
        } catch (Exception e) {
            log.warn("Could not derive default currency for country: {}", countryCode);
        }
    }

    void onSelectCurrencyCode(@Nullable String currencyCode) {
        if (currencyCode == null) return;
        log.debug("User selected default currency: {}", currencyCode);
        MarketRepository.getAllFiatMarkets().stream()
                .filter(m -> m.getQuoteCurrencyCode().equalsIgnoreCase(currencyCode))
                .findFirst()
                .ifPresentOrElse(market -> {
                    model.setSelectedCurrencyCode(currencyCode);
                    settingsService.setCountryCode(model.getSelectedCountryCode());
                    settingsService.setCurrencyCode(currencyCode);
                    // R118: Sin Platform.runLater (Contexto de UI garantizado)
                    if (marketPriceService != null) {
                        marketPriceService.setSelectedMarket(market);
                    }
                }, () -> log.debug("Market not found for: {}", currencyCode));
    }

    void onSelectLanguageTag(@Nullable String languageTag) {
        if (languageTag == null) return;
        model.setSelectedLanguageTag(languageTag);
        settingsService.setLanguageTag(languageTag);
        new Popup().feedback(Res.get("settings.language.restart")).useShutDownButton().show();
    }

    void onSelectSupportedLanguage(@Nullable String languageTag) {
        if (languageTag != null) model.getSelectedLSupportedLanguageTag().set(languageTag);
    }

    void onAddSupportedLanguage() {
        String selected = model.getSelectedLSupportedLanguageTag().get();
        if (StringUtils.isNotEmpty(selected)) {
            settingsService.getSupportedLanguageTags().add(selected);
            model.getSelectedLSupportedLanguageTag().set(null);
        }
    }

    void onRemoveSupportedLanguage(@Nullable String languageTag) {
        if (StringUtils.isNotEmpty(languageTag)) settingsService.getSupportedLanguageTags().remove(languageTag);
    }


    private List<String> getSortedLanguageTags() {
        return LanguageRepository.LANGUAGE_TAGS.stream()
                .sorted((tag1, tag2) -> convertLanguageTag(tag1).compareTo(convertLanguageTag(tag2)))
                .toList();
    }
}
