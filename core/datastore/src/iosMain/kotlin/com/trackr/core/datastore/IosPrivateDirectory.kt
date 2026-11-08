@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package com.trackr.core.datastore

import platform.Foundation.*

/** Exclude the containing directory: DataStore atomically replaces its files. */
fun prepareIosPrivateDirectory(applicationSupport: String): String {
    val directory = "$applicationSupport/private"
    check(NSFileManager.defaultManager.createDirectoryAtPath(directory, true, null, null))
    check(NSURL.fileURLWithPath(directory).setResourceValue(NSNumber(bool = true), NSURLIsExcludedFromBackupKey, null))
    return directory
}
