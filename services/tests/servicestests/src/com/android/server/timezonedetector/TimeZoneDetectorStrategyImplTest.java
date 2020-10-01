/*
 * Copyright 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.timezonedetector;

import static android.app.timezonedetector.TelephonyTimeZoneSuggestion.MATCH_TYPE_EMULATOR_ZONE_ID;
import static android.app.timezonedetector.TelephonyTimeZoneSuggestion.MATCH_TYPE_NETWORK_COUNTRY_AND_OFFSET;
import static android.app.timezonedetector.TelephonyTimeZoneSuggestion.MATCH_TYPE_NETWORK_COUNTRY_ONLY;
import static android.app.timezonedetector.TelephonyTimeZoneSuggestion.MATCH_TYPE_TEST_NETWORK_OFFSET_ONLY;
import static android.app.timezonedetector.TelephonyTimeZoneSuggestion.QUALITY_MULTIPLE_ZONES_WITH_DIFFERENT_OFFSETS;
import static android.app.timezonedetector.TelephonyTimeZoneSuggestion.QUALITY_MULTIPLE_ZONES_WITH_SAME_OFFSET;
import static android.app.timezonedetector.TelephonyTimeZoneSuggestion.QUALITY_SINGLE_ZONE;

import static com.android.server.timezonedetector.TimeZoneDetectorStrategyImpl.TELEPHONY_SCORE_HIGH;
import static com.android.server.timezonedetector.TimeZoneDetectorStrategyImpl.TELEPHONY_SCORE_HIGHEST;
import static com.android.server.timezonedetector.TimeZoneDetectorStrategyImpl.TELEPHONY_SCORE_LOW;
import static com.android.server.timezonedetector.TimeZoneDetectorStrategyImpl.TELEPHONY_SCORE_MEDIUM;
import static com.android.server.timezonedetector.TimeZoneDetectorStrategyImpl.TELEPHONY_SCORE_NONE;
import static com.android.server.timezonedetector.TimeZoneDetectorStrategyImpl.TELEPHONY_SCORE_USAGE_THRESHOLD;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.time.TimeZoneConfiguration;
import android.app.timezonedetector.ManualTimeZoneSuggestion;
import android.app.timezonedetector.TelephonyTimeZoneSuggestion;
import android.app.timezonedetector.TelephonyTimeZoneSuggestion.MatchType;
import android.app.timezonedetector.TelephonyTimeZoneSuggestion.Quality;
import android.util.IndentingPrintWriter;

import com.android.server.timezonedetector.TimeZoneDetectorStrategyImpl.QualifiedTelephonyTimeZoneSuggestion;

import org.junit.Before;
import org.junit.Test;

import java.io.StringWriter;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * White-box unit tests for {@link TimeZoneDetectorStrategyImpl}.
 */
public class TimeZoneDetectorStrategyImplTest {

    /** A time zone used for initialization that does not occur elsewhere in tests. */
    private static final @UserIdInt int USER_ID = 9876;
    private static final String ARBITRARY_TIME_ZONE_ID = "Etc/UTC";
    private static final int SLOT_INDEX1 = 10000;
    private static final int SLOT_INDEX2 = 20000;

    // Telephony test cases are ordered so that each successive one is of the same or higher score
    // than the previous.
    private static final TelephonyTestCase[] TELEPHONY_TEST_CASES = new TelephonyTestCase[] {
            newTelephonyTestCase(MATCH_TYPE_NETWORK_COUNTRY_ONLY,
                    QUALITY_MULTIPLE_ZONES_WITH_DIFFERENT_OFFSETS, TELEPHONY_SCORE_LOW),
            newTelephonyTestCase(MATCH_TYPE_NETWORK_COUNTRY_ONLY,
                    QUALITY_MULTIPLE_ZONES_WITH_SAME_OFFSET, TELEPHONY_SCORE_MEDIUM),
            newTelephonyTestCase(MATCH_TYPE_NETWORK_COUNTRY_AND_OFFSET,
                    QUALITY_MULTIPLE_ZONES_WITH_SAME_OFFSET, TELEPHONY_SCORE_MEDIUM),
            newTelephonyTestCase(MATCH_TYPE_NETWORK_COUNTRY_ONLY, QUALITY_SINGLE_ZONE,
                    TELEPHONY_SCORE_HIGH),
            newTelephonyTestCase(MATCH_TYPE_NETWORK_COUNTRY_AND_OFFSET, QUALITY_SINGLE_ZONE,
                    TELEPHONY_SCORE_HIGH),
            newTelephonyTestCase(MATCH_TYPE_TEST_NETWORK_OFFSET_ONLY,
                    QUALITY_MULTIPLE_ZONES_WITH_SAME_OFFSET, TELEPHONY_SCORE_HIGHEST),
            newTelephonyTestCase(MATCH_TYPE_EMULATOR_ZONE_ID, QUALITY_SINGLE_ZONE,
                    TELEPHONY_SCORE_HIGHEST),
    };

    private static final ConfigurationInternal CONFIG_INT_USER_RESTRICTED_AUTO_DISABLED =
            new ConfigurationInternal.Builder(USER_ID)
                    .setUserConfigAllowed(false)
                    .setAutoDetectionSupported(true)
                    .setAutoDetectionEnabled(false)
                    .setLocationEnabled(true)
                    .setGeoDetectionEnabled(false)
                    .build();

    private static final ConfigurationInternal CONFIG_INT_USER_RESTRICTED_AUTO_ENABLED =
            new ConfigurationInternal.Builder(USER_ID)
                    .setUserConfigAllowed(false)
                    .setAutoDetectionSupported(true)
                    .setAutoDetectionEnabled(true)
                    .setLocationEnabled(true)
                    .setGeoDetectionEnabled(true)
                    .build();

    private static final ConfigurationInternal CONFIG_INT_AUTO_DETECT_NOT_SUPPORTED =
            new ConfigurationInternal.Builder(USER_ID)
                    .setUserConfigAllowed(true)
                    .setAutoDetectionSupported(false)
                    .setAutoDetectionEnabled(false)
                    .setLocationEnabled(true)
                    .setGeoDetectionEnabled(false)
                    .build();

    private static final ConfigurationInternal CONFIG_INT_AUTO_DISABLED_GEO_DISABLED =
            new ConfigurationInternal.Builder(USER_ID)
                    .setUserConfigAllowed(true)
                    .setAutoDetectionSupported(true)
                    .setAutoDetectionEnabled(false)
                    .setLocationEnabled(true)
                    .setGeoDetectionEnabled(false)
                    .build();

    private static final ConfigurationInternal CONFIG_INT_AUTO_DISABLED_GEO_ENABLED =
            new ConfigurationInternal.Builder(USER_ID)
                    .setUserConfigAllowed(true)
                    .setAutoDetectionSupported(true)
                    .setAutoDetectionEnabled(false)
                    .setLocationEnabled(true)
                    .setGeoDetectionEnabled(true)
                    .build();

