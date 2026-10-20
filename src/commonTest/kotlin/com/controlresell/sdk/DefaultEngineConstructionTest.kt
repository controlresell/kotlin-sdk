package com.controlresell.sdk

import kotlin.test.Test

class DefaultEngineConstructionTest {

    @Test
    fun defaultClient_withoutEngine_isBuiltOnEveryTarget() {
        ControlResell(apiKey = "cr_test_key").close()
    }
}
