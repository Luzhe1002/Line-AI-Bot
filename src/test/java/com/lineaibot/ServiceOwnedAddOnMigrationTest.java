package com.lineaibot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

class ServiceOwnedAddOnMigrationTest {
    @Test
    void upgradesSharedAddOnsWithoutChangingReservationSnapshots() throws Exception {
        String url = "jdbc:h2:mem:upgrade_" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(url, "sa", "").target("8").load().migrate();
        try (var connection = DriverManager.getConnection(url, "sa", "");
                var sql = connection.createStatement()) {
            sql.execute("""
                    insert into tenants values
                    ('t','shop','Shop','Asia/Taipei',60,'hash',true,current_timestamp,current_timestamp)
                    """);
            for (String id : new String[] {"s1", "s2"}) {
                sql.execute("""
                        insert into booking_services
                          (id,tenant_id,name,active,created_at,updated_at,duration_minutes,price_amount)
                        values ('%s','t','%s',true,current_timestamp,current_timestamp,60,300)
                        """.formatted(id, id));
            }
            sql.execute("""
                    insert into booking_add_ons values
                    ('a','t','護髮','護理',60,500,true,current_timestamp,current_timestamp)
                    """);
            sql.execute("""
                    insert into booking_service_add_ons values
                    ('t','s1','a',current_timestamp),('t','s2','a',current_timestamp)
                    """);
            sql.execute("""
                    insert into reservations
                      (id,tenant_id,service_id,line_user_id,starts_at,ends_at,status,idempotency_key,
                       created_at,service_name,total_duration_minutes,total_price_amount)
                    values ('r','t','s2','u',current_timestamp,current_timestamp,'CONFIRMED','key',
                            current_timestamp,'s2',120,800)
                    """);
            sql.execute("""
                    insert into reservation_add_ons values
                    ('ra','t','r','a','護髮',60,500,current_timestamp)
                    """);
            var flyway = Flyway.configure().dataSource(url, "sa", "").load();
            flyway.migrate();
            flyway.validate();
            try (var rows = sql.executeQuery("select booking_enabled from tenants where id = 't'")) {
                rows.next();
                assertThat(rows.getBoolean(1)).isTrue();
            }
            try (var rows = sql.executeQuery("select count(distinct add_on_id) from booking_service_add_ons")) {
                rows.next();
                assertThat(rows.getInt(1)).isEqualTo(2);
            }
            sql.execute("""
                    update booking_add_ons set price_amount = 900
                    where id = (select add_on_id from booking_service_add_ons where service_id = 's2')
                    """);
            try (var rows = sql.executeQuery("select price_amount from booking_add_ons where id = 'a'")) {
                rows.next();
                assertThat(rows.getInt(1)).isEqualTo(500);
            }
            try (var rows = sql.executeQuery("select add_on_id, price_amount from reservation_add_ons where id = 'ra'")) {
                rows.next();
                assertThat(rows.getString(1)).isEqualTo("a");
                assertThat(rows.getInt(2)).isEqualTo(500);
            }
            assertThatThrownBy(() -> sql.execute("update booking_service_add_ons set add_on_id = 'a' where service_id = 's2'"))
                    .isInstanceOf(SQLException.class);
        }
    }
}