    private static final ConfigurationInternal CONFIG_INT_AUTO_ENABLED_GEO_DISABLED =
            new ConfigurationInternal.Builder(USER_ID)
                    .setAutoDetectionSupported(true)
                    .setUserConfigAllowed(true)
                    .setAutoDetectionEnabled(true)
                    .setLocationEnabled(true)
                    .setGeoDetectionEnabled(false)
                    .build();

    private static final ConfigurationInternal CONFIG_INT_AUTO_ENABLED_GEO_ENABLED =
            new ConfigurationInternal.Builder(USER_ID)
                    .setAutoDetectionSupported(true)
                    .setUserConfigAllowed(true)
                    .setAutoDetectionEnabled(true)
                    .setLocationEnabled(true)
                    .setGeoDetectionEnabled(true)
                    .build();

    private static final TimeZoneConfiguration CONFIG_AUTO_DISABLED =
            createConfig(false /* autoDetection */, null);
    private static final TimeZoneConfiguration CONFIG_AUTO_ENABLED =
            createConfig(true /* autoDetection */, null);
    private static final TimeZoneConfiguration CONFIG_GEO_DETECTION_ENABLED =
            createConfig(null, true /* geoDetection */);
    private static final TimeZoneConfiguration CONFIG_GEO_DETECTION_DISABLED =
            createConfig(null, false /* geoDetection */);

    private TimeZoneDetectorStrategyImpl mTimeZoneDetectorStrategy;
    private FakeCallback mFakeCallback;
    private MockConfigChangeListener mMockConfigChangeListener;


    @Before
    public void setUp() {
        mFakeCallback = new FakeCallback();
        mMockConfigChangeListener = new MockConfigChangeListener();
        mTimeZoneDetectorStrategy = new TimeZoneDetectorStrategyImpl(mFakeCallback);
        mTimeZoneDetectorStrategy.addConfigChangeListener(mMockConfigChangeListener);
    }

    @Test
    public void testGetCurrentUserConfiguration() {
        new Script().initializeConfig(CONFIG_INT_AUTO_ENABLED_GEO_DISABLED);
        ConfigurationInternal expectedConfiguration =
                mFakeCallback.getConfigurationInternal(USER_ID);
        assertEquals(expectedConfiguration,
                mTimeZoneDetectorStrategy.getCurrentUserConfigurationInternal());
    }

    @Test
    public void testUpdateConfiguration_unrestricted() {
        Script script = new Script().initializeConfig(CONFIG_INT_AUTO_ENABLED_GEO_DISABLED);

        // Set the configuration with auto detection enabled.
        script.simulateUpdateConfiguration(USER_ID, CONFIG_AUTO_ENABLED, true /* expectedResult */);

        // Nothing should have happened: it was initialized in this state.
        script.verifyConfigurationNotChanged();

        // Update the configuration with auto detection disabled.
        script.simulateUpdateConfiguration(
                USER_ID, CONFIG_AUTO_DISABLED, true /* expectedResult */);

        // The settings should have been changed and the StrategyListener onChange() called.
        script.verifyConfigurationChangedAndReset(CONFIG_INT_AUTO_DISABLED_GEO_DISABLED);

        // Update the configuration with auto detection enabled.
        script.simulateUpdateConfiguration(USER_ID, CONFIG_AUTO_ENABLED, true /* expectedResult */);

        // The settings should have been changed and the StrategyListener onChange() called.
        script.verifyConfigurationChangedAndReset(CONFIG_INT_AUTO_ENABLED_GEO_DISABLED);

        // Update the configuration to enable geolocation time zone detection.
        script.simulateUpdateConfiguration(
                USER_ID, CONFIG_GEO_DETECTION_ENABLED,  true /* expectedResult */);

        // The settings should have been changed and the StrategyListener onChange() called.
        script.verifyConfigurationChangedAndReset(CONFIG_INT_AUTO_ENABLED_GEO_ENABLED);
    }

    @Test
    public void testUpdateConfiguration_restricted() {
        Script script = new Script().initializeConfig(CONFIG_INT_USER_RESTRICTED_AUTO_ENABLED);

        // Try to update the configuration with auto detection disabled.
        script.simulateUpdateConfiguration(
                USER_ID, CONFIG_AUTO_DISABLED, false /* expectedResult */);

        // The settings should not have been changed: user shouldn't have the capabilities.
        script.verifyConfigurationNotChanged();

        // Update the configuration with auto detection enabled.
        script.simulateUpdateConfiguration(
                USER_ID, CONFIG_AUTO_ENABLED,  false /* expectedResult */);

        // The settings should not have been changed: user shouldn't have the capabilities.
        script.verifyConfigurationNotChanged();

        // Try to  update the configuration to enable geolocation time zone detection.
        script.simulateUpdateConfiguration(
                USER_ID, CONFIG_GEO_DETECTION_ENABLED,  false /* expectedResult */);

        // The settings should not have been changed: user shouldn't have the capabilities.
        script.verifyConfigurationNotChanged();
    }

    @Test
    public void testUpdateConfiguration_autoDetectNotSupported() {
        Script script = new Script().initializeConfig(CONFIG_INT_AUTO_DETECT_NOT_SUPPORTED);

        // Try to update the configuration with auto detection disabled.
        script.simulateUpdateConfiguration(
                USER_ID, CONFIG_AUTO_DISABLED, false /* expectedResult */);

        // The settings should not have been changed: user shouldn't have the capabilities.
        script.verifyConfigurationNotChanged();

        // Update the configuration with auto detection enabled.
        script.simulateUpdateConfiguration(
                USER_ID, CONFIG_AUTO_ENABLED, false /* expectedResult */);

        // The settings should not have been changed: user shouldn't have the capabilities.
        script.verifyConfigurationNotChanged();
    }

    @Test
    public void testEmptyTelephonySuggestions() {
        TelephonyTimeZoneSuggestion slotIndex1TimeZoneSuggestion =
                createEmptySlotIndex1Suggestion();
        TelephonyTimeZoneSuggestion slotIndex2TimeZoneSuggestion =
                createEmptySlotIndex2Suggestion();
        Script script = new Script()
                .initializeConfig(CONFIG_INT_AUTO_ENABLED_GEO_DISABLED)
                .initializeTimeZoneSetting(ARBITRARY_TIME_ZONE_ID);

        script.simulateTelephonyTimeZoneSuggestion(slotIndex1TimeZoneSuggestion)
                .verifyTimeZoneNotChanged();

        // Assert internal service state.
        QualifiedTelephonyTimeZoneSuggestion expectedSlotIndex1ScoredSuggestion =
                new QualifiedTelephonyTimeZoneSuggestion(slotIndex1TimeZoneSuggestion,
                        TELEPHONY_SCORE_NONE);
        assertEquals(expectedSlotIndex1ScoredSuggestion,
                mTimeZoneDetectorStrategy.getLatestTelephonySuggestion(SLOT_INDEX1));
        assertNull(mTimeZoneDetectorStrategy.getLatestTelephonySuggestion(SLOT_INDEX2));
        assertEquals(expectedSlotIndex1ScoredSuggestion,
                mTimeZoneDetectorStrategy.findBestTelephonySuggestionForTests());

        script.simulateTelephonyTimeZoneSuggestion(slotIndex2TimeZoneSuggestion)
                .verifyTimeZoneNotChanged();

        // Assert internal service state.
        QualifiedTelephonyTimeZoneSuggestion expectedSlotIndex2ScoredSuggestion =
                new QualifiedTelephonyTimeZoneSuggestion(slotIndex2TimeZoneSuggestion,
                        TELEPHONY_SCORE_NONE);
        assertEquals(expectedSlotIndex1ScoredSuggestion,
                mTimeZoneDetectorStrategy.getLatestTelephonySuggestion(SLOT_INDEX1));
        assertEquals(expectedSlotIndex2ScoredSuggestion,
                mTimeZoneDetectorStrategy.getLatestTelephonySuggestion(SLOT_INDEX2));
        // SlotIndex1 should always beat slotIndex2, all other things being equal.
        assertEquals(expectedSlotIndex1ScoredSuggestion,
                mTimeZoneDetectorStrategy.findBestTelephonySuggestionForTests());
    }

