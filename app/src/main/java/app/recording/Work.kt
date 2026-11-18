package app.recording

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The app's worker threads. They live as long as the process, not an activity, so a job that
 * outlasts the screen (Make louder, a rename) still finishes and never lands on a stopped executor.
 */
object Work {
    /** Listing, renaming, deleting, Make louder and voice notes, one at a time in order. */
    val clips: ExecutorService = Executors.newSingleThreadExecutor { Thread(it, "clips") }

    /** Opening a take and recovering unfinished ones, so recovery never touches a take being opened. */
    val takes: ExecutorService = Executors.newSingleThreadExecutor { Thread(it, "takes") }
}
