package com.truyencloud.shared.data

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class SupabaseAuth(
    supabaseUrl: String,
    supabaseAnonKey: String
) {
    val client: SupabaseClient = createSupabaseClient(
        supabaseUrl = supabaseUrl,
        supabaseKey = supabaseAnonKey
    ) {
        install(Auth)
        install(Realtime)
    }

    val currentUserId: String?
        get() = client.auth.currentSessionOrNull()?.user?.id

    val isAuthenticated: Boolean
        get() = client.auth.currentSessionOrNull() != null

    suspend fun signInWithEmail(emailStr: String, passwordStr: String) {
        client.auth.signInWith(Email) {
            email = emailStr
            password = passwordStr
        }
    }

    suspend fun signOut() {
        client.auth.signOut()
    }
}