    /**
     * Telephony suggestions have quality metadata. Ordinarily, low scoring suggestions are not
     * used, but this is not true if the device's time zone setting is uninitialized.
     */
    @Test
    public void testTelephonySuggestionsWhenTimeZoneUninitialized() {
        assertTrue(TELEPHONY_SCORE_LOW < TELEPHONY_SCORE_USAGE_THRESHOLD);
        assertTrue(TELEPHONY_SCORE_HIGH >= TELEPHONY_SCORE_USAGE_THRESHOLD);
        TelephonyTestCase testCase = newTelephonyTestCase(MATCH_TYPE_NETWORK_COUNTRY_ONLY,
                QUALITY_MULTIPLE_ZONES_WITH_DIFFERENT_OFFSETS, TELEPHONY_SCORE_LOW);
        TelephonyTestCase testCase2 = newTelephonyTestCase(MATCH_TYPE_NETWORK_COUNTRY_ONLY,
                QUALITY_SINGLE_ZONE, TELEPHONY_SCORE_HIGH);

        Script script = new Script().initializeConfig(CONFIG_INT_AUTO_ENABLED_GEO_DISABLED);

        // A low quality suggestions will not be taken: The device time zone setting is left
        // uninitialized.
        {
            TelephonyTimeZoneSuggestion lowQualitySuggestion =
                    testCase.createSuggestion(SLOT_INDEX1, "America/New_York");
            script.simulateTelephonyTimeZoneSuggestion(lowQualitySuggestion)
                    .verifyTimeZoneNotChanged();

            // Assert internal service state.
            QualifiedTelephonyTimeZoneSuggestion expectedScoredSuggestion =
                    new QualifiedTelephonyTimeZoneSuggestion(
                            lowQualitySuggestion, testCase.expectedScore);
            assertEquals(expectedScoredSuggestion,
                    mTimeZoneDetectorStrategy.getLatestTelephonySuggestion(SLOT_INDEX1));
            assertEquals(expectedScoredSuggestion,
                    mTimeZoneDetectorStrategy.findBestTelephonySuggestionForTests());
        }

        // A good quality suggestion will be used.
        {
            TelephonyTimeZoneSuggestion goodQualitySuggestion =
                    testCase2.createSuggestion(SLOT_INDEX1, "Europe/London");
            script.simulateTelephonyTimeZoneSuggestion(goodQualitySuggestion)
                    .verifyTimeZoneChangedAndReset(goodQualitySuggestion);

            // Assert internal service state.
            QualifiedTelephonyTimeZoneSuggestion expectedScoredSuggestion =
                    new QualifiedTelephonyTimeZoneSuggestion(
                            goodQualitySuggestion, testCase2.expectedScore);
            assertEquals(expectedScoredSuggestion,
                    mTimeZoneDetectorStrategy.getLatestTelephonySuggestion(SLOT_INDEX1));
            assertEquals(expectedScoredSuggestion,
                    mTimeZoneDetectorStrategy.findBestTelephonySuggestionForTests());
        }

        // A low quality suggestion will be accepted, but not used to set the device time zone.
        {
            TelephonyTimeZoneSuggestion lowQualitySuggestion2 =
                    testCase.createSuggestion(SLOT_INDEX1, "America/Los_Angeles");
            script.simulateTelephonyTimeZoneSuggestion(lowQualitySuggestion2)
                    .verifyTimeZoneNotChanged();

            // Assert internal service state.
            QualifiedTelephonyTimeZoneSuggestion expectedScoredSuggestion =
                    new QualifiedTelephonyTimeZoneSuggestion(
                            lowQualitySuggestion2, testCase.expectedScore);
            assertEquals(expectedScoredSuggestion,
                    mTimeZoneDetectorStrategy.getLatestTelephonySuggestion(SLOT_INDEX1));
            assertEquals(expectedScoredSuggestion,
                    mTimeZoneDetectorStrategy.findBestTelephonySuggestionForTests());
        }
    }

    /**
     * Confirms that toggling the auto time zone detection setting has the expected behavior when
     * the strategy is "opinionated" when using telephony auto detection.
     */
    @Test
    public void testTogglingAutoDetection_autoTelephony() {
        Script script = new Script();

        for (TelephonyTestCase testCase : TELEPHONY_TEST_CASES) {
            // Start with the device in a known state.
            script.initializeConfig(CONFIG_INT_AUTO_DISABLED_GEO_DISABLED)
                    .initializeTimeZoneSetting(ARBITRARY_TIME_ZONE_ID);

            TelephonyTimeZoneSuggestion suggestion =
                    testCase.createSuggestion(SLOT_INDEX1, "Europe/London");
            script.simulateTelephonyTimeZoneSuggestion(suggestion);

            // When time zone detection is not enabled, the time zone suggestion will not be set
            // regardless of the score.
            script.verifyTimeZoneNotChanged();

            // Assert internal service state.
            QualifiedTelephonyTimeZoneSuggestion expectedScoredSuggestion =
                    new QualifiedTelephonyTimeZoneSuggestion(suggestion, testCase.expectedScore);
            assertEquals(expectedScoredSuggestion,
                    mTimeZoneDetectorStrategy.getLatestTelephonySuggestion(SLOT_INDEX1));
            assertEquals(expectedScoredSuggestion,
                    mTimeZoneDetectorStrategy.findBestTelephonySuggestionForTests());

            // Toggling the time zone setting on should cause the device setting to be set.
            script.simulateUpdateConfiguration(
                    USER_ID, CONFIG_AUTO_ENABLED, true /* expectedResult */);

            // When time zone detection is already enabled the suggestion (if it scores highly
            // enough) should be set immediately.
            if (testCase.expectedScore >= TELEPHONY_SCORE_USAGE_THRESHOLD) {
                script.verifyTimeZoneChangedAndReset(suggestion);
            } else {
                script.verifyTimeZoneNotChanged();
            }

            // Assert internal service state.
            assertEquals(expectedScoredSuggestion,
                    mTimeZoneDetectorStrategy.getLatestTelephonySuggestion(SLOT_INDEX1));
            assertEquals(expectedScoredSuggestion,
                    mTimeZoneDetectorStrategy.findBestTelephonySuggestionForTests());

            // Toggling the time zone setting should off should do nothing.
            script.simulateUpdateConfiguration(
                    USER_ID, CONFIG_AUTO_DISABLED, true /* expectedResult */)
                    .verifyTimeZoneNotChanged();

            // Assert internal service state.
            assertEquals(expectedScoredSuggestion,
                    mTimeZoneDetectorStrategy.getLatestTelephonySuggestion(SLOT_INDEX1));
            assertEquals(expectedScoredSuggestion,
                    mTimeZoneDetectorStrategy.findBestTelephonySuggestionForTests());
        }
    }

