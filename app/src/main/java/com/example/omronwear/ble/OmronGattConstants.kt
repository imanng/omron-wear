package com.example.omronwear.ble

import java.util.UUID

/**
 * OMRON 2JCIE-BL01 GATT constants per Communication Interface Manual v1.6.
 * Base UUID: 0C4CXXXX-7700-46F4-AA96D5E974E32A54
 */
object OmronGattConstants {
    /** Sensor Service — sensor data acquisition */
    val SENSOR_SERVICE_UUID: UUID = UUID.fromString("0C4C3000-7700-46F4-AA96-D5E974E32A54")

    /** Latest data — 19 bytes, updated every measurement interval */
    val LATEST_DATA_CHAR_UUID: UUID = UUID.fromString("0C4C3001-7700-46F4-AA96-D5E974E32A54")

    /** OMRON device name (short) when in connection beacon modes */
    const val DEVICE_NAME_SHORT = "Env"

    /** OMRON device name (full) */
    const val DEVICE_NAME_FULL = "EnvSensor-BL01"

    /** Name prefixes to match OMRON 2JCIE-BL01 in various beacon modes (Env, EnvSensor-BL01, IM-BL01, EP-BL01) */
    val OMRON_DEVICE_NAME_PREFIXES = listOf("Env", "EnvSensor", "IM", "EP")
}
