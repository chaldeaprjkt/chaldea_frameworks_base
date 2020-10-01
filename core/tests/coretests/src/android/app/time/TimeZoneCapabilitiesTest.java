/*
 * Copyright 2020 The Android Open Source Project
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

package android.app.time;

import static android.app.time.TimeZoneCapabilities.CAPABILITY_NOT_ALLOWED;
import static android.app.time.TimeZoneCapabilities.CAPABILITY_POSSESSED;
import static android.app.timezonedetector.ParcelableTestSupport.assertRoundTripParcelable;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;

import android.os.UserHandle;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class TimeZoneCapabilitiesTest {

    private static final UserHandle TEST_USER_HANDLE = UserHandle.of(12345);

    @Test
    public void testEquals() {
        TimeZoneCapabilities.Builder builder1 = new TimeZoneCapabilities.Builder(TEST_USER_HANDLE)
                .setConfigureAutoDetectionEnabledCapability(CAPABILITY_POSSESSED)
                .setConfigureGeoDetectionEnabledCapability(CAPABILITY_POSSESSED)
                .setSuggestManualTimeZoneCapability(CAPABILITY_POSSESSED);
        TimeZoneCapabilities.Builder builder2 = new TimeZoneCapabilities.Builder(TEST_USER_HANDLE)
                .setConfigureAutoDetectionEnabledCapability(CAPABILITY_POSSESSED)
                .setConfigureGeoDetectionEnabledCapability(CAPABILITY_POSSESSED)
                .setSuggestManualTimeZoneCapability(CAPABILITY_POSSESSED);
        {
            TimeZoneCapabilities one = builder1.build();
            TimeZoneCapabilities two = builder2.build();
            assertEquals(one, two);
        }

        builder2.setConfigureAutoDetectionEnabledCapability(CAPABILITY_NOT_ALLOWED);
        {
            TimeZoneCapabilities one = builder1.build();
            TimeZoneCapabilities two = builder2.build();
            assertNotEquals(one, two);
        }

        builder1.setConfigureAutoDetectionEnabledCapability(CAPABILITY_NOT_ALLOWED);
        {
            TimeZoneCapabilities one = builder1.build();
            TimeZoneCapabilities two = builder2.build();
            assertEquals(one, two);
        }

        builder2.setConfigureGeoDetectionEnabledCapability(CAPABILITY_NOT_ALLOWED);
        {
            TimeZoneCapabilities one = builder1.build();
            TimeZoneCapabilities two = builder2.build();
            assertNotEquals(one, two);
        }

        builder1.setConfigureGeoDetectionEnabledCapability(CAPABILITY_NOT_ALLOWED);
        {
            TimeZoneCapabilities one = builder1.build();
            TimeZoneCapabilities two = builder2.build();
            assertEquals(one, two);
        }

        builder2.setSuggestManualTimeZoneCapability(CAPABILITY_NOT_ALLOWED);
        {
            TimeZoneCapabilities one = builder1.build();
            TimeZoneCapabilities two = builder2.build();
            assertNotEquals(one, two);
        }

        builder1.setSuggestManualTimeZoneCapability(CAPABILITY_NOT_ALLOWED);
        {
            TimeZoneCapabilities one = builder1.build();
            TimeZoneCapabilities two = builder2.build();
            assertEquals(one, two);
        }
    }

    @Test
    public void testParcelable() {
        TimeZoneCapabilities.Builder builder = new TimeZoneCapabilities.Builder(TEST_USER_HANDLE)
                .setConfigureAutoDetectionEnabledCapability(CAPABILITY_POSSESSED)
                .setConfigureGeoDetectionEnabledCapability(CAPABILITY_POSSESSED)
                .setSuggestManualTimeZoneCapability(CAPABILITY_POSSESSED);
        assertRoundTripParcelable(builder.build());

        builder.setConfigureAutoDetectionEnabledCapability(CAPABILITY_NOT_ALLOWED);
        assertRoundTripParcelable(builder.build());

        builder.setConfigureGeoDetectionEnabledCapability(CAPABILITY_NOT_ALLOWED);
        assertRoundTripParcelable(builder.build());

        builder.setSuggestManualTimeZoneCapability(CAPABILITY_NOT_ALLOWED);
        assertRoundTripParcelable(builder.build());
    }

    @Test
    public void testTryApplyConfigChanges_permitted() {
        TimeZoneConfiguration oldConfiguration =
                new TimeZoneConfiguration.Builder()
                        .setAutoDetectionEnabled(true)
                        .setGeoDetectionEnabled(true)
                        .build();
        TimeZoneCapabilities capabilities = new TimeZoneCapabilities.Builder(TEST_USER_HANDLE)
                .setConfigureAutoDetectionEnabledCapability(CAPABILITY_POSSESSED)
                .setConfigureGeoDetectionEnabledCapability(CAPABILITY_POSSESSED)
                .setSuggestManualTimeZoneCapability(CAPABILITY_POSSESSED)
                .build();

        TimeZoneConfiguration configChange = new TimeZoneConfiguration.Builder()
                .setAutoDetectionEnabled(false)
                .build();

        TimeZoneConfiguration expected = new TimeZoneConfiguration.Builder(oldConfiguration)
                .setAutoDetectionEnabled(false)
                .build();
        assertEquals(expected, capabilities.tryApplyConfigChanges(oldConfiguration, configChange));
    }

    @Test
    public void testTryApplyConfigChanges_notPermitted() {
        TimeZoneConfiguration oldConfiguration =
                new TimeZoneConfiguration.Builder()
                        .setAutoDetectionEnabled(true)
                        .setGeoDetectionEnabled(true)
                        .build();
        TimeZoneCapabilities capabilities = new TimeZoneCapabilities.Builder(TEST_USER_HANDLE)
                .setConfigureAutoDetectionEnabledCapability(CAPABILITY_NOT_ALLOWED)
                .setConfigureGeoDetectionEnabledCapability(CAPABILITY_NOT_ALLOWED)
                .setSuggestManualTimeZoneCapability(CAPABILITY_NOT_ALLOWED)
                .build();

        TimeZoneConfiguration configChange = new TimeZoneConfiguration.Builder()
                .setAutoDetectionEnabled(false)
                .build();

        assertNull(capabilities.tryApplyConfigChanges(oldConfiguration, configChange));
    }
}
