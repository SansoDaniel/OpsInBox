package com.opsinbox.db

import com.opsinbox.config.AppConfig
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Database
import javax.sql.DataSource

object DatabaseFactory {
    fun init(config: AppConfig): DataSource {
        val dataSource = HikariDataSource(HikariConfig().apply {
            jdbcUrl = config.dbUrl
            username = config.dbUser
            password = config.dbPassword
            maximumPoolSize = 10
        })
        Flyway.configure()
            .dataSource(dataSource)
            .load()
            .migrate()
        Database.connect(dataSource)
        return dataSource
    }
}
