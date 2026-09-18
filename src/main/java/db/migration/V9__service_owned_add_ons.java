package db.migration;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.UUID;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Split legacy shared add-ons without altering reservation snapshots or old migrations. */
public class V9__service_owned_add_ons extends BaseJavaMigration {
    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        try (var statement = connection.createStatement()) {
            statement.execute("alter table booking_add_ons drop constraint uq_booking_add_on_name");
        }
        record Link(String tenant, String service, String addOn) {}
        var links = new ArrayList<Link>();
        try (var statement = connection.createStatement();
                var rows = statement.executeQuery("""
                    select tenant_id, service_id, add_on_id from booking_service_add_ons
                    order by add_on_id, service_id
                    """)) {
            while (rows.next()) {
                links.add(new Link(rows.getString(1), rows.getString(2), rows.getString(3)));
            }
        }
        var seen = new HashSet<String>();
        for (var link : links) {
            if (seen.add(link.addOn())) continue;
            String copyId = UUID.randomUUID().toString();
            try (var statement = connection.prepareStatement("""
                    insert into booking_add_ons
                      (id, tenant_id, name, description, duration_minutes, price_amount,
                       active, created_at, updated_at)
                    select ?, tenant_id, name, description, duration_minutes, price_amount,
                           active, created_at, updated_at
                    from booking_add_ons where id = ? and tenant_id = ?
                    """)) {
                statement.setString(1, copyId);
                statement.setString(2, link.addOn());
                statement.setString(3, link.tenant());
                statement.executeUpdate();
            }
            try (var statement = connection.prepareStatement("""
                    update booking_service_add_ons set add_on_id = ?
                    where tenant_id = ? and service_id = ? and add_on_id = ?
                    """)) {
                statement.setString(1, copyId);
                statement.setString(2, link.tenant());
                statement.setString(3, link.service());
                statement.setString(4, link.addOn());
                statement.executeUpdate();
            }
        }
        try (var statement = connection.createStatement()) {
            statement.execute("alter table booking_service_add_ons add constraint uq_add_on_owner unique (add_on_id)");
        }
    }
}
