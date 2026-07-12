package com.example.weatherapp

    data class ForecastResponse(
        val list: List<ForecastItem>
    )


    data class ForecastItem(
    val dt: Long,
    val main: MainForecast,
    val weather: List<WeatherForecast>,
    val wind: Wind,          // reuses the same Wind class from WeatherResponse.kt
    val pop: Double = 0.0    // probability of precipitation, 0.0–1.0
    )

    data class MainForecast(
    val temp: Double,
    val humidity: Int
    )



    data class WeatherForecast(
        val description: String,
        val icon: String
    )