    @Test
    public void testTelephonySuggestionsSingleSlotId() {
        Script script = new Script()
                .initializeConfig(CONFIG_INT_AUTO_ENABLED_GEO_DISABLED)
                .initializeTimeZoneSetting(ARBITRARY_TIME_ZONE_ID);

        for (TelephonyTestCase testCase : TELEPHONY_TEST_CASES) {
            makeSlotIndex1SuggestionAndCheckState(script, testCase);
        }

        /*
         * This is the same test as above but the test cases are in
         * reverse order of their expected score. New suggestions always replace previous ones:
         * there's effectively no history and so ordering shouldn't make any difference.
         */

        // Each test case will have the same or lower score than the last.
        List<TelephonyTestCase> descendingCasesByScore = list(TELEPHONY_TEST_CASES);
        Collections.reverse(descendingCasesByScore);

        for (TelephonyTestCase testCase : descendingCasesByScore) {
            makeSlotIndex1SuggestionAndCheckState(script, testCase);
        }
    }

    private void makeSlotIndex1SuggestionAndCheckState(Script script, TelephonyTestCase testCase) {
        // Give the next suggestion a different zone from the currently set device time zone;
        String currentZoneId = mFakeCallback.getDeviceTimeZone();
        String suggestionZoneId =
                "Europe/London".equals(currentZoneId) ? "Europe/Paris" : "Europe/London";
        TelephonyTimeZoneSuggestion zoneSlotIndex1Suggestion =
                testCase.createSuggestion(SLOT_INDEX1, suggestionZoneId);
        QualifiedTelephonyTimeZoneSuggestion expectedZoneSlotIndex1ScoredSuggestion =
                new QualifiedTelephonyTimeZoneSuggestion(
                        zoneSlotIndex1Suggestion, testCase.expectedScore);

        script.simulateTelephonyTimeZoneSuggestion(zoneSlotIndex1Suggestion);
        if (testCase.expectedScore >= TELEPHONY_SCORE_USAGE_THRESHOLD) {
            script.verifyTimeZoneChangedAndReset(zoneSlotIndex1Suggestion);
        } else {
            script.verifyTimeZoneNotChanged();
        }

        // Assert internal service state.
        assertEquals(expectedZoneSlotIndex1ScoredSuggestion,
                mTimeZoneDetectorStrategy.getLatestTelephonySuggestion(SLOT_INDEX1));
        assertEquals(expectedZoneSlotIndex1ScoredSuggestion,
                mTimeZoneDetectorStrategy.findBestTelephonySuggestionForTests());
    }

    /**
     * Tries a set of test cases to see if the slotIndex with the lowest numeric value is given
     * preference. This test also confirms that the time zone setting would only be set if a
     * suggestion is of sufficient quality.
     */
    @Test
    public void testTelephonySuggestionMultipleSlotIndexSuggestionScoringAndSlotIndexBias() {
        String[] zoneIds = { "Europe/London", "Europe/Paris" };
        TelephonyTimeZoneSuggestion emptySlotIndex1Suggestion = createEmptySlotIndex1Suggestion();
        TelephonyTimeZoneSuggestion emptySlotIndex2Suggestion = createEmptySlotIndex2Suggestion();
        QualifiedTelephonyTimeZoneSuggestion expectedEmptySlotIndex1ScoredSuggestion =
                new QualifiedTelephonyTimeZoneSuggestion(emptySlotIndex1Suggestion,
                        TELEPHONY_SCORE_NONE);
        QualifiedTelephonyTimeZoneSuggestion expectedEmptySlotIndex2ScoredSuggestion =
                new QualifiedTelephonyTimeZoneSuggestion(emptySlotIndex2Suggestion,
                        TELEPHONY_SCORE_NONE);

        Script script = new Script()
                .initializeConfig(CONFIG_INT_AUTO_ENABLED_GEO_DISABLED)
                .initializeTimeZoneSetting(ARBITRARY_TIME_ZONE_ID)
                // Initialize the latest suggestions as empty so we don't need to worry about nulls
                // below for the first loop.
                .simulateTelephonyTimeZoneSuggestion(emptySlotIndex1Suggestion)
                .simulateTelephonyTimeZoneSuggestion(emptySlotIndex2Suggestion)
                .resetConfigurationTracking();

        for (TelephonyTestCase testCase : TELEPHONY_TEST_CASES) {
            TelephonyTimeZoneSuggestion zoneSlotIndex1Suggestion =
                    testCase.createSuggestion(SLOT_INDEX1, zoneIds[0]);
            TelephonyTimeZoneSuggestion zoneSlotIndex2Suggestion =
                    testCase.createSuggestion(SLOT_INDEX2, zoneIds[1]);
            QualifiedTelephonyTimeZoneSuggestion expectedZoneSlotIndex1ScoredSuggestion =
                    new QualifiedTelephonyTimeZoneSuggestion(zoneSlotIndex1Suggestion,
                            testCase.expectedScore);
            QualifiedTelephonyTimeZoneSuggestion expectedZoneSlotIndex2ScoredSuggestion =
                    new QualifiedTelephonyTimeZoneSuggestion(zoneSlotIndex2Suggestion,
                            testCase.expectedScore);

            // Start the test by making a suggestion for slotIndex1.
            script.simulateTelephonyTimeZoneSuggestion(zoneSlotIndex1Suggestion);
            if (testCase.expectedScore >= TELEPHONY_SCORE_USAGE_THRESHOLD) {
                script.verifyTimeZoneChangedAndReset(zoneSlotIndex1Suggestion);
            } else {
                script.verifyTimeZoneNotChanged();
            }

            // Assert internal service state.
            assertEquals(expectedZoneSlotIndex1ScoredSuggestion,
                    mTimeZoneDetectorStrategy.getLatestTelephonySuggestion(SLOT_INDEX1));
            assertEquals(expectedEmptySlotIndex2ScoredSuggestion,
                    mTimeZoneDetectorStrategy.getLatestTelephonySuggestion(SLOT_INDEX2));
            assertEquals(expectedZoneSlotIndex1ScoredSuggestion,
                    mTimeZoneDetectorStrategy.findBestTelephonySuggestionForTests());

            // SlotIndex2 then makes an alternative suggestion with an identical score. SlotIndex1's
            // suggestion should still "win" if it is above the required threshold.
            script.simulateTelephonyTimeZoneSuggestion(zoneSlotIndex2Suggestion);
            script.verifyTimeZoneNotChanged();

            // Assert internal service state.
            assertEquals(expectedZoneSlotIndex1ScoredSuggestion,
                    mTimeZoneDetectorStrategy.getLatestTelephonySuggestion(SLOT_INDEX1));
            assertEquals(expectedZoneSlotIndex2ScoredSuggestion,
                    mTimeZoneDetectorStrategy.getLatestTelephonySuggestion(SLOT_INDEX2));
            // SlotIndex1 should always beat slotIndex2, all other things being equal.
            assertEquals(expectedZoneSlotIndex1ScoredSuggestion,
                    mTimeZoneDetectorStrategy.findBestTelephonySuggestionForTests());

            // Withdrawing slotIndex1's suggestion should leave slotIndex2 as the new winner. Since
            // the zoneId is different, the time zone setting should be updated if the score is high
            // enough.
            script.simulateTelephonyTimeZoneSuggestion(emptySlotIndex1Suggestion);
            if (testCase.expectedScore >= TELEPHONY_SCORE_USAGE_THRESHOLD) {
                script.verifyTimeZoneChangedAndReset(zoneSlotIndex2Suggestion);
            } else {
                script.verifyTimeZoneNotChanged();
            }

            // Assert internal service state.
            assertEquals(expectedEmptySlotIndex1ScoredSuggestion,
                    mTimeZoneDetectorStrategy.getLatestTelephonySuggestion(SLOT_INDEX1));
            assertEquals(expectedZoneSlotIndex2ScoredSuggestion,
                    mTimeZoneDetectorStrategy.getLatestTelephonySuggestion(SLOT_INDEX2));
            assertEquals(expectedZoneSlotIndex2ScoredSuggestion,
                    mTimeZoneDetectorStrategy.findBestTelephonySuggestionForTests());

            // Reset the state for the next loop.
            script.simulateTelephonyTimeZoneSuggestion(emptySlotIndex2Suggestion)
                    .verifyTimeZoneNotChanged();
            assertEquals(expectedEmptySlotIndex1ScoredSuggestion,
                    mTimeZoneDetectorStrategy.getLatestTelephonySuggestion(SLOT_INDEX1));
            assertEquals(expectedEmptySlotIndex2ScoredSuggestion,
                    mTimeZoneDetectorStrategy.getLatestTelephonySuggestion(SLOT_INDEX2));
        }
    }

