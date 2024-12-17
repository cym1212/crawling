package codeRecipe.crawling.crawling;

import com.mysql.cj.NativeSession;
import com.mysql.cj.jdbc.JdbcConnection;
import com.mysql.cj.protocol.ServerSession;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.TimeZone;

@Component
@Endpoint(id = "connectj-timezone")
@RequiredArgsConstructor
public class TestConfig {

    private final DataSource dataSource;

    @ReadOperation
    public String mysqlTimeZone() {
        StringBuilder response = new StringBuilder();

        // 기본 JVM 시간대 확인
        TimeZone defaultTimeZone = TimeZone.getDefault();
        response.append("Default JVM Time Zone: ").append(defaultTimeZone.getID()).append("\n");

        // DataSource를 통해 MySQL 서버 시간대 확인
        try (Connection connection = dataSource.getConnection()) {
            response.append(getCachedServerTimezone(connection, "DataSource"));
        } catch (SQLException e) {
            response.append("Error fetching MySQL time zone from DataSource: ").append(e.getMessage()).append("\n");
        }

        return response.toString();
    }
    private String getCachedServerTimezone(Connection connection, String source) {
        StringBuilder response = new StringBuilder();
        try {
            JdbcConnection jdbcConnection = connection.unwrap(JdbcConnection.class);
            NativeSession nativeSession = (NativeSession) jdbcConnection.getSession();
            ServerSession serverSession = nativeSession.getServerSession();

            // 공개 메서드를 통해 시간대 가져오기
            TimeZone sessionTimeZone = serverSession.getSessionTimeZone();
            TimeZone defaultTimeZone = serverSession.getDefaultTimeZone();

            response.append(source).append(" MySQL Connector/J Default Time Zone (via method): ").append(defaultTimeZone.getID()).append("\n");
            response.append(source).append(" MySQL Connector/J Session Time Zone (via method): ").append(sessionTimeZone.getID()).append("\n");
        } catch (Exception e) {
            response.append("Error accessing cached MySQL time zone from ").append(source).append(": ").append(e.getMessage()).append("\n");
        }
        return response.toString();
    }
}