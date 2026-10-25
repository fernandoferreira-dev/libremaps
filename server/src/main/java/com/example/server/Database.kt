package com.example.server

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import javax.sql.DataSource

lateinit var dataSource: DataSource

fun configureDatabases() {
    val config = HikariConfig().apply {
        driverClassName = "org.mariadb.jdbc.Driver"
        jdbcUrl = System.getenv("JDBC_URL") ?: "jdbc:mariadb://85.247.43.227:3306/libremaps"
        username = System.getenv("DB_USER") ?: "diogo"
        password = System.getenv("DB_PASS") ?: "1234"
        maximumPoolSize = 10
    }
    dataSource = HikariDataSource(config)
}
