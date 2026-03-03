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

    /** Latest page — 9 bytes, progress of data recording (page, row, UNIX time, interval) */
    val LATEST_PAGE_CHAR_UUID: UUID = UUID.fromString("0C4C3002-7700-46F4-AA96-D5E974E32A54")

    /** Request page — 3 bytes, write page+row to read from flash */
    val REQUEST_PAGE_CHAR_UUID: UUID = UUID.fromString("0C4C3003-7700-46F4-AA96-D5E974E32A54")

    /** Response flag — 5 bytes, read until Completed before reading Response data */
    val RESPONSE_FLAG_CHAR_UUID: UUID = UUID.fromString("0C4C3004-7700-46F4-AA96-D5E974E32A54")

    /** Response data — 19 bytes, same format as Latest data for requested page/row */
    val RESPONSE_DATA_CHAR_UUID: UUID = UUID.fromString("0C4C3005-7700-46F4-AA96-D5E974E32A54")

    /** Setting Service — measurement interval and event thresholds */
    val SETTING_SERVICE_UUID: UUID = UUID.fromString("0C4C3010-7700-46F4-AA96-D5E974E32A54")

    /** Measurement interval — 2 bytes (UInt16 sec), range 1..3600 */
    val MEASUREMENT_INTERVAL_CHAR_UUID: UUID = UUID.fromString("0C4C3011-7700-46F4-AA96-D5E974E32A54")

    /** Control Service — time/trigger/error */
    val CONTROL_SERVICE_UUID: UUID = UUID.fromString("0C4C3030-7700-46F4-AA96-D5E974E32A54")

    /** Time information — 4 bytes (UInt32 UNIX time sec) */
    val TIME_INFORMATION_CHAR_UUID: UUID = UUID.fromString("0C4C3031-7700-46F4-AA96-D5E974E32A54")

    /** OMRON device name (short) when in connection beacon modes */
    const val DEVICE_NAME_SHORT = "Env"

    /** OMRON device name (full) */
    const val DEVICE_NAME_FULL = "EnvSensor-BL01"

    /** Name prefixes to match OMRON 2JCIE-BL01 in various beacon modes (Env, EnvSensor-BL01, IM-BL01, EP-BL01) */
    val OMRON_DEVICE_NAME_PREFIXES = listOf("Env", "EnvSensor", "IM", "EP")
}
