package com.limelight.preferences;

import android.app.LocaleManager;
import android.content.Context;
import android.os.Build;
import android.os.LocaleList;
import android.preference.ListPreference;
import android.util.AttributeSet;

import java.util.Locale;

public class LanguagePreference extends ListPreference {
    public LanguagePreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    public LanguagePreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public LanguagePreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public LanguagePreference(Context context) {
        super(context);
    }

    @Override
    public String getValue() {
        // Android 13+ stores the active app language in LocaleManager. Reflect
        // that selection in the dialog instead of showing the migrated legacy
        // preference value ("default").
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                LocaleManager localeManager = getContext().getSystemService(LocaleManager.class);
                LocaleList appLocales = localeManager != null ? localeManager.getApplicationLocales() : null;
                if (appLocales != null && !appLocales.isEmpty()) {
                    Locale activeLocale = appLocales.get(0);
                    String activeTag = activeLocale.toLanguageTag();
                    CharSequence[] values = getEntryValues();

                    // Prefer an exact BCP-47 match (for example zh-TW).
                    if (values != null) {
                        for (CharSequence value : values) {
                            if (value != null && value.toString().equalsIgnoreCase(activeTag)) {
                                return value.toString();
                            }
                        }

                        // Some Android releases canonicalize legacy tags such
                        // as iw/in to he/id. Match the language as a fallback.
                        for (CharSequence value : values) {
                            if (value != null &&
                                    Locale.forLanguageTag(value.toString()).getLanguage()
                                            .equalsIgnoreCase(activeLocale.getLanguage())) {
                                return value.toString();
                            }
                        }
                    }
                }
            } catch (RuntimeException e) {
                e.printStackTrace();
            }
        }

        return super.getValue();
    }

    @Override
    protected void onDialogClosed(boolean positiveResult) {
        super.onDialogClosed(positiveResult);

        if (!positiveResult || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return;
        }

        // ListPreference has persisted the selected value by this point.
        String selectedLanguage = super.getValue();
        try {
            LocaleManager localeManager = getContext().getSystemService(LocaleManager.class);
            if (localeManager != null) {
                LocaleList selectedLocales = PreferenceConfiguration.DEFAULT_LANGUAGE.equals(selectedLanguage)
                        ? LocaleList.getEmptyLocaleList()
                        : LocaleList.forLanguageTags(selectedLanguage);
                localeManager.setApplicationLocales(selectedLocales);

                // Android 13+ now owns the locale selection. Clear the legacy
                // preference so startup migration does not reapply it.
                PreferenceConfiguration.completeLanguagePreferenceMigration(getContext());
            }
        } catch (RuntimeException e) {
            // Keep the saved legacy value so UiHelper can apply its fallback.
            e.printStackTrace();
        }
    }
}