    /**
     * The {@link TimeZoneDetectorStrategyImpl.Callback} is left to detect whether changing the time
     * zone is actually necessary. This test proves that the strategy doesn't assume it knows the
     * current settings.
     */
    @Test
    public void testTelephonySuggestionStrategyDoesNotAssumeCurrentSetting_autoTelephony() {
        Script script = new Script().initializeConfig(CONFIG_INT_AUTO_ENABLED_GEO_DISABLED);

        TelephonyTestCase testCase = newTelephonyTestCase(
                MATCH_TYPE_NETWORK_COUNTRY_AND_OFFSET, QUALITY_SINGLE_ZONE, TELEPHONY_SCORE_HIGH);
        TelephonyTimeZoneSuggestion losAngelesSuggestion =
                testCase.createSuggestion(SLOT_INDEX1, "America/Los_Angeles");
        TelephonyTimeZoneSuggestion newYorkSuggestion =
                testCase.createSuggestion(SLOT_INDEX1, "America/New_York");

        // Initialization.
        script.simulateTelephonyTimeZoneSuggestion(losAngelesSuggestion)
                .verifyTimeZoneChangedAndReset(losAngelesSuggestion);
        // Suggest it again - it should not be set because it is already set.
        script.simulateTelephonyTimeZoneSuggestion(losAngelesSuggestion)
                .verifyTimeZoneNotChanged();

        // Toggling time zone detection should set the device time zone only if the current setting
        // value is different from the most recent telephony suggestion.
        script.simulateUpdateConfiguration(USER_ID, CONFIG_AUTO_DISABLED, true /* expectedResult */)
                .verifyTimeZoneNotChanged()
                .simulateUpdateConfiguration(
                        USER_ID, CONFIG_AUTO_ENABLED, true /* expectedResult */)
                .verifyTimeZoneNotChanged();

        // Simulate a user turning auto detection off, a new suggestion being made while auto
        // detection is off, and the user turning it on again.
        script.simulateUpdateConfiguration(USER_ID, CONFIG_AUTO_DISABLED, true /* expectedResult */)
                .simulateTelephonyTimeZoneSuggestion(newYorkSuggestion)
                .verifyTimeZoneNotChanged();
        // Latest suggestion should be used.
        script.simulateUpdateConfiguration(
                USER_ID, CONFIG_AUTO_ENABLED, true /* expectedResult */)
                .verifyTimeZoneChangedAndReset(newYorkSuggestion);
    }

    @Test
    public void testManualSuggestion_unrestricted_autoDetectionEnabled_autoTelephony() {
        checkManualSuggestion_unrestricted_autoDetectionEnabled(false /* geoDetectionEnabled */);
    }

    @Test
    public void testManualSuggestion_unrestricted_autoDetectionEnabled_autoGeo() {
        checkManualSuggestion_unrestricted_autoDetectionEnabled(true /* geoDetectionEnabled */);
    }

    private void checkManualSuggestion_unrestricted_autoDetectionEnabled(
            boolean geoDetectionEnabled) {
        ConfigurationInternal geoTzEnabledConfig =
                new ConfigurationInternal.Builder(CONFIG_INT_AUTO_ENABLED_GEO_DISABLED)
                        .setGeoDetectionEnabled(geoDetectionEnabled)
                        .build();
        Script script = new Script()
                .initializeConfig(geoTzEnabledConfig)
                .initializeTimeZoneSetting(ARBITRARY_TIME_ZONE_ID);

        // Auto time zone detection is enabled so the manual suggestion should be ignored.
        script.simulateManualTimeZoneSuggestion(
                USER_ID, createManualSuggestion("Europe/Paris"), false /* expectedResult */)
                .verifyTimeZoneNotChanged();
    }

    @Test
    public void testManualSuggestion_restricted_simulateAutoTimeZoneEnabled() {
        Script script = new Script()
                .initializeConfig(CONFIG_INT_USER_RESTRICTED_AUTO_ENABLED)
                .initializeTimeZoneSetting(ARBITRARY_TIME_ZONE_ID);

        // User is restricted so the manual suggestion should be ignored.
        script.simulateManualTimeZoneSuggestion(
                USER_ID, createManualSuggestion("Europe/Paris"), false /* expectedResult */)
                .verifyTimeZoneNotChanged();
    }

