package com.example.weatherapp

data class WeatherResponse(
    val coord: Coord,
    val main: Main,
    val weather: List<Weather>,
    val wind: Wind,
    val sys: Sys,
    val name: String,
    val timezone: Int
)
data class Coord(
    val lon: Double,
    val lat: Double
)
data class Main(
    val temp: Double,
    val feels_like: Double,
    val humidity: Int
)

data class Weather(
    val description: String,
    val icon: String
)

data class Wind(
    val speed: Double,
    val deg:Int

)

data class Sys(
    val country: String,
    val sunrise:Long,
    val sunset:Long
)