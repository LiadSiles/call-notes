package com.callnotes.util

import com.callnotes.capture.CallTrace
import java.io.File

/** Files that belong to one call's recording. */
object RecordingFiles {
    /** Deletes the recording (plain, compressed or sealed) and its diagnostic trace. */
    fun delete(path: String) {
        val f = File(path)
        f.delete()
        CallTrace.fileFor(f).delete()
    }
}