    @Test
    public void testManualSuggestion_unrestricted_autoTimeZoneDetectionDisabled() {
        Script script = new Script()
                .initializeConfig(CONFIG_INT_AUTO_DISABLED_GEO_DISABLED)
                .initializeTimeZoneSetting(ARBITRARY_TIME_ZONE_ID);

        // Auto time zone detection is disabled so the manual suggestion should be used.
        ManualTimeZoneSuggestion manualSuggestion = createManualSuggestion("Europe/Paris");
        script.simulateManualTimeZoneSuggestion(
                USER_ID, manualSuggestion, true /* expectedResult */)
            .verifyTimeZoneChangedAndReset(manualSuggestion);
    }

    @Test
    public void testManualSuggestion_restricted_autoTimeZoneDetectionDisabled() {
        Script script = new Script()
                .initializeConfig(CONFIG_INT_USER_RESTRICTED_AUTO_DISABLED)
                .initializeTimeZoneSetting(ARBITRARY_TIME_ZONE_ID);

        // Restricted users do not have the capability.
        ManualTimeZoneSuggestion manualSuggestion = createManualSuggestion("Europe/Paris");
        script.simulateManualTimeZoneSuggestion(
                USER_ID, manualSuggestion, false /* expectedResult */)
                .verifyTimeZoneNotChanged();
    }

    @Test
    public void testManualSuggestion_autoDetectNotSupported() {
        Script script = new Script()
                .initializeConfig(CONFIG_INT_AUTO_DETECT_NOT_SUPPORTED)
                .initializeTimeZoneSetting(ARBITRARY_TIME_ZONE_ID);

        // Unrestricted users have the capability.
        ManualTimeZoneSuggestion manualSuggestion = createManualSuggestion("Europe/Paris");
        script.simulateManualTimeZoneSuggestion(
                USER_ID, manualSuggestion, true /* expectedResult */)
                .verifyTimeZoneChangedAndReset(manualSuggestion);
    }

    @Test
    public void testGeoSuggestion_uncertain() {
        Script script = new Script().initializeConfig(CONFIG_INT_AUTO_ENABLED_GEO_ENABLED)
                .initializeTimeZoneSetting(ARBITRARY_TIME_ZONE_ID);

        GeolocationTimeZoneSuggestion uncertainSuggestion = createUncertainGeoLocationSuggestion();

        script.simulateGeolocationTimeZoneSuggestion(uncertainSuggestion)
                .verifyTimeZoneNotChanged();

        // Assert internal service state.
        assertEquals(uncertainSuggestion,
                mTimeZoneDetectorStrategy.getLatestGeolocationSuggestion());
    }

    @Test
    public void testGeoSuggestion_noZones() {
        Script script = new Script()
                .initializeConfig(CONFIG_INT_AUTO_ENABLED_GEO_ENABLED)
                .initializeTimeZoneSetting(ARBITRARY_TIME_ZONE_ID);

        GeolocationTimeZoneSuggestion noZonesSuggestion = createGeoLocationSuggestion(list());

        script.simulateGeolocationTimeZoneSuggestion(noZonesSuggestion)
                .verifyTimeZoneNotChanged();

        // Assert internal service state.
        assertEquals(noZonesSuggestion, mTimeZoneDetectorStrategy.getLatestGeolocationSuggestion());
    }

    @Test
    public void testGeoSuggestion_oneZone() {
        GeolocationTimeZoneSuggestion suggestion =
                createGeoLocationSuggestion(list("Europe/London"));

        Script script = new Script()
                .initializeConfig(CONFIG_INT_AUTO_ENABLED_GEO_ENABLED)
                .initializeTimeZoneSetting(ARBITRARY_TIME_ZONE_ID);

        script.simulateGeolocationTimeZoneSuggestion(suggestion)
                .verifyTimeZoneChangedAndReset("Europe/London");

        // Assert internal service state.
        assertEquals(suggestion, mTimeZoneDetectorStrategy.getLatestGeolocationSuggestion());
    }

    /**
     * In the current implementation, the first zone ID is always used unless the device is set to
     * one of the other options. This is "stickiness" - the device favors the zone it is currently
     * set to until that unambiguously can't be correct.
     */
    @Test
    public void testGeoSuggestion_multiZone() {
        GeolocationTimeZoneSuggestion londonOnlySuggestion =
                createGeoLocationSuggestion(list("Europe/London"));
        GeolocationTimeZoneSuggestion londonOrParisSuggestion =
                createGeoLocationSuggestion(list("Europe/Paris", "Europe/London"));
        GeolocationTimeZoneSuggestion parisOnlySuggestion =
                createGeoLocationSuggestion(list("Europe/Paris"));

        Script script = new Script()
                .initializeConfig(CONFIG_INT_AUTO_ENABLED_GEO_ENABLED)
                .initializeTimeZoneSetting(ARBITRARY_TIME_ZONE_ID);

        script.simulateGeolocationTimeZoneSuggestion(londonOnlySuggestion)
                .verifyTimeZoneChangedAndReset("Europe/London");
        assertEquals(londonOnlySuggestion,
                mTimeZoneDetectorStrategy.getLatestGeolocationSuggestion());

        // Confirm bias towards the current device zone when there's multiple zones to choose from.
        script.simulateGeolocationTimeZoneSuggestion(londonOrParisSuggestion)
                .verifyTimeZoneNotChanged();
        assertEquals(londonOrParisSuggestion,
                mTimeZoneDetectorStrategy.getLatestGeolocationSuggestion());

        script.simulateGeolocationTimeZoneSuggestion(parisOnlySuggestion)
                .verifyTimeZoneChangedAndReset("Europe/Paris");
        assertEquals(parisOnlySuggestion,
                mTimeZoneDetectorStrategy.getLatestGeolocationSuggestion());

        // Now the suggestion that previously left the device on Europe/London will leave the device
        // on Europe/Paris.
        script.simulateGeolocationTimeZoneSuggestion(londonOrParisSuggestion)
                .verifyTimeZoneNotChanged();
        assertEquals(londonOrParisSuggestion,
                mTimeZoneDetectorStrategy.getLatestGeolocationSuggestion());
    }

    @Test
    public void testGeoSuggestion_togglingGeoDetectionClearsLastSuggestion() {
        GeolocationTimeZoneSuggestion suggestion =
                createGeoLocationSuggestion(list("Europe/London"));

        Script script = new Script()
                .initializeConfig(CONFIG_INT_AUTO_ENABLED_GEO_ENABLED)
                .initializeTimeZoneSetting(ARBITRARY_TIME_ZONE_ID);

        script.simulateGeolocationTimeZoneSuggestion(suggestion)
                .verifyTimeZoneChangedAndReset("Europe/London");

        // Assert internal service state.
        assertEquals(suggestion, mTimeZoneDetectorStrategy.getLatestGeolocationSuggestion());

        // Turn off geo detection and verify the latest suggestion is cleared.
        script.simulateUpdateConfiguration(USER_ID, CONFIG_GEO_DETECTION_DISABLED, true)
                .verifyConfigurationChangedAndReset(CONFIG_INT_AUTO_ENABLED_GEO_DISABLED);

        // Assert internal service state.
        assertNull(mTimeZoneDetectorStrategy.getLatestGeolocationSuggestion());
    }

