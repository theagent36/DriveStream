package com.example.drivestream

import android.util.Log
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ValueEventListener
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.flow.MutableSharedFlow

/** Payload written to Firebase by the Host on every meaningful playback event. */
data class JamState(
    val fileId: String = "",
    val isPlaying: Boolean = false,
    val currentPosition: Long = 0L,
    val timestamp: Long = 0L
)

/**
 * Singleton that owns the Firebase Realtime Database reference for
 * a Jam Session. Only one session (host or guest) is active at a time.
 */
object JamSessionManager {

    private val db = if (BuildConfig.FIREBASE_DATABASE_URL.isNotEmpty()) {
        FirebaseDatabase.getInstance(BuildConfig.FIREBASE_DATABASE_URL)
    } else {
        FirebaseDatabase.getInstance()
    }
    private var activeRoomId: String? = null
    private var guestListener: ValueEventListener? = null

    // The ViewModel observes this flow to receive sync commands from Firebase
    val incomingState = MutableSharedFlow<JamState>(replay = 1, extraBufferCapacity = 8)

    // ── HOST ────────────────────────────────────────────────────────────────

    /** Write the current playback state to Firebase. Called from ViewModel's Player.Listener. */
    fun broadcastState(roomId: String, state: JamState) {
        db.getReference("sessions").child(roomId)
            .setValue(state)
            .addOnFailureListener { e ->
                Log.w("JamSession", "broadcastState failed", e)
            }
    }

    /** Write the initial active session state explicitly when 'Host Session' is tapped. */
    fun initRoom(roomId: String, onFail: (String) -> Unit) {
        val initState = JamState(fileId = "init")
        db.getReference("sessions").child(roomId)
            .setValue(initState)
            .addOnSuccessListener {
                Log.d("JamSession", "Host room $roomId initialized successfully on Firebase.")
            }
            .addOnFailureListener { e ->
                Log.w("JamSession", "initRoom failed: ${e.message}", e)
                onFail(e.message ?: "Unknown Firebase Database error")
            }
    }

    // ── GUEST ───────────────────────────────────────────────────────────────

    /** Attach a Firebase listener. Updates are emitted on [incomingState]. */
    fun joinRoom(roomId: String) {
        detachListeners()
        activeRoomId = roomId
        val ref = db.getReference("sessions").child(roomId)
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                try {
                    val state = snapshot.getValue(JamState::class.java) ?: return
                    incomingState.tryEmit(state)
                } catch (e: Exception) {
                    Log.w("JamSession", "onDataChange parse error", e)
                }
            }
            override fun onCancelled(error: DatabaseError) {
                Log.w("JamSession", "Firebase listener cancelled: ${error.message}")
            }
        }
        ref.addValueEventListener(listener)
        guestListener = listener
        Log.d("JamSession", "Guest attached to room: $roomId")
    }

    // ── CLEANUP ─────────────────────────────────────────────────────────────

    fun detachListeners() {
        val roomId = activeRoomId ?: return
        guestListener?.let { listener ->
            db.getReference("sessions").child(roomId).removeEventListener(listener)
            guestListener = null
            Log.d("JamSession", "Guest detached from room: $roomId")
        }
        activeRoomId = null
    }
}
