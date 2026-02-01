package com.alive.player.network

class AuthInterceptor(
    private val tokenProvider: () -> String?,
) {
    // TODO: implement OkHttp interceptor adding Authorization header.
}
