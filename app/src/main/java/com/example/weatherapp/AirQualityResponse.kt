package com.example.weatherapp

data class AirQualityResponse(
    val list: List<AirQualityItem>
)

data class AirQualityItem(
    val main: AQIMain
)
data class AQIMain(
    val aqi: Int
)