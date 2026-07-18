package com.opsinbox.db

import com.opsinbox.config.AppConfig
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Database
import javax.sql.DataSource

object DatabaseFactory {
    /** Migrazioni di schema condivise da tutti gli ambienti. */
    private const val SCHEMA_LOCATION = "classpath:db/migration"

    /**
     * Location del seed di sviluppo (company + utente fissi). Caricata SOLO in development:
     * in produzione questo seed sarebbe una backdoor multi-tenant.
     */
    private const val DEV_SEED_LOCATION = "classpath:db/migration-dev"

    fun init(config: AppConfig): DataSource {
        val dataSource = HikariDataSource(HikariConfig().apply {
            jdbcUrl = config.dbUrl
            username = config.dbUser
            password = config.dbPassword
            maximumPoolSize = 10
        })

        val flyway = Flyway.configure().dataSource(dataSource).apply {
            if (config.isProduction) {
                // Produzione: solo lo schema, niente seed dev.
                locations(SCHEMA_LOCATION)
                // V2 (dev seed) può risultare già applicata su un DB che è nato in dev:
                // non è più presente nel classpath di produzione, quindi va ignorata
                // invece di far fallire il boot con "applied migration not resolved locally".
                ignoreMigrationPatterns("*:missing")
                // ATTENZIONE (vincolo di deploy): escludere la location del seed impedisce di
                // INSERIRE la company demo, ma non RIMUOVE una company seed già presente. Se si
                // promuove a produzione un database nato in sviluppo, la company
                // 00000000-0000-0000-0000-000000000001 resta nel DB e costituisce un tenant
                // fantasma. In produzione partire SEMPRE da un database nuovo e vuoto: mai
                // promuovere un DB di sviluppo.
            } else {
                // Development: schema + seed dev (config-zero, comportamento identico a prima).
                locations(SCHEMA_LOCATION, DEV_SEED_LOCATION)
            }
        }.load()

        flyway.migrate()
        Database.connect(dataSource)
        return dataSource
    }
}
