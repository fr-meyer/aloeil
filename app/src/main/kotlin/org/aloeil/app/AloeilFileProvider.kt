package org.aloeil.app

import androidx.core.content.FileProvider

/** Only the dedicated app-private CSV cache directory is shareable. */
class AloeilFileProvider : FileProvider()
