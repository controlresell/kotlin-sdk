package com.controlresell.sdk

/** The value of the environment variable [name], `null` when it is not set. */
internal expect fun environmentVariable(name: String): String?