    /**
     * Confirms that changing the geolocation time zone detection enabled setting has the expected
     * behavior, i.e. immediately recompute the detected time zone using different signals.
     */
    @Test
    public void testChangingGeoDetectionEnabled() {
        GeolocationTimeZoneSuggestion geolocationSuggestion =
                createGeoLocationSuggestion(list("Europe/London"));
        TelephonyTimeZoneSuggestion telephonySuggestion = createTelephonySuggestion(
                SLOT_INDEX1, MATCH_TYPE_NETWORK_COUNTRY_AND_OFFSET, QUALITY_SINGLE_ZONE,
                "Europe/Paris");

        Script script = new Script()
                .initializeConfig(CONFIG_INT_AUTO_DISABLED_GEO_DISABLED)
                .initializeTimeZoneSetting(ARBITRARY_TIME_ZONE_ID);

        // Add suggestions. Nothing should happen as time zone detection is disabled.
        script.simulateGeolocationTimeZoneSuggestion(geolocationSuggestion)
                .verifyTimeZoneNotChanged();

        // Geolocation suggestions are only stored when geolocation detection is enabled.
        assertNull(mTimeZoneDetectorStrategy.getLatestGeolocationSuggestion());

        script.simulateTelephonyTimeZoneSuggestion(telephonySuggestion)
                .verifyTimeZoneNotChanged();

        // Telephony suggestions are always stored.
        assertEquals(telephonySuggestion,
                mTimeZoneDetectorStrategy.getLatestTelephonySuggestion(SLOT_INDEX1).suggestion);

        // Toggling the time zone detection enabled setting on should cause the device setting to be
        // set from the telephony signal, as we've started with geolocation time zone detection
        // disabled.
        script.simulateUpdateConfiguration(USER_ID, CONFIG_AUTO_ENABLED, true /* expectedResult */)
                .verifyTimeZoneChangedAndReset(telephonySuggestion);

        // Changing the detection to enable geo detection won't cause the device tz setting to
        // change because the geo suggestion is empty.
        script.simulateUpdateConfiguration(
                USER_ID, CONFIG_GEO_DETECTION_ENABLED, true /* expectedResult */)
                .verifyTimeZoneNotChanged()
                .simulateGeolocationTimeZoneSuggestion(geolocationSuggestion)
                .verifyTimeZoneChangedAndReset(geolocationSuggestion.getZoneIds().get(0));

        // Changing the detection to disable geo detection should cause the device tz setting to
        // change to the telephony suggestion.
        script.simulateUpdateConfiguration(
                USER_ID, CONFIG_GEO_DETECTION_DISABLED, true /* expectedResult */)
                .verifyTimeZoneChangedAndReset(telephonySuggestion);

        assertNull(mTimeZoneDetectorStrategy.getLatestGeolocationSuggestion());
    }

    @Test
    public void testAddDumpable() {
        new Script()
                .initializeConfig(CONFIG_INT_AUTO_DISABLED_GEO_DISABLED)
                .initializeTimeZoneSetting(ARBITRARY_TIME_ZONE_ID);

        AtomicBoolean dumpCalled = new AtomicBoolean(false);
        class FakeDumpable implements Dumpable {
            @Override
            public void dump(IndentingPrintWriter pw, String[] args) {
                dumpCalled.set(true);
            }
        }

        mTimeZoneDetectorStrategy.addDumpable(new FakeDumpable());
        IndentingPrintWriter ipw = new IndentingPrintWriter(new StringWriter());
        String[] args = {"ArgOne", "ArgTwo"};
        mTimeZoneDetectorStrategy.dump(ipw, args);

        assertTrue(dumpCalled.get());
    }

    private static ManualTimeZoneSuggestion createManualSuggestion(String zoneId) {
        return new ManualTimeZoneSuggestion(zoneId);
    }

    private static TelephonyTimeZoneSuggestion createTelephonySuggestion(
            int slotIndex, @MatchType int matchType, @Quality int quality, String zoneId) {
        return new TelephonyTimeZoneSuggestion.Builder(slotIndex)
                .setMatchType(matchType)
                .setQuality(quality)
                .setZoneId(zoneId)
                .build();
    }

    private static TelephonyTimeZoneSuggestion createEmptySlotIndex1Suggestion() {
        return new TelephonyTimeZoneSuggestion.Builder(SLOT_INDEX1).build();
    }

    private static TelephonyTimeZoneSuggestion createEmptySlotIndex2Suggestion() {
        return new TelephonyTimeZoneSuggestion.Builder(SLOT_INDEX2).build();
    }

    private static GeolocationTimeZoneSuggestion createUncertainGeoLocationSuggestion() {
        return createGeoLocationSuggestion(null);
    }

    private static GeolocationTimeZoneSuggestion createGeoLocationSuggestion(
            @Nullable List<String> zoneIds) {
        GeolocationTimeZoneSuggestion suggestion = new GeolocationTimeZoneSuggestion(zoneIds);
        suggestion.addDebugInfo("Test suggestion");
        return suggestion;
    }

    private static TimeZoneConfiguration createConfig(
            @Nullable Boolean autoDetection, @Nullable Boolean geoDetection) {
        TimeZoneConfiguration.Builder builder = new TimeZoneConfiguration.Builder();
        if (autoDetection != null) {
            builder.setAutoDetectionEnabled(autoDetection);
        }
        if (geoDetection != null) {
            builder.setGeoDetectionEnabled(geoDetection);
        }
        return builder.build();
    }

    static class FakeCallback implements TimeZoneDetectorStrategyImpl.Callback {

        private final TestState<ConfigurationInternal> mConfigurationInternal = new TestState<>();
        private final TestState<String> mTimeZoneId = new TestState<>();
        private ConfigurationChangeListener mConfigChangeListener;

        void initializeConfig(ConfigurationInternal configurationInternal) {
            mConfigurationInternal.init(configurationInternal);
        }

        void initializeTimeZoneSetting(String zoneId) {
            mTimeZoneId.init(zoneId);
        }

        @Override
        public void setConfigChangeListener(ConfigurationChangeListener listener) {
            mConfigChangeListener = listener;
        }

        @Override
        public ConfigurationInternal getConfigurationInternal(int userId) {
            ConfigurationInternal configuration = mConfigurationInternal.getLatest();
            if (userId != configuration.getUserId()) {
                fail("FakeCallback does not support multiple users.");
            }
            return configuration;
        }

        @Override
        public int getCurrentUserId() {
            return mConfigurationInternal.getLatest().getUserId();
        }

