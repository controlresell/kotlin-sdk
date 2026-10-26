package com.controlresell.sdk

/** Node.js has `process.env`; a browser has no environment, so the API key is given to the client. */
private fun processEnv(name: String): String? =
    js("(typeof process !== 'undefined' && process.env && typeof process.env[name] === 'string') ? process.env[name] : null")

internal actual fun environmentVariable(name: String): String? = processEnv(name)
