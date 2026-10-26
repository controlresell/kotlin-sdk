package com.controlresell.sdk

internal actual fun environmentVariable(name: String): String? = System.getenv(name)
