package jp.rstlab.batteryrelay;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withContentDescription;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.hamcrest.Matchers.anyOf;

import android.Manifest;
import android.content.pm.ActivityInfo;
import android.os.Build;
import android.os.ParcelFileDescriptor;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;

/** User-visible smoke coverage for ONDOKEI/Battery Relay's primary functions. */
@RunWith(AndroidJUnit4.class)
@LargeTest
public final class OndokeiUiSmokeTest {
    @Before
    public void allowNotificationSoTheDashboardIsNotCoveredByTheSystemDialog()
            throws IOException {
        if (Build.VERSION.SDK_INT >= 33) {
            try (ParcelFileDescriptor ignored = InstrumentationRegistry.getInstrumentation()
                    .getUiAutomation().executeShellCommand(
                            "pm grant jp.rstlab.batteryrelay.debug "
                                    + Manifest.permission.POST_NOTIFICATIONS)) {
                // Closing waits for the short shell operation to finish.
            }
        }
    }

    @Test
    public void dashboardRefreshTurboHistoryAndRotationRemainUsable() {
        try (ActivityScenario<MainActivity> scenario =
                     ActivityScenario.launch(MainActivity.class)) {
            onView(withText("端末コンディション")).check(matches(isDisplayed()));
            onView(withText("BATTERY")).check(matches(isDisplayed()));
            onView(withText("TEMPERATURE")).check(matches(isDisplayed()));
            onView(withContentDescription("表示中の端末を今すぐ更新")).perform(click());
            onView(withContentDescription("Turboモードを切り替え")).perform(click());
            onView(anyOf(withText("Turbo・5秒"), withText("保護・60秒")))
                    .check(matches(isDisplayed()));
            onView(withText("バッテリー残量")).perform(scrollTo())
                    .check(matches(isDisplayed()));
            onView(withText("バッテリー温度")).perform(scrollTo())
                    .check(matches(isDisplayed()));
            onView(withText("保存上限は30分。古い測定値は端末内DBから自動で削除され、共有先にも送られません。"))
                    .perform(scrollTo()).check(matches(isDisplayed()));

            scenario.onActivity(activity -> activity.setRequestedOrientation(
                    ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE));
            onView(withText("端末コンディション")).perform(scrollTo())
                    .check(matches(isDisplayed()));
            scenario.onActivity(activity -> activity.setRequestedOrientation(
                    ActivityInfo.SCREEN_ORIENTATION_PORTRAIT));
            onView(withContentDescription("Turboモードを切り替え"))
                    .perform(scrollTo()).check(matches(isDisplayed()));
        }
    }

    @Test
    public void encryptedSharingEntryPointOpensAndCanBeStopped() {
        try (ActivityScenario<MainActivity> scenario =
                     ActivityScenario.launch(MainActivity.class)) {
            onView(withText("この端末の情報を共有")).perform(scrollTo(), click());
            onView(withText("この端末から共有")).check(matches(isDisplayed()));
            onView(withText("128ビット共有キー")).check(matches(isDisplayed()));
            onView(withText("共有を停止")).perform(click());
            onView(withText("端末コンディション")).perform(scrollTo())
                    .check(matches(isDisplayed()));
        }
    }
}