        @Override
        public boolean isDeviceTimeZoneInitialized() {
            return mTimeZoneId.getLatest() != null;
        }

        @Override
        public String getDeviceTimeZone() {
            return mTimeZoneId.getLatest();
        }

        @Override
        public void setDeviceTimeZone(String zoneId) {
            mTimeZoneId.set(zoneId);
        }

        @Override
        public void storeConfiguration(
                @UserIdInt int userId, TimeZoneConfiguration newConfiguration) {
            ConfigurationInternal oldConfiguration = mConfigurationInternal.getLatest();
            if (userId != oldConfiguration.getUserId()) {
                fail("FakeCallback does not support multiple users");
            }

            ConfigurationInternal mergedConfiguration = oldConfiguration.merge(newConfiguration);
            if (!mergedConfiguration.equals(oldConfiguration)) {
                mConfigurationInternal.set(mergedConfiguration);

                // Note: Unlike the real callback impl, the listener is invoked synchronously.
                mConfigChangeListener.onChange();
            }
        }

        void assertKnownUser(int userId) {
            assertEquals("FakeCallback does not support multiple users",
                    mConfigurationInternal.getLatest().getUserId(), userId);
        }

        void assertTimeZoneNotChanged() {
            mTimeZoneId.assertHasNotBeenSet();
        }

        void assertTimeZoneChangedTo(String timeZoneId) {
            mTimeZoneId.assertHasBeenSet();
            mTimeZoneId.assertChangeCount(1);
            mTimeZoneId.assertLatestEquals(timeZoneId);
        }

        void commitAllChanges() {
            mTimeZoneId.commitLatest();
            mConfigurationInternal.commitLatest();
        }
    }

    /**
     * A "fluent" class allows reuse of code in tests: initialization, simulation and verification
     * logic.
     */
    private class Script {

        Script initializeConfig(ConfigurationInternal configuration) {
            mFakeCallback.initializeConfig(configuration);
            return this;
        }

        Script initializeTimeZoneSetting(String zoneId) {
            mFakeCallback.initializeTimeZoneSetting(zoneId);
            return this;
        }

        /**
         * Simulates the time zone detection strategy receiving an updated configuration and checks
         * the return value.
         */
        Script simulateUpdateConfiguration(
                int userId, TimeZoneConfiguration configuration, boolean expectedResult) {
            assertEquals(expectedResult,
                    mTimeZoneDetectorStrategy.updateConfiguration(userId, configuration));
            return this;
        }

        /**
         * Simulates the time zone detection strategy receiving a geolocation-originated
         * suggestion.
         */
        Script simulateGeolocationTimeZoneSuggestion(GeolocationTimeZoneSuggestion suggestion) {
            mTimeZoneDetectorStrategy.suggestGeolocationTimeZone(suggestion);
            return this;
        }

        /** Simulates the time zone detection strategy receiving a user-originated suggestion. */
        Script simulateManualTimeZoneSuggestion(
                @UserIdInt int userId, ManualTimeZoneSuggestion manualTimeZoneSuggestion,
                boolean expectedResult) {
            mFakeCallback.assertKnownUser(userId);
            boolean actualResult = mTimeZoneDetectorStrategy.suggestManualTimeZone(
                    userId, manualTimeZoneSuggestion);
            assertEquals(expectedResult, actualResult);
            return this;
        }

        /**
         * Simulates the time zone detection strategy receiving a telephony-originated suggestion.
         */
        Script simulateTelephonyTimeZoneSuggestion(TelephonyTimeZoneSuggestion timeZoneSuggestion) {
            mTimeZoneDetectorStrategy.suggestTelephonyTimeZone(timeZoneSuggestion);
            return this;
        }

        /**
         * Confirms that the device's time zone has not been set by previous actions since the test
         * state was last reset.
         */
        Script verifyTimeZoneNotChanged() {
            mFakeCallback.assertTimeZoneNotChanged();
            return this;
        }

        /** Verifies the device's time zone has been set and clears change tracking history. */
        Script verifyTimeZoneChangedAndReset(String zoneId) {
            mFakeCallback.assertTimeZoneChangedTo(zoneId);
            mFakeCallback.commitAllChanges();
            return this;
        }

        Script verifyTimeZoneChangedAndReset(ManualTimeZoneSuggestion suggestion) {
            mFakeCallback.assertTimeZoneChangedTo(suggestion.getZoneId());
            mFakeCallback.commitAllChanges();
            return this;
        }

        Script verifyTimeZoneChangedAndReset(TelephonyTimeZoneSuggestion suggestion) {
            mFakeCallback.assertTimeZoneChangedTo(suggestion.getZoneId());
            mFakeCallback.commitAllChanges();
            return this;
        }

        /**
         * Verifies that the configuration has been changed to the expected value.
         */
        Script verifyConfigurationChangedAndReset(ConfigurationInternal expected) {
            mFakeCallback.mConfigurationInternal.assertHasBeenSet();
            assertEquals(expected, mFakeCallback.mConfigurationInternal.getLatest());
            mFakeCallback.commitAllChanges();

            // Also confirm the listener triggered.
            mMockConfigChangeListener.verifyOnChangeCalled();
            mMockConfigChangeListener.reset();
            return this;
        }

        /**
         * Verifies that no underlying settings associated with the properties from the
         * {@link TimeZoneConfiguration} have been changed.
         */
        Script verifyConfigurationNotChanged() {
            mFakeCallback.mConfigurationInternal.assertHasNotBeenSet();

            // Also confirm the listener did not trigger.
            mMockConfigChangeListener.verifyOnChangeNotCalled();
            return this;
        }

        Script resetConfigurationTracking() {
            mFakeCallback.commitAllChanges();
            return this;
        }
    }

    private static class TelephonyTestCase {
        public final int matchType;
        public final int quality;
        public final int expectedScore;

        TelephonyTestCase(int matchType, int quality, int expectedScore) {
            this.matchType = matchType;
            this.quality = quality;
            this.expectedScore = expectedScore;
        }

        private TelephonyTimeZoneSuggestion createSuggestion(int slotIndex, String zoneId) {
            return new TelephonyTimeZoneSuggestion.Builder(slotIndex)
                    .setZoneId(zoneId)
                    .setMatchType(matchType)
                    .setQuality(quality)
                    .build();
        }
    }

    private static TelephonyTestCase newTelephonyTestCase(
            @MatchType int matchType, @Quality int quality, int expectedScore) {
        return new TelephonyTestCase(matchType, quality, expectedScore);
    }

    private static class MockConfigChangeListener implements ConfigurationChangeListener {
        private boolean mOnChangeCalled;

        @Override
        public void onChange() {
            mOnChangeCalled = true;
        }

        void verifyOnChangeCalled() {
            assertTrue(mOnChangeCalled);
        }

        void verifyOnChangeNotCalled() {
            assertFalse(mOnChangeCalled);
        }

        void reset() {
            mOnChangeCalled = false;
        }
    }

    private static <T> List<T> list(T... values) {
        return Arrays.asList(values);
    }
}
