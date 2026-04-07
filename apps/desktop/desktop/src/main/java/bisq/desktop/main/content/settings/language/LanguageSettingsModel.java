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

import bisq.common.locale.CountryRepository;
import bisq.desktop.common.view.Model;
import bisq.settings.SettingsService;

import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Getter
public class LanguageSettingsModel implements Model {

    public LanguageSettingsModel(SettingsService settingsService) {
        this.settingsService = settingsService;
        this.countryCodes.setAll(CountryRepository.getAllCountyCodes());
        this.selectedCountryCode = settingsService.getCountryCode().get();
        this.selectedCurrencyCode = settingsService.getCurrencyCode().get();
        this.selectedLanguageTag = settingsService.getLanguageTag().get();
    }
    private final SettingsService settingsService;

    @Setter
    private String selectedLanguageTag;

    @Setter
    private String selectedCountryCode;

    @Setter
    private String selectedCurrencyCode;

    private final StringProperty selectedLSupportedLanguageTag = new SimpleStringProperty();

    private final ObservableList<String> languageTags = FXCollections.observableArrayList();
    private final ObservableList<String> countryCodes = FXCollections.observableArrayList();
    private final ObservableList<String> currencyCodes = FXCollections.observableArrayList();

    private final ObservableList<String> supportedLanguageTags = FXCollections.observableArrayList();
    private final FilteredList<String> supportedLanguageTagsFilteredList = new FilteredList<>(supportedLanguageTags);
    private final ObservableList<String> selectedSupportedLanguageTags = FXCollections.observableArrayList();
}
