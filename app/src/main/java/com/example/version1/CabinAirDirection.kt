package com.example.version1

/** Confirmed SDK fan outlet selection, not a measured airflow field. Unknown stays null. */
enum class CabinAirDirection(val face: Boolean, val floor: Boolean, val defrost: Boolean, val label: String) {
    FACE(true,false,false,"얼굴"), FLOOR(false,true,false,"발밑"),
    FACE_AND_FLOOR(true,true,false,"얼굴+발밑"), DEFROST(false,false,true,"앞유리"),
    FACE_AND_DEFROST(true,false,true,"얼굴+앞유리"),
    DEFROST_AND_FLOOR(false,true,true,"발밑+앞유리"),
    FACE_AND_FLOOR_AND_DEFROST(true,true,true,"얼굴+발밑+앞유리");

    companion object {
        fun fromSdkName(name: String?): CabinAirDirection? = entries.firstOrNull { it.name == name }
    }
}
