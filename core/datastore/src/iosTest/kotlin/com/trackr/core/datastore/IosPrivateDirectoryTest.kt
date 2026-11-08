@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package com.trackr.core.datastore

import kotlinx.cinterop.*
import platform.Foundation.*
import kotlin.test.*

class IosPrivateDirectoryTest {
    @Test fun privateDirectoryRetainsBackupExclusionAcrossAtomicReplacementsAndReopen() {
        val root = NSTemporaryDirectory() + NSUUID().UUIDString
        try {
            val directory = prepareIosPrivateDirectory(root)
            repeat(2) { index ->
                val bytes = "snapshot $index".encodeToByteArray()
                val data = bytes.usePinned { NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong()) }
                assertTrue(data.writeToFile("$directory/tracking_session.json", atomically = true))
                val properties = NSURL.fileURLWithPath(directory).resourceValuesForKeys(listOf(NSURLIsExcludedFromBackupKey), null)
                assertEquals(true, (properties?.get(NSURLIsExcludedFromBackupKey) as? NSNumber)?.boolValue)
            }
            assertEquals(directory, prepareIosPrivateDirectory(root))
            assertTrue(NSFileManager.defaultManager.fileExistsAtPath("$directory/tracking_session.json"))
            assertFalse(NSFileManager.defaultManager.fileExistsAtPath("$root/trackr_amap_keys.preferences_pb"))
        } finally {
            NSFileManager.defaultManager.removeItemAtPath(root, null)
        }
    }
}
