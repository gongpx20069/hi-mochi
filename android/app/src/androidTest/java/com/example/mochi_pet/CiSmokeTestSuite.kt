package com.example.mochi_pet

import com.example.mochi_pet.feature.home.ProviderProfilesSmokeTest
import com.example.mochi_pet.feature.home.RestApiSmokeTest
import com.example.mochi_pet.feature.home.TaskCenterSmokeTest
import com.example.mochi_pet.platform.browser.AgentBrowserSemanticSnapshotTest
import com.example.mochi_pet.platform.wake.WakeKeywordDeviceTest
import org.junit.runner.RunWith
import org.junit.runners.Suite

@RunWith(Suite::class)
@Suite.SuiteClasses(
    ProviderProfilesSmokeTest::class,
    RestApiSmokeTest::class,
    TaskCenterSmokeTest::class,
    AgentBrowserSemanticSnapshotTest::class,
    WakeKeywordDeviceTest::class,
)
class CiSmokeTestSuite
